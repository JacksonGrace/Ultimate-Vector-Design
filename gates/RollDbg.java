import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Debug the bent-geo probe anomaly: 15° endTang + 185° roll-integral
 *  mismatch. Prints authored/measured/baked roll integrals and the end
 *  frames of original vs converted. */
public class RollDbg {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        List<List<BezierBaker.Node>> bl = new ArrayList<>();
        bl.add(nodes(new double[][]{ {0,0},{2,5},{5,-5},{8,0} }));
        bl.add(nodes(new double[][]{ {0,0},{3,16},{8,8} }));
        bl.add(nodes(new double[][]{ {0,0},{4,25},{6,-15},{8,0} }));
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new GeoSection(bl, 24));
        c.pieces.add(p);
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        List<FVDIntegrator.Sample> s = r.samples();
        int n = s.size();
        System.out.println("orig samples: " + n + "  tEnd=" + s.get(n-1).t()
            + "  sEnd=" + s.get(n-1).s());

        // authored roll lane integral (baked)
        List<FVDIntegrator.Keyframe> kfs0 = BezierBaker.bake(bl, 0.05);
        double a0 = 0;
        for (int i = 1; i < kfs0.size(); i++) {
            a0 += (kfs0.get(i).rollRateDeg() + kfs0.get(i-1).rollRateDeg())/2
                * (kfs0.get(i).t() - kfs0.get(i-1).t());
        }
        System.out.printf("authored roll lane integral = %.3f deg%n", a0);

        // measured roll integral off samples (the convert extraction)
        double m = 0, mPrev = 0;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = s.get(i);
            FVDIntegrator.Sample a = s.get(Math.max(0, i - 1));
            FVDIntegrator.Sample b = s.get(Math.min(n - 1, i + 1));
            double ds = b.s() - a.s();
            double rr = ds < 1e-9 ? mPrev
                : Math.toDegrees(-b.U().sub(a.U()).dot(sm.R()) / ds) * sm.v();
            if (i > 0) m += (rr + mPrev)/2 * (s.get(i).t() - s.get(i-1).t());
            mPrev = rr;
            if (i % 200 == 0 || i == n-1) {
                System.out.printf("  i=%4d t=%5.2f measRoll=%8.3f  authored@t=%8.3f%n",
                    i, sm.t(), rr, kfAt(kfs0, sm.t()));
            }
        }
        System.out.printf("measured roll integral = %.3f deg%n", m);

        // converted geo→geo, end frames
        LaneSection conv = Coaster.convertToLanes(s, 24, true, dt, 0.1, 0.0001);
        Coaster c2 = new Coaster();
        c2.v0 = 24;
        TrackPiece p2 = new TrackPiece(0, 21, 0, 0);
        p2.sections.add(conv);
        c2.pieces.add(p2);
        TrackCompiler.Result r2 = TrackCompiler.compile(c2, dt);
        List<FVDIntegrator.Sample> g = r2.samples();
        FVDIntegrator.Sample e0 = s.get(n-1), e1 = g.get(g.size()-1);
        System.out.printf("orig  end T=(%.4f,%.4f,%.4f) U=(%.4f,%.4f,%.4f) t=%.3f%n",
            e0.T().x(), e0.T().y(), e0.T().z(), e0.U().x(), e0.U().y(), e0.U().z(), e0.t());
        System.out.printf("conv  end T=(%.4f,%.4f,%.4f) U=(%.4f,%.4f,%.4f) t=%.3f%n",
            e1.T().x(), e1.T().y(), e1.T().z(), e1.U().x(), e1.U().y(), e1.U().z(), e1.t());
        // tangent angle at matched arc a bit BEFORE the end
        for (double back : new double[]{ 0, 0.5, 2, 5 }) {
            double arc = Math.min(e0.s(), e1.s()) - back;
            FVDIntegrator.Sample s0 = at(s, arc), s1 = at(g, arc);
            System.out.printf("  arc=%7.2f  tangDiff=%.4f deg  (orig T.y=%.4f)%n",
                arc, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                    s0.T().dot(s1.T()))))), s0.T().y());
        }
        // converted roll lane integral
        List<FVDIntegrator.Keyframe> kfs1 = BezierBaker.bake(conv.lanes, 0.05);
        double a1 = 0;
        for (int i = 1; i < kfs1.size(); i++) {
            a1 += (kfs1.get(i).rollRateDeg() + kfs1.get(i-1).rollRateDeg())/2
                * (kfs1.get(i).t() - kfs1.get(i-1).t());
        }
        System.out.printf("converted roll lane integral = %.3f deg (authored %.3f)%n", a1, a0);
    }

    static double kfAt(List<FVDIntegrator.Keyframe> kfs, double t) {
        FVDIntegrator.Keyframe prev = kfs.get(0);
        for (FVDIntegrator.Keyframe k : kfs) {
            if (k.t() >= t) {
                double u = k.t() - prev.t() < 1e-12 ? 0
                    : (t - prev.t()) / (k.t() - prev.t());
                return prev.rollRateDeg()
                    + u * (k.rollRateDeg() - prev.rollRateDeg());
            }
            prev = k;
        }
        return prev.rollRateDeg();
    }

    static FVDIntegrator.Sample at(List<FVDIntegrator.Sample> s, double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        return s.get(lo);
    }
}
