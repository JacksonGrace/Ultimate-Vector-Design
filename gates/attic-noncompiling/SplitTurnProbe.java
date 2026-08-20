import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * v13.4 continuation-march placement — the user's acceptance test:
 * split a CONSTANT TURN, bridge it back, optimize placement. The
 * bridge must CONTINUE THE SAME TURN (all rate graphs flat) and a
 * longer length must just slide the second piece around it.
 *
 * Fixtures are BODY-CONSTANT turns (what the march models, and what
 * force/geo constant turns are):
 *   1. flat s/c arc, rails level (RATE roll 0) — an exact circle.
 *   2. GEO coning turn: banked base frame + constant yaw rate — the
 *      banked-carousel case (constant body rates, tilted circle).
 *   3. straight control: continuation == dead-ahead exactly.
 * (An s/c GLOBAL-bank arc is deliberately NOT body-constant — bank is
 * held vs the horizon, so its body state varies around the loop.)
 */
public class SplitTurnProbe {

    static final double DT = 0.01;
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static StraightCurveSection arc(double r, double angle, double v) {
        List<StraightCurveSection.Element> els = new ArrayList<>();
        StraightCurveSection.Element e =
            StraightCurveSection.Element.curve(r, angle, 0, 0);
        e.rollMode = StraightCurveSection.RollMode.RATE;
        e.rollVal = 0;
        els.add(e);
        return new StraightCurveSection(els, v);
    }

    static List<BezierBaker.Node> flat(double t1, double val) {
        List<BezierBaker.Node> l = new ArrayList<>();
        l.add(new BezierBaker.Node(0, val));
        l.add(new BezierBaker.Node(t1, val));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static ForceSection forceTurn(double dur, double gv, double gl, double v) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(flat(dur, gv));
        lanes.add(flat(dur, gl));
        lanes.add(flat(dur, 0));
        return new ForceSection(lanes, v);
    }

    /** max |rates − reference row| over the span interior. */
    static double[] rateFlatness(TrackCompiler.Result r, TrackCompiler.Span sp,
                                 int refIdx) {
        double[][] rr = r.rates();
        double[] worst = new double[3];
        for (int i = sp.i0() + 6; i <= sp.i1() - 6; i++) {
            for (int c = 1; c <= 3; c++) {
                worst[c - 1] = Math.max(worst[c - 1],
                    Math.abs(rr[i][c] - rr[refIdx][c]));
            }
        }
        return worst;
    }

    /** Split piece 0 between its two sections, bridge across, and hand
     *  back the bridge (compile has captured its seam states). */
    static BridgeSection splitAndBridge(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        c.splitPiece(0, 1, r.spans().get(0).exit());
        r = TrackCompiler.compile(c, DT);
        TrackCompiler.Span spA = r.spans().get(0);
        BridgeSection bs = BridgeSection.solve(spA.exit(),
            c.pieces.get(1).entryPose(), r.samples().get(spA.i1()).v());
        c.pieces.get(0).sections.add(bs);
        TrackCompiler.compile(c, DT);   // live capture of seam states
        return bs;
    }

    /** The app's resize path: tangents follow the length, then the
     *  placement march runs (applyBridgeGap without the UI). */
    static void resize(Coaster c, BridgeSection bs, double L, double oldLen) {
        if (oldLen > 0.5) {
            bs.lenA *= L / oldLen;
            bs.lenB *= L / oldLen;
        } else {
            bs.lenA = L;
            bs.lenB = L;
        }
        c.alignPieceForBridge(bs, 1, L);
    }

