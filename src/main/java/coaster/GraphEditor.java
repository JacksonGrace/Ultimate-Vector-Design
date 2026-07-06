package coaster;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.geometry.VPos;
import javafx.scene.AmbientLight;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.robot.Robot;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The force-curve GRAPH EDITOR (Phase 1 milestone B split, D52) —
 * extracted verbatim from FvdDesigner with outer references made
 * explicit through `app`. All behavior decisions and their history
 * live in docs/DECISIONS.md; this class is the node/handle editing
 * surface: lanes, selection, drags, undo history, node dialog.
 */
class GraphEditor extends Region {

    /** The shell: document, settings, viewport and pipeline access. */
    private final FvdDesigner app;

    private static final int LANES = 3;
    private static final String[] LANE_NAMES = { "Vertical G", "Lateral G", "Roll rate (deg/s)" };
    private static final String[] LANE_UNITS = { " g", " g", " °/s" };
    // default visible ranges; the axis auto-expands (1-2-5 rounded)
    // whenever the curve exceeds them within the current time window
    private static final double[] DEF_MIN = { -1, -1, -150 };
    private static final double[] DEF_MAX = {  4,  1,  150 };
    private final double[] curMin = DEF_MIN.clone();
    private final double[] curMax = DEF_MAX.clone();

    private static final double MARGIN_LEFT = 58, MARGIN_RIGHT = 14;
    private static final double MARGIN_TOP = 8, MARGIN_BOTTOM = 32;
    private static final double NODE_RADIUS = 5, NODE_HIT = 9;
    private static final double KNOB_RADIUS = 4.5, KNOB_HIT = 9;
    private static final double HANDLE_ALPHA = 0.45;
    private static final double INACTIVE_ALPHA = 0.25;
    private static final double MIN_GAP = 0.01;   // minimum transition length
    private static final double INSERT_GAP = 0.02; // insert may get almost as close
    private static final double MIN_FRAC = 0.02;

    // node-drag snap (with Ctrl): TRANSITION length in time, value on a
    // GLOBAL absolute grid — the steps are adjustable in Edit > Options
    private double snapT() { return app.settings.snapT(); }
    private double snapVal(int lane) {
        return lane == 2 ? app.settings.snapRoll() : app.settings.snapG();
    }

    // handle-length snap points (with Ctrl): the named ease fractions
    // (flat-handle Bezier matching each ease's centre slope) padded
    // with 5% steps below cubic and 10% steps beyond circle
    private static final double SINE_FRAC = 1 - 2 / Math.PI;
    private static final double[] HANDLE_PRESETS = {
        0.05, 0.10, 0.15, 0.20, 0.25, 0.30,
        1.0 / 3, SINE_FRAC, 0.40, 7.0 / 15, 0.5523,
        0.60, 0.70, 0.80, 0.90, 1.00, 1.10, 1.20, 1.30, 1.40, 1.50 };
    private static final String[] HANDLE_PRESET_NAMES = {
        null, null, null, null, null, null,
        "cubic", "sine", null, "quintic", "circle",
        null, null, null, null, null, null, null, null, null, null };
    private String dragPresetName = null;

    private final Canvas canvas = new Canvas();

    private int activeLane = 0;
    private int selIdx = -1;

    private int dragNodeIdx = -1;
    private int dragHandleIdx = -1;
    private boolean dragHandleIsOut = false;
    private double hoverT = Double.NaN;   // insert preview position on the curve
    private boolean hoverSlope = false;   // Shift while hovering: show the slope

    // time-axis view window; viewT1 < 0 = auto (whole ride, follows edits)
    private double viewT0 = 0, viewT1 = -1;
    private double panLastX = Double.NaN;
    // window frozen for the duration of a drag gesture so the mapping
    // under the cursor stays stable while the ride length changes
    private double dragWinT0 = Double.NaN, dragWinT1;

    // node-drag axis lock: 0 free, 1 vertical only (Z), 2 horizontal only (X)
    private int axisLock = 0;
    // transient axis constraint while SHIFT is held during a drag
    // (chosen from the dominant direction of the first few pixels);
    // anchored to where the node STARTED the gesture
    private int shiftAxis = 0;
    private double gestureStartX, gestureStartY;
    private double gestureNodeT, gestureNodeV;

    // rate-based view growth while dragging past a plot edge: the
    // farther beyond the edge, the faster the axis extends (smooth,
    // controllable — an AnimationTimer so it grows even with the
    // mouse held still)
    private double lastDragX, lastDragY;
    private boolean lastDragSnap, lastDragShift;
    private long dragTimerLast = 0;
    private final AnimationTimer dragGrowTimer = new AnimationTimer() {
        @Override public void handle(long now) {
            if (dragTimerLast == 0) { dragTimerLast = now; return; }
            double dt = Math.min((now - dragTimerLast) / 1e9, 0.05);
            dragTimerLast = now;
            if (dragNodeIdx < 0 && dragHandleIdx < 0) return;
            double plotRight = MARGIN_LEFT + plotWidth();
            double plotBottom = MARGIN_TOP + plotHeight();
            double winW = dragWinT1 - dragWinT0;
            double vRange = curMax[activeLane] - curMin[activeLane];
            boolean grew = false;
            if (lastDragX > plotRight) {
                dragWinT1 += (lastDragX - plotRight) * 3 * dt * winW / plotWidth();
                grew = true;
            } else if (lastDragX < MARGIN_LEFT && dragWinT0 > 0) {
                dragWinT0 = Math.max(0,
                    dragWinT0 - (MARGIN_LEFT - lastDragX) * 3 * dt * winW / plotWidth());
                grew = true;
            }
            if (lastDragY < MARGIN_TOP) {
                curMax[activeLane] += (MARGIN_TOP - lastDragY) * 3 * dt * vRange / plotHeight();
                grew = true;
            } else if (lastDragY > plotBottom) {
                curMin[activeLane] -= (lastDragY - plotBottom) * 3 * dt * vRange / plotHeight();
                grew = true;
            }
            if (grew) {
                commitGestureSnapshot();
                if (dragNodeIdx >= 0) {
                    dragNodeTo(lastDragX, lastDragY, lastDragSnap, lastDragShift);
                } else {
                    dragHandleTo(lastDragX, lastDragY, lastDragShift, lastDragSnap);
                }
            }
        }
    };

    // box select (rubber band on empty plot; shift adds to selection)
    private double boxX0 = Double.NaN, boxY0, boxX1, boxY1;
    private boolean boxAdd;

    // an off-screen handle draws as a dashed stub with a hollow marker
    // at the plot edge; clicking the marker expands the value scale to
    // bring the whole handle into view (cleared when selection changes)
    private boolean handleViewExpand = false;
    private double[] outEdgeMarker, inEdgeMarker;   // set during drawHandles

    // multi-selection (shift+click adds); selIdx stays the primary node
    private final java.util.Set<Integer> selSet = new HashSet<>();

    private long lastNudgeNanos = 0;   // coalesces arrow-key undo snapshots

    // DOCUMENT-level history (D51): snapshots carry lanes + the
    // path-shaping settings (heart/gauge/start/v0), not lanes alone
    private final Deque<FvdDesigner.DocState> undoStack = new ArrayDeque<>();
    private final Deque<FvdDesigner.DocState> redoStack = new ArrayDeque<>();
    private FvdDesigner.DocState pendingSnapshot = null;
    private boolean gestureMoved = false;
    private static final int UNDO_LIMIT = 100;

