package coaster;

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
        if (samples == null || samples.size() < 2)
            throw new IllegalArgumentException("need at least 2 samples to export");
        double segLen = Math.max(0.01, segmentLength);

        // the exported line + its frames, and its own cumulative arc length
        int n = samples.size();
        Vec3[] p = new Vec3[n];
        Vec3[] t = new Vec3[n];
        Vec3[] u = new Vec3[n];
        double[] arc = new double[n];
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            p[i] = heartline ? sm.pos() : sm.pos().sub(sm.U().scale(heartOffset));
            t[i] = sm.T();
            u[i] = sm.U();
            if (i > 0) arc[i] = arc[i - 1] + p[i].sub(p[i - 1]).length();
        }
        double total = arc[n - 1];
        if (total < 1e-9)
            throw new IllegalArgumentException("track has zero length");

        int nSeg = Math.max(1, (int) Math.round(total / segLen));
        double step = total / nSeg;

        StringBuilder sb = new StringBuilder(64 + nSeg * 160);
        sb.append("\"No.\"\t\"PosX\"\t\"PosY\"\t\"PosZ\"\t\"FrontX\"\t\"FrontY\"\t\"FrontZ\"\t")
          .append("\"LeftX\"\t\"LeftY\"\t\"LeftZ\"\t\"UpX\"\t\"UpY\"\t\"UpZ\"\n");

        int idx = 0;
        for (int k = 0; k <= nSeg; k++) {
            double target = Math.min(k * step, total);
            while (idx < n - 2 && arc[idx + 1] < target) idx++;
            double span = arc[idx + 1] - arc[idx];
            double a = span < 1e-12 ? 0 : (target - arc[idx]) / span;

            Vec3 pos = p[idx].add(p[idx + 1].sub(p[idx]).scale(a));
            Vec3 front = t[idx].add(t[idx + 1].sub(t[idx]).scale(a)).normalize();
            Vec3 upRaw = u[idx].add(u[idx + 1].sub(u[idx]).scale(a));
            Vec3 up = upRaw.sub(front.scale(upRaw.dot(front))).normalize();
            if (up.length() < 0.5) up = u[idx];   // degenerate lerp; can't happen at dt=0.01 spacing

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
        return new Result(sb.toString(), nSeg + 1, total, step);
    }
}
