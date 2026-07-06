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
                         double[] trueV, double[][] rates) {}

    /** One section's geometry + the pose it hands the next section. */
    public record Centerline(List<FVDIntegrator.Sample> samples, TrackPose exit) {}

    public static Result compile(Coaster c, double dt) {
        List<FVDIntegrator.Sample> all = new ArrayList<>();
        for (TrackPiece piece : c.pieces) {
            TrackPose pose = piece.entryPose();
            boolean continuation = false;   // false for each piece's FIRST section
            for (Section section : piece.sections) {
                Centerline line = passG(section, pose, dt);
                appendRebased(all, line.samples(), continuation);
                pose = line.exit();
                continuation = true;
            }
        }
        double[] trueV = passD(all, c.v0);
        double[][] rates = passA(all);
        return new Result(all, trueV, rates);
    }

    /** Pass G for one section at its DESIGN speed. Force sections are
     *  the old integrator run, seeded from the entry pose. */
    public static Centerline passG(Section section, TrackPose entry, double dt) {
        if (!(section instanceof ForceSection fs))
            throw new IllegalArgumentException("Phase 1 compiles force sections only");
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(fs.lanes, 0.05);
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = dt;
        opts.v0 = fs.vDesign;
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
