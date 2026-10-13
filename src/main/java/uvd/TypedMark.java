package uvd;

/** THE TYPED MARK (v19.27, D256 — batch 37 decision 5, user spec:
 *  "any typed/pasted text directly from the user results in that
 *  value exactly"). Pure Java, no JavaFX — the Gestures / SpeedStrip
 *  pattern; TypedMarkGate drives its transition table.
 *
 *  WHY: every numeric box's "nothing was typed" test was TEXT
 *  EQUALITY alone (D247 isUntouchedEditor, D254 typing(), the D254
 *  BlankSafeConverter short-circuit): a box showing the "#.##"
 *  rendering of a value with more digits (12.35 over 12.3456) could
 *  not tell "retyped the shown digits" from "never touched", so typing
 *  the number a box already shows was a no-op (D255 §10). The mark is
 *  the missing evidence: one flag per editor, SET by real input,
 *  CLEARED by every program write. "untouched" := !typed && the text
 *  equals the rendering — the one predicate isUntouchedEditor /
 *  typing() / BlankSafeConverter.fromString share.
 *
 *  THE WIRING (FvdDesigner.numericEditor, the one install route of
 *  every numeric Spinner) only forwards events:
 *    editor text ChangeListener     → onTextChanged()  (equal text
 *                                     never fires — a JavaFX fact)
 *    spinner value ChangeListener   → onValueChanged() (registered
 *                                     there it runs AFTER the
 *                                     Spinner's own value→text render
 *                                     and BEFORE every site's listener)
 *    factory converter listener     → onValueChanged() (the Spinner
 *                                     re-renders first)
 *    our own editor.setText calls   → inside progWrite(..)
 *    commitTyped / discard / disable → clear()
 *    a paste key / a text drop       → the pre-mark (pasteMarks): the
 *                                     one gesture that can change
 *                                     nothing and fire no event
 *  The three JavaFX 26 ordering facts this rides on (wrapper listeners
 *  fire before read-only ones; an equal setValue/setText is silent;
 *  increment commits the editor first) are pinned by
 *  gates/probes/typed-mark/FxOrderProbe — re-run it on a JavaFX
 *  upgrade. */
public final class TypedMark {
    /** The numeric-box regexes (FvdDesigner's formatters read these
     *  — the paste model must apply the SAME rule the formatter
     *  applies to the pasted result). */
    public static final String SIGNED_RX = "-?\\d*\\.?\\d*";
    public static final String UNSIGNED_RX = "\\d*\\.?\\d*";

    private boolean typed;
    private int prog;   // program-write bracket depth

    /** Real input reached the editor since the last program write
     *  (the text MAY equal the rendering — that is the whole point). */
    public boolean typed() { return typed; }

    /** The editor's text changed. Unbracketed = the user, or the
     *  Spinner's own value→text render — which onValueChanged() clears
     *  right after (it is registered after the render). */
    public void onTextChanged() { if (prog == 0) typed = true; }

    /** The spinner's value changed: the Spinner has just re-rendered
     *  the editor from the value — whatever was typed is consumed or
     *  overwritten. */
    public void onValueChanged() { typed = false; }

    /** An explicit clear: commit done, Cancel discard, box disabled. */
    public void clear() { typed = false; }

    /** Bracket for the app's own editor.setText calls — a text event
     *  inside never marks. Re-entrant. */
    public void progWrite(Runnable write) {
        prog++;
        try { write.run(); } finally { prog--; }
    }

    /** "Nothing typed": the D247 rule, mark-aware. */
    public static boolean untouched(boolean typed, boolean textEqualsShown) {
        return !typed && textEqualsShown;
    }

    // ---- the PASTE / DROP pre-mark model -------------------------------
    // TextInputControl.paste() = Clipboard.getString() → replaceSelection
    // (JavaFX 26 bytecode), and replaceText runs the single-line
    // filterInput (control characters dropped) before the TextFormatter
    // filter judges the whole new text. A paste of the IDENTICAL text
    // over a selection therefore fires NO text event (FxOrderProbe 6a)
    // — the pre-mark reproduces the result and marks iff the formatter
    // would have let it through; a rejected clip never marks.

    /** TextInputControl's single-line input filter: control characters
     *  (newline, CR, tab, everything under 0x20 and DEL) are dropped. */
    public static String stripControl(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        return sb.toString();
    }

    /** The text a paste of `clip` over [selStart, selEnd) of `before`
     *  would produce. */
    public static String pasteResult(String before, int selStart,
                                     int selEnd, String clip) {
        if (before == null) before = "";
        int n = before.length();
        int s = Math.max(0, Math.min(n, Math.min(selStart, selEnd)));
        int e = Math.max(0, Math.min(n, Math.max(selStart, selEnd)));
        return before.substring(0, s) + stripControl(clip) + before.substring(e);
    }

    /** Does a paste of `clip` mark the box? Iff the resulting text
     *  passes the box's own regex (what the formatter accepts). A null
     *  clip (nothing on the clipboard) never marks. */
    public static boolean pasteMarks(String before, int selStart, int selEnd,
                                     String clip, boolean signed) {
        if (clip == null) return false;
        return pasteResult(before, selStart, selEnd, clip)
            .matches(signed ? SIGNED_RX : UNSIGNED_RX);
    }

    /** The DROP verification (JavaFX 26 has no text-drop handler on a
     *  TextField, so a drop may land nowhere): after the event the
     *  editor shows `now`; the pre-mark stands only when the drop
     *  changed nothing visibly AND the selection-model result is that
     *  same text (the identical drop) — a drop the toolkit ignored
     *  whose model differs never marks (a changed text marked itself
     *  through the text listener). */
    public static boolean dropMarks(String before, String expected,
                                    String now) {
        return now != null && now.equals(before) && now.equals(expected);
    }
}
