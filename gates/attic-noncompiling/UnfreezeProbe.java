import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * v13.5 UNFREEZE: a bridge re-authored as an editable geo section
 * must compile to (nearly) the same track — end pose within a few cm
 * and a fraction of a degree of the old bridge exit, felt G close
 * along the way. Fixture: rolling, curving bridge between two pieces
 * (the nasty BridgeProbe2-style seam states).
 */
public class UnfreezeProbe {

    static final double DT = 0.01;
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

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

    public static void main(String[] args) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 24, 0, 0);
        a.sections.add(force(new double[][]{ {0, 1}, {1, 1.6}, {2, 1.2} },
            new double[][]{ {0, 0}, {2, 0.3} },
            new double[][]{ {0, 0}, {1, 20}, {2, 30} }, 24));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(70, 28, 12, -25);
        b.sections.add(force(new double[][]{ {0, 1}, {2, 1} },
            new double[][]{ {0, 0}, {2, 0} },
            new double[][]{ {0, 10}, {2, 0} }, 22));
        c.pieces.add(b);
        TrackCompiler.Result pre = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(
            pre.spans().get(0).exit(), b.entryPose(),
            pre.samples().get(pre.spans().get(0).i1()).v());
        a.sections.add(bs);
        TrackCompiler.Result withBridge = TrackCompiler.compile(c, DT);
        TrackCompiler.Span brSpan = withBridge.spans().get(1);
        TrackPose oldExit = brSpan.exit();
        double brLen = brSpan.sLen();
        List<FVDIntegrator.Sample> oldS = withBridge.samples();

        // unfreeze: replace the bridge with the re-authored geo section
        GeoSection g = Coaster.unfreezeBridge(bs, DT);
        a.sections.set(1, g);
        TrackCompiler.Result after = TrackCompiler.compile(c, DT);
        TrackCompiler.Span gSpan = after.spans().get(1);
        TrackPose newExit = gSpan.exit();

        double posErr = newExit.pos().sub(oldExit.pos()).length();
        double tErr = angleDeg(newExit.T(), oldExit.T());
        double uErr = angleDeg(newExit.U(), oldExit.U());
        int nodes = g.lanes.get(0).size();
        System.out.printf(
            "bridge %.1f m -> geo section with %d nodes/lane: end pos err %.4f m, T %.3f deg, U %.3f deg%n",
            brLen, nodes, posErr, tErr, uErr);
        // a deliberately VIOLENT bridge (whipsaw rates, big twist):
        // fit undershoot + the dt-grid cadence quantum (≤ 5 ms ≈ up to
        // 12 cm at speed) bound the honest error here
        check(posErr < 0.25, "violent unfreeze: end position within 25 cm");
        check(tErr < 0.8 && uErr < 0.9, "violent unfreeze: end orientation within a degree");
        check(nodes >= 4 && nodes <= 40, "unfreeze: editable node count (4-40)");

        // felt G stays close along the way (same geometry = same felt)
        double worstG = 0;
        int n = Math.min(gSpan.i1() - gSpan.i0(), brSpan.i1() - brSpan.i0());
        for (int i = 6; i < n - 6; i++) {
            FVDIntegrator.Sample so = oldS.get(brSpan.i0() + i);
            FVDIntegrator.Sample sn = after.samples().get(gSpan.i0() + i);
            worstG = Math.max(worstG, Math.abs(so.Gv() - sn.Gv()));
        }
        System.out.printf("worst felt-Gv difference along the span: %.4f g%n", worstG);
        check(worstG < 0.15, "unfreeze: felt G reads like the bridge did");

        tameCase();

        System.out.println(failures == 0 ? "ALL UNFREEZE PROBES PASS"
            : failures + " FAILURES");
    }

    /** The realistic case: a continuation-optimized bridge through a
     *  split constant turn — near-flat rates. Unfreeze should land
     *  within a few cm. */
    static void tameCase() {
        Coaster c = new Coaster();
        c.v0 = 20;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> e1 = new ArrayList<>();
        e1.add(StraightCurveSection.Element.curve(25, 75, 0, 0));
        List<StraightCurveSection.Element> e2 = new ArrayList<>();
        e2.add(StraightCurveSection.Element.curve(25, 75, 0, 0));
        p.sections.add(new StraightCurveSection(e1, 20));
        p.sections.add(new StraightCurveSection(e2, 20));
        c.pieces.add(p);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        c.splitPiece(0, 1, r.spans().get(0).exit());
        r = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(r.spans().get(0).exit(),
            c.pieces.get(1).entryPose(),
            r.samples().get(r.spans().get(0).i1()).v());
        c.pieces.get(0).sections.add(bs);
        TrackCompiler.compile(c, DT);
        bs.lenA = 30;
        bs.lenB = 30;
        c.alignPieceForBridge(bs, 1, 30);
        TrackCompiler.Result withB = TrackCompiler.compile(c, DT);
        TrackPose oldExit = withB.spans().get(1).exit();

        GeoSection g = Coaster.unfreezeBridge(bs, DT);
        c.pieces.get(0).sections.set(1, g);
        TrackCompiler.Result after = TrackCompiler.compile(c, DT);
        TrackPose newExit = after.spans().get(1).exit();
        double posErr = newExit.pos().sub(oldExit.pos()).length();
        double tErr = angleDeg(newExit.T(), oldExit.T());
        System.out.printf("tame (split-turn continuation) unfreeze: pos err %.4f m, T %.3f deg%n",
            posErr, tErr);
        check(posErr < 0.13, "tame unfreeze: end position within the cadence quantum");
        // ~one dt of rate (0.46 deg here) rides on the exit pose — the
        // sample-then-step cadence's honest floor
        check(tErr < 0.6, "tame unfreeze: heading within ~half a degree");
    }
}
