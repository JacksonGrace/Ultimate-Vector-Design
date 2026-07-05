package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * BezierBaker.java  (per-lane independent nodes)
 *
 * Each of the three property lanes (0 = vertical G, 1 = lateral G,
 * 2 = roll rate deg/s) has its OWN independent list of nodes over ride
 * time. A node holds one value plus cubic Bezier handles, stored RELATIVE
 * to the neighboring segment:
 *   out handle: outFrac (fraction 0..MAX_FRAC of the time gap to the NEXT
 *               node) + outDv (absolute value offset at the handle end);
 *   in handle:  inFrac (fraction of the gap to the PREVIOUS node) + inDv.
 * Because handle lengths are fractions, moving a node in time rescales the
 * attached handles automatically — the curve "squishes" horizontally but
 * keeps its shape exactly (bring two nodes twice as close and the segment
 * is the same graph at half width).
 * `smooth` keeps the two handles collinear (C1 continuity) in the editor.
 *
 * The ride's duration is the max end time across the three lanes; a lane
 * whose nodes end earlier simply holds its final value to the end.
 *
 * NO JavaFX dependencies: unit-testable headlessly.
 */
public class BezierBaker {

    public static final int LANES = 3;
    public static final double DEFAULT_FRAC = 1.0 / 3.0;
    // handles may reach 150% of the segment (past the neighbor in time);
    // the resulting folded time-axis is sanitized by bakeLane's monotonic
    // guard, so extreme shapes stay finite (fuzz-tested)
    public static final double MAX_FRAC = 1.5;

    public static class Node {
        public double t;
        public double val;
        public double outFrac = DEFAULT_FRAC, outDv;
        public double inFrac = DEFAULT_FRAC, inDv;
        public boolean smooth = true;

        public Node(double t, double val) {
            this.t = t;
            this.val = val;
        }

        public Node copy() {
            Node n = new Node(t, val);
            n.outFrac = outFrac; n.outDv = outDv;
            n.inFrac = inFrac;   n.inDv = inDv;
            n.smooth = smooth;
            return n;
        }
    }

    /** Flat one-third default handles for every node in a lane. */
    public static void applyDefaultHandles(List<Node> lane) {
        for (Node n : lane) {
            n.outFrac = DEFAULT_FRAC; n.outDv = 0;
            n.inFrac = DEFAULT_FRAC;  n.inDv = 0;
        }
    }

    /** Flat default handles for one node given its (possibly null) neighbors. */
    public static void applyDefaultHandles(Node n, Node prev, Node next) {
        if (next != null) { n.outFrac = DEFAULT_FRAC; n.outDv = 0; }
        if (prev != null) { n.inFrac = DEFAULT_FRAC;  n.inDv = 0; }
    }

    /** Out-handle time offset in SECONDS for segment a->b, from the fraction. */
    public static double effOutDt(Node a, Node b) {
        return clamp(a.outFrac, 0, MAX_FRAC) * (b.t - a.t);
    }

    /** In-handle time offset in seconds (<= 0) for segment a->b. */
    public static double effInDt(Node a, Node b) {
        return -clamp(b.inFrac, 0, MAX_FRAC) * (b.t - a.t);
    }

    /** End time of the ride: latest node across all lanes. */
    public static double rideEnd(List<List<Node>> lanes) {
        double end = 0;
        for (List<Node> lane : lanes) end = Math.max(end, lane.get(lane.size() - 1).t);
        return end;
    }

    /**
     * Bake all three lanes onto a uniform time grid of keyframes for the
     * integrator. gridStep ~0.05s.
     */
    public static List<FVDIntegrator.Keyframe> bake(List<List<Node>> lanes, double gridStep) {
        double end = rideEnd(lanes);

        List<List<double[]>> polys = new ArrayList<>();
        for (int lane = 0; lane < LANES; lane++) polys.add(bakeLane(lanes.get(lane)));

        List<FVDIntegrator.Keyframe> out = new ArrayList<>();
        for (double t = 0; t < end; t += gridStep) {
            out.add(new FVDIntegrator.Keyframe(
                t, evalPolyline(polys.get(0), t), evalPolyline(polys.get(1), t), evalPolyline(polys.get(2), t)));
        }
        out.add(new FVDIntegrator.Keyframe(
            end, evalPolyline(polys.get(0), end), evalPolyline(polys.get(1), end), evalPolyline(polys.get(2), end)));
        return out;
    }

    /** Value of a single lane's curve at time t (used by the editor). */
    public static double evalAt(List<Node> lane, double t) {
        return evalPolyline(bakeLane(lane), t);
    }

    /** The lane's baked (t, value) polyline — for editor hit tests that
     *  need the whole curve (e.g. nearest-point on steep sections). */
    public static List<double[]> polyline(List<Node> lane) {
        return bakeLane(lane);
    }

