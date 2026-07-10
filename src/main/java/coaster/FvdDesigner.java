package coaster;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.geometry.Bounds;
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
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

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
 * FvdDesigner.java  (v5)
 *
 * Interactive FVD coaster designer.
 *   - Top: live 3D view of the track as TWO RAILS (140 mm diameter),
 *     offset from the integrated HEARTLINE by the Heart setting (m below
 *     the rider's heart; negative = inverted coaster) and separated by
 *     the Gauge setting. Drag = orbit, scroll = zoom.
 *   - Bottom: one plot over ride time with three fully INDEPENDENT
 *     curves (vertical G, lateral G, roll rate deg/s), each with its own
 *     nodes.
 *
 * Menus:
 *   File     save / load design
 *   Edit     app-wide settings persisted across runs (AppSettings):
 *            Shortcuts (rebindable keys) and Options (FOV slider)
 *   Coaster  per-coaster settings saved in the .fvd file: heart, gauge,
 *            rail color (solid color or vertical-G gradient)
 *
 * Default shortcuts (all rebindable via Edit > Shortcuts):
 *   1/2/3 (or lane buttons,  choose the editable curve; others translucent
 *    or click a dimmed curve/node — active lane keeps hit-test priority)
 *   click node               select; Bezier handles appear
 *   drag node                free; CTRL snaps the TRANSITION length to
 *                            0.1 s steps and the value to the global
 *                            0.1 G / 1 deg-s grid. Readout shows the
 *                            transition from the previous node (delta-t,
 *                            delta-value) with the true value in parens
 *   Ripple checks (toolbar)  per-axis: "time" shifts every later node in t
 *                            with the drag (keeps later transition times);
 *                            "value" translates the rest of the graph
 *   drag handle              free (angle + length); SHIFT locks the handle
 *                            on its current line (length only, partner
 *                            untouched); CTRL snaps the length fraction to
 *                            ease presets cubic/sine/quintic/circle. On a
 *                            smooth node a free drag mirrors the partner
 *                            handle proportionally on both axes
 *   B                        toggle smooth/broken handles on selected node
 *   H                        flatten the selected node's handles (level)
 *   Z / X                    lock node drags vertical / horizontal (guide
 *                            line shows the rail); same key unlocks
 *   click the curve          insert node exactly ON the curve (hover shows
 *                            a translucent dot): flat segments get sine
 *                            handles; otherwise a de Casteljau split —
 *                            tangent handles, the curve does not change
 *   double-click node        dialog: type transition time/magnitude and
 *                            handle fractions directly (3 decimals)
 *   right-click / Delete     delete node (interior deletes bridge the gap,
 *                            neighbors unchanged; last node shortens the
 *                            ride; the first node is undeletable)
 *   A                        append 1 s to the ACTIVE lane holding its final
 *                            value (other lanes keep their dotted hold)
 *   Space (or POV button)    POV cam; W/S ride forward/backward along the
 *                            track at simulated speed (SHIFT x4, CTRL x0.25,
 *                            no key = hold position); scroll = fast burst
 *                            along the track; a translucent line on the
 *                            graph marks the camera (Options toggle);
 *                            Space exits
 *   right-click 3D view      FLY mode: WASD + mouse look, SHIFT = fast,
 *                            CTRL = slow, scroll = burst move;
 *                            right-click again to exit (view stays put)
 *   graph scroll / rt-drag   zoom the time axis around the cursor / pan;
 *                            zooming fully out resumes auto-fit; adaptive
 *                            1-2-5 grid lines follow the zoom
 *   graph panel              vertically resizable (SplitPane divider); the
 *                            button on the 3D view collapses/restores it
 *   Ctrl+S / Ctrl+O          save / load design
 *   Ctrl+Z                   undo
 */
public class FvdDesigner extends Application {

    // ---------- track model: three independent node lanes ----------
    // points at the ACTIVE section's lane lists (D55) - the graph editor
    // re-targets it on section switches; loads/new re-point it wholesale
    List<List<BezierBaker.Node>> lanes = new ArrayList<>();
    {
        List<BezierBaker.Node> gv = nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}});
        List<BezierBaker.Node> gl = nodes(new double[][]{ {0,0},{13.46,0} });
        List<BezierBaker.Node> roll = nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}});
        lanes.add(gv); lanes.add(gl); lanes.add(roll);
    }
    private static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    // ---------- the DOCUMENT (Phase 1, D50) ----------
    // physics-relevant state lives in the Coaster: pieces (placement +
    // sections whose lane lists are the very lists the editor mutates),
    // dynamics v0. Start y default 21 keeps the shipped layout's old
    // clearance above the y=0 ground plane (D41). Cosmetics below stay
    // UI-side until the milestone-B split and travel via
    // DesignIO.Cosmetics at save/load.
    final Coaster coaster = Coaster.singleForce(lanes, 26, 0, 21, 0);

    // ---------- track appearance settings (per-coaster, saved in .fvd) ----------
    double heartOffset = 1.0;  // m from heartline down to track (negative = inverted)
    double railGauge = 1.0;    // m between rail centers
    double tieSpacing = 1.0;   // m of track between cross ties

    // ---------- document-level undo state (Phase 1 milestone B, D51) ----------
    // one snapshot = everything that shapes the RIDDEN TRACK: the lanes plus
    // heart, gauge, start placement and v0 (user rule: "everything that
    // changes the track path, not just aesthetics"). Tie spacing and colors
    // stay menu-local-undo only.
    // mode: 0 force, 1 geo, 2 straight/curve (lanes null, elements
    // set), 3 bridge (lanes+elements null, bridge set)
    record SecState(List<List<BezierBaker.Node>> lanes,
                    List<StraightCurveSection.Element> elements,
                    double vDesign, int mode, boolean reversed,
                    BridgeSection bridge) {}

    // one per TrackPiece (v12.0; baseOrient v12.1): sections + placement
    record PieceState(List<SecState> sections,
                      double x, double y, double z, double yaw, Quat base) {}

    record DocState(List<PieceState> pieces, int activePiece, int active,
                    double heart, double gauge, double v0) {}

    private boolean docApplying = false;   // suppress spinner listeners while
                                           // an undo/redo writes them back

    DocState snapshotDoc() {
        List<PieceState> ps = new ArrayList<>();
        for (TrackPiece piece : coaster.pieces) {
            List<SecState> secs = new ArrayList<>();
            for (Section s : piece.sections) {
                if (s instanceof BridgeSection bs) {
                    secs.add(new SecState(null, null, bs.vDesign, 3,
                        bs.reversed, bs.copy()));
                    continue;
                }
                if (s instanceof StraightCurveSection sc) {
                    List<StraightCurveSection.Element> els =
                        new ArrayList<>(sc.elements.size());
                    for (StraightCurveSection.Element e : sc.elements) els.add(e.copy());
                    secs.add(new SecState(null, els, sc.vDesign, 2, sc.reversed, null));
                    continue;
                }
                LaneSection fs = (LaneSection) s;
                List<List<BezierBaker.Node>> copy = new ArrayList<>(3);
                for (List<BezierBaker.Node> lane : fs.lanes) {
                    List<BezierBaker.Node> laneCopy = new ArrayList<>(lane.size());
                    for (BezierBaker.Node n : lane) laneCopy.add(n.copy());
                    copy.add(laneCopy);
                }
                secs.add(new SecState(copy, null, fs.vDesign,
                    fs instanceof GeoSection ? 1 : 0, fs.reversed, null));
            }
            ps.add(new PieceState(secs, piece.x, piece.y, piece.z,
                piece.yawDeg, piece.baseOrient));
        }
        return new DocState(ps,
            editor != null ? editor.activePiece() : 0,
            editor != null ? editor.activeSection() : 0,
            heartOffset, railGauge, coaster.v0);
    }

    /** Restore a document snapshot: pieces, path settings, and the
     *  spinner displays (their listeners are suppressed — fields are
     *  set here; placement spinners show the ACTIVE piece). */
    void applyDocState(DocState s) {
        docApplying = true;
        coaster.pieces.clear();
        // snapshot lists are deep copies popped off the stack - adopt them
        for (PieceState pst : s.pieces()) {
            TrackPiece piece = new TrackPiece(pst.x(), pst.y(), pst.z(), pst.yaw());
            piece.baseOrient = pst.base();
            for (SecState st : pst.sections()) {
                Section sec = switch (st.mode()) {
                    case 3  -> st.bridge();   // deep copy, adopted
                    case 2  -> new StraightCurveSection(st.elements(), st.vDesign());
                    case 1  -> new GeoSection(st.lanes(), st.vDesign());
                    default -> new ForceSection(st.lanes(), st.vDesign());
                };
                sec.reversed(st.reversed());
                piece.sections.add(sec);
            }
            coaster.pieces.add(piece);
        }
        if (editor != null) editor.restoreActive(s.activePiece(), s.active());
        heartOffset = s.heart();
        railGauge = s.gauge();
        coaster.v0 = s.v0();
        heartSpinner.getValueFactory().setValue(s.heart());
        gaugeSpinner.getValueFactory().setValue(s.gauge());
        startSpeedSpinner.getValueFactory().setValue(s.v0());
        docApplying = false;
        syncPlacementSpinners();
        updatePieceButton();
    }

    /** Numeric-only editor filter allowing a leading minus (v13.5 —
     *  same rationale as the length spinner's: focused editors still
     *  receive ride keys' KEY_TYPED characters). */
    private static javafx.scene.control.TextFormatter<String> signedNumFormatter() {
        return new javafx.scene.control.TextFormatter<>(ch ->
            ch.getControlNewText().matches("-?\\d*\\.?\\d*") ? ch : null);
    }

    /** One axis of the bridge target's manual x/y/z (mode: none). */
    private Spinner<Double> bridgePosSpinner(int axis) {
        Spinner<Double> sp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                -100000, 100000, 0, 1));
        sp.setPrefWidth(86);
        sp.setEditable(true);
        sp.getEditor().setTextFormatter(signedNumFormatter());
        sp.valueProperty().addListener((obs, o, v) -> {
            if (!docApplying) editor.setBridgeTargetPos(axis, v);
        });
        return sp;
    }

    /** Point the placement spinners at the ACTIVE piece's values
     *  (listener-safe: docApplying suppresses the undo push). */
    void syncPlacementSpinners() {
        if (editor == null || startXSpinner == null) return;
        TrackPiece p = coaster.pieces.get(
            Math.min(editor.activePiece(), coaster.pieces.size() - 1));
        boolean was = docApplying;
        docApplying = true;
        startXSpinner.getValueFactory().setValue(p.x);
        startYSpinner.getValueFactory().setValue(p.y);
        startZSpinner.getValueFactory().setValue(p.z);
        yawSpinner.getValueFactory().setValue(p.yawDeg);
        docApplying = was;
    }

    // ---------- file lifecycle: New / Save / Save As, dirty flag, title ----------
    private File currentFile = null;   // null = never saved ("Untitled")
    private boolean dirty = false;

    private String docName() {
        return currentFile != null ? currentFile.getName() : "Untitled";
    }

    void markDirty() {
        if (!dirty) {
            dirty = true;
            updateTitle();
        }
    }

    private void updateTitle() {
        if (stage != null)
            stage.setTitle(docName() + (dirty ? "*" : "") + " — FVD Coaster Designer");
    }

    // ---------- the 3D viewport (Viewport3D.java, D54) ----------
    Viewport3D viewport;
    Dialogs dialogs;   // Help/Shortcuts/Rail-colors/Options (Dialogs.java, D54)

    /** The piece the editor is pointed at (placement spinners write
     *  here; falls back to piece 0 before the editor exists). */
    TrackPiece activePieceRef() {
        int p = editor != null ? editor.activePiece() : 0;
        return coaster.pieces.get(Math.min(p, coaster.pieces.size() - 1));
    }

    /** Show/sync the bridge GAP spinner: visible while the active
     *  section is a bridge with a live target (v13.2). The value
     *  tracks the actual gap; docApplying suppresses the listener so
     *  syncing never re-runs the optimization. */
    void updateBridgeControls() {
        if (bridgeGapSpinner == null || editor == null) return;
        // the SAME toolbar button is the piece-placement menu or the
        // bridge's own properties menu (v13.10, D84 — user: "really a
        // completely different menu"); the label says which
        boolean bridge = editor.activeBridgeWithTarget();
        if (placementBtn != null) {
            placementBtn.setText(bridge
                ? "Bridge properties ▾" : "Placement ▾");
        }
        if (!bridge) return;
        boolean was = docApplying;
        docApplying = true;
        setIfOff(bridgeGapSpinner, Math.max(1, editor.activeBridgeGap()), 0.05);
        setIfOff(bridgeYawSpinner, editor.bridgeTargetOrient(0), 0.01);
        setIfOff(bridgePitchSpinner, editor.bridgeTargetOrient(1), 0.01);
        setIfOff(bridgeRollSpinner, editor.bridgeTargetOrient(2), 0.01);
        setIfOff(bridgeXSpinner, editor.bridgeTargetPos(0), 0.005);
        setIfOff(bridgeYSpinner, editor.bridgeTargetPos(1), 0.005);
        setIfOff(bridgeZSpinner, editor.bridgeTargetPos(2), 0.005);
        bridgeLockYawBtn.setSelected(!editor.bridgeLock(0));
        bridgeLockPitchBtn.setSelected(!editor.bridgeLock(1));
        bridgeLockRollBtn.setSelected(!editor.bridgeLock(2));
        int m = editor.bridgeOptMode();
        (m == BridgeSection.OPT_POS ? bridgeModeP
            : m == BridgeSection.OPT_NONE ? bridgeModeN
            : bridgeModePY).setSelected(true);
        if (bridgeOptLenCheck != null) {
            bridgeOptLenCheck.setSelected(editor.bridgeOptLen());
        }
        docApplying = was;
        refreshBridgeRows();
    }

    /** Write a synced value into a spinner only when it genuinely
     *  differs — re-syncs fire on every compile, and rewriting the
     *  editor mid-typing fights the caret. */
    private void setIfOff(Spinner<Double> sp, double v, double eps) {
        if (Math.abs(sp.getValue() - v) > eps) {
            sp.getValueFactory().setValue(Math.round(v * 100) / 100.0);
        }
    }

    /** AUTO RE-OPTIMIZE (v13.10, D84 — user rule: editing the track
     *  BEFORE a bridge updates its optimization automatically while
     *  an optimize mode is on): after a compile, every live
     *  optimize-mode bridge whose PLACEMENT-INVARIANT seam signature
     *  changed re-runs its optimization — at its last requested
     *  length (the knee when "Optimize length" is checked; no
     *  zero-distance prompt on the automatic path). The signature
     *  (BridgeSection.optSignature, v13.12 D86) is pure SEAM GEOMETRY
     *  — no mode flags, no target heading: flag flips and mode
     *  switches must never re-place anything by themselves (their
     *  explicit handlers own that), and Coaster.optimizeBridge STAMPS
     *  the signature when it applies, so explicit actions never
     *  double-fire this path. Optimizing moves/yaws the piece without
     *  changing its own signature, so ONE extra compile settles — no
     *  feedback loop. Returns true when the caller must recompile. */
    boolean autoReoptimizeBridges(TrackCompiler.Result r) {
        boolean changed = false;
        int spanIdx = 0;
        for (int p = 0; p < coaster.pieces.size(); p++) {
            java.util.List<Section> secs = coaster.pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++, spanIdx++) {
                if (!(secs.get(k) instanceof BridgeSection bs)) continue;
                if (bs.optMode == BridgeSection.OPT_NONE
                        || p + 1 >= coaster.pieces.size()) continue;
                double[] sig = bs.optSignature();
                if (bs.optSig == null) {
                    // FIRST SIGHT (load / fresh bridge): record only.
                    // Loading a file must never move its pieces — the
                    // auto path acts on CHANGES from here on.
                    bs.optSig = sig;
                    continue;
                }
                if (sigEq(sig, bs.optSig)) continue;
                boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
                double dist = bs.optLen
                    ? coaster.optimalBridgeLength(bs, py)
                    : bs.lastOptDist > 0 ? bs.lastOptDist
                    : Math.max(1, spanIdx < r.spans().size()
                        ? r.spans().get(spanIdx).sLen() : 1);
                coaster.optimizeBridge(bs, p + 1, dist, py);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean sigEq(double[] a, double[] b) {
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-9) return false;
        }
        return true;
    }

    /** Per-mode enabling of the bridge placement rows (v13.5):
     *  length drives the march in the optimize modes; yaw is the
     *  user's in position-only and manual; x/y/z manual only. */
    private void refreshBridgeRows() {
        if (bridgeLenRow == null || editor == null) return;
        int m = editor.bridgeOptMode();
        bridgeLenRow.setDisable(m == BridgeSection.OPT_NONE);
        // auto length owns the spinner, the check stays clickable
        bridgeGapSpinner.setDisable(editor.bridgeOptLen());
        // orientation spinners (v13.13): a LOCKED axis is frozen
        // everywhere; in optimize-orientation mode the UNLOCKED axes
        // are the march's (live values, not editable). The padlocks
        // themselves stay clickable in every bridge mode.
        boolean orient = m == BridgeSection.OPT_POS_YAW;
        bridgeYawSpinner.setDisable(orient || editor.bridgeLock(0));
        bridgePitchSpinner.setDisable(orient || editor.bridgeLock(1));
        bridgeRollSpinner.setDisable(orient || editor.bridgeLock(2));
        // all three locked = nothing to align: the orientation mode
        // grays out (its tooltip explains the padlocks)
        bridgeModePY.setDisable(editor.bridgeLock(0)
            && editor.bridgeLock(1) && editor.bridgeLock(2)
            && m != BridgeSection.OPT_POS_YAW);
        bridgePosRow.setDisable(m != BridgeSection.OPT_NONE);
    }

    /** Refresh the piece selector: label = the active piece, hidden
     *  entirely while the ride is a single piece (the common case). */
    void updatePieceButton() {
        if (pieceBtn == null) return;
        boolean multi = coaster.pieces.size() > 1;
        pieceBtn.setVisible(multi);
        pieceBtn.setManaged(multi);
        if (multi && editor != null) {
            pieceBtn.setText("Piece " + (editor.activePiece() + 1)
                + "/" + coaster.pieces.size() + " ▾");
        }
    }

    /** Delegates kept so the split moved no call sites: the pipeline
     *  entry, the marker sync and the POV toggle live in Viewport3D. */
    void rebuildTrack() { viewport.rebuildTrack(); }

    void refreshTrackMarkers(int lane, Set<Integer> sel, int selIdx, double tOff) {
        viewport.refreshTrackMarkers(lane, sel, selIdx, tOff);
    }

    void togglePov() { viewport.togglePov(); }
    boolean railSolid = false; // false = vertical-G gradient, true = solid color
    Color railColor = Color.rgb(220, 60, 50);
    // G-force gradient stops (per-coaster, saved in .fvd): each stop is a
    // COLOR at a G VALUE; flat outside the ends, lerped between stops
    final Color[] gradColors = {
        Color.color(0.2, 0.4, 1.0),
        Color.color(0.2, 0.9, 0.3),
        Color.color(1.0, 0.9, 0.1),
        Color.color(1.0, 0.15, 0.1),
    };
    final double[] gradStops = { 0, 1, 3, 5 };
    static final double G_MIN = -1.0, G_MAX = 5.0;
    static final double INTEGRATE_DT = 0.01;

    // lane accent colors, shared by the graph curves, node markers and the
    // lane buttons. Vert G blue / Lat G green since v10.8 (user swap; green
    // was Gv from v5). GEO mode has its OWN palette (v10.1 user spec): pitch
    // teal, yaw yellow, roll keeps the force palette's orange. (TIME moved
    // to light purple in v10.2, so yaw owns yellow outright.)
    static final Color[] LANE_COLORS = {
        Color.rgb(120, 170, 255), Color.rgb(120, 220, 130), Color.rgb(255, 170, 90)
    };
    static final Color[] GEO_LANE_COLORS = {
        Color.rgb(80, 210, 200), Color.rgb(245, 215, 80), Color.rgb(255, 170, 90)
    };
    // lane-button captions per section mode
    static final String[] FORCE_LANE_BTN = { "Vert G", "Lat G", "Roll" };
    static final String[] GEO_LANE_BTN = { "Pitch", "Yaw", "Roll" };

    static final String TOOLBTN_STYLE =
        "-fx-background-color: rgb(45,50,62); -fx-text-fill: rgb(210,215,225);";

    /** Relabel + retint the lane buttons for the active section's mode
     *  (called by the editor on section switches, undo restores, loads,
     *  adds and deletes). mode: 0 force, 1 geo, 2 straight/curve — SC
     *  has no lanes, so the buttons grey out and disable; the plot
     *  shows the FORCE view there (felt G), so the "Resulting" picks
     *  read as rates, same as over a force section. */
    void setLaneButtonMode(int mode) {
        boolean geo = mode == 1;
        String[] names = geo ? GEO_LANE_BTN : FORCE_LANE_BTN;
        Color[] cols = geo ? GEO_LANE_COLORS : LANE_COLORS;
        for (int i = 0; i < 3; i++) {
            if (laneButtons[i] == null) continue;
            laneButtons[i].setText(names[i]);
            laneButtons[i].setDisable(mode == 2);
            laneButtons[i].setStyle(TOOLBTN_STYLE + String.format(
                " -fx-text-fill: rgb(%d,%d,%d);",
                (int) (cols[i].getRed() * 255), (int) (cols[i].getGreen() * 255),
                (int) (cols[i].getBlue() * 255)));
        }
        if (resPitchCheck != null) {
            resPitchCheck.setText(geo ? "Vertical G (g)" : "Pitch rate (°/s)");
            resYawCheck.setText(geo ? "Lateral G (g)" : "Yaw rate (°/s)");
        }
    }
    // TIME is light purple throughout the graph UI (v10.2, was yellow —
    // freed for the geo yaw lane): the "Time (s)" axis label, the
    // horizontal (time-changing) guide line, the POV position marker
    static final Color TIME_COLOR = Color.rgb(200, 165, 255);


    Stage stage;
    final Label statsLabel = new Label();
    GraphEditor editor;
    // compiled track shared by the editor, viewport and exporter
    List<FVDIntegrator.Sample> lastSamples;
    double[][] resultRates;   // {t, pitch, yaw, roll} deg/s per sample
    // Pass D/A true-speed results (round A, SECTIONS-DESIGN §5.1):
    // aligned with lastSamples; equal to the design values wherever
    // no design speed has drifted
    double[] trueV;           // true speed m/s per sample
    double[][] trueFelt;      // {GvTrue, GlTrue} per sample
    double[][] trueRates;     // {t, pitch, yaw, roll} deg/s at true speed
    // per-section sample spans from the last compile (v11.0): the
    // editor lays out DISTANCE-domain strips from these
    List<TrackCompiler.Span> sectionSpans;
    final ToggleButton[] laneButtons = new ToggleButton[3];
    boolean rippleT, rippleV;   // ripple-edit toggles (time / value)
    boolean slideKey;           // S held: slide nodes/handles along a line
    boolean cKey;               // C held: click the track = recenter orbit
    private Spinner<Double> heartSpinner, gaugeSpinner, tieSpinner;
    private Spinner<Double> startXSpinner, startYSpinner, startZSpinner;
    private Spinner<Double> yawSpinner;   // active piece's heading (v12.0)
    Spinner<Double> startSpeedSpinner;
    private Button pieceBtn;              // piece selector (hidden at 1 piece)
    // piece placement/reverse live in a toolbar dropdown since v13.2
    // (user: piece settings belong by the graph, not the Coaster menu)
    private HBox placementRow, yawPlacementRow;
    private MenuItem reverseItem;
    private Label bridgeGapLbl;           // bridge LENGTH spinner (v13.2;
    private Spinner<Double> bridgeGapSpinner;   // in the Placement dropdown
                                                // since v13.5)
    // bridge placement modes (v13.5): the Placement dropdown edits the
    // NEXT piece through the bridge — mode radios + per-mode spinners
    private VBox bridgeModeRow;
    private HBox bridgeLenRow, bridgeYawRow, bridgePitchRow,
        bridgeRollRow, bridgePosRow;
    private RadioButton bridgeModePY, bridgeModeP, bridgeModeN;
    private CheckBox bridgeOptLenCheck;   // v13.8: auto length (D82)
    private Button placementBtn;   // "Placement ▾" / "Bridge properties ▾"
    private ToggleButton bridgeLockYawBtn, bridgeLockPitchBtn,
        bridgeLockRollBtn;         // v13.13: orientation locks (D87)
    private Spinner<Double> bridgeYawSpinner, bridgePitchSpinner,
        bridgeRollSpinner, bridgeXSpinner, bridgeYSpinner, bridgeZSpinner;

    /** An orientation padlock (v13.13): SELECTED = unlocked (🔓,
     *  editable / optimizer-aligned), unselected = locked (🔒,
     *  frozen — the optimization meets the value instead). */
    private ToggleButton bridgeLockBtn(int axis) {
        ToggleButton b = new ToggleButton("🔒");
        b.setFocusTraversable(false);
        b.setStyle("-fx-font-size: 10px; -fx-padding: 1 4 1 4;");
        b.selectedProperty().addListener((obs, o, sel) ->
            b.setText(sel ? "🔓" : "🔒"));
        b.setOnAction(e -> {
            if (docApplying) return;
            editor.setBridgeLock(axis, !b.isSelected());
            refreshBridgeRows();
        });
        Tooltip.install(b, new Tooltip(
            "Lock/unlock this orientation of the next piece.\n"
            + "Locked = frozen (the bridge bends to meet it);\n"
            + "unlocked = editable, and \"Optimize position and\n"
            + "orientation\" aligns it to the natural continuation."));
        return b;
    }
    private SplitPane mainSplit;           // 3D view over graph panel
    private double lastDividerPos = 0.6;   // restore point for the collapse toggle
    RadioMenuItem gradientModeItem, solidModeItem;
    private CheckBox resPitchCheck, resYawCheck;   // retitled per mode

    // app-wide settings persisted across runs (shortcuts, FOV)
    final AppSettings settings = new AppSettings();

    // "local" undo/redo for menu & dialog edits: alive only while the menu/
    // dialog is OPEN (Ctrl+Z / Ctrl+Shift+Z inside it); closing discards the
    // history — the changes lock in and Ctrl+Z acts on the coaster again
    private final Deque<Runnable[]> menuUndoStack = new ArrayDeque<>();
    private final Deque<Runnable[]> menuRedoStack = new ArrayDeque<>();
    private boolean menuSessionOpen = false;
    private boolean menuApplying = false;
    private final Set<Scene> menuKeyScenes = new HashSet<>();
    private Menu coasterMenu;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        // 3D viewport (cameras, meshes, grid, markers): Viewport3D.java
        viewport = new Viewport3D(this);
        dialogs = new Dialogs(this);

        StackPane subHolder = new StackPane(viewport.sub);
        viewport.sub.widthProperty().bind(subHolder.widthProperty());
        viewport.sub.heightProperty().bind(subHolder.heightProperty());
        // the SubScene reports its current size as its minimum, which stops
        // the SplitPane/window from shrinking the 3D area — and pushed the
        // divider + collapse button off-screen on non-maximized windows
        subHolder.setMinSize(0, 0);

        // ----- editor + toolbar -----
        editor = new GraphEditor(this);

        ToggleGroup laneGroup = new ToggleGroup();
        String[] names = FORCE_LANE_BTN;
        HBox toolbar = new HBox(6);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: rgb(25,28,36);");
        String btnStyle = TOOLBTN_STYLE;
        for (int i = 0; i < 3; i++) {
            final int lane = i;
            ToggleButton b = new ToggleButton(names[i]);
            b.setToggleGroup(laneGroup);
            b.setFocusTraversable(false);
            Color lc = LANE_COLORS[i];   // match the curve color in the graph
            b.setStyle(btnStyle + String.format(" -fx-text-fill: rgb(%d,%d,%d);",
                (int) (lc.getRed() * 255), (int) (lc.getGreen() * 255), (int) (lc.getBlue() * 255)));
            b.setOnAction(e -> editor.setActiveLane(lane));
            laneButtons[i] = b;
            toolbar.getChildren().add(b);
        }
        laneButtons[0].setSelected(true);

        Label rippleLbl = new Label("Ripple:");
        rippleLbl.setTextFill(Color.rgb(200, 205, 215));
        HBox rippleTCheck = makeCheck("time", v -> rippleT = v);
        HBox rippleVCheck = makeCheck("value", v -> rippleV = v);
        Tooltip.install(rippleTCheck, new Tooltip(
            "Dragging a node horizontally also shifts every later node of the\n"
            + "lane in time — later transition lengths are preserved."));
        Tooltip.install(rippleVCheck, new Tooltip(
            "Dragging a node vertically also shifts every later node's value —\n"
            + "the rest of the graph translates up/down."));

        // "Resulting" dropdown: pick which read-only derived curves overlay
        // the plot (roll is absent on purpose — the resulting roll rate IS
        // the authored roll lane). The two entries are MODE-AWARE (v10.2):
        // force sections show the resulting pitch/yaw rates, geo sections
        // the felt vertical/lateral G — setLaneButtonMode retitles them.
        // v10.3: a custom dark Popup of CheckBoxes instead of a
        // ContextMenu — it stays open while items are clicked (toggle
        // several without reopening) and dismisses by HOVER: a 250 ms
        // grace timer starts whenever the mouse leaves both the button
        // and the popup, and cancels on re-entry.
        Button resBtn = new Button("Resulting ▾");
        resBtn.setFocusTraversable(false);
        resBtn.setStyle(btnStyle);
        resPitchCheck = new CheckBox("Pitch rate (°/s)");
        resPitchCheck.setSelected(settings.resPitch());
        resPitchCheck.setStyle("-fx-text-fill: rgb(210,215,225);");
        resPitchCheck.setOnAction(e -> {
            settings.setResPitch(resPitchCheck.isSelected());
            editor.refresh();
        });
        resYawCheck = new CheckBox("Yaw rate (°/s)");
        resYawCheck.setSelected(settings.resYaw());
        resYawCheck.setStyle("-fx-text-fill: rgb(210,215,225);");
        resYawCheck.setOnAction(e -> {
            settings.setResYaw(resYawCheck.isSelected());
            editor.refresh();
        });
        VBox resBox = new VBox(8, resPitchCheck, resYawCheck);
        resBox.setPadding(new Insets(10, 16, 10, 12));
        resBox.setStyle("-fx-background-color: rgb(38,42,53);"
            + " -fx-background-radius: 6;"
            + " -fx-border-color: rgb(95,102,120); -fx-border-radius: 6;");
        Popup resPopup = new Popup();
        resPopup.getContent().add(resBox);
        resPopup.setAutoHide(true);   // backstop: clicks elsewhere close it
        // hover dismissal, verified against the REAL cursor: when the
        // popup materializes under an already-moving mouse, JavaFX can
        // skip MOUSE_ENTERED entirely — the exit-timer from leaving the
        // button then hid the menu mid-hover (v10.4 fix for the "fast
        // click closes it" report). So the timer's finish re-checks the
        // actual pointer via Robot and re-arms instead of hiding while
        // it sits over the popup or the button.
        Robot resRobot = new Robot();
        PauseTransition resHide = new PauseTransition(Duration.millis(250));
        resHide.setOnFinished(e -> {
            if (!resPopup.isShowing()) return;
            Point2D m = resRobot.getMousePosition();
            if (screenContains(resBox, m) || screenContains(resBtn, m)) {
                resHide.playFromStart();   // still hovered: poll again
            } else {
                resPopup.hide();
            }
        });
        resBtn.setOnAction(e -> {
            if (resPopup.isShowing()) {
                resPopup.hide();
            } else {
                // flush against the button — a visible gap was a dead
                // zone the cursor-check called "outside" (v10.5)
                Bounds b = resBtn.localToScreen(resBtn.getBoundsInLocal());
                resPopup.show(resBtn, b.getMinX(), b.getMaxY() - 1);
                resHide.playFromStart();   // self-arm: hover state unknown yet
            }
        });
        resBtn.setOnMouseEntered(e -> resHide.stop());
        resBtn.setOnMouseExited(e -> {
            if (resPopup.isShowing()) resHide.playFromStart();
        });
        resBox.setOnMouseEntered(e -> resHide.stop());
        resBox.setOnMouseMoved(e -> resHide.stop());   // covers a missed enter
        resBox.setOnMouseExited(e -> resHide.playFromStart());
        Tooltip.install(resBtn, new Tooltip(
            "Overlay read-only RESULTING curves for the section being\n"
            + "edited (thin dashed, on the right-edge axis): force\n"
            + "sections show pitch/yaw rates (°/s, geo colors); geo\n"
            + "sections show felt vertical/lateral G (force colors).\n"
            + "Hover a curve for its value. Picks persist per-user."));

        // piece selector (v12.0): hidden while the ride is one piece;
        // lists every piece, click to make it the graph's piece
        pieceBtn = new Button("Piece 1 ▾");
        pieceBtn.setFocusTraversable(false);
        pieceBtn.setStyle(btnStyle);
        pieceBtn.setOnAction(e -> {
            ContextMenu cm = new ContextMenu();
            for (int p = 0; p < coaster.pieces.size(); p++) {
                TrackPiece tp = coaster.pieces.get(p);
                MenuItem it = new MenuItem(String.format(Locale.US,
                    "%sPiece %d — %d section%s at (%.0f, %.0f, %.0f)",
                    p == editor.activePiece() ? "✓ " : "   ",
                    p + 1, tp.sections.size(),
                    tp.sections.size() == 1 ? "" : "s", tp.x, tp.y, tp.z));
                final int idx = p;
                it.setOnAction(ev -> editor.setActivePiece(idx));
                cm.getItems().add(it);
            }
            cm.show(pieceBtn, javafx.geometry.Side.BOTTOM, 0, 0);
        });
        Tooltip.install(pieceBtn, new Tooltip(
            "The graph edits ONE piece at a time; pick which.\n"
            + "Split a piece from a section badge; attach a floating\n"
            + "piece from the ⊕ menu at the ride end."));
        updatePieceButton();

        // piece placement/reverse dropdown (v13.2 — moved here from the
        // Coaster menu per user; the rows are built with the menu bar
        // and reparented into a fresh ContextMenu on each open). Over a
        // live bridge the SAME button is "Bridge properties ▾" (v13.10,
        // D84 — user: it's really a different menu): mode + length +
        // per-bridge optimizer preferences + next-piece placement.
        placementBtn = new Button("Placement ▾");
        placementBtn.setFocusTraversable(false);
        placementBtn.setStyle(btnStyle);
        placementBtn.setOnAction(e -> {
            ContextMenu cm = new ContextMenu();
            javafx.scene.Node sceneRow;
            if (editor.activeBridgeWithTarget()) {
                // a live bridge is active: place the NEXT piece through
                // it (v13.5 — mode + the controls that mode leaves free)
                updateBridgeControls();
                cm.getItems().addAll(
                    new CustomMenuItem(bridgeModeRow, false),
                    new CustomMenuItem(bridgeLenRow, false),
                    new CustomMenuItem(bridgeYawRow, false),
                    new CustomMenuItem(bridgePitchRow, false),
                    new CustomMenuItem(bridgeRollRow, false),
                    new CustomMenuItem(bridgePosRow, false));
                sceneRow = bridgeModeRow;
            } else {
                cm.getItems().addAll(
                    new CustomMenuItem(placementRow, false),
                    new CustomMenuItem(yawPlacementRow, false),
                    reverseItem);
                sceneRow = placementRow;
            }
            final javafx.scene.Node row = sceneRow;
            cm.setOnShown(ev -> {
                beginMenuSession();
                installMenuUndoKeys(row.getScene());
            });
            cm.setOnHidden(ev -> endMenuSession());
            cm.show(placementBtn, javafx.geometry.Side.BOTTOM, 0, 0);
        });
        Tooltip.install(placementBtn, new Tooltip(
            "The ACTIVE piece's placement: x/y/z, yaw (a delta on its\n"
            + "natural orientation — 0 reverts), and Reverse piece.\n"
            + "With a BRIDGE active it places the NEXT piece instead:\n"
            + "optimize position+yaw (default), position only (you\n"
            + "set the yaw), or fully manual."));

        // bridge LENGTH spinner (v13.2, length semantics v13.4): shown
        // while a bridge with a live target is active; every change
        // re-runs the placement optimization at that length
        bridgeGapLbl = new Label("Length (m)");
        bridgeGapLbl.setStyle("-fx-text-fill: rgb(200,205,215);");
        bridgeGapSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 1000, 20, 1));
        bridgeGapSpinner.setPrefWidth(80);
        bridgeGapSpinner.setEditable(true);
        bridgeGapSpinner.setFocusTraversable(false);
        // numeric-only editor (v13.4): ride/fly movement keys are handled
        // by scene FILTERS, but the focused editor still received their
        // KEY_TYPED characters — letters kept appearing in the box
        bridgeGapSpinner.getEditor().setTextFormatter(
            new javafx.scene.control.TextFormatter<String>(ch ->
                ch.getControlNewText().matches("\\d*\\.?\\d*") ? ch : null));
        bridgeGapSpinner.valueProperty().addListener((obs, o, v) -> {
            if (docApplying) return;
            editor.setBridgeGap(v);
        });
        Tooltip.install(bridgeGapSpinner, new Tooltip(
            "Length of this bridge along the track: each change slides\n"
            + "the next piece along the seams' natural continuation —\n"
            + "a split turn re-joins around its own circle — and the\n"
            + "bridge re-solves to it (placement optimization)."));
        updateBridgeControls();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button povBtn = new Button("POV");
        povBtn.setFocusTraversable(false);
        povBtn.setStyle(btnStyle);
        povBtn.setOnAction(e -> togglePov());

        // just "Append" (v11.1): it appends whatever the active section
        // authors — seconds on lane sections, ELEMENTS on straight/curve
        Button appendBtn = new Button("Append");
        appendBtn.setFocusTraversable(false);
        appendBtn.setStyle(btnStyle);
        appendBtn.setOnAction(e -> editor.appendAction());
        Tooltip.install(appendBtn, new Tooltip(
            "Lane section — no selection: extend the ride 1 s;\n"
            + "node selected: insert a 1 s flat hold after it\n"
            + "(everything later shifts 1 s).\n"
            + "Straight/curve section: append a 10 m straight element."));

        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(rippleLbl, rippleTCheck, rippleVCheck,
                                     resBtn, pieceBtn, placementBtn,
                                     spacer, povBtn, appendBtn);

        MenuBar menuBar = buildMenuBar();

        statsLabel.setTextFill(Color.rgb(200, 205, 215));
        statsLabel.setFont(Font.font(12));
        statsLabel.setPadding(new Insets(4, 8, 4, 8));
        statsLabel.setStyle("-fx-background-color: rgb(25,28,36);");
        statsLabel.setMaxWidth(Double.MAX_VALUE);

        BorderPane bottom = new BorderPane();
        bottom.setTop(new VBox(statsLabel, toolbar));
        bottom.setCenter(editor);
        bottom.setMinHeight(0);       // allow the divider to crush it fully
        editor.setMinHeight(0);

        // collapse/restore toggle lives ON the 3D view so it stays reachable
        // when the graph panel is fully minimized
        Button collapseBtn = new Button("▼");
        collapseBtn.setFocusTraversable(false);
        collapseBtn.setStyle("-fx-background-color: rgba(45,50,62,0.75); -fx-text-fill: rgb(210,215,225);");
        StackPane.setAlignment(collapseBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(collapseBtn, new Insets(8));
        collapseBtn.setOnAction(e -> toggleGraphPanel());
        subHolder.getChildren().add(collapseBtn);

        mainSplit = new SplitPane(subHolder, bottom);
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.setDividerPositions(lastDividerPos);
        mainSplit.getDividers().get(0).positionProperty().addListener(
            (obs, o, v) -> collapseBtn.setText(v.doubleValue() > 0.97 ? "▲" : "▼"));

        BorderPane layout = new BorderPane();
        layout.setTop(menuBar);
        layout.setCenter(mainSplit);

        rebuildTrack();

        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double w = Math.min(1400, vb.getWidth() * 0.95);
        double h = Math.min(880, vb.getHeight() * 0.90);

        Scene scene = new Scene(layout, w, h, true);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (viewport.flyActive || viewport.povActive) viewport.moveKeys.remove(e.getCode());
            if (e.getCode() == KeyCode.S) slideKey = false;
            if (e.getCode() == KeyCode.C) cKey = false;
            if (e.getCode() == KeyCode.SHIFT) editor.shiftHover(false);
            if (e.getCode() == KeyCode.CONTROL) editor.ctrlHover(false);
        });
        updateTitle();   // "Untitled — FVD Coaster Designer" until saved/loaded
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
    }

    private void handleKey(KeyEvent e) {
        // S (held, unmodified) = slide constraint for node/handle drags;
        // never while a camera mode owns WASD (uncaptured POV does not).
        // MID-DRAG the modifier check is waived: Ctrl is already held for
        // snapping, and pressing S then means snap+slide, not Ctrl+S.
        if (e.getCode() == KeyCode.S && !viewport.flyActive
                && !(viewport.povActive && viewport.povCaptured)
                && (!e.isShortcutDown() || editor.isDragging())) {
            slideKey = true;   // not consumed: bound actions still fire
        }
        // C held = click-the-track-recenters (FVD++ muscle memory; the
        // Shift+click gesture stays too). Tracked like the S slide key.
        if (e.getCode() == KeyCode.C && !viewport.flyActive
                && !(viewport.povActive && viewport.povCaptured)
                && !e.isShortcutDown()) {
            cKey = true;
        }
        if (e.getCode() == KeyCode.SHIFT) editor.shiftHover(true);
        if (e.getCode() == KeyCode.CONTROL) editor.ctrlHover(true);
        if (viewport.flyActive || (viewport.povActive && viewport.povCaptured)) {   // camera owns movement keys
            KeyCode c = e.getCode();
            boolean movement = c == KeyCode.W || c == KeyCode.S
                    || c == KeyCode.SHIFT || c == KeyCode.CONTROL
                    || (viewport.flyActive && (c == KeyCode.A || c == KeyCode.D));
            if (movement) {
                viewport.moveKeys.add(c);
                e.consume();
                return;
            }
        }
        // Held drag modifiers must not complete unrelated combos: Ctrl held
        // for SNAPPING while S is pressed for SLIDING is not Ctrl+S save;
        // Ctrl+Z while pressing Z for the axis lock is not undo. While a
        // drag gesture is live — or S is already held as the slide key
        // (covers keys still down right after the mouse releases) — events
        // carrying any modifier fire NO actions. Bare keys (B/H/Z/X) keep
        // their mid-drag roles, and a deliberate Ctrl+S outside a drag
        // still saves (S with Ctrl never becomes the slide key at rest).
        if ((editor.isDragging() || slideKey)
                && (e.isControlDown() || e.isShiftDown() || e.isAltDown() || e.isMetaDown())) {
            return;
        }
        if      (matches("undo", e))    editor.undo();
        else if (matches("redo", e))    editor.redo();
        else if (matches("save", e))    saveDesign(false);
        else if (matches("load", e))    loadDesign();
        else if (matches("newfile", e)) newDesign();
        else if (matches("export", e))  exportNl2Csv();
        else if (matches("smooth", e))  editor.toggleSmooth();
        else if (matches("matchAll", e)) editor.matchBoundaryAll();
        else if (matches("flath", e))   editor.levelSelected();
        else if (matches("levelSel", e)) editor.toggleLevelLock();
        else if (matches("align", e))   editor.straightenSelected();
        else if (matches("alignLock", e)) editor.toggleStraightLock();
        else if (matches("lockv", e))   editor.toggleAxisLock(true);
        else if (matches("lockh", e))   editor.toggleAxisLock(false);
        else if (matches("delNode", e)) editor.deleteSelected();
        else if (matches("append", e))  editor.appendAction();
        else if (matches("pov", e))     togglePov();
        else if (matches("viewTop", e))  viewport.toggleOrthoView(true);
        else if (matches("viewSide", e)) viewport.toggleOrthoView(false);
        else if (matches("lane1", e))   editor.setActiveLane(0);
        else if (matches("lane2", e))   editor.setActiveLane(1);
        else if (matches("lane3", e))   editor.setActiveLane(2);
        else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT
              || e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
            // arrows step the selected node(s); OS key-repeat = continuous
            KeyCode a = e.getCode();
            int dt = a == KeyCode.LEFT ? -1 : a == KeyCode.RIGHT ? 1 : 0;
            int dv = a == KeyCode.UP ? 1 : a == KeyCode.DOWN ? -1 : 0;
            if (!editor.nudgeSelected(dt, dv, e.isControlDown())) return;
        }
        else return;
        e.consume();
    }

    /** Does the event match the user's current binding for this action?
     *  Combo format: optional Ctrl+/Alt+/Shift+/Meta+ prefixes, then a KeyCode name. */
    private boolean matches(String actionId, KeyEvent e) {
        boolean ctrl = false, alt = false, shift = false, meta = false;
        KeyCode key = null;
        for (String part : settings.shortcut(actionId).split("\\+")) {
            switch (part) {
                case "Ctrl"  -> ctrl = true;
                case "Alt"   -> alt = true;
                case "Shift" -> shift = true;
                case "Meta"  -> meta = true;
                default      -> key = KeyCode.getKeyCode(part);
            }
        }
        if (key == null) return false;
        return normalizeNumpad(e.getCode()) == key
            && e.isControlDown() == ctrl && e.isAltDown() == alt
            && e.isShiftDown() == shift && e.isMetaDown() == meta;
    }

    /** Numpad digits count as their top-row equivalents (pre-menu behavior). */
    static KeyCode normalizeNumpad(KeyCode c) {
        return switch (c) {
            case NUMPAD0 -> KeyCode.DIGIT0;
            case NUMPAD1 -> KeyCode.DIGIT1;
            case NUMPAD2 -> KeyCode.DIGIT2;
            case NUMPAD3 -> KeyCode.DIGIT3;
            case NUMPAD4 -> KeyCode.DIGIT4;
            case NUMPAD5 -> KeyCode.DIGIT5;
            case NUMPAD6 -> KeyCode.DIGIT6;
            case NUMPAD7 -> KeyCode.DIGIT7;
            case NUMPAD8 -> KeyCode.DIGIT8;
            case NUMPAD9 -> KeyCode.DIGIT9;
            default -> c;
        };
    }


    /** Collapse the graph panel to full-screen the 3D view, or restore it
     *  to where the divider last sat. The divider itself stays draggable. */
    private void toggleGraphPanel() {
        double pos = mainSplit.getDividerPositions()[0];
        if (pos < 0.97) {
            lastDividerPos = pos;
            mainSplit.setDividerPositions(1.0);
        } else {
            mainSplit.setDividerPositions(Math.min(lastDividerPos, 0.9));
        }
    }

    /** Minimal checkbox for the dark toolbar: a rounded square that fills
     *  when on — no checkmark glyph (the stock CheckBox draws one). */
    private HBox makeCheck(String text, java.util.function.Consumer<Boolean> onChange) {
        String off = "-fx-background-color: rgb(45,50,62); -fx-border-color: rgb(140,145,160);"
                   + " -fx-border-radius: 3; -fx-background-radius: 3;";
        String on  = "-fx-background-color: rgb(120,170,255); -fx-border-color: rgb(170,200,255);"
                   + " -fx-border-radius: 3; -fx-background-radius: 3;";
        Region box = new Region();
        box.setMinSize(14, 14);
        box.setMaxSize(14, 14);
        box.setStyle(off);
        Label lbl = new Label(text);
        lbl.setTextFill(Color.rgb(200, 205, 215));
        HBox h = new HBox(5, box, lbl);
        h.setAlignment(Pos.CENTER_LEFT);
        boolean[] state = { false };
        h.setOnMouseClicked(e -> {
            state[0] = !state[0];
            box.setStyle(state[0] ? on : off);
            onChange.accept(state[0]);
        });
        return h;
    }

    // =====================================================================
    // Menu bar + settings dialogs
    // =====================================================================

    void beginMenuSession() {
        menuUndoStack.clear();
        menuRedoStack.clear();
        menuSessionOpen = true;
    }

    void endMenuSession() {
        menuSessionOpen = false;
        menuUndoStack.clear();
        menuRedoStack.clear();
    }

    /** Record one menu edit as an {undo, redo} pair of setters. No-op when
     *  no session is open (e.g. loadDesign writing spinners) or while an
     *  undo/redo is itself being applied. */
    void pushMenuEdit(Runnable undo, Runnable redo) {
        if (!menuSessionOpen || menuApplying) return;
        menuUndoStack.push(new Runnable[]{ undo, redo });
        menuRedoStack.clear();
    }

    private void menuUndo() {
        if (menuUndoStack.isEmpty()) return;
        Runnable[] edit = menuUndoStack.pop();
        menuApplying = true;
        edit[0].run();
        menuApplying = false;
        menuRedoStack.push(edit);
    }

    private void menuRedo() {
        if (menuRedoStack.isEmpty()) return;
        Runnable[] edit = menuRedoStack.pop();
        menuApplying = true;
        edit[1].run();
        menuApplying = false;
        menuUndoStack.push(edit);
    }

    /** Route Ctrl+Z / Ctrl+Shift+Z inside a menu popup or dialog scene to
     *  the menu-local history (idempotent per scene). */
    void installMenuUndoKeys(Scene s) {
        if (s == null || !menuKeyScenes.add(s)) return;
        s.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.Z && e.isShortcutDown()) {
                if (e.isShiftDown()) menuRedo(); else menuUndo();
                e.consume();
            }
        });
    }

    /** Spinner listener that applies the value AND records it in the
     *  menu-local history (aesthetic per-coaster values: tie spacing). */
    private void wireMenuSpinner(Spinner<Double> sp, java.util.function.DoubleConsumer apply) {
        sp.valueProperty().addListener((obs, o, v) -> {
            if (docApplying) return;   // undo/redo writes the display only
            pushMenuEdit(() -> sp.getValueFactory().setValue(o),
                         () -> sp.getValueFactory().setValue(v));
            apply.accept(v);
            markDirty();
            rebuildTrack();
        });
    }

    /** Like wireMenuSpinner, but the value SHAPES THE TRACK PATH (heart,
     *  gauge, start, v0), so each change also lands in the DOCUMENT undo
     *  history (D51) — the snapshot is taken before apply, while the
     *  field still holds the old value. Menu-local Ctrl+Z replays skip
     *  the document push (menuApplying) so histories don't double up. */
    private void wireDocSpinner(Spinner<Double> sp, java.util.function.DoubleConsumer apply) {
        sp.valueProperty().addListener((obs, o, v) -> {
            if (docApplying) return;   // undo/redo writes the display only
            if (!menuApplying) {
                editor.pushUndoSnapshot();
                pushMenuEdit(() -> sp.getValueFactory().setValue(o),
                             () -> sp.getValueFactory().setValue(v));
            }
            apply.accept(v);
            markDirty();
            rebuildTrack();
        });
    }

    private void setRailMode(boolean solid) {
        railSolid = solid;
        (solid ? solidModeItem : gradientModeItem).setSelected(true);
        rebuildTrack();
    }

    private void changeRailMode(boolean solid) {
        if (railSolid == solid) return;
        pushMenuEdit(() -> setRailMode(!solid), () -> setRailMode(solid));
        setRailMode(solid);
        markDirty();
    }

    private MenuBar buildMenuBar() {
        // --- File: save/load ---
        MenuItem newItem = new MenuItem("New…");
        newItem.setOnAction(e -> newDesign());
        MenuItem saveItem = new MenuItem("Save");
        saveItem.setOnAction(e -> saveDesign(false));
        MenuItem saveAsItem = new MenuItem("Save As…");
        saveAsItem.setOnAction(e -> saveDesign(true));
        MenuItem loadItem = new MenuItem("Load…");
        loadItem.setOnAction(e -> loadDesign());
        MenuItem exportItem = new MenuItem("Export NL2 CSV…");
        exportItem.setOnAction(e -> exportNl2Csv());
        Menu fileMenu = new Menu("File", null, newItem, saveItem, saveAsItem,
            loadItem, new SeparatorMenuItem(), exportItem);

        // --- Edit: app-wide settings, persisted across runs ---
        MenuItem shortcutsItem = new MenuItem("Shortcuts…");
        shortcutsItem.setOnAction(e -> dialogs.showShortcutsDialog());
        MenuItem optionsItem = new MenuItem("Options…");
        optionsItem.setOnAction(e -> dialogs.showOptionsDialog());
        Menu editMenu = new Menu("Edit", null, shortcutsItem, optionsItem);

        // --- Coaster: per-coaster settings, saved in the .fvd file ---
        heartSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-3.0, 3.0, heartOffset, 0.1));
        heartSpinner.setPrefWidth(70);
        wireDocSpinner(heartSpinner, v -> heartOffset = v);
        gaugeSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.3, 2.5, railGauge, 0.1));
        gaugeSpinner.setPrefWidth(70);
        wireDocSpinner(gaugeSpinner, v -> railGauge = v);
        tieSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.2, 10.0, tieSpacing, 0.1));
        tieSpinner.setPrefWidth(70);
        wireMenuSpinner(tieSpinner, v -> tieSpacing = v);
        // placement spinners edit the ACTIVE piece (v12.0) — piece 0
        // for a single-piece document, exactly the old "Start" behavior
        TrackPiece piece0 = coaster.pieces.get(0);
        startXSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.x, 1));
        startXSpinner.setPrefWidth(70);
        wireDocSpinner(startXSpinner, v -> activePieceRef().x = v);
        startYSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.y, 1));
        startYSpinner.setPrefWidth(70);
        wireDocSpinner(startYSpinner, v -> activePieceRef().y = v);
        startZSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.z, 1));
        startZSpinner.setPrefWidth(70);
        wireDocSpinner(startZSpinner, v -> activePieceRef().z = v);
        yawSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360, piece0.yawDeg, 5));
        yawSpinner.setPrefWidth(70);
        wireDocSpinner(yawSpinner, v -> activePieceRef().yawDeg = v);
        startSpeedSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 100, coaster.v0, 1));
        startSpeedSpinner.setPrefWidth(70);
        // the spinner is v0 AND the sole section's design speed — they
        // stay coupled until Phase 3 gives dynamics its own controls
        wireDocSpinner(startSpeedSpinner, v -> {
            coaster.v0 = v;
            coaster.soleSection().vDesign(v);
        });

        Label heartLbl = new Label("Heart (m)");
        heartLbl.setTextFill(Color.BLACK);   // menu popup is light; default fill washed out
        Label gaugeLbl = new Label("Gauge (m)");
        gaugeLbl.setTextFill(Color.BLACK);
        Label tieLbl = new Label("Tie every (m)");
        tieLbl.setTextFill(Color.BLACK);
        // "Piece" rows (v12.0) live in the toolbar's Placement dropdown
        // since v13.2 (user: piece settings belong by the graph); they
        // are built here with the other spinners and stored as fields
        Label startLbl = new Label("Piece x/y/z (m)");
        startLbl.setTextFill(Color.BLACK);
        Label yawLbl = new Label("Piece yaw (°)");
        yawLbl.setTextFill(Color.BLACK);
        Label speedLbl = new Label("Start speed (m/s)");
        speedLbl.setTextFill(Color.BLACK);
        HBox heartRow = new HBox(8, heartLbl, heartSpinner);
        heartRow.setAlignment(Pos.CENTER_LEFT);
        HBox gaugeRow = new HBox(8, gaugeLbl, gaugeSpinner);
        gaugeRow.setAlignment(Pos.CENTER_LEFT);
        HBox tieRow = new HBox(8, tieLbl, tieSpinner);
        tieRow.setAlignment(Pos.CENTER_LEFT);
        placementRow = new HBox(8, startLbl, startXSpinner, startYSpinner, startZSpinner);
        placementRow.setAlignment(Pos.CENTER_LEFT);
        yawPlacementRow = new HBox(8, yawLbl, yawSpinner);
        yawPlacementRow.setAlignment(Pos.CENTER_LEFT);

        // bridge placement panel (v13.5): while a live bridge is
        // active, the Placement dropdown edits the NEXT piece through
        // it — a mode choice plus the controls each mode leaves free
        Label bridgeModeLbl = new Label("Next piece placement");
        bridgeModeLbl.setTextFill(Color.BLACK);
        ToggleGroup modeGroup = new ToggleGroup();
        bridgeModePY = new RadioButton("Optimize position and orientation");
        bridgeModeP = new RadioButton("Optimize position");
        bridgeModeN = new RadioButton("No optimization");
        Tooltip.install(bridgeModePY, new Tooltip(
            "Places the next piece on the track's natural continuation\n"
            + "and rotates its UNLOCKED axes (the padlocks on yaw/pitch/\n"
            + "roll) to meet it; locked axes keep their values and the\n"
            + "bridge bends to them. Grayed out while all three are\n"
            + "locked — unlock at least one axis to optimize orientation."));
        RadioButton[] modeBtns = { bridgeModePY, bridgeModeP, bridgeModeN };
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            modeBtns[m].setToggleGroup(modeGroup);
            modeBtns[m].setOnAction(e -> {
                if (docApplying) return;
                editor.setBridgeOptMode(mode);
                refreshBridgeRows();
            });
        }
        bridgeModeRow = new VBox(4, bridgeModeLbl,
            bridgeModePY, bridgeModeP, bridgeModeN);
        bridgeGapLbl.setStyle("");   // popup is light; the toolbar
        bridgeGapLbl.setTextFill(Color.BLACK);   // styling was for dark
        // "Optimize length" (v13.8, D82): the length becomes the knee
        // of the smoothness curve — the shortest length at which the
        // transition stops improving; the spinner disables while on.
        // Switching INTO an optimize mode turns it on (user spec).
        bridgeOptLenCheck = new CheckBox("Optimize length");
        bridgeOptLenCheck.setTextFill(Color.BLACK);
        bridgeOptLenCheck.setFocusTraversable(false);
        bridgeOptLenCheck.setOnAction(e -> {
            if (docApplying) return;
            editor.setBridgeOptLen(bridgeOptLenCheck.isSelected());
            refreshBridgeRows();
        });
        Tooltip.install(bridgeOptLenCheck, new Tooltip(
            "Pick the length automatically: the shortest bridge whose\n"
            + "transition is (near-)as smooth as any longer one. Pieces\n"
            + "split at a smooth spot collapse to zero — the app then\n"
            + "offers to connect them directly."));
        bridgeLenRow = new HBox(8, bridgeGapLbl, bridgeGapSpinner,
            bridgeOptLenCheck);
        bridgeLenRow.setAlignment(Pos.CENTER_LEFT);
        // (v13.10-dev briefly exposed three per-bridge optimizer
        // spinners here — REMOVED in the same version, D84: the user
        // found them confusing and unpredictable; the optimizer's
        // constants are internal again)
        Label bYawLbl = new Label("Next piece yaw (°)");
        bYawLbl.setTextFill(Color.BLACK);
        bridgeYawSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360, 0, 5));
        bridgeYawSpinner.setPrefWidth(90);
        bridgeYawSpinner.setEditable(true);
        bridgeYawSpinner.getEditor().setTextFormatter(signedNumFormatter());
        bridgeYawSpinner.valueProperty().addListener((obs, o, v) -> {
            if (!docApplying) editor.setBridgeTargetOrient(0, v);
        });
        // orientation LOCKS (v13.13, D87): a locked axis is frozen —
        // no edits — and the optimization MEETS it; an unlocked axis
        // is editable, and in optimize-orientation mode the march
        // ALIGNS it instead. Defaults: yaw unlocked, pitch + roll
        // locked = the historic "position and yaw".
        bridgeLockYawBtn = bridgeLockBtn(0);
        bridgeLockPitchBtn = bridgeLockBtn(1);
        bridgeLockRollBtn = bridgeLockBtn(2);
        bridgeYawRow = new HBox(8, bYawLbl, bridgeYawSpinner,
            bridgeLockYawBtn);
        bridgeYawRow.setAlignment(Pos.CENTER_LEFT);
        Label bPitchLbl = new Label("Next piece pitch (°)");
        bPitchLbl.setTextFill(Color.BLACK);
        bridgePitchSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-85, 85, 0, 1));
        bridgePitchSpinner.setPrefWidth(90);
        bridgePitchSpinner.setEditable(true);
        bridgePitchSpinner.getEditor().setTextFormatter(signedNumFormatter());
        bridgePitchSpinner.valueProperty().addListener((obs, o, v) -> {
            if (!docApplying) editor.setBridgeTargetOrient(1, v);
        });
        bridgePitchRow = new HBox(8, bPitchLbl, bridgePitchSpinner,
            bridgeLockPitchBtn);
        bridgePitchRow.setAlignment(Pos.CENTER_LEFT);
        Label bRollLbl = new Label("Next piece roll (°)");
        bRollLbl.setTextFill(Color.BLACK);
        bridgeRollSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360, 0, 1));
        bridgeRollSpinner.setPrefWidth(90);
        bridgeRollSpinner.setEditable(true);
        bridgeRollSpinner.getEditor().setTextFormatter(signedNumFormatter());
        bridgeRollSpinner.valueProperty().addListener((obs, o, v) -> {
            if (!docApplying) editor.setBridgeTargetOrient(2, v);
        });
        bridgeRollRow = new HBox(8, bRollLbl, bridgeRollSpinner,
            bridgeLockRollBtn);
        bridgeRollRow.setAlignment(Pos.CENTER_LEFT);
        Label bPosLbl = new Label("Next piece x/y/z (m)");
        bPosLbl.setTextFill(Color.BLACK);
        bridgeXSpinner = bridgePosSpinner(0);
        bridgeYSpinner = bridgePosSpinner(1);
        bridgeZSpinner = bridgePosSpinner(2);
        bridgePosRow = new HBox(8, bPosLbl,
            bridgeXSpinner, bridgeYSpinner, bridgeZSpinner);
        bridgePosRow.setAlignment(Pos.CENTER_LEFT);
        HBox speedRow = new HBox(8, speedLbl, startSpeedSpinner);
        speedRow.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup colorMode = new ToggleGroup();
        gradientModeItem = new RadioMenuItem("Rail color: G-force gradient");
        gradientModeItem.setToggleGroup(colorMode);
        gradientModeItem.setOnAction(e -> changeRailMode(false));
        solidModeItem = new RadioMenuItem("Rail color: solid");
        solidModeItem.setToggleGroup(colorMode);
        solidModeItem.setOnAction(e -> changeRailMode(true));
        (railSolid ? solidModeItem : gradientModeItem).setSelected(true);

        MenuItem colorItem = new MenuItem("Rail colors (solid & gradient)…");
        colorItem.setOnAction(e -> dialogs.showRailColorDialog());

        // reversal is a PIECE operation (v12.1, D71 — was a per-section
        // badge check): flips the travel direction of the active piece,
        // track staying put (level seams exactly; others re-join with
        // the usual yaw-only honesty). Lives in the Placement dropdown.
        reverseItem = new MenuItem("Reverse piece (flip travel direction)");
        reverseItem.setOnAction(e -> editor.reverseActivePiece());

        coasterMenu = new Menu("Coaster", null,
            new CustomMenuItem(heartRow, false),
            new CustomMenuItem(gaugeRow, false),
            new CustomMenuItem(tieRow, false),
            new CustomMenuItem(speedRow, false),
            new SeparatorMenuItem(),
            gradientModeItem,
            solidModeItem,
            colorItem);
        // menu-local undo/redo lives exactly as long as the menu is open;
        // the popup has its own Scene, so the Ctrl+Z filter goes there
        coasterMenu.setOnShown(e -> {
            beginMenuSession();
            installMenuUndoKeys(heartRow.getScene());
        });
        coasterMenu.setOnHidden(e -> endMenuSession());

        // --- Help: clicking the menu itself opens the dialog directly
        // (a Label graphic takes the click; the empty menu never opens) ---
        Menu helpMenu = new Menu();
        Label helpLbl = new Label("Help");
        helpLbl.setOnMouseClicked(e -> dialogs.showHelpDialog());
        helpMenu.setGraphic(helpLbl);

        return new MenuBar(fileMenu, editMenu, coasterMenu, helpMenu);
    }


    // POV/fly cameras, meshes, grid: Viewport3D.java (D54).


    /** Built from the live bindings so it stays correct after rebinding. */
    String shortcutHint() {
        return String.format(
            "[%s/%s/%s lane · %s smooth · S=slide Ctrl=snap · %s append · %s POV · %s/%s save/load · %s undo]",
            settings.shortcut("lane1"), settings.shortcut("lane2"), settings.shortcut("lane3"),
            settings.shortcut("smooth"), settings.shortcut("append"), settings.shortcut("pov"),
            settings.shortcut("save"), settings.shortcut("load"), settings.shortcut("undo"));
    }


    // =====================================================================
    // Save / load
    // =====================================================================

    /** Save (Ctrl+S) writes straight to the open file; Save As — or a
     *  never-saved document — asks where. Returns true if written. */
    private boolean saveDesign(boolean saveAs) {
        File f = currentFile;
        if (saveAs || f == null) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save coaster design");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("FVD design (*.fvd)", "*.fvd"));
            fc.setInitialFileName(currentFile != null ? currentFile.getName() : "coaster.fvd");
            f = fc.showSaveDialog(stage);
            if (f == null) return false;
        }
        try (PrintWriter out = new PrintWriter(f, "UTF-8")) {
            // FVDD3 via DesignIO (Phase 1, D50) — cosmetics travel as
            // plain doubles so the writer stays JavaFX-free
            DesignIO.save(coaster, uiCosmetics(), out);
            currentFile = f;
            dirty = false;
            updateTitle();
            statsLabel.setText("Saved: " + f.getName());
            return true;
        } catch (Exception ex) {
            statsLabel.setText("Save failed: " + ex.getMessage());
            return false;
        }
    }

    /** Unsaved changes gate for New/Load: offer Save / Don't Save /
     *  Cancel. Returns true when it is OK to discard the current state. */
    private boolean confirmSaveDiscard() {
        if (!dirty) return true;
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType dont = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
            "Save changes to " + docName() + "?", save, dont, ButtonType.CANCEL);
        a.initOwner(stage);
        a.setTitle("Unsaved changes");
        a.setHeaderText(null);
        ButtonType r = a.showAndWait().orElse(ButtonType.CANCEL);
        if (r == save) return saveDesign(false);   // cancelled chooser = stay
        return r == dont;
    }

    /** File > New (Ctrl+N): offer to save, then start a fresh document —
     *  one 1 s force/time section holding 1 g / 0 / 0, default settings,
     *  empty history, "Untitled" title. */
    private void newDesign() {
        if (!confirmSaveDiscard()) return;
        docApplying = true;   // spinners get set programmatically below
        List<List<BezierBaker.Node>> nl = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> lane = new ArrayList<>(2);
            lane.add(new BezierBaker.Node(0, i == 0 ? 1 : 0));
            lane.add(new BezierBaker.Node(1, i == 0 ? 1 : 0));
            BezierBaker.applyDefaultHandles(lane);
            nl.add(lane);
        }
        coaster.pieces.clear();
        TrackPiece piece = new TrackPiece(0, 21, 0, 0);
        piece.sections.add(new ForceSection(nl, 26));
        coaster.pieces.add(piece);
        coaster.v0 = 26;
        coaster.speedRegions.clear();

        DesignIO.Cosmetics def = DesignIO.Cosmetics.defaults();
        heartOffset = def.heart();
        railGauge = def.gauge();
        tieSpacing = def.tiespace();
        railSolid = def.railSolid();
        railColor = Color.color(def.railColor()[0], def.railColor()[1], def.railColor()[2]);
        for (int i = 0; i < 4; i++) {
            gradColors[i] = Color.color(def.gradColors()[i * 3],
                def.gradColors()[i * 3 + 1], def.gradColors()[i * 3 + 2]);
        }
        System.arraycopy(def.gradStops(), 0, gradStops, 0, 4);

        heartSpinner.getValueFactory().setValue(heartOffset);
        gaugeSpinner.getValueFactory().setValue(railGauge);
        tieSpinner.getValueFactory().setValue(tieSpacing);
        startXSpinner.getValueFactory().setValue(0.0);
        startYSpinner.getValueFactory().setValue(21.0);
        startZSpinner.getValueFactory().setValue(0.0);
        yawSpinner.getValueFactory().setValue(0.0);
        startSpeedSpinner.getValueFactory().setValue(26.0);
        (railSolid ? solidModeItem : gradientModeItem).setSelected(true);
        docApplying = false;

        editor.clearHistory();
        editor.resetSections();
        editor.clearSelection();
        updatePieceButton();
        currentFile = null;
        dirty = false;
        updateTitle();
        rebuildTrack();      // spans first (stale ones belong to the
        editor.refresh();    // discarded document)
        statsLabel.setText("New design");
    }

    /** Snapshot the UI-side per-coaster cosmetics for DesignIO. */
    private DesignIO.Cosmetics uiCosmetics() {
        double[] rail = { railColor.getRed(), railColor.getGreen(), railColor.getBlue() };
        double[] grad = new double[12];
        for (int i = 0; i < 4; i++) {
            grad[i * 3]     = gradColors[i].getRed();
            grad[i * 3 + 1] = gradColors[i].getGreen();
            grad[i * 3 + 2] = gradColors[i].getBlue();
        }
        return new DesignIO.Cosmetics(heartOffset, railGauge, tieSpacing,
            railSolid, rail, grad, gradStops.clone());
    }

    private void loadDesign() {
        if (!confirmSaveDiscard()) return;   // same courtesy as File > New
        FileChooser fc = new FileChooser();
        fc.setTitle("Load coaster design");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("FVD design (*.fvd)", "*.fvd"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try (BufferedReader in = new BufferedReader(new FileReader(f, java.nio.charset.StandardCharsets.UTF_8))) {
            // FVDD3/2/1 parsing lives in DesignIO (Phase 1, D50); legacy
            // flat files arrive as one piece holding one force section
            DesignIO.LoadResult r = DesignIO.load(in);

            editor.pushUndoSnapshot(); // loading is undoable
            coaster.pieces.clear();
            coaster.pieces.addAll(r.coaster().pieces);
            coaster.v0 = r.coaster().v0;
            coaster.speedRegions.clear();
            coaster.speedRegions.addAll(r.coaster().speedRegions);
            editor.resetSections();   // active section 0, lanes re-pointed

            DesignIO.Cosmetics cos = r.cosmetics();
            heartOffset = cos.heart();
            railGauge = cos.gauge();
            tieSpacing = cos.tiespace();
            railSolid = cos.railSolid();
            railColor = Color.color(cos.railColor()[0], cos.railColor()[1], cos.railColor()[2]);
            for (int i = 0; i < 4; i++) {
                gradColors[i] = Color.color(cos.gradColors()[i * 3],
                    cos.gradColors()[i * 3 + 1], cos.gradColors()[i * 3 + 2]);
            }
            System.arraycopy(cos.gradStops(), 0, gradStops, 0, 4);

            heartSpinner.getValueFactory().setValue(heartOffset);
            gaugeSpinner.getValueFactory().setValue(railGauge);
            tieSpinner.getValueFactory().setValue(tieSpacing);
            startSpeedSpinner.getValueFactory().setValue(coaster.v0);
            syncPlacementSpinners();   // active piece (0 after reset)
            updatePieceButton();
            (railSolid ? solidModeItem : gradientModeItem).setSelected(true);
            editor.clearSelection();
            rebuildTrack();      // spans before the first graph layout:
            editor.refresh();    // s/c strip widths come from the compile
            currentFile = f;
            dirty = false;
            updateTitle();
            statsLabel.setText("Loaded: " + f.getName());
        } catch (Exception ex) {
            statsLabel.setText("Load failed: " + ex.getMessage());
        }
    }

    /** File > Export NL2 CSV (or Ctrl+E): resample the current track for
     *  NoLimits 2's CSV import. The two settings (which line, spacing) are
     *  app-wide and persist across runs; the math lives in Nl2CsvExporter. */
    private void exportNl2Csv() {
        if (lastSamples == null || lastSamples.size() < 2) return;

        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Export NL2 CSV");

        ToggleGroup lineGroup = new ToggleGroup();
        RadioButton heartBtn = new RadioButton("Heartline (rider path)");
        heartBtn.setToggleGroup(lineGroup);
        RadioButton spineBtn = new RadioButton("Rail center (NL2 track spline)");
        spineBtn.setToggleGroup(lineGroup);
        (settings.nl2Heartline() ? heartBtn : spineBtn).setSelected(true);
        lineGroup.selectedToggleProperty().addListener((obs, o, sel) -> {
            boolean heart = sel == heartBtn;
            pushMenuEdit(() -> { (heart ? spineBtn : heartBtn).setSelected(true); settings.setNl2Heartline(!heart); },
                         () -> { (heart ? heartBtn : spineBtn).setSelected(true); settings.setNl2Heartline(heart); });
            settings.setNl2Heartline(heart);
        });

        Spinner<Double> segSp = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(
            0.1, 10.0, settings.nl2SegLen(), 0.1));
        segSp.setEditable(true);
        segSp.setPrefWidth(80);
        dialogs.wireOptionSpinner(segSp, settings::setNl2SegLen);

        Label note = new Label("Heartline exports the integrated force path (for "
            + "NL2 heartlining workflows); rail center exports the spline NL2 "
            + "builds its track around, matching the rails drawn here. Spacing is "
            + "rounded so the ride divides into equal segments.");
        note.setWrapText(true);
        note.setMaxWidth(340);
        note.setStyle("-fx-text-fill: #666;");

        Button exportBtn = new Button("Export…");
        exportBtn.setDefaultButton(true);
        exportBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Export NL2 CSV");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("NL2 track (*.csv)", "*.csv"));
            fc.setInitialFileName("coaster.csv");
            File f = fc.showSaveDialog(dlg);
            if (f == null) return;
            try (PrintWriter out = new PrintWriter(f, "UTF-8")) {
                Nl2CsvExporter.Result r = Nl2CsvExporter.export(
                    lastSamples, heartBtn.isSelected(), heartOffset, segSp.getValue());
                out.print(r.csv());
                statsLabel.setText(String.format(Locale.US,
                    "Exported: %s (%d points, %.2f m spacing, %.0f m)",
                    f.getName(), r.points(), r.step(), r.length()));
                dlg.close();
            } catch (Exception ex) {
                statsLabel.setText("Export failed: " + ex.getMessage());
            }
        });
        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(e -> dlg.close());
        HBox buttons = new HBox(8, exportBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        HBox segRow = new HBox(8, new Label("Segment length (m)"), segSp,
            dialogs.optionReset(() -> segSp.getValueFactory().setValue(AppSettings.NL2_SEGLEN_DEFAULT)));
        segRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, new Label("Track points:"), heartBtn, spineBtn,
            segRow, note, buttons);
        box.setPadding(new Insets(14));

        Scene sc = new Scene(box);
        installMenuUndoKeys(sc);
        dlg.setScene(sc);
        beginMenuSession();
        dlg.setOnHidden(e -> endMenuSession());
        dlg.show();
    }

    // The graph editor lives in GraphEditor.java (Phase 1 split, D52).

    // =====================================================================


    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Does the node's on-screen bounding box (padded a few px, so the
     *  seam between the Resulting button and its popup never counts as
     *  "outside") contain this screen point? */
    private static boolean screenContains(javafx.scene.Node n, Point2D p) {
        Bounds b = n.localToScreen(n.getBoundsInLocal());
        if (b == null) return false;
        double pad = 6;
        return p.getX() >= b.getMinX() - pad && p.getX() <= b.getMaxX() + pad
            && p.getY() >= b.getMinY() - pad && p.getY() <= b.getMaxY() + pad;
    }

    public static void main(String[] args) {
        launch(args);
    }
}

