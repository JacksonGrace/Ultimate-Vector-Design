package uvd;

import java.util.List;

/** SECTION + JUNCTION NAMING (v19.19, D248 — user spec). Pure Java,
 *  no JavaFX, so LabelGate pins every string headlessly; GraphEditor's
 *  sectionTitle / junctionTitle / ownerButtonLabel wrap these on the
 *  active coaster.
 *
 *  SECTION TITLES are numbered PER TYPE across the WHOLE RUN in run
 *  order (the piece list IS the timeline, D73): "Force Section 1",
 *  "Geometric Section 1", "Geometric Section 2", "S/C Section 1",
 *  "Bridge Section 1". Flags, domain, influence and speed controls
 *  never change the type word — they stay badge TAGS (tags()).
 *  Numbering restarts per run (a detached piece is its own run).
 *
 *  JUNCTIONS are every piece boundary INSIDE a run — plain strict
 *  seams AND bridge exits, counted together in run order:
 *  "Strict Seam n" / "Strict Seam n (bridge)". The run head has none
 *  (ordinal 0, title null).
 *
 *  OWNER BUTTON LABEL = exactly the toolbar text that owns a
 *  section's placement: "Track Piece ▴" while no junction lies left
 *  of the section, else the junction's title + " ▴"; a BRIDGE section
 *  is owned by its own EXIT junction (the "(bridge)" form — for an
 *  orphan bridge the would-be exit's ordinal, which item W grays).
 *
 *  The DRAWN badge (v19.20, D249 — the D248 numbered/title ladder
 *  REVERTED on user hand test) is badgeLabel: the SHORT type word +
 *  tags ("Geo ·m ◀ fx", "Bridge !"), GraphEditor appending the
 *  conflict glyphs; the numbered sectionTitle stays the badge MENU's
 *  bold title row and the dialogs' titles. The bridge's badge-menu
 *  owner item is BRIDGE_SETTINGS_ITEM and its window title
 *  bridgeWindowTitle ("Bridge Settings (Strict Seam n)") — the
 *  toolbar button keeps ownerButtonLabel's "(bridge) ▴" form. */
public final class SectionNames {

    private SectionNames() {}

    /** The toolbar button that owns a run head's placement. */
    public static final String TRACK_PIECE_BUTTON = "Track Piece ▴";
    /** Junction title prefix. */
    public static final String STRICT_SEAM = "Strict Seam ";
    /** The bridge-exit qualifier. */
    public static final String BRIDGE_SUFFIX = " (bridge)";
    /** The bridge window's name (v19.20, D249 — user spec: the badge
     *  menu's owner item and the window title; the toolbar button
     *  keeps the junction form). */
    public static final String BRIDGE_SETTINGS = "Bridge settings";
    /** The bridge section's badge-menu owner item. */
    public static final String BRIDGE_SETTINGS_ITEM = BRIDGE_SETTINGS + "…";

    // ---- resolve ops behind a BRIDGE (batch 37) ----
    /** The hint closing every "an optimizing bridge blocks this
     *  resolve" reason (user wording). */
    public static final String BRIDGE_RESOLVE_HINT =
        " — convert the bridge first or set it to \"No optimization.\"";

    /** Why the badge's two resolve ops gray on the FIRST section of a
     *  piece a bridge leads into, by that bridge's placement mode;
     *  null = allowed (No optimization: the piece's entry never moves
     *  and the bridge re-captures its target every compile). */
    public static String bridgeResolveWhy(int optMode) {
        if (optMode == BridgeSection.OPT_NONE) return null;
        return (optMode == BridgeSection.OPT_POS_YAW
            ? "The bridge before this section re-places and re-aims"
                + " this piece after any resolution"
            : "The bridge before this section re-places this piece"
                + " after any resolution") + BRIDGE_RESOLVE_HINT;
    }

    /** Below this real speed off a bridge (m/s) the resolve ops on the
     *  piece it leads into REFUSE (batch 37, review round 4): a piece
     *  re-solved at a crawl has its head curvature grow as 1/v², and
     *  a "No optimization" bridge absorbs that head into its septic —
     *  at 0.55 m/s the bridge became 940 km of track and the renderer
     *  ran out of memory. Pass D's stall floor is 0.1; the reconcile's
     *  own floor 0.5. */
    public static final double BRIDGE_CRAWL_SPEED = 1.0;

