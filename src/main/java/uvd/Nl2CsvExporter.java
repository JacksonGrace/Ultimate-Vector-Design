package uvd;

import java.util.List;
import java.util.Locale;

/**
 * NoLimits 2 "External Track" CSV export.
 *
 * NL2 imports a TAB-separated table of track vertices, each carrying a
 * position plus an orthonormal front/left/up frame, in a RIGHT-handed
 * Y-up world. Our world as the user sees it (X right, Y up, Z into the
 * screen — the JavaFX render negates Y) is LEFT-handed, so Z is negated
 * on the way out; without the mirror every turn and every roll would come
 * out reversed in NL2. `left` is computed as up x front IN THE EXPORTED
 * coordinates (algebraically equal to mirroring -R, but immune to sign
 * slips).
 *
 * NL2 treats the imported vertices as the TRACK SPLINE (the center between
 * the rails), so the default export is our spine (heart - U*heartOffset);
 * exporting the heartline itself is offered for riders of NL2's own
 * heartline tools. The chosen line is resampled uniformly by ITS OWN arc
 * length (an offset curve is shorter/longer than the heartline through
 * hills), linearly interpolating position and frame between the dense
 * integration samples (~0.3 m apart at speed). The requested segment
 * length is rounded so the ride divides into a whole number of equal
 * segments — both endpoints land exactly, no short last piece.
 *
 * Pure Java, no JavaFX — headless-testable like the rest of the core.
 */
public final class Nl2CsvExporter {

    private Nl2CsvExporter() {}

    /** csv = file content; points = vertices written; length = arc length
     *  of the exported line (m); step = actual spacing used (m). */
    public record Result(String csv, int points, double length, double step) {}

    /** The SHARED export resampling (v18.30, D202 — the jitter fix,
     *  used by the CSV and .nl2elem writers alike): positions and
     *  frames at uniform arc steps of the CHOSEN line.
     *
     *  The old construction lerped positions and frames between the
     *  integration samples, leaving every export vertex on a CHORD of
     *  the true curve — an alternating radial sag of k*d^2/8 (~0.4 mm
     *  at 3 g / 16 m/s) that BEATS against the export spacing and
     *  reads as high-frequency curvature jitter once NL2 splines
     *  through the vertices (the user's 0.2 m report at ~935 m; the
     *  same classic FVD++ artifact). Now: the HEARTLINE interpolates
     *  as a cubic Hermite whose tangents are the samples' EXACT unit
     *  T (error O(d^4)), the frame interpolates as a SLERP of each
     *  segment's relative rotation — within one 0.01 s step the
     *  motion is near-constant-omega, so a slerp IS the step's true
     *  rotation — and a spine vertex derives as heart − offset*U
     *  from that frame. Position AND bank leave the sag regime
     *  together; frames arrive orthonormal by construction. */
    record Resampled(Vec3[] pos, Vec3[] front, Vec3[] up,
                     double length, double step) {}

    static Resampled resample(List<FVDIntegrator.Sample> samples,
                              boolean heartline, double heartOffset,
                              double segmentLength) {
        if (samples == null || samples.size() < 2)
            throw new IllegalArgumentException("need at least 2 samples to export");
        double segLen = Math.max(0.01, segmentLength);

        int n = samples.size();
        Vec3[] hp = new Vec3[n];      // heartline positions
        Vec3[] lp = new Vec3[n];      // the CHOSEN line (arc targeting)
        Quat[] q = new Quat[n];
        double[] harc = new double[n];
        double[] arc = new double[n];
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            hp[i] = sm.pos();
            lp[i] = heartline ? sm.pos() : sm.pos().sub(sm.U().scale(heartOffset));
            q[i] = Quat.fromFrame(sm.T(), sm.U());
            if (i > 0) {
                harc[i] = harc[i - 1] + hp[i].sub(hp[i - 1]).length();
                arc[i] = arc[i - 1] + lp[i].sub(lp[i - 1]).length();
            }
        }
        double total = arc[n - 1];
        if (total < 1e-9)
            throw new IllegalArgumentException("track has zero length");

