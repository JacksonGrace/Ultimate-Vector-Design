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
    final List<List<BezierBaker.Node>> lanes = new ArrayList<>();
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
    private double railGauge = 1.0;    // m between rail centers
    private double tieSpacing = 1.0;   // m of track between cross ties

    // ---------- document-level undo state (Phase 1 milestone B, D51) ----------
    // one snapshot = everything that shapes the RIDDEN TRACK: the lanes plus
    // heart, gauge, start placement and v0 (user rule: "everything that
    // changes the track path, not just aesthetics"). Tie spacing and colors
    // stay menu-local-undo only.
    record DocState(List<List<BezierBaker.Node>> lanes, double heart, double gauge,
                    double px, double py, double pz, double v0) {}

    private boolean docApplying = false;   // suppress spinner listeners while
                                           // an undo/redo writes them back

    DocState snapshotDoc() {
        List<List<BezierBaker.Node>> copy = new ArrayList<>(3);
        for (List<BezierBaker.Node> lane : lanes) {
            List<BezierBaker.Node> laneCopy = new ArrayList<>(lane.size());
            for (BezierBaker.Node n : lane) laneCopy.add(n.copy());
            copy.add(laneCopy);
        }
        TrackPiece p = coaster.pieces.get(0);
        return new DocState(copy, heartOffset, railGauge, p.x, p.y, p.z, coaster.v0);
    }

    /** Restore a document snapshot: lanes, path settings, and the spinner
     *  displays (their listeners are suppressed — fields are set here). */
    void applyDocState(DocState s) {
        docApplying = true;
        for (int i = 0; i < 3; i++) {
            lanes.get(i).clear();
            lanes.get(i).addAll(s.lanes().get(i));
        }
        heartOffset = s.heart();
        railGauge = s.gauge();
        TrackPiece p = coaster.pieces.get(0);
        p.x = s.px(); p.y = s.py(); p.z = s.pz();
        coaster.v0 = s.v0();
        coaster.soleSection().vDesign = s.v0();
        heartSpinner.getValueFactory().setValue(s.heart());
        gaugeSpinner.getValueFactory().setValue(s.gauge());
        startXSpinner.getValueFactory().setValue(s.px());
        startYSpinner.getValueFactory().setValue(s.py());
        startZSpinner.getValueFactory().setValue(s.pz());
        startSpeedSpinner.getValueFactory().setValue(s.v0());
        docApplying = false;
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
    private boolean railSolid = false; // false = vertical-G gradient, true = solid color
    private Color railColor = Color.rgb(220, 60, 50);
    // G-force gradient stops (per-coaster, saved in .fvd): each stop is a
    // COLOR at a G VALUE; flat outside the ends, lerped between stops
    private final Color[] gradColors = {
        Color.color(0.2, 0.4, 1.0),
        Color.color(0.2, 0.9, 0.3),
        Color.color(1.0, 0.9, 0.1),
        Color.color(1.0, 0.15, 0.1),
    };
    private final double[] gradStops = { 0, 1, 3, 5 };
    private static final double RAIL_RADIUS = 0.07; // 140 mm diameter
    private static final int RAIL_SIDES = 8;
    private static final int SAMPLE_STRIDE = 3;
    private static final double G_MIN = -1.0, G_MAX = 5.0;
    static final double INTEGRATE_DT = 0.01;

    // lane accent colors, shared by the graph curves and the lane buttons
    static final Color[] LANE_COLORS = {
        Color.rgb(120, 220, 130), Color.rgb(120, 170, 255), Color.rgb(255, 170, 90)
    };
    // TIME is yellow throughout the graph UI: the "Time (s)" axis label, the
    // vertical (time-pinning) guide line, the POV position marker
    static final Color TIME_COLOR = Color.rgb(235, 205, 90);

    // ---------- 3D scene state ----------
    private final Group trackGroup = new Group();
    private final Group world = new Group();
    private final Translate camPivot = new Translate();  // orbit centre (moves after flying)
    private final Rotate camYaw = new Rotate(-30, Rotate.Y_AXIS);
    private final Rotate camPitch = new Rotate(-20, Rotate.X_AXIS);
    // initial orbit camera distance, metres (user-tuned: was 260, "much
    // closer" requested — scroll zooms out to the whole ride quickly)
    private static final double ORBIT_DIST0 = 60;
    private final Translate camZoom = new Translate(0, 0, -ORBIT_DIST0);
    // ortho top/side views: PSEUDO-orthographic — JavaFX ParallelCamera is
    // unusable for 3D scenes (pixel-unit view volume, no depth control), so
    // ortho = the same PerspectiveCamera at 1° FOV moved ~72x farther out,
    // keeping the apparent size (visible height = 2·d·tan(fov/2))
    private static final double ORTHO_FOV = 1.0;
    private static final double ORTHO_FAR_CLIP = 200000, PERSP_FAR_CLIP = 5000;
    private boolean orthoView = false;
    private boolean orthoTop = false;              // which preset is active
    private double savedYaw, savedPitch, savedZoom; // perspective pose to restore
    private double lastMouseX, lastMouseY;
    private boolean worldCentered = false;
    private Group gridGroup;                    // floor grid, recentered on the camera (LOD)
    private double gridCamX0, gridCamZ0;        // scene->world offset for camera positions

    private SubScene sub;
    private MeshView heartMesh;   // neon heartline; hidden while riding POV
    private PerspectiveCamera orbitCam, povCam;
    boolean povActive = false;
    private boolean povCaptured = false;   // right-click: hide mouse, W/S ride;
                                           // uncaptured POV leaves W/S (and the
                                           // S slide key) to the graph editor
    double povTime;        // current ride position (s); W/S move it
    private long povLastNanos;
    private AnimationTimer povTimer;
    List<FVDIntegrator.Sample> lastSamples;
    double[][] resultRates;   // {t, pitch, yaw, roll} deg/s per sample
    private final Group markerGroup = new Group();   // on-track node markers
    private String markerSig = "";                   // skip no-op rebuilds

    // WASD/mouse fly camera (toggled with right-click in the 3D view)
    private static final double FLY_SPEED = 25;        // m/s base
    private PerspectiveCamera flyCam;
    private final Translate flyPos = new Translate();
    private final Rotate flyYaw = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate flyPitch = new Rotate(0, Rotate.X_AXIS);
    private boolean flyActive = false;
    private final Set<KeyCode> moveKeys = new HashSet<>();
    private AnimationTimer flyTimer;
    private long flyLastNanos;
    private Robot flyRobot;

    Stage stage;
    private final Label statsLabel = new Label();
    private GraphEditor editor;
    final ToggleButton[] laneButtons = new ToggleButton[3];
    boolean rippleT, rippleV;   // ripple-edit toggles (time / value)
    boolean slideKey;           // S held: slide nodes/handles along a line
    private boolean cKey;               // C held: click the track = recenter orbit
    private Spinner<Double> heartSpinner, gaugeSpinner, tieSpinner;
    private Spinner<Double> startXSpinner, startYSpinner, startZSpinner;
    Spinner<Double> startSpeedSpinner;
    private SplitPane mainSplit;           // 3D view over graph panel
    private double lastDividerPos = 0.6;   // restore point for the collapse toggle
    private RadioMenuItem gradientModeItem, solidModeItem;

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

        // ----- 3D subscene -----
        orbitCam = new PerspectiveCamera(true);
        orbitCam.setNearClip(0.1);
        orbitCam.setFarClip(PERSP_FAR_CLIP);
        // orbit pairs with FLY (they hand off to each other); POV has its own
        orbitCam.setFieldOfView(settings.flyFov());
        Group cameraRig = new Group(orbitCam);
        cameraRig.getTransforms().addAll(camPivot, camYaw, camPitch, camZoom);

        povCam = new PerspectiveCamera(true);
        povCam.setNearClip(0.05);
        povCam.setFarClip(5000);
        povCam.setFieldOfView(settings.fov());
        Group povHolder = new Group(povCam);

        flyCam = new PerspectiveCamera(true);
        flyCam.setNearClip(0.05);
        flyCam.setFarClip(5000);
        // fly has its OWN FOV setting; orbit + POV share the track-view one
        flyCam.setFieldOfView(settings.flyFov());
        Group flyHolder = new Group(flyCam);
        flyHolder.getTransforms().addAll(flyPos, flyYaw, flyPitch);

        AmbientLight ambient = new AmbientLight(Color.rgb(120, 120, 130));
        PointLight sun = new PointLight(Color.WHITE);
        sun.setTranslateX(-150);
        sun.setTranslateY(-300);
        sun.setTranslateZ(-100);

        // povHolder lives inside `world` so the centering translate applies
        // to the POV camera exactly as it does to the track mesh
        world.getChildren().addAll(trackGroup, povHolder);
        Group root3D = new Group(world, cameraRig, flyHolder, ambient, sun);

        sub = new SubScene(root3D, 800, 400, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(15, 18, 24));
        sub.setCamera(orbitCam);

        sub.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                if (orthoView) {
                    // in a snapped ortho view, right-DRAG pans (handled in
                    // the drag handler) — no fly handoff, the view stays
                    lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
                    return;
                }
                if (povActive) {
                    // capture/release the POV ride controls (no mouse look —
                    // orientation follows the track)
                    povCaptured = !povCaptured;
                    moveKeys.clear();
                    sub.setCursor(povCaptured ? Cursor.NONE : Cursor.DEFAULT);
                } else {
                    toggleFly();
                }
                // refresh the drag anchor: right-drag events that follow the
                // fly exit must not orbit from a stale position (view jumped)
                lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY && !povActive && !flyActive) {
                if (e.isControlDown()) {
                    // Ctrl+click the track = insert a node at that ride time
                    insertNodeAtTrack(e.getScreenX(), e.getScreenY());
                } else if (e.isShiftDown() || cKey) {
                    // Shift+click or C+click = re-center the orbit on the
                    // track piece under the cursor
                    snapPivotToTrack(e.getScreenX(), e.getScreenY());
                }
            }
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
        });
        sub.setOnMouseDragged(e -> {
            if (povActive || flyActive) return;
            if (orthoView && e.isSecondaryButtonDown()) {
                // right-drag in a snapped view = grab-pan in the view plane
                // (the world follows the mouse); the ortho view survives
                double d = -camZoom.getZ();
                double upp = 2 * d * Math.tan(Math.toRadians(ORTHO_FOV / 2))
                           / Math.max(1, sub.getHeight());   // world m per px
                double dx = e.getSceneX() - lastMouseX, dy = e.getSceneY() - lastMouseY;
                Rotate ry = new Rotate(camYaw.getAngle(), Rotate.Y_AXIS);
                Rotate rx = new Rotate(camPitch.getAngle(), Rotate.X_AXIS);
                Point3D right = ry.transform(rx.transform(new Point3D(1, 0, 0)));
                Point3D down = ry.transform(rx.transform(new Point3D(0, 1, 0)));
                camPivot.setX(camPivot.getX() - (right.getX() * dx + down.getX() * dy) * upp);
                camPivot.setY(camPivot.getY() - (right.getY() * dx + down.getY() * dy) * upp);
                camPivot.setZ(camPivot.getZ() - (right.getZ() * dx + down.getZ() * dy) * upp);
                updateGridLOD(camPivot.getX() + gridCamX0, camPivot.getZ() + gridCamZ0);
                lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
                return;
            }
            // primary OR middle drags orbit (middle: CAD muscle memory, and
            // frees primary for future viewport editing) — never secondary:
            // the right-drag that exits fly otherwise fell through here with
            // a huge stale delta (jump bug)
            if (!e.isPrimaryButtonDown() && !e.isMiddleButtonDown()) return;
            // rotating away from a snapped ortho view returns to the
            // perspective lens but keeps the current angles
            if (orthoView) exitOrthoKeepView();
            // orbit shares the mouse-sensitivity setting (fly's 0.15 default
            // maps to the original 0.3 orbit feel via the 2x factor)
            double s = settings.flySens() * 2;
            double dx = e.getSceneX() - lastMouseX, dy = e.getSceneY() - lastMouseY;
            camYaw.setAngle(camYaw.getAngle() + dx * s);
            camPitch.setAngle(clamp(camPitch.getAngle() - dy * s, -89, 89));
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
        });
        sub.setOnScroll(e -> {
            // SHIFT+wheel arrives as HORIZONTAL scroll on Windows (deltaY 0,
            // deltaX set) — fall back so scrolling keeps working with Shift
            double wheel = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
            if (flyActive) {
                // burst move along the view direction (much faster than WASD)
                Point3D fwd = flyForward();
                double d = wheel * 0.5;
                flyPos.setX(flyPos.getX() + fwd.getX() * d);
                flyPos.setY(flyPos.getY() + fwd.getY() * d);
                flyPos.setZ(flyPos.getZ() + fwd.getZ() * d);
                updateGridLOD(flyPos.getX() + gridCamX0, flyPos.getZ() + gridCamZ0);
            } else if (povActive) {
                // burst move along the TRACK (fast, like fly-mode scroll)
                if (lastSamples == null || lastSamples.isEmpty()) return;
                double rideTime = lastSamples.get(lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                povTime = ((povTime + wheel * 0.02) % rideTime + rideTime) % rideTime;
                if (settings.povMarker()) editor.refresh();
            } else if (orthoView) {
                // ortho zoom is multiplicative (the camera sits ~72x out;
                // the additive step and its -1500 clamp would wreck it)
                double f = Math.pow(1.0015, -wheel);
                camZoom.setZ(clamp(camZoom.getZ() * f, -ORTHO_FAR_CLIP * 0.5, -200));
            } else {
                camZoom.setZ(clamp(camZoom.getZ() + wheel * 0.5, -1500, -10));
            }
        });
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, this::flyLook);
        sub.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::flyLook);

        // POV: W rides forward at the simulated speed, S backward;
        // SHIFT x4, CTRL x0.25; no key = hold position (follows live edits)
        povTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (lastSamples == null || lastSamples.isEmpty()) return;
                double rideTime = lastSamples.get(lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                if (povLastNanos == 0) { povLastNanos = now; return; }
                double dt = Math.min((now - povLastNanos) / 1e9, 0.1);
                povLastNanos = now;
                double dir = (moveKeys.contains(KeyCode.W) ? 1 : 0)
                           - (moveKeys.contains(KeyCode.S) ? 1 : 0);
                if (dir != 0) {
                    double mult = 1;
                    if (moveKeys.contains(KeyCode.SHIFT))   mult = 4;
                    if (moveKeys.contains(KeyCode.CONTROL)) mult = 0.25;
                    povTime = ((povTime + dir * dt * mult) % rideTime + rideTime) % rideTime;
                    if (settings.povMarker()) editor.refresh();   // marker follows
                }
                int idx = (int) clamp(povTime / INTEGRATE_DT, 0, lastSamples.size() - 1);
                applyPovCamera(lastSamples.get(idx));
                updateGridLOD(lastSamples.get(idx).pos().x(), lastSamples.get(idx).pos().z());
            }
        };

        flyTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (flyLastNanos == 0) { flyLastNanos = now; return; }
                double dt = Math.min((now - flyLastNanos) / 1e9, 0.1);
                flyLastNanos = now;
                double f = (moveKeys.contains(KeyCode.W) ? 1 : 0) - (moveKeys.contains(KeyCode.S) ? 1 : 0);
                double r = (moveKeys.contains(KeyCode.D) ? 1 : 0) - (moveKeys.contains(KeyCode.A) ? 1 : 0);
                if (f == 0 && r == 0) return;
                double speed = FLY_SPEED * dt;
                if (moveKeys.contains(KeyCode.SHIFT))   speed *= 4;
                if (moveKeys.contains(KeyCode.CONTROL)) speed *= 0.2;
                Point3D fwd = flyForward();
                Point3D right = flyYaw.transform(new Point3D(1, 0, 0)); // strafe stays level
                flyPos.setX(flyPos.getX() + (fwd.getX() * f + right.getX() * r) * speed);
                flyPos.setY(flyPos.getY() + (fwd.getY() * f + right.getY() * r) * speed);
                flyPos.setZ(flyPos.getZ() + (fwd.getZ() * f + right.getZ() * r) * speed);
                updateGridLOD(flyPos.getX() + gridCamX0, flyPos.getZ() + gridCamZ0);
            }
        };

        StackPane subHolder = new StackPane(sub);
        sub.widthProperty().bind(subHolder.widthProperty());
        sub.heightProperty().bind(subHolder.heightProperty());
        // the SubScene reports its current size as its minimum, which stops
        // the SplitPane/window from shrinking the 3D area — and pushed the
        // divider + collapse button off-screen on non-maximized windows
        subHolder.setMinSize(0, 0);

        // ----- editor + toolbar -----
        editor = new GraphEditor(this);

        ToggleGroup laneGroup = new ToggleGroup();
        String[] names = { "Vert G", "Lat G", "Roll" };
        HBox toolbar = new HBox(6);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: rgb(25,28,36);");
        String btnStyle = "-fx-background-color: rgb(45,50,62); -fx-text-fill: rgb(210,215,225);";
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
        // the authored roll lane; more picks arrive with the section work).
        // A plain Button + ContextMenu instead of a MenuButton: MenuButton's
        // separate arrow region broke the flat toolbar-button look — this
        // keeps "Resulting ▾" in one clean button-sized space.
        Button resBtn = new Button("Resulting ▾");
        resBtn.setFocusTraversable(false);
        resBtn.setStyle(btnStyle);
        CheckMenuItem resPitchItem = new CheckMenuItem("Pitch rate (°/s)");
        resPitchItem.setSelected(settings.resPitch());
        resPitchItem.setOnAction(e -> {
            settings.setResPitch(resPitchItem.isSelected());
            editor.refresh();
        });
        CheckMenuItem resYawItem = new CheckMenuItem("Yaw rate (°/s)");
        resYawItem.setSelected(settings.resYaw());
        resYawItem.setOnAction(e -> {
            settings.setResYaw(resYawItem.isSelected());
            editor.refresh();
        });
        ContextMenu resMenu = new ContextMenu(resPitchItem, resYawItem);
        resBtn.setOnAction(e -> {
            if (resMenu.isShowing()) resMenu.hide();
            else resMenu.show(resBtn, Side.BOTTOM, 0, 2);
        });
        Tooltip.install(resBtn, new Tooltip(
            "Overlay read-only RESULTING curves derived from the\n"
            + "integrated track (°/s, thin dashed, on the roll lane's\n"
            + "scale). Picks persist per-user."));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button povBtn = new Button("POV");
        povBtn.setFocusTraversable(false);
        povBtn.setStyle(btnStyle);
        povBtn.setOnAction(e -> togglePov());

        Button appendBtn = new Button("Append +1s");
        appendBtn.setFocusTraversable(false);
        appendBtn.setStyle(btnStyle);
        appendBtn.setOnAction(e -> editor.appendAction());
        Tooltip.install(appendBtn, new Tooltip(
            "No selection: extend the ride 1 s.\n"
            + "Node selected: insert a 1 s flat hold after it\n"
            + "(everything later shifts 1 s)."));

        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(rippleLbl, rippleTCheck, rippleVCheck,
                                     resBtn, spacer, povBtn, appendBtn);

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
            if (flyActive || povActive) moveKeys.remove(e.getCode());
            if (e.getCode() == KeyCode.S) slideKey = false;
            if (e.getCode() == KeyCode.C) cKey = false;
            if (e.getCode() == KeyCode.SHIFT) editor.shiftHover(false);
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
        if (e.getCode() == KeyCode.S && !flyActive && !(povActive && povCaptured)
                && (!e.isShortcutDown() || editor.isDragging())) {
            slideKey = true;   // not consumed: bound actions still fire
        }
        // C held = click-the-track-recenters (FVD++ muscle memory; the
        // Shift+click gesture stays too). Tracked like the S slide key.
        if (e.getCode() == KeyCode.C && !flyActive && !(povActive && povCaptured)
                && !e.isShortcutDown()) {
            cKey = true;
        }
        if (e.getCode() == KeyCode.SHIFT) editor.shiftHover(true);
        if (flyActive || (povActive && povCaptured)) {   // camera owns movement keys
            KeyCode c = e.getCode();
            boolean movement = c == KeyCode.W || c == KeyCode.S
                    || c == KeyCode.SHIFT || c == KeyCode.CONTROL
                    || (flyActive && (c == KeyCode.A || c == KeyCode.D));
            if (movement) {
                moveKeys.add(c);
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
        else if (matches("flath", e))   editor.flattenHandles();
        else if (matches("levelSel", e)) editor.levelSelected();
        else if (matches("align", e))   editor.straightenSelected();
        else if (matches("alignLock", e)) editor.toggleStraightLock();
        else if (matches("lockv", e))   editor.toggleAxisLock(true);
        else if (matches("lockh", e))   editor.toggleAxisLock(false);
        else if (matches("delNode", e)) editor.deleteSelected();
        else if (matches("append", e))  editor.appendAction();
        else if (matches("pov", e))     togglePov();
        else if (matches("viewTop", e))  toggleOrthoView(true);
        else if (matches("viewSide", e)) toggleOrthoView(false);
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
    private static KeyCode normalizeNumpad(KeyCode c) {
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

    /** Serialize a pressed key (with modifiers) into the combo format above. */
    private static String comboString(KeyEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isAltDown())     sb.append("Alt+");
        if (e.isShiftDown())   sb.append("Shift+");
        if (e.isMetaDown())    sb.append("Meta+");
        return sb.append(normalizeNumpad(e.getCode()).getName()).toString();
    }

    /** Rebuild the on-track node markers (viewport editing part 1 —
     *  READ-ONLY). One small sphere per ACTIVE-lane node at its ride
     *  time, sitting on the spine like the ties; selected nodes are
     *  white. Signature-guarded: the graph calls this on every redraw,
     *  but the scene graph only churns when something relevant changed. */
    void refreshTrackMarkers(int lane, Set<Integer> sel, int selIdx) {
        List<BezierBaker.Node> laneNodes = lanes.get(lane);
        StringBuilder sb = new StringBuilder();
        sb.append(lane).append('|').append(selIdx).append('|').append(sel)
          .append('|').append(System.identityHashCode(lastSamples))
          .append('|').append(settings.trackMarkers());
        for (BezierBaker.Node n : laneNodes) sb.append(';').append(n.t);
        String sig = sb.toString();
        if (sig.equals(markerSig)) return;
        markerSig = sig;
        markerGroup.getChildren().clear();
        if (!settings.trackMarkers() || lastSamples == null || lastSamples.isEmpty()) return;
        for (int i = 0; i < laneNodes.size(); i++) {
            int idx = (int) clamp(Math.round(laneNodes.get(i).t / INTEGRATE_DT),
                                  0, lastSamples.size() - 1);
            FVDIntegrator.Sample sm = lastSamples.get(idx);
            Vec3 p = sm.pos().sub(sm.U().scale(heartOffset));   // spine, like ties
            boolean isSel = i == selIdx || sel.contains(i);
            // selection reads from any distance: white AND bigger
            Sphere ball = new Sphere(isSel ? 0.32 : 0.22, 12);
            ball.setMaterial(new PhongMaterial(isSel ? Color.WHITE : LANE_COLORS[lane]));
            ball.setTranslateX(p.x());
            ball.setTranslateY(-p.y());   // Y-flip discipline
            ball.setTranslateZ(p.z());
            markerGroup.getChildren().add(ball);
        }
    }

    /** Nearest track sample within ~25 px of a screen point (a cheap
     *  screen-space "tube" around the heartline — no mesh picking), or
     *  null on a miss. Samples BEHIND the camera are rejected:
     *  localToScreen happily projects those mirrored, which made clicks
     *  on empty sky hit invisible track. */
    private FVDIntegrator.Sample pickTrackSample(double screenX, double screenY) {
        if (lastSamples == null || lastSamples.isEmpty()) return null;
        double yaw = camYaw.getAngle(), pitch = camPitch.getAngle();
        Point3D fwd = new Rotate(yaw, Rotate.Y_AXIS).transform(
                      new Rotate(pitch, Rotate.X_AXIS).transform(new Point3D(0, 0, 1)));
        Point3D camPos = new Point3D(camPivot.getX(), camPivot.getY(), camPivot.getZ())
                .subtract(fwd.multiply(-camZoom.getZ()));
        double best = 25 * 25;
        FVDIntegrator.Sample bestSample = null;
        for (int i = 0; i < lastSamples.size(); i += 5) {
            Vec3 p = lastSamples.get(i).pos();
            Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
            if (sceneP.subtract(camPos).dotProduct(fwd) <= 0) continue;  // behind
            Point2D s = world.localToScreen(p.x(), -p.y(), p.z());
            if (s == null) continue;
            double dx = s.getX() - screenX, dy = s.getY() - screenY;
            double d2 = dx * dx + dy * dy;
            if (d2 < best) { best = d2; bestSample = lastSamples.get(i); }
        }
        return bestSample;
    }

    /** Shift+click (or C+click) in orbit mode: re-center the orbit pivot
     *  on the track point under the cursor. A miss moves nothing. */
    private void snapPivotToTrack(double screenX, double screenY) {
        FVDIntegrator.Sample hit = pickTrackSample(screenX, screenY);
        if (hit == null) return;   // miss: camera stays, drag orbits normally
        Vec3 p = hit.pos();
        Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
        camPivot.setX(sceneP.getX());
        camPivot.setY(sceneP.getY());
        camPivot.setZ(sceneP.getZ());
        updateGridLOD(p.x(), p.z());
    }

    /** Ctrl+click the track: insert a node in the ACTIVE lane at that
     *  point's ride time — the 3D twin of clicking the graph curve
     *  (same insert rules: exact split, gap guards, selects the node). */
    private void insertNodeAtTrack(double screenX, double screenY) {
        FVDIntegrator.Sample hit = pickTrackSample(screenX, screenY);
        if (hit == null) return;
        editor.insertAtTime(hit.t());
    }

    /** T / Shift+T: snap to a top or side view with a pseudo-orthographic
     *  lens. Same key again restores the saved perspective pose; orbiting
     *  away instead keeps the current angles and just restores the lens.
     *  Top keeps the current yaw (a rotated plan view is legitimate);
     *  side is the world X–Y elevation (the ride starts along +X). */
    private void toggleOrthoView(boolean top) {
        if (flyActive || povActive) return;
        if (orthoView && top == orthoTop) {   // same key: back to perspective
            camYaw.setAngle(savedYaw);
            camPitch.setAngle(savedPitch);
            camZoom.setZ(savedZoom);
            exitOrthoLens();
            return;
        }
        if (!orthoView) {
            savedYaw = camYaw.getAngle();
            savedPitch = camPitch.getAngle();
            savedZoom = camZoom.getZ();
            // same apparent size: visible height = 2·d·tan(fov/2)
            double scale = Math.tan(Math.toRadians(settings.flyFov() / 2))
                         / Math.tan(Math.toRadians(ORTHO_FOV / 2));
            camZoom.setZ(Math.max(-ORTHO_FAR_CLIP * 0.5, camZoom.getZ() * scale));
            orbitCam.setFieldOfView(ORTHO_FOV);
            orbitCam.setFarClip(ORTHO_FAR_CLIP);
            orthoView = true;
        }
        orthoTop = top;
        if (top) {
            // plan view LOCKS to the grid: yaw snaps to the nearest 90°
            camPitch.setAngle(-90);
            camYaw.setAngle(Math.round(camYaw.getAngle() / 90.0) * 90);
        } else {
            camYaw.setAngle(0);
            camPitch.setAngle(0);
            centerOrthoOnSelection();
        }
    }

    /** Entering the side view: center on the SELECTED node's track point
     *  (its marker shows white and larger); no selection = pivot stays. */
    private void centerOrthoOnSelection() {
        if (editor == null) return;
        Vec3 p = editor.selectedTrackPoint();
        if (p == null) return;
        Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
        camPivot.setX(sceneP.getX());
        camPivot.setY(sceneP.getY());
        camPivot.setZ(sceneP.getZ());
        updateGridLOD(p.x(), p.z());
    }

    /** Leave the ortho lens but KEEP the current view direction (used when
     *  the user orbits away from a snapped view, or hands off to fly/POV):
     *  the distance scales back so the picture size doesn't jump. */
    private void exitOrthoKeepView() {
        if (!orthoView) return;
        double scale = Math.tan(Math.toRadians(ORTHO_FOV / 2))
                     / Math.tan(Math.toRadians(settings.flyFov() / 2));
        camZoom.setZ(clamp(camZoom.getZ() * scale, -1500, -10));
        exitOrthoLens();
    }

    private void exitOrthoLens() {
        orbitCam.setFieldOfView(settings.flyFov());
        orbitCam.setFarClip(PERSP_FAR_CLIP);
        orthoView = false;
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

    private void beginMenuSession() {
        menuUndoStack.clear();
        menuRedoStack.clear();
        menuSessionOpen = true;
    }

    private void endMenuSession() {
        menuSessionOpen = false;
        menuUndoStack.clear();
        menuRedoStack.clear();
    }

    /** Record one menu edit as an {undo, redo} pair of setters. No-op when
     *  no session is open (e.g. loadDesign writing spinners) or while an
     *  undo/redo is itself being applied. */
    private void pushMenuEdit(Runnable undo, Runnable redo) {
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
    private void installMenuUndoKeys(Scene s) {
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
        shortcutsItem.setOnAction(e -> showShortcutsDialog());
        MenuItem optionsItem = new MenuItem("Options…");
        optionsItem.setOnAction(e -> showOptionsDialog());
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
        TrackPiece piece0 = coaster.pieces.get(0);
        startXSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.x, 1));
        startXSpinner.setPrefWidth(70);
        wireDocSpinner(startXSpinner, v -> coaster.pieces.get(0).x = v);
        startYSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.y, 1));
        startYSpinner.setPrefWidth(70);
        wireDocSpinner(startYSpinner, v -> coaster.pieces.get(0).y = v);
        startZSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.z, 1));
        startZSpinner.setPrefWidth(70);
        wireDocSpinner(startZSpinner, v -> coaster.pieces.get(0).z = v);
        startSpeedSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 100, coaster.v0, 1));
        startSpeedSpinner.setPrefWidth(70);
        // the spinner is v0 AND the sole section's design speed — they
        // stay coupled until Phase 3 gives dynamics its own controls
        wireDocSpinner(startSpeedSpinner, v -> {
            coaster.v0 = v;
            coaster.soleSection().vDesign = v;
        });

        Label heartLbl = new Label("Heart (m)");
        heartLbl.setTextFill(Color.BLACK);   // menu popup is light; default fill washed out
        Label gaugeLbl = new Label("Gauge (m)");
        gaugeLbl.setTextFill(Color.BLACK);
        Label tieLbl = new Label("Tie every (m)");
        tieLbl.setTextFill(Color.BLACK);
        Label startLbl = new Label("Start x/y/z (m)");
        startLbl.setTextFill(Color.BLACK);
        Label speedLbl = new Label("Start speed (m/s)");
        speedLbl.setTextFill(Color.BLACK);
        HBox heartRow = new HBox(8, heartLbl, heartSpinner);
        heartRow.setAlignment(Pos.CENTER_LEFT);
        HBox gaugeRow = new HBox(8, gaugeLbl, gaugeSpinner);
        gaugeRow.setAlignment(Pos.CENTER_LEFT);
        HBox tieRow = new HBox(8, tieLbl, tieSpinner);
        tieRow.setAlignment(Pos.CENTER_LEFT);
        HBox startRow = new HBox(8, startLbl, startXSpinner, startYSpinner, startZSpinner);
        startRow.setAlignment(Pos.CENTER_LEFT);
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
        colorItem.setOnAction(e -> showRailColorDialog());

        coasterMenu = new Menu("Coaster", null,
            new CustomMenuItem(heartRow, false),
            new CustomMenuItem(gaugeRow, false),
            new CustomMenuItem(tieRow, false),
            new CustomMenuItem(startRow, false),
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
        helpLbl.setOnMouseClicked(e -> showHelpDialog());
        helpMenu.setGraphic(helpLbl);

        return new MenuBar(fileMenu, editMenu, coasterMenu, helpMenu);
    }

    /**
     * The Help window (click "Help" in the menu bar). MAINTENANCE RULE
     * (user requirement): every new user-facing feature or control MUST be
     * added here, in the matching section, when it ships. Prioritize the
     * non-obvious. Layout: helpHead = section, helpKey = aligned
     * key/gesture row, helpText = prose for non-key behavior.
     */
    private void showHelpDialog() {
        VBox box = new VBox(5);
        box.setPadding(new Insets(18));

        // small diagram: a transition with node, handles and the insert dot
        Canvas diagram = new Canvas(560, 110);
        GraphicsContext g = diagram.getGraphicsContext2D();
        g.setFill(Color.rgb(28, 31, 40));
        g.fillRoundRect(0, 0, 560, 110, 8, 8);
        g.setStroke(LANE_COLORS[0]);
        g.setLineWidth(2);
        g.beginPath();
        g.moveTo(30, 85);
        g.bezierCurveTo(120, 85, 180, 30, 270, 30);
        g.stroke();
        g.setLineDashes(3, 7);
        g.strokeLine(270, 30, 420, 30);
        g.setLineDashes((double[]) null);
        g.setStroke(Color.WHITE);
        g.setLineWidth(1);
        g.strokeLine(270, 30, 320, 30);   // out handle
        g.strokeLine(270, 30, 215, 30);   // in handle
        g.setFill(Color.WHITE);
        g.fillOval(316, 26, 8, 8);
        g.fillOval(211, 26, 8, 8);
        g.setFill(LANE_COLORS[0]);
        g.fillOval(264, 24, 12, 12);
        g.fillOval(24, 79, 12, 12);
        g.setFill(Color.rgb(255, 255, 255, 0.5));
        g.fillOval(146, 54, 8, 8);        // insert dot on the curve
        g.setFill(Color.rgb(200, 205, 215));
        g.setFont(Font.font(11));
        g.fillText("node", 258, 18);
        g.fillText("handles (drag · Shift · Ctrl)", 330, 55);
        g.fillText("click curve = insert", 100, 78);
        g.fillText("dotted = held value", 380, 22);

        box.getChildren().add(diagram);
        VBox.setMargin(diagram, new Insets(0, 0, 8, 0));

        helpHead(box, "Getting started");
        helpText(box, "You design FORCES, not shapes: the three curves (vertical G, "
            + "lateral G, roll rate) over ride time are integrated into the 3D track. "
            + "Pick a lane with the colored buttons, keys 1/2/3, or by clicking a "
            + "dimmed curve — every edit rebuilds the track live.");
        helpText(box, "Ctrl+Z / Ctrl+Shift+Z undo and redo DOCUMENT edits — the "
            + "curves plus everything that shapes the path: heart, gauge, start "
            + "point and start speed (any new edit clears the redo branch; tie "
            + "spacing and colors are cosmetic and stay out). The window title "
            + "shows the open file, with * while unsaved. File > New starts a "
            + "fresh 1 s design (offering to save first), Save writes straight "
            + "to the open file, Save As picks a new one; File > "
            + "Export NL2 CSV hands the track to NoLimits 2. Keys shown "
            + "here are defaults — rebind anything in Edit > Shortcuts; the stats bar "
            + "always shows your current bindings.");

        helpHead(box, "Graph view");
        helpKey(box, "Scroll", "zoom the time axis around the cursor; fully zoomed out = auto-fit.");
        helpKey(box, "Right-drag", "pan while zoomed in.");
        helpKey(box, "Hover curve", "white dot + the value there; hold Shift for the slope instead.");
        helpKey(box, "Resulting (toolbar)", "dropdown of read-only derived curves: pitch rate and yaw rate (°/s, thin dashed, on the roll lane's scale, colored like the lane each answers to — pitch~vert G, yaw~lat G). No roll entry: the resulting roll rate IS the roll lane.");
        helpText(box, "Value axes auto-scale to the curves. The dotted line after a "
            + "lane's last node is its held final value — click it to add a real node. "
            + "Dragging past a plot edge grows that axis, faster the farther past. The "
            + "divider resizes the graph; the corner button on the 3D view collapses it.");

        helpHead(box, "Nodes");
        helpKey(box, "Click curve", "insert a node exactly ON it — the shape does not change.");
        helpKey(box, "Drag", "move freely; the readout shows Δt from the previous node + value.");
        helpKey(box, "Ctrl", "snap transition time and absolute value (steps set in Options; transitions can be as short as 0.01 s).");
        helpKey(box, "S (hold)", "slide along one axis from the start point — picked instantly from the dominant direction, switches if you pull the other way. Combines with Ctrl snapping: pressing S mid-drag with Ctrl held slides — it never triggers Ctrl+S save (no modifier combo fires during a drag).");
        helpKey(box, "Z / X", "sticky vertical/horizontal lock; overrides S, same key releases. Guides: lane color = value changing, yellow = time changing.");
        helpKey(box, "Arrows", "nudge by snap steps; Ctrl+arrow lands ON the grid; hold to glide.");
        helpKey(box, "Shift+click / box", "multi-select (drag a box on empty plot); dragging any member moves the group. The MAIN node (full ring) is the last one clicked — or the earliest in a box.");
        helpKey(box, "Delete / rt-click", "remove node(s): interior deletes bridge the gap, deleting the last node shortens the ride; node 0 stays.");
        helpKey(box, "Double-click", "type the transition time, value and handle sizes exactly — typed handle sizes slide the knob along its current angle (like S). On the first node the ride's start speed is edited here too.");
        helpKey(box, "B · H · F", "smooth/broken · level the handles (every selected node) · straighten between two adjacent selected nodes.");
        helpKey(box, "Shift+H", "level the selection ONTO the main node: all selected nodes take its value with flat handles — one horizontal line.");
        helpKey(box, "Shift+F", "straight-LOCK the segment between two adjacent selected nodes: it is drawn as twin thin lines and STAYS a line — move an endpoint and the line follows; its knobs only slide along it. Shift+F with either endpoint selected unlocks. Inserting on a locked segment keeps both halves locked.");
        helpKey(box, "A / Append", "+1 s at the ride end (active lane only; the others keep holding) — or a 1 s flat hold after the selected node (all lanes shift).");
        helpKey(box, "Ripple checks", "drags also shift every later node in time and/or value (toolbar).");

        helpHead(box, "Handles");
        helpKey(box, "Drag", "free (angle + length).");
        helpKey(box, "S (hold)", "slide: length only, along the current line (dashed rail).");
        helpKey(box, "Ctrl", "snap length to ease presets — cubic 0.33, sine 0.36, quintic 0.47, circle 0.55 — plus 0.05/0.1 steps up to 1.5.");
        helpText(box, "Round knob = smooth (the partner handle mirrors proportionally); "
            + "square = broken (B toggles). Handle lengths — the number in the drag "
            + "readout and the node dialog — are FRACTIONS of the gap to the "
            + "neighbor (0.33 = a third of the segment), so re-timing nodes keeps "
            + "curve shapes. \"dv\" is the handle's value offset from its node. "
            + "A handle beyond the "
            + "visible scale draws as a dashed stub with a hollow edge marker — click "
            + "the marker to expand the view; click elsewhere to restore.");

        helpHead(box, "Cameras");
        helpKey(box, "Orbit", "drag or MIDDLE-drag = rotate · scroll = zoom · Shift+click (or hold C and click) the track = orbit around that spot · Ctrl+click the track = insert a node there.");
        helpKey(box, "T / Shift+T", "snap to a TOP or SIDE view with a near-orthographic lens (no perspective — for judging alignments). Top locks square to the grid; side centers on the selected node (its marker shows white + larger). Right-drag PANS without leaving the view; scroll zooms. Same key again returns to the previous view; orbiting away keeps the angle and brings perspective back.");
        helpKey(box, "Track markers", "small spheres on the track mark the active lane's nodes (selection shows white; Options toggle). Read-only for now — dragging them comes later.");
        helpKey(box, "Fly", "right-click the 3D view: WASD + mouse look, Shift fast, Ctrl slow, scroll = burst; right-click exits keeping the view.");
        helpKey(box, "POV", "Space. Starts hands-off: the camera sits on the track (following edits) while W/S and the S key stay with the graph. Right-click CAPTURES: mouse hides, W/S ride at simulated speed (Shift/Ctrl faster/slower), no mouse look; right-click releases. Scroll jumps along the track either way.");
        helpKey(box, "Space (captured)", "flips between fly (WASD) and POV (W/S) with the mouse still hidden.");
        helpText(box, "The yellow line on the graph marks the POV camera (Options "
            + "toggle). The neon heartline hides itself while riding POV.");

        helpHead(box, "Menus & settings");
        helpKey(box, "File > Export NL2 CSV", "(Ctrl+E) writes the track for NoLimits 2's CSV import. Choose heartline (default) or rail center (the spline NL2 builds its track around), plus the point spacing; both remembered per-user.");
        helpKey(box, "Edit > Shortcuts", "click a key button, press the new combo (Esc cancels); ↺ resets a binding. Per-user.");
        helpKey(box, "Edit > Options", "POV FOV · fly+orbit FOV (shared so mode switches never zoom-jump) · mouse sensitivity (fly + orbit) · snap steps (arrows walk the 1-2-5 series) · neon heartline · 3D node markers · POV marker. Every row has a ↺ reset. Per-user.");
        helpKey(box, "Coaster menu", "heart offset, gauge, tie spacing, start point (y = height above the ground plane), start speed (also on the first node's double-click dialog), rail colors — incl. the four G-force gradient stops, each a COLOR + G VALUE (defaults 0/1/3/5 g). Per-coaster, saved in the .fvd.");
        helpKey(box, "Ctrl+Z in menus", "while a menu/dialog is open, Ctrl+Z / Ctrl+Shift+Z undo/redo the edits made IN it. Path-shaping values (heart, gauge, start, speed) ALSO land in the document history, so after closing, plain Ctrl+Z steps them back too.");

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 660);

        Stage dlg = new Stage();
        dlg.initOwner(stage);   // non-modal: usable as a reference while editing
        dlg.setTitle("Help");
        dlg.setScene(new Scene(scroll));
        dlg.show();
    }

    private static void helpHead(VBox box, String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
        VBox.setMargin(l, new Insets(10, 0, 2, 0));
        box.getChildren().add(l);
    }

    private static void helpText(VBox box, String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(566);
        l.setPadding(new Insets(1, 0, 1, 10));
        box.getChildren().add(l);
    }

    /** One aligned "key/gesture -> what it does" row. */
    private static void helpKey(VBox box, String term, String desc) {
        Label t = new Label(term);
        t.setStyle("-fx-font-weight: bold;");
        t.setMinWidth(122);
        t.setMaxWidth(122);
        t.setWrapText(true);
        Label d = new Label(desc);
        d.setWrapText(true);
        d.setMaxWidth(434);
        HBox row = new HBox(10, t, d);
        row.setPadding(new Insets(1, 0, 1, 10));
        box.getChildren().add(row);
    }

    /** All shortcuts, rebindable: click a key button, press the new combo.
     *  Edited rows get a ↺ button that restores the default. Persisted. */
    private void showShortcutsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Shortcuts");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));

        String[] capturing = { null };       // action id awaiting a key press
        List<Runnable> rowRefreshers = new ArrayList<>();
        Runnable refreshAll = () -> rowRefreshers.forEach(Runnable::run);

        int row = 0;
        for (String[] a : AppSettings.ACTIONS) {
            String id = a[0];
            Button combo = new Button();
            combo.setPrefWidth(110);
            Button reset = new Button("↺");   // circle-and-arrow: back to default
            reset.setTooltip(new Tooltip("Reset to default (" + a[2] + ")"));

            rowRefreshers.add(() -> {
                combo.setText(settings.shortcut(id));
                reset.setVisible(!settings.isDefaultShortcut(id));
            });
            combo.setOnAction(ev -> {
                capturing[0] = id;
                refreshAll.run();              // clear any stale "press a key…"
                combo.setText("press a key…");
            });
            reset.setOnAction(ev -> {
                settings.resetShortcut(id);
                refreshAll.run();
                rebuildTrack();                // stats-bar hint shows bindings
            });
            grid.addRow(row++, new Label(a[1]), combo, reset);
        }
        refreshAll.run();

        Scene sc = new Scene(grid);
        sc.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (capturing[0] == null) return;
            ev.consume();
            if (ev.getCode().isModifierKey()) return;   // wait for the real key
            if (ev.getCode() != KeyCode.ESCAPE) {       // Escape cancels capture
                settings.setShortcut(capturing[0], comboString(ev));
            }
            capturing[0] = null;
            refreshAll.run();
            rebuildTrack();
        });
        dlg.setScene(sc);
        dlg.show();
    }

    /** The ColorPicker gets its own dialog: embedded in the Coaster menu its
     *  "Custom Color" popup was owned by the menu popup, which auto-hides on
     *  click — the owner died and the app crashed. A real window owner fixes it. */
    private void showRailColorDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Rail color");

        ColorPicker picker = new ColorPicker(railColor);
        picker.setOnAction(e -> {
            Color nc = picker.getValue();
            Color oc = railColor;
            if (!nc.equals(oc)) {
                pushMenuEdit(() -> picker.setValue(oc), () -> picker.setValue(nc));
            }
            railColor = nc;
            if (!railSolid) {           // picking a color implies solid mode
                railSolid = true;
                solidModeItem.setSelected(true);
            }
            markDirty();
            rebuildTrack();
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        grid.addRow(0, new Label("Solid rail color"), picker);

        // customizable G-force gradient: each stop is a G value + a color
        // (flat below the first and above the last, lerped between)
        Label gradHead = new Label("G-force gradient stops (g value + color)");
        gradHead.setStyle("-fx-font-weight: bold;");
        grid.add(gradHead, 0, 1, 3, 1);
        for (int i = 0; i < 4; i++) {
            final int gi = i;
            Spinner<Double> vs = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(G_MIN, G_MAX, gradStops[gi], 0.1));
            vs.setPrefWidth(75);
            vs.setEditable(true);
            vs.valueProperty().addListener((obs, o, v) -> {
                pushMenuEdit(() -> vs.getValueFactory().setValue(o),
                             () -> vs.getValueFactory().setValue(v));
                gradStops[gi] = v;
                markDirty();
                rebuildTrack();
            });
            ColorPicker gp = new ColorPicker(gradColors[gi]);
            gp.setOnAction(ev -> {
                Color nc = gp.getValue();
                Color oc = gradColors[gi];
                if (!nc.equals(oc)) {
                    pushMenuEdit(() -> gp.setValue(oc), () -> gp.setValue(nc));
                }
                gradColors[gi] = nc;
                markDirty();
                rebuildTrack();
            });
            grid.addRow(2 + i, new Label("Stop " + (i + 1)), vs, gp);
        }

        Scene sc = new Scene(grid);
        installMenuUndoKeys(sc);
        dlg.setScene(sc);
        beginMenuSession();
        dlg.setOnHidden(e -> endMenuSession());
        dlg.show();
    }

    /** Slider with live apply + persisted commit + menu-local undo (drags
     *  record one entry per gesture; clicks/keys one per change). */
    private void wireOptionSlider(Slider s, Label valLbl, String fmt,
                                  java.util.function.DoubleConsumer apply,
                                  java.util.function.DoubleConsumer persist) {
        double[] dragStart = { s.getValue() };
        s.valueProperty().addListener((obs, o, v) -> {
            apply.accept(v.doubleValue());
            valLbl.setText(String.format(fmt, v.doubleValue()));
            if (!s.isValueChanging()) {
                persist.accept(v.doubleValue());
                pushMenuEdit(() -> s.setValue(o.doubleValue()),
                             () -> s.setValue(v.doubleValue()));
            }
        });
        s.valueChangingProperty().addListener((obs, was, is) -> {
            if (is) {
                dragStart[0] = s.getValue();
            } else {
                double from = dragStart[0], to = s.getValue();
                persist.accept(to);
                if (from != to) pushMenuEdit(() -> s.setValue(from), () -> s.setValue(to));
            }
        });
    }

    /** Settings spinner with persisted apply + menu-local undo. */
    private void wireOptionSpinner(Spinner<Double> sp, java.util.function.DoubleConsumer persist) {
        sp.valueProperty().addListener((obs, o, v) -> {
            pushMenuEdit(() -> sp.getValueFactory().setValue(o),
                         () -> sp.getValueFactory().setValue(v));
            persist.accept(v);
        });
    }

    private static Slider optionSlider(double min, double max, double value, double tick) {
        Slider s = new Slider(min, max, value);
        s.setPrefWidth(240);
        s.setShowTickMarks(true);
        s.setShowTickLabels(true);
        s.setMajorTickUnit(tick);
        return s;
    }

    /** Little ↺ that restores one Options row to its default (the change
     *  goes through the control, so it persists and is menu-undoable). */
    private Button optionReset(Runnable restore) {
        Button b = new Button("↺");
        b.setTooltip(new Tooltip("Reset to default"));
        b.setOnAction(e -> restore.run());
        return b;
    }

    /** Spinner whose arrow buttons walk the 1-2-5 series in both directions
     *  (… 0.05, 0.1, 0.2, 0.5, 1, 2, 5 …); typing still allows any value. */
    private static Spinner<Double> snap125Spinner(double min, double max, double initial) {
        SpinnerValueFactory<Double> f = new SpinnerValueFactory<>() {
            @Override public void decrement(int steps) {
                double v = getValue();
                for (int i = 0; i < steps; i++) v = nice125Prev(v);
                setValue(Math.max(min, v));
            }
            @Override public void increment(int steps) {
                double v = getValue();
                for (int i = 0; i < steps; i++) v = nice125Next(v);
                setValue(Math.min(max, v));
            }
        };
        f.setConverter(new javafx.util.converter.DoubleStringConverter());
        f.setValue(initial);
        Spinner<Double> sp = new Spinner<>(f);
        sp.setPrefWidth(80);
        sp.setEditable(true);
        return sp;
    }

    private static double nice125Next(double v) {
        double pow = Math.pow(10, Math.floor(Math.log10(v * 1.0001)));
        double m = v / pow;
        return (m < 1.5 ? 2 : m < 3.5 ? 5 : 10) * pow;
    }

    private static double nice125Prev(double v) {
        double pow = Math.pow(10, Math.floor(Math.log10(v * 0.9999)));
        double m = v / pow;
        return (m > 7 ? 5 : m > 3 ? 2 : m > 1.5 ? 1 : 0.5) * pow;
    }

    /** Visual/interaction options (app-wide, persisted). */
    private void showOptionsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Options");

        Slider fovTrack = optionSlider(30, 110, settings.fov(), 20);
        Label fovTrackVal = new Label(String.format("%.0f°", settings.fov()));
        wireOptionSlider(fovTrack, fovTrackVal, "%.0f°",
            v -> povCam.setFieldOfView(v),
            settings::setFov);

        Slider fovFly = optionSlider(30, 110, settings.flyFov(), 20);
        Label fovFlyVal = new Label(String.format("%.0f°", settings.flyFov()));
        wireOptionSlider(fovFly, fovFlyVal, "%.0f°",
            v -> {
                flyCam.setFieldOfView(v);
                // never stomp the 1° ortho lens; it re-reads the setting on exit
                if (!orthoView) orbitCam.setFieldOfView(v);
            },
            settings::setFlyFov);

        Slider sens = optionSlider(0.03, 0.40, settings.flySens(), 0.1);
        Label sensVal = new Label(String.format("%.2f", settings.flySens()));
        wireOptionSlider(sens, sensVal, "%.2f",
            v -> { }, settings::setFlySens);   // fly + orbit read it live

        CheckBox heartCheck = new CheckBox("Show heartline (neon)");
        heartCheck.setSelected(settings.showHeartline());
        heartCheck.setOnAction(ev -> {
            boolean on = heartCheck.isSelected();
            pushMenuEdit(
                () -> { heartCheck.setSelected(!on); settings.setShowHeartline(!on); rebuildTrack(); },
                () -> { heartCheck.setSelected(on); settings.setShowHeartline(on); rebuildTrack(); });
            settings.setShowHeartline(on);
            rebuildTrack();
        });

        CheckBox markCheck = new CheckBox("Node markers on the 3D track");
        markCheck.setSelected(settings.trackMarkers());
        markCheck.setOnAction(ev -> {
            boolean on = markCheck.isSelected();
            pushMenuEdit(
                () -> { markCheck.setSelected(!on); settings.setTrackMarkers(!on); editor.refreshMarkers(); },
                () -> { markCheck.setSelected(on); settings.setTrackMarkers(on); editor.refreshMarkers(); });
            settings.setTrackMarkers(on);
            editor.refreshMarkers();
        });

        Spinner<Double> snapTSp = snap125Spinner(0.01, 2.0, settings.snapT());
        wireOptionSpinner(snapTSp, settings::setSnapT);
        Spinner<Double> snapGSp = snap125Spinner(0.01, 2.0, settings.snapG());
        wireOptionSpinner(snapGSp, settings::setSnapG);
        Spinner<Double> snapRollSp = snap125Spinner(0.5, 50, settings.snapRoll());
        wireOptionSpinner(snapRollSp, settings::setSnapRoll);

        CheckBox povMark = new CheckBox("POV position marker on the graph");
        povMark.setSelected(settings.povMarker());
        povMark.setOnAction(ev -> {
            boolean on = povMark.isSelected();
            pushMenuEdit(() -> { povMark.setSelected(!on); settings.setPovMarker(!on); editor.refresh(); },
                         () -> { povMark.setSelected(on); settings.setPovMarker(on); editor.refresh(); });
            settings.setPovMarker(on);
            editor.refresh();
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int r = 0;
        grid.addRow(r++, new Label("POV camera FOV"), fovTrack, fovTrackVal,
            optionReset(() -> fovTrack.setValue(AppSettings.FOV_DEFAULT)));
        grid.addRow(r++, new Label("Fly & orbit FOV"), fovFly, fovFlyVal,
            optionReset(() -> fovFly.setValue(AppSettings.FOV_DEFAULT)));
        grid.addRow(r++, new Label("Mouse sensitivity (°/px)"), sens, sensVal,
            optionReset(() -> sens.setValue(AppSettings.SENS_DEFAULT)));
        grid.add(new Label("Snap: transition time (s)"), 0, r);
        grid.add(snapTSp, 1, r);
        grid.add(optionReset(() -> snapTSp.getValueFactory().setValue(AppSettings.SNAP_T_DEFAULT)), 3, r++);
        grid.add(new Label("Snap: G value (g)"), 0, r);
        grid.add(snapGSp, 1, r);
        grid.add(optionReset(() -> snapGSp.getValueFactory().setValue(AppSettings.SNAP_G_DEFAULT)), 3, r++);
        grid.add(new Label("Snap: roll rate (°/s)"), 0, r);
        grid.add(snapRollSp, 1, r);
        grid.add(optionReset(() -> snapRollSp.getValueFactory().setValue(AppSettings.SNAP_ROLL_DEFAULT)), 3, r++);
        grid.add(heartCheck, 0, r++, 3, 1);
        grid.add(markCheck, 0, r++, 3, 1);
        grid.add(povMark, 0, r, 3, 1);

        Scene sc = new Scene(grid);
        installMenuUndoKeys(sc);
        dlg.setScene(sc);
        beginMenuSession();
        dlg.setOnHidden(e -> endMenuSession());
        dlg.show();
    }

    // =====================================================================
    // POV ride camera
    // =====================================================================

    /** Space. From orbit: enter POV (uncaptured — right-click to take the
     *  W/S ride controls). From captured fly: switch to CAPTURED POV, mouse
     *  stays hidden. From captured POV: switch back to fly, still captured.
     *  From uncaptured POV: back to orbit. Right-click always releases. */
    private void togglePov() {
        if (!povActive) {
            if (lastSamples == null || lastSamples.isEmpty()) return;
            if (orthoView) exitOrthoKeepView();   // orbit must be sane on exit
            boolean keepCapture = flyActive;   // Space mid-flight keeps capture
            if (flyActive) toggleFly();        // hand the camera over cleanly
            povActive = true;
            povTime = 0;
            povLastNanos = 0;
            moveKeys.clear();
            povCaptured = keepCapture;
            if (povCaptured) sub.setCursor(Cursor.NONE);
            if (heartMesh != null) heartMesh.setVisible(false);
            sub.setCamera(povCam);
            povTimer.start();
            editor.refresh();   // show the POV marker line
        } else if (povCaptured) {
            // captured POV -> captured fly (capture survives until right-click)
            povActive = false;
            povCaptured = false;
            povTimer.stop();
            moveKeys.clear();
            sub.setCursor(Cursor.DEFAULT);
            if (heartMesh != null) heartMesh.setVisible(true);
            sub.setCamera(orbitCam);
            editor.refresh();
            toggleFly();
        } else {
            povActive = false;
            povTimer.stop();
            moveKeys.clear();
            if (heartMesh != null) heartMesh.setVisible(true);
            sub.setCamera(orbitCam);
            editor.refresh();   // hide the POV marker line
        }
    }

    // =====================================================================
    // Fly camera: right-click the 3D view to enter/exit; WASD + mouse look,
    // SHIFT faster, CTRL slower, scroll = fast burst along the view dir
    // =====================================================================

    private void toggleFly() {
        if (povActive) return;
        if (!flyActive) {
            if (orthoView) exitOrthoKeepView();   // hand off a sane pose
            flyActive = true;
            // seamless handoff: adopt the orbit camera's current pose
            double yaw = camYaw.getAngle(), pitch = camPitch.getAngle();
            Point3D p = new Rotate(yaw, Rotate.Y_AXIS).transform(
                        new Rotate(pitch, Rotate.X_AXIS).transform(
                            new Point3D(0, 0, camZoom.getZ())));
            flyPos.setX(camPivot.getX() + p.getX());
            flyPos.setY(camPivot.getY() + p.getY());
            flyPos.setZ(camPivot.getZ() + p.getZ());
            flyYaw.setAngle(yaw);
            flyPitch.setAngle(pitch);
            if (flyRobot == null) flyRobot = new Robot();
            moveKeys.clear();
            flyLastNanos = 0;
            sub.setCursor(Cursor.NONE);
            sub.setCamera(flyCam);
            flyTimer.start();
            centerFlyCursor();
        } else {
            flyActive = false;
            flyTimer.stop();
            moveKeys.clear();
            sub.setCursor(Cursor.DEFAULT);
            // hand the pose back: keep the current view and orbit around the
            // point `zoom` metres in front of where we stopped flying
            double d = -camZoom.getZ();
            Point3D fwd = flyForward();
            camPivot.setX(flyPos.getX() + fwd.getX() * d);
            camPivot.setY(flyPos.getY() + fwd.getY() * d);
            camPivot.setZ(flyPos.getZ() + fwd.getZ() * d);
            camYaw.setAngle(flyYaw.getAngle());
            camPitch.setAngle(flyPitch.getAngle());
            sub.setCamera(orbitCam);
            updateGridLOD(camPivot.getX() + gridCamX0, camPivot.getZ() + gridCamZ0);
        }
    }

    /** Mouse look: measure the offset from the viewport centre, then warp
     *  the (hidden) cursor back — JavaFX cannot capture the pointer, so
     *  Robot-recentering is the standard FPS-camera workaround. */
    private void flyLook(MouseEvent e) {
        if (!flyActive) return;
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c == null) return;
        // per-event delta capped: OS pointer acceleration makes fast flicks
        // arrive as huge single deltas, which felt twitchy — the cap evens
        // out slow-vs-fast response; base sensitivity is an Options slider
        double sens = settings.flySens();
        double dx = clamp(e.getScreenX() - c.getX(), -35, 35);
        double dy = clamp(e.getScreenY() - c.getY(), -35, 35);
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;  // our own warp event
        flyYaw.setAngle(flyYaw.getAngle() + dx * sens);
        flyPitch.setAngle(clamp(flyPitch.getAngle() - dy * sens, -89, 89));
        flyRobot.mouseMove(c.getX(), c.getY());
    }

    private void centerFlyCursor() {
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c != null) flyRobot.mouseMove(c.getX(), c.getY());
    }

    /** Unit view direction of the fly camera (local +Z through yaw+pitch). */
    private Point3D flyForward() {
        return flyYaw.transform(flyPitch.transform(new Point3D(0, 0, 1)));
    }

    /**
     * Position/orient the POV camera at a sample's heartline frame.
     * Rendering flips world Y (JavaFX screen Y+ is down), so all frame
     * vectors are flipped too. JavaFX camera looks along its local +Z with
     * +Y as screen-down; build the rotation columns accordingly:
     *   c2 (local +Z) = forward = flip(T)
     *   c1 (local +Y) = screen down = -flip(U)
     *   c0 (local +X) = c1 x c2   (completes the right-handed basis)
     */
    private void applyPovCamera(FVDIntegrator.Sample sm) {
        Vec3 f = new Vec3(sm.T().x(), -sm.T().y(), sm.T().z());
        Vec3 d = new Vec3(-sm.U().x(), sm.U().y(), -sm.U().z()); // -flip(U)
        Vec3 r = d.cross(f);
        Vec3 eye = new Vec3(sm.pos().x(), -sm.pos().y(), sm.pos().z());
        Affine a = new Affine(
            r.x(), d.x(), f.x(), eye.x(),
            r.y(), d.y(), f.y(), eye.y(),
            r.z(), d.z(), f.z(), eye.z());
        povCam.getTransforms().setAll(a);
    }

    // =====================================================================
    // Track rebuild: lanes -> keyframes -> heartline samples -> two rails
    // =====================================================================

    void rebuildTrack() {
        // the three-pass pipeline (Phase 1): geometry per section at its
        // design speed, then global dynamics + analysis. For the current
        // single-force-section document this reproduces the old direct
        // integrator call bit-for-bit (gated headlessly, D50).
        TrackCompiler.Result compiled = TrackCompiler.compile(coaster, INTEGRATE_DT);
        List<FVDIntegrator.Sample> samples = compiled.samples();
        if (samples.size() < 2) return;
        lastSamples = samples;
        resultRates = compiled.rates();

        // two rails + cross ties, offset from the heartline
        MeshView leftRail = buildRailMesh(samples, -railGauge / 2);
        MeshView rightRail = buildRailMesh(samples, railGauge / 2);
        MeshView ties = buildTieMesh(samples);

        List<javafx.scene.Node> parts = new ArrayList<>(List.of(leftRail, rightRail, ties));
        heartMesh = settings.showHeartline() ? buildHeartlineMesh(samples) : null;
        if (heartMesh != null) {
            heartMesh.setVisible(!povActive);   // the camera rides ON it
            parts.add(heartMesh);
        }
        parts.add(markerGroup);
        trackGroup.getChildren().setAll(parts);
        markerSig = null;                          // geometry moved: force
        if (editor != null) editor.refreshMarkers();

        if (!worldCentered) {
            FVDIntegrator.Sample mid = samples.get(samples.size() / 2);
            world.getTransforms().add(new Translate(-mid.pos().x(), 0, -mid.pos().z()));
            gridCamX0 = mid.pos().x();   // scene coords + this = world coords
            gridCamZ0 = mid.pos().z();
            // transparent reference grid at ABSOLUTE GROUND LEVEL y=0 (it
            // used to sit just under the track's lowest point — now Start y
            // IS height above ground; tracks may dig below it); its XZ
            // position follows the camera in 10 m steps so full line density
            // is always around the viewer (LOD)
            gridGroup = buildGrid(0);
            world.getChildren().add(gridGroup);
            updateGridLOD(mid.pos().x(), mid.pos().z());
            worldCentered = true;
        }

        double maxGv = -99, minGv = 99, maxY = -1e9, minY = 1e9, maxV = 0;
        for (FVDIntegrator.Sample sm : samples) {
            maxGv = Math.max(maxGv, sm.Gv()); minGv = Math.min(minGv, sm.Gv());
            maxY = Math.max(maxY, sm.pos().y()); minY = Math.min(minY, sm.pos().y());
            maxV = Math.max(maxV, sm.v());
        }
        FVDIntegrator.Sample last = samples.get(samples.size() - 1);
        statsLabel.setText(String.format(
            "Ride: %.1f s   |   Track: %.0f m   |   Height: %.1f to %.1f m   |   Top speed: %.0f km/h   |   Gv: %.1f to %.1f      %s",
            last.t(), last.s(), minY, maxY, maxV * 3.6, minGv, maxGv, shortcutHint()));
    }

    /** Built from the live bindings so it stays correct after rebinding. */
    private String shortcutHint() {
        return String.format(
            "[%s/%s/%s lane · %s smooth · S=slide Ctrl=snap · %s append · %s POV · %s/%s save/load · %s undo]",
            settings.shortcut("lane1"), settings.shortcut("lane2"), settings.shortcut("lane3"),
            settings.shortcut("smooth"), settings.shortcut("append"), settings.shortcut("pov"),
            settings.shortcut("save"), settings.shortcut("load"), settings.shortcut("undo"));
    }

    /** A single rail tube: heartline offset down by heartOffset along U,
     *  sideways by `lateral` along R, radius 70 mm, colored by vertical G. */
    private MeshView buildRailMesh(List<FVDIntegrator.Sample> samples, double lateral) {
        TriangleMesh mesh = new TriangleMesh();
        int nRings = 0;
        for (int i = 0; i < samples.size(); i += SAMPLE_STRIDE) {
            FVDIntegrator.Sample sm = samples.get(i);
            Vec3 center = sm.pos()
                .sub(sm.U().scale(heartOffset))
                .add(sm.R().scale(lateral));
            for (int j = 0; j < RAIL_SIDES; j++) {
                double theta = 2 * Math.PI * j / RAIL_SIDES;
                Vec3 offset = sm.R().scale(Math.cos(theta) * RAIL_RADIUS)
                        .add(sm.U().scale(Math.sin(theta) * RAIL_RADIUS));
                Vec3 p = center.add(offset);
                mesh.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            }
            float u = (float) clamp((sm.Gv() - G_MIN) / (G_MAX - G_MIN), 0.001, 0.999);
            mesh.getTexCoords().addAll(u, 0.5f);
            nRings++;
        }
        for (int ring = 0; ring < nRings - 1; ring++) {
            int r0 = ring * RAIL_SIDES, r1 = (ring + 1) * RAIL_SIDES;
            for (int j = 0; j < RAIL_SIDES; j++) {
                int jn = (j + 1) % RAIL_SIDES;
                int a = r0 + j, b = r0 + jn, c = r1 + j, d = r1 + jn;
                mesh.getFaces().addAll(a, ring, c, ring + 1, b, ring);
                mesh.getFaces().addAll(b, ring, c, ring + 1, d, ring + 1);
            }
        }
        PhongMaterial mat = new PhongMaterial();
        if (railSolid) mat.setDiffuseColor(railColor);
        else mat.setDiffuseMap(buildGForceGradient());
        mat.setSpecularColor(Color.rgb(200, 200, 200));
        MeshView view = new MeshView(mesh);
        view.setMaterial(mat);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    /** The integrated heartline — the curve the physics actually lives on —
     *  as a hairline neon-yellow strand (Options toggle). JavaFX 3D has no
     *  polylines, so it's a minimal 4-sided tube; the self-illumination map
     *  makes it glow unlit at any distance. */
    private MeshView buildHeartlineMesh(List<FVDIntegrator.Sample> samples) {
        final double R = 0.015;
        final int SIDES = 4;
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        int nRings = 0;
        for (int i = 0; i < samples.size(); i += SAMPLE_STRIDE) {
            FVDIntegrator.Sample sm = samples.get(i);
            for (int j = 0; j < SIDES; j++) {
                double th = 2 * Math.PI * j / SIDES;
                Vec3 off = sm.R().scale(Math.cos(th) * R)
                        .add(sm.U().scale(Math.sin(th) * R));
                Vec3 p = sm.pos().add(off);
                mesh.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            }
            nRings++;
        }
        for (int ring = 0; ring < nRings - 1; ring++) {
            int r0 = ring * SIDES, r1 = (ring + 1) * SIDES;
            for (int j = 0; j < SIDES; j++) {
                int jn = (j + 1) % SIDES;
                int a = r0 + j, b = r0 + jn, c = r1 + j, d = r1 + jn;
                mesh.getFaces().addAll(a, 0, c, 0, b, 0);
                mesh.getFaces().addAll(b, 0, c, 0, d, 0);
            }
        }
        MeshView mv = new MeshView(mesh);
        WritableImage glow = new WritableImage(1, 1);
        glow.getPixelWriter().setColor(0, 0, Color.rgb(255, 255, 60));
        PhongMaterial mat = new PhongMaterial(Color.BLACK);
        mat.setSelfIlluminationMap(glow);   // unlit neon yellow
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    /** Cross ties: one thin box between the rails every `tieSpacing` metres
     *  of track (placed by arc length s, so spacing is uniform regardless of
     *  speed; positions quantize to the nearest integration sample). */
    private MeshView buildTieMesh(List<FVDIntegrator.Sample> samples) {
        final double TIE_HALF_W = 0.045;  // along T (track direction)
        final double TIE_HALF_H = 0.03;   // along U
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        int v = 0;
        double nextS = 0;
        // ties end exactly at the rail centerlines: the tie cross-section
        // (0.09 x 0.06 m) hides inside the 0.07 m-radius tubes, nothing pokes out
        double halfLen = railGauge / 2;
        for (FVDIntegrator.Sample sm : samples) {
            if (sm.s() < nextS) continue;
            nextS += Math.max(0.1, tieSpacing);
            Vec3 spine = sm.pos().sub(sm.U().scale(heartOffset));
            Vec3 a = spine.sub(sm.R().scale(halfLen));
            Vec3 b = spine.add(sm.R().scale(halfLen));
            Vec3 tw = sm.T().scale(TIE_HALF_W);
            Vec3 uh = sm.U().scale(TIE_HALF_H);
            // 8 corners: (a|b) ± tw ± uh, Y-flipped like all mesh vertices
            Vec3[] c = {
                a.sub(tw).sub(uh), a.add(tw).sub(uh), a.add(tw).add(uh), a.sub(tw).add(uh),
                b.sub(tw).sub(uh), b.add(tw).sub(uh), b.add(tw).add(uh), b.sub(tw).add(uh),
            };
            for (Vec3 p : c) mesh.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            int[][] quads = {
                {0,1,2,3}, {4,5,6,7},           // end caps
                {0,1,5,4}, {2,3,7,6},           // bottom, top
                {1,2,6,5}, {0,3,7,4},           // front, back
            };
            for (int[] q : quads) {
                mesh.getFaces().addAll(v + q[0], 0, v + q[1], 0, v + q[2], 0);
                mesh.getFaces().addAll(v + q[0], 0, v + q[2], 0, v + q[3], 0);
            }
            v += 8;
        }
        MeshView mv = new MeshView(mesh);
        PhongMaterial mat = new PhongMaterial(Color.rgb(70, 74, 84));
        mat.setSpecularColor(Color.rgb(90, 90, 90));
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    /** Recenter the floor grid under the camera, quantized to 10 m steps so
     *  the 1 m / 10 m line pattern stays aligned with the world grid. */
    private void updateGridLOD(double worldX, double worldZ) {
        if (gridGroup == null) return;
        gridGroup.setTranslateX(Math.round(worldX / 10) * 10);
        gridGroup.setTranslateZ(Math.round(worldZ / 10) * 10);
    }

    /** Floor: grid lines that fade smoothly with distance over several
     *  concentric bands — dense 1 m lines near, only 10 m lines further
     *  out, nothing past 240 m — resting on a solid translucent sheet that
     *  carries the far field. JavaFX 3D has no texture mipmapping, so
     *  distant sub-pixel lines shimmer; fading them out band by band is
     *  the workaround, and the sheet keeps the ground read as a surface. */
    private Group buildGrid(double y) {
        double[] edge   = { 0, 30, 60, 90, 120, 180, 240 };  // band bounds (m)
        double[] minorA = { 0.30, 0.22, 0.14, 0.07, 0, 0 };  // 1 m lines (0 = none)
        double[] majorA = { 0.50, 0.42, 0.34, 0.26, 0.16, 0.08 };
        Group g = new Group();
        for (int b = 0; b < 6; b++) {
            boolean last = b == 5;
            if (minorA[b] > 0) {
                g.getChildren().add(buildGridBand(y, edge[b], edge[b + 1], last, 1, 0.03,
                    Color.color(0.55, 0.60, 0.70, minorA[b]), true));
            }
            g.getChildren().add(buildGridBand(y, edge[b], edge[b + 1], last, 10, 0.08,
                Color.color(0.75, 0.80, 0.90, majorA[b]), false));
        }
        // solid translucent sheet just below the lines (offset avoids
        // z-fighting), reaching past the last line band
        TriangleMesh sheet = new TriangleMesh();
        sheet.getTexCoords().addAll(0, 0);
        addFloorQuad(sheet, 0, -600, -600, 600, 600, y - 0.03);
        MeshView sheetView = new MeshView(sheet);
        sheetView.setMaterial(new PhongMaterial(Color.color(0.45, 0.50, 0.60, 0.10)));
        sheetView.setCullFace(CullFace.NONE);
        g.getChildren().add(sheetView);
        return g;
    }

    /** Lines of one weight clipped to the square ring [inner, outer). A line
     *  whose own coordinate lies inside the ring runs full length; a line
     *  closer to the centre contributes only its two flanks past `inner`.
     *  skipTenth leaves out every 10th line so the major mesh can draw it. */
    private MeshView buildGridBand(double y, double inner, double outer, boolean includeEdge,
                                   double spacing, double halfWidth, Color color, boolean skipTenth) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        int v = 0;
        int n = (int) (outer / spacing);
        for (int i = -n; i <= n; i++) {
            if (skipTenth && i % 10 == 0) continue;
            double c = i * spacing;
            double a = Math.abs(c);
            if (a >= inner && (a < outer || includeEdge)) {
                v = addFloorQuad(mesh, v, -outer, c - halfWidth, outer, c + halfWidth, y);
                v = addFloorQuad(mesh, v, c - halfWidth, -outer, c + halfWidth, outer, y);
            } else if (a < inner) {
                v = addFloorQuad(mesh, v, -outer, c - halfWidth, -inner, c + halfWidth, y);
                v = addFloorQuad(mesh, v, inner, c - halfWidth, outer, c + halfWidth, y);
                v = addFloorQuad(mesh, v, c - halfWidth, -outer, c + halfWidth, -inner, y);
                v = addFloorQuad(mesh, v, c - halfWidth, inner, c + halfWidth, outer, y);
            }
        }
        MeshView mv = new MeshView(mesh);
        mv.setMaterial(new PhongMaterial(color));
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    /** Axis-aligned rectangle (x0,z0)-(x1,z1) at world height y (Y-flipped). */
    private static int addFloorQuad(TriangleMesh mesh, int v,
                                    double x0, double z0, double x1, double z1, double y) {
        float sy = (float) -y;
        mesh.getPoints().addAll(
            (float) x0, sy, (float) z0,
            (float) x1, sy, (float) z0,
            (float) x1, sy, (float) z1,
            (float) x0, sy, (float) z1);
        mesh.getFaces().addAll(v, 0, v + 1, 0, v + 2, 0,  v, 0, v + 2, 0, v + 3, 0);
        return v + 4;
    }

    private WritableImage buildGForceGradient() {
        int w = 256;
        WritableImage img = new WritableImage(w, 1);
        PixelWriter pw = img.getPixelWriter();
        for (int i = 0; i < w; i++) {
            double g = G_MIN + (G_MAX - G_MIN) * i / (w - 1.0);
            Color c;
            if (g <= gradStops[0]) {
                c = gradColors[0];
            } else if (g >= gradStops[3]) {
                c = gradColors[3];
            } else {
                int k = 0;
                while (k < 2 && g > gradStops[k + 1]) k++;
                double span = gradStops[k + 1] - gradStops[k];
                c = span < 1e-9 ? gradColors[k + 1]
                    : lerpColor(gradColors[k], gradColors[k + 1], (g - gradStops[k]) / span);
            }
            pw.setColor(i, 0, c);
        }
        return img;
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
        for (int i = 0; i < 3; i++) {
            lanes.get(i).clear();
            lanes.get(i).add(new BezierBaker.Node(0, i == 0 ? 1 : 0));
            lanes.get(i).add(new BezierBaker.Node(1, i == 0 ? 1 : 0));
            BezierBaker.applyDefaultHandles(lanes.get(i));
        }
        coaster.pieces.clear();
        TrackPiece piece = new TrackPiece(0, 21, 0, 0);
        piece.sections.add(new ForceSection(lanes, 26));
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
        startSpeedSpinner.getValueFactory().setValue(26.0);
        (railSolid ? solidModeItem : gradientModeItem).setSelected(true);
        docApplying = false;

        editor.clearHistory();
        editor.clearSelection();
        editor.resetView();
        currentFile = null;
        dirty = false;
        updateTitle();
        editor.refresh();
        rebuildTrack();
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
            // the editor keeps editing the SAME lane list objects across
            // loads: copy the loaded section-0 nodes into them and point
            // the section at them
            DesignIO.rebindLanes(coaster, lanes);

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

            TrackPiece p0 = coaster.pieces.get(0);
            heartSpinner.getValueFactory().setValue(heartOffset);
            gaugeSpinner.getValueFactory().setValue(railGauge);
            tieSpinner.getValueFactory().setValue(tieSpacing);
            startXSpinner.getValueFactory().setValue(p0.x);
            startYSpinner.getValueFactory().setValue(p0.y);
            startZSpinner.getValueFactory().setValue(p0.z);
            startSpeedSpinner.getValueFactory().setValue(coaster.v0);
            (railSolid ? solidModeItem : gradientModeItem).setSelected(true);
            editor.clearSelection();
            editor.refresh();
            rebuildTrack();
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
        wireOptionSpinner(segSp, settings::setNl2SegLen);

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
            optionReset(() -> segSp.getValueFactory().setValue(AppSettings.NL2_SEGLEN_DEFAULT)));
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

    private static Color lerpColor(Color a, Color b, double t) {
        return Color.color(
            a.getRed() + (b.getRed() - a.getRed()) * t,
            a.getGreen() + (b.getGreen() - a.getGreen()) * t,
            a.getBlue() + (b.getBlue() - a.getBlue()) * t
        );
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
