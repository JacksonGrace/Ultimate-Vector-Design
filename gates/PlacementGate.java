import coaster.*;

import java.io.*;
import java.util.*;

/** D248 gate — the three placement WINDOWS' model half (v19.19):
 *
 *  a) defaults: a fresh TrackPiece is LOCKSTEP (D249's 3-state
 *     orientMode; = D248's unlocked, superseding D222's locked
 *     default) with affectSubsequentSeams FALSE (the D249 inverted
 *     rename of Only-Affect); Coaster.carriesDownstream is false for
 *     it. "Locked" below = REAL_ONLY, "Only-Affect ON" = affect off.
 *  b) carry scoping (Coaster.carryAfterEdit — placementEdit's choke):
 *     b1 unlocked: no carry; b2 locked + Only-Affect ON (default): NO
 *     carry — a manual-bridge target's x/y/z + orientation and a
 *     follower's held angles stay BITWISE; b3 locked + Only-Affect
 *     OFF: bitwise today's full rigid carry (== carryDownstream on a
 *     twin); b4 the predicate is per piece.
 *  c) porient IO: the 5-field D249 line round-trips byte-identically
 *     for every LOCKSTEP/REAL_ONLY × carry combination; a 3-field
 *     (v19.0-v19.18) line keeps field 1 and defaults the carry OFF;
 *     the D221 interim 2-field line still migrates (LOCKSTEP + design
 *     := true) with the carry off. (OrientModeGate covers the D248
 *     4-field inversion + DESIGN_ONLY.)
 *  d) a MISSING porient line loads LOCKSTEP + no carry + (0,0) and
 *     the compile is BITWISE the same document with the line present
 *     (the compile never reads either flag).
 *  e) TrackPiece.copy carries both flags.
 *  f) bridge-target routing: a routed pitch write (setDispPitch(v,
 *     true) — what setBridgeTargetOrient does since D248) on an
 *     UNLOCKED target moves both tiers (no conflict) and re-solves to
 *     bakeTilt's shape; on a LOCKED target the conflict opens and the
 *     design tier holds.
 *  g) bridge-target Design → Real: g1 under OPT_POS the SIG_STALE
 *     stamp + design := true re-marches on the next rebuild (the
 *     stamp is consumed, the bridge lands on the target, the conflict
 *     is closed, and a further rebuild is a no-op); g2 under OPT_NONE
 *     nothing marches and the stamp is inert; g3 Real → Design (true
 *     := design) then the OPT_POS re-place at lastOptDist lands with
 *     the conflict closed.
 *  h) BRIDGE LOCKS — the SEAM RULE (v19.20, D249, batch 34 item B;
 *     rows reference scratchpad/b34_edges_B-bridgelocks.md): h0 the
 *     model predicate BridgeSection.axisEditable / axisAligned truth
 *     table (rows 1.2, 2.1, 3.1); h1 a padlock flip under OPT_POS_YAW
 *     = the flag + a re-march at lastOptDist (GraphEditor.setBridgeLock's
 *     model twin, from EITHER window since D249): a held axis is never
 *     touched by the march (h1a), unlocking aligns it (h1b — LOCKSTEP:
 *     both tiers), re-locking CAPTURES the current angle bitwise and the
 *     next march meets it (h1c, row 1.3) and lands; h2 a TYPED held
 *     value under OPT_POS_YAW (the box is editable there now) followed
 *     by the bridgeTargetWrite tail (re-march, alignYaw): the value
 *     survives the march, the bridge lands, LOCKSTEP keeps design ==
 *     true, the next rebuild is a no-op (row 1.1); h2b the REAL_ONLY
 *     twin: design untouched, conflict, still lands (rows 5.1/5.2); h4
 *     OPT_POS: a free yaw typed is kept by the position-only re-place;
 *     OPT_NONE: a held pitch typed, the septic re-captures (rows 2.1/
 *     3.1); h7 a lock flip under OPT_POS / OPT_NONE moves nothing —
 *     target fields and samples bitwise (row 2.2); h8 a blen line
 *     without fields 6-8 loads the defaults and compiles bitwise (row
 *     7.4). The UI half (the enable rule on both windows, the hint
 *     strings, the held-axis rings, the Track Piece padlock routing)
 *     is verified by reading.
 */
public class PlacementGate {

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

