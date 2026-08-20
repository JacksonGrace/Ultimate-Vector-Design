package coaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Round-14 repro: does the recorded/true speed stairstep near zero
 *  (the D175 park returning), and if so on which array? Builds a hill
 *  that dips the speed toward ~0, then scans for runs of IDENTICAL
 *  consecutive values (the stair signature). */
public class SpeedStairProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static void scan(String name, double[] vals) {
        int worstRun = 1, run = 1;
        double runVal = Double.NaN, minV = Double.MAX_VALUE;
        for (int i = 1; i < vals.length; i++) {
            minV = Math.min(minV, vals[i]);
            if (vals[i] == vals[i - 1]) {
                run++;
                if (run > worstRun) { worstRun = run; runVal = vals[i]; }
            } else {
                run = 1;
            }
        }
        System.out.printf(Locale.US,
            "%-10s min=%.4f  worst flat run=%d samples (val=%.4f)%n",
            name, minV, worstRun, runVal);
    }

    public static void main(String[] a) {
        Coaster c = new Coaster();
        c.v0 = 8;
        TrackPiece pc = new TrackPiece(0, 21, 0, 0);
        // GEO climb (shape speed-independent): pitch up 45 deg, hold
        // the climb until the energy dies (v -> 0, creep), then dive
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{
            {0, 30}, {1.5, 30}, {1.6, 0}, {5, 0}, {5.1, -30},
            {8, -30}, {8.1, 0}, {12, 0} }));
        fl.add(nodes(new double[][]{ {0, 0}, {12, 0} }));
        fl.add(nodes(new double[][]{ {0, 0}, {12, 0} }));
        GeoSection gs = new GeoSection(fl, 8);
        pc.sections.add(gs);
        c.pieces.add(pc);
        // his working defaults: NL2-ish friction + a low brake
        c.fricRoll = 0.03;
        c.fricAir = 1.0e-4;
        SpeedControl br = new SpeedControl();
        br.type = SpeedControl.SPEED;
        br.speed = 0.8;
        br.x0 = 9;
        br.len = 2;
        br.minLimit = true;
        br.max = true;
        gs.speedControls().add(br);
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        int n = r.samples().size();
        double[] rec = new double[n];
        double[] tru = new double[n];
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            rec[i] = r.samples().get(i).v();
            tru[i] = r.trueV()[i];
            hMin = Math.min(hMin, r.samples().get(i).pos().y());
            hMax = Math.max(hMax, r.samples().get(i).pos().y());
        }
        System.out.printf(Locale.US,
            "samples=%d height %.2f..%.2f m%n", n, hMin, hMax);
        scan("design v", rec);
        scan("true v", tru);
        // print the slowest stretch of the DESIGN record
        int iMin = 0;
        for (int i = 0; i < n; i++) if (rec[i] < rec[iMin]) iMin = i;
        System.out.println("around the design minimum:");
        for (int i = Math.max(0, iMin - 8);
                i < Math.min(n, iMin + 8); i++) {
            System.out.printf(Locale.US, "  i=%d t=%.2f v=%.6f true=%.6f%n",
                i, r.samples().get(i).t(), rec[i], tru[i]);
        }
    }
}
