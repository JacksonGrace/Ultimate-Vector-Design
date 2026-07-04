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

    // ---------- 3D scene state ----------
    private final Group trackGroup = new Group();
    private final Group world = new Group();
    private final Translate camPivot = new Translate();  // orbit centre (moves after flying)
    private final Rotate camYaw = new Rotate(-30, Rotate.Y_AXIS);
    private final Rotate camPitch = new Rotate(-20, Rotate.X_AXIS);
    private final Translate camZoom = new Translate(0, 0, -260);
    private double lastMouseX, lastMouseY;
    private boolean worldCentered = false;

    private SubScene sub;
    private PerspectiveCamera orbitCam, povCam;
    private boolean povActive = false;
    private double povTime;        // current ride position (s); W/S move it
    private long povLastNanos;
    private AnimationTimer povTimer;
    private List<FVDIntegrator.Sample> lastSamples;

    // WASD/mouse fly camera (toggled with right-click in the 3D view)
    private static final double FLY_SPEED = 25;        // m/s base
    private static final double FLY_LOOK_SENS = 0.12;  // deg per pixel
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
    private Spinner<Double> heartSpinner, gaugeSpinner, tieSpinner;
    private Spinner<Double> startXSpinner, startYSpinner, startZSpinner;
    private SplitPane mainSplit;           // 3D view over graph panel
    private double lastDividerPos = 0.6;   // restore point for the collapse toggle
    private RadioMenuItem gradientModeItem, solidModeItem;

    // app-wide settings persisted across runs (shortcuts, FOV)
    private final AppSettings settings = new AppSettings();

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        // ----- 3D subscene -----
        orbitCam = new PerspectiveCamera(true);
        orbitCam.setNearClip(0.1);
        orbitCam.setFarClip(5000);
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
        // fly hands off to/from the orbit camera, so their FOVs must match
        // (the Options FOV slider applies to the POV ride cam only)
        flyCam.setFieldOfView(orbitCam.getFieldOfView());
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
            double dx = e.getSceneX() - lastMouseX, dy = e.getSceneY() - lastMouseY;
            camYaw.setAngle(camYaw.getAngle() + dx * 0.3);
            camPitch.setAngle(clamp(camPitch.getAngle() - dy * 0.3, -89, 89));
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
        });
        sub.setOnScroll(e -> {
            if (flyActive) {
                // burst move along the view direction (much faster than WASD)
                Point3D fwd = flyForward();
                double d = e.getDeltaY() * 0.5;
                flyPos.setX(flyPos.getX() + fwd.getX() * d);
                flyPos.setY(flyPos.getY() + fwd.getY() * d);
                flyPos.setZ(flyPos.getZ() + fwd.getZ() * d);
            } else if (povActive) {
                // burst move along the TRACK (fast, like fly-mode scroll)
                if (lastSamples == null || lastSamples.isEmpty()) return;
                double rideTime = lastSamples.get(lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                povTime = ((povTime + e.getDeltaY() * 0.02) % rideTime + rideTime) % rideTime;
                if (settings.povMarker()) editor.refresh();
            } else {
                camZoom.setZ(clamp(camZoom.getZ() + e.getDeltaY() * 0.5, -1500, -10));
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
            }
        };

        StackPane subHolder = new StackPane(sub);
        sub.widthProperty().bind(subHolder.widthProperty());
        sub.heightProperty().bind(subHolder.heightProperty());

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
            b.setStyle(btnStyle);
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
        appendBtn.setOnAction(e -> editor.appendSecond());

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
        });
        stage.setTitle("FVD Coaster Designer");
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
    }

    private void handleKey(KeyEvent e) {
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
        else if (matches("lockv", e))   editor.toggleAxisLock(true);
        else if (matches("lockh", e))   editor.toggleAxisLock(false);
        else if (matches("delNode", e)) editor.deleteSelected();
        else if (matches("append", e))  editor.appendSecond();
        else if (matches("pov", e))     togglePov();
        else if (matches("lane1", e))   editor.setActiveLane(0);
        else if (matches("lane2", e))   editor.setActiveLane(1);
        else if (matches("lane3", e))   editor.setActiveLane(2);
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
        heartSpinner.valueProperty().addListener((obs, o, v) -> { heartOffset = v; rebuildTrack(); });
        gaugeSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.3, 2.5, railGauge, 0.1));
        gaugeSpinner.setPrefWidth(70);
        gaugeSpinner.valueProperty().addListener((obs, o, v) -> { railGauge = v; rebuildTrack(); });
        tieSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.2, 10.0, tieSpacing, 0.1));
        tieSpinner.setPrefWidth(70);
        tieSpinner.valueProperty().addListener((obs, o, v) -> { tieSpacing = v; rebuildTrack(); });
        startXSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, startX, 1));
        startXSpinner.setPrefWidth(70);
        startXSpinner.valueProperty().addListener((obs, o, v) -> { startX = v; rebuildTrack(); });
        startYSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, startY, 1));
        startYSpinner.setPrefWidth(70);
        startYSpinner.valueProperty().addListener((obs, o, v) -> { startY = v; rebuildTrack(); });
        startZSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, startZ, 1));
        startZSpinner.setPrefWidth(70);
        startZSpinner.valueProperty().addListener((obs, o, v) -> { startZ = v; rebuildTrack(); });

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
        gradientModeItem.setOnAction(e -> { railSolid = false; rebuildTrack(); });
        solidModeItem = new RadioMenuItem("Rail color: solid");
        solidModeItem.setToggleGroup(colorMode);
        solidModeItem.setOnAction(e -> { railSolid = true; rebuildTrack(); });
        (railSolid ? solidModeItem : gradientModeItem).setSelected(true);

        MenuItem colorItem = new MenuItem("Choose solid color…");
        colorItem.setOnAction(e -> showRailColorDialog());

        Menu coasterMenu = new Menu("Coaster", null,
            new CustomMenuItem(heartRow, false),
            new CustomMenuItem(gaugeRow, false),
            new CustomMenuItem(tieRow, false),
            new CustomMenuItem(startRow, false),
            new SeparatorMenuItem(),
            gradientModeItem,
            solidModeItem,
            colorItem);

        return new MenuBar(fileMenu, editMenu, coasterMenu);
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
            railColor = picker.getValue();
            if (!railSolid) {           // picking a color implies solid mode
                railSolid = true;
                solidModeItem.setSelected(true);
            }
            rebuildTrack();
        });

        HBox row = new HBox(10, new Label("Solid rail color"), picker);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14));
        dlg.setScene(new Scene(row));
        dlg.show();
    }

    /** Visual-space options (app-wide, persisted). Just FOV for now; colors,
     *  font sizes etc. slot in as more rows here. */
    private void showOptionsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Options");

        Slider fov = new Slider(30, 110, settings.fov());
        fov.setPrefWidth(240);
        fov.setShowTickMarks(true);
        fov.setShowTickLabels(true);
        fov.setMajorTickUnit(20);
        Label fovVal = new Label(String.format("%.0f°", settings.fov()));
        fov.valueProperty().addListener((obs, o, v) -> {
            povCam.setFieldOfView(v.doubleValue());
            fovVal.setText(String.format("%.0f°", v.doubleValue()));
            if (!fov.isValueChanging()) settings.setFov(v.doubleValue());
        });
        // save once at drag end instead of on every tick
        fov.valueChangingProperty().addListener((obs, was, is) -> {
            if (!is) settings.setFov(fov.getValue());
        });

        CheckBox povMark = new CheckBox("POV position marker on the graph");
        povMark.setSelected(settings.povMarker());
        povMark.setOnAction(ev -> {
            settings.setPovMarker(povMark.isSelected());
            editor.refresh();
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        grid.addRow(0, new Label("POV field of view"), fov, fovVal);
        grid.add(povMark, 0, 1, 3, 1);

        dlg.setScene(new Scene(grid));
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
        }
    }

    /** Mouse look: measure the offset from the viewport centre, then warp
     *  the (hidden) cursor back — JavaFX cannot capture the pointer, so
     *  Robot-recentering is the standard FPS-camera workaround. */
    private void flyLook(MouseEvent e) {
        if (!flyActive) return;
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c == null) return;
        double dx = e.getScreenX() - c.getX(), dy = e.getScreenY() - c.getY();
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;  // our own warp event
        flyYaw.setAngle(flyYaw.getAngle() + dx * FLY_LOOK_SENS);
        flyPitch.setAngle(clamp(flyPitch.getAngle() - dy * FLY_LOOK_SENS, -89, 89));
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
            // static transparent reference grid (FVD++-style): placed ONCE
            // just below the initial track and never moved while editing
            double lowest = 1e9;
            for (FVDIntegrator.Sample sm : samples) {
                double railY = sm.pos().y() - sm.U().y() * heartOffset;
                lowest = Math.min(lowest, railY);
            }
            world.getChildren().add(buildGrid(Math.floor(lowest - 1)));
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
            "[%s/%s/%s lane · %s smooth · Shift=in-line Ctrl=snap · %s append · %s POV · %s/%s save/load · %s undo]",
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

    /** See-through floor grid: 1 m lines, heavier every 10 m, fading out in
     *  concentric square bands. JavaFX 3D has no texture mipmapping, so
     *  distant sub-pixel lines shimmer/moiré badly; fading them with
     *  distance — and dropping the minor lines entirely in the far band —
     *  is the workaround. */
    private Group buildGrid(double y) {
        double[] edge   = { 0, 50, 100, 150 };   // band boundaries (m from centre)
        double[] minorA = { 0.26, 0.12, 0 };     // minor-line alpha per band (0 = none)
        double[] majorA = { 0.50, 0.30, 0.15 };
        Group g = new Group();
        for (int b = 0; b < 3; b++) {
            boolean last = b == 2;
            if (minorA[b] > 0) {
                g.getChildren().add(buildGridBand(y, edge[b], edge[b + 1], last, 1, 0.03,
                    Color.color(0.55, 0.60, 0.70, minorA[b]), true));
            }
            g.getChildren().add(buildGridBand(y, edge[b], edge[b + 1], last, 10, 0.08,
                Color.color(0.75, 0.80, 0.90, majorA[b]), false));
        }
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
        private static final double[] LANE_MIN = { -2, -3, -120 };
        private static final double[] LANE_MAX = {  6,  3,  120 };
        private final Color[] laneColors = {
            Color.rgb(120, 220, 130), Color.rgb(120, 170, 255), Color.rgb(255, 170, 90)
        };

        private static final double MARGIN_LEFT = 58, MARGIN_RIGHT = 14;
        private static final double MARGIN_TOP = 8, MARGIN_BOTTOM = 32;
        private static final double NODE_RADIUS = 5, NODE_HIT = 9;
        private static final double KNOB_RADIUS = 4.5, KNOB_HIT = 9;
        private static final double HANDLE_ALPHA = 0.45;
        private static final double INACTIVE_ALPHA = 0.25;
        private static final double MIN_GAP = 0.1;    // node-drag spacing
        private static final double INSERT_GAP = 0.02; // insert may get much closer
        private static final double MIN_FRAC = 0.02;

        // node-drag snap (with Ctrl): TRANSITION length to 0.1 s steps,
        // value to a GLOBAL absolute grid (0.1 G; whole deg/s for roll)
        private static final double SNAP_T = 0.1;
        private static final double[] SNAP_VAL = { 0.1, 0.1, 1 };

        // handle-length snap points (with Ctrl): fraction of the segment
        // that, with level handles on both ends, approximates each ease
        // (derived by matching the curve's centre slope: f = 1 - 1/slope)
        private static final double SINE_FRAC = 1 - 2 / Math.PI;
        private static final double[] HANDLE_PRESETS =
            { 1.0 / 3, SINE_FRAC, 7.0 / 15, 0.5523 };
        private static final String[] HANDLE_PRESET_NAMES =
            { "cubic", "sine", "quintic", "circle" };
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

        // node-drag axis lock: 0 free, 1 vertical only (Z), 2 horizontal only (X)
        private int axisLock = 0;

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
                    if (selIdx >= 0) {
                        int knob = hitKnob(e.getX(), e.getY());
                        if (knob != 0) {
                            beginGesture();
                            dragHandleIdx = selIdx;
                            dragHandleIsOut = knob > 0;
                            return;
                        }
                    }
                    int hit = hitNode(e.getX(), e.getY());
                    if (hit >= 0) {
                        beginGesture();
                        if (hit != selIdx) axisLock = 0;   // lock is per selection
                        dragNodeIdx = hit;
                        selIdx = hit;
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
                        } else if (selIdx >= 0) {
                            selIdx = -1;
                            axisLock = 0;
                            redraw();
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
                double t0 = viewStart(), t1 = viewEnd();
                double width = t1 - t0;
                double newW = width * (e.getDeltaY() > 0 ? 1 / 1.15 : 1.15);
                if (newW >= domain()) {
                    viewT0 = 0; viewT1 = -1;
                    redraw();
                    return;
                }
                newW = Math.max(0.5, newW);
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
                    // free by default; SHIFT locks in-line, CTRL snaps
                    dragHandleTo(e.getX(), e.getY(), e.isShiftDown(), e.isControlDown());
                } else if (dragNodeIdx >= 0) {
                    commitGestureSnapshot();
                    dragNodeTo(e.getX(), e.getY(), e.isControlDown());
                }
            });

            canvas.setOnMouseReleased(e -> {
                dragNodeIdx = -1;
                dragHandleIdx = -1;
                dragPresetName = null;
                panLastX = Double.NaN;
                pendingSnapshot = null;
                gestureMoved = false;
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
        void clearSelection() { selIdx = -1; axisLock = 0; }

        // ---------- toolbar actions ----------

        void setActiveLane(int lane) {
            if (lane == activeLane) return;
            activeLane = lane;
            selIdx = -1;
            axisLock = 0;
            laneButtons[lane].setSelected(true);
            redraw();
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

        private double viewStart() { return viewT1 > 0 ? viewT0 : 0; }
        private double viewEnd()   { return viewT1 > 0 ? viewT1 : domain(); }

        private double tToX(double t) {
            return MARGIN_LEFT + (t - viewStart()) / (viewEnd() - viewStart()) * plotWidth();
        }
        private double xToT(double x) {
            return viewStart()
                + clamp((x - MARGIN_LEFT) / plotWidth(), 0, 1) * (viewEnd() - viewStart());
        }
        private double valToY(int lane, double val) {
            double t = (val - LANE_MIN[lane]) / (LANE_MAX[lane] - LANE_MIN[lane]);
            return MARGIN_TOP + plotHeight() * (1 - clamp(t, -0.05, 1.05));
        }
        private double yToVal(int lane, double y) {
            double t = 1 - (y - MARGIN_TOP) / plotHeight();
            return LANE_MIN[lane] + clamp(t, 0, 1) * (LANE_MAX[lane] - LANE_MIN[lane]);
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

        private void dragNodeTo(double x, double y, boolean snap) {
            List<BezierBaker.Node> lane = active();
            BezierBaker.Node n = lane.get(dragNodeIdx);
            double newT = xToT(x);
            double newVal = yToVal(activeLane, y);
            if (snap) {   // Ctrl enables snapping; free otherwise
                // time: quantize the TRANSITION from the previous node
                // (0.3 s, 0.8 s, ...), not the absolute position
                if (dragNodeIdx > 0) {
                    double prevT = lane.get(dragNodeIdx - 1).t;
                    newT = prevT + Math.max(MIN_GAP,
                        Math.round((newT - prevT) / SNAP_T) * SNAP_T);
                }
                // value: global absolute grid (0.0 G, 3.6 G, 124 deg/s ...)
                newVal = Math.round(newVal / SNAP_VAL[activeLane]) * SNAP_VAL[activeLane];
            }
            if (axisLock == 1) newT = n.t;      // vertical only: time pinned
            if (axisLock == 2) newVal = n.val;  // horizontal only: value pinned
            moveNode(dragNodeIdx, newT, newVal);
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
            for (int i = 1; i < poly.size(); i++) {
                double px1 = tToX(poly.get(i)[0]), py1 = valToY(activeLane, poly.get(i)[1]);
                double segX = px1 - px0, segY = py1 - py0;
                double len2 = segX * segX + segY * segY;
                double u = len2 < 1e-9 ? 0
                         : clamp(((x - px0) * segX + (y - py0) * segY) / len2, 0, 1);
                double dx = x - (px0 + segX * u), dy = y - (py0 + segY * u);
                double d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestT = poly.get(i - 1)[0] + (poly.get(i)[0] - poly.get(i - 1)[0]) * u;
                }
                px0 = px1; py0 = py1;
            }
            if (Double.isNaN(bestT)) return Double.NaN;
            // require just enough room on both sides to insert — the dot
            // must only appear where a click will actually succeed
            int i = 0;
            while (i < lane.size() && lane.get(i).t < bestT) i++;
            if (i == 0 || i == lane.size()) return Double.NaN;
            if (bestT - lane.get(i - 1).t < INSERT_GAP
                    || lane.get(i).t - bestT < INSERT_GAP) return Double.NaN;
            return bestT;
        }

        /** Insert a node ON the curve. Flat segments get sine handles at the
         *  same level; anything else is a de Casteljau split — tangent
         *  handles, lengths rewritten so the curve does not change at all. */
        private void insertNodeOnCurve(double t) {
            List<BezierBaker.Node> lane = active();
            int i = 0;
            while (i < lane.size() && lane.get(i).t < t) i++;
            if (i == 0 || i == lane.size()) return;
            BezierBaker.Node a = lane.get(i - 1), b = lane.get(i);
            if (t - a.t < INSERT_GAP || b.t - t < INSERT_GAP) return;
            pushUndoSnapshot();
            BezierBaker.Node n;
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
            redraw();
            rebuildTrack();
        }

        void deleteSelected() {
            if (selIdx >= 0) deleteNode(selIdx);
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

            TextField dtField = null, dvField = null, valField = null;
            TextField inField = null, outField = null;
            int row = 0;
            if (prev != null) {
                dtField = new TextField(String.format(Locale.US, "%.3f", n.t - prev.t));
                grid.addRow(row++, new Label("Transition time (s)"), dtField);
                dvField = new TextField(String.format(Locale.US, "%.3f", n.val - prev.val));
                grid.addRow(row++, new Label("Transition Δ (" + LANE_UNITS[activeLane].trim() + ")"), dvField);
                inField = new TextField(String.format(Locale.US, "%.3f", n.inFrac));
                grid.addRow(row++, new Label("In handle (fraction)"), inField);
            } else {
                valField = new TextField(String.format(Locale.US, "%.3f", n.val));
                grid.addRow(row++, new Label("Value (" + LANE_UNITS[activeLane].trim() + ")"), valField);
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

            final TextField fDt = dtField, fDv = dvField, fVal = valField;
            final TextField fIn = inField, fOut = outField;
            ok.setOnAction(ev -> {
                pushUndoSnapshot();
                if (fIn != null)  n.inFrac = clamp(parseOr(fIn, n.inFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                if (fOut != null) n.outFrac = clamp(parseOr(fOut, n.outFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                if (prev != null) {
                    double dt = Math.max(MIN_GAP, parseOr(fDt, n.t - prev.t));
                    double dv = parseOr(fDv, n.val - prev.val);
                    moveNode(idx, prev.t + dt, prev.val + dv);   // honors ripple toggles
                } else {
                    moveNode(idx, n.t, parseOr(fVal, n.val));
                }
                dlg.close();
            });
            cancel.setOnAction(ev -> dlg.close());

            dlg.setScene(new Scene(grid));
            dlg.show();
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
            gc.setGlobalAlpha(1.0);
            gc.clearRect(0, 0, w, h);
            gc.setFill(Color.rgb(20, 22, 28));
            gc.fillRect(0, 0, w, h);

            gc.setFill(Color.rgb(28, 31, 40));
            gc.fillRect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - MARGIN_RIGHT, plotHeight());

            // adaptive grid: nice 1-2-5 steps, density follows zoom/size
            double vt0 = viewStart(), vt1 = viewEnd();
            double gridTStep = niceStep((vt1 - vt0) * 60 / plotWidth());
            gc.setStroke(Color.rgb(42, 47, 60));
            gc.setLineWidth(1);
            for (double t = Math.ceil(vt0 / gridTStep) * gridTStep; t <= vt1 + 1e-9; t += gridTStep) {
                double gx = tToX(t);
                gc.strokeLine(gx, MARGIN_TOP, gx, MARGIN_TOP + plotHeight());
            }
            double vRange = LANE_MAX[activeLane] - LANE_MIN[activeLane];
            double gridVStep = niceStep(vRange * 30 / plotHeight());
            for (double v = Math.ceil(LANE_MIN[activeLane] / gridVStep) * gridVStep;
                    v <= LANE_MAX[activeLane] + 1e-9; v += gridVStep) {
                double gy = valToY(activeLane, v);
                gc.strokeLine(MARGIN_LEFT, gy, w - MARGIN_RIGHT, gy);
            }

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

                if (isSel) {
                    gc.setStroke(Color.WHITE);
                    gc.setLineWidth(1.5);
                    gc.strokeOval(px - NODE_RADIUS - 3, py - NODE_RADIUS - 3,
                                  (NODE_RADIUS + 3) * 2, (NODE_RADIUS + 3) * 2);
                }
                gc.setFill(isDragged ? Color.WHITE : laneColors[activeLane]);
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

            // insert preview: translucent white dot riding the curve
            if (!Double.isNaN(hoverT)) {
                double hx = tToX(hoverT);
                double hy = valToY(activeLane, BezierBaker.evalAt(lane, hoverT));
                gc.setGlobalAlpha(0.5);
                gc.setFill(Color.WHITE);
                gc.fillOval(hx - 4, hy - 4, 8, 8);
                gc.setGlobalAlpha(1.0);
            }

            // axis-lock guide: the line the node rides along (Z/X)
            if (selIdx >= 0 && axisLock != 0) {
                double lx = tToX(lane.get(selIdx).t);
                double ly = valToY(activeLane, lane.get(selIdx).val);
                gc.setGlobalAlpha(0.25);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1.5);
                if (axisLock == 1) gc.strokeLine(lx, MARGIN_TOP, lx, MARGIN_TOP + plotHeight());
                else gc.strokeLine(MARGIN_LEFT, ly, w - MARGIN_RIGHT, ly);
                gc.setGlobalAlpha(1.0);
            }

            // POV camera position on the track (toggle in Edit > Options)
            if (povActive && settings.povMarker()) {
                double px2 = tToX(povTime);
                if (px2 >= MARGIN_LEFT - 1 && px2 <= w - MARGIN_RIGHT + 1) {
                    gc.setGlobalAlpha(0.4);
                    gc.setStroke(Color.rgb(255, 245, 200));
                    gc.setLineWidth(2);
                    gc.strokeLine(px2, MARGIN_TOP, px2, MARGIN_TOP + plotHeight());
                    gc.setGlobalAlpha(1.0);
                }
            }

            if (selIdx >= 0) drawHandles(gc, selIdx);

            gc.setGlobalAlpha(1.0);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(laneColors[activeLane]);
            gc.setTextBaseline(VPos.TOP);
            gc.fillText(String.format("%.0f", LANE_MAX[activeLane]), 6, MARGIN_TOP);
            gc.setTextBaseline(VPos.BOTTOM);
            gc.fillText(String.format("%.0f", LANE_MIN[activeLane]), 6, MARGIN_TOP + plotHeight());

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
            String tickFmt = gridTStep < 1 ? "%.1f" : "%.0f";
            for (double t = Math.ceil(vt0 / gridTStep) * gridTStep; t <= vt1 + 1e-9; t += gridTStep) {
                gc.fillText(String.format(tickFmt, t), tToX(t), h - 16);
            }
            gc.setFont(Font.font(11));
            gc.fillText("Time (s)", MARGIN_LEFT + plotWidth() / 2, h - 2);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
        }

        private void strokeLaneCurve(GraphicsContext gc, int laneIdx) {
            List<BezierBaker.Node> lane = lanes.get(laneIdx);
            gc.setStroke(laneColors[laneIdx]);
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
            // hold the final value out to the ride end if this lane is shorter
            double end = maxT();
            BezierBaker.Node last = lane.get(lane.size() - 1);
            if (last.t < end - 1e-9) {
                gc.lineTo(tToX(end), valToY(laneIdx, last.val));
            }
            gc.stroke();
        }

        private void drawHandles(GraphicsContext gc, int idx) {
            List<BezierBaker.Node> lane = active();
            BezierBaker.Node n = lane.get(idx);
            double nx = tToX(n.t);
            double ny = valToY(activeLane, n.val);

            for (boolean out : new boolean[]{ true, false }) {
                double[] k = knobPos(idx, out);
                if (k == null) continue;
                boolean dragging = (idx == dragHandleIdx && out == dragHandleIsOut);

                gc.setGlobalAlpha(dragging ? 1.0 : HANDLE_ALPHA);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1);
                gc.strokeLine(nx, ny, k[0], k[1]);
                gc.setFill(Color.WHITE);
                if (n.smooth) {
                    gc.fillOval(k[0] - KNOB_RADIUS, k[1] - KNOB_RADIUS, KNOB_RADIUS * 2, KNOB_RADIUS * 2);
                } else {
                    gc.fillRect(k[0] - KNOB_RADIUS, k[1] - KNOB_RADIUS, KNOB_RADIUS * 2, KNOB_RADIUS * 2);
                }
                if (dragging) {
                    gc.setFont(Font.font(10));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.setTextBaseline(VPos.TOP);
                    gc.fillText(String.format("%s %.0f%%%s  dv %.2f",
                        out ? "out" : "in",
                        (out ? n.outFrac : n.inFrac) * 100,
                        dragPresetName != null ? " " + dragPresetName : "",
                        out ? n.outDv : n.inDv),
                        k[0] + 8, k[1] - 16);
                }
            }
            gc.setGlobalAlpha(1.0);
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
