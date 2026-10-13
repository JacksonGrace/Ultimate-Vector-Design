import javafx.beans.value.ChangeListener;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;

/** JavaFX 26 property-level facts the typed-mark design relies on
 *  (no toolkit, no window — pure property/listener mechanics):
 *   1. Spinner's own value->editor setText runs BEFORE any listener
 *      registered on spinner.valueProperty() (wrapper first, then the
 *      read-only property).
 *   2. factory.setValue(equal double) fires NO change listener.
 *   3. editor.setText(equal text) fires NO text change listener.
 *   4. commitValue() -> converter.fromString(editorText) -> setValue.
 *   5. DoubleSpinnerValueFactory clamps on setValue (listener order).
 *   6. a user-like edit (replaceText / replaceSelection / deleteText)
 *      fires the text change listener; the TextFormatter filter rejects
 *      a letter WITHOUT any text event.
 *   7. converter swap re-renders the editor (Spinner$1) before a
 *      converterProperty listener registered afterwards.
 *   8. does an EQUAL-text setText move the caret?
 *   9. increment(1) commits the editor text first (JDK-8150946).
 */
public class FxOrderProbe {
    static final List<String> log = new ArrayList<>();

    static void run() {
        SpinnerValueFactory.DoubleSpinnerValueFactory f =
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-90, 90, 12.3456, 1);
        Spinner<Double> sp = new Spinner<>(f);
        sp.setEditable(true);
        TextField ed = sp.getEditor();
        System.out.println("initial editor text = '" + ed.getText() + "' (default #.## converter)");

        // the proposed mark wiring, registered AFTER the Spinner ctor
        ed.textProperty().addListener((o, a, b) ->
            log.add("TEXT '" + a + "' -> '" + b + "'"));
        sp.valueProperty().addListener((o, a, b) ->
            log.add("VALUE " + a + " -> " + b + " (editor now '" + ed.getText() + "')"));
        f.converterProperty().addListener((o, a, b) ->
            log.add("CONV changed (editor now '" + ed.getText() + "')"));

        // 1 + 2: value write, then an equal value write
        log.clear();
        f.setValue(20.0);
        System.out.println("\n[1] f.setValue(20.0): " + log);
        log.clear();
        f.setValue(20.0);
        System.out.println("[2] f.setValue(20.0) again (equal): " + log + "  <- expect empty");
        log.clear();
        f.setValue(Double.valueOf(20.0));   // a fresh box, equal value
        System.out.println("[2b] f.setValue(new Double 20.0): " + log + "  <- expect empty");

        // 3: equal text
        log.clear();
        ed.setText(ed.getText());
        System.out.println("[3] ed.setText(same): " + log + "  <- expect empty");

        // 8: caret on an equal setText
        ed.positionCaret(1);
        int c0 = ed.getCaretPosition();
        ed.setText(ed.getText());
        System.out.println("[8] caret before equal setText = " + c0
            + ", after = " + ed.getCaretPosition());
        ed.setText("20.5");
        System.out.println("[8b] caret after a DIFFERENT setText('20.5') = "
            + ed.getCaretPosition() + " (len " + ed.getText().length() + ")");

        // 6: user-like edits through the TextInputControl API
        f.setValue(12.3456);
        log.clear();
        ed.selectAll();
        ed.replaceSelection("12.35");   // paste-like over a selection
        System.out.println("[6a] replaceSelection('12.35') over '" + "12.35" + "': " + log
            + "  <- expect NO event (same text) — the ambiguity the mark resolves");
        log.clear();
        ed.deleteText(ed.getLength() - 1, ed.getLength());
        ed.insertText(ed.getLength(), "5");
        System.out.println("[6b] delete last char + retype it: " + log);
        sp.getEditor().setTextFormatter(new javafx.scene.control.TextFormatter<String>(ch ->
            ch.getControlNewText().matches("-?\\d*\\.?\\d*") ? ch : null));
        log.clear();
        ed.insertText(ed.getLength(), "x");
        System.out.println("[6c] insertText('x') under the signed formatter: " + log
            + " text='" + ed.getText() + "'  <- expect no event, text unchanged");
        log.clear();
        ed.setText("abc");
        System.out.println("[6d] programmatic setText('abc') under the formatter: " + log
            + " text='" + ed.getText() + "'  <- the filter applies to setText too?");

        // 4: commitValue path with the typed text "12.35" over 12.3456
        f.setValue(12.3456);
        ed.setText("12.35");
        log.clear();
        sp.commitValue();
        System.out.println("[4] commitValue() with editor '12.35' over value 12.3456: " + log
            + " value=" + sp.getValue());

        // 4b: typed the SAME value: value 5.0, editor "5.0" retyped as "5.00"
        f.setValue(5.0);
        ed.setText("5.00");
        log.clear();
        sp.commitValue();
        System.out.println("[4b] commitValue() with editor '5.00' over value 5.0: " + log
            + " value=" + sp.getValue() + " editor='" + ed.getText()
            + "'  <- expect no VALUE event and the text left as typed");

        // 5: clamp ordering
        f.setValue(90.0);
        ed.setText("500");
        log.clear();
        sp.commitValue();
        System.out.println("[5] commitValue('500') over 90 in [-90,90]: " + log
            + " value=" + sp.getValue() + " editor='" + ed.getText() + "'");

        // 9: increment commits the editor first
        f.setValue(10.0);
        ed.setText("23");
        log.clear();
        sp.increment(1);
        System.out.println("[9] increment(1) with editor '23' over 10: " + log
            + " value=" + sp.getValue());

        // 7: converter swap re-renders
        f.setValue(0.03);
        log.clear();
        f.setConverter(new StringConverter<Double>() {
            @Override public String toString(Double v) {
                return v == null ? "" : String.format(java.util.Locale.US, "%.3f", v);
            }
            @Override public Double fromString(String s) { return Double.parseDouble(s); }
        });
        System.out.println("[7] setConverter(fixed 3): " + log + " editor='" + ed.getText() + "'");

        // 10: a NumberFormatException out of fromString during commitValue
        f.setValue(1.0);
        ed.setText("-");
        log.clear();
        try {
            sp.commitValue();
            System.out.println("[10] commitValue('-'): no throw, " + log + " value=" + sp.getValue());
        } catch (RuntimeException ex) {
            System.out.println("[10] commitValue('-') THREW " + ex.getClass().getSimpleName()
                + " — Spinner's focus-loss listener would then cancelEdit() (rendering written)"
                + " log=" + log);
        }

        // 11: isUndoable as a provenance signal?
        ed.setText("1.000");
        System.out.println("[11] after programmatic setText: undoable=" + ed.isUndoable());
        ed.insertText(ed.getLength(), "5");
        System.out.println("[11b] after insertText (user-like): undoable=" + ed.isUndoable());
        ed.setText("1.000");
        System.out.println("[11c] after programmatic setText again: undoable=" + ed.isUndoable());
    }

    public static void main(String[] args) throws Exception {
        // the attic probes' pattern (gates/attic-noncompiling/ClipProbe):
        // toolkit up, NO Stage, work on the FX thread, exit
        javafx.application.Platform.startup(() -> {});
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                run();
            } catch (Throwable t) {
                t.printStackTrace(System.out);
            } finally {
                done.countDown();
            }
        });
        done.await();
        javafx.application.Platform.exit();
    }
}
