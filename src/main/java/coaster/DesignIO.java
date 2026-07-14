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
            // 5th-8th fields (v12.1): the piece's BASE ORIENTATION
            // quaternion (split/reversal anchor); missing = identity,
            // riding the ignore-extra-fields rule for old readers.
            // %.10f keeps the quantized quat unit to ~4e-10, under the
            // loader's re-normalize threshold — round-trips stay byte-
            // identical
            // fields 9-10: pitch/roll deltas (v13.13; missing = 0)
            out.printf(Locale.US,
                "piece %.4f %.4f %.4f %.4f %.10f %.10f %.10f %.10f %.4f %.4f%n",
                piece.x, piece.y, piece.z, piece.yawDeg,
                piece.baseOrient.w(), piece.baseOrient.x(),
                piece.baseOrient.y(), piece.baseOrient.z(),
                piece.pitchDeg, piece.rollDeg);
            for (Section section : piece.sections) {
                if (section instanceof BridgeSection bs) {
                    // bridge (v13.0): the FROZEN solve inputs — poses at
                    // %.10f (world-anchored geometry re-derives from
                    // them deterministically) + tangent lengths
                    out.printf(Locale.US, "section bridge %.4f distance %d%n",
                        bs.vDesign, bs.reversed ? 1 : 0);
                    out.printf(Locale.US, "bpose"
                        + " %.10f %.10f %.10f %.10f %.10f %.10f %.10f"
                        + " %.10f %.10f %.10f %.10f %.10f %.10f %.10f%n",
                        bs.solvedEntry.pos().x(), bs.solvedEntry.pos().y(),
                        bs.solvedEntry.pos().z(),
                        bs.solvedEntry.orient().w(), bs.solvedEntry.orient().x(),
                        bs.solvedEntry.orient().y(), bs.solvedEntry.orient().z(),
                        bs.solvedTarget.pos().x(), bs.solvedTarget.pos().y(),
                        bs.solvedTarget.pos().z(),
                        bs.solvedTarget.orient().w(), bs.solvedTarget.orient().x(),
                        bs.solvedTarget.orient().y(), bs.solvedTarget.orient().z());
                    // 3rd field: placement-optimization mode (v13.5;
                    // missing = 0, optimize position + yaw); 4th:
                    // optimize-length check (v13.8; missing = off);
                    // 5th: last requested optimize length (v13.12 —
                    // the march-spine geometry reproduces from it;
                    // missing/0 = never optimized, septic shape)
                    // fields 6-8: yaw/pitch/roll locks (v13.13;
                    // missing = yaw unlocked, pitch+roll locked)
                    out.printf(Locale.US,
                        "blen %.4f %.4f %d %d %.4f %d %d %d%n",
                        bs.lenA, bs.lenB, bs.optMode, bs.optLen ? 1 : 0,
                        bs.lastOptDist, bs.lockYaw ? 1 : 0,
                        bs.lockPitch ? 1 : 0, bs.lockRoll ? 1 : 0);
                    // seam curvature vectors (v13.1 — G² ends; only the
                    // orphaned-bridge fallback ever reads them back,
                    // live compiles re-capture)
                    out.printf(Locale.US, "bacc %.10f %.10f %.10f %.10f %.10f %.10f%n",
                        bs.accA.x(), bs.accA.y(), bs.accA.z(),
                        bs.accB.x(), bs.accB.y(), bs.accB.z());
                    // seam jerk vectors + roll rates + target entry
                    // speed (v13.2 — rate-slope/speed continuity) +
                    // roll accelerations (v13.4 — roll-rate slope)
                    out.printf(Locale.US, "bdyn %.10f %.10f %.10f %.10f %.10f %.10f"
                        + " %.10f %.10f %.10f %.10f %.10f%n",
                        bs.jerkA.x(), bs.jerkA.y(), bs.jerkA.z(),
                        bs.jerkB.x(), bs.jerkB.y(), bs.jerkB.z(),
                        bs.rollA, bs.rollB, bs.vB,
                        bs.rollAccA, bs.rollAccB);
                    out.println("endsection");
                    continue;
                }
                if (section instanceof StraightCurveSection sc) {
                    // straight/curve (v11.0): element lines instead of
                    // lanes — same unknown-line forgiveness both ways.
                    // Trailing roll fields (v11.1): mode 0 = rate °/s,
                    // 1 = global bank °; readers default MISSING fields
                    // to rate 0 so v11.0 saves keep their geometry.
                    // 5th field: pinned design speed (v14.5 D97;
                    // missing = off — old readers ignore it)
                    out.printf(Locale.US, "section sc %.4f distance %d %d%n",
                        sc.vDesign, sc.reversed ? 1 : 0,
                        sc.vPinned ? 1 : 0);
                    for (StraightCurveSection.Element e : sc.elements) {
                        int rm = e.rollMode == StraightCurveSection.RollMode.GLOBAL
                            ? 1 : 0;
                        if (e.kind == StraightCurveSection.Kind.STRAIGHT) {
                            out.printf(Locale.US, "elem straight %.4f %.4f %d %.4f%n",
                                e.length, e.smoothIn, rm, e.rollVal);
                        } else {
                            out.printf(Locale.US,
                                "elem curve %.4f %.4f %.4f %.4f %d %.4f%n",
                                e.radius, e.angleDeg, e.dirDeg, e.smoothIn,
                                rm, e.rollVal);
                        }
                    }
                    out.println("endsection");
                    continue;
                }
                LaneSection fs = (LaneSection) section;   // force | geo (Phase 2)
                // 5th field: pinned design speed (v14.5 D97). An
                // INFLUENCED section's line carries the AUTHORED speed
                // (§5.6): an old reader ignoring the influence lines
                // still pairs the lanes with the speed they were
                // authored at; the `infl` line restores the operating
                // speed on load.
                // fields 6-7 (v16.1, D107): global roll / global
                // yaw-pitch axis flags; missing = 0 = local (the
                // historic behavior — old files unchanged, old readers
                // ignore the extra fields)
                // field 8 (v16.6, D115): per-meter rate units;
                // missing = 0 = the historic °/s
                out.printf(Locale.US, "section %s %.4f %s %d %d %d %d %d%n",
                    fs instanceof GeoSection ? "geo" : "force",
                    fs.influenced() ? fs.vAuthored : fs.vDesign,
                    fs.domain() == Section.Domain.TIME ? "time" : "distance",
                    fs.reversed ? 1 : 0,
                    fs.vPinned ? 1 : 0,
                    fs.rollGlobal ? 1 : 0,
                    fs.yawPitchGlobal ? 1 : 0,
                    fs.ratePerMeter ? 1 : 0);
                for (int lane = 0; lane < 3; lane++) {
                    out.println("lane " + lane);
                    for (BezierBaker.Node n : fs.lanes.get(lane)) {
                        // 8th field straight-lock, 9th level-lock — both
                        // ride the ignore-extra-fields rule for old readers
                        writeNode(out, "n", n);
                    }
                }
                if (fs.influenced()) {
                    // INFLUENCE (v15.0, D98): operating speed + delta
                    // lanes in the exact `n` grammar; missing = the
                    // section loads uninfluenced (old files, old apps).
                    // Fields 2-3 (v16.5, D112): the influence axis
                    // flags — missing = local (historic), old readers
                    // ignore extra fields on known lines.
                    out.printf(Locale.US, "infl %.4f %d %d%n", fs.vDesign,
                        fs.inflRollGlobal ? 1 : 0,
                        fs.inflYawPitchGlobal ? 1 : 0);
                    for (int lane = 0; lane < 3; lane++) {
                        out.println("dlane " + lane);
                        for (BezierBaker.Node n : fs.deltaLanes.get(lane)) {
                            writeNode(out, "dn", n);
                        }
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
        Section curSection = null;
        int curLane = -1;
        int curDLane = -1;   // influence delta lane (v15.0, D98)
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
                    if (parts.length > 8) {   // base orientation (v12.1)
                        curPiece.baseOrient = loadQuat(parts, 5);
                    }
                    if (parts.length > 10) {  // pitch/roll deltas (v13.13)
                        curPiece.pitchDeg = Double.parseDouble(parts[9]);
                        curPiece.rollDeg = Double.parseDouble(parts[10]);
                    }
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
                        case "sc"    -> new StraightCurveSection(new ArrayList<>(), vd);
                        case "bridge" -> new BridgeSection(   // poses via bpose
                            new TrackPose(Vec3.ZERO, Quat.IDENTITY),
                            new TrackPose(new Vec3(10, 0, 0), Quat.IDENTITY),
                            5, 5, vd);
                        default -> throw new IOException("unknown section mode: " + parts[1]);
                    };
                    // the DOMAIN slot has been written since Phase 1 and
                    // honored for force/geo since v16.0 (D103); old
                    // files say "time" there — nothing changes for them
                    if (parts.length > 3 && curSection instanceof LaneSection) {
                        curSection.domain(parts[3].equals("distance")
                            ? Section.Domain.DISTANCE : Section.Domain.TIME);
                    }
                    if (parts.length > 4) curSection.reversed(parts[4].equals("1"));
                    // v14.5 pinned design speed; missing = off
                    if (parts.length > 5) curSection.vPinned(parts[5].equals("1"));
                    // v16.1 axis flags; missing = local (D107)
                    if (curSection instanceof LaneSection ls6) {
                        ls6.rollGlobal = parts.length > 6
                            && parts[6].equals("1");
                        ls6.yawPitchGlobal = parts.length > 7
                            && parts[7].equals("1");
                        // v16.6 rate units; missing = °/s
                        ls6.ratePerMeter = parts.length > 8
                            && parts[8].equals("1");
                    }
                    curPiece.sections.add(curSection);
                    curLane = -1;
                    curDLane = -1;
                }
                case "lane" -> {
                    if (!(curSection instanceof LaneSection))
                        throw new IOException("lane outside lane section");
                    curLane = Integer.parseInt(parts[1]);
                    if (curLane < 0 || curLane > 2) throw new IOException("bad lane index");
                }
                case "n" -> {
                    if (!(curSection instanceof LaneSection ls) || curLane < 0)
                        throw new IOException("node outside lane section");
                    ls.lanes.get(curLane).add(parseNode(parts));
                }
                // ---- INFLUENCE lines (v15.0, D98; missing = section
                // loads uninfluenced — the unknown-line rule covers
                // old readers seeing these) ----
                case "infl" -> {
                    if (!(curSection instanceof LaneSection ls))
                        throw new IOException("infl outside lane section");
                    // the section line carried the AUTHORED speed; the
                    // operating speed rides this line (§5.6 mapping)
                    ls.vAuthored = ls.vDesign;
                    ls.vDesign = clamp(Double.parseDouble(parts[1]), 0.5, 100);
                    // v16.5 influence axis flags; missing = local
                    ls.inflRollGlobal = parts.length > 2
                        && parts[2].equals("1");
                    ls.inflYawPitchGlobal = parts.length > 3
                        && parts[3].equals("1");
                    // scaffolding only: the dlane blocks that follow
                    // replace these; a file with no dn lines heals to
                    // lone (0,0) nodes in the post-load validation
                    ls.deltaLanes = LaneSection.zeroDeltaLanes(1);
                    curDLane = -1;
                }
                case "dlane" -> {
                    if (!(curSection instanceof LaneSection ls)
                            || !ls.influenced())
                        throw new IOException("dlane outside influenced section");
                    curDLane = Integer.parseInt(parts[1]);
                    if (curDLane < 0 || curDLane > 2)
                        throw new IOException("bad dlane index");
                    ls.deltaLanes.get(curDLane).clear();
                }
                case "dn" -> {
                    if (!(curSection instanceof LaneSection ls)
                            || !ls.influenced() || curDLane < 0)
                        throw new IOException("dn outside delta lane");
                    ls.deltaLanes.get(curDLane).add(parseNode(parts));
                }
                case "bpose" -> {
                    if (!(curSection instanceof BridgeSection bs))
                        throw new IOException("bpose outside bridge section");
                    bs.solvedEntry = new TrackPose(
                        new Vec3(Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2]), Double.parseDouble(parts[3])),
                        loadQuat(parts, 4));
                    bs.solvedTarget = new TrackPose(
                        new Vec3(Double.parseDouble(parts[8]),
                            Double.parseDouble(parts[9]), Double.parseDouble(parts[10])),
                        loadQuat(parts, 11));
                }
                case "blen" -> {
                    if (!(curSection instanceof BridgeSection bs))
                        throw new IOException("blen outside bridge section");
                    bs.lenA = Math.max(0.01, Double.parseDouble(parts[1]));
                    bs.lenB = Math.max(0.01, Double.parseDouble(parts[2]));
                    if (parts.length > 3) {   // v13.5 placement mode
                        bs.optMode = Math.max(0, Math.min(2,
                            Integer.parseInt(parts[3])));
                    }
                    // v13.8 optimize-length check; missing = off
                    // (a v13.10-dev `bopt` prefs line existed briefly
                    // and was removed with the prefs — any such lines
                    // fall to the unknown-line rule and are ignored)
                    bs.optLen = parts.length > 4 && parts[4].equals("1");
                    // v13.12 march-spine length; missing = 0 (septic)
                    if (parts.length > 5) {
                        bs.lastOptDist = Double.parseDouble(parts[5]);
                    }
                    // v13.13 orientation locks; missing = defaults
                    if (parts.length > 8) {
                        bs.lockYaw = parts[6].equals("1");
                        bs.lockPitch = parts[7].equals("1");
                        bs.lockRoll = parts[8].equals("1");
                    }
                }
                case "bacc" -> {   // v13.1; missing = zero (v13.0 = G¹)
                    if (!(curSection instanceof BridgeSection bs))
                        throw new IOException("bacc outside bridge section");
                    bs.accA = new Vec3(Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                    bs.accB = new Vec3(Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), Double.parseDouble(parts[6]));
                }
                case "bdyn" -> {   // v13.2; missing = zeros (v13.1 shape)
                    if (!(curSection instanceof BridgeSection bs))
                        throw new IOException("bdyn outside bridge section");
                    bs.jerkA = new Vec3(Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                    bs.jerkB = new Vec3(Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]), Double.parseDouble(parts[6]));
                    bs.rollA = Double.parseDouble(parts[7]);
                    bs.rollB = Double.parseDouble(parts[8]);
                    bs.vB = Double.parseDouble(parts[9]);
                    if (parts.length > 11) {   // v13.4 roll accelerations
                        bs.rollAccA = Double.parseDouble(parts[10]);
                        bs.rollAccB = Double.parseDouble(parts[11]);
                    }
                }
                case "elem" -> {
                    if (!(curSection instanceof StraightCurveSection sc))
                        throw new IOException("elem outside straight/curve section");
                    StraightCurveSection.Element e = switch (parts[1]) {
                        case "straight" -> StraightCurveSection.Element.straight(
                            Math.max(0.01, Double.parseDouble(parts[2])),
                            Math.max(0, Double.parseDouble(parts[3])));
                        case "curve" -> StraightCurveSection.Element.curve(
                            Math.max(0.1, Double.parseDouble(parts[2])),
                            Double.parseDouble(parts[3]),
                            Double.parseDouble(parts[4]),
                            Math.max(0, Double.parseDouble(parts[5])));
                        default -> throw new IOException("unknown element kind: " + parts[1]);
                    };
                    // trailing roll fields (v11.1); ABSENT on v11.0
                    // lines = rate 0, the exact old behavior (the
                    // GLOBAL-0 factory default is for NEW elements only)
                    int ri = parts[1].equals("straight") ? 4 : 6;
                    e.rollMode = parts.length > ri && parts[ri].equals("1")
                        ? StraightCurveSection.RollMode.GLOBAL
                        : StraightCurveSection.RollMode.RATE;
                    e.rollVal = parts.length > ri + 1
                        ? Double.parseDouble(parts[ri + 1]) : 0;
                    sc.elements.add(e);
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
                if (s instanceof BridgeSection) continue;   // bpose defaulted is fine
                if (s instanceof StraightCurveSection sc) {
                    if (sc.elements.isEmpty())
                        throw new IOException("straight/curve section needs >= 1 element");
                    continue;
                }
                LaneSection fs = (LaneSection) s;
                for (List<BezierBaker.Node> lane : fs.lanes) {
                    if (lane.size() < 2)
                        throw new IOException("each lane needs at least 2 nodes");
                }
                if (fs.influenced()) {
                    // delta lanes need only ONE node (flat zero is a
                    // single node at t=0); an empty block heals to it
                    for (List<BezierBaker.Node> dl : fs.deltaLanes) {
                        if (dl.isEmpty()) dl.add(new BezierBaker.Node(0, 0));
                    }
                }
                if (legacy1) convertLegacyHandles(fs);
            }
        }

        return new LoadResult(c, new Cosmetics(heart, gauge, tie, solid, rail, grad, stops));
    }

    /** One node line in the shared `n`/`dn` grammar (9 fields; the
     *  8th/9th lock fields ride the ignore-extra-fields rule). */
    private static void writeNode(PrintWriter out, String tag, BezierBaker.Node n) {
        out.printf(Locale.US, "%s %.4f %.4f %.4f %.4f %.4f %.4f %d %d %d%n",
            tag, n.t, n.val, n.outFrac, n.outDv, n.inFrac, n.inDv,
            n.smooth ? 1 : 0, n.straightOut ? 1 : 0,
            n.levelOut ? 1 : 0);
    }

    /** Parse a node from an `n`/`dn` line's fields (writeNode's dual;
     *  outFrac/inFrac are raw seconds under FVDD1 — the caller's
     *  convertLegacyHandles fixes design lanes up afterwards). */
    private static BezierBaker.Node parseNode(String[] parts) {
        BezierBaker.Node n = new BezierBaker.Node(
            Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
        n.outFrac = Double.parseDouble(parts[3]);
        n.outDv   = Double.parseDouble(parts[4]);
        n.inFrac  = Double.parseDouble(parts[5]);
        n.inDv    = Double.parseDouble(parts[6]);
        n.smooth = !parts[7].equals("0");
        n.straightOut = parts.length > 8 && parts[8].equals("1");
        n.levelOut = parts.length > 9 && parts[9].equals("1");
        return n;
    }

    /** Four quat fields starting at parts[at]. Re-normalized only when
     *  genuinely off-unit: %.10f quantization stays under the
     *  threshold, keeping save→load→save byte-identical. */
    private static Quat loadQuat(String[] parts, int at) {
        Quat q = new Quat(
            Double.parseDouble(parts[at]),
            Double.parseDouble(parts[at + 1]),
            Double.parseDouble(parts[at + 2]),
            Double.parseDouble(parts[at + 3]));
        double n2 = q.w() * q.w() + q.x() * q.x() + q.y() * q.y() + q.z() * q.z();
        return Math.abs(n2 - 1) > 1e-9 ? q.normalize() : q;
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