    /** Why the resolve ops refuse on the first section of a piece a
     *  bridge leads into, by the REAL speed at the bridge's end; null
     *  = the train arrives at a usable speed. Two sentences: stalled
     *  (never reaches the piece — Pass D's 0.1 floor) and crawling
     *  (arrives, but under BRIDGE_CRAWL_SPEED). */
    public static String bridgeCrawlWhy(double realEnd) {
        if (realEnd <= 0.1) {
            return "The train stalls on the bridge before this track "
                + "piece — there is no real entry speed to take.";
        }
        if (realEnd < BRIDGE_CRAWL_SPEED) {
            return String.format(java.util.Locale.US, "The train crawls "
                + "off the bridge at %.2f m/s — re-solving this track "
                + "piece at that speed would blow the bridge up; raise "
                + "the bridge or the entry speed first.", realEnd);
        }
        return null;
    }

    /** The model's refusal when a write DID blow the bridge up (the
     *  belt behind bridgeCrawlWhy: the arc it re-solved to, against
     *  the arc it had). */
    public static String bridgeBlowUpWhy(double v, double arcNow,
                                         double arcWas) {
        return String.format(java.util.Locale.US, "at %.2f m/s the "
            + "bridge re-solves to %.0f m of track (it was %.0f m) — "
            + "raise the bridge or the entry speed first.", v, arcNow,
            arcWas);
    }

    /** A later section (k > 0) of a piece an optimize-ORIENTATION
     *  bridge leads into, orientation conflict. */
    public static final String MARCH_OWNS_RESOLVE =
        "The optimize-orientation bridge owns this piece's pitch/roll"
        + " — convert the bridge first or set it to \"Optimize"
        + " position\" or \"No optimization.\"";

    /** Real → Design's gray reason in the Track Piece / bridge
     *  windows under an optimize-orientation bridge. */
    public static final String MARCH_OWNS_R2D =
        "The optimize-orientation bridge owns this piece's pitch/roll"
        + " — set the bridge to \"Optimize position\" or \"No"
        + " optimization.\"";

    // ---- the Track Piece ops row, scoped to the RUN (batch 37) ----
    /** Reverse's gray reason on a run of more than one piece. */
    public static final String REVERSE_MULTI_WHY =
        "Reverse works on a track piece without strict seams or bridges —"
        + " reversing a whole track piece is not available yet.";
        // (v20.1, D259 §3.10 — the old "un-strict its seams" advice is
        // forbidden by L4: a straight/curve edge is always a strict seam)
    /** Reverse's gray reason on a single piece with speed controls
     *  (batch 38, D256 — replaces the literal "Remove the speed
     *  controls first."). */
    public static final String REVERSE_CONTROLS_WHY =
        "Reverse works on a track piece without speed controls —"
        + " remove its speed controls first.";
    /** A run of several pieces that ALSO carries speed controls
     *  (batch 38 — the user's combined wording: the sentence names
     *  both). */
    public static final String REVERSE_MULTI_CONTROLS_WHY =
        "Reverse works on a track piece without strict seams, bridges or"
        + " speed controls — reversing a whole track piece is not available"
        + " yet.";   // (v20.1, D259 §3.10 — see REVERSE_MULTI_WHY)
    /** A single piece ending in an ORPHAN bridge (batch 38 — the
     *  "Reverse works on …" form of "Delete or convert the bridge
     *  first."). */
    public static final String REVERSE_BRIDGE_WHY =
        "Reverse works on a track piece without bridges — delete or"
        + " convert its bridge first.";
    /** … that also carries speed controls. */
    public static final String REVERSE_BRIDGE_CONTROLS_WHY =
        "Reverse works on a track piece without bridges or speed"
        + " controls — delete or convert its bridge and remove its"
        + " speed controls first.";

    /** Reverse's gray reason for the active run (batch 38, D256 — the
     *  ONE chooser; FvdDesigner's busy / no-piece reasons come before
     *  it): null = Reverse is allowed. Precedence multi (a run of
     *  several pieces — "bridges" covers an orphan tail too) →
     *  orphanTail (a single piece ending in an orphan bridge) →
     *  controls; speed controls are APPENDED to the first two
     *  sentences rather than hidden behind them. */
    public static String reverseWhy(boolean multi, boolean orphanTail,
                                    boolean controls) {
        if (multi) return controls ? REVERSE_MULTI_CONTROLS_WHY
                                   : REVERSE_MULTI_WHY;
        if (orphanTail) return controls ? REVERSE_BRIDGE_CONTROLS_WHY
                                        : REVERSE_BRIDGE_WHY;
        return controls ? REVERSE_CONTROLS_WHY : null;
    }

