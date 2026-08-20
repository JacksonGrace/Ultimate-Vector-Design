import coaster.*;

import java.io.*;
import java.util.*;

/** D222 gate — TWO-TIER ORIENTATION ("Lock Track Shape") + RIGID
 *  DOWNSTREAM CARRY + the D221→D222 re-spec.
 *
 *  a) defaults: fresh piece locked, design (0,0), no conflict.
 *  b) edit routing: locked moves TRUE only; unlocked moves BOTH by
 *     the same amount; a pre-existing conflict rides along.
 *  c) compile tiers: design (0,0) = the historic rigid path (exact
 *     rigid image); design == true = bakeTilt's re-solve (no ≠v);
 *     MIXED = the D222 invariance — the same design triple produces
 *     the same shape whether or not a conflict rides on top (the
 *     conflicted twin is the exact rigid image of the clean one).
 *  d) design deltas live: x/y/z + yaw moves stay BITWISE-rigid.
 *  e) RIGID carry, translation: exact through a manual bridge.
 *  f) RIGID carry, pitch: pieces exact rigid images, bridge spine
 *     rigid, target adopts the conflict (true tier only).
 *  g) yaw carry across a strict seam: follower rides exactly.
 *  h) porient IO: new format round-trips; the D221 interim 2-field
 *     line migrates (mode 1 → unlocked + design := true); missing =
 *     locked + (0,0).
 *  i) the march's alignment writes never touch the design tier
 *     (no reshape-loop even on an unlocked target).
 *  j) button semantics at the model level: real→design keeps the
 *     shape (rigid image); design→real re-solves to the pure-link
 *     shape.
 */
