package coaster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Save/load for coaster documents (Phase 1, D50) — extracted from
 * FvdDesigner so round-trip tests exercise the REAL production code
 * (TESTING.md #4's long-standing wish). Pure Java, no JavaFX: colors
 * travel as double triples in {@link Cosmetics}.
 *
 * WRITES FVDD3: hierarchical text — global lines, then piece blocks
 * containing section blocks containing lanes. READS FVDD3, FVDD2 and
 * FVDD1; the legacy flat formats load as one piece at the file's
 * `start` (default (0,21,0), matching what a fresh session's spinners
 * did to such files) holding one force section whose vDesign is the
 * file's v0 (default 26). FVDD1 handle time-offsets (absolute seconds,
 * negative in-handle) auto-convert to segment fractions, as before.
 *
 * Format rules carried forward from FVDD2 (D14/D40-era decisions):
 * Locale.US decimals ALWAYS (comma-decimal systems corrupted saves
 * once); unknown LINES are ignored (forward compatibility); extra
 * FIELDS on known lines are ignored (the straight-lock 8th n-field
 * rides on this both ways).
 */
public final class DesignIO {

    private DesignIO() {}

    // gradient stop range — keep in sync with FvdDesigner's G_MIN/G_MAX
    private static final double GRAD_G_MIN = -1.0, GRAD_G_MAX = 5.0;

    /** Render-only per-coaster settings (UI-layer state until the
     *  milestone-B split moves them into the document). Colors are
     *  r,g,b in 0..1: railColor has 3 entries, gradColors 12 (four
     *  stops), gradStops 4 G-values. */
    public record Cosmetics(double heart, double gauge, double tiespace,
                            boolean railSolid, double[] railColor,
                            double[] gradColors, double[] gradStops) {

        public static Cosmetics defaults() {
            return new Cosmetics(1.0, 1.0, 1.0, false,
                new double[]{ 220 / 255.0, 60 / 255.0, 50 / 255.0 },
                new double[]{ 0.2, 0.4, 1.0,  0.2, 0.9, 0.3,
                              1.0, 0.9, 0.1,  1.0, 0.15, 0.1 },
                new double[]{ 0, 1, 3, 5 });
        }
    }

    public record LoadResult(Coaster coaster, Cosmetics cosmetics) {}

    // =====================================================================
    // Save (FVDD3 only)
    // =====================================================================

    public static void save(Coaster c, Cosmetics cos, PrintWriter out) {
        out.println("FVDD3");
        out.printf(Locale.US, "v0 %.4f%n", c.v0);
        out.printf(Locale.US, "heart %.4f%n", cos.heart());
        out.printf(Locale.US, "gauge %.4f%n", cos.gauge());
        out.printf(Locale.US, "tiespace %.4f%n", cos.tiespace());
        out.printf(Locale.US, "railmode %s%n", cos.railSolid() ? "solid" : "gradient");
        out.printf(Locale.US, "railcolor %.4f %.4f %.4f%n",
            cos.railColor()[0], cos.railColor()[1], cos.railColor()[2]);
        StringBuilder gc = new StringBuilder("gradcolors");
        for (double v : cos.gradColors()) gc.append(String.format(Locale.US, " %.4f", v));
        out.println(gc);
        out.printf(Locale.US, "gradstops %.4f %.4f %.4f %.4f%n",
            cos.gradStops()[0], cos.gradStops()[1], cos.gradStops()[2], cos.gradStops()[3]);
        for (Coaster.SpeedRegion r : c.speedRegions) {
            out.printf(Locale.US, "speedregion %.4f %.4f %s %.4f%n",
                r.s0(), r.length(), r.mode(), r.value());
        }
        for (TrackPiece piece : c.pieces) {
            out.printf(Locale.US, "piece %.4f %.4f %.4f %.4f%n",
                piece.x, piece.y, piece.z, piece.yawDeg);
            for (Section section : piece.sections) {
                LaneSection fs = (LaneSection) section;   // force | geo (Phase 2)
                out.printf(Locale.US, "section %s %.4f %s %d%n",
                    fs instanceof GeoSection ? "geo" : "force",
                    fs.vDesign,
                    fs.domain() == Section.Domain.TIME ? "time" : "distance",
                    fs.reversed ? 1 : 0);
                for (int lane = 0; lane < 3; lane++) {
                    out.println("lane " + lane);
                    for (BezierBaker.Node n : fs.lanes.get(lane)) {
                        // 8th field straight-lock, 9th level-lock — both
                        // ride the ignore-extra-fields rule for old readers
                        out.printf(Locale.US, "n %.4f %.4f %.4f %.4f %.4f %.4f %d %d %d%n",
                            n.t, n.val, n.outFrac, n.outDv, n.inFrac, n.inDv,
                            n.smooth ? 1 : 0, n.straightOut ? 1 : 0,
                            n.levelOut ? 1 : 0);
                    }
                }
                out.println("endsection");
            }
            out.println("endpiece");
        }
    }

    // =====================================================================
    // Load (FVDD3 / FVDD2 / FVDD1)
    // =====================================================================

