package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * The three-pass pipeline (Phase 1, SECTIONS-DESIGN §1) that replaced
 * the single integrator call:
 *
 *   Pass G (geometry, per section):  (entry pose, control curves,
 *       vDesign) -> centerline samples. For a force section this is
 *       EXACTLY the old pipeline — bake the lanes, RK4 at v0 = vDesign,
 *       energy referenced to the section's own entry height. Shape
 *       depends only on those inputs, NEVER on actual speed (§0).
 *   Pass D (dynamics, global):  assembled geometry + coaster v0 ->
 *       true speed per sample from energy vs the FIRST sample's height
 *       minus the document's FRICTION losses (Phase 3, v17.0 D133 —
 *       rolling + air, marched in travel order) and through the
 *       sections' SPEED CONTROLS (v18.0, D160 — the same drive/snap
 *       rules the design integrators apply).
 *   Pass A (analysis, global):  geometry + true speed -> resulting
 *       body rates (and, later, true forces).
 *
 * BIT-FOR-BIT GATE (D50): for today's document shape — one piece at
 * yaw 0, one force section with vDesign == v0 — compile() must produce
 * float-identical samples to the pre-Phase-1 call
 * {@code integrateTrack(bake(lanes), opts)}. Every step on that path
 * (identity quaternion rotate, pose seeding, pass-through assembly) is
 * arranged to add zero arithmetic; the headless gate asserts it.
 * Sections after the first re-base t/s and drop their duplicated entry
 * sample — that path has no legacy baseline to match.
 */
public final class TrackCompiler {

    private TrackCompiler() {}

    /** Global samples plus per-sample analysis, aligned by index.
     *  trueV is Pass D's TRUE speed (per-RUN energy, §5.1 round A);
     *  it equals Sample.v exactly wherever nothing has drifted.
     *  trueFelt {GvT, GlT} and trueRates {t, pitch, yaw, roll} are
     *  Pass A at the true speed — the §5 "true curves". */
    public record Result(List<FVDIntegrator.Sample> samples,
                         double[] trueV, double[][] rates,
                         double[][] trueFelt, double[][] trueRates,
                         List<Span> spans,
                         // the KINKS (v20.1, his 2026-10-21 note 7): the
                         // global sample indices k where an AUTHORED
                         // instantaneous orientation change sits between
                         // samples k − 1 and k off a strict seam — a
                         // section boundary into a section with absolute
                         // lanes (k = its span's i0) or a straight/curve
                         // element border into an absolute-bank element
                         // (k = the border sample + 1); ascending; Pass A
                         // differences one-sided around them and the
                         // editor draws carets there (see kinksOf)
                         int[] kinks) {
        public Result(List<FVDIntegrator.Sample> samples, double[] trueV,
                      double[][] rates, double[][] trueFelt,
                      double[][] trueRates, List<Span> spans) {
            this(samples, trueV, rates, trueFelt, trueRates, spans, new int[0]);
        }
    }

    /** One section's slice of the assembled ride (v11.0): sample index
     *  range [i0..i1] plus its global time/arc-length extent, plus the
     *  section's EXACT exit pose (v12.1 — the chain's own quaternion,
     *  not a frame round-trip: splitting a piece at this boundary
     *  copies it into the new piece's baseOrient, which is what makes
     *  splits bitwise at ANY boundary). exitPsi/exitCanon (v18.27,
     *  D199) are the CANONICAL chain state at the same boundary —
     *  splitPiece stores them on the new piece so a split generates
     *  with bitwise the parent chain's arithmetic; exitCanon is null
     *  where the chain state doesn't equal the world pose (tilted /
     *  follow-tilted pieces, D100 corner). The graph
     *  editor lays out DISTANCE-domain strips from these (a
     *  straight/curve section's DURATION exists only after compilation)
     *  and maps element arc lengths onto the shared time axis. Order
     *  matches the flattened piece/section iteration. */
    public record Span(int i0, int i1, double t0, double t1,
                       double s0, double sLen, TrackPose exit,
                       double exitPsi, Quat exitCanon, Quat exitTilt,
                       List<ScIntegrator.Bound> scBounds) {
        public Span(int i0, int i1, double t0, double t1,
                    double s0, double sLen, TrackPose exit) {
            this(i0, i1, t0, t1, s0, sLen, exit, 0, null, Quat.IDENTITY, null);
        }
        public Span(int i0, int i1, double t0, double t1,
                    double s0, double sLen, TrackPose exit,
                    double exitPsi, Quat exitCanon) {
            this(i0, i1, t0, t1, s0, sLen, exit, exitPsi, exitCanon,
                Quat.IDENTITY, null);
        }
        public Span(int i0, int i1, double t0, double t1,
                    double s0, double sLen, TrackPose exit,
                    double exitPsi, Quat exitCanon, Quat exitTilt) {
            this(i0, i1, t0, t1, s0, sLen, exit, exitPsi, exitCanon,
                exitTilt, null);
        }
    }

    /** One section's geometry + the pose it hands the next section;
     *  scBounds = a straight/curve section's per-border records (v20.0,
     *  D259 §1.4.3 — the ONE per-border record the Span carries), null
     *  for every other kind. */
    public record Centerline(List<FVDIntegrator.Sample> samples, TrackPose exit,
                             List<ScIntegrator.Bound> scBounds) {
        public Centerline(List<FVDIntegrator.Sample> samples, TrackPose exit) {
            this(samples, exit, null);
        }
    }

    // -----------------------------------------------------------------
    // CANONICAL-FRAME GENERATION (v18.27, D199): every non-bridge
    // section integrates at position ZERO with its entry HEADING
    // stripped (only the gravity-relevant pitch/roll of the entry
    // remain — rotation about world Y and translation commute with
    // gravity exactly), and the result is placed by one rigid
    // heading-rotation + translation. The chain's heading accumulates
    // ADDITIVELY (psi + the local exit's own azimuth) and the canonical
    // orientation is NEVER re-extracted from a composed world
    // orientation — that is what makes placement moves (x/y/z, piece
    // yaw) BITWISE-rigid: the generation arithmetic literally does not
    // contain the placement, so even a chaotic stall knot re-renders
    // identically and only its placement moves.
    // -----------------------------------------------------------------

    /** The chain's placement state: world position + heading (deg) +
     *  the heading-stripped canonical orientation the NEXT section
     *  generates at. */
    record CanonPose(Vec3 pos, double psiDeg, Quat canon) {}

    /** Heading (deg) of an orientation's T over the ground —
     *  atan2(−T.z, T.x), the decomposeEntry convention. Deterministic
     *  for every input incl. vertical T (atan2 of the signed-zero
     *  projections); any exact value works here — canon keeps the
     *  full orientation and place() re-applies psi. */
    static double headingDeg(Quat q) {
        Vec3 t = q.T();
        return Math.toDegrees(Math.atan2(-t.z(), t.x()));
    }

    /** Decompose a WORLD pose into a canonical state. Used at chain
     *  re-anchors (bridge exits) and for one-shot external passG
     *  calls — the piece-entry path uses pieceCanon instead, whose
     *  bits don't move under placement edits. */
    static CanonPose canonOf(TrackPose world) {
        double psi = headingDeg(world.orient());
        return new CanonPose(world.pos(), psi,
            Quat.aboutY(-psi).mul(world.orient()));
    }

    /** A piece's entry canonical state: from the STORED pair when a
     *  split recorded one (bitwise the parent chain, D199), else by
     *  decomposing baseOrient — and the yaw DELTA adds to psi
     *  ADDITIVELY either way, so the yaw spinner never changes the
     *  generation arithmetic. */
    static CanonPose pieceCanon(TrackPiece piece, Vec3 pos) {   // package-private since D247 (Coaster reads it)
        if (piece.canonOrient != null) {
            return new CanonPose(pos, piece.canonPsiDeg + piece.yawDeg,
                piece.canonOrient);
        }
        double psiB = headingDeg(piece.baseOrient);
        return new CanonPose(pos, psiB + piece.yawDeg,
            Quat.aboutY(-psiB).mul(piece.baseOrient));
    }

