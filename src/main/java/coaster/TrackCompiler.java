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
     *  trueV duplicates Sample.v while every section runs at its design
     *  speed (single-section today) — it EXISTS so Phase 3 has the
     *  seam to diverge them. */
    public record Result(List<FVDIntegrator.Sample> samples,
                         double[] trueV, double[][] rates, List<Span> spans) {}

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
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece piece = c.pieces.get(p);
            TrackPose pose = piece.entryPose();
            int pieceStart = all.size();
            boolean continuation = false;   // false for each piece's FIRST section
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
                    bs.solvedEntry = new TrackPose(pose.pos(), pose.orient());
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
                            // agnostic (reversed sections included)
                            List<FVDIntegrator.Sample> head =
                                passG(next.sections.get(0), tgt, dt).samples();
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
                int i0 = all.size();
                double t0 = all.isEmpty() ? 0 : all.get(all.size() - 1).t();
                double s0 = all.isEmpty() ? 0 : all.get(all.size() - 1).s();
                appendRebased(all, line.samples(), continuation);
                FVDIntegrator.Sample end = all.get(all.size() - 1);
                pose = line.exit();
                spans.add(new Span(i0, all.size() - 1, t0, end.t(),
                    s0, end.s() - s0, pose));
                continuation = true;
            }
        }
        double[] trueV = passD(all, c.v0);
        double[][] rates = passA(all);
        return new Result(all, trueV, rates, spans);
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
            List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(fs.lanes, 0.05);
            FVDIntegrator.Options opts = new FVDIntegrator.Options();
            opts.dt = dt;
            opts.v0 = fs.vDesign;
            if (fs.reversed) {
                // canonical frame at the ENTRY HEIGHT: energy is
                // height-relative but float rounding is not — this keeps
                // the reversed shape bitwise equal to a forward run at
                // the same height (gated)
                opts.pos0 = new Vec3(0, entry.pos().y(), 0);
                return reverseAndPlace(FVDIntegrator.integrateTrack(kfs, opts), entry);
            }
            opts.pos0 = entry.pos();
            opts.startHeading = entry.T();   // identity orientation: exactly (1,0,0)
            opts.startUp = entry.U();        //                       exactly (0,1,0)
            List<FVDIntegrator.Sample> samples = FVDIntegrator.integrateTrack(kfs, opts);
            FVDIntegrator.Sample last = samples.get(samples.size() - 1);
            TrackPose exit = new TrackPose(last.pos(),
                Quat.fromFrame(last.T(), last.U()),
                entry.s0() + last.s(), entry.t0() + last.t());
            return new Centerline(samples, exit);
        }
        if (section instanceof GeoSection gs) {
            List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(gs.lanes, 0.05);
            if (gs.reversed) {
                GeoIntegrator.Result rr = GeoIntegrator.integrate(kfs, dt,
                    gs.vDesign, new Vec3(0, entry.pos().y(), 0), Quat.IDENTITY);
                return reverseAndPlace(rr.samples(), entry);
            }
            // quaternion-native (the §2 amendment): the exit orientation
            // is the integrated q itself, not a frame round-trip
            GeoIntegrator.Result r = GeoIntegrator.integrate(
                kfs, dt, gs.vDesign, entry.pos(), entry.orient());
            FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
            TrackPose exit = new TrackPose(last.pos(), r.qEnd(),
                entry.s0() + last.s(), entry.t0() + last.t());
            return new Centerline(r.samples(), exit);
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

    /** Pass D: true speed along the assembled track from energy vs the
     *  global start height — the same formula the integrator records,
     *  so with one section at vDesign == v0 this reproduces Sample.v
     *  exactly. Friction and speed regions are Phase 3. */
    public static double[] passD(List<FVDIntegrator.Sample> samples, double v0) {
        double[] v = new double[samples.size()];
        if (samples.isEmpty()) return v;
        double y0 = samples.get(0).pos().y();
        for (int i = 0; i < samples.size(); i++) {
            double vh = Math.sqrt(Math.max(
                v0 * v0 + 2 * FVDIntegrator.G0 * (y0 - samples.get(i).pos().y()), 0));
            v[i] = Math.max(0.1, vh);
        }
        return v;
    }

    /** Pass A: resulting body rates from the geometry (true forces join
     *  when true speed can differ from design speed). */
    public static double[][] passA(List<FVDIntegrator.Sample> samples) {
        return FVDIntegrator.bodyRates(samples);
    }
}
