import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Does a POSITIVE roll-rate lane produce a POSITIVE bankOfTU-style
 *  reading? Local vs global, and what the absolute-roll B target
 *  would hand a neighbor. */
public class SignProbe {

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

    public static void main(String[] args) {
        // straight level track, +25 deg/s roll for 2 s then hold
        List<List<BezierBaker.Node>> l = new ArrayList<>();
        l.add(nodes(new double[][]{ {0,1},{4,1} }));
        l.add(nodes(new double[][]{ {0,0},{4,0} }));
        l.add(nodes(new double[][]{ {0,25},{2,25},{2.2,0},{4,0} }));
        for (boolean glob : new boolean[]{ false, true }) {
            Coaster c = Coaster.singleForce(l, 20, 0, 21, 0);
            ((LaneSection) c.soleSection()).rollGlobal = glob;
            TrackCompiler.Result r = TrackCompiler.compile(c, dt);
            FVDIntegrator.Sample e = r.samples().get(r.samples().size() - 1);
            System.out.printf(java.util.Locale.US,
                "%s roll +25deg/s x ~2s: end bankOfTU-style = %+.2f deg  (U.y=%.3f R.y=%+.3f)%n",
                glob ? "GLOBAL" : "LOCAL ", bank(e), e.U().y(), e.R().y());
        }
        // and an ABSOLUTE lane holding +50: which way does the track lean?
        List<List<BezierBaker.Node>> a = new ArrayList<>();
        a.add(nodes(new double[][]{ {0,1},{4,1} }));
        a.add(nodes(new double[][]{ {0,0},{4,0} }));
        a.add(nodes(new double[][]{ {0,0},{2,50},{4,50} }));
        Coaster ca = Coaster.singleForce(a, 20, 0, 21, 0);
        LaneSection sa = (LaneSection) ca.soleSection();
        sa.rollGlobal = true;
        sa.rollAbsolute = true;
        TrackCompiler.Result ra = TrackCompiler.compile(ca, dt);
        FVDIntegrator.Sample ea = ra.samples().get(ra.samples().size() - 1);
        System.out.printf(java.util.Locale.US,
            "ABSOLUTE lane +50: end bankOfTU-style = %+.2f deg  (R.y=%+.3f)%n",
            bank(ea), ea.R().y());
    }
}