    // ---- the badge's conflict glyphs (batch 38, D256) ----
    /** The SPEED-conflict glyph: the section enters more than
     *  Gestures.DRIFT_EPS off its design speed. */
    public static final String SPEED_GLYPH = " ≠v";
    /** The SLIGHT speed-conflict glyph (user spec): the entry gap
     *  shows in the initial design speed box's two decimals (more
     *  than Gestures.INIT_SPEED_SHOW_EPS) but stays within the
     *  conflict threshold. */
    public static final String SLIGHT_SPEED_GLYPH = " ~v";
    /** The ORIENTATION-conflict glyph (lowercase o since D248). */
    public static final String ORIENT_GLYPH = " ≠o";

    /** The three-state speed-conflict LEVEL of an entry gap (m/s):
     *  0 = nothing to show (NaN, or at most INIT_SPEED_SHOW_EPS — the
     *  box's %.2f cannot display it), 1 = slight (over that, at most
     *  DRIFT_EPS — " ~v"), 2 = the conflict (" ≠v", bitwise the
     *  historic gap > DRIFT_EPS test). The exact 0.005 and 0.2 are
     *  the LOWER levels (≤), matching Gestures.speedDiffers's strict
     *  greater-than. */
    public static int speedLevel(double gap) {
        if (Double.isNaN(gap) || gap <= Gestures.INIT_SPEED_SHOW_EPS) return 0;
        return gap <= Gestures.DRIFT_EPS ? 1 : 2;
    }

    /** The badge glyph for an entry gap: "" / SLIGHT_SPEED_GLYPH /
     *  SPEED_GLYPH by speedLevel. */
    public static String speedGlyph(double gap) {
        int lv = speedLevel(gap);
        return lv == 0 ? "" : lv == 1 ? SLIGHT_SPEED_GLYPH : SPEED_GLYPH;
    }

    /** Switch Coaster's refusal when the DESTINATION coaster's last
     *  piece ends in an orphan bridge (appending the run would hand
     *  that bridge a target — the Copy rule). */
    public static final String SWITCH_DEST_ORPHAN_WHY =
        "That coaster's last track piece ends in an orphaned bridge"
        + " — delete or convert it first.";
    /** The toolbar buttons' arrow (the windows open ABOVE the graph). */
    public static final String OWNER_ARROW = " ▴";

    // ---- the SMOOTHING OPTIONS window (v19.27, D256) ----
    /** The window's TITLE word (title case like "Bridge Settings"). */
    public static final String SMOOTH_TITLE = "Smoothing Options";
    /** The seam menu's item while the seam smooths (sentence case,
     *  the "Bridge settings…" pattern). */
    public static final String SMOOTH_OPTIONS_ITEM = "Smoothing options…";
    /** The seam menu's item while the seam does NOT smooth (the D141
     *  toggle's historic label). */
    public static final String ENABLE_SMOOTHING_ITEM =
        "Enable smoothing (10 m)";
    /** The window's length row. */
    public static final String SMOOTH_LEN_LABEL = "Smoothing length (m)";
    /** The window's two action buttons (the former menu items'
     *  labels, verbatim). */
    public static final String SMOOTH_REAL_BUTTON = "Make smoothing real…";
    public static final String SMOOTH_DISABLE_BUTTON = "Disable smoothing";
    /** The window's self-teardown reason. */
    public static final String SMOOTH_WINDOW_CLOSED =
        "The smoothing options window closed — its seam no longer"
        + " smooths.";
    /** The badge's D141 line for a speed conflict the smoothing itself
     *  opened — the D255 sentence verbatim (no period, a disabled
     *  MenuItem's text; its D256 mode-aware twin went with the
     *  translational mode in D257). */
    public static final String SMOOTH_SHRINK_WHY =
        "Speed conflict caused by seam smoothing — shrink the ◇ window"
        + " instead";
    /** The initial-speed window's smoothing-opened sentence (the D255
     *  text verbatim; its D256 translational twin is gone, D257). */
    public static final String INIT_SMOOTH_SHRINK_WHY =
        "Seam smoothing opens this speed difference — shrink the ◇"
        + " window instead.";