    static void constantTurnCase(String name, Coaster c, boolean circleChecks) {
        System.out.println("--- " + name + " ---");
        BridgeSection bs = splitAndBridge(c);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        TrackCompiler.Span spA = r.spans().get(0);
        int ref = spA.i1() - 8;
        double[][] rr = r.rates();
        System.out.printf("  seam rates: pitch %.2f yaw %.2f roll %.2f deg/s  v %.2f%n",
            rr[ref][1], rr[ref][2], rr[ref][3], r.samples().get(ref).v());
        TrackPose seam = spA.exit();
        Vec3 kap = bs.accA;
        double k = kap.length();
        Vec3 center = seam.pos().add(kap.scale(1 / (k * k)));
        double radius = 1 / k;
        double prevLen = 0;
        for (double L : new double[]{ 15, 30, 45 }) {
            resize(c, bs, L, prevLen);
            r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span spBr = r.spans().get(1);
            prevLen = spBr.sLen();
            double[] flat = rateFlatness(r, spBr, ref);
            double worstRad = 0, worstY = 0;
            for (int i = spBr.i0(); i <= spBr.i1(); i++) {
                Vec3 pos = r.samples().get(i).pos();
                worstRad = Math.max(worstRad, Math.abs(
                    Math.hypot(pos.x() - center.x(), pos.z() - center.z())
                    - radius));
                worstY = Math.max(worstY, Math.abs(pos.y() - seam.pos().y()));
            }
            System.out.printf(
                "  L %4.0f: len %6.2f | rate dev p %5.2f y %5.2f r %5.2f deg/s | radial %6.3f m | dy %6.3f m%n",
                L, spBr.sLen(), flat[0], flat[1], flat[2], worstRad, worstY);
            check(flat[0] < 1.5 && flat[1] < 1.5 && flat[2] < 1.5,
                String.format("%s L=%.0f: rate graphs stay flat", name, L));
            if (circleChecks) {
                check(worstRad < 0.10,
                    String.format("%s L=%.0f: bridge stays ON the circle", name, L));
                check(worstY < 0.05,
                    String.format("%s L=%.0f: flat turn stays flat", name, L));
            }
            check(Math.abs(spBr.sLen() - L) < L * 0.05 + 0.5,
                String.format("%s L=%.0f: length tracks the request", name, L));
        }
    }

    public static void main(String[] args) {
        // 1. flat circle, rails level
        Coaster c1 = new Coaster();
        c1.v0 = 20;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(arc(25, 75, 20));
        p.sections.add(arc(25, 75, 20));
        c1.pieces.add(p);
        constantTurnCase("flat s/c circle", c1, true);

        // 2. the user's actual case: a FORCE-mode banked LEVEL turn
        // (the example layout's kind of turn) — entry frame banked 30°,
        // Gv = 1/cos(30°), Gl = 0, roll 0: a constant level circle
        Coaster c2 = new Coaster();
        c2.v0 = 20;
        TrackPiece g = new TrackPiece(0, 40, 0, 0);
        g.baseOrient = Quat.fromFrame(new Vec3(1, 0, 0),
            new Vec3(0, Math.cos(Math.toRadians(30)), Math.sin(Math.toRadians(30))));
        double gv = 1 / Math.cos(Math.toRadians(30));
        g.sections.add(forceTurn(3, gv, 0, 20));
        g.sections.add(forceTurn(3, gv, 0, 20));
        c2.pieces.add(g);
        constantTurnCase("banked level force turn", c2, true);

        // 3. straight control: dead-ahead preserved exactly
        Coaster s = new Coaster();
        s.v0 = 20;
        TrackPiece q0 = new TrackPiece(0, 21, 0, 0);
        q0.sections.add(StraightCurveSection.straight(30, 20));
        q0.sections.add(StraightCurveSection.straight(30, 20));
        s.pieces.add(q0);
        BridgeSection sbs = splitAndBridge(s);
        TrackCompiler.Result rs = TrackCompiler.compile(s, DT);
        TrackPose send = rs.spans().get(0).exit();
        s.alignPieceForBridge(sbs, 1, 30);
        Vec3 expect = send.pos().add(send.T().scale(30));
        Vec3 got = s.pieces.get(1).entryPose().pos();
        System.out.printf("straight control: |placed − dead-ahead| = %.2e m%n",
            got.sub(expect).length());
        check(got.sub(expect).length() < 1e-6,
            "straight seams: continuation IS dead-ahead");

        System.out.println(failures == 0 ? "ALL SPLIT-TURN PROBES PASS"
            : failures + " FAILURES");
    }
}
