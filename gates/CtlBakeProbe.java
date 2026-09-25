import uvd.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

/** D236 probe — the user's "influence bake and conversion not
 *  working well with speed section": load his file, bake/convert
 *  section 0 (influenced force + a SPEED control 29 m/s at
 *  x0=3.63..5.32), measure the result against the pre-convert
 *  compiled curves; then remove the control and re-check the
 *  oscillation story at 0.11 uniform. */
public class CtlBakeProbe {

    static final double DT = 0.01;

    static Coaster load(String path) throws Exception {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** worst nearest-point distance from b's samples to a's polyline
     *  over span-0 only. */
    static double pathDev(TrackCompiler.Result a, TrackCompiler.Result b) {
        double worst = 0;
        int lo = 0;
        int n = Math.min(a.spans().get(0).i1(), b.spans().get(0).i1());
        for (int i = 0; i < n; i += 3) {
            Vec3 q = b.samples().get(i).pos();
            double best = Double.MAX_VALUE;
            int bestK = lo;
            for (int k = Math.max(0, lo - 80); k + 1 < a.samples().size()
                    && k < lo + 500; k++) {
                Vec3 s0 = a.samples().get(k).pos(), s1 = a.samples().get(k + 1).pos();
                Vec3 d = s1.sub(s0);
                double l2 = d.dot(d);
                double t = l2 < 1e-18 ? 0
                    : Math.max(0, Math.min(1, q.sub(s0).dot(d) / l2));
                double dist = q.sub(s0.add(d.scale(t))).length();
                if (dist < best) { best = dist; bestK = k; }
            }
            lo = bestK;
            worst = Math.max(worst, best);
        }
        return worst;
    }

    static void oscStats(String tag, LaneSection out,
                         double[] refT, double[] refG) {
        List<BezierBaker.Node> lane0 = out.lanes.get(0);
        int flips = 0;
        double worst = 0, prev = 0;
        for (int i = 0; i < lane0.size(); i++) {
            BezierBaker.Node n = lane0.get(i);
            double dev = n.val - interp(refT, refG, n.t);
            if (i > 0 && dev * prev < 0 && Math.abs(dev) > 0.15
                    && Math.abs(prev) > 0.15) flips++;
            worst = Math.max(worst, Math.abs(dev));
            prev = dev;
        }
        System.out.printf(Locale.US,
            "%s: nodes=%d flips=%d worstNodeDev=%.3f g%n",
            tag, lane0.size(), flips, worst);
    }

    static double interp(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        int hi = xs.length - 1;
        if (x >= xs[hi]) return ys[hi];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] <= x) lo = mid; else hi = mid;
        }
        double u = (x - xs[lo]) / Math.max(1e-12, xs[hi] - xs[lo]);
        return ys[lo] + u * (ys[hi] - ys[lo]);
    }

    static double[][] refCurves(TrackCompiler.Result r) {
        TrackCompiler.Span sp = r.spans().get(0);
        double[][] out = new double[2][sp.i1() - sp.i0() + 1];
        for (int i = 0; i < out[0].length; i++) {
            FVDIntegrator.Sample sm = r.samples().get(sp.i0() + i);
            out[0][i] = sm.t() - sp.t0();
            out[1][i] = sm.Gv();
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String path = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
            + "influence bake and conversion not working well with "
            + "speed section 8.25.26.fvd";

        // ---- A: bake WITH the control (his exact repro) ----
        Coaster c = load(path);
        TrackCompiler.Result r0 = rebuild(c);
        double[][] rc = refCurves(r0);
        LaneSection s0 = (LaneSection) c.pieces.get(0).sections.get(0);
        System.out.println("s0 infl=" + s0.influenced()
            + " ctls=" + s0.speedCtls.size()
            + " vA=" + s0.vAuthored + " vD=" + s0.vDesign);
        // speed profile around the control before the bake
        TrackCompiler.Span sp0 = r0.spans().get(0);
        for (double tq : new double[]{ 3.0, 4.0, 5.0, 6.0 }) {
            for (int i = sp0.i0(); i <= sp0.i1(); i++) {
                if (r0.samples().get(i).t() - sp0.t0() >= tq) {
                    System.out.printf(Locale.US,
                        "  pre-bake v(t=%.1f)=%.2f%n", tq,
                        r0.samples().get(i).v());
                    break;
                }
            }
        }
        boolean ok = c.convertWholeSection(0, 0, false, DT, 0.15, 0,
            Section.Domain.TIME, false, false, false, false, false);
        TrackCompiler.Result r1 = rebuild(c);
        LaneSection out = (LaneSection) c.pieces.get(0).sections.get(0);
        System.out.println("A bake+ctl ok=" + ok
            + " pathDev=" + String.format(Locale.US, "%.3f",
                pathDev(r0, r1)) + " m");
        oscStats("A nodes", out, rc[0], rc[1]);

        // ---- B: control removed, bake at 0.11 uniform ----
        Coaster cb = load(path);
        LaneSection sb = (LaneSection) cb.pieces.get(0).sections.get(0);
        sb.speedCtls.clear();
        TrackCompiler.Result b0 = rebuild(cb);
        double[][] rb = refCurves(b0);
        boolean okB = cb.convertWholeSection(0, 0, false, DT, 0.11, 0,
            Section.Domain.TIME, false, false, false, false, false);
        TrackCompiler.Result b1 = rebuild(cb);
        System.out.println("B bake-no-ctl ok=" + okB
            + " pathDev=" + String.format(Locale.US, "%.3f",
                pathDev(b0, b1)) + " m");
        oscStats("B nodes", (LaneSection) cb.pieces.get(0).sections.get(0),
            rb[0], rb[1]);

        // ---- C: adaptive flavor of B ----
        Coaster cc = load(path);
        ((LaneSection) cc.pieces.get(0).sections.get(0)).speedCtls.clear();
        TrackCompiler.Result c0 = rebuild(cc);
        double[][] rcv = refCurves(c0);
        boolean okC = cc.convertWholeSection(0, 0, false, DT, 0.11, 0.01,
            Section.Domain.TIME, false, false, false, false, false);
        TrackCompiler.Result c1 = rebuild(cc);
        System.out.println("C bake-adaptive ok=" + okC
            + " pathDev=" + String.format(Locale.US, "%.3f",
                pathDev(c0, c1)) + " m");
        oscStats("C nodes", (LaneSection) cc.pieces.get(0).sections.get(0),
            rcv[0], rcv[1]);
    }
}