    /** "Smoothing Options (Strict Seam n)" — n = the follower's own
     *  junction (junctionOrdinal); "Smoothing Options" at a run head
     *  (never shown there — the window opens on followers only). */
    public static String smoothWindowTitle(Coaster c, int followerIdx) {
        int n = junctionOrdinal(c, followerIdx);
        return n == 0 ? SMOOTH_TITLE
            : SMOOTH_TITLE + " (" + STRICT_SEAM + n + ")";
    }

    // ---- the STRAIGHT/CURVE OVERHAUL (v20.0, D259 §1.8 — LabelGate
    // section 18) ----
    /** The ONE helix-on-vertical refusal (D259 §1.1): creation, insert,
     *  change type, the ghost, the "Helical (3)" pick. */
    public static final String HELIX_VERTICAL_WHY =
        "A helix can't start on vertical track — add a curve first.";
    /** A planar element's direction refusal after a VERTICAL entry
     *  (D259 §1.2 / §1.8): no level frame there, so its plane is the
     *  previous element's roll-free end frame. */
    public static final String DIR_INHERITED_WHY =
        "This element starts on vertical track — its plane is inherited"
        + " from the element before it.";
    /** A helix's end elevation past B1_MAX (D6's < 90°; D259 §4.14's
     *  register). */
    public static final String HELIX_ELEV_WHY =
        "A helix's end elevation must stay under 90° — use a planar"
        + " pull-up for a loop.";
    /** The load report's line when old `elem` lines were converted
     *  (D259 §1.8 / §10.4 — the LONG form since Stage 1b, v20.1: the
     *  interim roll pair and element 0's entry ease are DROPPED, the
     *  lane starts at its default). */
    public static final String SC_LEGACY_CONVERTED =
        "Old straight/curve elements were converted — their roll settings"
        + " and entry ease were dropped.";
    /** The radius twin of a curvature under KAPPA_INF (ρ > 100 km). */
    public static final String INFINITY_TWIN = "∞";

    // ---- the ROLL LANE per s/c element (v20.1, D259 §2.10 — LabelGate
    // section 19; the editor sites of §2.4-§2.6 consume them) ----
    /** The yaw/pitch lane buttons / rows over an s/c (mode 3). */
    public static final String SC_LANES_WHY =
        "Straight/curve sections author their shape as elements — only"
        + " the roll lane exists.";
    /** A at the lane's end wall / a drag past it (the wall is HARD). */
    public static final String ROLL_WALL_WHY =
        "The roll lane ends at the element end — resize the element to"
        + " extend it.";
    /** The Domain row over an s/c (D30). */
    public static final String SC_DISTANCE_WHY =
        "Straight/curve sections are always distance-based.";
    /** The Local radio while Absolute bank is selected. */
    public static final String ABS_NEEDS_GLOBAL_WHY =
        "Absolute bank holds a bank against the horizon — it needs the"
        + " global roll axis.";
    /** The Roll block's two buttons + their one-sentence tooltips. */
    public static final String ROLL_SWITCH_BUTTON = "Switch to These Settings";
    public static final String ROLL_BAKE_BUTTON = "Bake These Settings";
    /** Their gray captions under the buttons (his 2026-10-21 round-2
     *  note 3 — the parentheticals moved out of the labels, the
     *  Orientation settings dialog's pattern). */
    public static final String ROLL_SWITCH_CAP = "same graph, change bank";
    public static final String ROLL_BAKE_CAP = "convert graph, keep bank";
    public static final String ROLL_SWITCH_TIP =
        "Reinterprets this element's roll graph under the new settings.";
    public static final String ROLL_BAKE_TIP =
        "Converts this element's roll graph so the bank stays the same.";
    /** The Roll block's title. */
    public static final String ROLL_BLOCK = "Roll";
    /** The track double-click that switched the lane element instead
     *  of inserting. */
    public static final String SC_TRACK_DBLCLICK_WHY =
        "Switched the roll lane to this element — double-click again to"
        + " insert a node.";
    /** The Roll block's Switch / Bake buttons while the radios equal
     *  the element's current settings. */
    public static final String ROLL_PICK_WHY =
        "Pick different roll settings first.";
    /** The Rate-units row under Absolute bank, and the badge menu's
     *  rate-view item over a bank lane. */
    public static final String BANK_NO_RATE_WHY =
        "A bank lane holds angles — there is no rate to view.";

