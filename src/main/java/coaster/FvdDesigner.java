package coaster;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.VPos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

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
 * Controls:
 *   1/2/3 (or lane buttons)  choose the editable curve; others translucent
 *   click node               select; Bezier handles appear
 *   drag handle              length only; hold SHIFT to change angle
 *   B                        toggle smooth/broken handles on selected node
 *   double-click / rt-click  add / delete node (active lane only)
 *   A                        append 1 s of track holding final values
 *   R (or POV button)        ride the coaster; R again to exit
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

    // ---------- track appearance / geometry settings ----------
    private double heartOffset = 1.0;  // m from heartline down to track (negative = inverted)
    private double railGauge = 1.0;    // m between rail centers
    private static final double RAIL_RADIUS = 0.07; // 140 mm diameter
    private static final int RAIL_SIDES = 8;
    private static final int SAMPLE_STRIDE = 3;
    private static final double G_MIN = -1.0, G_MAX = 5.0;
    private static final double INTEGRATE_DT = 0.01;

    // ---------- 3D scene state ----------
    private final Group trackGroup = new Group();
    private final Group world = new Group();
    private final Rotate camYaw = new Rotate(-30, Rotate.Y_AXIS);
    private final Rotate camPitch = new Rotate(-20, Rotate.X_AXIS);
    private final Translate camZoom = new Translate(0, 0, -260);
    private double lastMouseX, lastMouseY;
    private boolean worldCentered = false;

    private SubScene sub;
    private PerspectiveCamera orbitCam, povCam;
    private boolean povActive = false;
    private long povStartNanos;
    private AnimationTimer povTimer;
    private List<FVDIntegrator.Sample> lastSamples;

    private Stage stage;
    private final Label statsLabel = new Label();
    private GraphEditor editor;
    private final ToggleButton[] laneButtons = new ToggleButton[3];
    private Spinner<Double> heartSpinner, gaugeSpinner;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        // ----- 3D subscene -----
        orbitCam = new PerspectiveCamera(true);
        orbitCam.setNearClip(0.1);
        orbitCam.setFarClip(5000);
        Group cameraRig = new Group(orbitCam);
        cameraRig.getTransforms().addAll(camYaw, camPitch, camZoom);

        povCam = new PerspectiveCamera(true);
        povCam.setNearClip(0.05);
        povCam.setFarClip(5000);
        povCam.setFieldOfView(65);
        Group povHolder = new Group(povCam);

        AmbientLight ambient = new AmbientLight(Color.rgb(120, 120, 130));
        PointLight sun = new PointLight(Color.WHITE);
        sun.setTranslateX(-150);
        sun.setTranslateY(-300);
        sun.setTranslateZ(-100);

        // povHolder lives inside `world` so the centering translate applies
        // to the POV camera exactly as it does to the track mesh
        world.getChildren().addAll(trackGroup, povHolder);
        Group root3D = new Group(world, cameraRig, ambient, sun);

        sub = new SubScene(root3D, 800, 400, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(15, 18, 24));
        sub.setCamera(orbitCam);

        sub.setOnMousePressed(e -> { lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY(); });
        sub.setOnMouseDragged(e -> {
            if (povActive) return;
            double dx = e.getSceneX() - lastMouseX, dy = e.getSceneY() - lastMouseY;
            camYaw.setAngle(camYaw.getAngle() + dx * 0.3);
            camPitch.setAngle(clamp(camPitch.getAngle() - dy * 0.3, -89, 89));
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
        });
        sub.setOnScroll(e -> {
            if (!povActive) camZoom.setZ(clamp(camZoom.getZ() + e.getDeltaY() * 0.5, -1500, -10));
        });

        povTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (lastSamples == null || lastSamples.isEmpty()) return;
                double rideTime = lastSamples.get(lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                double t = ((now - povStartNanos) / 1e9) % rideTime;
                int idx = (int) clamp(t / INTEGRATE_DT, 0, lastSamples.size() - 1);
                applyPovCamera(lastSamples.get(idx));
            }
        };

        StackPane subHolder = new StackPane(sub);
        sub.widthProperty().bind(subHolder.widthProperty());
        sub.heightProperty().bind(subHolder.heightProperty());

        // ----- editor + toolbar -----
        editor = new GraphEditor();

        ToggleGroup laneGroup = new ToggleGroup();
        String[] names = { "1 Vert G", "2 Lat G", "3 Roll" };
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

        heartSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-3.0, 3.0, heartOffset, 0.1));
        heartSpinner.setPrefWidth(70);
        heartSpinner.setFocusTraversable(false);
        heartSpinner.valueProperty().addListener((obs, o, v) -> { heartOffset = v; rebuildTrack(); });
        gaugeSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.3, 2.5, railGauge, 0.1));
        gaugeSpinner.setPrefWidth(70);
        gaugeSpinner.setFocusTraversable(false);
        gaugeSpinner.valueProperty().addListener((obs, o, v) -> { railGauge = v; rebuildTrack(); });

        Label heartLbl = new Label("Heart");
        heartLbl.setTextFill(Color.rgb(200, 205, 215));
        Label gaugeLbl = new Label("Gauge");
        gaugeLbl.setTextFill(Color.rgb(200, 205, 215));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button povBtn = new Button("POV (R)");
        povBtn.setFocusTraversable(false);
        povBtn.setStyle(btnStyle);
        povBtn.setOnAction(e -> togglePov());

        Button appendBtn = new Button("Append +1s (A)");
        appendBtn.setFocusTraversable(false);
        appendBtn.setStyle(btnStyle);
        appendBtn.setOnAction(e -> editor.appendSecond());

        Button saveBtn = new Button("Save");
        saveBtn.setFocusTraversable(false);
        saveBtn.setStyle(btnStyle);
        saveBtn.setOnAction(e -> saveDesign());

        Button loadBtn = new Button("Load");
        loadBtn.setFocusTraversable(false);
        loadBtn.setStyle(btnStyle);
        loadBtn.setOnAction(e -> loadDesign());

        toolbar.getChildren().addAll(heartLbl, heartSpinner, gaugeLbl, gaugeSpinner,
                                     spacer, povBtn, appendBtn, saveBtn, loadBtn);

        statsLabel.setTextFill(Color.rgb(200, 205, 215));
        statsLabel.setFont(Font.font(12));
        statsLabel.setPadding(new Insets(4, 8, 4, 8));
        statsLabel.setStyle("-fx-background-color: rgb(25,28,36);");
        statsLabel.setMaxWidth(Double.MAX_VALUE);

        BorderPane layout = new BorderPane();
        layout.setCenter(subHolder);
        BorderPane bottom = new BorderPane();
        bottom.setTop(new VBox(statsLabel, toolbar));
        bottom.setCenter(editor);
        layout.setBottom(bottom);

        rebuildTrack();

        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double w = Math.min(1400, vb.getWidth() * 0.95);
        double h = Math.min(880, vb.getHeight() * 0.90);

        Scene scene = new Scene(layout, w, h, true);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        stage.setTitle("FVD Coaster Designer");
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
    }

    private void handleKey(KeyEvent e) {
        KeyCode c = e.getCode();
        if (c == KeyCode.Z && e.isShortcutDown()) { editor.undo(); e.consume(); }
        else if (c == KeyCode.S && e.isShortcutDown()) { saveDesign(); e.consume(); }
        else if (c == KeyCode.O && e.isShortcutDown()) { loadDesign(); e.consume(); }
        else if (c == KeyCode.B) { editor.toggleSmooth(); e.consume(); }
        else if (c == KeyCode.A) { editor.appendSecond(); e.consume(); }
        else if (c == KeyCode.R) { togglePov(); e.consume(); }
        else if (c == KeyCode.DIGIT1 || c == KeyCode.NUMPAD1) { editor.setActiveLane(0); e.consume(); }
        else if (c == KeyCode.DIGIT2 || c == KeyCode.NUMPAD2) { editor.setActiveLane(1); e.consume(); }
        else if (c == KeyCode.DIGIT3 || c == KeyCode.NUMPAD3) { editor.setActiveLane(2); e.consume(); }
    }

    // =====================================================================
    // POV ride camera
    // =====================================================================

    private void togglePov() {
        if (!povActive) {
            if (lastSamples == null || lastSamples.isEmpty()) return;
            povActive = true;
            povStartNanos = System.nanoTime();
            sub.setCamera(povCam);
            povTimer.start();
        } else {
            povActive = false;
            povTimer.stop();
            sub.setCamera(orbitCam);
        }
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
        opts.pos0 = Vec3.ZERO;
        opts.startHeading = new Vec3(1, 0, 0);

        List<FVDIntegrator.Sample> samples = FVDIntegrator.integrateTrack(kfs, opts);
        if (samples.size() < 2) return;
        lastSamples = samples;

        // two rails, offset from the heartline
        MeshView leftRail = buildRailMesh(samples, -railGauge / 2);
        MeshView rightRail = buildRailMesh(samples, railGauge / 2);

        // ground just below the lowest rail point
        double lowest = 1e9;
        for (FVDIntegrator.Sample sm : samples) {
            double railY = sm.pos().y() - sm.U().y() * heartOffset;
            lowest = Math.min(lowest, railY);
        }
        Box ground = new Box(700, 0.2, 700);
        ground.setMaterial(new PhongMaterial(Color.rgb(40, 45, 50)));
        ground.setTranslateY(-(lowest - RAIL_RADIUS) + 1.0);

        trackGroup.getChildren().setAll(leftRail, rightRail, ground);

        if (!worldCentered) {
            FVDIntegrator.Sample mid = samples.get(samples.size() / 2);
            world.getTransforms().add(new Translate(-mid.pos().x(), 0, -mid.pos().z()));
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
            "Ride: %.1f s   |   Track: %.0f m   |   Height: %.1f to %.1f m   |   Top speed: %.0f km/h   |   Gv: %.1f to %.1f      [1/2/3 lane · B smooth · Shift+drag angle · A append · R POV · Ctrl+S/O save/load · Ctrl+Z undo]",
            last.t(), last.s(), minY, maxY, maxV * 3.6, minGv, maxGv));
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
        mat.setDiffuseMap(buildGForceGradient());
        mat.setSpecularColor(Color.rgb(200, 200, 200));
        MeshView view = new MeshView(mesh);
        view.setMaterial(mat);
        view.setCullFace(CullFace.NONE);
        return view;
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
            out.println("FVDD1");
            out.printf(Locale.US, "heart %.4f%n", heartOffset);
            out.printf(Locale.US, "gauge %.4f%n", railGauge);
            for (int lane = 0; lane < 3; lane++) {
                out.println("lane " + lane);
                for (BezierBaker.Node n : lanes.get(lane)) {
                    out.printf(Locale.US, "n %.4f %.4f %.4f %.4f %.4f %.4f %d%n",
                        n.t, n.val, n.outDt, n.outDv, n.inDt, n.inDv, n.smooth ? 1 : 0);
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

            double newHeart = heartOffset, newGauge = railGauge;
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
                    case "lane"  -> curLane = Integer.parseInt(parts[1]);
                    case "n" -> {
                        if (curLane < 0 || curLane > 2) throw new Exception("node outside lane section");
                        BezierBaker.Node n = new BezierBaker.Node(
                            Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
                        n.outDt = Double.parseDouble(parts[3]);
                        n.outDv = Double.parseDouble(parts[4]);
                        n.inDt  = Double.parseDouble(parts[5]);
                        n.inDv  = Double.parseDouble(parts[6]);
                        n.smooth = !parts[7].equals("0");
                        newLanes.get(curLane).add(n);
                    }
                    default -> { /* ignore unknown lines: forward compatibility */ }
                }
            }
            for (List<BezierBaker.Node> lane : newLanes) {
                if (lane.size() < 2) throw new Exception("each lane needs at least 2 nodes");
            }

            editor.pushUndoSnapshot(); // loading is undoable
            for (int i = 0; i < 3; i++) {
                lanes.get(i).clear();
                lanes.get(i).addAll(newLanes.get(i));
            }
            heartOffset = newHeart;
            railGauge = newGauge;
            heartSpinner.getValueFactory().setValue(newHeart);
            gaugeSpinner.getValueFactory().setValue(newGauge);
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
        private static final double[] LANE_MIN = { -2, -3, -120 };
        private static final double[] LANE_MAX = {  6,  3,  120 };
        private final Color[] laneColors = {
            Color.rgb(120, 220, 130), Color.rgb(120, 170, 255), Color.rgb(255, 170, 90)
        };

        private static final double MARGIN_LEFT = 58, MARGIN_RIGHT = 14;
        private static final double MARGIN_TOP = 8, MARGIN_BOTTOM = 20;
        private static final double NODE_RADIUS = 5, NODE_HIT = 9;
        private static final double KNOB_RADIUS = 4.5, KNOB_HIT = 9;
        private static final double HANDLE_ALPHA = 0.45;
        private static final double INACTIVE_ALPHA = 0.25;
        private static final double MIN_GAP = 0.1;
        private static final double MIN_HANDLE = 0.02;

        private final Canvas canvas = new Canvas();

        private int activeLane = 0;
        private int selIdx = -1;

        private int dragNodeIdx = -1;
        private int dragHandleIdx = -1;
        private boolean dragHandleIsOut = false;

        private final Deque<List<List<BezierBaker.Node>>> undoStack = new ArrayDeque<>();
        private List<List<BezierBaker.Node>> pendingSnapshot = null;
        private boolean gestureMoved = false;
        private static final int UNDO_LIMIT = 100;

        GraphEditor() {
            setPrefHeight(260);
            setStyle("-fx-background-color: rgb(20,22,28);");
            getChildren().add(canvas);

            canvas.setOnMousePressed(e -> {
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
                        dragNodeIdx = hit;
                        selIdx = hit;
                        redraw();
                    } else if (selIdx >= 0) {
                        selIdx = -1;
                        redraw();
                    }
                } else if (e.getButton() == MouseButton.SECONDARY) {
                    int hit = hitNode(e.getX(), e.getY());
                    if (hit >= 0) deleteNode(hit);
                }
            });

            canvas.setOnMouseDragged(e -> {
                if (dragHandleIdx >= 0) {
                    commitGestureSnapshot();
                    dragHandleTo(e.getX(), e.getY(), e.isShiftDown());
                } else if (dragNodeIdx >= 0) {
                    commitGestureSnapshot();
                    dragNodeTo(e.getX(), e.getY());
                }
            });

            canvas.setOnMouseReleased(e -> {
                dragNodeIdx = -1;
                dragHandleIdx = -1;
                pendingSnapshot = null;
                gestureMoved = false;
            });

            canvas.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
                    addNodeAt(e.getX());
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
        void clearSelection() { selIdx = -1; }

        // ---------- toolbar actions ----------

        void setActiveLane(int lane) {
            if (lane == activeLane) return;
            activeLane = lane;
            selIdx = -1;
            laneButtons[lane].setSelected(true);
            redraw();
        }

        void appendSecond() {
            pushUndoSnapshot();
            double newEnd = BezierBaker.rideEnd(lanes) + 1.0;
            for (List<BezierBaker.Node> lane : lanes) {
                BezierBaker.Node last = lane.get(lane.size() - 1);
                BezierBaker.Node np = new BezierBaker.Node(newEnd, last.val);
                double gap = newEnd - last.t;
                last.outDt = gap / 3.0; last.outDv = 0;
                np.inDt = -gap / 3.0;   np.inDv = 0;
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
                double slope = n.inDv / n.inDt;
                n.outDv = slope * n.outDt;
            }
            redraw();
            rebuildTrack();
        }

        // ---------- coordinate mapping ----------

        private double maxT() { return Math.max(0.5, BezierBaker.rideEnd(lanes)); }
        private double domain() { return maxT() * 1.08; }
        private double plotHeight() { return canvas.getHeight() - MARGIN_TOP - MARGIN_BOTTOM; }

        private double tToX(double t) {
            return MARGIN_LEFT + (t / domain()) * (canvas.getWidth() - MARGIN_LEFT - MARGIN_RIGHT);
        }
        private double xToT(double x) {
            return clamp((x - MARGIN_LEFT) / (canvas.getWidth() - MARGIN_LEFT - MARGIN_RIGHT), 0, 1) * domain();
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

        private void dragNodeTo(double x, double y) {
            List<BezierBaker.Node> lane = active();
            BezierBaker.Node n = lane.get(dragNodeIdx);
            double newT = xToT(x);
            if (dragNodeIdx == 0) {
                newT = 0;
            } else {
                double lo = lane.get(dragNodeIdx - 1).t + MIN_GAP;
                double hi = dragNodeIdx == lane.size() - 1
                        ? Double.MAX_VALUE
                        : lane.get(dragNodeIdx + 1).t - MIN_GAP;
                newT = clamp(newT, lo, hi);
            }
            n.t = newT;
            n.val = yToVal(activeLane, y);
            redraw();
            rebuildTrack();
        }

        private void dragHandleTo(double x, double y, boolean shift) {
            List<BezierBaker.Node> lane = active();
            BezierBaker.Node n = lane.get(dragHandleIdx);

            double segLen = dragHandleIsOut
                ? lane.get(dragHandleIdx + 1).t - n.t
                : n.t - lane.get(dragHandleIdx - 1).t;

            if (shift) {
                double dt = xToT(x) - n.t;
                double dv = yToVal(activeLane, y) - n.val;
                if (dragHandleIsOut) {
                    n.outDt = clamp(dt, MIN_HANDLE, segLen * 0.95);
                    n.outDv = dv;
                    if (n.smooth && dragHandleIdx > 0) {
                        double slope = n.outDv / n.outDt;
                        n.inDv = slope * n.inDt;
                    }
                } else {
                    n.inDt = clamp(dt, -segLen * 0.95, -MIN_HANDLE);
                    n.inDv = dv;
                    if (n.smooth && dragHandleIdx < lane.size() - 1) {
                        double slope = n.inDv / n.inDt;
                        n.outDv = slope * n.outDt;
                    }
                }
            } else {
                // length-only along the current direction (pixel projection)
                double curDt = dragHandleIsOut ? n.outDt : n.inDt;
                double curDv = dragHandleIsOut ? n.outDv : n.inDv;
                double slope = curDv / curDt;

                double nx = tToX(n.t), ny = valToY(activeLane, n.val);
                double kx = tToX(n.t + curDt), ky = valToY(activeLane, n.val + curDv);
                double dirX = kx - nx, dirY = ky - ny;
                double dirLen = Math.hypot(dirX, dirY);
                if (dirLen < 1e-6) return;
                dirX /= dirLen; dirY /= dirLen;

                double proj = Math.max(4, (x - nx) * dirX + (y - ny) * dirY);
                double newKx = nx + dirX * proj;

                double dt = xToT(newKx) - n.t;
                if (dragHandleIsOut) {
                    dt = clamp(dt, MIN_HANDLE, segLen * 0.95);
                    n.outDt = dt;
                    n.outDv = slope * dt;
                } else {
                    dt = clamp(dt, -segLen * 0.95, -MIN_HANDLE);
                    n.inDt = dt;
                    n.inDv = slope * dt;
                }
            }
            redraw();
            rebuildTrack();
        }

        private void addNodeAt(double x) {
            List<BezierBaker.Node> lane = active();
            double t = xToT(x);
            int i = 0;
            while (i < lane.size() && lane.get(i).t < t) i++;
            if (i == 0 || i == lane.size()) return;
            BezierBaker.Node a = lane.get(i - 1), b = lane.get(i);
            if (b.t - a.t < MIN_GAP * 3) return;
            pushUndoSnapshot();
            BezierBaker.Node n = new BezierBaker.Node(t, BezierBaker.evalAt(lane, t));
            BezierBaker.applyDefaultHandles(n, a, b);
            lane.add(i, n);
            selIdx = -1;
            redraw();
            rebuildTrack();
        }

        private void deleteNode(int index) {
            List<BezierBaker.Node> lane = active();
            if (index == 0 || index == lane.size() - 1) return;
            pushUndoSnapshot();
            lane.remove(index);
            selIdx = -1;
            redraw();
            rebuildTrack();
        }

        // ---------- drawing ----------

        private void redraw() {
            GraphicsContext gc = canvas.getGraphicsContext2D();
            double w = canvas.getWidth(), h = canvas.getHeight();
            if (w <= 0 || h <= 0) return;
            gc.setGlobalAlpha(1.0);
            gc.clearRect(0, 0, w, h);
            gc.setFill(Color.rgb(20, 22, 28));
            gc.fillRect(0, 0, w, h);

            gc.setFill(Color.rgb(28, 31, 40));
            gc.fillRect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - MARGIN_RIGHT, plotHeight());

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
                    gc.fillText(String.format("t=%.2fs  %.2f",
                        lane.get(i).t, lane.get(i).val), px + 8, py - 20);
                }
            }

            if (selIdx >= 0) drawHandles(gc, selIdx);

            gc.setGlobalAlpha(1.0);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(laneColors[activeLane]);
            gc.setTextBaseline(VPos.TOP);
            gc.fillText(String.format("%.0f", LANE_MAX[activeLane]), 6, MARGIN_TOP);
            gc.fillText(LANE_NAMES[activeLane], 6, MARGIN_TOP + 16);
            gc.setTextBaseline(VPos.BOTTOM);
            gc.fillText(String.format("%.0f", LANE_MIN[activeLane]), 6, MARGIN_TOP + plotHeight());

            gc.setFill(Color.rgb(130, 135, 150));
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.CENTER);
            int ticks = 8;
            for (int i = 0; i <= ticks; i++) {
                double t = maxT() * i / ticks;
                gc.fillText(String.format("%.1fs", t), tToX(t), h - 2);
            }
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
                    gc.fillText(String.format("%s %.2fs, %.2f",
                        out ? "out" : "in",
                        out ? n.outDt : n.inDt,
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
