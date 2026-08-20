package coaster;

import java.util.ArrayList;
import java.util.List;

/** Replicates the v16.8 GraphEditor.absRollTarget (neighbor-side
 *  sample + slope 3 inside + unwrap-to-current-node) for the abs
 *  section's START node (atStart=false), incl. a CUTBACK case where
 *  the seam bank sits past 180° unwrapped. */
public class AbsSeamProbe {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** the NEW absRollTarget replica: atStart=false (our START node,
     *  neighbor before us = section 0). */
    static double[] target(TrackCompiler.Result r, LaneSection abs) {
        TrackCompiler.Span act = r.spans().get(1);
        List<FVDIntegrator.Sample> all = r.samples();
        int last = all.size() - 1;
        int ib = Math.max(0, Math.min(act.i0() - 1, last));
        FVDIntegrator.Sample sm = all.get(ib);
        double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
        if (Double.isNaN(b)) return null;
        int is = Math.max(0, Math.min(ib - 3,
            Math.min(last, r.rates().length - 1)));
        FVDIntegrator.Sample ss = all.get(is);
        double vSafe = Math.max(0.5, ss.v());
        double pitB = Math.toDegrees(FVDIntegrator.G0
            * (ss.Gv() - ss.U().y()) / vSafe);
        double yawB = Math.toDegrees(FVDIntegrator.G0
            * (ss.Gl() - ss.R().y()) / vSafe);
        double phi = Coaster.worldRates(ss, pitB, yawB,
            r.rates()[is][3])[2];
        double slope = Double.isNaN(phi) ? 0 : phi;
        double bd = Math.toDegrees(b);
        List<BezierBaker.Node> rl = abs.lanes.get(2);
        double cur = rl.get(0).val;
        bd += 360 * Math.round((cur - bd) / 360);
        return new double[]{ bd, slope };
    }

    static void run(String tag, double upRate, double upHold,
                    boolean upGlobal, double absStart) {
        double tEnd = upHold + 2;
        List<List<BezierBaker.Node>> up = new ArrayList<>();
        up.add(nodes(new double[][]{ {0,1},{tEnd,1} }));
        up.add(nodes(new double[][]{ {0,0},{tEnd,0} }));
        up.add(nodes(new double[][]{
            {0,upRate},{upHold,upRate},{upHold+0.2,0},{tEnd,0} }));
        List<List<BezierBaker.Node>> dn = new ArrayList<>();
        dn.add(nodes(new double[][]{ {0,1},{4,1} }));
        dn.add(nodes(new double[][]{ {0,0},{4,0} }));
        dn.add(nodes(new double[][]{ {0,absStart},{4,absStart} }));

        Coaster c = new Coaster();
        c.v0 = 20;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        LaneSection s0 = new ForceSection(up, 20);
        s0.rollGlobal = upGlobal;
        LaneSection s1 = new ForceSection(dn, 20);
        s1.rollGlobal = true;
        s1.rollAbsolute = true;
        p.sections.add(s0);
        p.sections.add(s1);
        c.pieces.add(p);

        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        double[] tg = target(r, s1);
        double upBank = upRate * upHold;   // exact for GLOBAL rate
        System.out.printf(java.util.Locale.US,
            "%-34s upstream bank(unwrapped)=%+7.1f | B value=%+8.2f "
            + "slope=%+7.2f deg/s%n",
            tag, upBank, tg == null ? Double.NaN : tg[0],
            tg == null ? Double.NaN : tg[1]);
    }

    public static void main(String[] args) {
        run("global +25x2s -> abs lane 0", 25, 2, true, 0);
        run("local  +25x2s -> abs lane 0", 25, 2, false, 0);
        run("global +25x2s -> abs lane -50", 25, 2, true, -50);
        run("global -25x2s -> abs lane 0", -25, 2, true, 0);
        // CUTBACK: +25 x 10 s = +250 unwrapped (principal -110)
        run("global +25x10s -> abs lane +250", 25, 10, true, 250);
        run("global +25x10s -> abs lane 0", 25, 10, true, 0);
        // mid-roll seam: rate still 25 deg/s AT the seam (slope test)
        double tEnd = 2;
        List<List<BezierBaker.Node>> up = new ArrayList<>();
        up.add(nodes(new double[][]{ {0,1},{tEnd,1} }));
        up.add(nodes(new double[][]{ {0,0},{tEnd,0} }));
        up.add(nodes(new double[][]{ {0,25},{tEnd,25} }));
        List<List<BezierBaker.Node>> dn = new ArrayList<>();
        dn.add(nodes(new double[][]{ {0,1},{4,1} }));
        dn.add(nodes(new double[][]{ {0,0},{4,0} }));
        dn.add(nodes(new double[][]{ {0,0},{4,0} }));
        Coaster c = new Coaster();
        c.v0 = 20;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        LaneSection s0 = new ForceSection(up, 20);
        s0.rollGlobal = true;
        LaneSection s1 = new ForceSection(dn, 20);
        s1.rollGlobal = true;
        s1.rollAbsolute = true;
        p.sections.add(s0);
        p.sections.add(s1);
        c.pieces.add(p);
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        double[] tg = target(r, s1);
        System.out.printf(java.util.Locale.US,
            "%-34s upstream bank(unwrapped)=%+7.1f | B value=%+8.2f "
            + "slope=%+7.2f deg/s  (expect slope ~ +25)%n",
            "mid-roll seam (rate 25 AT seam)", 50.0,
            tg == null ? Double.NaN : tg[0],
            tg == null ? Double.NaN : tg[1]);
    }
}