    /** An element kind's word (D259 §1.8): Straight / Planar / Helical. */
    public static String kindWord(StraightCurveSection.Kind k) {
        return switch (k) {
            case STRAIGHT -> "Straight";
            case PLANAR -> "Planar";
            case HELICAL -> "Helical";
        };
    }

    /** An interpolation's word (D259 §1.8): Linear / Sinusoidal. */
    public static String interpWord(StraightCurveSection.Interp i) {
        return switch (i) {
            case LINEAR -> "Linear";
            case SINUSOIDAL -> "Sinusoidal";
        };
    }

    /** The full type word: Force / Geometric / S/C / Bridge. */
    public static String typeWord(Section s) {
        return s instanceof GeoSection ? "Geometric"
             : s instanceof StraightCurveSection ? "S/C"
             : s instanceof BridgeSection ? "Bridge" : "Force";
    }

    /** The badge's SHORT type word (the narrow-strip form). */
    public static String shortWord(Section s) {
        return s instanceof GeoSection ? "Geo"
             : s instanceof StraightCurveSection ? "S/C"
             : s instanceof BridgeSection ? "Bridge" : "Force";
    }

    /** The badge TAGS after a title, in the fixed order: " !" (a
     *  dirty/orphan bridge), " ·m" (distance-domain lanes), " ◀"
     *  (the legacy reversed flag), " fx" (influenced). The conflict
     *  glyphs ~v / ≠v / ≠o follow these (GraphEditor adds them —
     *  speedGlyph / ORIENT_GLYPH). */
    public static String tags(Section sec, boolean dirty) {
        return (sec instanceof BridgeSection && dirty ? " !" : "")
            + (sec instanceof LaneSection
                && sec.domain() == Section.Domain.DISTANCE ? " ·m" : "")
            + (sec.reversed() ? " ◀" : "")
            + (sec instanceof LaneSection li && li.influenced()
                ? " fx" : "");
    }

    private static Section at(Coaster c, int p, int k) {
        if (c == null || p < 0 || p >= c.pieces.size()) return null;
        List<Section> secs = c.pieces.get(p).sections;
        return k < 0 || k >= secs.size() ? null : secs.get(k);
    }

    /** 1-based ordinal of (p, k) among the sections of ITS TYPE from
     *  the run head through (p, k); 0 when (p, k) doesn't exist. */
    public static int sectionOrdinal(Coaster c, int p, int k) {
        Section target = at(c, p, k);
        if (target == null) return 0;
        String tw = typeWord(target);
        int n = 0;
        for (int q = c.runStartOf(p); q <= p; q++) {
            List<Section> secs = c.pieces.get(q).sections;
            int lim = q == p ? k + 1 : secs.size();
            for (int j = 0; j < lim; j++) {
                if (typeWord(secs.get(j)).equals(tw)) n++;
            }
        }
        return n;
    }

    /** "Force Section n" etc.; "Section" alone for a missing one. */
    public static String sectionTitle(Coaster c, int p, int k) {
        Section s = at(c, p, k);
        if (s == null) return "Section";
        return typeWord(s) + " Section " + sectionOrdinal(c, p, k);
    }

    /** The DRAWN badge's text before the conflict glyphs (v19.20,
     *  D249 — the pre-D248 form back: "Geo ·m ◀ fx", "Bridge !",
     *  "S/C"; no number, no title; the tags SPACED as tags() spells
     *  them — the pre-D248 badge glued "Geo·m"). GraphEditor appends
     *  speedGlyph (" ~v" / " ≠v") + ORIENT_GLYPH. (The D248 shortTitle
     *  "Geo 2" is gone.) */
    public static String badgeLabel(Section sec, boolean dirty) {
        return shortWord(sec) + tags(sec, dirty);
    }

    /** The bridge window's title (v19.20, D249): "Bridge settings
     *  (Strict Seam n)" — n = the bridge's own EXIT junction, the
     *  same number as ownerButtonLabel's "Strict Seam n (bridge) ▴"
     *  (an orphan's would-be exit included). */
    /** The bridge window's TITLE word (batch 36, D254 — title case like
     *  "Track Piece" / "Strict Seam n"; the badge-menu item keeps its
     *  sentence case). */
    public static final String BRIDGE_TITLE = "Bridge Settings";

