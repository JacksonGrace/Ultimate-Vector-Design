import uvd.*;

import java.util.*;

/** D248 gate — RUN AS PIECE (v19.19): the toolbar selector, the '+'
 *  attach/bridge lists and the export picker enumerate RUNS (head..
 *  runEndOf over strict follows + trailing bridges — Coaster's ONE
 *  topology definition) and Coaster.moveRun / connectStrictRun move a
 *  WHOLE run as a contiguous block. Pure model, pins the edge table
 *  (scratchpad b33_edges_R-runs.md):
 *  a) runHeads / runCount / runOrdinal on a 3-run fixture
 *     {A = [force, strict follower, bridge → bridged-in], B = [single],
 *     C = [head + follower]} + runStartOf/runEndOf == the historic
 *     GraphEditor loops for every index;
 *  b) moveRun(C.head, after A's tail): block contiguous + in order,
 *     every TrackPiece field bit-identical, section lists the same
 *     objects, head followPrev false; the inert-flag head (index 0)
 *     normalizes; the list's last index survives a move that isn't of
 *     the last run;
 *  c) per-piece compiles BEFORE vs AFTER the move bitwise (no world
 *     pose changed, canonical generation, Pass D run sets unchanged);
 *  d) connectStrictRun: the moved head follows with the given locks,
 *     its follower still follows it, its internal bridge still targets
 *     the next block piece, the rebuild runs;
 *  e) refusals (−1, list untouched): non-head, afterIdx inside the
 *     block, a bridge-ending destination, an orphan-bridge block not
 *     landing last — and the same block landing LAST is allowed;
 *  f) a run of one: moveRun == movePiece with connectStrict's index
 *     arithmetic; connectStrictRun == connectStrict field for field;
 *  g) save→load→rebuild after a move reproduces the session bitwise;
 *  h) Coaster.runTailEnd (the floating wizard's placement) reads the
 *     RUN TAIL's end for every member of the run;
 *  i) Coaster.runSampleRange / runLengthM (the export picker + labels)
 *     cover the run's whole contiguous range incl. its bridge;
 *  j) Coaster.deleteRun (D249, batch 34 item C — the Track Piece
 *     window's Delete…): a middle run (the run after activates), the
 *     last run (the previous head), the first run (the new head at 0,
 *     followPrev normalized), a run holding a bridge + its target (3
 *     pieces at once), the ONLY run (empty coaster, −1), the orphan-
 *     bridge run, survivors' fields bitwise, the run-head topology
 *     invariant (no bridge-ending piece before a head — the "bridge
 *     before it re-targets" sentence is unreachable), refusals (−2,
 *     list untouched), and the undo model (re-inserting the removed
 *     objects compiles bitwise the pre-delete document);
 *  k) Coaster.moveRunTo (batch 37 — the Track Piece window's Switch
 *     Coaster… moves the WHOLE run): the block lands contiguous and in
 *     order at the destination's end, every TrackPiece field + section
 *     identity bitwise (the head's followPrev normalized), the run's
 *     samples compile bitwise before vs after — lane spans and
 *     bridged-in targets always, a whole plain run, and a smoothed +
 *     bridged run whose clock/arc offsets stay put (an empty
 *     destination); behind other pieces a SMOOTHED seam's window and a
 *     BRIDGE's time resampling keep their known offset dust, bounded
 *     1e-9 (measured 1.2e-11 m) —, the source remainder
 *     and the destination's prior pieces bitwise, refusals (−1, both
 *     lists untouched: non-head, out of range, null / same coaster,
 *     different friction, a destination ending in an orphan bridge),
 *     an orphan-bridge run moves and stays orphan, the source may go
 *     empty, save→load→rebuild of both coasters bitwise. */
