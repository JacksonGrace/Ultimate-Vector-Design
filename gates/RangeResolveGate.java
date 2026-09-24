import coaster.*;

import java.util.*;

/** D247 gate — RANGE-SCOPED ORIENTATION RESOLVE (v19.18, item J).
 *
 *  g1) isolateRange on a tilted 3-section force piece ([1..1]): the
 *      whole track stays put (< 1e-9 m — local shape bitwise by the
 *      canonical pair, world placement to Euler dust, the D71/D224
 *      split rule), structure = free piece | locked follower |
 *      locked follower, both followers keep the conflict.
 *  g2) refreezeRange over [1..1] of a tilted [s/c, force, force] piece
 *      == today's refreezeSpeed(0, 1) restricted to section 1
 *      (< 1e-6 m — the s/c section 0's SHAPE is bake-invariant, but
 *      today's op re-times it at the baked frame and its landing
 *      moves the seam ~1e-7; the range flow keeps section 0 bitwise);
 *      section 0 unmoved, section 2's local shape unmoved with its
 *      entry orientation held (position follows the new seam).
 *  g3) chained "Convert Real to Design" over [0..1] of the 3-section
 *      force piece, the composite steps run headlessly: sections 0/1
 *      stay within the fit residual of their pre-op geometry, section
 *      2 (the remainder follower) too; structure = [0..1] one piece,
 *      resolved | strict | [2] keeping its conflict.
 *  g4) single-section piece: refreezeRange / the composite step ==
 *      today's refreezeSpeed / makeGraphsReal BITWISE.
 *  g5) the D247 leak fix: makeGraphsReal refuses a too-short section
 *      BEFORE baking (the piece's conflict tilt survives the refusal).
 *  s)  RESOLVE UNDER SEAM SMOOTHING (v19.20, D249, batch 34 item C —
 *      Coaster.withSeamsSuspended): on a chain {H, P0 (smoothed
 *      follower of H — the UNRELATED seam), P1 (conflicted follower
 *      of P0, own seam smoothed 6 m), P2 (follower of P1, seam
 *      smoothed 5 m)} each resolve flow run from the SMOOTHED document
 *      compiles bitwise the manual (zero both lengths → the same ops →
 *      restore on the objects) sequence, the lengths come back
 *      bit-exact on the OBJECTS that own the seams after the splits
 *      (the new range/remainder pieces own sharp 0 seams), the
 *      unrelated H→P0 seam keeps its length, a single-piece tail
 *      (no next seam) works, and the chained Convert's smoothed
 *      result stays within the fit residual of the pre-op smoothed
 *      track: s1 Set Design to Real (isolate + refreezeRange under
 *      one suspension, the UI's shape), s2 refreezeSpeed (the pure
 *      speed path) + the tail case, s3 the unrelated seam, s4 the
 *      chained Convert's two composite steps, s5 the residual.
 */
public class RangeResolveGate {

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

