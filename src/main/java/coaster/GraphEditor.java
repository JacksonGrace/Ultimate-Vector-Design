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

    // ---------- sections (Phase 1 final, D55) ----------
    // the piece's sections render as strips along one shared time axis;
    // the ACTIVE one is editable, greyed ones activate on click, and a
    // '+' affordance past the ride end appends a new section. All node
    // math stays SECTION-LOCAL: ltToX/xToLt add/remove the section's
    // global start, so with one section (tOff = 0) everything below is
    // bit-identical to the pre-section editor.
    private int activeSection = 0;
    private double tOff = 0;                         // active section's global start
    private double addBtnX = -999, addBtnY = -999;   // '+' hit centre (px)

    private List<LaneSection> sections() {
        List<LaneSection> out = new ArrayList<>();
        for (Section s : app.coaster.pieces.get(0).sections) out.add((LaneSection) s);
        return out;
    }

    // ---------- section MODE (Phase 2: force vs geo lanes) ----------
    // one editor edits both lane-based modes; only the lane SEMANTICS
    // switch — names, units, default scales, snap steps, lane buttons

    /** Is the ACTIVE section a geo (body-rate) section? */
    private boolean activeGeo() {
        List<LaneSection> secs = sections();
        return activeSection < secs.size() && secs.get(activeSection) instanceof GeoSection;
    }

    private String laneName(int lane) {
        return activeGeo() ? GEO_LANE_NAMES[lane] : LANE_NAMES[lane];
    }

    private String laneUnit(int lane) {
        return activeGeo() ? " °/s" : LANE_UNITS[lane];
    }

    /** The active mode's lane accent color (geo: teal/yellow/orange) —
     *  curves, nodes, buttons, guides and 3D markers all follow it. */
    Color laneColor(int lane) {
        return (activeGeo() ? FvdDesigner.GEO_LANE_COLORS
                            : FvdDesigner.LANE_COLORS)[lane];
    }

    private static double[] modeDefMin(boolean geo) { return geo ? GEO_DEF_MIN : DEF_MIN; }
    private static double[] modeDefMax(boolean geo) { return geo ? GEO_DEF_MAX : DEF_MAX; }

    private double defMin(int lane) { return modeDefMin(activeGeo())[lane]; }
    private double defMax(int lane) { return modeDefMax(activeGeo())[lane]; }

    /** Lane buttons show the active mode's lane names + colors. */
    private void syncLaneButtons() {
        app.setLaneButtonMode(activeGeo());
    }

    int activeSection() { return activeSection; }

    double activeSectionStart() { refreshOffset(); return tOff; }

    private double sectionDur(int k) {
        return BezierBaker.rideEnd(sections().get(k).lanes);
    }

    /** Duration of the whole piece = the global ride end. */
    private double pieceDur() {
        double d = 0;
        for (int k = 0; k < sections().size(); k++) d += sectionDur(k);
        return d;
    }

    private void refreshOffset() {
        double o = 0;
        List<LaneSection> secs = sections();
        for (int k = 0; k < activeSection && k < secs.size(); k++) {
            o += BezierBaker.rideEnd(secs.get(k).lanes);
        }
        tOff = o;
    }

    /** End of the ACTIVE section in its local time — its own ride end
     *  (the old single-section maxT() semantics, now per section). */
    private double sectionEnd() { return BezierBaker.rideEnd(app.lanes); }

    private double ltToX(double localT) { return tToX(tOff + localT); }

    private double xToLt(double x) { return xToT(x) - tOff; }

    /** Which section contains this global time; -1 past the ride end. */
    private int sectionAt(double globalT) {
        if (globalT < -1e-9) return -1;
        double o = 0;
        List<LaneSection> secs = sections();
        for (int k = 0; k < secs.size(); k++) {
            double d = BezierBaker.rideEnd(secs.get(k).lanes);
            if (globalT <= o + d + 1e-9) return k;
            o += d;
        }
        return -1;
    }

    void setActiveSection(int k) {
        List<LaneSection> secs = sections();
        k = (int) FvdDesigner.clamp(k, 0, secs.size() - 1);
        if (k == activeSection && app.lanes == secs.get(k).lanes) return;
        activeSection = k;
        app.lanes = secs.get(k).lanes;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        syncLaneButtons();
        refreshOffset();
        redraw();
    }

    /** After load/new: the section list was replaced wholesale. */
    void resetSections() {
        activeSection = 0;
        app.lanes = sections().get(0).lanes;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;
        syncLaneButtons();
        refreshOffset();
    }

    /** Undo/redo restore: re-point at the snapshot's section; the
     *  caller (applySnapshot) fixes selection against the new lanes. */
    void restoreActive(int k) {
        List<LaneSection> secs = sections();
        activeSection = (int) FvdDesigner.clamp(k, 0, secs.size() - 1);
        app.lanes = secs.get(activeSection).lanes;
        syncLaneButtons();
        refreshOffset();
    }

    // the open '+'/badge context menu — remembered so a press ON THE
    // GRAPH dismisses it like a press anywhere else (v10.1: JavaFX's
    // auto-hide doesn't fire for clicks landing on this canvas)
    private ContextMenu openMenu;

    private void showMenu(ContextMenu cm, double screenX, double screenY) {
        openMenu = cm;
        cm.show(canvas, screenX, screenY);
    }

    /** The '+' menu: force and geo sections (Phase 2); bridge/curve
     *  modes and attaching floating pieces are still to come. */
    private void showAddSectionMenu(double screenX, double screenY) {
        ContextMenu cm = new ContextMenu();
        MenuItem force = new MenuItem("Add force section (1 s hold)");
        force.setOnAction(ev -> addSection(false));
        MenuItem geo = new MenuItem("Add geo section (1 s hold)");
        geo.setOnAction(ev -> addSection(true));
        MenuItem later = new MenuItem("Bridge / curve — later in Phase 2");
        later.setDisable(true);
        MenuItem pieces = new MenuItem("Attach floating piece — later in Phase 2");
        pieces.setDisable(true);
        cm.getItems().addAll(force, geo, later, pieces);
        showMenu(cm, screenX, screenY);
    }

    /** The section BADGE menu (the strip's type tag doubles as its
     *  per-section actions handle — reverse + delete today). */
    private void showSectionMenu(int k, double screenX, double screenY) {
        LaneSection sec = sections().get(k);
        ContextMenu cm = new ContextMenu();
        MenuItem info = new MenuItem(String.format(Locale.US,
            "%s section %d — design %.1f m/s",
            sec instanceof GeoSection ? "Geo" : "Force", k + 1, sec.vDesign));
        info.setDisable(true);
        CheckMenuItem rev = new CheckMenuItem("Reversed (train runs it backward)");
        rev.setSelected(sec.reversed);
        rev.setOnAction(ev -> {
            pushUndoSnapshot();
            sec.reversed = rev.isSelected();
            refreshOffset();
            redraw();
            app.rebuildTrack();
        });
        MenuItem del = new MenuItem("Delete section");
        del.setDisable(sections().size() <= 1);   // the ride keeps >= 1
        del.setOnAction(ev -> deleteSection(k));
        cm.getItems().addAll(info, new SeparatorMenuItem(), rev, del);
        showMenu(cm, screenX, screenY);
    }

    /** Remove section k; downstream sections re-chain from the new
     *  previous exit (their LOCAL shapes untouched, §0). One undo step. */
    private void deleteSection(int k) {
        List<LaneSection> secs = sections();
        if (secs.size() <= 1 || k < 0 || k >= secs.size()) return;
        pushUndoSnapshot();
        boolean wasActive = k == activeSection;
        app.coaster.pieces.get(0).sections.remove(k);
        if (activeSection > k) activeSection--;
        activeSection = (int) FvdDesigner.clamp(activeSection, 0, sections().size() - 1);
        app.lanes = sections().get(activeSection).lanes;
        if (wasActive) {
            selIdx = -1;
            selSet.clear();
            axisLock = 0;
            handleViewExpand = false;
        }
        syncLaneButtons();
        refreshOffset();
        redraw();
        app.rebuildTrack();
    }

    /** Append a 1 s section HOLDING the ride's end state, so the
     *  transition is seamless whatever came before (v10.1 user spec).
     *  Its vDesign freezes at the design speed of the piece's current
     *  end (§0: later v0 edits must never reshape it). One undo step.
     *  Same-mode: the previous section's final lane values. Force
     *  after geo: the felt end forces (compiled Gv/Gl at design speed)
     *  + the final roll rate. Geo after force: the RESULTING end
     *  pitch/yaw rates + the authored roll rate. */
    private void addSection(boolean geo) {
        pushUndoSnapshot();
        List<LaneSection> secs = sections();
        LaneSection prev = secs.get(secs.size() - 1);
        FVDIntegrator.Sample end = app.lastSamples != null && !app.lastSamples.isEmpty()
            ? app.lastSamples.get(app.lastSamples.size() - 1) : null;
        double vEnd = end != null ? end.v() : prev.vDesign;
        List<BezierBaker.Node> roll = prev.lanes.get(2);
        // a reversed prev's authored roll reads against the chain — use
        // the compiled resulting roll at the ride end instead (D67)
        double rollEnd = prev.reversed
                && app.resultRates != null && app.resultRates.length > 0
            ? app.resultRates[app.resultRates.length - 1][3]
            : roll.get(roll.size() - 1).val;
        LaneSection added;
        if (geo == prev instanceof GeoSection && !prev.reversed) {
            added = Coaster.continuation(prev, vEnd);
        } else if (geo) {
            double[] endRates = app.resultRates != null && app.resultRates.length > 0
                ? app.resultRates[app.resultRates.length - 1] : new double[4];
            added = new GeoSection(
                LaneSection.holdLanes(endRates[1], endRates[2], rollEnd), vEnd);
        } else {
            added = Coaster.holdSection(
                end != null ? end.Gv() : 1, end != null ? end.Gl() : 0, rollEnd, vEnd);
        }
        app.coaster.pieces.get(0).sections.add(added);
        activeSection = sections().size() - 1;
        app.lanes = added.lanes;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        syncLaneButtons();
        refreshOffset();
        redraw();
        app.rebuildTrack();
    }

    // inactive-strip visibility (v9.6 user feel pass: 0.13 was too faint)
    private static final double INACTIVE_SECTION_ALPHA = 0.20;

    /** Greyed strips for inactive sections + boundary lines. UNIFIED
     *  VIEW (v10.1 user spec): the whole graph shows ONE mode's
     *  quantities — the active section's. Same-mode strips draw their
     *  authored lanes; a strip of the OTHER mode draws what it DOES in
     *  the active mode's units, derived from the compiled track (geo
     *  view of a force strip = its resulting pitch/yaw/roll rates;
     *  force view of a geo strip = its felt Gv/Gl + resulting roll).
     *  Everything shares the active value scales, so curves join up
     *  visually across every boundary. */
    private void drawInactiveSections(GraphicsContext gc) {
        List<LaneSection> secs = sections();
        boolean actGeo = activeGeo();
        double off = 0;
        for (int k = 0; k < secs.size(); k++) {
            double dur = BezierBaker.rideEnd(secs.get(k).lanes);
            if (k != activeSection) {
                gc.setGlobalAlpha(INACTIVE_SECTION_ALPHA);
                gc.setLineWidth(1.5);
                // REVERSED strips draw derived too: their authored lanes
                // read back-to-front against the chain (D67)
                if ((secs.get(k) instanceof GeoSection) == actGeo
                        && !secs.get(k).reversed) {
                    drawAuthoredStrip(gc, secs.get(k), off, dur);
                } else {
                    drawDerivedStrip(gc, off, dur);
                }
                gc.setGlobalAlpha(1.0);
            }
            off += dur;
            if (k < secs.size() - 1) {   // boundary between sections
                double bx = tToX(off);
                gc.setGlobalAlpha(0.55);
                gc.setStroke(Color.rgb(150, 156, 175));
                gc.setLineWidth(1);
                gc.strokeLine(bx, MARGIN_TOP, bx, MARGIN_TOP + plotHeight());
                gc.setGlobalAlpha(1.0);
            }
        }
    }

    /** A same-mode inactive strip: its authored lanes on the shared
     *  scales, dotted hold to the section end, zero-line fill. */
    private void drawAuthoredStrip(GraphicsContext gc, LaneSection sec,
                                   double off, double dur) {
        for (int L = 0; L < LANES; L++) {
            List<double[]> poly = BezierBaker.polyline(sec.lanes.get(L));
            List<double[]> pts = new ArrayList<>(poly.size() + 1);
            for (double[] p : poly) {
                pts.add(new double[]{ tToX(off + p[0]), valToY(L, p[1]) });
            }
            double[] lastP = poly.get(poly.size() - 1);
            boolean held = lastP[0] < dur - 1e-9;
            if (held) {   // fill runs under the hold too
                pts.add(new double[]{ tToX(off + dur), valToY(L, lastP[1]) });
            }
            fillUnderSeries(gc, pts, L);
            gc.setStroke(laneColor(L));
            gc.beginPath();
            int n = poly.size();   // stroke the authored part solid...
            for (int i = 0; i < n; i++) {
                double[] p = pts.get(i);
                if (i == 0) gc.moveTo(p[0], p[1]); else gc.lineTo(p[0], p[1]);
            }
            gc.stroke();
            if (held) {            // ...and the hold dotted, as ever
                gc.setLineDashes(3, 7);
                gc.strokeLine(pts.get(n - 1)[0], pts.get(n - 1)[1],
                              pts.get(n)[0], pts.get(n)[1]);
                gc.setLineDashes((double[]) null);
            }
        }
    }

    // bodyRates' central differences smear a section-boundary jump over
    // ±2 samples; derived curves trim that band off their ends so seams
    // read as direct connections, not tiny angled ramps (v10.2)
    private static final double SEAM_TRIM = 2.5 * FvdDesigner.INTEGRATE_DT;

    /** An other-mode inactive strip: the active mode's quantities over
     *  its stretch, straight off the compiled samples. */
    private void drawDerivedStrip(GraphicsContext gc, double off, double dur) {
        if (app.resultRates == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return;
        for (int L = 0; L < LANES; L++) {
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < app.resultRates.length; i++) {
                double t = app.resultRates[i][0];
                if (t < off + SEAM_TRIM || t > off + dur - SEAM_TRIM) continue;
                pts.add(new double[]{ tToX(t), valToY(L, derivedAt(i, L)) });
            }
            if (pts.size() < 2) continue;
            fillUnderSeries(gc, pts, L);
            gc.setStroke(laneColor(L));
            gc.beginPath();
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i);
                if (i == 0) gc.moveTo(p[0], p[1]); else gc.lineTo(p[0], p[1]);
            }
            gc.stroke();
        }
    }

    /** The active mode's lane-L value at compiled sample i (unified
     *  strips + cross-mode boundary matching). Geo view: resulting
     *  pitch/yaw/roll rates. Force view: felt Gv/Gl (the geo
     *  integrator records them at design speed) + resulting roll. */
    private double derivedAt(int i, int L) {
        if (activeGeo()) return app.resultRates[i][L + 1];
        if (L == 0) return app.lastSamples.get(i).Gv();
        if (L == 1) return app.lastSamples.get(i).Gl();
        return app.resultRates[i][3];
    }

    // zero-line fill under every drawn lane curve (v10.1 visual pass;
    // the read-only "Resulting" overlays deliberately stay unfilled)
    private static final double LANE_FILL_ALPHA = 0.13;

    /** Translucent fill between a lane's pixel-space series and its
     *  value-zero line (inherits the caller's globalAlpha, so dimmed
     *  lanes/strips get proportionally fainter fills). */
    private void fillUnderSeries(GraphicsContext gc, List<double[]> pts, int L) {
        if (pts.size() < 2) return;
        double zy = valToY(L, 0);
        gc.setFill(laneColor(L).deriveColor(0, 1, 1, LANE_FILL_ALPHA));
        gc.beginPath();
        gc.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) gc.lineTo(pts.get(i)[0], pts.get(i)[1]);
        gc.lineTo(pts.get(pts.size() - 1)[0], zy);
        gc.lineTo(pts.get(0)[0], zy);
        gc.closePath();
        gc.fill();
    }

    /** Pixel distance from a point to the nearest ACTIVE-section node
     *  (any lane — dimmed-lane nodes are clickable too). */
    private double nearestNodePx(double x, double y) {
        double best = 1e9;
        for (int L = 0; L < LANES; L++) {
            for (BezierBaker.Node n : app.lanes.get(L)) {
                best = Math.min(best, Math.hypot(x - ltToX(n.t), y - valToY(L, n.val)));
            }
        }
        return best;
    }

    /** The vertical line + circled '+' at the ride end (user spec):
     *  click opens the add-section menu. */
    private void drawAddButton(GraphicsContext gc, double w) {
        double x = tToX(pieceDur());
        if (x < MARGIN_LEFT - 1 || x > w - marginRight() + 1) {
            addBtnX = -999;
            return;
        }
        gc.setGlobalAlpha(0.55);
        gc.setStroke(Color.rgb(150, 156, 175));
        gc.setLineWidth(1);
        gc.setLineDashes(4, 6);
        gc.strokeLine(x, MARGIN_TOP, x, MARGIN_TOP + plotHeight());
        gc.setLineDashes((double[]) null);
        // the ride's END nodes sit exactly on this line — and the OTHER
        // lanes' value-0 end nodes map to mid-plot on their scales (Gl 0
        // on ±1, roll 0 on ±150), so a single top-vs-mid comparison never
        // dodged (D57). Walk a ladder of spots downward and take the
        // first CLEAR one; if none is clear, the clearest.
        double cy = MARGIN_TOP + 16;
        double[] ladder = { MARGIN_TOP + 16,
                            MARGIN_TOP + plotHeight() * 0.30,
                            MARGIN_TOP + plotHeight() * 0.50,
                            MARGIN_TOP + plotHeight() * 0.70,
                            MARGIN_TOP + plotHeight() - 16 };
        double bestD = -1;
        for (double cand : ladder) {
            double d = nearestNodePx(x, cand);
            if (d >= 26) { cy = cand; bestD = Double.MAX_VALUE; break; }
            if (d > bestD) { bestD = d; cy = cand; }
        }
        gc.setGlobalAlpha(0.9);
        gc.setFill(Color.rgb(45, 50, 62));
        gc.fillOval(x - 9, cy - 9, 18, 18);
        gc.strokeOval(x - 9, cy - 9, 18, 18);
        gc.setLineWidth(1.6);
        gc.strokeLine(x - 4.5, cy, x + 4.5, cy);
        gc.strokeLine(x, cy - 4.5, x, cy + 4.5);
        gc.setGlobalAlpha(1.0);
        addBtnX = x;
        addBtnY = cy;
    }

    // section type badges (v10.1): a small "force"/"geo" tag at the top
    // of every strip — the type indicator doubles as the per-section
    // menu handle (delete section). Rebuilt each redraw; rows {x,y,w,h,k}.
    private final List<double[]> badgeRects = new ArrayList<>();

    private void drawSectionBadges(GraphicsContext gc, double w) {
        badgeRects.clear();
        List<LaneSection> secs = sections();
        double off = 0;
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (int k = 0; k < secs.size(); k++) {
            double dur = BezierBaker.rideEnd(secs.get(k).lanes);
            String label = (secs.get(k) instanceof GeoSection ? "geo" : "force")
                + (secs.get(k).reversed ? " ◀" : "");
            double bw = label.length() * 6 + 10;
            double x0 = Math.max(tToX(off), MARGIN_LEFT);
            double x1 = Math.min(tToX(off + dur), w - marginRight());
            if (x1 - x0 >= bw + 8) {   // strip too narrow on screen = no badge
                double bx = x0 + 4, by = MARGIN_TOP + 4;
                gc.setGlobalAlpha(k == activeSection ? 0.85 : 0.45);
                gc.setFill(Color.rgb(45, 50, 62));
                gc.fillRoundRect(bx, by, bw, 15, 7, 7);
                gc.setStroke(Color.rgb(150, 156, 175));
                gc.setLineWidth(1);
                gc.strokeRoundRect(bx, by, bw, 15, 7, 7);
                gc.setFill(Color.rgb(205, 210, 220));
                gc.fillText(label, bx + bw / 2, by + 8);
                badgeRects.add(new double[]{ bx, by, bw, 15, k });
            }
            off += dur;
        }
        gc.setGlobalAlpha(1.0);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    /** Which section's badge is under this point; -1 for none. */
    private int hitBadge(double x, double y) {
        for (double[] r : badgeRects) {
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                return (int) r[4];
            }
        }
        return -1;
    }

    private static final int LANES = 3;
    private static final String[] LANE_NAMES = { "Vertical G", "Lateral G", "Roll rate (deg/s)" };
    private static final String[] GEO_LANE_NAMES = { "Pitch rate (deg/s)", "Yaw rate (deg/s)", "Roll rate (deg/s)" };
    private static final String[] LANE_UNITS = { " g", " g", " °/s" };
    // default visible ranges; the axis auto-expands (1-2-5 rounded)
    // whenever the curve exceeds them within the current time window
    private static final double[] DEF_MIN = { -1, -1, -150 };
    private static final double[] DEF_MAX = {  4,  1,  150 };
    // geo mode: all three lanes are rates — pitch/yaw default tighter
    // than roll (±150 would dwarf typical pitch curves)
    private static final double[] GEO_DEF_MIN = { -60, -60, -150 };
    private static final double[] GEO_DEF_MAX = {  60,  60,  150 };
    private final double[] curMin = DEF_MIN.clone();
    private final double[] curMax = DEF_MAX.clone();

    private static final double MARGIN_LEFT = 58, MARGIN_RIGHT = 14;
    // widened right margin while the RESULTING axis is shown (v10.3)
    private static final double MARGIN_RIGHT_RES = 48;

    // the resulting overlays' OWN value scale (v10.3): one shared axis
    // for the enabled curves (they share units per mode — °/s over a
    // force section, g over a geo one), fitted like the lane scales and
    // labeled on the RIGHT edge. resAxisOn also widens the margin.
    private boolean resAxisOn = false;
    private double resLo = -60, resHi = 60;

    private double marginRight() { return resAxisOn ? MARGIN_RIGHT_RES : MARGIN_RIGHT; }

    // hover readout on a resulting curve: comp 0 = none, 1/2 = the
    // pitch-or-Gv / yaw-or-Gl overlay under the cursor
    private int hoverResComp = 0;
    private double hoverResT, hoverResVal;
    private static final double MARGIN_TOP = 8, MARGIN_BOTTOM = 32;
    private static final double NODE_RADIUS = 5, NODE_HIT = 9;
    private static final double KNOB_RADIUS = 4.5, KNOB_HIT = 9;
    private static final double HANDLE_ALPHA = 0.45;
    private static final double INACTIVE_ALPHA = 0.25;
    private static final double MIN_GAP = 0.01;   // minimum transition length
    private static final double INSERT_GAP = 0.02; // insert may get almost as close
    private static final double MIN_FRAC = 0.02;

    // node-drag snap (with Ctrl): TRANSITION length in time, value on a
    // GLOBAL absolute grid — the steps are adjustable in Edit > Options.
    // In a geo section every lane is a rate, so all snap by the roll step.
    private double snapT() { return app.settings.snapT(); }
    private double snapVal(int lane) {
        return (lane == 2 || activeGeo())
            ? app.settings.snapRoll() : app.settings.snapG();
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

    // horizontal WALL for the live node drag (v10.2): the section end as
    // established by the OTHER lanes — see endLimitFor(). MAX = no wall.
    private double dragEndLimit = Double.MAX_VALUE;

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

    // right-click on a segment opens its lock menu — deferred to a
    // clean RELEASE so right-drag pan still works from the curve (v10.7)
    private int rClickSeg = -1;
    private double rPressX, rPressY;

    // Ctrl held while hovering the curve: the insert dot snaps in TIME
    // (the horizontal snap reference applies; value rides the curve)
    private boolean ctrlHover = false;
    private double hoverRawT = Double.NaN;   // unsnapped hover position

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
            hoverT = Double.NaN;   // no hover previews while interacting
            hoverRawT = Double.NaN;
            hoverResComp = 0;
            // an open '+'/badge menu: this press just dismisses it (JavaFX
            // auto-hides on clicks anywhere EXCEPT this canvas — v10.1)
            if (openMenu != null && openMenu.isShowing()) {
                openMenu.hide();
                openMenu = null;
                return;
            }
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
                    dragEndLimit = endLimitFor(hit);
                    selIdx = hit;
                    lastDragX = e.getX();
                    lastDragY = e.getY();
                    lastDragSnap = e.isControlDown();
                    lastDragShift = app.slideKey;
                    dragTimerLast = 0;
                    dragGrowTimer.start();
                    redraw();
                } else {
                    // the '+' add-section affordance — tested AFTER the
                    // node/knob hits so an overlapping node stays
                    // grabbable (D57); the button's exposed edge still
                    // opens the menu
                    if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
                        showAddSectionMenu(e.getScreenX(), e.getScreenY());
                        return;
                    }
                    // section type badges (same press rank as the '+')
                    int badge = hitBadge(e.getX(), e.getY());
                    if (badge >= 0) {
                        showSectionMenu(badge, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    // a click ON the active curve inserts a node there —
                    // and keeps DRAGGING it in the same gesture (v10.1:
                    // press-insert-drag is one motion; Ctrl held from the
                    // start snaps the drag as usual). The insert's undo
                    // snapshot covers the whole gesture.
                    double t = curveHitT(e.getX(), e.getY());
                    if (!Double.isNaN(t) && e.isControlDown()) {
                        // Ctrl: the insert lands on the snapped spot the
                        // hover dot showed (time only; value on-curve)
                        double s = snapInsertT(t);
                        t = insertRoomAt(s) ? s : Double.NaN;
                    }
                    if (!Double.isNaN(t)) {
                        int ni = insertNodeOnCurve(t);
                        if (ni >= 0) {
                            dragNodeIdx = ni;
                            dragEndLimit = endLimitFor(ni);
                            gestureStartX = e.getX();
                            gestureStartY = e.getY();
                            gestureNodeT = active().get(ni).t;
                            gestureNodeV = active().get(ni).val;
                            dragWinT0 = viewStart();   // freeze the window,
                            dragWinT1 = viewEnd();     // like beginGesture
                            pendingSnapshot = null;    // insert already pushed
                            gestureMoved = true;
                            lastDragX = e.getX();
                            lastDragY = e.getY();
                            lastDragSnap = e.isControlDown();
                            lastDragShift = app.slideKey;
                            dragTimerLast = 0;
                            dragGrowTimer.start();
                        }
                        return;
                    }
                    // a press inside a GREYED section's strip ALWAYS
                    // activates that section — checked BEFORE the dimmed-
                    // lane test (v10.2): the active section's lanes hold
                    // their end values across the whole axis, so a click
                    // on another strip's curve used to get eaten as a
                    // lane switch whenever it lined up with a held value
                    double gt = xToT(e.getX());
                    if (gt < tOff - 1e-9 || gt > tOff + sectionEnd() + 1e-9) {
                        int k = sectionAt(gt);
                        if (k >= 0 && k != activeSection) {
                            setActiveSection(k);
                            return;
                        }
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
                if (hit >= 0) {
                    // right-click a node = its editor dialog (v10.7 —
                    // was delete; the Delete key keeps that job)
                    selIdx = hit;
                    if (!selSet.contains(hit)) {
                        selSet.clear();
                        selSet.add(hit);
                    }
                    redraw();
                    showNodeEditor(hit);
                    return;
                }
                // right-click a SEGMENT = its lock menu — but only on a
                // clean click: the menu is deferred to release so that
                // right-DRAG still pans even when it starts on the curve
                rClickSeg = segmentAt(curveHitT(e.getX(), e.getY()));
                rPressX = e.getX();
                rPressY = e.getY();
                panLastX = e.getX();   // right-drag = pan
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
            ctrlHover = e.isControlDown();
            double t = (hitNode(e.getX(), e.getY()) < 0
                    && (selIdx < 0 || hitKnob(e.getX(), e.getY()) == 0))
                    ? curveHitT(e.getX(), e.getY()) : Double.NaN;
            hoverRawT = t;
            t = snappedHover(t);
            // resulting-curve hover (v10.3): when the insert dot isn't
            // claiming the spot, a resulting overlay within a few px
            // vertically shows its value readout instead
            int rc = 0;
            double rt = 0, rv = 0;
            if (Double.isNaN(t) && resAxisOn) {
                double gt = xToT(e.getX());
                double rideT = app.resultRates[app.resultRates.length - 1][0];
                if (gt > SEAM_TRIM && gt < rideT - SEAM_TRIM
                        && !nearSeam(gt, sectionBoundaries())) {
                    int i = rateIdxAt(gt);
                    double best = 8;
                    for (int comp = 1; comp <= 2; comp++) {
                        if (comp == 1 ? !app.settings.resPitch()
                                      : !app.settings.resYaw()) continue;
                        double v = resValueAt(i, comp);
                        double d = Math.abs(e.getY() - resToY(v));
                        if (d < best) { best = d; rc = comp; rt = gt; rv = v; }
                    }
                }
            }
            boolean was = !Double.isNaN(hoverT) || hoverResComp != 0;
            boolean is = !Double.isNaN(t) || rc != 0;
            hoverT = t;
            hoverResComp = rc;
            hoverResT = rt;
            hoverResVal = rv;
            if (was || is) redraw();
        });
        canvas.setOnMouseExited(e -> {
            if (!Double.isNaN(hoverT) || hoverResComp != 0) {
                hoverT = Double.NaN;
                hoverRawT = Double.NaN;
                hoverResComp = 0;
                redraw();
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (!Double.isNaN(boxX0) && e.isPrimaryButtonDown()) {
                boxX1 = e.getX();
                boxY1 = e.getY();
                redraw();
                return;
            }
            if (!Double.isNaN(panLastX) && e.isSecondaryButtonDown()) {
                if (Math.hypot(e.getX() - rPressX, e.getY() - rPressY) > 3) {
                    rClickSeg = -1;   // it's a pan, not a segment click
                }
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
            if (e.getButton() == MouseButton.SECONDARY && rClickSeg >= 0
                    && Math.hypot(e.getX() - rPressX, e.getY() - rPressY) <= 3) {
                int seg = rClickSeg;
                rClickSeg = -1;
                panLastX = Double.NaN;
                showSegmentLockMenu(seg, e.getScreenX(), e.getScreenY());
                return;
            }
            rClickSeg = -1;
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
                        double px = ltToX(lane.get(i).t);
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
            dragEndLimit = Double.MAX_VALUE;
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
    void refreshMarkers() {
        refreshOffset();
        app.refreshTrackMarkers(activeLane, selSet, selIdx, tOff);
    }

    /** Spine-level track position of the primary selected node, or
     *  null when nothing is selected (side-view centering). */
    Vec3 selectedTrackPoint() {
        FVDIntegrator.Sample sm = selectedSample();
        return sm == null ? null : sm.pos().sub(sm.U().scale(app.heartOffset));
    }

    /** Track direction at the primary selected node, or null (the side
     *  view aligns its lens tangent to this — D53). */
    Vec3 selectedTrackTangent() {
        FVDIntegrator.Sample sm = selectedSample();
        return sm == null ? null : sm.T();
    }

    private FVDIntegrator.Sample selectedSample() {
        if (selIdx < 0 || selIdx >= active().size()) return null;
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return null;
        refreshOffset();
        int idx = (int) FvdDesigner.clamp(
            Math.round((tOff + active().get(selIdx).t) / FvdDesigner.INTEGRATE_DT),
            0, app.lastSamples.size() - 1);
        return app.lastSamples.get(idx);
    }

    /** Ctrl+click on the 3D track: insert at that RIDE time — the
     *  containing section activates, then the insert runs locally
     *  (same rules as clicking the graph curve). */
    void insertAtTime(double globalT) {
        int k = sectionAt(globalT);
        if (k < 0) return;
        setActiveSection(k);
        insertNodeOnCurve(globalT - tOff);
    }

    /** Shift pressed/released while hovering: toggle the slope readout
     *  without waiting for the next mouse move. */
    void shiftHover(boolean on) {
        if (hoverSlope == on) return;
        hoverSlope = on;
        if (!Double.isNaN(hoverT) || hoverResComp != 0) redraw();
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
        // section-EDGE nodes have no smooth/broken meaning (one handle) —
        // there B RETURNS the node to the boundary value + slope (v10.1)
        if (selIdx == 0 || selIdx == active().size() - 1) {
            matchBoundary(selIdx);
            return;
        }
        if (selIdx < 0) return;
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

    /** B (or the node dialog's button) on a node at a section EDGE:
     *  RETURN it to the boundary (v10.1 user spec — value AND slope,
     *  not slope alone): the node's value snaps to the neighboring
     *  section's value at the shared boundary and its single handle
     *  takes the neighboring slope, so the curve continues seamlessly.
     *  Works across MODES too — a same-mode neighbor is matched from
     *  its authored lane (exact: a cubic's endpoint slope IS its
     *  handle-line slope, and a lane holding past its last node is
     *  FLAT there); an other-mode neighbor is matched from its
     *  RESULTING curve in the active units (boundaryState). A one-time
     *  move, not a lock (a straight-locked segment re-chords over it);
     *  no adjacent section = no-op. */
    void matchBoundary(int idx) {
        List<BezierBaker.Node> lane = active();
        boolean start = idx == 0 && activeSection > 0 && lane.size() >= 2;
        boolean end = idx == lane.size() - 1 && idx > 0
            && activeSection < sections().size() - 1;
        if (!start && !end) return;
        refreshOffset();
        if (boundaryState(start ? activeSection - 1 : activeSection + 1,
                !start, activeLane) == null) return;
        pushUndoSnapshot();
        applyBoundaryMatch(activeLane, start);
        redraw();
        app.rebuildTrack();
    }

    /** Shift+B with a section-EDGE node selected: the boundary return
     *  on EVERY lane at once — each lane's edge node takes its own
     *  neighbor value/slope, exactly like pressing B on each in turn
     *  (v10.4 user spec), as ONE undo step. */
    void matchBoundaryAll() {
        List<BezierBaker.Node> lane = active();
        if (selIdx < 0) return;
        boolean start = selIdx == 0 && activeSection > 0;
        boolean end = selIdx == active().size() - 1 && selIdx > 0
            && activeSection < sections().size() - 1;
        if (!start && !end) return;
        refreshOffset();
        // nullity is uniform across lanes (it only means "no compiled
        // data yet" at a cross-mode boundary) — probe once
        if (boundaryState(start ? activeSection - 1 : activeSection + 1,
                !start, 0) == null) return;
        pushUndoSnapshot();
        for (int L = 0; L < LANES; L++) applyBoundaryMatch(L, start);
        redraw();
        app.rebuildTrack();
    }

    /** The boundary return for ONE lane's edge node (no undo push):
     *  value + handle slope from the neighbor at the shared boundary.
     *  Returns false when that lane has nothing to match. */
    private boolean applyBoundaryMatch(int laneIdx, boolean startEdge) {
        List<BezierBaker.Node> ln = app.lanes.get(laneIdx);
        if (ln.size() < 2) return false;
        if (startEdge) {
            if (activeSection <= 0) return false;
            double[] vs = boundaryState(activeSection - 1, false, laneIdx);
            if (vs == null) return false;
            BezierBaker.Node n = ln.get(0);
            n.val = vs[0];
            n.outDv = vs[1] * BezierBaker.effOutDt(n, ln.get(1));
            return true;
        }
        if (activeSection >= sections().size() - 1) return false;
        double[] vs = boundaryState(activeSection + 1, true, laneIdx);
        if (vs == null) return false;
        BezierBaker.Node n = ln.get(ln.size() - 1);
        n.val = vs[0];
        n.inDv = vs[1] * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
        return true;
    }

    /** {value, slope} of neighbor section k's active-lane curve at the
     *  boundary it shares with the active section, in the ACTIVE
     *  mode's units. atStart = the neighbor is the NEXT section (look
     *  at its beginning) vs the previous one (look at its end). The
     *  roll lane is authored °/s in both modes, so it always matches
     *  from the authored lane; other cross-mode lanes read the
     *  compiled RESULTING curves, slope by finite difference over
     *  ~0.05 s on the neighbor's side. Null = no data yet. */
    private double[] boundaryState(int k, boolean atStart, int laneIdx) {
        List<LaneSection> secs = sections();
        LaneSection nb = secs.get(k);
        // a REVERSED neighbor's authored lanes run against the chain
        // (values at the wrong end, roll/Gl signs flipped) — its
        // boundary state comes from the compiled curves instead (D67)
        if ((sameMode(nb, secs.get(activeSection)) || laneIdx == 2)
                && !nb.reversed) {
            List<BezierBaker.Node> ln = nb.lanes.get(laneIdx);
            if (atStart) {
                BezierBaker.Node n0 = ln.get(0);
                double outDt = ln.size() >= 2
                    ? BezierBaker.effOutDt(n0, ln.get(1)) : 0;
                return new double[]{ n0.val,
                    Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt };
            }
            BezierBaker.Node last = ln.get(ln.size() - 1);
            if (ln.size() < 2 || last.t < BezierBaker.rideEnd(nb.lanes) - 1e-9) {
                return new double[]{ last.val, 0 };   // holding flat at the boundary
            }
            double inDt = BezierBaker.effInDt(ln.get(ln.size() - 2), last);
            return new double[]{ last.val,
                Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt };
        }
        if (app.resultRates == null || app.lastSamples == null
                || app.resultRates.length < 12) return null;
        // sample CLEAR of the seam (v10.2): bodyRates' central
        // differences SMEAR a boundary discontinuity across ±2 samples
        // (the short angled connector once visible at seams), and the
        // seam's sample index shifts with the integrator's cadence — so
        // read the neighbor ~3 samples inside and slope over the next 5.
        // Sampling the smear made B converge over several presses
        // instead of landing in one.
        double dt = FvdDesigner.INTEGRATE_DT;
        double tB = atStart ? tOff + sectionEnd() : tOff;
        int n = app.resultRates.length;
        int i0, i1;
        if (atStart) {   // just inside the next section
            i0 = rateIdxAt(tB + 3 * dt);
            i1 = Math.min(i0 + 5, n - 1);
        } else {         // just inside the previous section
            i1 = rateIdxAt(tB - 3 * dt);
            i0 = Math.max(i1 - 5, 0);
        }
        double val = derivedAt(atStart ? i0 : i1, laneIdx);
        double span = app.resultRates[i1][0] - app.resultRates[i0][0];
        double slope = span < 1e-9 ? 0
            : (derivedAt(i1, laneIdx) - derivedAt(i0, laneIdx)) / span;
        return new double[]{ val, slope };
    }

    /** Index of the compiled sample nearest ride time t (rows are
     *  time-sorted; binary search — authored section durations and
     *  sample times drift apart by the integrator cadence, so never
     *  compute indices as t/dt). */
    private int rateIdxAt(double t) {
        double[][] r = app.resultRates;
        int lo = 0, hi = r.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (r[mid][0] <= t) lo = mid; else hi = mid;
        }
        return t - r[lo][0] <= r[hi][0] - t ? lo : hi;
    }

    /** Same lane semantics (both force or both geo)? */
    private static boolean sameMode(LaneSection a, LaneSection b) {
        return (a instanceof GeoSection) == (b instanceof GeoSection);
    }

    /** The horizontal WALL for a node drag (v10.2): the section end as
     *  established by the OTHER lanes' last nodes ("where another graph
     *  already is"). Only the lane's LAST node can meet it (interior
     *  nodes are clamped by their neighbors), and a node that BEGINS
     *  the gesture at/past the wall is exempt — that is how a section
     *  is deliberately extended: drag to the end, release, grab it
     *  again to pull further. MAX_VALUE = no wall. */
    private double endLimitFor(int nodeIdx) {
        List<BezierBaker.Node> lane = active();
        if (nodeIdx != lane.size() - 1) return Double.MAX_VALUE;
        double end = 0;
        for (List<BezierBaker.Node> ln : app.lanes) {
            if (ln == lane) continue;
            end = Math.max(end, ln.get(ln.size() - 1).t);
        }
        if (end <= 1e-9) return Double.MAX_VALUE;
        return lane.get(nodeIdx).t >= end - 1e-6 ? Double.MAX_VALUE : end;
    }

    // ---------- coordinate mapping ----------

    private double maxT() { return Math.max(0.5, pieceDur()); }
    private double domain() { return maxT() * 1.08; }
    private double plotHeight() { return canvas.getHeight() - MARGIN_TOP - MARGIN_BOTTOM; }
    private double plotWidth() { return canvas.getWidth() - MARGIN_LEFT - marginRight(); }

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
            double px = ltToX(lane.get(i).t);
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
                double d = Math.hypot(x - ltToX(lane.get(i).t),
                                      y - valToY(L, lane.get(i).val));
                if (d < best) { best = d; bestLane = L; bestNode = i; }
            }
        }
        if (bestLane >= 0) return new int[]{ bestLane, bestNode };

        // curve body: vertical distance to the lane's value at this time
        // (evalAt holds the end values outside the node range)
        double t = xToLt(x);
        double bestCurve = 7;
        for (int L = 0; L < LANES; L++) {
            if (L == activeLane) continue;
            double d = Math.abs(y - valToY(L, BezierBaker.evalAt(app.lanes.get(L), t)));
            if (d < bestCurve) { bestCurve = d; bestLane = L; }
        }
        return bestLane >= 0 ? new int[]{ bestLane, -1 } : null;
    }

    /** Screen position of a node's handle knob, or null when the knob
     *  doesn't exist — or faces a LOCKED segment (v10.4/5): a locked
     *  segment's shape is its line no matter the handles, so its
     *  facing knobs are hidden and unclickable. */
    private double[] knobPos(int idx, boolean out) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        if (out) {
            if (idx >= lane.size() - 1 || n.lockedOut()) return null;
            double dt = BezierBaker.effOutDt(n, lane.get(idx + 1));
            return new double[]{ ltToX(n.t + dt), valToY(activeLane, n.val + n.outDv) };
        } else {
            if (idx <= 0 || lane.get(idx - 1).lockedOut()) return null;
            double dt = BezierBaker.effInDt(lane.get(idx - 1), n);
            return new double[]{ ltToX(n.t + dt), valToY(activeLane, n.val + n.inDv) };
        }
    }

    // ---------- editing ----------

    private void dragNodeTo(double x, double y, boolean snap, boolean shift) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(dragNodeIdx);

        // beyond a plot edge the node rides the edge while dragGrowTimer
        // smoothly extends the axis at a rate set by the overshoot
        double newT = xToLt(x);
        double newVal = yToVal(activeLane, y);

        if (snap) {   // Ctrl enables snapping; free otherwise
            // time: quantize the TRANSITION from the previous node
            // (0.3 s, 0.8 s, ...) by default, or the ABSOLUTE ride-time
            // grid the axis ticks show (Options toggle, v10.2)
            if (dragNodeIdx > 0) {
                double step = snapT();
                double prevT = lane.get(dragNodeIdx - 1).t;
                double g = app.settings.snapTAbsolute()
                    ? Math.round((tOff + newT) / step) * step - tOff
                    : prevT + Math.round((newT - prevT) / step) * step;
                // the section end is a snap point of its own, and grid
                // points hugging it are ABSORBED — a snap near the end
                // lands EXACTLY on the end, never almost (v10.2)
                if (dragEndLimit < Double.MAX_VALUE
                        && (Math.abs(g - dragEndLimit) < step * 0.5
                            || Math.abs(newT - dragEndLimit) < Math.abs(newT - g))) {
                    newT = dragEndLimit;
                } else {
                    newT = Math.max(prevT + MIN_GAP, g);
                }
            }
            // value: global absolute grid (0.0 G, 3.6 G, 124 deg/s ...)
            // by default, or Δ from the previous node (Options toggle)
            double vStep = snapVal(activeLane);
            if (app.settings.snapVRelative() && dragNodeIdx > 0) {
                double pv = lane.get(dragNodeIdx - 1).val;
                newVal = pv + Math.round((newVal - pv) / vStep) * vStep;
            } else {
                newVal = Math.round(newVal / vStep) * vStep;
            }
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

        // the section end is a WALL: only a node that began the gesture
        // already at the end may drag past it to extend the section
        if (newT > dragEndLimit) newT = dragEndLimit;

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
        if (grid) {   // land ON the grid — same axis references as Ctrl-drag
            if (selIdx > 0) {
                double step = snapT();
                double prevT = lane.get(selIdx - 1).t;
                double g = app.settings.snapTAbsolute()
                    ? Math.round((tOff + newT) / step) * step - tOff
                    : prevT + Math.round((newT - prevT) / step) * step;
                newT = Math.max(prevT + MIN_GAP, g);
            }
            double vStep = snapVal(activeLane);
            if (app.settings.snapVRelative() && selIdx > 0) {
                double pv = lane.get(selIdx - 1).val;
                newVal = pv + Math.round((newVal - pv) / vStep) * vStep;
            } else {
                newVal = Math.round(newVal / vStep) * vStep;
            }
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
        // a level run's height is steerable from EITHER END (v10.6):
        // enforcement propagates left-to-right, so a move of the RIGHT
        // end writes its value back through the run first; interior
        // nodes stay pinned to the line (enforcement snaps them back)
        if (idx > 0 && lane.get(idx - 1).levelOut
                && (idx >= lane.size() - 1 || !lane.get(idx).levelOut)) {
            for (int j = idx - 1; j >= 0 && lane.get(j).levelOut; j--) {
                lane.get(j).val = newVal;
            }
        }
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

            double nx = ltToX(n.t), ny = valToY(activeLane, n.val);
            double kx = ltToX(n.t + curDt), ky = valToY(activeLane, n.val + oldDv);
            double dirX = kx - nx, dirY = ky - ny;
            double dirLen = Math.hypot(dirX, dirY);
            if (dirLen < 1e-6) return;
            dirX /= dirLen; dirY /= dirLen;

            double proj = Math.max(4, (x - nx) * dirX + (y - ny) * dirY);
            double newDt = xToLt(nx + dirX * proj) - n.t;
            newFrac = FvdDesigner.clamp(Math.abs(newDt) / segLen, MIN_FRAC, BezierBaker.MAX_FRAC);
            if (snap) newFrac = snapFrac(newFrac);
            newDv = slope * ((dragHandleIsOut ? 1 : -1) * newFrac * segLen);
        } else {
            // free: fraction from mouse time, dv from mouse value
            double dt = xToLt(x) - n.t;
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
        double px0 = ltToX(poly.get(0)[0]), py0 = valToY(activeLane, poly.get(0)[1]);
        // the dotted hold-final-value extension is insertable too
        double laneEnd = lane.get(lane.size() - 1).t;
        boolean extended = laneEnd < sectionEnd() - 1e-9;
        int segs = poly.size() + (extended ? 1 : 0);
        for (int i = 1; i < segs; i++) {
            double t1 = i < poly.size() ? poly.get(i)[0] : sectionEnd();
            double v1 = i < poly.size() ? poly.get(i)[1] : lane.get(lane.size() - 1).val;
            double t0 = i < poly.size() ? poly.get(i - 1)[0] : laneEnd;
            double px1 = ltToX(t1), py1 = valToY(activeLane, v1);
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
        return insertRoomAt(bestT) ? bestT : Double.NaN;
    }

    /** Enough room to insert a node at curve time t (curveHitT's
     *  spacing rules, reusable for re-checking a SNAPPED position). */
    private boolean insertRoomAt(double t) {
        List<BezierBaker.Node> lane = active();
        if (t > sectionEnd() + 1e-9) return false;
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return false;
        if (t - lane.get(i - 1).t < INSERT_GAP) return false;
        if (i < lane.size() && lane.get(i).t - t < INSERT_GAP) return false;
        return true;
    }

    /** The insert position's Ctrl time-snap (v10.7): the horizontal
     *  snap reference applies — Δt from the previous node (local,
     *  default) or the absolute ride-time grid. */
    private double snapInsertT(double t) {
        double step = snapT();
        if (app.settings.snapTAbsolute()) {
            return Math.round((tOff + t) / step) * step - tOff;
        }
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return t;
        double prevT = lane.get(i - 1).t;
        return prevT + Math.round((t - prevT) / step) * step;
    }

    /** The hover dot after the optional Ctrl snap: time lands on the
     *  grid, the VALUE keeps riding the curve (deliberately unsnapped);
     *  NaN when the snapped spot has no room to insert. */
    private double snappedHover(double raw) {
        if (Double.isNaN(raw) || !ctrlHover) return raw;
        double s = snapInsertT(raw);
        return insertRoomAt(s) ? s : Double.NaN;
    }

    /** Ctrl pressed/released while hovering: re-snap the insert dot
     *  without waiting for the next mouse move (key filters, like the
     *  Shift slope toggle). */
    void ctrlHover(boolean on) {
        if (ctrlHover == on) return;
        ctrlHover = on;
        if (Double.isNaN(hoverRawT) && Double.isNaN(hoverT)) return;
        hoverT = snappedHover(hoverRawT);
        redraw();
    }

    /** Insert a node ON the curve. Flat segments get sine handles at the
     *  same level; anything else is a de Casteljau split — tangent
     *  handles, lengths rewritten so the curve does not change at all.
     *  Returns the new node's index, or -1 if there was no room (the
     *  caller may chain straight into a drag gesture — v10.1). */
    private int insertNodeOnCurve(double t) {
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return -1;
        BezierBaker.Node n;
        if (i == lane.size()) {
            // on the dotted hold-final-value extension: append a real
            // node at the same level (flat sine transition)
            BezierBaker.Node last = lane.get(i - 1);
            if (t - last.t < INSERT_GAP || t > sectionEnd() + 1e-9) return -1;
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
            return i;
        }
        BezierBaker.Node a = lane.get(i - 1), b = lane.get(i);
        if (t - a.t < INSERT_GAP || b.t - t < INSERT_GAP) return -1;
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
        n.levelOut = a.levelOut;         // through a split (both halves)
        lane.add(i, n);
        selIdx = i;
        selSet.clear();
        selSet.add(i);
        axisLock = 0;
        redraw();
        app.rebuildTrack();
        return i;
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

    /** H (v10.5 — was Shift+H): level the whole selection ONTO the main
     *  highlighted node — every selected node takes selIdx's value with
     *  flat handles, so the selected run becomes one horizontal line
     *  (segments between selected nodes go exactly flat; transitions
     *  to unselected neighbors keep their handles). The main node is
     *  the last one clicked, or the earliest in a box selection.
     *  With fewer than two selected this falls back to flattening the
     *  selected node's handles (H's classic single-node meaning). */
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

    /** Which authored segment (index of its LEFT node) contains curve
     *  time t; -1 for NaN, the hold extension, or before node 0. */
    private int segmentAt(double t) {
        if (Double.isNaN(t)) return -1;
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        return i == 0 || i >= lane.size() ? -1 : i - 1;
    }

    /** Right-click on a segment (v10.7): its lock setting as a
     *  three-way pick — same semantics as Shift+F / Shift+H, one
     *  segment at a time. */
    private void showSegmentLockMenu(int seg, double screenX, double screenY) {
        List<BezierBaker.Node> lane = active();
        if (seg < 0 || seg >= lane.size() - 1) return;
        BezierBaker.Node a = lane.get(seg);
        ContextMenu cm = new ContextMenu();
        ToggleGroup grp = new ToggleGroup();
        RadioMenuItem none = new RadioMenuItem("Unlocked");
        RadioMenuItem straight = new RadioMenuItem("Straight");
        RadioMenuItem level = new RadioMenuItem("Horizontal");
        none.setToggleGroup(grp);
        straight.setToggleGroup(grp);
        level.setToggleGroup(grp);
        (a.levelOut ? level : a.straightOut ? straight : none).setSelected(true);
        none.setOnAction(ev -> setSegmentLock(seg, 0));
        straight.setOnAction(ev -> setSegmentLock(seg, 1));
        level.setOnAction(ev -> setSegmentLock(seg, 2));
        cm.getItems().addAll(none, straight, level);
        showMenu(cm, screenX, screenY);
    }

    /** Apply one segment's lock pick: 0 unlocked, 1 straight, 2 level. */
    private void setSegmentLock(int seg, int mode) {
        List<BezierBaker.Node> lane = active();
        if (seg < 0 || seg >= lane.size() - 1) return;
        BezierBaker.Node a = lane.get(seg);
        boolean straight = mode == 1, level = mode == 2;
        if (a.straightOut == straight && a.levelOut == level) return;
        pushUndoSnapshot();
        boolean unlocking = mode == 0;
        a.straightOut = straight;
        a.levelOut = level;
        if (unlocking) {
            breakKinkedJoints(lane, java.util.Set.of(seg, seg + 1));
        }
        redraw();          // enforcement applies the new lock right here
        app.rebuildTrack();
    }

    /** The selection to operate on for lock toggles. */
    private Set<Integer> lockSelection() {
        return selSet.isEmpty() && selIdx >= 0 ? java.util.Set.of(selIdx) : selSet;
    }

    /** Segment indices BETWEEN adjacent selected nodes (i where i and
     *  i+1 are both selected) — the lock toggles' target set (v10.6):
     *  what happens between the selected nodes never disturbs a
     *  neighboring lock that merely shares an endpoint. */
    private List<Integer> selectedPairs(Set<Integer> sel) {
        List<Integer> out = new ArrayList<>();
        List<BezierBaker.Node> lane = active();
        for (int i : sel) {
            if (i < lane.size() - 1 && sel.contains(i + 1)) out.add(i);
        }
        return out;
    }

    /** Clear one lock flag on the given segments; kinked joints come
     *  out honestly broken (D62). Caller pushes the undo snapshot. */
    private void unlockSegments(List<BezierBaker.Node> lane,
                                Iterable<Integer> segs, boolean level) {
        Set<Integer> joints = new HashSet<>();
        for (int i : segs) {
            if (level) lane.get(i).levelOut = false;
            else lane.get(i).straightOut = false;
            joints.add(i);
            joints.add(i + 1);
        }
        breakKinkedJoints(lane, joints);
    }

    /** Shift+F: PERSISTENT straightness. With adjacent nodes selected
     *  (2+ — every selected pair counts, v10.6), the segments BETWEEN
     *  them lock straight — or unlock, if they are all already
     *  straight-locked; locks merely NEIGHBORING the selection are
     *  never touched. With no pair selected, a single selected
     *  endpoint unlocks the locked segment(s) it sits on (the classic
     *  gesture). enforceLocks() re-chords locked segments after every
     *  edit, so dragging an endpoint keeps the line straight. */
    void toggleStraightLock() {
        List<BezierBaker.Node> lane = active();
        Set<Integer> sel = lockSelection();
        List<Integer> pairs = selectedPairs(sel);
        if (!pairs.isEmpty()) {
            boolean allLocked = true;
            for (int i : pairs) allLocked &= lane.get(i).straightOut;
            pushUndoSnapshot();
            if (allLocked) {
                unlockSegments(lane, pairs, false);
            } else {
                for (int i : pairs) {
                    lane.get(i).straightOut = true;
                    lane.get(i).levelOut = false;   // swap from Shift+H
                }
            }
            redraw();          // enforcement straightens it right here
            app.rebuildTrack();
            return;
        }
        Set<Integer> locked = new HashSet<>();
        for (int i : sel) {
            if (i < lane.size() - 1 && lane.get(i).straightOut) locked.add(i);
            if (i > 0 && lane.get(i - 1).straightOut) locked.add(i - 1);
        }
        if (locked.isEmpty()) return;
        pushUndoSnapshot();
        unlockSegments(lane, locked, false);
        redraw();
        app.rebuildTrack();
    }

    /** Re-assert every LOCKED segment (runs at the top of every redraw,
     *  which follows every lane mutation; idempotent and cheap).
     *  STRAIGHT locks (Shift+F) re-chord their facing handles onto the
     *  endpoint line; LEVEL locks (Shift+H, v10.5) first pin the RIGHT
     *  node's value to the left's — so a run's height follows its left
     *  end, moveNode writes a RIGHT-end move back through the run
     *  before this pass (either end steers, v10.6), and interior
     *  nodes snap back onto the line — then the same
     *  slope math (slope 0) flattens the facing handles. Smooth
     *  endpoints keep C1 by re-angling their far handle — unless that
     *  side is itself locked, which owns its own handles. */
    private void enforceLocks() {
        for (List<BezierBaker.Node> ln : app.lanes) {
            for (int i = 0; i < ln.size() - 1; i++) {
                BezierBaker.Node a = ln.get(i);
                if (!a.lockedOut()) continue;
                BezierBaker.Node b = ln.get(i + 1);
                if (b.t - a.t < 1e-9) continue;   // malformed file guard
                if (a.levelOut) b.val = a.val;
                double slope = (b.val - a.val) / (b.t - a.t);
                a.outDv = slope * BezierBaker.effOutDt(a, b);
                b.inDv  = slope * BezierBaker.effInDt(a, b);
                if (a.smooth && i > 0 && !ln.get(i - 1).lockedOut()) {
                    a.inDv = slope * BezierBaker.effInDt(ln.get(i - 1), a);
                }
                if (b.smooth && i + 1 < ln.size() - 1 && !b.lockedOut()) {
                    b.outDv = slope * BezierBaker.effOutDt(b, ln.get(i + 2));
                }
            }
        }
    }

    /** After an unlock, a joint whose two sides sit at different slopes
     *  would be a kinked "smooth" node — mark those broken; sides that
     *  still agree stay smooth (D62). */
    private void breakKinkedJoints(List<BezierBaker.Node> lane, Set<Integer> joints) {
        for (int i : joints) {
            if (i <= 0 || i >= lane.size() - 1) continue;
            BezierBaker.Node n = lane.get(i);
            if (!n.smooth) continue;
            double inDt = BezierBaker.effInDt(lane.get(i - 1), n);
            double outDt = BezierBaker.effOutDt(n, lane.get(i + 1));
            double sIn = Math.abs(inDt) < 1e-12 ? 0 : n.inDv / inDt;
            double sOut = Math.abs(outDt) < 1e-12 ? 0 : n.outDv / outDt;
            if (Math.abs(sIn - sOut)
                    > 1e-6 * Math.max(1, Math.max(Math.abs(sIn), Math.abs(sOut)))) {
                n.smooth = false;
            }
        }
    }

    /** Shift+H: PERSISTENT level — the horizontal sibling of Shift+F
     *  (v10.5). With adjacent nodes selected, the segments BETWEEN
     *  them are the target (v10.6, like Shift+F): all already
     *  level-locked = unlock them; otherwise the selection levels onto
     *  the MAIN node and those segments lock horizontal — locks merely
     *  neighboring the selection stay untouched. With no pair
     *  selected, a single selected endpoint unlocks its segment(s).
     *  enforceLocks keeps a run level through any edit (its height is
     *  steerable from EITHER end; interior nodes are pinned), and
     *  nodes inserted on it stay on the line. Swaps directly with
     *  Shift+F: locking one way clears the other. */
    void toggleLevelLock() {
        List<BezierBaker.Node> lane = active();
        Set<Integer> sel = lockSelection();
        List<Integer> pairs = selectedPairs(sel);
        if (!pairs.isEmpty()) {
            boolean allLevel = true;
            for (int i : pairs) allLevel &= lane.get(i).levelOut;
            pushUndoSnapshot();
            if (allLevel) {
                unlockSegments(lane, pairs, true);
            } else {
                double ref = lane.get(selIdx >= 0 ? selIdx : pairs.get(0)).val;
                for (int i : sel) {
                    BezierBaker.Node n = lane.get(i);
                    n.val = ref;
                    n.outDv = 0;
                    n.inDv = 0;
                }
                for (int i : pairs) {
                    lane.get(i).levelOut = true;
                    lane.get(i).straightOut = false;   // swap from Shift+F
                }
            }
            redraw();          // enforcement pins it right here
            app.rebuildTrack();
            return;
        }
        Set<Integer> locked = new HashSet<>();
        for (int i : sel) {
            if (i < lane.size() - 1 && lane.get(i).levelOut) locked.add(i);
            if (i > 0 && lane.get(i - 1).levelOut) locked.add(i - 1);
        }
        if (locked.isEmpty()) return;
        pushUndoSnapshot();
        unlockSegments(lane, locked, true);
        redraw();
        app.rebuildTrack();
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
        dlg.setTitle("Node " + idx + " — " + laneName(activeLane));

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
        } else if (activeSection == 0) {
            // node 0 of the FIRST section: the ride's start speed lives
            // here too (same value as the Coaster-menu spinner)
            v0Field = new TextField(String.format(Locale.US, "%.3f", app.coaster.v0));
            grid.addRow(row++, new Label("Start speed (m/s)"), v0Field);
        }
        // vertical axis is the GLOBAL value, not a delta
        valField = new TextField(String.format(Locale.US, "%.3f", n.val));
        grid.addRow(row++, new Label("Value (" + laneUnit(activeLane).trim() + ")"), valField);
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

        // section-edge nodes: the boundary return lives here too (same
        // one-time value + slope move as pressing B on the node)
        boolean edgeStart = idx == 0 && activeSection > 0;
        boolean edgeEnd = idx == lane.size() - 1 && idx > 0
            && activeSection < sections().size() - 1;
        if (edgeStart || edgeEnd) {
            Button match = new Button("Match boundary (value + slope)");
            match.setOnAction(ev -> {
                matchBoundary(idx);
                dlg.close();
            });
            grid.add(match, 0, row++, 2, 1);
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
            double lo = defMin(L), hi = defMax(L);
            for (double[] p : BezierBaker.polyline(app.lanes.get(L))) {
                if (tOff + p[0] < t0 || tOff + p[0] > t1) continue;
                lo = Math.min(lo, p[1]);
                hi = Math.max(hi, p[1]);
            }
            double eA = BezierBaker.evalAt(app.lanes.get(L), t0 - tOff);
            double eB = BezierBaker.evalAt(app.lanes.get(L), t1 - tOff);
            lo = Math.min(lo, Math.min(eA, eB));
            hi = Math.max(hi, Math.max(eA, eB));
            // expanded-handle view: include the selected node's knobs
            if (handleViewExpand && L == activeLane
                    && selIdx >= 0 && selIdx < app.lanes.get(L).size()) {
                BezierBaker.Node sn = app.lanes.get(L).get(selIdx);
                lo = Math.min(lo, Math.min(sn.val + sn.outDv, sn.val + sn.inDv));
                hi = Math.max(hi, Math.max(sn.val + sn.outDv, sn.val + sn.inDv));
            }
            if (lo < defMin(L) || hi > defMax(L)) {
                double pad = (hi - lo) * 0.05;   // "slightly beyond"
                double step = niceStep((hi - lo) / 6);
                curMin[L] = Math.floor((lo - pad) / step) * step;
                curMax[L] = Math.ceil((hi + pad) / step) * step;
            } else {
                curMin[L] = defMin(L);
                curMax[L] = defMax(L);
            }
        }
        updateResScale();
    }

    /** Fit the shared RESULTING scale (right axis) to the enabled
     *  overlay curves — over the WHOLE ride within the visible time
     *  window (v10.8: overlays span every section, fitted like a lane
     *  of the edited graph): rates default ±60 °/s, felt forces
     *  default −1..4 g, expanded 1-2-5-rounded like the lane scales.
     *  Frozen during drags with the rest of updateScale. */
    private void updateResScale() {
        resAxisOn = app.resultRates != null && app.lastSamples != null
            && app.resultRates.length > 0
            && (app.settings.resPitch() || app.settings.resYaw());
        if (!resAxisOn) return;
        boolean geo = activeGeo();
        double lo = geo ? -1 : -60, hi = geo ? 4 : 60;
        double t0 = viewStart(), t1 = viewEnd();
        List<Double> seams = sectionBoundaries();
        double rideT = app.resultRates[app.resultRates.length - 1][0];
        for (int i = 0; i < app.resultRates.length; i++) {
            double t = app.resultRates[i][0];
            if (t < t0 || t > t1) continue;
            if (t < SEAM_TRIM || t > rideT - SEAM_TRIM || nearSeam(t, seams)) continue;
            if (app.settings.resPitch()) {
                lo = Math.min(lo, resValueAt(i, 1));
                hi = Math.max(hi, resValueAt(i, 1));
            }
            if (app.settings.resYaw()) {
                lo = Math.min(lo, resValueAt(i, 2));
                hi = Math.max(hi, resValueAt(i, 2));
            }
        }
        double pad = (hi - lo) * 0.05;
        double step = niceStep((hi - lo) / 6);
        resLo = Math.floor((lo - pad) / step) * step;
        resHi = Math.ceil((hi + pad) / step) * step;
    }

    /** Interior section-boundary times (global) — the overlays skip a
     *  SEAM_TRIM band around each (bodyRates smear discontinuities). */
    private List<Double> sectionBoundaries() {
        List<Double> out = new ArrayList<>();
        List<LaneSection> secs = sections();
        double off = 0;
        for (int k = 0; k < secs.size() - 1; k++) {
            off += BezierBaker.rideEnd(secs.get(k).lanes);
            out.add(off);
        }
        return out;
    }

    private static boolean nearSeam(double t, List<Double> seams) {
        for (double s : seams) {
            if (Math.abs(t - s) < SEAM_TRIM) return true;
        }
        return false;
    }

    /** The overlay quantity at compiled sample i: resulting pitch/yaw
     *  rates over a force section, felt Gv/Gl over a geo one. */
    private double resValueAt(int i, int comp) {
        if (activeGeo()) {
            return comp == 1 ? app.lastSamples.get(i).Gv() : app.lastSamples.get(i).Gl();
        }
        return app.resultRates[i][comp];
    }

    private double resToY(double v) {
        return MARGIN_TOP + plotHeight() * (1 - (v - resLo) / (resHi - resLo));
    }

    /** Overlay accent: rates wear the geo palette, forces the force one
     *  (a curve's color names its QUANTITY, not the active mode). */
    private Color overlayColor(int comp) {
        return (activeGeo() ? FvdDesigner.LANE_COLORS
                            : FvdDesigner.GEO_LANE_COLORS)[comp - 1];
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
        enforceLocks();           // before updateScale sees the handles
        refreshOffset();          // section offsets before any t mapping
        updateScale();
        refreshMarkers();         // 3D markers follow lane + selection
        gc.setGlobalAlpha(1.0);
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.rgb(20, 22, 28));
        gc.fillRect(0, 0, w, h);

        gc.setFill(Color.rgb(28, 31, 40));
        gc.fillRect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - marginRight(), plotHeight());

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
            gc.strokeLine(MARGIN_LEFT, gy, w - marginRight(), gy);
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
        gc.rect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - marginRight(), plotHeight());
        gc.clip();

        double zy = valToY(activeLane, 0);
        gc.setStroke(Color.rgb(70, 75, 90));
        gc.setLineWidth(1);
        gc.strokeLine(MARGIN_LEFT, zy, w - marginRight(), zy);
        if (activeLane == 0 && !activeGeo()) {   // 1 g reference: force mode only
            double oy = valToY(0, 1);
            gc.setStroke(Color.rgb(60, 90, 65));
            gc.strokeLine(MARGIN_LEFT, oy, w - marginRight(), oy);
        }

        drawInactiveSections(gc);

        for (int lane = 0; lane < LANES; lane++) {
            if (lane == activeLane) continue;
            gc.setGlobalAlpha(INACTIVE_ALPHA);
            strokeLaneCurve(gc, lane);
        }
        gc.setGlobalAlpha(1.0);
        strokeLaneCurve(gc, activeLane);

        // read-only RESULTING curves (toolbar "Resulting" dropdown),
        // drawn across the WHOLE ride (v10.8 — was active-section only),
        // with the path broken over a SEAM_TRIM band at each section
        // boundary (bodyRates smear discontinuities there); no
        // zero-line fill. MODE-AWARE (v10.2): the active mode decides
        // the quantity — resulting pitch/yaw RATES over a force
        // section, felt VERTICAL/LATERAL G over a geo one. Both
        // enabled curves share THE RESULTING SCALE (v10.3) — fitted in
        // updateResScale and labeled on the right-edge axis. Colors are
        // the QUANTITY's palette (overlayColor). Roll is not offered:
        // resulting roll = the authored roll lane.
        if (resAxisOn) {
            List<Double> seams = sectionBoundaries();
            double rideT = app.resultRates[app.resultRates.length - 1][0];
            gc.setLineWidth(1);
            gc.setLineDashes(4, 4);
            gc.setGlobalAlpha(0.7);
            for (int comp = 1; comp <= 2; comp++) {
                if (comp == 1 ? !app.settings.resPitch() : !app.settings.resYaw()) continue;
                gc.setStroke(overlayColor(comp));
                gc.beginPath();
                boolean started = false;
                for (int i = 0; i < app.resultRates.length; i++) {
                    double t = app.resultRates[i][0];
                    if (t < SEAM_TRIM || t > rideT - SEAM_TRIM
                            || nearSeam(t, seams)) {
                        started = false;   // break the path across seams
                        continue;
                    }
                    double rx = tToX(t);
                    double ry = resToY(resValueAt(i, comp));
                    if (!started) { gc.moveTo(rx, ry); started = true; }
                    else gc.lineTo(rx, ry);
                }
                gc.stroke();
            }
            gc.setLineDashes((double[]) null);
            gc.setGlobalAlpha(1.0);
            gc.setLineWidth(2);
        }

        // hover readout on a resulting curve: dot + the value there, in
        // the curve's color (the lane hover's read-only sibling, v10.3);
        // Shift swaps in the numeric slope, like the lane hover (v10.4)
        if (hoverResComp != 0) {
            double hx = tToX(hoverResT);
            double hy = resToY(hoverResVal);
            gc.setGlobalAlpha(0.55);
            gc.setFill(overlayColor(hoverResComp));
            gc.fillOval(hx - 4, hy - 4, 8, 8);
            gc.setGlobalAlpha(0.9);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            String name = activeGeo()
                ? (hoverResComp == 1 ? "Gv" : "Gl")
                : (hoverResComp == 1 ? "pitch" : "yaw");
            String unit = activeGeo() ? "g" : "°/s";
            String txt;
            if (hoverSlope) {
                double dt = FvdDesigner.INTEGRATE_DT;
                int i0 = rateIdxAt(hoverResT - 2 * dt);
                int i1 = rateIdxAt(hoverResT + 2 * dt);
                double span = app.resultRates[i1][0] - app.resultRates[i0][0];
                double slope = span < 1e-9 ? 0
                    : (resValueAt(i1, hoverResComp) - resValueAt(i0, hoverResComp)) / span;
                txt = String.format("%s %.2f %s/s", name, slope, unit);
            } else {
                txt = String.format("%s %.2f %s", name, hoverResVal, unit);
            }
            gc.fillText(txt, hx + 8, hy - 6);
            gc.setGlobalAlpha(1.0);
        }

        List<BezierBaker.Node> lane = active();
        for (int i = 0; i < lane.size(); i++) {
            double px = ltToX(lane.get(i).t);
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
            gc.setFill(isDragged ? Color.WHITE : laneColor(activeLane));
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
                    ? String.format("t=%.2fs  %.2f%s", nd.t, nd.val, laneUnit(activeLane))
                    : String.format("Δt=%.2fs  %.2f%s",
                        nd.t - lane.get(i - 1).t, nd.val, laneUnit(activeLane));
                double tw = txt.length() * 6.5;   // approx width at 11 px
                double tx = FvdDesigner.clamp(px + 8, MARGIN_LEFT + 4, w - marginRight() - tw - 4);
                double ty = FvdDesigner.clamp(py - 20, MARGIN_TOP + 4, MARGIN_TOP + plotHeight() - 16);
                gc.fillText(txt, tx, ty);
            }
        }

        // insert preview: translucent white dot riding the curve, with
        // the lane's value at that point alongside
        if (!Double.isNaN(hoverT)) {
            double hv = BezierBaker.evalAt(lane, hoverT);
            double hx = ltToX(hoverT);
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
                hoverTxt = String.format("%.2f %s/s", slope, laneUnit(activeLane).trim());
            } else {
                hoverTxt = String.format("%.2f%s", hv, laneUnit(activeLane));
            }
            gc.fillText(hoverTxt, hx + 8, hy - 6);
            gc.setGlobalAlpha(1.0);
        }

        // axis-lock guide: the line the node rides along. Explicit Z/X
        // overrides the transient slide axis. Color = what the drag
        // CHANGES: vertical movement changes the VALUE (lane color);
        // horizontal movement changes TIME (light purple).
        int lock = axisLock != 0 ? axisLock
                 : (dragNodeIdx >= 0 ? shiftAxis : 0);
        if (selIdx >= 0 && lock != 0) {
            double lx = ltToX(lane.get(selIdx).t);
            double ly = valToY(activeLane, lane.get(selIdx).val);
            gc.setGlobalAlpha(0.5);
            gc.setLineWidth(1.5);
            if (lock == 1) {
                gc.setStroke(laneColor(activeLane));
                gc.strokeLine(lx, MARGIN_TOP, lx, MARGIN_TOP + plotHeight());
            } else {
                gc.setStroke(FvdDesigner.TIME_COLOR);
                gc.strokeLine(MARGIN_LEFT, ly, w - marginRight(), ly);
            }
            gc.setGlobalAlpha(1.0);
        }

        // POV camera position on the track (toggle in Edit > Options)
        if (app.viewport.povActive && app.settings.povMarker()) {
            double px2 = tToX(app.viewport.povTime);
            if (px2 >= MARGIN_LEFT - 1 && px2 <= w - marginRight() + 1) {
                gc.setGlobalAlpha(0.4);
                gc.setStroke(FvdDesigner.TIME_COLOR);   // purple = time
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

        drawAddButton(gc, w);
        drawSectionBadges(gc, w);

        gc.restore();   // end plot clip

        gc.setGlobalAlpha(1.0);
        gc.setFont(Font.font(11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(laneColor(activeLane));
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
        gc.fillText(laneName(activeLane), 0, 0);
        gc.restore();

        // RIGHT axis for the resulting overlays (v10.3): their shared
        // scale's ticks down the right edge (its own nice steps — they
        // deliberately don't chase the active lane's gridlines) + a
        // rotated title naming the quantity/unit
        if (resAxisOn) {
            double rx = w - marginRight() + 4;
            double rStep = niceStep((resHi - resLo) / 5);
            gc.setFill(Color.rgb(150, 150, 168));
            gc.setFont(Font.font(9));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            for (double v = Math.ceil(resLo / rStep) * rStep; v <= resHi + 1e-9; v += rStep) {
                gc.fillText(fmtVal(v), rx, resToY(v));
            }
            gc.save();
            gc.translate(w - 6, MARGIN_TOP + plotHeight() / 2);
            gc.rotate(90);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(activeGeo() ? "Resulting G (g)" : "Resulting rate (°/s)", 0, 0);
            gc.restore();
        }

        gc.setFill(Color.rgb(130, 135, 150));
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM);
        String tickFmt = gridTStep < 0.1 ? "%.2f" : gridTStep < 1 ? "%.1f" : "%.0f";
        for (double t = Math.ceil(vt0 / gridTStep) * gridTStep; t <= vt1 + 1e-9; t += gridTStep) {
            gc.fillText(String.format(tickFmt, t), tToX(t), h - 16);
        }
        gc.setFont(Font.font(11));
        gc.setFill(FvdDesigner.TIME_COLOR);   // purple = time, everywhere in the UI
        gc.fillText("Time (s)", MARGIN_LEFT + plotWidth() / 2, h - 2);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    private void strokeLaneCurve(GraphicsContext gc, int laneIdx) {
        List<BezierBaker.Node> lane = app.lanes.get(laneIdx);
        // zero-line fill first (from the baked polyline + hold extension;
        // the true bezier stroke sits on top of its edge)
        List<double[]> poly = BezierBaker.polyline(lane);
        List<double[]> pts = new ArrayList<>(poly.size() + 1);
        for (double[] p : poly) {
            pts.add(new double[]{ ltToX(p[0]), valToY(laneIdx, p[1]) });
        }
        double[] lastP = poly.get(poly.size() - 1);
        if (lastP[0] < sectionEnd() - 1e-9) {
            pts.add(new double[]{ ltToX(sectionEnd()), valToY(laneIdx, lastP[1]) });
        }
        fillUnderSeries(gc, pts, laneIdx);

        gc.setStroke(laneColor(laneIdx));
        gc.setLineWidth(2);
        gc.beginPath();
        gc.moveTo(ltToX(lane.get(0).t), valToY(laneIdx, lane.get(0).val));
        for (int i = 0; i < lane.size() - 1; i++) {
            BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
            if (a.lockedOut()) {   // drawn separately as twin lines
                gc.moveTo(ltToX(b.t), valToY(laneIdx, b.val));
                continue;
            }
            double c1x = ltToX(a.t + BezierBaker.effOutDt(a, b));
            double c1y = valToY(laneIdx, a.val + a.outDv);
            double c2x = ltToX(b.t + BezierBaker.effInDt(a, b));
            double c2y = valToY(laneIdx, b.val + b.inDv);
            gc.bezierCurveTo(c1x, c1y, c2x, c2y, ltToX(b.t), valToY(laneIdx, b.val));
        }
        gc.stroke();

        // locked segments: TWO skinny parallel lines offset along the
        // chord normal — the "this is pinned" look. LEVEL locks (v10.5)
        // additionally tint the band between the lines, telling the two
        // locks apart at a glance.
        gc.setLineWidth(1);
        for (int i = 0; i < lane.size() - 1; i++) {
            if (!lane.get(i).lockedOut()) continue;
            BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
            double ax = ltToX(a.t), ay = valToY(laneIdx, a.val);
            double bx = ltToX(b.t), by = valToY(laneIdx, b.val);
            double dx = bx - ax, dy = by - ay;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) continue;
            double ox = -dy / len * 1.6, oy = dx / len * 1.6;
            if (lane.get(i).levelOut) {   // level segments are horizontal
                gc.setFill(laneColor(laneIdx).deriveColor(0, 1, 1, 0.35));
                gc.fillRect(Math.min(ax, bx), ay - Math.abs(oy),
                            Math.abs(bx - ax), Math.abs(oy) * 2);
            }
            gc.strokeLine(ax + ox, ay + oy, bx + ox, by + oy);
            gc.strokeLine(ax - ox, ay - oy, bx - ox, by - oy);
        }
        gc.setLineWidth(2);

        // a lane that ends early holds its final value to the ride end:
        // drawn DOTTED to show it's implied, not authored (clickable to
        // insert a real node on it)
        double end = sectionEnd();
        BezierBaker.Node last = lane.get(lane.size() - 1);
        if (last.t < end - 1e-9) {
            double ly = valToY(laneIdx, last.val);
            gc.setLineDashes(3, 7);
            gc.strokeLine(ltToX(last.t), ly, ltToX(end), ly);
            gc.setLineDashes((double[]) null);
        }
    }

    private void drawHandles(GraphicsContext gc, int idx) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        double nx = ltToX(n.t);
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
            boolean offscreen = k[0] < MARGIN_LEFT || k[0] > canvas.getWidth() - marginRight()
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
        double L = MARGIN_LEFT, R = canvas.getWidth() - marginRight();
        double T = MARGIN_TOP, B = MARGIN_TOP + plotHeight();
        if (x1 < L) t = Math.min(t, (L - x0) / (x1 - x0));
        if (x1 > R) t = Math.min(t, (R - x0) / (x1 - x0));
        if (y1 < T) t = Math.min(t, (T - y0) / (y1 - y0));
        if (y1 > B) t = Math.min(t, (B - y0) / (y1 - y0));
        return new double[]{ x0 + (x1 - x0) * t, y0 + (y1 - y0) * t };
    }
}
