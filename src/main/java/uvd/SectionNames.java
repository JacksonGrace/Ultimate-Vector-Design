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
 *  bridgeWindowTitle ("Bridge settings (Strict Seam n)") — the
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
    /** The toolbar buttons' arrow (the windows open ABOVE the graph). */
    public static final String OWNER_ARROW = " ▴";

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
     *  glyphs ≠v / ≠o follow these (GraphEditor adds them). */
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
     *  " ≠v" / " ≠o". (The D248 shortTitle "Geo 2" is gone.) */
    public static String badgeLabel(Section sec, boolean dirty) {
        return shortWord(sec) + tags(sec, dirty);
    }

    /** The bridge window's title (v19.20, D249): "Bridge settings
     *  (Strict Seam n)" — n = the bridge's own EXIT junction, the
     *  same number as ownerButtonLabel's "Strict Seam n (bridge) ▴"
     *  (an orphan's would-be exit included). */
    public static String bridgeWindowTitle(Coaster c, int bp) {
        return BRIDGE_SETTINGS + " (" + STRICT_SEAM
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
}
