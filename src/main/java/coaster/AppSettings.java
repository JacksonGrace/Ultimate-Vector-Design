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

    public double fov() {
        try {
            return Double.parseDouble(props.getProperty("fov", ""));
        } catch (NumberFormatException e) {
            return FOV_DEFAULT;
        }
    }

    public void setFov(double fov) {
        props.setProperty("fov", String.format(Locale.US, "%.1f", fov));
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

    private void save() {
        try (FileWriter out = new FileWriter(file)) {
            props.store(out, "FVD Coaster Designer settings");
        } catch (IOException ignored) {
            // settings that fail to persist shouldn't kill the app
        }
    }
}
