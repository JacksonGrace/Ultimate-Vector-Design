package coaster;

import java.io.*;
import java.util.*;

/** D237 gate — the PHANTOM UNRESOLVABLE ≠v (the user's "Conflict not
 *  getting resolved 8.25.26.fvd", embedded below verbatim): a strict
 *  follower's FIRST section carries a small stale design speed
 *  (0.155 m/s at the seam — under DRIFT_EPS), the constant ENERGY
 *  offset amplifies to 0.357 m/s at the next section's slow crest
 *  (over DRIFT_EPS → badge), and the reconcile's real-anchor gate —
 *  which tested the SEAM gap against the same 0.2 — refused to
 *  recognize either resolve op's anchor and snapped it back every
 *  rebuild. Checks:
 *  a) the scenario reproduces (B badges, A does not);
 *  b) "Set Design to Real" (refreezeSpeed) on B STICKS through the
 *     rebuild loop — bitwise (the ops anchor at the reconcile's own
 *     seam sample now);
 *  c) "Convert Real to Design" (makeGraphsReal) on B sticks too;
 *  d) refreezing the SOURCE section (A) clears the whole chain.
 *  EXTENDED v19.19 (D248 — the INVISIBLE lateral-G true stroke under an
 *  ORIENTATION conflict): GraphEditor's true-stroke split reference
 *  keyed off the pre-v19.0 true-tier test (pitchDeg||rollDeg ≠ 0), so a
 *  DESIGN-tier conflict (D222) or a follower's HELD-SEAM tilt (D247) —
 *  both rigid tilts in the compile — compared the true felt against the
 *  already-tilted compiled felt and culled the stroke wherever the
 *  speeds matched (the user's "band in which it wasn't visible"). The
 *  editor now selects the AUTHORED lane as the reference on the
 *  deviating lanes of an orientation-CONFLICTED piece
 *  (orientConflictAt × LaneSection.tiltDeviates per lane). Pure-model
 *  twin of that selector on the real compile (e/f/g):
 *  e1-e3) fixtures A (true roll 30 / design 0 — the D100 path, worked
 *     before), B (design roll 30 / true 0), C (strict follower, held
 *     seam roll −30, entry speed matched so only the tilt speaks): the
 *     NEW rule draws ≥ 98% of the active section's samples in all
 *     three; the OLD rule drew < 60% on B and C (the bug, documented);
 *  e4) D: a follower's held YAW only (pure heading re-aim) = no
 *     conflict and nothing to draw;
 *  e5) E: lockstep design == true ≠ 0 = no conflict, authored == the
 *     compiled felt;
 *  e6) A's force LOCAL roll lane takes the AUTHORED reference like the
 *     felt (the D248 brief: the whole force section keeps D100's rule)
 *     and that reference is harmless — authored == derived body roll
 *     rate up to differencing dust (rotation-invariant);
 *  e7) F: a PITCH conflict (design pitch 20 / true 0) through the Gv
 *     lane;
 *  f) the LaneSection.tiltDeviates truth table (every force lane,
 *     geo world-axis / effective-roll / absolute lanes deviate; geo
 *     local body rates don't);
 *  g) G: a geo ABSOLUTE-bank section on a design-roll-30 piece — the
 *     authored bank sits ~30° off the tilted samples' bankOfTU, and
 *     the per-lane rule picks the authored reference for lane 2 only. */
public class ConflictGate {

    static final double DT = 0.01;
    static final double EPS = 0.2;   // GraphEditor.DRIFT_EPS

