import uvd.*;

import java.io.*;
import java.util.*;

/** D249 gate — the 3-STATE ORIENTATION MODE (v19.20, item M of batch
 *  34): TrackPiece.orientMode {LOCKSTEP, REAL_ONLY, DESIGN_ONLY}
 *  replaces the shapeLocked boolean (shapeLocked() = the derived
 *  accessor), orientOnlyToSeam becomes the INVERTED
 *  affectSubsequentSeams, and EVERY writer of a piece's pitch/roll —
 *  the user's boxes/rings AND the bridge march — takes the one tier
 *  rule through editPitch/editRoll. Rows reference
 *  scratchpad/b34_edges_M-orientmodel.md.
 *
 *  a) editPitch/editRoll routing per mode (rows 1.1-1.7).
 *  b) the display/raw wrappers derive the new delta from the tier the
 *     mode edits: DESIGN_ONLY boxes/rings round-trip on the design
 *     tier, incl. its own pole branch (rows 2.1-2.6).
 *  c) porient IO: the 5-field line round-trips byte-identically for
 *     every mode × carry; a bad/inconsistent 5th field; the D248
 *     4-field line INVERTS field 4; 3-field / D221 interim / missing
 *     lines; a DESIGN_ONLY target under an OPT_POS_YAW bridge loads
 *     record-only (rows 3.1-3.7).
 *  d) TrackPiece.copy carries mode + carry (row 4.2).
 *  e) Coaster.carriesDownstream per mode × carry (rows 5.1-5.4).
 *  f) followers: seamDesignLockstep under LOCKSTEP / REAL_ONLY, the
 *     DESIGN_ONLY divert's model twin (applyDesignEulerDelta with the
 *     held values restored — the real entry never moves) and
 *     Coaster.seamDesignSet's absolute design writes + refusals
 *     (rows 6.1-6.3, 6.7).
 *  g) the bridge march: LOCKSTEP → design == true after optimize (no
 *     conflict) and the rebuild loop sees a FIXED POINT in ≤ 1 pass
 *     (the inner settle), also after an upstream edit; REAL_ONLY →
 *     design untouched, conflict, the pre-D249 numbers; DESIGN_ONLY →
 *     bitwise the REAL_ONLY write (defensive); a yaw-only alignment
 *     re-solves nothing in any mode (rows 7.1-7.4, 7.7); g7 the REST
 *     GUARANTEE (D249 review round 2): SLOW pitched LOCKSTEP targets
 *     (vDesign 5-8 m/s, ±25-30°, pitch/roll unlocked — a repelling
 *     fixed point the 24-round settle cannot converge) rest after ≤ 2
 *     passes and a second rebuild is a BITWISE no-op (the stamped
 *     signature == the next capture, whatever the convergence), g7b
 *     the optLen variant re-knees off the SETTLED seams (a second
 *     explicit optimize at that knee keeps it — the contracting
 *     family) and rests too.
 *  h) ITEM O's model half (b34_edges_O-orientui.md, sections 2 + 5):
 *     h1 the seam window's DESIGN box contract — its value is the
 *     design elevation/bank (eulerOf(designPose)), a typed value lands
 *     on it exactly (Coaster.seamDesignSet) with the held angles, the
 *     locks and the real seam entry bitwise untouched; h2-h5 the
 *     FULL-SCOPE Cancel record (PlacementSnapshot): capture → a
 *     carried head edit + mode flips + seam-lock flips + bridge field
 *     writes + an emulated Design → Real bake → restore returns EVERY
 *     piece's fields + baseOrient / canonical-pair identities + the
 *     bridge's fields (optSig included) BITWISE, the compile == the
 *     open-time compile, and the next rebuild is a no-op; an untouched
 *     document does not differ; a vanished piece is skipped, an
 *     appended one untouched; a replaced bridge object skips its bridge
 *     half; the SIG_STALE / null sentinel identities survive; h6 the
 *     seam rig's DESIGN_ONLY roll / unbanked pitch ring hands back a
 *     held yaw that differs from the stored one by eulerOf dust only
 *     (< 1e-9°, under gizmoSetSeamDesignAll's write guard — D249
 *     review round 2), and the design writes keep the held yaw + the
 *     real seam entry bitwise.
 */
public class OrientModeGate {

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

