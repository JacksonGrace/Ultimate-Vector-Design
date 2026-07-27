package coaster;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * App-wide persistent settings: keyboard shortcuts and view options.
 * These survive across program runs (per-coaster settings do NOT belong
 * here — those go in the .fvd save file).
 *
 * Stored as a properties file in the user's home directory. Pure Java,
 * no JavaFX — shortcut combos are plain strings like "Ctrl+Z" or "Space"
 * (modifiers Ctrl/Alt/Shift/Meta joined with '+', then the KeyCode name);
 * the UI layer parses/matches them.
 */
public class AppSettings {

    /** {id, display label, default combo, category} — order is the
     *  display order; the category groups the Shortcuts dialog's
     *  titled columns (v10.3). */
    public static final String[][] ACTIONS = {
        { "lane1",  "Select lane 1 (Vert G / Pitch)", "1", "Graph editor" },
        { "lane2",  "Select lane 2 (Lat G / Yaw)",    "2", "Graph editor" },
        { "lane3",  "Select lane 3 (Roll)",       "3", "Graph editor" },
        { "smooth", "Toggle smooth/broken node",  "B", "Graph editor" },
        { "matchAll", "Match boundary: all lanes", "Shift+B", "Graph editor" },
        { "flath",  "Level selection / flatten handles", "H", "Graph editor" },
        { "levelSel", "Level-lock selection", "Shift+H", "Graph editor" },
        { "align",  "Straighten between selected pair", "F", "Graph editor" },
        { "alignLock", "Straight-lock between selected pair", "Shift+F", "Graph editor" },
        { "lockv",  "Lock node drag: vertical",   "Z", "Graph editor" },
        { "lockh",  "Lock node drag: horizontal", "X", "Graph editor" },
        { "delNode", "Delete selected node",      "Delete", "Graph editor" },
        { "append", "Append 1 s of track",        "A", "Graph editor" },
        { "pov",    "POV ride",                   "Space", "View" },
        { "viewTop",  "Top view (ortho)",         "T", "View" },
        { "viewSide", "Side view (ortho)",        "Shift+T", "View" },
        { "save",   "Save design",                "Ctrl+S", "File" },
        { "load",   "Load design",                "Ctrl+O", "File" },
        { "newfile", "New design",                "Ctrl+N", "File" },
        { "export", "Export NL2 CSV",             "Ctrl+E", "File" },
        { "undo",   "Undo",                       "Ctrl+Z", "History" },
        { "redo",   "Redo",                       "Ctrl+Shift+Z", "History" },
    };

    public static final double FOV_DEFAULT = 65;
    public static final double SENS_DEFAULT = 0.15;      // mouse look, deg/px
    public static final double SNAP_T_DEFAULT = 0.1;     // s
    public static final double SNAP_G_DEFAULT = 0.1;     // g
    public static final double SNAP_ROLL_DEFAULT = 5;    // deg/s
    public static final double SNAP_RATE_PM_DEFAULT = 1; // deg/m (v16.10)
    public static final double SNAP_LEN_DEFAULT = 1;     // m (s/c elements)
    public static final double NL2_SEGLEN_DEFAULT = 1.0; // m between CSV vertices

    private final File file;
    private final Properties props = new Properties();

    public AppSettings() {
        this(new File(System.getProperty("user.home"), ".fvd-designer.properties"));
    }

