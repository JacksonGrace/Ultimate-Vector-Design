package coaster;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
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
 *   A                        append 1 s of track holding final values
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
    private final List<List<BezierBaker.Node>> lanes = new ArrayList<>();
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

    // ---------- track appearance / geometry settings (per-coaster, saved in .fvd) ----------
    private double heartOffset = 1.0;  // m from heartline down to track (negative = inverted)
    private double railGauge = 1.0;    // m between rail centers
    private double tieSpacing = 1.0;   // m of track between cross ties
    private double startX = 0, startY = 0, startZ = 0;  // track starting point (m)
    private boolean railSolid = false; // false = vertical-G gradient, true = solid color
    private Color railColor = Color.rgb(220, 60, 50);
    private static final double RAIL_RADIUS = 0.07; // 140 mm diameter
    private static final int RAIL_SIDES = 8;
    private static final int SAMPLE_STRIDE = 3;
    private static final double G_MIN = -1.0, G_MAX = 5.0;
    private static final double INTEGRATE_DT = 0.01;

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
    private final Translate camZoom = new Translate(0, 0, -260);
    private double lastMouseX, lastMouseY;
    private boolean worldCentered = false;
    private Group gridGroup;                    // floor grid, recentered on the camera (LOD)
    private double gridCamX0, gridCamZ0;        // scene->world offset for camera positions

    private SubScene sub;
    private PerspectiveCamera orbitCam, povCam;
    private boolean povActive = false;
    private double povTime;        // current ride position (s); W/S move it
    private long povLastNanos;
    private AnimationTimer povTimer;
    private List<FVDIntegrator.Sample> lastSamples;

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

    private Stage stage;
    private final Label statsLabel = new Label();
    private GraphEditor editor;
    private final ToggleButton[] laneButtons = new ToggleButton[3];
    private boolean rippleT, rippleV;   // ripple-edit toggles (time / value)
    private boolean slideKey;           // S held: slide nodes/handles along a line
    private Spinner<Double> heartSpinner, gaugeSpinner, tieSpinner;
    private Spinner<Double> startXSpinner, startYSpinner, startZSpinner;
    private SplitPane mainSplit;           // 3D view over graph panel
    private double lastDividerPos = 0.6;   // restore point for the collapse toggle
    private RadioMenuItem gradientModeItem, solidModeItem;

    // app-wide settings persisted across runs (shortcuts, FOV)
    private final AppSettings settings = new AppSettings();

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
        orbitCam.setFarClip(5000);
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
            if (e.getButton() == MouseButton.SECONDARY) { toggleFly(); return; }
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
        });
        sub.setOnMouseDragged(e -> {
            if (povActive || flyActive) return;
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
        editor = new GraphEditor();

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
            if (flyActive || povActive) moveKeys.remove(e.getCode());
            if (e.getCode() == KeyCode.S) slideKey = false;
        });
        stage.setTitle("FVD Coaster Designer");
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
    }

    private void handleKey(KeyEvent e) {
        // S (held, unmodified) = slide constraint for node/handle drags;
        // never while a camera mode owns WASD
        if (e.getCode() == KeyCode.S && !flyActive && !povActive
                && !e.isShortcutDown()) {
            slideKey = true;   // not consumed: bound actions still fire
        }
        if (flyActive || povActive) {   // movement keys belong to the camera
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
        if      (matches("undo", e))    editor.undo();
        else if (matches("save", e))    saveDesign();
        else if (matches("load", e))    loadDesign();
        else if (matches("smooth", e))  editor.toggleSmooth();
        else if (matches("flath", e))   editor.flattenHandles();
        else if (matches("align", e))   editor.straightenSelected();
        else if (matches("lockv", e))   editor.toggleAxisLock(true);
        else if (matches("lockh", e))   editor.toggleAxisLock(false);
        else if (matches("delNode", e)) editor.deleteSelected();
        else if (matches("append", e))  editor.appendAction();
        else if (matches("pov", e))     togglePov();
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
     *  menu-local history. */
    private void wireMenuSpinner(Spinner<Double> sp, java.util.function.DoubleConsumer apply) {
        sp.valueProperty().addListener((obs, o, v) -> {
            pushMenuEdit(() -> sp.getValueFactory().setValue(o),
                         () -> sp.getValueFactory().setValue(v));
            apply.accept(v);
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
    }

    private MenuBar buildMenuBar() {
        // --- File: save/load ---
        MenuItem saveItem = new MenuItem("Save…");
        saveItem.setOnAction(e -> saveDesign());
        MenuItem loadItem = new MenuItem("Load…");
        loadItem.setOnAction(e -> loadDesign());
        Menu fileMenu = new Menu("File", null, saveItem, loadItem);

        // --- Edit: app-wide settings, persisted across runs ---
        MenuItem shortcutsItem = new MenuItem("Shortcuts…");
        shortcutsItem.setOnAction(e -> showShortcutsDialog());
        MenuItem optionsItem = new MenuItem("Options…");
        optionsItem.setOnAction(e -> showOptionsDialog());
        Menu editMenu = new Menu("Edit", null, shortcutsItem, optionsItem);

        // --- Coaster: per-coaster settings, saved in the .fvd file ---
        heartSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-3.0, 3.0, heartOffset, 0.1));
        heartSpinner.setPrefWidth(70);
        wireMenuSpinner(heartSpinner, v -> heartOffset = v);
        gaugeSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.3, 2.5, railGauge, 0.1));
        gaugeSpinner.setPrefWidth(70);
        wireMenuSpinner(gaugeSpinner, v -> railGauge = v);
        tieSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.2, 10.0, tieSpacing, 0.1));
        tieSpinner.setPrefWidth(70);
        wireMenuSpinner(tieSpinner, v -> tieSpacing = v);
        startXSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, startX, 1));
        startXSpinner.setPrefWidth(70);
        wireMenuSpinner(startXSpinner, v -> startX = v);
        startYSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, startY, 1));
        startYSpinner.setPrefWidth(70);
        wireMenuSpinner(startYSpinner, v -> startY = v);
        startZSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, startZ, 1));
        startZSpinner.setPrefWidth(70);
        wireMenuSpinner(startZSpinner, v -> startZ = v);

        Label heartLbl = new Label("Heart (m)");
        heartLbl.setTextFill(Color.BLACK);   // menu popup is light; default fill washed out
        Label gaugeLbl = new Label("Gauge (m)");
        gaugeLbl.setTextFill(Color.BLACK);
        Label tieLbl = new Label("Tie every (m)");
        tieLbl.setTextFill(Color.BLACK);
        Label startLbl = new Label("Start x/y/z (m)");
        startLbl.setTextFill(Color.BLACK);
        HBox heartRow = new HBox(8, heartLbl, heartSpinner);
        heartRow.setAlignment(Pos.CENTER_LEFT);
        HBox gaugeRow = new HBox(8, gaugeLbl, gaugeSpinner);
        gaugeRow.setAlignment(Pos.CENTER_LEFT);
        HBox tieRow = new HBox(8, tieLbl, tieSpinner);
        tieRow.setAlignment(Pos.CENTER_LEFT);
        HBox startRow = new HBox(8, startLbl, startXSpinner, startYSpinner, startZSpinner);
        startRow.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup colorMode = new ToggleGroup();
        gradientModeItem = new RadioMenuItem("Rail color: G-force gradient");
        gradientModeItem.setToggleGroup(colorMode);
        gradientModeItem.setOnAction(e -> changeRailMode(false));
        solidModeItem = new RadioMenuItem("Rail color: solid");
        solidModeItem.setToggleGroup(colorMode);
        solidModeItem.setOnAction(e -> changeRailMode(true));
        (railSolid ? solidModeItem : gradientModeItem).setSelected(true);

        MenuItem colorItem = new MenuItem("Choose solid color…");
        colorItem.setOnAction(e -> showRailColorDialog());

        coasterMenu = new Menu("Coaster", null,
            new CustomMenuItem(heartRow, false),
            new CustomMenuItem(gaugeRow, false),
            new CustomMenuItem(tieRow, false),
            new CustomMenuItem(startRow, false),
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

        MenuItem helpItem = new MenuItem("Controls & Features…");
        helpItem.setOnAction(e -> showHelpDialog());
        Menu helpMenu = new Menu("Help", null, helpItem);

        return new MenuBar(fileMenu, editMenu, coasterMenu, helpMenu);
    }

    /**
     * Help > Controls & Features. MAINTENANCE RULE (user requirement):
     * every new user-facing feature or control MUST be added here, in the
     * matching section, when it ships. Prioritize the non-obvious.
     */
    private void showHelpDialog() {
        // each section: title, then bullet lines
        String[][] sections = {
            { "Getting started",
              "You design FORCES, not shapes: the three curves (vertical G, lateral G, roll rate) over ride time are integrated into the 3D track.",
              "Pick a lane with the colored buttons, keys 1/2/3, or by clicking a dimmed curve; edits rebuild the track live.",
              "Key names here are defaults — rebind anything in Edit > Shortcuts; the stats bar shows your current bindings.",
              "File > Save/Load stores designs (.fvd), including the Coaster-menu settings." },
            { "Graph view",
              "Scroll = zoom time around the cursor · right-drag = pan · fully zoomed out = auto-fit.",
              "Value axes auto-scale when a curve exceeds the defaults; grid lines and labels re-density as you zoom.",
              "Dotted flat line after a lane's last node = held final value; click it to add a real node there.",
              "Dragging a node or handle past a plot edge grows that axis — the farther past, the faster.",
              "Resize the graph with the divider; the corner button on the 3D view collapses/restores the whole panel." },
            { "Nodes",
              "Click the curve = insert a node exactly ON it (white dot preview; the curve shape is unchanged).",
              "Drag = move freely · Ctrl = snap (steps adjustable in Edit > Options) · hold S = slide along one axis, anchored at the start point — picked instantly from the dominant direction and it SWITCHES if you pull further the other way.",
              "Z / X = sticky vertical/horizontal lock (overrides S; same key returns it) · guide lines are color-coded by what the drag changes: lane color = value changing (vertical), yellow = time changing (horizontal) · arrow keys nudge by snap steps (Ctrl+arrow lands ON the grid; hold to glide).",
              "Hovering the curve shows the insert dot AND the lane's value at that point.",
              "Select two ADJACENT nodes (Shift+click) and press F: their facing handles align onto the chord — the segment becomes a straight line.",
              "Shift+click OR drag a box on empty plot = multi-select; drag any member to move the group; Delete removes them.",
              "Double-click a node = type the transition time, global value and handle sizes exactly.",
              "B = smooth/broken · H = level handles · A/Append = +1 s at the ride end, or a 1 s flat hold after the selected node.",
              "Ripple checks (toolbar): drags also shift every later node in time and/or value.",
              "Transitions can be as short as 0.01 s." },
            { "Handles",
              "Drag = free (angle + length) · hold S = slide: length only along the current line · Ctrl = snap to ease presets (cubic, sine, quintic, circle, plus 5% steps below and 10% steps above).",
              "Handles reach up to 150% of the segment for aggressive shapes (folds are sanitized when baked).",
              "Round knob = smooth (partner mirrors proportionally); square = broken; B toggles.",
              "Lengths are a fraction of the gap to the neighbor, so re-timing nodes keeps curve shapes.",
              "A handle beyond the visible scale draws as a dashed stub with a hollow marker at the edge — click the marker to expand the view; clicking elsewhere restores it." },
            { "Cameras",
              "Orbit (default): drag = rotate, scroll = zoom.",
              "Fly: right-click the 3D view; WASD + mouse look, Shift fast, Ctrl slow, scroll = burst; right-click exits and keeps the view.",
              "POV (Space): W/S ride at simulated speed, Shift/Ctrl faster/slower, scroll jumps; the line on the graph is the camera (Options toggle).",
              "Edit > Options: one FOV for fly + orbit (they hand off to each other), a separate POV FOV, and mouse sensitivity (fly look + orbit). Every row has a ↺ reset-to-default." },
            { "Menus & settings",
              "Edit > Shortcuts: click a key button, press a new combo (Esc cancels); ↺ resets one binding.",
              "Coaster menu: heart, gauge, tie spacing, start point, rail color — per-coaster, saved in the .fvd.",
              "Edit > Options: FOVs, fly sensitivity, snap steps (arrow buttons walk the 1-2-5 series), POV marker — per-user, persist across runs.",
              "While a menu/dialog is open, Ctrl+Z / Ctrl+Shift+Z undo/redo the edits made IN it; closing locks them in (curve editing has no redo yet)." },
        };

        VBox box = new VBox(6);
        box.setPadding(new Insets(16));

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

        for (String[] s : sections) {
            Label head = new Label(s[0]);
            head.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
            VBox.setMargin(head, new Insets(8, 0, 0, 0));
            box.getChildren().add(head);
            for (int i = 1; i < s.length; i++) {
                Label bullet = new Label("•  " + s[i]);
                bullet.setWrapText(true);
                bullet.setMaxWidth(548);
                bullet.setPadding(new Insets(0, 0, 0, 8));
                box.getChildren().add(bullet);
            }
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 660);

        Stage dlg = new Stage();
        dlg.initOwner(stage);   // non-modal: usable as a reference while editing
        dlg.setTitle("Controls & Features");
        dlg.setScene(new Scene(scroll));
        dlg.show();
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
            rebuildTrack();
        });

        HBox row = new HBox(10, new Label("Solid rail color"), picker);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14));
        Scene sc = new Scene(row);
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
            v -> { flyCam.setFieldOfView(v); orbitCam.setFieldOfView(v); },
            settings::setFlyFov);

        Slider sens = optionSlider(0.03, 0.40, settings.flySens(), 0.1);
        Label sensVal = new Label(String.format("%.2f", settings.flySens()));
        wireOptionSlider(sens, sensVal, "%.2f",
            v -> { }, settings::setFlySens);   // fly + orbit read it live

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

    private void togglePov() {
        if (!povActive) {
            if (lastSamples == null || lastSamples.isEmpty()) return;
            if (flyActive) toggleFly();   // hand the camera over cleanly
            povActive = true;
            povTime = 0;
            povLastNanos = 0;
            moveKeys.clear();
            sub.setCamera(povCam);
            povTimer.start();
            editor.refresh();   // show the POV marker line
        } else {
            povActive = false;
            povTimer.stop();
            moveKeys.clear();
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

    private void rebuildTrack() {
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(lanes, 0.05);

        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = INTEGRATE_DT;
        opts.v0 = 26;
        opts.pos0 = new Vec3(startX, startY, startZ);
        opts.startHeading = new Vec3(1, 0, 0);

        List<FVDIntegrator.Sample> samples = FVDIntegrator.integrateTrack(kfs, opts);
        if (samples.size() < 2) return;
        lastSamples = samples;

        // two rails + cross ties, offset from the heartline
        MeshView leftRail = buildRailMesh(samples, -railGauge / 2);
        MeshView rightRail = buildRailMesh(samples, railGauge / 2);
        MeshView ties = buildTieMesh(samples);

        trackGroup.getChildren().setAll(leftRail, rightRail, ties);

        if (!worldCentered) {
            FVDIntegrator.Sample mid = samples.get(samples.size() / 2);
            world.getTransforms().add(new Translate(-mid.pos().x(), 0, -mid.pos().z()));
            gridCamX0 = mid.pos().x();   // scene coords + this = world coords
            gridCamZ0 = mid.pos().z();
            // transparent reference grid at a FIXED height (placed once below
            // the initial track); its XZ position follows the camera in 10 m
            // steps so full line density is always around the viewer (LOD)
            double lowest = 1e9;
            for (FVDIntegrator.Sample sm : samples) {
                double railY = sm.pos().y() - sm.U().y() * heartOffset;
                lowest = Math.min(lowest, railY);
            }
            gridGroup = buildGrid(Math.floor(lowest - 1));
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
            if (g < 0)        c = Color.color(0.2, 0.4, 1.0);
            else if (g < 1.5) c = Color.color(0.2, 0.9, 0.3);
            else if (g < 3.0) c = lerpColor(Color.color(0.2, 0.9, 0.3), Color.color(1.0, 0.9, 0.1), (g - 1.5) / 1.5);
            else              c = lerpColor(Color.color(1.0, 0.9, 0.1), Color.color(1.0, 0.15, 0.1), Math.min((g - 3.0) / 2.0, 1.0));
            pw.setColor(i, 0, c);
        }
        return img;
    }

    // =====================================================================
    // Save / load
    // =====================================================================

    private void saveDesign() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save coaster design");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("FVD design (*.fvd)", "*.fvd"));
        fc.setInitialFileName("coaster.fvd");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try (PrintWriter out = new PrintWriter(f, "UTF-8")) {
            out.println("FVDD2");   // v2: handle time-offsets are FRACTIONS of the segment
            out.printf(Locale.US, "heart %.4f%n", heartOffset);
            out.printf(Locale.US, "gauge %.4f%n", railGauge);
            out.printf(Locale.US, "tiespace %.4f%n", tieSpacing);
            out.printf(Locale.US, "start %.4f %.4f %.4f%n", startX, startY, startZ);
            // rail color lines are ignored by older versions (unknown-line rule)
            out.printf(Locale.US, "railmode %s%n", railSolid ? "solid" : "gradient");
            out.printf(Locale.US, "railcolor %.4f %.4f %.4f%n",
                railColor.getRed(), railColor.getGreen(), railColor.getBlue());
            for (int lane = 0; lane < 3; lane++) {
                out.println("lane " + lane);
                for (BezierBaker.Node n : lanes.get(lane)) {
                    out.printf(Locale.US, "n %.4f %.4f %.4f %.4f %.4f %.4f %d%n",
                        n.t, n.val, n.outFrac, n.outDv, n.inFrac, n.inDv, n.smooth ? 1 : 0);
                }
            }
            statsLabel.setText("Saved: " + f.getName());
        } catch (Exception ex) {
            statsLabel.setText("Save failed: " + ex.getMessage());
        }
    }

    private void loadDesign() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Load coaster design");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("FVD design (*.fvd)", "*.fvd"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try (BufferedReader in = new BufferedReader(new FileReader(f, java.nio.charset.StandardCharsets.UTF_8))) {
            String header = in.readLine();
            if (header == null || !header.startsWith("FVDD")) throw new Exception("not an FVD design file");
            // FVDD1 stored handle time-offsets in absolute seconds (in-handle
            // negative); FVDD2 stores fractions of the neighboring segment
            boolean legacy = header.trim().equals("FVDD1");

            double newHeart = heartOffset, newGauge = railGauge, newTie = tieSpacing;
            double[] newStart = { startX, startY, startZ };
            boolean newSolid = railSolid;
            Color newColor = railColor;
            List<List<BezierBaker.Node>> newLanes = new ArrayList<>();
            for (int i = 0; i < 3; i++) newLanes.add(new ArrayList<>());
            int curLane = -1;

            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) continue;
                switch (parts[0]) {
                    case "heart" -> newHeart = Double.parseDouble(parts[1]);
                    case "gauge" -> newGauge = Double.parseDouble(parts[1]);
                    case "tiespace" -> newTie = clamp(Double.parseDouble(parts[1]), 0.2, 10);
                    case "start" -> {
                        for (int k = 0; k < 3; k++) {
                            newStart[k] = clamp(Double.parseDouble(parts[k + 1]), -500, 500);
                        }
                    }
                    case "railmode" -> newSolid = parts[1].equals("solid");
                    case "railcolor" -> newColor = Color.color(
                        clamp(Double.parseDouble(parts[1]), 0, 1),
                        clamp(Double.parseDouble(parts[2]), 0, 1),
                        clamp(Double.parseDouble(parts[3]), 0, 1));
                    case "lane"  -> curLane = Integer.parseInt(parts[1]);
                    case "n" -> {
                        if (curLane < 0 || curLane > 2) throw new Exception("node outside lane section");
                        BezierBaker.Node n = new BezierBaker.Node(
                            Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                        n.outFrac = Double.parseDouble(parts[3]);  // raw seconds if legacy
                        n.outDv   = Double.parseDouble(parts[4]);
                        n.inFrac  = Double.parseDouble(parts[5]);
                        n.inDv    = Double.parseDouble(parts[6]);
                        n.smooth = !parts[7].equals("0");
                        newLanes.get(curLane).add(n);
                    }
                    default -> { /* ignore unknown lines: forward compatibility */ }
                }
            }
            for (List<BezierBaker.Node> lane : newLanes) {
                if (lane.size() < 2) throw new Exception("each lane needs at least 2 nodes");
            }
            if (legacy) {   // absolute seconds -> fraction of the segment
                for (List<BezierBaker.Node> lane : newLanes) {
                    for (int i = 0; i < lane.size(); i++) {
                        BezierBaker.Node n = lane.get(i);
                        double outDt = n.outFrac, inDt = n.inFrac;
                        n.outFrac = i < lane.size() - 1
                            ? clamp(outDt / (lane.get(i + 1).t - n.t), 0, BezierBaker.MAX_FRAC)
                            : BezierBaker.DEFAULT_FRAC;
                        n.inFrac = i > 0
                            ? clamp(-inDt / (n.t - lane.get(i - 1).t), 0, BezierBaker.MAX_FRAC)
                            : BezierBaker.DEFAULT_FRAC;
                    }
                }
            }

            editor.pushUndoSnapshot(); // loading is undoable
            for (int i = 0; i < 3; i++) {
                lanes.get(i).clear();
                lanes.get(i).addAll(newLanes.get(i));
            }
            heartOffset = newHeart;
            railGauge = newGauge;
            tieSpacing = newTie;
            startX = newStart[0]; startY = newStart[1]; startZ = newStart[2];
            railSolid = newSolid;
            railColor = newColor;
            heartSpinner.getValueFactory().setValue(newHeart);
            gaugeSpinner.getValueFactory().setValue(newGauge);
            tieSpinner.getValueFactory().setValue(newTie);
            startXSpinner.getValueFactory().setValue(startX);
            startYSpinner.getValueFactory().setValue(startY);
            startZSpinner.getValueFactory().setValue(startZ);
            (railSolid ? solidModeItem : gradientModeItem).setSelected(true);
            editor.clearSelection();
            editor.refresh();
            rebuildTrack();
            statsLabel.setText("Loaded: " + f.getName());
        } catch (Exception ex) {
            statsLabel.setText("Load failed: " + ex.getMessage());
        }
    }

    // =====================================================================
    // Graph editor: independent per-lane nodes
    // =====================================================================

    private class GraphEditor extends Region {

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
        private double snapT() { return settings.snapT(); }
        private double snapVal(int lane) {
            return lane == 2 ? settings.snapRoll() : settings.snapG();
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

        private final Deque<List<List<BezierBaker.Node>>> undoStack = new ArrayDeque<>();
        private List<List<BezierBaker.Node>> pendingSnapshot = null;
        private boolean gestureMoved = false;
        private static final int UNDO_LIMIT = 100;

        GraphEditor() {
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
                            lastDragShift = slideKey;
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
                        lastDragShift = slideKey;
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
                double n0 = clamp(tm - (tm - t0) / width * newW, 0, domain() - newW);
                viewT0 = n0;
                viewT1 = n0 + newW;
                redraw();
            });

            canvas.setOnMouseMoved(e -> {
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
                        double n0 = clamp(t0 + shift, 0, domain() - (t1 - t0));
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
                    lastDragShift = slideKey;
                    // free by default; S (held) slides in-line, CTRL snaps
                    dragHandleTo(e.getX(), e.getY(), slideKey, e.isControlDown());
                } else if (dragNodeIdx >= 0) {
                    commitGestureSnapshot();
                    lastDragX = e.getX();
                    lastDragY = e.getY();
                    lastDragSnap = e.isControlDown();
                    lastDragShift = slideKey;
                    dragNodeTo(e.getX(), e.getY(), e.isControlDown(), slideKey);
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
                        for (int i = 0; i < lane.size(); i++) {
                            double px = tToX(lane.get(i).t);
                            double py = valToY(activeLane, lane.get(i).val);
                            if (px >= x0 && px <= x1 && py >= y0 && py <= y1) {
                                selSet.add(i);
                                selIdx = i;
                            }
                        }
                        if (selSet.isEmpty()) selIdx = -1;
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

        private List<BezierBaker.Node> active() { return lanes.get(activeLane); }

        void refresh() { redraw(); }
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
            laneButtons[lane].setSelected(true);
            redraw();
        }

        /** Append action: with a node selected, insert a 1 s flat hold right
         *  after it (everything later shifts 1 s, regardless of the ripple
         *  toggles); otherwise extend the ride end by 1 s. */
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
            for (List<BezierBaker.Node> ln : lanes) {
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
            rebuildTrack();
        }

        void appendSecond() {
            pushUndoSnapshot();
            double newEnd = BezierBaker.rideEnd(lanes) + 1.0;
            for (List<BezierBaker.Node> lane : lanes) {
                BezierBaker.Node last = lane.get(lane.size() - 1);
                BezierBaker.Node np = new BezierBaker.Node(newEnd, last.val);
                // appended transitions default to the sinusoidal ease
                last.outFrac = SINE_FRAC; last.outDv = 0;
                np.inFrac = SINE_FRAC;    np.inDv = 0;
                lane.add(np);
            }
            redraw();
            rebuildTrack();
        }

        // ---------- undo ----------

        private List<List<BezierBaker.Node>> snapshot() {
            List<List<BezierBaker.Node>> copy = new ArrayList<>(3);
            for (List<BezierBaker.Node> lane : lanes) {
                List<BezierBaker.Node> laneCopy = new ArrayList<>(lane.size());
                for (BezierBaker.Node n : lane) laneCopy.add(n.copy());
                copy.add(laneCopy);
            }
            return copy;
        }

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

        private void pushUndo(List<List<BezierBaker.Node>> snap) {
            undoStack.push(snap);
            while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        }

        void undo() {
            if (undoStack.isEmpty()) return;
            List<List<BezierBaker.Node>> snap = undoStack.pop();
            for (int i = 0; i < 3; i++) {
                lanes.get(i).clear();
                lanes.get(i).addAll(snap.get(i));
            }
            if (selIdx >= active().size()) selIdx = -1;
            selSet.removeIf(i -> i >= active().size());
            redraw();
            rebuildTrack();
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
            rebuildTrack();
        }

        // ---------- coordinate mapping ----------

        private double maxT() { return Math.max(0.5, BezierBaker.rideEnd(lanes)); }
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
                + clamp((x - MARGIN_LEFT) / plotWidth(), 0, 1) * (viewEnd() - viewStart());
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
            return curMin[lane] + clamp(t, 0, 1) * (curMax[lane] - curMin[lane]);
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
            double best = NODE_HIT + 3;   // slightly forgiving on dimmed lanes
            for (int L = 0; L < LANES; L++) {
                if (L == activeLane) continue;
                List<BezierBaker.Node> lane = lanes.get(L);
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
                double d = Math.abs(y - valToY(L, BezierBaker.evalAt(lanes.get(L), t)));
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
            dT = clamp(dT, Math.min(lo, 0), Math.max(hi, 0));
            for (int i : selSet) {
                BezierBaker.Node m = lane.get(i);
                if (i > 0) m.t += dT;
                m.val += dV;
            }
            redraw();
            rebuildTrack();
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
                double hi = (rippleT || idx == lane.size() - 1)
                        ? Double.MAX_VALUE
                        : lane.get(idx + 1).t - MIN_GAP;
                newT = clamp(newT, lo, hi);
            }
            if (rippleT || rippleV) {
                // shift everything after the node by the same deltas on the
                // enabled axes: time keeps later transitions, value
                // translates the rest of the graph
                double dT = newT - n.t, dV = newVal - n.val;
                for (int j = idx + 1; j < lane.size(); j++) {
                    if (rippleT) lane.get(j).t += dT;
                    if (rippleV) lane.get(j).val += dV;
                }
            }
            n.t = newT;
            n.val = newVal;
            redraw();
            rebuildTrack();
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
                newFrac = clamp(Math.abs(newDt) / segLen, MIN_FRAC, BezierBaker.MAX_FRAC);
                if (snap) newFrac = snapFrac(newFrac);
                newDv = slope * ((dragHandleIsOut ? 1 : -1) * newFrac * segLen);
            } else {
                // free: fraction from mouse time, dv from mouse value
                double dt = xToT(x) - n.t;
                newFrac = clamp((dragHandleIsOut ? dt : -dt) / segLen,
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
                    n.inFrac = clamp(n.inFrac * (newFrac / oldFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                    n.inDv = Math.abs(oldDv) > 1e-9
                        ? n.inDv * (newDv / oldDv)
                        // dragged handle was flat: ratio undefined, mirror by slope
                        : (newDv / (newFrac * segLen)) * (-n.inFrac * (n.t - prev.t));
                } else if (!dragHandleIsOut && next != null) {
                    n.outFrac = clamp(n.outFrac * (newFrac / oldFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                    n.outDv = Math.abs(oldDv) > 1e-9
                        ? n.outDv * (newDv / oldDv)
                        : (newDv / (-newFrac * segLen)) * (n.outFrac * (next.t - n.t));
                }
            }
            redraw();
            rebuildTrack();
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
                         : clamp(((x - px0) * segX + (y - py0) * segY) / len2, 0, 1);
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
                rebuildTrack();
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
            lane.add(i, n);
            selIdx = i;
            selSet.clear();
            selSet.add(i);
            axisLock = 0;
            redraw();
            rebuildTrack();
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
            rebuildTrack();
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
            rebuildTrack();
        }

        /** H: make the selected node's handles horizontal (dv = 0). While a
         *  handle is being dragged on a BROKEN node, only that handle. */
        void flattenHandles() {
            if (selIdx < 0) return;
            BezierBaker.Node n = active().get(selIdx);
            pushUndoSnapshot();
            if (dragHandleIdx >= 0 && !n.smooth) {
                if (dragHandleIsOut) n.outDv = 0; else n.inDv = 0;
            } else {
                n.outDv = 0;
                n.inDv = 0;
            }
            redraw();
            rebuildTrack();
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
            rebuildTrack();
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
            dlg.initOwner(stage);
            dlg.initModality(Modality.WINDOW_MODAL);
            dlg.setTitle("Node " + idx + " — " + LANE_NAMES[activeLane]);

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(8);
            grid.setPadding(new Insets(14));

            TextField dtField = null, valField;
            TextField inField = null, outField = null;
            int row = 0;
            if (prev != null) {
                dtField = new TextField(String.format(Locale.US, "%.3f", n.t - prev.t));
                grid.addRow(row++, new Label("Transition time (s)"), dtField);
            }
            // vertical axis is the GLOBAL value, not a delta
            valField = new TextField(String.format(Locale.US, "%.3f", n.val));
            grid.addRow(row++, new Label("Value (" + LANE_UNITS[activeLane].trim() + ")"), valField);
            if (prev != null) {
                inField = new TextField(String.format(Locale.US, "%.3f", n.inFrac));
                grid.addRow(row++, new Label("In handle (fraction)"), inField);
            }
            if (hasOut) {
                outField = new TextField(String.format(Locale.US, "%.3f", n.outFrac));
                grid.addRow(row++, new Label("Out handle (fraction)"), outField);
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

            final TextField fDt = dtField, fVal = valField;
            final TextField fIn = inField, fOut = outField;
            ok.setOnAction(ev -> {
                pushUndoSnapshot();
                if (fIn != null)  n.inFrac = clamp(parseOr(fIn, n.inFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                if (fOut != null) n.outFrac = clamp(parseOr(fOut, n.outFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
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
                for (double[] p : BezierBaker.polyline(lanes.get(L))) {
                    if (p[0] < t0 || p[0] > t1) continue;
                    lo = Math.min(lo, p[1]);
                    hi = Math.max(hi, p[1]);
                }
                double eA = BezierBaker.evalAt(lanes.get(L), t0);
                double eB = BezierBaker.evalAt(lanes.get(L), t1);
                lo = Math.min(lo, Math.min(eA, eB));
                hi = Math.max(hi, Math.max(eA, eB));
                // expanded-handle view: include the selected node's knobs
                if (handleViewExpand && L == activeLane
                        && selIdx >= 0 && selIdx < lanes.get(L).size()) {
                    BezierBaker.Node sn = lanes.get(L).get(selIdx);
                    lo = Math.min(lo, Math.min(sn.val + sn.outDv, sn.val + sn.inDv));
                    hi = Math.max(hi, Math.max(sn.val + sn.outDv, sn.val + sn.inDv));
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
            updateScale();
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
                gc.setFill(isDragged ? Color.WHITE : LANE_COLORS[activeLane]);
                gc.fillOval(px - NODE_RADIUS, py - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);
                if (isDragged) {
                    gc.setFill(Color.WHITE);
                    gc.setFont(Font.font(11));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.setTextBaseline(VPos.TOP);
                    // transition time from the previous node; global value + unit
                    BezierBaker.Node nd = lane.get(i);
                    String txt = i == 0
                        ? String.format("t=%.2fs  %.2f%s", nd.t, nd.val, LANE_UNITS[activeLane])
                        : String.format("Δt=%.2fs  %.2f%s",
                            nd.t - lane.get(i - 1).t, nd.val, LANE_UNITS[activeLane]);
                    gc.fillText(txt, px + 8, py - 20);
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
                gc.fillText(String.format("%.2f%s", hv, LANE_UNITS[activeLane]),
                    hx + 8, hy - 6);
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
                gc.setGlobalAlpha(0.35);
                gc.setLineWidth(1.5);
                if (lock == 1) {
                    gc.setStroke(LANE_COLORS[activeLane]);
                    gc.strokeLine(lx, MARGIN_TOP, lx, MARGIN_TOP + plotHeight());
                } else {
                    gc.setStroke(TIME_COLOR);
                    gc.strokeLine(MARGIN_LEFT, ly, w - MARGIN_RIGHT, ly);
                }
                gc.setGlobalAlpha(1.0);
            }

            // POV camera position on the track (toggle in Edit > Options)
            if (povActive && settings.povMarker()) {
                double px2 = tToX(povTime);
                if (px2 >= MARGIN_LEFT - 1 && px2 <= w - MARGIN_RIGHT + 1) {
                    gc.setGlobalAlpha(0.4);
                    gc.setStroke(TIME_COLOR);   // yellow = time
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
            gc.setFill(LANE_COLORS[activeLane]);
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
            gc.setFill(TIME_COLOR);   // yellow = time, everywhere in the UI
            gc.fillText("Time (s)", MARGIN_LEFT + plotWidth() / 2, h - 2);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
        }

        private void strokeLaneCurve(GraphicsContext gc, int laneIdx) {
            List<BezierBaker.Node> lane = lanes.get(laneIdx);
            gc.setStroke(LANE_COLORS[laneIdx]);
            gc.setLineWidth(2);
            gc.beginPath();
            gc.moveTo(tToX(lane.get(0).t), valToY(laneIdx, lane.get(0).val));
            for (int i = 0; i < lane.size() - 1; i++) {
                BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
                double c1x = tToX(a.t + BezierBaker.effOutDt(a, b));
                double c1y = valToY(laneIdx, a.val + a.outDv);
                double c2x = tToX(b.t + BezierBaker.effInDt(a, b));
                double c2y = valToY(laneIdx, b.val + b.inDv);
                gc.bezierCurveTo(c1x, c1y, c2x, c2y, tToX(b.t), valToY(laneIdx, b.val));
            }
            gc.stroke();

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
                    gc.fillText(String.format("%s %.0f%%%s  dv %.2f",
                        out ? "out" : "in",
                        (out ? n.outFrac : n.inFrac) * 100,
                        dragPresetName != null ? " " + dragPresetName : "",
                        out ? n.outDv : n.inDv),
                        clamp(k[0] + 8, MARGIN_LEFT, canvas.getWidth() - 140),
                        clamp(k[1] - 16, MARGIN_TOP, MARGIN_TOP + plotHeight() - 14));
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

    // =====================================================================

    private static Color lerpColor(Color a, Color b, double t) {
        return Color.color(
            a.getRed() + (b.getRed() - a.getRed()) * t,
            a.getGreen() + (b.getGreen() - a.getGreen()) * t,
            a.getBlue() + (b.getBlue() - a.getBlue()) * t
        );
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
