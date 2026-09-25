import uvd.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

/** D235 probe — reproduce the user's "oscillations in baking
 *  influence" (2026-08-24): load his split-influence file, bake the
 *  influence of section 0 (convertWholeSection to the OWN mode,
 *  uniform 0.15 s), and measure node-to-node alternation of the
 *  fitted Gv lane against the reference curve. */
public class BakeOscProbe {

    static final double DT = 0.01;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
            : "C:/Users/jacks/OneDrive/Documents/AI Fun/"
              + "Split Influence not lining up 8.24.26.fvd";
        DesignIO.LoadResult lr;
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            lr = DesignIO.load(in);
        }
        Coaster c = lr.coasters().get(0);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        LaneSection ls = (LaneSection) c.pieces.get(0).sections.get(0);
        System.out.println("section0 influenced=" + ls.influenced()
            + " vA=" + ls.vAuthored + " vD=" + ls.vDesign);
        // reference curve BEFORE the bake: the compiled samples' felt
        TrackCompiler.Span sp = r.spans().get(0);
        double[] refT = new double[sp.i1() - sp.i0() + 1];
        double[] refG = new double[refT.length];
        for (int i = 0; i < refT.length; i++) {
            FVDIntegrator.Sample sm = r.samples().get(sp.i0() + i);
            refT[i] = sm.t() - sp.t0();
            refG[i] = sm.Gv();
        }
        // the bake = whole convert to the OWN mode (force → force),
        // uniform 0.15, no adaptive — the user's exact settings
        boolean ok = c.convertWholeSection(0, 0, false,
            DT, 0.15, 0, Section.Domain.TIME, false, false, false,
            false, false);
        System.out.println("bake ok=" + ok);
        LaneSection conv = (LaneSection) c.pieces.get(0).sections.get(0);
        List<BezierBaker.Node> lane0 = conv.lanes.get(0);
        System.out.println("nodes=" + lane0.size());
        // alternation metric: successive node deviations from the
        // reference with flipping signs
        int flips = 0;
        double worst = 0;
        double prevDev = 0;
        for (int i = 0; i < lane0.size(); i++) {
            BezierBaker.Node n = lane0.get(i);
            double refV = interp(refT, refG, n.t);
            double dev = n.val - refV;
            if (i > 0 && dev * prevDev < 0
                    && Math.abs(dev) > 0.15 && Math.abs(prevDev) > 0.15) {
                flips++;
            }
            worst = Math.max(worst, Math.abs(dev));
            prevDev = dev;
            if (i < 30) {
                System.out.printf(Locale.US,
                    "  n%02d t=%.3f val=%8.3f ref=%8.3f dev=%8.3f%n",
                    i, n.t, n.val, refV, dev);
            }
        }
        System.out.printf(Locale.US,
            "ALTERNATION: %d sign-flipping >0.15g deviations, worst %.3f g%n",
            flips, worst);

        // ---- replicate the convertToLanes DATA extraction to see
        // whether the fed values already alternate ----
        Coaster c2;
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            c2 = DesignIO.load(in).coasters().get(0);
        }
        TrackCompiler.Result r2 = TrackCompiler.compile(c2, DT);
        TrackCompiler.Span sp2 = r2.spans().get(0);
        double y0 = r2.samples().get(sp2.i0()).pos().y();
        double vD = Math.max(1, c2.pieces.get(0).sections.get(0).vDesign());
        double lossG = 0;
        double prevVal = Double.NaN;
        int dataFlips = 0;
        for (int i = sp2.i0(); i <= sp2.i1(); i++) {
            FVDIntegrator.Sample sm = r2.samples().get(i);
            double vG = Math.max(0.5, Math.sqrt(Math.max(vD * vD
                + 2 * FVDIntegrator.G0 * (y0 - sm.pos().y()) - lossG, 0)));
            double vSafe = Math.max(0.5, sm.v());
            double kU = FVDIntegrator.G0 * (sm.Gv() - sm.U().y())
                / (vSafe * vSafe);
            double val = kU * vG * vG / FVDIntegrator.G0 + sm.U().y();
            if (i % 25 == 0 || (i < sp2.i0() + 8)) {
                System.out.printf(Locale.US,
                    "  data i=%03d t=%.2f Gv=%7.3f val=%7.3f vG=%6.2f "
                    + "v=%6.2f%n",
                    i, sm.t(), sm.Gv(), val, vG, sm.v());
            }
            if (!Double.isNaN(prevVal)
                    && Math.abs(val - prevVal) > 0.5) dataFlips++;
            prevVal = val;
        }
        System.out.println("data big jumps sample-to-sample: " + dataFlips);

        // ---- CONTROL: identical lanes, influence stripped ----
        Coaster c3;
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            c3 = DesignIO.load(in).coasters().get(0);
        }
        LaneSection p3 = (LaneSection) c3.pieces.get(0).sections.get(0);
        p3.deltaLanes = null;
        p3.vAuthored = Double.NaN;
        boolean ok3 = c3.convertWholeSection(0, 0, false,
            DT, 0.15, 0, Section.Domain.TIME, false, false, false,
            false, false);
        LaneSection conv3 = (LaneSection) c3.pieces.get(0).sections.get(0);
        List<BezierBaker.Node> l3 = conv3.lanes.get(0);
        int flips3 = 0;
        double worst3 = 0;
        double prev3 = 0;
        for (int i = 0; i < l3.size(); i++) {
            BezierBaker.Node n = l3.get(i);
            double refV = interp(refT, refG, n.t);   // rough ref reuse
            double dev = n.val - refV;
            if (i > 0 && dev * prev3 < 0
                    && Math.abs(dev) > 0.15 && Math.abs(prev3) > 0.15) {
                flips3++;
            }
            worst3 = Math.max(worst3, Math.abs(dev));
            prev3 = dev;
        }
        System.out.printf(Locale.US,
            "CONTROL (uninfluenced, ok=%b): %d flips, worst %.3f g%n",
            ok3, flips3, worst3);
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
}
