package uvd;

import java.util.ArrayList;
import java.util.List;

/** Compare the compiled absolute-roll bank/rate against the analytic
 *  smoothstep on a pure geo fixture (straight, no felt solver). */
public class StairProbe2 {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        // geo: zero pitch/yaw, ABSOLUTE roll ramp 0 -> 60 over 2 s
        List<List<BezierBaker.Node>> gb = new ArrayList<>();
        gb.add(nodes(new double[][]{ {0,0},{6,0} }));
        gb.add(nodes(new double[][]{ {0,0},{6,0} }));
        gb.add(nodes(new double[][]{ {0,0},{2,60},{6,60} }));
        Coaster cg = new Coaster();
        cg.v0 = 20;
        TrackPiece pg = new TrackPiece(0, 21, 0, 0);
        GeoSection sg = new GeoSection(gb, 20);
        sg.rollGlobal = true;
        sg.rollAbsolute = true;
        pg.sections.add(sg);
        cg.pieces.add(pg);
        TrackCompiler.Result r = TrackCompiler.compile(cg, dt);
        List<FVDIntegrator.Sample> sm = r.samples();
        double[][] rates = r.rates();

        System.out.println("  t     bank(meas)  bank(true)  rate(meas)  rate(true)");
        for (int i = 5; i <= 60; i += 1) {
            double t = sm.get(i).t();
            double bm = Math.toDegrees(
                FVDIntegrator.bankOfTU(sm.get(i).T(), sm.get(i).U()));
            double u = Math.min(1, t / 2);
            double bt = 60 * (3 * u * u - 2 * u * u * u);
            double rt = 60 * (6 * u - 6 * u * u) / 2;
            System.out.printf(java.util.Locale.US,
                "%5.2f  %10.4f  %10.4f  %10.3f  %10.3f%n",
                t, bm, bt, rates[i][3], rt);
        }
        // worst bank error + worst rate jump across the whole ramp
        double worstB = 0, worstJ = 0;
        for (int i = 5; i < 200; i++) {
            double t = sm.get(i).t();
            double u = Math.min(1, t / 2);
            double bt = 60 * (3 * u * u - 2 * u * u * u);
            double bm = Math.toDegrees(
                FVDIntegrator.bankOfTU(sm.get(i).T(), sm.get(i).U()));
            worstB = Math.max(worstB, Math.abs(bm - bt));
            if (i > 5) {
                worstJ = Math.max(worstJ,
                    Math.abs(rates[i][3] - rates[i - 1][3]));
            }
        }
        System.out.printf(java.util.Locale.US,
            "worst |bank err| = %.4f deg, worst rate jump = %.4f deg/s%n",
            worstB, worstJ);
    }
}
