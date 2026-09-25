import uvd.*;

import java.util.*;

/** D236 probe — the user's "Odd influence behavior 8.25.26.fvd":
 *  residual laterals persisted after the influence deltas returned
 *  to zero (the two-pass replayed ref rates in the deltas' exit
 *  frame). Under the one-pass hybrid the felt records must equal
 *  the authored targets wherever the deltas are zero. */
public class OddInflProbe {

    static final double DT = 0.01;

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

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
            : "C:\\Users\\jacks\\OneDrive\\Documents\\AI Fun\\"
              + "Odd influence behavior 8.25.26.fvd";
        Coaster c = DesignIO.load(new java.io.BufferedReader(
            new java.io.FileReader(path))).coasters().get(0);
        TrackCompiler.Result r = rebuild(c);
        int flat = 0;
        for (TrackPiece p : c.pieces) {
            for (Section sec : p.sections) {
                TrackCompiler.Span sp = r.spans().get(flat++);
                if (!(sec instanceof LaneSection ls)
                        || !ls.influenced()) continue;
                boolean dist = ls.domain() == Section.Domain.DISTANCE;
                List<double[]> d0 = BezierBaker.polyline(ls.deltaLanes.get(0));
                List<double[]> d1 = BezierBaker.polyline(ls.deltaLanes.get(1));
                double worstGv = 0, worstGl = 0, x0 = -1, x1 = -1;
                int n = 0;
                for (int i = sp.i0(); i <= sp.i1(); i++) {
                    FVDIntegrator.Sample s = r.samples().get(i);
                    double x = dist ? s.s() - sp.s0() : s.t() - sp.t0();
                    // only where the deltas are (numerically) zero
                    if (Math.abs(polyAt(d0, x)) > 1e-9
                            || Math.abs(polyAt(d1, x)) > 1e-9) continue;
                    n++;
                    double aGv = BezierBaker.exactAt(ls.lanes.get(0), x);
                    double aGl = BezierBaker.exactAt(ls.lanes.get(1), x);
                    double eGv = Math.abs(s.Gv() - aGv);
                    double eGl = Math.abs(s.Gl() - aGl);
                    if (Math.max(eGv, eGl) > Math.max(worstGv, worstGl)) {
                        x0 = x;
                    }
                    worstGv = Math.max(worstGv, eGv);
                    worstGl = Math.max(worstGl, eGl);
                }
                System.out.printf(Locale.US,
                    "infl section span %d: %d zero-delta samples, "
                    + "worst |felt-authored| Gv %.3e  Gl %.3e  (at x %.2f)%n",
                    flat - 1, n, worstGv, worstGl, x0);
            }
        }
        System.out.println("done");
    }

    static double polyAt(List<double[]> poly, double x) {
        if (poly.isEmpty()) return 0;
        if (x <= poly.get(0)[0]) return poly.get(0)[1];
        for (int i = 1; i < poly.size(); i++) {
            if (x <= poly.get(i)[0]) {
                double[] a = poly.get(i - 1), b = poly.get(i);
                double f = b[0] - a[0] < 1e-12 ? 0
                    : (x - a[0]) / (b[0] - a[0]);
                return a[1] + (b[1] - a[1]) * f;
            }
        }
        return poly.get(poly.size() - 1)[1];
    }
}
