package uvd;

import java.util.*;

/** D247 gate (v19.18) — STRICT-SEAM FOLLOWERS WITH A LIVE DESIGN
 *  TIER. The compiler's follower branch generates through
 *  designPose()/designCanon when the design deltas are non-zero
 *  (zero deltas = the historic genPose/pieceCanon path bitwise), so a
 *  follower's DESIGN orientation is its designPose and its REAL one
 *  the follow contract; the gap is the seam's conflict tilt.
 *
 *  1) ATTACH of a design-tilted (6, −9) + true-conflicted piece via
 *     connectStrict(target, floating, false, true, true): the
 *     follower's samples in its OWN entry frame equal the pre-attach
 *     ones (< 1e-9), its design elevation/bank equal the pre-attach
 *     designPose's, its real elevation/bank equal the pre-attach true
 *     ones, and the seam reads as an orientation conflict.
 *  2) a (0,0)-design attach of a TRUE-tilted piece is BITWISE the
 *     pre-D247 build (checksum recorded from commit f84061a's core,
 *     RefBits probe) and a second compile is bitwise the first.
 *  3) seamRealToDesign: the follower is a rigid image (< 1e-9), the
 *     seam conflict closes, pitch+roll lock, yaw untouched.
 *  4) bakeFollow (the seam's Design → Real): samples bitwise the
 *     pure-link twin (a detached piece at the follow frame); the
 *     public designToRealConvert follower branch is the same bake.
 *  5) seamDesignLockstep (the model half of GraphEditor.seamEdit)
 *     with shapeLocked=false: the design tier moves by exactly the
 *     Euler delta (1e-9) and the design/real ELEVATION + BANK gaps
 *     stay constant through the rebuild; locked = the design tier is
 *     untouched bitwise.
 *  6) run-head translate with a follower active: every sample +Δ
 *     (< 1e-9), frames bitwise; a one-section follower keeps speeds
 *     + felt bitwise with zero vDesign writes, a real-anchored
 *     continuation to the D199-accepted tilted-piece dust.
 *  7) an all-zero-delta follower with held angles compiles BITWISE
 *     the pre-D247 build (recorded checksum) and reads the designed=
 *     false fork (designPose bits == genPose bits).
 *  8) detachStrictSeam PRESERVES the conflict (D247 review fix):
 *     attach → detach leaves the piece's samples in place (< 1e-9,
 *     end-aligned) with orientConflict() true and the design tier
 *     untouched — for a design-tilted and a held-angle follower.
 *  9) the all-Preserve Design → Real on a follower (the model half of
 *     FvdDesigner.designToRealDirect = bakeFollow) closes the seam
 *     tilt to the identity on an absolute-bank follower with a design
 *     pitch, deterministically.
 * 10) seamDesignLockstep's POLE GUARD: a held pitch 85 → 95 (a
 *     canonical heading/bank flip) returns false and leaves the tier
 *     bitwise; 80 → 89 applies.
 *
 *  Package coaster (like DecompProbe): bakeFollow and
 *  seamDesignLockstep are package-private. Run: java -cp
 *  "target/classes;gates/out" uvd.SeamShapeGate
 */
