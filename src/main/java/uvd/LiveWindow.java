package uvd;

import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** THE SHARED LIVE-WINDOW BUILDER (v19.19, D248 — user spec: the
 *  Track Piece panel, the bridge window ("Bridge Settings") and the
 *  Strict
 *  seam pop-out become ONE kind of window, "like the node editor"):
 *  an OWNED Stage in the light default theme, placed AT SHOW just
 *  ABOVE the graph canvas (its bottom edge at the canvas top, left
 *  at an anchor x — the toolbar button, the seam strip, a node, or a
 *  pending gizmo double-click anchor), clamped into the screen's
 *  visual bounds, never a hardcoded size; later content changes
 *  grow/shrink it from its TOP-LEFT corner (refit, D254 — nothing
 *  above a revealed row moves). Values apply LIVE through
 *  the callers' chokes; the footer is explicit [Confirm][Cancel]:
 *  Confirm / Enter / the X = keep (the focused box commits first),
 *  Cancel / Esc = the caller's field-level revert to its open-time
 *  record. Undo keys route to the global history (installDocUndoKeys)
 *  and ride keys keep working (installViewKeys). Row factories for
 *  the unified layout live here too — orientation rows, bridge-style
 *  padlocks, the small ↺, the grey other-tier readout (click-
 *  swappable), the orientation-MODE row (independent check + real/
 *  design radios + the carry check, D249) and the Design ↔ Real
 *  resolve row —
 *  so the three windows look identical. Pure JavaFX, no model. */
final class LiveWindow {

    private LiveWindow() {}

    // ---- the ORIENTATION-MODE row's strings (v19.20, D249 — user
    // spec; replacing D248's "Lock Track Shape (creates orientation
    // conflict)" + "Only Affect Orientation Until Next Strict Seam") ----
    /** The independent-edit check (unchecked = LOCKSTEP). */
    static final String INDEP_LABEL =
        "Edit Real and Design Independently";
    static final String INDEP_TIP = "Unchecked: pitch/roll edits move the"
        + " real and design orientation together (the shape re-solves).\n"
        + "Checked: choose which one the boxes and gizmo rings edit.";
    /** The REAL_ONLY radio (= D222's Lock Track Shape). */
    static final String REAL_RADIO_LABEL =
        "Edit real orientation (locks track shape)";
    /** Its tooltip — the D247 Lock Track Shape sentence, verbatim. */
    static final String REAL_RADIO_TIP =
        "Design orientation does not change for adjustments to pitch/roll";
    /** The DESIGN_ONLY radio. */
    static final String DESIGN_RADIO_LABEL = "Edit design orientation";
    static final String DESIGN_RADIO_TIP = "Edits move the design"
        + " orientation only — the real entry stays put and the shape"
        + " re-solves.";
    /** The design radio's gray reason on a bridge target under
     *  Optimize position and orientation (mutual exclusion, D249). */
    static final String DESIGN_RADIO_GRAY_PY =
        "Optimize position and orientation must not be selected.";
    /** The PY mode radio's gray reason while the target edits its
     *  design orientation (the other half of the exclusion). */
    static final String PY_GRAY_DESIGN =
        "Edit design orientation must not be selected.";
    /** The carry flag (the INVERTED rename of D248's Only-Affect —
     *  TrackPiece.affectSubsequentSeams). */
    static final String AFFECT_LABEL = "Affect Subsequent Strict Seams";
    static final String AFFECT_TIP = "Also move the pieces after this"
        + " piece's next strict seam or bridge by the same rigid change"
        + " (real-orientation edits only).";
    /** The carry flag grays under DESIGN_ONLY (inert there — the real
     *  entry never moves). */
    static final String AFFECT_GRAY_DESIGN =
        "Applies while editing the real orientation.";
    /** The readout's hover tooltip while it swaps the mode. */
    static final String READOUT_SWAP_TIP =
        "Click to edit this orientation instead";
    /** Real → Design's gray reason under an optimize-orientation
     *  bridge — the same sentence in all three windows (D248). The
     *  predicate is MODE-only, so the old "or lock the axes" advice
     *  was never honored; the sentence names the two modes that
     *  un-gray it (batch 37, SectionNames — LabelGate pins it). */
    static final String MARCH_OWNS_R2D = SectionNames.MARCH_OWNS_R2D;
    static final String D2R_TIP = "Adopt the real orientation as the"
        + " design — graphs stay,\nthe shape re-solves; conflict closed.";
    static final String R2D_TIP = "Un-rotate the piece (and its carried"
        + " chain) back to the\ndesign orientation — shape kept; conflict"
        + " closed.";
    /** Every window's spinner column width (the panel's 80 px). */
    static final double SPIN_W = 80;
    /** Label column width — the longest label is "Next piece pitch (°)". */
    static final double LABEL_W = 132;
    /** Gap kept between the window's bottom edge and the canvas top. */
    private static final double ABOVE_GAP = 4;
    /** Pre-show estimate of the OS title bar — the decoration-height
     *  seed for the FIRST live window of the session (D249; the cache
     *  below learns the real insets in setOnShown). */
    private static final double TITLE_BAR_EST = 38;
    /** The DECORATION INSETS cache (v19.20, D249 — the "window flash"
     *  fix): frame width/height minus scene width/height, learned from
     *  every shown window, so the NEXT window's frame size — and hence
     *  its placement — is known BEFORE show. NaN = not learned yet. */
    private static double decoW = Double.NaN, decoH = Double.NaN;

