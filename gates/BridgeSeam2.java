package coaster;

import java.util.*;

/** Sharper seam-discontinuity probe (v17.10 / D148): measures the
 *  per-step frame rotation PER METER (local, two samples, no
 *  differencing smear) around both bridge seams.
 *  - kink: |theta(step across the seam) - theta(adjacent step)| minus
 *    the largest neighboring step-to-step change on either side
 *    (excess = a value discontinuity in curvature).
 *  - slopeBreak: theta'(s) measured from the first two steps on each
 *    side of the seam (C1 break in the rate graphs).
 *  Also reports whether the march spine or the septic rendered. */
public class BridgeSeam2 {

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

    /** Rotation angle (deg) per METER over step i -> i+1. */
    static double theta(List<FVDIntegrator.Sample> s, int i) {
        Quat qa = Quat.fromFrame(s.get(i).T(), s.get(i).U());
        Quat qb = Quat.fromFrame(s.get(i + 1).T(), s.get(i + 1).U());
        double dot = Math.abs(qa.w() * qb.w() + qa.x() * qb.x()
            + qa.y() * qb.y() + qa.z() * qb.z());
        double ds = Math.max(1e-9, s.get(i + 1).s() - s.get(i).s());
        return Math.toDegrees(2 * Math.acos(Math.min(1, dot))) / ds;
    }

    /** mid-arc of step i. */
    static double midS(List<FVDIntegrator.Sample> s, int i) {
        return (s.get(i).s() + s.get(i + 1).s()) / 2;
    }

    /** {kinkExcess deg/m, slopeBreak deg/m^2} at boundary sample ib. */
    static double[] seam(List<FVDIntegrator.Sample> s, int ib) {
        // steps: ...  [ib-2,ib-1] [ib-1,ib] | [ib,ib+1] [ib+1,ib+2] ...
        double thB2 = theta(s, ib - 3), thB1 = theta(s, ib - 2),
               thB0 = theta(s, ib - 1);
        double thA0 = theta(s, ib), thA1 = theta(s, ib + 1),
               thA2 = theta(s, ib + 2);
        double stepJump = Math.abs(thA0 - thB0);
        double typ = Math.max(Math.abs(thB1 - thB2),
            Math.max(Math.abs(thB0 - thB1),
            Math.max(Math.abs(thA1 - thA0), Math.abs(thA2 - thA1))));
        double kink = Math.max(0, stepJump - typ);
        double slL = (thB0 - thB1)
            / (midS(s, ib - 1) - midS(s, ib - 2));
        double slR = (thA1 - thA0)
            / (midS(s, ib + 1) - midS(s, ib));
        return new double[]{ kink, Math.abs(slR - slL) };
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
                + "  (kink deg/m, slopeBreak deg/m^2 at A | B; render)");
            for (double d = 8; d <= 64; d += 8) {
                c.optimizeBridge(bs, 1, d, mode == BridgeSection.OPT_POS_YAW);
                TrackCompiler.Result r = settle(c, dt);
                boolean spine = Coaster.marchSpine(bs,
                    mode == BridgeSection.OPT_POS_YAW) != null;
                int ibA = r.spans().get(1).i0();
                int ibB = r.spans().get(2).i0();
                double[] gA = seam(r.samples(), ibA);
                double[] gB = seam(r.samples(), ibB);
                System.out.printf(Locale.US,
                    "len %5.1f   A %8.4f %9.3f   B %8.4f %9.3f   %s%n",
                    d, gA[0], gA[1], gB[0], gB[1],
                    spine ? "MARCH" : "septic");
            }
            // septic reference: same seams, never optimized
            Coaster cs = bridged(BridgeSection.OPT_NONE);
            TrackCompiler.Result rs = settle(cs, dt);
            int ibA = rs.spans().get(1).i0();
            int ibB = rs.spans().get(2).i0();
            double[] gA = seam(rs.samples(), ibA);
            double[] gB = seam(rs.samples(), ibB);
            System.out.printf(Locale.US,
                "septic ref  A %8.4f %9.3f   B %8.4f %9.3f%n",
                gA[0], gA[1], gB[0], gB[1]);
        }
    }
}