    /** Sample one lane's chain of cubic Beziers into a (t, value) polyline. */
    private static List<double[]> bakeLane(List<Node> lane) {
        List<double[]> poly = new ArrayList<>();
        if (lane.size() == 1) {
            Node only = lane.get(0);
            poly.add(new double[]{ only.t, only.val });
            return poly;
        }
        for (int i = 0; i < lane.size() - 1; i++) {
            Node a = lane.get(i), b = lane.get(i + 1);
            double x0 = a.t,                    y0 = a.val;
            double x1 = a.t + effOutDt(a, b),   y1 = a.val + a.outDv;
            double x2 = b.t + effInDt(a, b),    y2 = b.val + b.inDv;
            double x3 = b.t,                    y3 = b.val;

            int n = (int) clamp(Math.round((b.t - a.t) / 0.1), 8, 48);
            for (int k = 0; k < n; k++) {
                double u = (double) k / n;
                poly.add(new double[]{ cubic(x0, x1, x2, x3, u), cubic(y0, y1, y2, y3, u) });
            }
        }
        Node last = lane.get(lane.size() - 1);
        poly.add(new double[]{ last.t, last.val });

        // enforce strictly increasing t
        for (int i = 1; i < poly.size(); i++) {
            if (poly.get(i)[0] <= poly.get(i - 1)[0]) {
                poly.get(i)[0] = poly.get(i - 1)[0] + 1e-6;
            }
        }
        return poly;
    }

    /**
     * De Casteljau split of segment a->b at time t: returns the new middle
     * node and REWRITES a's out / b's in handles so the two halves trace the
     * ORIGINAL curve exactly (zero disturbance — this is the standard cubic
     * Bezier subdivision identity, no approximation involved). The new
     * node's handles land tangent to the curve and collinear, so it is
     * naturally smooth.
     */
    public static Node splitSegment(Node a, Node b, double t) {
        double x0 = a.t,                  y0 = a.val;
        double x1 = a.t + effOutDt(a, b), y1 = a.val + a.outDv;
        double x2 = b.t + effInDt(a, b),  y2 = b.val + b.inDv;
        double x3 = b.t,                  y3 = b.val;

        // find u with x(u) = t; x(0) < t < x(1), bisection is robust even
        // for pathological folded handles
        double lo = 0, hi = 1;
        for (int k = 0; k < 60; k++) {
            double mid = (lo + hi) / 2;
            if (cubic(x0, x1, x2, x3, mid) < t) lo = mid; else hi = mid;
        }
        double u = (lo + hi) / 2;

        double q1x = lerp(x0, x1, u), q1y = lerp(y0, y1, u);
        double q2x = lerp(x1, x2, u), q2y = lerp(y1, y2, u);
        double q3x = lerp(x2, x3, u), q3y = lerp(y2, y3, u);
        double r1x = lerp(q1x, q2x, u), r1y = lerp(q1y, q2y, u);
        double r2x = lerp(q2x, q3x, u), r2y = lerp(q2y, q3y, u);
        double mx  = lerp(r1x, r2x, u), my  = lerp(r1y, r2y, u);

        Node n = new Node(mx, my);
        double leftSeg = mx - a.t, rightSeg = b.t - mx;
        a.outFrac = clamp((q1x - a.t) / leftSeg, 0, MAX_FRAC);
        a.outDv   = q1y - a.val;
        n.inFrac  = clamp((mx - r1x) / leftSeg, 0, MAX_FRAC);
        n.inDv    = r1y - my;
        n.outFrac = clamp((r2x - mx) / rightSeg, 0, MAX_FRAC);
        n.outDv   = r2y - my;
        b.inFrac  = clamp((b.t - q3x) / rightSeg, 0, MAX_FRAC);
        b.inDv    = q3y - b.val;
        n.smooth  = true;
        return n;
    }

    private static double lerp(double a, double b, double u) {
        return a + (b - a) * u;
    }

    private static double cubic(double p0, double p1, double p2, double p3, double u) {
        double w = 1 - u;
        return w * w * w * p0 + 3 * w * w * u * p1 + 3 * w * u * u * p2 + u * u * u * p3;
    }

    private static double evalPolyline(List<double[]> poly, double t) {
        if (t <= poly.get(0)[0]) return poly.get(0)[1];
        int hi = poly.size() - 1;
        if (t >= poly.get(hi)[0]) return poly.get(hi)[1];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (poly.get(mid)[0] <= t) lo = mid; else hi = mid;
        }
        double[] a = poly.get(lo), b = poly.get(hi);
        double u = (t - a[0]) / (b[0] - a[0]);
        return a[1] + (b[1] - a[1]) * u;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
