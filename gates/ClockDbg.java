import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Compare a distance-section compile's real clock against the
 *  convert re-time (tG) — where do the missing ~23 ms of ride go? */
public class ClockDbg {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> defaultLanes() {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));
        return lanes;
    }

    public static void main(String[] args) {
        Coaster base = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        TrackCompiler.Result r0 = TrackCompiler.compile(base, dt);
        LaneSection leg1 = Coaster.convertToLanes(r0.samples(), 26, false,
            dt, 0.7, 0, Section.Domain.DISTANCE);
        Coaster c1 = new Coaster();
        c1.v0 = 26;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(leg1);
        c1.pieces.add(p);
        TrackCompiler.Result r1 = TrackCompiler.compile(c1, dt);
        List<FVDIntegrator.Sample> s = r1.samples();
        int n = s.size();
        System.out.printf(java.util.Locale.US,
            "orig:  n=%d tEnd=%.6f sEnd=%.4f%n", r0.samples().size(),
            r0.samples().get(r0.samples().size()-1).t(),
            r0.samples().get(r0.samples().size()-1).s());
        System.out.printf(java.util.Locale.US,
            "leg1:  n=%d tEnd=%.6f sEnd=%.4f%n",
            n, s.get(n-1).t(), s.get(n-1).s());
        // last few sample deltas
        for (int i = n - 4; i < n; i++) {
            System.out.printf(java.util.Locale.US,
            "  i=%d t=%.6f dt=%.6f s=%.5f ds=%.6f v=%.4f%n", i,
                s.get(i).t(), s.get(i).t()-s.get(i-1).t(),
                s.get(i).s(), s.get(i).s()-s.get(i-1).s(), s.get(i).v());
        }
        // the convert re-time over these samples (identity profile)
        double y0 = s.get(0).pos().y();
        double tg = 0, prevVG = Math.max(0.5, 26);
        for (int i = 0; i < n; i++) {
            double vG = Math.max(0.5, Math.sqrt(Math.max(0,
                26*26 + 2*FVDIntegrator.G0*(y0 - s.get(i).pos().y()))));
            if (i > 0) {
                double ds = s.get(i).s() - s.get(i-1).s();
                tg += ds / Math.max(0.5, (vG + prevVG) / 2);
            }
            prevVG = vG;
        }
        System.out.printf(java.util.Locale.US,
            "convert tG_end=%.6f  vs real tEnd=%.6f  (diff %.4f s ~ %.3f m)%n",
            tg, s.get(n-1).t(), tg - s.get(n-1).t(),
            (tg - s.get(n-1).t()) * s.get(n-1).v());
    }
}
