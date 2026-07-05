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

    /** {id, display label, default combo} — order is the display order. */
    public static final String[][] ACTIONS = {
        { "lane1",  "Select lane 1 (Vert G)",     "1" },
        { "lane2",  "Select lane 2 (Lat G)",      "2" },
        { "lane3",  "Select lane 3 (Roll)",       "3" },
        { "smooth", "Toggle smooth/broken node",  "B" },
        { "flath",  "Flatten handles (level)",    "H" },
        { "align",  "Straighten between selected pair", "F" },
        { "lockv",  "Lock node drag: vertical",   "Z" },
        { "lockh",  "Lock node drag: horizontal", "X" },
        { "delNode", "Delete selected node",      "Delete" },
        { "append", "Append 1 s of track",        "A" },
        { "pov",    "POV ride",                   "Space" },
        { "save",   "Save design",                "Ctrl+S" },
        { "load",   "Load design",                "Ctrl+O" },
        { "undo",   "Undo",                       "Ctrl+Z" },
    };

    public static final double FOV_DEFAULT = 65;
    public static final double SENS_DEFAULT = 0.15;      // mouse look, deg/px
    public static final double SNAP_T_DEFAULT = 0.1;     // s
    public static final double SNAP_G_DEFAULT = 0.1;     // g
    public static final double SNAP_ROLL_DEFAULT = 1;    // deg/s

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

    /** Show the POV camera's track position as a line on the graph. */
    public boolean povMarker() {
        return Boolean.parseBoolean(props.getProperty("povMarker", "true"));
    }

    public void setPovMarker(boolean on) {
        props.setProperty("povMarker", String.valueOf(on));
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
