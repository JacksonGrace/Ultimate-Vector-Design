import coaster.*;

import java.util.*;

/** D224 gate — ABSOLUTE ORIENTATION DISPLAY + TILTED-SPLIT CONTINUITY
 *  + VERTICAL PASS-THROUGH.
 *
 *  a) fresh piece: displays == deltas bitwise.
 *  b) untilted split at a pitched/banked boundary: geometry bitwise
 *     (the D71/D199 rule), the new piece's boxes read the boundary's
 *     real heading/elevation/bank, deltas exactly 0.
 *  c) LOCKED-conflict piece split: whole track stays put (< 1e-9 m),
 *     new piece carries a conflict whose display == the true boundary.
 *  d) unlocked design-tilted piece split: bitwise.
 *  e) held-angle strict follower split: whole track stays put.
 *  f) decomposeNear: pure yaw carry stays pure yaw; a local pitch
 *     rotation through the pole keeps yaw/roll continuous (pitch > 90).
 *  g) raw pitch drive 80 → 100 through the pole: entry orientation
 *     continuous, display flips (heading +180, elevation 80, bank +180).
 *  h) setDispPitch on the flipped branch keeps the branch.
 *  i) setDispYaw / setDispRoll round-trip on a split (pitched) base.
 */
public class OrientGate2 {

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
        ls.add(lane(new double[][]{ {0, 0}, {2, 40}, {5.5, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> turnLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.5, 2.2}, {3, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, -0.5}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        return ls;
    }

    static Coaster twoSec() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(new ForceSection(hillLanes(), 26));
        p.sections.add(new ForceSection(turnLanes(), 26));
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

    /** The split's ONLY structural change: the new piece is its own
     *  run, so its entry sample is no longer the dropped continuation
     *  sample — the boundary sample appears TWICE. Map new → old
     *  indices around that duplicate (cut = old boundary index). */
    static List<FVDIntegrator.Sample> unsplit(List<FVDIntegrator.Sample> b,
                                              int cut) {
        List<FVDIntegrator.Sample> out = new ArrayList<>(b);
        if (cut + 1 < out.size()) out.remove(cut + 1);
        return out;
    }

    static double maxDev(List<FVDIntegrator.Sample> a,
                         List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return Double.MAX_VALUE;
        double w = 0;
        for (int i = 0; i < a.size(); i++) {
            w = Math.max(w, a.get(i).pos().sub(b.get(i).pos()).length());
            w = Math.max(w, a.get(i).U().sub(b.get(i).U()).length());
        }
        return w;
    }

    static boolean bitwise(List<FVDIntegrator.Sample> a,
                           List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Vec3 pa = a.get(i).pos(), pb = b.get(i).pos();
            if (Double.doubleToLongBits(pa.x()) != Double.doubleToLongBits(pb.x())
                || Double.doubleToLongBits(pa.y()) != Double.doubleToLongBits(pb.y())
                || Double.doubleToLongBits(pa.z()) != Double.doubleToLongBits(pb.z())) {
                return false;
            }
        }
        return true;
    }

    static double angDiff(double a, double b) {
        return Math.abs(TrackPiece.wrap180(a - b));
    }

    public static void main(String[] args) throws Exception {
        // ---------- (a) fresh piece: display == delta bitwise ----------
        TrackPiece fp = new TrackPiece(0, 21, 0, 0);
        fp.yawDeg = 12.5; fp.pitchDeg = -7.25; fp.rollDeg = 33;
        check(fp.dispYaw() == 12.5 && fp.dispPitch() == -7.25
                && fp.dispRoll() == 33 && fp.rawAbsPitch() == -7.25,
            "a  identity base: displays are the deltas bitwise");

        // ---------- (b) untilted split at pitched/banked boundary ------
        Coaster cb = twoSec();
        TrackCompiler.Result rb0 = rebuild(cb);
        TrackPose E = rb0.spans().get(0).exit();
        double[] eul = Coaster.eulerOf(E.orient());
        cb.splitPiece(0, 1, rb0.spans().get(0));
        TrackCompiler.Result rb1 = rebuild(cb);
        TrackPiece nb = cb.pieces.get(1);
        check(bitwise(rb0.samples(), unsplit(rb1.samples(), rb0.spans().get(0).i1())),
            "b1 untilted split: geometry BITWISE");
        check(nb.yawDeg == 0 && nb.pitchDeg == 0 && nb.rollDeg == 0,
            "b2 untilted split: deltas exactly 0 (historic path)");
        check(angDiff(nb.dispYaw(), eul[0]) < 1e-9
                && Math.abs(nb.dispPitch() - eul[1]) < 1e-9
                && angDiff(nb.dispRoll(), eul[2]) < 1e-9,
            "b3 untilted split: boxes read the REAL boundary orientation",
            String.format("disp (%.3f, %.3f, %.3f) vs euler (%.3f, %.3f, %.3f)",
                nb.dispYaw(), nb.dispPitch(), nb.dispRoll(),
                eul[0], eul[1], eul[2]));
        // (i) round-trips on the pitched base
        double y0 = nb.dispYaw(), p0 = nb.dispPitch(), r0 = nb.dispRoll();
        nb.setDispYaw(y0 + 15);
        nb.setDispPitch(p0 - 6, true);
        nb.setDispRoll(r0 + 20, true);
        double[] e2 = Coaster.eulerOf(nb.entryPose().orient());
        check(angDiff(e2[0], y0 + 15) < 1e-9
                && Math.abs(e2[1] - (p0 - 6)) < 1e-9
                && angDiff(e2[2], r0 + 20) < 1e-9
                && angDiff(nb.dispYaw(), y0 + 15) < 1e-9
                && Math.abs(nb.dispPitch() - (p0 - 6)) < 1e-9
                && angDiff(nb.dispRoll(), r0 + 20) < 1e-9,
            "i  setDisp* on a pitched/banked base: entry euler == typed",
            String.format("euler (%.4f, %.4f, %.4f)", e2[0], e2[1], e2[2]));

        // ---------- (c) LOCKED-conflict split -------------------------
        Coaster cc = twoSec();
        TrackPiece pc = cc.pieces.get(0);
        pc.editPitch(12);
        pc.editRoll(-8);
        TrackCompiler.Result rc0 = rebuild(cc);
        TrackPose Ec = rc0.spans().get(0).exit();
        double[] eulc = Coaster.eulerOf(Ec.orient());
        cc.splitPiece(0, 1, rc0.spans().get(0));
        TrackCompiler.Result rc1 = rebuild(cc);
        TrackPiece nc = cc.pieces.get(1);
        double devC = maxDev(rc0.samples(), unsplit(rc1.samples(), rc0.spans().get(0).i1()));
        check(devC < 1e-9,
            "c1 locked-conflict split: track stays put",
            String.format("max dev %.2e", devC));
        check(nc.orientConflict()
                && angDiff(nc.dispYaw(), eulc[0]) < 1e-9
                && Math.abs(nc.dispPitch() - eulc[1]) < 1e-9
                && angDiff(nc.dispRoll(), eulc[2]) < 1e-9,
            "c2 locked-conflict split: new piece shows the REAL boundary "
            + "and carries the conflict",
            String.format("disp (%.3f, %.3f, %.3f) design (%.3f, %.3f)",
                nc.dispYaw(), nc.dispPitch(), nc.dispRoll(),
                nc.dispDesignPitch(), nc.dispDesignRoll()));
        // and Real→Design on the new piece un-rotates it (shape kept)
        int i0 = rc1.spans().get(1).i0(), i1 = rc1.spans().get(1).i1();
        Quat qj = nc.designPose().orient()
            .mul(nc.entryPose().orient().conjugate());
        Vec3 piv = nc.entryPose().pos();
        nc.pitchDeg = nc.designPitchDeg;
        nc.rollDeg = nc.designRollDeg;
        TrackCompiler.Result rc2 = rebuild(cc);
        double devJ = 0;
        for (int i = i0; i <= i1; i++) {
            Vec3 want = piv.add(qj.rotate(rc1.samples().get(i).pos().sub(piv)));
            devJ = Math.max(devJ, rc2.samples().get(i).pos().sub(want).length());
        }
        check(devJ < 1e-9, "c3 new piece Real→Design: rigid un-rotation",
            String.format("dev %.2e", devJ));

        // ---------- (d) unlocked design-tilted split: bitwise ----------
        Coaster cd = twoSec();
        TrackPiece pd = cd.pieces.get(0);
        pd.shapeLocked = false;
        pd.editPitch(9);
        pd.editRoll(-5);
        TrackCompiler.Result rd0 = rebuild(cd);
        cd.splitPiece(0, 1, rd0.spans().get(0));
        TrackCompiler.Result rd1 = rebuild(cd);
        check(bitwise(rd0.samples(), unsplit(rd1.samples(), rd0.spans().get(0).i1()))
                && cd.pieces.get(1).pitchDeg == 0,
            "d  unlocked design-tilted split: BITWISE, no conflict");

        // ---------- (e) held-angle follower split ---------------------
        Coaster ce = new Coaster();
        TrackPiece eA = new TrackPiece(0, 21, 0, 0);
        eA.sections.add(new ForceSection(hillLanes(), 26));
        ce.pieces.add(eA);
        TrackPiece eB = new TrackPiece(0, 0, 0, 0);
        eB.sections.add(new ForceSection(turnLanes(), 24));
        eB.sections.add(new ForceSection(hillLanes(), 24));
        eB.followPrev = true;
        ce.pieces.add(eB);
        TrackCompiler.Result re0 = TrackCompiler.compile(ce, DT);
        double[] seed = Coaster.eulerOf(Coaster.strictEntry(
            re0.spans().get(0).exit(), eB).orient());
        eB.sYawDeg = Double.isNaN(seed[0]) ? 0 : seed[0];
        eB.sPitchDeg = seed[1] + 9;
        eB.sRollDeg = (Double.isNaN(seed[2]) ? 0 : seed[2]) - 6;
        TrackCompiler.Result re1 = rebuild(ce);
        ce.splitPiece(1, 1, re1.spans().get(1));
        TrackCompiler.Result re2 = rebuild(ce);
        double devE = maxDev(re1.samples(), unsplit(re2.samples(), re1.spans().get(1).i1()));
        check(devE < 1e-9, "e  held-angle follower split: track stays put",
            String.format("max dev %.2e", devE));

        // ---------- (f) decomposeNear ---------------------------------
        Quat base = Quat.IDENTITY;
        Quat yawT = Quat.aboutY(170).mul(base);
        double[] dY = TrackPiece.decomposeNear(base, yawT, 0, 0, 0);
        check(dY != null && Math.abs(dY[0] - 170) < 1e-9
                && Math.abs(dY[1]) < 1e-9 && Math.abs(dY[2]) < 1e-9,
            "f1 decomposeNear: 170° yaw carry stays pure yaw",
            String.format("(%.3f, %.3f, %.3f)", dY[0], dY[1], dY[2]));
        // local pitch rotation through the pole: 95° about R
        Vec3 R = base.T().cross(base.U()).scale(-1);   // U×T = R
        R = base.U().cross(base.T());
        Quat over = Quat.aboutAxis(R.scale(1 / R.length()),
            Math.toRadians(95)).mul(base);
        double[] dO = TrackPiece.decomposeNear(base, over, 0, 0, 0);
        // whichever sign convention R has, |pitch| must run PAST 90 with
        // yaw and roll continuous (near 0)
        check(dO != null && Math.abs(Math.abs(dO[1]) - 95) < 1e-6
                && Math.abs(dO[0]) < 1e-6 && Math.abs(dO[2]) < 1e-6,
            "f2 decomposeNear: pole crossing keeps yaw/roll, pitch past 90",
            String.format("(%.3f, %.3f, %.3f)", dO[0], dO[1], dO[2]));
        // and it reproduces the orientation exactly
        TrackPiece tp = new TrackPiece(0, 0, 0, 0);
        tp.yawDeg = dO[0]; tp.pitchDeg = dO[1]; tp.rollDeg = dO[2];
        Quat rep = tp.entryPose().orient();
        double qd = Math.min(
            Math.abs(rep.w() - over.w()) + Math.abs(rep.x() - over.x())
            + Math.abs(rep.y() - over.y()) + Math.abs(rep.z() - over.z()),
            Math.abs(rep.w() + over.w()) + Math.abs(rep.x() + over.x())
            + Math.abs(rep.y() + over.y()) + Math.abs(rep.z() + over.z()));
        check(qd < 1e-9, "f3 decomposeNear triple reproduces the target",
            String.format("quat dev %.2e", qd));

        // ---------- (g) raw drive through the pole ---------------------
        TrackPiece g = new TrackPiece(0, 0, 0, 0);
        g.setRawAbsPitch(80, true);
        Quat q80 = g.entryPose().orient();
        g.setRawAbsPitch(100, true);
        Quat q100 = g.entryPose().orient();
        // continuity: q100 == aboutAxis(r, 100°) with r the pitch axis
        Vec3 t0 = Quat.IDENTITY.T();
        Vec3 r = new Vec3(-t0.z(), 0, t0.x());
        Quat want = Quat.aboutAxis(r.scale(1 / r.length()),
            Math.toRadians(100));
        double gd = Math.min(
            Math.abs(q100.w() - want.w()) + Math.abs(q100.x() - want.x())
            + Math.abs(q100.y() - want.y()) + Math.abs(q100.z() - want.z()),
            Math.abs(q100.w() + want.w()) + Math.abs(q100.x() + want.x())
            + Math.abs(q100.y() + want.y()) + Math.abs(q100.z() + want.z()));
        check(gd < 1e-12 && g.pitchDeg == 100,
            "g1 raw pitch drive 80→100: continuous rotation, delta raw 100",
            String.format("quat dev %.2e", gd));
        double[] eg = Coaster.eulerOf(q100);
        check(Math.abs(g.dispPitch() - 80) < 1e-9
                && angDiff(g.dispYaw(), 180) < 1e-9
                && angDiff(g.dispRoll(), 180) < 1e-9
                && Math.abs(eg[1] - 80) < 1e-9
                && angDiff(eg[0], g.dispYaw()) < 1e-9
                && angDiff(eg[2], g.dispRoll()) < 1e-9,
            "g2 past the pole the display flips: heading+180, elev 80, bank+180 "
            + "== eulerOf(entry)",
            String.format("disp (%.2f, %.2f, %.2f) euler (%.2f, %.2f, %.2f)",
                g.dispYaw(), g.dispPitch(), g.dispRoll(), eg[0], eg[1], eg[2]));
        // design tier followed in lockstep (unlocked): none here — locked
        check(g.designPitchDeg == 0 && g.orientConflict(),
            "g3 locked drive: design tier untouched, conflict open");

        // ---------- (h) setDispPitch on the flipped branch ---------------
        g.setDispPitch(70, true);
        check(Math.abs(g.pitchDeg - 110) < 1e-9
                && Math.abs(g.dispPitch() - 70) < 1e-9
                && angDiff(g.dispYaw(), 180) < 1e-9,
            "h  setDispPitch on the flipped branch keeps heading, raw → 110",
            String.format("raw %.3f disp %.3f yaw %.3f", g.pitchDeg,
                g.dispPitch(), g.dispYaw()));
        // unlocked lockstep through the pole: design rides raw
        TrackPiece u = new TrackPiece(0, 0, 0, 0);
        u.shapeLocked = false;
        u.setRawAbsPitch(85, true);
        u.setRawAbsPitch(95, true);
        check(u.designPitchDeg == 95 && !u.orientConflict()
                && Math.abs(u.dispDesignPitch() - 85) < 1e-9,
            "h2 unlocked raw drive: design tier in lockstep past the pole");

        System.out.println();
        System.out.println(fail == 0
            ? "ALL ORIENT-2 GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
