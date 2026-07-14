package coaster;

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
 *       (friction and speed regions join in Phase 3).
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
                         List<Span> spans) {}

    /** One section's slice of the assembled ride (v11.0): sample index
     *  range [i0..i1] plus its global time/arc-length extent, plus the
     *  section's EXACT exit pose (v12.1 — the chain's own quaternion,
     *  not a frame round-trip: splitting a piece at this boundary
     *  copies it into the new piece's baseOrient, which is what makes
     *  splits bitwise at ANY boundary). The graph
     *  editor lays out DISTANCE-domain strips from these (a
     *  straight/curve section's DURATION exists only after compilation)
     *  and maps element arc lengths onto the shared time axis. Order
     *  matches the flattened piece/section iteration. */
    public record Span(int i0, int i1, double t0, double t1,
                       double s0, double sLen, TrackPose exit) {}

    /** One section's geometry + the pose it hands the next section. */
    public record Centerline(List<FVDIntegrator.Sample> samples, TrackPose exit) {}

    public static Result compile(Coaster c, double dt) {
        List<FVDIntegrator.Sample> all = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        // Pass D's run table (§5.1, round A): a RUN starts at every
        // piece not preceded by a trailing bridge; rows are
        // {first sample index, entry speed = first section's vDesign}
        // (run 0's equals v0 through the Start-speed coupling).
        List<double[]> runs = new ArrayList<>();
        boolean prevEndsBridge = false;
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece piece = c.pieces.get(p);
            // RIGID TILT placement (v15.2, D100): sections integrate
            // at the DESIGN orientation (genPose — gravity solves
            // there) and the piece's pitch/roll deltas rotate the
            // finished geometry rigidly about the entry point. tilt()
            // returns the IDENTITY object for untilted pieces, so the
            // historic path below is bitwise-untouched.
            Quat tilt = piece.tilt();
            TrackPose pose = tilt == Quat.IDENTITY
                ? piece.entryPose() : piece.genPose();
            Vec3 anchor = pose.pos();
            int pieceStart = all.size();
            boolean bridgedIn = prevEndsBridge;
            if (!piece.sections.isEmpty() && !bridgedIn) {
                runs.add(new double[]{ pieceStart,
                    piece.sections.get(0).vDesign() });
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
            // differenced across dt = 0; v13.10 D84)
            boolean continuation = bridgedIn;
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
                    bs.solvedEntry = new TrackPose(worldPose.pos(),
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
                                pieceHead(next, dt);
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
                }
                Centerline line = passG(section, pose, dt);
                // chain the NEXT section in design space; the world
                // gets the rigidly tilted copy (identity = no-op).
                // Bridges are world-anchored already and never tilt.
                pose = line.exit();
                if (tilt != Quat.IDENTITY
                        && !(section instanceof BridgeSection)) {
                    line = tiltLine(line, tilt, anchor);
                }
                int i0 = all.size();
                double t0 = all.isEmpty() ? 0 : all.get(all.size() - 1).t();
                double s0 = all.isEmpty() ? 0 : all.get(all.size() - 1).s();
                appendRebased(all, line.samples(), continuation);
                FVDIntegrator.Sample end = all.get(all.size() - 1);
                spans.add(new Span(i0, all.size() - 1, t0, end.t(),
                    s0, end.s() - s0, line.exit()));
                continuation = true;
            }
        }
        double[] trueV = passD(all, runs);
        double[][] rates = passA(all, runs);
        return new Result(all, trueV, rates,
            trueFelt(all, trueV), trueRates(all, rates, trueV), spans);
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
     *  CANONICAL frame and hand it to reverseAndPlace. */
    public static Centerline passG(Section section, TrackPose entry, double dt) {
        if (section instanceof ForceSection fs) {
            if (fs.influenced()) return influencedLane(fs, entry, dt, false);
            return forceLane(fs, entry, dt);
        }
        if (section instanceof GeoSection gs) {
            if (gs.influenced()) return influencedLane(gs, entry, dt, true);
            return geoLane(gs, entry, dt);
        }
        if (section instanceof StraightCurveSection sc) {
            // straight/curve (v11.0): same quaternion-native pattern as
            // geo; reversal generates canonically and yaw-places (D67)
            if (sc.reversed) {
                ScIntegrator.Result rr = ScIntegrator.integrate(sc, dt,
                    sc.vDesign, new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY);
                return reverseAndPlace(rr.samples(), entry);
            }
            ScIntegrator.Result r = ScIntegrator.integrate(
                sc, dt, sc.vDesign, entry.pos(), entry.orient());
            FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
            TrackPose exit = new TrackPose(last.pos(), r.qEnd(),
                entry.s0() + last.s(), entry.t0() + last.t());
            return new Centerline(r.samples(), exit);
        }
        if (section instanceof BridgeSection bs) {
            // bridge (v13.0, D72): FROZEN world-anchored geometry — the
            // incoming entry pose is deliberately IGNORED (§4: a bridge
            // never chases its endpoints; the editor shows dirty when
            // they drift). Exit = the solved target pose verbatim.
            BridgeSection.Result r = bs.integrate(dt);
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
            sm.Gl() - sm.R().y() + R.y());
    }

    static TrackPose tiltPose(TrackPose p, Quat tilt, Vec3 anchor) {
        return new TrackPose(
            tilt.rotate(p.pos().sub(anchor)).add(anchor),
            tilt.mul(p.orient()), p.s0(), p.t0());
    }

    /** A piece's FIRST section as it really starts — generation at the
     *  design orientation with the rigid tilt applied (the bridge
     *  target-head capture reads this; identity tilt = the historic
     *  call bitwise). */
    static List<FVDIntegrator.Sample> pieceHead(TrackPiece piece, double dt) {
        Quat tilt = piece.tilt();
        if (tilt == Quat.IDENTITY) {
            return passG(piece.sections.get(0), piece.entryPose(), dt)
                .samples();
        }
        TrackPose gen = piece.genPose();
        Centerline line = passG(piece.sections.get(0), gen, dt);
        return tiltLine(line, tilt, gen.pos()).samples();
    }

    /** The plain FORCE path (extracted v15.0 — the influence fast path
     *  reuses it verbatim; zero added arithmetic on the D50 gate).
     *  DISTANCE-domain sections (v16.0, D103) bake onto an s-grid and
     *  take the arc-terminated integrator — same physics, other
     *  parameterization. */
    private static Centerline forceLane(ForceSection fs, TrackPose entry, double dt) {
        boolean dist = fs.domain() == Section.Domain.DISTANCE;
        // 0.05 = the bake grid in the lane's own axis units (s or m) —
        // the same density relative to the axis either way
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(fs.lanes, 0.05);
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = dt;
        opts.v0 = fs.vDesign;
        opts.rollGlobal = fs.rollGlobal;   // v16.1, D107
        opts.rollPerMeter = fs.ratePerMeter;   // v16.6, D115
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
    private static Centerline geoLane(GeoSection gs, TrackPose entry, double dt) {
        boolean dist = gs.domain() == Section.Domain.DISTANCE;
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(gs.lanes, 0.05);
        if (gs.reversed) {
            GeoIntegrator.Result rr = dist
                ? GeoIntegrator.integrateDist(kfs, dt, gs.vDesign,
                    new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY,
                    gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter)
                : GeoIntegrator.integrate(kfs, dt,
                    gs.vDesign, new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY,
                    gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter);
            return reverseAndPlace(rr.samples(), entry);
        }
        // quaternion-native (the §2 amendment): the exit orientation
        // is the integrated q itself, not a frame round-trip
        GeoIntegrator.Result r = dist
            ? GeoIntegrator.integrateDist(kfs, dt, gs.vDesign,
                entry.pos(), entry.orient(),
                gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter)
            : GeoIntegrator.integrate(kfs, dt, gs.vDesign,
                entry.pos(), entry.orient(),
                gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter);
        FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
        TrackPose exit = new TrackPose(last.pos(), r.qEnd(),
            entry.s0() + last.s(), entry.t0() + last.t());
        return new Centerline(r.samples(), exit);
    }

    /** INFLUENCE two-pass (v15.0, D98; SECTIONS-DESIGN §5.4): the
     *  section's RESULTING other-mode graphs become base + delta.
     *
     *    ref     = integrate(authored lanes, vAuthored, entry) —
     *              the section's old shape, fresh every compile (the
     *              base can never feed on its own output)
     *    v*(s)   = energy from the OPERATING vDesign over ref's
     *              heights; t* = ∫ds/v* (trapezoid, the convert
     *              re-time recipe)
     *    base    = the other mode's quantities along ref at v*,
     *              ANALYTIC per sample (rates ω = g(G−axis.y)/v
     *              scaled by v* / v_d — κ is fixed; felt via the
     *              §5.1 identity; roll = the authored bake at the
     *              same scale) — exact at the edges, no differencing
     *    result  = integrate(base + delta curves, vDesign, entry) as
     *              the OTHER mode (force section → GeoIntegrator,
     *              geo section → FVDIntegrator), so the resulting
     *              graphs equal base + delta BY CONSTRUCTION and the
     *              felt records stay honest at the operating profile.
     *
     *  FAST PATH (do-no-harm, gated bitwise): all-zero deltas at
     *  vDesign == vAuthored take the plain single-pass route — a
     *  freshly-entered influence moves nothing; discarding restores
     *  the un-influenced document exactly. Reversed sections run
     *  both passes CANONICALLY and reverseAndPlace the result, like
     *  every lane mode (influence editing is refused on them
     *  UI-side; the geometry stays well-defined under piece
     *  reversal). Shape is vDesign-invariant by construction (rates
     *  at v* retrace the same spatial curve — only the clock moves),
     *  which is what lets the operating speed reconcile without
     *  reshaping. */
    static Centerline influencedLane(LaneSection ls, TrackPose entry,
                                     double dt, boolean isGeo) {
        // GLOBAL-AXIS sections take influence since v16.5 (D112): the
        // ref runs under the section's OWN flags, the base rows come
        // out in the INFLUENCE flags' semantics (worldRates + the
        // transport term convert between roll representations), and
        // pass 2 integrates under the influence flags — any section ×
        // influence orientation combination compiles.
        // DISTANCE sections run the two-pass over ARC (v16.1, D107 —
        // the v16.0 mutual exclusion lifted): base rows and delta
        // lanes key by section-local METERS, and no re-time exists at
        // all (arc is speed-invariant — the operating profile only
        // moves the values, never the axis).
        // PER-METER sections influence too since v16.7 (D117): the
        // base rows and the delta lanes speak °/m — per-meter rates
        // are speed-INVARIANT (angle per meter of a fixed shape), so
        // the operating-speed re-expression is the identity there —
        // and pass 2 integrates under the perMeter flag.
        boolean distAxis = ls.domain() == Section.Domain.DISTANCE;
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(ls.lanes, 0.05);
        if (ls.deltasAllZero() && ls.vDesign == ls.vAuthored) {
            Centerline line = isGeo ? geoLane((GeoSection) ls, entry, dt)
                                    : forceLane((ForceSection) ls, entry, dt);
            // base guides off the very samples on show (identity
            // re-time); reversed strips never edit influence — skip
            ls.baseCurves = ls.reversed ? null
                : buildBase(line.samples(), kfs, ls, isGeo, null, null,
                    distAxis);
            return line;
        }
        // REF at the AUTHORED speed (canonical frame when reversed,
        // the D67 rule), under the section's OWN axis flags
        Vec3 pos0 = ls.reversed ? new Vec3(0, entry.pos().y(), 0) : entry.pos();
        Quat q0 = ls.reversed ? Quat.IDENTITY : entry.orient();
        List<FVDIntegrator.Sample> ref;
        if (isGeo) {
            ref = distAxis
                ? GeoIntegrator.integrateDist(kfs, dt, ls.vAuthored, pos0, q0,
                    ls.rollGlobal, ls.yawPitchGlobal, ls.ratePerMeter)
                    .samples()
                : GeoIntegrator.integrate(kfs, dt, ls.vAuthored, pos0, q0,
                    ls.rollGlobal, ls.yawPitchGlobal, ls.ratePerMeter)
                    .samples();
        } else {
            FVDIntegrator.Options opts = new FVDIntegrator.Options();
            opts.dt = dt;
            opts.v0 = ls.vAuthored;
            opts.pos0 = pos0;
            opts.rollGlobal = ls.rollGlobal;
            opts.rollPerMeter = ls.ratePerMeter;
            if (!ls.reversed) {
                opts.startHeading = entry.T();
                opts.startUp = entry.U();
            }
            ref = distAxis ? FVDIntegrator.integrateTrackDist(kfs, opts)
                           : FVDIntegrator.integrateTrack(kfs, opts);
        }
        int n = ref.size();
        // operating profile (+ the re-timed clock for TIME sections;
        // a DISTANCE section's axis IS the arc — no re-time)
        double yEntry = ref.get(0).pos().y();
        double vApply = Math.max(0.5, ls.vDesign);
        double[] tStar = new double[n];
        double[] vStar = new double[n];
        for (int i = 0; i < n; i++) {
            vStar[i] = Math.max(0.1,
                FVDIntegrator.speedAt(ref.get(i).pos(), vApply, yEntry));
            if (i > 0) {
                double ds = ref.get(i).s() - ref.get(i - 1).s();
                tStar[i] = tStar[i - 1]
                    + ds / Math.max(0.5, (vStar[i] + vStar[i - 1]) / 2);
            }
        }
        double[][] base = buildBase(ref, kfs, ls, isGeo,
            distAxis ? null : tStar, vStar, distAxis);
        // TIME durations land ON the dt grid (the convert rounding
        // recipe — lane integration drops fractional tails); DISTANCE
        // sections Newton-land, so the arc is exact
        double dur = distAxis
            ? Math.max(0.1, ref.get(n - 1).s())
            : Math.max(dt,
                Math.round(Math.max(0.1, tStar[n - 1]) / dt) * dt)
                * (1 + 1e-9);
        // synthesized keyframes: base + the delta CURVES (bakeLane's
        // polylines with held ends — exactly what the editor draws)
        List<double[]> d0 = BezierBaker.polyline(ls.deltaLanes.get(0));
        List<double[]> d1 = BezierBaker.polyline(ls.deltaLanes.get(1));
        List<double[]> d2 = BezierBaker.polyline(ls.deltaLanes.get(2));
        List<FVDIntegrator.Keyframe> synth = new ArrayList<>(n + 1);
        for (int i = 0; i < n && base[i][0] < dur; i++) {
            if (i > 0 && base[i][0] <= base[i - 1][0] + 1e-12) continue;
            synth.add(new FVDIntegrator.Keyframe(base[i][0],
                base[i][1] + polyAt(d0, base[i][0]),
                base[i][2] + polyAt(d1, base[i][0]),
                base[i][3] + polyAt(d2, base[i][0])));
        }
        // final keyframe exactly at the end
        synth.add(new FVDIntegrator.Keyframe(dur,
            LaneSection.baseAt(base, 0, dur) + polyAt(d0, dur),
            LaneSection.baseAt(base, 1, dur) + polyAt(d1, dur),
            LaneSection.baseAt(base, 2, dur) + polyAt(d2, dur)));
        ls.baseCurves = base;
        // pass 2: the OTHER mode's integrator at the operating speed,
        // under the INFLUENCE axis flags (v16.5, D112)
        if (isGeo) {   // geo section + felt-G deltas → force integrator
            FVDIntegrator.Options o2 = new FVDIntegrator.Options();
            o2.dt = dt;
            o2.v0 = ls.vDesign;
            o2.pos0 = pos0;
            o2.rollGlobal = ls.inflRollGlobal;
            o2.rollPerMeter = ls.ratePerMeter;
            if (!ls.reversed) {
                o2.startHeading = entry.T();
                o2.startUp = entry.U();
            }
            List<FVDIntegrator.Sample> out = distAxis
                ? FVDIntegrator.integrateTrackDist(synth, o2)
                : FVDIntegrator.integrateTrack(synth, o2);
            if (ls.reversed) return reverseAndPlace(out, entry);
            FVDIntegrator.Sample last = out.get(out.size() - 1);
            return new Centerline(out, new TrackPose(last.pos(),
                Quat.fromFrame(last.T(), last.U()),
                entry.s0() + last.s(), entry.t0() + last.t()));
        }
        GeoIntegrator.Result r2 = distAxis
            ? GeoIntegrator.integrateDist(synth, dt, ls.vDesign, pos0, q0,
                ls.inflRollGlobal, ls.inflYawPitchGlobal, ls.ratePerMeter)
            : GeoIntegrator.integrate(synth, dt, ls.vDesign, pos0, q0,
                ls.inflRollGlobal, ls.inflYawPitchGlobal, ls.ratePerMeter);
        if (ls.reversed) return reverseAndPlace(r2.samples(), entry);
        FVDIntegrator.Sample last = r2.samples().get(r2.samples().size() - 1);
        return new Centerline(r2.samples(), new TrackPose(last.pos(), r2.qEnd(),
            entry.s0() + last.s(), entry.t0() + last.t()));
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
                * (sm.Gl() - sm.R().y()) / vSafe);
            // the roll lane in BOTH representations (only the needed
            // conversion runs; ψ' is roll-independent — Ω's T part
            // drops out of Ω×T)
            double rollB, bankR;
            if (ls.rollGlobal) {
                bankR = rollLane;
                double psi = Coaster.worldRates(sm, pitB, yawB, 0)[1];
                rollB = bankR + (Double.isNaN(psi) ? 0
                    : psi * sm.T().y());
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
                base[i][2] = r2 * (sm.Gl() - sm.R().y()) + sm.R().y();
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
     *  start). Friction and speed regions are Phase 3. */
    public static double[] passD(List<FVDIntegrator.Sample> samples,
                                 List<double[]> runs) {
        double[] v = new double[samples.size()];
        for (int r = 0; r < runs.size(); r++) {
            int i0 = (int) runs.get(r)[0];
            int i1 = r + 1 < runs.size()
                ? (int) runs.get(r + 1)[0] : samples.size();
            if (i0 >= i1) continue;
            double v0 = runs.get(r)[1];
            double y0 = samples.get(i0).pos().y();
            for (int i = i0; i < i1; i++) {
                double vh = Math.sqrt(Math.max(v0 * v0
                    + 2 * FVDIntegrator.G0 * (y0 - samples.get(i).pos().y()), 0));
                v[i] = Math.max(0.1, vh);
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
     *  continuous body and still difference straight through. */
    public static double[][] passA(List<FVDIntegrator.Sample> samples,
                                   List<double[]> runs) {
        double[][] out = new double[samples.size()][4];
        for (int r = 0; r < runs.size(); r++) {
            int i0 = (int) runs.get(r)[0];
            int i1 = r + 1 < runs.size() ? (int) runs.get(r + 1)[0]
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
            out[i][1] = r2 * (sm.Gl() - sm.R().y()) + sm.R().y();
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