public class SeamShapeGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    // ---- recorded from the PRE-D247 core (commit f84061a; the
    // ---- scratch RefBits probe printed identical values on HEAD and
    // ---- the D247 tree). Re-record only with a deliberate physics
    // ---- change — the designed=false follower path is meant to stay
    // ---- bitwise-historic.
    static final int  REF_ATTACH00_N    = 851;
    static final long REF_ATTACH00_SUM  = 4431353221753639906L;
    static final int  REF_FOLLOWER00_N  = 1401;
    static final long REF_FOLLOWER00_SUM = -115641838315780675L;

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

    static long mix(long h, double d) {
        h ^= Double.doubleToLongBits(d);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return h;
    }

    /** Every sample's pos/T/U/v/Gv/Gl bits folded into one long. */
    static long checksum(TrackCompiler.Result r) {
        long h = 1234567L;
        for (FVDIntegrator.Sample s : r.samples()) {
            h = mix(h, s.pos().x()); h = mix(h, s.pos().y()); h = mix(h, s.pos().z());
            h = mix(h, s.T().x()); h = mix(h, s.T().y()); h = mix(h, s.T().z());
            h = mix(h, s.U().x()); h = mix(h, s.U().y()); h = mix(h, s.U().z());
            h = mix(h, s.v()); h = mix(h, s.Gv()); h = mix(h, s.Gl());
        }
        return h;
    }

    static boolean bitsEq(Vec3 a, Vec3 b) {
        return Double.doubleToLongBits(a.x()) == Double.doubleToLongBits(b.x())
            && Double.doubleToLongBits(a.y()) == Double.doubleToLongBits(b.y())
            && Double.doubleToLongBits(a.z()) == Double.doubleToLongBits(b.z());
    }

    static boolean bitsEq(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static double quatDev(Quat a, Quat b) {
        return Math.min(
            Math.abs(a.w() - b.w()) + Math.abs(a.x() - b.x())
            + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()),
            Math.abs(a.w() + b.w()) + Math.abs(a.x() + b.x())
            + Math.abs(a.y() + b.y()) + Math.abs(a.z() + b.z()));
    }

    /** Piece p's samples (all its spans) as a sub-list. */
    static List<FVDIntegrator.Sample> pieceSamples(Coaster c,
            TrackCompiler.Result r, int p) {
        int k0 = 0;
        for (int q = 0; q < p; q++) k0 += c.pieces.get(q).sections.size();
        int k1 = k0 + c.pieces.get(p).sections.size() - 1;
        return r.samples().subList(r.spans().get(k0).i0(),
            r.spans().get(k1).i1() + 1);
    }

    /** Samples expressed in a frame: local pos = q⁻¹·(pos − origin),
     *  local U = q⁻¹·U. */
    static List<Vec3[]> inFrame(List<FVDIntegrator.Sample> s, Vec3 origin,
                                Quat q) {
        Quat inv = q.conjugate();
        List<Vec3[]> out = new ArrayList<>();
        for (FVDIntegrator.Sample sm : s) {
            out.add(new Vec3[]{ inv.rotate(sm.pos().sub(origin)),
                inv.rotate(sm.U()) });
        }
        return out;
    }

    /** Max deviation between two local-frame sample lists ALIGNED AT
     *  THE END (a follower drops its duplicated entry sample — the
     *  D84/v17.1 continuation rule — so counts differ by one). */
    static double endAlignedDev(List<Vec3[]> a, List<Vec3[]> b) {
        int n = Math.min(a.size(), b.size());
        if (Math.abs(a.size() - b.size()) > 1) return Double.MAX_VALUE;
        double w = 0;
        for (int i = 0; i < n; i++) {
            Vec3[] x = a.get(a.size() - n + i), y = b.get(b.size() - n + i);
            w = Math.max(w, x[0].sub(y[0]).length());
            w = Math.max(w, x[1].sub(y[1]).length());
        }
        return w;
    }

    /** Max world-frame pos/T/U deviation between two sample lists
     *  ALIGNED AT THE END (a follower drops its duplicated entry
     *  sample, a detached piece keeps it — counts differ by one). */
    static double worldDevEndAligned(List<FVDIntegrator.Sample> a,
                                     List<FVDIntegrator.Sample> b) {
        int n = Math.min(a.size(), b.size());
        if (Math.abs(a.size() - b.size()) > 1) return Double.MAX_VALUE;
        double w = 0;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample x = a.get(a.size() - n + i);
            FVDIntegrator.Sample y = b.get(b.size() - n + i);
            w = Math.max(w, x.pos().sub(y.pos()).length());
            w = Math.max(w, x.T().sub(y.T()).length());
            w = Math.max(w, x.U().sub(y.U()).length());
        }
        return w;
    }

    /** Fixture (2): a (0,0)-design, TRUE-tilted floating piece
     *  attached via connectStrict(target, floating, false, true, true). */
    static Coaster fixAttach00() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 14);
        a.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(60, 15, 10, 20);
        b.sections.add(new ForceSection(turnLanes(), 24));
        b.orientMode = TrackPiece.OrientMode.REAL_ONLY;    // D248: locked is explicit now
        b.editPitch(10);      // locked: true tilt, design (0,0)
        b.editRoll(-7);
        c.pieces.add(b);
        c.connectStrict(0, 1, false, true, true);
        return c;
    }

    /** Fixture (7)/(3)/(5)/(6): an all-zero-delta follower with HELD
     *  pitch/roll angles off the seam (a conflicted seam on the
     *  designed=false path). `twoSec` = the follower has two sections. */
    static Coaster fixFollower00(boolean twoSec) {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(0, 0, 0, 0);
        b.sections.add(new ForceSection(turnLanes(), 24));
        if (twoSec) b.sections.add(new ForceSection(hillLanes(), 24));
        b.followPrev = true;
        c.pieces.add(b);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        double[] seed = Coaster.eulerOf(Coaster.strictEntry(
            r0.spans().get(0).exit(), b).orient());
        b.sYawDeg = Double.isNaN(seed[0]) ? 0 : seed[0];
        b.sPitchDeg = seed[1] + 9;
        b.sRollDeg = (Double.isNaN(seed[2]) ? 0 : seed[2]) - 6;
        return c;
    }

    static TrackPose prevExit(TrackCompiler.Result r) {
        return r.spans().get(0).exit();   // piece A is one section
    }

    public static void main(String[] args) throws Exception {
        // ---------- (1) attach of a design-tilted, conflicted piece ----
        {
            Coaster c = new Coaster();
            TrackPiece a = new TrackPiece(0, 21, 0, 14);
            a.sections.add(new ForceSection(hillLanes(), 26));
            c.pieces.add(a);
            TrackPiece b = new TrackPiece(60, 15, 10, 20);
            b.sections.add(new ForceSection(turnLanes(), 24));
            b.sections.add(new ForceSection(hillLanes(), 24));
            b.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            b.editPitch(6);            // design == true == (6, −9)
            b.editRoll(-9);
            b.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            b.editPitch(10);           // true pitch 10 over design 6
            c.pieces.add(b);
            check(b.designPitchDeg == 6 && b.designRollDeg == -9
                    && b.pitchDeg == 10 && b.orientConflict(),
                "1a fixture: design (6, −9), true (10, −9), conflicted");
            TrackCompiler.Result r0 = rebuild(c);
            TrackPose preE = b.entryPose();
            double[] dPre = Coaster.eulerOf(b.designPose().orient());
            double[] tPre = Coaster.eulerOf(preE.orient());
            List<Vec3[]> locPre = inFrame(pieceSamples(c, r0, 1),
                preE.pos(), preE.orient());
            int np = c.connectStrict(0, 1, false, true, true);
            TrackCompiler.Result r1 = rebuild(c);
            TrackPose eff = Coaster.strictEntry(prevExit(r1), b);
            List<Vec3[]> locPost = inFrame(pieceSamples(c, r1, 1),
                eff.pos(), eff.orient());
            double dev = endAlignedDev(locPre, locPost);
            check(np == 1 && dev < 1e-9,
                "1b attach: follower's samples in its own entry frame == "
                + "pre-attach", String.format("dev %.2e m (n %d vs %d)",
                    dev, locPre.size(), locPost.size()));
            double[] dPost = Coaster.eulerOf(b.designPose().orient());
            double[] tPost = Coaster.eulerOf(eff.orient());
            check(Math.abs(dPost[1] - dPre[1]) < 1e-12
                    && Math.abs(TrackPiece.wrap180(dPost[2] - dPre[2])) < 1e-12
                    && b.designPitchDeg == 6 && b.designRollDeg == -9,
                "1c attach: design elevation/bank == pre-attach designPose's",
                String.format("elev %.4f bank %.4f", dPost[1], dPost[2]));
            check(Math.abs(tPost[1] - tPre[1]) < 1e-9
                    && Math.abs(TrackPiece.wrap180(tPost[2] - tPre[2])) < 1e-9
                    && b.sLockPitch && b.sLockRoll && !b.sLockYaw,
                "1d attach: held real elevation/bank == pre-attach true "
                + "(yaw follows)", String.format("elev %.4f bank %.4f",
                    tPost[1], tPost[2]));
            Quat tq = c.seamConflictTilt(1, prevExit(r1));
            check(Coaster.isSeamOrientConflict(tq),
                "1e attach: the seam reads as an ORIENTATION conflict",
                String.format("tilt hypot(x,z) %.3e", Math.hypot(tq.x(), tq.z())));
        }

        // ---------- (2) (0,0)-design attach bitwise the pre-change build
        {
            Coaster c = fixAttach00();
            TrackCompiler.Result r = rebuild(c);
            long s1 = checksum(r);
            check(r.samples().size() == REF_ATTACH00_N && s1 == REF_ATTACH00_SUM,
                "2a (0,0)-design attach of a true-tilted piece: BITWISE the "
                + "pre-D247 build", String.format("n %d sum %d", r.samples().size(), s1));
            long s2 = checksum(rebuild(c));
            check(s1 == s2, "2b second compile bitwise the first");
            TrackPiece b = c.pieces.get(1);
            check(Coaster.isSeamOrientConflict(c.seamConflictTilt(1, prevExit(r)))
                    && b.designPitchDeg == 0 && b.designRollDeg == 0,
                "2c the attached true tilt is a seam conflict, design (0,0)");
        }

        // ---------- (3) seamRealToDesign ------------------------------
        // ONE-section follower (the OrientGate j1 / OrientGate2 c3
        // shape): a second section constructed at 24 m/s lands REAL-
        // anchored (D101/D237 — its vDesign sits nearer the true seam
        // speed than the design end) and then honestly chases Pass D's
        // true speed, which a rigid ROTATION changes through the
        // heights — that re-solve is the reconcile's job, not this
        // op's (probed: 9 m of tail on the two-section fixture).
        {
            Coaster c = fixFollower00(false);
            TrackPiece b = c.pieces.get(1);
            TrackCompiler.Result r0 = rebuild(c);
            check(Coaster.isSeamOrientConflict(c.seamConflictTilt(1, prevExit(r0))),
                "3a held +9/−6 follower: seam conflict open");
            Quat effOld = Coaster.strictEntry(prevExit(r0), b).orient();
            double yaw0 = b.sYawDeg;
            boolean lockYaw0 = b.sLockYaw;
            List<FVDIntegrator.Sample> before = new ArrayList<>(pieceSamples(c, r0, 1));
            boolean ok = c.seamRealToDesign(1);
            TrackCompiler.Result r1 = rebuild(c);
            Quat effNew = Coaster.strictEntry(prevExit(r1), b).orient();
            Quat q = effNew.mul(effOld.conjugate());
            Vec3 piv = prevExit(r1).pos();
            List<FVDIntegrator.Sample> after = pieceSamples(c, r1, 1);
            double dev = 0;
            int n = Math.min(before.size(), after.size());
            for (int i = 0; i < n; i++) {
                Vec3 want = piv.add(q.rotate(before.get(i).pos().sub(piv)));
                dev = Math.max(dev, after.get(i).pos().sub(want).length());
                Vec3 wantU = q.rotate(before.get(i).U());
                dev = Math.max(dev, after.get(i).U().sub(wantU).length());
            }
            check(ok && before.size() == after.size() && dev < 1e-9,
                "3b seamRealToDesign: follower is a RIGID image",
                String.format("dev %.2e m", dev));
            Quat tq = c.seamConflictTilt(1, prevExit(r1));
            check(!Coaster.isSeamOrientConflict(tq),
                "3c seamRealToDesign: seam conflict CLOSED (heading-only tilt)",
                String.format("hypot(x,z) %.2e", Math.hypot(tq.x(), tq.z())));
            double[] g = Coaster.eulerOf(b.designPose().orient());
            check(b.sLockPitch && b.sLockRoll && b.sPitchDeg == g[1]
                    && b.sRollDeg == g[2] && b.sYawDeg == yaw0
                    && b.sLockYaw == lockYaw0,
                "3d seamRealToDesign: pitch+roll lock at the design angles, "
                + "yaw untouched");
        }

        // ---------- (4) bakeFollow = the seam's Design → Real ----------
        {
            Coaster c = fixFollower00(false);
            TrackPiece b = c.pieces.get(1);
            TrackCompiler.Result r0 = rebuild(c);
            boolean ok = c.bakeFollow(1, r0);
            TrackCompiler.Result r1 = rebuild(c);
            Quat tq = c.seamConflictTilt(1, prevExit(r1));
            check(ok && b.followPrev && b.sLockPitch && b.sLockRoll
                    && b.designPitchDeg == 0 && b.pitchDeg == 0
                    && Math.abs(tq.w()) > 1 - 1e-14,
                "4a bakeFollow: still following, both tiers zero, seam tilt "
                + "== identity", String.format("|w| − 1 = %.2e", Math.abs(tq.w()) - 1));
            // the pure-link twin: a DETACHED piece at the follow frame
            Coaster t = new Coaster();
            TrackPiece ta = new TrackPiece(0, 21, 0, 0);
            ta.sections.add(new ForceSection(hillLanes(), 26));
            t.pieces.add(ta);
            TrackPiece tb = new TrackPiece(b.x, b.y, b.z, 0);
            tb.baseOrient = b.baseOrient;
            tb.sections.add(new ForceSection(turnLanes(), 24));
            t.pieces.add(tb);
            TrackCompiler.Result rt = rebuild(t);
            List<FVDIntegrator.Sample> fs = pieceSamples(c, r1, 1);
            List<FVDIntegrator.Sample> ts = pieceSamples(t, rt, 1);
            int n = Math.min(fs.size(), ts.size());
            boolean bit = Math.abs(fs.size() - ts.size()) <= 1;
            for (int i = 0; i < n && bit; i++) {
                FVDIntegrator.Sample x = fs.get(fs.size() - n + i);
                FVDIntegrator.Sample y = ts.get(ts.size() - n + i);
                bit = bitsEq(x.pos(), y.pos()) && bitsEq(x.T(), y.T())
                    && bitsEq(x.U(), y.U()) && bitsEq(x.Gv(), y.Gv())
                    && bitsEq(x.Gl(), y.Gl());
            }
            check(bit, "4b bakeFollow: samples BITWISE the pure-link twin "
                + "(detached piece at the follow frame)",
                String.format("n %d vs %d", fs.size(), ts.size()));
            // the PUBLIC route: designToRealConvert on a follower with
            // no absolute lanes == the same bake (D4)
            Coaster d = fixFollower00(false);
            rebuild(d);
            boolean ok2 = d.designToRealConvert(1, false, false, DT, 0.1, 0.01);
            TrackCompiler.Result rd = rebuild(d);
            check(ok2 && d.pieces.get(1).followPrev
                    && checksum(rd) == checksum(r1),
                "4c designToRealConvert on a follower == bakeFollow (bitwise)");
        }

        // ---------- (5) seamDesignLockstep (seamEdit's model half) ----
        {
            Coaster c = fixFollower00(true);
            TrackPiece b = c.pieces.get(1);
            TrackCompiler.Result r0 = rebuild(c);
            TrackPose ex = prevExit(r0);
            // LOCKED (explicit since D248 — the default is unlocked
            // now): the design tier never moves
            b.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            double[] bL = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            b.sPitchDeg += 7;
            b.sRollDeg -= 4;
            double[] aL = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            Coaster.seamDesignLockstep(b, bL, aL);
            check(bitsEq(b.designPitchDeg, 0.0) && bitsEq(b.designRollDeg, 0.0),
                "5a shape LOCKED: seam edit leaves the design tier bitwise");
            b.sPitchDeg -= 7;
            b.sRollDeg += 4;
            // UNLOCKED: design moves by exactly the Euler delta, so the
            // design/real ELEVATION and BANK differences stay constant
            // (the decided Euler-additive lockstep — editPitch/editRoll's
            // D222 invariance on the seam; the quaternion tilt itself
            // is constant only to second order when BOTH differences
            // are non-zero, exactly like a free piece's two tiers)
            b.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            double[] bU = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            double[] dB = Coaster.eulerOf(b.designPose().orient());
            double gapE0 = bU[1] - dB[1];
            double gapB0 = TrackPiece.wrap180(bU[2] - dB[2]);
            b.sPitchDeg += 7;
            b.sRollDeg -= 4;
            double[] aU = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            Coaster.seamDesignLockstep(b, bU, aU);
            double[] dA = Coaster.eulerOf(b.designPose().orient());
            check(Math.abs(b.designPitchDeg - 7) < 1e-9
                    && Math.abs(TrackPiece.wrap180(b.designRollDeg + 4)) < 1e-9
                    && Math.abs(dA[1] - dB[1] - 7) < 1e-9
                    && Math.abs(TrackPiece.wrap180(dA[2] - dB[2] + 4)) < 1e-9,
                "5b shape UNLOCKED: design tier (and its readout) moved by "
                + "the Euler delta (+7, −4)", String.format("design (%.6f, %.6f)",
                    b.designPitchDeg, b.designRollDeg));
            double gapE1 = aU[1] - dA[1];
            double gapB1 = TrackPiece.wrap180(aU[2] - dA[2]);
            check(Math.abs(gapE1 - gapE0) < 1e-9
                    && Math.abs(TrackPiece.wrap180(gapB1 - gapB0)) < 1e-9,
                "5c shape UNLOCKED: design/real elevation + bank gaps CONSTANT",
                String.format("elev gap %.6f→%.6f bank gap %.6f→%.6f",
                    gapE0, gapE1, gapB0, gapB1));
            // and the compile generates through the moved design tier:
            // the seam still reads as the SAME Euler conflict after the
            // rebuild (the tilt has a horizontal component)
            TrackCompiler.Result r1 = rebuild(c);
            double[] aR = Coaster.eulerOf(Coaster.strictEntry(prevExit(r1), b).orient());
            double[] dR = Coaster.eulerOf(b.designPose().orient());
            check(Math.abs(aR[1] - dR[1] - gapE0) < 1e-9
                    && Math.abs(TrackPiece.wrap180(aR[2] - dR[2] - gapB0)) < 1e-9
                    && Coaster.isSeamOrientConflict(c.seamConflictTilt(1, prevExit(r1))),
                "5d after the rebuild the seam's Euler gaps are the pre-edit ones",
                String.format("elev gap %.6f bank gap %.6f", aR[1] - dR[1],
                    TrackPiece.wrap180(aR[2] - dR[2])));
        }

        // ---------- (6) run-head translate with a follower active ----
        // 6a ONE-section follower: positions +Δ (< 1e-9 — the follower's
        //    anchor is the upstream WORLD exit, so the translation add
        //    is not bit-associative; D199's own bound), frames, design
        //    speeds and felt BITWISE, no vDesign writes.
        // 6b TWO-section follower whose second section is REAL-anchored
        //    (see (3)): the same rigidity, with the design speed of the
        //    real-anchored continuation to 1e-9 — it reads Pass D's
        //    trueV, which carries the D199-accepted tilted-piece world-
        //    delta dust (a follower with a non-identity seam tilt IS a
        //    tilted piece).
        for (int variant = 0; variant < 2; variant++) {
            boolean twoSec = variant == 1;
            Coaster c = fixFollower00(twoSec);
            TrackPiece a = c.pieces.get(0);
            TrackCompiler.Result r0 = rebuild(c);
            double[] vd0 = new double[3];
            int k = 0;
            for (TrackPiece p : c.pieces) {
                for (Section s : p.sections) vd0[k++] = s.vDesign();
            }
            TrackPose bef = a.entryPose();
            a.x += 7; a.y += 11; a.z -= 5;
            c.carryDownstream(0, bef, a.entryPose(), r0.spans());
            TrackCompiler.Result r1 = rebuild(c);
            Vec3 d = new Vec3(7, 11, -5);
            double worst = 0, worstV = 0, worstF = 0;
            boolean same = r0.samples().size() == r1.samples().size();
            boolean frames = same, speedsBit = same;
            for (int i = 0; i < r0.samples().size() && same; i++) {
                FVDIntegrator.Sample x = r0.samples().get(i);
                FVDIntegrator.Sample y = r1.samples().get(i);
                worst = Math.max(worst, y.pos().sub(x.pos().add(d)).length());
                frames &= bitsEq(x.T(), y.T()) && bitsEq(x.U(), y.U());
                worstF = Math.max(worstF, x.T().sub(y.T()).length());
                worstF = Math.max(worstF, x.U().sub(y.U()).length());
                speedsBit &= bitsEq(x.v(), y.v()) && bitsEq(x.Gv(), y.Gv())
                    && bitsEq(x.Gl(), y.Gl());
                worstV = Math.max(worstV, Math.abs(x.v() - y.v()));
                worstV = Math.max(worstV, Math.abs(x.Gv() - y.Gv()));
                worstV = Math.max(worstV, Math.abs(x.Gl() - y.Gl()));
            }
            k = 0;
            boolean vdBit = true;
            double vdDev = 0;
            for (TrackPiece p : c.pieces) {
                for (Section s : p.sections) {
                    vdBit &= bitsEq(vd0[k], s.vDesign());
                    vdDev = Math.max(vdDev, Math.abs(vd0[k++] - s.vDesign()));
                }
            }
            if (!twoSec) {
                check(same && frames && worst < 1e-9 && speedsBit && vdBit,
                    "6a run-head translate (1-section follower): every sample "
                    + "+Δ, frames/speeds/felt BITWISE, zero vDesign writes",
                    String.format("worst %.2e m", worst));
            } else {
                // the real-anchored continuation re-solves at its dust-
                // moved vDesign, so ITS frames carry the same dust
                check(same && worst < 1e-9 && worstF < 1e-9 && worstV < 1e-9
                        && vdDev < 1e-9,
                    "6b run-head translate (real-anchored continuation): "
                    + "+Δ rigid, frames/speeds/felt/vDesign to dust",
                    String.format("worst %.2e m, frame dust %.2e, speed dust %.2e,"
                        + " vDesign dust %.2e", worst, worstF, worstV, vdDev));
            }
        }

        // ---------- (7) all-zero-delta follower: pre-change bitwise ----
        {
            Coaster c = fixFollower00(true);
            TrackPiece b = c.pieces.get(1);
            TrackCompiler.Result r = rebuild(c);
            long s = checksum(r);
            check(r.samples().size() == REF_FOLLOWER00_N && s == REF_FOLLOWER00_SUM,
                "7a zero-delta held-angle follower: BITWISE the pre-D247 build",
                String.format("n %d sum %d", r.samples().size(), s));
            Quat gp = b.genPose().orient(), dp = b.designPose().orient();
            check(bitsEq(gp.w(), dp.w()) && bitsEq(gp.x(), dp.x())
                    && bitsEq(gp.y(), dp.y()) && bitsEq(gp.z(), dp.z()),
                "7b zero deltas: designPose bits == genPose bits (the "
                + "designed=false fork's object state)");
        }

        // ---------- (8) detachStrictSeam PRESERVES the conflict ----------
        // (D247 review fix) attach → detach is an involution on the
        // world track: a design-tilted attached piece detaches with
        // every sample bitwise-in-place (< 1e-9) and its ≠o carried
        // over as the free piece's own; the design tier untouched.
        {
            Coaster c = new Coaster();
            TrackPiece a = new TrackPiece(0, 21, 0, 14);
            a.sections.add(new ForceSection(hillLanes(), 26));
            c.pieces.add(a);
            // ONE-section follower: a continuation section would be
            // re-frozen by the reconcile once the detached piece runs
            // its own energy (the (3)/(6b) story) — not this op's
            TrackPiece b = new TrackPiece(60, 15, 10, 20);
            b.sections.add(new ForceSection(turnLanes(), 24));
            b.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            b.editPitch(6);
            b.editRoll(-9);
            b.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            b.editPitch(10);
            c.pieces.add(b);
            c.connectStrict(0, 1, false, true, true);
            TrackCompiler.Result r0 = rebuild(c);
            List<FVDIntegrator.Sample> before = new ArrayList<>(pieceSamples(c, r0, 1));
            boolean ok = c.detachStrictSeam(1, DT);
            TrackCompiler.Result r1 = rebuild(c);
            // a detached piece keeps its entry sample (a follower drops
            // the duplicate) — compare end-aligned in the WORLD frame
            double dev = worldDevEndAligned(before, pieceSamples(c, r1, 1));
            check(ok && !b.followPrev && dev < 1e-9,
                "8a attach → detach (design-tilted follower): every sample "
                + "in place", String.format("dev %.2e m (n %d vs %d)", dev,
                    before.size(), pieceSamples(c, r1, 1).size()));
            check(b.orientConflict() && b.designPitchDeg == 6
                    && b.designRollDeg == -9,
                "8b detach carries the seam ≠o over as the free piece's "
                + "(design tier untouched)", String.format(
                    "design (%.3f, %.3f) true (%.3f, %.3f)", b.designPitchDeg,
                    b.designRollDeg, b.pitchDeg, b.rollDeg));
            // the (0,0)-design held-angle follower: same involution,
            // the seam's held tilt becomes a true-tier tilt
            Coaster d = fixFollower00(false);
            TrackPiece db = d.pieces.get(1);
            TrackCompiler.Result rd0 = rebuild(d);
            List<FVDIntegrator.Sample> dBefore = new ArrayList<>(pieceSamples(d, rd0, 1));
            boolean okd = d.detachStrictSeam(1, DT);
            TrackCompiler.Result rd1 = rebuild(d);
            double devd = worldDevEndAligned(dBefore, pieceSamples(d, rd1, 1));
            check(okd && !db.followPrev && devd < 1e-9
                    && db.orientConflict() && db.designPitchDeg == 0,
                "8c attach → detach (held-angle follower): in place, ≠o kept",
                String.format("dev %.2e m", devd));
        }

        // ---------- (9) the all-Preserve Design → Real on a follower ---
        // (D247 review fix) FvdDesigner.designToRealDirect routes a
        // follower to bakeFollow — the model half gated here: a
        // follower carrying an ABSOLUTE-bank section + designPitch 6
        // bakes to a heading-only (identity) seam tilt, and the bake is
        // bitwise the reference bakeFollow compile.
        {
            Coaster c = fixFollower00(false);
            TrackPiece b = c.pieces.get(1);
            ForceSection fs = (ForceSection) b.sections.get(0);
            fs.rollGlobal = true;
            fs.rollAbsolute = true;
            b.designPitchDeg = 6;
            TrackCompiler.Result r0 = rebuild(c);
            check(Coaster.isSeamOrientConflict(c.seamConflictTilt(1, prevExit(r0))),
                "9a abs-bank follower with designPitch 6: seam ≠o open");
            boolean ok = c.bakeFollow(1, r0);
            TrackCompiler.Result r1 = rebuild(c);
            Quat tq = c.seamConflictTilt(1, prevExit(r1));
            check(ok && b.followPrev && b.designPitchDeg == 0
                    && b.designRollDeg == 0 && Math.abs(tq.w()) > 1 - 1e-14,
                "9b all-Preserve Design → Real (bakeFollow): seam tilt == "
                + "identity, both tiers zero, still following",
                String.format("|w| − 1 = %.2e", Math.abs(tq.w()) - 1));
            Coaster e = fixFollower00(false);
            ForceSection es = (ForceSection) e.pieces.get(1).sections.get(0);
            es.rollGlobal = true;
            es.rollAbsolute = true;
            e.pieces.get(1).designPitchDeg = 6;
            TrackCompiler.Result re0 = rebuild(e);
            e.bakeFollow(1, re0);
            check(checksum(rebuild(e)) == checksum(r1),
                "9c the bake is deterministic (bitwise across two runs)");
        }

        // ---------- (10) seamDesignLockstep pole guard ----------------
        // (D247 review fix) a held pitch crossing ±90° reads, in
        // canonical Euler, as heading +180 / bank +180 with the
        // elevation mirrored — the lockstep must NOT attribute that
        // flip to the design roll; it returns false and leaves the
        // tier bitwise. A non-crossing write still applies.
        {
            Coaster c = fixFollower00(true);
            TrackPiece b = c.pieces.get(1);
            b.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            b.sLockYaw = true;
            b.sLockPitch = true;
            b.sLockRoll = true;
            b.sYawDeg = 0;
            b.sPitchDeg = 85;
            b.sRollDeg = 0;
            TrackCompiler.Result r0 = rebuild(c);
            TrackPose ex = prevExit(r0);
            double[] before = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            b.sPitchDeg = 95;
            double[] after = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            double dp0 = b.designPitchDeg, dr0 = b.designRollDeg;
            boolean applied = Coaster.seamDesignLockstep(b, before, after);
            check(!applied && bitsEq(b.designPitchDeg, dp0)
                    && bitsEq(b.designRollDeg, dr0),
                "10a held pitch 85 → 95 (pole crossing): lockstep refuses, "
                + "design tier bitwise", String.format(
                    "before {%.1f, %.1f, %.1f} after {%.1f, %.1f, %.1f}",
                    before[0], before[1], before[2], after[0], after[1], after[2]));
            b.sPitchDeg = 80;
            double[] b2 = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            b.sPitchDeg = 89;
            double[] a2 = Coaster.eulerOf(Coaster.strictEntry(ex, b).orient());
            boolean applied2 = Coaster.seamDesignLockstep(b, b2, a2);
            check(applied2 && Math.abs(b.designPitchDeg - dp0 - 9) < 1e-9
                    && Math.abs(TrackPiece.wrap180(b.designRollDeg - dr0)) < 1e-9,
                "10b held pitch 80 → 89 (no crossing): lockstep applies +9",
                String.format("design pitch %.6f", b.designPitchDeg));
        }

        System.out.println();
        System.out.println(fail == 0
            ? "ALL SEAM-SHAPE GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