    /** Build, place and show a live window. `content` = the rows
     *  (the footer is added here). onConfirm runs on Confirm / Enter
     *  (AFTER the focused numeric box committed); onCancel on Cancel
     *  / Esc; the X button is Confirm (a close without revert). Both
     *  callbacks are expected to close the window themselves through
     *  the caller's null-before-hide teardown; the window only closes
     *  itself when a callback is null. `anchorX` = the screen x the
     *  window's left edge lands on (a pending gizmo anchor overrides
     *  it and is consumed). Returns the Stage. */
    static Stage show(FvdDesigner app, String title, VBox content,
                      Runnable onConfirm, Runnable onCancel,
                      double anchorX, Node... footLead) {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle(title);
        dlg.setResizable(false);
        Button confirm = new Button("Confirm");
        confirm.setDefaultButton(true);
        confirm.setFocusTraversable(false);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setFocusTraversable(false);
        // footLead = a caller's own action button(s) sitting LEFT of
        // Confirm/Cancel (the init-speed window's "Set Design to Real"
        // — its pre-D249 [button][Apply][Cancel] row; batch 36, D254).
        // They pack against Confirm; a footSpring() among them pushes
        // what PRECEDES it to the window's left edge (the Track Piece
        // window's Switch Coaster… / Delete…, batch 37)
        HBox foot = new HBox(8);
        if (footLead != null) foot.getChildren().addAll(footLead);
        foot.getChildren().addAll(confirm, cancel);
        foot.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().add(foot);
        content.getProperties().put(FOOT, foot);   // footOf (batch 39)
        content.setPadding(new javafx.geometry.Insets(12, 14, 12, 14));
        // a row that may be REVEALED later reserves its width now
        // (batch 37): the window is wide enough for the resolve row
        // even while that row is hidden (28 = the side padding)
        double reserve = 0;
        for (Node c : content.getChildren()) {
            if (c.getProperties().get(RESERVE_W) instanceof Double d) {
                reserve = Math.max(reserve, d);
            }
        }
        if (reserve > 0) {
            content.setMinWidth(Math.max(content.getMinWidth(),
                reserve + 28));
        }
        Scene sc = new Scene(content);
        dlg.setScene(sc);
        Runnable doConfirm = () -> {
            // a typed-but-unentered box commits EXPLICITLY (a mouse
            // press already did via installNumericPressCommit;
            // keyboard Enter / the X need this — the seam Apply's
            // D247 order: commit, then the muted teardown)
            FvdDesigner.commitFocusedNumericEditor(sc);
            if (onConfirm != null) onConfirm.run(); else dlg.close();
        };
        Runnable doCancel = () -> {
            // a typed-but-unentered box is DISCARDED first (D248 review
            // fix): the caller's revert reads the MODEL, and the
            // hide-time focus-loss commit then applied the typed text
            // AFTER the revert (the piece moved on Cancel); with the
            // editor reset to the model's rendering that commit is
            // commitTyped's untouched-text no-op
            FvdDesigner.discardFocusedNumericEditor(sc);
            if (onCancel != null) onCancel.run(); else dlg.close();
        };
        confirm.setOnAction(e -> doConfirm.run());
        cancel.setOnAction(e -> doCancel.run());
        // the X = Confirm (keep): the close request is consumed and
        // the confirm path tears the window down through the caller
        dlg.setOnCloseRequest(e -> {
            e.consume();
            doConfirm.run();
        });
        // Esc / Enter as a scene FILTER limited to those two codes
        // (D248 review fix): every value box here is a Spinner whose
        // editor CONSUMES a bubbling Enter (its onAction commit) and
        // Esc (its cancelEdit), so a handler / the default and cancel
        // buttons never saw them while a box had focus — typing 45
        // then Enter committed but left the window open. The undo /
        // ride key filters act only on their own keys, so a filter on
        // these two cannot shadow them; doConfirm commits the focused
        // box first (a blank one commits zero via commitTyped, the
        // same as numericEditor's own Enter filter)
        sc.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                doCancel.run();
            } else if (e.getCode() == KeyCode.ENTER) {
                e.consume();
                doConfirm.run();
            }
        });
        place(app, dlg, content, anchorX);
        dlg.show();
        app.installDocUndoKeys(sc);   // global undo + ride keys (D227)
        return dlg;
    }

    /** PLACEMENT (D248, user spec — explicit, never JavaFX's centered
     *  default): bottom edge ABOVE_GAP above the graph canvas top,
     *  left edge at `anchorX` (a pending gizmo double-click anchor
     *  wins and is consumed), clamped into the visual bounds of the
     *  screen holding the anchor.
     *
     *  PLACED BEFORE SHOW since v19.20 (D249 — the "moved from another
     *  location" flash): the D248 build showed the Stage invisible at
     *  JavaFX's default owner-centered spot and moved it in
     *  setOnShown, but the native frame (and Windows' open animation)
     *  is created at the creation rect, and opacity 0 does not cover
     *  it — a one-frame jump. Now the content's CSS is applied and it
     *  is measured + laid out at its pref size pre-show. THE LAYOUT
     *  IS LOAD-BEARING (winshot A/B, D249): applyCss + prefWidth/
     *  prefHeight ALONE reproduce the D248 "No optimizati…" clip
     *  (a Labeled measured before its first layout caches the
     *  clipped string), while resize(pw, ph) + layout() right after
     *  gives every label its full width and nothing clips — the
     *  winshot's visible radios render whole and the three windows
     *  land at the D248 positions exactly. The frame size is the
     *  pref size plus the cached decoration insets (decoW/
     *  decoH — TITLE_BAR_EST/0 for the session's first window), and
     *  setX/setY land before show. setOnShown stays as the RESIDUAL
     *  corrector: it learns the real insets, re-clamps with the real
     *  frame and moves only when > 0.5 px off (the first window's
     *  small hop; later windows are exact), then reveals. refit()
     *  remains the belt for post-show row toggles. */
    static void place(FvdDesigner app, Stage dlg, Region content,
                      double anchorX) {
        double ax = anchorX;
        if (app.popupAnchorX != null) {
            ax = app.popupAnchorX;
            app.popupAnchorX = app.popupAnchorY = null;
        }
        double[] cv = app.editor != null ? app.editor.canvasScreenBox()
                                          : null;
        if (Double.isNaN(ax)) {
            // no anchor on screen (a hidden toolbar button): the
            // canvas's left edge, else the primary screen's
            ax = cv != null ? cv[0]
                : Screen.getPrimary().getVisualBounds().getMinX();
        }
        double canvasTop = cv != null ? cv[1] : ax;
        final double fax = ax, fTop = canvasTop;
        // PRE-SHOW measure + placement (D249)
        content.applyCss();
        // a caller's pre-measure step (batch 39): CSS is applied, so
        // the footer's buttons have their real pref widths — the
        // init-speed window sizes its label column off them here
        if (content.getProperties().get(PRE_MEASURE) instanceof Runnable pm) {
            pm.run();
        }
        double pw = Math.max(content.prefWidth(-1), content.minWidth(-1));
        double ph = content.prefHeight(pw);
        if (pw > 0 && ph > 0) {
            content.resize(pw, ph);
            content.layout();
            double ew = pw + (Double.isNaN(decoW) ? 0 : decoW);
            double eh = ph + (Double.isNaN(decoH) ? TITLE_BAR_EST : decoH);
            double[] p0 = clamp(fax, fTop - ABOVE_GAP - eh, ew, eh, fax,
                fTop);
            dlg.setX(p0[0]);
            dlg.setY(p0[1]);
        }
        dlg.setOpacity(0);
        dlg.setOnShown(e -> {
            double rw = dlg.getWidth(), rh = dlg.getHeight();
            if (!Double.isNaN(rw) && !Double.isNaN(rh) && rw > 0 && rh > 0) {
                Scene sc = dlg.getScene();
                if (sc != null && sc.getWidth() > 0 && sc.getHeight() > 0) {
                    // learn the real decoration insets for the next
                    // window (per-monitor DPI differences land here too)
                    decoW = Math.max(0, rw - sc.getWidth());
                    decoH = Math.max(0, rh - sc.getHeight());
                }
                double[] p = clamp(fax, fTop - ABOVE_GAP - rh, rw, rh, fax,
                    fTop);
                if (Math.abs(p[0] - dlg.getX()) > 0.5) dlg.setX(p[0]);
                if (Math.abs(p[1] - dlg.getY()) > 0.5) dlg.setY(p[1]);
            }
            dlg.setOpacity(1);
        });
    }

    /** RE-FIT a shown window to its content (D248 review fix): the
     *  windows toggle MANAGED rows live (the resolve row, the design
     *  readouts, the bridge's per-mode rows), and a JavaFX Stage never
     *  grows when its root's pref size changes after show — the last
     *  rows (ops row, [Confirm][Cancel]) fell off the bottom of a
     *  non-resizable frame. When the root's pref size differs from the
     *  scene's by more than 0.5 px the Stage re-sizes to its scene,
     *  TOP-LEFT ANCHORED since v19.25 (D254 — see the body: the
     *  window only resizes, its width ratchets — unless the window
     *  opted in through allowNarrowing (batch 39) —, the screen clamp
     *  is the one residual move; D248-D253 re-placed it by the
     *  show-time rule, bottom edge ABOVE_GAP above the canvas top).
     *  A steady state is free (no size change → no work). Called at
     *  the end of every row sync. */
    static void refit(FvdDesigner app, Stage dlg) {
        if (dlg == null || !dlg.isShowing() || dlg.getScene() == null) return;
        Scene sc = dlg.getScene();
        if (!(sc.getRoot() instanceof Region root)) return;
        // the width the scene takes = pref bounded by the (ratcheted /
        // reserved) min; comparing the bare pref re-ran sizeToScene on
        // every hook once a wide row had hidden again (batch 37)
        double pw = Math.max(root.prefWidth(-1), root.minWidth(-1)),
               ph = root.prefHeight(-1);
        Prof.count("refitCheck");
        if (Math.abs(pw - sc.getWidth()) <= 0.5
                && Math.abs(ph - sc.getHeight()) <= 0.5) {
            return;
        }
        // TOP-LEFT ANCHORED growth (batch 36, D254 — user spec: nothing
        // above a revealed row may move): the window only RESIZES here;
        // a revealed row extends the bottom edge downward, a hidden
        // one pulls it up, and the rows above stay under the mouse. It
        // never NARROWS either (the root's min width ratchets to the
        // widest layout seen), so a readout or a shorter label cannot
        // shift a button sideways. The only move left is the screen
        // clamp when the bottom/right edge would leave the visual
        // bounds — minimal, and only then. (D248-D253 re-pinned the
        // BOTTOM edge above the canvas per size change: every reveal
        // shifted the whole window UP by the revealed height — the
        // reported "jumping around".) EXCEPT a window that opted in
        // through allowNarrowing (batch 39): its min width is left
        // computed, so a row that lost a node pulls the right edge
        // back in — the init-speed window's readout, the user's
        // "remove the real speed readout space" (its spinner never
        // moves: the column left of the readout is fixed pre-show)
        if (root.getProperties().get(MAY_NARROW) != Boolean.TRUE) {
            root.setMinWidth(Math.max(root.getMinWidth(), pw));
        }
        long profT = Prof.t();
        dlg.sizeToScene();   // synchronous: the frame size is current after it
        Prof.add("refitSize", profT);
        double rw = dlg.getWidth(), rh = dlg.getHeight();
        if (Double.isNaN(rw) || Double.isNaN(rh) || rw <= 0 || rh <= 0) return;
        double x = dlg.getX(), y = dlg.getY();
        double[] p = clamp(x, y, rw, rh, x, y);
        if (Math.abs(p[0] - x) > 0.5) dlg.setX(p[0]);
        if (Math.abs(p[1] - y) > 0.5) dlg.setY(p[1]);
    }

    /** Drop a pending one-shot gizmo anchor (D248 review fix): every
     *  REFUSING early return of a window open must consume it, or the
     *  next toolbar-opened window lands at the stale 3D-click x. */
    static void dropAnchor(FvdDesigner app) {
        if (app != null) app.popupAnchorX = app.popupAnchorY = null;
    }

    /** Clamp a w×h frame at (x, y) into the visual bounds of the
     *  screen containing (sx, sy); the primary screen when none. */
    private static double[] clamp(double x, double y, double w, double h,
                                  double sx, double sy) {
        Rectangle2D vb = null;
        for (Screen s : Screen.getScreensForRectangle(sx, sy, 1, 1)) {
            vb = s.getVisualBounds();
            break;
        }
        if (vb == null) vb = Screen.getPrimary().getVisualBounds();
        double nx = Math.max(vb.getMinX(), Math.min(x, vb.getMaxX() - w));
        double ny = Math.max(vb.getMinY(), Math.min(y, vb.getMaxY() - h));
        return new double[]{ nx, ny };
    }

    /** The screen x of a toolbar button's left edge (the windows'
     *  default anchor); NaN when the node isn't on screen. */
    static double anchorOf(Node n) {
        if (n == null || n.getScene() == null) return Double.NaN;
        Bounds b = n.localToScreen(n.getBoundsInLocal());
        return b == null ? Double.NaN : b.getMinX();
    }

    /** One orientation row of the unified layout: [label][spinner]
     *  [🔒 or nothing][↺][grey design readout or nothing], left-
     *  aligned, the label on a fixed column so the rows line up. */
    static HBox orientRow(String label, Node spinner, Node lock,
                          Node revert, Label design) {
        return orientRow(label, spinner, lock, revert, null, design);
    }

    /** The 6-arg form (batch 36, D254): `extra` sits RIGHT of the ↺ and
     *  before the grey readout — the Track Piece window's "Edit with
     *  gizmo" check; the readout stays LAST so the check's x never
     *  moves when the readout shows. */
    static HBox orientRow(String label, Node spinner, Node lock,
                          Node revert, Node extra, Label design) {
        Label l = new Label(label);
        l.setMinWidth(LABEL_W);
        l.setPrefWidth(LABEL_W);
        HBox row = new HBox(8, l, spinner);
        if (lock != null) row.getChildren().add(lock);
        if (revert != null) {
            row.getChildren().add(revert);
            row.getProperties().put(REVERT_END, revert);   // rowSeparator
        }
        if (extra != null) row.getChildren().add(extra);
        if (design != null) row.getChildren().add(design);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A plain labeled row ([label][nodes…]) on the same label column. */
    static HBox labeledRow(String label, Node... nodes) {
        Label l = new Label(label);
        l.setMinWidth(LABEL_W);
        l.setPrefWidth(LABEL_W);
        HBox row = new HBox(8, l);
        row.getChildren().addAll(nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A bridge-style padlock (the D91/D96 red/green look): SELECTED
     *  = 🔓 green, unselected = 🔒 red. The MEANING is the caller's —
     *  the seam locks AND the bridge locks hold a typed angle since
     *  v19.20 (🔒 = held/typeable, 🔓 = follows the seam / the bridge
     *  aligns it — D249, the seam rule). The Track Piece window
     *  carries none since v19.25 (D254 — "Edit with gizmo" checks). */
    static ToggleButton lockToggle(String tip) {
        ToggleButton b = new ToggleButton("🔒");
        b.setFocusTraversable(false);
        b.setStyle(FvdDesigner.LOCK_SHUT_STYLE);
        b.selectedProperty().addListener((obs, o, sel) -> {
            b.setText(sel ? "🔓" : "🔒");
            b.setStyle(sel ? FvdDesigner.LOCK_OPEN_STYLE
                           : FvdDesigner.LOCK_SHUT_STYLE);
        });
        if (tip != null) Tooltip.install(b, new Tooltip(tip));
        return b;
    }

    /** The small ↺ — the padlocks' size and padding, neutral look. */
    static Button revertBtn(String tip) {
        Button b = new Button();
        b.setGraphic(FvdDesigner.circleArrowIcon(11, false,
            javafx.scene.paint.Color.rgb(55, 58, 66)));
        b.setFocusTraversable(false);
        b.setStyle("-fx-font-size: 10px; -fx-padding: 1 4 1 4;");
        if (tip != null) Tooltip.install(b, new Tooltip(tip));
        return b;
    }

    /** The grey OTHER-TIER readout right of a pitch/roll row's ↺
     *  (v19.20, D249 — was the "design 12.34°" label of D222/D248):
     *  reads "design 12.34°" while the boxes edit the real
     *  orientation (LOCKSTEP / REAL_ONLY) and "real 12.34°" while they
     *  edit the design one (DESIGN_ONLY). SWAPPABLE while the caller
     *  says so (the independent check is on and the other choice is
     *  not grayed): the node editor's twin click-swap recipe — a
     *  constant transparent 1 px border (hover never shifts layout),
     *  hover outline, HAND cursor, a 300 ms tooltip, click = the
     *  caller's swap (mode := the other tier). A plain grey label
     *  otherwise (no hover, no handler). */
    static final class Readout {
        final Label node = new Label();
        private boolean swappable;
        private final Tooltip tip = new Tooltip(READOUT_SWAP_TIP);
        private static final String BASE = "-fx-text-fill: rgb(120,125,140);"
            + " -fx-font-size: 10px; -fx-border-width: 1;"
            + " -fx-border-radius: 3; -fx-padding: 0 3 0 3;"
            + " -fx-border-color: transparent;";
        private static final String HOT = "-fx-text-fill: rgb(120,125,140);"
            + " -fx-font-size: 10px; -fx-border-width: 1;"
            + " -fx-border-radius: 3; -fx-padding: 0 3 0 3;"
            + " -fx-border-color: rgb(120,125,140);";

        /** The reserved slot width (batch 36, D254): the widest text
         *  the readout can show, measured in its own font, so a row's
         *  width never changes when the readout appears — the Strict
         *  Seam window grew ~100 px per conflict before. */
        static final double READOUT_W = reservedWidth();

        private static double reservedWidth() {
            javafx.scene.text.Text t = new javafx.scene.text.Text("design -180.00°");
            t.setFont(javafx.scene.text.Font.font(10));
            return Math.ceil(t.getLayoutBounds().getWidth()) + 10;   // + padding/border
        }

        Readout(Runnable onSwap) {
            node.setStyle(BASE);
            node.setVisible(false);
            // MANAGED at a fixed width from the start (D254): showing
            // the readout only toggles visibility — no layout change
            node.setManaged(true);
            node.setMinWidth(READOUT_W);
            node.setPrefWidth(READOUT_W);
            node.setMaxWidth(READOUT_W);
            tip.setShowDelay(javafx.util.Duration.millis(300));
            node.setOnMouseEntered(ev -> {
                if (swappable) node.setStyle(HOT);
            });
            node.setOnMouseExited(ev -> node.setStyle(BASE));
            node.setOnMouseClicked(ev -> {
                if (swappable && ev.getButton()
                        == javafx.scene.input.MouseButton.PRIMARY) {
                    onSwap.run();
                }
            });
        }

        /** Show/hide with the OTHER tier's prefix + angle (NaN → "—")
         *  and the swap affordance. */
        void set(boolean show, String prefix, double deg, boolean swap) {
            node.setVisible(show);   // the slot stays managed (D254)
            if (show) {
                node.setText(prefix + " " + (Double.isNaN(deg) ? "—"
                    : String.format(java.util.Locale.US, "%.2f°",
                        FvdDesigner.z3(deg))));
            }
            boolean s = show && swap;
            if (s != swappable) {
                swappable = s;
                if (s) Tooltip.install(node, tip);
                else Tooltip.uninstall(node, tip);
                node.setCursor(s ? javafx.scene.Cursor.HAND : null);
                if (!s) node.setStyle(BASE);
            }
        }
    }

    /** The other tier's prefix for a piece's readout under its mode. */
    static String readoutPrefix(TrackPiece p) {
        return p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
            ? "real" : "design";
    }

    /** A 1 px separator line between the yaw and pitch/roll rows. */
    static Region thinSeparator() {
        Region r = new Region();
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setMaxHeight(1);
        r.setMaxWidth(Double.MAX_VALUE);
        r.setStyle("-fx-background-color: rgb(200,204,212);");
        return r;
    }

    private static final String REVERT_END = "uvd.revertEnd";
    /** A row's reserved width, read by show() (the resolve row). */
    private static final String RESERVE_W = "uvd.reserveW";
    /** The window's footer HBox, stored on the content by show() so a
     *  caller's pre-measure can read its pref width (batch 39). */
    private static final String FOOT = "uvd.foot";
    /** A caller's Runnable run by place() right after the content's
     *  CSS is applied and BEFORE the pre-show measure (batch 39). */
    private static final String PRE_MEASURE = "uvd.preMeasure";
    /** Boolean.TRUE = refit() releases the D254 min-width ratchet for
     *  THIS window: it narrows when its content narrows (batch 39 —
     *  the init-speed window's grey readout, the second deliberate
     *  exception to the no-jump rule; every other window keeps the
     *  ratchet). */
    private static final String MAY_NARROW = "uvd.mayNarrow";

    /** Let this window NARROW when a row loses width (batch 39, user
     *  spec — the init-speed window: "when there is not a speed
     *  conflict remove the real speed readout space"). refit() then
     *  skips the ratchet; a steady state is still free. Used only by
     *  that window — the no-jump rule stands for every other. */
    static void allowNarrowing(Region content) {
        content.getProperties().put(MAY_NARROW, Boolean.TRUE);
    }

    /** Register a PRE-SHOW measure step (batch 39): `r` runs inside
     *  place() once the content's CSS is applied (every Labeled has
     *  its skin, font and padding — pref widths are real) and before
     *  the window is measured, so a caller can size a column off the
     *  footer's pref width (footOf) and the window opens at the
     *  right size — no post-show hop. */
    static void preMeasure(Region content, Runnable r) {
        content.getProperties().put(PRE_MEASURE, r);
    }

    /** The footer HBox show() appended to `content` ([footLead…]
     *  [Confirm][Cancel]); null before show() built it. */
    static HBox footOf(Region content) {
        return content.getProperties().get(FOOT) instanceof HBox h ? h : null;
    }

    /** The yaw | pitch-roll separator (batch 37, user spec): from the
     *  content's left edge to the right edge of the rows' ↺, and
     *  lighter than the full-width lines (rgb 222,225,230 — halfway
     *  from their 200,204,212 to the window's background). The width
     *  is BOUND to the ↺'s layout position in its row (row and line
     *  share parent and left inset), so it follows the label / spinner
     *  / padlock columns of whichever window uses it; layoutX +
     *  layoutBounds, never boundsInParent (a focus ring would count).
     *  The rows given may disagree by a pixel or two — the line ends
     *  at the right-most ↺. */
    static Region rowSeparator(HBox... rows) {
        Region r = thinSeparator();
        r.setStyle("-fx-background-color: rgb(222,225,230);");
        java.util.List<Node> ends = new java.util.ArrayList<>();
        for (HBox row : rows) {
            if (row != null
                    && row.getProperties().get(REVERT_END) instanceof Node n) {
                ends.add(n);
            }
        }
        if (ends.isEmpty()) return r;
        javafx.beans.Observable[] deps =
            new javafx.beans.Observable[ends.size() * 2];
        for (int i = 0; i < ends.size(); i++) {
            deps[2 * i] = ends.get(i).layoutXProperty();
            deps[2 * i + 1] = ends.get(i).layoutBoundsProperty();
        }
        r.maxWidthProperty().bind(
            javafx.beans.binding.Bindings.createDoubleBinding(() -> {
                double m = 0;
                for (Node n : ends) {
                    m = Math.max(m, n.getLayoutX()
                        + n.getLayoutBounds().getWidth());
                }
                return m > 0 ? m : Double.MAX_VALUE;   // pre-layout: full
            }, deps));
        return r;
    }

    /** A growing gap for show()'s footer: lead nodes BEFORE it sit at
     *  the window's left edge, Confirm / Cancel stay at the right
     *  (batch 37). */
    static Region footSpring() {
        Region s = new Region();
        HBox.setHgrow(s, javafx.scene.layout.Priority.ALWAYS);
        return s;
    }

    /** A button width that cannot clip its text (batch 37 — the
     *  bridge window's "Design → Real Orientati…" report): the text
     *  measured in the button's own font + modena's 0.667 em side
     *  padding + 4 px of slack. A button laid out at EXACTLY its
     *  snapped pref width can lose a sub-pixel to snapping on a
     *  fractional render scale and ellipsize. */
    static double fixedButtonWidth(String text, double fontPx) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(text);
        t.setFont(javafx.scene.text.Font.font(fontPx));
        return Math.ceil(t.getLayoutBounds().getWidth())
            + 2 * Math.ceil(0.666667 * fontPx) + 4;
    }

    /** Small gray caption text. */
    static Label grayNote(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: rgb(150,155,165); -fx-font-size: 10px;");
        return l;
    }

    /** THE ORIENTATION-MODE ROW (v19.20, D249 — user spec, replacing
     *  D248's [Lock Track Shape (creates orientation conflict)] [Only
     *  Affect Orientation Until Next Strict Seam]): a CheckBox "Edit
     *  Real and Design Independently" (unchecked =
     *  LOCKSTEP); checked reveals, directly below and condensed, the
     *  two RadioButtons "Edit real orientation (locks track shape)"
     *  (= REAL_ONLY, pre-selected on check) / "Edit design
     *  orientation" (= DESIGN_ONLY) and below them the "Affect
     *  Subsequent Strict Seams" check (HIDDEN while the check is off
     *  — not grayed; grayed "Applies while editing the real
     *  orientation." under DESIGN_ONLY). Pure ROUTING writes on the
     *  live piece — a flip moves nothing and rebuilds nothing (the
     *  D222 rule), one global undo step each; the caller's afterWrite
     *  resyncs its boxes (they show the other tier now) and the rigs
     *  (the mode rides the gizmo sig). `designWhy` = the live gray
     *  reason of the design radio (a bridge target under Optimize
     *  position and orientation — the mutual exclusion), null =
     *  enabled; `rowWhy` = the caller's reason for graying the WHOLE
     *  row (a follower's seam name in the Track Piece window). A
     *  typed-but-unentered box COMMITS to the tier it edited before
     *  any flip (settleTyping), so no number lands on the wrong tier.
     *  swap() = the readouts' click: REAL_ONLY <-> DESIGN_ONLY. */
    static final class FlagRow {
        final VBox node;
        final CheckBox indep, affect;
        final RadioButton realR, designR;
        final Runnable sync;
        private final Runnable swapImpl;

        private FlagRow(VBox node, CheckBox indep, RadioButton realR,
                        RadioButton designR, CheckBox affect,
                        Runnable sync, Runnable swapImpl) {
            this.node = node;
            this.indep = indep;
            this.realR = realR;
            this.designR = designR;
            this.affect = affect;
            this.sync = sync;
            this.swapImpl = swapImpl;
        }

        /** The readout click: the other tier becomes the edited one
         *  (only while the check is on and the target choice is not
         *  grayed — the caller decides swappability). */
        void swap() { swapImpl.run(); }
    }

    /** Commit a typed-but-unentered box to the tier it was editing
     *  and move focus off it (D249): a mode flip re-labels every box
     *  with the OTHER tier, and a focused editor's text would
     *  otherwise survive the resync (setUnlessTyping) and commit the
     *  old tier's number onto the new tier on Enter. The root takes
     *  focus (requestFocus needs no focusTraversable). */
    static void settleTyping(Node any) {
        if (any == null) return;
        Scene sc = any.getScene();
        if (sc == null) return;
        FvdDesigner.commitFocusedNumericEditor(sc);
        if (sc.getRoot() != null) sc.getRoot().requestFocus();
    }

    /** hasNext (batch 36, D254): the "Affect Subsequent Strict Seams"
     *  row shows only while a junction FOLLOWS the piece in its run
     *  (Coaster.hasSubsequentJunction); null = always. The stored flag
     *  is inert while hidden and reappears with its value when a seam
     *  or bridge is added later. */
    static FlagRow flagRow(Supplier<TrackPiece> live,
                           BooleanSupplier canWrite, Runnable pushUndo,
                           Runnable markDirty, Supplier<String> rowWhy,
                           Supplier<String> designWhy,
                           Runnable afterWrite, BooleanSupplier hasNext) {
        CheckBox indep = new CheckBox(INDEP_LABEL);
        indep.setFocusTraversable(false);
        indep.setStyle("-fx-font-size: 11px;");
        Tooltip.install(indep, new Tooltip(INDEP_TIP));
        RadioButton realR = new RadioButton(REAL_RADIO_LABEL);
        RadioButton designR = new RadioButton(DESIGN_RADIO_LABEL);
        ToggleGroup tg = new ToggleGroup();
        for (RadioButton r : new RadioButton[]{ realR, designR }) {
            r.setToggleGroup(tg);
            r.setFocusTraversable(false);
            r.setStyle("-fx-font-size: 11px;");
        }
        Tooltip.install(realR, new Tooltip(REAL_RADIO_TIP));
        Tooltip.install(designR, new Tooltip(DESIGN_RADIO_TIP));
        CheckBox affect = new CheckBox(AFFECT_LABEL);
        affect.setFocusTraversable(false);
        affect.setStyle("-fx-font-size: 11px;");
        Tooltip.install(affect, new Tooltip(AFFECT_TIP));
        final VBox[] rowRef = new VBox[1];
        // the row reason outranks the inner hints while the WHOLE row
        // is grayed (D248 review fix: a disabled ancestor disables the
        // children too, and the innermost tooltip wins under the mouse)
        Supplier<String> outer = () -> {
            if (rowRef[0] != null && rowRef[0].isDisabled() && rowWhy != null) {
                String w = rowWhy.get();
                if (w != null && !w.isEmpty()) return w;
            }
            return null;
        };
        StackPane affectWrap = GraphEditor.grayHint(affect, () -> {
            String w = outer.get();
            return w != null ? w : AFFECT_GRAY_DESIGN;
        });
        StackPane designWrap = GraphEditor.grayHint(designR, () -> {
            String w = outer.get();
            if (w != null) return w;
            String d = designWhy == null ? null : designWhy.get();
            return d == null ? "" : d;
        });
        // the sub-block: radios + the carry check, indented under the
        // check's box, shown only while the check is on
        VBox sub = new VBox(2, realR, designWrap, affectWrap);
        sub.setPadding(new javafx.geometry.Insets(0, 0, 0, 22));
        VBox row = new VBox(3, indep, sub);
        rowRef[0] = row;
        row.setAlignment(Pos.CENTER_LEFT);
        final boolean[] syncing = { false };
        Runnable sync = () -> {
            TrackPiece p = live.get();
            if (p == null) return;
            TrackPiece.OrientMode m = p.orientMode;
            boolean on = m != TrackPiece.OrientMode.LOCKSTEP;
            syncing[0] = true;
            try {
                if (indep.isSelected() != on) indep.setSelected(on);
                boolean d = m == TrackPiece.OrientMode.DESIGN_ONLY;
                if (realR.isSelected() != (on && !d)) realR.setSelected(on && !d);
                if (designR.isSelected() != d) designR.setSelected(d);
                if (affect.isSelected() != p.affectSubsequentSeams) {
                    affect.setSelected(p.affectSubsequentSeams);
                }
            } finally {
                syncing[0] = false;
            }
            sub.setVisible(on);
            sub.setManaged(on);
            // the carry row only while a junction follows (D254)
            boolean next = hasNext == null || hasNext.getAsBoolean();
            affectWrap.setVisible(on && next);
            affectWrap.setManaged(on && next);
            // inert under DESIGN_ONLY (the real entry never moves) —
            // grayed with the reason; the design radio grays with the
            // bridge exclusion (the whole row's gray outranks both)
            affect.setDisable(m == TrackPiece.OrientMode.DESIGN_ONLY);
            designR.setDisable(designWhy != null && designWhy.get() != null);
        };
        // ONE mode writer for the check, the radios and the readouts'
        // swap: a refused click just resyncs; an equal mode is a no-op
        java.util.function.Consumer<TrackPiece.OrientMode> setMode = m -> {
            if (syncing[0]) return;
            TrackPiece p = live.get();
            if (p == null || !canWrite.getAsBoolean()) {
                sync.run();
                return;
            }
            if (p.orientMode == m) {
                sync.run();
                return;
            }
            if (m == TrackPiece.OrientMode.DESIGN_ONLY && designWhy != null
                    && designWhy.get() != null) {
                sync.run();   // grayed choice (a race with the hover)
                return;
            }
            settleTyping(indep);
            pushUndo.run();
            p.orientMode = m;
            markDirty.run();
            sync.run();
            if (afterWrite != null) afterWrite.run();
        };
        indep.setOnAction(e -> setMode.accept(indep.isSelected()
            ? TrackPiece.OrientMode.REAL_ONLY
            : TrackPiece.OrientMode.LOCKSTEP));
        realR.setOnAction(e -> setMode.accept(TrackPiece.OrientMode.REAL_ONLY));
        designR.setOnAction(e ->
            setMode.accept(TrackPiece.OrientMode.DESIGN_ONLY));
        affect.setOnAction(e -> {
            if (syncing[0]) return;
            TrackPiece p = live.get();
            if (p == null || !canWrite.getAsBoolean()) {
                sync.run();
                return;
            }
            pushUndo.run();
            p.affectSubsequentSeams = affect.isSelected();
            markDirty.run();
            sync.run();
        });
        Runnable swap = () -> {
            TrackPiece p = live.get();
            if (p == null) return;
            if (p.orientMode == TrackPiece.OrientMode.LOCKSTEP) return;
            setMode.accept(p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                ? TrackPiece.OrientMode.REAL_ONLY
                : TrackPiece.OrientMode.DESIGN_ONLY);
        };
        sync.run();
        return new FlagRow(row, indep, realR, designR, affect, sync, swap);
    }

    /** May a piece's readout SWAP the mode (D249)? Only while the
     *  independent check is on, and never onto a grayed design
     *  choice (a bridge target under Optimize position and
     *  orientation while the boxes edit the real tier). */
    static boolean swappable(TrackPiece p, Supplier<String> designWhy) {
        if (p == null || p.orientMode == TrackPiece.OrientMode.LOCKSTEP) {
            return false;
        }
        if (p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY) return true;
        return designWhy == null || designWhy.get() == null;
    }

    /** The Design ↔ Real resolve row (D248): both buttons, Real →
     *  Design grayed with a LIVE reason (the optimize-orientation
     *  march) — the whole row is shown only while conflicted (the
     *  caller's sync sets visible/managed). */
    static final class ResolveRow {
        final HBox node;
        final Button d2r, r2d;
        /** Re-read the R→D gray reason (null = enabled). */
        final Runnable sync;

        private ResolveRow(HBox node, Button d2r, Button r2d,
                           Runnable sync) {
            this.node = node;
            this.d2r = d2r;
            this.r2d = r2d;
            this.sync = sync;
        }

        void setShown(boolean show) {
            node.setVisible(show);
            node.setManaged(show);
        }
    }

    /** d2rWhy / r2dWhy: a live gray reason per button (null = enabled)
     *  — the Track Piece window grays both with the seam's name for a
     *  follower; the bridge and Track Piece windows gray R→D under an
     *  optimize-orientation bridge. */
    static ResolveRow resolveRow(Runnable onD2r, Runnable onR2d,
                                 Supplier<String> d2rWhy,
                                 Supplier<String> r2dWhy) {
        Button d2r = new Button("Design → Real Orientation");
        d2r.setFocusTraversable(false);
        d2r.setStyle("-fx-font-size: 11px;");
        Tooltip.install(d2r, new Tooltip(D2R_TIP));
        Button r2d = new Button("Real → Design Orientation");
        r2d.setFocusTraversable(false);
        r2d.setStyle("-fx-font-size: 11px;");
        Tooltip.install(r2d, new Tooltip(R2D_TIP));
        d2r.setOnAction(e -> onD2r.run());
        r2d.setOnAction(e -> onR2d.run());
        // FIXED widths with slack, never shrinking (batch 37): the
        // same size in all three windows, whatever the render scale
        double bw = Math.max(fixedButtonWidth(d2r.getText(), 11),
            fixedButtonWidth(r2d.getText(), 11));
        for (Button b : new Button[]{ d2r, r2d }) {
            b.setMinWidth(bw);
            b.setPrefWidth(bw);
        }
        HBox row = new HBox(8,
            GraphEditor.grayHint(d2r, () -> {
                String w = d2rWhy.get();
                return w == null ? "" : w;
            }),
            GraphEditor.grayHint(r2d, () -> {
                String w = r2dWhy.get();
                return w == null ? "" : w;
            }));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(2 * bw + 8);
        row.getProperties().put(RESERVE_W, 2 * bw + 8);   // show() reads it
        Runnable sync = () -> {
            d2r.setDisable(d2rWhy.get() != null);
            r2d.setDisable(r2dWhy.get() != null);
        };
        sync.run();
        return new ResolveRow(row, d2r, r2d, sync);
    }
}