    static List<List<BezierBaker.Node>> turnLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {3, 1.3}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.5}, {4.5, 0.5}, {6, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {6, 0} }));
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

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static boolean samplesBitwise(List<FVDIntegrator.Sample> a,
                                  List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            FVDIntegrator.Sample x = a.get(i), y = b.get(i);
            if (!bits(x.pos().x(), y.pos().x()) || !bits(x.pos().y(), y.pos().y())
                    || !bits(x.pos().z(), y.pos().z()) || !bits(x.v(), y.v())
                    || !bits(x.T().x(), y.T().x()) || !bits(x.U().y(), y.U().y())) {
                return false;
            }
        }
        return true;
    }

    static double maxDev(List<FVDIntegrator.Sample> a,
                         List<FVDIntegrator.Sample> b) {
        double w = 0;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            w = Math.max(w, a.get(i).pos().sub(b.get(i).pos()).length());
        }
        return Math.max(w, Math.abs(a.size() - b.size()) * 1e9);
    }

    /** head (hill) → MANUAL bridge → target (flat) → strict follower
     *  with ALL THREE axes held. Returns the coaster; pieces 0/1/2. */
    static Coaster chain(int optMode) {
        Coaster c = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(hA);
        TrackPiece hB = new TrackPiece(120, 14, 30, 20);
        hB.sections.add(new ForceSection(flatLanes(3), 24));
        c.pieces.add(hB);
        TrackPiece hC = new TrackPiece(0, 0, 0, 0);
        hC.sections.add(new ForceSection(turnLanes(), 24));
        hC.followPrev = true;
        hC.sLockYaw = true;
        hC.sLockPitch = true;
        hC.sLockRoll = true;
        c.pieces.add(hC);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        BridgeSection hbr = BridgeSection.solve(r0.spans().get(0).exit(),
            hB.entryPose(),
            r0.samples().get(r0.spans().get(0).i1()).v());
        hbr.optMode = optMode;
        hA.sections.add(hbr);
        TrackCompiler.Result r1 = rebuild(c);
        // seed the follower's held angles from its seam (no jump)
        double[] e = Coaster.eulerOf(Coaster.strictEntry(
            r1.spans().get(2).exit(), hC).orient());
        hC.sYawDeg = Double.isNaN(e[0]) ? 0 : e[0];
        hC.sPitchDeg = e[1] + 5;
        hC.sRollDeg = (Double.isNaN(e[2]) ? 0 : e[2]) - 4;
        rebuild(c);
        return c;
    }

    static String save(Coaster c) {
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3], null);
        try (PrintWriter pw = new PrintWriter(sw)) {
            DesignIO.save(c, cos, pw);
        }
        return sw.toString();
    }

    static Coaster load(String txt) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(txt)))
            .coaster();
    }

    static long[] pieceBits(TrackPiece t) {
        double[] d = { t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
            t.designPitchDeg, t.designRollDeg, t.sYawDeg, t.sPitchDeg,
            t.sRollDeg };
        long[] out = new long[d.length];
        for (int i = 0; i < d.length; i++) out[i] = Double.doubleToLongBits(d[i]);
        return out;
    }

    public static void main(String[] args) throws Exception {
        // ---------- (a) defaults ----------
        TrackPiece fresh = new TrackPiece(0, 21, 0, 0);
        check(!fresh.shapeLocked() && !fresh.affectSubsequentSeams
                && !Coaster.carriesDownstream(fresh),
            "a1 fresh piece: unlocked + Only-Affect on, no carry");
        fresh.orientMode = TrackPiece.OrientMode.REAL_ONLY;
        check(!Coaster.carriesDownstream(fresh),
            "a2 locked + Only-Affect on: still no carry (the default flag)");
        fresh.affectSubsequentSeams = true;
        check(Coaster.carriesDownstream(fresh),
            "a3 locked + Only-Affect off: the D221 rigid carry");
        fresh.orientMode = TrackPiece.OrientMode.LOCKSTEP;
        check(!Coaster.carriesDownstream(fresh),
            "b4 the flag is inert while unlocked (per-piece predicate)");

        // ---------- (b) carry scoping ----------
        // b1: unlocked head — carryAfterEdit does nothing
        {
            Coaster c = chain(BridgeSection.OPT_NONE);
            TrackPiece hA = c.pieces.get(0), hB = c.pieces.get(1),
                hC = c.pieces.get(2);
            long[] b0 = pieceBits(hB), c0 = pieceBits(hC);
            TrackPose be = hA.entryPose();
            hA.x += 7; hA.y += 11; hA.z -= 5;
            hA.editPitch(6);
            TrackPose af = hA.entryPose();
            boolean carried = c.carryAfterEdit(0, be, af,
                TrackCompiler.compile(c, DT).spans());
            check(!carried && Arrays.equals(b0, pieceBits(hB))
                    && Arrays.equals(c0, pieceBits(hC))
                    && hA.designPitchDeg == 6,
                "b1 unlocked head: no carry, downstream fields bitwise,"
                + " design tier moved in lockstep");
        }
        // b2: locked + Only-Affect ON (default) — no carry either;
        // target + follower fields bitwise, the conflict opens on the
        // head only
        {
            Coaster c = chain(BridgeSection.OPT_NONE);
            TrackPiece hA = c.pieces.get(0), hB = c.pieces.get(1),
                hC = c.pieces.get(2);
            hA.orientMode = TrackPiece.OrientMode.REAL_ONLY;   // affectSubsequentSeams stays false
            long[] b0 = pieceBits(hB), c0 = pieceBits(hC);
            TrackPose be = hA.entryPose();
            hA.x += 7; hA.y += 11; hA.z -= 5;
            hA.editPitch(6);
            TrackPose af = hA.entryPose();
            List<TrackCompiler.Span> spans = TrackCompiler.compile(c, DT).spans();
            boolean carried = c.carryAfterEdit(0, be, af, spans);
            check(!carried && Arrays.equals(b0, pieceBits(hB))
                    && Arrays.equals(c0, pieceBits(hC))
                    && hA.orientConflict() && hA.designPitchDeg == 0,
                "b2 locked + Only-Affect ON: NO carry — bridge target x/y/z/"
                + "orientation + follower held angles bitwise, head conflict");
            // the downstream still compiles and re-solves (the manual
            // bridge stretches to the unmoved target)
            TrackCompiler.Result r = rebuild(c);
            double land = r.spans().get(1).exit().pos()
                .sub(hB.entryPose().pos()).length();
            check(land < 1e-6,
                "b2b the manual bridge re-solves to the UNMOVED target",
                String.format("bridge exit vs target %.2e m", land));
        }
        // b3: locked + Only-Affect OFF — bitwise today's carry
        {
            Coaster c = chain(BridgeSection.OPT_NONE);
            Coaster d = chain(BridgeSection.OPT_NONE);
            TrackPiece cA = c.pieces.get(0), dA = d.pieces.get(0);
            cA.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            cA.affectSubsequentSeams = true;
            dA.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            dA.affectSubsequentSeams = true;
            List<TrackCompiler.Span> cs = TrackCompiler.compile(c, DT).spans();
            List<TrackCompiler.Span> ds = TrackCompiler.compile(d, DT).spans();
            TrackPose cbe = cA.entryPose(), dbe = dA.entryPose();
            cA.x += 7; cA.y += 11; cA.z -= 5; cA.editPitch(6);
            dA.x += 7; dA.y += 11; dA.z -= 5; dA.editPitch(6);
            boolean carried = c.carryAfterEdit(0, cbe, cA.entryPose(), cs);
            d.carryDownstream(0, dbe, dA.entryPose(), ds);   // today's direct call
            boolean same = Arrays.equals(pieceBits(c.pieces.get(1)),
                    pieceBits(d.pieces.get(1)))
                && Arrays.equals(pieceBits(c.pieces.get(2)),
                    pieceBits(d.pieces.get(2)));
            boolean moved = c.pieces.get(1).x != 120;
            check(carried && same && moved,
                "b3 locked + Only-Affect OFF: carryAfterEdit == carryDownstream"
                + " bitwise (target + follower moved)");
            TrackCompiler.Result rc = rebuild(c), rd = rebuild(d);
            check(samplesBitwise(rc.samples(), rd.samples()),
                "b3b ... and the two documents compile bitwise");
        }

        // ---------- (c) porient IO ----------
        {
            boolean all = true;
            String detail = "";
            for (int combo = 0; combo < 4; combo++) {
                Coaster c = new Coaster();
                TrackPiece p = new TrackPiece(0, 21, 0, 0);
                p.sections.add(new ForceSection(flatLanes(4), 26));
                p.orientMode = (combo & 1) != 0
                    ? TrackPiece.OrientMode.REAL_ONLY
                    : TrackPiece.OrientMode.LOCKSTEP;
                p.affectSubsequentSeams = (combo & 2) == 0;   // (the old 'only' bit inverted)
                p.designPitchDeg = 3.25;
                p.designRollDeg = -1.5;
                p.pitchDeg = 8;
                c.pieces.add(p);
                String t1 = save(c);
                Coaster c2 = load(t1);
                TrackPiece q = c2.pieces.get(0);
                String t2 = save(c2);
                boolean ok = q.orientMode == p.orientMode
                    && q.affectSubsequentSeams == p.affectSubsequentSeams
                    && q.designPitchDeg == 3.25 && q.designRollDeg == -1.5
                    && q.pitchDeg == 8 && t1.equals(t2);
                boolean line = t1.lines().anyMatch(l -> l.equals("porient "
                    + (p.shapeLocked() ? 1 : 0) + " 3.25 -1.5 "
                    + (p.affectSubsequentSeams ? 1 : 0) + " "
                    + p.orientMode.ordinal()));   // D249: 5 fields
                all &= ok && line;
                if (!(ok && line)) detail += " combo" + combo;
            }
            check(all, "c1 porient 5-field line (D249): every flag combination"
                + " round-trips byte-identically", detail.isEmpty()
                ? "4 combos" : "failed" + detail);
            // c3: a 3-field line (v19.0-v19.18) keeps field 1 and
            // defaults Only-Affect true
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(new ForceSection(flatLanes(4), 26));
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.affectSubsequentSeams = true;
            c.pieces.add(p);
            String txt = save(c);
            String three = txt.lines().map(l -> l.startsWith("porient")
                    ? "porient 1 0.0 0.0" : l)
                .reduce("", (a, b) -> a + b + "\n");
            TrackPiece q3 = load(three).pieces.get(0);
            check(q3.shapeLocked() && !q3.affectSubsequentSeams,
                "c3 3-field porient (v19.0-18): locked kept, Only-Affect"
                + " defaults TRUE");
            String zero = txt.lines().map(l -> l.startsWith("porient")
                    ? "porient 0 0.0 0.0" : l)
                .reduce("", (a, b) -> a + b + "\n");
            TrackPiece q0 = load(zero).pieces.get(0);
            check(!q0.shapeLocked() && !q0.affectSubsequentSeams,
                "c3b 3-field porient with 0: unlocked, Only-Affect TRUE");
            // c4: the D221 interim 2-field line still migrates
            String interim = txt.lines().map(l -> l.startsWith("porient")
                    ? "porient 1 1" : l)
                .reduce("", (a, b) -> a + b + "\n");
            TrackPiece q2 = load(interim).pieces.get(0);
            check(!q2.shapeLocked() && !q2.affectSubsequentSeams
                    && q2.designPitchDeg == q2.pitchDeg,
                "c4 D221 interim line: unlocked + design := true + Only-Affect"
                + " TRUE");
        }

        // ---------- (d) missing porient line ----------
        {
            Coaster c = chain(BridgeSection.OPT_NONE);
            c.pieces.get(0).orientMode = TrackPiece.OrientMode.REAL_ONLY;   // the old default, saved
            c.pieces.get(1).affectSubsequentSeams = true;
            String txt = save(c);
            String stripped = txt.lines().filter(l -> !l.startsWith("porient"))
                .reduce("", (a, b) -> a + b + "\n");
            Coaster with = load(txt), without = load(stripped);
            boolean flags = true;
            for (TrackPiece p : without.pieces) {
                flags &= !p.shapeLocked() && !p.affectSubsequentSeams
                    && p.designPitchDeg == 0 && p.designRollDeg == 0;
            }
            check(flags, "d1 no porient line: every piece unlocked +"
                + " Only-Affect + design (0,0)");
            TrackCompiler.Result rw = rebuild(with), ro = rebuild(without);
            check(samplesBitwise(rw.samples(), ro.samples()),
                "d2 with vs without the porient line: compile BITWISE (the"
                + " compile never reads the flags)");
        }

        // ---------- (e) copy ----------
        {
            TrackPiece p = new TrackPiece(1, 2, 3, 4);
            p.sections.add(new ForceSection(flatLanes(2), 20));
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.affectSubsequentSeams = true;
            TrackPiece q = p.copy(5);
            check(q.shapeLocked() && q.affectSubsequentSeams && q.z == 8,
                "e1 TrackPiece.copy carries shapeLocked + orientOnlyToSeam");
        }

        // ---------- (f) bridge-target routing ----------
        {
            // f1: an UNLOCKED target: the routed write moves both tiers
            // and re-solves to bakeTilt's shape (no conflict)
            Coaster c = chain(BridgeSection.OPT_NONE);
            TrackPiece t = c.pieces.get(1);
            double p0 = t.dispPitch();
            t.setDispPitch(p0 + 9, true);   // what setBridgeTargetOrient does
            check(!t.orientConflict() && Math.abs(t.designPitchDeg - t.pitchDeg) < 1e-12
                    && Math.abs(t.dispPitch() - (p0 + 9)) < 1e-9,
                "f1 routed pitch write on an UNLOCKED target: both tiers,"
                + " no conflict, display == typed");
            TrackCompiler.Result r1 = rebuild(c);
            Coaster d = chain(BridgeSection.OPT_NONE);
            TrackPiece u = d.pieces.get(1);
            u.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            u.setDispPitch(p0 + 9, true);   // locked: true tier only
            u.bakeTilt();                   // then baked = design == true
            TrackCompiler.Result r2 = rebuild(d);
            int i0 = r1.spans().get(2).i0(), i1 = r1.spans().get(2).i1();
            double dev = 0;
            for (int i = i0; i <= i1 && i < r2.samples().size(); i++) {
                dev = Math.max(dev, r1.samples().get(i).pos()
                    .sub(r2.samples().get(i).pos()).length());
            }
            check(dev < 1e-6, "f1b ... the target re-solves to bakeTilt's shape",
                String.format("dev %.2e m", dev));
            // f2: a LOCKED target: conflict opens, design holds
            Coaster e = chain(BridgeSection.OPT_NONE);
            TrackPiece v = e.pieces.get(1);
            v.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            double q0 = v.dispPitch();
            v.setDispPitch(q0 + 9, true);
            check(v.orientConflict() && v.designPitchDeg == 0
                    && Math.abs(v.dispPitch() - (q0 + 9)) < 1e-9,
                "f2 routed pitch write on a LOCKED target: real only, conflict"
                + " opens, design tier holds");
        }

        // ---------- (g) bridge-target Design → Real / Real → Design ----------
        {
            // g1: OPT_POS, a locked-conflict target: design := true +
            // SIG_STALE → the next rebuild re-marches, lands, closes it
            Coaster c = chain(BridgeSection.OPT_POS);
            TrackPiece hA = c.pieces.get(0), t = c.pieces.get(1);
            BridgeSection bs = (BridgeSection) hA.sections.get(hA.sections.size() - 1);
            c.optimizeBridge(bs, 1, 40, false);
            rebuild(c);
            t.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            t.editPitch(t.pitchDeg + 8);   // the conflict the window shows
            rebuild(c);
            check(t.orientConflict(), "g1a fixture: OPT_POS bridge, conflicted"
                + " target");
            // the window's D→R: stamp, then design := true
            bs.optSig = BridgeSection.SIG_STALE;
            t.designPitchDeg = t.pitchDeg;
            t.designRollDeg = t.rollDeg;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            boolean marched = c.autoReoptimizeBridges(r);
            r = rebuild(c);
            double land = bs.solvedTarget.pos().sub(t.entryPose().pos()).length();
            check(marched && bs.optSig != BridgeSection.SIG_STALE
                    && !t.orientConflict() && land < 0.02
                    && Math.abs(bs.lastOptDist - 40) < 1e-9,
                "g1 target Design → Real under OPT_POS: the stale stamp forces"
                + " ONE re-march at lastOptDist, the bridge lands, conflict closed",
                String.format("landing %.2e m", land));
            TrackCompiler.Result r2 = TrackCompiler.compile(c, DT);
            boolean again = c.autoReoptimizeBridges(r2)
                | c.reconcileChainedSpeeds(r2, true);
            check(!again, "g1b ... and the following rebuild is a no-op"
                + " (determinism contract b)");
            // g2: OPT_NONE — nothing marches, the stamp is inert
            Coaster d = chain(BridgeSection.OPT_NONE);
            TrackPiece dA = d.pieces.get(0), dt = d.pieces.get(1);
            BridgeSection dbs = (BridgeSection) dA.sections.get(dA.sections.size() - 1);
            dt.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            dt.editPitch(dt.pitchDeg + 8);
            rebuild(d);
            double[] posBefore = { dt.x, dt.y, dt.z, dt.yawDeg };
            dbs.optSig = BridgeSection.SIG_STALE;
            dt.designPitchDeg = dt.pitchDeg;
            dt.designRollDeg = dt.rollDeg;
            TrackCompiler.Result rd = TrackCompiler.compile(d, DT);
            boolean dm = d.autoReoptimizeBridges(rd);
            rd = rebuild(d);
            double dland = rd.spans().get(1).exit().pos()
                .sub(dt.entryPose().pos()).length();
            check(!dm && !dt.orientConflict() && dt.x == posBefore[0]
                    && dt.y == posBefore[1] && dt.z == posBefore[2]
                    && dt.yawDeg == posBefore[3] && dland < 1e-6,
                "g2 target Design → Real under OPT_NONE: no march, target"
                + " placement untouched, the septic re-captures the new head",
                String.format("bridge exit vs target %.2e m", dland));
            // g3: Real → Design under OPT_POS with the bridge padlocks
            // set: true := design, then the OPT_POS re-place lands
            Coaster e = chain(BridgeSection.OPT_POS);
            TrackPiece eA = e.pieces.get(0), et = e.pieces.get(1);
            BridgeSection ebs = (BridgeSection) eA.sections.get(eA.sections.size() - 1);
            ebs.lockPitch = true;
            ebs.lockRoll = true;
            e.optimizeBridge(ebs, 1, 40, false);
            rebuild(e);
            et.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            et.editPitch(et.pitchDeg + 8);
            et.editRoll(et.rollDeg - 5);
            rebuild(e);
            check(et.orientConflict(), "g3a fixture: conflicted target,"
                + " padlocks held");
            et.pitchDeg = et.designPitchDeg;   // the window's R→D write
            et.rollDeg = et.designRollDeg;
            e.optimizeBridge(ebs, 1, ebs.lastOptDist, false);   // the re-place
            TrackCompiler.Result re = rebuild(e);
            double eland = ebs.solvedTarget.pos().sub(et.entryPose().pos()).length();
            check(!et.orientConflict() && eland < 0.02,
                "g3 target Real → Design under OPT_POS (locks held): conflict"
                + " closed, the re-place lands",
                String.format("landing %.2e m", eland));
            // g4 WHY the window's stamp must land AFTER the undo push
            // (D248 review fix — FvdDesigner.designToRealDirect's
            // afterPush hook): a DocState snapshot copies bridges via
            // BridgeSection.copy, which CARRIES optSig — a pre-push
            // stamp would ride into the snapshot and Ctrl+Z would
            // restore a SIG_STALE bridge that the next rebuild's auto
            // pass re-marches (an undo that moves the target). Pinned
            // on the record-only (optSig == null) state the window
            // finds: the copy taken BEFORE the stamp is null-signed and
            // the auto pass leaves it alone.
            Coaster h = chain(BridgeSection.OPT_POS);
            TrackPiece hA2 = h.pieces.get(0), ht = h.pieces.get(1);
            BridgeSection hbs = (BridgeSection) hA2.sections.get(hA2.sections.size() - 1);
            hbs.optSig = null;                       // record-only (a loaded file)
            ht.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            ht.editPitch(ht.pitchDeg + 8);
            rebuild(h);
            hbs.optSig = null;
            BridgeSection snapBefore = hbs.copy();   // the push's copy (before the stamp)
            hbs.optSig = BridgeSection.SIG_STALE;    // the stamp
            BridgeSection snapAfter = hbs.copy();    // what a pre-push stamp would have snapshotted
            check(snapBefore.optSig == null && snapAfter.optSig == BridgeSection.SIG_STALE,
                "g4 BridgeSection.copy carries optSig: the snapshot must precede the stamp");
            hA2.sections.set(hA2.sections.size() - 1, snapBefore);   // the undo restore
            double[] tb = { ht.x, ht.y, ht.z, ht.yawDeg };
            TrackCompiler.Result rh = TrackCompiler.compile(h, DT);
            boolean hm = h.autoReoptimizeBridges(rh);
            check(!hm && snapBefore.optSig != null && snapBefore.optSig != BridgeSection.SIG_STALE
                    && ht.x == tb[0] && ht.y == tb[1] && ht.z == tb[2] && ht.yawDeg == tb[3],
                "g4b a restored null-signed bridge is RECORDED, not re-marched (target placement bitwise)");
            hA2.sections.set(hA2.sections.size() - 1, snapAfter);
            TrackCompiler.Result rh2 = TrackCompiler.compile(h, DT);
            boolean hm2 = h.autoReoptimizeBridges(rh2);
            check(hm2, "g4c ... whereas a restored SIG_STALE bridge WOULD re-march — the ordering is load-bearing");
        }

        // ---------- (h) BRIDGE LOCKS — the seam rule (D249) ----------
        {
            // h0: the ONE enable predicate every surface reads
            TrackPose p0 = new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY);
            TrackPose p1 = new TrackPose(new Vec3(30, 21, 0), Quat.IDENTITY);
            BridgeSection fb = BridgeSection.solve(p0, p1, 20);
            boolean t0 = !fb.lockYaw && fb.lockPitch && fb.lockRoll;
            fb.optMode = BridgeSection.OPT_POS_YAW;
            boolean tPY = !fb.axisEditable(0) && fb.axisEditable(1) && fb.axisEditable(2)
                && fb.axisAligned(0) && !fb.axisAligned(1) && !fb.axisAligned(2);
            fb.optMode = BridgeSection.OPT_POS;
            boolean tP = fb.axisEditable(0) && fb.axisEditable(1) && fb.axisEditable(2)
                && !fb.axisAligned(0) && !fb.axisAligned(1) && !fb.axisAligned(2);
            fb.optMode = BridgeSection.OPT_NONE;
            boolean tN = fb.axisEditable(0) && fb.axisEditable(1) && fb.axisEditable(2)
                && !fb.axisAligned(0);
            fb.optMode = BridgeSection.OPT_POS_YAW;
            fb.lockYaw = true;
            fb.lockPitch = false;
            boolean tFlip = fb.axisEditable(0) && !fb.axisEditable(1) && fb.axisAligned(1);
            check(t0 && tPY && tP && tN && tFlip,
                "h0 axisEditable = held || mode != OPT_POS_YAW; axisAligned = free"
                + " && OPT_POS_YAW (defaults yaw free, pitch/roll held)");

            // h1: padlock flips under OPT_POS_YAW (the setBridgeLock twin)
            Coaster c = chain(BridgeSection.OPT_POS_YAW);
            TrackPiece hA = c.pieces.get(0), t = c.pieces.get(1);
            BridgeSection bs = (BridgeSection) hA.sections.get(hA.sections.size() - 1);
            double heldP = t.pitchDeg, heldR = t.rollDeg;
            c.optimizeBridge(bs, 1, 40, true);
            rebuild(c);
            check(bits(t.pitchDeg, heldP) && bits(t.rollDeg, heldR)
                    && !t.orientConflict(),
                "h1a OPT_POS_YAW: the HELD pitch/roll are never touched by the"
                + " march (yaw aligned only), no conflict");
            // unlock pitch — the flip re-marches at lastOptDist
            bs.lockPitch = false;
            c.optimizeBridge(bs, 1, bs.lastOptDist, true);
            TrackCompiler.Result r1 = rebuild(c);
            check(Math.abs(t.pitchDeg - heldP) > 1e-6 && bits(t.pitchDeg, t.designPitchDeg)
                    && !t.orientConflict(),
                "h1b unlock pitch: the re-march ALIGNS it (LOCKSTEP: both tiers,"
                + " no conflict)", String.format("pitch %.4f → %.4f", heldP, t.pitchDeg));
            // re-lock: CAPTURE = the current angle, bitwise; the march meets it
            long[] before = pieceBits(t);
            bs.lockPitch = true;
            c.optimizeBridge(bs, 1, bs.lastOptDist, true);
            TrackCompiler.Result r2 = rebuild(c);
            double land = r2.spans().get(1).exit().pos().sub(t.entryPose().pos()).length();
            check(Double.doubleToLongBits(t.pitchDeg) == before[4]
                    && Double.doubleToLongBits(t.designPitchDeg) == before[6]
                    && land < 0.02,
                "h1c re-lock pitch: the current angle is CAPTURED bitwise (nothing"
                + " jumps) and the march meets it — the bridge lands",
                String.format("landing %.2e m", land));
            // (h1d) a further rebuild rests — determinism contract b
            TrackCompiler.Result r3 = TrackCompiler.compile(c, DT);
            boolean again = c.autoReoptimizeBridges(r3) | c.reconcileChainedSpeeds(r3, true);
            check(!again, "h1d ... and the following rebuild is a no-op");

            // h2: a TYPED held value under OPT_POS_YAW + the write tail
            Coaster d = chain(BridgeSection.OPT_POS_YAW);
            TrackPiece dA = d.pieces.get(0), dt = d.pieces.get(1);
            BridgeSection dbs = (BridgeSection) dA.sections.get(dA.sections.size() - 1);
            d.optimizeBridge(dbs, 1, 40, true);
            rebuild(d);
            double typed = dt.dispPitch() + 7;
            dt.setDispPitch(typed, true);                    // the box (pitch is HELD)
            d.optimizeBridge(dbs, 1, dbs.lastOptDist, true);  // bridgeTargetWrite's tail
            TrackCompiler.Result rd = rebuild(d);
            double dland = rd.spans().get(1).exit().pos().sub(dt.entryPose().pos()).length();
            check(Math.abs(dt.dispPitch() - typed) < 1e-9 && bits(dt.pitchDeg, dt.designPitchDeg)
                    && !dt.orientConflict() && dland < 0.02,
                "h2 OPT_POS_YAW typed HELD pitch: survives the re-march, LOCKSTEP"
                + " design == true, the bridge lands (the hump meets it)",
                String.format("landing %.2e m, pitch %.3f", dland, dt.dispPitch()));
            TrackCompiler.Result rd2 = TrackCompiler.compile(d, DT);
            boolean dagain = d.autoReoptimizeBridges(rd2) | d.reconcileChainedSpeeds(rd2, true);
            check(!dagain, "h2a ... and the following rebuild is a no-op");
            // h2b: the REAL_ONLY twin — design untouched, conflict, lands
            Coaster e = chain(BridgeSection.OPT_POS_YAW);
            TrackPiece eA = e.pieces.get(0), et = e.pieces.get(1);
            BridgeSection ebs = (BridgeSection) eA.sections.get(eA.sections.size() - 1);
            e.optimizeBridge(ebs, 1, 40, true);
            rebuild(e);
            et.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            double etyped = et.dispPitch() + 7;
            et.setDispPitch(etyped, true);
            e.optimizeBridge(ebs, 1, ebs.lastOptDist, true);
            TrackCompiler.Result re = rebuild(e);
            double eland = re.spans().get(1).exit().pos().sub(et.entryPose().pos()).length();
            check(Math.abs(et.dispPitch() - etyped) < 1e-9 && et.designPitchDeg == 0
                    && et.orientConflict() && eland < 0.02,
                "h2b ... REAL_ONLY twin: real only, design (0,0), conflict, lands",
                String.format("landing %.2e m", eland));

            // h4: OPT_POS — a FREE yaw typed is kept by the position-only
            // re-place; OPT_NONE — a HELD pitch typed, the septic re-captures
            Coaster f = chain(BridgeSection.OPT_POS);
            TrackPiece fA = f.pieces.get(0), ft = f.pieces.get(1);
            BridgeSection fbs = (BridgeSection) fA.sections.get(fA.sections.size() - 1);
            f.optimizeBridge(fbs, 1, 40, false);
            rebuild(f);
            double fyaw = ft.dispYaw() + 11;
            ft.setDispYaw(fyaw);                              // free yaw, editable (row 2.1)
            f.optimizeBridge(fbs, 1, fbs.lastOptDist, false);  // the OPT_POS tail
            TrackCompiler.Result rf = rebuild(f);
            double fland = rf.spans().get(1).exit().pos().sub(ft.entryPose().pos()).length();
            check(Math.abs(TrackPiece.wrap180(ft.dispYaw() - fyaw)) < 1e-9 && fland < 0.02,
                "h4 OPT_POS: a free (unlocked) yaw typed is KEPT by the position-only"
                + " re-place, which lands", String.format("landing %.2e m", fland));
            Coaster g = chain(BridgeSection.OPT_NONE);
            TrackPiece gt = g.pieces.get(1);
            double gtyped = gt.dispPitch() + 5;
            gt.setDispPitch(gtyped, true);                     // held pitch, editable (row 3.1)
            TrackCompiler.Result rg = rebuild(g);
            double gland = rg.spans().get(1).exit().pos().sub(gt.entryPose().pos()).length();
            check(Math.abs(gt.dispPitch() - gtyped) < 1e-9 && gland < 1e-6,
                "h4b OPT_NONE: a held pitch typed, the septic re-captures the moved"
                + " head", String.format("bridge exit vs target %.2e m", gland));

            // h7: a flip under OPT_POS / OPT_NONE moves nothing
            for (int mode : new int[]{ BridgeSection.OPT_POS, BridgeSection.OPT_NONE }) {
                Coaster k = chain(mode);
                TrackPiece kA = k.pieces.get(0), kt = k.pieces.get(1);
                BridgeSection kbs = (BridgeSection) kA.sections.get(kA.sections.size() - 1);
                if (mode == BridgeSection.OPT_POS) k.optimizeBridge(kbs, 1, 40, false);
                TrackCompiler.Result k0 = rebuild(k);
                long[] kb = pieceBits(kt);
                kbs.lockYaw = true;      // lock (capture = no write)
                kbs.lockPitch = false;   // unlock
                TrackCompiler.Result k1 = rebuild(k);   // no explicit re-march (the choke only redraws)
                check(Arrays.equals(kb, pieceBits(kt)) && samplesBitwise(k0.samples(), k1.samples()),
                    "h7 lock flips under " + (mode == BridgeSection.OPT_POS ? "OPT_POS" : "OPT_NONE")
                    + ": target fields + samples bitwise (nothing aligns there)");
            }

            // h8: a blen line without fields 6-8 = the defaults, bitwise
            Coaster o = chain(BridgeSection.OPT_POS_YAW);
            BridgeSection obs = (BridgeSection) o.pieces.get(0).sections.get(1);
            o.optimizeBridge(obs, 1, 40, true);
            rebuild(o);
            String txt = save(o);
            String stripped = txt.lines().map(l -> {
                if (!l.startsWith("blen ")) return l;
                String[] p = l.split(" ");
                return String.join(" ", Arrays.copyOf(p, 6));
            }).reduce("", (a, b) -> a + b + "\n");
            Coaster o1 = load(txt), o2 = load(stripped);
            BridgeSection b1 = (BridgeSection) o1.pieces.get(0).sections.get(1);
            BridgeSection b2 = (BridgeSection) o2.pieces.get(0).sections.get(1);
            TrackCompiler.Result ro1 = rebuild(o1), ro2 = rebuild(o2);
            check(!b2.lockYaw && b2.lockPitch && b2.lockRoll
                    && b1.lockYaw == b2.lockYaw && b1.lockPitch == b2.lockPitch
                    && samplesBitwise(ro1.samples(), ro2.samples()),
                "h8 blen without fields 6-8: defaults (yaw free, pitch/roll held),"
                + " compile bitwise the full line");
        }

        System.out.println();
        System.out.println(fail == 0
            ? "ALL PLACEMENT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
