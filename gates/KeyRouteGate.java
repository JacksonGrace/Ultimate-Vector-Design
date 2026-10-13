package uvd;

/** KeyRoute gate (v19.27, D256 — the pv-keys root cause): the pure
 *  "would this key TYPE into the focused box" predicate behind every
 *  secondary scene's ride-key forwarder (FvdDesigner.keyTypes →
 *  KeyRoute.typesInto / numericTextKey).
 *  Pins: (a) the numeric-box TEXT table — digits 0x30-0x39, numpad
 *  0x60-0x69, period 0x2E, decimal 0x6E, Backspace 0x08, Delete 0x7F,
 *  the arrows 0x25-0x28, Home 0x24, End 0x23, Enter 0x0A, Escape 0x1B,
 *  Tab 0x09 — on signed AND unsigned boxes; (b) minus 0x2D / subtract
 *  0x6D type ONLY on a signed box; (c) NOT text: W 0x57, S 0x53,
 *  A 0x41, D 0x44, Space 0x20, Shift 0x10, Control 0x11, every other
 *  letter, F1-F12 0x70-0x7B; (d) typesInto's rules — a release never
 *  types, a shortcut combo never types, a non-text focus owner never
 *  types, a disabled / read-only box never types, a FREE-TEXT box
 *  (null sign) takes everything incl. Space and letters (D235 kept),
 *  a numeric box takes exactly the table; (e) when JavaFX is on the
 *  classpath, KeyCode.getCode() of the named keys equals the table's
 *  ints (skipped on the plain battery classpath).
 *  Run: java -cp "target/classes;gates/out" uvd.KeyRouteGate */
public class KeyRouteGate {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name
            + (detail == null ? "" : "  " + detail));
    }

    static String hex(int c) { return String.format("0x%02X", c); }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void main(String[] args) {
        // (a) the text table on both sign flavors
        java.util.List<Integer> t = new java.util.ArrayList<>();
        for (int c = 0x30; c <= 0x39; c++) t.add(c);
        for (int c = 0x60; c <= 0x69; c++) t.add(c);
        for (int c = 0x25; c <= 0x28; c++) t.add(c);
        for (int c : new int[]{ 0x2E, 0x6E, 0x08, 0x7F, 0x24, 0x23, 0x0A, 0x1B, 0x09 }) t.add(c);
        for (int c : t) {
            check("a numeric text " + hex(c) + " unsigned", KeyRoute.numericTextKey(c, false), null);
            check("a numeric text " + hex(c) + " signed", KeyRoute.numericTextKey(c, true), null);
        }
        // (b) minus only when signed
        for (int c : new int[]{ 0x2D, 0x6D }) {
            check("b minus " + hex(c) + " types on a SIGNED box", KeyRoute.numericTextKey(c, true), null);
            check("b minus " + hex(c) + " does NOT type on an unsigned box", !KeyRoute.numericTextKey(c, false), null);
        }
        // (c) never text in a numeric box
        int[] ride = { 0x57, 0x53, 0x41, 0x44, 0x20, 0x10, 0x11 };
        String[] rideN = { "W", "S", "A", "D", "Space", "Shift", "Control" };
        for (int i = 0; i < ride.length; i++) {
            check("c " + rideN[i] + " " + hex(ride[i]) + " is not numeric text (signed)",
                !KeyRoute.numericTextKey(ride[i], true), null);
            check("c " + rideN[i] + " " + hex(ride[i]) + " is not numeric text (unsigned)",
                !KeyRoute.numericTextKey(ride[i], false), null);
        }
        boolean letters = true;
        for (int c = 0x41; c <= 0x5A; c++) letters &= !KeyRoute.numericTextKey(c, true);
        check("c every letter A-Z is not numeric text", letters, null);
        boolean fkeys = true;
        for (int c = 0x70; c <= 0x7B; c++) fkeys &= !KeyRoute.numericTextKey(c, true);
        check("c F1-F12 are not numeric text", fkeys, null);
        // (d) the whole rule
        check("d a RELEASE never types (free text, Space)",
            !KeyRoute.typesInto(false, false, true, false, null, 0x20), null);
        check("d a RELEASE never types (numeric, digit)",
            !KeyRoute.typesInto(false, false, true, false, Boolean.FALSE, 0x32), null);
        check("d a shortcut combo never types (free text, S)",
            !KeyRoute.typesInto(true, true, true, false, null, 0x53), null);
        check("d a non-text focus owner never types (Space)",
            !KeyRoute.typesInto(true, false, false, false, null, 0x20), null);
        check("d a disabled / read-only box never types (free text, W)",
            !KeyRoute.typesInto(true, false, true, true, null, 0x57), null);
        check("d a disabled numeric box never types (digit)",
            !KeyRoute.typesInto(true, false, true, true, Boolean.TRUE, 0x32), null);
        check("d a FREE-TEXT box takes Space",
            KeyRoute.typesInto(true, false, true, false, null, 0x20), null);
        check("d a FREE-TEXT box takes W",
            KeyRoute.typesInto(true, false, true, false, null, 0x57), null);
        check("d a FREE-TEXT box takes a digit",
            KeyRoute.typesInto(true, false, true, false, null, 0x32), null);
        check("d a numeric box takes a digit",
            KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x32), null);
        check("d a numeric box takes Backspace",
            KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x08), null);
        check("d a numeric box lets Space ride",
            !KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x20), null);
        check("d a numeric box lets W ride",
            !KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x57), null);
        check("d a numeric box lets bare Shift ride",
            !KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x10), null);
        check("d a signed numeric box takes minus",
            KeyRoute.typesInto(true, false, true, false, Boolean.TRUE, 0x2D), null);
        check("d an unsigned numeric box lets minus through",
            !KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x2D), null);
        check("d a numeric box takes Escape (the window's / box's own key)",
            KeyRoute.typesInto(true, false, true, false, Boolean.FALSE, 0x1B), null);
        // (e) the AWT codes behind the table — only with JavaFX loadable
        try {
            Class<?> kc = Class.forName("javafx.scene.input.KeyCode");
            java.lang.reflect.Method getCode = kc.getMethod("getCode");
            String[] names = { "W", "S", "A", "D", "SPACE", "SHIFT", "CONTROL",
                "DIGIT0", "DIGIT9", "NUMPAD0", "NUMPAD9", "PERIOD", "DECIMAL",
                "BACK_SPACE", "DELETE", "LEFT", "UP", "RIGHT", "DOWN", "HOME",
                "END", "ENTER", "ESCAPE", "TAB", "MINUS", "SUBTRACT", "F1", "F12" };
            int[] codes = { 0x57, 0x53, 0x41, 0x44, 0x20, 0x10, 0x11,
                0x30, 0x39, 0x60, 0x69, 0x2E, 0x6E,
                0x08, 0x7F, 0x25, 0x26, 0x27, 0x28, 0x24,
                0x23, 0x0A, 0x1B, 0x09, 0x2D, 0x6D, 0x70, 0x7B };
            for (int i = 0; i < names.length; i++) {
                Object k = Enum.valueOf((Class<Enum>) kc.asSubclass(Enum.class), names[i]);
                int code = (Integer) getCode.invoke(k);
                check("e KeyCode." + names[i] + ".getCode() == " + hex(codes[i]),
                    code == codes[i], hex(code));
            }
        } catch (ClassNotFoundException e) {
            System.out.println("SKIP e KeyCode codes (JavaFX not on the classpath"
                + " - run with the module path to pin them)");
        } catch (Exception e) {
            check("e KeyCode codes", false, e.toString());
        }

        System.out.println("KeyRouteGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