    /** Test seam: point at any file. */
    public AppSettings(File file) {
        this.file = file;
        if (file.isFile()) {
            try (FileReader in = new FileReader(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // unreadable settings -> run with defaults
            }
        }
    }

    public static String defaultShortcut(String id) {
        for (String[] a : ACTIONS) if (a[0].equals(id)) return a[2];
        throw new IllegalArgumentException("unknown action: " + id);
    }

    public String shortcut(String id) {
        return props.getProperty("shortcut." + id, defaultShortcut(id));
    }

    public boolean isDefaultShortcut(String id) {
        return shortcut(id).equals(defaultShortcut(id));
    }

    public void setShortcut(String id, String combo) {
        props.setProperty("shortcut." + id, combo);
        save();
    }

    public void resetShortcut(String id) {
        props.remove("shortcut." + id);
        save();
    }

    private double getD(String key, double def) {
        try {
            return Double.parseDouble(props.getProperty(key, ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void setD(String key, double v) {
        props.setProperty(key, String.format(Locale.US, "%.4f", v));
        save();
    }

    /** Track-view field of view: orbit + POV cameras. */
    public double fov() { return getD("fov", FOV_DEFAULT); }
    public void setFov(double v) { setD("fov", v); }

    /** Fly-camera field of view (independent of the track view). */
    public double flyFov() { return getD("flyFov", FOV_DEFAULT); }
    public void setFlyFov(double v) { setD("flyFov", v); }

    /** Mouse-look/orbit sensitivity, degrees per pixel (key kept as
     *  "flySens" for settings-file compatibility). */
    public double flySens() { return getD("flySens", SENS_DEFAULT); }
    public void setFlySens(double v) { setD("flySens", v); }

    /** Node-drag snap intervals (Ctrl): transition seconds, G, deg/s. */
    public double snapT() { return getD("snapT", SNAP_T_DEFAULT); }
    public void setSnapT(double v) { setD("snapT", v); }
    public double snapG() { return getD("snapG", SNAP_G_DEFAULT); }
    public void setSnapG(double v) { setD("snapG", v); }
    public double snapRoll() { return getD("snapRoll", SNAP_ROLL_DEFAULT); }
    public void setSnapRoll(double v) { setD("snapRoll", v); }
    /** Rate lanes DISPLAYED in °/m snap by their own, finer step
     *  (v16.10, user spec — typical °/m values live near ±3, where
     *  the 5 °/s grid was a whole turn of range). */
    public double snapRatePM() { return getD("snapRatePM", SNAP_RATE_PM_DEFAULT); }
    public void setSnapRatePM(double v) { setD("snapRatePM", v); }

    /** Straight/curve element boundary drag snap (Ctrl), meters. */
    public double snapLen() { return getD("snapLen", SNAP_LEN_DEFAULT); }
    public void setSnapLen(double v) { setD("snapLen", v); }

    // (the v13.9 bridge-optimizer preferences were WITHDRAWN in
    // v13.10, D84 — internal constants in Coaster now; any
    // bridgeSweep/bridgeKneeTol/bridgeSlopeW keys left in the
    // properties file are simply ignored)

    /** Ctrl-snap axis references (v10.2). Defaults = the historical
     *  behavior: time snaps the TRANSITION length from the previous
     *  node, value snaps the ABSOLUTE grid. Each axis can flip to the
     *  other reference (absolute ride time / Δ value from previous). */
    public boolean snapTAbsolute() {
        return Boolean.parseBoolean(props.getProperty("snapTAbsolute", "false"));
    }

    public void setSnapTAbsolute(boolean on) {
        props.setProperty("snapTAbsolute", String.valueOf(on));
        save();
    }

    public boolean snapVRelative() {
        return Boolean.parseBoolean(props.getProperty("snapVRelative", "false"));
    }

    public void setSnapVRelative(boolean on) {
        props.setProperty("snapVRelative", String.valueOf(on));
        save();
    }

    // (the POV graph marker is ALWAYS ON since v16.14, user spec —
    // the povMarker key is ignored)

    /** NL2 CSV export: true = heartline points (the default, user
     *  preference — see D41), false = rail-center spine. */
    public boolean nl2Heartline() {
        return Boolean.parseBoolean(props.getProperty("nl2Heartline", "true"));
    }

    public void setNl2Heartline(boolean on) {
        props.setProperty("nl2Heartline", String.valueOf(on));
        save();
    }

    /** NL2 CSV export: requested vertex spacing in meters. */
    public double nl2SegLen() { return getD("nl2SegLen", NL2_SEGLEN_DEFAULT); }
    public void setNl2SegLen(double v) { setD("nl2SegLen", v); }

    /** Resulting-graph overlay picks (toolbar "Resulting" dropdown).
     *  Roll rate is deliberately absent: the resulting roll IS the
     *  authored roll lane (D47). */
    public boolean resPitch() {
        return Boolean.parseBoolean(props.getProperty("resPitch", "false"));
    }

    public void setResPitch(boolean on) {
        props.setProperty("resPitch", String.valueOf(on));
        save();
    }

    public boolean resYaw() {
        return Boolean.parseBoolean(props.getProperty("resYaw", "false"));
    }

    public void setResYaw(boolean on) {
        props.setProperty("resYaw", String.valueOf(on));
        save();
    }

    /** The ROLL/BANK resulting overlay (v17.6, D143): meaningful once
     *  the resulting settings below leave the local-rate default (a
     *  local resulting roll IS the authored lane). Force view only. */
    public boolean resRoll() {
        return Boolean.parseBoolean(props.getProperty("resRoll", "false"));
    }

    public void setResRoll(boolean on) {
        props.setProperty("resRoll", String.valueOf(on));
        save();
    }

    /** RESULTING GRAPH SETTINGS (v17.6, D143 — user spec): how the
     *  resulting overlays present — rate units, roll axis, yaw/pitch
     *  axis, roll behavior; the same representation options sections
     *  themselves have. Display-only, per-user. */
    public boolean resRateM() {
        return Boolean.parseBoolean(props.getProperty("resRateM", "false"));
    }

    public void setResRateM(boolean on) {
        props.setProperty("resRateM", String.valueOf(on));
        save();
    }

    public boolean resRollG() {
        return Boolean.parseBoolean(props.getProperty("resRollG", "false"));
    }

    public void setResRollG(boolean on) {
        props.setProperty("resRollG", String.valueOf(on));
        save();
    }

    public boolean resYpG() {
        return Boolean.parseBoolean(props.getProperty("resYpG", "false"));
    }

    public void setResYpG(boolean on) {
        props.setProperty("resYpG", String.valueOf(on));
        save();
    }

    public boolean resRollAbs() {
        return Boolean.parseBoolean(props.getProperty("resRollAbs", "false"));
    }

    public void setResRollAbs(boolean on) {
        props.setProperty("resRollAbs", String.valueOf(on));
        save();
    }

    /** Placement/seam gizmos always show for the active piece since
     *  v17.7 (D144 — the reveal-click gating is gone); this View-menu
     *  check is the only off switch. */
    public boolean showGizmos() {
        return Boolean.parseBoolean(props.getProperty("showGizmos", "true"));
    }

    public void setShowGizmos(boolean on) {
        props.setProperty("showGizmos", String.valueOf(on));
        save();
    }

    /** SPEED-CONTROL STRIP (v18.0, D160): the "+V" box toggles the
     *  strip above the x-axis; the arrow expands it to the tall view
     *  (resulting speed + complex graphs). SESSION-ONLY since v18.4
     *  (D173, user spec): the strip always starts CLOSED on program
     *  open — only the tall/height prefs persist. */
    private boolean showSpeedStrip = false;

    public boolean showSpeedStrip() {
        return showSpeedStrip;
    }

    public void setShowSpeedStrip(boolean on) {
        showSpeedStrip = on;
    }

    public boolean speedStripTall() {
        return Boolean.parseBoolean(
            props.getProperty("speedStripTall", "false"));
    }

    public void setSpeedStripTall(boolean on) {
        props.setProperty("speedStripTall", String.valueOf(on));
        save();
    }

    /** The tall strip's HEIGHT in px (v18.2 — the grabbable divider
     *  resizes it; below the threshold it collapses to the line). */
    public double speedStripSize() {
        try {
            return Double.parseDouble(
                props.getProperty("speedStripSize", "118"));
        } catch (NumberFormatException e) {
            return 118;
        }
    }

    public void setSpeedStripSize(double h) {
        props.setProperty("speedStripSize", String.valueOf(h));
        save();
    }

    /** Show the active lane's nodes as small spheres on the track. */
    public boolean trackMarkers() {
        return Boolean.parseBoolean(props.getProperty("trackMarkers", "true"));
    }

    public void setTrackMarkers(boolean on) {
        props.setProperty("trackMarkers", String.valueOf(on));
        save();
    }

    /** "Visualize Track Selection" (v17.10, user spec): ON = the
     *  historic look — unselected pieces desaturate and the active
     *  section's ties render white. OFF = every piece renders in its
     *  full active coloring and every tie stays the plain dark color,
     *  as if nothing were selected. Pure view choice. */
    public boolean visualizeSelection() {
        return Boolean.parseBoolean(
            props.getProperty("visualizeSelection", "true"));
    }

    public void setVisualizeSelection(boolean on) {
        props.setProperty("visualizeSelection", String.valueOf(on));
        save();
    }

    /** Gizmo translation arrows follow the piece's entry frame (local)
     *  instead of world X/Y/Z (v16.11) — a pure view choice, toggled
     *  by the viewport's overlay button; switching never moves track. */
    public boolean gizmoLocal() {
        return Boolean.parseBoolean(props.getProperty("gizmoLocal", "false"));
    }

    public void setGizmoLocal(boolean on) {
        props.setProperty("gizmoLocal", String.valueOf(on));
        save();
    }

    /** Track COMB mode (v16.13; three-way since v16.14, FIVE-way
     *  since v17.16 D157 — modes stay exclusive, user's original
     *  spec): 0 = none, 1 = G-FORCE (vertical + lateral teeth as one
     *  unit), 2 = SPEED (green since v17.16), 3 = GEOMETRIC (orange
     *  curvature teeth, sub-mode below), 4 = TIME (a tooth every
     *  0.1 s, whole seconds white). Per-user view preference; the
     *  View menu radios drive it.
     *  (The v16.13 combVert/combLat/combSpeed keys are ignored.) */
    public static final int COMB_NONE = 0, COMB_GFORCE = 1, COMB_SPEED = 2,
        COMB_GEO = 3, COMB_TIME = 4;

    public int combMode() {
        try {
            int m = Integer.parseInt(props.getProperty("combMode", "0"));
            return m < 0 || m > COMB_TIME ? COMB_NONE : m;
        } catch (NumberFormatException e) {
            return COMB_NONE;
        }
    }

    public void setCombMode(int mode) {
        props.setProperty("combMode", String.valueOf(mode));
        save();
    }

    /** GEOMETRIC comb sub-mode (v17.16, D157 — the inline dots next
     *  to the Geometric radio): 0 = LOCAL (body pitch + yaw °/m as
     *  two tooth sets), 1 = GLOBAL (elevation + heading rates °/m),
     *  2 = COMBINED (one tooth along the outward curve normal — the
     *  default: "the curvature of the track"). */
    public static final int COMB_GEO_LOCAL = 0, COMB_GEO_GLOBAL = 1,
        COMB_GEO_COMBINED = 2;

    public int combGeoMode() {
        try {
            int m = Integer.parseInt(props.getProperty("combGeoMode", "2"));
            return m < 0 || m > 2 ? COMB_GEO_COMBINED : m;
        } catch (NumberFormatException e) {
            return COMB_GEO_COMBINED;
        }
    }

    public void setCombGeoMode(int mode) {
        props.setProperty("combGeoMode", String.valueOf(mode));
        save();
    }

    /** Comb tooth size, PERCENT of the built-in default (v16.14). */
    public static final double COMB_SCALE_DEFAULT = 100;
    public double combScale() { return getD("combScale", COMB_SCALE_DEFAULT); }
    public void setCombScale(double v) { setD("combScale", v); }

    // (a meshQuality setting existed for one unshipped round — v18.2
    // removed it with its View row; stale property keys are ignored)

    /** The color dialog's LAST TEN picked colors (v16.16, user spec),
     *  hex strings, most recent first — shown as a "Recent" swatch
     *  row in Dialogs.pickColor. */
    public java.util.List<String> recentColors() {
        String s = props.getProperty("recentColors", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) out.add(p.trim());
        }
        return out;
    }

    public void pushRecentColor(String hex) {
        java.util.List<String> list = recentColors();
        list.remove(hex);
        list.add(0, hex);
        while (list.size() > 10) list.remove(list.size() - 1);
        props.setProperty("recentColors", String.join(",", list));
        save();
    }

    /** POV rides looking BACKWARD (v16.15): the View-menu check, or
     *  A / D while the ride is captured — W/S keep their track
     *  direction either way. */
    public boolean povReversed() {
        return Boolean.parseBoolean(props.getProperty("povReversed", "false"));
    }

    public void setPovReversed(boolean on) {
        props.setProperty("povReversed", String.valueOf(on));
        save();
    }

    /** Render the integrated heartline as a thin tube in the 3D view. */
    public boolean showHeartline() {
        return Boolean.parseBoolean(props.getProperty("showHeartline", "false"));
    }

    public void setShowHeartline(boolean on) {
        props.setProperty("showHeartline", String.valueOf(on));
        save();
    }

    private void save() {
        try (FileWriter out = new FileWriter(file)) {
            props.store(out, "FVD Coaster Designer settings");
        } catch (IOException ignored) {
            // settings that fail to persist shouldn't kill the app
        }
    }
}
