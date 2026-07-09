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

    // ---------- pieces (v12.0, D70) ----------
    // the graph shows ONE piece's sections at a time (SECTIONS-DESIGN
    // §8); the toolbar selector / 3D Ctrl+click switch pieces. All
    // compiled lookups stay in GLOBAL ride time (pieces concatenate on
    // the timeline), so the active piece's strips live in the window
    // [pieceT0, pieceT0 + pieceDur] and everything derived just works.
    private int activePiece = 0;

    int activePiece() { return activePiece; }

    private List<Section> sections() {
        return app.coaster.pieces.get(activePiece).sections;
    }

    /** Flattened span index of piece p's first section. */
    private int pieceBase(int p) {
        int n = 0;
        for (int q = 0; q < p && q < app.coaster.pieces.size(); q++) {
            n += app.coaster.pieces.get(q).sections.size();
        }
        return n;
    }

    /** Flattened span index of the active piece's first section. */
    private int spanBase() {
        return pieceBase(activePiece);
    }

    /** Span of the active piece's section k, or null (pre-compile /
     *  stale). Structural edits rebuild BEFORE relayout, so live use
     *  always sees fresh spans. k may reach past the piece (negative /
     *  ≥ size): the flattened index lands on the neighboring piece's
     *  spans — cross-piece boundary matching inside a run rides on
     *  this. */
    private TrackCompiler.Span spanOf(int k) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        int idx = spanBase() + k;
        return spans != null && idx >= 0 && idx < spans.size()
            ? spans.get(idx) : null;
    }

    /** Span of piece p's section k, or null. */
    private TrackCompiler.Span spanAt(int p, int k) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        if (spans == null) return null;
        int idx = pieceBase(p) + k;
        return idx >= 0 && idx < spans.size() ? spans.get(idx) : null;
    }

    /** Global ride time where the active piece begins. */
    private double pieceT0() {
        if (activePiece == 0) return 0;
        TrackCompiler.Span sp = spanOf(0);
        return sp != null ? sp.t0() : 0;
    }

    // ---------- bridged RUNS (v13.4) ----------
    // pieces chained by trailing bridges read and edit as ONE track:
    // the graph shows every section of the run containing the active
    // piece — strips, badges, boundary matching and the '+' all span
    // it. A single unbridged piece is a run of one (bit-identical to
    // the old behavior).

    private boolean endsWithBridge(int p) {
        List<Section> s = app.coaster.pieces.get(p).sections;
        return !s.isEmpty() && s.get(s.size() - 1) instanceof BridgeSection;
    }

    /** First piece of the bridged run containing the active piece. */
    private int runStart() {
        int p = activePiece;
        while (p > 0 && endsWithBridge(p - 1)) p--;
        return p;
    }

    /** Last piece of the bridged run containing the active piece. */
    private int runEnd() {
        int p = activePiece;
        while (p < app.coaster.pieces.size() - 1 && endsWithBridge(p)) p++;
        return p;
    }

    /** Run bounds for the viewport (v13.4): the WHOLE bridged run
     *  renders full-color — it reads as one track. */
    int runStartPiece() { return runStart(); }

    int runEndPiece() { return runEnd(); }

    /** Duration of piece p's section k (the sectionDur recipe, any
     *  piece). */
    private double durOf(int p, int k) {
        Section s = app.coaster.pieces.get(p).sections.get(k);
        if (s instanceof LaneSection ls) return BezierBaker.rideEnd(ls.lanes);
        TrackCompiler.Span sp = spanAt(p, k);
        if (sp != null) return sp.t1() - sp.t0();
        if (s instanceof StraightCurveSection sc) return sc.estDuration();
        BridgeSection bs = (BridgeSection) s;   // pre-compile estimate
        return Math.max(0.1,
            bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length()
                / Math.max(0.5, bs.vDesign));
    }

    /** Global ride time where the RUN begins. */
    private double runT0() {
        int rs = runStart();
        if (rs == 0) return 0;
        TrackCompiler.Span sp = spanAt(rs, 0);
        return sp != null ? sp.t0() : 0;
    }

    /** Total duration of the run (the graph window's width basis). */
    private double runDur() {
        double d = 0;
        for (int p = runStart(); p <= runEnd(); p++) {
            for (int k = 0; k < app.coaster.pieces.get(p).sections.size(); k++) {
                d += durOf(p, k);
            }
        }
        return d;
    }

    /** One strip of the run's flattened layout. */
    private record RunSec(int piece, int section, Section sec,
                          double t0, double dur) {}

    /** The run's sections in timeline order with their global starts —
     *  the strip/badge/hit layer iterates this. */
    private List<RunSec> runLayout() {
        List<RunSec> out = new ArrayList<>();
        double off = runT0();
        for (int p = runStart(); p <= runEnd(); p++) {
            List<Section> secs = app.coaster.pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++) {
                double d = durOf(p, k);
                out.add(new RunSec(p, k, secs.get(k), off, d));
                off += d;
            }
        }
        return out;
    }

    /** The run strip under global time t, or null. */
    private RunSec runSecAt(double globalT) {
        for (RunSec rs : runLayout()) {
            if (globalT >= rs.t0() - 1e-9
                    && globalT <= rs.t0() + rs.dur() + 1e-9) return rs;
        }
        return null;
    }

    /** Activate a section anywhere in the CURRENT run without resetting
     *  the zoom (the run is one continuous editing surface); falls back
     *  to the full piece switch for out-of-run targets. */
    private void activateRunSec(int p, int k) {
        if (p == activePiece) {
            setActiveSection(k);
            return;
        }
        if (p < runStart() || p > runEnd()) {
            setActivePiece(p);
            setActiveSection(k);
            return;
        }
        activePiece = p;
        activeSection = (int) FvdDesigner.clamp(k, 0, sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        canvas.setCursor(Cursor.DEFAULT);
        syncLaneButtons();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        app.rebuildTrack();   // inactive-piece desaturation follows
        refreshOffset();
        redraw();
    }

    void setActivePiece(int p) {
        p = (int) FvdDesigner.clamp(p, 0, app.coaster.pieces.size() - 1);
        if (p == activePiece) return;
        activePiece = p;
        activeSection = 0;
        app.lanes = lanesFor(sections().get(0));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        canvas.setCursor(Cursor.DEFAULT);
        viewT0 = 0;
        viewT1 = -1;          // auto-fit the new piece's stretch
        syncLaneButtons();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        app.rebuildTrack();   // inactive pieces desaturate (v13.2)
        refreshOffset();
        redraw();
    }

    // ---------- section MODE (Phase 2: force / geo lanes, s/c elements) ----------
    // one editor edits both lane-based modes; only the lane SEMANTICS
    // switch — names, units, default scales, snap steps, lane buttons.
    // A STRAIGHT/CURVE section (v11.0) has no lanes at all: app.lanes
    // points at NO_LANES (every node path naturally empty) and the strip
    // shows the ELEMENT WIDGET instead; the plot stays in FORCE view
    // (activeGeo() false), so other strips and overlays read as felt G.

    /** Shared empty lanes for the no-lane modes: hit tests, markers and
     *  lock enforcement all iterate to nothing. */
    private static final List<List<BezierBaker.Node>> NO_LANES = List.of(
        List.of(), List.of(), List.of());

    private List<List<BezierBaker.Node>> lanesFor(Section s) {
        return s instanceof LaneSection ls ? ls.lanes : NO_LANES;
    }

    /** Is the ACTIVE section a geo (body-rate) section? */
    private boolean activeGeo() {
        List<Section> secs = sections();
        return activeSection < secs.size() && secs.get(activeSection) instanceof GeoSection;
    }

    /** The active STRAIGHT/CURVE section, or null (v11.0). */
    private StraightCurveSection activeSc() {
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof StraightCurveSection sc ? sc : null;
    }

    /** The active BRIDGE section, or null (v13.0) — like s/c it has no
     *  lanes; unlike s/c it has no widget either (frozen geometry): the
     *  strip shows the derived curves and the badge menu does the rest. */
    private BridgeSection activeBridge() {
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof BridgeSection b ? b : null;
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

    /** Lane buttons show the active mode's lane names + colors (mode 2
     *  = the no-lane modes: straight/curve AND bridge). The bridge GAP
     *  spinner's visibility rides along (v13.2) — this runs on every
     *  section/piece switch, load, add and delete. */
    private void syncLaneButtons() {
        app.setLaneButtonMode(activeGeo() ? 1
            : activeSc() != null || activeBridge() != null ? 2 : 0);
        app.updateBridgeControls();
    }

    int activeSection() { return activeSection; }

    double activeSectionStart() { refreshOffset(); return tOff; }

    /** A lane section's duration is its authored ride end; a DISTANCE-
     *  domain section's duration exists only after compilation — read
     *  it from the last compile's span (estimate before the first). */
    private double sectionDur(int k) {
        return durOf(activePiece, k);
    }

    private void refreshOffset() {
        double o = pieceT0();
        for (int k = 0; k < activeSection && k < sections().size(); k++) {
            o += sectionDur(k);
        }
        tOff = o;
    }

    /** End of the ACTIVE section in its local time — its own ride end
     *  (the old single-section maxT() semantics, now per section). */
    private double sectionEnd() {
        return activeSc() != null
            ? sectionDur(activeSection) : BezierBaker.rideEnd(app.lanes);
    }

    private double ltToX(double localT) { return tToX(tOff + localT); }

    private double xToLt(double x) { return xToT(x) - tOff; }

    void setActiveSection(int k) {
        List<Section> secs = sections();
        k = (int) FvdDesigner.clamp(k, 0, secs.size() - 1);
        if (k == activeSection && app.lanes == lanesFor(secs.get(k))) return;
        activeSection = k;
        app.lanes = lanesFor(secs.get(k));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        canvas.setCursor(Cursor.DEFAULT);
        syncLaneButtons();
        refreshOffset();
        redraw();
    }

    /** After load/new: the piece/section lists were replaced wholesale. */
    void resetSections() {
        activePiece = 0;
        activeSection = 0;
        app.lanes = lanesFor(sections().get(0));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;
        syncLaneButtons();
        refreshOffset();
    }

    /** Undo/redo restore: re-point at the snapshot's piece + section;
     *  the caller (applySnapshot) fixes selection against the lanes. */
    void restoreActive(int piece, int section) {
        activePiece = (int) FvdDesigner.clamp(piece, 0,
            app.coaster.pieces.size() - 1);
        List<Section> secs = sections();
        activeSection = (int) FvdDesigner.clamp(section, 0, secs.size() - 1);
        app.lanes = lanesFor(secs.get(activeSection));
        StraightCurveSection sc = activeSc();
        elSel = sc == null ? -1
            : Math.min(elSel, sc.elements.size() - 1);
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

    /** The '+' menu: force, geo and straight/curve sections (Phase 2),
     *  attaching a floating piece (v12.0), and BRIDGING to the next
     *  piece (v13.0). A piece already ending with a bridge cannot be
     *  extended — its end is glued to the next piece's entry. */
    private void showAddSectionMenu(double screenX, double screenY) {
        ContextMenu cm = new ContextMenu();
        boolean endsBridge = !sections().isEmpty()
            && sections().get(sections().size() - 1) instanceof BridgeSection;
        MenuItem force = new MenuItem("Add force section (1 s hold)");
        force.setOnAction(ev -> addSection(0));
        MenuItem geo = new MenuItem("Add geo section (1 s hold)");
        geo.setOnAction(ev -> addSection(1));
        MenuItem sc = new MenuItem("Add straight/curve section (10 m straight)");
        sc.setOnAction(ev -> addSection(2));
        force.setDisable(endsBridge);
        geo.setDisable(endsBridge);
        sc.setDisable(endsBridge);
        cm.getItems().addAll(force, geo, sc);
        List<TrackPiece> pieces = app.coaster.pieces;
        // pieces already INSIDE this bridged run are not bridge/attach
        // targets (v13.4): they're connected — reordering or dissolving
        // one would tear the chain apart
        int rs = runStart(), re = runEnd();
        boolean hasOutside = pieces.size() > re - rs + 1;
        if (endsBridge) {
            MenuItem hint = new MenuItem("Piece ends with a bridge — extend the NEXT piece");
            hint.setDisable(true);
            cm.getItems().add(hint);
        } else if (hasOutside) {
            // ANY outside piece (v13.1): the chosen one reorders to come
            // next on the timeline — connecting MEANS "this one follows"
            Menu bridge = new Menu("Bridge to piece");
            for (int p = 0; p < pieces.size(); p++) {
                if (p >= rs && p <= re) continue;
                TrackPiece tp = pieces.get(p);
                MenuItem it = new MenuItem(String.format(Locale.US,
                    "Piece %d — %d section%s%s", p + 1, tp.sections.size(),
                    tp.sections.size() == 1 ? "" : "s",
                    p == activePiece + 1 ? " (next)" : ""));
                final int idx = p;
                it.setOnAction(ev -> addBridgeTo(idx));
                bridge.getItems().add(it);
            }
            cm.getItems().add(bridge);
        } else {
            MenuItem bridge = new MenuItem("Bridge — needs another piece");
            bridge.setDisable(true);
            cm.getItems().add(bridge);
        }
        if (!hasOutside) {
            MenuItem none = new MenuItem("Attach floating piece — none to attach");
            none.setDisable(true);
            cm.getItems().add(none);
        } else {
            // attaching = the floating piece's sections CONTINUE this
            // piece's chain (its own placement is discarded — §0 keeps
            // every section's local shape)
            Menu attach = new Menu("Attach floating piece");
            attach.setDisable(endsBridge);
            for (int p = 0; p < pieces.size(); p++) {
                if (p >= rs && p <= re) continue;
                TrackPiece tp = pieces.get(p);
                MenuItem it = new MenuItem(String.format(Locale.US,
                    "Piece %d — %d section%s", p + 1, tp.sections.size(),
                    tp.sections.size() == 1 ? "" : "s"));
                final int idx = p;
                it.setOnAction(ev -> attachFloating(idx));
                attach.getItems().add(it);
            }
            cm.getItems().add(attach);
        }
        showMenu(cm, screenX, screenY);
    }

    /** Append a live-following bridge from the ACTIVE piece's end to
     *  piece `target`'s entry (v13.0 D72, any-piece + live v13.1 D73).
     *  A non-adjacent target REORDERS to come right after this piece —
     *  the piece order is the ride timeline, and connecting means
     *  "this one follows". One undo step. */
    private void addBridgeTo(int target) {
        List<TrackPiece> pieces = app.coaster.pieces;
        if (target == activePiece || target >= pieces.size()) return;
        TrackCompiler.Span endSp = spanOf(sections().size() - 1);
        if (endSp == null) return;
        double vEnd = app.lastSamples != null
            && endSp.i1() < app.lastSamples.size()
            ? app.lastSamples.get(endSp.i1()).v() : sections()
                .get(sections().size() - 1).vDesign();
        pushUndoSnapshot();
        TrackPose end = endSp.exit();   // capture BEFORE the reorder
        TrackPiece tp = pieces.get(target);
        if (target != activePiece + 1) {
            if (target < activePiece) activePiece--;   // list shifts
            app.coaster.movePiece(pieces.indexOf(tp), activePiece + 1);
        }
        BridgeSection br = BridgeSection.solve(end, tp.entryPose(), vEnd);
        // a FRESH bridge moves nothing, so its placement mode says so
        // (v13.7 user rule — the dropdown used to claim "optimize
        // position and yaw" over a deliberately unoptimized track);
        // switching to an optimize mode stays the explicit action and
        // applies immediately. Old files keep the documented
        // missing-field default (OPT_POS_YAW).
        br.optMode = BridgeSection.OPT_NONE;
        sections().add(br);
        activeSection = sections().size() - 1;
        app.lanes = lanesFor(br);
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;          // auto-fit — same rule as addSection
        syncLaneButtons();
        app.rebuildTrack();   // the compile live-captures poses + G² acc
        app.updatePieceButton();
        refreshOffset();
        redraw();
    }

    /** CONVERT section k of the active piece into an editable force or
     *  geo section re-authoring its compiled shape (v13.7, D81 — the
     *  v13.5 bridge "unfreeze" unified: force↔geo, s/c→either,
     *  bridge→either, one feature, one engine). A bridge converts its
     *  solved geometry — the piece then no longer ends with a bridge,
     *  so the run splits and the next piece keeps its placement (the
     *  seam keeps only the fit residual, TESTING #20/#23). Other
     *  sections re-run their own passG locally from their entry pose.
     *  Reversed sections refuse (their lanes author the opposite
     *  direction — same round-1 rule as §5.3). One undo step returns
     *  the original. */
    private void convertSection(int k, boolean toGeo) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        Section sec = secs.get(k);
        List<FVDIntegrator.Sample> local;
        if (sec instanceof BridgeSection bs) {
            local = bs.integrate(FvdDesigner.INTEGRATE_DT).samples();
        } else {
            if (sec.reversed()) return;
            TrackPose entry = k == 0
                ? app.coaster.pieces.get(activePiece).entryPose()
                : spanOf(k - 1) != null ? spanOf(k - 1).exit() : null;
            if (entry == null) return;
            local = TrackCompiler.passG(sec, entry,
                FvdDesigner.INTEGRATE_DT).samples();
        }
        if (local.size() < 5) return;   // too short to measure rates
        pushUndoSnapshot();
        LaneSection conv = Coaster.convertToLanes(local, sec.vDesign(),
            toGeo, FvdDesigner.INTEGRATE_DT);
        secs.set(k, conv);
        activeSection = k;
        app.lanes = lanesFor(conv);
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        syncLaneButtons();
        app.rebuildTrack();
        app.updatePieceButton();
        refreshOffset();
        redraw();
    }

    /** The bridge lost its target piece (deleted/attached): it stays
     *  frozen at its last capture — the badge's warning state. */
    private boolean bridgeOrphan(BridgeSection bs) {
        return activePiece + 1 >= app.coaster.pieces.size();
    }

    /** The toolbar GAP spinner shows for an active, non-orphaned
     *  bridge (v13.2). */
    boolean activeBridgeWithTarget() {
        BridgeSection bs = activeBridge();
        return bs != null && !bridgeOrphan(bs);
    }

    /** The active bridge's LENGTH along the track (the spinner value,
     *  v13.4 — was the straight-line chord): the compiled span's arc
     *  extent, so a bridge that curves around a turn reads its real
     *  length; chord fallback before the first compile. */
    double activeBridgeGap() {
        BridgeSection bs = activeBridge();
        if (bs == null || bridgeOrphan(bs)) return 0;
        TrackCompiler.Span sp = spanOf(activeSection);
        if (sp != null && sp.sLen() > 1e-6) return sp.sLen();
        return app.coaster.pieces.get(activePiece + 1).entryPose().pos()
            .sub(bs.solvedEntry.pos()).length();
    }

    private long lastGapNanos = 0;   // coalesces spinner-arrow undo steps

    /** The length spinner's write path (v13.2): every change RE-RUNS
     *  the placement optimization at the requested length — the target
     *  piece slides along the seams' natural continuation (v13.4:
     *  around a split turn's own circle) and the live bridge follows.
     *  Arrow bursts coalesce into one undo step. */
    void setBridgeGap(double dist) {
        BridgeSection bs = activeBridge();
        if (bs == null || bridgeOrphan(bs) || dist <= 0
                || bs.optMode == BridgeSection.OPT_NONE
                || bs.optLen) return;   // auto length owns the spinner
        long now = System.nanoTime();
        if (now - lastGapNanos > 800_000_000L) pushUndoSnapshot();
        lastGapNanos = now;
        applyBridgeGap(bs, dist);
    }

    /** Re-run the placement optimization at length `dist` (no undo
     *  push — the spinner and the end-line drag each manage their
     *  own). v13.8 (D82): the tangent lengths come from
     *  Coaster.optimizeBridge's FIXED grid — deterministic per
     *  (geometry, dist). The old multiplicative rescale
     *  (lens ×= requested/realized arc) compounded a non-inverting
     *  ratio: every length round trip drifted the sweep a little and
     *  the shape got rougher — the user's "different each time". */
    private void applyBridgeGap(BridgeSection bs, double dist) {
        app.coaster.optimizeBridge(bs, activePiece + 1, dist,
            bs.optMode == BridgeSection.OPT_POS_YAW,
            app.settings.bridgeSweep());
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    boolean bridgeOptLen() {
        BridgeSection bs = activeBridge();
        return bs != null && bs.optLen;
    }

    /** The "Optimize length" check (v13.8, D82): on = the length is
     *  Coaster.optimalBridgeLength's knee — the spinner and end-line
     *  drag disable; off = the length is the user's again. */
    void setBridgeOptLen(boolean on) {
        BridgeSection bs = activeBridge();
        if (bs == null || bs.optLen == on) return;
        pushUndoSnapshot();
        bs.optLen = on;
        app.markDirty();
        if (on && !bridgeOrphan(bs)
                && bs.optMode != BridgeSection.OPT_NONE) {
            applyOptimalLength(bs);
        } else {
            redraw();
        }
        app.updateBridgeControls();
    }

    /** Apply the optimal length. The search bottoming out at its 1 m
     *  floor means the seams continue each other smoothly and ZERO is
     *  the natural optimum (v13.8 second round — purely the
     *  calculation's verdict, wherever the piece currently sits, not
     *  a proximity check): offer the direct attach; "No" keeps
     *  everything as it is with the length check off. */
    private void applyOptimalLength(BridgeSection bs) {
        double dOpt = app.coaster.optimalBridgeLength(bs,
            bs.optMode == BridgeSection.OPT_POS_YAW,
            app.settings.bridgeKneeTol(), app.settings.bridgeSlopeW(),
            app.settings.bridgeSweep());
        if (dOpt <= 1 + 1e-9) {
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "This would create a zero-distance bridge section.\n"
                + "Connect the pieces directly instead?",
                javafx.scene.control.ButtonType.YES,
                javafx.scene.control.ButtonType.NO);
            a.setHeaderText(null);
            java.util.Optional<javafx.scene.control.ButtonType> r =
                a.showAndWait();
            if (r.isPresent() && r.get() == javafx.scene.control.ButtonType.YES) {
                attachThroughBridge();
                return;
            }
            bs.optLen = false;
            app.updateBridgeControls();
            redraw();
            return;
        }
        applyBridgeGap(bs, dOpt);
    }

    /** Replace the active piece's trailing bridge with a DIRECT attach
     *  of the next piece (the zero-distance answer): the bridge goes,
     *  the next piece's sections continue this chain. */
    private void attachThroughBridge() {
        List<Section> secs = sections();
        int k = secs.size() - 1;
        if (!(secs.get(k) instanceof BridgeSection)
                || activePiece + 1 >= app.coaster.pieces.size()) return;
        secs.remove(k);
        app.coaster.attachPiece(activePiece, activePiece + 1);
        activeSection = Math.min(k, sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        syncLaneButtons();
        app.rebuildTrack();
        app.updatePieceButton();
        refreshOffset();
        redraw();
    }

    // ---------- bridge placement modes (v13.5) ----------
    // the Placement dropdown edits the NEXT piece through the bridge:
    // mode 0 = length re-runs the full continuation march, 1 = the
    // march places position only (the user owns yaw), 2 = fully
    // manual x/y/z + yaw. Spinner bursts coalesce into one undo step
    // via the shared lastGapNanos clock.

    int bridgeOptMode() {
        BridgeSection bs = activeBridge();
        return bs == null ? BridgeSection.OPT_POS_YAW : bs.optMode;
    }

    void setBridgeOptMode(int mode) {
        BridgeSection bs = activeBridge();
        if (bs == null || bs.optMode == mode) return;
        pushUndoSnapshot();
        bs.optMode = mode;
        app.markDirty();
        if (mode != BridgeSection.OPT_NONE && !bridgeOrphan(bs)) {
            // switching INTO an optimize mode applies it right away —
            // at the OPTIMAL length (v13.8, user spec: the switch
            // itself should land at the optimal distance); the length
            // check turns on with it
            bs.optLen = true;
            applyOptimalLength(bs);
        } else {
            redraw();
        }
        app.updateBridgeControls();
    }

    double bridgeTargetYaw() {
        return activeBridgeWithTarget()
            ? app.coaster.pieces.get(activePiece + 1).yawDeg : 0;
    }

    void setBridgeTargetYaw(double deg) {
        BridgeSection bs = activeBridge();
        if (bs == null || bridgeOrphan(bs)
                || bs.optMode == BridgeSection.OPT_POS_YAW) return;
        long now = System.nanoTime();
        if (now - lastGapNanos > 800_000_000L) pushUndoSnapshot();
        lastGapNanos = now;
        app.coaster.pieces.get(activePiece + 1).yawDeg = deg;
        if (bs.optMode == BridgeSection.OPT_POS) {
            if (bs.optLen) applyOptimalLength(bs);
            else applyBridgeGap(bs, Math.max(1, activeBridgeGap()));
        } else {
            app.rebuildTrack();
            refreshOffset();
            redraw();
        }
    }

    double bridgeTargetPos(int axis) {
        if (!activeBridgeWithTarget()) return 0;
        TrackPiece t = app.coaster.pieces.get(activePiece + 1);
        return axis == 0 ? t.x : axis == 1 ? t.y : t.z;
    }

    void setBridgeTargetPos(int axis, double v) {
        BridgeSection bs = activeBridge();
        if (bs == null || bridgeOrphan(bs)
                || bs.optMode != BridgeSection.OPT_NONE) return;
        long now = System.nanoTime();
        if (now - lastGapNanos > 800_000_000L) pushUndoSnapshot();
        lastGapNanos = now;
        TrackPiece t = app.coaster.pieces.get(activePiece + 1);
        if (axis == 0) t.x = v;
        else if (axis == 1) t.y = v;
        else t.z = v;
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** Is (x, y) on the ACTIVE bridge's end boundary line (the piece
     *  end)? Grab range mirrors the s/c separators; only a bridge with
     *  a live target resizes (v13.3 — the draggable gap). */
    private boolean hitBridgeEnd(double x, double y) {
        if (!activeBridgeWithTarget()
                || activeBridge().optMode == BridgeSection.OPT_NONE
                || activeBridge().optLen) {
            return false;   // manual mode / auto length: nothing to drag
        }
        if (y < MARGIN_TOP || y > MARGIN_TOP + plotHeight()) return false;
        return Math.abs(x - tToX(tOff + sectionDur(activeSection))) <= 5;
    }

    /** The running bridge end-line drag (v13.3): the cursor's fraction
     *  of the current strip maps to a new GAP distance and re-runs the
     *  same placement optimization as the toolbar spinner — the next
     *  piece slides along the chain end's tangent, the live bridge
     *  follows, and the recompile re-maps the cursor each event (the
     *  s/c boundary-drag pattern). Ctrl snaps the gap by the Options
     *  element-length step. */
    private void bridgeDragTo(double x, boolean snap) {
        BridgeSection bs = activeBridge();
        if (bs == null || bridgeOrphan(bs)) return;
        double dur = Math.max(sectionDur(activeSection), 1e-6);
        double frac = Math.max(0.05, (xToT(x) - tOff) / dur);
        double gap = activeBridgeGap() * frac;
        if (snap) {
            double step = app.settings.snapLen();
            gap = Math.round(gap / step) * step;
        }
        gap = FvdDesigner.clamp(gap, 1, 1000);   // the spinner's range
        applyBridgeGap(bs, gap);
    }

    /** The bridge's tangent-length dialog ("how straight it leaves /
     *  arrives"; bigger = wider sweep). One undo step on Apply. */
    private void showBridgeDialog(int idx) {
        if (!(sections().get(idx) instanceof BridgeSection bs)) return;
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Bridge tangents — section " + (idx + 1));
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        TextField aF = new TextField(String.format(Locale.US, "%.2f", bs.lenA));
        TextField bF = new TextField(String.format(Locale.US, "%.2f", bs.lenB));
        aF.setPrefColumnCount(7);
        bF.setPrefColumnCount(7);
        grid.addRow(0, new Label("Entry tangent (m)"), aF);
        grid.addRow(1, new Label("Exit tangent (m)"), bF);
        Button ok = new Button("Apply");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, 2, 2, 1);
        ok.setOnAction(ev -> {
            pushUndoSnapshot();
            bs.lenA = Math.max(0.1, parseOr(aF, bs.lenA));
            bs.lenB = Math.max(0.1, parseOr(bF, bs.lenB));
            app.rebuildTrack();
            refreshOffset();
            redraw();
            dlg.close();
        });
        cancel.setOnAction(ev -> dlg.close());
        dlg.setScene(new Scene(grid));
        dlg.show();
    }

    /** Append floating piece p's sections to the ACTIVE piece (v12.0):
     *  they re-chain from its exit pose on the rebuild; the emptied
     *  piece dissolves. One undo step. */
    private void attachFloating(int p) {
        pushUndoSnapshot();
        app.coaster.attachPiece(activePiece, p);
        if (p < activePiece) activePiece--;   // list shifted under us
        app.rebuildTrack();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
    }

    /** Split the ACTIVE piece before section k (v12.0; exact since
     *  v12.1): sections k.. move to a NEW floating piece placed at the
     *  compiled boundary POSE — position and FULL orientation (into
     *  baseOrient), so the track does not move AT ALL, whatever the
     *  boundary's pitch or bank (user rule, gated bitwise). If the
     *  section being edited moved, the editor follows it. One undo
     *  step. */
    private void splitAt(int k) {
        if (k <= 0 || k >= sections().size()) return;
        TrackCompiler.Span sp = spanOf(k - 1);
        if (sp == null) return;
        pushUndoSnapshot();
        int np = app.coaster.splitPiece(activePiece, k, sp.exit());
        if (np >= 0 && activeSection >= k) {
            activePiece = np;         // follow the section being edited
            activeSection -= k;
        }
        activeSection = (int) FvdDesigner.clamp(activeSection, 0,
            sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        syncLaneButtons();
        app.rebuildTrack();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
    }

    /** Reverse the ACTIVE piece (v12.1, D71 — the Coaster menu item):
     *  the model transformation lives in Coaster.reversePiece (train
     *  order + toggled section flags + placement re-anchored at the
     *  old exit); the editor follows the section it was editing to its
     *  new list position. One undo step. */
    void reverseActivePiece() {
        for (Section s : sections()) {
            if (s instanceof BridgeSection) {
                // a bridge's poses are world-anchored A→B; running the
                // piece backward through it has no defined meaning
                app.statsLabel.setText("Reversing a piece with a bridge"
                    + " isn't supported — delete the bridge first.");
                return;
            }
        }
        TrackCompiler.Span last = spanOf(sections().size() - 1);
        if (last == null) return;
        pushUndoSnapshot();
        app.coaster.reversePiece(activePiece, last.exit());
        activeSection = sections().size() - 1 - activeSection;
        activeSection = (int) FvdDesigner.clamp(activeSection, 0,
            sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        syncLaneButtons();
        app.rebuildTrack();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
    }

    private static String modeName(Section s) {
        return s instanceof GeoSection ? "Geo"
             : s instanceof StraightCurveSection ? "Straight/curve"
             : s instanceof BridgeSection ? "Bridge" : "Force";
    }

    /** Badge press anywhere in the run: a badge on ANOTHER piece's
     *  strip activates that section first (its menu actions — split,
     *  delete, bridge tools — operate on the active piece), then the
     *  ordinary per-section menu opens (v13.4). */
    private void showRunBadgeMenu(int runIdx, double screenX, double screenY) {
        List<RunSec> rl = runLayout();
        if (runIdx < 0 || runIdx >= rl.size()) return;
        RunSec rs = rl.get(runIdx);
        if (rs.piece() != activePiece) {
            activateRunSec(rs.piece(), rs.section());
        }
        showSectionMenu(rs.section(), screenX, screenY);
    }

    /** '+' press: the menu appends to the RUN's end — activate its
     *  last piece first when the editor is parked upstream (v13.4). */
    private void plusPressed(double screenX, double screenY) {
        int re = runEnd();
        if (re != activePiece) {
            activateRunSec(re,
                app.coaster.pieces.get(re).sections.size() - 1);
        }
        showAddSectionMenu(screenX, screenY);
    }

    /** The section BADGE menu (the strip's type tag doubles as its
     *  per-section actions handle — reverse + delete, plus append
     *  element on a straight/curve section). */
    private void showSectionMenu(int k, double screenX, double screenY) {
        Section sec = sections().get(k);
        ContextMenu cm = new ContextMenu();
        String extra = sec instanceof StraightCurveSection s
            ? String.format(Locale.US, " · %.0f m, %d elem",
                s.totalLen(), s.elements.size())
            : "";
        // §5.2: a drifted section's info line carries the TRUE entry
        // speed next to the design speed (the badge's ≠v, spelled out)
        double[] es = entrySpeeds(activePiece, k);
        String drift = es != null && Math.abs(es[1] - es[0]) > DRIFT_EPS
            ? String.format(Locale.US, " · entered %.1f m/s", es[1])
            : "";
        MenuItem info = new MenuItem(String.format(Locale.US,
            "%s section %d — design %.1f m/s%s%s",
            modeName(sec), k + 1, sec.vDesign(), drift, extra));
        info.setDisable(true);
        // the per-section Reversed check moved to piece scope in v12.1
        // (Coaster menu > Reverse piece, D71); the ◀ badge still marks
        // sections whose flag is set — that is reversal's MECHANISM
        MenuItem del = new MenuItem("Delete section");
        // the DOCUMENT keeps >= 1 section; a piece's last section
        // deletes the piece itself when others remain (v12.0)
        del.setDisable(sections().size() <= 1 && app.coaster.pieces.size() <= 1);
        del.setOnAction(ev -> deleteSection(k));
        MenuItem split = new MenuItem("Split piece before this section");
        split.setDisable(k == 0);   // the piece starts here already
        split.setOnAction(ev -> splitAt(k));
        cm.getItems().addAll(info, new SeparatorMenuItem(), split, del);
        if (sec instanceof StraightCurveSection s) {
            MenuItem addEl = new MenuItem("Append element (10 m straight)");
            addEl.setOnAction(ev -> {
                pushUndoSnapshot();
                s.elements.add(StraightCurveSection.Element.straight(10, SC_SMOOTH_DEFAULT));
                if (k == activeSection) elSel = s.elements.size() - 1;
                app.rebuildTrack();
                refreshOffset();
                redraw();
            });
            cm.getItems().add(addEl);
        }
        if (sec instanceof BridgeSection bs) {
            if (bridgeOrphan(bs)) {
                MenuItem status = new MenuItem(
                    "! no following piece — frozen at its last shape");
                status.setDisable(true);
                cm.getItems().add(status);
            }
            MenuItem tangents = new MenuItem("Bridge tangents…");
            tangents.setOnAction(ev -> showBridgeDialog(k));
            // §4's second mode as an explicit, repeatable ACTION: the
            // target follows the seams' natural CONTINUATION at the
            // current bridge length (v13.4 — a split turn re-joins its
            // own circle; straight seams reduce to dead ahead)
            MenuItem align = new MenuItem(String.format(Locale.US,
                bs.optMode == BridgeSection.OPT_POS
                    ? "Optimize position of piece %d (keep its yaw)"
                    : "Optimize placement of piece %d (continue the turn)",
                activePiece + 2));
            align.setDisable(bridgeOrphan(bs)
                || bs.optMode == BridgeSection.OPT_NONE);
            align.setOnAction(ev -> {
                pushUndoSnapshot();
                if (bs.optLen) {
                    applyOptimalLength(bs);
                } else {
                    applyBridgeGap(bs, activeBridgeGap());
                }
            });
            cm.getItems().addAll(tangents, align);
        }
        // CONVERT (v13.7, D81): ONE unified re-authoring feature at
        // the bottom of every badge menu — the other lane mode(s)
        // this section's shape can become. The v13.5 bridge
        // "Unfreeze (edit as geo section)" renamed and extended:
        // force↔geo, s/c→either, bridge→either (a converted bridge
        // stops following — it's real track; the run splits).
        // Reversed sections refuse: their lanes author the opposite
        // direction (the §5.3 round-1 rule).
        cm.getItems().add(new SeparatorMenuItem());
        if (!(sec instanceof GeoSection)) {
            MenuItem cg = new MenuItem("Convert to geo section");
            cg.setDisable(sec.reversed());
            cg.setOnAction(ev -> convertSection(k, true));
            cm.getItems().add(cg);
        }
        if (!(sec instanceof ForceSection)) {
            MenuItem cf = new MenuItem("Convert to force section");
            cf.setDisable(sec.reversed());
            cf.setOnAction(ev -> convertSection(k, false));
            cm.getItems().add(cf);
        }
        showMenu(cm, screenX, screenY);
    }

    /** Remove section k of the ACTIVE piece; downstream sections
     *  re-chain from the new previous exit (their LOCAL shapes
     *  untouched, §0). Deleting a piece's LAST section deletes the
     *  piece when others remain (v12.0). One undo step. */
    private void deleteSection(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        boolean lastInPiece = secs.size() <= 1;
        if (lastInPiece && app.coaster.pieces.size() <= 1) return;
        pushUndoSnapshot();
        boolean wasActive = k == activeSection;
        if (lastInPiece) {
            app.coaster.pieces.remove(activePiece);
            activePiece = (int) FvdDesigner.clamp(activePiece, 0,
                app.coaster.pieces.size() - 1);
            activeSection = 0;
            wasActive = true;
        } else {
            secs.remove(k);
            if (activeSection > k) activeSection--;
            activeSection = (int) FvdDesigner.clamp(activeSection, 0,
                sections().size() - 1);
        }
        app.lanes = lanesFor(sections().get(activeSection));
        if (wasActive) {
            selIdx = -1;
            selSet.clear();
            elSel = -1;
            axisLock = 0;
            handleViewExpand = false;
        }
        syncLaneButtons();
        app.rebuildTrack();   // spans first: an s/c strip's width may shift
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
    }

    /** Append a 1 s section HOLDING the ride's end state, so the
     *  transition is seamless whatever came before (v10.1 user spec).
     *  Its vDesign freezes at the design speed of the piece's current
     *  end (§0: later v0 edits must never reshape it). One undo step.
     *  Same-mode: the previous section's final lane values. Force
     *  after geo: the felt end forces (compiled Gv/Gl at design speed)
     *  + the final roll rate. Geo after force: the RESULTING end
     *  pitch/yaw rates + the authored roll rate. */
    private void addSection(int mode) {
        pushUndoSnapshot();
        List<Section> secs = sections();
        Section prevSec = secs.get(secs.size() - 1);
        LaneSection prev = prevSec instanceof LaneSection ls ? ls : null;
        // the ACTIVE PIECE's end sample (v12.0) — with pieces after
        // this one, the ride's last sample belongs to somebody else
        TrackCompiler.Span endSp = spanOf(secs.size() - 1);
        int endIdx = endSp != null && app.lastSamples != null
            && endSp.i1() < app.lastSamples.size() ? endSp.i1() : -1;
        FVDIntegrator.Sample end = endIdx >= 0 ? app.lastSamples.get(endIdx) : null;
        double vEnd = end != null ? end.v() : prevSec.vDesign();
        // authored roll only reads WITH the chain from a non-reversed
        // lane section; otherwise (reversed D67, or s/c which has no
        // roll lane) use the compiled resulting roll at the piece end
        double rollEnd = prev != null && !prev.reversed
            ? prev.lanes.get(2).get(prev.lanes.get(2).size() - 1).val
            : (app.resultRates != null && endIdx >= 0
                && endIdx < app.resultRates.length
                ? app.resultRates[endIdx][3] : 0);
        Section added;
        if (mode == 2) {
            // straight/curve: a straight IS the seamless continuation
            added = StraightCurveSection.straight(10, vEnd);
        } else if (prev != null && !prev.reversed
                && (mode == 1) == prev instanceof GeoSection) {
            added = Coaster.continuation(prev, vEnd);
        } else if (mode == 1) {
            double[] endRates = app.resultRates != null && endIdx >= 0
                && endIdx < app.resultRates.length
                ? app.resultRates[endIdx] : new double[4];
            added = new GeoSection(
                LaneSection.holdLanes(endRates[1], endRates[2], rollEnd), vEnd);
        } else {
            added = Coaster.holdSection(
                end != null ? end.Gv() : 1, end != null ? end.Gl() : 0, rollEnd, vEnd);
        }
        secs.add(added);
        activeSection = sections().size() - 1;
        app.lanes = lanesFor(added);
        selIdx = -1;
        selSet.clear();
        elSel = mode == 2 ? 0 : -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;          // auto-fit: the new ACTIVE section must be
                              // on screen, or the next click maps through
                              // a window that ends before it
        syncLaneButtons();
        app.rebuildTrack();   // spans before layout: s/c widths need them
        refreshOffset();
        redraw();
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
        List<RunSec> rl = runLayout();   // the whole bridged run (v13.4)
        boolean actGeo = activeGeo();
        for (int i = 0; i < rl.size(); i++) {
            RunSec rs = rl.get(i);
            if (rs.piece() != activePiece || rs.section() != activeSection) {
                gc.setGlobalAlpha(INACTIVE_SECTION_ALPHA);
                gc.setLineWidth(1.5);
                // REVERSED strips draw derived too: their authored lanes
                // read back-to-front against the chain (D67); a
                // straight/curve strip has no lanes — always derived,
                // plus its faint element separators
                if (rs.sec() instanceof LaneSection ls
                        && (ls instanceof GeoSection) == actGeo
                        && !ls.reversed) {
                    drawAuthoredStrip(gc, ls, rs.t0(), rs.dur());
                } else {
                    drawDerivedStrip(gc, rs.t0(), rs.dur());
                    if (rs.sec() instanceof StraightCurveSection sc) {
                        drawScSeparators(gc, sc, spanAt(rs.piece(), rs.section()));
                    }
                }
                gc.setGlobalAlpha(1.0);
            }
            if (i < rl.size() - 1) {   // boundary between sections
                double bx = tToX(rs.t0() + rs.dur());
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

    // ---------- TRUE-speed display (round A, SECTIONS-DESIGN §5) ----
    // design-vs-true drift made visible: wherever the Pass-A true
    // curve visibly leaves the design curve, a THIN solid companion
    // stroke draws in the lane color — the strokes splitting IS the
    // drift indicator, and they rejoin when a seam is reconciled.

    /** Entry drift (m/s) worth flagging on a badge. */
    static final double DRIFT_EPS = 0.2;
    /** Pixel gap where the true stroke starts drawing. */
    private static final double TRUE_SPLIT_PX = 1.5;
    private static final double TRUE_STROKE_ALPHA = 0.85;

    /** derivedAt's sibling on the TRUE arrays: the active mode's
     *  lane-L value at compiled sample i, at the Pass-D true speed. */
    private double trueAt(int i, int L) {
        if (activeGeo()) return app.trueRates[i][L + 1];
        if (L == 0) return app.trueFelt[i][0];
        if (L == 1) return app.trueFelt[i][1];
        return app.trueRates[i][3];
    }

    private boolean trueArraysReady() {
        return app.trueFelt != null && app.trueRates != null
            && app.resultRates != null && app.lastSamples != null
            && !app.lastSamples.isEmpty()
            && app.trueFelt.length == app.lastSamples.size();
    }

    /** {design v, true v} at piece p section k's first compiled
     *  sample, or null before a compile. The two differing is the §5
     *  DRIFT: the section's design speed no longer matches the energy
     *  actually reaching it. */
    private double[] entrySpeeds(int p, int k) {
        TrackCompiler.Span sp = spanAt(p, k);
        if (sp == null || app.trueV == null || app.lastSamples == null
                || sp.i0() >= app.trueV.length) return null;
        return new double[]{ app.lastSamples.get(sp.i0()).v(),
                             app.trueV[sp.i0()] };
    }

    /** The thin TRUE companion strokes over the whole run — one per
     *  lane, drawn only where the true value visibly splits off the
     *  design curve (> TRUE_SPLIT_PX on the lane's scale). Felt
     *  components draw straight through seams (no differencing — true
     *  curves are seamless by construction, §5.0); rate-sourced ones
     *  (roll, and all lanes in geo view) trim the SEAM_TRIM band like
     *  every bodyRates consumer. */
    private void drawTrueStrokes(GraphicsContext gc) {
        if (!trueArraysReady()) return;
        List<Double> seams = sectionBoundaries();
        double rideT = app.resultRates[app.resultRates.length - 1][0];
        double vt0 = viewStart(), vt1 = viewEnd();
        gc.setLineWidth(1);
        gc.setGlobalAlpha(TRUE_STROKE_ALPHA);
        for (int L = 0; L < LANES; L++) {
            boolean rateSrc = activeGeo() || L == 2;
            gc.setStroke(laneColor(L));
            gc.beginPath();
            boolean started = false;
            for (int i = 0; i < app.resultRates.length; i++) {
                double t = app.resultRates[i][0];
                if (t < vt0 || t > vt1
                        || (rateSrc && (t < SEAM_TRIM || t > rideT - SEAM_TRIM
                                        || nearSeam(t, seams)))) {
                    started = false;
                    continue;
                }
                double dy = valToY(L, derivedAt(i, L));
                double ty = valToY(L, trueAt(i, L));
                if (Math.abs(ty - dy) <= TRUE_SPLIT_PX) {
                    started = false;
                    continue;
                }
                double x = tToX(t);
                if (!started) { gc.moveTo(x, ty); started = true; }
                else gc.lineTo(x, ty);
            }
            gc.stroke();
        }
        gc.setGlobalAlpha(1.0);
        gc.setLineWidth(2);
    }

    /** " (true …)" hover suffix at global time gt on lane L — empty
     *  until the true curve visibly splits from the design curve
     *  there (round A, §5.2). slope = the Shift readout. */
    private String trueSuffix(double gt, int L, boolean slope) {
        if (!trueArraysReady()) return "";
        int i = rateIdxAt(gt);
        double dv = derivedAt(i, L), tv = trueAt(i, L);
        if (Math.abs(valToY(L, tv) - valToY(L, dv)) <= TRUE_SPLIT_PX) return "";
        if (!slope) return String.format(" (true %.2f)", tv);
        double dt = FvdDesigner.INTEGRATE_DT;
        int i0 = rateIdxAt(gt - 2 * dt), i1 = rateIdxAt(gt + 2 * dt);
        double span = app.resultRates[i1][0] - app.resultRates[i0][0];
        double sl = span < 1e-9 ? 0 : (trueAt(i1, L) - trueAt(i0, L)) / span;
        return String.format(" (true %.2f)", sl);
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

    /** The vertical line + circled '+' at the ACTIVE PIECE's end (user
     *  spec): click opens the add-section menu. */
    private void drawAddButton(GraphicsContext gc, double w) {
        double x = tToX(runT0() + runDur());   // the RUN's end (v13.4)
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
        List<RunSec> rl = runLayout();   // badges across the run (v13.4)
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (int k = 0; k < rl.size(); k++) {
            RunSec rs = rl.get(k);
            double off = rs.t0();
            double dur = rs.dur();
            boolean dirty = rs.sec() instanceof BridgeSection bs
                && rs.piece() + 1 >= app.coaster.pieces.size();
            double[] es = entrySpeeds(rs.piece(), rs.section());
            String label = (rs.sec() instanceof GeoSection ? "geo"
                    : rs.sec() instanceof StraightCurveSection ? "s/c"
                    : rs.sec() instanceof BridgeSection
                        ? (dirty ? "bridge !" : "bridge") : "force")
                + (rs.sec().reversed() ? " ◀" : "")
                // §5.2 drift hint: entered at a different speed than
                // it was designed for (badge menu shows the numbers)
                + (es != null && Math.abs(es[1] - es[0]) > DRIFT_EPS
                    ? " ≠v" : "");
            double bw = label.length() * 6 + 10;
            double x0 = Math.max(tToX(off), MARGIN_LEFT);
            double x1 = Math.min(tToX(off + dur), w - marginRight());
            if (x1 - x0 >= bw + 8) {   // strip too narrow on screen = no badge
                double bx = x0 + 4, by = MARGIN_TOP + 4;
                boolean isActive = rs.piece() == activePiece
                    && rs.section() == activeSection;
                gc.setGlobalAlpha(isActive ? 0.85 : 0.45);
                gc.setFill(Color.rgb(45, 50, 62));
                gc.fillRoundRect(bx, by, bw, 15, 7, 7);
                // a dirty bridge wears the warning color (v13.0)
                gc.setStroke(dirty ? Color.rgb(255, 140, 90)
                                   : Color.rgb(150, 156, 175));
                gc.setLineWidth(1);
                gc.strokeRoundRect(bx, by, bw, 15, 7, 7);
                gc.setFill(dirty ? Color.rgb(255, 170, 130)
                                 : Color.rgb(205, 210, 220));
                gc.fillText(label, bx + bw / 2, by + 8);
                badgeRects.add(new double[]{ bx, by, bw, 15, k });
            }
        }
        gc.setGlobalAlpha(1.0);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    /** Which RUN strip's badge is under this point; -1 for none (the
     *  stored index is into runLayout(), v13.4). */
    private int hitBadge(double x, double y) {
        for (double[] r : badgeRects) {
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                return (int) r[4];
            }
        }
        return -1;
    }

    // ---------- straight/curve ELEMENT STRIP (v11.0, D68) ----------
    // the active s/c section's strip REPLACES the lanes with the element
    // widget: a translucent band per element (labeled chip at the top),
    // dashed separators between them, and a smoothing DIAMOND on every
    // internal boundary. Clicking a band selects; dragging a separator
    // (or the section end) resizes the element left of it (Ctrl snaps by
    // the Options length step); dragging a diamond sizes the blend;
    // right-click = insert/delete menu; double-click = element dialog.
    // The felt-G curves draw behind at SC_CURVE_ALPHA for context.
    private int elSel = -1;            // selected element of the active s/c
    private int elDragBoundary = -1;   // element whose LENGTH is dragging
    private int elDragSmooth = -1;     // element whose ENTRY blend is dragging
    private boolean bridgeDragGap = false;   // bridge end line = gap drag (v13.3)
    private int rClickElem = -1;       // deferred right-click menu (pan-safe)

    private static final double SC_CURVE_ALPHA = 0.45;
    static final double SC_SMOOTH_DEFAULT = 2;   // m, new elements' entry blend
    private static final double MIN_ELEM_LEN = 0.5;   // m
    private static final Color SC_STRAIGHT_COLOR = Color.rgb(150, 160, 185);
    private static final Color SC_CURVE_COLOR = Color.rgb(235, 130, 180);

    /** Cumulative AUTHORED arc-length boundaries: [0, L0, L0+L1, …]. */
    private static double[] scBoundsS(StraightCurveSection sc) {
        double[] b = new double[sc.elements.size() + 1];
        for (int i = 0; i < sc.elements.size(); i++) {
            b[i + 1] = b[i] + sc.elements.get(i).arcLen();
        }
        return b;
    }

    /** The active section's span from the last compile, or null. */
    private TrackCompiler.Span activeSpan() {
        return spanOf(activeSection);
    }

    /** Global ride time where the compiled track crosses TRAVERSAL arc
     *  length sTrav inside span sp (binary search + lerp on samples). */
    private double timeAtSpanS(TrackCompiler.Span sp, double sTrav) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        double target = sp.s0() + sTrav;
        int lo = Math.min(sp.i0(), ss.size() - 1);
        int hi = Math.min(sp.i1(), ss.size() - 1);
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ss.get(mid).s() <= target) lo = mid; else hi = mid;
        }
        FVDIntegrator.Sample a = ss.get(lo), b = ss.get(hi);
        double ds = b.s() - a.s();
        double u = ds < 1e-12 ? 0 : FvdDesigner.clamp((target - a.s()) / ds, 0, 1);
        return a.t() + (b.t() - a.t()) * u;
    }

    /** Global ride time of AUTHORED arc length s in the active s/c
     *  section. Reversal runs the authored shape back-to-front, so
     *  authored s maps to traversal (total − s) there — element 0 draws
     *  at the strip's right edge, matching what the train does. */
    private double scTimeAtS(StraightCurveSection sc, double sLocal) {
        double sTrav = sc.reversed ? sc.totalLen() - sLocal : sLocal;
        TrackCompiler.Span sp = activeSpan();
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) {
            double tot = Math.max(sc.totalLen(), 1e-6);
            return tOff + sectionDur(activeSection) * (sTrav / tot);
        }
        return timeAtSpanS(sp, sTrav);
    }

    /** AUTHORED arc length under global ride time t (inverse map).
     *  EXTRAPOLATES past both span ends at the boundary sample's speed
     *  (v11.1): the cursor keeps meaning something beyond the current
     *  section end, so a boundary drag can EXTEND the section, not
     *  just shorten it (the live rebuild re-maps every event). */
    private double scSAtT(StraightCurveSection sc, double gt) {
        double tot = Math.max(sc.totalLen(), 1e-6);
        TrackCompiler.Span sp = activeSpan();
        double sTrav;
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) {
            double dur = Math.max(sectionDur(activeSection), 1e-6);
            sTrav = tot * Math.max(0, (gt - tOff) / dur);
        } else {
            List<FVDIntegrator.Sample> ss = app.lastSamples;
            int lo = Math.min(sp.i0(), ss.size() - 1);
            int hi = Math.min(sp.i1(), ss.size() - 1);
            FVDIntegrator.Sample first = ss.get(lo), last = ss.get(hi);
            if (gt >= last.t()) {
                sTrav = tot + (gt - last.t()) * Math.max(0.5, last.v());
            } else if (gt <= first.t()) {
                // negative: before the entry (extends a REVERSED
                // section, whose authored end sits at the strip's left)
                sTrav = (gt - first.t()) * Math.max(0.5, first.v());
            } else {
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    if (ss.get(mid).t() <= gt) lo = mid; else hi = mid;
                }
                FVDIntegrator.Sample a = ss.get(lo), b = ss.get(hi);
                double dt = b.t() - a.t();
                double u = dt < 1e-12 ? 0 : FvdDesigner.clamp((gt - a.t()) / dt, 0, 1);
                sTrav = FvdDesigner.clamp(a.s() + (b.s() - a.s()) * u - sp.s0(), 0, tot);
            }
        }
        return sc.reversed ? tot - sTrav : sTrav;
    }

    /** Element index under global ride time t. */
    private int elementAtT(StraightCurveSection sc, double gt) {
        double sAuth = scSAtT(sc, gt);
        double[] b = scBoundsS(sc);
        for (int i = 0; i < sc.elements.size(); i++) {
            if (sAuth <= b[i + 1] + 1e-9) return i;
        }
        return sc.elements.size() - 1;
    }

    /** Which element BOUNDARY (1..n; n = the section end) is within
     *  grab range of (x, y); 0/-1 = none (the entry is not draggable).
     *  Nearest wins so tiny elements stay resizable. */
    private int hitScBoundary(double x, double y) {
        StraightCurveSection sc = activeSc();
        if (sc == null || y < MARGIN_TOP || y > MARGIN_TOP + plotHeight()) return -1;
        double[] b = scBoundsS(sc);
        int best = -1;
        double bestD = 5;
        for (int j = 1; j < b.length; j++) {
            double d = Math.abs(x - tToX(scTimeAtS(sc, b[j])));
            if (d <= bestD) { bestD = d; best = j; }
        }
        return best;
    }

    private double scDiamondY() { return MARGIN_TOP + 48; }

    /** Which element's entry-smoothing diamond is under (x, y); -1 =
     *  none. Diamonds sit on internal boundaries only (1..n-1). */
    private int hitScDiamond(double x, double y) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return -1;
        double[] b = scBoundsS(sc);
        for (int j = 1; j < b.length - 1; j++) {
            if (Math.hypot(x - tToX(scTimeAtS(sc, b[j])), y - scDiamondY()) <= 7) {
                return j;
            }
        }
        return -1;
    }

    private static String elemLabel(StraightCurveSection.Element e) {
        String base = e.kind == StraightCurveSection.Kind.STRAIGHT
            ? "— " + fmtVal(e.length) + " m"
            : "R" + fmtVal(e.radius) + " " + fmtVal(Math.abs(e.angleDeg)) + "°"
                + dirArrow(e);
        // non-default roll rides the chip: ∠ = held global bank,
        // ↻ = body roll rate (global 0 — level — is the quiet norm)
        if (Math.abs(e.rollVal) > 1e-9) {
            base += e.rollMode == StraightCurveSection.RollMode.GLOBAL
                ? " ∠" + fmtVal(e.rollVal) + "°"
                : " ↻" + fmtVal(e.rollVal) + "°/s";
        }
        return base;
    }

    /** Turn-plane arrow for a curve chip: the effective direction
     *  (angle sign folds into dir ± 180) rounded to the nearest 45°. */
    private static String dirArrow(StraightCurveSection.Element e) {
        String[] arrows = { "→", "↗", "↑", "↖", "←", "↙", "↓", "↘" };
        double eff = e.angleDeg >= 0 ? e.dirDeg : e.dirDeg + 180;
        int oct = (int) Math.round(((eff % 360) + 360) % 360 / 45.0) % 8;
        return arrows[oct];
    }

    /** The active s/c strip: derived felt-G context curves + the
     *  element widget (bands, chips, separators, smoothing diamonds,
     *  live drag readout). */
    private void drawScStrip(GraphicsContext gc) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return;
        double dur = sectionDur(activeSection);
        gc.setGlobalAlpha(SC_CURVE_ALPHA);
        gc.setLineWidth(1.5);
        drawDerivedStrip(gc, tOff, dur);
        gc.setGlobalAlpha(1.0);

        double[] b = scBoundsS(sc);
        // the same clamped blend halves the integrator uses
        ScIntegrator.Profile prof = new ScIntegrator.Profile(sc);
        double top = MARGIN_TOP, bot = MARGIN_TOP + plotHeight();
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < sc.elements.size(); i++) {
            StraightCurveSection.Element e = sc.elements.get(i);
            double xa = tToX(scTimeAtS(sc, b[i]));
            double xb = tToX(scTimeAtS(sc, b[i + 1]));
            double x0 = Math.min(xa, xb), x1 = Math.max(xa, xb);
            Color col = e.kind == StraightCurveSection.Kind.STRAIGHT
                ? SC_STRAIGHT_COLOR : SC_CURVE_COLOR;
            gc.setFill(col.deriveColor(0, 1, 1, i == elSel ? 0.17 : 0.09));
            gc.fillRect(x0, top, x1 - x0, bot - top);
            if (i == elSel) {
                gc.setStroke(col.deriveColor(0, 1, 1, 0.85));
                gc.setLineWidth(1.5);
                gc.strokeRect(x0 + 1, top + 1, x1 - x0 - 2, bot - top - 2);
            }
            String label = elemLabel(e);
            double bw = label.length() * 6 + 12;
            if (x1 - x0 >= bw + 6) {
                double bx = (x0 + x1) / 2 - bw / 2, by = top + 24;
                gc.setGlobalAlpha(0.92);
                gc.setFill(Color.rgb(45, 50, 62));
                gc.fillRoundRect(bx, by, bw, 15, 7, 7);
                gc.setStroke(i == elSel ? Color.WHITE : col.deriveColor(0, 1, 1, 0.7));
                gc.setLineWidth(1);
                gc.strokeRoundRect(bx, by, bw, 15, 7, 7);
                gc.setFill(col.brighter());
                gc.fillText(label, bx + bw / 2, by + 8);
                gc.setGlobalAlpha(1.0);
            }
        }
        for (int j = 1; j < b.length; j++) {
            double bx = tToX(scTimeAtS(sc, b[j]));
            boolean internal = j < b.length - 1;
            if (internal && prof.half[j] > 1e-9) {   // the blend zone
                double za = tToX(scTimeAtS(sc, b[j] - prof.half[j]));
                double zb = tToX(scTimeAtS(sc, b[j] + prof.half[j]));
                gc.setFill(Color.rgb(255, 255, 255, 0.07));
                gc.fillRect(Math.min(za, zb), top, Math.abs(zb - za), bot - top);
            }
            gc.setStroke(Color.rgb(200, 206, 222));
            gc.setGlobalAlpha(internal ? 0.5 : 0.35);
            gc.setLineWidth(1);
            gc.setLineDashes(2, 5);
            gc.strokeLine(bx, top, bx, bot);
            gc.setLineDashes((double[]) null);
            gc.setGlobalAlpha(1.0);
            if (internal) {   // smoothing diamond: filled = blending
                double dy = scDiamondY();
                boolean on = sc.elements.get(j).smoothIn > 1e-9;
                gc.setGlobalAlpha(0.85);
                gc.beginPath();
                gc.moveTo(bx, dy - 5);
                gc.lineTo(bx + 5, dy);
                gc.lineTo(bx, dy + 5);
                gc.lineTo(bx - 5, dy);
                gc.closePath();
                if (on) { gc.setFill(Color.WHITE); gc.fill(); }
                gc.setStroke(Color.WHITE);
                gc.stroke();
                gc.setGlobalAlpha(1.0);
            }
        }
        // live readout for the running element drag, node-drag style
        String readout = null;
        double rx = 0;
        if (elDragBoundary >= 0 && elDragBoundary < sc.elements.size()) {
            StraightCurveSection.Element e = sc.elements.get(elDragBoundary);
            readout = e.kind == StraightCurveSection.Kind.STRAIGHT
                ? String.format(Locale.US, "length %.1f m", e.length)
                : String.format(Locale.US, "arc %.1f m  (%.0f°)", e.arcLen(), e.angleDeg);
            rx = tToX(scTimeAtS(sc, b[elDragBoundary + 1]));
        } else if (elDragSmooth >= 1 && elDragSmooth < sc.elements.size()) {
            double sm = sc.elements.get(elDragSmooth).smoothIn;
            readout = sm < 1e-9 ? "smooth off"
                : String.format(Locale.US, "smooth %.1f m", sm);
            rx = tToX(scTimeAtS(sc, b[elDragSmooth]));
        }
        if (readout != null) {
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            double tw = readout.length() * 6.5;
            gc.fillText(readout,
                FvdDesigner.clamp(rx + 8, MARGIN_LEFT + 4,
                    canvas.getWidth() - marginRight() - tw - 4),
                MARGIN_TOP + plotHeight() * 0.5);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setLineWidth(2);
    }

    /** The active BRIDGE strip (v13.0): the felt-G derived curves at
     *  s/c-widget alpha — the geometry itself is solver-owned (the
     *  badge menu re-tangents; the END LINE drags the gap, v13.3) —
     *  plus a centered status line when the endpoints have drifted. */
    private void drawBridgeStrip(GraphicsContext gc) {
        BridgeSection bs = activeBridge();
        if (bs == null) return;
        double dur = sectionDur(activeSection);
        gc.setGlobalAlpha(SC_CURVE_ALPHA);
        gc.setLineWidth(1.5);
        drawDerivedStrip(gc, tOff, dur);
        gc.setGlobalAlpha(1.0);
        if (bridgeDragGap) {   // live readout, element-drag style
            String readout = String.format(Locale.US, "length %.1f m",
                activeBridgeGap());
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            double tw = readout.length() * 6.5;
            gc.fillText(readout,
                FvdDesigner.clamp(tToX(tOff + dur) + 8, MARGIN_LEFT + 4,
                    canvas.getWidth() - marginRight() - tw - 4),
                MARGIN_TOP + plotHeight() * 0.5);
        }
        if (bridgeOrphan(bs)) {
            gc.setFill(Color.rgb(255, 170, 130));
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("no following piece — frozen at its last shape",
                (tToX(tOff) + tToX(tOff + dur)) / 2,
                MARGIN_TOP + plotHeight() * 0.5);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
        }
        gc.setLineWidth(2);
    }

    /** Faint element separators over an INACTIVE s/c strip (inherits
     *  the caller's strip alpha). */
    private void drawScSeparators(GraphicsContext gc, StraightCurveSection sc,
                                  TrackCompiler.Span sp) {
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) return;
        double[] b = scBoundsS(sc);
        gc.setStroke(Color.rgb(200, 206, 222));
        gc.setLineWidth(1);
        gc.setLineDashes(2, 5);
        for (int j = 1; j < b.length - 1; j++) {
            double sTrav = sc.reversed ? sc.totalLen() - b[j] : b[j];
            double bx = tToX(timeAtSpanS(sp, sTrav));
            gc.strokeLine(bx, MARGIN_TOP, bx, MARGIN_TOP + plotHeight());
        }
        gc.setLineDashes((double[]) null);
    }

    /** Primary press while a straight/curve section is active. Press
     *  rank mirrors the node editor's: diamonds (the "knobs") > '+' >
     *  badges > boundary grabs > band select > strip activation. */
    private void scPrimaryPressed(MouseEvent e) {
        StraightCurveSection sc = activeSc();
        int d = hitScDiamond(e.getX(), e.getY());
        if (d >= 1) {
            beginGesture();
            elDragSmooth = d;
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            dragTimerLast = 0;
            dragGrowTimer.start();
            return;
        }
        if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
            plusPressed(e.getScreenX(), e.getScreenY());
            return;
        }
        int badge = hitBadge(e.getX(), e.getY());
        if (badge >= 0) {
            showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
            return;
        }
        int bIdx = hitScBoundary(e.getX(), e.getY());
        if (bIdx >= 1) {
            beginGesture();
            elDragBoundary = bIdx - 1;
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            dragTimerLast = 0;
            dragGrowTimer.start();
            return;
        }
        double gt = xToT(e.getX());
        if (gt >= tOff - 1e-9 && gt <= tOff + sectionEnd() + 1e-9
                && e.getY() >= MARGIN_TOP && e.getY() <= MARGIN_TOP + plotHeight()) {
            int i = elementAtT(sc, gt);
            if (i != elSel) {
                elSel = i;
                redraw();
            }
            return;
        }
        RunSec rs = runSecAt(gt);
        if (rs != null && (rs.piece() != activePiece
                || rs.section() != activeSection)) {
            activateRunSec(rs.piece(), rs.section());
            return;
        }
        if (elSel != -1) {
            elSel = -1;
            redraw();
        }
    }

    /** The running element drag: separators resize the element left of
     *  them (straight = length, curve = angle at fixed radius); the
     *  diamond sizes its boundary's blend, snapping shut near zero.
     *  Ctrl snaps by the Options length step. Live recompile keeps the
     *  x→arc-length map honest while the strip stretches. */
    private void scDragTo(double x, boolean snap) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return;
        double[] b = scBoundsS(sc);
        double sAuth = scSAtT(sc, xToT(x));
        if (elDragBoundary >= 0 && elDragBoundary < sc.elements.size()) {
            double newLen = sAuth - b[elDragBoundary];
            if (snap) {
                double step = app.settings.snapLen();
                newLen = Math.round(newLen / step) * step;
            }
            applyElemLen(sc.elements.get(elDragBoundary),
                Math.max(MIN_ELEM_LEN, newLen));
        } else if (elDragSmooth >= 1 && elDragSmooth < sc.elements.size()) {
            double len = 2 * Math.abs(sAuth - b[elDragSmooth]);
            if (snap) {
                double step = app.settings.snapLen();
                len = Math.round(len / step) * step;
            }
            sc.elements.get(elDragSmooth).smoothIn = len < 0.2 ? 0 : len;
        } else {
            return;
        }
        app.rebuildTrack();
        redraw();
    }

    /** Set an element's arc length: a straight's length directly; a
     *  curve keeps its radius and re-angles (sign preserved). */
    private static void applyElemLen(StraightCurveSection.Element el, double len) {
        if (el.kind == StraightCurveSection.Kind.STRAIGHT) {
            el.length = len;
        } else {
            double sign = el.angleDeg < 0 ? -1 : 1;
            el.radius = Math.max(0.1, el.radius);
            el.angleDeg = sign * Math.toDegrees(len / el.radius);
        }
    }

    /** Right-click menu of one element (deferred to a clean release so
     *  right-drag still pans). */
    private void showElementMenu(int i, double screenX, double screenY) {
        StraightCurveSection sc = activeSc();
        if (sc == null || i < 0 || i >= sc.elements.size()) return;
        elSel = i;
        redraw();
        ContextMenu cm = new ContextMenu();
        MenuItem edit = new MenuItem("Edit element…");
        edit.setOnAction(ev -> showElementDialog(i));
        MenuItem insSB = new MenuItem("Insert straight before");
        insSB.setOnAction(ev -> insertElement(i, true));
        MenuItem insCB = new MenuItem("Insert curve before");
        insCB.setOnAction(ev -> insertElement(i, false));
        MenuItem insSA = new MenuItem("Insert straight after");
        insSA.setOnAction(ev -> insertElement(i + 1, true));
        MenuItem insCA = new MenuItem("Insert curve after");
        insCA.setOnAction(ev -> insertElement(i + 1, false));
        MenuItem del = new MenuItem("Delete element");
        del.setDisable(sc.elements.size() <= 1);
        del.setOnAction(ev -> deleteElement(i));
        cm.getItems().addAll(edit, new SeparatorMenuItem(),
            insSB, insCB, insSA, insCA, new SeparatorMenuItem(), del);
        showMenu(cm, screenX, screenY);
    }

    /** Insert an element at list position `at` (one undo step). New
     *  elements arrive with the default entry blend — smoothing is a
     *  first-class citizen here, not an afterthought. */
    private void insertElement(int at, boolean straight) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return;
        pushUndoSnapshot();
        sc.elements.add(at, straight
            ? StraightCurveSection.Element.straight(10, SC_SMOOTH_DEFAULT)
            : StraightCurveSection.Element.curve(20, 90, 0, SC_SMOOTH_DEFAULT));
        elSel = at;
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    private void deleteElement(int i) {
        StraightCurveSection sc = activeSc();
        if (sc == null || sc.elements.size() <= 1
                || i < 0 || i >= sc.elements.size()) return;
        pushUndoSnapshot();
        sc.elements.remove(i);
        elSel = Math.min(elSel, sc.elements.size() - 1);
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** Double-click an element (or its right-click "Edit element…"):
     *  type the parameters directly. The kind radios switch the visible
     *  rows; switching keeps the arc length where it can. */
    private void showElementDialog(int idx) {
        StraightCurveSection sc = activeSc();
        if (sc == null || idx < 0 || idx >= sc.elements.size()) return;
        StraightCurveSection.Element el = sc.elements.get(idx);
        boolean isStraight = el.kind == StraightCurveSection.Kind.STRAIGHT;

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Element " + (idx + 1) + " — straight/curve section");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));

        ToggleGroup kindGrp = new ToggleGroup();
        RadioButton rbS = new RadioButton("Straight");
        RadioButton rbC = new RadioButton("Curve");
        rbS.setToggleGroup(kindGrp);
        rbC.setToggleGroup(kindGrp);
        (isStraight ? rbS : rbC).setSelected(true);

        TextField lenF = new TextField(String.format(Locale.US, "%.3f",
            isStraight ? el.length : el.arcLen()));
        TextField radF = new TextField(String.format(Locale.US, "%.3f",
            el.radius > 0 ? el.radius : 20));
        TextField angF = new TextField(String.format(Locale.US, "%.3f",
            !isStraight ? el.angleDeg : 90));
        TextField dirF = new TextField(String.format(Locale.US, "%.3f", el.dirDeg));
        TextField smF = new TextField(String.format(Locale.US, "%.3f", el.smoothIn));
        dirF.setTooltip(new Tooltip(
            "Curve plane: 0 = flat turn (positive angle = right), 90 = pull up,"
            + " 180 = left, 270 = drop; negative angle mirrors"));

        // roll (v11.1): body rate like the roll lane, or a bank held
        // against the HORIZON (global 0 = rails stay level through
        // slopes and turns — the station/brake default)
        ToggleGroup rollGrp = new ToggleGroup();
        RadioButton rbRate = new RadioButton("Rate (°/s)");
        RadioButton rbGlob = new RadioButton("Global bank (°)");
        rbRate.setToggleGroup(rollGrp);
        rbGlob.setToggleGroup(rollGrp);
        rbRate.setTooltip(new Tooltip(
            "Body roll about the track at this rate, like the roll lane"
            + " (0 = never twist: bank drifts through pitched turns)"));
        rbGlob.setTooltip(new Tooltip(
            "Hold this bank angle measured against the horizon — 0 keeps"
            + " the rails LEVEL through slopes and turns; eased in over"
            + " max(entry smoothing, 2 m)"));
        (el.rollMode == StraightCurveSection.RollMode.GLOBAL ? rbGlob : rbRate)
            .setSelected(true);
        TextField rollF = new TextField(String.format(Locale.US, "%.3f", el.rollVal));

        int row = 0;
        grid.add(new Label("Kind"), 0, row);
        grid.add(new HBox(10, rbS, rbC), 1, row++);
        Label lenL = new Label("Length (m)");
        grid.addRow(row++, lenL, lenF);
        Label radL = new Label("Radius (m)");
        grid.addRow(row++, radL, radF);
        Label angL = new Label("Angle (°)");
        grid.addRow(row++, angL, angF);
        Label dirL = new Label("Direction (°)");
        grid.addRow(row++, dirL, dirF);
        Label smL = new Label("Entry smoothing (m)");
        grid.addRow(row++, smL, smF);
        grid.add(new Label("Roll"), 0, row);
        grid.add(new HBox(10, rbRate, rbGlob), 1, row++);
        Label rollL = new Label("Roll value");
        grid.addRow(row++, rollL, rollF);
        if (idx == 0) {
            smF.setDisable(true);
            smL.setDisable(true);
            smL.setTooltip(new Tooltip(
                "Element 0's entry is the section seam — always sharp"));
        }
        for (javafx.scene.Node c : grid.getChildren()) {
            if (c instanceof TextField tf) tf.setPrefColumnCount(7);
        }

        Runnable sync = () -> {
            boolean st = rbS.isSelected();
            lenF.setDisable(!st);
            lenL.setDisable(!st);
            radF.setDisable(st);
            radL.setDisable(st);
            angF.setDisable(st);
            angL.setDisable(st);
            dirF.setDisable(st);
            dirL.setDisable(st);
        };
        sync.run();
        rbS.setOnAction(ev -> sync.run());
        rbC.setOnAction(ev -> sync.run());

        Button ok = new Button("Apply");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);

        ok.setOnAction(ev -> {
            pushUndoSnapshot();
            if (rbS.isSelected()) {
                double keep = el.kind == StraightCurveSection.Kind.STRAIGHT
                    ? el.length : el.arcLen();
                el.kind = StraightCurveSection.Kind.STRAIGHT;
                el.length = Math.max(MIN_ELEM_LEN, parseOr(lenF, keep));
            } else {
                el.kind = StraightCurveSection.Kind.CURVE;
                el.radius = Math.max(0.1, parseOr(radF, el.radius > 0 ? el.radius : 20));
                double ang = parseOr(angF, el.angleDeg != 0 ? el.angleDeg : 90);
                if (Math.abs(ang) < 0.5) ang = ang < 0 ? -0.5 : 0.5;
                el.angleDeg = ang;
                el.dirDeg = parseOr(dirF, el.dirDeg);
            }
            if (idx > 0) el.smoothIn = Math.max(0, parseOr(smF, el.smoothIn));
            el.rollMode = rbGlob.isSelected()
                ? StraightCurveSection.RollMode.GLOBAL
                : StraightCurveSection.RollMode.RATE;
            el.rollVal = parseOr(rollF, el.rollVal);
            elSel = idx;
            app.rebuildTrack();
            refreshOffset();
            redraw();
            dlg.close();
        });
        cancel.setOnAction(ev -> dlg.close());

        dlg.setScene(new Scene(grid));
        dlg.show();
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
    // hover readout on a DERIVED strip curve (v13.5): a bridge/s-c
    // section's felt-G/roll curves answer like lanes — value, or the
    // slope while Shift is held. -1 = none.
    private int hoverDerLane = -1;
    private double hoverDerT, hoverDerVal;
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
            if (dragNodeIdx < 0 && dragHandleIdx < 0
                    && elDragBoundary < 0 && elDragSmooth < 1
                    && !bridgeDragGap) return;
            double plotRight = MARGIN_LEFT + plotWidth();
            double plotBottom = MARGIN_TOP + plotHeight();
            double winW = dragWinT1 - dragWinT0;
            double vRange = curMax[activeLane] - curMin[activeLane];
            boolean grew = false;
            if (lastDragX > plotRight) {
                dragWinT1 += (lastDragX - plotRight) * 3 * dt * winW / plotWidth();
                grew = true;
            } else if (lastDragX < MARGIN_LEFT && dragWinT0 > runT0()) {
                dragWinT0 = Math.max(runT0(),
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
                } else if (dragHandleIdx >= 0) {
                    dragHandleTo(lastDragX, lastDragY, lastDragShift, lastDragSnap);
                } else if (bridgeDragGap) {
                    bridgeDragTo(lastDragX, lastDragSnap);
                } else {
                    scDragTo(lastDragX, lastDragSnap);
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
            canvas.requestFocus();   // pull focus off toolbar editors
            hoverT = Double.NaN;   // no hover previews while interacting
            hoverRawT = Double.NaN;
            hoverResComp = 0;
            hoverDerLane = -1;
            // an open '+'/badge menu: this press just dismisses it (JavaFX
            // auto-hides on clicks anywhere EXCEPT this canvas — v10.1)
            if (openMenu != null && openMenu.isShowing()) {
                openMenu.hide();
                openMenu = null;
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY) {
                // a straight/curve section has its own surface (v11.0)
                if (activeSc() != null) {
                    scPrimaryPressed(e);
                    return;
                }
                // a bridge has no node surface: '+', badges, the
                // draggable END LINE (gap, v13.3) and strip switches
                if (activeBridge() != null) {
                    if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
                        plusPressed(e.getScreenX(), e.getScreenY());
                        return;
                    }
                    int badge = hitBadge(e.getX(), e.getY());
                    if (badge >= 0) {
                        showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    if (hitBridgeEnd(e.getX(), e.getY())) {
                        beginGesture();
                        bridgeDragGap = true;
                        lastDragX = e.getX();
                        lastDragY = e.getY();
                        lastDragSnap = e.isControlDown();
                        dragTimerLast = 0;
                        dragGrowTimer.start();
                        return;
                    }
                    RunSec rs = runSecAt(xToT(e.getX()));
                    if (rs != null && (rs.piece() != activePiece
                            || rs.section() != activeSection)) {
                        activateRunSec(rs.piece(), rs.section());
                    }
                    return;
                }
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
                        plusPressed(e.getScreenX(), e.getScreenY());
                        return;
                    }
                    // section type badges (same press rank as the '+')
                    int badge = hitBadge(e.getX(), e.getY());
                    if (badge >= 0) {
                        showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
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
                        RunSec rs = runSecAt(gt);
                        if (rs != null && (rs.piece() != activePiece
                                || rs.section() != activeSection)) {
                            activateRunSec(rs.piece(), rs.section());
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
                if (activeBridge() != null) {   // pan only — no nodes
                    rPressX = e.getX();
                    rPressY = e.getY();
                    panLastX = e.getX();
                    return;
                }
                if (activeSc() != null) {
                    // right-click an ELEMENT = its menu, deferred to a
                    // clean release exactly like the segment lock menu
                    double gt = xToT(e.getX());
                    rClickElem = gt >= tOff - 1e-9
                            && gt <= tOff + sectionEnd() + 1e-9
                            && e.getY() >= MARGIN_TOP
                            && e.getY() <= MARGIN_TOP + plotHeight()
                        ? elementAtT(activeSc(), gt) : -1;
                    rPressX = e.getX();
                    rPressY = e.getY();
                    panLastX = e.getX();   // right-drag = pan
                    return;
                }
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
            double n0 = FvdDesigner.clamp(tm - (tm - t0) / width * newW,
                runT0(), runT0() + domain() - newW);
            viewT0 = n0;
            viewT1 = n0 + newW;
            redraw();
        });

        canvas.setOnMouseMoved(e -> {
            hoverSlope = e.isShiftDown();
            ctrlHover = e.isControlDown();
            if (activeSc() != null) {   // grab cursor over drag targets
                boolean grab = hitScBoundary(e.getX(), e.getY()) >= 1
                    || hitScDiamond(e.getX(), e.getY()) >= 1;
                canvas.setCursor(grab ? Cursor.H_RESIZE : Cursor.DEFAULT);
            } else if (activeBridge() != null) {   // the end line drags
                canvas.setCursor(hitBridgeEnd(e.getX(), e.getY())
                    ? Cursor.H_RESIZE : Cursor.DEFAULT);
            }
            double t = (activeSc() == null && activeBridge() == null
                    && hitNode(e.getX(), e.getY()) < 0
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
            // a bridge/s-c strip's DERIVED curves take hover readouts
            // too (v13.5) — unless the cursor owns a drag handle there
            int dl = -1;
            double dlt = 0, dlv = 0;
            if ((activeSc() != null || activeBridge() != null)
                    && canvas.getCursor() != Cursor.H_RESIZE
                    && app.resultRates != null && app.lastSamples != null
                    && app.resultRates.length > 0
                    && e.getY() >= MARGIN_TOP
                    && e.getY() <= MARGIN_TOP + plotHeight()) {
                double gt = xToT(e.getX());
                double dur = sectionDur(activeSection);
                if (gt > tOff + SEAM_TRIM && gt < tOff + dur - SEAM_TRIM) {
                    int i = rateIdxAt(gt);
                    double bestPx = 8;
                    for (int L = 0; L < LANES; L++) {
                        double v = derivedAt(i, L);
                        double d = Math.abs(e.getY() - valToY(L, v));
                        if (d < bestPx) {
                            bestPx = d;
                            dl = L;
                            dlt = gt;
                            dlv = v;
                        }
                    }
                }
            }
            boolean was = !Double.isNaN(hoverT) || hoverResComp != 0
                || hoverDerLane >= 0;
            boolean is = !Double.isNaN(t) || rc != 0 || dl >= 0;
            hoverT = t;
            hoverResComp = rc;
            hoverResT = rt;
            hoverResVal = rv;
            hoverDerLane = dl;
            hoverDerT = dlt;
            hoverDerVal = dlv;
            if (was || is) redraw();
        });
        canvas.setOnMouseExited(e -> {
            if (!Double.isNaN(hoverT) || hoverResComp != 0
                    || hoverDerLane >= 0) {
                hoverT = Double.NaN;
                hoverRawT = Double.NaN;
                hoverResComp = 0;
                hoverDerLane = -1;
                redraw();
            }
        });

        canvas.setOnMouseDragged(e -> {
            if ((elDragBoundary >= 0 || elDragSmooth >= 1 || bridgeDragGap)
                    && e.isPrimaryButtonDown()) {
                commitGestureSnapshot();
                lastDragX = e.getX();
                lastDragY = e.getY();
                lastDragSnap = e.isControlDown();
                lastDragShift = app.slideKey;
                if (bridgeDragGap) bridgeDragTo(e.getX(), e.isControlDown());
                else scDragTo(e.getX(), e.isControlDown());
                return;
            }
            if (!Double.isNaN(boxX0) && e.isPrimaryButtonDown()) {
                boxX1 = e.getX();
                boxY1 = e.getY();
                redraw();
                return;
            }
            if (!Double.isNaN(panLastX) && e.isSecondaryButtonDown()) {
                if (Math.hypot(e.getX() - rPressX, e.getY() - rPressY) > 3) {
                    rClickSeg = -1;   // it's a pan, not a segment/element click
                    rClickElem = -1;
                }
                double t0 = viewStart(), t1 = viewEnd();
                if (t1 - t0 < domain()) {   // full view has nowhere to pan
                    double shift = (panLastX - e.getX()) * (t1 - t0) / plotWidth();
                    double n0 = FvdDesigner.clamp(t0 + shift,
                        runT0(), runT0() + domain() - (t1 - t0));
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
            if (e.getButton() == MouseButton.SECONDARY && rClickElem >= 0
                    && Math.hypot(e.getX() - rPressX, e.getY() - rPressY) <= 3) {
                int el = rClickElem;
                rClickElem = -1;
                panLastX = Double.NaN;
                showElementMenu(el, e.getScreenX(), e.getScreenY());
                return;
            }
            rClickElem = -1;
            elDragBoundary = -1;
            elDragSmooth = -1;
            bridgeDragGap = false;
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
                StraightCurveSection sc = activeSc();
                if (sc != null) {
                    double gt = xToT(e.getX());
                    if (gt >= tOff - 1e-9 && gt <= tOff + sectionEnd() + 1e-9
                            && e.getY() >= MARGIN_TOP
                            && e.getY() <= MARGIN_TOP + plotHeight()) {
                        showElementDialog(elementAtT(sc, gt));
                    }
                    return;
                }
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
     *  containing PIECE + section activate (v12.0: any piece's track
     *  is clickable), then the insert runs locally (same rules as
     *  clicking the graph curve). */
    void insertAtTime(double globalT) {
        if (!activateGlobal(globalT)) return;
        // s/c + bridge: activate only, no nodes to insert
        if (activeSc() != null || activeBridge() != null) return;
        insertNodeOnCurve(globalT - tOff);
    }

    /** 3D plain-click: activate the piece + section at this ride time
     *  (v13.1) — the graph follows what you click in the viewport. */
    void activateAt(double globalT) {
        activateGlobal(globalT);
    }

    /** Map a GLOBAL ride time to its piece + section via the compile
     *  spans and activate both; false when unmapped (no compile yet). */
    private boolean activateGlobal(double globalT) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        if (spans == null || spans.isEmpty()) return false;
        int idx = -1;
        for (int i = 0; i < spans.size(); i++) {
            if (globalT <= spans.get(i).t1() + 1e-9) { idx = i; break; }
        }
        if (idx < 0) return false;
        int p = 0, base = 0;
        List<TrackPiece> pieces = app.coaster.pieces;
        while (p < pieces.size() - 1
                && base + pieces.get(p).sections.size() <= idx) {
            base += pieces.get(p).sections.size();
            p++;
        }
        int sec = Math.min(idx - base, pieces.get(p).sections.size() - 1);
        if (p >= runStart() && p <= runEnd()) {
            // same bridged run: keep the zoom (one editing surface)
            activateRunSec(p, sec);
        } else {
            setActivePiece(p);
            setActiveSection(sec);
        }
        return true;
    }

    /** Shift pressed/released while hovering: toggle the slope readout
     *  without waiting for the next mouse move. */
    void shiftHover(boolean on) {
        if (hoverSlope == on) return;
        hoverSlope = on;
        if (!Double.isNaN(hoverT) || hoverResComp != 0
                || hoverDerLane >= 0) redraw();
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
        StraightCurveSection sc = activeSc();
        if (sc != null) {   // A appends an element instead of a second
            insertElement(elSel >= 0 ? elSel + 1 : sc.elements.size(), true);
            return;
        }
        if (activeBridge() != null) {   // frozen: nothing to append to
            app.statsLabel.setText(
                "A bridge is frozen — extend the next piece instead.");
            return;
        }
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
        app.rebuildTrack();   // spans first: s/c strip layout needs them
        redraw();
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
    /** Does the active section have a boundary neighbor on this side —
     *  in its own piece, or across a bridged piece boundary within the
     *  run (v13.4: the neighbor is then the bridge itself, matched from
     *  its compiled curves)? */
    private boolean hasNeighborBefore() {
        return activeSection > 0 || activePiece > runStart();
    }

    private boolean hasNeighborAfter() {
        return activeSection < sections().size() - 1
            || activePiece < runEnd();
    }

    void matchBoundary(int idx) {
        List<BezierBaker.Node> lane = active();
        boolean start = idx == 0 && hasNeighborBefore() && lane.size() >= 2;
        boolean end = idx == lane.size() - 1 && idx > 0
            && hasNeighborAfter();
        if (!start && !end) return;
        refreshOffset();
        if (boundaryState(start ? activeSection - 1 : activeSection + 1,
                !start, activeLane) == null) return;
        pushUndoSnapshot();
        refreezeSeamSpeed(start);
        applyBoundaryMatch(activeLane, start);
        redraw();
        app.rebuildTrack();
    }

    /** Boundary matching first makes the seam's DESIGN SPEEDS agree
     *  (v13.5, the seam-jolt root cause): the DOWNSTREAM section's
     *  vDesign re-freezes to the upstream side's design-end speed —
     *  the same rule '+' applies at creation, re-applied after edits
     *  drift the chain. Rates matched at a COMMON seam speed are also
     *  CURVATURE-matched, so the felt-G lanes, the rate graphs and
     *  the true ride all join; with drifted speeds, rate-only
     *  matching left the felt Gv/Gl display (and the rider) a value
     *  step by ω·Δv/g exactly on the boundary line. Bridge neighbors
     *  are skipped — a bridge's speed profile already blends onto its
     *  target's design speed (D74), so there is nothing to re-freeze
     *  and chasing its tail-end speed would loop through our own. */
    private void refreezeSeamSpeed(boolean startEdge) {
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
        Section down;
        TrackCompiler.Span upSpan;
        if (startEdge) {   // upstream = previous section, downstream = US
            int k = activeSection - 1;
            Section up = k >= 0 ? sections().get(k)
                : null;   // cross-piece: the previous piece's bridge
            if (up == null || up instanceof BridgeSection) return;
            down = sections().get(activeSection);
            upSpan = spanOf(k);
        } else {           // downstream = the NEXT section
            if (activeSection + 1 >= sections().size()) return;
            down = sections().get(activeSection + 1);
            if (down instanceof BridgeSection) return;   // auto-captures
            upSpan = spanOf(activeSection);
        }
        if (upSpan == null) return;
        int i1 = Math.min(upSpan.i1(), app.lastSamples.size() - 1);
        double v = FvdDesigner.clamp(app.lastSamples.get(i1).v(), 1, 100);
        if (Math.abs(v - down.vDesign()) > 1e-9) down.vDesign(v);
    }

    /** Shift+B with a section-EDGE node selected: the boundary return
     *  on EVERY lane at once — each lane's edge node takes its own
     *  neighbor value/slope, exactly like pressing B on each in turn
     *  (v10.4 user spec), as ONE undo step. */
    void matchBoundaryAll() {
        List<BezierBaker.Node> lane = active();
        if (selIdx < 0) return;
        boolean start = selIdx == 0 && hasNeighborBefore();
        boolean end = selIdx == active().size() - 1 && selIdx > 0
            && hasNeighborAfter();
        if (!start && !end) return;
        refreshOffset();
        // nullity is uniform across lanes (it only means "no compiled
        // data yet" at a cross-mode boundary) — probe once
        if (boundaryState(start ? activeSection - 1 : activeSection + 1,
                !start, 0) == null) return;
        pushUndoSnapshot();
        refreezeSeamSpeed(start);
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
            if (!hasNeighborBefore()) return false;
            double[] vs = boundaryState(activeSection - 1, false, laneIdx);
            if (vs == null) return false;
            BezierBaker.Node n = ln.get(0);
            n.val = vs[0];
            n.outDv = vs[1] * BezierBaker.effOutDt(n, ln.get(1));
            return true;
        }
        if (!hasNeighborAfter()) return false;
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
        List<Section> secs = sections();
        // k may fall OUTSIDE the active piece (v13.4: matching across a
        // bridged piece boundary) — the neighbor is then the bridge,
        // which has no lanes: only the sampled path below applies, and
        // it needs no Section object (spanOf's flattened index already
        // reaches the neighboring piece's spans)
        Section nbSec = k >= 0 && k < secs.size() ? secs.get(k) : null;
        // a REVERSED neighbor's authored lanes run against the chain
        // (values at the wrong end, roll/Gl signs flipped) — its
        // boundary state comes from the compiled curves instead (D67);
        // a straight/curve neighbor has no lanes at all — likewise
        if (nbSec instanceof LaneSection nb
                && (sameMode(nb, secs.get(activeSection)) || laneIdx == 2)
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
        // a CROSS-MODE lane-section neighbor is matched ANALYTICALLY
        // (v13.4): its boundary rates are its authored lane values (geo)
        // or the exact force inversion ω = g(G − axis.y)/v at the seam
        // frame (force) — no bodyRates sampling at all. The sampled path
        // below extrapolates LINEARLY, and at a banked rolling seam the
        // rate curves are strongly curved, so every press re-injected a
        // small bias: the values (and the seam frame with them) crept
        // ~0.1 °/s per Shift+B round-trip without ever settling.
        // Analytic targets are exact AT the fixed point, so a re-press
        // changes nothing and the seam stays put.
        if (laneIdx < 2 && nbSec instanceof LaneSection nbl
                && !nbl.reversed) {
            return crossModeFromLanes(nbl, k, atStart, laneIdx);
        }
        // sample CLEAR of the seam (v10.2): bodyRates' central
        // differences SMEAR a boundary discontinuity across ±2 samples
        // (the short angled connector once visible at seams), and the
        // seam's sample index shifts with the integrator's cadence — so
        // read the neighbor ~3 samples inside and slope over the next 5,
        // then EXTRAPOLATE the value back to the seam itself (v13.3:
        // returning the inside sample verbatim left a 3·dt·slope bias —
        // every B press moved the node again and the seam kept a small
        // step wherever the neighbor's curve had slope). Reversed, s/c
        // and bridge neighbors still come through here.
        double dt = FvdDesigner.INTEGRATE_DT;
        TrackCompiler.Span act = spanOf(activeSection);
        if (act == null) return null;
        // the COMPILED boundary time (authored section ends truncate to
        // the sample grid; tOff accumulates authored durations)
        double tB = atStart ? act.t1() : act.t0();
        int n = app.resultRates.length;
        int i0, i1;
        if (atStart) {   // just inside the next section
            i0 = rateIdxAt(tB + 3 * dt);
            i1 = Math.min(i0 + 5, n - 1);
        } else {         // just inside the previous section
            i1 = rateIdxAt(tB - 3 * dt);
            i0 = Math.max(i1 - 5, 0);
        }
        int at = atStart ? i0 : i1;
        if (!activeGeo() && laneIdx < 2) {
            return forceTargetFromRates(laneIdx, atStart, tB, i0, i1, at);
        }
        double span = app.resultRates[i1][0] - app.resultRates[i0][0];
        double slope = span < 1e-9 ? 0
            : (derivedAt(i1, laneIdx) - derivedAt(i0, laneIdx)) / span;
        double val = derivedAt(at, laneIdx)
            - slope * (app.resultRates[at][0] - tB);
        return new double[]{ val, slope };
    }

    /** {value, slope} of one lane at its start or end, with the
     *  hold-flat rule (a lane ending before the section end holds its
     *  final value — slope 0 at the boundary). */
    private static double[] laneEdge(List<BezierBaker.Node> ln,
                                     double laneSetEnd, boolean atStart) {
        if (atStart) {
            BezierBaker.Node n0 = ln.get(0);
            double outDt = ln.size() >= 2
                ? BezierBaker.effOutDt(n0, ln.get(1)) : 0;
            return new double[]{ n0.val,
                Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt };
        }
        BezierBaker.Node last = ln.get(ln.size() - 1);
        if (ln.size() < 2 || last.t < laneSetEnd - 1e-9) {
            return new double[]{ last.val, 0 };
        }
        double inDt = BezierBaker.effInDt(ln.get(ln.size() - 2), last);
        return new double[]{ last.val,
            Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt };
    }

    /** ANALYTIC cross-mode boundary state (v13.4): the neighbor's edge
     *  body rates + slopes, exactly, from its authored lanes — a geo
     *  neighbor's lanes ARE the rates; a force neighbor's rates invert
     *  its authored targets at the seam frame and ITS OWN seam design
     *  speed (ω = g(G − axis.y)/v, ω' = g(G' − (Ω×axis).y)/v + ω·g·T.y/v)
     *  — then converted to the ACTIVE mode's units (identity for a geo
     *  view; G = ω·v_act/g + axis.y for a force view, v13.3's formula).
     *  Exact both directions, so alternating B/Shift+B is idempotent
     *  once matched. */
    private double[] crossModeFromLanes(LaneSection nb, int k,
                                        boolean atStart, int laneIdx) {
        TrackCompiler.Span act = spanOf(activeSection);
        TrackCompiler.Span seamSp = atStart ? act
            : activeSection > 0 ? spanOf(activeSection - 1) : null;
        if (act == null || seamSp == null) return null;
        TrackPose seam = seamSp.exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        boolean nbStarts = atStart;   // neighbor is NEXT ⇒ its start is the seam
        double nbEnd = BezierBaker.rideEnd(nb.lanes);
        double[][] edge = new double[3][];
        for (int L = 0; L < 3; L++) {
            edge[L] = laneEdge(nb.lanes.get(L), nbEnd, nbStarts);
        }
        double rate, rateSlope;   // the neighbor's laneIdx rate, °/s + °/s²
        double pitchDeg, yawDeg;
        if (nb instanceof GeoSection) {
            pitchDeg = edge[0][0];
            yawDeg = edge[1][0];
            rate = edge[laneIdx][0];
            rateSlope = edge[laneIdx][1];
        } else {
            // force neighbor: design speed at ITS side of the seam —
            // exactly vDesign at its start, the compiled end sample at
            // its end
            TrackCompiler.Span nbSp = spanOf(k);
            if (nbSp == null) return null;
            double vNb = nbStarts ? nb.vDesign
                : app.lastSamples.get(Math.min(nbSp.i1(),
                    app.lastSamples.size() - 1)).v();
            vNb = Math.max(0.5, vNb);
            pitchDeg = Math.toDegrees(
                FVDIntegrator.G0 * (edge[0][0] - U.y()) / vNb);
            yawDeg = Math.toDegrees(
                FVDIntegrator.G0 * (edge[1][0] - R.y()) / vNb);
            double rateRad = Math.toRadians(laneIdx == 0 ? pitchDeg : yawDeg);
            Vec3 omega = omegaAt(T, U, R, pitchDeg, yawDeg, edge[2][0]);
            Vec3 axis = laneIdx == 0 ? U : R;
            double slopeRad = FVDIntegrator.G0 / vNb
                * (edge[laneIdx][1] - omega.cross(axis).y())
                + rateRad * FVDIntegrator.G0 * T.y() / vNb;
            rate = Math.toDegrees(rateRad);
            rateSlope = Math.toDegrees(slopeRad);
        }
        if (activeGeo()) return new double[]{ rate, rateSlope };
        // force view: v13.3's rate→G conversion at the ACTIVE side's
        // seam design speed
        double vAct = atStart
            ? app.lastSamples.get(Math.min(act.i1(),
                app.lastSamples.size() - 1)).v()
            : sections().get(activeSection).vDesign();
        double w = Math.toRadians(rate);
        Vec3 axis = laneIdx == 0 ? U : R;
        Vec3 omega = omegaAt(T, U, R, pitchDeg, yawDeg, edge[2][0]);
        double val = w * vAct / FVDIntegrator.G0 + axis.y();
        double slope = Math.toRadians(rateSlope) * vAct / FVDIntegrator.G0
            - w * T.y() + omega.cross(axis).y();
        return new double[]{ val, slope };
    }

    /** Ω from a body-rate triple (°/s) in the given frame — the shared
     *  integrator convention Ω = yaw·U − pitch·R + roll·T. */
    private static Vec3 omegaAt(Vec3 T, Vec3 U, Vec3 R,
                                double pitchDeg, double yawDeg, double rollDeg) {
        return U.scale(Math.toRadians(yawDeg))
            .add(R.scale(-Math.toRadians(pitchDeg)))
            .add(T.scale(Math.toRadians(rollDeg)));
    }

    /** FORCE-view cross-mode boundary target for Gv/Gl (v13.3): the
     *  neighbor's resulting RATE at the seam, converted to a G target
     *  at the ACTIVE side's own seam design speed —
     *      G = ω·v_seam/g + axis.y
     *  Both edges of the seam then enforce the SAME invariant (body-
     *  rate continuity in ride time, what the unified graph shows), so
     *  alternating Shift+B has a fixed point. The old behavior copied
     *  the neighbor's FELT G — recorded at the NEIGHBOR'S design
     *  speed — which multiplied the seam rate by the design-speed
     *  ratio on every alternate press: once the two sections' design
     *  speeds drifted apart (any height edit upstream of a frozen
     *  vDesign), the B/Shift+B ping-pong never converged. Slope
     *  carries the same conversion (dG/dt = ω'·v/g + ω·v'/g + axis'ʸ,
     *  with v' = −g·T.y from the design energy profile and
     *  axis' = Ω×axis). */
    private double[] forceTargetFromRates(int laneIdx, boolean atStart,
                                          double tB, int i0, int i1, int at) {
        int comp = laneIdx + 1;   // resultRates rows: {t, pitch, yaw, roll}
        double[][] rr = app.resultRates;
        double span = rr[i1][0] - rr[i0][0];
        double slopeR = span < 1e-9 ? 0
            : (rr[i1][comp] - rr[i0][comp]) / span;
        double rateDeg = rr[at][comp] - slopeR * (rr[at][0] - tB);
        // the ACTIVE side's design state at the seam: the entry is
        // exactly vDesign on the chained entry pose; the end is the
        // compiled end sample (both move as the section reshapes — the
        // documented mild iteration, now convergent)
        TrackCompiler.Span sp = spanOf(atStart ? activeSection
                                               : activeSection - 1);
        if (sp == null) return null;
        double vSeam;
        if (atStart) {
            int ie = Math.min(sp.i1(), app.lastSamples.size() - 1);
            vSeam = app.lastSamples.get(ie).v();
        } else {
            vSeam = sections().get(activeSection).vDesign();
        }
        TrackPose seam = sp.exit();   // exact seam frame either way
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double w = Math.toRadians(rateDeg);
        double val = w * vSeam / FVDIntegrator.G0
            + (laneIdx == 0 ? U.y() : R.y());
        Vec3 omega = U.scale(Math.toRadians(rr[at][2]))
            .add(R.scale(-Math.toRadians(rr[at][1])))
            .add(T.scale(Math.toRadians(rr[at][3])));
        Vec3 axis = laneIdx == 0 ? U : R;
        double slope = Math.toRadians(slopeR) * vSeam / FVDIntegrator.G0
            - w * T.y() + omega.cross(axis).y();
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

    /** Same lane semantics (both force or both geo)? A straight/curve
     *  section is never same-mode with anything (no lanes). */
    private static boolean sameMode(LaneSection a, Section b) {
        return b instanceof LaneSection
            && (a instanceof GeoSection) == (b instanceof GeoSection);
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

    private double maxT() { return Math.max(0.5, runDur()); }
    private double domain() { return maxT() * 1.08; }
    private double plotHeight() { return canvas.getHeight() - MARGIN_TOP - MARGIN_BOTTOM; }
    private double plotWidth() { return canvas.getWidth() - MARGIN_LEFT - marginRight(); }

    private double viewStart() {
        return !Double.isNaN(dragWinT0) ? dragWinT0
             : (viewT1 > 0 ? viewT0 : runT0());
    }
    private double viewEnd() {
        return !Double.isNaN(dragWinT0) ? dragWinT1
             : (viewT1 > 0 ? viewT1 : runT0() + domain());
    }

    /** A MANUAL zoom window survives structural edits, so the piece's
     *  stretch can move out from under it (add/delete section, attach,
     *  split, undo…): every click then maps through a window the strips
     *  have left — inserts land in the wrong place and the plot shows
     *  nothing but ghost strips ("the graph disappeared"). Run before
     *  any mapping: slide the window back into the piece's stretch,
     *  keeping its width; a window at least as wide as the stretch
     *  resumes auto-fit. */
    private void sanitizeView() {
        if (viewT1 <= 0) return;   // auto-fit follows the run already
        double width = viewT1 - viewT0;
        if (width >= domain() - 1e-9) {
            viewT0 = 0;
            viewT1 = -1;
            return;
        }
        double n0 = FvdDesigner.clamp(viewT0,
            runT0(), runT0() + domain() - width);
        viewT0 = n0;
        viewT1 = n0 + width;
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
        if (activeSc() != null) {   // Delete removes the selected element
            deleteElement(elSel);
            return;
        }
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
        if (dragNodeIdx >= 0 || dragHandleIdx >= 0
                || elDragBoundary >= 0 || elDragSmooth >= 1
                || bridgeDragGap) return;
        double t0 = viewStart(), t1 = viewEnd();
        if (activeSc() != null || activeBridge() != null) {
            // no authored lanes (s/c widget or frozen bridge): fit the
            // (force-view) scales to the DERIVED curves on show
            double dur = sectionDur(activeSection);
            for (int L = 0; L < LANES; L++) {
                double lo = defMin(L), hi = defMax(L);
                if (app.resultRates != null && app.lastSamples != null) {
                    for (int i = 0; i < app.resultRates.length; i++) {
                        double t = app.resultRates[i][0];
                        if (t < Math.max(tOff + SEAM_TRIM, t0)
                                || t > Math.min(tOff + dur - SEAM_TRIM, t1)) continue;
                        double v = derivedAt(i, L);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                }
                if (lo < defMin(L) || hi > defMax(L)) {
                    double pad = (hi - lo) * 0.05;
                    double step = niceStep((hi - lo) / 6);
                    curMin[L] = Math.floor((lo - pad) / step) * step;
                    curMax[L] = Math.ceil((hi + pad) / step) * step;
                } else {
                    curMin[L] = defMin(L);
                    curMax[L] = defMax(L);
                }
            }
            updateResScale();
            return;
        }
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

    /** Interior section-boundary times (global) across the whole RUN —
     *  the overlays skip a SEAM_TRIM band around each (bodyRates smear
     *  discontinuities). */
    private List<Double> sectionBoundaries() {
        List<Double> out = new ArrayList<>();
        List<RunSec> rl = runLayout();
        for (int i = 1; i < rl.size(); i++) out.add(rl.get(i).t0());
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
        sanitizeView();           // stale manual windows follow the piece
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

        if (activeSc() != null) {
            // a straight/curve section: the element widget IS the
            // editing surface — no lanes to stroke (v11.0)
            drawScStrip(gc);
        } else if (activeBridge() != null) {
            drawBridgeStrip(gc);   // frozen: derived curves + status
        } else {
            for (int lane = 0; lane < LANES; lane++) {
                if (lane == activeLane) continue;
                gc.setGlobalAlpha(INACTIVE_ALPHA);
                strokeLaneCurve(gc, lane);
            }
            gc.setGlobalAlpha(1.0);
            strokeLaneCurve(gc, activeLane);
        }

        // TRUE companion strokes (round A, §5.2): thin solid lines in
        // the lane colors wherever the Pass-D/A true curves visibly
        // leave the design curves — drift made visible; nothing draws
        // while design == true
        drawTrueStrokes(gc);

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

        // hover readout on the active DERIVED strip (bridge/s-c,
        // v13.5): value in the curve's lane color, slope with Shift —
        // the lane hover's read-only sibling for frozen curves
        if (hoverDerLane >= 0 && app.resultRates != null
                && app.resultRates.length > 0) {
            double hx = tToX(hoverDerT);
            double hy = valToY(hoverDerLane, hoverDerVal);
            gc.setGlobalAlpha(0.55);
            gc.setFill(laneColor(hoverDerLane));
            gc.fillOval(hx - 4, hy - 4, 8, 8);
            gc.setGlobalAlpha(0.9);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            String txt;
            if (hoverSlope) {
                double hdt = FvdDesigner.INTEGRATE_DT;
                int i0 = rateIdxAt(hoverDerT - 2 * hdt);
                int i1 = rateIdxAt(hoverDerT + 2 * hdt);
                double span = app.resultRates[i1][0] - app.resultRates[i0][0];
                double slope = span < 1e-9 ? 0
                    : (derivedAt(i1, hoverDerLane)
                        - derivedAt(i0, hoverDerLane)) / span;
                txt = String.format("%.2f %s/s", slope,
                    laneUnit(hoverDerLane).trim())
                    + trueSuffix(hoverDerT, hoverDerLane, true);
            } else {
                txt = String.format("%.2f%s", hoverDerVal,
                    laneUnit(hoverDerLane))
                    + trueSuffix(hoverDerT, hoverDerLane, false);
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
            // §5.2: the true value rides along once it visibly differs
            hoverTxt += trueSuffix(tOff + hoverT, activeLane, hoverSlope);
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
