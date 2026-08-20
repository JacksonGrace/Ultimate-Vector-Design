package coaster;

import java.util.ArrayList;
import java.util.List;

/** Quantify the absolute-roll rate STAIRCASE: a high-rate bank ramp,
 *  max consecutive-sample jump of the resulting roll rate. */
public class StairProbe {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        // absolute lane ramping 0 -> 200 deg over 4 s (peak ~75 deg/s)
        List<List<BezierBaker.Node>> ab = new ArrayList<>();
        ab.add(nodes(new double[][]{ {0,1},{6,1} }));
        ab.add(nodes(new double[][]{ {0,0},{6,0} }));
        ab.add(nodes(new double[][]{ {0,0},{2,60},{6,60} }));
        Coaster c = Coaster.singleForce(ab, 20, 0, 21, 0);
        LaneSection s = (LaneSection) c.soleSection();
        s.rollGlobal = true;
        s.rollAbsolute = true;
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        double[][] rates = r.rates();
        double maxJump = 0, maxRate = 0;
        int jAt = 0;
        // skip the first/last 10 samples (entry/exit transients)
        for (int i = 11; i < rates.length - 10; i++) {
            double d = Math.abs(rates[i][3] - rates[i - 1][3]);
            if (d > maxJump) { maxJump = d; jAt = i; }
            maxRate = Math.max(maxRate, Math.abs(rates[i][3]));
        }
        System.out.printf(java.util.Locale.US,
            "max consecutive roll-rate jump = %.4f deg/s (at i=%d, "
            + "t=%.2f), peak rate = %.2f deg/s%n",
            maxJump, jAt, rates[jAt][0], maxRate);
        StringBuilder sb = new StringBuilder("profile near jump: ");
        for (int i = Math.max(1, jAt - 6); i < Math.min(rates.length,
                jAt + 6); i++) {
            sb.append(String.format(java.util.Locale.US, "%.2f ",
                rates[i][3]));
        }
        System.out.println(sb);
        // also the geo integrator path: same lane as a geo section
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
        TrackCompiler.Result rg = TrackCompiler.compile(cg, dt);
        double[][] gr = rg.rates();
        double gJump = 0;
        for (int i = 11; i < gr.length - 10; i++) {
            gJump = Math.max(gJump, Math.abs(gr[i][3] - gr[i - 1][3]));
        }
        System.out.printf(java.util.Locale.US,
            "geo path: max consecutive roll-rate jump = %.4f deg/s%n",
            gJump);
    }
}