        int nSeg = Math.max(1, (int) Math.round(total / segLen));
        double step = total / nSeg;
        Vec3[] pos = new Vec3[nSeg + 1];
        Vec3[] front = new Vec3[nSeg + 1];
        Vec3[] up = new Vec3[nSeg + 1];
        int idx = 0;
        for (int k = 0; k <= nSeg; k++) {
            double target = Math.min(k * step, total);
            while (idx < n - 2 && arc[idx + 1] < target) idx++;
            double span = arc[idx + 1] - arc[idx];
            double a = span < 1e-12 ? 0 : (target - arc[idx]) / span;
            // frame: slerp of the segment's relative rotation
            Quat qa = slerpSeg(q[idx], q[idx + 1], a);
            Vec3 T = qa.T(), U = qa.U();
            // heartline: cubic Hermite, tangents = the exact unit T
            // scaled by the segment's heart arc
            double hs = harc[idx + 1] - harc[idx];
            double h00 = (1 + 2 * a) * (1 - a) * (1 - a);
            double h10 = a * (1 - a) * (1 - a);
            double h01 = a * a * (3 - 2 * a);
            double h11 = a * a * (a - 1);
            Vec3 heart = hp[idx].scale(h00)
                .add(q[idx].T().scale(h10 * hs))
                .add(hp[idx + 1].scale(h01))
                .add(q[idx + 1].T().scale(h11 * hs));
            pos[k] = heartline ? heart : heart.sub(U.scale(heartOffset));
            front[k] = T;
            up[k] = U;
        }
        return new Resampled(pos, front, up, total, step);
    }

    /** slerp from qa toward qb by fraction a of their relative
     *  rotation (shortest way), staying exactly on qa/qb at 0/1. */
    private static Quat slerpSeg(Quat qa, Quat qb, double a) {
        if (a <= 0) return qa;
        if (a >= 1) return qb;
        Quat r = qb.mul(qa.conjugate());
        double w = r.w();
        Vec3 ax = new Vec3(r.x(), r.y(), r.z());
        if (w < 0) { w = -w; ax = ax.scale(-1); }   // shortest arc
        double s = ax.length();
        if (s < 1e-12) return qa;                    // no rotation
        double ang = 2 * Math.atan2(s, w);
        return Quat.aboutAxis(ax.scale(1 / s), ang * a).mul(qa).normalize();
    }

    /**
     * @param heartline     true = export the integrated heartline; false =
     *                      the rail-center spine (heart - U*heartOffset)
     * @param heartOffset   meters from heartline down to the spine (the
     *                      designer's Coaster > Heart setting)
     * @param segmentLength requested vertex spacing in meters (>= 0.01)
     */
    public static Result export(List<FVDIntegrator.Sample> samples,
                                boolean heartline, double heartOffset,
                                double segmentLength) {
        Resampled rs = resample(samples, heartline, heartOffset, segmentLength);
        int nSeg = rs.pos().length - 1;

        StringBuilder sb = new StringBuilder(64 + nSeg * 160);
        sb.append("\"No.\"\t\"PosX\"\t\"PosY\"\t\"PosZ\"\t\"FrontX\"\t\"FrontY\"\t\"FrontZ\"\t")
          .append("\"LeftX\"\t\"LeftY\"\t\"LeftZ\"\t\"UpX\"\t\"UpY\"\t\"UpZ\"\n");

        for (int k = 0; k <= nSeg; k++) {
            Vec3 pos = rs.pos()[k];
            Vec3 front = rs.front()[k];
            Vec3 up = rs.up()[k];

            // left-handed view -> NL2's right-handed Y-up world: negate Z
            Vec3 fN = new Vec3(front.x(), front.y(), -front.z());
            Vec3 uN = new Vec3(up.x(), up.y(), -up.z());
            Vec3 lN = uN.cross(fN);   // NL2 convention: up = front x left

            sb.append(String.format(Locale.US,
                "%d\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\n",
                k + 1, pos.x(), pos.y(), -pos.z(),
                fN.x(), fN.y(), fN.z(),
                lN.x(), lN.y(), lN.z(),
                uN.x(), uN.y(), uN.z()));
        }
        return new Result(sb.toString(), nSeg + 1, rs.length(), rs.step());
    }
}