public class RunGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name
            + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name
            + "  (" + detail + ")"); }
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
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

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static boolean bits(Vec3 a, Vec3 b) {
        return bits(a.x(), b.x()) && bits(a.y(), b.y()) && bits(a.z(), b.z());
    }

    static String identical(List<FVDIntegrator.Sample> a, int i0,
                            List<FVDIntegrator.Sample> b, int j0, int n) {
        if (i0 + n > a.size() || j0 + n > b.size()) {
            return "range " + i0 + "+" + n + " / " + j0 + "+" + n
                + " beyond " + a.size() + " / " + b.size();
        }
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sa = a.get(i0 + i), sb = b.get(j0 + i);
            if (!bits(sa.pos(), sb.pos()) || !bits(sa.T(), sb.T())
                    || !bits(sa.U(), sb.U()) || !bits(sa.v(), sb.v())
                    || !bits(sa.Gv(), sb.Gv()) || !bits(sa.Gl(), sb.Gl())) {
                return "sample " + i + " differs";
            }
        }
        return null;
    }

    static Coaster roundTrip(Coaster c) throws Exception {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        return DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
    }

    // ---------------- fixtures ----------------

    static List<List<BezierBaker.Node>> forceLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 2.4}, {4.2, 0.4}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.8}, {3.5, -0.5},
            {6, 0.2} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 35}, {4.5, -20},
            {6, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> shortLanes(double gv, double rr) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, gv}, {4, 1} }));
        ls.add(lane(new double[][]{ {0, 0.2}, {2, -0.3}, {4, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, rr}, {4, 0} }));
        return ls;
    }

    /** {A0, A1 (strict follower of A0), A2 (bridged in from A1)},
     *  {B}, {C0, C1 (strict follower of C0)} — three runs, six
     *  pieces. A0's force section carries a speed control + a
     *  global/absolute geo section (index-blind features inside a
     *  moved block, edge row 33). */
    static Coaster threeRuns() {
        Coaster c = new Coaster();
        // run A
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        ForceSection fs = new ForceSection(forceLanes(), 26);
        SpeedControl ctl = new SpeedControl(1.0, 1.2);
        ctl.speed = 22;
        fs.speedCtls.add(ctl);
        a.sections.add(fs);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 8}, {3, -4} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 12}, {3, 20} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 15}, {3, 0} }));
        GeoSection gs = new GeoSection(gl, 24);
        gs.yawPitchGlobal = true;
        gs.rollGlobal = true;
        gs.ypAbsolute = true;
        gs.rollAbsolute = true;
        a.sections.add(gs);
        a.sections.add(new ForceSection(shortLanes(1.6, 10), 23));
        c.pieces.add(a);
        int f = c.makeSeamStrict(0, 2, DT);   // A1 follows A0
        if (f != 1) throw new IllegalStateException("makeSeamStrict A " + f);
        c.pieces.get(1).sPitchDeg += 3;       // a held angle (row 9)
        c.pieces.get(1).sSmoothLen = 3;       // + a smoothed seam
        TrackPiece a2 = new TrackPiece(150, 16, 20, 15);
        a2.sections.add(new ForceSection(shortLanes(1.4, 5), 24));
        c.pieces.add(a2);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        TrackCompiler.Span tail = r0.spans().get(3);   // A1's section
        BridgeSection bs = BridgeSection.solve(tail.exit(), a2.entryPose(),
            r0.samples().get(tail.i1()).v());
        bs.optMode = BridgeSection.OPT_POS_YAW;
        c.pieces.get(1).sections.add(bs);
        c.optimizeBridge(bs, 2, 40, true);
        // run B — a lone detached piece
        TrackPiece b = new TrackPiece(-80, 30, 60, 200);
        b.sections.add(new ForceSection(shortLanes(2.0, -15), 20));
        c.pieces.add(b);
        // run C — head + follower
        TrackPiece c0 = new TrackPiece(60, 12, -90, 95);
        c0.sections.add(new ForceSection(forceLanes(), 25));
        c0.sections.add(new ForceSection(shortLanes(1.3, 8), 22));
        c.pieces.add(c0);
        int f2 = c.makeSeamStrict(4, 1, DT);
        if (f2 != 5) throw new IllegalStateException("makeSeamStrict C " + f2);
        c.pieces.get(5).designPitchDeg = 2;   // a live design tier (row 9)
        return c;
    }

    /** {B}, {C0, C1}, {O (ends in an ORPHAN bridge)} — an orphan-bridge
     *  run is necessarily the LAST run (a bridge targets the next list
     *  entry, so a bridge-ending piece with anything after it absorbs
     *  that piece). */
    static Coaster orphanLast() {
        Coaster c = new Coaster();
        TrackPiece b = new TrackPiece(-80, 30, 60, 200);
        b.sections.add(new ForceSection(shortLanes(2.0, -15), 20));
        c.pieces.add(b);
        TrackPiece c0 = new TrackPiece(60, 12, -90, 95);
        c0.sections.add(new ForceSection(forceLanes(), 25));
        c0.sections.add(new ForceSection(shortLanes(1.3, 8), 22));
        c.pieces.add(c0);
        int f2 = c.makeSeamStrict(1, 1, DT);
        if (f2 != 2) throw new IllegalStateException("makeSeamStrict C " + f2);
        TrackPiece o = new TrackPiece(0, 21, 0, 0);
        o.sections.add(new ForceSection(forceLanes(), 26));
        c.pieces.add(o);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        TrackCompiler.Span os = r0.spans().get(3);
        TrackPose exit = os.exit();
        TrackPose tgt = new TrackPose(exit.pos().add(new Vec3(30, -4, 6)),
            exit.orient());
        BridgeSection bs = BridgeSection.solve(exit, tgt,
            r0.samples().get(os.i1()).v());
        bs.optMode = BridgeSection.OPT_NONE;
        o.sections.add(bs);
        return c;
    }

    /** A destination coaster for moveRunTo (batch 37): one run of two
     *  — a head + its strict follower — zero friction like the other
     *  fixtures. */
    static Coaster destCoaster() {
        Coaster c = new Coaster();
        TrackPiece d = new TrackPiece(200, 25, -40, 70);
        d.sections.add(new ForceSection(forceLanes(), 25));
        d.sections.add(new ForceSection(shortLanes(1.3, 8), 22));
        c.pieces.add(d);
        int f = c.makeSeamStrict(0, 1, DT);
        if (f != 1) throw new IllegalStateException("makeSeamStrict D " + f);
        return c;
    }

    /** identical() + the TRUE speed array over the same ranges. */
    static String identicalTrue(TrackCompiler.Result a, int i0,
                                TrackCompiler.Result b, int j0, int n) {
        String why = identical(a.samples(), i0, b.samples(), j0, n);
        if (why != null) return why;
        for (int i = 0; i < n; i++) {
            if (!bits(a.trueV()[i0 + i], b.trueV()[j0 + i])) {
                return "trueV " + i + " differs";
            }
        }
        return null;
    }

    /** Worst deviation over the same ranges: position (m), T/U,
     *  design speed, felt and true speed. */
    static double maxDev(TrackCompiler.Result a, int i0,
                         TrackCompiler.Result b, int j0, int n) {
        double w = 0;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sa = a.samples().get(i0 + i);
            FVDIntegrator.Sample sb = b.samples().get(j0 + i);
            w = Math.max(w, sa.pos().sub(sb.pos()).length());
            w = Math.max(w, sa.T().sub(sb.T()).length());
            w = Math.max(w, sa.U().sub(sb.U()).length());
            w = Math.max(w, Math.abs(sa.v() - sb.v()));
            w = Math.max(w, Math.abs(sa.Gv() - sb.Gv()));
            w = Math.max(w, Math.abs(sa.Gl() - sb.Gl()));
            w = Math.max(w, Math.abs(a.trueV()[i0 + i] - b.trueV()[j0 + i]));
        }
        return w;
    }

    // ---------------- helpers ----------------

    /** Historic GraphEditor.runStart/runEnd loops, inlined verbatim. */
    static int histStart(Coaster c, int p) {
        while (p > 0 && (c.endsWithBridge(p - 1) || c.followsPrev(p))) p--;
        return p;
    }

    static int histEnd(Coaster c, int p) {
        while (p < c.pieces.size() - 1
                && (c.endsWithBridge(p) || c.followsPrev(p + 1))) p++;
        return p;
    }

    /** Every TrackPiece field that shapes generation or the seam, as
     *  raw bits, plus the section list's object identities. */
    static long[] fieldBits(TrackPiece t) {
        double[] d = { t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
            t.designPitchDeg, t.designRollDeg, t.sYawDeg, t.sPitchDeg,
            t.sRollDeg, t.sSmoothLen, t.canonPsiDeg,
            t.baseOrient.w(), t.baseOrient.x(), t.baseOrient.y(),
            t.baseOrient.z() };
        long[] out = new long[d.length + 7 + t.sections.size()];
        for (int i = 0; i < d.length; i++) out[i] = Double.doubleToLongBits(d[i]);
        int k = d.length;
        out[k++] = t.followPrev ? 1 : 0;
        out[k++] = t.sLockYaw ? 1 : 0;
        out[k++] = t.sLockPitch ? 1 : 0;
        out[k++] = t.sLockRoll ? 1 : 0;
        out[k++] = t.orientMode.ordinal();              // D249
        out[k++] = t.affectSubsequentSeams ? 1 : 0;   // D248/D249
        out[k++] = t.canonOrient == null ? 0 : 1;
        for (Section s : t.sections) out[k++] = System.identityHashCode(s);
        return out;
    }

    /** Per-piece sample range {i0, i1} from a compile's spans. */
    static int[] pieceRange(Coaster c, TrackCompiler.Result r, int p) {
        int b = 0;
        for (int q = 0; q < p; q++) b += c.pieces.get(q).sections.size();
        int ns = c.pieces.get(p).sections.size();
        return new int[]{ r.spans().get(b).i0(), r.spans().get(b + ns - 1).i1() };
    }

    static String order(Coaster c, Map<TrackPiece, String> names) {
        StringBuilder sb = new StringBuilder();
        for (TrackPiece t : c.pieces) sb.append(names.get(t)).append(' ');
        return sb.toString().trim();
    }

    static Map<TrackPiece, String> name3(Coaster c) {
        String[] n = { "A0", "A1", "A2", "B", "C0", "C1" };
        Map<TrackPiece, String> m = new IdentityHashMap<>();
        for (int i = 0; i < c.pieces.size(); i++) m.put(c.pieces.get(i), n[i]);
        return m;
    }

    public static void main(String[] args) throws Exception {
        // ---------------- a) topology ----------------
        Coaster c = threeRuns();
        Map<TrackPiece, String> nm = name3(c);
        check(c.runHeads().equals(List.of(0, 3, 4)) && c.runCount() == 3,
            "a1 runHeads {0,3,4} / runCount 3",
            c.runHeads() + " / " + c.runCount());
        int[] ordExp = { 1, 1, 1, 2, 3, 3 };
        boolean ordOk = true;
        for (int p = 0; p < 6; p++) ordOk &= c.runOrdinal(p) == ordExp[p];
        check(ordOk && c.runOrdinal(-1) == 0 && c.runOrdinal(6) == 0,
            "a2 runOrdinal per piece = 1,1,1,2,3,3 (0 out of range)", "");
        boolean eq = true;
        for (int p = 0; p < 6; p++) {
            eq &= c.runStartOf(p) == histStart(c, p)
                && c.runEndOf(p) == histEnd(c, p);
        }
        check(eq, "a3 runStartOf/runEndOf == the historic GraphEditor loops", "");
        check(c.followsPrev(1) && c.bridgedInto(2) && !c.followsPrev(2)
                && !c.followsPrev(3) && c.followsPrev(5) && c.endsWithBridge(1),
            "a4 link predicates: A1 follows, A2 bridged-in, B free, C1 follows", "");
        Coaster empty = new Coaster();
        check(empty.runHeads().isEmpty() && empty.runCount() == 0
                && empty.runOrdinal(0) == 0,
            "a5 empty coaster: no runs, ordinal 0", "");

        // ---------------- b) moveRun block move ----------------
        c = threeRuns();
        nm = name3(c);
        TrackCompiler.Result before = rebuild(c);
        long[][] fb = new long[6][];
        for (int p = 0; p < 6; p++) fb[p] = fieldBits(c.pieces.get(p));
        TrackPiece lastBefore = c.pieces.get(5);
        int nh = c.moveRun(4, 2);
        check(nh == 3 && order(c, nm).equals("A0 A1 A2 C0 C1 B"),
            "b1 moveRun(C.head, after A's tail) → C block after A, head at 3",
            nh + " / " + order(c, nm));
        boolean fieldsOk = true;
        String[] n = { "A0", "A1", "A2", "B", "C0", "C1" };
        for (int p = 0; p < 6; p++) {
            TrackPiece t = null;
            for (TrackPiece q : c.pieces) if (nm.get(q).equals(n[p])) t = q;
            fieldsOk &= Arrays.equals(fb[p], fieldBits(t));
        }
        check(fieldsOk, "b2 every TrackPiece field + section identity bitwise after the move", "");
        // a bare moveRun only REORDERS — the moved head stays a run
        // head until attach sets followPrev / bridge-to appends a
        // bridge behind it (d covers the attach)
        check(!c.pieces.get(3).followPrev && c.pieces.get(4).followPrev
                && c.runCount() == 3 && c.runEndOf(0) == 2 && c.runEndOf(3) == 4,
            "b3 C0 head followPrev false, C1 still follows, still 3 runs {A, C, B}",
            "runs " + c.runCount());
        // the inert-flag head normalizes (row 16): A0 at index 0 with a
        // stale followPrev, moved after C's tail
        Coaster ci = threeRuns();
        Map<TrackPiece, String> nmi = name3(ci);
        ci.pieces.get(0).followPrev = true;   // inert at index 0
        check(ci.runCount() == 3, "b4 a stale followPrev at index 0 is inert (3 runs)", "");
        int nhi = ci.moveRun(0, 5);
        check(nhi == 3 && order(ci, nmi).equals("B C0 C1 A0 A1 A2")
                && !ci.pieces.get(3).followPrev && ci.runCount() == 3,
            "b5 moving the inert-flag run normalizes its head → still 3 runs (no phantom follow)",
            nhi + " / " + order(ci, nmi) + " / runs " + ci.runCount());
        // row 31: the list's last index survives a move of a non-last run
        Coaster cl = threeRuns();
        TrackPiece last0 = cl.pieces.get(5);
        cl.moveRun(0, 3);   // A after B
        check(cl.pieces.get(5) == last0 && cl.pieces.size() == 6,
            "b6 the last list entry (a pending wizard piece's slot) survives a move of another run", "");

        // ---------------- c) compile bitwise per piece ----------------
        TrackCompiler.Result after = rebuild(c);
        boolean cmpOk = true;
        String cmpWhy = "";
        // the BEFORE compile used the fixture order (names n[]); map
        // each piece's new index back to its before-range by name
        for (int p = 0; p < 6; p++) {
            // before-index of the piece now at p
            String nmP = nm.get(c.pieces.get(p));
            int bp = Arrays.asList(n).indexOf(nmP);
            int[] rb = pieceRangeByIndexCount(c, before, bp, n, nm);
            int[] ra = pieceRange(c, after, p);
            int len = rb[1] - rb[0] + 1;
            if (len != ra[1] - ra[0] + 1) {
                cmpOk = false;
                cmpWhy = nmP + " length " + len + " vs " + (ra[1] - ra[0] + 1);
                break;
            }
            String d = identical(before.samples(), rb[0], after.samples(), ra[0], len);
            if (d != null) { cmpOk = false; cmpWhy = nmP + ": " + d; break; }
        }
        check(cmpOk, "c1 every piece's samples bitwise before vs after the move (bridge incl.)", cmpWhy);
        check(before.samples().size() == after.samples().size(),
            "c2 sample count unchanged", before.samples().size() + " / " + after.samples().size());
        TrackCompiler.Result again = rebuild(c);
        check(identical(after.samples(), 0, again.samples(), 0, after.samples().size()) == null,
            "c3 a second rebuild after the move is a bitwise no-op", "");

        // ---------------- d) connectStrictRun ----------------
        c = threeRuns();
        nm = name3(c);
        TrackCompiler.Result rd0 = rebuild(c);
        int np = c.connectStrictRun(2, 4, false, true, true);
        check(np == 3 && order(c, nm).equals("A0 A1 A2 C0 C1 B"),
            "d1 connectStrictRun(A's tail, C) → C after A, follower at 3",
            np + " / " + order(c, nm));
        TrackPiece c0 = c.pieces.get(3);
        check(c0.followPrev && !c0.sLockYaw && c0.sLockPitch && c0.sLockRoll
                && c.followsPrev(3) && c.followsPrev(4),
            "d2 head follows with locks (F,T,T); C1 still follows C0", "");
        check(c.runCount() == 2 && c.runEndOf(0) == 4 && c.runStartOf(4) == 0
                && c.endsWithBridge(1) && c.bridgedInto(2),
            "d3 one run A0..C1 — the internal bridge still targets A2", "");
        TrackCompiler.Result rd1 = rebuild(c);
        check(rd1.samples().size() > 0 && rd1.spans().size() == rd0.spans().size(),
            "d4 the rebuild runs with the same span count", rd1.spans().size() + "");
        // B (never moved, its own run) compiles bitwise
        int[] rbB = pieceRange(c, rd0, 3);   // B was index 3 before
        int[] raB = pieceRange(c, rd1, 5);   // B is index 5 now
        check(identical(rd0.samples(), rbB[0], rd1.samples(), raB[0], rbB[1] - rbB[0] + 1) == null
                && rbB[1] - rbB[0] == raB[1] - raB[0],
            "d5 the untouched run B compiles bitwise after the attach", "");

        // ---------------- e) refusals ----------------
        c = threeRuns();
        nm = name3(c);
        String o0 = order(c, nm);
        long[][] fe = new long[6][];
        for (int p = 0; p < 6; p++) fe[p] = fieldBits(c.pieces.get(p));
        check(!c.canMoveRun(1, 3) && c.moveRun(1, 3) == -1,
            "e1 a non-head (A1) refuses", "");
        check(!c.canMoveRun(4, 5) && c.moveRun(4, 5) == -1
                && !c.canMoveRun(0, 1) && c.moveRun(0, 1) == -1,
            "e2 afterIdx inside the block refuses", "");
        check(!c.canMoveRun(3, 1) && c.moveRun(3, 1) == -1,
            "e3 a bridge-ending destination (A1) refuses", "");
        check(!c.canMoveRun(-1, 0) && !c.canMoveRun(0, 6) && c.moveRun(6, 0) == -1,
            "e3b out-of-range indices refuse", "");
        // e3c a destination that is NOT its run's tail (D248 review
        // fix): landing B after A0 (A1 follows it) or after C0 (C1
        // follows it) would splice the block into the middle of that
        // run — the follower re-chained onto B's tail
        check(!c.canMoveRun(3, 0) && c.moveRun(3, 0) == -1
                && !c.canMoveRun(3, 4) && c.moveRun(3, 4) == -1,
            "e3c a mid-run destination (A0 with A1 following, C0 with C1 following) refuses", "");
        check(c.canMoveRun(3, 5), "e3d ... while the run's TAIL (C1) is a legal destination", "");
        boolean untouched = order(c, nm).equals(o0);
        for (int p = 0; p < 6; p++) untouched &= Arrays.equals(fe[p], fieldBits(c.pieces.get(p)));
        check(untouched, "e4 refusals leave the list + every field untouched", "");
        // e4b the minimal [A, B(followPrev), C] fixture of the review
        // finding: moveRun(2, 0) must refuse, list untouched
        {
            Coaster m = new Coaster();
            TrackPiece ma = new TrackPiece(0, 21, 0, 0);
            ma.sections.add(new ForceSection(forceLanes(), 25));
            ma.sections.add(new ForceSection(shortLanes(1.3, 8), 22));
            m.pieces.add(ma);
            int fm = m.makeSeamStrict(0, 1, DT);   // B follows A
            if (fm != 1) throw new IllegalStateException("makeSeamStrict m " + fm);
            TrackPiece mc = new TrackPiece(40, 15, 30, 60);
            mc.sections.add(new ForceSection(shortLanes(2.0, -15), 20));
            m.pieces.add(mc);
            List<TrackPiece> mBefore = new ArrayList<>(m.pieces);
            check(m.followsPrev(1) && m.runEndOf(0) == 1 && m.runStartOf(2) == 2,
                "e4b fixture [A, B(follows A), C]: two runs", "");
            check(!m.canMoveRun(2, 0) && m.moveRun(2, 0) == -1
                    && m.pieces.equals(mBefore) && m.followsPrev(1),
                "e4c moveRun(C, after A) refuses — A is not its run's end — and the list is untouched",
                m.runHeads() + "");
            check(m.canMoveRun(2, 1) && m.moveRun(2, 1) == 2 && m.pieces.equals(mBefore),
                "e4d moveRun(C, after B) = the no-move case still passes (already there)", "");
        }
        // an ORPHAN-bridge run (edge rows 11-13): {B}, {C0, C1}, {O}
        // with O's bridge targeting nothing — necessarily the last run
        Coaster co = orphanLast();
        check(co.listEndsInOrphanBridge() && co.runCount() == 3
                && co.runHeads().equals(List.of(0, 1, 3)),
            "e5 orphanLast fixture: 3 runs, the orphan bridge last", co.runHeads() + "");
        // a plain run may land right BEFORE the orphan run (O stays last)
        check(co.canMoveRun(0, 2), "e5b a plain run may land right before the orphan-bridge run", "");
        int nho = co.moveRun(0, 2);
        check(nho == 2 && co.runHeads().equals(List.of(0, 2, 3))
                && co.listEndsInOrphanBridge(),
            "e5c ... and the orphan bridge stays orphan and last",
            nho + " / " + co.runHeads());
        // now C0 C1 B O: O (index 3) may NOT land after C1 (index 1) —
        // B would be seized as its target and the runs would merge
        long[] fo = fieldBits(co.pieces.get(3));
        check(!co.canMoveRun(3, 1) && co.moveRun(3, 1) == -1,
            "e6 an orphan-bridge run refuses to land anywhere but where it is", "");
        check(co.runHeads().equals(List.of(0, 2, 3))
                && Arrays.equals(fo, fieldBits(co.pieces.get(3))),
            "e6b the refusal touched nothing", "");
        // "already last" = the '+' lists' (next) no-move case: allowed,
        // and moveRun is then the identity
        check(co.canMoveRun(3, 2) && co.moveRun(3, 2) == 3
                && co.runHeads().equals(List.of(0, 2, 3)),
            "e7 the orphan-bridge run may 'land' where it already is (the (next) case, identity)", "");
        // nothing may land AFTER the orphan-bridge run — a bridge-ending
        // destination (its bridge would seize the arrival)
        check(!co.canMoveRun(0, 3) && co.moveRun(0, 3) == -1
                && !co.canMoveRun(2, 3),
            "e8 nothing may land after the orphan-bridge run (a bridge-ending destination)", "");

        // ---------------- f) run of one == movePiece / connectStrict ----------------
        Coaster f1 = threeRuns(), f2 = threeRuns();
        Map<TrackPiece, String> nf1 = name3(f1), nf2 = name3(f2);
        int toF = 3 < 5 ? 5 : 6;   // connectStrict's arithmetic for (target 5, floating 3)
        f1.movePiece(3, toF);
        int nhf = f2.moveRun(3, 5);
        check(nhf == toF && order(f1, nf1).equals(order(f2, nf2))
                && order(f2, nf2).equals("A0 A1 A2 C0 C1 B"),
            "f1 run of one: moveRun == movePiece with connectStrict's index arithmetic",
            nhf + " / " + order(f2, nf2));
        Coaster g1 = threeRuns(), g2 = threeRuns();
        int r1 = g1.connectStrict(5, 3, true, false, true);
        int r2 = g2.connectStrictRun(5, 3, true, false, true);
        boolean sameFields = r1 == r2;
        for (int p = 0; p < 6 && sameFields; p++) {
            long[] x = fieldBits(g1.pieces.get(p)), y = fieldBits(g2.pieces.get(p));
            // section identities differ between two fixtures: compare
            // the numeric/flag prefix only
            sameFields &= Arrays.equals(Arrays.copyOf(x, 23), Arrays.copyOf(y, 23));
        }
        check(sameFields, "f2 run of one: connectStrictRun == connectStrict field for field", r1 + " / " + r2);
        TrackCompiler.Result rg1 = rebuild(g1), rg2 = rebuild(g2);
        check(identical(rg1.samples(), 0, rg2.samples(), 0, rg1.samples().size()) == null
                && rg1.samples().size() == rg2.samples().size(),
            "f3 run of one: both attach flows compile bitwise", "");

        // ---------------- g) save→load after a move ----------------
        c = threeRuns();
        c.moveRun(4, 2);
        TrackCompiler.Result rs0 = rebuild(c);
        Coaster rl = roundTrip(c);
        TrackCompiler.Result rs1 = rebuild(rl);
        check(rl.pieces.size() == 6 && rl.runHeads().equals(c.runHeads())
                && identical(rs0.samples(), 0, rs1.samples(), 0, rs0.samples().size()) == null
                && rs0.samples().size() == rs1.samples().size(),
            "g1 save→load→rebuild after the move: order + samples bitwise",
            rl.runHeads() + "");

        // ---------------- h) runTailEnd ----------------
        c = threeRuns();
        TrackCompiler.Result rh = rebuild(c);
        double[] e0 = Coaster.runTailEnd(c, rh.samples(), rh.spans(), null, 0);
        double[] e1 = Coaster.runTailEnd(c, rh.samples(), rh.spans(), null, 1);
        double[] e2 = Coaster.runTailEnd(c, rh.samples(), rh.spans(), null, 2);
        boolean sameEnd = e0 != null && e1 != null && e2 != null;
        for (int i = 0; sameEnd && i < 5; i++) {
            sameEnd &= bits(e0[i], e1[i]) && bits(e0[i], e2[i]);
        }
        check(sameEnd, "h1 every member of run A places the wizard piece off the TAIL's end", "");
        // the expected: A2's last sample + 5 m along its horizontal heading
        int[] ra2 = pieceRange(c, rh, 2);
        FVDIntegrator.Sample tailS = rh.samples().get(ra2[1]);
        double hl = Math.hypot(tailS.T().x(), tailS.T().z());
        double hx = tailS.T().x() / hl, hz = tailS.T().z() / hl;
        check(e0 != null && bits(e0[0], tailS.pos().x() + 5 * hx)
                && bits(e0[1], tailS.pos().y())
                && bits(e0[2], tailS.pos().z() + 5 * hz)
                && bits(e0[4], tailS.v()),
            "h2 runTailEnd == the tail's last sample + 5 m along its heading", "");
        double[] eB = Coaster.runTailEnd(c, rh.samples(), rh.spans(), null, 3);
        int[] rB = pieceRange(c, rh, 3);
        check(eB != null && bits(eB[1], rh.samples().get(rB[1]).pos().y()),
            "h3 a lone run's tail is itself", "");
        check(Coaster.runTailEnd(c, null, rh.spans(), null, 0) == null
                && Coaster.runTailEnd(c, rh.samples(), rh.spans(), null, 9) == null,
            "h4 null without samples / out of range", "");
        double[] tv = new double[rh.samples().size()];
        Arrays.fill(tv, 7.5);
        double[] eT = Coaster.runTailEnd(c, rh.samples(), rh.spans(), tv, 5);
        check(eT != null && bits(eT[4], 7.5), "h5 the TRUE speed array feeds the entry speed", "");

        // ---------------- i) runSampleRange / runLengthM ----------------
        int[] rA = Coaster.runSampleRange(c, rh.spans(), 0);
        int[] a0r = pieceRange(c, rh, 0), a2r = pieceRange(c, rh, 2);
        check(rA != null && rA[0] == a0r[0] && rA[1] == a2r[1],
            "i1 run A's range = A0's first sample .. A2's last (bridge inside)",
            rA == null ? "null" : rA[0] + ".." + rA[1]);
        int[] rBr = Coaster.runSampleRange(c, rh.spans(), 3);
        check(rBr != null && rBr[0] == rB[0] && rBr[1] == rB[1],
            "i2 a lone run's range is its own samples only (no foreign seam sample)",
            rBr == null ? "null" : rBr[0] + ".." + rBr[1]);
        int[] rC = Coaster.runSampleRange(c, rh.spans(), 4);
        int[] c1r = pieceRange(c, rh, 5), c0r = pieceRange(c, rh, 4);
        check(rC != null && rC[0] == c0r[0] && rC[1] == c1r[1]
                && Coaster.runSampleRange(c, rh.spans(), 5) != null
                && Coaster.runSampleRange(c, rh.spans(), 5)[1] == c1r[1],
            "i3 run C's range spans the strict seam; a member index reads its run", "");
        check(Coaster.runSampleRange(c, null, 0) == null
                && Coaster.runSampleRange(c, rh.spans(), 6) == null,
            "i4 null without spans / out of range", "");
        double lenA = 0;
        for (int k = 0; k < 5; k++) lenA += rh.spans().get(k).sLen();   // A0: 2, A1: 2 (+bridge), A2: 1 = 5 spans
        check(bits(Coaster.runLengthM(c, rh.spans(), 0), lenA)
                && bits(Coaster.runLengthM(c, rh.spans(), 2), Double.NaN) == false
                && Double.isNaN(Coaster.runLengthM(c, null, 0)),
            "i5 runLengthM(head A) == the sum of its 5 spans; NaN without spans",
            String.format(Locale.US, "%.3f", lenA));
        double lenB = rh.spans().get(5).sLen();
        check(bits(Coaster.runLengthM(c, rh.spans(), 3), lenB),
            "i6 runLengthM(B) == its one span", "");

        // ---------------- j) deleteRun (D249) ----------------
        {
            // j1 a MIDDLE run (B at 3): the run after (C0) activates at 3
            Coaster d = threeRuns();
            Map<TrackPiece, String> dn = name3(d);
            long[][] fbj = new long[6][];
            for (int i = 0; i < 6; i++) fbj[i] = fieldBits(d.pieces.get(i));
            int nb = d.deleteRun(3);
            check(nb == 3 && order(d, dn).equals("A0 A1 A2 C0 C1")
                    && !d.pieces.get(3).followPrev && d.followsPrev(4)
                    && d.runHeads().equals(List.of(0, 3)),
                "j1 delete the middle run B: the run after activates at its index",
                "nb " + nb + " order " + order(d, dn));
            boolean surv = Arrays.equals(fbj[0], fieldBits(d.pieces.get(0)))
                && Arrays.equals(fbj[1], fieldBits(d.pieces.get(1)))
                && Arrays.equals(fbj[2], fieldBits(d.pieces.get(2)))
                && Arrays.equals(fbj[4], fieldBits(d.pieces.get(3)))
                && Arrays.equals(fbj[5], fieldBits(d.pieces.get(4)));
            check(surv, "j1b every surviving piece's fields + section identity bitwise", "");
            // j2 the LAST run (C at 4): the previous run's head (B at 3)
            Coaster e = threeRuns();
            Map<TrackPiece, String> en = name3(e);
            int nbe = e.deleteRun(4);
            check(nbe == 3 && order(e, en).equals("A0 A1 A2 B")
                    && e.runHeads().equals(List.of(0, 3)),
                "j2 delete the last run C: the previous run's head activates",
                "nb " + nbe + " order " + order(e, en));
            // j3 the FIRST run (A: strict follower + bridge + bridged-in
            // target = 3 pieces at once): B heads the list, its flag inert
            Coaster f = threeRuns();
            Map<TrackPiece, String> fn = name3(f);
            int nbf = f.deleteRun(0);
            check(nbf == 0 && order(f, fn).equals("B C0 C1")
                    && !f.pieces.get(0).followPrev
                    && f.runHeads().equals(List.of(0, 1)),
                "j3 delete the first run A (bridge + target inside): 3 pieces go, B heads",
                "nb " + nbf + " order " + order(f, fn));
            // j4 the ONLY run → empty coaster, −1
            Coaster g = new Coaster();
            TrackPiece gp = new TrackPiece(0, 21, 0, 0);
            gp.sections.add(new ForceSection(forceLanes(), 26));
            g.pieces.add(gp);
            g.v0 = 26;
            int nbg = g.deleteRun(0);
            check(nbg == -1 && g.pieces.isEmpty() && g.v0 == 26,
                "j4 delete the coaster's only run: empty coaster, −1, v0 the caller's",
                "nb " + nbg);
            // j5 the orphan-bridge run (the list's last run) goes with its bridge
            Coaster h = orphanLast();
            check(h.listEndsInOrphanBridge(), "j5a fixture: the list ends in an orphan bridge", "");
            int nbh = h.deleteRun(3);
            check(nbh == 1 && h.pieces.size() == 3 && !h.listEndsInOrphanBridge()
                    && h.runHeads().equals(List.of(0, 1)),
                "j5b delete the orphan-bridge run: the previous run's head (C0) activates",
                "nb " + nbh);
            // j6 the run-head TOPOLOGY invariant over both fixtures: the
            // piece before a run head never ends in a bridge — so a
            // deleted run's successor can never be re-targeted by a
            // bridge (the brief's confirm sentence is unreachable)
            boolean inv = true;
            for (Coaster x : List.of(threeRuns(), orphanLast())) {
                for (int hd : x.runHeads()) {
                    if (hd > 0 && x.endsWithBridge(hd - 1)) inv = false;
                    if (x.runStartOf(hd) != hd) inv = false;
                }
            }
            check(inv, "j6 no run head is preceded by a bridge-ending piece (topology invariant)", "");
            // j7 refusals: a non-head, out of range — list untouched
            Coaster r = threeRuns();
            Map<TrackPiece, String> rn = name3(r);
            long[][] rb = new long[6][];
            for (int i = 0; i < 6; i++) rb[i] = fieldBits(r.pieces.get(i));
            boolean ref = r.deleteRun(1) == -2 && r.deleteRun(2) == -2
                && r.deleteRun(5) == -2 && r.deleteRun(-1) == -2
                && r.deleteRun(6) == -2;
            boolean same = r.pieces.size() == 6;
            for (int i = 0; same && i < 6; i++) {
                same = Arrays.equals(rb[i], fieldBits(r.pieces.get(i)));
            }
            check(ref && same && order(r, rn).equals("A0 A1 A2 B C0 C1"),
                "j7 deleteRun refuses a non-head / out-of-range index (−2) and touches nothing", "");
            // j8 the UNDO model: the removed pieces are the same untouched
            // objects — re-inserting them compiles bitwise the pre-delete
            // document (what applyDocState's snapshot adoption restores)
            Coaster u = threeRuns();
            TrackCompiler.Result u0 = rebuild(u);
            List<TrackPiece> removed = new ArrayList<>(u.pieces.subList(3, 4));
            check(u.deleteRun(3) == 3 && u.pieces.size() == 5,
                "j8a delete run B from the settled document", "");
            TrackCompiler.Result u1 = rebuild(u);
            check(u1.spans().size() == u0.spans().size() - 1,
                "j8b the remaining document compiles one span short", "");
            u.pieces.addAll(3, removed);
            TrackCompiler.Result u2 = rebuild(u);
            String why8 = u2.samples().size() != u0.samples().size()
                ? "size " + u2.samples().size() + " vs " + u0.samples().size()
                : identical(u0.samples(), 0, u2.samples(), 0, u0.samples().size());
            check(why8 == null, "j8c re-inserting the removed run compiles bitwise the pre-delete document",
                why8 == null ? "" : why8);
            // j9 a deleted run in the middle: the remaining runs compile
            // bitwise their pre-delete samples (runs are independent)
            Coaster w = threeRuns();
            TrackCompiler.Result w0 = rebuild(w);
            int[] wA = pieceRange(w, w0, 0), wA2 = pieceRange(w, w0, 2);
            int[] wC0 = pieceRange(w, w0, 4), wC1 = pieceRange(w, w0, 5);
            check(w.deleteRun(3) == 3, "j9a delete run B", "");
            TrackCompiler.Result w1 = rebuild(w);
            int[] vA = pieceRange(w, w1, 0), vA2 = pieceRange(w, w1, 2);
            int[] vC0 = pieceRange(w, w1, 3), vC1 = pieceRange(w, w1, 4);
            String whyA = identical(w0.samples(), wA[0], w1.samples(), vA[0], wA2[1] - wA[0] + 1);
            String whyC = identical(w0.samples(), wC0[0], w1.samples(), vC0[0], wC1[1] - wC0[0] + 1);
            check(whyA == null && whyC == null,
                "j9b runs A and C compile bitwise after B's deletion",
                (whyA == null ? "" : "A: " + whyA) + (whyC == null ? "" : " C: " + whyC));
            // j10 (D249 review) deleting run after run — the SECOND head
            // each time, the last one when a single run is left: every
            // return is a run HEAD of the remaining list, the run count
            // drops by exactly one per delete, the last delete empties
            // the coaster (−1)
            Coaster z = threeRuns();
            boolean seq = true;
            int steps = 0;
            while (!z.pieces.isEmpty() && seq && steps < 10) {
                int nRuns10 = z.runCount();
                List<Integer> hs = z.runHeads();
                int at = hs.get(Math.min(1, hs.size() - 1));
                int r10 = z.deleteRun(at);
                steps++;
                seq = z.runCount() == nRuns10 - 1
                    && (z.pieces.isEmpty() ? r10 == -1
                        : r10 >= 0 && r10 < z.pieces.size()
                            && z.runStartOf(r10) == r10);
            }
            check(seq && z.pieces.isEmpty() && steps == 3,
                "j10 deleting run after run: a head returned every time, run count −1 per delete, −1 when empty",
                "steps " + steps);
        }

        // ---------------- k) moveRunTo (batch 37) ----------------
        {
            // k1 run A (strict follower + bridge + bridged-in target)
            // moves to a coaster that already holds a run of two
            Coaster src = threeRuns(), dst = destCoaster();
            Map<TrackPiece, String> sn = name3(src);
            TrackCompiler.Result s0 = rebuild(src), d0 = rebuild(dst);
            List<TrackPiece> block = new ArrayList<>(src.pieces.subList(0, 3));
            List<TrackPiece> dPrior = new ArrayList<>(dst.pieces);
            long[][] fk = new long[6][];
            for (int i = 0; i < 6; i++) fk[i] = fieldBits(src.pieces.get(i));
            long[][] fd = new long[2][];
            for (int i = 0; i < 2; i++) fd[i] = fieldBits(dst.pieces.get(i));
            int[] sA = { pieceRange(src, s0, 0)[0], pieceRange(src, s0, 2)[1] };
            int[] sT = pieceRange(src, s0, 2);   // A2, the bridged-in target
            int[] sB = pieceRange(src, s0, 3);
            int[] sC = { pieceRange(src, s0, 4)[0], pieceRange(src, s0, 5)[1] };
            int[] dD = { pieceRange(dst, d0, 0)[0], pieceRange(dst, d0, 1)[1] };
            int nhk = src.moveRunTo(dst, 0);
            check(nhk == 2 && dst.pieces.size() == 5
                    && dst.pieces.subList(0, 2).equals(dPrior)
                    && dst.pieces.subList(2, 5).equals(block)
                    && order(src, sn).equals("B C0 C1"),
                "k1 moveRunTo: run A lands contiguous + in order at the destination's end",
                "nh " + nhk + " / src " + order(src, sn));
            boolean fOk = true;
            for (int i = 0; i < 3; i++) fOk &= Arrays.equals(fk[i], fieldBits(dst.pieces.get(2 + i)));
            for (int i = 0; i < 3; i++) fOk &= Arrays.equals(fk[3 + i], fieldBits(src.pieces.get(i)));
            for (int i = 0; i < 2; i++) fOk &= Arrays.equals(fd[i], fieldBits(dst.pieces.get(i)));
            check(fOk && !dst.pieces.get(2).followPrev,
                "k2 every TrackPiece field + section identity bitwise (moved, source remainder, destination's prior)", "");
            check(dst.runHeads().equals(List.of(0, 2)) && dst.runEndOf(2) == 4
                    && dst.followsPrev(3) && dst.bridgedInto(4)
                    && src.runHeads().equals(List.of(0, 1)) && src.followsPrev(2),
                "k3 links intact on both sides (index-relative inside the block)",
                dst.runHeads() + " / " + src.runHeads());
            TrackCompiler.Result s1 = rebuild(src), d1 = rebuild(dst);
            int[] mA = { pieceRange(dst, d1, 2)[0], pieceRange(dst, d1, 4)[1] };
            // run A lands BEHIND the destination's own run, so its
            // global clock / arc offsets change: the lane spans and the
            // bridged-in target's GEOMETRY stay BITWISE (canonical
            // generation), the SMOOTHED seam's window and the BRIDGE's
            // time resampling keep their known offset dust (the
            // CopyGate family) — measured 1.2e-11 m — and the TRUE
            // speed past them carries it (Pass D marches the whole run)
            int nA = sA[1] - sA[0] + 1;
            boolean lenOk = mA[1] - mA[0] == sA[1] - sA[0];
            TrackCompiler.Span h0 = s0.spans().get(0);        // A0's first section
            String wHead = !lenOk ? "run length differs"
                : identicalTrue(s0, h0.i0(), d1, mA[0] + (h0.i0() - sA[0]),
                    h0.i1() - h0.i0() + 1);
            String wTgt = !lenOk ? "run length differs"
                : identical(s0.samples(), sT[0], d1.samples(),
                    mA[0] + (sT[0] - sA[0]), sT[1] - sT[0] + 1);
            double devA = lenOk ? maxDev(s0, sA[0], d1, mA[0], nA) : Double.NaN;
            check(wHead == null && wTgt == null && devA <= 1e-9,
                "k4 the moved run at the destination: the head's lane span + the bridged-in target's"
                + " geometry BITWISE, the smoothed window + bridge (and the true speed past them) within 1e-9",
                (wHead == null ? "" : "head: " + wHead + " ") + (wTgt == null ? "" : "target: " + wTgt + " ")
                    + String.format(Locale.US, "%d samples, worst %.2e", nA, devA));
            // k4b a run with NO smoothing window and NO bridge (run C:
            // head + follower with a live design tier) is bitwise whole
            {
                Coaster s2c = threeRuns(), d2c = destCoaster();
                TrackCompiler.Result cRun0 = rebuild(s2c);
                rebuild(d2c);
                int[] rc = { pieceRange(s2c, cRun0, 4)[0], pieceRange(s2c, cRun0, 5)[1] };
                int nhc = s2c.moveRunTo(d2c, 4);
                TrackCompiler.Result cRun1 = rebuild(d2c);
                int m0 = nhc < 0 ? 0 : pieceRange(d2c, cRun1, nhc)[0];
                String wc = nhc != 2 ? "refused " + nhc
                    : identicalTrue(cRun0, rc[0], cRun1, m0, rc[1] - rc[0] + 1);
                check(wc == null && d2c.followsPrev(3),
                    "k4b a plain strict run (no smoothing, no bridge) compiles BITWISE at the destination",
                    wc == null ? (rc[1] - rc[0] + 1) + " samples" : wc);
            }
            // k4c the SAME smoothed + bridged run A moved to an EMPTY
            // coaster (its offsets stay 0) is bitwise whole — the dust
            // above is the offsets', not the move's
            {
                Coaster s3 = threeRuns(), d3 = new Coaster();
                TrackCompiler.Result e0r = rebuild(s3);
                int[] ra = { pieceRange(s3, e0r, 0)[0], pieceRange(s3, e0r, 2)[1] };
                int nhe = s3.moveRunTo(d3, 0);
                TrackCompiler.Result e1r = rebuild(d3);
                String we = nhe != 0 ? "refused " + nhe
                    : identicalTrue(e0r, ra[0], e1r, 0, ra[1] - ra[0] + 1);
                check(we == null && e1r.samples().size() == ra[1] - ra[0] + 1,
                    "k4c the smoothed + bridged run moved to an EMPTY coaster compiles BITWISE whole",
                    we == null ? (ra[1] - ra[0] + 1) + " samples" : we);
            }
            int[] nB = pieceRange(src, s1, 0);
            int[] nC = { pieceRange(src, s1, 1)[0], pieceRange(src, s1, 2)[1] };
            int[] nD = { pieceRange(dst, d1, 0)[0], pieceRange(dst, d1, 1)[1] };
            String wB = identicalTrue(s0, sB[0], s1, nB[0], sB[1] - sB[0] + 1);
            String wC = identicalTrue(s0, sC[0], s1, nC[0], sC[1] - sC[0] + 1);
            String wD = identicalTrue(d0, dD[0], d1, nD[0], dD[1] - dD[0] + 1);
            check(wB == null && wC == null && wD == null,
                "k5 the source remainder and the destination's prior run compile bitwise",
                (wB == null ? "" : "B: " + wB) + (wC == null ? "" : " C: " + wC)
                    + (wD == null ? "" : " D: " + wD));
            // k6 save→load→rebuild of BOTH coasters
            TrackCompiler.Result s2 = rebuild(roundTrip(src)), d2 = rebuild(roundTrip(dst));
            check(s2.samples().size() == s1.samples().size()
                    && d2.samples().size() == d1.samples().size()
                    && identicalTrue(s1, 0, s2, 0, s1.samples().size()) == null
                    && identicalTrue(d1, 0, d2, 0, d1.samples().size()) == null,
                "k6 save→load→rebuild of both coasters bitwise", "");
        }
        {
            // k7 refusals: −1, both lists untouched
            Coaster src = threeRuns(), dst = destCoaster();
            List<TrackPiece> sBefore = new ArrayList<>(src.pieces);
            List<TrackPiece> dBefore = new ArrayList<>(dst.pieces);
            long[][] fs = new long[6][];
            for (int i = 0; i < 6; i++) fs[i] = fieldBits(src.pieces.get(i));
            boolean ref = src.moveRunTo(dst, 1) == -1      // a follower
                && src.moveRunTo(dst, 2) == -1             // a bridged-in piece
                && src.moveRunTo(dst, 5) == -1
                && src.moveRunTo(dst, -1) == -1
                && src.moveRunTo(dst, 6) == -1
                && src.moveRunTo(null, 0) == -1
                && src.moveRunTo(src, 0) == -1;
            Coaster fric = destCoaster();
            fric.fricRoll = 0.03;
            List<TrackPiece> fBefore = new ArrayList<>(fric.pieces);
            ref &= src.moveRunTo(fric, 0) == -1;
            Coaster air = destCoaster();
            air.fricAir = 1.0e-4;
            ref &= src.moveRunTo(air, 0) == -1;
            Coaster orph = orphanLast();
            List<TrackPiece> oBefore = new ArrayList<>(orph.pieces);
            ref &= src.moveRunTo(orph, 0) == -1;
            boolean same = src.pieces.equals(sBefore) && dst.pieces.equals(dBefore)
                && fric.pieces.equals(fBefore) && orph.pieces.equals(oBefore);
            for (int i = 0; same && i < 6; i++) {
                same = Arrays.equals(fs[i], fieldBits(src.pieces.get(i)));
            }
            check(ref && same,
                "k7 moveRunTo refuses a non-head / out of range / null / the same coaster / different friction /"
                + " a destination ending in an orphan bridge (−1) and touches nothing", "");
            // k8 an ORPHAN-bridge run MAY move: it lands last, stays orphan
            Coaster os = orphanLast(), od = destCoaster();
            TrackPiece o = os.pieces.get(3);
            int nho2 = os.moveRunTo(od, 3);
            check(nho2 == 2 && od.pieces.get(2) == o && od.listEndsInOrphanBridge()
                    && !os.listEndsInOrphanBridge() && os.pieces.size() == 3
                    && od.runHeads().equals(List.of(0, 2)),
                "k8 an orphan-bridge run moves, lands last and stays orphan",
                "nh " + nho2 + " / " + od.runHeads());
            // k9 the source may go EMPTY (D239); an empty destination takes it at 0
            Coaster one = new Coaster(), none = new Coaster();
            TrackPiece op = new TrackPiece(0, 21, 0, 0);
            op.sections.add(new ForceSection(forceLanes(), 26));
            one.pieces.add(op);
            one.v0 = 26;
            TrackCompiler.Result only0 = rebuild(one);
            int nh9 = one.moveRunTo(none, 0);
            TrackCompiler.Result only1 = rebuild(none);
            check(nh9 == 0 && one.pieces.isEmpty() && none.pieces.size() == 1
                    && none.pieces.get(0) == op && one.v0 == 26
                    && only0.samples().size() == only1.samples().size()
                    && identicalTrue(only0, 0, only1, 0, only0.samples().size()) == null,
                "k9 the only run moves to an empty coaster: source empty, v0 the caller's, compile bitwise", "");
            // k10 an INERT followPrev flag on the moved head (index 0)
            // must not go live behind the destination's last piece; the
            // source's successor takes deleteRun's belt
            Coaster is = new Coaster(), id = destCoaster();
            TrackPiece inert0 = new TrackPiece(0, 21, 0, 0);
            inert0.sections.add(new ForceSection(forceLanes(), 26));
            inert0.followPrev = true;                  // inert at index 0
            is.pieces.add(inert0);
            TrackPiece inert1 = new TrackPiece(40, 25, 10, 30);
            inert1.sections.add(new ForceSection(shortLanes(1.4, 5), 24));
            is.pieces.add(inert1);
            boolean heads = is.runHeads().equals(List.of(0, 1));
            int nh10 = is.moveRunTo(id, 0);
            check(heads && nh10 == 2 && !id.pieces.get(2).followPrev
                    && id.runStartOf(2) == 2 && id.runHeads().equals(List.of(0, 2))
                    && is.pieces.size() == 1 && !is.pieces.get(0).followPrev,
                "k10 the moved head's inert followPrev normalizes — it heads its own run at the destination",
                "nh " + nh10 + " / " + id.runHeads());
        }

        System.out.println("RunGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** The BEFORE-compile range of the piece named n[bp] — the before
     *  compile used the fixture order, so its index is bp. */
    static int[] pieceRangeByIndexCount(Coaster cNow, TrackCompiler.Result before,
                                        int bp, String[] n, Map<TrackPiece, String> nm) {
        // section counts in fixture order: rebuild them from the names
        int[] counts = new int[6];
        for (TrackPiece t : cNow.pieces) {
            counts[Arrays.asList(n).indexOf(nm.get(t))] = t.sections.size();
        }
        int b = 0;
        for (int q = 0; q < bp; q++) b += counts[q];
        return new int[]{ before.spans().get(b).i0(),
            before.spans().get(b + counts[bp] - 1).i1() };
    }
}
