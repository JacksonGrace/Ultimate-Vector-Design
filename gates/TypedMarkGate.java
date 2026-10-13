import java.util.ArrayList;
import java.util.List;

import uvd.TypedMark;

/** TypedMarkGate (v19.27, D256 — the TYPED MARK): drives uvd.TypedMark
 *  through a stub spinner that models exactly the two JavaFX facts the
 *  wiring rides on (the Spinner's own value→text render runs BEFORE
 *  our value listener; an equal setValue / setText fires nothing —
 *  gates/probes/typed-mark/FxOrderProbe pins them on the real
 *  toolkit). Sections A–G = the batch-38 prototype's 29-check
 *  transition table verbatim; section H = the paste / drop pre-mark
 *  model (pasteResult / pasteMarks / dropMarks / stripControl).
 *
 *  Run: javac -encoding UTF-8 -cp target/classes -d gates/out gates/*.java
 *       java -cp "target/classes;gates/out" TypedMarkGate            */
public class TypedMarkGate {
    static int fails = 0, checks = 0;
    static void check(String what, boolean ok) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    /** A stub spinner: value + editor text + the mark, wired exactly
     *  like numericEditor (the Spinner's render precedes
     *  onValueChanged). */
    static final class Stub {
        double value;
        String text;
        final TypedMark m = new TypedMark();
        final List<String> log = new ArrayList<>();
        String render(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
        Stub(double v) { value = v; text = render(v); }
        // --- the JavaFX mechanics, modelled ---
        void editorSetText(String s) {          // TextField.setText (any origin)
            if (s.equals(text)) return;          // equal text: no change event
            text = s;
            m.onTextChanged();
            log.add("text:" + s + (m.typed() ? "(typed)" : ""));
        }
        void factorySetValue(double v) {        // DoubleSpinnerValueFactory.setValue
            if (v == value) return;              // equal value: no change event
            value = v;
            editorSetText(render(v));            // Spinner's own render FIRST
            m.onValueChanged();                  // our listener AFTER
            log.add("value:" + v);
        }
        // --- the user ---
        void userType(String s) { editorSetText(s); }
        // --- the app code (FvdDesigner) ---
        boolean textEqualsShown() { return text.equals(render(value)); }
        boolean isUntouched() { return TypedMark.untouched(m.typed(), textEqualsShown()); }
        boolean typing(boolean focused, boolean disabled, boolean restoring) {
            return !restoring && !disabled && focused && !isUntouched();
        }
        double fromString(String s) {           // BlankSafeConverter (non-blank)
            if (TypedMark.untouched(m.typed(), s.equals(render(value)))) return value;
            return Double.parseDouble(s);
        }
        void spinnerCommitValue() {             // Spinner.commitValue
            factorySetValue(fromString(text));
        }
        void commitTyped() {                    // FvdDesigner.commitTyped
            if (isUntouched()) return;
            spinnerCommitValue();
            m.progWrite(() -> editorSetText(render(value)));   // canonical text
            m.clear();
        }
        void discard() {                        // discardFocusedNumericEditor
            m.progWrite(() -> editorSetText(render(value)));
            m.clear();
        }
        void setUnlessTyping(double v, boolean focused, boolean restoring) {
            if (typing(focused, false, restoring)) return;
            if (value == v) return;
            factorySetValue(v);
        }
        void increment() {                      // Spinner.increment: commit first
            spinnerCommitValue();
            factorySetValue(value + 1);
        }
    }

