import uvd.*;

import java.util.*;

/** D247 gate — INSERT SECTION AT A SEAM (v19.18, the seam menu's
 *  "Insert section…": Coaster.insertSectionAtSeam).
 *  a) LOCK insert on an interior seam: the downstream becomes an
 *     all-locked follower FROZEN at its pre-insert shape — its world
 *     samples equal the pre-insert ones translated by the new exit
 *     (Euler dust), its local shape holds to dust, the seam gap is
 *     preserved, the upstream stays bitwise, the held angles are the
 *     pre-insert boundary's, a second rebuild is a bitwise no-op.
 *  b) append-insert behind a PRE-MADE strict seam (the strict-seam
 *     menu case): same claims + the held angles/locks untouched.
 *  c) NO lock: a plain interior insert — the settle terminates, the
 *     second rebuild is bitwise, the upstream is bitwise, the
 *     downstream's design speed re-freezes to the inserted exit.
 *  d) lengths: an s/c insert's totalLen() == len, a lane insert's
 *     rideEnd == len and its compiled span extent == len (D147).
 *  e) refusals / edge indices mutate nothing.
 *  f) save→load→rebuild bitwise for the lock and no-lock documents.
 */
public class InsertGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;
    static boolean settled;

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
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes(double len, double amp) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 1 + amp},
            {len * 0.7, 1 - amp * 0.6}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.3}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 25}, {len, 0} }));
        return ls;
    }

    static Coaster fixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(hillLanes(4, 1.2), 26));
        p.sections.add(new ForceSection(hillLanes(5, 0.9), 24));
        p.sections.add(new ForceSection(hillLanes(4, 1.1), 23));
        c.pieces.add(p);
        return c;
    }

    /** FvdDesigner.rebuildTrack's settle loops (tube pass, then the
     *  D200 exact pass); `settled` = the exact pass reached a fixed
     *  point within its cap. */
    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        settled = false;
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) { settled = true; break; }
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** A flat hold of the seam sample's felt state over `len` s — the
     *  editor's hold recipe reduced to what a headless gate needs
     *  (LaneSection.holdLanes is package-private). */
    static ForceSection hold(TrackCompiler.Result r, int spanIdx, double len) {
        FVDIntegrator.Sample end = r.samples().get(r.spans().get(spanIdx).i1());
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, end.Gv()}, {len, end.Gv()} }));
        ls.add(lane(new double[][]{ {0, end.Gl()}, {len, end.Gl()} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        return new ForceSection(ls, end.v());
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

    /** Samples [0, n) of two results bitwise identical (pos/T/U/v/Gv/Gl). */
    static boolean prefixBitwise(TrackCompiler.Result a, TrackCompiler.Result b,
                                 int n) {
        if (a.samples().size() < n || b.samples().size() < n) return false;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            if (!bitsEq(x.pos(), y.pos()) || !bitsEq(x.T(), y.T())
                    || !bitsEq(x.U(), y.U()) || !bitsEq(x.v(), y.v())
                    || !bitsEq(x.Gv(), y.Gv()) || !bitsEq(x.Gl(), y.Gl())) {
                return false;
            }
        }
        return true;
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

    /** Max deviation between two local-frame lists ALIGNED AT THE END
     *  (counts may differ by one — the D84 dropped entry sample). */
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

    /** Max world deviation, end-aligned: post vs pre + delta (pos) and
     *  pre vs post frames (T, U). */
    static double worldDev(List<FVDIntegrator.Sample> pre,
                           List<FVDIntegrator.Sample> post, Vec3 delta) {
        int n = Math.min(pre.size(), post.size());
        if (Math.abs(pre.size() - post.size()) > 1) return Double.MAX_VALUE;
        double w = 0;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample x = pre.get(pre.size() - n + i);
            FVDIntegrator.Sample y = post.get(post.size() - n + i);
            w = Math.max(w, x.pos().add(delta).sub(y.pos()).length());
            w = Math.max(w, x.T().sub(y.T()).length());
            w = Math.max(w, x.U().sub(y.U()).length());
            w = Math.max(w, Math.abs(x.v() - y.v()));
        }
        return w;
    }

    static Coaster roundTrip(Coaster c) throws Exception {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        return DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
    }

    static String f(double v) { return String.format("%.2e", v); }

    /** The shared follower claims after an insert behind a strict
     *  seam (a and b): world translate, local shape, seam gap. */
    static void followerClaims(String tag, Coaster c, TrackCompiler.Result r0,
                               List<FVDIntegrator.Sample> pre, TrackPose preExit,
                               Quat preFrame, TrackCompiler.Result r1) {
        int nUp = c.pieces.get(0).sections.size();   // post-insert
        followerClaims(tag, c, r0, pre, preExit, preFrame, r1, 1, nUp - 1);
    }

    /** The general form (v20.1, D259 §3): `fi` = the LOCKED follower's
     *  piece index, `insIdx` = the inserted section's flattened span
     *  index (its exit is the follower's seam). */
    static void followerClaims(String tag, Coaster c, TrackCompiler.Result r0,
                               List<FVDIntegrator.Sample> pre, TrackPose preExit,
                               Quat preFrame, TrackCompiler.Result r1,
                               int fi, int insIdx) {
        int nUp = insIdx + 1;
        TrackCompiler.Span ins = r1.spans().get(insIdx);   // inserted exit
        TrackPiece f = c.pieces.get(fi);
        List<FVDIntegrator.Sample> post = pieceSamples(c, r1, fi);
        Vec3 delta = ins.exit().pos().sub(preExit.pos());
        double dw = worldDev(pre, post, delta);
        check(dw < 1e-9, tag + " follower world samples == pre-insert ones "
            + "translated by the new exit (Euler dust)", "dev " + f(dw));
        Quat eff = Coaster.strictEntry(ins.exit(), f).orient();
        double dl = endAlignedDev(
            inFrame(pre, preExit.pos(), preFrame),
            inFrame(post, ins.exit().pos(), eff));
        check(dl < 1e-12, tag + " follower LOCAL shape holds to dust",
            "dev " + f(dl));
        double gapPre = pre.get(0).pos().sub(preExit.pos()).length();
        double gapPost = post.get(0).pos().sub(ins.exit().pos()).length();
        check(Math.abs(gapPre - gapPost) < 1e-12,
            tag + " seam gap preserved: first follower sample sits one step "
            + "past the inserted exit", "pre " + f(gapPre) + " post " + f(gapPost));
        // the upstream (everything before the seam) is bitwise
        int nPre = r0.spans().get(nUp - 2).i1() + 1;
        check(prefixBitwise(r0, r1, nPre),
            tag + " upstream sections stay BITWISE", nPre + " samples");
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) LOCK insert on the interior seam 1|2 ----
        Coaster c = fixture();
        TrackCompiler.Result r0 = rebuild(c);
        TrackPose preExit = r0.spans().get(1).exit();
        double[] preEuler = Coaster.eulerOf(preExit.orient());
        List<FVDIntegrator.Sample> pre = new ArrayList<>(r0.samples().subList(
            r0.spans().get(2).i0(), r0.spans().get(2).i1() + 1));
        ForceSection addA = hold(r0, 1, 3.0);
        int[] at = c.insertSectionAtSeam(0, 2, addA, true, DT);
        check(at != null && at[0] == 0 && at[1] == 2,
            "a1 lock insert returns {0, 2}",
            at == null ? "null" : at[0] + "," + at[1]);
        check(c.pieces.size() == 2 && c.pieces.get(0).sections.size() == 3
            && c.pieces.get(1).sections.size() == 1
            && c.pieces.get(0).sections.get(2) == addA,
            "a2 structure: [s0 s1 added] | strict | [s2]");
        TrackPiece fa = c.pieces.get(1);
        check(fa.followPrev && fa.sLockYaw && fa.sLockPitch && fa.sLockRoll
            && !(c.pieces.get(0).sections.get(2) instanceof BridgeSection),
            "a3 the downstream is an ACTIVE follower with all three axes locked");
        check(bitsEq(fa.sYawDeg, preEuler[0]) && bitsEq(fa.sPitchDeg, preEuler[1])
            && bitsEq(fa.sRollDeg, preEuler[2]),
            "a4 held angles == the PRE-insert boundary's Euler triple (bitwise)");
        TrackCompiler.Result r1 = rebuild(c);
        check(settled, "a5 settle terminates after the lock insert");
        followerClaims("a6", c, r0, pre, preExit, preExit.orient(), r1);
        TrackCompiler.Span insA = r1.spans().get(2);
        check(Math.abs((insA.t1() - insA.t0()) - 3.0) < 1e-9,
            "a7 inserted TIME section's compiled extent == len (D147)",
            "extent " + f(insA.t1() - insA.t0()));
        long h1 = checksum(r1);
        TrackCompiler.Result r1b = rebuild(c);
        check(checksum(r1b) == h1 && settled,
            "a8 second rebuild is a BITWISE no-op");
        Coaster back = roundTrip(c);
        TrackCompiler.Result r1c = rebuild(back);
        check(checksum(r1c) == h1 && back.pieces.size() == 2
            && back.pieces.get(1).followPrev,
            "f1 lock-insert document save→load→rebuild BITWISE");

        // ---- (b) a PRE-MADE strict seam, then the strict-seam append ----
        Coaster c2 = fixture();
        rebuild(c2);   // at rest first — the UI never splits mid-cascade
        int np = c2.makeSeamStrict(0, 2, DT);
        check(np == 1, "b0 fixture: makeSeamStrict(0, 2) → piece 1");
        TrackCompiler.Result r20 = rebuild(c2);
        TrackPiece fb = c2.pieces.get(1);
        double[] held = { fb.sYawDeg, fb.sPitchDeg, fb.sRollDeg };
        boolean[] locks = { fb.sLockYaw, fb.sLockPitch, fb.sLockRoll };
        TrackPose preExitB = r20.spans().get(1).exit();
        List<FVDIntegrator.Sample> preB = new ArrayList<>(pieceSamples(c2, r20, 1));
        ForceSection addB = hold(r20, 1, 2.5);
        int[] atB = c2.insertSectionAtSeam(0, 2, addB, false, DT);
        check(atB != null && atB[0] == 0 && atB[1] == 2
            && c2.pieces.size() == 2
            && c2.pieces.get(0).sections.size() == 3
            && c2.pieces.get(0).sections.get(2) == addB,
            "b1 strict-seam append lands as the upstream piece's last section");
        TrackCompiler.Result r21 = rebuild(c2);
        check(settled, "b2 settle terminates");
        check(bitsEq(fb.sYawDeg, held[0]) && bitsEq(fb.sPitchDeg, held[1])
            && bitsEq(fb.sRollDeg, held[2]) && fb.sLockYaw == locks[0]
            && fb.sLockPitch == locks[1] && fb.sLockRoll == locks[2]
            && fb.followPrev,
            "b3 held angles + locks UNCHANGED by the append");
        // the follower's OWN pre-insert frame (the follow contract)
        followerClaims("b4", c2, r20, preB, preExitB,
            Coaster.strictEntry(preExitB, fb).orient(), r21);
        long h2 = checksum(r21);
        check(checksum(rebuild(c2)) == h2 && settled,
            "b5 second rebuild is a BITWISE no-op");

        // ---- (c) NO lock: plain interior insert ----
        Coaster c3 = fixture();
        TrackCompiler.Result r30 = rebuild(c3);
        ForceSection addC = hold(r30, 1, 3.0);
        int[] atC = c3.insertSectionAtSeam(0, 2, addC, false, DT);
        check(atC != null && atC[0] == 0 && atC[1] == 2
            && c3.pieces.size() == 1 && c3.pieces.get(0).sections.size() == 4
            && c3.pieces.get(0).sections.get(2) == addC,
            "c1 no-lock insert: one piece of four sections, added at index 2");
        TrackCompiler.Result r31 = rebuild(c3);
        check(settled, "c2 settle terminates (downstream re-chains)");
        long h3 = checksum(r31);
        boolean writes = c3.reconcileChainedSpeeds(r31, true);
        TrackCompiler.Result r32 = rebuild(c3);
        check(!writes && checksum(r32) == h3 && settled,
            "c3 fixed point: the exact pass writes nothing, the second "
            + "rebuild is BITWISE");
        int nPreC = r30.spans().get(1).i1() + 1;
        check(prefixBitwise(r30, r31, nPreC),
            "c4 upstream sections 0-1 stay BITWISE", nPreC + " samples");
        double vIns = r31.samples().get(r31.spans().get(2).i1()).v();
        check(bitsEq(c3.pieces.get(0).sections.get(3).vDesign(), vIns),
            "c5 the downstream section's design speed re-froze to the "
            + "inserted exit (bitwise, the D200 exact pass)",
            "vDesign " + c3.pieces.get(0).sections.get(3).vDesign()
            + " exit v " + vIns);
        check(BezierBaker.rideEnd(addC.lanes) == 3.0,
            "d1 lane insert: rideEnd == len");
        TrackCompiler.Span insC = r31.spans().get(2);
        check(Math.abs((insC.t1() - insC.t0()) - 3.0) < 1e-9,
            "d2 lane insert: compiled extent == len",
            "extent " + f(insC.t1() - insC.t0()));
        Coaster back3 = roundTrip(c3);
        check(checksum(rebuild(back3)) == h3,
            "f2 no-lock document save→load→rebuild BITWISE");

        // ---- (d) s/c insert ----
        Coaster c4 = fixture();
        TrackCompiler.Result r40 = rebuild(c4);
        double vSeam = r40.samples().get(r40.spans().get(0).i1()).v();
        StraightCurveSection sc = StraightCurveSection.straight(7, vSeam);
        int[] atD = c4.insertSectionAtSeam(0, 1, sc, true, DT);
        // RE-PINNED v20.1 (D259 §3.2 — the straight/curve invariant): the
        // inserted s/c is its OWN one-section AUTOMATIC follower between
        // the upstream and the all-locked follower: [s0] | auto [sc] |
        // locked [s1 s2]; the returned {piece, section} is the s/c's new
        // home
        TrackPiece fD = c4.pieces.size() > 2 ? c4.pieces.get(2) : null;
        check(atD != null && atD[0] == 1 && atD[1] == 0
            && c4.pieces.size() == 3
            && c4.pieces.get(1).sections.size() == 1
            && c4.pieces.get(1).sections.get(0) == sc
            && c4.autoFollower(1) && c4.pieces.get(1).sAuto
            && sc.vRealAnchored() && c4.pieces.get(1).sSmoothLen == 0
            && fD != null && fD.followPrev && fD.sLockYaw && fD.sLockPitch
            && fD.sLockRoll && !fD.sAuto && fD.sections.size() == 2
            && c4.scInvariantHolds(),
            "d3 s/c lock insert at seam 0|1: [s0] | automatic [sc] | locked [s1 s2] (D259 §3.2)",
            atD == null ? "null" : atD[0] + "," + atD[1] + " pieces " + c4.pieces.size());
        check(sc.totalLen() == 7.0, "d4 s/c insert: totalLen() == len");
        TrackCompiler.Result r41 = rebuild(c4);
        check(settled, "d5 settle terminates with the s/c insert");
        TrackCompiler.Span insD = r41.spans().get(1);
        check(Math.abs(insD.sLen() - 7.0) < 1e-9,
            "d6 s/c insert: compiled arc == len", "arc " + f(insD.sLen()));
        List<FVDIntegrator.Sample> preD = new ArrayList<>(r40.samples().subList(
            r40.spans().get(1).i0(), r40.spans().get(2).i1() + 1));
        followerClaims("d7", c4, r40, preD, r40.spans().get(0).exit(),
            r40.spans().get(0).exit().orient(), r41, 2, 1);

        // ---- (e) refusals / edge indices mutate nothing ----
        Coaster c5 = fixture();
        TrackCompiler.Result r50 = rebuild(c5);
        ForceSection addE = hold(r50, 1, 1.0);
        check(c5.insertSectionAtSeam(1, 0, addE, false, DT) == null
            && c5.insertSectionAtSeam(0, 4, addE, false, DT) == null
            && c5.insertSectionAtSeam(0, -1, addE, false, DT) == null
            && c5.insertSectionAtSeam(0, 1, null, false, DT) == null
            && c5.pieces.size() == 1 && c5.pieces.get(0).sections.size() == 3,
            "e1 bad piece/section indices and a null section refuse, "
            + "nothing mutated");
        int[] atE = c5.insertSectionAtSeam(0, 3, addE, true, DT);
        check(atE != null && atE[1] == 3 && c5.pieces.size() == 1
            && c5.pieces.get(0).sections.size() == 4,
            "e2 lock at secIdx == size is a plain append (no seam to lock)");
        Coaster c6 = fixture();
        rebuild(c6);
        int[] atF = c6.insertSectionAtSeam(0, 0, hold(r50, 0, 1.0), true, DT);
        check(atF != null && atF[1] == 0 && c6.pieces.size() == 1
            && c6.pieces.get(0).sections.size() == 4,
            "e3 lock at secIdx == 0 is a plain insert (no upstream to seam)");

        System.out.println();
        System.out.println(fail == 0
            ? "ALL INSERT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
