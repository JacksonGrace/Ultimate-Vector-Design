package uvd;

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
    // widened −1→−2 in v16.14 (the purple stop sits at −1.5)
    private static final double GRAD_G_MIN = -2.0, GRAD_G_MAX = 5.0;

    /** Render-only per-coaster settings (UI-layer state until the
     *  milestone-B split moves them into the document). Colors are
     *  r,g,b in 0..1: railColor has 3 entries; gradColors 15 (FIVE
     *  stops since v16.14 — purple joined below the blue; old
     *  four-stop files load with the default purple prepended),
     *  gradStops 5 G-values; latColors/latStops (v16.13; ABSOLUTE
     *  since v16.14) are the LATERAL comb's three |Gl| stops,
     *  mirrored about zero. */
    public record Cosmetics(double heart, double gauge, double tiespace,
                            boolean railSolid, double[] railColor,
                            double[] gradColors, double[] gradStops,
                            double[] latColors, double[] latStops,
                            double[] camera) {

        // camera (v18.38, D210): the fly/orbit pose saved per file —
        // {pivot world x, y, z, yaw°, pitch°, distance m}; null =
        // no camera line (old files: the view stays where it is)

        public static Cosmetics defaults() {
            return new Cosmetics(1.0, 1.0, 1.0, false,
                new double[]{ 220 / 255.0, 60 / 255.0, 50 / 255.0 },
                new double[]{ 0.55, 0.15, 0.85,  0.2, 0.4, 1.0,
                              0.15, 0.8, 0.9,  0.2, 0.9, 0.3,
                              1.0, 0.9, 0.1,  1.0, 0.15, 0.1 },
                new double[]{ -1.5, -1, -0.5, 1, 3, 5 },
                new double[]{ 0.2, 0.9, 0.3,  1.0, 0.9, 0.1,
                              1.0, 0.15, 0.1 },
                new double[]{ 0.5, 1.0, 1.5 },
                null);
        }
    }

    /** warnings > 0 = the file was damaged and loaded FAIL-SOFT
     *  (v18.44, D216): that many lines were skipped or healed — the
     *  caller should tell the user and mark dirty so a re-save
     *  writes a clean file. MULTI-COASTER since v19.2 (D230): every
     *  coaster in the file with its own look; the legacy accessors
     *  coaster()/cosmetics() return the FIRST (or an empty coaster +
     *  defaults for an empty document — FVDD4 files may hold none).
     *  legacyScSections (v20.0, D259 §10.4) = how many straight/curve
     *  sections loaded from OLD `elem` lines (converted best-effort);
     *  legacyNote() is the load report's sentence for it. */
    public record LoadResult(List<Coaster> coasters, List<Cosmetics> looks,
                             double[] camera, int warnings,
                             int legacyScSections, int scSeamsCut) {
        public LoadResult(Coaster coaster, Cosmetics cosmetics, int warnings) {
            this(new ArrayList<>(List.of(coaster)),
                new ArrayList<>(List.of(cosmetics)), cosmetics.camera(),
                warnings, 0, 0);
        }
        /** The pre-v20.1 shape (no seam count). */
        public LoadResult(List<Coaster> coasters, List<Cosmetics> looks,
                          double[] camera, int warnings, int legacyScSections) {
            this(coasters, looks, camera, warnings, legacyScSections, 0);
        }
        /** The load report's converted-elements sentence
         *  (SectionNames.SC_LEGACY_CONVERTED) when any s/c section
         *  loaded from old `elem` lines — or when the straight/curve
         *  invariant's load pass cut a strict seam (scSeamsCut, v20.1
         *  D259 §3.2 / §10.4: the ONE load sentence, no second line;
         *  the caller marks the document dirty) —, else null. */
        public String legacyNote() {
            return legacyScSections > 0 || scSeamsCut > 0
                ? SectionNames.SC_LEGACY_CONVERTED : null;
        }
        public Coaster coaster() {
            return coasters.isEmpty() ? new Coaster() : coasters.get(0);
        }
        public Cosmetics cosmetics() {
            Cosmetics c = looks.isEmpty() ? Cosmetics.defaults() : looks.get(0);
            return new Cosmetics(c.heart(), c.gauge(), c.tiespace(),
                c.railSolid(), c.railColor(), c.gradColors(), c.gradStops(),
                c.latColors(), c.latStops(), camera);
        }
    }

    /** Which of the SIX gradient slots the k-th stop of an n-stop
     *  file line lands in (v16.15): 6 = direct; 5 (v16.14) = cyan
     *  slot 2 skipped; 4 (legacy) = slots 2-5. */
    private static int gradSlot(int k, int n) {
        if (n >= 6) return k;
        if (n == 5) return k < 2 ? k : k + 1;
        return k + 2;
    }

    // =====================================================================
    // Save (FVDD3 only)
    // =====================================================================

    /** File > Rename…'s name check (v19.23 D252; the character and
     *  device-name refusals since the D254 review — an illegal
     *  character reached Path.of and threw InvalidPathException, a
     *  RuntimeException, out of the button handler): the reason a
     *  typed file NAME is refused, or null when it is usable. Pure —
     *  LabelGate pins the sentences. */
    public static String fileNameWhy(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.isEmpty()) return "Type a name.";
        if (t.indexOf('/') >= 0 || t.indexOf((char) 92) >= 0   // 92 = backslash
                || t.indexOf(':') >= 0 || t.equals(".") || t.equals("..")) {
            return "Just a file name — no folders or drive letters.";
        }
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch < 0x20 || "*?\"<>|".indexOf(ch) >= 0) {
                return "A file name can't contain * ? \" < > | or control"
                    + " characters.";
            }
        }
        // Windows device names refuse with or without an extension
        int dot = t.indexOf('.');
        String stem = (dot < 0 ? t : t.substring(0, dot)).trim()
            .toUpperCase(java.util.Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            return stem + " is a reserved Windows name — pick another.";
        }
        return null;
    }

    /** Single-coaster save (the classic entry; gates + one-coaster
     *  documents): FVDD3, byte-compatible with every reader. */
    public static void save(Coaster c, Cosmetics cos, PrintWriter out) {
        List<Coaster> cs = new ArrayList<>();
        cs.add(c);
        List<Cosmetics> ls = new ArrayList<>();
        ls.add(cos);
        saveAll(cs, ls, cos.camera(), out);
    }

    /** MULTI-COASTER save (v19.2, D230): ONE coaster writes the classic
     *  FVDD3 (plus a harmless `name` line); any other count writes
     *  FVDD4 — `ncoasters N`, the camera, then one `coaster <name>`
     *  block per coaster holding exactly the FVDD3 body. Zero coasters
     *  is a legal FVDD4 document. Old readers refuse FVDD4 cleanly
     *  ("not an FVD design file" no — the header still starts with
     *  FVDD, so they would merge the blocks: hence the version bump is
     *  only taken when a merge would be wrong). */
    public static void saveAll(List<Coaster> coasters, List<Cosmetics> looks,
                               double[] camera, PrintWriter out) {
        boolean single = coasters.size() == 1;
        out.println(single ? "FVDD3" : "FVDD4");
        if (!single) {
            out.printf(Locale.US, "ncoasters %d%n", coasters.size());
            writeCamera(camera, out);
        }
        for (int i = 0; i < coasters.size(); i++) {
            Coaster c = coasters.get(i);
            Cosmetics cos = i < looks.size() && looks.get(i) != null
                ? looks.get(i) : Cosmetics.defaults();
            if (!single) out.println("coaster " + c.name.replace('\n', ' '));
            else out.println("name " + c.name.replace('\n', ' '));
            writeCoaster(c, cos, single ? camera : null, out);
        }
    }

    private static void writeCamera(double[] camera, PrintWriter out) {
        // the fly/orbit camera pose (v18.38, D210): pivot world
        // x/y/z + yaw/pitch degrees + orbit distance — cosmetic
        // precision (render-only); missing = the view stays put
        if (camera != null && camera.length >= 6) {
            out.printf(Locale.US,
                "camera %.4f %.4f %.4f %.4f %.4f %.4f%n",
                camera[0], camera[1], camera[2], camera[3], camera[4], camera[5]);
        }
    }

    /** One coaster's FVDD3 body (global lines + piece blocks). */
    private static void writeCoaster(Coaster c, Cosmetics cos, double[] camera,
                                     PrintWriter out) {
        // GEOMETRY-FEEDING numbers write BIT-EXACTLY since v18.26
        // (D198, superseding D197's %.10f): Double.toString is the
        // shortest decimal that parses back to the SAME double, so
        // the loaded document IS the session document and a reload
        // compiles bitwise-identically — even inside a stalled
        // design-speed knot, where curvature ~ 1/v² makes the
        // geometry chaotic and ANY finite fixed precision
        // decorrelates it (the user's 2026-07-31 "identical up to
        // exactly one point" report; %.10f's 5e-11 dust was enough).
        // Colors stay %.4f; old files load unchanged (the parser
        // always read full doubles, E-notation included)
        out.printf(Locale.US, "v0 %s%n", d(c.v0));
        // PHASE 3 friction (v17.0, D133): rolling coefficient
        // (decel = roll·g) + air drag (1/m, decel = air·v²);
        // missing = 0 0 = the historic loss-free model, and old
        // readers ignore the unknown line
        out.printf(Locale.US, "friction %s %s%n", d(c.fricRoll), d(c.fricAir));
        out.printf(Locale.US, "heart %s%n", d(cos.heart()));
        out.printf(Locale.US, "gauge %s%n", d(cos.gauge()));
        out.printf(Locale.US, "tiespace %s%n", d(cos.tiespace()));
        out.printf(Locale.US, "railmode %s%n", cos.railSolid() ? "solid" : "gradient");
        out.printf(Locale.US, "railcolor %.4f %.4f %.4f%n",
            cos.railColor()[0], cos.railColor()[1], cos.railColor()[2]);
        StringBuilder gc = new StringBuilder("gradcolors");
        for (double v : cos.gradColors()) gc.append(String.format(Locale.US, " %.4f", v));
        out.println(gc);
        StringBuilder gs = new StringBuilder("gradstops");
        for (double v : cos.gradStops()) gs.append(String.format(Locale.US, " %.4f", v));
        out.println(gs);
        // lateral comb stops (v16.13; three |Gl| stops since v16.14)
        // — old readers ignore unknown lines
        StringBuilder lc = new StringBuilder("latcolors");
        for (double v : cos.latColors()) lc.append(String.format(Locale.US, " %.4f", v));
        out.println(lc);
        StringBuilder ls = new StringBuilder("latstops");
        for (double v : cos.latStops()) ls.append(String.format(Locale.US, " %.4f", v));
        out.println(ls);
        writeCamera(camera, out);   // single-coaster files: here (D210)
        for (TrackPiece piece : c.pieces) {
            // 5th-8th fields (v12.1): the piece's BASE ORIENTATION
            // quaternion (split/reversal anchor); missing = identity,
            // riding the ignore-extra-fields rule for old readers.
            // Bit-exact writes keep the stored norm² within ulps of 1
            // (in-app quats are normalized), so the loader's 1e-9
            // re-normalize threshold never fires — round-trips stay
            // byte-identical AND bit-exact (D198)
            // fields 9-10: pitch/roll deltas (v13.13; missing = 0)
            // fields 11-14 (v17.1, D135): STRICT SEAM — followPrev +
            // the three orientation locks; missing = detached.
            // fields 15-17 (D136): the DISCONTINUOUS axis values
            // (deg); missing = re-seed from the stored placement.
            // field 18 (v17.2, D139): seam SMOOTHING length (m);
            // missing = 0 = off
            out.printf(Locale.US,
                "piece %s %s %s %s %s %s %s %s %s %s"
                + " %d %d %d %d %s %s %s %s",
                d(piece.x), d(piece.y), d(piece.z), d(piece.yawDeg),
                d(piece.baseOrient.w()), d(piece.baseOrient.x()),
                d(piece.baseOrient.y()), d(piece.baseOrient.z()),
                d(piece.pitchDeg), d(piece.rollDeg),
                piece.followPrev ? 1 : 0, piece.sLockYaw ? 1 : 0,
                piece.sLockPitch ? 1 : 0, piece.sLockRoll ? 1 : 0,
                d(piece.sYawDeg), d(piece.sPitchDeg), d(piece.sRollDeg),
                d(piece.sSmoothLen));
            // fields 19-23 (v18.27, D199): the canonical generation
            // pair a split stored — heading + heading-stripped quat;
            // absent = derive from baseOrient (old readers ignore
            // extra fields)
            if (piece.canonOrient != null) {
                out.printf(Locale.US, " %s %s %s %s %s",
                    d(piece.canonPsiDeg),
                    d(piece.canonOrient.w()), d(piece.canonOrient.x()),
                    d(piece.canonOrient.y()), d(piece.canonOrient.z()));
            }
            out.println();
            // TWO-TIER ORIENTATION (v19.0, D221/D222) — its own line
            // (the piece line's fields 19-23 are conditional, so
            // trailing positions are unsafe): `porient locked
            // designPitch designRoll affect mode`. Field 1 keeps its
            // D222 meaning (1 = REAL_ONLY, "locks track shape") for
            // old readers; the design deltas feed generation
            // (bit-exact, they shape the track); field 4 = the
            // "Affect Subsequent Strict Seams" carry flag with its
            // D249 meaning (1 = carry — D248 wrote the INVERTED
            // 'only' bit in this slot, the loader tells them apart by
            // field count); field 5 (v19.20, D249) = the OrientMode
            // ordinal (0 LOCKSTEP / 1 REAL_ONLY / 2 DESIGN_ONLY), the
            // authority when present. Missing line = LOCKSTEP + (0,0)
            // + no carry (the compile reads none of them, so the
            // track stays bitwise). Old readers ignore unknown lines
            // and extra fields (D50). Field 6 (v20.1, D259 §3.5) =
            // the AUTOMATIC-seam flag `auto` (0|1; missing = false):
            // the follower the straight/curve invariant created —
            // generated AT the follow contract while its locks are
            // off; the `piece` line's fields 19-23 are conditional, so
            // a trailing piece field would be unsafe.
            out.printf(Locale.US, "porient %d %s %s %d %d %d%n",
                piece.shapeLocked() ? 1 : 0,
                d(piece.designPitchDeg), d(piece.designRollDeg),
                piece.affectSubsequentSeams ? 1 : 0,
                piece.orientMode.ordinal(),
                piece.sAuto ? 1 : 0);
            for (Section section : piece.sections) {
                if (section instanceof BridgeSection bs) {
                    // bridge (v13.0): the FROZEN solve inputs — poses
                    // bit-exact (world-anchored geometry re-derives
                    // from them deterministically) + tangent lengths
                    // field 6 = vPinned (v18.44, D216 — lane/sc
                    // section lines always persisted it; a pin on a
                    // bridge-FIRST piece silently dropped on reload;
                    // the reader takes parts[5] for every kind)
                    out.printf(Locale.US, "section bridge %s distance %d %d%n",
                        d(bs.vDesign), bs.reversed ? 1 : 0,
                        bs.vPinned() ? 1 : 0);
                    out.printf(Locale.US, "bpose"
                        + " %s %s %s %s %s %s %s"
                        + " %s %s %s %s %s %s %s%n",
                        d(bs.solvedEntry.pos().x()), d(bs.solvedEntry.pos().y()),
                        d(bs.solvedEntry.pos().z()),
                        d(bs.solvedEntry.orient().w()), d(bs.solvedEntry.orient().x()),
                        d(bs.solvedEntry.orient().y()), d(bs.solvedEntry.orient().z()),
                        d(bs.solvedTarget.pos().x()), d(bs.solvedTarget.pos().y()),
                        d(bs.solvedTarget.pos().z()),
                        d(bs.solvedTarget.orient().w()), d(bs.solvedTarget.orient().x()),
                        d(bs.solvedTarget.orient().y()), d(bs.solvedTarget.orient().z()));
                    // 3rd field: placement-optimization mode (v13.5;
                    // missing = 0, optimize position + yaw); 4th:
                    // optimize-length check (v13.8; missing = off);
                    // 5th: last requested optimize length (v13.12 —
                    // the march-spine geometry reproduces from it;
                    // missing/0 = never optimized, septic shape)
                    // fields 6-8: yaw/pitch/roll locks (v13.13;
                    // missing = yaw unlocked, pitch+roll locked)
                    out.printf(Locale.US,
                        "blen %s %s %d %d %s %d %d %d%n",
                        d(bs.lenA), d(bs.lenB), bs.optMode, bs.optLen ? 1 : 0,
                        d(bs.lastOptDist), bs.lockYaw ? 1 : 0,
                        bs.lockPitch ? 1 : 0, bs.lockRoll ? 1 : 0);
                    // seam curvature vectors (v13.1 — G² ends; only the
                    // orphaned-bridge fallback ever reads them back,
                    // live compiles re-capture)
                    out.printf(Locale.US, "bacc %s %s %s %s %s %s%n",
                        d(bs.accA.x()), d(bs.accA.y()), d(bs.accA.z()),
                        d(bs.accB.x()), d(bs.accB.y()), d(bs.accB.z()));
                    // seam jerk vectors + roll rates + target entry
                    // speed (v13.2 — rate-slope/speed continuity) +
                    // roll accelerations (v13.4 — roll-rate slope)
                    out.printf(Locale.US, "bdyn %s %s %s %s %s %s"
                        + " %s %s %s %s %s%n",
                        d(bs.jerkA.x()), d(bs.jerkA.y()), d(bs.jerkA.z()),
                        d(bs.jerkB.x()), d(bs.jerkB.y()), d(bs.jerkB.z()),
                        d(bs.rollA), d(bs.rollB), d(bs.vB),
                        d(bs.rollAccA), d(bs.rollAccB));
                    out.println("endsection");
                    continue;
                }
                if (section instanceof StraightCurveSection sc) {
                    // straight/curve (v11.0): element lines instead of
                    // lanes — same unknown-line forgiveness both ways.
                    // The `el` grammar since v20.0 (D259 §1.6): one
                    // positional KIND word, every other token
                    // name=value in a fixed order (writeElement) — the
                    // pre-D259 `elem` lines are READ only (best-effort,
                    // counted, D259 §10.4).
                    // 5th field: pinned design speed (v14.5 D97;
                    // missing = off — old readers ignore it); a
                    // TRI-STATE since v19.27 (D256): 2 = REAL-ANCHORED
                    // (Section.vRealAnchored — an old reader sees it
                    // as unpinned = the pre-D256 picture, fail-soft)
                    out.printf(Locale.US, "section sc %s distance %d %d%n",
                        d(sc.vDesign), sc.reversed ? 1 : 0,
                        sc.vPinned ? 1 : sc.vRealAnchored ? 2 : 0);
                    for (int ei = 0; ei < sc.elements.size(); ei++) {
                        writeElement(out, sc.elements.get(ei), ei);
                    }
                    writeSpeedCtls(out, sc.speedCtls, sc.speedMarks);
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
                // field 9 (v16.7, D118): absolute roll; missing = 0
                // field 10 (v19.8, D237): absolute yaw/pitch; missing = 0
                // field 6 is a TRI-STATE since v19.27 (D256): 1 = pinned,
                // 2 = REAL-ANCHORED (Section.vRealAnchored; an old reader
                // takes 2 as unpinned = the pre-D256 picture, fail-soft)
                out.printf(Locale.US,
                    "section %s %s %s %d %d %d %d %d %d %d%n",
                    fs instanceof GeoSection ? "geo" : "force",
                    d(fs.influenced() ? fs.vAuthored : fs.vDesign),
                    fs.domain() == Section.Domain.TIME ? "time" : "distance",
                    fs.reversed ? 1 : 0,
                    fs.vPinned ? 1 : fs.vRealAnchored ? 2 : 0,
                    fs.rollGlobal ? 1 : 0,
                    fs.yawPitchGlobal ? 1 : 0,
                    fs.ratePerMeter ? 1 : 0,
                    fs.rollAbsolute ? 1 : 0,
                    fs.ypAbsolute ? 1 : 0);
                for (int lane = 0; lane < 3; lane++) {
                    // 3rd field (v19.19, D248): the END-FOLLOW flag —
                    // old readers ignore it (they read parts[1] only);
                    // missing = derived from the pristine-hold shape
                    out.println("lane " + lane + " "
                        + (fs.endFollows[lane] ? 1 : 0));
                    for (BezierBaker.Node n : fs.lanes.get(lane)) {
                        // 8th field straight-lock, 9th level-lock — both
                        // ride the ignore-extra-fields rule for old readers
                        writeNode(out, "n", n);
                    }
                }
                writeSpeedCtls(out, fs.speedCtls, fs.speedMarks);
                if (fs.influenced()) {
                    // INFLUENCE (v15.0, D98): operating speed + delta
                    // lanes in the exact `n` grammar; missing = the
                    // section loads uninfluenced (old files, old apps).
                    // Fields 2-3 (v16.5, D112): the influence axis
                    // flags — missing = local (historic), old readers
                    // ignore extra fields on known lines. (The
                    // short-lived D235 anchor fields 4-8 are no
                    // longer written — D236's hybrid needs none —
                    // and load as ignored extras.)
                    out.printf(Locale.US, "infl %s %d %d%n", d(fs.vDesign),
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
        // a UTF-8 BOM (an external editor's re-save) must not refuse
        // an otherwise byte-perfect file (v18.44, D216)
        if (header != null && !header.isEmpty()
                && header.charAt(0) == '﻿') {
            header = header.substring(1);
        }
        if (header == null || !header.startsWith("FVDD"))
            throw new IOException("not an FVD design file");
        boolean legacy1 = header.trim().equals("FVDD1");
        boolean v4 = header.trim().equals("FVDD4");   // multi-coaster (D230)
        boolean v3 = header.trim().equals("FVDD3") || v4;

        // MULTI-COASTER (v19.2, D230): the per-coaster locals below
        // describe the CURRENT block; a `coaster <name>` line finishes
        // it into these lists and resets them. FVDD3/2/1 files are one
        // implicit block.
        List<Coaster> coasters = new ArrayList<>();
        List<Cosmetics> looks = new ArrayList<>();
        boolean touched = false;   // any per-coaster line seen in this block

        Coaster c = new Coaster();
        // cosmetics accumulate over defaults
        Cosmetics def = Cosmetics.defaults();
        double heart = def.heart(), gauge = def.gauge(), tie = def.tiespace();
        boolean solid = def.railSolid();
        double[] rail = def.railColor().clone();
        double[] grad = def.gradColors().clone();
        double[] stops = def.gradStops().clone();
        double[] latc = def.latColors().clone();
        double[] lats = def.latStops().clone();
        double v0 = 26;
        double[] start = { 0, 21, 0 };   // legacy `start` line (FVDD2/1)
        double[] camera = null;          // v18.38: missing = view stays
        int warnings = 0;                // fail-soft skips/heals (D216)
        // s/c sections that loaded OLD `elem` lines (D259 §10.4 — the
        // whole DOCUMENT's count, every coaster block; legacyScSec =
        // the section last counted, so each one counts once)
        int legacyScSections = 0;
        int scSeamsCut = 0;   // the invariant's load pass (v20.1, D259 §3.2)
        StraightCurveSection legacyScSec = null;
        // lanes whose `lane` line carried the END-FOLLOW field (D248):
        // identity-keyed, so the flag of every OTHER lane is derived
        // from its loaded shape in finishCoaster
        java.util.Set<List<BezierBaker.Node>> explicitFollow =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        TrackPiece curPiece = null;
        Section curSection = null;
        int curLane = -1;
        int curDLane = -1;   // influence delta lane (v15.0, D98)
        SpeedControl curCtl = null;   // speed control (v18.0, D160)
        // the s/c element whose roll block (`rn` / `rsh` / `rcut`, v20.1
        // D259 §2.8) the next lines belong to; curElemLane = its `rn`
        // lines have started (the first one replaces the default lane);
        // the pending shadow + fingerprint close with the element
        StraightCurveSection.Element curElem = null;
        boolean curElemLane = false;
        List<BezierBaker.Node> curShadow = null;
        RollLane.Cut curCut = null;
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
            // FAIL-SOFT per line (v18.44, D216): a truncated or
            // garbled line — a partial write, a sync conflict —
            // used to throw out of the whole load and refuse the
            // entire document. Skip it, count it, keep parsing;
            // the caller reports the count and marks dirty. The
            // post-load validation still refuses documents with
            // nothing left in them.
            try {
            if (!parts[0].equals("coaster") && !parts[0].equals("ncoasters")
                    && !parts[0].equals("camera")) {
                touched = true;
            }
            // any line but the element's own roll lines CLOSES the
            // pending element's roll block (§2.8 ii: a shadow without a
            // fingerprint, a fingerprint without a shadow, or one whose
            // t is not the lane's last node's t is DROPPED and counted)
            if (curElem != null && !parts[0].equals("rn")
                    && !parts[0].equals("rsh") && !parts[0].equals("rcut")) {
                warnings += closeElement(curElem, curShadow, curCut);
                curElem = null;
                curElemLane = false;
                curShadow = null;
                curCut = null;
            }
            switch (parts[0]) {
                // MULTI-COASTER block separator (v19.2, D230): finish
                // the current block, start a fresh one named by the
                // rest of the line; the first separator of a v4 file
                // just NAMES the implicit (still empty) block
                case "coaster" -> {
                    String nm = line.trim().length() > 8
                        ? line.trim().substring(8).trim() : "";
                    if (touched) {
                        if (!v3) {   // legacy: the flat file's start/v0
                            TrackPiece p = c.pieces.get(0);
                            p.x = start[0]; p.y = start[1]; p.z = start[2];
                            ((ForceSection) p.sections.get(0)).vDesign = v0;
                        }
                        c.v0 = v0;
                        int[] w = { 0 };
                        int[] lsc = { 0 };   // the invariant's load pass (D259 §3.2)
                        finishCoaster(c, legacy1, w, explicitFollow, lsc);
                        warnings += w[0];
                        scSeamsCut += lsc[0];
                        if (!c.pieces.isEmpty()) {
                            coasters.add(c);
                            looks.add(new Cosmetics(heart, gauge, tie, solid,
                                rail, grad, stops, latc, lats, null));
                        }
                        c = new Coaster();
                        heart = def.heart(); gauge = def.gauge(); tie = def.tiespace();
                        solid = def.railSolid();
                        rail = def.railColor().clone();
                        grad = def.gradColors().clone();
                        stops = def.gradStops().clone();
                        latc = def.latColors().clone();
                        lats = def.latStops().clone();
                        v0 = 26;
                        curPiece = null; curSection = null; curLane = -1;
                        curDLane = -1; curCtl = null;
                        touched = false;
                    }
                    if (!nm.isEmpty()) c.name = nm;
                }
                case "ncoasters" -> { /* informational (D230) */ }
                case "name" -> {   // single-coaster files carry it (D230)
                    String nm = line.trim().length() > 5
                        ? line.trim().substring(5).trim() : "";
                    if (!nm.isEmpty()) c.name = nm;
                }
                case "v0" -> v0 = clamp(Double.parseDouble(parts[1]), 1, 100);
                // Phase 3 friction (v17.0, D133); missing = 0 0
                case "friction" -> {
                    c.fricRoll = clamp(Double.parseDouble(parts[1]), 0, 1);
                    if (parts.length > 2) {
                        c.fricAir = clamp(Double.parseDouble(parts[2]), 0, 1);
                    }
                }
                case "heart" -> heart = Double.parseDouble(parts[1]);
                case "gauge" -> gauge = Double.parseDouble(parts[1]);
                case "tiespace" -> tie = clamp(Double.parseDouble(parts[1]), 0.2, 10);
                case "start" -> {
                    // bounded by parts.length like gradcolors (D216:
                    // a truncated cosmetic line keeps its defaults)
                    for (int k = 0; k < 3 && k + 1 < parts.length; k++)
                        start[k] = clamp(Double.parseDouble(parts[k + 1]), -500, 500);
                }
                case "railmode" -> solid = parts[1].equals("solid");
                case "railcolor" -> {
                    for (int k = 0; k < 3 && k + 1 < parts.length; k++)
                        rail[k] = clamp(Double.parseDouble(parts[k + 1]), 0, 1);
                }
                // the saved fly/orbit camera pose (v18.38, D210):
                // {pivot world x,y,z, yaw°, pitch°, dist}; missing
                // or short = the view stays where it is
                case "camera" -> {
                    if (parts.length > 6) {
                        camera = new double[6];
                        for (int k = 0; k < 6; k++)
                            camera[k] = Double.parseDouble(parts[k + 1]);
                    }
                }
                case "gradcolors" -> {
                    // SIX stops since v16.15 (18 values). Legacy maps:
                    // a 15-value v16.14 line (purple/blue/green/
                    // yellow/red) keeps the default CYAN inserted at
                    // stop 2; a 12-value 4-stop line (blue@0/green/
                    // yellow/red) fills stops 2-5 under the default
                    // purple + blue.
                    int n = (parts.length - 1) / 3;
                    for (int k = 0; k < n && k < 6; k++) {
                        int slot = gradSlot(k, n);
                        if (slot > 5) continue;
                        for (int ch = 0; ch < 3; ch++)
                            grad[slot * 3 + ch] = clamp(
                                Double.parseDouble(parts[k * 3 + ch + 1]), 0, 1);
                    }
                }
                case "gradstops" -> {
                    int n = parts.length - 1;
                    for (int k = 0; k < n && k < 6; k++) {
                        int slot = gradSlot(k, n);
                        if (slot > 5) continue;
                        stops[slot] = clamp(Double.parseDouble(parts[k + 1]),
                            GRAD_G_MIN, GRAD_G_MAX);
                    }
                }
                // lateral comb stops (v16.13; missing = defaults;
                // THREE |Gl| stops since v16.14 — the v16.13 4-stop
                // shape never shipped)
                case "latcolors" -> {
                    for (int k = 0; k < 9 && k < parts.length - 1; k++)
                        latc[k] = clamp(Double.parseDouble(parts[k + 1]), 0, 1);
                }
                case "latstops" -> {
                    for (int k = 0; k < 3 && k < parts.length - 1; k++)
                        lats[k] = clamp(Double.parseDouble(parts[k + 1]), 0, GRAD_G_MAX);
                }
                case "piece" -> {
                    // ±1e6, not the spinners' ±500 (D198): the bridge
                    // optimizer MARCHES next-piece placements wherever
                    // the geometry lands — a long layout legally puts
                    // pieces past ±500, and the old clamp teleported
                    // them on reload
                    curPiece = new TrackPiece(
                        clamp(Double.parseDouble(parts[1]), -1e6, 1e6),
                        clamp(Double.parseDouble(parts[2]), -1e6, 1e6),
                        clamp(Double.parseDouble(parts[3]), -1e6, 1e6),
                        parts.length > 4 ? Double.parseDouble(parts[4]) : 0);
                    if (parts.length > 8) {   // base orientation (v12.1)
                        curPiece.baseOrient = loadQuat(parts, 5);
                    }
                    if (parts.length > 10) {  // pitch/roll deltas (v13.13)
                        curPiece.pitchDeg = Double.parseDouble(parts[9]);
                        curPiece.rollDeg = Double.parseDouble(parts[10]);
                    }
                    if (parts.length > 14) {  // strict seam (v17.1, D135)
                        curPiece.followPrev = parts[11].equals("1");
                        curPiece.sLockYaw = parts[12].equals("1");
                        curPiece.sLockPitch = parts[13].equals("1");
                        curPiece.sLockRoll = parts[14].equals("1");
                        if (parts.length > 17) {   // D136 values
                            curPiece.sYawDeg = Double.parseDouble(parts[15]);
                            curPiece.sPitchDeg = Double.parseDouble(parts[16]);
                            curPiece.sRollDeg = Double.parseDouble(parts[17]);
                            if (parts.length > 18) {   // smoothing (v17.2)
                                curPiece.sSmoothLen = clamp(
                                    Double.parseDouble(parts[18]), 0, 200);
                            }
                        } else if (curPiece.followPrev) {
                            Coaster.seedStrictValues(curPiece,
                                curPiece.entryPose().orient());
                        }
                    }
                    if (parts.length > 23) {   // canonical generation
                        // pair (v18.27, D199 — split pieces keep the
                        // parent chain's exact state); missing = null
                        // = decompose baseOrient
                        curPiece.canonPsiDeg = Double.parseDouble(parts[19]);
                        curPiece.canonOrient = loadQuat(parts, 20);
                    }
                    c.pieces.add(curPiece);
                    curSection = null;
                    curLane = -1;
                }
                case "porient" -> {
                    // two-tier orientation (v19.0, D221/D222; the
                    // 3-state mode v19.20, D249): `porient locked
                    // designPitch designRoll [affect [mode]]`;
                    // missing line = the TrackPiece constructor
                    // defaults (LOCKSTEP + (0,0) + no carry). A
                    // 2-field line is the short-lived D221 interim
                    // build (`porient mode dirty`): its mode 1
                    // (LINKED) meant design == true — migrate by
                    // copying the true deltas into the design tier.
                    // FIELD COUNT decides field 4's meaning (D249):
                    //   3 fields (v19.0-v19.18): no carry;
                    //   exactly 4 (D248): the 'Only Affect … Until
                    //     Next Strict Seam' bit — INVERT (1 = no carry);
                    //   5+ (D249): 'Affect Subsequent Strict Seams'
                    //     verbatim (1 = carry) + field 5 = the mode
                    //     ordinal, the authority over field 1 when it
                    //     parses to 0..2 (fail-soft otherwise: field
                    //     1's REAL_ONLY / LOCKSTEP — D216).
                    if (curPiece != null && parts.length > 3) {
                        curPiece.orientMode = parts[1].equals("0")
                            ? TrackPiece.OrientMode.LOCKSTEP
                            : TrackPiece.OrientMode.REAL_ONLY;
                        curPiece.designPitchDeg =
                            Double.parseDouble(parts[2]);
                        curPiece.designRollDeg =
                            Double.parseDouble(parts[3]);
                        if (parts.length > 5) {
                            curPiece.affectSubsequentSeams =
                                !parts[4].equals("0");
                            // field 6 (v20.1, D259 §3.5): the
                            // AUTOMATIC-seam flag; missing = false
                            curPiece.sAuto = parts.length > 6
                                && parts[6].equals("1");
                            try {
                                int m = Integer.parseInt(parts[5]);
                                if (m >= 0 && m < TrackPiece.OrientMode
                                        .values().length) {
                                    curPiece.orientMode =
                                        TrackPiece.OrientMode.values()[m];
                                }
                            } catch (NumberFormatException ignored) {
                                // field 1 stands (fail-soft)
                            }
                        } else if (parts.length > 4) {
                            curPiece.affectSubsequentSeams =
                                parts[4].equals("0");   // D248 'only' inverts
                        } else {
                            curPiece.affectSubsequentSeams = false;
                        }
                    } else if (curPiece != null && parts.length > 2
                            && parts[1].equals("1")) {
                        curPiece.orientMode = TrackPiece.OrientMode.LOCKSTEP;
                        curPiece.designPitchDeg = curPiece.pitchDeg;
                        curPiece.designRollDeg = curPiece.rollDeg;
                    }
                }
                case "section" -> {
                    if (curPiece == null) throw new IOException("section outside piece");
                    // the load clamp must ADMIT every value the app
                    // itself writes (D198 — save/load asymmetries
                    // silently rewrite the document): the reconcile
                    // floors vDesign at 0.1 (a stalled seam saves
                    // exactly 0.1, and the old [1,100] turned it into
                    // 1.0 — 10x the knot curvature, the user's
                    // "changes at exactly one point" reload), and
                    // speed controls can push design speeds past 100.
                    // Upper bound 1e4 since v18.44 (D216): the
                    // reconcile writes true entry speeds UNCAPPED,
                    // and the admitted ±1e6 coordinate envelope
                    // reaches ~6.3 km/s — the old 1000 silently
                    // rewrote such saves (non-bitwise reload)
                    double vd = parts.length > 2
                        ? clamp(Double.parseDouble(parts[2]), 0.1, 1e4) : v0;
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
                    // v14.5 pinned design speed; missing = off; the slot
                    // is a TRI-STATE since v19.27 (D256): 2 = REAL-
                    // ANCHORED (never pinned); 0 / 1 / missing bitwise
                    // as before; a bridge's setter is a no-op
                    if (parts.length > 5) {
                        curSection.vPinned(parts[5].equals("1"));
                        curSection.vRealAnchored(parts[5].equals("2"));
                    }
                    // v16.1 axis flags; missing = local (D107)
                    if (curSection instanceof LaneSection ls6) {
                        ls6.rollGlobal = parts.length > 6
                            && parts[6].equals("1");
                        ls6.yawPitchGlobal = parts.length > 7
                            && parts[7].equals("1");
                        // v16.6 rate units; missing = °/s
                        ls6.ratePerMeter = parts.length > 8
                            && parts[8].equals("1");
                        // v16.7 absolute roll; missing = rate
                        ls6.rollAbsolute = parts.length > 9
                            && parts[9].equals("1");
                        // v19.8 absolute yaw/pitch; missing = rate
                        ls6.ypAbsolute = parts.length > 10
                            && parts[10].equals("1");
                    }
                    curPiece.sections.add(curSection);
                    curLane = -1;
                    curDLane = -1;
                    curCtl = null;
                }
                case "lane" -> {
                    if (!(curSection instanceof LaneSection ls))
                        throw new IOException("lane outside lane section");
                    curLane = Integer.parseInt(parts[1]);
                    if (curLane < 0 || curLane > 2) throw new IOException("bad lane index");
                    // 3rd field (v19.19, D248): the END-FOLLOW flag.
                    // Present = taken verbatim (an explicit 0 on a
                    // hold-shaped lane is a deliberate non-follow);
                    // missing (every pre-D248 file) = derived from the
                    // loaded nodes in finishCoaster
                    if (parts.length > 2) {
                        ls.endFollows[curLane] = parts[2].equals("1");
                        explicitFollow.add(ls.lanes.get(curLane));
                    }
                }
                case "n" -> {
                    if (!(curSection instanceof LaneSection ls) || curLane < 0)
                        throw new IOException("node outside lane section");
                    ls.lanes.get(curLane).add(parseNode(parts));
                }
                // ---- SPEED CONTROLS (v18.0, D160; missing = none —
                // unknown-line rule covers old readers) ----
                case "sctl" -> {
                    List<SpeedControl> dst = ctlListOf(curSection);
                    if (dst == null)
                        throw new IOException("sctl outside lane/sc section");
                    curCtl = new SpeedControl(
                        Double.parseDouble(parts[3]),
                        Math.max(0.01, Double.parseDouble(parts[4])));
                    curCtl.type = parts[1].equals("1")
                        ? SpeedControl.ACCEL : SpeedControl.SPEED;
                    curCtl.complex = parts[2].equals("1");
                    curCtl.speed = clamp(Double.parseDouble(parts[5]), 0.5, 200);
                    curCtl.accel = clamp(Double.parseDouble(parts[6]), 0, 20);
                    curCtl.minLimit = parts[7].equals("1");
                    curCtl.maxLimit = parts[8].equals("1");
                    if (!curCtl.minLimit && !curCtl.maxLimit) {
                        curCtl.minLimit = true;   // at least one, always
                        curCtl.maxLimit = true;
                    }
                    // fields 9-10 = decel + tied (v18.45, D217);
                    // missing = tied, decel mirrors accel
                    if (parts.length > 10) {
                        curCtl.decel = clamp(
                            Double.parseDouble(parts[9]), 0, 20);
                        curCtl.tied = parts[10].equals("1");
                    } else {
                        curCtl.decel = curCtl.accel;
                        curCtl.tied = true;
                    }
                    dst.add(curCtl);
                }
                case "cn" -> {
                    if (curCtl == null)
                        throw new IOException("cn outside speed control");
                    curCtl.lane.add(parseNode(parts));
                }
                case "cdn" -> {   // decel graph nodes (v18.45, D217)
                    if (curCtl == null)
                        throw new IOException("cdn outside speed control");
                    curCtl.decelLane.add(parseNode(parts));
                }
                case "smark" -> {
                    List<Double> dst = markListOf(curSection);
                    if (dst == null)
                        throw new IOException("smark outside lane/sc section");
                    dst.add(Double.parseDouble(parts[1]));
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
                    // same admit-what-the-app-writes rule as the
                    // section line (D198; upper bound 1e4 with it,
                    // D216): influenced sections join the reconcile,
                    // whose floor is 0.1 and whose top is uncapped
                    ls.vDesign = clamp(Double.parseDouble(parts[1]), 0.1, 1e4);
                    // v16.5 influence axis flags; missing = local
                    ls.inflRollGlobal = parts.length > 2
                        && parts[2].equals("1");
                    ls.inflYawPitchGlobal = parts.length > 3
                        && parts[3].equals("1");
                    // (fields 4-8 = the short-lived D235 split anchor
                    // — ignored since v19.7 D236, the hybrid model
                    // needs no ref frame)
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
                // straight/curve elements (v20.0, D259 §1.6): the
                // named-token `el` grammar — parseElement owns it
                case "el" -> {
                    if (!(curSection instanceof StraightCurveSection sc))
                        throw new IOException("el outside straight/curve section");
                    StraightCurveSection.Element e =
                        parseElement(parts, sc.elements.isEmpty());
                    sc.elements.add(e);
                    curElem = e;
                    curElemLane = false;
                }
                // the element's ROLL LANE lines (v20.1, D259 §2.8): `rn`
                // the lane's nodes (the first replaces the default lane),
                // `rsh` the shadow, `rcut` the fingerprint — closed by the
                // next line of any other kind (above)
                case "rn" -> {
                    if (curElem == null) throw new IOException("rn outside an element");
                    if (!curElemLane) {
                        curElem.roll.nodes.clear();
                        curElemLane = true;
                    }
                    curElem.roll.nodes.add(parseNode(parts));
                }
                case "rsh" -> {
                    if (curElem == null) throw new IOException("rsh outside an element");
                    if (curShadow == null) curShadow = new ArrayList<>();
                    curShadow.add(parseNode(parts));
                }
                case "rcut" -> {
                    if (curElem == null) throw new IOException("rcut outside an element");
                    curCut = parseCut(parts);
                }
                // the PRE-D259 `elem` lines (v11.0-v19.29): best-effort
                // (D259 §1.6 iii / §10.4) — the κ-form element with its
                // DEFAULT roll lane: the interim roll pair and element
                // 0's entry ease are DROPPED since Stage 1b (v20.1); each
                // s/c section that carried any counts ONCE into
                // legacyScSections (the load report's long sentence)
                case "elem" -> {
                    if (!(curSection instanceof StraightCurveSection sc))
                        throw new IOException("elem outside straight/curve section");
                    boolean first = sc.elements.isEmpty();
                    StraightCurveSection.Element e = switch (parts[1]) {
                        case "straight" -> StraightCurveSection.Element.straight(
                            Math.max(0.01, Double.parseDouble(parts[2])),
                            first ? 0 : Math.max(0, Double.parseDouble(parts[3])));
                        case "curve" -> StraightCurveSection.Element.curve(
                            Math.max(0.1, Double.parseDouble(parts[2])),
                            Double.parseDouble(parts[3]),
                            Double.parseDouble(parts[4]),
                            first ? 0 : Math.max(0, Double.parseDouble(parts[5])));
                        default -> throw new IOException("unknown element kind: " + parts[1]);
                    };
                    sc.elements.add(e);
                    curElem = e;
                    curElemLane = false;
                    if (sc != legacyScSec) {   // the section's first converted line
                        legacyScSec = sc;
                        legacyScSections++;
                    }
                }
                default -> { /* unknown lines ignored: forward compatibility */ }
            }
            } catch (RuntimeException | IOException damaged) {
                warnings++;   // skip the damaged line, keep the rest
            }
        }

        if (!v3) {
            // legacy: the flat file's start/v0 lines arrive in any order,
            // so apply them after the fact
            TrackPiece p = c.pieces.get(0);
            p.x = start[0]; p.y = start[1]; p.z = start[2];
            ((ForceSection) p.sections.get(0)).vDesign = v0;
        }
        // a file ending inside an element's roll block (truncated)
        if (curElem != null) {
            warnings += closeElement(curElem, curShadow, curCut);
        }
        c.v0 = v0;
        {
            int[] w = { 0 };
            int[] lsc = { 0 };   // the invariant's load pass (D259 §3.2)
            finishCoaster(c, legacy1, w, explicitFollow, lsc);
            warnings += w[0];
            scSeamsCut += lsc[0];
        }
        // the last block joins (an empty trailing block of a v4 file —
        // or an untouched one — is nothing); a v3 document with NOTHING
        // left is the one clean refusal, and it must happen HERE,
        // before the caller mutates the live document (D216); a v4
        // document may legally hold ZERO coasters (D230)
        if (!c.pieces.isEmpty()) {
            coasters.add(c);
            looks.add(new Cosmetics(heart, gauge, tie, solid,
                rail, grad, stops, latc, lats, null));
        } else if (!v4 && coasters.isEmpty()) {
            throw new IOException("no track data in file"
                + (warnings > 0 ? " (" + warnings + " damaged lines)" : ""));
        }
        return new LoadResult(coasters, looks, camera, warnings,
            legacyScSections, scSeamsCut);
    }

    /** FAIL-SOFT validation of one coaster (v18.44, D216 — was a
     *  whole-file refusal): a truncated tail leaves an element-less s/c
     *  section or an empty lane block — DROP the former, HEAL the
     *  latter (a 1-node lane is legal, D196; an empty one heals to a
     *  held constant — 1 g on a force Gv lane so a damaged file still
     *  loads as track, 0 elsewhere), count every repair, and drop
     *  sectionless pieces. Also DERIVES the END-FOLLOW flag (v19.19,
     *  D248) of every lane whose `lane` line had no 3rd field
     *  (explicitFollow = the lanes that carried one): a pristine hold
     *  (LaneSection.pristineHold — the shape every hold is created
     *  with) sitting AT the section end follows it like a freshly made
     *  one — but only while the DEFINING end (the lanes that will not
     *  follow) is the section end too, so the derived state is a
     *  FIXED POINT of LaneSection.syncFollowingEnds and the first
     *  follow write after opening moves nothing (D248 review fix: a
     *  pristine hold that is the sole longest lane must not follow —
     *  the sync would shrink it onto the shorter lanes; a hold short
     *  of the end never follows either — both would be a non-bitwise,
     *  undirtied load). Evaluated HERE, after the nodes loaded and
     *  after the FVDD1 handle conversion. A healed (1-node) lane can
     *  neither define nor follow. */
    private static void finishCoaster(Coaster c, boolean legacy1, int[] warnings,
                                      java.util.Set<List<BezierBaker.Node>> explicitFollow) {
        finishCoaster(c, legacy1, warnings, explicitFollow, new int[1]);
    }

    /** `legacySc[0]` += the seams the straight/curve invariant's load
     *  pass cut (D259 §3.2 "Load" / §10.4 — counted into the single
     *  SC_LEGACY_CONVERTED load sentence; the caller marks dirty). */
    private static void finishCoaster(Coaster c, boolean legacy1, int[] warnings,
                                      java.util.Set<List<BezierBaker.Node>> explicitFollow,
                                      int[] legacySc) {
        for (TrackPiece piece : c.pieces) {
            // FAIL-SOFT validation (v18.44, D216 — was a whole-file
            // refusal): a truncated tail leaves an element-less s/c
            // section or an empty lane block — DROP the former, HEAL
            // the latter (a 1-node lane is legal, D196; an empty one
            // heals to a held constant — 1 g on a force Gv lane so a
            // damaged file still loads as track, 0 elsewhere), and
            // count every repair for the caller's warning line.
            for (java.util.Iterator<Section> it = piece.sections.iterator();
                    it.hasNext();) {
                Section s = it.next();
                if (s instanceof BridgeSection) continue;   // bpose defaulted is fine
                if (s instanceof StraightCurveSection sc) {
                    if (sc.elements.isEmpty()) {
                        it.remove();
                        warnings[0]++;
                        continue;
                    }
                    // an element's EMPTY roll lane (every `rn` line of
                    // its block damaged) heals to the default lane
                    // (D216 / D259 §2.8 ii)
                    for (StraightCurveSection.Element e : sc.elements) {
                        if (e.roll.nodes.isEmpty()) {
                            e.roll.installNodes(BezierBaker.holdLane(0, e.L, 0));
                            warnings[0]++;
                        }
                    }
                    continue;
                }
                LaneSection fs = (LaneSection) s;
                for (int li = 0; li < fs.lanes.size(); li++) {
                    List<BezierBaker.Node> lane = fs.lanes.get(li);
                    if (lane.isEmpty()) {
                        lane.add(new BezierBaker.Node(0,
                            li == 0 && fs instanceof ForceSection ? 1 : 0));
                        warnings[0]++;
                    }
                }
                if (fs.influenced()) {
                    // delta lanes need only ONE node (flat zero is a
                    // single node at t=0); an empty block heals to it
                    for (List<BezierBaker.Node> dl : fs.deltaLanes) {
                        if (dl.isEmpty()) dl.add(new BezierBaker.Node(0, 0));
                    }
                }
                if (legacy1) convertLegacyHandles(fs);
                // END-FOLLOW derivation (D248) — AFTER the legacy handle
                // conversion (FVDD1 fractions are raw seconds before it).
                // The derived state must be a FIXED POINT of
                // LaneSection.syncFollowingEnds, or the first follow
                // write after opening would move a node — an undirtied,
                // non-bitwise load (the D197-D200 contract): a lane is a
                // CANDIDATE when it is a pristine hold sitting AT the
                // section end (a follower is never short of the end —
                // the sync keeps it there), and the candidates follow
                // only when the DEFINING end — the latest last node over
                // the lanes that will NOT follow (explicit-0 lanes,
                // edited lanes, holds short of the end) — IS the section
                // end. A candidate that is the sole longest lane defines
                // the end itself and must not follow, or the sync would
                // pull it back onto the shorter lanes (D248 review fix:
                // a '+' hold whose Gl node had been time-dragged short
                // derived 101 and SHRANK the section on the first redraw)
                double secEnd = BezierBaker.rideEnd(fs.lanes);
                int nl = fs.lanes.size();
                boolean[] cand = new boolean[nl];
                double defEnd = Double.NaN;
                for (int li = 0; li < nl; li++) {
                    List<BezierBaker.Node> lane = fs.lanes.get(li);
                    boolean follows;
                    if (explicitFollow.contains(lane)) {
                        // verbatim flags: a flagged lane that is no
                        // longer hold-shaped is what the sync's belt
                        // clears, so it DEFINES
                        follows = fs.endFollows[li]
                            && LaneSection.pristineHold(lane);
                    } else {
                        cand[li] = LaneSection.pristineHold(lane)
                            && Math.abs(lane.get(lane.size() - 1).t - secEnd)
                                <= 1e-9;
                        follows = cand[li];
                    }
                    if (follows || lane.size() < 2) continue;
                    double t = lane.get(lane.size() - 1).t;
                    defEnd = Double.isNaN(defEnd) ? t : Math.max(defEnd, t);
                }
                boolean defAtEnd = Double.isNaN(defEnd)
                    || Math.abs(defEnd - secEnd) <= 1e-9;
                for (int li = 0; li < nl; li++) {
                    if (explicitFollow.contains(fs.lanes.get(li))) continue;
                    fs.endFollows[li] = cand[li] && defAtEnd;
                }
            }
            // complex speed controls heal to a flat two-node graph
            // when their cn block is missing/degenerate (v18.0);
            // an untied ACCEL control's decel graph likewise (D217)
            for (Section s : piece.sections) {
                for (SpeedControl sc2 : s.speedControls()) {
                    if (sc2.complex && sc2.lane.size() < 2) {
                        sc2.freshLane(sc2.type == SpeedControl.ACCEL
                            ? sc2.accel : sc2.speed);
                    }
                    if (sc2.complex && !sc2.tied
                            && sc2.type == SpeedControl.ACCEL
                            && sc2.decelLane.size() < 2) {
                        sc2.freshDecelLane(sc2.decel);
                    }
                }
            }
        }
        // sectionless pieces drop (the caller decides what an empty
        // coaster means — a v3 file refuses, a v4 file just has fewer)
        int before = c.pieces.size();
        c.pieces.removeIf(p -> p.sections.isEmpty());
        warnings[0] += before - c.pieces.size();
        // THE STRAIGHT/CURVE INVARIANT's load pass (v20.1, D259 §3.2
        // "Load", §10.4 — D7 best-effort): a pre-D259 file holds its
        // s/c sections INSIDE a piece (an s/c at k > 0, a section after
        // an s/c); every such boundary becomes an automatic strict seam
        // — all axes follow, smoothing 0, the follower's first section
        // real-anchored — cut last-to-first per piece off ONE compile
        // (the Result serves every split when walked from the end).
        // Nothing moves at creation (the split copies the compiled
        // boundary pose — loads never move pieces, D88); the follower's
        // real-anchored first section re-settles ONCE on the first
        // rebuild (the k == 0 reconcile, §3.5 A4). A file with no such
        // boundary is untouched — no compile, every bit as before.
        legacySc[0] += scInvariantLoadPass(c);
    }

    /** The load pass itself (D259 §3.2): returns the seams cut. */
    static int scInvariantLoadPass(Coaster c) {
        if (c.scInvariantHolds()) return 0;
        TrackCompiler.Result r = TrackCompiler.compile(c, Coaster.INTEGRATE_DT);
        int cuts = 0;
        for (int p = c.pieces.size() - 1; p >= 0; p--) {
            List<Section> s = c.pieces.get(p).sections;
            for (int k = s.size() - 1; k >= 1; k--) {
                boolean scHere = s.get(k) instanceof StraightCurveSection;
                boolean scBefore = s.get(k - 1) instanceof StraightCurveSection;
                boolean bridgeHere = s.get(k) instanceof BridgeSection;
                if (!(scHere || (scBefore && !bridgeHere))) continue;
                if (c.ensureScFollower(p, k, r, false, false, false, 0) >= 0) {
                    cuts++;
                }
            }
        }
        return cuts;
    }

    /** SPEED CONTROLS (v18.0, D160): one `sctl` line per control —
     *  type (0 speed / 1 accel), complex flag, start, length, target
     *  speed, accel, min/max flags — followed by its complex graph's
     *  nodes as `cn` lines (the shared node grammar), plus `smark`
     *  lines for plain speed-line diamonds. Old readers ignore all
     *  three as unknown lines. */
    private static void writeSpeedCtls(PrintWriter out,
                                       List<SpeedControl> ctls,
                                       List<Double> marks) {
        for (SpeedControl c : ctls) {
            // fields 9-10 = decel + tied (v18.45, D217; missing =
            // tied with decel := accel — every old file); the decel
            // graph rides `cdn` lines, written even while tied
            // (session fidelity — the evaluator ignores it then)
            out.printf(Locale.US, "sctl %d %d %s %s %s %s %d %d %s %d%n",
                c.type, c.complex ? 1 : 0, d(c.x0), d(c.len), d(c.speed),
                d(c.accel), c.minLimit ? 1 : 0, c.maxLimit ? 1 : 0,
                d(c.decel), c.tied ? 1 : 0);
            for (BezierBaker.Node n : c.lane) writeNode(out, "cn", n);
            for (BezierBaker.Node n : c.decelLane) writeNode(out, "cdn", n);
        }
        for (double m : marks) {
            out.printf(Locale.US, "smark %s%n", d(m));
        }
    }

    /** ONE straight/curve element as an `el` line (v20.0, D259 §1.6):
     *  the KIND word is the one positional token, every other token is
     *  name=value in this FIXED order (byte-canonical round trips) and
     *  every double goes through d(). STRAIGHT carries no curvature
     *  fields (κ ≡ 0), PLANAR no elevation fields, HELICAL no dir.
     *  kint/bint: 0 = LINEAR, 1 = SINUSOIDAL. Then the element's ROLL
     *  settings `rollg= ratem= rollabs=` on EVERY line (defaults too —
     *  byte-canonical round trips, v20.1 D259 §2.8), the lane's `rn`
     *  node lines (the writeNode grammar verbatim), and `rsh` (the
     *  shadow) + `rcut` (the fingerprint) iff the lane is cut; element
     *  0's blend is emitted as 0 (§1.1). The Stage-1a rm=/rv= pair is
     *  no longer written (the loader migrates it best-effort). Old
     *  readers see `el` as an unknown line and drop the element-less
     *  section (§1.6 v); rn/rsh/rcut are unknown lines there. */
    private static void writeElement(PrintWriter out,
                                     StraightCurveSection.Element e, int idx) {
        int kint = e.kInterp == StraightCurveSection.Interp.SINUSOIDAL ? 1 : 0;
        double blend = idx == 0 ? 0.0 : e.blendIn;
        RollLane rl = e.roll;
        String roll = String.format(Locale.US, " rollg=%d ratem=%d rollabs=%d",
            rl.rollGlobal() ? 1 : 0, rl.ratePerMeter() ? 1 : 0,
            rl.rollAbsolute() ? 1 : 0);
        switch (e.kind) {
            case STRAIGHT -> out.printf(Locale.US,
                "el straight L=%s blend=%s locks=%d%s%n",
                d(e.L), d(blend), e.locks, roll);
            case PLANAR -> out.printf(Locale.US,
                "el planar L=%s k0=%s k1=%s klink=%d kint=%d dir=%s"
                    + " blend=%s locks=%d%s%n",
                d(e.L), d(e.k0), d(e.k1), e.kLink ? 1 : 0, kint,
                d(e.dirDeg), d(blend), e.locks, roll);
            case HELICAL -> out.printf(Locale.US,
                "el helical L=%s k0=%s k1=%s klink=%d kint=%d b1=%s"
                    + " blink=%d bint=%d blend=%s locks=%d%s%n",
                d(e.L), d(e.k0), d(e.k1), e.kLink ? 1 : 0, kint,
                d(e.b1Deg), e.bLink ? 1 : 0,
                e.bInterp == StraightCurveSection.Interp.SINUSOIDAL ? 1 : 0,
                d(blend), e.locks, roll);
        }
        for (BezierBaker.Node n : rl.nodes) writeNode(out, "rn", n);
        if (rl.isCut()) {
            for (BezierBaker.Node n : rl.shadow()) writeNode(out, "rsh", n);
            RollLane.Cut c = rl.cut();
            out.printf(Locale.US, "rcut ins=%d t=%s v=%s si=%s so=%s sm=%d po=%s%n",
                c.inserted() ? 1 : 0, d(c.t()), d(c.val()), d(c.inSlope()),
                d(c.outSlope()), c.smooth() ? 1 : 0, d(c.prevOutSlope()));
        }
    }

    /** An `rcut` line's fingerprint (writeElement's dual): name=value
     *  tokens by name, every number finite (else a damaged line); a
     *  missing t is damaged too. */
    private static RollLane.Cut parseCut(String[] parts) throws IOException {
        boolean ins = false, sm = true;
        double t = Double.NaN, v = 0, si = 0, so = 0, po = 0;
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            String name = parts[i].substring(0, eq);
            String val = parts[i].substring(eq + 1);
            switch (name) {
                case "ins" -> ins = val.equals("1");
                case "t" -> t = finiteNum(val);
                case "v" -> v = finiteNum(val);
                case "si" -> si = finiteNum(val);
                case "so" -> so = finiteNum(val);
                case "sm" -> sm = !val.equals("0");
                case "po" -> po = finiteNum(val);
                default -> { /* unknown names ignored */ }
            }
        }
        if (Double.isNaN(t)) throw new IOException("rcut without t");
        return new RollLane.Cut(ins, t, v, si, so, sm, po);
    }

    /** Close an element's roll block (§2.8 ii): install the shadow +
     *  fingerprint when BOTH are present and the fingerprint's t is the
     *  lane's last node's t bitwise; otherwise drop whatever half
     *  exists. Returns the damaged-line count (0 or 1). */
    private static int closeElement(StraightCurveSection.Element e,
                                    List<BezierBaker.Node> shadow, RollLane.Cut cut) {
        if (shadow == null && cut == null) return 0;
        return e.roll.installShadow(shadow, cut) ? 0 : 1;
    }

    /** An `el` line's element (v20.0, D259 §1.6 — writeElement's
     *  dual). parts[1] = the kind word (unknown → IOException: a
     *  damaged line under the D216 fail-soft); the kind's factory
     *  supplies every default (L placeholder 0.01), then every
     *  remaining token parses as name=value BY NAME, split at the
     *  first '=': unknown names — and names the kind does not carry
     *  (a straight keeps κ ≡ 0) — are ignored and a missing name keeps
     *  the default, so the line can grow. The clamps ADMIT everything
     *  the writer emits (D198): L ≥ 0.01, |k0|, |k1| ≤ KAPPA_MAX,
     *  |b1| ≤ B1_MAX, blend ≥ 0, locks masked to the defined bits,
     *  kint/bint/klink/blink anything but "1" = the 0 meaning. A
     *  NON-FINITE number is a damaged line (b1 per §1.6 ii; the others
     *  too — clamp() lets NaN through, the D216 lesson). ROLL (v20.1,
     *  D259 §2.8): the default lane over [0, L] (the `rn` lines that
     *  follow replace it), flags from rollg/ratem/rollabs when any is
     *  present (rollabs=1 forces rollg=1); a Stage-1a line's rm=/rv=
     *  pair MIGRATES best-effort (§2.3.6: rm=0 with rv ≠ 0 → a LOCAL
     *  °/s flat lane at rv; rm=1 with rv ≠ 0 → an ABSOLUTE bank lane at
     *  rv; rv == 0 → the default) and the inert slots stay zero;
     *  first = element 0, whose blend is forced to 0 (§1.1). */
    private static StraightCurveSection.Element parseElement(String[] parts,
                                                             boolean first)
            throws IOException {
        if (parts.length < 2) throw new IOException("el without a kind");
        StraightCurveSection.Element e = switch (parts[1]) {
            case "straight" -> StraightCurveSection.Element.straight(0.01);
            case "planar" -> StraightCurveSection.Element.planar(0.01, 0, 0);
            case "helical" -> StraightCurveSection.Element.helical(0.01, 0, 0.0);
            default -> throw new IOException("unknown element kind: " + parts[1]);
        };
        boolean curved = e.kind != StraightCurveSection.Kind.STRAIGHT;
        boolean planar = e.kind == StraightCurveSection.Kind.PLANAR;
        boolean helix = e.kind == StraightCurveSection.Kind.HELICAL;
        double kMax = StraightCurveSection.KAPPA_MAX;
        double bMax = StraightCurveSection.B1_MAX;
        boolean anyRoll = false, rollG = true, rateM = true, rollAbs = false;
        int rm = -1;
        double rv = 0;
        for (int i = 2; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;   // not name=value: ignored like an unknown name
            String name = parts[i].substring(0, eq);
            String v = parts[i].substring(eq + 1);
            switch (name) {
                case "L" -> e.L = Math.max(0.01, finiteNum(v));
                case "k0" -> { if (curved) e.k0 = clamp(finiteNum(v), -kMax, kMax); }
                case "k1" -> { if (curved) e.k1 = clamp(finiteNum(v), -kMax, kMax); }
                case "klink" -> { if (curved) e.kLink = v.equals("1"); }
                case "kint" -> {
                    if (curved) e.kInterp = v.equals("1")
                        ? StraightCurveSection.Interp.SINUSOIDAL
                        : StraightCurveSection.Interp.LINEAR;
                }
                case "dir" -> { if (planar) e.dirDeg = finiteNum(v); }
                case "b1" -> { if (helix) e.b1Deg = clamp(finiteNum(v), -bMax, bMax); }
                case "blink" -> { if (helix) e.bLink = v.equals("1"); }
                case "bint" -> {
                    if (helix) e.bInterp = v.equals("1")
                        ? StraightCurveSection.Interp.SINUSOIDAL
                        : StraightCurveSection.Interp.LINEAR;
                }
                case "blend" -> e.blendIn = Math.max(0, finiteNum(v));
                case "locks" -> e.locks = Integer.parseInt(v)
                    & StraightCurveSection.Element.LOCK_MASK;
                case "rollg" -> { rollG = v.equals("1"); anyRoll = true; }
                case "ratem" -> { rateM = v.equals("1"); anyRoll = true; }
                case "rollabs" -> { rollAbs = v.equals("1"); anyRoll = true; }
                case "rm" -> rm = v.equals("1") ? 1 : 0;
                case "rv" -> rv = finiteNum(v);
                default -> { /* unknown names ignored: the line can grow */ }
            }
        }
        if (first) e.blendIn = 0;
        // the default lane over the PARSED length (the factory built it
        // over the 0.01 placeholder); then the flags or the migration
        e.roll = new RollLane(e.L);
        if (anyRoll) {
            e.roll.setFlags(rollG, rateM, rollAbs);
        } else if (rm >= 0 && rv != 0) {
            e.roll.installNodes(BezierBaker.holdLane(0, e.L, rv));
            if (rm == 0) e.roll.setFlags(false, false, false);   // LOCAL °/s at rv
            else e.roll.setFlags(true, true, true);              // ABSOLUTE bank at rv
        }
        return e;
    }

    /** A finite double of an `el` field, else IOException (a damaged
     *  line — D216 counts it). */
    private static double finiteNum(String v) throws IOException {
        double x = Double.parseDouble(v);
        if (!Double.isFinite(x)) throw new IOException("non-finite element field: " + v);
        return x;
    }

    /** One node line in the shared `n`/`dn` grammar (9 fields; the
     *  8th/9th lock fields ride the ignore-extra-fields rule). */
    private static void writeNode(PrintWriter out, String tag, BezierBaker.Node n) {
        out.printf(Locale.US, "%s %s %s %s %s %s %s %d %d %d%n",
            tag, d(n.t), d(n.val), d(n.outFrac), d(n.outDv), d(n.inFrac),
            d(n.inDv), n.smooth ? 1 : 0, n.straightOut ? 1 : 0,
            n.levelOut ? 1 : 0);
    }

    /** BIT-EXACT double serialization (v18.26, D198): the shortest
     *  decimal that parses back to the SAME double bits, so a loaded
     *  document compiles bitwise-identically to the session that
     *  saved it — the determinism contract's save/load leg. Locale-
     *  independent ('.' always); E-notation parses fine everywhere. */
    private static String d(double v) {
        return Double.toString(v);
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

    /** The mutable speed-control list of a lane/sc section (null =
     *  the section mode carries none). */
    private static List<SpeedControl> ctlListOf(Section s) {
        if (s instanceof LaneSection ls) return ls.speedCtls;
        if (s instanceof StraightCurveSection sc) return sc.speedCtls;
        return null;
    }

    private static List<Double> markListOf(Section s) {
        if (s instanceof LaneSection ls) return ls.speedMarks;
        if (s instanceof StraightCurveSection sc) return sc.speedMarks;
        return null;
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
                // zero-length segments (damaged legacy file) take the
                // default fraction — 0/0 was a NaN that rode straight
                // through clamp() into the baker (D216)
                double segR = i < lane.size() - 1
                    ? lane.get(i + 1).t - n.t : 0;
                double segL = i > 0 ? n.t - lane.get(i - 1).t : 0;
                n.outFrac = segR > 1e-9
                    ? clamp(outDt / segR, 0, BezierBaker.MAX_FRAC)
                    : BezierBaker.DEFAULT_FRAC;
                n.inFrac = segL > 1e-9
                    ? clamp(-inDt / segL, 0, BezierBaker.MAX_FRAC)
                    : BezierBaker.DEFAULT_FRAC;
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

}
