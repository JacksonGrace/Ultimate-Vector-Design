import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Why does force→geo+globalRoll convert diverge 35 m? Track bank +
 *  heading of conv vs orig along arc. */
public class GlobDbg {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static double bank(FVDIntegrator.Sample sm) {
        Vec3 T = sm.T();
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < 1e-6) return Double.NaN;
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.toDegrees(Math.atan2(ul.cross(sm.U()).dot(T), sm.U().dot(ul)));
    }

    static FVDIntegrator.Sample at(List<FVDIntegrator.Sample> s, double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        return s.get(lo);
    }

    public static void main(String[] args) {
        List<List<BezierBaker.Node>> g5 = new ArrayList<>();
        g5.add(nodes(new double[][]{ {0,1},{2,1.8},{5,0.8},{8,1} }));
        g5.add(nodes(new double[][]{ {0,0},{3,0.5},{8,0.2} }));
        g5.add(nodes(new double[][]{ {0,0},{4,20},{6,-10},{8,0} }));
        Coaster ec = Coaster.singleForce(g5, 24, 0, 21, 0);
        TrackCompiler.Result before = TrackCompiler.compile(ec, dt);
        ec.convertWholeSection(0, 0, true, dt, 0.05, 0,
            Section.Domain.TIME, true, false, false);
        TrackCompiler.Result after = TrackCompiler.compile(ec, dt);
        List<FVDIntegrator.Sample> a = before.samples(), b = after.samples();
        double sEnd = Math.min(a.get(a.size()-1).s(), b.get(b.size()-1).s());
        for (double f = 0; f <= 1.0001; f += 0.125) {
            FVDIntegrator.Sample sa = at(a, f * sEnd), sb = at(b, f * sEnd);
            System.out.printf(java.util.Locale.US,
                "arc=%6.1f  bankO=%8.2f bankC=%8.2f  posDiff=%7.3f  Ty O/C=%.3f/%.3f%n",
                f * sEnd, bank(sa), bank(sb),
                sa.pos().sub(sb.pos()).length(), sa.T().y(), sb.T().y());
        }
    }
}
