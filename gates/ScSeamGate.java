package uvd;

import java.util.*;

/** D259 §3.11 gate — THE STRAIGHT/CURVE SEAM INVARIANT (Stage 1b run 3,
 *  v20.1; D5/D31). Fixtures built in code (the KnotFixture pattern):
 *    base = one piece [force hill 5 s at 26 m/s] from (0, 21, 0);
 *    F1 = base → '+' s/c straight 10 m at the compiled end speed through
 *         Coaster.appendSectionWithInvariant = [force] | automatic [sc];
 *    F2 = F1 → '+' force hold 1 s = [force] | auto [sc] | auto [hold];
 *    F3 = F1 with the seam's yaw LOCKED (freezeAutoFollower + sLockYaw);
 *    F4 = F1 with the seam smoothed at 4 m;
 *    F5 = [sc 10 m] | automatic [sc 8 m] (two s/c sections through the
 *         model append — §7.6's border split is Stage 4's).
 *  Rows (a-c, e-h, j-l, n-o run here; d / i (Stage 4 / 5) and m (the
 *  54-document dump) print SKIPPED with the reason):
 *  a) F1 / F2 vs the same sections built in-piece by direct sections.add
 *     — samples + true speeds BITWISE;
 *  b) F1 head x/y/z + yaw moves vs the in-piece twin — bitwise (D199);
 *  c) an upstream pitch node edit / a head LOCKSTEP pitch edit: the auto
 *     follower vs the in-piece twin bitwise, no orientation conflict;
 *     F3 keeps the D135 rigid tilt (held yaw, frozen local shape);
 *  e) F4 compiles; the upstream outside the window bitwise vs F1, the
 *     s fields kept, the closure caps honored; smoothEngages ==
 *     (smoothStrictSeam != null) over 200 random (len, v);
 *  f) F1 at rest: the follower's vDesign == trueV[seam] BITWISE (friction
 *     0.03 / 1e-4 too), design == real across its span, a typed speed
 *     pins, realEntrySpeed predicts the landing; f2: F4's follower's
 *     entry gap level + the smoothing-suspended compile's;
 *  g) unstrictSeam refuses on F1 / F2 (UNSTRICT_CONVERT_WHY) and F5
 *     (UNSTRICT_SC_WHY); detachStrictSeam succeeds — a run head, in place;
 *  h) a '+' costs ≤ 2 compiles (Prof under -Dfvd.timing=1);
 *  i*) the forward align's model op (the Stage-5 row proper is skipped);
 *  j) freeze then unlock-all → the next compile == the never-frozen F1;
 *  k) lock ON after an upstream edit: samples before / after bitwise;
 *  l) seamWorldPos == the upstream exit position (the ties are JavaFX);
 *  n) the §3.10 strings (LabelGate section 20 is the pin);
 *  o) copy / PlacementSnapshot / copyRun / mirrorRun carry sAuto.
 *
 *  Compile + run (from the project root):
 *    javac -encoding UTF-8 -cp target/classes -d gates/out gates/ScSeamGate.java
 *    java -cp "target/classes;gates/out" uvd.ScSeamGate   (':' on Linux)
 */
public final class ScSeamGate {

    private ScSeamGate() {}

    static final double DT = 0.01;
    static int pass = 0, fail = 0;
    static boolean settled;

