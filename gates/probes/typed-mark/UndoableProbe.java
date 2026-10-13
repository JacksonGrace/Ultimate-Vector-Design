import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;

/** Does TextInputControl.undoable see an IDENTICAL paste over a
 *  selection (no text change)? A formatter-rejected insert? And does a
 *  caret survive a re-render + positionCaret? */
public class UndoableProbe {
    static void run() {
        SpinnerValueFactory.DoubleSpinnerValueFactory f =
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-90, 90, 12.3456, 1);
        Spinner<Double> sp = new Spinner<>(f);
        sp.setEditable(true);
        TextField ed = sp.getEditor();
        ed.setTextFormatter(new javafx.scene.control.TextFormatter<String>(ch ->
            ch.getControlNewText().matches("-?\\d*\\.?\\d*") ? ch : null));
        System.out.println("text='" + ed.getText() + "' undoable=" + ed.isUndoable());
        ed.selectAll();
        ed.replaceSelection("12.35");   // identical paste over a full selection
        System.out.println("[a] replaceSelection(identical) -> text='" + ed.getText()
            + "' undoable=" + ed.isUndoable() + "  <- can JavaFX's undo see a no-op paste?");
        ed.setText("12.35");
        System.out.println("[b] setText(same) resets -> undoable=" + ed.isUndoable());
        ed.selectAll();
        ed.replaceSelection("12.35x");   // rejected by the formatter
        System.out.println("[c] rejected paste -> text='" + ed.getText()
            + "' undoable=" + ed.isUndoable());
        ed.selectAll();
        ed.replaceSelection("12.3");
        System.out.println("[d] real paste -> text='" + ed.getText()
            + "' undoable=" + ed.isUndoable());
        ed.selectAll();
        ed.replaceSelection("12.35");    // back to the rendering by a paste
        System.out.println("[e] paste back to the rendering -> text='" + ed.getText()
            + "' undoable=" + ed.isUndoable());
        // caret after a re-render
        ed.positionCaret(3);
        ed.setText(ed.getText());
        ed.positionCaret(ed.getText().length());
        System.out.println("[f] caret after setText(same)+positionCaret(end) = "
            + ed.getCaretPosition());
        // Spinner render resets undoable?
        ed.selectAll();
        ed.replaceSelection("7");
        System.out.println("[g] typed '7': undoable=" + ed.isUndoable());
        f.setValue(8.0);
        System.out.println("[h] after a value write (Spinner render '" + ed.getText()
            + "'): undoable=" + ed.isUndoable());
        // undo via the control's own API (the context menu's Undo)
        ed.selectAll();
        ed.replaceSelection("9");
        ed.undo();
        System.out.println("[i] typed '9' then TextInputControl.undo(): text='" + ed.getText()
            + "' undoable=" + ed.isUndoable() + " redoable=" + ed.isRedoable());
    }

    public static void main(String[] args) throws Exception {
        javafx.application.Platform.startup(() -> {});
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try { run(); } catch (Throwable t) { t.printStackTrace(System.out); }
            finally { done.countDown(); }
        });
        done.await();
        javafx.application.Platform.exit();
    }
}