    public static String bridgeWindowTitle(Coaster c, int bp) {
        return BRIDGE_TITLE + " (" + STRICT_SEAM
            + (junctionOrdinal(c, bp) + 1) + ")";
    }

    /** 0 = piece p is its run's head (no junction); else the 1-based
     *  index of the boundary entering p among the run's boundaries
     *  (bridge exits and strict seams alike). p itself is returned
     *  as-is by runStartOf out of range, so the ordinal is 0 there. */
    public static int junctionOrdinal(Coaster c, int p) {
        if (c == null || p < 0 || p >= c.pieces.size()) return 0;
        return p - c.runStartOf(p);
    }

    /** "Strict Seam n" / "Strict Seam n (bridge)" for the boundary
     *  entering piece p; null at a run head. The bridge test runs
     *  FIRST: a bridged-in piece with a stale followPrev flag is a
     *  bridge exit (followsPrev is false there by the D135 rule). */
    public static String junctionTitle(Coaster c, int p) {
        int n = junctionOrdinal(c, p);
        if (n == 0) return null;
        return STRICT_SEAM + n + (c.bridgedInto(p) ? BRIDGE_SUFFIX : "");
    }

    /** EXACTLY the toolbar text that owns (p, k)'s placement — see
     *  the class comment. A missing section reads as a run head. */
    public static String ownerButtonLabel(Coaster c, int p, int k) {
        Section s = at(c, p, k);
        if (s instanceof BridgeSection) {
            // the bridge's own EXIT junction: p+1 sits in the same
            // run (endsWithBridge(p) links it), so its ordinal is one
            // past p's — the same number whether the target exists
            // (live) or not (orphan, the would-be exit)
            return STRICT_SEAM + (junctionOrdinal(c, p) + 1) + BRIDGE_SUFFIX
                + OWNER_ARROW;
        }
        String jt = junctionTitle(c, p);
        return jt == null ? TRACK_PIECE_BUTTON : jt + OWNER_ARROW;
    }

    // ---- the seam invariant (v20.1, D259 §3.10 — LabelGate section 20) ----
    /** Un-strict / absorb refused on an s/c‖s/c seam (L4). */
    public static final String UNSTRICT_SC_WHY =
        "A straight/curve section always begins and ends at a strict seam"
        + " — connect the sections instead.";
    /** Un-strict / absorb refused on a seam with a straight/curve
     *  section on ONE side (L4). */
    public static final String UNSTRICT_CONVERT_WHY =
        "A straight/curve section always begins and ends at a strict seam"
        + " — convert it first.";
    /** The smoothing window would not engage: too few samples
     *  (TrackCompiler.smoothEngagesWhy, §3.6 SM2). */
    public static final String SMOOTH_TOO_SHORT_WHY =
        "The smoothing window holds too few samples to engage — lengthen it.";
    /** … because a section beside the seam clamps the window's half. */
    public static final String SMOOTH_SECTION_SHORT_WHY =
        "A section beside this seam is too short to smooth.";
    /** Make smoothing real refused next to a straight/curve section
     *  (§3.6 SM4 — Coaster.makeSmoothingReal's sentence, promoted). */
    public static final String SMOOTH_REAL_SC_WHY =
        "smoothing conversion needs force/geo sections on both sides of"
        + " the seam";
    /** The forward align with no locked axis to write (§3.7). */
    public static final String ALIGN_FOLLOWS_WHY =
        "This seam already follows the previous section.";
    /** The forward align on a vertical upstream frame (§3.7). */
    public static final String ALIGN_VERTICAL_WHY =
        "The track is vertical at this seam — there is no heading or bank"
        + " to align.";
    /** B / Shift+B with neither a selected node nor a highlighted strict
     *  seam (§3.7 — today's silent return goes). */
    public static final String B_NO_TARGET_WHY =
        "B needs a selected node or a highlighted strict seam.";
    /** The seam window's align button caption (the live "(B)" shortcut
     *  tag is appended by the window). */
    /** Its tooltip (one sentence). */
    /** The seam window's roll row under an s/c whose element 0 carries
     *  an ABSOLUTE bank lane (§3.9). */
    public static final String SEAM_ROLL_ABS_WHY =
        "The bank lane sets the bank at this seam — edit its first node.";
}