    static List<List<BezierBaker.Node>> dipLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1, 0.3}, {2.5, 2.4}, {4, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.3}, {4, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, -30}, {3, 30}, {4, 0} }));
        return ls;
    }

    /** A RATE-roll s/c section (frame-independent generation — the
     *  g2 equivalence hinge): a straight then a pull-up arc. */
    static StraightCurveSection scSec() {
        List<StraightCurveSection.Element> els = new ArrayList<>();
        StraightCurveSection.Element a =
            StraightCurveSection.Element.straight(20, 2);
        a.rollMode = StraightCurveSection.RollMode.RATE;
        StraightCurveSection.Element b =
            StraightCurveSection.Element.curve(30, 25, 90, 2);
        b.rollMode = StraightCurveSection.RollMode.RATE;
        els.add(a);
        els.add(b);
        return new StraightCurveSection(els, 26);
    }

    /** The tilted 3-section FORCE piece (true-tier tilt, design (0,0)
     *  = a locked conflict). */
    static Coaster threeForce() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(new ForceSection(hillLanes(), 26));
        p.sections.add(new ForceSection(turnLanes(), 26));
        p.sections.add(new ForceSection(dipLanes(), 26));
        p.orientMode = TrackPiece.OrientMode.REAL_ONLY;      // D248: locked is explicit now
        p.editPitch(12);
        p.editRoll(-8);
        c.pieces.add(p);
        return c;
    }

    /** The tilted [s/c, force, force] piece for g2. */
    static Coaster scForceForce() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(scSec());
        p.sections.add(new ForceSection(turnLanes(), 26));
        p.sections.add(new ForceSection(dipLanes(), 26));
        p.orientMode = TrackPiece.OrientMode.REAL_ONLY;      // D248: locked is explicit now
        p.editPitch(12);
        p.editRoll(-8);
        c.pieces.add(p);
        return c;
    }

    static Coaster oneForce() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(new ForceSection(hillLanes(), 26));
        p.orientMode = TrackPiece.OrientMode.REAL_ONLY;      // D248: locked is explicit now
        p.editPitch(12);
        p.editRoll(-8);
        c.pieces.add(p);
        return c;
    }

    static boolean bitsEq(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /** {H, P0 (follower of H, seam 4 m), P1 (follower of P0, seam 6 m,
     *  CONFLICTED by held angles), P2 (follower of P1, seam 5 m)} —
     *  the D249 smoothed chain. Built as one 5-section force piece
     *  cut three times (bitwise splits), then the held angles + the
     *  smoothing lengths. P1 = [turn, dip] (two sections — the range
     *  flows cut inside it). */
    static Coaster smoothedChain() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(new ForceSection(hillLanes(), 26));
        p.sections.add(new ForceSection(hillLanes(), 26));
        p.sections.add(new ForceSection(turnLanes(), 26));
        p.sections.add(new ForceSection(dipLanes(), 26));
        p.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(p);
        if (c.makeSeamStrict(0, 1, DT) != 1) throw new IllegalStateException("seam 1");
        if (c.makeSeamStrict(1, 1, DT) != 2) throw new IllegalStateException("seam 2");
        if (c.makeSeamStrict(2, 2, DT) != 3) throw new IllegalStateException("seam 3");
        c.pieces.get(1).sSmoothLen = 4;
        c.pieces.get(2).sPitchDeg += 6;     // the conflict (held vs design)
        c.pieces.get(2).sRollDeg -= 5;
        c.pieces.get(2).sSmoothLen = 6;
        c.pieces.get(3).sSmoothLen = 5;
        return c;
    }

    /** {H, P (follower of H, seam 7 m, conflicted)} — the LAST-piece
     *  tail (no seam after it). */
    static Coaster twoPieceTail() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 14);
        p.sections.add(new ForceSection(hillLanes(), 26));
        p.sections.add(new ForceSection(dipLanes(), 26));
        c.pieces.add(p);
        if (c.makeSeamStrict(0, 1, DT) != 1) throw new IllegalStateException("seam");
        c.pieces.get(1).sPitchDeg += 6;
        c.pieces.get(1).sSmoothLen = 7;
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

    /** Samples of flattened span index k. */
    static List<FVDIntegrator.Sample> spanSamples(TrackCompiler.Result r,
                                                  int k) {
        TrackCompiler.Span sp = r.spans().get(k);
        return r.samples().subList(sp.i0(), sp.i1() + 1);
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

    static double frameDev(List<Vec3[]> a, List<Vec3[]> b) {
        if (a.size() != b.size()) return Double.MAX_VALUE;
        double w = 0;
        for (int i = 0; i < a.size(); i++) {
            w = Math.max(w, a.get(i)[0].sub(b.get(i)[0]).length());
            w = Math.max(w, a.get(i)[1].sub(b.get(i)[1]).length());
        }
        return w;
    }

    static double quatDev(Quat a, Quat b) {
        return Math.min(
            Math.abs(a.w() - b.w()) + Math.abs(a.x() - b.x())
            + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()),
            Math.abs(a.w() + b.w()) + Math.abs(a.x() + b.x())
            + Math.abs(a.y() + b.y()) + Math.abs(a.z() + b.z()));
    }

    /** One-sided polyline distance: max over a's points of the
     *  distance to b's segments (a refit changes the sample count —
     *  the true clock differs from the design clock). */
    static double polyDev(List<FVDIntegrator.Sample> a,
                          List<FVDIntegrator.Sample> b) {
        double w = 0;
        for (FVDIntegrator.Sample s : a) {
            Vec3 p = s.pos();
            double best = Double.MAX_VALUE;
            for (int i = 0; i + 1 < b.size(); i++) {
                Vec3 u = b.get(i).pos(), v = b.get(i + 1).pos();
                Vec3 d = v.sub(u);
                double L2 = d.dot(d);
                double t = L2 > 0 ? Math.max(0, Math.min(1,
                    p.sub(u).dot(d) / L2)) : 0;
                best = Math.min(best, p.sub(u.add(d.scale(t))).length());
            }
            w = Math.max(w, best);
        }
        return w;
    }

    static boolean seamConflicted(Coaster c, TrackCompiler.Result r,
                                  int followerIdx) {
        int k = -1;
        for (int p = 0; p < followerIdx; p++) {
            k += c.pieces.get(p).sections.size();
        }
        return Coaster.isSeamOrientConflict(
            c.seamConflictTilt(followerIdx, r.spans().get(k).exit()));
    }

    public static void main(String[] args) throws Exception {

        // ---------- (g1) isolateRange keeps the track put ----------
        Coaster c1 = threeForce();
        TrackCompiler.Result r10 = rebuild(c1);
        int rp1 = c1.isolateRange(0, 1, 1, DT);
        TrackCompiler.Result r11 = rebuild(c1);
        check(rp1 == 1 && c1.pieces.size() == 3
                && c1.pieces.get(0).sections.size() == 1
                && c1.pieces.get(1).sections.size() == 1
                && c1.pieces.get(2).sections.size() == 1
                && !c1.pieces.get(0).followPrev
                && c1.pieces.get(1).followPrev && c1.pieces.get(2).followPrev
                && c1.pieces.get(1).sLockPitch && c1.pieces.get(1).sLockRoll
                && c1.pieces.get(2).sLockPitch && c1.pieces.get(2).sLockRoll,
            "g1a isolateRange [1..1]: free | locked follower | locked follower",
            "rp " + rp1 + " pieces " + c1.pieces.size());
        double dev1 = maxDev(r10.samples(), r11.samples());
        check(dev1 < 1e-9, "g1b isolateRange: whole track stays put",
            String.format("max dev %.2e (%d/%d samples)", dev1,
                r10.samples().size(), r11.samples().size()));
        check(c1.pieces.get(0).orientConflict()
                && seamConflicted(c1, r11, 1) && seamConflicted(c1, r11, 2),
            "g1c isolateRange: upstream keeps its tilt, both followers "
            + "keep the conflict at their seams");
        TrackCompiler.Result r12 = rebuild(c1);
        check(checksum(r11) == checksum(r12),
            "g1d isolateRange: the second rebuild is a bitwise no-op");

        // ---------- (g2) refreezeRange == today's op on the range ----------
        Coaster c2a = scForceForce();
        Coaster c2b = scForceForce();
        TrackCompiler.Result r20 = rebuild(c2a);
        rebuild(c2b);
        Quat seam2Before = r20.spans().get(1).exit().orient();   // sec 2 entry
        List<Vec3[]> sec2Before = inFrame(spanSamples(r20, 2),
            r20.spans().get(1).exit().pos(), seam2Before);
        // today's op on the whole piece
        check(c2b.refreezeSpeed(0, 1, DT),
            "g2a today's refreezeSpeed(0, 1) runs");
        TrackCompiler.Result r2b = rebuild(c2b);
        // the range flow
        int rp2 = c2a.isolateRange(0, 1, 1, DT);
        check(rp2 == 1 && c2a.refreezeRange(rp2, DT),
            "g2b isolateRange [1..1] + refreezeRange run", "rp " + rp2);
        TrackCompiler.Result r2a = rebuild(c2a);
        double dev2s1 = maxDev(spanSamples(r2a, 1), spanSamples(r2b, 1));
        // the bound is 1e-6, NOT 1e-9: today's whole-piece op re-TIMES
        // the s/c section 0 at the baked frame (its design speed
        // profile reads the tilted heights), and the re-timed RK4
        // sub-steps + Newton landing put ITS seam ~1e-7 m / 1e-8 rad
        // off the pre-op exit — the range flow keeps section 0
        // bitwise (g2d) and hands section 1 the exact contract (the
        // follower's entry == its predecessor's exit to 6e-17). The
        // two flows therefore agree to the dust today's op itself
        // introduces at the seam (printed in the detail).
        TrackPose exA = r2a.spans().get(0).exit(), exB = r2b.spans().get(0).exit();
        check(dev2s1 < 1e-6,
            "g2c refreezeRange [1..1] == today's op restricted to section 1",
            String.format("max dev %.2e; today's op moved the seam itself "
                + "by %.2e m / %.2e rad; vDesign %.9f vs %.9f", dev2s1,
                exA.pos().sub(exB.pos()).length(),
                quatDev(exA.orient(), exB.orient()),
                c2a.pieces.get(1).sections.get(0).vDesign(),
                c2b.pieces.get(0).sections.get(1).vDesign()));
        double dev2s0 = maxDev(spanSamples(r2a, 0), spanSamples(r20, 0));
        check(dev2s0 < 1e-9, "g2d section 0 unmoved",
            String.format("max dev %.2e", dev2s0));
        Quat seam2After = r2a.spans().get(1).exit().orient();
        // section 2 = a locked follower: its entry ORIENTATION is held
        // (the pre-op boundary), its local shape unchanged; only its
        // position follows the reshaped section 1's exit
        Quat ent2 = Coaster.strictEntry(r2a.spans().get(1).exit(),
            c2a.pieces.get(2)).orient();
        double qd2 = quatDev(ent2, seam2Before);
        List<Vec3[]> sec2After = inFrame(spanSamples(r2a, 2),
            r2a.spans().get(1).exit().pos(), ent2);
        double dev2s2 = frameDev(sec2Before, sec2After);
        check(qd2 < 1e-9 && dev2s2 < 1e-9,
            "g2e section 2: entry orientation held, local shape unmoved",
            String.format("orient dev %.2e, local dev %.2e, seam moved %.3f m",
                qd2, dev2s2, r2a.spans().get(1).exit().pos()
                    .sub(r20.spans().get(1).exit().pos()).length()));
        check(!c2a.pieces.get(1).orientConflict()
                && !seamConflicted(c2a, r2a, 1)
                && seamConflicted(c2a, r2a, 2)
                && c2a.pieces.get(0).orientConflict(),
            "g2f range piece resolved, upstream + remainder keep their "
            + "conflicts",
            "seam-2 orient still differs by " + String.format("%.3f",
                quatDev(seam2After, seam2Before)));
        TrackCompiler.Result r2a2 = rebuild(c2a);
        check(checksum(r2a) == checksum(r2a2),
            "g2g refreezeRange: the second rebuild is a bitwise no-op");

        // ---------- (g3) chained Convert Real to Design over [0..1] ----------
        Coaster c3 = threeForce();
        TrackCompiler.Result r30 = rebuild(c3);
        List<FVDIntegrator.Sample> pre0 = new ArrayList<>(spanSamples(r30, 0));
        List<FVDIntegrator.Sample> pre1 = new ArrayList<>(spanSamples(r30, 1));
        List<FVDIntegrator.Sample> pre2 = new ArrayList<>(spanSamples(r30, 2));
        double sp3 = 0.05, tol3 = 0;   // fine uniform = max fidelity
        // step 0 (the composite op): isolate [0..1], split [1] off as a
        // locked follower, make section 0 real
        int rp3 = c3.isolateRange(0, 0, 1, DT);
        boolean s0 = rp3 == 0 && c3.makeSeamStrict(0, 1, DT) == 1
            && c3.makeGraphsReal(0, 0, DT, sp3, tol3);
        check(s0 && c3.pieces.size() == 3
                && !c3.pieces.get(0).orientConflict(),
            "g3a step 0: isolate + split + make section 0 real",
            "pieces " + c3.pieces.size());
        rebuild(c3);
        // step 1: make the waiting follower's section real, absorb it
        boolean s1 = c3.makeGraphsReal(1, 0, DT, sp3, tol3)
            && c3.absorbFollower(0, 1);
        check(s1 && c3.pieces.size() == 2
                && c3.pieces.get(0).sections.size() == 2
                && c3.pieces.get(1).sections.size() == 1
                && c3.pieces.get(1).followPrev
                && !c3.pieces.get(0).followPrev,
            "g3b step 1: make section 1 real + absorb → [0..1] | strict | [2]",
            "pieces " + c3.pieces.size());
        TrackCompiler.Result r31 = rebuild(c3);
        double d30 = polyDev(spanSamples(r31, 0), pre0);
        double d31 = polyDev(spanSamples(r31, 1), pre1);
        double d32 = polyDev(spanSamples(r31, 2), pre2);
        check(d30 < 5e-3 && d31 < 5e-3,
            "g3c converted sections stay within the fit residual",
            String.format("sec0 %.4f m, sec1 %.4f m", d30, d31));
        check(d32 < 5e-3,
            "g3d remainder follower stays within the fit residual",
            String.format("sec2 %.4f m", d32));
        check(!c3.pieces.get(0).orientConflict()
                && seamConflicted(c3, r31, 1)
                && c3.pieces.get(0).sections.get(0) instanceof ForceSection
                && c3.pieces.get(0).sections.get(1) instanceof ForceSection,
            "g3e range resolved (no conflict), remainder keeps its conflict "
            + "behind the strict seam");
        // the resolved range reads no ≠v: design == true at both entries
        double gap0 = Math.abs(r31.trueV()[r31.spans().get(0).i0()]
            - r31.samples().get(r31.spans().get(0).i0()).v());
        double gap1 = Math.abs(r31.trueV()[r31.spans().get(0).i1()]
            - c3.pieces.get(0).sections.get(1).vDesign());
        check(gap0 < 1e-6 && gap1 < 0.05,
            "g3f converted sections start at their true speeds",
            String.format("gap0 %.2e, gap1 %.4f", gap0, gap1));
        TrackCompiler.Result r32 = rebuild(c3);
        check(checksum(r31) == checksum(r32),
            "g3g chained convert: the second rebuild is a bitwise no-op");

        // ---------- (g4) one-section piece == today's ops bitwise ----------
        Coaster c4a = oneForce(), c4b = oneForce();
        rebuild(c4a);
        rebuild(c4b);
        int rp4 = c4a.isolateRange(0, 0, 0, DT);
        check(rp4 == 0 && c4a.pieces.size() == 1 && c4a.refreezeRange(0, DT)
                && c4b.refreezeSpeed(0, 0, DT),
            "g4a one-section piece: isolateRange is a no-op, both ops run");
        check(checksum(rebuild(c4a)) == checksum(rebuild(c4b)),
            "g4b refreezeRange == refreezeSpeed bitwise on a one-section piece");
        Coaster c4c = oneForce(), c4d = oneForce();
        rebuild(c4c);
        rebuild(c4d);
        check(c4c.isolateRange(0, 0, 0, DT) == 0
                && c4c.makeGraphsReal(0, 0, DT, 0.1, 0.01)
                && c4d.makeGraphsReal(0, 0, DT, 0.1, 0.01)
                && checksum(rebuild(c4c)) == checksum(rebuild(c4d)),
            "g4c composite step == makeGraphsReal bitwise on a one-section "
            + "piece");

        // ---------- (g5) the leak fix ----------
        Coaster c5 = new Coaster();
        TrackPiece p5 = new TrackPiece(0, 21, 0, 14);
        p5.sections.add(new ForceSection(hillLanes(), 26));
        List<List<BezierBaker.Node>> tiny = new ArrayList<>();
        tiny.add(lane(new double[][]{ {0, 1}, {0.02, 1} }));
        tiny.add(lane(new double[][]{ {0, 0}, {0.02, 0} }));
        tiny.add(lane(new double[][]{ {0, 0}, {0.02, 0} }));
        p5.sections.add(new ForceSection(tiny, 26));
        p5.orientMode = TrackPiece.OrientMode.REAL_ONLY;     // D248: locked is explicit now
        p5.editPitch(12);
        p5.editRoll(-8);
        c5.pieces.add(p5);
        TrackCompiler.Result r50 = rebuild(c5);
        Quat base5 = p5.baseOrient;
        boolean ran5 = c5.makeGraphsReal(0, 1, DT, 0.1, 0.01);
        check(!ran5 && p5.orientConflict() && p5.baseOrient == base5
                && p5.pitchDeg == 12 && p5.rollDeg == -8,
            "g5a too-short section: makeGraphsReal refuses BEFORE baking "
            + "(tilt + base untouched)",
            "span " + (r50.spans().get(1).i1() - r50.spans().get(1).i0())
                + " samples");
        check(checksum(rebuild(c5)) == checksum(r50),
            "g5b the refused document compiles bitwise as before");

        // ---------- (s) resolve under seam smoothing (D249) ----------
        {
            // s1: Set Design to Real over [0..0] of the conflicted
            // follower P1 (index 2) — the UI's shape: isolate +
            // refreezeRange under ONE suspension of P1's own seam + P2's
            Coaster ca = smoothedChain(), cb = smoothedChain();
            rebuild(ca);
            rebuild(cb);
            TrackPiece a1 = ca.pieces.get(2), a2 = ca.pieces.get(3);
            TrackPiece b1 = cb.pieces.get(2), b2 = cb.pieces.get(3);
            check(a1.sSmoothLen == 6 && a2.sSmoothLen == 5
                    && ca.pieces.get(1).sSmoothLen == 4
                    && seamConflicted(ca, rebuild(ca), 2)
                    && !seamConflicted(ca, rebuild(ca), 3),
                "s1a fixture: P1 conflicted, P2 a pure link, seams 4 | 6 | 5 m smoothed");
            boolean okA = ca.withSeamsSuspended(2, () -> {
                int rp = ca.isolateRange(2, 0, 0, DT);
                return rp == 2 && ca.refreezeRange(rp, DT);
            });
            // the manual reference: lengths zeroed for good, the same
            // ops, then restored on the OBJECTS
            b1.sSmoothLen = 0;
            b2.sSmoothLen = 0;
            int rpb = cb.isolateRange(2, 0, 0, DT);
            boolean okB = rpb == 2 && cb.refreezeRange(rpb, DT);
            b1.sSmoothLen = 6;
            b2.sSmoothLen = 5;
            check(okA && okB, "s1b both flows run (the model never refused smoothed seams; the UI gray is gone)");
            check(bitsEq(a1.sSmoothLen, 6.0) && bitsEq(a2.sSmoothLen, 5.0)
                    && ca.pieces.get(2) == a1 && ca.pieces.get(4) == a2
                    && ca.pieces.get(3).sSmoothLen == 0
                    && bitsEq(ca.pieces.get(1).sSmoothLen, 4.0),
                "s1c lengths restored bit-exact on the OBJECTS (P2 moved to 4), the new remainder owns a sharp seam, H→P0 untouched",
                "P1 " + a1.sSmoothLen + " rem " + ca.pieces.get(3).sSmoothLen
                    + " P2 " + a2.sSmoothLen);
            TrackCompiler.Result ra = rebuild(ca), rb = rebuild(cb);
            check(checksum(ra) == checksum(rb),
                "s1d Set Design to Real from the smoothed document == suspend → resolve → restore BITWISE");
            check(!a1.orientConflict() && !seamConflicted(ca, ra, 2),
                "s1e the range piece resolved under its (restored) smoothing");
            check(checksum(rebuild(ca)) == checksum(ra),
                "s1f the second rebuild is a bitwise no-op");

            // s2: the pure speed path — refreezeSpeed on the smoothed
            // follower P1 (no isolate), plus the single-piece TAIL
            Coaster cc = smoothedChain(), cd = smoothedChain();
            rebuild(cc);
            rebuild(cd);
            TrackPiece cc1 = cc.pieces.get(2), cc2 = cc.pieces.get(3);
            TrackPiece d1 = cd.pieces.get(2), d2 = cd.pieces.get(3);
            boolean okC = cc.refreezeSpeed(2, 0, DT);
            d1.sSmoothLen = 0;
            d2.sSmoothLen = 0;
            boolean okD = cd.refreezeSpeed(2, 0, DT);
            d1.sSmoothLen = 6;
            d2.sSmoothLen = 5;
            check(okC && okD && bitsEq(cc1.sSmoothLen, 6.0) && bitsEq(cc2.sSmoothLen, 5.0)
                    && checksum(rebuild(cc)) == checksum(rebuild(cd)),
                "s2a refreezeSpeed on a smoothed follower == suspend → op → restore bitwise, lengths restored");
            Coaster ce = twoPieceTail(), cf = twoPieceTail();
            rebuild(ce);
            rebuild(cf);
            TrackPiece e1 = ce.pieces.get(1), f1 = cf.pieces.get(1);
            boolean okE = ce.refreezeSpeed(1, 0, DT);
            f1.sSmoothLen = 0;
            boolean okF = cf.refreezeSpeed(1, 0, DT);
            f1.sSmoothLen = 7;
            check(okE && okF && bitsEq(e1.sSmoothLen, 7.0)
                    && checksum(rebuild(ce)) == checksum(rebuild(cf)),
                "s2b the LAST piece (no seam after it): own seam suspended alone, bitwise");
            Coaster cg = twoPieceTail(), ch = twoPieceTail();
            rebuild(cg);
            rebuild(ch);
            TrackPiece g1 = cg.pieces.get(1), h1 = ch.pieces.get(1);
            boolean okG = cg.makeGraphsReal(1, 0, DT, 0.1, 0.01);
            h1.sSmoothLen = 0;
            boolean okH = ch.makeGraphsReal(1, 0, DT, 0.1, 0.01);
            h1.sSmoothLen = 7;
            check(okG && okH && bitsEq(g1.sSmoothLen, 7.0)
                    && checksum(rebuild(cg)) == checksum(rebuild(ch)),
                "s2c makeGraphsReal on a smoothed follower == suspend → op → restore bitwise");

            // s3: the UNRELATED seam (H→P0) is never zeroed by the
            // scoped suspension: an op that observes P0's own length
            // from inside sees 4 while P1's and P2's read 0
            Coaster ci = smoothedChain();
            rebuild(ci);
            double[] seen = ci.withSeamsSuspended(2, () -> new double[]{
                ci.pieces.get(1).sSmoothLen, ci.pieces.get(2).sSmoothLen,
                ci.pieces.get(3).sSmoothLen });
            check(seen[0] == 4 && seen[1] == 0 && seen[2] == 0
                    && ci.pieces.get(1).sSmoothLen == 4
                    && ci.pieces.get(2).sSmoothLen == 6
                    && ci.pieces.get(3).sSmoothLen == 5,
                "s3 the suspension is SCOPED: own + next seams read 0 inside, the unrelated seam keeps 4; all restored after",
                seen[0] + " " + seen[1] + " " + seen[2]);

            // s4: the chained Convert Real to Design over [0..1] of P1
            // (2 sections) — both composite steps, each == its manual
            // twin bitwise, lengths restored on the objects
            Coaster cj = smoothedChain(), ck = smoothedChain();
            TrackCompiler.Result rj0 = rebuild(cj);
            rebuild(ck);
            TrackPiece j1 = cj.pieces.get(2), j2 = cj.pieces.get(3);
            TrackPiece k1 = ck.pieces.get(2), k2 = ck.pieces.get(3);
            List<FVDIntegrator.Sample> preAll = new ArrayList<>(rj0.samples());
            double sp4 = 0.05, tol4 = 0;
            // step 0 (the UI lambda's shape)
            boolean st0 = cj.withSeamsSuspended(2, () -> {
                int rp = cj.isolateRange(2, 0, 1, DT);
                if (rp != 2) return false;
                if (cj.makeSeamStrict(rp, 1, DT) < 0) return false;
                return cj.makeGraphsReal(rp, 0, DT, sp4, tol4);
            });
            k1.sSmoothLen = 0;
            k2.sSmoothLen = 0;
            int rpk = ck.isolateRange(2, 0, 1, DT);
            boolean sk0 = rpk == 2 && ck.makeSeamStrict(rpk, 1, DT) >= 0
                && ck.makeGraphsReal(rpk, 0, DT, sp4, tol4);
            k1.sSmoothLen = 6;
            k2.sSmoothLen = 5;
            check(st0 && sk0 && cj.pieces.size() == 5
                    && bitsEq(j1.sSmoothLen, 6.0) && bitsEq(j2.sSmoothLen, 5.0)
                    && cj.pieces.get(3).sSmoothLen == 0 && cj.pieces.get(4) == j2,
                "s4a chained step 0 under smoothing: runs, lengths restored on the objects (waiting follower sharp)",
                "pieces " + cj.pieces.size());
            check(checksum(rebuild(cj)) == checksum(rebuild(ck)),
                "s4b step 0 == suspend → steps → restore BITWISE");
            // step 1: the waiting follower (3) converts + absorbs into 2
            boolean st1 = cj.withSeamsSuspended(2, () -> cj.withSeamsSuspended(3,
                () -> cj.makeGraphsReal(3, 0, DT, sp4, tol4)
                    && cj.absorbFollower(2, 3)));
            k1.sSmoothLen = 0;
            k2.sSmoothLen = 0;
            boolean sk1 = ck.makeGraphsReal(3, 0, DT, sp4, tol4)
                && ck.absorbFollower(2, 3);
            k1.sSmoothLen = 6;
            k2.sSmoothLen = 5;
            check(st1 && sk1 && cj.pieces.size() == 4 && cj.pieces.get(3) == j2
                    && bitsEq(j1.sSmoothLen, 6.0) && bitsEq(j2.sSmoothLen, 5.0)
                    && cj.pieces.get(2).sections.size() == 2,
                "s4c chained step 1 under smoothing: absorbed, P2 (now 3) keeps its 5 m on the object",
                "pieces " + cj.pieces.size());
            TrackCompiler.Result rj1 = rebuild(cj);
            check(checksum(rj1) == checksum(rebuild(ck)),
                "s4d step 1 == suspend → steps → restore BITWISE");
            // P2 was a PURE link before (a bitwise split, no held-angle
            // offset) and stays one: still a follower, still unconflicted
            check(!j1.orientConflict() && !seamConflicted(cj, rj1, 2)
                    && cj.followsPrev(3) && !seamConflicted(cj, rj1, 3),
                "s4e the range resolved; P2 still follows behind its (smoothed) strict seam, a pure link as before");
            // s5: the smoothed track after the chained Convert stays
            // within the fit residual of the pre-op smoothed track
            double d5 = polyDev(rj1.samples(), preAll);
            check(d5 < 2e-2,
                "s5 the smoothed track after Convert Real to Design stays within the fit residual",
                String.format("max dev %.4f m", d5));
            check(checksum(rebuild(cj)) == checksum(rj1),
                "s5b the second rebuild is a bitwise no-op");

            // s6: the wrapper's own contract (edge rows 6, 9, 12 —
            // D249 review): the op reads BOTH lengths at 0, a nested
            // call is a no-op (inner stash 0 → restore 0, the outer
            // restores the real values), a THROWING op still restores
            // in the finally, and an out-of-range index suspends
            // nothing but runs the op
            Coaster cx = smoothedChain();
            rebuild(cx);
            TrackPiece x1 = cx.pieces.get(2), x2 = cx.pieces.get(3);
            double[] seen6 = new double[4];
            boolean nested = cx.withSeamsSuspended(2, () -> {
                seen6[0] = x1.sSmoothLen;
                seen6[1] = x2.sSmoothLen;
                Boolean inner = cx.withSeamsSuspended(2, () -> {
                    seen6[2] = x1.sSmoothLen;
                    seen6[3] = x2.sSmoothLen;
                    return Boolean.TRUE;
                });
                return inner && x1.sSmoothLen == 0 && x2.sSmoothLen == 0;
            });
            check(nested && seen6[0] == 0 && seen6[1] == 0 && seen6[2] == 0
                    && seen6[3] == 0
                    && bitsEq(x1.sSmoothLen, 6.0) && bitsEq(x2.sSmoothLen, 5.0)
                    && bitsEq(cx.pieces.get(1).sSmoothLen, 4.0),
                "s6a the op reads 0 on both seams, a nested suspension is a no-op, both restored bit-exact");
            boolean threw = false;
            try {
                cx.<Boolean>withSeamsSuspended(2, () -> {
                    throw new IllegalStateException("probe");
                });
            } catch (IllegalStateException ex) {
                threw = true;
            }
            check(threw && bitsEq(x1.sSmoothLen, 6.0) && bitsEq(x2.sSmoothLen, 5.0),
                "s6b a throwing op still restores both lengths (finally)");
            check(cx.withSeamsSuspended(99, () -> Boolean.TRUE)
                    && cx.withSeamsSuspended(-1, () -> Boolean.TRUE)
                    && bitsEq(x1.sSmoothLen, 6.0) && bitsEq(x2.sSmoothLen, 5.0),
                "s6c an out-of-range index suspends nothing and runs the op");
        }

        System.out.println();
        System.out.println(fail == 0
            ? "ALL RANGE-RESOLVE GATES PASS (" + pass + ")"
            : "RANGE-RESOLVE GATES: " + pass + " passed, " + fail + " FAILED");
        if (fail != 0) System.exit(1);
    }
}
