package uvd;

/**
 * KEY ROUTING predicates (v19.27, D256 — the pv-keys root cause). Pure
 * Java, no JavaFX, so KeyRouteGate pins the table headlessly.
 *
 * The question every secondary scene's ride-key forwarder asks is
 * "would this key TYPE into the focused box?" — not "is a text box
 * focused?" (the v19.6/D235 guard, written for the Coaster Options
 * NAME field, which silently killed W/S/Space/the view digits in the
 * density preview: its numeric spacing box is auto-focused on open,
 * every plain press is consumed by the TextField and the main window
 * never sees it). A NUMERIC box can only ever take digits, a period,
 * editing/caret keys and — when signed — a minus; everything else
 * (W/S/A/D, Space, bare Shift/Ctrl, every other letter, F-keys) is
 * free to ride. A FREE-TEXT box takes everything (D235 kept). A
 * release never types; a shortcut combo never types; a disabled or
 * read-only box never types (the D252 rule).
 *
 * Codes are the AWT-compatible ints JavaFX's KeyCode.getCode() reports.
 */
final class KeyRoute {
    private KeyRoute() { }

    static final int VK_BACK_SPACE = 0x08, VK_TAB = 0x09, VK_ENTER = 0x0A,
        VK_ESCAPE = 0x1B, VK_END = 0x23, VK_HOME = 0x24, VK_LEFT = 0x25,
        VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_MINUS = 0x2D,
        VK_PERIOD = 0x2E, VK_0 = 0x30, VK_9 = 0x39, VK_NUMPAD0 = 0x60,
        VK_NUMPAD9 = 0x69, VK_SUBTRACT = 0x6D, VK_DECIMAL = 0x6E,
        VK_DELETE = 0x7F;

    /** True when a press of this key is TEXT (or a caret / edit /
     *  commit key) for a numeric-only box: digits 0-9, numpad 0-9,
     *  period, numpad decimal, Backspace, Delete, the four arrows,
     *  Home, End, Enter, Escape, Tab — plus minus / numpad subtract
     *  on a SIGNED box. Every other code (letters, Space, modifiers,
     *  F-keys…) is not. */
    static boolean numericTextKey(int awtCode, boolean signed) {
        if (awtCode >= VK_0 && awtCode <= VK_9) return true;
        if (awtCode >= VK_NUMPAD0 && awtCode <= VK_NUMPAD9) return true;
        if (awtCode >= VK_LEFT && awtCode <= VK_DOWN) return true;
        switch (awtCode) {
            case VK_PERIOD, VK_DECIMAL, VK_BACK_SPACE, VK_DELETE,
                 VK_HOME, VK_END, VK_ENTER, VK_ESCAPE, VK_TAB -> {
                return true;
            }
            case VK_MINUS, VK_SUBTRACT -> {
                return signed;
            }
            default -> {
                return false;
            }
        }
    }

    /** The whole rule: would this key event TYPE into the focused
     *  control, so the ride-key forwarder must stay out of its way?
     *  press = KEY_PRESSED (a release never types); shortcutDown =
     *  the platform shortcut modifier is held (combos never type);
     *  textBox = the focus owner is a text input control;
     *  disabledOrReadOnly = that box cannot take typing; numericSigned
     *  = null for a FREE-TEXT box (takes everything), else the numeric
     *  box's sign flag; code = the AWT key code. */
    static boolean typesInto(boolean press, boolean shortcutDown,
                             boolean textBox, boolean disabledOrReadOnly,
                             Boolean numericSigned, int code) {
        if (!press || shortcutDown || !textBox || disabledOrReadOnly) {
            return false;
        }
        if (numericSigned == null) return true;   // free text: D235 kept
        return numericTextKey(code, numericSigned);
    }
}
