package coaster;

import java.util.List;
import java.util.Locale;

/**
 * NoLimits 2 ELEMENT export (.nl2elem) — the FVD++ "NoLimits 2
 * Exporter" format (v18.29, D201): an XML element NL2's editor inserts
 * at a chosen track vertex, carrying anchor-RELATIVE vertices plus
 * roll entries (up + right vectors at normalized arc coords).
 *
 * The structure matches FVD++'s writer exactly (openFVD
 * ui/exportui.cpp + core/track.cpp::exportNL2Track): root/element/
 * description, then per-vertex x/y/z/strict blocks, then per-point
 * roll blocks with ux..uz, rx..rz, coord, strict — all values in C
 * "%e" scientific notation. Coordinates are ELEMENT-LOCAL: the anchor
 * (first exported point) subtracts out, and a pure heading rotation
 * about Y lands the entry's horizontal direction on NL2's forward
 * (−Z), leaving heights untouched — FVD++'s anchorBase transform.
 * Before that local transform our left-handed render world maps to
 * NL2's right-handed Y-up world by negating Z, the D40 convention the
 * CSV exporter gates. Roll rights are front × up, which equals
 * FVD++'s vLat (rider-right) identically.
 *
 * The exported line (heartline vs rail-center spine), the resampling
 * (uniform by the line's OWN arc, endpoints exact, whole number of
 * equal segments) and the frame interpolation mirror Nl2CsvExporter.
 * Pure Java, no JavaFX — headless-testable.
 */
public final class Nl2ElemExporter {

    private Nl2ElemExporter() {}

    /** xml = file content; points = vertices (and roll entries)
     *  written; length = arc length of the exported line (m); step =
     *  actual vertex spacing used (m). */
    public record Result(String xml, int points, double length, double step) {}

    /**
     * @param heartline     true = export the integrated heartline; false =
     *                      the rail-center spine (heart - U*heartOffset)
     * @param heartOffset   meters from heartline down to the spine
     * @param segmentLength requested vertex spacing in meters (>= 0.01)
     */
    public static Result export(List<FVDIntegrator.Sample> samples,
                                boolean heartline, double heartOffset,
                                double segmentLength) {
        // the SHARED heart-Hermite + frame-slerp resampling (v18.30,
        // D202 — see Nl2CsvExporter.resample: the old per-exporter
        // lerp left chord-sag aliasing that read as high-frequency
        // jitter in NL2)
        Nl2CsvExporter.Resampled rs =
            Nl2CsvExporter.resample(samples, heartline, heartOffset,
                segmentLength);
        Vec3[] pos = rs.pos();
        Vec3[] front = rs.front();
        Vec3[] up = rs.up();
        int nSeg = pos.length - 1;
        double total = rs.length();
        double step = rs.step();

        // left-handed render world -> NL2's right-handed Y-up (D40:
        // negate Z), THEN FVD++'s anchor transform: subtract the first
        // point, rotate about Y so the entry heading lands on -Z
        // (heights ride through untouched). Vertical entry fallback:
        // the up vector's horizontal heading (a frame can't have both
        // T and U vertical) — deterministic either way.
        Vec3 f0 = zNeg(front[0]);
        double dx = f0.x(), dz = f0.z();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-6) {
            Vec3 u0 = zNeg(up[0]);
            dx = u0.x();
            dz = u0.z();
            horiz = Math.hypot(dx, dz);
            if (horiz < 1e-6) { dx = 1; dz = 0; horiz = 1; }
        }
        dx /= horiz;
        dz /= horiz;

        Vec3 anchor = zNeg(pos[0]);
        StringBuilder sb = new StringBuilder(96 + (nSeg + 1) * 420);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
          .append("<root>\n")
          .append("\t<element>\n")
          // the display name (v19.20, D249) — a literal mirror of
          // FvdDesigner.APP_NAME (pure Java, no shell import);
          // LabelGate pins it, Nl2ElemProbe compares geometry only
          .append("\t\t<description>Ultimate Vector Design Export</description>\n");

        for (int k = 0; k <= nSeg; k++) {
            Vec3 v = rotA(zNeg(pos[k]).sub(anchor), dx, dz);
            sb.append("\t\t\t<vertex>\n");
            appendTag(sb, "x", v.x());
            appendTag(sb, "y", v.y());
            appendTag(sb, "z", v.z());
            // FVD++ marks endpoints strict (interior section-boundary
            // stricts are its bezier-segment bookkeeping; our uniform
            // resample has none)
            sb.append("\t\t\t\t<strict>")
              .append(k == 0 || k == nSeg ? "true" : "false")
              .append("</strict>\n");
            sb.append("\t\t\t</vertex>\n");
        }
        for (int k = 0; k <= nSeg; k++) {
            Vec3 ua = rotA(zNeg(up[k]), dx, dz);
            Vec3 fa = rotA(zNeg(front[k]), dx, dz);
            Vec3 ra = fa.cross(ua);   // == FVD++'s vLat (rider-right)
            double coord = (double) k / nSeg;
            sb.append("\t\t\t<roll>\n");
            appendTag(sb, "ux", ua.x());
            appendTag(sb, "uy", ua.y());
            appendTag(sb, "uz", ua.z());
            appendTag(sb, "rx", ra.x());
            appendTag(sb, "ry", ra.y());
            appendTag(sb, "rz", ra.z());
            appendTag(sb, "coord", coord);
            sb.append("\t\t\t\t<strict>false</strict>\n");
            sb.append("\t\t\t</roll>\n");
        }
        sb.append("\t</element>\n")
          .append("</root>\n");
        return new Result(sb.toString(), nSeg + 1, total, step);
    }

    private static Vec3 zNeg(Vec3 v) {
        return new Vec3(v.x(), v.y(), -v.z());
    }

    /** FVD++'s anchorBase rows applied to a vector: the pure
     *  heading-about-Y rotation taking the unit horizontal entry
     *  direction (dx, dz) onto (0, 0, -1); y rides through bitwise. */
    private static Vec3 rotA(Vec3 v, double dx, double dz) {
        return new Vec3(-dz * v.x() + dx * v.z(), v.y(),
                        -dx * v.x() - dz * v.z());
    }

    /** One "\t\t\t\t<tag>%e</tag>\n" line — C-style scientific
     *  notation, the exact FVD++ number format. */
    private static void appendTag(StringBuilder sb, String tag, double v) {
        sb.append("\t\t\t\t<").append(tag).append('>')
          .append(String.format(Locale.US, "%e", v))
          .append("</").append(tag).append(">\n");
    }
}