    static final String FVD = String.join("\n",
        "FVDD4",
        "ncoasters 2",
        "coaster Coaster 1",
        "v0 20.0",
        "friction 0.03 1.0E-4",
        "piece 0.0 5.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 20.0 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "coaster Coaster 2",
        "v0 20.0",
        "friction 0.03 1.0E-4",
        "piece 0.0 10.0 0.0 -0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 20.0 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 3.550373831775701 1.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 5.0 1.8 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "sctl 0 0 4.12570093457944 0.2876635514018684 25.0 0.5 1 1 0.5 1",
        "endsection",
        "endpiece",
        "piece 101.00558685777081 12.525635131104872 0.0 0.0 1.0 0.0 -0.0 -2.069147755613188E-17 0.0 0.0 1 1 1 1 -0.0 -2.3710686717120474E-15 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 24.108558580194405 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "section force 23.757080736105365 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.39773554885220364 2.4489795918367347 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 0.7444678598607126 0.42857142857142905 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 1.0 1.0 0.3633802276324186 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.583550580343065 1.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 1.9835505803430649 3.1 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 4.030671555919328 3.1 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 2.1909574811102335 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "");

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        String tag = ok ? "PASS  " : "FAIL  ";
        if (ok) pass++; else fail++;
        System.out.println(tag + name + (detail.isEmpty()
            ? "" : "  (" + detail + ")"));
    }

    static Coaster load() throws Exception {
        return DesignIO.load(new BufferedReader(new StringReader(FVD)))
            .coasters().get(1);   // Coaster 2
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

    /** GraphEditor.sectionDrift replicated: whole-span max split. */
    static double drift(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(),
            Math.min(r.trueV().length, r.samples().size()) - 1);
        double worst = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    // ---------- D248: the TRUE-STROKE split reference (pure model) ----------

    /** ≈ GraphEditor.TRUE_SPLIT_PX (1.5 px) on a −1..1 g lane over
     *  ~300 px: the stroke draws where |true − reference| exceeds it. */
    static final double STROKE_G = 0.01;
    /** The same band on a ±90° absolute-bank lane. */
    static final double STROKE_DEG = 1.0;

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] q : pts) l.add(new BezierBaker.Node(q[0], q[1]));
        return l;
    }
    static List<List<BezierBaker.Node>> hillLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.2, 1}, {2.4, 3.2}, {4, 0.4}, {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.4}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 40}, {5.5, 0} }));
        return ls;
    }
    /** A straight LEVEL run (Gv 1, Gl 0, roll 0) — an exit with zero
     *  elevation and bank, so a held seam roll reads as the plain
     *  bank band sin 30° = 0.5 g (the hill's lateral through its
     *  pitch change drifts the bank ~95° with local roll 0). */
    static List<List<BezierBaker.Node>> levelLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        return ls;
    }
    static List<List<BezierBaker.Node>> turnLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.5, 2.2}, {3, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, -0.5}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        return ls;
    }
    /** Geo lanes: pitch 0, yaw 0, ABSOLUTE bank held at `bank`°. */
    static List<List<BezierBaker.Node>> bankLanes(double bank) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, bank}, {3, bank} }));
        return ls;
    }

    /** The PRE-D248 selector (GraphEditor.pieceTilted): true-tier
     *  deltas only — the bug. */
    static boolean oldTilted(TrackPiece tp) {
        return tp.pitchDeg != 0 || tp.rollDeg != 0;
    }

    /** GraphEditor.orientConflictAt's model twin (D247): a follower's
     *  seam tilt with a horizontal component, else the two tiers. */
    static boolean conflictAt(Coaster c, TrackCompiler.Result r, int p) {
        TrackPiece tp = c.pieces.get(p);
        if (tp.followPrev) {
            int prevLast = spanIdx(c, p) - 1;
            return Coaster.isSeamOrientConflict(
                c.seamConflictTilt(p, r.spans().get(prevLast).exit()));
        }
        return tp.orientConflict();
    }

    /** Flattened span index of piece p's section 0. */
    static int spanIdx(Coaster c, int p) {
        int k = 0;
        for (int q = 0; q < p; q++) k += c.pieces.get(q).sections.size();
        return k;
    }

    /** Fraction of the span's samples where the true FORCE felt
     *  splits off the chosen reference on lane L (0 = Gv, 1 = Gl):
     *  reference = the AUTHORED lane when `authored`, else the
     *  compiled (tilt-re-projected) felt — GraphEditor.splitRef's
     *  model twin for a force section. */
    static double drawnFelt(TrackCompiler.Result r, TrackCompiler.Span sp,
            LaneSection ls, int L, boolean authored) {
        int n = 0, drawn = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double auth = BezierBaker.evalAt(ls.lanes.get(L), sm.t() - sp.t0());
            double der = L == 0 ? sm.Gv() : sm.Gl();
            double tru = r.trueFelt()[i][L];
            if (Math.abs(tru - (authored ? auth : der)) > STROKE_G) drawn++;
            n++;
        }
        return n == 0 ? 0 : drawn / (double) n;
    }

    /** drawnFelt over the span's FIRST `n` samples only (the entry
     *  window, where a free piece's speeds still match — the band the
     *  user reported). */
    static double drawnFeltHead(TrackCompiler.Result r, TrackCompiler.Span sp,
            LaneSection ls, int L, boolean authored, int n) {
        int drawn = 0, cnt = 0;
        for (int i = sp.i0(); i <= Math.min(sp.i1(), sp.i0() + n - 1); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double auth = BezierBaker.evalAt(ls.lanes.get(L), sm.t() - sp.t0());
            double der = L == 0 ? sm.Gv() : sm.Gl();
            double tru = r.trueFelt()[i][L];
            if (Math.abs(tru - (authored ? auth : der)) > STROKE_G) drawn++;
            cnt++;
        }
        return cnt == 0 ? 0 : drawn / (double) cnt;
    }

    /** max |authored − compiled felt| over the span on lane L. */
    static double maxAuthVsDer(TrackCompiler.Result r, TrackCompiler.Span sp,
            LaneSection ls, int L) {
        double m = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double auth = BezierBaker.evalAt(ls.lanes.get(L), sm.t() - sp.t0());
            double der = L == 0 ? sm.Gv() : sm.Gl();
            m = Math.max(m, Math.abs(auth - der));
        }
        return m;
    }

    /** max |true − compiled| felt over the span on lane L (the
     *  derived-reference split — zero wherever the speeds match). */
    static double maxTrueVsDer(TrackCompiler.Result r, TrackCompiler.Span sp, int L) {
        double m = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double der = L == 0 ? sm.Gv() : sm.Gl();
            m = Math.max(m, Math.abs(r.trueFelt()[i][L] - der));
        }
        return m;
    }

    /** Free-piece fixture: turn + hill force sections at (0, 21, 0). */
    static Coaster freePiece() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(turnLanes(), 24));
        p.sections.add(new ForceSection(hillLanes(), 24));
        c.pieces.add(p);
        return c;
    }

    /** Follower fixture: hill piece + a strict follower (turn + hill)
     *  whose held seam angles seed from the follow contract, then
     *  roll −30 / yaw +30 per the flags; the follower's entry design
     *  speed := its true entry speed so only the tilt speaks. */
    static Coaster follower(double dRoll, double dYaw, boolean lockPitchRoll) {
        Coaster c = new Coaster();
        TrackPiece pa = new TrackPiece(0, 21, 0, 0);
        pa.sections.add(new ForceSection(levelLanes(), 26));
        c.pieces.add(pa);
        TrackPiece pb = new TrackPiece(0, 0, 0, 0);
        pb.sections.add(new ForceSection(turnLanes(), 24));
        pb.sections.add(new ForceSection(hillLanes(), 24));
        pb.followPrev = true;
        pb.sLockYaw = dYaw != 0;
        pb.sLockPitch = lockPitchRoll;
        pb.sLockRoll = lockPitchRoll;
        c.pieces.add(pb);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        // the follower's DESIGN pose = the boundary pose (the D71
        // makeSeamStrict recipe) so the seam tilt is exactly the held
        // gap — an identity baseOrient would read the upstream's exit
        // elevation as a conflict of its own
        pb.baseOrient = r0.spans().get(0).exit().orient();
        // held angles seed from the INCOMING frame's own triple (the
        // D247 creation rule — reading strictEntry with the locks
        // already on would return the default 0/0 held values)
        double[] seed = Coaster.eulerOf(r0.spans().get(0).exit().orient());
        pb.sYawDeg = (Double.isNaN(seed[0]) ? 0 : seed[0]) + dYaw;
        pb.sPitchDeg = seed[1];
        pb.sRollDeg = (Double.isNaN(seed[2]) ? 0 : seed[2]) + dRoll;
        // the follower's design entry speed := the TRUE speed AT the
        // seam sample (span 0's last sample IS the seam)
        pb.sections.get(0).vDesign(r0.trueV()[r0.spans().get(0).i1()]);
        return c;
    }

    static void trueRefBlock() {
        // ---- e1: A — TRUE-tier roll 30 (the D100 path) ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.editRoll(30);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            double dOld = drawnFelt(r, sp, ls, 1, oldTilted(p));
            check(conf && dNew >= 0.98 && dOld >= 0.98,
                "e1 A true roll 30 / design 0: Gl stroke draws the whole section (both rules)",
                String.format("conflict %b new %.3f old %.3f band %.3f g",
                    conf, dNew, dOld, maxAuthVsDer(r, sp, ls, 1)));
            // e6: the force LOCAL roll lane takes the AUTHORED
            // reference with the rest of the force section (the D248
            // brief keeps D100's whole-section rule) — harmless,
            // because a rigid tilt leaves the BODY roll rate exactly
            // the authored lane up to the differencing dust, so the
            // authored and derived references draw the same stroke
            // (true vs either still splits by the SPEED term — a tilt
            // changes heights — the honest speed split, not a band)
            double mRoll = 0;
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                double auth = BezierBaker.evalAt(ls.lanes.get(2),
                    r.samples().get(i).t() - sp.t0());
                mRoll = Math.max(mRoll, Math.abs(auth - r.rates()[i][3]));
            }
            check(LaneSection.tiltDeviates(ls, 2) && mRoll < 0.5,
                "e6 A force local roll lane: authored reference (D248 brief), harmless — authored == derived body roll rate",
                String.format("deviates %b max|authored-derivedRoll| %.2e deg/s",
                    LaneSection.tiltDeviates(ls, 2), mRoll));
        }
        // ---- e2: B — DESIGN-tier roll 30 / true 0 ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.designRollDeg = 30;
            p.rollDeg = 0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            double dOld = drawnFelt(r, sp, ls, 1, oldTilted(p));
            check(conf && dNew >= 0.98 && dOld < 0.60,
                "e2 B design roll 30 / true 0: the NEW rule draws the whole section, the OLD rule culled it",
                String.format("conflict %b new %.3f old %.3f band %.3f g",
                    conf, dNew, dOld, maxAuthVsDer(r, sp, ls, 1)));
        }
        // ---- e3: C — strict follower, held seam roll −30 ----
        {
            Coaster c = follower(-30, 0, true);
            TrackPiece p = c.pieces.get(1);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(spanIdx(c, 1));
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 1);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            double dOld = drawnFelt(r, sp, ls, 1, oldTilted(p));
            check(conf && dNew >= 0.98 && dOld < 0.60,
                "e3 C follower held roll -30: NEW rule draws the whole first section, OLD rule culled it",
                String.format("conflict %b new %.3f old %.3f band %.3f g speedSplit %.2e g",
                    conf, dNew, dOld, maxAuthVsDer(r, sp, ls, 1), maxTrueVsDer(r, sp, 1)));
        }
        // ---- e4: D — follower, held YAW only (pure heading re-aim) ----
        {
            Coaster c = follower(0, 30, false);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(spanIdx(c, 1));
            LaneSection ls = (LaneSection) c.pieces.get(1).sections.get(0);
            boolean conf = conflictAt(c, r, 1);
            double sGv = maxTrueVsDer(r, sp, 0), sGl = maxTrueVsDer(r, sp, 1);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            // (the entry speed is matched one dt inside the seam, so a
            // ~3e-3 g residual speed split remains — below the stroke
            // threshold: nothing draws)
            check(!conf && sGv < STROKE_G && sGl < STROKE_G && dNew == 0,
                "e4 D follower heading-only re-aim: no conflict, derived reference, nothing draws",
                String.format("conflict %b max|true-derived| Gv %.2e Gl %.2e drawn %.3f",
                    conf, sGv, sGl, dNew));
        }
        // ---- e5: E — lockstep design == true == 30 ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            p.editRoll(30);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            double bGv = maxAuthVsDer(r, sp, ls, 0), bGl = maxAuthVsDer(r, sp, ls, 1);
            // baseline: the same recording dust on an UNtilted piece
            Coaster c0 = freePiece();
            TrackCompiler.Result r0 = TrackCompiler.compile(c0, DT);
            LaneSection ls0 = (LaneSection) c0.pieces.get(0).sections.get(0);
            double b0 = Math.max(maxAuthVsDer(r0, r0.spans().get(0), ls0, 0),
                                 maxAuthVsDer(r0, r0.spans().get(0), ls0, 1));
            check(!conf && p.designRollDeg == 30 && p.rollDeg == 30
                    && bGv < STROKE_G && bGl < STROKE_G && oldTilted(p),
                "e5 E lockstep design == true: no conflict, no tilt band (authored == compiled felt below the stroke threshold; the old rule fired needlessly)",
                String.format("conflict %b max|auth-der| Gv %.2e Gl %.2e (untilted baseline %.2e) g",
                    conf, bGv, bGl, b0));
        }
        // ---- e7: F — PITCH conflict (design pitch 20 / true 0) ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.designPitchDeg = 20;
            p.pitchDeg = 0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            boolean authored = conf && LaneSection.tiltDeviates(ls, 0);
            double dNew = drawnFelt(r, sp, ls, 0, authored);
            // the pitch tilt drops the whole piece's heights, so the
            // speed term alone draws most of the section under EITHER
            // rule — the bug is the ENTRY window, where the speeds still
            // match and the old derived reference culled the stroke
            double hNew = drawnFeltHead(r, sp, ls, 0, authored, 20);
            double hOld = drawnFeltHead(r, sp, ls, 0, oldTilted(p), 20);
            check(conf && dNew >= 0.90 && hNew >= 0.90 && hOld < 0.50,
                "e7 F design pitch 20 / true 0: the Gv stroke draws from the ENTRY under the NEW rule, the OLD rule culled the entry window",
                String.format("conflict %b whole new %.3f entry-20 new %.3f old %.3f band %.3f g",
                    conf, dNew, hNew, hOld, maxAuthVsDer(r, sp, ls, 0)));
        }
        // ---- f: the tiltDeviates truth table ----
        {
            ForceSection fl = new ForceSection(turnLanes(), 24);
            ForceSection fg = new ForceSection(turnLanes(), 24);
            fg.rollGlobal = true;
            GeoSection gl = new GeoSection(turnLanes(), 24);
            GeoSection gr = new GeoSection(turnLanes(), 24);
            gr.rollGlobal = true;
            GeoSection gy = new GeoSection(turnLanes(), 24);
            gy.yawPitchGlobal = true;
            GeoSection gb = new GeoSection(bankLanes(20), 24);
            gb.rollGlobal = true;
            gb.rollAbsolute = true;
            GeoSection ga = new GeoSection(turnLanes(), 24);
            ga.yawPitchGlobal = true;
            ga.ypAbsolute = true;
            boolean ok = true;
            ok &= tt(fl, true, true, true);     // force local: every lane (D100 rule kept, brief)
            ok &= tt(fg, true, true, true);     // force + global roll (bank rate)
            ok &= tt(gl, false, false, false);  // geo local rates: invariant
            ok &= tt(gr, false, false, true);   // geo + global roll
            ok &= tt(gy, true, true, true);     // geo yaw/pitch global: heading/elevation + effective roll (D177)
            ok &= tt(gb, false, false, true);   // geo absolute bank
            ok &= tt(ga, true, true, true);     // geo absolute elevation/heading
            ok &= LaneSection.tiltDeviatesAny(fl) && !LaneSection.tiltDeviatesAny(gl)
                && LaneSection.tiltDeviatesAny(gr);
            check(ok, "f1 LaneSection.tiltDeviates truth table (7 sections × 3 lanes + tiltDeviatesAny)", "");
        }
        // ---- g: G — geo ABSOLUTE bank on a design-roll-30 piece ----
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            GeoSection gb = new GeoSection(bankLanes(20), 24);
            gb.rollGlobal = true;
            gb.rollAbsolute = true;
            p.sections.add(gb);
            c.pieces.add(p);
            p.designRollDeg = 30;
            p.rollDeg = 0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            boolean conf = conflictAt(c, r, 0);
            int n = 0, drawn = 0;
            double maxBand = 0;
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                FVDIntegrator.Sample sm = r.samples().get(i);
                double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
                double der = Double.isNaN(b) ? 0 : Math.toDegrees(b);
                double auth = BezierBaker.evalAt(gb.lanes.get(2), sm.t() - sp.t0());
                maxBand = Math.max(maxBand, Math.abs(auth - der));
                if (Math.abs(auth - der) > STROKE_DEG) drawn++;
                n++;
            }
            boolean lanes = conf && LaneSection.tiltDeviates(gb, 2)
                && !LaneSection.tiltDeviates(gb, 0) && !LaneSection.tiltDeviates(gb, 1);
            check(lanes && drawn / (double) n >= 0.90 && maxBand > 25 && maxBand < 35,
                "g1 G geo absolute bank 20 on a design-roll-30 piece: authored bank ~30 deg off the tilted samples, lane 2 only",
                String.format("conflict %b drawn %.3f maxBand %.2f deg", conf, drawn / (double) n, maxBand));
        }
    }

    /** tiltDeviates truth-table row. */
    static boolean tt(LaneSection ls, boolean l0, boolean l1, boolean l2) {
        boolean ok = LaneSection.tiltDeviates(ls, 0) == l0
            && LaneSection.tiltDeviates(ls, 1) == l1
            && LaneSection.tiltDeviates(ls, 2) == l2;
        if (!ok) System.out.println("   truth-table miss on " + ls.getClass().getSimpleName()
            + " rollGlobal " + ls.rollGlobal + " ypGlobal " + ls.yawPitchGlobal);
        return ok;
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) the scenario reproduces ----
        Coaster c = load();
        TrackCompiler.Result r = rebuild(c);
        double dA = drift(r, 1), dB = drift(r, 2);
        check(dB > EPS && dA < EPS,
            "a1 amplified badge on B only (the phantom's shape)",
            String.format("A %.3f B %.3f", dA, dB));

        // ---- (b) Set Design to Real on B STICKS bitwise ----
        Coaster c2 = load();
        rebuild(c2);
        check(c2.refreezeSpeed(1, 1, DT), "b1 refreeze applies", "");
        TrackCompiler.Result r2 = rebuild(c2);
        double vAfter = c2.pieces.get(1).sections.get(1).vDesign();
        double dB2 = drift(r2, 2);
        check(dB2 < 0.05,
            "b2 the resolved conflict STAYS resolved",
            String.format("B drift %.5f (was 0.357, snapped back)", dB2));
        TrackCompiler.Result r3 = rebuild(c2);
        check(Double.doubleToLongBits(vAfter) == Double.doubleToLongBits(
                c2.pieces.get(1).sections.get(1).vDesign()),
            "b3 the anchor is a BITWISE fixed point", "");

        // ---- (c) Convert Real to Design on B sticks too ----
        Coaster c3 = load();
        rebuild(c3);
        check(c3.makeGraphsReal(1, 1, DT, 0.1, 0.003),
            "c1 make-real applies", "");
        TrackCompiler.Result r4 = rebuild(c3);
        double dB3 = drift(r4, 2);
        check(dB3 < 0.1,
            "c2 make-real's resolution survives the rebuild",
            String.format("B drift %.5f", dB3));

        // ---- (d) refreezing the SOURCE clears the whole chain ----
        Coaster c4 = load();
        rebuild(c4);
        check(c4.refreezeSpeed(1, 0, DT), "d1 source refreeze applies", "");
        TrackCompiler.Result r5 = rebuild(c4);
        check(drift(r5, 1) < 0.05 && drift(r5, 2) < 0.05,
            "d2 whole chain clean from the source",
            String.format("A %.5f B %.5f", drift(r5, 1), drift(r5, 2)));

        // ---- (e-g) D248: the true-stroke split reference ----
        trueRefBlock();

        System.out.println();
        System.out.println(fail == 0
            ? "ALL CONFLICT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