public class OrientGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static void check(boolean ok, String name, String detail) {
        check(ok, name + "  (" + detail + ")");
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.2, 1}, {2.4, 3.2}, {4, 0.4},
            {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.4}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> flatLanes(double len) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        return ls;
    }

    static Coaster oneHill() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(p);
        return c;
    }

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    static double rigidImageDev(List<FVDIntegrator.Sample> ref,
                                List<FVDIntegrator.Sample> got,
                                Quat q, Vec3 a0) {
        double worst = 0;
        int n = Math.min(ref.size(), got.size());
        for (int i = 0; i < n; i++) {
            Vec3 want = a0.add(q.rotate(ref.get(i).pos().sub(a0)));
            worst = Math.max(worst, got.get(i).pos().sub(want).length());
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        // ---------- (a) defaults ----------
        TrackPiece fresh = new TrackPiece(0, 21, 0, 0);
        check(fresh.shapeLocked && fresh.designPitchDeg == 0
                && fresh.designRollDeg == 0 && !fresh.orientConflict(),
            "a  fresh piece: locked, design (0,0), no conflict");

        // ---------- (b) edit routing ----------
        TrackPiece rp = new TrackPiece(0, 21, 0, 0);
        rp.editPitch(10);
        check(rp.pitchDeg == 10 && rp.designPitchDeg == 0
                && rp.orientConflict(),
            "b1 locked edit: TRUE moves, design holds, conflict opens");
        rp.shapeLocked = false;
        rp.editPitch(16);          // +6 with a 10-deg conflict riding
        check(rp.pitchDeg == 16 && rp.designPitchDeg == 6,
            "b2 unlocked edit: both tiers move by the same amount");
        rp.editRoll(-9);
        check(rp.rollDeg == -9 && rp.designRollDeg == -9,
            "b3 unlocked roll from zero: tiers stay equal");
        rp.shapeLocked = true;
        rp.editRoll(-4);
        check(rp.rollDeg == -4 && rp.designRollDeg == -9,
            "b4 re-locked edit: TRUE only again");

        // ---------- (c) compile tiers ----------
        // c1: design (0,0) + true tilt = the HISTORIC rigid path
        Coaster c1 = oneHill();
        TrackPiece p1 = c1.pieces.get(0);
        TrackCompiler.Result flat = rebuild(c1);
        p1.editPitch(12);          // locked: pure conflict
        p1.editRoll(-8);
        TrackCompiler.Result rigid = rebuild(c1);
        Quat q1 = p1.entryPose().orient()
            .mul(p1.genPose().orient().conjugate());
        double devR = rigidImageDev(flat.samples(), rigid.samples(),
            q1, p1.genPose().pos());
        check(p1.designPitchDeg == 0 && devR < 1e-9,
            "c1 locked tilt: exact rigid image (historic D100)",
            String.format("dev %.2e m", devR));
        // c2: design == true = bakeTilt's re-solve, no ≠v
        Coaster c2 = oneHill();
        TrackPiece p2 = c2.pieces.get(0);
        p2.shapeLocked = false;
        p2.editPitch(12);
        p2.editRoll(-8);
        TrackCompiler.Result linked = rebuild(c2);
        Coaster c3 = oneHill();
        TrackPiece p3 = c3.pieces.get(0);
        p3.editPitch(12);
        p3.editRoll(-8);
        p3.bakeTilt();
        TrackCompiler.Result baked = rebuild(c3);
        double devL = 0;
        int nL = Math.min(linked.samples().size(), baked.samples().size());
        for (int i = 0; i < nL; i++) {
            devL = Math.max(devL, linked.samples().get(i).pos()
                .sub(baked.samples().get(i).pos()).length());
        }
        double vSplit = 0;
        for (int i = 0; i < linked.samples().size(); i++) {
            vSplit = Math.max(vSplit, Math.abs(linked.trueV()[i]
                - linked.samples().get(i).v()));
        }
        check(devL < 1e-6 && vSplit < 1e-9,
            "c2 design == true: bakeTilt's re-solve, no design/true split",
            String.format("dev %.2e m, dv %.2e", devL, vSplit));
        // c3: the D222 INVARIANCE — same design triple, conflict on
        // top: the conflicted twin is the exact rigid image
        Coaster cA = oneHill();
        TrackPiece pA = cA.pieces.get(0);
        pA.shapeLocked = false;
        pA.editPitch(7);           // design = true = (7, -5)
        pA.editRoll(-5);
        TrackCompiler.Result rA = rebuild(cA);
        Coaster cB = oneHill();
        TrackPiece pB = cB.pieces.get(0);
        pB.editPitch(11);          // locked first: conflict 11 deg
        pB.shapeLocked = false;
        pB.editPitch(18);          // +7 → design pitch 7, true 18
        pB.editRoll(-5);           // design roll -5, true -5
        TrackCompiler.Result rB = rebuild(cB);
        boolean tripleEq = pB.designPitchDeg == pA.designPitchDeg
            && pB.designRollDeg == pA.designRollDeg;
        Quat qAB = pB.entryPose().orient()
            .mul(pA.entryPose().orient().conjugate());
        double devI = rigidImageDev(rA.samples(), rB.samples(),
            qAB, pA.entryPose().pos());
        check(tripleEq && rA.samples().size() == rB.samples().size()
                && devI < 1e-9,
            "c3 D222 invariance: same edits after unlock = same shape "
            + "(conflicted twin = exact rigid image)",
            String.format("design (%.1f,%.1f) dev %.2e m",
                pB.designPitchDeg, pB.designRollDeg, devI));

        // ---------- (d) design deltas live: moves bitwise ----------
        List<FVDIntegrator.Sample> d0 = rB.samples();
        pB.x += 30;
        pB.y += 50;
        pB.z -= 12;
        TrackCompiler.Result rBm = rebuild(cB);
        boolean vBit = true;
        double dyWorst = 0;
        for (int i = 0; i < d0.size(); i++) {
            if (Double.doubleToLongBits(d0.get(i).v())
                    != Double.doubleToLongBits(rBm.samples().get(i).v())) {
                vBit = false;
            }
            dyWorst = Math.max(dyWorst, Math.abs(
                rBm.samples().get(i).pos().y() - d0.get(i).pos().y() - 50));
        }
        check(vBit && dyWorst < 1e-9,
            "d1 both tiers live: translation bitwise-rigid",
            String.format("worst |dy-50| %.2e", dyWorst));
        pB.x -= 30; pB.y -= 50; pB.z += 12;
        // d2 on the PURE-LINK twin (conflict == IDENTITY): with a
        // live conflict the tilt quat is yaw-conjugated, so a yaw
        // move carries the same last-ulp dust every D100 tilted
        // piece always had (documented limit) — the bitwise
        // guarantee belongs to the conflict-free cases
        List<FVDIntegrator.Sample> dA = rA.samples();
        double yawA = pA.yawDeg;
        pA.yawDeg = yawA + 37;
        TrackCompiler.Result rAy = rebuild(cA);
        vBit = true;
        for (int i = 0; i < dA.size(); i++) {
            if (Double.doubleToLongBits(dA.get(i).v())
                    != Double.doubleToLongBits(rAy.samples().get(i).v())
                || Double.doubleToLongBits(dA.get(i).pos().y())
                    != Double.doubleToLongBits(rAy.samples().get(i).pos().y())) {
                vBit = false;
            }
        }
        check(vBit,
            "d2 design tier live, no conflict: yaw move bitwise");
        pA.yawDeg = yawA;

        // ---------- (e/f) RIGID carry through a manual bridge ----------
        Coaster cc = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(hillLanes(), 26));
        cc.pieces.add(hA);
        TrackPiece hB = new TrackPiece(120, 14, 30, 20);
        hB.sections.add(new ForceSection(flatLanes(3), 24));
        cc.pieces.add(hB);
        TrackCompiler.Result rc0 = TrackCompiler.compile(cc, DT);
        BridgeSection hbr = BridgeSection.solve(rc0.spans().get(0).exit(),
            hB.entryPose(),
            rc0.samples().get(rc0.spans().get(0).i1()).v());
        hbr.optMode = BridgeSection.OPT_NONE;   // the UI's new-bridge
                                                // default (D81)
        hA.sections.add(hbr);
        TrackCompiler.Result rcA = rebuild(cc);
        TrackPose beA = hA.entryPose();
        hA.x += 7; hA.y += 11; hA.z -= 5;
        TrackPose afA = hA.entryPose();
        double bx = hB.x, by = hB.y, bz = hB.z;
        cc.carryDownstream(0, beA, afA, rcA.spans());
        check(hB.x == bx + 7 && hB.y == by + 11 && hB.z == bz - 5,
            "e1 carry translation: manual-bridge target moves exactly");
        TrackCompiler.Result rcT = rebuild(cc);
        double worstT = 0;
        for (int i = 0; i < rcA.samples().size(); i++) {
            Vec3 want = rcA.samples().get(i).pos().add(new Vec3(7, 11, -5));
            worstT = Math.max(worstT,
                rcT.samples().get(i).pos().sub(want).length());
        }
        check(worstT < 1e-6, "e2 carry translation: whole assembly rigid",
            String.format("worst %.2e m", worstT));
        hA.x -= 7; hA.y -= 11; hA.z += 5;
        cc.carryDownstream(0, afA, hA.entryPose(), rebuild(cc).spans());
        TrackCompiler.Result rcB = rebuild(cc);
        TrackPose be2 = hA.entryPose();
        hA.editPitch(6);           // locked: conflict tilt
        TrackPose af2 = hA.entryPose();
        cc.carryDownstream(0, be2, af2, rcB.spans());
        TrackCompiler.Result rcR = rebuild(cc);
        Quat dq = af2.orient().mul(be2.orient().conjugate());
        Vec3 a0 = be2.pos();
        double worstP = 0;
        for (int s : new int[]{ 0, 2 }) {
            TrackCompiler.Span sb = rcB.spans().get(s);
            TrackCompiler.Span sr = rcR.spans().get(s);
            int nAl = Math.min(sb.i1() - sb.i0(), sr.i1() - sr.i0());
            for (int k = 0; k <= nAl; k++) {
                Vec3 want = a0.add(dq.rotate(
                    rcB.samples().get(sb.i0() + k).pos().sub(a0)));
                worstP = Math.max(worstP, rcR.samples()
                    .get(sr.i0() + k).pos().sub(want).length());
            }
        }
        check(worstP < 1e-9,
            "f1a carry pitch: BOTH pieces are exact rigid images",
            String.format("worst %.2e m", worstP));
        List<Vec3> oldSpine = new ArrayList<>();
        for (int i = Math.max(0, rcB.spans().get(1).i0() - 1);
                i <= rcB.spans().get(1).i1() + 1
                && i < rcB.samples().size(); i++) {
            oldSpine.add(a0.add(dq.rotate(
                rcB.samples().get(i).pos().sub(a0))));
        }
        double worstSp = 0;
        for (int i = rcR.spans().get(1).i0();
                i <= rcR.spans().get(1).i1(); i++) {
            Vec3 q = rcR.samples().get(i).pos();
            double best = Double.MAX_VALUE;
            for (int k = 0; k + 1 < oldSpine.size(); k++) {
                Vec3 s0 = oldSpine.get(k), s1 = oldSpine.get(k + 1);
                Vec3 d = s1.sub(s0);
                double l2 = d.dot(d);
                double t = l2 < 1e-18 ? 0
                    : Math.max(0, Math.min(1, q.sub(s0).dot(d) / l2));
                best = Math.min(best, q.sub(s0.add(d.scale(t))).length());
            }
            worstSp = Math.max(worstSp, best);
        }
        check(worstSp < 5e-3,
            "f1b carry pitch: the bridge spine is the rigid image",
            String.format("worst nearest-dist %.2e m", worstSp));
        check((Math.abs(hB.pitchDeg) > 1e-9 || Math.abs(hB.rollDeg) > 1e-9)
                && hB.designPitchDeg == 0 && hB.designRollDeg == 0
                && hB.orientConflict(),
            "f2 carried target adopts the conflict on the TRUE tier only",
            String.format("true (%.3f,%.3f) design (0,0)",
                hB.pitchDeg, hB.rollDeg));
        int b0 = rcR.spans().get(2).i0();
        double vSplitB = 0;
        for (int i = b0; i < rcR.samples().size(); i++) {
            vSplitB = Math.max(vSplitB, Math.abs(
                rcR.trueV()[i] - rcR.samples().get(i).v()));
        }
        check(vSplitB > 1,
            "f3 carried target's design/true SPEED split is honest",
            String.format("max %.3f m/s", vSplitB));

        // ---------- (g) yaw carry across a strict seam ----------
        Coaster cs = new Coaster();
        TrackPiece sA = new TrackPiece(0, 21, 0, 0);
        sA.sections.add(new ForceSection(hillLanes(), 26));
        cs.pieces.add(sA);
        TrackPiece sB = new TrackPiece(0, 0, 0, 0);
        sB.sections.add(new ForceSection(flatLanes(3), 24));
        sB.followPrev = true;
        cs.pieces.add(sB);
        TrackCompiler.Result rs0 = TrackCompiler.compile(cs, DT);
        double[] seed = Coaster.eulerOf(Coaster.strictEntry(
            rs0.spans().get(0).exit(), sB).orient());
        sB.sYawDeg = Double.isNaN(seed[0]) ? 0 : seed[0];
        sB.sPitchDeg = seed[1] + 9;
        sB.sRollDeg = Double.isNaN(seed[2]) ? 0 : seed[2];
        TrackCompiler.Result rs1 = rebuild(cs);
        Quat effOld = Coaster.strictEntry(
            rs1.spans().get(0).exit(), sB).orient();
        TrackPose bs1 = sA.entryPose();
        sA.yawDeg += 24;
        TrackPose as1 = sA.entryPose();
        cs.carryDownstream(0, bs1, as1, rs1.spans());
        TrackCompiler.Result rs2 = rebuild(cs);
        Quat dqs = as1.orient().mul(bs1.orient().conjugate());
        Quat effWant = dqs.mul(effOld);
        Quat effNew = Coaster.strictEntry(
            rs2.spans().get(0).exit(), sB).orient();
        double qd = Math.min(
            Math.abs(effNew.w() - effWant.w()) + Math.abs(effNew.x() - effWant.x())
            + Math.abs(effNew.y() - effWant.y()) + Math.abs(effNew.z() - effWant.z()),
            Math.abs(effNew.w() + effWant.w()) + Math.abs(effNew.x() + effWant.x())
            + Math.abs(effNew.y() + effWant.y()) + Math.abs(effNew.z() + effWant.z()));
        check(qd < 1e-9,
            "g  yaw carry across strict seam: follower rides exactly",
            String.format("quat dev %.2e", qd));

        // ---------- (h) porient IO ----------
        Coaster ch = new Coaster();
        TrackPiece ph = new TrackPiece(0, 21, 0, 0);
        ph.sections.add(new ForceSection(flatLanes(4), 26));
        ph.shapeLocked = false;
        ph.designPitchDeg = 3.25;
        ph.designRollDeg = -1.5;
        ph.pitchDeg = 8;
        ch.pieces.add(ph);
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3], null);
        try (PrintWriter pw = new PrintWriter(sw)) {
            DesignIO.save(ch, cos, pw);
        }
        String txt = sw.toString();
        Coaster ch2 = DesignIO.load(new BufferedReader(
            new StringReader(txt))).coaster();
        TrackPiece ph2 = ch2.pieces.get(0);
        check(!ph2.shapeLocked && ph2.designPitchDeg == 3.25
                && ph2.designRollDeg == -1.5 && ph2.pitchDeg == 8,
            "h1 porient round-trips locked + design tier bit-exact");
        String stripped = txt.lines().filter(l -> !l.startsWith("porient"))
            .reduce("", (a, b) -> a + b + "\n");
        Coaster ch3 = DesignIO.load(new BufferedReader(
            new StringReader(stripped))).coaster();
        check(ch3.pieces.get(0).shapeLocked
                && ch3.pieces.get(0).designPitchDeg == 0
                && ch3.pieces.get(0).designRollDeg == 0,
            "h2 no porient line: locked + (0,0) = historic rigid");
        String interim = txt.lines().map(l -> l.startsWith("porient")
                ? "porient 1 1" : l)
            .reduce("", (a, b) -> a + b + "\n");
        Coaster ch4 = DesignIO.load(new BufferedReader(
            new StringReader(interim))).coaster();
        check(!ch4.pieces.get(0).shapeLocked
                && ch4.pieces.get(0).designPitchDeg == 8
                && ch4.pieces.get(0).designRollDeg == 0,
            "h3 D221 interim line (mode 1) migrates: design := true");

        // ---------- (i) march writes never touch the design tier ----
        Coaster ci = new Coaster();
        TrackPiece iA = new TrackPiece(0, 21, 0, 0);
        iA.sections.add(new ForceSection(hillLanes(), 26));
        ci.pieces.add(iA);
        TrackPiece iB = new TrackPiece(130, 12, 25, 15);
        iB.sections.add(new ForceSection(flatLanes(3), 24));
        iB.shapeLocked = false;    // even unlocked: machine bypasses
        ci.pieces.add(iB);
        TrackCompiler.Result ri0 = TrackCompiler.compile(ci, DT);
        BridgeSection ib = BridgeSection.solve(ri0.spans().get(0).exit(),
            iB.entryPose(),
            ri0.samples().get(ri0.spans().get(0).i1()).v());
        iA.sections.add(ib);
        rebuild(ci);
        ib.optMode = BridgeSection.OPT_POS_YAW;
        ib.lockPitch = false;
        ci.optimizeBridge(ib, 1, 40, true);
        check(iB.designPitchDeg == 0 && iB.designRollDeg == 0
                && Math.abs(iB.pitchDeg) > 1e-9,
            "i  march pitch alignment writes TRUE tier only (no loop)",
            String.format("true pitch %.3f design 0", iB.pitchDeg));

        // ---------- (j) button semantics (model level) ----------
        Coaster cj = oneHill();
        TrackPiece pj = cj.pieces.get(0);
        pj.editPitch(9);           // locked conflict
        TrackCompiler.Result rj = rebuild(cj);
        // Real → Design: true := design, shape preserved
        Quat qj = pj.designPose().orient()
            .mul(pj.entryPose().orient().conjugate());
        pj.pitchDeg = pj.designPitchDeg;
        pj.rollDeg = pj.designRollDeg;
        TrackCompiler.Result rj2 = rebuild(cj);
        double devJ = rigidImageDev(rj.samples(), rj2.samples(),
            qj, pj.entryPose().pos());
        check(!pj.orientConflict() && devJ < 1e-9,
            "j1 real→design: conflict closed, shape preserved (rigid)",
            String.format("dev %.2e m", devJ));
        // Design → Real: design := true, re-solves to the pure link
        Coaster ck = oneHill();
        TrackPiece pk = ck.pieces.get(0);
        pk.editPitch(9);
        rebuild(ck);
        pk.designPitchDeg = pk.pitchDeg;
        pk.designRollDeg = pk.rollDeg;
        TrackCompiler.Result rk = rebuild(ck);
        Coaster cl = oneHill();
        TrackPiece pl = cl.pieces.get(0);
        pl.shapeLocked = false;
        pl.editPitch(9);
        TrackCompiler.Result rl = rebuild(cl);
        double devK = 0;
        int nK = Math.min(rk.samples().size(), rl.samples().size());
        for (int i = 0; i < nK; i++) {
            devK = Math.max(devK, rk.samples().get(i).pos()
                .sub(rl.samples().get(i).pos()).length());
        }
        check(!pk.orientConflict() && devK == 0,
            "j2 design→real: bitwise the pure-link shape",
            String.format("dev %.2e m", devK));

        System.out.println();
        System.out.println(fail == 0
            ? "ALL ORIENT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