    /** A piece's canonical entry with its DESIGN tilt composed IN
     *  CANONICAL SPACE on top of pieceCanon (v19.0, D221/D222) — the
     *  same axis math as designPose() rotated by −psi (the
     *  composition is heading-equivariant, so the world pose is
     *  identical in exact arithmetic). Keeping psi additive is the
     *  point: yaw and position moves of a design-tilted piece stay
     *  bitwise-rigid (D199) while design pitch/roll edits re-solve
     *  the generation — which is what the unlocked shape MEANS.
     *  Zero design deltas return pieceCanon's exact object state
     *  (bitwise-historic). Package-private since D247 (the follower
     *  branch and Coaster's seam ops share it). */
    static CanonPose designCanon(TrackPiece piece, Vec3 pos) {
        CanonPose cp = pieceCanon(piece, pos);
        if (piece.designPitchDeg == 0 && piece.designRollDeg == 0) {
            return cp;
        }
        Quat q = cp.canon();
        if (piece.designPitchDeg != 0) {
            Vec3 t = q.T();
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                q = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(piece.designPitchDeg)).mul(q);
            }
        }
        if (piece.designRollDeg != 0) {
            q = Quat.aboutAxis(q.T(),
                Math.toRadians(piece.designRollDeg)).mul(q);
        }
        return new CanonPose(pos, cp.psiDeg(), q);
    }

    /** The rigid CONFLICT tilt on top of the design generation
     *  (D222): design == (0,0) takes tilt()'s historic arithmetic
     *  bitwise; design == true short-circuits to the IDENTITY
     *  object (pure link); the mixed case measures the exact
     *  design→true rotation. */
    private static Quat conflictTilt(TrackPiece piece) {
        if (piece.designPitchDeg == 0 && piece.designRollDeg == 0) {
            return piece.tilt();
        }
        if (!piece.orientConflict()) return Quat.IDENTITY;
        return piece.entryPose().orient()
            .mul(piece.designPose().orient().conjugate());
    }

    /** Rotation about world Y matching aboutY's action on vectors:
     *  x' = c·x + s·z, y' = y (BITWISE — the y column is untouched,
     *  which is what keeps heights and every .y-based gravity read
     *  placement-exact), z' = −s·x + c·z. */
    private static Vec3 rotY(Vec3 v, double c, double s) {
        return new Vec3(c * v.x() + s * v.z(), v.y(),
                        -s * v.x() + c * v.z());
    }

    /** Rigidly place a canonically-generated centerline into the
     *  world: rotate by psi about Y, translate to pos. Speeds, times,
     *  arcs, felt G and every sample's y-height ride through with
     *  their generation bits. */
    private static Centerline placeLine(Centerline local, CanonPose cp) {
        double rad = Math.toRadians(cp.psiDeg());
        double c = Math.cos(rad), s = Math.sin(rad);
        List<FVDIntegrator.Sample> out =
            new ArrayList<>(local.samples().size());
        for (FVDIntegrator.Sample sm : local.samples()) {
            out.add(new FVDIntegrator.Sample(sm.t(), sm.s(),
                cp.pos().add(rotY(sm.pos(), c, s)),
                rotY(sm.T(), c, s), rotY(sm.U(), c, s), rotY(sm.R(), c, s),
                sm.v(), sm.Gv(), sm.Gl()));
        }
        TrackPose e = local.exit();
        // the per-border records ride through (v20.1, D259 §1.4.3 —
        // Stage 1a dropped them here, so Span.scBounds was null for
        // every placed s/c section): the integrator's CANONICAL-frame
        // states, unplaced — their arcs, times and speeds are what the
        // element bake and the gates read
        return new Centerline(out, new TrackPose(
            cp.pos().add(rotY(e.pos(), c, s)),
            Quat.aboutY(cp.psiDeg()).mul(e.orient()), e.s0(), e.t0()),
            local.scBounds());
    }

    /** Advance the chain past a canonically-generated section: heading
     *  grows by the LOCAL exit's own azimuth (placement-independent
     *  bits), the canonical orientation re-strips from the LOCAL exit
     *  — never from the composed world orientation. */
    private static CanonPose chainNext(CanonPose cp, Centerline local,
                                       Vec3 worldExitPos) {
        double dpsi = headingDeg(local.exit().orient());
        return new CanonPose(worldExitPos, cp.psiDeg() + dpsi,
            Quat.aboutY(-dpsi).mul(local.exit().orient()));
    }

    public static Result compile(Coaster c, double dt) {
        Prof.count("compile");   // -Dfvd.timing=1 only (ScSeamGate h
                                 // counts the compiles a '+' costs)
        FVDIntegrator.Friction fric = c.fric();   // Phase 3 (v17.0)
        List<FVDIntegrator.Sample> all = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        // ENERGY HEIGHTS (v18.27, D199): a run-relative height per
        // sample, chained from the LOCAL generation bits wherever the
        // chain is plain (no tilt/shift/bridge), so Pass D's energy
        // arithmetic never contains the placement — a speed control
        // snapped EXACTLY onto its target is a knife-edge comparison
        // every sample, and world-height dust under a Y move flipped
        // its engage pattern (trueV moved 0.4 m/s at the user's
        // seam 12, flipping the reconcile's anchor). World-delta
        // fallback where the world genuinely is the reference.
        List<Double> hyAcc = new ArrayList<>();
        // Pass D's run table (§5.1, round A): a RUN starts at every
        // piece not preceded by a trailing bridge; rows are
        // {first sample index, entry speed = first section's vDesign}
        // (run 0's equals v0 through the Start-speed coupling).
        List<double[]> runs = new ArrayList<>();
        // CLEAN SPANS (batch 37 — see passD's clean variant): one row
        // per span, null = not clean; {entry sample, first sample,
        // last sample, the section's design entry speed, 1 = the
        // entry sample is a BRIDGE's last}
        List<double[]> cleanRows = new ArrayList<>();
        // per span: did it DROP its first sample onto the previous
        // span's last (a continuation)? - ctlSampleRows' seam rule
        List<Boolean> contRows = new ArrayList<>();
        boolean prevEndsBridge = false;
        double hNext = 0;   // energy height of the next section's entry
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece piece = c.pieces.get(p);
            // RIGID TILT placement (v15.2, D100): sections integrate
            // at the DESIGN orientation (genPose — gravity solves
            // there) and the piece's pitch/roll deltas rotate the
            // finished geometry rigidly about the entry point. tilt()
            // returns the IDENTITY object for untilted pieces, so the
            // historic path below is bitwise-untouched.
            // TWO-TIER TILT (v19.0, D221/D222): sections generate at
            // the DESIGN orientation — baseOrient ⊕ yaw ⊕ the design
            // pitch/roll deltas, composed in canonical space
            // (designCanon) — and the remaining design→true gap
            // applies as the rigid conflict tilt about the entry.
            // Zero design deltas keep the historic tilt() arithmetic
            // bitwise; design == true is the pure link (IDENTITY
            // object). orientMode routes EDITS only (D249) — the
            // compile reads nothing but the two stored tiers.
            Quat tilt = conflictTilt(piece);
            boolean designed = piece.designPitchDeg != 0
                || piece.designRollDeg != 0;
            TrackPose pose = tilt == Quat.IDENTITY
                ? piece.entryPose()
                : designed ? piece.designPose() : piece.genPose();
            Vec3 anchor = pose.pos();
            CanonPose cp = designed ? designCanon(piece, pose.pos())
                                    : pieceCanon(piece, pose.pos());
            int pieceStart = all.size();
            boolean bridgedIn = prevEndsBridge;
            // STRICT SEAM (v17.1, D135): a FOLLOWING piece's entry
            // sits on the previous piece's exit — position always,
            // orientation per its locks (Coaster.strictEntry). The
            // piece still GENERATES at its stored generation
            // orientation (shape frozen — the D100 rigidity) and the
            // whole difference applies as the rigid tilt below.
            // Ignored when bridged-in (the bridge IS the connection)
            // or first/orphaned (falls back to its stored placement).
            boolean followed = piece.followPrev && p > 0 && !bridgedIn
                && !spans.isEmpty() && !piece.sections.isEmpty()
                && !c.pieces.get(p - 1).sections.isEmpty();
            // strict-seam SMOOTHING (v17.2, D139): blend the junction
            // right after the follower's FIRST section lands; the
            // window-end position delta then translates the piece's
            // remaining sections (postShift). 0 = the pass never runs.
            boolean smoothSeam = followed && piece.sSmoothLen > 0.01;
            Vec3 postShift = null;
            int secIdx = 0;
            if (followed) {
                // (D222 walked back D221's linked-follower branch on
                // user spec — "there might be some weird edge cases":
                // followers ALWAYS take the D135 freeze — the REAL
                // entry is the follow contract, never the true tier.)
                // FOLLOWER DESIGN TIER (v19.18, D247): a follower's
                // DESIGN orientation is designPose() — baseOrient ⊕
                // yaw ⊕ the design pitch/roll deltas — composed in
                // canonical space (designCanon) exactly like a free
                // piece's, so an attached design-tilted piece keeps
                // generating the shape it had (D5: connectStrict
                // leaves the deltas alone) and the seam window's
                // Lock-Track-Shape-off edits re-solve through the
                // design tier (Euler-additive, the D222 invariance).
                // Zero design deltas take genPose/pieceCanon BITWISE
                // (the historic object state). The true tier stays
                // dead here: the follow contract IS the real frame.
                Span prevSpan = spans.get(spans.size() - 1);
                TrackPose eff = Coaster.strictEntry(prevSpan.exit(), piece);
                if (c.autoFollower(p)) {
                    // AUTOMATIC FOLLOWER (v20.1, D259 §3.5 A1 — D5): the
                    // seam the straight/curve invariant created, all
                    // three locks off, design tier zero. The piece
                    // GENERATES AT THE FOLLOW CONTRACT — eff IS the
                    // previous span's exit (strictEntry's exact
                    // all-unlocked frame) — exactly like an in-piece
                    // continuation: the SAME CanonPose chainNext handed
                    // the previous span (psi additive, canon = that
                    // span's exit canon — the D199 bits, so head x/y/z
                    // + yaw moves stay bitwise-rigid and the samples
                    // equal the in-piece twin's bit for bit, ScSeamGate
                    // a-c), and NO tilt (the IDENTITY object: the
                    // clean-span rule and tiltLine see "untilted" — an
                    // unsmoothed auto follower's design speed lands on
                    // its real seam speed bitwise, A4). The piece's
                    // x/y/z/yaw/baseOrient/canonical pair/design tier
                    // are NOT read — inert until a lock or a design
                    // delta turns it back into a D135/D247 follower
                    // (Coaster.freezeAutoFollower writes the contract
                    // into them first). Behind a TILTED upstream span (a
                    // conflict tilt, a locked follower's re-aim) the
                    // design chain's canon is not the world frame this
                    // seam continues: the world pose is decomposed
                    // instead (canonOf — D199 dust under a yaw move
                    // there, the documented tilted-piece limit).
                    // [SUPERSEDED in the run-3 review — see the branch
                    // below: the design chain continues and the tilt
                    // is inherited; canonOf only without a pair.]
                    anchor = eff.pos();
                    if (prevSpan.exitCanon() == null) {
                        // no chain pair to continue (never after a
                        // placed section — a belt): the world pose
                        tilt = Quat.IDENTITY;
                        pose = eff;
                        cp = canonOf(eff);
                    } else {
                        // the DESIGN chain continues EXACTLY (the
                        // in-piece twin's generation bits) — and behind
                        // a TILTED upstream span (a conflict tilt, a
                        // locked follower's re-aim) the follower
                        // INHERITS that tilt about its own entry (the
                        // run-3 review, closing the notes' open question
                        // 2): the in-piece twin is generated in the
                        // design chain and rigid-tilted about the PIECE
                        // entry; rotating the same design geometry
                        // about THIS entry by the same tilt lands on the
                        // identical world track, because the tilt's
                        // rotation center commutes with the chain's
                        // rigid placement (ScSeamGate c4 — ≤ 1e-9 m,
                        // frames / felt bitwise). The untilted upstream
                        // keeps the IDENTITY object (the clean-span rule
                        // + tiltLine see "untilted", bitwise); the tilted
                        // one carries the conflict on — ≠o shows on the
                        // follower like on the twin's sections
                        // (Coaster.seamConflictTilt's Span form reads
                        // the same object). `pose` is the DESIGN-space
                        // entry (aboutY(psi)·canon — placeLine's
                        // composition), what a trailing bridge capture
                        // tilts back into the world.
                        tilt = prevSpan.exitTilt();
                        cp = new CanonPose(eff.pos(), prevSpan.exitPsi(),
                            prevSpan.exitCanon());
                        pose = tilt == Quat.IDENTITY ? eff
                            : new TrackPose(eff.pos(),
                                Quat.aboutY(prevSpan.exitPsi())
                                    .mul(prevSpan.exitCanon()));
                    }
                } else {
                    Quat gen = designed ? piece.designPose().orient()
                                        : piece.genPose().orient();
                    // the seam's CONFLICT TILT: real (follow contract) vs
                    // design — Coaster.seamConflictTilt reads the same
                    // product for the ≠o predicate (a pure heading
                    // difference inside it is rigid, not a conflict)
                    Quat tq = eff.orient().mul(gen.conjugate());
                    tilt = Math.abs(tq.w()) > 1 - 1e-14
                        ? Quat.IDENTITY : tq;
                    pose = new TrackPose(eff.pos(), gen);
                    anchor = eff.pos();
                    // follower generation stays at the STORED design
                    // orientation (shape frozen); only the position comes
                    // from the upstream exit — placement-side, so upstream
                    // moves keep the follower's generation bits (D199)
                    cp = designed ? designCanon(piece, eff.pos())
                                  : pieceCanon(piece, eff.pos());
                }
            }
            if (!piece.sections.isEmpty() && !bridgedIn && !followed) {
                runs.add(new double[]{ pieceStart,
                    piece.sections.get(0).vDesign() });
                hNext = 0;   // each run's energy references its own entry
            }
            if (!piece.sections.isEmpty()) {
                prevEndsBridge = piece.sections
                    .get(piece.sections.size() - 1) instanceof BridgeSection;
            }
            // a piece's first sample is a genuinely new point for a
            // DETACHED piece — but a BRIDGED piece's entry coincides
            // exactly with the bridge's exit (the seam contract), so
            // keeping it duplicated the sample: zero-advance tick at
            // every bridge seam (POV froze for a frame, bodyRates
            // differenced across dt = 0; v13.10 D84). A strict-seam
            // FOLLOWER's entry coincides the same way (v17.1).
            boolean continuation = bridgedIn || followed;
            // PIECE-RELATIVE absolute heading (v19.9, D238 — user
            // spec: "make yaw a global value like the pitch and
            // roll"): an ABSOLUTE heading lane's zero is the PIECE's
            // entry heading, not the section's. Each section still
            // generates heading-stripped (D199), so geoLane shifts
            // the lane by the section entry's piece-relative heading
            // — the chain-psi DIFFERENCE, pure generation bits:
            // placement x/y/z + yaw moves stay bitwise-rigid, and a
            // piece's FIRST section keeps offset 0 (bitwise the
            // D237 behavior).
            double psiPieceStart = cp.psiDeg();
            for (Section section : piece.sections) {
                if (section instanceof BridgeSection bs) {
                    // LIVE-FOLLOW (v13.1, D73): re-capture the solve
                    // inputs every compile — poses, seam curvature AND
                    // its derivative, roll rates, the target's entry
                    // speed (v13.2: the rate graphs stay continuous in
                    // value and slope across both seams). No feedback
                    // loop: a bridge's geometry never feeds its own
                    // endpoints. With the target piece gone the stored
                    // solve stands (frozen fallback, badge "!").
                    // The chain pose is DESIGN-space under a tilt — the
                    // bridge lives in the world and captures the REAL
                    // (tilted) seam (v15.2).
                    TrackPose worldPose = tilt == Quat.IDENTITY ? pose
                        : tiltPose(pose, tilt, anchor);
                    // the LAST capture's gap, before this compile
                    // overwrites the poses (v17.14, D155 — see the
                    // manual-mode tangent rescale below)
                    double preGap = bs.solvedTarget.pos()
                        .sub(bs.solvedEntry.pos()).length();
                    // a smoothed strict seam upstream in THIS piece
                    // translated the chain (v17.2): the bridge starts
                    // where the track really ends
                    bs.solvedEntry = new TrackPose(postShift == null
                        ? worldPose.pos() : worldPose.pos().add(postShift),
                        worldPose.orient());
                    if (continuation && all.size() - pieceStart >= 9) {
                        int e = all.size() - 1;
                        bs.vDesign = all.get(e).v();   // live entry speed
                        Vec3[] kj = seamDerivs(all, e, -1);
                        bs.accA = kj[0];
                        bs.jerkA = kj[1];
                        bs.rollA = seamRollRate(all, e, -1);
                        bs.rollAccA = seamRollAcc(all, e, -1);
                    } else {
                        bs.accA = Vec3.ZERO;
                        bs.jerkA = Vec3.ZERO;
                        bs.rollA = 0;
                        bs.rollAccA = 0;
                    }
                    if (p + 1 < c.pieces.size()) {
                        TrackPiece next = c.pieces.get(p + 1);
                        captureTargetHead(bs, next, dt, fric);
                        // MANUAL-mode tangent rescale (v17.14, D155):
                        // in "No optimization" the Length spinner is
                        // disabled and the septic's lenA/lenB have no
                        // user surface, so hand-dragging the target
                        // left them STALE — 11 m tangents over a 27 m
                        // gap on the user's file, the exact D75
                        // short-tangent disease (peak rates 331/12/179
                        // °/s by thirds). Scale both with the gap so a
                        // moved target keeps the sweep's PROPORTION
                        // (D75's full-gap default heals as soon as the
                        // gap changes; partial-convert cut shares keep
                        // their ratio). The 1e-6 band keeps unmoved
                        // captures and text-round-trip noise bitwise
                        // (the canonical save→load→save gate).
                        if (bs.optMode == BridgeSection.OPT_NONE
                                && preGap > 1e-9) {
                            double ratio = bs.solvedTarget.pos()
                                .sub(bs.solvedEntry.pos()).length()
                                / preGap;
                            if (Math.abs(ratio - 1) > 1e-6) {
                                bs.lenA *= ratio;
                                bs.lenB *= ratio;
                            }
                        }
                    }
                }
                Centerline line;
                Centerline local = null;
                if (section instanceof BridgeSection) {
                    // bridges are world-anchored (their endpoints ARE
                    // world data) and never tilt; the chain re-anchors
                    // from the world exit — placement-bitwise rigidity
                    // deliberately ends at a bridge (it re-solves to
                    // its endpoints by design, D199)
                    line = passGRaw(section, pose, dt, fric);
                    pose = line.exit();
                    cp = canonOf(pose);
                } else {
                    // CANONICAL generation (D199): integrate at the
                    // heading-stripped entry from position zero, place
                    // rigidly; chain heading additively in design
                    // space; the world gets the rigidly tilted copy
                    // (identity = no-op)
                    local = passGRaw(section,
                        new TrackPose(Vec3.ZERO, cp.canon(),
                            pose.s0(), pose.t0()), dt, fric,
                        psiPieceStart - cp.psiDeg());   // RL (D238)
                    line = placeLine(local, cp);
                    pose = line.exit();
                    cp = chainNext(cp, local, pose.pos());
                    if (tilt != Quat.IDENTITY) {
                        line = tiltLine(line, tilt, anchor);
                    }
                    // a smoothed seam's window-end delta translates
                    // the rest of the piece (v17.2 — the design-space
                    // chain stays untouched; only the world output
                    // shifts). A bridge's entry shifted above already.
                    if (postShift != null) {
                        line = shiftLine(line, postShift);
                    }
                }
                int i0 = all.size();
                double t0 = all.isEmpty() ? 0 : all.get(all.size() - 1).t();
                double s0 = all.isEmpty() ? 0 : all.get(all.size() - 1).s();
                boolean contAtAppend = continuation;
                appendRebased(all, line.samples(), continuation);
                FVDIntegrator.Sample end = all.get(all.size() - 1);
                // ENERGY HEIGHTS (D199): plain sections chain from the
                // LOCAL generation bits (placement-free); tilted /
                // shifted / bridge stretches fall back to world deltas
                // anchored on the chain (the world IS their reference)
                double hEntry = hNext;
                boolean worldH = local == null || tilt != Quat.IDENTITY
                    || postShift != null;
                if (worldH) {
                    double yRef, hRef;
                    if (contAtAppend && i0 > 0) {
                        yRef = all.get(i0 - 1).pos().y();
                        hRef = hyAcc.get(i0 - 1);
                    } else {
                        yRef = all.get(i0).pos().y();
                        hRef = hEntry;
                    }
                    for (int i = i0; i < all.size(); i++) {
                        hyAcc.add(hRef + (all.get(i).pos().y() - yRef));
                    }
                } else {
                    int off = contAtAppend ? 1 : 0;   // dropped seam sample
                    for (int i = i0; i < all.size(); i++) {
                        hyAcc.add(hEntry
                            + local.samples().get(i - i0 + off).pos().y());
                    }
                }
                hNext = hyAcc.get(all.size() - 1);
                // the span's canonical pair is the DESIGN chain; the
                // world exit is that chain rigidly tilted (identity
                // for plain pieces). Both ride the span since v19.1
                // (D224): a split of a TILTED piece / re-aimed
                // follower now hands the new piece the design chain
                // (bitwise generation) PLUS the tilt as its own
                // conflict deltas, so nothing moves — the D100
                // "splits re-solve" corner is closed.
                spans.add(new Span(i0, all.size() - 1, t0, end.t(),
                    s0, end.s() - s0, line.exit(),
                    cp.psiDeg(), cp.canon(), tilt, line.scBounds()));
                // a CLEAN span's samples are the design solve's own
                // geometry: generated here (never a bridge), placed
                // rigidly WITHOUT a tilt or a smoothing shift (either
                // changes the heights the train really climbs), and
                // shedding friction in travel order (a legacy reversed
                // section's design profile sheds it backward)
                boolean cleanSpan = local != null && tilt == Quat.IDENTITY
                    && postShift == null && !section.reversed()
                    && i0 <= all.size() - 1;
                contRows.add(contAtAppend && i0 > 0);
                cleanRows.add(cleanSpan ? new double[]{
                    contAtAppend && i0 > 0 ? i0 - 1 : i0, i0,
                    all.size() - 1, section.vDesign(),
                    bridgedIn && secIdx == 0 && contAtAppend ? 1 : 0 }
                    : null);
                continuation = true;
                if (smoothSeam && secIdx == 0
                        && !(section instanceof BridgeSection)) {
                    // the blend REPLACES the samples it rewrites
                    // (records): remember the upstream span's objects
                    // and heights to find where its window begins
                    int nsp = spans.size();
                    Span up = nsp >= 2 ? spans.get(nsp - 2) : null;
                    FVDIntegrator.Sample[] was = null;
                    double[] wasH = null;
                    if (up != null && up.i1() >= up.i0()) {
                        int n = up.i1() - up.i0() + 1;
                        was = new FVDIntegrator.Sample[n];
                        wasH = new double[n];
                        for (int k = 0; k < n; k++) {
                            was[k] = all.get(up.i0() + k);
                            wasH[k] = hyAcc.get(up.i0() + k);
                        }
                    }
                    postShift = smoothStrictSeam(all, spans,
                        piece.sSmoothLen, hyAcc);
                    if (postShift != null) {
                        // the blend re-stamped heights up to the span end
                        hNext = hyAcc.get(all.size() - 1);
                        // CLEAN ROWS (review round 2, C1): a window too
                        // short to blend rewrote nothing — both rows
                        // stand. A blend leaves the follower PIECE from
                        // the window on to the march (this span is
                        // blended, the remainder rides postShift — no
                        // later row of the piece is clean, and each
                        // downstream piece runs the march until an
                        // exact entry; dust-level, continuous), and the
                        // upstream span the design solve's own UP TO
                        // its window: the row ends on the last sample
                        // the blend left alone
                        int nc = cleanRows.size();
                        cleanRows.set(nc - 1, null);
                        double[] upRow = nc >= 2 ? cleanRows.get(nc - 2)
                            : null;
                        if (upRow != null && was != null) {
                            int first = up.i1() + 1;
                            for (int k = 0; k < was.length; k++) {
                                int i = up.i0() + k;
                                if (all.get(i) != was[k]
                                        || Double.doubleToLongBits(
                                            hyAcc.get(i))
                                           != Double.doubleToLongBits(
                                               wasH[k])) {
                                    first = i;
                                    break;
                                }
                            }
                            if (first - 1 >= (int) upRow[1]) {
                                upRow[2] = Math.min(upRow[2], first - 1);
                            } else {
                                cleanRows.set(nc - 2, null);
                            }
                        } else if (nc >= 2) {
                            cleanRows.set(nc - 2, null);
                        }
                    }
                }
                secIdx++;
            }
        }
        double[] hy = new double[hyAcc.size()];
        for (int i = 0; i < hy.length; i++) hy[i] = hyAcc.get(i);
        List<double[]> clean = new ArrayList<>();
        for (double[] row : cleanRows) if (row != null) clean.add(row);
        double[] trueV = passD(all, runs, c.fricRoll, c.fricAir,
            ctlSampleRows(all, spans, c, contRows), hy, clean);
        // rates difference per SECTION SPAN since v18.35 (D207): the
        // ±1 central windows never straddle a seam, so seam-adjacent
        // rates are one-sided and CLEAN — the graphs draw right up to
        // the boundary instead of trimming a smear band
        // + the authored KINKS inside spans (his 2026-10-21 note 7): an
        // absolute-bank element border snaps the bank between two
        // samples of ONE span — the central difference across it was
        // the exploding roll rate; one-sided around it like a seam
        int[] kinks = kinksOf(c, all, spans);
        java.util.TreeSet<Integer> starts = new java.util.TreeSet<>();
        for (Span sp : spans) starts.add(sp.i0());
        for (int k : kinks) starts.add(k);
        int[] segStarts = new int[starts.size()];
        int si = 0;
        for (int k : starts) segStarts[si++] = k;
        double[][] rates = passA(all, segStarts);
        return new Result(all, trueV, rates,
            trueFelt(all, trueV), trueRates(all, rates, trueV), spans, kinks);
    }

    /** An authored instantaneous orientation change counts as a KINK
     *  from this frame jump between two consecutive samples (deg; the
     *  editor's CARET_KINK_MIN) — a continuous rate of 150 °/s turns
     *  1.5° per 0.01 s step, under it. */
    public static final double KINK_MIN_DEG = 2;

    /** THE KINKS of a compile (v20.1, his 2026-10-21 note 7): the
     *  global sample indices k where an AUTHORED instantaneous
     *  orientation change sits between samples k − 1 and k, OFF the
     *  strict seams (those wear their own carets) and the bridges:
     *  <ul><li>a section at k ≥ 1 of its piece (a plain boundary) whose
     *  lanes are ABSOLUTE — bank (rollAbsolute + rollGlobal) or
     *  elevation / heading (ypAbsolute + yawPitchGlobal): the
     *  projection snaps the frame onto the lane's start over the
     *  first step, so the jump sits between the boundary sample
     *  (the previous span's last) and this span's first — k = i0;</li>
     *  <li>a straight/curve element border INTO an element whose roll
     *  lane is ABSOLUTE: the first leg into it snaps the bank onto
     *  the lane — k = the border sample + 1 (ScIntegrator.Bound's
     *  arc locates the border sample).</li></ul>
     *  A candidate is a kink when the FRAME JUMP between the two
     *  samples (TrackCompiler.axisAngle) reaches KINK_MIN_DEG — a
     *  lane that happens to start at the incoming bank snaps nothing
     *  and gets no caret. Ascending, unique. */
    static int[] kinksOf(Coaster c, List<FVDIntegrator.Sample> all,
                         List<Span> spans) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        double minRad = Math.toRadians(KINK_MIN_DEG);
        int flat = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece piece = c.pieces.get(p);
            for (int k = 0; k < piece.sections.size(); k++, flat++) {
                if (flat >= spans.size()) break;
                Span sp = spans.get(flat);
                Section sec = piece.sections.get(k);
                if (sec instanceof LaneSection ls && k >= 1 && !ls.reversed()
                        && ((ls.rollAbsolute && ls.rollGlobal)
                            || (ls.ypAbsolute && ls.yawPitchGlobal))) {
                    int i0 = sp.i0();
                    if (i0 >= 1 && i0 < all.size()
                            && jumpRad(all.get(i0 - 1), all.get(i0)) >= minRad) {
                        out.add(i0);
                    }
                } else if (sec instanceof StraightCurveSection sc
                        && !sc.reversed && sp.scBounds() != null) {
                    List<ScIntegrator.Bound> bd = sp.scBounds();
                    for (int j = 1; j < bd.size() - 1 && j < sc.elements.size(); j++) {
                        if (!sc.elements.get(j).roll.rollAbsolute()) continue;
                        ScIntegrator.Bound b = bd.get(j);
                        if (!b.landed()) continue;
                        int kb = sampleAtArc(all, sp, sp.s0() + b.s());
                        if (kb < 0 || kb + 1 > sp.i1()) continue;
                        if (jumpRad(all.get(kb), all.get(kb + 1)) >= minRad) {
                            out.add(kb + 1);
                        }
                    }
                }
            }
        }
        int[] arr = new int[out.size()];
        int i = 0;
        for (int k : out) arr[i++] = k;
        return arr;
    }

    /** The rotation angle (rad) between two samples' frames. */
    static double jumpRad(FVDIntegrator.Sample a, FVDIntegrator.Sample b) {
        Quat qa = Quat.fromFrame(a.T(), a.U()), qb = Quat.fromFrame(b.T(), b.U());
        return axisAngle(qb.mul(qa.conjugate())).length();
    }

    /** The span's sample at global arc s (a landed border sits ON a
     *  sample — the nearest within 1e-6 m), else −1. */
    static int sampleAtArc(List<FVDIntegrator.Sample> all, Span sp, double s) {
        int lo = Math.max(0, sp.i0()), hi = Math.min(sp.i1(), all.size() - 1);
        if (lo > hi) return -1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (all.get(mid).s() <= s) lo = mid; else hi = mid;
        }
        int at = Math.abs(all.get(lo).s() - s) <= Math.abs(all.get(hi).s() - s) ? lo : hi;
        return Math.abs(all.get(at).s() - s) <= 1e-6 ? at : -1;
    }

    /** SPEED CONTROLS resolved per global sample (v18.0, D160): row i
     *  = {type, target, accel, min, max} where sample i sits inside a
     *  control (complex graphs evaluated at the sample's own local x),
     *  null elsewhere. Null outright when no section carries any —
     *  passD's bitwise-off gate. The DESIGN pass placed each control's
     *  samples; the REAL march applies it over the same track region
     *  (the hardware sits where the design put it). */
    static double[][] ctlSampleRows(List<FVDIntegrator.Sample> all,
                                    List<Span> spans, Coaster c) {
        return ctlSampleRows(all, spans, c, null);
    }

    /** THE SEAM STEP (v19.26, D255): rows[i] governs the real march's
     *  step i -> i+1, and a CONTINUATION span's first own sample sits
     *  one step INSIDE its section - the sample it dropped IS the
     *  previous span's last. The step that starts on the seam
     *  therefore runs over the NEW section's x = 0..ds, but its row
     *  was resolved from the PREVIOUS section at its end coordinate
     *  (none: a control's interval is half-open). A control starting
     *  exactly at a section start - every promoted x = 0 diamond -
     *  lost its first step in the real march while the design solve
     *  drove it: on the user's 62-degree lift the real speed fell
     *  0.09 m/s behind the design in that one step and carried the
     *  phantom split down the whole ride. cont = per span, true where
     *  the span dropped its first sample (compile's contAtAppend);
     *  the seam sample then takes the NEW section's row at x = 0, or
     *  none. null = the historic rows. */
    static double[][] ctlSampleRows(List<FVDIntegrator.Sample> all,
                                    List<Span> spans, Coaster c,
                                    List<Boolean> cont) {
        double[][] rows = ctlSampleRowsOwn(all, spans, c);
        if (cont == null) return rows;
        int flat = 0;
        for (TrackPiece piece : c.pieces) {
            for (Section sec : piece.sections) {
                int idx = flat++;
                if (idx >= spans.size() || idx >= cont.size()
                        || !cont.get(idx)) continue;
                int seam = spans.get(idx).i0() - 1;
                if (seam < 0 || seam >= all.size()) continue;
                double[] row = null;
                if (sec.speedControllable()) {
                    for (SpeedControl sc : sec.speedControls()) {
                        if (0 >= sc.x0 && 0 < sc.x0 + sc.len) {
                            row = SpeedControl.rowFor(sc, 0 - sc.x0);
                            break;
                        }
                    }
                }
                if (rows == null) {
                    if (row == null) continue;
                    rows = new double[all.size()][];
                }
                rows[seam] = row;
            }
        }
        return rows;
    }

    /** Each span's OWN samples resolved against its section's
     *  controls (the historic rows). */
    private static double[][] ctlSampleRowsOwn(
            List<FVDIntegrator.Sample> all, List<Span> spans, Coaster c) {
        double[][] rows = null;
        int flat = 0;
        for (TrackPiece piece : c.pieces) {
            for (Section sec : piece.sections) {
                int idx = flat++;
                if (!sec.speedControllable()
                        || sec.speedControls().isEmpty()) continue;
                if (idx >= spans.size()) continue;
                Span sp = spans.get(idx);
                boolean distAxis = sec.domain() == Section.Domain.DISTANCE;
                if (rows == null) rows = new double[all.size()][];
                for (int i = sp.i0(); i <= sp.i1() && i < all.size(); i++) {
                    double x = distAxis
                        ? all.get(i).s() - sp.s0()
                        : all.get(i).t() - sp.t0();
                    for (SpeedControl sc : sec.speedControls()) {
                        if (x >= sc.x0 && x < sc.x0 + sc.len) {
                            rows[i] = SpeedControl.rowFor(sc, x - sc.x0);
                            break;
                        }
                    }
                }
            }
        }
        return rows;
    }

    /** {curvature dT/ds, its derivative d²T/ds²} at sample `at`, from
     *  a one-sided quadratic fit of T(s) through samples at, at+4·dir,
     *  at+8·dir (2nd-order accurate at the seam itself; dir −1 = the
     *  tail of a chain, +1 = the head of a pre-integrated section).
     *  BOTH are projected ⊥ T: the raw second derivative carries a
     *  −|κ|²·T parametrization term that, imposed at tangent-length³
     *  scale, made the septic RING (probed: a tame seam whipped to a
     *  reverse-curvature spike within 2 m); only the perpendicular
     *  part is geometry — rate-graph slope — and that is what the
     *  seam needs. */
    static Vec3[] seamDerivs(List<FVDIntegrator.Sample> s, int at, int dir) {
        FVDIntegrator.Sample f0 = s.get(at);
        FVDIntegrator.Sample f1 = s.get(at + 4 * dir);
        FVDIntegrator.Sample f2 = s.get(at + 8 * dir);
        double h1 = f1.s() - f0.s(), h2 = f2.s() - f0.s();
        double den = h1 * h2 * (h2 - h1);
        if (Math.abs(den) < 1e-12 || Math.abs(h1) < 1e-9) {
            return new Vec3[]{ Vec3.ZERO, Vec3.ZERO };
        }
        Vec3 d1 = f1.T().sub(f0.T()), d2 = f2.T().sub(f0.T());
        Vec3 b = d1.scale(h2 * h2).sub(d2.scale(h1 * h1)).scale(1 / den);
        Vec3 cc = d2.scale(h1).sub(d1.scale(h2)).scale(1 / den);
        Vec3 k = b.sub(f0.T().scale(b.dot(f0.T())));
        Vec3 j = cc.scale(2);
        j = j.sub(f0.T().scale(j.dot(f0.T())));
        return new Vec3[]{ k, j };
    }

    /** Roll rate (rad/s, roll-lane sign) at sample `at`, measured from
     *  its neighbor in direction dir: transport the earlier U across
     *  the tangent change, the residual angle about T over dt is roll. */
    static double seamRollRate(List<FVDIntegrator.Sample> s, int at, int dir) {
        return rollRateBetween(s, at, at + dir);
    }

    /** Roll rate (rad/s) measured between two samples: transport i's U
     *  across the tangent change to j, the residual angle about T over
     *  their time gap is roll. */
    private static double rollRateBetween(List<FVDIntegrator.Sample> s,
                                          int i, int j) {
        FVDIntegrator.Sample a = s.get(Math.min(i, j));
        FVDIntegrator.Sample b = s.get(Math.max(i, j));
        double dt = b.t() - a.t();
        if (dt < 1e-9) return 0;
        Vec3 axis = a.T().cross(b.T());
        double sin = axis.length();
        double cos = Math.max(-1, Math.min(1, a.T().dot(b.T())));
        Vec3 u = sin > 1e-12
            ? a.U().rotate(axis.scale(1 / sin), Math.atan2(sin, cos)) : a.U();
        u = u.sub(b.T().scale(u.dot(b.T()))).normalize();
        return Math.atan2(u.cross(b.U()).dot(b.T()), u.dot(b.U())) / dt;
    }

    /** d(roll rate)/dt (rad/s², chain time) at sample `at`, one-sided
     *  toward dir (v13.4 — the bridge twist matches the roll-rate
     *  graph's SLOPE at the seams): two short roll-rate reads centered
     *  ~1 and ~3 samples inside, differenced at their midpoints. The
     *  signed midpoint times make the derivative direction-correct for
     *  both the chain tail (dir −1) and a section head (dir +1). */
    static double seamRollAcc(List<FVDIntegrator.Sample> s, int at, int dir) {
        double rNear = rollRateBetween(s, at, at + 2 * dir);
        double rFar = rollRateBetween(s, at + 2 * dir, at + 4 * dir);
        double dt = s.get(at + 3 * dir).t() - s.get(at + dir).t();
        return Math.abs(dt) < 1e-9 ? 0 : (rFar - rNear) / dt;
    }

    /** Pass G for one section at its DESIGN speed. Force sections are
     *  the old integrator run, seeded from the entry pose; geo sections
     *  (Phase 2) integrate their authored body rates directly. REVERSED
     *  sections (Phase 2, D67) generate the authored shape in the
     *  CANONICAL frame and hand it to reverseAndPlace. Loss-free
     *  delegate (probes/gates); compile() threads the document
     *  friction. Since v18.27 (D199) the public entry canonicalizes:
     *  generation runs at position zero with the heading stripped and
     *  the result places rigidly — one-shot callers get the same
     *  placement-equivariant geometry the compile chain produces
     *  (compile() itself chains CanonPose additively and calls
     *  passGRaw directly). */
    public static Centerline passG(Section section, TrackPose entry, double dt) {
        return passG(section, entry, dt, FVDIntegrator.Friction.NONE);
    }

    public static Centerline passG(Section section, TrackPose entry, double dt,
                                   FVDIntegrator.Friction fric) {
        if (section instanceof BridgeSection) {
            return passGRaw(section, entry, dt, fric);
        }
        CanonPose cp = canonOf(entry);
        Centerline local = passGRaw(section,
            new TrackPose(Vec3.ZERO, cp.canon(), entry.s0(), entry.t0()),
            dt, fric);
        return placeLine(local, cp);
    }

    static Centerline passGRaw(Section section, TrackPose entry, double dt,
                               FVDIntegrator.Friction fric) {
        return passGRaw(section, entry, dt, fric, 0);
    }

    /** ypHeadDeg = the section entry's PIECE-relative heading (deg,
     *  reported-left; v19.9, D238) — the constant an ABSOLUTE
     *  heading lane shifts by so its zero is the PIECE entry. 0 for
     *  piece firsts, one-shot callers and every non-abs section. */
    static Centerline passGRaw(Section section, TrackPose entry, double dt,
                               FVDIntegrator.Friction fric,
                               double ypHeadDeg) {
        if (section instanceof ForceSection fs) {
            if (fs.influenced()) return influencedLane(fs, entry, dt, false, fric);
            return forceLane(fs, entry, dt, fric);
        }
        if (section instanceof GeoSection gs) {
            if (gs.influenced()) {
                return influencedLane(gs, entry, dt, true, fric, ypHeadDeg);
            }
            return geoLane(gs, entry, dt, fric, null, ypHeadDeg);
        }
        if (section instanceof StraightCurveSection sc) {
            // straight/curve (v11.0): same quaternion-native pattern as
            // geo; reversal generates canonically and yaw-places (D67)
            if (sc.reversed) {
                ScIntegrator.Result rr = ScIntegrator.integrate(sc, dt,
                    sc.vDesign, new Vec3(0, entry.pos().y(), 0),
                    Quat.IDENTITY, fric);
                return reverseAndPlace(rr.samples(), entry);
            }
            ScIntegrator.Result r = ScIntegrator.integrate(
                sc, dt, sc.vDesign, entry.pos(), entry.orient(), fric,
                SpeedControl.Ctl.of(sc.speedCtls));
            FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
            TrackPose exit = new TrackPose(last.pos(), r.qEnd(),
                entry.s0() + last.s(), entry.t0() + last.t());
            // the per-border records ride the Span (D259 §1.4.3)
            return new Centerline(r.samples(), exit, r.bounds());
        }
        if (section instanceof BridgeSection bs) {
            // bridge (v13.0, D72): FROZEN world-anchored geometry — the
            // incoming entry pose is deliberately IGNORED (§4: a bridge
            // never chases its endpoints; the editor shows dirty when
            // they drift). Exit = the solved target pose verbatim.
            BridgeSection.Result r = bs.integrate(dt, fric);
            FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
            TrackPose exit = new TrackPose(r.exit().pos(), r.exit().orient(),
                entry.s0() + last.s(), entry.t0() + last.t());
            return new Centerline(r.samples(), exit);
        }
        throw new IllegalArgumentException("unhandled section mode: " + section);
    }

    /** Rigid pitch/roll placement (v15.2, D100): rotate a section's
     *  samples + exit pose about the piece's entry point. The recorded
     *  felt RE-PROJECTS (G' = G − axis.y + axis'.y) so the κ identity
     *  g(G − axis.y)/v² keeps measuring the rotated-but-unchanged
     *  curvature — Pass A's true curves and every felt consumer stay
     *  honest. v stays the DESIGN profile (the tilt's real speed
     *  change IS Pass D's design/real split); body rates are
     *  rotation-invariant, so authored GEO lanes stay true and only
     *  FORCE felt splits design-vs-real. */
    static Centerline tiltLine(Centerline line, Quat tilt, Vec3 anchor) {
        List<FVDIntegrator.Sample> out =
            new ArrayList<>(line.samples().size());
        for (FVDIntegrator.Sample sm : line.samples()) {
            out.add(tiltSample(sm, tilt, anchor));
        }
        return new Centerline(out, tiltPose(line.exit(), tilt, anchor));
    }

    static FVDIntegrator.Sample tiltSample(FVDIntegrator.Sample sm,
                                           Quat tilt, Vec3 anchor) {
        Vec3 U = tilt.rotate(sm.U());
        Vec3 R = tilt.rotate(sm.R());
        return new FVDIntegrator.Sample(sm.t(), sm.s(),
            tilt.rotate(sm.pos().sub(anchor)).add(anchor),
            tilt.rotate(sm.T()), U, R, sm.v(),
            sm.Gv() - sm.U().y() + U.y(),
            sm.Gl() + sm.R().y() - R.y());   // lateral mirrored (D184)
    }

    static TrackPose tiltPose(TrackPose p, Quat tilt, Vec3 anchor) {
        return new TrackPose(
            tilt.rotate(p.pos().sub(anchor)).add(anchor),
            tilt.mul(p.orient()), p.s0(), p.t0());
    }

    /** The bridge's TARGET-side solve inputs off the next piece's head
     *  (factored VERBATIM out of compile() in v19.20, D249, so
     *  Coaster.optimizeBridge's LOCKSTEP inner settle re-captures
     *  exactly what the next compile will capture): solvedTarget =
     *  the piece's entry position + its ACTUAL first-sample frame, vB
     *  + the head curvature/jerk/roll-rate/roll-acceleration (zeros
     *  for a section-less or too-short head). */
    static void captureTargetHead(BridgeSection bs, TrackPiece next,
                                  double dt, FVDIntegrator.Friction fric) {
        TrackPose tgt = next.entryPose();
        bs.solvedTarget = new TrackPose(tgt.pos(), tgt.orient());
        bs.accB = Vec3.ZERO;
        bs.jerkB = Vec3.ZERO;
        bs.rollB = 0;
        bs.rollAccB = 0;
        bs.vB = 0;
        if (!next.sections.isEmpty()) {
            // pre-integrate the target's FIRST section
            // once: measured head derivatives are mode-
            // agnostic (reversed sections included; a
            // TILTED target's head arrives rigidly
            // rotated, v15.2 — pieceHead applies it)
            List<FVDIntegrator.Sample> head =
                pieceHead(next, dt, fric);
            if (!head.isEmpty()) {
                bs.vB = head.get(0).v();
                // aim at the ACTUAL first-sample frame,
                // not the piece's nominal entry pose
                // (v13.4): a REVERSED first section
                // yaw-places itself — pitch/bank of the
                // entry pose are NOT honored — and the
                // bridge aiming at the nominal pose put
                // a hard kink at the reversed entrance.
                // Same position either way; for normal
                // sections the frame IS the entry pose.
                FVDIntegrator.Sample h0 = head.get(0);
                bs.solvedTarget = new TrackPose(tgt.pos(),
                    Quat.fromFrame(h0.T(), h0.U()));
            }
            if (head.size() >= 9) {
                Vec3[] kj = seamDerivs(head, 0, 1);
                bs.accB = kj[0];
                bs.jerkB = kj[1];
                bs.rollB = seamRollRate(head, 0, 1);
                bs.rollAccB = seamRollAcc(head, 0, 1);
            }
        }
    }

    /** A piece's FIRST section as it really starts — generation at the
     *  design orientation with the rigid tilt applied (the bridge
     *  target-head capture reads this; identity tilt = the historic
     *  call bitwise). An AUTOMATIC follower (D259 §3.5 A1) has no
     *  mirror here by construction: its head generates at the UPSTREAM
     *  span's exit, which this piece-only read cannot see — and a
     *  bridge target is bridged-in, never a follower, so no caller
     *  reaches that case (the design doc's "pieceHead mirrors the
     *  branch" is satisfied vacuously; a Coaster-level read would need
     *  the upstream compile). */
    static List<FVDIntegrator.Sample> pieceHead(TrackPiece piece, double dt) {
        return pieceHead(piece, dt, FVDIntegrator.Friction.NONE);
    }

    static List<FVDIntegrator.Sample> pieceHead(TrackPiece piece, double dt,
                                                FVDIntegrator.Friction fric) {
        // TWO-TIER TILT (D221/D222): mirror the compile chain's
        // branch exactly so the captured head matches bit-for-bit.
        // (D247: the compile's FOLLOWER branch generates through the
        // same designed ? designCanon : pieceCanon fork — a bridge
        // target is bridged-in and therefore never a follower, so
        // this free-piece mirror already covers every capture.)
        Quat tilt = conflictTilt(piece);
        boolean designed = piece.designPitchDeg != 0
            || piece.designRollDeg != 0;
        // the SAME canonical entry the compile chain uses (pieceCanon
        // honors a split's stored pair, D199) so the captured head
        // matches the piece's actual compiled samples bit-for-bit
        TrackPose gen = piece.genPose();
        CanonPose cp = designed ? designCanon(piece, gen.pos())
                                : pieceCanon(piece, gen.pos());
        Centerline local = passGRaw(piece.sections.get(0),
            new TrackPose(Vec3.ZERO, cp.canon()), dt, fric);
        Centerline line = placeLine(local, cp);
        if (tilt == Quat.IDENTITY) return line.samples();
        return tiltLine(line, tilt, gen.pos()).samples();
    }

    // -----------------------------------------------------------------
    // STRICT-SEAM GEOMETRIC SMOOTHING (v17.2, D139 — user spec: the s/c
    // entry-smoothing idea for the frozen junction; "make sure it
    // doesn't impact anything outside the influence — no orientation
    // change, moving the position slightly is okay")
    // -----------------------------------------------------------------

    /** Blend the strict seam between the LAST TWO spans across a
     *  ±len/2 arc window. Orientations SLERP from the upstream curve
     *  onto the follower's — each side extrapolated across the seam at
     *  its own measured per-ARC rate (v18.31, D203: per-INDEX
     *  exponents turned each side at the OTHER side's design-spaced
     *  cadence at a ≠v seam — the blended field's per-arc rate then
     *  STEPPED by the design-speed ratio at any window length, the
     *  user's "instant jump however long the smoothing"; equal
     *  cadence reduces to the old exponent to dust), quintic-
     *  smoothstep weight over
     *  arc — so the window ends sit EXACTLY on the original frames and
     *  nothing outside reorients, ever. Positions re-march along the
     *  blended tangents with the ORIGINAL per-step arc (lengths kept);
     *  the window's interior felt re-derives from the blended rates by
     *  the κ identity at the design profile (sample v kept — the
     *  window's height shift is cm-scale, and Pass D re-reads real
     *  heights anyway). The follower's remainder rigidly TRANSLATES by
     *  the window-end delta (returned; the compile loop shifts the
     *  piece's later sections the same way — the user's "position may
     *  move slightly"). Both spans' exits re-stamp to the drawn
     *  samples (the D80 contract). Halves clamp to HALF of each
     *  adjacent section's arc, so windows never reach a second seam.
     *  Returns null (blend skipped) when the window is too tight. */
    /** DRY RUN of the smoothing window (v20.1, D259 §3.6 SM2 — the
     *  seam diamond's hover, the "Enable smoothing (10 m)" item and the
     *  Smoothing Options window ask it for the length they would set):
     *  would smoothStrictSeam ENGAGE at the seam INTO span spanB with
     *  window length `len`? The IDENTICAL window arithmetic — hUp/hDn =
     *  min(len/2, half the adjacent span's arc), the bounds iA/iB, the
     *  2/3-samples-per-side test — on the compile's samples, nothing
     *  written (gated: smoothEngages == (smoothStrictSeam != null) over
     *  200 random (len, v) pairs, ScSeamGate e). Type-agnostic like the
     *  blend itself: an s/c edge smooths exactly like a lane seam (D3 —
     *  the D232 ban is lifted, the seam diamond is the one control). */
    public static boolean smoothEngages(List<FVDIntegrator.Sample> all,
                                        List<Span> spans, int spanB,
                                        double len) {
        return smoothEngagesWhy(all, spans, spanB, len) == null;
    }

    /** The LAST TWO spans' seam (smoothStrictSeam's own case). */
    public static boolean smoothEngages(List<FVDIntegrator.Sample> all,
                                        List<Span> spans, double len) {
        return smoothEngages(all, spans, spans.size() - 1, len);
    }

    /** Why the window would NOT engage (D259 §3.6 SM2): null = it
     *  engages; SMOOTH_SECTION_SHORT_WHY when the failing side's half
     *  was CLAMPED by its own span's arc (a section beside the seam is
     *  too short to hold the window), SMOOTH_TOO_SHORT_WHY when the
     *  window simply holds too few samples (lengthen it). A seam the
     *  compile does not cover reads TOO_SHORT. */
    public static String smoothEngagesWhy(List<FVDIntegrator.Sample> all,
                                          List<Span> spans, int spanB,
                                          double len) {
        if (all == null || spans == null || spanB < 1
                || spanB >= spans.size() || !(len > 0)) {
            return SectionNames.SMOOTH_TOO_SHORT_WHY;
        }
        Span sa = spans.get(spanB - 1);   // upstream piece's last section
        Span sb = spans.get(spanB);       // the follower's first section
        int iSeam = sa.i1();
        if (iSeam < 0 || iSeam >= all.size() || sb.i1() >= all.size()) {
            return SectionNames.SMOOTH_TOO_SHORT_WHY;
        }
        double sSeam = all.get(iSeam).s();
        double hUp = Math.min(len / 2, sa.sLen() * 0.5);
        double hDn = Math.min(len / 2, sb.sLen() * 0.5);
        int iA = iSeam;
        while (iA > sa.i0() + 1
                && sSeam - all.get(iA - 1).s() <= hUp) iA--;
        int iB = iSeam + 1;
        while (iB < sb.i1() - 1
                && all.get(iB + 1).s() - sSeam <= hDn) iB++;
        boolean upShort = iSeam - iA < 2, dnShort = iB - iSeam < 3;
        if (!upShort && !dnShort) return null;
        boolean upClamped = sa.sLen() * 0.5 < len / 2;
        boolean dnClamped = sb.sLen() * 0.5 < len / 2;
        if ((upShort && upClamped) || (dnShort && dnClamped)) {
            return SectionNames.SMOOTH_SECTION_SHORT_WHY;
        }
        return SectionNames.SMOOTH_TOO_SHORT_WHY;
    }

    static Vec3 smoothStrictSeam(List<FVDIntegrator.Sample> all,
                                 List<Span> spans, double len,
                                 List<Double> hy) {
        int nsp = spans.size();
        if (nsp < 2) return null;
        Span sa = spans.get(nsp - 2);   // upstream piece's last section
        Span sb = spans.get(nsp - 1);   // the follower's first section
        int iSeam = sa.i1();
        double sSeam = all.get(iSeam).s();
        double hUp = Math.min(len / 2, sa.sLen() * 0.5);
        double hDn = Math.min(len / 2, sb.sLen() * 0.5);
        int iA = iSeam;
        while (iA > sa.i0() + 1
                && sSeam - all.get(iA - 1).s() <= hUp) iA--;
        int iB = iSeam + 1;
        while (iB < sb.i1() - 1
                && all.get(iB + 1).s() - sSeam <= hDn) iB++;
        // engage as soon as 2/3 samples exist per side (v17.4 — the
        // 1 m default window is sub-sample at speed otherwise; the
        // rate-measurement windows below adapt via mU/mD)
        if (iSeam - iA < 2 || iB - iSeam < 3) return null;
        // each side's per-sample world rotation, measured just inside
        // it — the extrapolation carries the side's own turning across
        // the seam so the blend morphs RATES, not just angles
        Quat qSeam = frameQ(all.get(iSeam));
        Quat qDn0 = frameQ(all.get(iSeam + 1));
        int mU = Math.min(3, iSeam - iA);
        int mD = Math.min(3, iB - iSeam - 1);
        Quat stepUp = quatPow(
            qSeam.mul(frameQ(all.get(iSeam - mU)).conjugate()), 1.0 / mU);
        Quat stepDn = quatPow(
            frameQ(all.get(iSeam + 1 + mD)).mul(qDn0.conjugate()), 1.0 / mD);
        // ARC-TRUE extrapolation (v18.31, D203 — the user's "instant
        // jump at the seam however long the smoothing"): stepUp/stepDn
        // are per-SAMPLE rotations, but at a ≠v seam the two sides'
        // samples are design-spaced DIFFERENTLY (v_d·dt each) — an
        // index-exponent extrapolation therefore turned each side's
        // field at the OTHER side's cadence, wrong by exactly the
        // design-speed ratio, and the blended field's per-arc rate
        // STEPPED at the seam at any window length (TRUE felt jumped
        // ~2 g on the test coaster). The exponents now advance by
        // ARC over each side's own measured per-sample arc — at an
        // unconflicted seam (equal cadence) this is the old exponent
        // to float dust.
        double dsUp = Math.max(1e-9,
            (all.get(iSeam).s() - all.get(iSeam - mU).s()) / mU);
        double dsDn = Math.max(1e-9,
            (all.get(iSeam + 1 + mD).s() - all.get(iSeam + 1).s()) / mD);
        double sA = all.get(iA).s(), sB = all.get(iB).s();
        double sSeamUp = all.get(iSeam).s();
        double sDn0 = all.get(iSeam + 1).s();
        int n = iB - iA + 1;
        Quat[] q = new Quat[n];
        double[] uu = new double[n];
        for (int i = iA; i <= iB; i++) {
            Quat up = i <= iSeam ? frameQ(all.get(i))
                : quatPow(stepUp,
                    (all.get(i).s() - sSeamUp) / dsUp).mul(qSeam);
            Quat dn = i > iSeam ? frameQ(all.get(i))
                : quatPow(stepDn,
                    (all.get(i).s() - sDn0) / dsDn).mul(qDn0);
            double u = (all.get(i).s() - sA) / Math.max(1e-9, sB - sA);
            double w = u * u * u * (10 + u * (-15 + 6 * u));
            uu[i - iA] = u;
            q[i - iA] = slerp(up, dn, w);
        }
        // re-march positions along the blended tangents; per-step arc
        // = the original lengths (times the closure stretch below)
        double[] st = new double[n];   // per-step hump weight (mid)
        for (int j = 1; j < n; j++) {
            st[j] = hump((uu[j - 1] + uu[j]) / 2);
        }
        Vec3[] pos = marchWindow(all, iA, n, q, 0, st);
        // POSITION CLOSURE (v17.3, D140 — the user's "still a little
        // drift ... noticeable on longer sections"): the window is
        // bent and stretched until its re-march lands back on the
        // ORIGINAL end position, so the downstream translation all
        // but vanishes. Per iteration: (1) corrective rotation HUMPS
        // (sin²(πu) — zero value AND slope at both ends: outside
        // frames and edge rates stay exact) close the ⊥-to-chord
        // components — two axes, first-order least squares; (2) a
        // hump-weighted per-step ARC STRETCH closes the along-chord
        // foreshortening a bend cannot reach (measured: 100% of the
        // moderate-fixture residual). Caps — 0.2 rad of bend, ±5%
        // of stretch — make pathological kinks accept a leftover
        // translation instead of corkscrewing. The stretch means
        // the drawn arc inside the window exceeds the s fields by
        // ≤ the cap (mm–cm; ties/combs inside a smoothed window sit
        // that hair wide — documented corner). The felt recompute
        // below reads the corrected quats: the closing bend shows
        // honestly in the graphs.
        Vec3 origEnd = all.get(iB).pos();
        Vec3 chord = origEnd.sub(pos[0]);
        double clen = chord.length();
        if (clen > 1e-9) {
            chord = chord.scale(1 / clen);
            Vec3 e1 = (Math.abs(chord.y()) < 0.9
                ? chord.cross(new Vec3(0, 1, 0))
                : chord.cross(new Vec3(1, 0, 0))).normalize();
            Vec3 e2 = chord.cross(e1);
            double amp = 0, beta = 0;
            for (int it = 0; it < 4; it++) {
                Vec3 dp = pos[n - 1].sub(origEnd);
                if (dp.length() < 1e-5) break;
                if (amp < 0.2) {   // (1) bend ⊥
                    Vec3 k1 = Vec3.ZERO, k2 = Vec3.ZERO;
                    for (int j = 1; j < n; j++) {
                        Vec3 seg = pos[j].sub(pos[j - 1]);
                        k1 = k1.add(e1.cross(seg).scale(st[j]));
                        k2 = k2.add(e2.cross(seg).scale(st[j]));
                    }
                    double a11 = k1.dot(k1), a12 = k1.dot(k2),
                           a22 = k2.dot(k2);
                    double det = a11 * a22 - a12 * a12;
                    if (Math.abs(det) > 1e-12) {
                        double b1 = -k1.dot(dp), b2 = -k2.dot(dp);
                        Vec3 w = e1.scale((b1 * a22 - b2 * a12) / det)
                            .add(e2.scale((a11 * b2 - a12 * b1) / det));
                        double wl = w.length();
                        if (wl > 1e-12) {
                            if (amp + wl > 0.2) {
                                double sc = (0.2 - amp) / wl;
                                w = w.scale(sc);
                                wl *= sc;
                            }
                            amp += wl;
                            Vec3 axis = w.scale(1 / wl);
                            for (int j = 1; j < n - 1; j++) {
                                double h = hump(uu[j]);
                                if (h > 1e-12) {
                                    q[j] = Quat.aboutAxis(axis, wl * h)
                                        .mul(q[j]);
                                }
                            }
                            pos = marchWindow(all, iA, n, q, beta, st);
                        }
                    }
                }
                dp = pos[n - 1].sub(origEnd);   // (2) stretch ∥
                Vec3 k3 = Vec3.ZERO;
                for (int j = 1; j < n; j++) {
                    k3 = k3.add(pos[j].sub(pos[j - 1]).scale(st[j]));
                }
                double k3n = k3.dot(k3);
                if (k3n < 1e-12) break;
                double binc = -dp.dot(k3) / k3n;
                binc = Math.max(-0.05 - beta, Math.min(0.05 - beta, binc));
                if (Math.abs(binc) < 1e-9) continue;
                beta += binc;
                pos = marchWindow(all, iA, n, q, beta, st);
            }
        }
        // interior samples: blended frame + marched position + felt
        // from the blended rotation measured PER ARC (κ — the
        // speed-free invariant; a time-based Ω would mix the two
        // sides' design speeds across a conflicted seam and jitter
        // the decoded curvature). Felt = κ·v²/g + axis.y at the
        // sample's own design speed; κ·U = curvature toward R,
        // −κ·R = toward U (the §2 composition read back).
        for (int j = 1; j < n - 1; j++) {
            int i = iA + j;
            FVDIntegrator.Sample sm = all.get(i);
            Vec3 T = q[j].T(), U = q[j].U(), R = U.cross(T);
            double dsc = all.get(i + 1).s() - all.get(i - 1).s();
            Vec3 kap = dsc < 1e-9 ? Vec3.ZERO
                : axisAngle(q[j + 1].mul(q[j - 1].conjugate()))
                    .scale(1 / dsc);
            double v = sm.v();
            all.set(i, new FVDIntegrator.Sample(sm.t(), sm.s(), pos[j],
                T, U, R, v,
                -kap.dot(R) * v * v / FVDIntegrator.G0 + U.y(),
                -(kap.dot(U) * v * v / FVDIntegrator.G0 + R.y())));   // D184
        }
        // the follower's remainder translates rigidly; its span exit
        // moves with it (orientation untouched). The UPSTREAM span's
        // exit deliberately does NOT re-stamp to the blended seam
        // sample: that pose is the FOLLOW CONTRACT — strictEntry and
        // bakeFollow read it, and handing them the blended (half-kink)
        // frame would bake smoothing INTO the placement on the next
        // resolve. The D80 "exit == last drawn sample" contract bends
        // knowingly inside a smoothed window: samples are the cosmetic
        // blend, the exit is the junction's true state.
        Vec3 dp = pos[n - 1].sub(all.get(iB).pos());
        for (int i = iB; i <= sb.i1(); i++) {
            FVDIntegrator.Sample sm = all.get(i);
            all.set(i, new FVDIntegrator.Sample(sm.t(), sm.s(),
                sm.pos().add(dp), sm.T(), sm.U(), sm.R(), sm.v(),
                sm.Gv(), sm.Gl()));
        }
        TrackPose xb = sb.exit();
        spans.set(nsp - 1, new Span(sb.i0(), sb.i1(), sb.t0(), sb.t1(),
            sb.s0(), sb.sLen(), new TrackPose(xb.pos().add(dp),
                xb.orient(), xb.s0(), xb.t0()),
            sb.exitPsi(), sb.exitCanon(), sb.exitTilt(), sb.scBounds()));
        // re-stamp the ENERGY heights the blend/translation moved
        // (D199): world deltas anchored on the untouched window start
        // — the window is world geometry by definition
        if (hy != null) {
            double yRef = all.get(iA).pos().y();
            double hRef = hy.get(iA);
            for (int i = iA + 1; i <= sb.i1(); i++) {
                hy.set(i, hRef + (all.get(i).pos().y() - yRef));
            }
        }
        return dp;
    }

    /** A world Centerline rigidly translated (the post-smoothing shift
     *  for a piece's later sections). */
    static Centerline shiftLine(Centerline line, Vec3 dp) {
        List<FVDIntegrator.Sample> out =
            new ArrayList<>(line.samples().size());
        for (FVDIntegrator.Sample sm : line.samples()) {
            out.add(new FVDIntegrator.Sample(sm.t(), sm.s(),
                sm.pos().add(dp), sm.T(), sm.U(), sm.R(), sm.v(),
                sm.Gv(), sm.Gl()));
        }
        TrackPose e = line.exit();
        return new Centerline(out, new TrackPose(e.pos().add(dp),
            e.orient(), e.s0(), e.t0()));
    }

    private static Quat frameQ(FVDIntegrator.Sample sm) {
        return Quat.fromFrame(sm.T(), sm.U());
    }

    /** The smoothing window's re-march: positions along the blended
     *  tangents at the original per-step arc lengths, each scaled by
     *  (1 + beta·st[j]) — the closure's along-chord stretch. */
    private static Vec3[] marchWindow(List<FVDIntegrator.Sample> all,
                                      int iA, int n, Quat[] q,
                                      double beta, double[] st) {
        Vec3[] pos = new Vec3[n];
        pos[0] = all.get(iA).pos();
        for (int j = 1; j < n; j++) {
            double ds = (all.get(iA + j).s() - all.get(iA + j - 1).s())
                * (1 + beta * st[j]);
            Vec3 dir = q[j - 1].T().add(q[j].T()).normalize();
            pos[j] = pos[j - 1].add(dir.scale(ds));
        }
        return pos;
    }

    /** Closure hump sin²(πu): zero value AND slope at both ends. */
    private static double hump(double u) {
        double s = Math.sin(Math.PI * u);
        return s * s;
    }

    /** q^k by axis–angle scaling (k may be fractional or negative —
     *  per-sample steps and their inverses stay far from ±180°). */
    private static Quat quatPow(Quat q, double k) {
        Vec3 aa = axisAngle(q);
        double ang = aa.length();
        if (ang < 1e-15) return Quat.IDENTITY;
        return Quat.aboutAxis(aa.scale(1 / ang), ang * k);
    }

    /** Rotation vector (axis × angle, rad) of a unit quaternion,
     *  shortest representation. (Package-visible: the seam carets
     *  read the seam's angular jump through it, v17.3.) */
    static Vec3 axisAngle(Quat q) {
        double w = q.w(), x = q.x(), y = q.y(), z = q.z();
        if (w < 0) { w = -w; x = -x; y = -y; z = -z; }
        double sl = Math.sqrt(x * x + y * y + z * z);
        if (sl < 1e-15) return Vec3.ZERO;
        double ang = 2 * Math.atan2(sl, Math.min(1, w));
        return new Vec3(x / sl * ang, y / sl * ang, z / sl * ang);
    }

    /** Shortest-arc SLERP (nlerp under 1.8° — indistinguishable there
     *  and division-safe). */
    private static Quat slerp(Quat a, Quat b, double t) {
        double dot = a.w() * b.w() + a.x() * b.x() + a.y() * b.y()
            + a.z() * b.z();
        if (dot < 0) { b = b.scale(-1); dot = -dot; }
        if (dot > 0.99988) {
            return a.scale(1 - t).add(b.scale(t)).normalize();
        }
        double th = Math.acos(Math.min(1, dot));
        double s = Math.sin(th);
        return a.scale(Math.sin((1 - t) * th) / s)
            .add(b.scale(Math.sin(t * th) / s)).normalize();
    }

    /** ABSOLUTE roll lanes get their bank knots re-read EXACTLY off
     *  the Bezier (v16.9, D121): bake()'s parameter walk targets
     *  ~0.1-unit steps and LINEAR-fills the 0.05 grid between —
     *  invisible for rate lanes, but the ride effectively
     *  DIFFERENTIATES an absolute lane's values, and the fill made
     *  the resulting roll rate wobble cell to cell (the user's
     *  "jitter"/stair-stepping, with bankHermite's C1 read the
     *  other half of the fix). PACKAGE-VISIBLE since v20.1 (D259
     *  §2.3.1): ScIntegrator applies it to an ABSOLUTE s/c element's
     *  own lane keyframes — the same read, never a second copy. */
    static List<FVDIntegrator.Keyframe> exactRoll(
            List<FVDIntegrator.Keyframe> kfs, List<BezierBaker.Node> lane) {
        List<FVDIntegrator.Keyframe> out = new ArrayList<>(kfs.size());
        for (FVDIntegrator.Keyframe k : kfs) {
            out.add(new FVDIntegrator.Keyframe(k.t(), k.Gv(), k.Gl(),
                BezierBaker.exactAt(lane, k.t())));
        }
        return out;
    }

    /** exactRoll's twin for ABSOLUTE yaw/pitch lanes (v19.8, D237 —
     *  the same D121 rule: the ride differentiates absolute lanes,
     *  so their knots must sit exactly ON the Bezier). Fires only
     *  under the flag — historic paths bitwise. */
    private static List<FVDIntegrator.Keyframe> exactYp(
            List<FVDIntegrator.Keyframe> kfs,
            List<BezierBaker.Node> lane0, List<BezierBaker.Node> lane1) {
        List<FVDIntegrator.Keyframe> out = new ArrayList<>(kfs.size());
        for (FVDIntegrator.Keyframe k : kfs) {
            out.add(new FVDIntegrator.Keyframe(k.t(),
                BezierBaker.exactAt(lane0, k.t()),
                BezierBaker.exactAt(lane1, k.t()), k.rollRateDeg()));
        }
        return out;
    }

    /** The plain FORCE path (extracted v15.0 — the influence fast path
     *  reuses it verbatim; zero added arithmetic on the D50 gate).
     *  DISTANCE-domain sections (v16.0, D103) bake onto an s-grid and
     *  take the arc-terminated integrator — same physics, other
     *  parameterization. */
    private static Centerline forceLane(ForceSection fs, TrackPose entry,
                                        double dt,
                                        FVDIntegrator.Friction fric) {
        return forceLane(fs, entry, dt, fric, null, false);
    }

    /** dInfl = influence GEO-RATE delta keyframes on the lanes' own
     *  axis (v19.7, D236 — the one-pass hybrid); null = the plain
     *  path, bitwise. */
    private static Centerline forceLane(ForceSection fs, TrackPose entry,
                                        double dt,
                                        FVDIntegrator.Friction fric,
                                        List<FVDIntegrator.Keyframe> dInfl,
                                        boolean dYpG) {
        boolean dist = fs.domain() == Section.Domain.DISTANCE;
        // bake grid in the lane's own axis units: TIME lanes at 0.01 s
        // since v17.11 (D151 — knots on every RK4 step boundary; the
        // 0.05 grid's phase was the last visible split-vs-unsplit
        // term), DISTANCE lanes keep 0.05 m (~26× denser per meter of
        // track already; 0.01 m would balloon long sections' tables)
        List<FVDIntegrator.Keyframe> kfs =
            BezierBaker.bake(fs.lanes, dist ? 0.05 : 0.01);
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = dt;
        opts.v0 = fs.vDesign;
        opts.fric = fric;   // Phase 3 (v17.0, D133)
        opts.rollGlobal = fs.rollGlobal;   // v16.1, D107
        opts.rollPerMeter = fs.ratePerMeter;   // v16.6, D115
        opts.rollAbsolute = fs.rollAbsolute && fs.rollGlobal;   // v16.7, D118
        if (opts.rollAbsolute) kfs = exactRoll(kfs, fs.lanes.get(2));
        // speed controls (v18.0, D160) — legacy-reversed/influenced
        // sections carry none (speedControllable gates); null = bitwise
        if (fs.speedControllable()) {
            opts.ctl = SpeedControl.Ctl.of(fs.speedCtls);
        }
        opts.dInfl = dInfl;               // influence hybrid (D236)
        opts.dInflYpGlobal = dYpG;
        if (fs.reversed) {
            // canonical frame at the ENTRY HEIGHT: energy is
            // height-relative but float rounding is not — this keeps
            // the reversed shape bitwise equal to a forward run at
            // the same height (gated)
            opts.pos0 = new Vec3(0, entry.pos().y(), 0);
            return reverseAndPlace(dist
                ? FVDIntegrator.integrateTrackDist(kfs, opts)
                : FVDIntegrator.integrateTrack(kfs, opts), entry);
        }
        opts.pos0 = entry.pos();
        opts.startHeading = entry.T();   // identity orientation: exactly (1,0,0)
        opts.startUp = entry.U();        //                       exactly (0,1,0)
        List<FVDIntegrator.Sample> samples = dist
            ? FVDIntegrator.integrateTrackDist(kfs, opts)
            : FVDIntegrator.integrateTrack(kfs, opts);
        FVDIntegrator.Sample last = samples.get(samples.size() - 1);
        TrackPose exit = new TrackPose(last.pos(),
            Quat.fromFrame(last.T(), last.U()),
            entry.s0() + last.s(), entry.t0() + last.t());
        return new Centerline(samples, exit);
    }

    /** The plain GEO path (extracted v15.0, same rationale; distance
     *  domain v16.0 like forceLane). */
    private static Centerline geoLane(GeoSection gs, TrackPose entry, double dt,
                                      FVDIntegrator.Friction fric) {
        return geoLane(gs, entry, dt, fric, null, 0);
    }

    /** dInfl = influence FELT delta keyframes (ΔGv/ΔGl) on the
     *  lanes' own axis (v19.7, D236); null = plain, bitwise.
     *  ypHeadDeg = the entry's piece-relative heading (D238): an
     *  ABSOLUTE heading lane authors PIECE-relative values, and the
     *  canonical generation frame re-zeroes at the SECTION entry —
     *  the constant shift bridges the two (slopes untouched, so the
     *  feed-forward and felt records ride through; representatives
     *  360° apart are the same orientation — projectYp wraps). */
    private static Centerline geoLane(GeoSection gs, TrackPose entry, double dt,
                                      FVDIntegrator.Friction fric,
                                      List<FVDIntegrator.Keyframe> dInfl,
                                      double ypHeadDeg) {
        boolean dist = gs.domain() == Section.Domain.DISTANCE;
        List<FVDIntegrator.Keyframe> kfs =
            BezierBaker.bake(gs.lanes, dist ? 0.05 : 0.01);   // D151
        boolean gAbs = gs.rollAbsolute && gs.rollGlobal;   // v16.7
        if (gAbs) kfs = exactRoll(kfs, gs.lanes.get(2));
        boolean gYpAbs = gs.ypAbsolute && gs.yawPitchGlobal;   // v19.8, D237
        if (gYpAbs) {
            kfs = exactYp(kfs, gs.lanes.get(0), gs.lanes.get(1));
            // piece-relative → section-local heading (D238); the
            // reversed legacy path keeps its own canonical frame
            if (ypHeadDeg != 0 && !gs.reversed) {
                List<FVDIntegrator.Keyframe> sh =
                    new ArrayList<>(kfs.size());
                for (FVDIntegrator.Keyframe k : kfs) {
                    sh.add(new FVDIntegrator.Keyframe(k.t(), k.Gv(),
                        k.Gl() - ypHeadDeg, k.rollRateDeg()));
                }
                kfs = sh;
            }
        }
        if (gs.reversed) {
            GeoIntegrator.Result rr = dist
                ? GeoIntegrator.integrateDist(kfs, dt, gs.vDesign,
                    new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY,
                    gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                    gAbs, fric, null, dInfl, gYpAbs)
                : GeoIntegrator.integrate(kfs, dt,
                    gs.vDesign, new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY,
                    gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                    gAbs, fric, null, dInfl, gYpAbs);
            return reverseAndPlace(rr.samples(), entry);
        }
        // quaternion-native (the §2 amendment): the exit orientation
        // is the integrated q itself, not a frame round-trip.
        // Speed controls (v18.0): null unless the section carries any
        SpeedControl.Ctl ctl = gs.speedControllable()
            ? SpeedControl.Ctl.of(gs.speedCtls) : null;
        GeoIntegrator.Result r = dist
            ? GeoIntegrator.integrateDist(kfs, dt, gs.vDesign,
                entry.pos(), entry.orient(),
                gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                gAbs, fric, ctl, dInfl, gYpAbs)
            : GeoIntegrator.integrate(kfs, dt, gs.vDesign,
                entry.pos(), entry.orient(),
                gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                gAbs, fric, ctl, dInfl, gYpAbs);
        FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
        TrackPose exit = new TrackPose(last.pos(), r.qEnd(),
            entry.s0() + last.s(), entry.t0() + last.t());
        return new Centerline(r.samples(), exit);
    }

    /** INFLUENCE — the ONE-PASS HYBRID (v19.7, D236 — user re-spec,
     *  superseding the v15.0 two-pass AND its D235 split anchor):
     *  the section integrates its OWN mode — force targets keep
     *  re-solving against gravity live — with the delta lanes
     *  injected per RK4 stage AT THE CURRENT STATE: a force
     *  section's Δpitch/Δyaw join Ω as extra rates (local body or
     *  global heading/elevation per inflYawPitchGlobal), a geo
     *  section's ΔGv/ΔGl become Δω = ±g·ΔG/v. Where the deltas
     *  are zero the solve IS the plain one — felt == the authored
     *  targets, no residual laterals, no leftover conflict ("the
     *  influence adjusts the value that much; back at zero
     *  everything behaves per the original graphs" — the user's
     *  2026-08-25 re-spec; the old two-pass replayed the ref's
     *  rates in whatever frame the deltas had left the track in,
     *  which read as phantom laterals after the delta window, and
     *  its splits needed a stored ref anchor). resulting == base +
     *  delta holds EXACTLY by linearity — the integrators' felt
     *  records add the deltas' contribution analytically — and
     *  baseCurves := resulting − delta on the section's OWN axis
     *  (no re-timed t* clock exists any more: the delta axis IS
     *  the authoring axis). vAuthored stays a mirrored file-compat
     *  field; splits are exact by the Markov property alone; speed
     *  controls ride the single integration like any section.
     *  ABSOLUTE-ROLL sections still take no influence; the
     *  all-zero-deltas fast path is the plain route, bitwise. */
    static Centerline influencedLane(LaneSection ls, TrackPose entry,
                                     double dt, boolean isGeo) {
        return influencedLane(ls, entry, dt, isGeo,
            FVDIntegrator.Friction.NONE);
    }

    static Centerline influencedLane(LaneSection ls, TrackPose entry,
                                     double dt, boolean isGeo,
                                     FVDIntegrator.Friction fric) {
        return influencedLane(ls, entry, dt, isGeo, fric, 0);
    }

    static Centerline influencedLane(LaneSection ls, TrackPose entry,
                                     double dt, boolean isGeo,
                                     FVDIntegrator.Friction fric,
                                     double ypHeadDeg) {
        if ((ls.rollAbsolute && ls.rollGlobal)
                || (ls.ypAbsolute && ls.yawPitchGlobal)) {
            // absolute-angle sections take no influence (deltas are
            // RATES — the projection would silently erase them;
            // ypAbsolute joins in v19.8, D237)
            ls.baseCurves = null;
            return isGeo
                ? geoLane((GeoSection) ls, entry, dt, fric, null, ypHeadDeg)
                : forceLane((ForceSection) ls, entry, dt, fric);
        }
        boolean distAxis = ls.domain() == Section.Domain.DISTANCE;
        List<FVDIntegrator.Keyframe> kfs =
            BezierBaker.bake(ls.lanes, distAxis ? 0.05 : 0.01);   // D151
        if (ls.deltasAllZero()) {
            Centerline line = isGeo ? geoLane((GeoSection) ls, entry, dt, fric)
                                    : forceLane((ForceSection) ls, entry, dt, fric);
            // base guides off the very samples on show; reversed
            // strips never edit influence — skip
            ls.baseCurves = ls.reversed ? null
                : buildBase(line.samples(), kfs, ls, isGeo, null, null,
                    distAxis);
            return line;
        }
        // delta keyframes on the section's OWN axis (Gv slot = lane 0,
        // Gl slot = lane 1; roll takes no deltas)
        List<FVDIntegrator.Keyframe> dKfs =
            BezierBaker.bake(ls.deltaLanes, distAxis ? 0.05 : 0.01);
        Centerline line = isGeo
            ? geoLane((GeoSection) ls, entry, dt, fric, dKfs, 0)
            : forceLane((ForceSection) ls, entry, dt, fric, dKfs,
                ls.inflYawPitchGlobal);
        // base rows for the editor's guides: resulting − delta —
        // exact by the linearity of the records
        double[][] base = buildBase(line.samples(), kfs, ls, isGeo,
            null, null, distAxis);
        List<double[]> d0 = BezierBaker.polyline(ls.deltaLanes.get(0));
        List<double[]> d1 = BezierBaker.polyline(ls.deltaLanes.get(1));
        for (double[] row : base) {
            row[1] -= polyAt(d0, row[0]);
            row[2] -= polyAt(d1, row[0]);
        }
        ls.baseCurves = ls.reversed ? null : base;
        return line;
    }

    /** BASE rows {x, lane0, lane1, lane2} in the delta lanes' units
     *  AND the INFLUENCE flags' semantics (v16.5, D112), analytic per
     *  ref sample; x = the section's NATIVE axis — the re-timed clock
     *  t* (TIME) or the local arc (DISTANCE, v16.1). tStar/vStar null
     *  = identity re-time (the fast path: the operating profile IS
     *  the ref profile). Representation conversion: body pitch/yaw
     *  come from the κ identity regardless of the section's flags
     *  (felt records Ω's body components honestly, D107); the roll
     *  lane's meaning follows ls.rollGlobal — bank rate φ' vs body
     *  roll p — and converts through the transport relation
     *  p = φ' + ψ'·sinθ (worldRates' φ' line, inverted); world
     *  heading/elevation rates for a global-axis influence come from
     *  worldRates. Near-vertical samples have no heading/bank —
     *  NaN world terms fall back like the bake extraction (hold:
     *  rate 0). All rates scale by v* / v_d — a fixed shape's angular
     *  rates, world or body, are linear in traversal speed. */
    private static double[][] buildBase(List<FVDIntegrator.Sample> ref,
            List<FVDIntegrator.Keyframe> kfs, LaneSection ls, boolean isGeo,
            double[] tStar, double[] vStar, boolean distAxis) {
        int n = ref.size();
        double[][] base = new double[n][4];
        boolean pm = ls.ratePerMeter;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = ref.get(i);
            double vd = Math.max(0.1, sm.v());
            double vSafe = Math.max(vd, 0.5);
            double scale = vStar == null ? 1 : vStar[i] / vd;
            // °/m rows (v16.7, D117): a per-meter rate is angle per
            // meter of the FIXED ref shape — invariant under the
            // operating speed, so the rate scale is 1 and °/s reads
            // divide by the sample's own design speed
            double rateDiv = 1;
            if (pm) {
                rateDiv = vSafe;
                scale = 1;
            }
            double rollLane = FVDIntegrator.interpAt(kfs,
                distAxis ? sm.s() : sm.t()).rollRateDeg();
            // per-meter sections author °/m: all conversion math runs
            // in °/s space, so the lane read converts up front and
            // the emitted rows divide back down (rateDiv, v16.7)
            if (pm) rollLane *= vSafe;
            base[i][0] = distAxis ? sm.s()
                : tStar == null ? sm.t() : tStar[i];
            // body pitch/yaw rates (deg/s) — the κ identity
            double pitB = Math.toDegrees(FVDIntegrator.G0
                * (sm.Gv() - sm.U().y()) / vSafe);
            double yawB = Math.toDegrees(FVDIntegrator.G0
                * (sm.Gl() + sm.R().y()) / vSafe);   // REPORTED yaw
                    // (+ = left, D185; the D184/D185 signs cancel)
            // the roll lane in BOTH representations (only the needed
            // conversion runs; ψ' is roll-independent — Ω's T part
            // drops out of Ω×T)
            double rollB, bankR;
            if (ls.rollGlobal) {
                bankR = rollLane;
                double psi = Coaster.worldRates(sm, pitB, yawB, 0)[1];
                // transport uses the INTERNAL heading rate = the
                // negated reported one (D185)
                rollB = bankR + (Double.isNaN(psi) ? 0
                    : -psi * sm.T().y());
            } else {
                rollB = rollLane;
                bankR = ls.inflRollGlobal
                    ? capNaN(Coaster.worldRates(sm, pitB, yawB, rollB)[2])
                    : 0;   // unused
            }
            if (isGeo) {
                // geo section: deltas are FELT G — the §5.1 identity
                // (felt scales with the operating profile even for
                // per-meter sections: G targets are per-speed)
                double fScale = vStar == null ? 1 : vStar[i] / vd;
                double r2 = fScale * fScale;
                base[i][1] = r2 * (sm.Gv() - sm.U().y()) + sm.U().y();
                base[i][2] = r2 * (sm.Gl() + sm.R().y()) - sm.R().y();
            } else if (ls.inflYawPitchGlobal) {
                // force section, GLOBAL-axis influence: elevation +
                // heading rates
                double[] wr = Coaster.worldRates(sm, pitB, yawB, rollB);
                base[i][1] = capNaN(wr[0]) * scale / rateDiv;
                base[i][2] = capNaN(wr[1]) * scale / rateDiv;
            } else {
                // force section, local influence: body rates
                base[i][1] = pitB * scale / rateDiv;
                base[i][2] = yawB * scale / rateDiv;
            }
            base[i][3] = (ls.inflRollGlobal ? bankR : rollB)
                * scale / rateDiv;
        }
        return base;
    }

    /** NaN world rates (vertical track: no heading/bank) hold the
     *  angle — rate 0, the bake extraction's rule. */
    private static double capNaN(double r) {
        return Double.isNaN(r) ? 0 : r;
    }

    /** Linear eval of a (t, value) polyline with held ends — the
     *  delta lanes' hold-final-value semantics. Package-visible: the
     *  editor's influence overlays evaluate the same curve. */
    static double polyAt(List<double[]> poly, double t) {
        if (t <= poly.get(0)[0]) return poly.get(0)[1];
        int hi = poly.size() - 1;
        if (t >= poly.get(hi)[0]) return poly.get(hi)[1];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (poly.get(mid)[0] <= t) lo = mid; else hi = mid;
        }
        double[] a = poly.get(lo), b = poly.get(hi);
        return a[1] + (b[1] - a[1]) * (t - a[0]) / (b[0] - a[0]);
    }

    /** REVERSAL (Phase 2, D67; SECTIONS-DESIGN §3): the authored
     *  centerline traversed back-to-front — same point set, opposite
     *  direction (the §9 gate). The shape is generated in the CANONICAL
     *  frame (identity pose at x=z=0, ENTRY height: a force shape is
     *  only honest against gravity in the orientation it was authored
     *  for, and yaw about world-Y is the one alignment preserving
     *  that), then
     *  the samples are reversed — T → −T, R → −R, U unchanged, felt Gl
     *  negated, t/s re-based forward — and the whole line is yawed +
     *  translated so its new FIRST sample sits exactly on the entry
     *  position with the entry's horizontal heading. A pitched or
     *  banked entry cannot be fully matched (that would tilt the shape
     *  against its own gravity): the seam shows the mismatch honestly —
     *  end the upstream section level for a clean join. The section's
     *  vDesign stays the AUTHORED-direction entry speed (= the train's
     *  exit side); the train-side design speed is the authored end of
     *  the profile. */
    static Centerline reverseAndPlace(List<FVDIntegrator.Sample> fwd, TrackPose entry) {
        int n = fwd.size();
        FVDIntegrator.Sample lastF = fwd.get(n - 1);
        double tEnd = lastF.t(), sEnd = lastF.s();
        Vec3 tRev = lastF.T().scale(-1);   // the train's entry direction
        double yaw = Math.atan2(-entry.T().z(), entry.T().x())
                   - Math.atan2(-tRev.z(), tRev.x());
        Quat q = Quat.aboutY(Math.toDegrees(yaw));
        Vec3 anchor = q.rotate(lastF.pos());
        List<FVDIntegrator.Sample> out = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            FVDIntegrator.Sample sm = fwd.get(i);
            Vec3 pos = q.rotate(sm.pos()).sub(anchor).add(entry.pos());
            Vec3 T = q.rotate(sm.T()).scale(-1);
            Vec3 U = q.rotate(sm.U());
            out.add(new FVDIntegrator.Sample(tEnd - sm.t(), sEnd - sm.s(),
                pos, T, U, U.cross(T), sm.v(), sm.Gv(), -sm.Gl()));
        }
        FVDIntegrator.Sample last = out.get(out.size() - 1);
        TrackPose exit = new TrackPose(last.pos(),
            Quat.fromFrame(last.T(), last.U()),
            entry.s0() + last.s(), entry.t0() + last.t());
        return new Centerline(out, exit);
    }

    /** Chain a section's samples onto the global list. The very first
     *  chunk passes through UNTOUCHED (the bit-for-bit path). Later
     *  chunks shift t/s by the running totals; a CONTINUATION section
     *  (same piece) additionally drops its first sample, which
     *  duplicates the previous section's exit point — a new PIECE's
     *  first sample is a genuinely new point and is kept. */
    private static void appendRebased(List<FVDIntegrator.Sample> all,
                                      List<FVDIntegrator.Sample> add,
                                      boolean continuation) {
        if (all.isEmpty()) {
            all.addAll(add);
            return;
        }
        FVDIntegrator.Sample prev = all.get(all.size() - 1);
        double tOff = prev.t(), sOff = prev.s();
        for (int i = continuation ? 1 : 0; i < add.size(); i++) {
            FVDIntegrator.Sample sm = add.get(i);
            all.add(new FVDIntegrator.Sample(tOff + sm.t(), sOff + sm.s(),
                sm.pos(), sm.T(), sm.U(), sm.R(), sm.v(), sm.Gv(), sm.Gl()));
        }
    }

    /** Pass D (round A, §5.1): TRUE speed per sample — energy per RUN
     *  (runs = pieces chained by trailing bridges; rows from compile:
     *  {first sample index, entry speed}), referenced to each run's
     *  first sample height. Same expression the integrator records, so
     *  a clean undrifted chain reproduces Sample.v (the single-force
     *  document: bitwise — the D50 gate rides on that). Bridges are
     *  pure GEOMETRY here: their design-side speed blend (D74) is
     *  deliberately not applied — the true curves show the ride
     *  without the blend's invisible brake/boost. A detached piece is
     *  its own run (its trueV references its own entry, not the ride
     *  start). FRICTION (Phase 3, v17.0 D133): the run march
     *  accumulates the document's rolling + air losses over the
     *  sample arc (Heun; the true ride always sheds in TRAVEL order —
     *  which is exactly how a pre-resolve reversed section's design
     *  profile stays honestly conflicted). Zero coefficients add a
     *  literal − 0.0 — bitwise. */
    public static double[] passD(List<FVDIntegrator.Sample> samples,
                                 List<double[]> runs) {
        return passD(samples, runs, 0, 0);
    }

    public static double[] passD(List<FVDIntegrator.Sample> samples,
                                 List<double[]> runs,
                                 double fricRoll, double fricAir) {
        return passD(samples, runs, fricRoll, fricAir, null);
    }

    /** SPEED-CONTROL variant (v18.0, D160): ctl = per-sample resolved
     *  rows from {@link #ctlSampleRows} (null = none — the friction
     *  march below is arithmetic-identical to the historic path). A
     *  driven interval replaces friction (the drive absorbs it, the
     *  felt-G contract); snaps land the march ON the target exactly
     *  like the design integrators, so a control closes design/real
     *  conflicts by construction. */
    public static double[] passD(List<FVDIntegrator.Sample> samples,
                                 List<double[]> runs,
                                 double fricRoll, double fricAir,
                                 double[][] ctl) {
        return passD(samples, runs, fricRoll, fricAir, ctl, null);
    }

    /** ENERGY-HEIGHT variant (v18.27, D199): hy = compile's
     *  run-relative heights chained from the LOCAL generation bits,
     *  so the real march's arithmetic never contains the placement —
     *  a min/max speed control snapped EXACTLY on its target is a
     *  knife-edge comparison at every sample, and world-height dust
     *  under a translation flipped its engage pattern (0.4 m/s of
     *  trueV at the user's seam, flipping the reconcile's anchor).
     *  null = read the world heights (the historic path, bitwise). */
    public static double[] passD(List<FVDIntegrator.Sample> samples,
                                 List<double[]> runs,
                                 double fricRoll, double fricAir,
                                 double[][] ctl, double[] hy) {
        return passD(samples, runs, fricRoll, fricAir, ctl, hy, null);
    }

    /** How close the train must enter a BRIDGED-IN piece to its design
     *  speed for the flows to count as one (m/s): the bridge's own
     *  time sampling reacts to the last bits of the target's design
     *  speed, so "design speed -> real speed at the bridge's end" has
     *  NO bit-level fixed point on some bridges (2- and 3-cycles of
     *  adjacent doubles, up to 19 ulp wide, on two of the user's
     *  files). Far below anything the app shows or compares (the
     *  init-speed window's 0.005, the badge's 0.2), two orders above
     *  the widest cycle (6.75e-14) and the change a rigid move of a
     *  whole document makes to the gap (5e-14 over 120 documents x 4
     *  moves) — AND the tolerance Coaster.refreezeSpeedOnly rests at
     *  (review round 4): its loop reads the recorded seam speed, so
     *  it ends the moment the raw march is inside this band; a wider
     *  band (1e-9 at first) left the resting gap anywhere inside it.
     *  Same size as Coaster.REFREEZE_TOL by design. */
    static final double BRIDGE_ENTRY_TOL = 1e-11;

    /** CLEAN-SPAN variant (v19.26, D255 - the user's "is the design
     *  speed the real speed BITWISE?"): clean = compile's rows {entry
     *  sample, first sample, last sample, the section's design entry
     *  speed, 1 = the entry sample is a bridge's last}, ascending -
     *  stretches whose samples ARE the design solve's own geometry
     *  (no bridge, no rigid tilt, no smoothing blend, travel-order
     *  friction). Where the train ENTERS such a stretch at exactly the
     *  section's design speed (BIT equality of the RECORDED speed at
     *  the entry sample; within BRIDGE_ENTRY_TOL behind a bridge, and
     *  the seam then records the design speed), the real flow and the
     *  design flow are one initial-value problem on one track - so
     *  the stretch RECORDS the design speed as its true speed. Before,
     *  the same physics was integrated twice by different rules (the
     *  design integrators carry friction in their RK4 state, this
     *  march sums it by Heun over the sample arc; held speed controls,
     *  before D257, lost their hold at different samples), so an
     *  unconflicted chain's real speed sat beside its design speed for
     *  good - 1e-6 m/s with friction, up to 0.15 on the user's lift -
     *  and "Set Design to Real" could not land on the real speed's
     *  bits: the reconcile chains sections on the DESIGN end speed.
     *  ONE FLOW (review round 2): the march is RE-SEEDED on the last
     *  sample of every recorded stretch - it continues FROM the
     *  design speed there - so the recorded real speed never steps
     *  where a clean stretch hands over to one that is not (a tilted
     *  follower, a smoothing window, a bridge, a conflicted section).
     *  A first build recorded the design speed but let the march run
     *  on beside it: the record stepped by their drift at every such
     *  hand-over, the badge and the resolve op read opposite sides of
     *  the step, and behind a held control the step was a speed
     *  conflict nothing could close. THE RE-SEED IS CAUSAL, per run:
     *  it happens only where the two CAN have drifted - friction is
     *  on, or a control governed a step of THIS run upstream. Where
     *  neither holds they agree to rounding, the march keeps its
     *  historic bits, no design speed moves and the GEOMETRY is
     *  bitwise what it was (a coaster-wide switch let the first
     *  control on a DETACHED piece re-round every other run). And
     *  never from a STALLED design sample: the recorded design speed
     *  is clamped at 0 while the design creeps above its energy
     *  height, so seeding from it would forget the deficit and hand
     *  the descent more speed than the energy allows. With friction
     *  or controls a document can re-settle ONCE on first open -
     *  where a section had been anchored on the old march's seam
     *  speed (see D255 section 11). null = the historic recording. */
    public static double[] passD(List<FVDIntegrator.Sample> samples,
                                 List<double[]> runs,
                                 double fricRoll, double fricAir,
                                 double[][] ctl, double[] hy,
                                 List<double[]> clean) {
        double[] v = new double[samples.size()];
        boolean fOn = fricRoll != 0 || fricAir != 0;
        int ci = 0;               // the next clean row
        int cFrom = 0, cTo = -1;  // the clean stretch being recorded
        for (int r = 0; r < runs.size(); r++) {
            int i0 = (int) runs.get(r)[0];
            int i1 = r + 1 < runs.size()
                ? (int) runs.get(r + 1)[0] : samples.size();
            if (i0 >= i1) continue;
            double v0 = runs.get(r)[1];
            double y0 = hy != null ? hy[i0] : samples.get(i0).pos().y();
            double loss = 0;
            // has a control governed a step of THIS run yet?
            boolean ctlSeen = false;
            for (int i = i0; i < i1; i++) {
                double vh = Math.sqrt(Math.max(v0 * v0
                    + 2 * FVDIntegrator.G0 * (y0
                        - (hy != null ? hy[i] : samples.get(i).pos().y()))
                    - loss, 0));
                // what this sample RECORDS: the design speed inside a
                // clean stretch entered at its design speed, the march
                // everywhere else
                boolean inside = i >= cFrom && i <= cTo;
                // the stretch ends HERE: the march takes over from the
                // design speed (one flow)
                boolean handOver = inside && i == cTo;
                double rec = inside ? samples.get(i).v() : vh;
                if (clean != null) {
                    // rows whose entry lies behind the march belong
                    // to spans it has passed
                    while (ci < clean.size()
                            && (int) clean.get(ci)[0] < i) ci++;
                    while (ci < clean.size()
                            && (int) clean.get(ci)[0] == i) {
                        double[] cr = clean.get(ci++);
                        int a = (int) cr[1];
                        int b = Math.min((int) cr[2], i1 - 1);
                        if (a < i || a > b) continue;
                        boolean bridged = cr.length > 4 && cr[4] != 0;
                        if (bridged
                                ? !(Math.abs(rec - cr[3])
                                    <= BRIDGE_ENTRY_TOL)
                                : Double.doubleToLongBits(rec)
                                  != Double.doubleToLongBits(cr[3])) {
                            continue;
                        }
                        cFrom = a;
                        cTo = b;
                        // behind a bridge the seam records the design
                        // speed it met within the tolerance
                        if (bridged) rec = cr[3];
                        // a run head's span starts ON its entry sample
                        if (a == i) {
                            rec = samples.get(i).v();
                            handOver = b == i;
                        }
                    }
                }
                if (handOver && (fOn || ctlSeen)
                        && samples.get(i).v() > 0) {
                    vh = samples.get(i).v();
                    loss = v0 * v0 + 2 * FVDIntegrator.G0 * (y0
                        - (hy != null ? hy[i] : samples.get(i).pos().y()))
                        - vh * vh;
                }
                // recorded HONEST since v18.6 (D175 — the user's
                // "speed becomes piecewise near zero"): the old
                // max(0.1, vh) parked every slow dip at exactly
                // 0.1 m/s on the resulting graph. The march below
                // always used the raw vh; consumers that divide or
                // seed design speeds floor at THEIR sites, and the
                // viewport's stall predicate (≤ 0.1) reads the same
                // ranges either way.
                v[i] = rec;
                double[] row = ctl == null ? null : ctl[i];
                if (row != null && i + 1 < i1) ctlSeen = true;
                double drv = SpeedControl.driveRow(row, vh);
                if (!Double.isNaN(drv) && i + 1 < i1) {
                    // driven interval: loss' is the constant ∓2gA —
                    // exact over the sample arc, friction absorbed
                    loss += drv * (samples.get(i + 1).s()
                        - samples.get(i).s());
                } else if (fOn && i + 1 < i1) {
                    double ds = samples.get(i + 1).s() - samples.get(i).s();
                    double f0 = 2 * (fricRoll * FVDIntegrator.G0
                        + fricAir * vh * vh);
                    double vp2 = Math.max(v0 * v0 + 2 * FVDIntegrator.G0
                        * (y0 - (hy != null ? hy[i + 1]
                                 : samples.get(i + 1).pos().y()))
                        - (loss + f0 * ds), 0);
                    double f1 = 2 * (fricRoll * FVDIntegrator.G0
                        + fricAir * vp2);
                    loss += (f0 + f1) / 2 * ds;
                }
                if (row != null && i + 1 < i1) {
                    double vA = Math.sqrt(Math.max(v0 * v0
                        + 2 * FVDIntegrator.G0
                        * (y0 - (hy != null ? hy[i + 1]
                                 : samples.get(i + 1).pos().y()))
                        - loss, 0));
                    double dl = SpeedControl.snapRow(row, vh, vA,
                        !Double.isNaN(drv));
                    if (dl != 0) loss += dl;
                }
            }
        }
        return v;
    }

    /** Pass A: resulting body rates from the geometry. */
    public static double[][] passA(List<FVDIntegrator.Sample> samples) {
        return FVDIntegrator.bodyRates(samples);
    }

    /** Pass A, differenced WITHIN each run (v13.10, D84): the central
     *  differences used to reach across the GAP to a detached piece's
     *  first sample — the run-end rates jumped wild (the user's
     *  "graphs jump around at the very end"). Bridged seams are one
     *  continuous body and still difference straight through.
     *  (Probe/legacy surface — compile() segments per SPAN since
     *  v18.35, the int[] overload below.) */
    public static double[][] passA(List<FVDIntegrator.Sample> samples,
                                   List<double[]> runs) {
        int[] starts = new int[runs.size()];
        for (int r = 0; r < runs.size(); r++) starts[r] = (int) runs.get(r)[0];
        return passA(samples, starts);
    }

    /** Pass A, differenced WITHIN each segment (v18.35, D207 — the
     *  segments are the section SPANS now, was per run): a rate
     *  discontinuity at a seam used to smear across the ±1-sample
     *  windows that straddled it, and every consumer trimmed a
     *  SEAM_TRIM band around each boundary to hide it — the user's
     *  "buffer space" between sections' graphs. One-sided windows at
     *  segment edges keep every sample honest: a boundary sample's
     *  rate is its OWN section's, exact to the sample spacing. A
     *  single-section document is bitwise-identical (one segment ==
     *  the old whole-array call); smooth seams (bridges included)
     *  change only by the O(dt·slope) one-sided-vs-central dust. */
    public static double[][] passA(List<FVDIntegrator.Sample> samples,
                                   int[] segStarts) {
        double[][] out = new double[samples.size()][4];
        for (int r = 0; r < segStarts.length; r++) {
            int i0 = segStarts[r];
            int i1 = r + 1 < segStarts.length ? segStarts[r + 1]
                                              : samples.size();
            if (i0 >= i1) continue;
            double[][] part = FVDIntegrator.bodyRates(
                samples.subList(i0, i1));
            for (int i = 0; i < part.length; i++) out[i0 + i] = part[i];
        }
        return out;
    }

    /** Pass A extension (round A, §5.1): TRUE felt G per sample. No
     *  differencing — the recorded design felt already encodes the
     *  geometry exactly: κ_U = g(Gv_d − U.y)/v_d², so at the true
     *  speed Gv_true = (v_t²/v_d²)(Gv_d − U.y) + U.y (Gl against R.y
     *  alike). Holds for every mode — force, geo, s/c and bridge all
     *  record felt at their design-speed profile. Rows {GvT, GlT}. */
    public static double[][] trueFelt(List<FVDIntegrator.Sample> samples,
                                      double[] trueV) {
        double[][] out = new double[samples.size()][2];
        for (int i = 0; i < samples.size(); i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            double vd = Math.max(0.1, sm.v());
            double r2 = (trueV[i] * trueV[i]) / (vd * vd);
            out[i][0] = r2 * (sm.Gv() - sm.U().y()) + sm.U().y();
            out[i][1] = r2 * (sm.Gl() + sm.R().y()) - sm.R().y();   // D184
        }
        return out;
    }

    /** TRUE body rates: the design rates scaled onto the true clock
     *  (ω = κ·v, so rates scale by v_t/v_d). Same {t, pitch, yaw,
     *  roll} °/s layout as rates — drop-in for the overlay code. */
    public static double[][] trueRates(List<FVDIntegrator.Sample> samples,
                                       double[][] rates, double[] trueV) {
        double[][] out = new double[rates.length][4];
        for (int i = 0; i < rates.length; i++) {
            double r = trueV[i] / Math.max(0.1, samples.get(i).v());
            out[i][0] = rates[i][0];
            out[i][1] = rates[i][1] * r;
            out[i][2] = rates[i][2] * r;
            out[i][3] = rates[i][3] * r;
        }
        return out;
    }
}
