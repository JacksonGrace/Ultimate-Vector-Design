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

    /** Show the POV camera's track position as a line on the graph. */
    public boolean povMarker() {
        return Boolean.parseBoolean(props.getProperty("povMarker", "true"));
    }

    public void setPovMarker(boolean on) {
        props.setProperty("povMarker", String.valueOf(on));
        save();
    }

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

    /** Show the active lane's nodes as small spheres on the track. */
    public boolean trackMarkers() {
        return Boolean.parseBoolean(props.getProperty("trackMarkers", "true"));
    }

    public void setTrackMarkers(boolean on) {
        props.setProperty("trackMarkers", String.valueOf(on));
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
