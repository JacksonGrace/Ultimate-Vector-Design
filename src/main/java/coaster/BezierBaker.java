package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * BezierBaker.java  (per-lane independent nodes)
 *
 * Each of the three property lanes (0 = vertical G, 1 = lateral G,
 * 2 = roll rate deg/s) has its OWN independent list of nodes over ride
 * time. A node holds one value plus cubic Bezier handles:
 *   out handle (outDt >= 0, outDv) shapes the curve toward the next node,
 *   in handle  (inDt <= 0,  inDv) shapes the curve from the previous node.
 * `smooth` keeps the two handles collinear (C1 continuity) in the editor.
 *
 * The ride's duration is the max end time across the three lanes; a lane
 * whose nodes end earlier simply holds its final value to the end.
 *
 * NO JavaFX dependencies: unit-testable headlessly.
 */
public class BezierBaker {

    public static final int LANES = 3;

    public static class Node {
        public double t;
        public double val;
        public double outDt, outDv;
        public double inDt, inDv;
        public boolean smooth = true;

        public Node(double t, double val) {
            this.t = t;
            this.val = val;
        }

        public Node copy() {
            Node n = new Node(t, val);
            n.outDt = outDt; n.outDv = outDv;
            n.inDt = inDt;   n.inDv = inDv;
            n.smooth = smooth;
            return n;
        }
    }

    /** Flat one-third default handles for every node in a lane. */
    public static void applyDefaultHandles(List<Node> lane) {
        for (int i = 0; i < lane.size(); i++) {
            Node n = lane.get(i);
            if (i < lane.size() - 1) {
                n.outDt = (lane.get(i + 1).t - n.t) / 3.0;
                n.outDv = 0;
            }
            if (i > 0) {
                n.inDt = -(n.t - lane.get(i - 1).t) / 3.0;
                n.inDv = 0;
            }
        }
    }

    /** Flat default handles for one node given its (possibly null) neighbors. */
    public static void applyDefaultHandles(Node n, Node prev, Node next) {
        if (next != null) { n.outDt = (next.t - n.t) / 3.0; n.outDv = 0; }
        if (prev != null) { n.inDt = -(n.t - prev.t) / 3.0; n.inDv = 0; }
    }

    public static double effOutDt(Node a, Node b) {
        return clamp(a.outDt, 0, (b.t - a.t) * 0.95);
    }

    public static double effInDt(Node a, Node b) {
        return clamp(b.inDt, -(b.t - a.t) * 0.95, 0);
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