    static void check(boolean ok, String name, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name
            + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    static void skip(String name, String why) {
        System.out.println("SKIP  " + name + "  (" + why + ")");
    }

    static void info(String name, String detail) {
        System.out.println("INFO  " + name + "  (" + detail + ")");
    }

    static String f(String fmt, Object... a) { return String.format(Locale.US, fmt, a); }

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static boolean bits(Vec3 a, Vec3 b) {
        return bits(a.x(), b.x()) && bits(a.y(), b.y()) && bits(a.z(), b.z());
    }

    // ---------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** A 5 s hill: Gv 1 → 2.2 → 0.6 → 1, Gl 0 → 0.3 → 0, roll 0 → 15 → 0. */
    static List<List<BezierBaker.Node>> hillLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.5, 2.2}, {3.5, 0.6}, {5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 0.3}, {5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 15}, {5, 0} }));
        return ls;
    }

    static Coaster base() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(p);
        return c;
    }

    static TrackCompiler.Result compile(Coaster c) {
        return TrackCompiler.compile(c, DT);
    }

    /** FvdDesigner.rebuildTrack's settle loops (tube pass, then the D200
     *  exact pass); `settled` = the exact pass reached a fixed point. */
    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = compile(c);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = compile(c);
        }
        settled = false;
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) { settled = true; break; }
            r = compile(c);
        }
        return r;
    }

    /** The compiled END speed of the last span — the '+' s/c's design
     *  speed (buildScStraight's vEnd). */
    static double endV(Coaster c) {
        TrackCompiler.Result r = compile(c);
        return r.samples().get(r.spans().get(r.spans().size() - 1).i1()).v();
    }

    /** F1 = base → '+' s/c 10 m through the invariant. */
    static Coaster f1() {
        Coaster c = base();
        c.appendSectionWithInvariant(0, StraightCurveSection.straight(10, endV(c)), DT);
        return c;
    }

    /** The same sections in ONE piece (the pre-D259 '+'). */
    static Coaster twin1() {
        Coaster c = base();
        c.pieces.get(0).sections.add(StraightCurveSection.straight(10, endV(c)));
        return c;
    }

    /** A flat 1 s hold of the compiled end state (the '+' force hold
     *  after an s/c, reduced to what a headless gate needs). */
    static ForceSection holdAfter(Coaster c) {
        TrackCompiler.Result r = rebuild(c);
        FVDIntegrator.Sample end = r.samples().get(
            r.spans().get(r.spans().size() - 1).i1());
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, end.Gv()}, {1, end.Gv()} }));
        ls.add(lane(new double[][]{ {0, end.Gl()}, {1, end.Gl()} }));
        ls.add(lane(new double[][]{ {0, 0}, {1, 0} }));
        return new ForceSection(ls, end.v());
    }

    static Coaster f2() {
        Coaster c = f1();
        c.appendSectionWithInvariant(1, holdAfter(c), DT);
        return c;
    }

    static Coaster twin2() {
        Coaster c = twin1();
        c.pieces.get(0).sections.add(holdAfter(c));
        return c;
    }

    static Coaster f5() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        StraightCurveSection a = StraightCurveSection.straight(10, 24);
        a.elements.add(StraightCurveSection.Element.curve(20, 60, 0, 0));
        p.sections.add(a);
        c.pieces.add(p);
        c.appendSectionWithInvariant(0, StraightCurveSection.straight(8, 24), DT);
        return c;
    }

    /** The upstream hill's Gv node 1: 2.2 → `v` (the same edit on a twin). */
    static void editUpstream(Coaster c, double v) {
        ForceSection fs = (ForceSection) c.pieces.get(0).sections.get(0);
        fs.lanes.get(0).get(1).val = v;
    }

    // ---------------------------------------------------------------
    // comparisons
    // ---------------------------------------------------------------

    /** null when the two results are BITWISE identical (count, every
     *  sample's pos / T / U / v / Gv / Gl, every true speed); else the
     *  first difference. */
    static String sameSamples(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) {
            return "sample count " + a.samples().size() + " vs " + b.samples().size();
        }
        if (a.trueV().length != b.trueV().length) return "trueV length";
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            if (!(bits(x.pos(), y.pos()) && bits(x.T(), y.T()) && bits(x.U(), y.U())
                    && bits(x.v(), y.v()) && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl())
                    && bits(a.trueV()[i], b.trueV()[i]))) {
                return f("sample %d differs (|dpos| %.3e, dv %.3e, dtrueV %.3e)", i,
                    x.pos().sub(y.pos()).length(), x.v() - y.v(),
                    a.trueV()[i] - b.trueV()[i]);
            }
        }
        return null;
    }

    /** Bitwise on everything but the positions, whose rigid image is
     *  checked within `tol` (ex = the expected image of a's position). */
    static String sameButPos(TrackCompiler.Result a, TrackCompiler.Result b,
                             java.util.function.UnaryOperator<Vec3> ex, double tol) {
        if (a.samples().size() != b.samples().size()) {
            return "sample count " + a.samples().size() + " vs " + b.samples().size();
        }
        double worst = 0;
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            worst = Math.max(worst, ex.apply(x.pos()).sub(y.pos()).length());
            if (!(bits(x.v(), y.v()) && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl())
                    && bits(a.trueV()[i], b.trueV()[i])
                    && bits(x.T().y(), y.T().y()) && bits(x.U().y(), y.U().y()))) {
                return "sample " + i + " v/felt/trueV/T.y/U.y differ";
            }
        }
        return worst <= tol ? null : f("worst pos image %.3e m", worst);
    }

    /** Positions in the frame (origin, q): local = q⁻¹·(p − origin). */
    static List<Vec3> inFrame(List<FVDIntegrator.Sample> s, Vec3 origin, Quat q) {
        Quat inv = q.conjugate();
        List<Vec3> out = new ArrayList<>(s.size());
        for (FVDIntegrator.Sample sm : s) out.add(inv.rotate(sm.pos().sub(origin)));
        return out;
    }

    static double maxDev(List<Vec3> a, List<Vec3> b) {
        if (a.size() != b.size()) return Double.POSITIVE_INFINITY;
        double w = 0;
        for (int i = 0; i < a.size(); i++) w = Math.max(w, a.get(i).sub(b.get(i)).length());
        return w;
    }

    /** Positions within tol, trueV within tol (the march's dust behind a
     *  tilt), design v / felt bitwise; frames bitwise when
     *  framesBitwise, else within tol (the review's c4 rows). */
    static String nearSamples(TrackCompiler.Result a, TrackCompiler.Result b,
                              double tol, boolean framesBitwise) {
        if (a.samples().size() != b.samples().size()) {
            return "sample count " + a.samples().size() + " vs " + b.samples().size();
        }
        double wp = 0, wv = 0, wf = 0;
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            wp = Math.max(wp, x.pos().sub(y.pos()).length());
            wv = Math.max(wv, Math.abs(a.trueV()[i] - b.trueV()[i]));
            if (!bits(x.v(), y.v())) return "sample " + i + " design v differs";
            if (framesBitwise) {
                if (!(bits(x.T(), y.T()) && bits(x.U(), y.U())
                        && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl()))) {
                    return "sample " + i + " frames / felt differ";
                }
            } else {
                // a tilt recomposed from Euler angles (the lock) differs
                // by dust: frames and the re-projected felt within tol
                wf = Math.max(wf, Math.max(x.T().sub(y.T()).length(),
                    x.U().sub(y.U()).length()));
                wf = Math.max(wf, Math.max(Math.abs(x.Gv() - y.Gv()),
                    Math.abs(x.Gl() - y.Gl())));
            }
        }
        return wp <= tol && wv <= tol && wf <= tol ? null
            : f("worst pos %.2e m, trueV %.2e, frame %.2e", wp, wv, wf);
    }

    static List<FVDIntegrator.Sample> spanSamples(TrackCompiler.Result r, int k) {
        TrackCompiler.Span sp = r.spans().get(k);
        return new ArrayList<>(r.samples().subList(sp.i0(), sp.i1() + 1));
    }

    static double headingDeg(Vec3 T) {
        return Math.toDegrees(Math.atan2(-T.z(), T.x()));
    }

    static int profCount(String key) {
        for (String part : Prof.report().split(" \\| ")) {
            if (part.startsWith(key + " ")) {
                String n = part.substring(key.length() + 1);
                return Integer.parseInt(n.substring(0, n.indexOf('×')));
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------
    // rows
    // ---------------------------------------------------------------

    static void rowA() {
        Coaster a = f1(), t = twin1();
        TrackPiece fo = a.pieces.get(1);
        check(a.pieces.size() == 2 && a.autoFollower(1) && fo.sAuto && fo.followPrev
                && !fo.sLockYaw && !fo.sLockPitch && !fo.sLockRoll
                && fo.sSmoothLen == 0 && fo.orientMode == TrackPiece.OrientMode.LOCKSTEP
                && fo.sections.get(0).vRealAnchored() && !fo.sections.get(0).vPinned()
                && a.scInvariantHolds() && t.pieces.size() == 1,
            "a0 F1 = [force] | automatic [sc]: sAuto, all three locks off, smoothing 0,"
            + " LOCKSTEP, the follower's section 0 real-anchored, L1 holds (L3)", "");
        TrackCompiler.Result ra = rebuild(a);
        boolean sa = settled;
        TrackCompiler.Result rt = rebuild(t);
        String d = sameSamples(ra, rt);
        check(d == null && sa && settled,
            "a1 F1 (the automatic follower) == the in-piece twin BITWISE — samples + true speeds"
            + " — both settle", d == null ? ra.samples().size() + " samples" : d);
        Coaster b = f2(), u = twin2();
        TrackCompiler.Result rb = rebuild(b), ru = rebuild(u);
        String d2 = sameSamples(rb, ru);
        check(d2 == null && b.pieces.size() == 3 && b.autoFollower(2) && b.scInvariantHolds()
                && b.pieces.get(2).sections.get(0) instanceof ForceSection,
            "a2 F2 = F1 → '+' force hold = [force] | auto [sc] | auto [hold] == the in-piece"
            + " twin BITWISE", d2 == null ? rb.samples().size() + " samples" : d2);
    }

    static void rowB() {
        Coaster a = f1(), t = twin1();
        TrackCompiler.Result r0 = rebuild(a);
        for (Coaster c : List.of(a, t)) {
            TrackPiece h = c.pieces.get(0);
            h.x += 50; h.y += 7; h.z -= 3; h.yawDeg += 37;
        }
        TrackCompiler.Result ra = rebuild(a), rt = rebuild(t);
        String d = sameSamples(ra, rt);
        check(d == null, "b1 head x/y/z + yaw moved on both: the automatic follower == the"
            + " in-piece twin BITWISE", d == null ? "" : d);
        // D199 rigidity: v / felt / trueV / T.y / U.y bitwise the unmoved F1,
        // positions the rigid image
        Quat rot = Quat.aboutY(37);
        Vec3 o0 = new Vec3(0, 21, 0), o1 = new Vec3(50, 28, -3);
        String dr = sameButPos(r0, ra, p -> o1.add(rot.rotate(p.sub(o0))), 1e-9);
        check(dr == null, "b2 the moved F1 vs the unmoved: v / felt / trueV / T.y / U.y BITWISE,"
            + " positions the rigid image ≤ 1e-9 m (D199 — the follower's generation never"
            + " reads the placement)", dr == null ? "" : dr);
    }

    static void rowC() {
        // c1 an upstream pitch (Gv) node edit on both
        Coaster a = f1(), t = twin1();
        rebuild(a); rebuild(t);
        editUpstream(a, 2.6); editUpstream(t, 2.6);
        TrackCompiler.Result ra = rebuild(a), rt = rebuild(t);
        String d1 = sameSamples(ra, rt);
        check(d1 == null && settled, "c1 an upstream Gv node edit (2.2 → 2.6): the automatic"
            + " follower re-solves == the in-piece twin BITWISE", d1 == null ? "" : d1);
        // c2 a head LOCKSTEP pitch edit on both
        Coaster a2 = f1(), t2 = twin1();
        rebuild(a2); rebuild(t2);
        a2.pieces.get(0).editPitch(5); t2.pieces.get(0).editPitch(5);
        TrackCompiler.Result ra2 = rebuild(a2), rt2 = rebuild(t2);
        String d2 = sameSamples(ra2, rt2);
        Quat tq = a2.seamConflictTilt(1, ra2.spans().get(0).exit());
        check(d2 == null && tq == Quat.IDENTITY && !Coaster.isSeamOrientConflict(tq)
                && a2.autoFollower(1),
            "c2 a head LOCKSTEP pitch edit (+5°): bitwise the twin; seamConflictTilt reads the"
            + " IDENTITY object — no orientation conflict (the ≠o predicate matches the compile)",
            d2 == null ? "" : d2);
        // c4 a head REAL_ONLY pitch edit = a CONFLICT TILT on the upstream span (the run-3
        // review): the automatic follower continues the DESIGN chain and INHERITS the tilt
        // about its own entry — the in-piece twin (generated in the chain, tilted about the
        // piece entry) to dust, frames / felt / design speeds bitwise; the Span-form
        // predicate reads the inherited tilt (≠o like the twin's sections); a lock ON
        // (the freeze) keeps the track and reads the same conflict
        Coaster a4 = f1(), t4 = twin1();
        rebuild(a4); rebuild(t4);
        // the s/c's design speed PINNED equal on both: the A4 real anchor
        // lands the follower's on the tilted span's real seam speed (the
        // D259 §3.5 rule — an in-piece continuation keeps the design
        // chain's), which would re-time the samples; the claim here is
        // the GEOMETRY of the inherited tilt
        a4.pieces.get(1).sections.get(0).vDesign(20);
        a4.pieces.get(1).sections.get(0).vPinned(true);
        t4.pieces.get(0).sections.get(1).vDesign(20);
        t4.pieces.get(0).sections.get(1).vPinned(true);
        for (Coaster c : List.of(a4, t4)) {
            c.pieces.get(0).orientMode = TrackPiece.OrientMode.REAL_ONLY;
            c.pieces.get(0).editPitch(5);
        }
        TrackCompiler.Result ra4 = rebuild(a4), rt4 = rebuild(t4);
        String d4 = nearSamples(ra4, rt4, 1e-9, true);
        Quat tq4 = a4.seamConflictTilt(1, ra4.spans().get(0));
        check(d4 == null && a4.autoFollower(1)
                && tq4 == ra4.spans().get(0).exitTilt()
                && Coaster.isSeamOrientConflict(tq4)
                && Coaster.isSeamOrientConflict(ra4.spans().get(0).exitTilt()),
            "c4 a head REAL_ONLY pitch edit (+5°, a conflict tilt): the automatic follower"
            + " == the in-piece twin to 1e-9 m (frames / design v / felt bitwise, trueV to"
            + " 1e-9); the Span-form predicate reads the inherited tilt (≠o)",
            d4 == null ? "" : d4);
        // the freeze (a lock ON) keeps the track and the conflict
        TrackPiece f4 = a4.pieces.get(1);
        a4.freezeAutoFollower(1, ra4);
        f4.sLockYaw = true;
        TrackCompiler.Result ra4b = rebuild(a4);
        String d4b = nearSamples(ra4, ra4b, 1e-9, false);
        Quat tq4b = a4.seamConflictTilt(1, ra4b.spans().get(0));
        check(d4b == null && !a4.autoFollower(1) && Coaster.isSeamOrientConflict(tq4b)
                && Math.abs(Math.abs(tq4b.w()) - Math.abs(tq4.w())) < 1e-9,
            "c4b the freeze + a yaw lock behind the tilted upstream: the track to 1e-9 m, the"
            + " frozen follower's design tier = the chain (the conflict tilt kept)",
            d4b == null ? "" : d4b);
        // c3 F3: yaw LOCKED — the D135 rigid tilt holds the heading, the local shape freezes
        Coaster a3 = f1(), t3 = twin1();
        TrackCompiler.Result r30 = rebuild(a3);
        rebuild(t3);
        TrackPiece f3 = a3.pieces.get(1);
        boolean frozen = a3.freezeAutoFollower(1, r30.spans());
        f3.sLockYaw = true;   // held at the seeded (current) heading
        double held = f3.sYawDeg;
        List<Vec3> localBefore = inFrame(spanSamples(r30, 1),
            r30.spans().get(0).exit().pos(), r30.spans().get(0).exit().orient());
        editUpstream(a3, 2.6); editUpstream(t3, 2.6);
        TrackCompiler.Result ra3 = rebuild(a3), rt3 = rebuild(t3);
        double hF = headingDeg(ra3.samples().get(ra3.spans().get(1).i0()).T());
        double hT = headingDeg(rt3.samples().get(rt3.spans().get(1).i0()).T());
        List<Vec3> localAfter = inFrame(spanSamples(ra3, 1),
            ra3.spans().get(0).exit().pos(),
            Coaster.strictEntry(ra3.spans().get(0).exit(), f3).orient());
        // the follower's first section is real-anchored (flagged), so its
        // design speed — and its sample COUNT over the 10 m — moves with the
        // upstream edit: the frozen SHAPE is read off the local END position
        double dev = localBefore.get(localBefore.size() - 1)
            .sub(localAfter.get(localAfter.size() - 1)).length();
        check(frozen && !a3.autoFollower(1) && f3.sAuto
                && Math.abs(hF - held) <= 1e-9 && dev <= 1e-9
                && sameSamples(ra3, rt3) != null,
            "c3 F3 (yaw locked): the D135 rigid tilt — the held heading survives the upstream"
            + " edit, the follower's LOCAL shape is frozen (≤ 1e-9 m), the twin re-aims; sAuto"
            + " stays (a lock turned it into a classic follower, not a hand-made one)",
            f("held %.6f° got %.6f° (twin %.6f°), local dev %.2e m", held, hF, hT, dev));
        info("c3 SeamShapeGate's recorded pre-D247 checksums",
            "the classic-follower branch is untouched — run uvd.SeamShapeGate (31)");
    }

    static void rowE() {
        Coaster a = f1();
        TrackCompiler.Result r1 = rebuild(a);
        Coaster s = f1();
        rebuild(s);
        s.pieces.get(1).sSmoothLen = 4;
        TrackCompiler.Result r4 = rebuild(s);
        boolean sm = settled;
        // the window on the UNSMOOTHED compile
        TrackCompiler.Span sa = r1.spans().get(0), sb = r1.spans().get(1);
        int iSeam = sa.i1();
        double sSeam = r1.samples().get(iSeam).s();
        double hUp = Math.min(2, sa.sLen() * 0.5), hDn = Math.min(2, sb.sLen() * 0.5);
        int iA = iSeam;
        while (iA > sa.i0() + 1 && sSeam - r1.samples().get(iA - 1).s() <= hUp) iA--;
        int iB = iSeam + 1;
        while (iB < sb.i1() - 1 && r1.samples().get(iB + 1).s() - sSeam <= hDn) iB++;
        boolean upBitwise = r4.samples().size() == r1.samples().size();
        for (int i = 0; upBitwise && i < iA; i++) {
            FVDIntegrator.Sample x = r1.samples().get(i), y = r4.samples().get(i);
            upBitwise = bits(x.pos(), y.pos()) && bits(x.T(), y.T()) && bits(x.U(), y.U());
        }
        // the s FIELDS and the per-step arc are read against THIS document's
        // smoothing-suspended compile (the same settled vDesign): the
        // follower's first section is real-anchored, so its design speed —
        // and its sample spacing — follow the SMOOTHED seam's speed, not F1's
        s.pieces.get(1).sSmoothLen = 0;
        TrackCompiler.Result r4s = compile(s);
        s.pieces.get(1).sSmoothLen = 4;
        boolean sKept = r4s.samples().size() == r4.samples().size();
        double stretch = 0, downDev = 0, worstIn = 0;
        for (int i = 0; sKept && i < r4s.samples().size(); i++) {
            sKept = bits(r4s.samples().get(i).s(), r4.samples().get(i).s());
        }
        for (int i = iA + 1; i <= iB && sKept; i++) {
            double ds1 = r4s.samples().get(i).s() - r4s.samples().get(i - 1).s();
            double dp4 = r4.samples().get(i).pos().sub(r4.samples().get(i - 1).pos()).length();
            if (ds1 > 1e-9) stretch = Math.max(stretch, Math.abs(dp4 / ds1 - 1));
            worstIn = Math.max(worstIn,
                r4.samples().get(i).pos().sub(r4s.samples().get(i).pos()).length());
        }
        for (int i = iB + 1; sKept && i < r4s.samples().size(); i++) {
            downDev = Math.max(downDev,
                r4.samples().get(i).pos().sub(r4s.samples().get(i).pos()).length());
        }
        check(sm && upBitwise && sKept && stretch <= 0.05 + 1e-9 && downDev <= 0.02
                && s.pieces.get(1).sSmoothLen == 4,
            "e1 F4 (4 m smoothing on the s/c edge) compiles and settles; the upstream outside"
            + " the window BITWISE vs F1, the s fields kept (vs the same document compiled"
            + " unsmoothed), the per-step arc stretch within the 5 % cap, the downstream"
            + " translated by the closure residual only",
            f("settled %b, upstream bitwise %b, s kept %b; window samples %d..%d of %d, stretch"
                + " %.4f, inside %.3e m, downstream %.3e m", sm, upBitwise, sKept,
                iA, iB, r1.samples().size(), stretch, worstIn, downDev));
        // the dry run == the blend's own verdict over 200 random (len, v)
        Random rnd = new Random(20261021L);
        int mism = 0, engaged = 0;
        for (int k = 0; k < 200; k++) {
            double v = 5 + 35 * rnd.nextDouble();
            double u = rnd.nextDouble();
            double len = 0.05 + 60 * u * u;
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(new ForceSection(hillLanes(), v));
            c.pieces.add(p);
            c.appendSectionWithInvariant(0, StraightCurveSection.straight(10, endV(c)), DT);
            TrackCompiler.Result r = compile(c);
            boolean dry = TrackCompiler.smoothEngages(r.samples(), r.spans(), 1, len);
            List<FVDIntegrator.Sample> all = new ArrayList<>(r.samples());
            List<TrackCompiler.Span> spans = new ArrayList<>(r.spans());
            List<Double> hy = new ArrayList<>(all.size());
            for (int i = 0; i < all.size(); i++) hy.add(0.0);
            boolean wet = TrackCompiler.smoothStrictSeam(all, spans, len, hy) != null;
            if (dry != wet) mism++;
            if (wet) engaged++;
        }
        check(mism == 0 && engaged > 20 && engaged < 180,
            "e2 smoothEngages == (smoothStrictSeam != null) over 200 random (len 0.05-60 m,"
            + " v 5-40 m/s) pairs", f("mismatches %d, engaged %d of 200", mism, engaged));
        TrackCompiler.Result rr = compile(f1());
        check(TrackCompiler.smoothEngagesWhy(rr.samples(), rr.spans(), 1, 10) == null
                && SectionNames.SMOOTH_TOO_SHORT_WHY.equals(
                    TrackCompiler.smoothEngagesWhy(rr.samples(), rr.spans(), 1, 0.3))
                && SectionNames.SMOOTH_TOO_SHORT_WHY.equals(
                    TrackCompiler.smoothEngagesWhy(rr.samples(), rr.spans(), 7, 10)),
            "e3 smoothEngagesWhy: 10 m → null, 0.3 m → SMOOTH_TOO_SHORT_WHY, an uncovered"
            + " seam index → SMOOTH_TOO_SHORT_WHY", "");
    }

    static void rowF() {
        for (int fr = 0; fr < 2; fr++) {
            Coaster a = f1();
            if (fr == 1) { a.fricRoll = 0.03; a.fricAir = 1.0e-4; }
            TrackCompiler.Result r = rebuild(a);
            Section sc = a.pieces.get(1).sections.get(0);
            int ip = r.spans().get(0).i1();
            TrackCompiler.Span sp = r.spans().get(1);
            boolean clean = true;
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                clean &= bits(r.trueV()[i], r.samples().get(i).v());
            }
            double[] dry = a.realEntrySpeed(1, 0, r.samples(), r.trueV(), r.spans());
            long b0 = Double.doubleToLongBits(sc.vDesign());
            TrackCompiler.Result r2 = rebuild(a);
            check(settled && bits(sc.vDesign(), r.trueV()[ip]) && clean
                    && dry != null && bits(dry[1], sc.vDesign()) && bits(dry[0], r.trueV()[ip])
                    && Double.doubleToLongBits(sc.vDesign()) == b0 && sameSamples(r, r2) == null,
                f("f1%s F1 at rest%s: the follower's vDesign == trueV[seam] BITWISE (the k == 0"
                    + " real anchor), design == real across its span (the clean-span rule),"
                    + " realEntrySpeed predicts the landing, a second rebuild is a bitwise no-op",
                    fr == 1 ? "b" : "a", fr == 1 ? " with friction 0.03 / 1e-4" : ""),
                f("vDesign %.12f real %.12f", sc.vDesign(), r.trueV()[ip]));
        }
        // a typed speed pins and the reconcile leaves it
        Coaster p = f1();
        rebuild(p);
        Section sc = p.pieces.get(1).sections.get(0);
        sc.vDesign(30);
        sc.vPinned(true);
        sc.vRealAnchored(false);   // the UI clears the flag on a typed speed (D256)
        TrackCompiler.Result rp = rebuild(p);
        int ip = rp.spans().get(0).i1();
        double[] dryP = p.realEntrySpeed(1, 0, rp.samples(), rp.trueV(), rp.spans());
        check(settled && bits(sc.vDesign(), 30) && sc.vPinned()
                && Math.abs(rp.trueV()[ip] - 30) > 1 && dryP != null
                && bits(dryP[1], Math.max(0.1, rp.trueV()[ip])),
            "f3 a typed 30 m/s pins the follower's first section: the reconcile leaves it, the"
            + " real seam speed differs (an honest ≠v), the dry run predicts the flagged landing"
            + " a click would reach", f("real %.6f", rp.trueV()[ip]));
        boolean ok = p.refreezeSpeedOnly(1, 0, DT);
        TrackCompiler.Result rq = rebuild(p);
        int iq = rq.spans().get(0).i1();
        check(ok && settled && !sc.vPinned() && sc.vRealAnchored()
                && bits(sc.vDesign(), rq.trueV()[iq]) && bits(sc.vDesign(), dryP[1]),
            "f4 Set Design to Real on the follower's first section lands BITWISE on the real seam"
            + " speed == the dry run's prediction (max(0.1, real)), un-pinned + flagged",
            f("vDesign %.12f", sc.vDesign()));
        // f2: F4's follower — the entry gap level under smoothing
        Coaster s = f1();
        rebuild(s);
        s.pieces.get(1).sSmoothLen = 4;
        TrackCompiler.Result r4 = rebuild(s);
        TrackCompiler.Span sb = r4.spans().get(1);
        double gap = Math.abs(Math.max(0.1, r4.trueV()[sb.i0()]) - r4.samples().get(sb.i0()).v());
        int lv = SectionNames.speedLevel(gap);
        s.pieces.get(1).sSmoothLen = 0;
        TrackCompiler.Result r0 = compile(s);   // the smoothing-suspended read
        TrackCompiler.Span sb0 = r0.spans().get(1);
        double gap0 = Math.abs(Math.max(0.1, r0.trueV()[sb0.i0()])
            - r0.samples().get(sb0.i0()).v());
        s.pieces.get(1).sSmoothLen = 4;
        check(settled && lv <= 1 && SectionNames.speedLevel(gap0) == 0,
            "f2 F4's follower at rest: the entry gap reads at most the SLIGHT level (\" ~v\" or"
            + " nothing — the record behind a smoothed seam is the march), and the"
            + " smoothing-suspended compile reads level 0 — \"caused by smoothing\"",
            f("gap %.6f m/s level %d, suspended gap %.3e level %d", gap, lv, gap0,
                SectionNames.speedLevel(gap0)));
    }

    static void rowG() {
        Coaster a = f1();
        rebuild(a);
        check(!a.unstrictSeam(1) && a.pieces.size() == 2 && a.autoFollower(1)
                && SectionNames.UNSTRICT_CONVERT_WHY.equals(a.unstrictSeamWhy(1)),
            "g1 unstrictSeam refuses on F1 (force | s/c) with UNSTRICT_CONVERT_WHY, nothing moved", "");
        Coaster b = f2();
        rebuild(b);
        check(!b.unstrictSeam(2) && !b.unstrictSeam(1) && b.pieces.size() == 3
                && SectionNames.UNSTRICT_CONVERT_WHY.equals(b.unstrictSeamWhy(2))
                && SectionNames.UNSTRICT_CONVERT_WHY.equals(b.absorbFollowerWhy(1, 2)),
            "g2 unstrictSeam / absorbFollower refuse on F2's s/c | force seam too", "");
        Coaster e = f5();
        rebuild(e);
        check(!e.unstrictSeam(1) && e.pieces.size() == 2 && e.autoFollower(1)
                && SectionNames.UNSTRICT_SC_WHY.equals(e.unstrictSeamWhy(1))
                && !e.absorbFollower(0, 1) && e.scInvariantHolds(),
            "g3 F5 (s/c | s/c): unstrictSeam / absorbFollower refuse with UNSTRICT_SC_WHY", "");
        // L1 assert in attachPiece (a gate build): a direct merge throws
        boolean threw = false;
        Coaster.ASSERT_SC_INVARIANT = true;
        try {
            Coaster x = f1();
            try { x.attachPiece(0, 1); } catch (IllegalStateException ex) { threw = true; }
        } finally {
            Coaster.ASSERT_SC_INVARIANT = false;
        }
        check(threw, "g4 attachPiece across an s/c edge throws under ASSERT_SC_INVARIANT (a gate build)", "");
        // detach: a run head, in place
        Coaster d = f1();
        TrackCompiler.Result r0 = rebuild(d);
        boolean det = d.detachStrictSeam(1, DT);
        TrackPiece h = d.pieces.get(1);
        TrackCompiler.Result r1 = rebuild(d);
        List<FVDIntegrator.Sample> fol = spanSamples(r0, 1);
        List<FVDIntegrator.Sample> head = spanSamples(r1, 1);
        boolean inPlace = head.size() == fol.size() + 1;   // a run head keeps its entry sample
        for (int i = 0; inPlace && i < fol.size(); i++) {
            FVDIntegrator.Sample x = fol.get(i), y = head.get(i + 1);
            inPlace = bits(x.pos(), y.pos()) && bits(x.T(), y.T()) && bits(x.U(), y.U())
                && bits(x.v(), y.v());
        }
        check(det && !h.followPrev && d.runStartOf(1) == 1 && !d.autoFollower(1)
                && Math.abs(h.yawDeg) <= 1e-9 && Math.abs(h.pitchDeg) <= 1e-9
                && Math.abs(h.rollDeg) <= 1e-9 && inPlace,
            "g5 detachStrictSeam on the automatic follower: a run head (followPrev off), zero"
            + " deltas (the freeze wrote the contract first), its samples BITWISE where the"
            + " follower's were (one entry sample more — a run head drops none)", "");
    }

    static void rowH() {
        if (!Prof.ON) {
            check(false, "h Prof is ON (the gate sets -Dfvd.timing before the first compile)", "");
            return;
        }
        Coaster c = base();
        rebuild(c);
        double v = endV(c);
        Prof.reset();
        int[] at = c.appendSectionWithInvariant(0, StraightCurveSection.straight(10, v), DT);
        int n1 = profCount("compile");
        rebuild(c);
        int n2 = profCount("compile");
        check(at != null && at[0] == 1 && at[1] == 0 && n1 == 1 && n2 - n1 <= 2,
            "h a '+' s/c costs ONE compile for the cut (ensureScFollower reads its Span pair)"
            + " and the rebuild (≤ 2 compiles there: the s/c is created at the end speed, so"
            + " the settle writes nothing or one anchor)", f("cut %d, rebuild %d", n1, n2 - n1));
        Prof.reset();
        Coaster l = base();
        rebuild(l);
        Prof.reset();
        l.appendSectionWithInvariant(0, Coaster.holdSection(1, 0, 0, 20), DT);
        check(profCount("compile") == 0 && l.pieces.size() == 1,
            "h2 a lane after a lane through appendSectionWithInvariant: no cut, NO compile"
            + " (bitwise the bare append)", "");
    }

    static void rowI() {
        skip("i alignSeamToUpstream's Stage-5 row", "Stage 5 (the seam window button + B with a"
            + " highlighted seam are JavaFX); the model op landed in run 3 is smoke-tested as i*");
        Coaster a = f1();
        TrackCompiler.Result r = rebuild(a);
        check(!a.alignSeamToUpstream(1, r) && SectionNames.ALIGN_FOLLOWS_WHY.equals(
                a.alignSeamWhy(1, r)),
            "i*1 the forward align on an automatic (all-unlocked) seam refuses with"
            + " ALIGN_FOLLOWS_WHY", "");
        TrackPiece fo = a.pieces.get(1);
        a.freezeAutoFollower(1, r);
        fo.sLockYaw = true;
        fo.sYawDeg = 123;   // a wrong held heading
        double want = Coaster.eulerOf(r.spans().get(0).exit().orient())[0];
        double p0 = fo.sPitchDeg, r0 = fo.sRollDeg;
        boolean ok = a.alignSeamToUpstream(1, r);
        check(ok && bits(fo.sYawDeg, want) && bits(fo.sPitchDeg, p0) && bits(fo.sRollDeg, r0)
                && fo.sLockYaw && !fo.sLockPitch && !fo.sLockRoll,
            "i*2 the forward align on a yaw-locked seam lands the held yaw BITWISE on"
            + " eulerOf(the upstream exit); pitch / roll untouched, locks kept (one-shot)",
            f("held %.9f", fo.sYawDeg));
        // roll skipped under an absolute element-0 lane
        Coaster b = f1();
        TrackCompiler.Result rb = rebuild(b);
        TrackPiece fb = b.pieces.get(1);
        StraightCurveSection sc = (StraightCurveSection) fb.sections.get(0);
        sc.elements.get(0).roll.setFlags(true, true, true);   // absolute bank lane
        b.freezeAutoFollower(1, rb);
        fb.sLockRoll = true;
        fb.sRollDeg = 77;
        check(b.seamRollOwnedByLane(1) && SectionNames.ALIGN_FOLLOWS_WHY.equals(b.alignSeamWhy(1, rb))
                && !b.alignSeamToUpstream(1, rb) && bits(fb.sRollDeg, 77),
            "i*3 only roll locked under an ABSOLUTE element-0 bank lane: roll is the lane's"
            + " (seamRollOwnedByLane) — nothing to align, ALIGN_FOLLOWS_WHY, the held roll untouched", "");
        // a vertical upstream exit
        Coaster v = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(lane(new double[][]{ {0, 90}, {1, 90} }));   // pitch 90 °/s for 1 s
        gl.add(lane(new double[][]{ {0, 0}, {1, 0} }));
        gl.add(lane(new double[][]{ {0, 0}, {1, 0} }));
        p.sections.add(new GeoSection(gl, 20));
        v.pieces.add(p);
        v.appendSectionWithInvariant(0, StraightCurveSection.straight(6, 20), DT);
        TrackCompiler.Result rv = rebuild(v);
        double[] ev = Coaster.eulerOf(rv.spans().get(0).exit().orient());
        TrackPiece fv = v.pieces.get(1);
        v.freezeAutoFollower(1, rv);
        fv.sLockYaw = true;
        check(Double.isNaN(ev[0]) && SectionNames.ALIGN_VERTICAL_WHY.equals(v.alignSeamWhy(1, rv))
                && !v.alignSeamToUpstream(1, rv),
            "i*4 a VERTICAL upstream exit (a geo section pitching 90°): yaw locked → the align"
            + " refuses with ALIGN_VERTICAL_WHY", f("T.y %.9f", rv.spans().get(0).exit().T().y()));
    }

    static void rowJK() {
        Coaster a = f1();
        rebuild(a);
        editUpstream(a, 2.6);
        TrackCompiler.Result rK = rebuild(a);
        TrackPiece fo = a.pieces.get(1);
        double[] stored0 = { fo.x, fo.y, fo.z };
        boolean frozen = a.freezeAutoFollower(1, rK.spans());
        TrackCompiler.Span up = rK.spans().get(0);
        boolean pose = bits(fo.x, up.exit().pos().x()) && bits(fo.y, up.exit().pos().y())
            && bits(fo.z, up.exit().pos().z()) && fo.baseOrient == up.exit().orient()
            && fo.canonOrient == up.exitCanon() && bits(fo.canonPsiDeg, up.exitPsi())
            && fo.yawDeg == 0 && fo.pitchDeg == 0 && fo.rollDeg == 0
            && fo.designPitchDeg == 0 && fo.designRollDeg == 0;
        fo.sLockYaw = true;   // the padlock's hold of the current (seeded) angle
        TrackCompiler.Result rK2 = rebuild(a);
        String dk = sameSamples(rK, rK2);
        check(frozen && pose && !a.autoFollower(1) && dk == null && settled,
            "k lock ON after the upstream edit: freezeAutoFollower wrote the contract (position,"
            + " baseOrient, the upstream span's exact (psi, canon) pair, both tiers 0) and the"
            + " samples before / after the lock are BITWISE (the frozen pose IS the contract)",
            dk == null ? f("stored moved from (%.3f, %.3f, %.3f)", stored0[0], stored0[1],
                stored0[2]) : dk);
        fo.sLockYaw = false;
        TrackCompiler.Result rK3 = rebuild(a);
        String dj = sameSamples(rK, rK3);
        check(a.autoFollower(1) && dj == null,
            "j unlock-all after the freeze: branch A1 resumes — the next compile == the"
            + " never-frozen F1 BITWISE (the stored fields went inert again)",
            dj == null ? "" : dj);
        // a freeze on a NON-automatic follower writes nothing
        Coaster l = new Coaster();
        TrackPiece lp = new TrackPiece(0, 21, 0, 0);
        lp.sections.add(new ForceSection(hillLanes(), 26));
        lp.sections.add(new ForceSection(hillLanes(), 24));
        l.pieces.add(lp);
        int fl = l.makeSeamStrict(0, 1, DT);
        TrackCompiler.Result rl = rebuild(l);
        TrackPiece lf = l.pieces.get(fl);
        double lx = lf.x;
        check(!l.freezeAutoFollower(fl, rl) && !lf.sAuto && bits(lf.x, lx)
                && !l.freezeAutoFollower(0, rl),
            "k2 freezeAutoFollower on a hand-made (all-locked) follower and on a run head"
            + " returns false and writes nothing", "");
    }

    static void rowL() {
        Coaster a = f1();
        TrackCompiler.Result r = rebuild(a);
        Vec3 sw = a.seamWorldPos(1, r.spans());
        Vec3 ex = r.spans().get(0).exit().pos();
        Vec3 first = r.samples().get(r.spans().get(1).i0()).pos();
        double v = r.samples().get(r.spans().get(0).i1()).v();
        double gap = first.sub(ex).length();
        check(sw != null && bits(sw, ex) && gap > 0.5 * v * DT && gap < 1.5 * v * DT
                && a.seamWorldPos(0, r.spans()) == null,
            "l seamWorldPos(follower) == the UPSTREAM span's exit position BITWISE — one dropped"
            + " dt before the follower's first sample; null for a run head",
            f("first sample %.4f m past the seam (v·dt %.4f)", gap, v * DT));
        skip("l ties across the seam spaced tieSpacing",
            "Viewport3D's run-anchored tie grid is JavaFX — the local session (§3.8)");
    }

    static void rowN() {
        String[][] want = {
            { "A straight/curve section always begins and ends at a strict seam — connect the sections instead.", SectionNames.UNSTRICT_SC_WHY },
            { "A straight/curve section always begins and ends at a strict seam — convert it first.", SectionNames.UNSTRICT_CONVERT_WHY },
            { "This seam already follows the previous section.", SectionNames.ALIGN_FOLLOWS_WHY },
            { "The track is vertical at this seam — there is no heading or bank to align.", SectionNames.ALIGN_VERTICAL_WHY },
            { "B needs a selected node or a highlighted strict seam.", SectionNames.B_NO_TARGET_WHY },
            { "Align to previous end", SectionNames.ALIGN_BUTTON },
            { "Sets the locked angles to the previous section's end orientation.", SectionNames.ALIGN_TIP },
            { "The smoothing window holds too few samples to engage — lengthen it.", SectionNames.SMOOTH_TOO_SHORT_WHY },
            { "A section beside this seam is too short to smooth.", SectionNames.SMOOTH_SECTION_SHORT_WHY },
            { "smoothing conversion needs force/geo sections on both sides of the seam", SectionNames.SMOOTH_REAL_SC_WHY },
            { "The bank lane sets the bank at this seam — edit its first node.", SectionNames.SEAM_ROLL_ABS_WHY },
            { "Reverse works on a track piece without strict seams or bridges — reversing a whole track piece is not available yet.", SectionNames.REVERSE_MULTI_WHY },
            { "Reverse works on a track piece without strict seams, bridges or speed controls — reversing a whole track piece is not available yet.", SectionNames.REVERSE_MULTI_CONTROLS_WHY },
        };
        int bad = 0;
        for (String[] w : want) if (!w[0].equals(w[1])) bad++;
        check(bad == 0, "n the §3.10 sentences verbatim (" + want.length + " constants; LabelGate"
            + " section 20 is the pin)", bad == 0 ? "" : bad + " differ");
    }

    static void rowO() {
        Coaster a = f1();
        TrackCompiler.Result r = rebuild(a);
        TrackPiece fo = a.pieces.get(1);
        TrackPiece cp = fo.copy(0);
        PlacementSnapshot snap = PlacementSnapshot.capture(a);
        fo.sAuto = false;
        boolean diff = snap.differs();
        snap.restore();
        check(cp.sAuto && cp.followPrev && !cp.sLockYaw && diff && fo.sAuto && !snap.differs(),
            "o1 TrackPiece.copy carries sAuto; PlacementSnapshot records it (differs on a flip,"
            + " restore brings it back)", "");
        int nh = a.copyRun(0, 1, Coaster.COPY_DZ, r.spans());
        TrackCompiler.Result rc = rebuild(a);
        boolean autoCopy = nh == 2 && a.pieces.size() == 4 && a.autoFollower(3)
            && a.pieces.get(3).sAuto && a.scInvariantHolds();
        List<FVDIntegrator.Sample> src = new ArrayList<>(rc.samples().subList(
            rc.spans().get(0).i0(), rc.spans().get(1).i1() + 1));
        List<FVDIntegrator.Sample> dst = new ArrayList<>(rc.samples().subList(
            rc.spans().get(2).i0(), rc.spans().get(3).i1() + 1));
        double worst = 0;
        boolean same = src.size() == dst.size();
        for (int i = 0; same && i < src.size(); i++) {
            FVDIntegrator.Sample x = src.get(i), y = dst.get(i);
            same = bits(x.v(), y.v()) && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl())
                && bits(x.T(), y.T()) && bits(x.U(), y.U())
                && bits(rc.trueV()[rc.spans().get(0).i0() + i],
                        rc.trueV()[rc.spans().get(2).i0() + i]);
            worst = Math.max(worst,
                x.pos().add(new Vec3(0, 0, Coaster.COPY_DZ)).sub(y.pos()).length());
        }
        check(autoCopy && same && worst <= 1e-9,
            "o2 copyRun of F1 (through the orphan-guard overload): the copied follower is"
            + " automatic; its samples == the source's translated by +5 z (frames / v / felt /"
            + " trueV bitwise, positions ≤ 1e-9)", f("worst %.2e m", worst));
        // mirrorRun keeps the automatic seam and reflects the compile
        Coaster m = f1();
        TrackCompiler.Result r0 = rebuild(m);
        String why = m.mirrorRunWhy(0);
        boolean mir = why == null && m.mirrorRun(0);
        TrackCompiler.Result rm = rebuild(m);
        double wm = 0;
        boolean sm = rm.samples().size() == r0.samples().size();
        for (int i = 0; sm && i < r0.samples().size(); i++) {
            Vec3 x = r0.samples().get(i).pos(), y = rm.samples().get(i).pos();
            wm = Math.max(wm, new Vec3(x.x(), x.y(), -x.z()).sub(y).length());
        }
        check(mir && sm && wm <= 1e-9 && m.autoFollower(1) && m.pieces.get(1).sAuto,
            "o3 mirrorRun on F1: the follower stays automatic, the compile is the z-reflection"
            + " ≤ 1e-9 m", f("why %s, worst %.2e m", why, wm));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("fvd.timing", "1");   // before Prof's class init (row h)
        Locale.setDefault(Locale.US);
        System.out.println("ScSeamGate — D259 §3.11 (the straight/curve seam invariant, Stage 1b run 3)");
        rowA();
        rowB();
        rowC();
        skip("d splitScAtBorder → combineScSections round trip", "Stage 4 (§7.6/§7.7)");
        rowE();
        rowF();
        rowG();
        rowH();
        rowI();
        rowJK();
        rowL();
        skip("m the 54-document clean-span dump", "needs gates/probes/cleanspan-2026-09-30 +"
            + " the owner's files — RE-RUN LOCALLY (CleanProbe2 + compare3.py)");
        rowN();
        rowO();
        System.out.println();
        System.out.println("ScSeamGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