    public static LoadResult load(BufferedReader in) throws IOException {
        String header = in.readLine();
        if (header == null || !header.startsWith("FVDD"))
            throw new IOException("not an FVD design file");
        boolean legacy1 = header.trim().equals("FVDD1");
        boolean v3 = header.trim().equals("FVDD3");

        Coaster c = new Coaster();
        // cosmetics accumulate over defaults
        Cosmetics def = Cosmetics.defaults();
        double heart = def.heart(), gauge = def.gauge(), tie = def.tiespace();
        boolean solid = def.railSolid();
        double[] rail = def.railColor().clone();
        double[] grad = def.gradColors().clone();
        double[] stops = def.gradStops().clone();
        double v0 = 26;
        double[] start = { 0, 21, 0 };   // legacy `start` line (FVDD2/1)

        TrackPiece curPiece = null;
        LaneSection curSection = null;
        int curLane = -1;
        // legacy flat files: one implicit piece + section holds everything
        if (!v3) {
            curPiece = new TrackPiece(0, 21, 0, 0);
            curSection = ForceSection.empty(26);
            curPiece.sections.add(curSection);
            c.pieces.add(curPiece);
        }

        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            switch (parts[0]) {
                case "v0" -> v0 = clamp(Double.parseDouble(parts[1]), 1, 100);
                case "heart" -> heart = Double.parseDouble(parts[1]);
                case "gauge" -> gauge = Double.parseDouble(parts[1]);
                case "tiespace" -> tie = clamp(Double.parseDouble(parts[1]), 0.2, 10);
                case "start" -> {
                    for (int k = 0; k < 3; k++)
                        start[k] = clamp(Double.parseDouble(parts[k + 1]), -500, 500);
                }
                case "railmode" -> solid = parts[1].equals("solid");
                case "railcolor" -> {
                    for (int k = 0; k < 3; k++)
                        rail[k] = clamp(Double.parseDouble(parts[k + 1]), 0, 1);
                }
                case "gradcolors" -> {
                    for (int k = 0; k < 12; k++)
                        grad[k] = clamp(Double.parseDouble(parts[k + 1]), 0, 1);
                }
                case "gradstops" -> {
                    for (int k = 0; k < 4; k++)
                        stops[k] = clamp(Double.parseDouble(parts[k + 1]), GRAD_G_MIN, GRAD_G_MAX);
                }
                case "speedregion" -> c.speedRegions.add(new Coaster.SpeedRegion(
                    Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    parts[3], Double.parseDouble(parts[4])));
                case "piece" -> {
                    curPiece = new TrackPiece(
                        clamp(Double.parseDouble(parts[1]), -500, 500),
                        clamp(Double.parseDouble(parts[2]), -500, 500),
                        clamp(Double.parseDouble(parts[3]), -500, 500),
                        parts.length > 4 ? Double.parseDouble(parts[4]) : 0);
                    c.pieces.add(curPiece);
                    curSection = null;
                    curLane = -1;
                }
                case "section" -> {
                    if (curPiece == null) throw new IOException("section outside piece");
                    double vd = parts.length > 2
                        ? clamp(Double.parseDouble(parts[2]), 1, 100) : v0;
                    curSection = switch (parts[1]) {
                        case "force" -> ForceSection.empty(vd);
                        case "geo"   -> GeoSection.empty(vd);
                        default -> throw new IOException("unknown section mode: " + parts[1]);
                    };
                    if (parts.length > 4) curSection.reversed = parts[4].equals("1");
                    curPiece.sections.add(curSection);
                    curLane = -1;
                }
                case "lane" -> {
                    if (curSection == null) throw new IOException("lane outside section");
                    curLane = Integer.parseInt(parts[1]);
                    if (curLane < 0 || curLane > 2) throw new IOException("bad lane index");
                }
                case "n" -> {
                    if (curSection == null || curLane < 0)
                        throw new IOException("node outside lane section");
                    BezierBaker.Node n = new BezierBaker.Node(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                    n.outFrac = Double.parseDouble(parts[3]);  // raw seconds if FVDD1
                    n.outDv   = Double.parseDouble(parts[4]);
                    n.inFrac  = Double.parseDouble(parts[5]);
                    n.inDv    = Double.parseDouble(parts[6]);
                    n.smooth = !parts[7].equals("0");
                    n.straightOut = parts.length > 8 && parts[8].equals("1");
                    n.levelOut = parts.length > 9 && parts[9].equals("1");
                    curSection.lanes.get(curLane).add(n);
                }
                default -> { /* unknown lines ignored: forward compatibility */ }
            }
        }

        if (!v3) {
            // legacy: the flat file's start/v0 lines arrive in any order,
            // so apply them after the fact
            TrackPiece p = c.pieces.get(0);
            p.x = start[0]; p.y = start[1]; p.z = start[2];
            ((ForceSection) p.sections.get(0)).vDesign = v0;
        }
        c.v0 = v0;

        for (TrackPiece piece : c.pieces) {
            for (Section s : piece.sections) {
                LaneSection fs = (LaneSection) s;
                for (List<BezierBaker.Node> lane : fs.lanes) {
                    if (lane.size() < 2)
                        throw new IOException("each lane needs at least 2 nodes");
                }
                if (legacy1) convertLegacyHandles(fs);
            }
        }

        return new LoadResult(c, new Cosmetics(heart, gauge, tie, solid, rail, grad, stops));
    }

    /** FVDD1 stored handle time-offsets in absolute seconds (in-handle
     *  negative); convert to fractions of the neighboring segment. */
    private static void convertLegacyHandles(LaneSection fs) {
        for (List<BezierBaker.Node> lane : fs.lanes) {
            for (int i = 0; i < lane.size(); i++) {
                BezierBaker.Node n = lane.get(i);
                double outDt = n.outFrac, inDt = n.inFrac;
                n.outFrac = i < lane.size() - 1
                    ? clamp(outDt / (lane.get(i + 1).t - n.t), 0, BezierBaker.MAX_FRAC)
                    : BezierBaker.DEFAULT_FRAC;
                n.inFrac = i > 0
                    ? clamp(-inDt / (n.t - lane.get(i - 1).t), 0, BezierBaker.MAX_FRAC)
                    : BezierBaker.DEFAULT_FRAC;
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

}
