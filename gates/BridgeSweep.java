import coaster.*;
import java.util.*;

/** Length-sweep probe for the bridge seam-roughness report: for each
 *  requested bridge length, optimize + compile, then measure at BOTH
 *  bridge seams how the resulting-rate graphs (and true felt) break —
 *  VALUE gap and SLOPE gap, extrapolated to the seam from 3-6 samples
 *  on each side (outside the SEAM_TRIM smear). */
public class BridgeSweep {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0, 1}, {dur * 0.2, 0.6}, {dur * 0.5, 1.4},
            {dur * 0.8, 0.6}, {dur, 1}}));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return lanes;
    }

    static Coaster bridged(int optMode) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(hillLanes(5), 24));
        c.pieces.add(p);
        TrackPiece p2 = new TrackPiece(120, 16, -30, 40);
        p2.sections.add(new ForceSection(hillLanes(4), 21));
        c.pieces.add(p2);
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        BridgeSection bs = BridgeSection.solve(
            r.spans().get(0).exit(), p2.entryPose(), 24);
        bs.optMode = optMode;
        c.pieces.get(0).sections.add(bs);
        return c;
    }

    static TrackCompiler.Result settle(Coaster c, double dt) {
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        for (int i = 0; i < 6; i++) {
            boolean a = c.autoReoptimizeBridges(r);
            boolean b = c.reconcileChainedSpeeds(r);
            if (!a && !b) break;
            r = TrackCompiler.compile(c, dt);
        }
        return r;
    }

    /** {maxValueGap degps, maxSlopeGap degps2} across the 3 rate cols
     *  at boundary sample ib (linear extrapolation from each side). */
    static double[] seamGap(TrackCompiler.Result r, int ib) {
        List<FVDIntegrator.Sample> s = r.samples();
        double tS = s.get(ib).t();
        double vGap = 0, sGap = 0;
        for (int col = 0; col < 3; col++) {
            double tL1 = s.get(ib - 6).t(), tL2 = s.get(ib - 3).t();
            double rL1 = r.trueRates()[ib - 6][col],
                   rL2 = r.trueRates()[ib - 3][col];
            double slL = (rL2 - rL1) / (tL2 - tL1);
            double vL = rL2 + slL * (tS - tL2);
            double tR1 = s.get(ib + 3).t(), tR2 = s.get(ib + 6).t();
            double rR1 = r.trueRates()[ib + 3][col],
                   rR2 = r.trueRates()[ib + 6][col];
            double slR = (rR2 - rR1) / (tR2 - tR1);
            double vR = rR1 - slR * (tR1 - tS);
            vGap = Math.max(vGap, Math.abs(vL - vR));
            sGap = Math.max(sGap, Math.abs(slL - slR));
        }
        return new double[]{ vGap, sGap };
    }

    public static void main(String[] args) {
        double dt = 0.01;
        for (int mode : new int[]{ BridgeSection.OPT_POS,
                                   BridgeSection.OPT_POS_YAW }) {
            Coaster c = bridged(mode);
            BridgeSection bs =
                (BridgeSection) c.pieces.get(0).sections.get(1);
            System.out.println("=== mode "
                + (mode == BridgeSection.OPT_POS ? "OPT_POS" : "OPT_POS_YAW")
                + "  (len: seamA valGap slopeGap | seamB valGap slopeGap"
                + "  [deg/s, deg/s^2])");
            for (double d = 8; d <= 64; d += 4) {
                c.optimizeBridge(bs, 1, d, mode == BridgeSection.OPT_POS_YAW);
                TrackCompiler.Result r = settle(c, dt);
                int ibA = r.spans().get(1).i0();
                int ibB = r.spans().get(2).i0();
                double[] gA = seamGap(r, ibA);
                double[] gB = seamGap(r, ibB);
                System.out.printf(Locale.US,
                    "len %5.1f   A %8.3f %9.2f   B %8.3f %9.2f%n",
                    d, gA[0], gA[1], gB[0], gB[1]);
            }
        }
    }
}
