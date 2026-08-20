import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * v13.4 bridge fixes, verified headlessly:
 *  1. REVERSED-target kink: bridging into a piece whose first section
 *     is reversed used to aim at the nominal entry pose (pitched/
 *     banked from the reversal) while the track actually starts
 *     yaw-placed — hard kink at the reversed entrance. Now the bridge
 *     aims at the ACTUAL head frame: seam tangent angle ~ 0.
 *  2. DELETE the bridge: pieces stay detached, placements unchanged.
 *  3. Bisector placement: chain ending pitched into a level-headed
 *     piece — the bisector chord halves the max rate-slope vs the old
 *     dead-ahead placement.
 *  4. Roll-rate SLOPE continuity: a chain whose roll rate is ramping
 *     at the seam — the bridge's roll-rate slope now continues it
 *     (was: flat entry regardless).
 */
public class BridgeProbe2 {

    static final double DT = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static ForceSection force(double[][] gv, double[][] gl, double[][] roll, double v) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(gv));
        lanes.add(nodes(gl));
        lanes.add(nodes(roll));
        return new ForceSection(lanes, v);
    }

    static double angleDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            a.normalize().dot(b.normalize())))));
    }

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------------
    static void reversedTargetKinkAndDelete() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(force(new double[][]{ {0, 1}, {2, 1} },
            new double[][]{ {0, 0}, {2, 0} },
            new double[][]{ {0, 0}, {2, 0} }, 26));
        c.pieces.add(a);
        // piece B ends pitched + banked, then gets REVERSED in place
        TrackPiece b = new TrackPiece(60, 26, 10, 30);
        b.sections.add(force(new double[][]{ {0, 1}, {1.0, 2.2}, {2, 2.2} },
            new double[][]{ {0, 0}, {2, 0.3} },
            new double[][]{ {0, 0}, {1, 30}, {2, 30} }, 22));
        c.pieces.add(b);
        TrackCompiler.Result pre = TrackCompiler.compile(c, DT);
        c.reversePiece(1, pre.spans().get(1).exit());
        // EDIT the reversed section afterwards: the stored baseOrient
        // (the pre-edit exit) goes stale vs the re-generated shape, so
        // the nominal entry pose and the ACTUAL yaw-placed head now
        // genuinely differ — the user's kink scenario
        ForceSection rf = (ForceSection) b.sections.get(0);
        rf.lanes.get(0).get(1).val = 1.5;   // was 2.2
        rf.lanes.get(0).get(2).val = 1.5;
        rf.lanes.get(2).get(1).val = 45;    // was 30
        rf.lanes.get(2).get(2).val = 45;
        TrackCompiler.Result rev = TrackCompiler.compile(c, DT);

        // bridge A -> B(reversed head)
        BridgeSection bs = BridgeSection.solve(
            rev.spans().get(0).exit(), b.entryPose(), 26);
        a.sections.add(bs);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        TrackCompiler.Span spBr = r.spans().get(1);
        TrackCompiler.Span spB = r.spans().get(2);
        List<FVDIntegrator.Sample> s = r.samples();
        FVDIntegrator.Sample brEnd = s.get(spBr.i1());
        FVDIntegrator.Sample bHead = s.get(Math.min(spB.i0(), s.size() - 1));
        double kinkT = angleDeg(brEnd.T(), bHead.T());
        double kinkU = angleDeg(brEnd.U(), bHead.U());
        double posGap = bHead.pos().sub(brEnd.pos()).length();
        System.out.printf("  reversed head seam: T off %.4f deg, U off %.4f deg, pos gap %.4f m%n",
            kinkT, kinkU, posGap);
        check(kinkT < 0.6 && kinkU < 0.6, "bridge->reversed head: no kink (T+U continuous)");
        check(posGap < 0.3, "bridge->reversed head: positions meet");
        double nomPitch = Math.toDegrees(Math.asin(b.entryPose().T().y()));
        double actPitch = Math.toDegrees(Math.asin(bHead.T().y()));
        System.out.printf("  nominal entry pitch %.2f deg vs actual head pitch %.2f deg%n",
            nomPitch, actPitch);

        // DELETE the bridge: detached again, placements untouched
        double bx = b.x, by = b.y, bz = b.z, byaw = b.yawDeg;
        a.sections.remove(bs);
        TrackCompiler.Result del = TrackCompiler.compile(c, DT);
        check(c.pieces.size() == 2, "delete bridge: still two pieces");
        check(b.x == bx && b.y == by && b.z == bz && b.yawDeg == byaw,
            "delete bridge: target placement untouched");
        TrackCompiler.Span dA = del.spans().get(0), dB = del.spans().get(1);
        double gap = del.samples().get(dB.i0()).pos()
            .sub(del.samples().get(dA.i1()).pos()).length();
        System.out.printf("  post-delete piece gap: %.2f m%n", gap);
        check(gap > 5, "delete bridge: pieces are detached in space");
        boolean finite = true;
        for (FVDIntegrator.Sample sm : del.samples()) {
            if (!Double.isFinite(sm.pos().x()) || !Double.isFinite(sm.pos().y())
                    || !Double.isFinite(sm.pos().z())) finite = false;
        }
        check(finite, "delete bridge: compile stays finite");
    }

    // ------------------------------------------------------------------
    static double maxRateSlope(TrackCompiler.Result r, TrackCompiler.Span sp) {
        double[][] rr = r.rates();
        double max = 0;
        for (int i = sp.i0() + 4; i <= sp.i1() - 4; i++) {
            double dt = rr[i][0] - rr[i - 1][0];
            if (dt < 1e-9) continue;
            max = Math.max(max, Math.hypot(rr[i][1] - rr[i - 1][1],
                rr[i][2] - rr[i - 1][2]) / dt);
        }
        return max;
    }

    static void bisectorPlacement() {
        // chain dives at ~ -14 deg into a LEVEL-headed target piece
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 40, 0, 0);
        a.sections.add(force(new double[][]{ {0, 1}, {0.6, 0.2}, {1.4, 0.2}, {2.0, 1} },
            new double[][]{ {0, 0}, {2, 0} },
            new double[][]{ {0, 0}, {2, 0} }, 26));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(70, 20, 0, 0);
        b.sections.add(force(new double[][]{ {0, 1}, {2, 1} },
            new double[][]{ {0, 0}, {2, 0} },
            new double[][]{ {0, 0}, {2, 0} }, 24));
        c.pieces.add(b);
        TrackCompiler.Result pre = TrackCompiler.compile(c, DT);
        TrackPose end = pre.spans().get(0).exit();
        double endPitch = Math.toDegrees(Math.asin(end.T().y()));

        BridgeSection bs = BridgeSection.solve(end, b.entryPose(), 26);
        a.sections.add(bs);

        // OLD dead-ahead placement, emulated by hand
        Vec3 p = end.pos().add(end.T().scale(30));
        b.x = p.x(); b.y = p.y(); b.z = p.z(); b.yawDeg = 0;
        TrackCompiler.Result dead = TrackCompiler.compile(c, DT);
        double deadSlope = maxRateSlope(dead, dead.spans().get(1));

        // NEW continuation-march placement
        c.alignPieceForBridge(bs, 1, 30);
        TrackCompiler.Result bis = TrackCompiler.compile(c, DT);
        double bisSlope = maxRateSlope(bis, bis.spans().get(1));
        System.out.printf("  chain end pitch %.1f deg; max |dRate/dt| dead-ahead %.1f vs continuation %.1f deg/s^2%n",
            endPitch, deadSlope, bisSlope);
        check(endPitch < -8, "continuation fixture: chain genuinely dives");
        check(bisSlope < deadSlope * 0.75,
            "continuation placement calms the bridge vs dead-ahead");
    }

    // ------------------------------------------------------------------
    static void rollSlopeContinuity() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        ForceSection fs = force(new double[][]{ {0, 1}, {2, 1} },
            new double[][]{ {0, 0}, {2, 0} },
            new double[][]{ {0, 0}, {1, 10}, {2, 40} }, 26);
        // give the roll lane a real SLOPE at the section end
        List<BezierBaker.Node> roll = fs.lanes.get(2);
        BezierBaker.Node lastN = roll.get(roll.size() - 1);
        lastN.inDv = 25 * BezierBaker.effInDt(roll.get(roll.size() - 2), lastN);
        a.sections.add(fs);
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(75, 21, 5, -10);
        b.sections.add(force(new double[][]{ {0, 1}, {2, 1} },
            new double[][]{ {0, 0}, {2, 0} },
            new double[][]{ {0, 0}, {2, 0} }, 24));
        c.pieces.add(b);
        TrackCompiler.Result pre = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(
            pre.spans().get(0).exit(), b.entryPose(), 26);
        a.sections.add(bs);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        double[][] rr = r.rates();
        TrackCompiler.Span spBr = r.spans().get(1);
        int i0 = spBr.i0();
        // roll-rate slope just before the seam
        double slopeChain = (rr[i0 - 3][3] - rr[i0 - 7][3])
            / (rr[i0 - 3][0] - rr[i0 - 7][0]);
        // the bridge alone, with captured accs (v13.4) vs zeroed accs
        // (the v13.3 flat shelf) — same captured everything else
        double newSlope = bridgeEntrySlope(bs);
        double accA = bs.rollAccA, accB = bs.rollAccB;
        bs.rollAccA = 0;
        bs.rollAccB = 0;
        double flatSlope = bridgeEntrySlope(bs);
        bs.rollAccA = accA;
        bs.rollAccB = accB;
        System.out.printf("  roll-rate slope: chain end %.1f | bridge entry with accs %.1f | zeroed %.1f deg/s^2%n",
            slopeChain, newSlope, flatSlope);
        check(Math.abs(slopeChain) > 8, "roll-slope fixture: chain really ramps");
        check(Math.abs(newSlope - slopeChain) < Math.abs(flatSlope - slopeChain),
            "captured roll accs pull the bridge's entry slope toward the chain's");
    }

    /** The bridge's own roll-rate slope over its first few samples
     *  (bodyRates on the isolated bridge samples, clear of ends). */
    static double bridgeEntrySlope(BridgeSection bs) {
        List<FVDIntegrator.Sample> s = bs.integrate(DT).samples();
        double[][] rr = FVDIntegrator.bodyRates(s);
        return (rr[4][3] - rr[1][3]) / (rr[4][0] - rr[1][0]);
    }

    public static void main(String[] args) {
        System.out.println("--- reversed-target kink + delete ---");
        reversedTargetKinkAndDelete();
        System.out.println("--- bisector placement ---");
        bisectorPlacement();
        System.out.println("--- roll-rate slope continuity ---");
        rollSlopeContinuity();
        System.out.println(failures == 0 ? "ALL BRIDGE PROBES PASS"
            : failures + " FAILURES");
    }
}