    public static void main(String[] args) {
        System.out.println("== A. the user's case: typing exactly the shown text ==");
        Stub s = new Stub(12.3456);
        check("shows 12.35", s.text.equals("12.35"));
        check("untouched before typing", s.isUntouched());
        s.userType("12.35");   // select-all + paste of the same text: NO text event
        check("paste of the identical text fires no event (the ambiguity)", !s.m.typed());
        s.userType("12.3"); s.userType("12.35");   // typed char by char
        check("after typing, marked", s.m.typed());
        check("not untouched any more", !s.isUntouched());
        s.commitTyped();
        check("commit stored EXACTLY 12.35", s.value == 12.35);
        check("mark cleared after commit", !s.m.typed());
        check("text canonical after commit", s.text.equals("12.35") && s.isUntouched());

        System.out.println("== B. the D247/D254 phantom stays closed ==");
        s = new Stub(12.3456);
        s.commitTyped();
        check("untouched commit is a no-op (value kept at full precision)", s.value == 12.3456);
        s.factorySetValue(7.891);   // a program sync (Spinner renders, we clear)
        check("program value write leaves the box untouched", !s.m.typed() && s.isUntouched());
        check("focused untouched box is NOT typing (D254)", !s.typing(true, false, false));
        s.commitTyped();
        check("still full precision after a focus-loss commit", s.value == 7.891);

        System.out.println("== C. typing() while typed; a live sync skips, a restore wins ==");
        s = new Stub(12.3456);
        s.userType("12.3"); s.userType("12.35");
        check("typed-equal box IS typing while focused", s.typing(true, false, false));
        s.setUnlessTyping(99.0, true, false);
        check("live sync skipped the typed box", s.value == 12.3456 && s.text.equals("12.35"));
        s.setUnlessTyping(99.0, true, true);   // docRestoring
        check("restore wrote through, re-rendered, cleared", s.value == 99.0 && s.text.equals("99.00") && !s.m.typed());
        s = new Stub(12.3456);
        s.userType("12.3"); s.userType("12.35");
        s.setUnlessTyping(12.3456, true, true);   // restore to an EQUAL value: no write
        check("equal restore leaves the typed intent (mark kept)", s.m.typed());
        s.commitTyped();
        check("... and the later commit lands the typed 12.35", s.value == 12.35);

        System.out.println("== D. Spinner's own commit paths (focus loss / Enter / arrows) ==");
        s = new Stub(12.3456);
        s.userType("12.3"); s.userType("12.35");
        s.spinnerCommitValue();   // Spinner's focus-loss commit runs BEFORE ours
        check("Spinner's own commit parsed the marked text", s.value == 12.35);
        check("value change cleared the mark", !s.m.typed());
        s.commitTyped();          // ours follows: untouched -> no-op
        check("our follow-up commit is inert", s.value == 12.35);
        s = new Stub(5.0);
        s.userType("5.0"); s.userType("5.00");   // typed the SAME value, differently
        s.spinnerCommitValue();   // Enter: equal value -> no change event
        check("same-value commit fires nothing, mark still set", s.m.typed() && s.text.equals("5.00"));
        s.commitTyped();          // our ActionEvent / focus-loss follow-up
        check("follow-up re-renders canonical text + clears", s.text.equals("5.00") && !s.m.typed() && s.isUntouched());
        s = new Stub(10.0);
        s.userType("23");
        s.increment();
        check("arrow click commits the typed 23 then steps to 24", s.value == 24.0 && !s.m.typed());

        System.out.println("== E. Cancel / Esc discards even an identical typed text ==");
        s = new Stub(12.3456);
        s.userType("12.3"); s.userType("12.35");
        s.discard();
        check("discard: text = rendering, mark cleared", s.isUntouched() && !s.m.typed());
        s.commitTyped();
        check("hide-time commit after Cancel is a no-op", s.value == 12.3456);

        System.out.println("== F. program writes never mark ==");
        final Stub f1 = new Stub(1.0);
        f1.m.progWrite(() -> f1.editorSetText("1.000"));   // a fixed-decimal style rewrite
        check("bracketed setText does not mark", !f1.m.typed());
        f1.factorySetValue(2.0);
        check("value render does not leave a mark", !f1.m.typed());

        System.out.println("== G. unchanged rule for unmarked NON-rendering text ==");
        final Stub g1 = new Stub(0.03);
        g1.m.progWrite(() -> g1.editorSetText("0.030"));   // e.g. a text restored by a catch path (non-rendering form)
        check("unmarked different text still parses (today's rule)", !g1.isUntouched() && g1.fromString(g1.text) == 0.03);

        System.out.println("== H. the paste / drop pre-mark model ==");
        // TextInputControl.paste = Clipboard.getString -> replaceSelection
        check("identical paste over a full selection reproduces the text",
            TypedMark.pasteResult("12.35", 0, 5, "12.35").equals("12.35"));
        check("... and MARKS (the box's own regex accepts it)",
            TypedMark.pasteMarks("12.35", 0, 5, "12.35", true));
        check("a clip with a trailing newline / tab is stripped like filterInput",
            TypedMark.pasteResult("12.35", 0, 5, "12.35\r\n\t").equals("12.35")
            && TypedMark.pasteMarks("12.35", 0, 5, "12.35\n", true));
        check("a REJECTED clip (letters) never marks",
            !TypedMark.pasteMarks("12.35", 0, 5, "12.35x", true));
        check("a signed clip into an UNSIGNED box never marks",
            !TypedMark.pasteMarks("12.35", 0, 5, "-12.35", false)
            && TypedMark.pasteMarks("12.35", 0, 5, "-12.35", true));
        check("nothing on the clipboard never marks",
            !TypedMark.pasteMarks("12.35", 0, 5, null, true));
        check("a partial selection pastes into place",
            TypedMark.pasteResult("12.35", 3, 5, "35").equals("12.35")
            && TypedMark.pasteResult("12.35", 2, 2, "9").equals("129.35"));
        check("a second period is rejected (the formatter's rule)",
            !TypedMark.pasteMarks("12.35", 5, 5, ".5", true));
        check("a reversed selection range still works",
            TypedMark.pasteResult("12.35", 5, 0, "7").equals("7"));
        check("stripControl keeps every printable char, drops controls",
            TypedMark.stripControl("1\u00072.\t3\u007F5\n").equals("12.35"));
        // the DROP verification (JavaFX 26 has no text-drop handler)
        check("drop: identical over a full selection marks (before == expected == now)",
            TypedMark.dropMarks("12.35", "12.35", "12.35"));
        check("drop the toolkit IGNORED whose model differs never marks",
            !TypedMark.dropMarks("12.35", "912.35", "12.35"));
        check("drop that CHANGED the text does not pre-mark (the text listener did)",
            !TypedMark.dropMarks("12.35", "912.35", "912.35"));
        check("drop with a null editor text never marks",
            !TypedMark.dropMarks("12.35", "12.35", null));

        System.out.println("TypedMarkGate: " + checks + " checks, " + fails + " failures"
            + (fails == 0 ? " — ALL OK" : ""));
        System.exit(fails == 0 ? 0 : 1);
    }
}