    GraphEditor(FvdDesigner app) {
        this.app = app;
        setPrefHeight(260);
        setStyle("-fx-background-color: rgb(20,22,28);");
        getChildren().add(canvas);

        canvas.setOnMousePressed(e -> {
            hoverT = Double.NaN;   // no insert preview while interacting
            if (e.getButton() == MouseButton.PRIMARY) {
                int hit = hitNode(e.getX(), e.getY());
                // the node body ALWAYS outranks its own knobs, so a node
                // overlapping its handles stays grabbable
                if (selIdx >= 0 && hit != selIdx) {
                    // an off-screen handle's edge marker: expand the view
                    // to bring the whole handle in reach
                    if (hitEdgeMarker(e.getX(), e.getY())) {
                        handleViewExpand = true;
                        redraw();
                        return;
                    }
                    int knob = hitKnob(e.getX(), e.getY());
                    if (knob != 0) {
                        beginGesture();
                        dragHandleIdx = selIdx;
                        dragHandleIsOut = knob > 0;
                        lastDragX = e.getX();
                        lastDragY = e.getY();
                        lastDragSnap = e.isControlDown();
                        lastDragShift = app.slideKey;
                        dragTimerLast = 0;
                        dragGrowTimer.start();
                        return;
                    }
                }
                if (hit >= 0) {
                    beginGesture();
                    gestureStartX = e.getX();
                    gestureStartY = e.getY();
                    gestureNodeT = active().get(hit).t;
                    gestureNodeV = active().get(hit).val;
                    if (e.isShiftDown()) {
                        selSet.add(hit);               // shift+click adds
                    } else if (!selSet.contains(hit)) {
                        selSet.clear();                // plain click on a
                        selSet.add(hit);               // non-member: single
                    }
                    if (hit != selIdx) {   // lock/expand are per selection
                        axisLock = 0;
                        handleViewExpand = false;
                    }
                    dragNodeIdx = hit;
                    selIdx = hit;
                    lastDragX = e.getX();
                    lastDragY = e.getY();
                    lastDragSnap = e.isControlDown();
                    lastDragShift = app.slideKey;
                    dragTimerLast = 0;
                    dragGrowTimer.start();
                    redraw();
                } else {
                    // a click ON the active curve inserts a node there
                    double t = curveHitT(e.getX(), e.getY());
                    if (!Double.isNaN(t)) {
                        insertNodeOnCurve(t);
                        return;
                    }
                    // active lane missed: a click on a dimmed lane's node
                    // or curve switches editing to it (active lane wins,
                    // since its hit tests all ran first)
                    int[] other = hitOtherLane(e.getX(), e.getY());
                    if (other != null) {
                        setActiveLane(other[0]);
                        if (other[1] >= 0) selIdx = other[1];
                        redraw();
                    } else {
                        // empty plot: begin a box select (deselection
                        // happens on release if the box stays tiny)
                        boxX0 = e.getX(); boxY0 = e.getY();
                        boxX1 = boxX0;    boxY1 = boxY0;
                        boxAdd = e.isShiftDown();
                    }
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                int hit = hitNode(e.getX(), e.getY());
                if (hit >= 0) deleteNode(hit);
                else panLastX = e.getX();   // right-drag on empty = pan
            }
        });

        // scroll = zoom the time axis around the cursor; zooming all the
        // way out returns to auto-full (which follows ride-length edits)
        canvas.setOnScroll(e -> {
            double wheel = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
            double t0 = viewStart(), t1 = viewEnd();
            double width = t1 - t0;
            double newW = width * (wheel > 0 ? 1 / 1.15 : 1.15);
            if (newW >= domain()) {
                viewT0 = 0; viewT1 = -1;
                redraw();
                return;
            }
            newW = Math.max(0.1, newW);
            double tm = xToT(e.getX());
            double n0 = FvdDesigner.clamp(tm - (tm - t0) / width * newW, 0, domain() - newW);
            viewT0 = n0;
            viewT1 = n0 + newW;
            redraw();
        });

        canvas.setOnMouseMoved(e -> {
            hoverSlope = e.isShiftDown();
            double t = (hitNode(e.getX(), e.getY()) < 0
                    && (selIdx < 0 || hitKnob(e.getX(), e.getY()) == 0))
                    ? curveHitT(e.getX(), e.getY()) : Double.NaN;
            if (Double.isNaN(t) && Double.isNaN(hoverT)) return;
            hoverT = t;
            redraw();
        });
        canvas.setOnMouseExited(e -> {
            if (!Double.isNaN(hoverT)) { hoverT = Double.NaN; redraw(); }
        });

        canvas.setOnMouseDragged(e -> {
            if (!Double.isNaN(boxX0) && e.isPrimaryButtonDown()) {
                boxX1 = e.getX();
                boxY1 = e.getY();
                redraw();
                return;
            }
            if (!Double.isNaN(panLastX) && e.isSecondaryButtonDown()) {
                double t0 = viewStart(), t1 = viewEnd();
                if (t1 - t0 < domain()) {   // full view has nowhere to pan
                    double shift = (panLastX - e.getX()) * (t1 - t0) / plotWidth();
                    double n0 = FvdDesigner.clamp(t0 + shift, 0, domain() - (t1 - t0));
                    viewT0 = n0;
                    viewT1 = n0 + (t1 - t0);
                    redraw();
                }
                panLastX = e.getX();
                return;
            }
            if (dragHandleIdx >= 0) {
                commitGestureSnapshot();
                lastDragX = e.getX();
                lastDragY = e.getY();
                lastDragSnap = e.isControlDown();
                lastDragShift = app.slideKey;
                // free by default; S (held) slides in-line, CTRL snaps
                dragHandleTo(e.getX(), e.getY(), app.slideKey, e.isControlDown());
            } else if (dragNodeIdx >= 0) {
                commitGestureSnapshot();
                lastDragX = e.getX();
                lastDragY = e.getY();
                lastDragSnap = e.isControlDown();
                lastDragShift = app.slideKey;
                dragNodeTo(e.getX(), e.getY(), e.isControlDown(), app.slideKey);
            }
        });

        canvas.setOnMouseReleased(e -> {
            dragGrowTimer.stop();
            if (!Double.isNaN(boxX0)) {
                double x0 = Math.min(boxX0, boxX1), x1 = Math.max(boxX0, boxX1);
                double y0 = Math.min(boxY0, boxY1), y1 = Math.max(boxY0, boxY1);
                if (x1 - x0 < 3 && y1 - y0 < 3) {
                    // a plain click on empty plot: deselect
                    if (selIdx >= 0 && !boxAdd) {
                        selIdx = -1;
                        selSet.clear();
                        axisLock = 0;
                        handleViewExpand = false;
                    }
                } else {
                    if (!boxAdd) selSet.clear();
                    List<BezierBaker.Node> lane = active();
                    int first = -1;   // primary = EARLIEST node in the box
                    for (int i = 0; i < lane.size(); i++) {
                        double px = tToX(lane.get(i).t);
                        double py = valToY(activeLane, lane.get(i).val);
                        if (px >= x0 && px <= x1 && py >= y0 && py <= y1) {
                            selSet.add(i);
                            if (first < 0) first = i;
                        }
                    }
                    if (first >= 0) selIdx = first;
                    else if (selSet.isEmpty()) selIdx = -1;
                    axisLock = 0;
                }
                boxX0 = Double.NaN;
            }
            dragNodeIdx = -1;
            dragHandleIdx = -1;
            dragPresetName = null;
            panLastX = Double.NaN;
            dragWinT0 = Double.NaN;   // unfreeze the time window
            shiftAxis = 0;
            pendingSnapshot = null;
            gestureMoved = false;
            redraw();                 // view re-fits after the drag
        });

        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
                int hit = hitNode(e.getX(), e.getY());
                if (hit >= 0) showNodeEditor(hit);
            }
        });
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        redraw();
    }

    private List<BezierBaker.Node> active() { return app.lanes.get(activeLane); }

    void refresh() { redraw(); }

    /** A node or handle drag gesture is in progress (keyboard combos
     *  formed by held drag modifiers are suppressed while true). */
    boolean isDragging() { return dragNodeIdx >= 0 || dragHandleIdx >= 0; }

    /** Sync the 3D node markers with this editor's lane/selection. */
    void refreshMarkers() { app.refreshTrackMarkers(activeLane, selSet, selIdx); }

    /** Spine-level track position of the primary selected node, or
     *  null when nothing is selected (side-view centering). */
    Vec3 selectedTrackPoint() {
        if (selIdx < 0 || selIdx >= active().size()) return null;
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return null;
        int idx = (int) FvdDesigner.clamp(Math.round(active().get(selIdx).t / FvdDesigner.INTEGRATE_DT),
                              0, app.lastSamples.size() - 1);
        FVDIntegrator.Sample sm = app.lastSamples.get(idx);
        return sm.pos().sub(sm.U().scale(app.heartOffset));
    }

    /** Ctrl+click on the 3D track: insert at that ride time — same
     *  rules as clicking the graph curve (exact split, gap guards,
     *  selects the new node). */
    void insertAtTime(double t) { insertNodeOnCurve(t); }

    /** Shift pressed/released while hovering: toggle the slope readout
     *  without waiting for the next mouse move. */
    void shiftHover(boolean on) {
        if (hoverSlope == on) return;
        hoverSlope = on;
        if (!Double.isNaN(hoverT)) redraw();
    }

    void clearSelection() {
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
    }

    // ---------- toolbar actions ----------

    void setActiveLane(int lane) {
        if (lane == activeLane) return;
        activeLane = lane;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        app.laneButtons[lane].setSelected(true);
        redraw();
    }

    /** Append action: with a node selected, insert a 1 s flat hold right
     *  after it (everything later shifts 1 s, regardless of the ripple
     *  toggles); otherwise extend the ride end by 1 s — ACTIVE lane only
     *  (the others keep their dotted hold, no nodes added). */
    void appendAction() {
        if (selIdx >= 0) insertHoldAfterSelected();
        else appendSecond();
    }

    private void insertHoldAfterSelected() {
        pushUndoSnapshot();
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node sel = lane.get(selIdx);
        double tc = sel.t;
        // every lane's later nodes move 1 s (fractional handles keep all
        // shapes; a foreign segment spanning tc stretches by 1 s)
        for (List<BezierBaker.Node> ln : app.lanes) {
            for (BezierBaker.Node m : ln) {
                if (m.t > tc + 1e-9) m.t += 1.0;
            }
        }
        // the selected node's outgoing transition moves to the new node;
        // selected -> new is a flat 1 s hold
        BezierBaker.Node hold = new BezierBaker.Node(tc + 1.0, sel.val);
        hold.outFrac = sel.outFrac;
        hold.outDv = sel.outDv;
        hold.inFrac = SINE_FRAC;
        hold.inDv = 0;
        sel.outFrac = SINE_FRAC;
        sel.outDv = 0;
        lane.add(selIdx + 1, hold);
        selSet.clear();
        selSet.add(selIdx);
        redraw();
        app.rebuildTrack();
    }

    void appendSecond() {
        pushUndoSnapshot();
        // only the ACTIVE lane gets a real node; the other lanes keep
        // holding their final value (dotted line) without extra nodes
        double newEnd = BezierBaker.rideEnd(app.lanes) + 1.0;
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node last = lane.get(lane.size() - 1);
        BezierBaker.Node np = new BezierBaker.Node(newEnd, last.val);
        // appended transitions default to the sinusoidal ease
        last.outFrac = SINE_FRAC; last.outDv = 0;
        np.inFrac = SINE_FRAC;    np.inDv = 0;
        lane.add(np);
        redraw();
        app.rebuildTrack();
    }

    // ---------- undo ----------

    private FvdDesigner.DocState snapshot() { return app.snapshotDoc(); }

    void pushUndoSnapshot() { pushUndo(snapshot()); }

    private void beginGesture() {
        pendingSnapshot = snapshot();
        gestureMoved = false;
        double t0 = viewStart(), t1 = viewEnd();
        dragWinT0 = t0;   // freeze the time window for the drag
        dragWinT1 = t1;
    }

    private void commitGestureSnapshot() {
        if (!gestureMoved && pendingSnapshot != null) {
            pushUndo(pendingSnapshot);
            gestureMoved = true;
        }
    }

    private void pushUndo(FvdDesigner.DocState snap) {
        undoStack.push(snap);
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        redoStack.clear();   // a fresh edit invalidates the redo branch
        app.markDirty();
    }

    void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(snapshot());        // current state becomes redoable
        applySnapshot(undoStack.pop());
    }

    /** Ctrl+Shift+Z, mirroring the menu-local sessions. Any new edit
     *  (pushUndo) clears the redo branch. */
    void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(snapshot());        // direct: must NOT clear redo
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        applySnapshot(redoStack.pop());
    }

    private void applySnapshot(FvdDesigner.DocState snap) {
        app.applyDocState(snap);
        if (selIdx >= active().size()) selIdx = -1;
        selSet.removeIf(i -> i >= active().size());
        app.markDirty();
        redraw();
        app.rebuildTrack();
    }

    /** New document: history starts over. */
    void clearHistory() {
        undoStack.clear();
        redoStack.clear();
        pendingSnapshot = null;
    }

    /** Zoomed time window back to auto-fit (new document). */
    void resetView() {
        viewT0 = 0;
        viewT1 = -1;
    }

    // ---------- smooth toggle ----------

    void toggleSmooth() {
        if (selIdx <= 0 || selIdx >= active().size() - 1) return;
        pushUndoSnapshot();
        BezierBaker.Node n = active().get(selIdx);
        n.smooth = !n.smooth;
        if (n.smooth) {
            // keep the IN handle, re-angle the OUT to match its slope
            double inDt = BezierBaker.effInDt(active().get(selIdx - 1), n);
            double outDt = BezierBaker.effOutDt(n, active().get(selIdx + 1));
            n.outDv = (n.inDv / inDt) * outDt;
        }
        redraw();
        app.rebuildTrack();
    }

    // ---------- coordinate mapping ----------

    private double maxT() { return Math.max(0.5, BezierBaker.rideEnd(app.lanes)); }
    private double domain() { return maxT() * 1.08; }
    private double plotHeight() { return canvas.getHeight() - MARGIN_TOP - MARGIN_BOTTOM; }
    private double plotWidth() { return canvas.getWidth() - MARGIN_LEFT - MARGIN_RIGHT; }

    private double viewStart() {
        return !Double.isNaN(dragWinT0) ? dragWinT0 : (viewT1 > 0 ? viewT0 : 0);
    }
    private double viewEnd() {
        return !Double.isNaN(dragWinT0) ? dragWinT1 : (viewT1 > 0 ? viewT1 : domain());
    }

    private double tToX(double t) {
        return MARGIN_LEFT + (t - viewStart()) / (viewEnd() - viewStart()) * plotWidth();
    }
    private double xToT(double x) {
        return viewStart()
            + FvdDesigner.clamp((x - MARGIN_LEFT) / plotWidth(), 0, 1) * (viewEnd() - viewStart());
    }
    private double valToY(int lane, double val) {
        // NO clamping: out-of-view geometry keeps its true position
        // (drawing is clipped to the plot; clamping here bent the
        // apparent angle of off-screen handles)
        double t = (val - curMin[lane]) / (curMax[lane] - curMin[lane]);
        return MARGIN_TOP + plotHeight() * (1 - t);
    }
    private double yToVal(int lane, double y) {
        double t = 1 - (y - MARGIN_TOP) / plotHeight();
        return curMin[lane] + FvdDesigner.clamp(t, 0, 1) * (curMax[lane] - curMin[lane]);
    }

    // ---------- hit testing (active lane only) ----------

    private int hitNode(double x, double y) {
        List<BezierBaker.Node> lane = active();
        for (int i = 0; i < lane.size(); i++) {
            double px = tToX(lane.get(i).t);
            double py = valToY(activeLane, lane.get(i).val);
            if (Math.hypot(x - px, y - py) <= NODE_HIT) return i;
        }
        return -1;
    }

    private int hitKnob(double x, double y) {
        double[] out = knobPos(selIdx, true);
        if (out != null && Math.hypot(x - out[0], y - out[1]) <= KNOB_HIT) return 1;
        double[] in = knobPos(selIdx, false);
        if (in != null && Math.hypot(x - in[0], y - in[1]) <= KNOB_HIT) return -1;
        return 0;
    }

    /** The hollow marker where an off-screen handle exits the plot. */
    private boolean hitEdgeMarker(double x, double y) {
        return (outEdgeMarker != null
                    && Math.hypot(x - outEdgeMarker[0], y - outEdgeMarker[1]) <= KNOB_HIT)
            || (inEdgeMarker != null
                    && Math.hypot(x - inEdgeMarker[0], y - inEdgeMarker[1]) <= KNOB_HIT);
    }

    /** Hit test the INACTIVE lanes: nodes first, then curve bodies.
     *  Returns {lane, nodeIdx} (nodeIdx -1 for a curve hit) or null. */
    private int[] hitOtherLane(double x, double y) {
        int bestLane = -1, bestNode = -1;
        double best = NODE_HIT + 3;   // slightly forgiving on dimmed app.lanes
        for (int L = 0; L < LANES; L++) {
            if (L == activeLane) continue;
            List<BezierBaker.Node> lane = app.lanes.get(L);
            for (int i = 0; i < lane.size(); i++) {
                double d = Math.hypot(x - tToX(lane.get(i).t),
                                      y - valToY(L, lane.get(i).val));
                if (d < best) { best = d; bestLane = L; bestNode = i; }
            }
        }
        if (bestLane >= 0) return new int[]{ bestLane, bestNode };

        // curve body: vertical distance to the lane's value at this time
        // (evalAt holds the end values outside the node range)
        double t = xToT(x);
        double bestCurve = 7;
        for (int L = 0; L < LANES; L++) {
            if (L == activeLane) continue;
            double d = Math.abs(y - valToY(L, BezierBaker.evalAt(app.lanes.get(L), t)));
            if (d < bestCurve) { bestCurve = d; bestLane = L; }
        }
        return bestLane >= 0 ? new int[]{ bestLane, -1 } : null;
    }

    private double[] knobPos(int idx, boolean out) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        if (out) {
            if (idx >= lane.size() - 1) return null;
            double dt = BezierBaker.effOutDt(n, lane.get(idx + 1));
            return new double[]{ tToX(n.t + dt), valToY(activeLane, n.val + n.outDv) };
        } else {
            if (idx <= 0) return null;
            double dt = BezierBaker.effInDt(lane.get(idx - 1), n);
            return new double[]{ tToX(n.t + dt), valToY(activeLane, n.val + n.inDv) };
        }
    }

    // ---------- editing ----------

    private void dragNodeTo(double x, double y, boolean snap, boolean shift) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(dragNodeIdx);

        // beyond a plot edge the node rides the edge while dragGrowTimer
        // smoothly extends the axis at a rate set by the overshoot
        double newT = xToT(x);
        double newVal = yToVal(activeLane, y);

        if (snap) {   // Ctrl enables snapping; free otherwise
            // time: quantize the TRANSITION from the previous node
            // (0.3 s, 0.8 s, ...), not the absolute position
            if (dragNodeIdx > 0) {
                double prevT = lane.get(dragNodeIdx - 1).t;
                newT = prevT + Math.max(MIN_GAP,
                    Math.round((newT - prevT) / snapT()) * snapT());
            }
            // value: global absolute grid (0.0 G, 3.6 G, 124 deg/s ...)
            newVal = Math.round(newVal / snapVal(activeLane)) * snapVal(activeLane);
        }

        // SHIFT constrains to an axis, anchored where the node STARTED
        // the gesture. The axis is picked immediately from whichever
        // direction dominates the TOTAL movement since the press — and
        // re-picked every event, so dragging further the other way
        // switches axes mid-gesture. An explicit Z/X lock overrides.
        if (shift) {
            double dx = x - gestureStartX, dy = y - gestureStartY;
            if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                shiftAxis = Math.abs(dx) >= Math.abs(dy) ? 2 : 1;
            }
        } else {
            shiftAxis = 0;
        }
        int lock = axisLock != 0 ? axisLock : shiftAxis;
        if (lock == 1) newT = gestureNodeT;    // vertical only
        if (lock == 2) newVal = gestureNodeV;  // horizontal only

        if (selSet.size() > 1 && selSet.contains(dragNodeIdx)) {
            moveGroupBy(newT - n.t, newVal - n.val);
        } else {
            moveNode(dragNodeIdx, newT, newVal);
        }
    }

    /** Move every selected node by the same deltas, clamped as a group
     *  against non-selected neighbors. Ripple is ignored for group
     *  moves (the group IS the ripple). Node 0 pins the group in time. */
    private void moveGroupBy(double dT, double dV) {
        List<BezierBaker.Node> lane = active();
        if (selSet.contains(0)) dT = 0;
        double lo = -1e18, hi = 1e18;
        for (int i : selSet) {
            if (i > 0 && !selSet.contains(i - 1)) {
                lo = Math.max(lo, lane.get(i - 1).t + MIN_GAP - lane.get(i).t);
            }
            if (i < lane.size() - 1 && !selSet.contains(i + 1)) {
                hi = Math.min(hi, lane.get(i + 1).t - MIN_GAP - lane.get(i).t);
            }
        }
        dT = FvdDesigner.clamp(dT, Math.min(lo, 0), Math.max(hi, 0));
        for (int i : selSet) {
            BezierBaker.Node m = lane.get(i);
            if (i > 0) m.t += dT;
            m.val += dV;
        }
        redraw();
        app.rebuildTrack();
    }

    /** Arrow keys: step the selection by the snap intervals; with the
     *  grid modifier the node LANDS on the grid instead. Auto-repeat
     *  (holding the key) gives continuous movement for free. */
    boolean nudgeSelected(int dtDir, int dvDir, boolean grid) {
        if (selIdx < 0) return false;
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(selIdx);
        double newT = n.t + dtDir * snapT();
        double newVal = n.val + dvDir * snapVal(activeLane);
        if (grid) {
            if (selIdx > 0) {
                double prevT = lane.get(selIdx - 1).t;
                newT = prevT + Math.max(MIN_GAP,
                    Math.round((newT - prevT) / snapT()) * snapT());
            }
            newVal = Math.round(newVal / snapVal(activeLane)) * snapVal(activeLane);
        }
        // one undo step per burst of keypresses, not per press
        long now = System.nanoTime();
        if (now - lastNudgeNanos > 800_000_000L) pushUndoSnapshot();
        lastNudgeNanos = now;
        if (selSet.size() > 1) moveGroupBy(newT - n.t, newVal - n.val);
        else moveNode(selIdx, newT, newVal);
        return true;
    }

    /** Clamp + apply a node position, honoring the ripple toggles.
     *  Shared by dragging and the node-editor dialog. */
    private void moveNode(int idx, double newT, double newVal) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        if (idx == 0) {
            newT = 0;
        } else {
            double lo = lane.get(idx - 1).t + MIN_GAP;
            // time-rippling pushes the later nodes along, so no upper clamp
            double hi = (app.rippleT || idx == lane.size() - 1)
                    ? Double.MAX_VALUE
                    : lane.get(idx + 1).t - MIN_GAP;
            newT = FvdDesigner.clamp(newT, lo, hi);
        }
        if (app.rippleT || app.rippleV) {
            // shift everything after the node by the same deltas on the
            // enabled axes: time keeps later transitions, value
            // translates the rest of the graph
            double dT = newT - n.t, dV = newVal - n.val;
            for (int j = idx + 1; j < lane.size(); j++) {
                if (app.rippleT) lane.get(j).t += dT;
                if (app.rippleV) lane.get(j).val += dV;
            }
        }
        n.t = newT;
        n.val = newVal;
        redraw();
        app.rebuildTrack();
    }

    /** inline (SHIFT): keep the handle on its current line, change length
     *  only, and never touch the partner handle. Default: fully free.
     *  snap (CTRL): quantize the length fraction to the ease presets. */
    private void dragHandleTo(double x, double y, boolean inline, boolean snap) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(dragHandleIdx);
        BezierBaker.Node prev = dragHandleIdx > 0 ? lane.get(dragHandleIdx - 1) : null;
        BezierBaker.Node next = dragHandleIdx < lane.size() - 1 ? lane.get(dragHandleIdx + 1) : null;
        // a visible knob guarantees the neighbor on its side exists
        double segLen = dragHandleIsOut ? next.t - n.t : n.t - prev.t;

        double oldFrac = dragHandleIsOut ? n.outFrac : n.inFrac;
        double oldDv   = dragHandleIsOut ? n.outDv : n.inDv;
        double newFrac, newDv;
        dragPresetName = null;

        if (inline) {
            // length-only along the current direction (pixel projection)
            double curDt = (dragHandleIsOut ? 1 : -1) * oldFrac * segLen;
            double slope = oldDv / curDt;   // value units per second

            double nx = tToX(n.t), ny = valToY(activeLane, n.val);
            double kx = tToX(n.t + curDt), ky = valToY(activeLane, n.val + oldDv);
            double dirX = kx - nx, dirY = ky - ny;
            double dirLen = Math.hypot(dirX, dirY);
            if (dirLen < 1e-6) return;
            dirX /= dirLen; dirY /= dirLen;

            double proj = Math.max(4, (x - nx) * dirX + (y - ny) * dirY);
            double newDt = xToT(nx + dirX * proj) - n.t;
            newFrac = FvdDesigner.clamp(Math.abs(newDt) / segLen, MIN_FRAC, BezierBaker.MAX_FRAC);
            if (snap) newFrac = snapFrac(newFrac);
            newDv = slope * ((dragHandleIsOut ? 1 : -1) * newFrac * segLen);
        } else {
            // free: fraction from mouse time, dv from mouse value
            double dt = xToT(x) - n.t;
            newFrac = FvdDesigner.clamp((dragHandleIsOut ? dt : -dt) / segLen,
                            MIN_FRAC, BezierBaker.MAX_FRAC);
            if (snap) newFrac = snapFrac(newFrac);
            newDv = yToVal(activeLane, y) - n.val;
        }

        if (dragHandleIsOut) { n.outFrac = newFrac; n.outDv = newDv; }
        else                 { n.inFrac = newFrac;  n.inDv = newDv; }

        // smooth node, free drag: the partner handle mirrors the change
        // proportionally on BOTH axes (its fraction and dv scale by the
        // same ratios as the dragged handle's), so joined handles grow,
        // shrink and re-angle together. Inline drags leave it alone.
        if (!inline && n.smooth) {
            if (dragHandleIsOut && prev != null) {
                n.inFrac = FvdDesigner.clamp(n.inFrac * (newFrac / oldFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                n.inDv = Math.abs(oldDv) > 1e-9
                    ? n.inDv * (newDv / oldDv)
                    // dragged handle was flat: ratio undefined, mirror by slope
                    : (newDv / (newFrac * segLen)) * (-n.inFrac * (n.t - prev.t));
            } else if (!dragHandleIsOut && next != null) {
                n.outFrac = FvdDesigner.clamp(n.outFrac * (newFrac / oldFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                n.outDv = Math.abs(oldDv) > 1e-9
                    ? n.outDv * (newDv / oldDv)
                    : (newDv / (-newFrac * segLen)) * (n.outFrac * (next.t - n.t));
            }
        }
        redraw();
        app.rebuildTrack();
    }

    /** Nearest ease-preset fraction; records its name for the readout. */
    private double snapFrac(double frac) {
        int best = 0;
        for (int i = 1; i < HANDLE_PRESETS.length; i++) {
            if (Math.abs(HANDLE_PRESETS[i] - frac)
                    < Math.abs(HANDLE_PRESETS[best] - frac)) best = i;
        }
        dragPresetName = HANDLE_PRESET_NAMES[best];
        return HANDLE_PRESETS[best];
    }

    /** Where on the ACTIVE curve a point hits: nearest point on the
     *  baked polyline in PIXEL space (true 2D distance, so steep or
     *  near-vertical sections hit just as well as flat ones). Returns
     *  the curve time, or NaN if farther than a few pixels or without
     *  room to insert. Drives the hover dot AND click-to-insert, so
     *  they can never disagree. */
    private double curveHitT(double x, double y) {
        List<BezierBaker.Node> lane = active();
        List<double[]> poly = BezierBaker.polyline(lane);
        double bestD2 = 9 * 9;
        double bestT = Double.NaN;
        double px0 = tToX(poly.get(0)[0]), py0 = valToY(activeLane, poly.get(0)[1]);
        // the dotted hold-final-value extension is insertable too
        double laneEnd = lane.get(lane.size() - 1).t;
        boolean extended = laneEnd < maxT() - 1e-9;
        int segs = poly.size() + (extended ? 1 : 0);
        for (int i = 1; i < segs; i++) {
            double t1 = i < poly.size() ? poly.get(i)[0] : maxT();
            double v1 = i < poly.size() ? poly.get(i)[1] : lane.get(lane.size() - 1).val;
            double t0 = i < poly.size() ? poly.get(i - 1)[0] : laneEnd;
            double px1 = tToX(t1), py1 = valToY(activeLane, v1);
            double segX = px1 - px0, segY = py1 - py0;
            double len2 = segX * segX + segY * segY;
            double u = len2 < 1e-9 ? 0
                     : FvdDesigner.clamp(((x - px0) * segX + (y - py0) * segY) / len2, 0, 1);
            double dx = x - (px0 + segX * u), dy = y - (py0 + segY * u);
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                bestT = t0 + (t1 - t0) * u;
            }
            px0 = px1; py0 = py1;
        }
        if (Double.isNaN(bestT)) return Double.NaN;
        // require just enough room on both sides to insert — the dot
        // must only appear where a click will actually succeed
        int i = 0;
        while (i < lane.size() && lane.get(i).t < bestT) i++;
        if (i == 0) return Double.NaN;
        if (bestT - lane.get(i - 1).t < INSERT_GAP) return Double.NaN;
        if (i < lane.size() && lane.get(i).t - bestT < INSERT_GAP) return Double.NaN;
        return bestT;
    }

    /** Insert a node ON the curve. Flat segments get sine handles at the
     *  same level; anything else is a de Casteljau split — tangent
     *  handles, lengths rewritten so the curve does not change at all. */
    private void insertNodeOnCurve(double t) {
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return;
        BezierBaker.Node n;
        if (i == lane.size()) {
            // on the dotted hold-final-value extension: append a real
            // node at the same level (flat sine transition)
            BezierBaker.Node last = lane.get(i - 1);
            if (t - last.t < INSERT_GAP || t > maxT() + 1e-9) return;
            pushUndoSnapshot();
            n = new BezierBaker.Node(t, last.val);
            last.outFrac = SINE_FRAC; last.outDv = 0;
            n.inFrac = SINE_FRAC;
            n.outFrac = SINE_FRAC;
            lane.add(n);
            selIdx = i;
            selSet.clear();
            selSet.add(i);
            axisLock = 0;
            redraw();
            app.rebuildTrack();
            return;
        }
        BezierBaker.Node a = lane.get(i - 1), b = lane.get(i);
        if (t - a.t < INSERT_GAP || b.t - t < INSERT_GAP) return;
        pushUndoSnapshot();
        boolean flat = Math.abs(a.outDv) < 1e-9 && Math.abs(b.inDv) < 1e-9
                    && Math.abs(a.val - b.val) < 1e-9;
        if (flat) {
            n = new BezierBaker.Node(t, a.val);
            n.outFrac = SINE_FRAC;
            n.inFrac = SINE_FRAC;   // dv 0: level, no angle
        } else {
            n = BezierBaker.splitSegment(a, b, t);
        }
        n.straightOut = a.straightOut;   // a locked segment stays locked
        lane.add(i, n);                  // through a split (both halves)
        selIdx = i;
        selSet.clear();
        selSet.add(i);
        axisLock = 0;
        redraw();
        app.rebuildTrack();
    }

    /** Removing an interior node bridges the gap naturally: the two
     *  neighbors keep their positions and handle settings (fractions
     *  simply apply to the merged segment). Deleting the LAST node
     *  shortens the ride; the first node (t=0 anchor) is undeletable. */
    private void deleteNode(int index) {
        List<BezierBaker.Node> lane = active();
        if (index == 0 || lane.size() <= 2) return;
        pushUndoSnapshot();
        lane.remove(index);
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        redraw();
        app.rebuildTrack();
    }

    void deleteSelected() {
        if (selIdx < 0) return;
        if (selSet.isEmpty()) selSet.add(selIdx);
        List<BezierBaker.Node> lane = active();
        List<Integer> idxs = new ArrayList<>(selSet);
        idxs.sort(java.util.Comparator.reverseOrder());
        boolean pushed = false;
        for (int i : idxs) {
            if (i == 0 || lane.size() <= 2) continue;
            if (!pushed) { pushUndoSnapshot(); pushed = true; }
            lane.remove(i);
        }
        if (!pushed) return;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        redraw();
        app.rebuildTrack();
    }

    /** H: make the selected node's handles horizontal (dv = 0) — EVERY
     *  selected node when multi-selected. While a handle is being
     *  dragged on a BROKEN node, only that handle. */
    void flattenHandles() {
        if (selIdx < 0) return;
        List<BezierBaker.Node> lane = active();
        pushUndoSnapshot();
        if (selSet.size() > 1) {
            for (int i : selSet) {
                BezierBaker.Node n = lane.get(i);
                n.outDv = 0;
                n.inDv = 0;
            }
        } else {
            BezierBaker.Node n = lane.get(selIdx);
            if (dragHandleIdx >= 0 && !n.smooth) {
                if (dragHandleIsOut) n.outDv = 0; else n.inDv = 0;
            } else {
                n.outDv = 0;
                n.inDv = 0;
            }
        }
        redraw();
        app.rebuildTrack();
    }

    /** Shift+H: level the whole selection ONTO the main highlighted
     *  node — every selected node takes selIdx's value with flat
     *  handles, so the selected run becomes one horizontal line
     *  (segments between selected nodes go exactly flat; transitions
     *  to unselected neighbors keep their handles). The main node is
     *  the last one clicked, or the earliest in a box selection.
     *  With fewer than two selected this is plain H. */
    void levelSelected() {
        if (selIdx < 0) return;
        if (selSet.size() < 2) {
            flattenHandles();
            return;
        }
        List<BezierBaker.Node> lane = active();
        double ref = lane.get(selIdx).val;
        pushUndoSnapshot();
        for (int i : selSet) {
            BezierBaker.Node n = lane.get(i);
            n.val = ref;
            n.outDv = 0;
            n.inDv = 0;
        }
        redraw();
        app.rebuildTrack();
    }

    /** Z/X: constrain node drags to one axis; same key again unlocks. */
    void toggleAxisLock(boolean vertical) {
        if (selIdx < 0) return;
        int target = vertical ? 1 : 2;
        axisLock = (axisLock == target) ? 0 : target;
        redraw();
    }

    /** F with exactly two ADJACENT nodes selected: put their facing
     *  handles on the chord between them — the segment becomes a
     *  straight line. Smooth nodes re-angle their far handles to keep
     *  C1 continuity. */
    void straightenSelected() {
        if (selSet.size() != 2) return;
        List<Integer> idx = new ArrayList<>(selSet);
        java.util.Collections.sort(idx);
        int i = idx.get(0), j = idx.get(1);
        if (j != i + 1) return;
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node a = lane.get(i), b = lane.get(j);
        pushUndoSnapshot();
        double slope = (b.val - a.val) / (b.t - a.t);
        a.outDv = slope * BezierBaker.effOutDt(a, b);
        b.inDv  = slope * BezierBaker.effInDt(a, b);
        if (a.smooth && i > 0) {
            a.inDv = slope * BezierBaker.effInDt(lane.get(i - 1), a);
        }
        if (b.smooth && j < lane.size() - 1) {
            b.outDv = slope * BezierBaker.effOutDt(b, lane.get(j + 1));
        }
        redraw();
        app.rebuildTrack();
    }

    /** Shift+F: PERSISTENT straightness. If the selection touches any
     *  straight-locked segment, unlock those (selecting either endpoint
     *  is enough); otherwise, with exactly two ADJACENT nodes selected,
     *  lock their segment — enforceStraightLocks() then re-chords it
     *  after every edit, so dragging an endpoint keeps the line
     *  straight and dragging a facing knob only slides along it. */
    void toggleStraightLock() {
        List<BezierBaker.Node> lane = active();
        Set<Integer> sel = selSet.isEmpty() && selIdx >= 0
            ? java.util.Set.of(selIdx) : selSet;
        Set<Integer> locked = new HashSet<>();
        for (int i : sel) {
            if (i < lane.size() - 1 && lane.get(i).straightOut) locked.add(i);
            if (i > 0 && lane.get(i - 1).straightOut) locked.add(i - 1);
        }
        if (!locked.isEmpty()) {
            pushUndoSnapshot();
            for (int i : locked) lane.get(i).straightOut = false;
            redraw();
            app.rebuildTrack();
            return;
        }
        if (sel.size() != 2) return;
        List<Integer> idx = new ArrayList<>(sel);
        java.util.Collections.sort(idx);
        if (idx.get(1) != idx.get(0) + 1) return;
        pushUndoSnapshot();
        lane.get(idx.get(0)).straightOut = true;
        redraw();          // enforcement straightens it right here
        app.rebuildTrack();
    }

    /** Re-chord every straight-locked segment: facing handles onto the
     *  line between the endpoints (smooth endpoints keep C1 by
     *  re-angling their far handle — unless that side is itself locked,
     *  which owns its own handles). Runs at the top of every redraw,
     *  which follows every lane mutation; idempotent and cheap. */
    private void enforceStraightLocks() {
        for (List<BezierBaker.Node> ln : app.lanes) {
            for (int i = 0; i < ln.size() - 1; i++) {
                BezierBaker.Node a = ln.get(i);
                if (!a.straightOut) continue;
                BezierBaker.Node b = ln.get(i + 1);
                if (b.t - a.t < 1e-9) continue;   // malformed file guard
                double slope = (b.val - a.val) / (b.t - a.t);
                a.outDv = slope * BezierBaker.effOutDt(a, b);
                b.inDv  = slope * BezierBaker.effInDt(a, b);
                if (a.smooth && i > 0 && !ln.get(i - 1).straightOut) {
                    a.inDv = slope * BezierBaker.effInDt(ln.get(i - 1), a);
                }
                if (b.smooth && i + 1 < ln.size() - 1 && !b.straightOut) {
                    b.outDv = slope * BezierBaker.effOutDt(b, ln.get(i + 2));
                }
            }
        }
    }

    /** Double-click on a node: type its settings directly. Transition
     *  time/magnitude are relative to the PREVIOUS node (node 0 edits
     *  its global value); handles are segment fractions. 3 decimals. */
    private void showNodeEditor(int idx) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        BezierBaker.Node prev = idx > 0 ? lane.get(idx - 1) : null;
        boolean hasOut = idx < lane.size() - 1;

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Node " + idx + " — " + LANE_NAMES[activeLane]);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));

        TextField dtField = null, valField, v0Field = null;
        TextField inField = null, outField = null;
        int row = 0;
        if (prev != null) {
            dtField = new TextField(String.format(Locale.US, "%.3f", n.t - prev.t));
            grid.addRow(row++, new Label("Transition time (s)"), dtField);
        } else {
            // node 0: the ride's start speed lives here too (same value
            // as the Coaster-menu spinner, whichever lane you opened)
            v0Field = new TextField(String.format(Locale.US, "%.3f", app.coaster.v0));
            grid.addRow(row++, new Label("Start speed (m/s)"), v0Field);
        }
        // vertical axis is the GLOBAL value, not a delta
        valField = new TextField(String.format(Locale.US, "%.3f", n.val));
        grid.addRow(row++, new Label("Value (" + LANE_UNITS[activeLane].trim() + ")"), valField);
        if (prev != null) {
            inField = new TextField(String.format(Locale.US, "%.3f", n.inFrac));
            grid.addRow(row++, new Label("In handle"), inField);
        }
        if (hasOut) {
            outField = new TextField(String.format(Locale.US, "%.3f", n.outFrac));
            grid.addRow(row++, new Label("Out handle"), outField);
        }
        for (javafx.scene.Node c : grid.getChildren()) {
            if (c instanceof TextField tf) tf.setPrefColumnCount(7);
        }

        Button ok = new Button("Apply");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);

        final TextField fDt = dtField, fVal = valField, fV0 = v0Field;
        final TextField fIn = inField, fOut = outField;
        ok.setOnAction(ev -> {
            pushUndoSnapshot();
            // typed fractions SLIDE the knob along its current line (S
            // semantics): dv scales with the length, angle preserved
            if (fIn != null) {
                double nf = FvdDesigner.clamp(parseOr(fIn, n.inFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                if (n.inFrac > 1e-9) n.inDv *= nf / n.inFrac;
                n.inFrac = nf;
            }
            if (fOut != null) {
                double nf = FvdDesigner.clamp(parseOr(fOut, n.outFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                if (n.outFrac > 1e-9) n.outDv *= nf / n.outFrac;
                n.outFrac = nf;
            }
            if (fV0 != null) {
                // through the spinner so the menu shows it and the track
                // rebuilds (start speed is per-coaster, not in lane undo)
                app.startSpeedSpinner.getValueFactory().setValue(
                    FvdDesigner.clamp(parseOr(fV0, app.coaster.v0), 1, 100));
            }
            double val = parseOr(fVal, n.val);
            if (prev != null) {
                double dt = Math.max(MIN_GAP, parseOr(fDt, n.t - prev.t));
                moveNode(idx, prev.t + dt, val);   // honors ripple toggles
            } else {
                moveNode(idx, n.t, val);
            }
            dlg.close();
        });
        cancel.setOnAction(ev -> dlg.close());

        dlg.setScene(new Scene(grid));
        dlg.show();
    }

    /** Auto-fit each lane's value axis to the curve within the current
     *  time window: at least the default range, expanded and rounded
     *  outward to a nice step when the data pokes beyond it. Frozen
     *  during drags so the scale doesn't shift under the cursor. */
    private void updateScale() {
        if (dragNodeIdx >= 0 || dragHandleIdx >= 0) return;
        double t0 = viewStart(), t1 = viewEnd();
        for (int L = 0; L < LANES; L++) {
            double lo = DEF_MIN[L], hi = DEF_MAX[L];
            for (double[] p : BezierBaker.polyline(app.lanes.get(L))) {
                if (p[0] < t0 || p[0] > t1) continue;
                lo = Math.min(lo, p[1]);
                hi = Math.max(hi, p[1]);
            }
            double eA = BezierBaker.evalAt(app.lanes.get(L), t0);
            double eB = BezierBaker.evalAt(app.lanes.get(L), t1);
            lo = Math.min(lo, Math.min(eA, eB));
            hi = Math.max(hi, Math.max(eA, eB));
            // expanded-handle view: include the selected node's knobs
            if (handleViewExpand && L == activeLane
                    && selIdx >= 0 && selIdx < app.lanes.get(L).size()) {
                BezierBaker.Node sn = app.lanes.get(L).get(selIdx);
                lo = Math.min(lo, Math.min(sn.val + sn.outDv, sn.val + sn.inDv));
                hi = Math.max(hi, Math.max(sn.val + sn.outDv, sn.val + sn.inDv));
            }
            // resulting overlay rides the roll scale — keep it in view
            if (L == 2 && app.resultRates != null
                    && (app.settings.resPitch() || app.settings.resYaw())) {
                for (double[] row : app.resultRates) {
                    if (row[0] < t0 || row[0] > t1) continue;
                    if (app.settings.resPitch()) {
                        lo = Math.min(lo, row[1]);
                        hi = Math.max(hi, row[1]);
                    }
                    if (app.settings.resYaw()) {
                        lo = Math.min(lo, row[2]);
                        hi = Math.max(hi, row[2]);
                    }
                }
            }
            if (lo < DEF_MIN[L] || hi > DEF_MAX[L]) {
                double pad = (hi - lo) * 0.05;   // "slightly beyond"
                double step = niceStep((hi - lo) / 6);
                curMin[L] = Math.floor((lo - pad) / step) * step;
                curMax[L] = Math.ceil((hi + pad) / step) * step;
            } else {
                curMin[L] = DEF_MIN[L];
                curMax[L] = DEF_MAX[L];
            }
        }
    }

    private static String fmtVal(double v) {
        return v == Math.rint(v) || Math.abs(v) >= 10
            ? String.format("%.0f", v) : String.format("%.1f", v);
    }

    /** Round a raw interval up to a "nice" 1-2-5 step (0.1, 0.2, 0.5, 1,
     *  2, 5, 10, ...) for grid lines and tick labels. */
    private static double niceStep(double raw) {
        double pow = Math.pow(10, Math.floor(Math.log10(raw)));
        double m = raw / pow;
        return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * pow;
    }

    private double parseOr(TextField f, double fallback) {
        try {
            return Double.parseDouble(f.getText().trim());
        } catch (NumberFormatException ex) {
            return fallback;   // bad input: keep the old value
        }
    }

    // ---------- drawing ----------

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0 || plotWidth() <= 0 || plotHeight() <= 0) return;
        enforceStraightLocks();   // before updateScale sees the handles
        updateScale();
        refreshMarkers();         // 3D markers follow lane + selection
        gc.setGlobalAlpha(1.0);
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.rgb(20, 22, 28));
        gc.fillRect(0, 0, w, h);

        gc.setFill(Color.rgb(28, 31, 40));
        gc.fillRect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - MARGIN_RIGHT, plotHeight());

        // adaptive grid: nice 1-2-5 steps, density follows zoom/size
        double vt0 = viewStart(), vt1 = viewEnd();
        double gridTStep = Math.max(0.01, niceStep((vt1 - vt0) * 60 / plotWidth()));
        gc.setStroke(Color.rgb(42, 47, 60));
        gc.setLineWidth(1);
        for (double t = Math.ceil(vt0 / gridTStep) * gridTStep; t <= vt1 + 1e-9; t += gridTStep) {
            double gx = tToX(t);
            gc.strokeLine(gx, MARGIN_TOP, gx, MARGIN_TOP + plotHeight());
        }
        double vRange = curMax[activeLane] - curMin[activeLane];
        double gridVStep = niceStep(vRange * 30 / plotHeight());
        for (double v = Math.ceil(curMin[activeLane] / gridVStep) * gridVStep;
                v <= curMax[activeLane] + 1e-9; v += gridVStep) {
            double gy = valToY(activeLane, v);
            gc.strokeLine(MARGIN_LEFT, gy, w - MARGIN_RIGHT, gy);
        }
        // small faded value labels on every horizontal grid line
        gc.setFill(Color.rgb(118, 124, 140));
        gc.setFont(Font.font(9));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.CENTER);
        for (double v = Math.ceil(curMin[activeLane] / gridVStep) * gridVStep;
                v <= curMax[activeLane] + 1e-9; v += gridVStep) {
            gc.fillText(fmtVal(v), MARGIN_LEFT - 4, valToY(activeLane, v));
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);

        // clip everything geometric to the plot rect: off-view curves and
        // handles keep their TRUE positions (valToY no longer clamps)
        gc.save();
        gc.beginPath();
        gc.rect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - MARGIN_RIGHT, plotHeight());
        gc.clip();

        double zy = valToY(activeLane, 0);
        gc.setStroke(Color.rgb(70, 75, 90));
        gc.setLineWidth(1);
        gc.strokeLine(MARGIN_LEFT, zy, w - MARGIN_RIGHT, zy);
        if (activeLane == 0) {
            double oy = valToY(0, 1);
            gc.setStroke(Color.rgb(60, 90, 65));
            gc.strokeLine(MARGIN_LEFT, oy, w - MARGIN_RIGHT, oy);
        }

        for (int lane = 0; lane < LANES; lane++) {
            if (lane == activeLane) continue;
            gc.setGlobalAlpha(INACTIVE_ALPHA);
            strokeLaneCurve(gc, lane);
        }
        gc.setGlobalAlpha(1.0);
        strokeLaneCurve(gc, activeLane);

        // read-only RESULTING curves (toolbar "Resulting" dropdown):
        // what the geometry actually does, derived from the integrated
        // samples — dashed thin curves on the ROLL lane's value scale,
        // colored like the lane each answers to (pitch~Gv, yaw~Gl).
        // Roll is not offered: resulting roll = the authored roll lane.
        if (app.resultRates != null && (app.settings.resPitch() || app.settings.resYaw())) {
            gc.setLineWidth(1);
            gc.setLineDashes(4, 4);
            gc.setGlobalAlpha(0.7);
            for (int comp = 1; comp <= 2; comp++) {
                if (comp == 1 ? !app.settings.resPitch() : !app.settings.resYaw()) continue;
                gc.setStroke(FvdDesigner.LANE_COLORS[comp - 1]);
                gc.beginPath();
                boolean started = false;
                for (double[] row : app.resultRates) {
                    double rx = tToX(row[0]);
                    double ry = valToY(2, row[comp]);
                    if (!started) { gc.moveTo(rx, ry); started = true; }
                    else gc.lineTo(rx, ry);
                }
                gc.stroke();
            }
            gc.setLineDashes((double[]) null);
            gc.setGlobalAlpha(1.0);
            gc.setLineWidth(2);
        }

        List<BezierBaker.Node> lane = active();
        for (int i = 0; i < lane.size(); i++) {
            double px = tToX(lane.get(i).t);
            double py = valToY(activeLane, lane.get(i).val);
            boolean isSel = (i == selIdx);
            boolean isDragged = (i == dragNodeIdx);

            if (isSel || selSet.contains(i)) {
                // primary = solid white ring; other members = fainter
                gc.setStroke(isSel ? Color.WHITE : Color.rgb(255, 255, 255, 0.5));
                gc.setLineWidth(isSel ? 1.5 : 1.2);
                gc.strokeOval(px - NODE_RADIUS - 3, py - NODE_RADIUS - 3,
                              (NODE_RADIUS + 3) * 2, (NODE_RADIUS + 3) * 2);
            }
            gc.setFill(isDragged ? Color.WHITE : FvdDesigner.LANE_COLORS[activeLane]);
            gc.fillOval(px - NODE_RADIUS, py - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);
            if (isDragged) {
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(11));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.TOP);
                // transition time from the previous node; global value +
                // unit. Position clamped INTO the plot so the readout
                // stays readable while dragging past the bounds.
                BezierBaker.Node nd = lane.get(i);
                String txt = i == 0
                    ? String.format("t=%.2fs  %.2f%s", nd.t, nd.val, LANE_UNITS[activeLane])
                    : String.format("Δt=%.2fs  %.2f%s",
                        nd.t - lane.get(i - 1).t, nd.val, LANE_UNITS[activeLane]);
                double tw = txt.length() * 6.5;   // approx width at 11 px
                double tx = FvdDesigner.clamp(px + 8, MARGIN_LEFT + 4, w - MARGIN_RIGHT - tw - 4);
                double ty = FvdDesigner.clamp(py - 20, MARGIN_TOP + 4, MARGIN_TOP + plotHeight() - 16);
                gc.fillText(txt, tx, ty);
            }
        }

        // insert preview: translucent white dot riding the curve, with
        // the lane's value at that point alongside
        if (!Double.isNaN(hoverT)) {
            double hv = BezierBaker.evalAt(lane, hoverT);
            double hx = tToX(hoverT);
            double hy = valToY(activeLane, hv);
            gc.setGlobalAlpha(0.5);
            gc.setFill(Color.WHITE);
            gc.fillOval(hx - 4, hy - 4, 8, 8);
            gc.setGlobalAlpha(0.75);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            String hoverTxt;
            if (hoverSlope) {
                // Shift: the ANALYTIC slope here replaces the value
                // (differencing the baked polyline stair-stepped)
                double slope = BezierBaker.slopeAt(lane, hoverT);
                hoverTxt = String.format("%.2f %s/s", slope, LANE_UNITS[activeLane].trim());
            } else {
                hoverTxt = String.format("%.2f%s", hv, LANE_UNITS[activeLane]);
            }
            gc.fillText(hoverTxt, hx + 8, hy - 6);
            gc.setGlobalAlpha(1.0);
        }

        // axis-lock guide: the line the node rides along. Explicit Z/X
        // overrides the transient slide axis. Color = what the drag
        // CHANGES: vertical movement changes the VALUE (lane color);
        // horizontal movement changes TIME (yellow).
        int lock = axisLock != 0 ? axisLock
                 : (dragNodeIdx >= 0 ? shiftAxis : 0);
        if (selIdx >= 0 && lock != 0) {
            double lx = tToX(lane.get(selIdx).t);
            double ly = valToY(activeLane, lane.get(selIdx).val);
            gc.setGlobalAlpha(0.5);
            gc.setLineWidth(1.5);
            if (lock == 1) {
                gc.setStroke(FvdDesigner.LANE_COLORS[activeLane]);
                gc.strokeLine(lx, MARGIN_TOP, lx, MARGIN_TOP + plotHeight());
            } else {
                gc.setStroke(FvdDesigner.TIME_COLOR);
                gc.strokeLine(MARGIN_LEFT, ly, w - MARGIN_RIGHT, ly);
            }
            gc.setGlobalAlpha(1.0);
        }

        // POV camera position on the track (toggle in Edit > Options)
        if (app.povActive && app.settings.povMarker()) {
            double px2 = tToX(app.povTime);
            if (px2 >= MARGIN_LEFT - 1 && px2 <= w - MARGIN_RIGHT + 1) {
                gc.setGlobalAlpha(0.4);
                gc.setStroke(FvdDesigner.TIME_COLOR);   // yellow = time
                gc.setLineWidth(2);
                gc.strokeLine(px2, MARGIN_TOP, px2, MARGIN_TOP + plotHeight());
                gc.setGlobalAlpha(1.0);
            }
        }

        if (selIdx >= 0) drawHandles(gc, selIdx);

        // box-select rubber band
        if (!Double.isNaN(boxX0)) {
            double bx = Math.min(boxX0, boxX1), by = Math.min(boxY0, boxY1);
            double bw = Math.abs(boxX1 - boxX0), bh = Math.abs(boxY1 - boxY0);
            gc.setFill(Color.rgb(255, 255, 255, 0.07));
            gc.fillRect(bx, by, bw, bh);
            gc.setStroke(Color.rgb(255, 255, 255, 0.55));
            gc.setLineWidth(1);
            gc.strokeRect(bx, by, bw, bh);
        }

        gc.restore();   // end plot clip

        gc.setGlobalAlpha(1.0);
        gc.setFont(Font.font(11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(FvdDesigner.LANE_COLORS[activeLane]);
        gc.setTextBaseline(VPos.TOP);
        gc.fillText(fmtVal(curMax[activeLane]), 6, MARGIN_TOP);
        gc.setTextBaseline(VPos.BOTTOM);
        gc.fillText(fmtVal(curMin[activeLane]), 6, MARGIN_TOP + plotHeight());

        // y-axis label, rotated along the left edge (tracks the edit mode)
        gc.save();
        gc.translate(14, MARGIN_TOP + plotHeight() / 2);
        gc.rotate(-90);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(LANE_NAMES[activeLane], 0, 0);
        gc.restore();

        gc.setFill(Color.rgb(130, 135, 150));
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM);
        String tickFmt = gridTStep < 0.1 ? "%.2f" : gridTStep < 1 ? "%.1f" : "%.0f";
        for (double t = Math.ceil(vt0 / gridTStep) * gridTStep; t <= vt1 + 1e-9; t += gridTStep) {
            gc.fillText(String.format(tickFmt, t), tToX(t), h - 16);
        }
        gc.setFont(Font.font(11));
        gc.setFill(FvdDesigner.TIME_COLOR);   // yellow = time, everywhere in the UI
        gc.fillText("Time (s)", MARGIN_LEFT + plotWidth() / 2, h - 2);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    private void strokeLaneCurve(GraphicsContext gc, int laneIdx) {
        List<BezierBaker.Node> lane = app.lanes.get(laneIdx);
        gc.setStroke(FvdDesigner.LANE_COLORS[laneIdx]);
        gc.setLineWidth(2);
        gc.beginPath();
        gc.moveTo(tToX(lane.get(0).t), valToY(laneIdx, lane.get(0).val));
        for (int i = 0; i < lane.size() - 1; i++) {
            BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
            if (a.straightOut) {   // drawn separately as twin lines
                gc.moveTo(tToX(b.t), valToY(laneIdx, b.val));
                continue;
            }
            double c1x = tToX(a.t + BezierBaker.effOutDt(a, b));
            double c1y = valToY(laneIdx, a.val + a.outDv);
            double c2x = tToX(b.t + BezierBaker.effInDt(a, b));
            double c2y = valToY(laneIdx, b.val + b.inDv);
            gc.bezierCurveTo(c1x, c1y, c2x, c2y, tToX(b.t), valToY(laneIdx, b.val));
        }
        gc.stroke();

        // straight-locked segments: TWO skinny parallel lines offset
        // along the chord normal — the "this is pinned" look
        gc.setLineWidth(1);
        for (int i = 0; i < lane.size() - 1; i++) {
            if (!lane.get(i).straightOut) continue;
            BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
            double ax = tToX(a.t), ay = valToY(laneIdx, a.val);
            double bx = tToX(b.t), by = valToY(laneIdx, b.val);
            double dx = bx - ax, dy = by - ay;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) continue;
            double ox = -dy / len * 1.6, oy = dx / len * 1.6;
            gc.strokeLine(ax + ox, ay + oy, bx + ox, by + oy);
            gc.strokeLine(ax - ox, ay - oy, bx - ox, by - oy);
        }
        gc.setLineWidth(2);

        // a lane that ends early holds its final value to the ride end:
        // drawn DOTTED to show it's implied, not authored (clickable to
        // insert a real node on it)
        double end = maxT();
        BezierBaker.Node last = lane.get(lane.size() - 1);
        if (last.t < end - 1e-9) {
            double ly = valToY(laneIdx, last.val);
            gc.setLineDashes(3, 7);
            gc.strokeLine(tToX(last.t), ly, tToX(end), ly);
            gc.setLineDashes((double[]) null);
        }
    }

    private void drawHandles(GraphicsContext gc, int idx) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        double nx = tToX(n.t);
        double ny = valToY(activeLane, n.val);
        outEdgeMarker = null;
        inEdgeMarker = null;

        for (boolean out : new boolean[]{ true, false }) {
            double[] k = knobPos(idx, out);
            if (k == null) continue;
            boolean dragging = (idx == dragHandleIdx && out == dragHandleIsOut);

            // S-slide rail: the line the knob is locked to (dashed,
            // extended across the plot — the clip trims it)
            if (dragging && app.slideKey) {
                double ddx = k[0] - nx, ddy = k[1] - ny;
                double len = Math.hypot(ddx, ddy);
                if (len > 1e-6) {
                    ddx /= len; ddy /= len;
                    gc.setGlobalAlpha(0.5);
                    gc.setStroke(Color.WHITE);
                    gc.setLineWidth(1);
                    gc.setLineDashes(4, 6);
                    gc.strokeLine(nx - ddx * 4000, ny - ddy * 4000,
                                  nx + ddx * 4000, ny + ddy * 4000);
                    gc.setLineDashes((double[]) null);
                }
            }
            boolean offscreen = k[0] < MARGIN_LEFT || k[0] > canvas.getWidth() - MARGIN_RIGHT
                             || k[1] < MARGIN_TOP || k[1] > MARGIN_TOP + plotHeight();

            gc.setGlobalAlpha(dragging ? 1.0 : HANDLE_ALPHA);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1);
            if (offscreen) {
                // dashed stub to the plot edge + hollow marker: the true
                // handle continues beyond; click the marker to expand
                double[] edge = clipToPlot(nx, ny, k[0], k[1]);
                gc.setLineDashes(3, 5);
                gc.strokeLine(nx, ny, edge[0], edge[1]);
                gc.setLineDashes((double[]) null);
                gc.strokeOval(edge[0] - KNOB_RADIUS, edge[1] - KNOB_RADIUS,
                              KNOB_RADIUS * 2, KNOB_RADIUS * 2);
                if (out) outEdgeMarker = edge; else inEdgeMarker = edge;
            } else {
                gc.strokeLine(nx, ny, k[0], k[1]);
                gc.setFill(Color.WHITE);
                if (n.smooth) {
                    gc.fillOval(k[0] - KNOB_RADIUS, k[1] - KNOB_RADIUS, KNOB_RADIUS * 2, KNOB_RADIUS * 2);
                } else {
                    gc.fillRect(k[0] - KNOB_RADIUS, k[1] - KNOB_RADIUS, KNOB_RADIUS * 2, KNOB_RADIUS * 2);
                }
            }
            if (dragging) {
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(10));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.TOP);
                // lengths read as FRACTIONS of the segment, matching the
                // node dialog (was percentages; what they mean lives in
                // the Help window, not here)
                gc.fillText(String.format("%s %.2f%s  dv %.2f",
                    out ? "out" : "in",
                    out ? n.outFrac : n.inFrac,
                    dragPresetName != null ? " " + dragPresetName : "",
                    out ? n.outDv : n.inDv),
                    FvdDesigner.clamp(k[0] + 8, MARGIN_LEFT, canvas.getWidth() - 140),
                    FvdDesigner.clamp(k[1] - 16, MARGIN_TOP, MARGIN_TOP + plotHeight() - 14));
            }
        }
        gc.setGlobalAlpha(1.0);
    }

    /** Where the segment (x0,y0)->(x1,y1) exits the plot rectangle. */
    private double[] clipToPlot(double x0, double y0, double x1, double y1) {
        double t = 1;
        double L = MARGIN_LEFT, R = canvas.getWidth() - MARGIN_RIGHT;
        double T = MARGIN_TOP, B = MARGIN_TOP + plotHeight();
        if (x1 < L) t = Math.min(t, (L - x0) / (x1 - x0));
        if (x1 > R) t = Math.min(t, (R - x0) / (x1 - x0));
        if (y1 < T) t = Math.min(t, (T - y0) / (y1 - y0));
        if (y1 > B) t = Math.min(t, (B - y0) / (y1 - y0));
        return new double[]{ x0 + (x1 - x0) * t, y0 + (y1 - y0) * t };
    }
}
