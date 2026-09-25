import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Print the convert extraction's rates at the LAST samples of the
 *  bent-geo fixture — is the end-tangent kink fed by poisoned end
 *  data? */
public class EndSpike {

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
        c.v0 = 26;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new GeoSection(bl, 26));
        c.pieces.add(p);
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        List<FVDIntegrator.Sample> s = r.samples();
        int n = s.size();
        System.out.println("samples " + n + "  vEnd=" + s.get(n-1).v());
        for (int i = n - 6; i < n; i++) {
            FVDIntegrator.Sample sm = s.get(i);
            double v = Math.max(0.5, sm.v());
            double pit = Math.toDegrees(
                FVDIntegrator.G0 * (sm.Gv() - sm.U().y()) / v);
            double yaw = Math.toDegrees(
                FVDIntegrator.G0 * (sm.Gl() - sm.R().y()) / v);
            FVDIntegrator.Sample a = s.get(Math.max(0, i - 1));
            FVDIntegrator.Sample b = s.get(Math.min(n - 1, i + 1));
            double ds = b.s() - a.s();
            double roll = ds < 1e-9 ? Double.NaN
                : Math.toDegrees(-b.U().sub(a.U()).dot(sm.R()) / ds) * v;
            System.out.printf(java.util.Locale.US,
                "i=%4d t=%7.4f v=%6.3f Gv=%8.4f Gl=%8.4f  pit=%8.3f yaw=%8.3f roll=%8.3f%n",
                i, sm.t(), sm.v(), sm.Gv(), sm.Gl(), pit, yaw, roll);
        }
    }
}
