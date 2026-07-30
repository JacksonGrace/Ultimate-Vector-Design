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
        FVDIntegrator.Friction fric = c.fric();   // Phase 3 (v17.0)
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
                TrackPose eff = Coaster.strictEntry(
                    spans.get(spans.size() - 1).exit(), piece);
                Quat gen = piece.genPose().orient();
                Quat tq = eff.orient().mul(gen.conjugate());
                tilt = Math.abs(tq.w()) > 1 - 1e-14 ? Quat.IDENTITY : tq;
                pose = new TrackPose(eff.pos(), gen);
                anchor = eff.pos();
            }
            if (!piece.sections.isEmpty() && !bridgedIn && !followed) {
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
            // differenced across dt = 0; v13.10 D84). A strict-seam
            // FOLLOWER's entry coincides the same way (v17.1).
            boolean continuation = bridgedIn || followed;
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
                Centerline line = passG(section, pose, dt, fric);
                // chain the NEXT section in design space; the world
                // gets the rigidly tilted copy (identity = no-op).
                // Bridges are world-anchored already and never tilt.
                pose = line.exit();
                if (tilt != Quat.IDENTITY
                        && !(section instanceof BridgeSection)) {
                    line = tiltLine(line, tilt, anchor);
                }
                // a smoothed seam's window-end delta translates the
                // rest of the piece (v17.2 — the design-space chain
                // stays untouched; only the world output shifts).
                // Bridges skip: their entry already shifted above.
                if (postShift != null
                        && !(section instanceof BridgeSection)) {
                    line = shiftLine(line, postShift);
                }
                int i0 = all.size();
                double t0 = all.isEmpty() ? 0 : all.get(all.size() - 1).t();
                double s0 = all.isEmpty() ? 0 : all.get(all.size() - 1).s();
                appendRebased(all, line.samples(), continuation);
                FVDIntegrator.Sample end = all.get(all.size() - 1);
                spans.add(new Span(i0, all.size() - 1, t0, end.t(),
                    s0, end.s() - s0, line.exit()));
                continuation = true;
                if (smoothSeam && secIdx == 0
                        && !(section instanceof BridgeSection)) {
                    postShift = smoothStrictSeam(all, spans,
                        piece.sSmoothLen);
                }
                secIdx++;
            }
        }
        double[] trueV = passD(all, runs, c.fricRoll, c.fricAir,
            ctlSampleRows(all, spans, c));
        double[][] rates = passA(all, runs);
        return new Result(all, trueV, rates,
            trueFelt(all, trueV), trueRates(all, rates, trueV), spans);
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
     *  friction. */
    public static Centerline passG(Section section, TrackPose entry, double dt) {
        return passG(section, entry, dt, FVDIntegrator.Friction.NONE);
    }

    public static Centerline passG(Section section, TrackPose entry, double dt,
                                   FVDIntegrator.Friction fric) {
        if (section instanceof ForceSection fs) {
            if (fs.influenced()) return influencedLane(fs, entry, dt, false, fric);
            return forceLane(fs, entry, dt, fric);
        }
        if (section instanceof GeoSection gs) {
            if (gs.influenced()) return influencedLane(gs, entry, dt, true, fric);
            return geoLane(gs, entry, dt, fric);
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
            return new Centerline(r.samples(), exit);
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

    /** A piece's FIRST section as it really starts — generation at the
     *  design orientation with the rigid tilt applied (the bridge
     *  target-head capture reads this; identity tilt = the historic
     *  call bitwise). */
    static List<FVDIntegrator.Sample> pieceHead(TrackPiece piece, double dt) {
        return pieceHead(piece, dt, FVDIntegrator.Friction.NONE);
    }

    static List<FVDIntegrator.Sample> pieceHead(TrackPiece piece, double dt,
                                                FVDIntegrator.Friction fric) {
        Quat tilt = piece.tilt();
        if (tilt == Quat.IDENTITY) {
            return passG(piece.sections.get(0), piece.entryPose(), dt, fric)
                .samples();
        }
        TrackPose gen = piece.genPose();
        Centerline line = passG(piece.sections.get(0), gen, dt, fric);
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
     *  its own measured per-sample rate, quintic-smoothstep weight over
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
    static Vec3 smoothStrictSeam(List<FVDIntegrator.Sample> all,
                                 List<Span> spans, double len) {
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
        double sA = all.get(iA).s(), sB = all.get(iB).s();
        int n = iB - iA + 1;
        Quat[] q = new Quat[n];
        double[] uu = new double[n];
        for (int i = iA; i <= iB; i++) {
            Quat up = i <= iSeam ? frameQ(all.get(i))
                : quatPow(stepUp, i - iSeam).mul(qSeam);
            Quat dn = i > iSeam ? frameQ(all.get(i))
                : quatPow(stepDn, i - iSeam - 1).mul(qDn0);
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
                xb.orient(), xb.s0(), xb.t0())));
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
     *  other half of the fix). */
    private static List<FVDIntegrator.Keyframe> exactRoll(
            List<FVDIntegrator.Keyframe> kfs, List<BezierBaker.Node> lane) {
        List<FVDIntegrator.Keyframe> out = new ArrayList<>(kfs.size());
        for (FVDIntegrator.Keyframe k : kfs) {
            out.add(new FVDIntegrator.Keyframe(k.t(), k.Gv(), k.Gl(),
                BezierBaker.exactAt(lane, k.t())));
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
        boolean dist = gs.domain() == Section.Domain.DISTANCE;
        List<FVDIntegrator.Keyframe> kfs =
            BezierBaker.bake(gs.lanes, dist ? 0.05 : 0.01);   // D151
        boolean gAbs = gs.rollAbsolute && gs.rollGlobal;   // v16.7
        if (gAbs) kfs = exactRoll(kfs, gs.lanes.get(2));
        if (gs.reversed) {
            GeoIntegrator.Result rr = dist
                ? GeoIntegrator.integrateDist(kfs, dt, gs.vDesign,
                    new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY,
                    gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                    gAbs, fric)
                : GeoIntegrator.integrate(kfs, dt,
                    gs.vDesign, new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY,
                    gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                    gAbs, fric);
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
                gAbs, fric, ctl)
            : GeoIntegrator.integrate(kfs, dt, gs.vDesign,
                entry.pos(), entry.orient(),
                gs.rollGlobal, gs.yawPitchGlobal, gs.ratePerMeter,
                gAbs, fric, ctl);
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
        return influencedLane(ls, entry, dt, isGeo,
            FVDIntegrator.Friction.NONE);
    }

    static Centerline influencedLane(LaneSection ls, TrackPose entry,
                                     double dt, boolean isGeo,
                                     FVDIntegrator.Friction fric) {
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
        // ABSOLUTE-ROLL sections take no influence (v16.7, D118 —
        // the roll channel of the two-pass is rate-shaped); the UI
        // refuses entry, hand-edited files fall back to plain.
        if (ls.rollAbsolute && ls.rollGlobal) {
            ls.baseCurves = null;
            return isGeo ? geoLane((GeoSection) ls, entry, dt, fric)
                         : forceLane((ForceSection) ls, entry, dt, fric);
        }
        // PER-METER sections influence too since v16.7 (D117): the
        // base rows and the delta lanes speak °/m — per-meter rates
        // are speed-INVARIANT (angle per meter of a fixed shape), so
        // the operating-speed re-expression is the identity there —
        // and pass 2 integrates under the perMeter flag.
        boolean distAxis = ls.domain() == Section.Domain.DISTANCE;
        List<FVDIntegrator.Keyframe> kfs =
            BezierBaker.bake(ls.lanes, distAxis ? 0.05 : 0.01);   // D151
        if (ls.deltasAllZero() && ls.vDesign == ls.vAuthored) {
            Centerline line = isGeo ? geoLane((GeoSection) ls, entry, dt, fric)
                                    : forceLane((ForceSection) ls, entry, dt, fric);
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
                    ls.rollGlobal, ls.yawPitchGlobal, ls.ratePerMeter, false,
                    fric)
                    .samples()
                : GeoIntegrator.integrate(kfs, dt, ls.vAuthored, pos0, q0,
                    ls.rollGlobal, ls.yawPitchGlobal, ls.ratePerMeter, false,
                    fric)
                    .samples();
        } else {
            FVDIntegrator.Options opts = new FVDIntegrator.Options();
            opts.dt = dt;
            opts.v0 = ls.vAuthored;
            opts.pos0 = pos0;
            opts.fric = fric;
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
        // a DISTANCE section's axis IS the arc — no re-time). The
        // profile carries its OWN friction deficit over ref's arc
        // (v17.0 — Heun march; skipped entirely when loss-free).
        double yEntry = ref.get(0).pos().y();
        double vApply = Math.max(0.5, ls.vDesign);
        double[] tStar = new double[n];
        double[] vStar = new double[n];
        double lossStar = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && !fric.off()) {
                double dsF = ref.get(i).s() - ref.get(i - 1).s();
                double f0 = fric.dLossDs(vStar[i - 1]);
                double vp = Math.max(0.5, FVDIntegrator.speedAt(
                    ref.get(i).pos(), vApply, yEntry, lossStar + f0 * dsF));
                lossStar += (f0 + fric.dLossDs(vp)) / 2 * dsF;
            }
            vStar[i] = Math.max(0.1,
                FVDIntegrator.speedAt(ref.get(i).pos(), vApply, yEntry,
                    lossStar));
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
            o2.fric = fric;
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
                ls.inflRollGlobal, ls.inflYawPitchGlobal, ls.ratePerMeter,
                false, fric)
            : GeoIntegrator.integrate(synth, dt, ls.vDesign, pos0, q0,
                ls.inflRollGlobal, ls.inflYawPitchGlobal, ls.ratePerMeter,
                false, fric);
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
        double[] v = new double[samples.size()];
        boolean fOn = fricRoll != 0 || fricAir != 0;
        for (int r = 0; r < runs.size(); r++) {
            int i0 = (int) runs.get(r)[0];
            int i1 = r + 1 < runs.size()
                ? (int) runs.get(r + 1)[0] : samples.size();
            if (i0 >= i1) continue;
            double v0 = runs.get(r)[1];
            double y0 = samples.get(i0).pos().y();
            double loss = 0;
            for (int i = i0; i < i1; i++) {
                double vh = Math.sqrt(Math.max(v0 * v0
                    + 2 * FVDIntegrator.G0 * (y0 - samples.get(i).pos().y())
                    - loss, 0));
                // recorded HONEST since v18.6 (D175 — the user's
                // "speed becomes piecewise near zero"): the old
                // max(0.1, vh) parked every slow dip at exactly
                // 0.1 m/s on the resulting graph. The march below
                // always used the raw vh; consumers that divide or
                // seed design speeds floor at THEIR sites, and the
                // viewport's stall predicate (≤ 0.1) reads the same
                // ranges either way.
                v[i] = vh;
                double[] row = ctl == null ? null : ctl[i];
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
                        * (y0 - samples.get(i + 1).pos().y())
                        - (loss + f0 * ds), 0);
                    double f1 = 2 * (fricRoll * FVDIntegrator.G0
                        + fricAir * vp2);
                    loss += (f0 + f1) / 2 * ds;
                }
                if (row != null && i + 1 < i1) {
                    double vA = Math.sqrt(Math.max(v0 * v0
                        + 2 * FVDIntegrator.G0
                        * (y0 - samples.get(i + 1).pos().y()) - loss, 0));
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