    /** The app's rebuild loop (FvdDesigner.rebuildTrack: 12 fast +
     *  40 exact passes); passes = compiles beyond the first. */
    static int passes;
    static TrackCompiler.Result rebuild(Coaster c) {
        passes = 0;
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            passes++;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            passes++;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** Would one more rebuild write anything? (determinism b) */
    static boolean restless(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        return c.autoReoptimizeBridges(r) | c.reconcileChainedSpeeds(r, true);
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

    static long[] pieceBits(TrackPiece t) {
        double[] d = { t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
            t.designPitchDeg, t.designRollDeg, t.sYawDeg, t.sPitchDeg,
            t.sRollDeg };
        long[] out = new long[d.length];
        for (int i = 0; i < d.length; i++) out[i] = Double.doubleToLongBits(d[i]);
        return out;
    }

    static long[] poseBits(TrackPose p) {
        double[] d = { p.pos().x(), p.pos().y(), p.pos().z(),
            p.orient().w(), p.orient().x(), p.orient().y(), p.orient().z() };
        long[] out = new long[d.length];
        for (int i = 0; i < d.length; i++) out[i] = Double.doubleToLongBits(d[i]);
        return out;
    }

    /** head (hill) → bridge (mode) → target (turn, orientMode) →
     *  strict follower with ALL THREE axes held (pieces 0/1/2). */
    static Coaster chain(int optMode, TrackPiece.OrientMode targetMode) {
        Coaster c = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(hA);
        TrackPiece hB = new TrackPiece(120, 14, 30, 20);
        hB.sections.add(new ForceSection(turnLanes(), 24));
        hB.orientMode = targetMode;
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
        double[] e = Coaster.eulerOf(Coaster.strictEntry(
            r1.spans().get(2).exit(), hC).orient());
        hC.sYawDeg = Double.isNaN(e[0]) ? 0 : e[0];
        hC.sPitchDeg = e[1] + 5;
        hC.sRollDeg = (Double.isNaN(e[2]) ? 0 : e[2]) - 4;
        rebuild(c);
        return c;
    }

    /** The REST-GUARANTEE fixture (D249 review round 2, the
     *  SettleProbe layout): head (hill, 26) → OPT_POS_YAW bridge with
     *  pitch/roll UNLOCKED → a SLOW pitched LOCKSTEP target (turn at
     *  vT, editPitch(pitch)) at (gapX, 14, 30) yaw 20. The first
     *  compile records the signature (loads never move pieces); the
     *  caller optimizes explicitly. */
    static Coaster slowChain(double vT, double pitch, double gapX,
                             boolean optLen) {
        Coaster c = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(hA);
        TrackPiece hB = new TrackPiece(gapX, 14, 30, 20);
        hB.sections.add(new ForceSection(turnLanes(), vT));
        hB.orientMode = TrackPiece.OrientMode.LOCKSTEP;
        hB.editPitch(pitch);
        c.pieces.add(hB);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(r0.spans().get(0).exit(),
            hB.entryPose(), r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_POS_YAW;
        bs.lockPitch = false;
        bs.lockRoll = false;
        bs.optLen = optLen;
        hA.sections.add(bs);
        TrackCompiler.compile(c, DT);   // captures the seams, optSig null
        return c;
    }

    /** GraphEditor.followerEuler's analytic read (the follow contract
     *  off the previous piece's exit). */
    static double[] followerEuler(Coaster c, TrackCompiler.Result r,
                                  int fi) {
        TrackPiece f = c.pieces.get(fi);
        int span = 0;
        for (int p = 0; p < fi; p++) span += c.pieces.get(p).sections.size();
        return Coaster.eulerOf(Coaster.strictEntry(
            r.spans().get(span - 1).exit(), f).orient());
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

    static String withPorient(String txt, String line) {
        return txt.lines().map(l -> l.startsWith("porient") ? line : l)
            .reduce("", (a, b) -> a + b + "\n");
    }

    public static void main(String[] args) throws Exception {
        final TrackPiece.OrientMode LS = TrackPiece.OrientMode.LOCKSTEP;
        final TrackPiece.OrientMode RO = TrackPiece.OrientMode.REAL_ONLY;
        final TrackPiece.OrientMode DO = TrackPiece.OrientMode.DESIGN_ONLY;

        // ---------- (a) routing ----------
        {
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            check(p.orientMode == LS && !p.affectSubsequentSeams
                    && !p.shapeLocked(),
                "a0 fresh piece: LOCKSTEP, no carry, shapeLocked() false");
            p.editPitch(10);
            check(p.pitchDeg == 10 && p.designPitchDeg == 10 && !p.orientConflict(),
                "a1 LOCKSTEP editPitch from zero: both tiers, no conflict");
            p.designPitchDeg = 4;                  // a 6° conflict riding
            p.editPitch(16);
            check(p.pitchDeg == 16 && p.designPitchDeg == 10,
                "a2 LOCKSTEP with a conflict: both move by the same amount,"
                + " the offset stays");
            p.orientMode = RO;
            p.editPitch(20);
            check(p.pitchDeg == 20 && p.designPitchDeg == 10 && p.shapeLocked(),
                "a3 REAL_ONLY editPitch: true only, design holds");
            p.orientMode = DO;
            p.editPitch(-5);
            check(p.pitchDeg == 20 && p.designPitchDeg == -5,
                "a4 DESIGN_ONLY editPitch(v): design := v, the real entry"
                + " untouched");
            TrackPiece q = new TrackPiece(0, 21, 0, 0);
            q.editRoll(-9);
            boolean r1 = q.rollDeg == -9 && q.designRollDeg == -9;
            q.orientMode = RO;
            q.editRoll(-4);
            boolean r2 = q.rollDeg == -4 && q.designRollDeg == -9;
            q.orientMode = DO;
            q.editRoll(370);
            boolean r3 = q.rollDeg == -4 && q.designRollDeg == 10;
            check(r1 && r2 && r3,
                "a5/a6 editRoll per mode: LOCKSTEP both, REAL_ONLY true only,"
                + " DESIGN_ONLY design only (wrap360: 370 → 10)");
            boolean acc = true;
            for (TrackPiece.OrientMode m : TrackPiece.OrientMode.values()) {
                q.orientMode = m;
                acc &= q.shapeLocked() == (m == RO);
            }
            check(acc, "a7 shapeLocked() == (orientMode == REAL_ONLY)");
        }

        // ---------- (b) display / raw wrappers ----------
        {
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.setDispPitch(12, true);                    // LOCKSTEP
            boolean b1 = Math.abs(p.dispPitch() - 12) < 1e-12
                && Math.abs(p.dispDesignPitch() - 12) < 1e-12;
            p.orientMode = RO;
            p.setDispPitch(8, true);
            b1 &= Math.abs(p.dispPitch() - 8) < 1e-12
                && Math.abs(p.dispDesignPitch() - 12) < 1e-12;
            check(b1, "b1 setDispPitch(v, true): LOCKSTEP both tiers read v,"
                + " REAL_ONLY the real reads v and the design holds");
            p.orientMode = DO;
            p.setDispPitch(15, true);
            check(Math.abs(p.dispDesignPitch() - 15) < 1e-12
                    && Math.abs(p.dispPitch() - 8) < 1e-12 && p.pitchDeg == 8,
                "b2 DESIGN_ONLY setDispPitch(15): the design box reads 15,"
                + " the real entry stays at 8");
            p.designPitchDeg = 100;                     // past the pole
            boolean mirrored = Math.abs(p.dispDesignPitch() - 80) < 1e-12;
            p.setDispPitch(70, true);
            check(mirrored && Math.abs(p.dispDesignPitch() - 70) < 1e-12
                    && p.designPitchDeg == 110 && p.pitchDeg == 8,
                "b3 DESIGN_ONLY on the design tier's OWN pole branch: raw"
                + " 100 shows 80, typing 70 lands raw 110 (mirrored scale),"
                + " real untouched");
            p.setDispRoll(25, true);
            check(Math.abs(p.dispDesignRoll() - 25) < 1e-12 && p.rollDeg == 0,
                "b4 DESIGN_ONLY setDispRoll(25) through the flipped branch:"
                + " the design bank reads 25, real roll untouched");
            p.setRawAbsPitch(33, true);
            p.setRawAbsRoll(-40, true);
            check(p.rawAbsDesignPitch() == 33 && p.rawAbsDesignRoll() == -40
                    && p.pitchDeg == 8 && p.rollDeg == 0
                    && p.rawAbsPitch() == 8,
                "b5 DESIGN_ONLY setRawAbsPitch/Roll: rawAbsDesignPitch/Roll"
                + " read back, the real raw untouched");
            TrackPiece u = new TrackPiece(0, 21, 0, 0);
            u.orientMode = RO;
            u.setRawAbsPitch(33, true);
            check(u.rawAbsPitch() == 33 && u.designPitchDeg == 0,
                "b6 REAL_ONLY setRawAbsPitch: real raw reads back, design holds"
                + " (today's ring path)");
        }

        // ---------- (c) porient IO ----------
        {
            boolean all = true;
            String detail = "";
            for (TrackPiece.OrientMode m : TrackPiece.OrientMode.values()) {
                for (int a = 0; a < 2; a++) {
                    Coaster c = new Coaster();
                    TrackPiece p = new TrackPiece(0, 21, 0, 0);
                    p.sections.add(new ForceSection(flatLanes(4), 26));
                    p.orientMode = m;
                    p.affectSubsequentSeams = a == 1;
                    p.designPitchDeg = 3.25;
                    p.designRollDeg = -1.5;
                    p.pitchDeg = 8;
                    c.pieces.add(p);
                    String t1 = save(c);
                    Coaster c2 = load(t1);
                    TrackPiece q = c2.pieces.get(0);
                    String t2 = save(c2);
                    String want = "porient " + (m == RO ? 1 : 0) + " 3.25 -1.5 "
                        + a + " " + m.ordinal() + " 0";   // + field 6 = the
                        // automatic-seam flag (v20.1, D259 §3.5; 0 on a free piece)
                    boolean ok = q.orientMode == m && q.affectSubsequentSeams == (a == 1)
                        && q.designPitchDeg == 3.25 && q.designRollDeg == -1.5
                        && q.pitchDeg == 8 && t1.equals(t2)
                        && t1.lines().anyMatch(l -> l.equals(want));
                    all &= ok;
                    if (!ok) detail += " " + m + "/" + a;
                }
            }
            check(all, "c1 porient 6-field line (D249 + D259's auto flag): every"
                + " mode × carry round-trips byte-identically, field 1 == (mode == REAL_ONLY)",
                detail.isEmpty() ? "6 combos" : "failed" + detail);
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(new ForceSection(flatLanes(4), 26));
            c.pieces.add(p);
            String txt = save(c);
            TrackPiece q;
            q = load(withPorient(txt, "porient 1 0.0 0.0 1 7")).pieces.get(0);
            boolean c2 = q.orientMode == RO && q.affectSubsequentSeams;
            q = load(withPorient(txt, "porient 0 0.0 0.0 0 x")).pieces.get(0);
            c2 &= q.orientMode == LS && !q.affectSubsequentSeams;
            check(c2, "c2 5-field line with an out-of-range / non-numeric mode:"
                + " fail-soft to field 1, carry from field 4");
            q = load(withPorient(txt, "porient 0 0.0 0.0 1 2")).pieces.get(0);
            check(q.orientMode == DO && q.affectSubsequentSeams,
                "c2b field 5 is the authority over an inconsistent field 1"
                + " (0 … 2 → DESIGN_ONLY)");
            q = load(withPorient(txt, "porient 1 0.0 0.0 1")).pieces.get(0);
            boolean c3 = q.orientMode == RO && !q.affectSubsequentSeams;
            q = load(withPorient(txt, "porient 0 0.0 0.0 0")).pieces.get(0);
            c3 &= q.orientMode == LS && q.affectSubsequentSeams;
            check(c3, "c3 D248 4-field line: field 4 was 'Only Affect' — INVERTS"
                + " (1 → no carry, 0 → carry)");
            q = load(withPorient(txt, "porient 1 0.0 0.0")).pieces.get(0);
            check(q.orientMode == RO && !q.affectSubsequentSeams,
                "c4 3-field line (v19.0-v19.18): mode from field 1, no carry");
            q = load(withPorient(txt, "porient 1 1")).pieces.get(0);
            check(q.orientMode == LS && !q.affectSubsequentSeams
                    && q.designPitchDeg == q.pitchDeg,
                "c5 D221 interim line: LOCKSTEP + design := true, no carry");
            // c6: missing line — defaults, and the compile is bitwise
            Coaster d = chain(BridgeSection.OPT_NONE, RO);
            d.pieces.get(1).affectSubsequentSeams = true;
            String dtxt = save(d);
            String stripped = dtxt.lines().filter(l -> !l.startsWith("porient"))
                .reduce("", (x, y) -> x + y + "\n");
            Coaster with = load(dtxt), without = load(stripped);
            boolean flags = true;
            for (TrackPiece w : without.pieces) {
                flags &= w.orientMode == LS && !w.affectSubsequentSeams
                    && w.designPitchDeg == 0 && w.designRollDeg == 0;
            }
            check(flags && samplesBitwise(rebuild(with).samples(),
                    rebuild(without).samples()),
                "c6 missing porient line: LOCKSTEP + no carry + (0,0), compile"
                + " BITWISE the document with the line (the compile never reads"
                + " the mode)");
            // c7: a DESIGN_ONLY target under an OPT_POS_YAW bridge (a
            // hand-edited / older file) loads and is record-only
            Coaster e = chain(BridgeSection.OPT_POS_YAW, DO);
            Coaster e2 = load(save(e));
            TrackPiece et = e2.pieces.get(1);
            long[] tb = pieceBits(et);
            TrackCompiler.Result re = TrackCompiler.compile(e2, DT);
            boolean marched = e2.autoReoptimizeBridges(re);
            check(et.orientMode == DO && !marched
                    && Arrays.equals(tb, pieceBits(et)),
                "c7 DESIGN_ONLY target + OPT_POS_YAW in a file: loads, first"
                + " sight is record-only (nothing moves)");
        }

        // ---------- (d) copy ----------
        {
            TrackPiece p = new TrackPiece(1, 2, 3, 4);
            p.sections.add(new ForceSection(flatLanes(2), 20));
            p.orientMode = DO;
            p.affectSubsequentSeams = true;
            TrackPiece q = p.copy(5);
            check(q.orientMode == DO && q.affectSubsequentSeams && q.z == 8,
                "d1 TrackPiece.copy carries orientMode + affectSubsequentSeams");
        }

        // ---------- (e) carry predicate ----------
        {
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            boolean e1 = !Coaster.carriesDownstream(p);
            p.affectSubsequentSeams = true;
            e1 &= !Coaster.carriesDownstream(p);
            check(e1, "e1 LOCKSTEP never carries (either flag value)");
            p.orientMode = RO;
            p.affectSubsequentSeams = false;
            check(!Coaster.carriesDownstream(p),
                "e2 REAL_ONLY + carry off: no carry (the default flag)");
            p.affectSubsequentSeams = true;
            check(Coaster.carriesDownstream(p),
                "e3 REAL_ONLY + carry on: the D221 rigid carry");
            p.orientMode = DO;
            check(!Coaster.carriesDownstream(p),
                "e4 DESIGN_ONLY + carry on: inert (the real entry never moves)");
        }

        // ---------- (f) followers ----------
        {
            // f1 LOCKSTEP: a held-pitch write moves the design tier by
            // the same elevation delta
            Coaster c = chain(BridgeSection.OPT_NONE, LS);
            TrackPiece hC = c.pieces.get(2);
            hC.orientMode = LS;
            TrackCompiler.Result r = rebuild(c);
            double[] before = followerEuler(c, r, 2);
            double d0 = hC.designPitchDeg;
            hC.sPitchDeg += 5;
            r = TrackCompiler.compile(c, DT);
            double[] after = followerEuler(c, r, 2);
            boolean ok = Coaster.seamDesignLockstep(hC, before, after);
            check(ok && Math.abs(hC.designPitchDeg - d0 - 5) < 1e-9,
                "f1 LOCKSTEP seam write: the design elevation follows the"
                + " held-pitch delta", String.format("Δdesign %.9f",
                    hC.designPitchDeg - d0));
            // f2 REAL_ONLY: nothing on the design tier
            Coaster c2 = chain(BridgeSection.OPT_NONE, LS);
            TrackPiece f2 = c2.pieces.get(2);
            f2.orientMode = RO;
            TrackCompiler.Result r2 = rebuild(c2);
            double[] b2 = followerEuler(c2, r2, 2);
            double d2 = f2.designPitchDeg;
            f2.sPitchDeg += 5;
            double[] a2 = followerEuler(c2, TrackCompiler.compile(c2, DT), 2);
            check(Coaster.seamDesignLockstep(f2, b2, a2) && f2.designPitchDeg == d2,
                "f2 REAL_ONLY seam write: the design tier holds (true-only)");
            // f3 DESIGN_ONLY — the seamEdit divert's model twin: the
            // real Euler delta goes to the design tier, the held values
            // come back, the real entry is bitwise where it was
            Coaster c3 = chain(BridgeSection.OPT_NONE, LS);
            TrackPiece f3 = c3.pieces.get(2);
            f3.orientMode = DO;
            TrackCompiler.Result r3 = rebuild(c3);
            double[] b3 = followerEuler(c3, r3, 2);
            long[] entry0 = poseBits(Coaster.strictEntry(r3.spans().get(2).exit(), f3));
            double hp0 = f3.sPitchDeg, d3 = f3.designPitchDeg;
            boolean lockstepInert = Coaster.seamDesignLockstep(f3, b3, b3)
                && f3.designPitchDeg == d3;
            f3.sPitchDeg += 5;                        // the write
            double[] a3 = followerEuler(c3, TrackCompiler.compile(c3, DT), 2);
            f3.sPitchDeg = hp0;                       // restored (GraphEditor.seamEdit)
            boolean applied = Coaster.applyDesignEulerDelta(f3, b3, a3);
            TrackCompiler.Result r3b = TrackCompiler.compile(c3, DT);
            long[] entry1 = poseBits(Coaster.strictEntry(r3b.spans().get(2).exit(), f3));
            check(lockstepInert && applied
                    && Math.abs(f3.designPitchDeg - d3 - 5) < 1e-9
                    && bits(f3.sPitchDeg, hp0) && Arrays.equals(entry0, entry1)
                    && f3.orientConflict(),
                "f3 DESIGN_ONLY seam divert: design += the delta, held pitch"
                + " restored bitwise, the real seam entry bitwise where it was,"
                + " the conflict opened");
            // f5 seamDesignSet: absolute design elevation / bank + refusals
            Coaster c5 = chain(BridgeSection.OPT_NONE, LS);
            TrackPiece f5 = c5.pieces.get(2);
            f5.orientMode = DO;
            rebuild(c5);
            long[] real0 = poseBits(f5.entryPose());
            boolean s1 = Coaster.seamDesignSet(f5, 1, 12);
            double[] g = Coaster.eulerOf(f5.designPose().orient());
            boolean s2 = Coaster.seamDesignSet(f5, 2, -20);
            double[] g2 = Coaster.eulerOf(f5.designPose().orient());
            double dp = f5.designPitchDeg, dr = f5.designRollDeg;
            boolean s0 = Coaster.seamDesignSet(f5, 0, 30);
            check(s1 && s2 && !s0 && Math.abs(g[1] - 12) < 1e-9
                    && Math.abs(g2[1] - 12) < 1e-9 && Math.abs(g2[2] + 20) < 1e-9
                    && f5.designPitchDeg == dp && f5.designRollDeg == dr
                    && Arrays.equals(real0, poseBits(f5.entryPose())),
                "f5 seamDesignSet: elevation := 12 then bank := −20 (elevation"
                + " kept), yaw refused untouched, the real entry pose bitwise",
                String.format("design euler %.6f / %.6f", g2[1], g2[2]));
            TrackPiece v = new TrackPiece(0, 21, 0, 0);
            v.designPitchDeg = 90;                   // a vertical design frame
            boolean sv = Coaster.seamDesignSet(v, 1, 10);
            check(!sv && v.designPitchDeg == 90,
                "f5b seamDesignSet refuses a vertical design frame, nothing"
                + " written");
        }

        // ---------- (g) the bridge march ----------
        {
            // g1 LOCKSTEP: optimize-orientation aligns BOTH tiers
            Coaster c = chain(BridgeSection.OPT_POS_YAW, LS);
            TrackPiece hA = c.pieces.get(0), t = c.pieces.get(1);
            BridgeSection bs = (BridgeSection) hA.sections.get(hA.sections.size() - 1);
            bs.lockPitch = false;
            bs.lockRoll = false;
            c.optimizeBridge(bs, 1, 45, true);
            check(!t.orientConflict() && Math.abs(t.pitchDeg) > 1e-9
                    && bits(t.pitchDeg, t.designPitchDeg)
                    && bits(t.rollDeg, t.designRollDeg),
                "g1 LOCKSTEP march: pitch + roll aligned on BOTH tiers — no"
                + " conflict", String.format("pitch %.4f roll %.4f",
                    t.pitchDeg, t.rollDeg));
            TrackCompiler.Result r = rebuild(c);
            int p1 = passes;
            boolean still = !restless(c);
            check(still && p1 <= 1,
                "g4 ... the rebuild loop sees a FIXED POINT within one pass (the"
                + " inner settle) and the next rebuild is a no-op",
                "passes " + p1);
            double land = r.spans().get(1).exit().pos()
                .sub(t.entryPose().pos()).length();
            check(land < 0.02, "g1b ... the bridge lands on the re-solved target",
                String.format("landing %.2e m", land));
            // g4b an UPSTREAM edit re-marches once and rests again
            hA.editPitch(hA.pitchDeg + 3);
            rebuild(c);
            int p2 = passes;
            check(!restless(c) && p2 <= 2 && !t.orientConflict()
                    && bits(t.pitchDeg, t.designPitchDeg),
                "g4b upstream tilt under LOCKSTEP: the auto pass re-marches,"
                + " rests within two passes, tiers still equal",
                "passes " + p2);
            // g2 REAL_ONLY: true only — the pre-D249 numbers
            Coaster d = chain(BridgeSection.OPT_POS_YAW, RO);
            TrackPiece dA = d.pieces.get(0), dt = d.pieces.get(1);
            BridgeSection dbs = (BridgeSection) dA.sections.get(dA.sections.size() - 1);
            dbs.lockPitch = false;
            dbs.lockRoll = false;
            d.optimizeBridge(dbs, 1, 45, true);
            check(dt.orientConflict() && dt.designPitchDeg == 0
                    && dt.designRollDeg == 0 && Math.abs(dt.pitchDeg) > 1e-9,
                "g2 REAL_ONLY march: true tier only, design (0,0), conflict",
                String.format("pitch %.6f", dt.pitchDeg));
            // recorded on this fixture before the D249 routing landed
            // (MarchProbe, 2026-09-24: −27.507015) — the REAL_ONLY path
            // is bitwise the historic write by construction
            check(Math.abs(dt.pitchDeg - (-27.507015)) < 1e-6,
                "g2b ... and it is the recorded pre-D249 alignment",
                String.format("pitch %.6f (recorded −27.507015)", dt.pitchDeg));
            long[] dtBits = pieceBits(dt);   // right after the explicit optimize
            rebuild(d);
            check(!restless(d), "g2c ... REAL_ONLY rests (the historic one-pass"
                + " settle)");
            // g3 DESIGN_ONLY (defensive): bitwise the REAL_ONLY write
            Coaster e = chain(BridgeSection.OPT_POS_YAW, DO);
            TrackPiece eA = e.pieces.get(0), et = e.pieces.get(1);
            BridgeSection ebs = (BridgeSection) eA.sections.get(eA.sections.size() - 1);
            ebs.lockPitch = false;
            ebs.lockRoll = false;
            e.optimizeBridge(ebs, 1, 45, true);
            check(Arrays.equals(pieceBits(et), dtBits),
                "g3 DESIGN_ONLY target under an optimize-orientation march:"
                + " bitwise the REAL_ONLY write (defensive true-only)");
            // g5 yaw-only alignment (default locks): no re-solve in any
            // mode — LOCKSTEP == REAL_ONLY bitwise
            Coaster y1 = chain(BridgeSection.OPT_POS_YAW, LS);
            Coaster y2 = chain(BridgeSection.OPT_POS_YAW, RO);
            BridgeSection b1 = (BridgeSection) y1.pieces.get(0).sections.get(1);
            BridgeSection b2 = (BridgeSection) y2.pieces.get(0).sections.get(1);
            y1.optimizeBridge(b1, 1, 45, true);
            y2.optimizeBridge(b2, 1, 45, true);
            check(Arrays.equals(pieceBits(y1.pieces.get(1)), pieceBits(y2.pieces.get(1)))
                    && y1.pieces.get(1).designPitchDeg == 0,
                "g5 yaw-only alignment (pitch/roll padlocked): LOCKSTEP =="
                + " REAL_ONLY bitwise, design untouched");
            // g6 OPT_POS: no orientation write in any mode
            Coaster o = chain(BridgeSection.OPT_POS, LS);
            TrackPiece ot = o.pieces.get(1);
            BridgeSection obs = (BridgeSection) o.pieces.get(0).sections.get(1);
            double oy = ot.yawDeg, op = ot.pitchDeg, orl = ot.rollDeg;
            o.optimizeBridge(obs, 1, 45, false);
            check(ot.yawDeg == oy && ot.pitchDeg == op && ot.rollDeg == orl
                    && ot.designPitchDeg == 0,
                "g6 OPT_POS march writes position only in every mode");
            // g7 REST GUARANTEE (D249 review round 2): slow pitched
            // LOCKSTEP targets — the settle's fixed point is REPELLING
            // (gain > 1), the loop exits unconverged; the stamp must
            // still equal the next capture so the document rests and a
            // second rebuild is a bitwise no-op (the pre-fix loop
            // stamped the penultimate capture: 52 passes, restless,
            // every rebuild re-marching — the D197 class)
            double[][] slow = { {8, 30, 40}, {8, -30, 40}, {5, 25, 30},
                {5, -25, 30}, {2, 30, 10} };
            boolean g7 = true;
            StringBuilder g7d = new StringBuilder();
            for (double[] sc : slow) {
                Coaster s = slowChain(sc[0], sc[1], sc[2], false);
                TrackPiece st = s.pieces.get(1);
                BridgeSection sbs = (BridgeSection) s.pieces.get(0).sections.get(1);
                double chord = new Vec3(st.x, st.y, st.z)
                    .sub(sbs.solvedEntry.pos()).length();
                s.optimizeBridge(sbs, 1, chord * 1.2, true);
                TrackCompiler.Result s1 = rebuild(s);
                int sp1 = passes;
                boolean rest = !restless(s);
                long[] pb1 = pieceBits(st);
                TrackCompiler.Result s2 = rebuild(s);
                int sp2 = passes;
                boolean noop = sp2 == 0 && Arrays.equals(pb1, pieceBits(st))
                    && samplesBitwise(s1.samples(), s2.samples());
                boolean tiers = bits(st.pitchDeg, st.designPitchDeg)
                    && bits(st.rollDeg, st.designRollDeg);
                g7 &= rest && sp1 <= 2 && noop && tiers;
                g7d.append(String.format(Locale.US, " [v%.0f %+.0f°: passes %d"
                    + " rest %b noop %b pitch %.3f]", sc[0], sc[1], sp1, rest,
                    noop, st.pitchDeg));
            }
            check(g7, "g7 REST GUARANTEE: slow pitched LOCKSTEP targets under"
                + " OPT_POS_YAW rest in ≤ 2 passes, tiers equal, and a second"
                + " rebuild is a BITWISE no-op", g7d.toString().trim());
            // g7b the optLen variant: the applied length is RE-KNEED off
            // the settled seams (the compile's knee was stale once the
            // settle moved the head dynamics); a second explicit
            // optimize at the knee off the settled seams keeps it (the
            // contracting family — the gate fixture's own layout at
            // vDesign 24 and the slow v8 +30° case), and rests
            double[][] kn = { {24, 0, 120}, {8, 30, 40} };
            boolean g7b = true;
            StringBuilder kd = new StringBuilder();
            for (double[] sc : kn) {
                Coaster s = slowChain(sc[0], sc[1], sc[2], true);
                BridgeSection sbs = (BridgeSection) s.pieces.get(0).sections.get(1);
                double knee1 = s.optimalBridgeLength(sbs, true);
                s.optimizeBridge(sbs, 1, knee1, true);
                double l1 = sbs.lastOptDist;
                double knee2 = s.optimalBridgeLength(sbs, true);   // settled seams
                boolean rest1 = !restless(s);
                s.optimizeBridge(sbs, 1, knee2, true);
                double l2 = sbs.lastOptDist;
                double knee3 = s.optimalBridgeLength(sbs, true);
                boolean rest2 = !restless(s);
                g7b &= rest1 && rest2 && Math.abs(knee3 - l2) < 1e-9
                    && Math.abs(l2 - knee2) < 1e-9;
                kd.append(String.format(Locale.US, " [v%.0f: knee %.3f → applied"
                    + " %.3f, settled knee %.3f, re-applied %.3f → knee %.3f]",
                    sc[0], knee1, l1, knee2, l2, knee3));
            }
            check(g7b, "g7b Optimize length under LOCKSTEP re-knees off the"
                + " SETTLED seams; a second explicit optimize at that knee keeps"
                + " it and rests", kd.toString().trim());
        }

        // ---------- (h) item O: the seam design box + the full-scope Cancel ----------
        {
            // h1 the DESIGN box: value = design elevation/bank; a write
            // lands exactly; held angles / locks / the real entry bitwise
            Coaster c = chain(BridgeSection.OPT_NONE, LS);
            TrackPiece f = c.pieces.get(2);
            f.orientMode = DO;
            TrackCompiler.Result r = rebuild(c);
            long[] entry0 = poseBits(Coaster.strictEntry(r.spans().get(2).exit(), f));
            long[] held0 = pieceBits(f);
            boolean l0 = f.sLockPitch, l1 = f.sLockRoll;
            double boxE = Coaster.eulerOf(f.designPose().orient())[1];
            double boxB = Coaster.eulerOf(f.designPose().orient())[2];
            boolean w1 = Coaster.seamDesignSet(f, 1, boxE + 7);
            boolean w2 = Coaster.seamDesignSet(f, 2, boxB - 11);
            double[] g = Coaster.eulerOf(f.designPose().orient());
            TrackCompiler.Result r1 = TrackCompiler.compile(c, DT);
            long[] entry1 = poseBits(Coaster.strictEntry(r1.spans().get(2).exit(), f));
            long[] held1 = pieceBits(f);
            boolean heldSame = held0[8] == held1[8] && held0[9] == held1[9]
                && held0[10] == held1[10];
            check(w1 && w2 && Math.abs(g[1] - (boxE + 7)) < 1e-9
                    && Math.abs(TrackPiece.wrap180(g[2] - (boxB - 11))) < 1e-9
                    && heldSame && f.sLockPitch == l0 && f.sLockRoll == l1
                    && Arrays.equals(entry0, entry1),
                "h1 seam DESIGN box: typed elevation/bank land on"
                + " eulerOf(designPose) exactly; held angles, locks and the"
                + " real seam entry bitwise untouched",
                String.format("box %.4f/%.4f → %.4f/%.4f", boxE, boxB, g[1], g[2]));
        }
        {
            // h2 the full-scope Cancel: OPT_POS bridge, a REAL_ONLY +
            // carrying head, a held follower — capture at rest
            Coaster c = chain(BridgeSection.OPT_POS, RO);
            TrackPiece hA = c.pieces.get(0), hB = c.pieces.get(1),
                hC = c.pieces.get(2);
            hA.orientMode = RO;
            hA.affectSubsequentSeams = true;
            BridgeSection bs = (BridgeSection) hA.sections.get(hA.sections.size() - 1);
            TrackCompiler.Result r0 = rebuild(c);
            r0 = TrackCompiler.compile(c, DT);
            PlacementSnapshot snap = PlacementSnapshot.capture(c);
            boolean untouched = !snap.differs();
            long[] a0 = pieceBits(hA), b0 = pieceBits(hB), c0 = pieceBits(hC);
            Quat baseA = hA.baseOrient, baseC = hC.baseOrient, canC = hC.canonOrient;
            double[] sig0 = bs.optSig;
            double lod0 = bs.lastOptDist, lenA0 = bs.lenA;
            boolean ly0 = bs.lockYaw, lp0 = bs.lockPitch;
            int mode0 = bs.optMode;
            boolean opt0 = bs.optLen;
            // the edits: a CARRIED head edit (target + follower move),
            // mode/flag flips, a seam-lock flip, bridge field writes, an
            // emulated Design → Real bake on the follower (baseOrient
            // replaced, tiers zeroed, canonical pair nulled)
            TrackPose be = hA.entryPose();
            hA.x += 3; hA.y -= 2;
            hA.editPitch(hA.pitchDeg + 5);
            c.carryAfterEdit(0, be, hA.entryPose(), r0.spans());
            hB.orientMode = DO;
            hB.designRollDeg += 4;
            hB.affectSubsequentSeams = true;
            hB.uiLockPitch = false;
            hC.sLockPitch = !hC.sLockPitch;
            hC.sSmoothLen = 6;
            hC.baseOrient = Quat.aboutY(17).mul(hC.baseOrient);
            hC.designPitchDeg = 0; hC.pitchDeg = 0;
            hC.canonOrient = null;
            bs.optSig = BridgeSection.SIG_STALE;
            bs.lastOptDist = 33;
            bs.lenA = 9;
            bs.lockYaw = !bs.lockYaw;
            bs.lockPitch = !bs.lockPitch;
            bs.optMode = BridgeSection.OPT_NONE;
            bs.optLen = !bs.optLen;
            boolean moved = !Arrays.equals(b0, pieceBits(hB))
                && !Arrays.equals(c0, pieceBits(hC));
            boolean differs = snap.differs();
            int n = snap.restore();
            boolean fields = Arrays.equals(a0, pieceBits(hA))
                && Arrays.equals(b0, pieceBits(hB))
                && Arrays.equals(c0, pieceBits(hC))
                && hA.orientMode == RO && hA.affectSubsequentSeams
                && hB.orientMode == RO && !hB.affectSubsequentSeams
                && hB.uiLockPitch
                && hA.baseOrient == baseA && hC.baseOrient == baseC
                && hC.canonOrient == canC;
            // chain() holds all three follower axes and leaves smoothing off
            boolean locks = hC.sLockPitch && hC.sSmoothLen == 0;
            boolean bridge = bs.optSig != BridgeSection.SIG_STALE
                && ((sig0 == null && bs.optSig == null)
                    || (sig0 != null && bs.optSig != null
                        && Arrays.equals(sig0, bs.optSig)))
                && bits(bs.lastOptDist, lod0) && bits(bs.lenA, lenA0)
                && bs.lockYaw == ly0 && bs.lockPitch == lp0
                && bs.optMode == mode0 && bs.optLen == opt0;
            check(untouched && moved && differs && n == 3 && fields && locks
                    && bridge,
                "h2 PlacementSnapshot: a carried head edit + mode/flag/lock"
                + " flips + bridge writes + an emulated bake → restore returns"
                + " every piece's fields, baseOrient / canonical identities"
                + " and the bridge's fields (optSig incl.) BITWISE",
                "restored " + n + " pieces");
            TrackCompiler.Result r2 = TrackCompiler.compile(c, DT);
            check(samplesBitwise(r0.samples(), r2.samples()),
                "h2b ... the compile is bitwise the open-time compile");
            rebuild(c);
            check(passes == 0 && !restless(c),
                "h2c ... and the next rebuild is a no-op (the restored"
                + " signature matches the open-time seams)",
                "passes " + passes);
            check(!snap.differs(), "h3 an untouched record does not differ"
                + " (Cancel pushes no undo step)");
        }
        {
            // h4 a vanished piece is skipped, an appended one untouched
            Coaster c = chain(BridgeSection.OPT_NONE, LS);
            TrackPiece hA = c.pieces.get(0), hB = c.pieces.get(1),
                hC = c.pieces.get(2);
            PlacementSnapshot snap = PlacementSnapshot.capture(c);
            long[] a0 = pieceBits(hA);
            c.pieces.remove(hC);
            TrackPiece nw = new TrackPiece(5, 6, 7, 8);
            nw.sections.add(new ForceSection(flatLanes(2), 20));
            c.pieces.add(nw);
            hA.x += 10;
            nw.x += 1;
            int n = snap.restore();
            check(n == 2 && Arrays.equals(a0, pieceBits(hA)) && nw.x == 6
                    && c.pieces.size() == 3 && !c.pieces.contains(hC),
                "h4 restore skips a vanished piece, leaves an appended one"
                + " untouched, restores the survivors", "restored " + n);
        }
        {
            // h5 a replaced bridge object skips its bridge half; the
            // SIG_STALE / null sentinels keep their identity
            Coaster c = chain(BridgeSection.OPT_POS, LS);
            TrackPiece hA = c.pieces.get(0);
            BridgeSection bs = (BridgeSection) hA.sections.get(hA.sections.size() - 1);
            rebuild(c);
            bs.optSig = BridgeSection.SIG_STALE;
            PlacementSnapshot s1 = PlacementSnapshot.capture(c);
            bs.optSig = null;
            s1.restore();
            boolean stale = bs.optSig == BridgeSection.SIG_STALE;
            bs.optSig = null;
            PlacementSnapshot s2 = PlacementSnapshot.capture(c);
            bs.optSig = new double[]{ 1, 2, 3 };
            boolean d2 = s2.differs();
            s2.restore();
            boolean nul = bs.optSig == null;
            // replace the bridge object: the new one's fields stay
            BridgeSection nb = bs.copy();
            nb.lastOptDist = 99;
            hA.sections.set(hA.sections.size() - 1, nb);
            boolean d3 = !s2.differs();   // a replaced bridge never differs
            s2.restore();
            check(stale && d2 && nul && d3 && nb.lastOptDist == 99,
                "h5 sentinels: SIG_STALE and null restore by identity; a"
                + " replaced bridge object is skipped (its fields stay)");
            // h6 the seam rig's DESIGN_ONLY roll / pitch rings (D249
            // review round 2): Viewport3D.seamLocal hands
            // gizmoSetSeamDesignAll heldYaw = y0 + wrap180(e[0] − h0)
            // for EVERY ring; about T (roll) and about the horizontal
            // right (pitch, unbanked design frame) the heading delta
            // is eulerOf dust (1e-16..1e-13°) — below the 1e-9 write
            // guard, so the held yaw never re-aims the real entry
            {
                Coaster c6 = chain(BridgeSection.OPT_NONE, LS);
                TrackPiece f6 = c6.pieces.get(2);
                f6.orientMode = DO;
                TrackCompiler.Result r6 = rebuild(c6);
                long[] real0 = poseBits(Coaster.strictEntry(
                    r6.spans().get(2).exit(), f6));
                long yaw0 = Double.doubleToLongBits(f6.sYawDeg);
                Quat q0 = f6.designPose().orient();
                double h0 = Coaster.eulerOf(q0)[0], y0 = f6.sYawDeg;
                Vec3 tAx = q0.T();
                Vec3 hr = q0.U().cross(q0.T());   // rightOf — unbanked = horizontal
                double maxDust = 0;
                boolean writes = true;
                for (double deg : new double[] { -30, 17, 45 }) {
                    for (Vec3 ax : new Vec3[] { tAx, hr }) {
                        Quat tgt = Quat.aboutAxis(ax, Math.toRadians(deg)).mul(q0);
                        double[] e = Coaster.eulerOf(tgt);
                        double heldYaw = y0 + TrackPiece.wrap180(e[0] - h0);
                        maxDust = Math.max(maxDust,
                            Math.abs(TrackPiece.wrap180(heldYaw - f6.sYawDeg)));
                        writes &= Coaster.seamDesignSet(f6, 1, e[1])
                            && Coaster.seamDesignSet(f6, 2, e[2]);
                    }
                }
                TrackCompiler.Result r7 = TrackCompiler.compile(c6, DT);
                long[] real1 = poseBits(Coaster.strictEntry(
                    r7.spans().get(2).exit(), f6));
                check(maxDust < 1e-9 && writes
                        && Double.doubleToLongBits(f6.sYawDeg) == yaw0
                        && Arrays.equals(real0, real1),
                    "h6 DESIGN_ONLY roll / unbanked pitch ring: the held-yaw"
                    + " delta is dust under the 1e-9 write guard; the design"
                    + " writes keep sYawDeg and the real seam entry bitwise",
                    String.format("max heading dust %.2e°", maxDust));
            }
        }

        System.out.println();
        System.out.println(fail == 0
            ? "ALL ORIENT-MODE GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
