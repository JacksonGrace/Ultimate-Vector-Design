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
import javafx.scene.shape.Cylinder;
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
 * The 3D VIEWPORT (Phase 1 milestone B split part 2, D54) — extracted
 * verbatim from FvdDesigner: cameras (orbit/fly/POV + pseudo-ortho
 * views), rail/tie/heartline meshes, ground grid + LOD, on-track node
 * markers, track picking, and the rebuild pipeline entry. Outer state
 * is reached through `app` (document, settings, editor, cosmetics).
 */
class Viewport3D {

    /** The shell: document, settings, editor and file state. */
    private final FvdDesigner app;

    static final double RAIL_RADIUS = 0.07; // 140 mm diameter
    static final int RAIL_SIDES = 8;
    static final int SAMPLE_STRIDE = 3;
    // consecutive mesh rings further apart than this are a PIECE GAP,
    // not track — no tube segment across (max legit ring spacing is
    // SAMPLE_STRIDE × v·dt = 3 m at the 100 m/s speed cap)
    static final double MESH_GAP_BREAK = 3.5;
    // plain-click anchor: a clean primary release on the track
    // activates its piece + section (v13.1)
    private double clickX = Double.NaN, clickY;

    // ---------- 3D scene state ----------
    final Group trackGroup = new Group();
    final Group world = new Group();
    final Translate camPivot = new Translate();  // orbit centre (moves after flying)
    final Rotate camYaw = new Rotate(-30, Rotate.Y_AXIS);
    final Rotate camPitch = new Rotate(-20, Rotate.X_AXIS);
    // initial orbit camera distance, metres (user-tuned: was 260, "much
    // closer" requested — scroll zooms out to the whole ride quickly)
    static final double ORBIT_DIST0 = 60;
    final Translate camZoom = new Translate(0, 0, -ORBIT_DIST0);
    // ortho top/side views: PSEUDO-orthographic — JavaFX ParallelCamera is
    // unusable for 3D scenes (pixel-unit view volume, no depth control), so
    // ortho = the same PerspectiveCamera at 1° FOV moved ~72x farther out,
    // keeping the apparent size (visible height = 2·d·tan(fov/2))
    static final double ORTHO_FOV = 1.0;
    static final double ORTHO_FAR_CLIP = 200000, PERSP_FAR_CLIP = 5000;
    boolean orthoView = false;
    boolean orthoTop = false;              // which preset is active
    double savedYaw, savedPitch, savedZoom; // perspective pose to restore
    double lastMouseX, lastMouseY;
    boolean worldCentered = false;
    Group gridGroup;                    // floor grid, recentered on the camera (LOD)
    double gridCamX0, gridCamZ0;        // scene->world offset for camera positions

    SubScene sub;
    Group heartMesh;   // neon heartline, one strand PER PIECE (v16.13
                       // — a single ride-wide mesh only broke on the
                       // 3.5 m gap heuristic, so pieces parked close
                       // together read as connected); hidden in POV
    PerspectiveCamera orbitCam, povCam;
    boolean povActive = false;
    boolean povCaptured = false;   // right-click: hide mouse, W/S ride;
                                           // uncaptured POV leaves W/S (and the
                                           // S slide key) to the graph editor
    double povTime;        // current ride position (s); W/S move it
    private int povFollowPiece = -1;   // the piece POV is riding: the
                                       // editor follows it (v13.11),
                                       // and a DIFFERENT user-selected
                                       // piece re-enters at its start
    long povLastNanos;
    AnimationTimer povTimer;
    final Group markerGroup = new Group();   // on-track node markers
    String markerSig = "";                   // skip no-op rebuilds

    // WASD/mouse fly camera (toggled with right-click in the 3D view)
    static final double FLY_SPEED = 25;        // m/s base
    PerspectiveCamera flyCam;
    final Translate flyPos = new Translate();
    final Rotate flyYaw = new Rotate(0, Rotate.Y_AXIS);
    final Rotate flyPitch = new Rotate(0, Rotate.X_AXIS);
    boolean flyActive = false;
    final Set<KeyCode> moveKeys = new HashSet<>();
    AnimationTimer flyTimer;
    long flyLastNanos;
    Robot flyRobot;

    Viewport3D(FvdDesigner app) {
        this.app = app;

        // ----- 3D subscene -----
        orbitCam = new PerspectiveCamera(true);
        orbitCam.setNearClip(0.1);
        orbitCam.setFarClip(PERSP_FAR_CLIP);
        // orbit pairs with FLY (they hand off to each other); POV has its own
        orbitCam.setFieldOfView(app.settings.flyFov());
        Group cameraRig = new Group(orbitCam);
        cameraRig.getTransforms().addAll(camPivot, camYaw, camPitch, camZoom);

        povCam = new PerspectiveCamera(true);
        povCam.setNearClip(0.05);
        povCam.setFarClip(5000);
        povCam.setFieldOfView(app.settings.fov());
        Group povHolder = new Group(povCam);

        flyCam = new PerspectiveCamera(true);
        flyCam.setNearClip(0.05);
        flyCam.setFarClip(5000);
        // fly has its OWN FOV setting; orbit + POV share the track-view one
        flyCam.setFieldOfView(app.settings.flyFov());
        Group flyHolder = new Group(flyCam);
        flyHolder.getTransforms().addAll(flyPos, flyYaw, flyPitch);

        AmbientLight ambient = new AmbientLight(Color.rgb(120, 120, 130));
        PointLight sun = new PointLight(Color.WHITE);
        sun.setTranslateX(-150);
        sun.setTranslateY(-300);
        sun.setTranslateZ(-100);

        // povHolder lives inside `world` so the centering translate applies
        // to the POV camera exactly as it does to the track mesh;
        // gizmoGroup rides along (its coords are world coords, v16.11)
        world.getChildren().addAll(trackGroup, povHolder, gizmoGroup);
        Group root3D = new Group(world, cameraRig, flyHolder, ambient, sun);

        sub = new SubScene(root3D, 800, 400, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(15, 18, 24));
        sub.setCamera(orbitCam);

        sub.setOnMousePressed(e -> {
            // pull focus off toolbar editors (the gap spinner): scene
            // key filters work regardless, but a focused TextField kept
            // receiving KEY_TYPED characters while riding/flying (v13.4)
            sub.requestFocus();
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
            if (e.getButton() == MouseButton.MIDDLE && !povActive
                    && !flyActive && (e.isShiftDown() || app.cKey)) {
                // Shift+middle-click (or C+middle) = re-center the orbit
                // on the track under the cursor (v16.12 — moved off
                // left-click with the orbit drag)
                snapPivotToTrack(e.getScreenX(), e.getScreenY());
            }
            if (e.getButton() == MouseButton.PRIMARY && !povActive && !flyActive) {
                // plain press: remember it — a CLEAN release on the
                // track activates that piece + section (v13.1), a
                // DOUBLE-click inserts a node (v16.12); left-DRAG no
                // longer orbits (middle owns the camera now)
                clickX = e.getSceneX();
                clickY = e.getSceneY();
            } else if (e.getButton() == MouseButton.PRIMARY
                    && povActive && !povCaptured) {
                // hands-off POV keeps the mouse: a clean click selects
                // the piece + section under it, same as orbit (v13.4)
                clickX = e.getSceneX();
                clickY = e.getSceneY();
            }
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
        });
        sub.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY || flyActive
                    || (povActive && povCaptured)
                    || Double.isNaN(clickX)
                    || Math.hypot(e.getSceneX() - clickX,
                                  e.getSceneY() - clickY) > 3) {
                clickX = Double.NaN;
                return;
            }
            clickX = Double.NaN;
            if (e.getClickCount() == 2) {
                // DOUBLE-click = insert a node at that track point
                // (v16.12 — moved off Ctrl+click; the first click
                // already activated the piece + section, so the
                // insert lands in the right lane, and the graph view
                // snaps to the new node like a marker click)
                insertNodeAtTrack(e.getScreenX(), e.getScreenY());
                return;
            }
            // node MARKERS take the click before the track does
            // (v16.11, user spec): a clean click on one selects that
            // node in the graph and brings it into view
            int mi = pickMarkerIndex(e.getScreenX(), e.getScreenY());
            if (mi >= 0) {
                app.editor.selectNodeFromViewport(mi);
                return;
            }
            FVDIntegrator.Sample hit = pickTrackSample(e.getScreenX(), e.getScreenY());
            if (hit != null) {
                app.editor.activateAt(hit.t());
            }
            // (the v16.13 empty-click gizmo hide is gone — gizmos are
            // always on for the active piece since v17.7, D144; the
            // View menu's check is the off switch)
        });
        sub.setOnMouseDragged(e -> {
            if (povActive || flyActive) return;
            if (orthoView && e.isSecondaryButtonDown()) {
                // right-drag in a snapped view = grab-pan in the view plane
                // (the world follows the mouse); the ortho view survives
                panView(e.getSceneX() - lastMouseX, e.getSceneY() - lastMouseY);
                lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
                return;
            }
            // MIDDLE-drag = orbit, REPLACING left-drag entirely (v16.12,
            // user re-spec of the v16.11 pan: "middle-drag to orbit");
            // left-drag does nothing so the primary button is free for
            // selection/gizmos — and never secondary: the right-drag
            // that exits fly otherwise fell through here with a huge
            // stale delta (jump bug)
            if (!e.isMiddleButtonDown()) return;
            // rotating away from a snapped ortho view returns to the
            // perspective lens but keeps the current angles
            if (orthoView) exitOrthoKeepView();
            // orbit shares the mouse-sensitivity setting (fly's 0.15 default
            // maps to the original 0.3 orbit feel via the 2x factor)
            double s = app.settings.flySens() * 2;
            double dx = e.getSceneX() - lastMouseX, dy = e.getSceneY() - lastMouseY;
            camYaw.setAngle(camYaw.getAngle() + dx * s);
            camPitch.setAngle(FvdDesigner.clamp(camPitch.getAngle() - dy * s, -89, 89));
            lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
            positionGizmo();
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
                if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
                double rideTime = app.lastSamples.get(app.lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                povTime = ((povTime + wheel * 0.02) % rideTime + rideTime) % rideTime;
                app.editor.refresh();   // POV marker always on (v16.14)
                updatePovStats();       // live POV line (v17.11)
            } else if (orthoView) {
                // ortho zoom is multiplicative (the camera sits ~72x out;
                // the additive step and its -1500 clamp would wreck it)
                double f = Math.pow(1.0015, -wheel);
                camZoom.setZ(FvdDesigner.clamp(camZoom.getZ() * f, -ORTHO_FAR_CLIP * 0.5, -200));
            } else {
                camZoom.setZ(FvdDesigner.clamp(camZoom.getZ() + wheel * 0.5, -1500, -10));
            }
            positionGizmo();   // constant apparent size through zooms
        });
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, this::flyLook);
        sub.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::flyLook);
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, this::combHover);

        // POV: W rides forward at the simulated speed, S backward;
        // SHIFT x4, CTRL x0.25; no key = hold position (follows live edits)
        povTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
                double rideTime = app.lastSamples.get(app.lastSamples.size() - 1).t();
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
                    // ride at the TRUE speed (round A, §5.1): the time
                    // axis is design time, so covering true meters per
                    // wall second means advancing it by v_true/v_design
                    // at the current sample (ratio 1 until drift)
                    int at = (int) FvdDesigner.clamp(
                        povTime / FvdDesigner.INTEGRATE_DT, 0,
                        app.lastSamples.size() - 1);
                    double ratio = app.trueV == null ? 1
                        : app.trueV[at] / Math.max(0.1, app.lastSamples.get(at).v());
                    povTime = ((povTime + dir * dt * mult * ratio) % rideTime + rideTime) % rideTime;
                    app.editor.refresh();   // marker follows (always on, v16.14)
                }
                int idx = (int) FvdDesigner.clamp(povTime / FvdDesigner.INTEGRATE_DT, 0, app.lastSamples.size() - 1);
                applyPovCamera(app.lastSamples.get(idx));
                updateGridLOD(app.lastSamples.get(idx).pos().x(), app.lastSamples.get(idx).pos().z());
                updatePovStats();   // live POV line (v17.11)
                // the editor FOLLOWS the ride across pieces (v13.11,
                // user spec: the piece you're on is the one you're
                // editing) — piece transitions only, so hands-off
                // graph edits are never fought mid-piece. NOT while
                // the convert UI owns the graph (v15.4, D102): a
                // whole-bridge convert ATTACHES a piece, the indices
                // shift, this follow saw a "new" piece under the
                // parked camera and its activation change CANCELLED
                // the density preview — restoring the pre-convert
                // state within a frame (the user's "convert whole
                // bridge did nothing in POV beyond the bridge").
                if (app.editor != null && !app.editor.convertUiActive()) {
                    int pc = app.editor.pieceAtTime(
                        app.lastSamples.get(idx).t());
                    if (pc >= 0 && pc != povFollowPiece) {
                        povFollowPiece = pc;
                        app.editor.activateAt(app.lastSamples.get(idx).t());
                    }
                }
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
                positionGizmo();   // gizmos stay visible in fly (v16.12)
            }
        };
    }

    /** Rebuild the on-track node markers (viewport editing part 1 —
     *  READ-ONLY). One small sphere per ACTIVE-lane node at its ride
     *  time, sitting on the spine like the ties; selected nodes are
     *  white. Signature-guarded: the graph calls this on every redraw,
     *  but the scene graph only churns when something relevant changed. */
    void refreshTrackMarkers(int lane, Set<Integer> sel, int selIdx,
                             double[] nodeTimes) {
        // activation changes arrive here on every graph redraw — the
        // tie highlight and the gizmo ride along (each has its own
        // signature guard, v16.11)
        refreshTieHighlight(false);
        refreshGizmo(false);
        List<BezierBaker.Node> laneNodes = app.lanes.get(lane);
        int count = Math.min(laneNodes.size(), nodeTimes.length);
        Color laneCol = app.editor.laneColor(lane);   // mode palette (geo: teal/yellow)
        StringBuilder sb = new StringBuilder();
        sb.append(lane).append('|').append(selIdx).append('|').append(sel)
          .append('|').append(laneCol)
          .append('|').append(System.identityHashCode(app.lastSamples))
          .append('|').append(app.settings.trackMarkers());
        // nodeTimes = each node's GLOBAL RIDE TIME, computed by the
        // editor (v16.0 — distance-domain lanes' x is meters, so the
        // old tOff + n.t no longer meant time)
        for (int i = 0; i < count; i++) sb.append(';').append(nodeTimes[i]);
        String sig = sb.toString();
        if (sig.equals(markerSig)) return;
        markerSig = sig;
        markerGroup.getChildren().clear();
        if (!app.settings.trackMarkers() || app.lastSamples == null || app.lastSamples.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            int idx = (int) FvdDesigner.clamp(Math.round(nodeTimes[i] / FvdDesigner.INTEGRATE_DT),
                                  0, app.lastSamples.size() - 1);
            FVDIntegrator.Sample sm = app.lastSamples.get(idx);
            Vec3 p = sm.pos().sub(sm.U().scale(app.heartOffset));   // spine, like ties
            boolean isSel = i == selIdx || sel.contains(i);
            // selection reads from any distance: white AND bigger
            Sphere ball = new Sphere(isSel ? 0.32 : 0.22, 12);
            ball.setMaterial(new PhongMaterial(isSel ? Color.WHITE : laneCol));
            ball.setTranslateX(p.x());
            ball.setTranslateY(-p.y());   // Y-flip discipline
            ball.setTranslateZ(p.z());
            markerGroup.getChildren().add(ball);
        }
    }

    /** Nearest node MARKER within ~14 px of a screen point (v16.11):
     *  markerGroup children are one sphere per active-lane node, in
     *  order, translated to Y-flipped world coords — project each and
     *  pick the closest, behind-camera filtered like pickTrackSample.
     *  Returns the node index, or -1 on a miss. */
    private int pickMarkerIndex(double screenX, double screenY) {
        if (markerGroup.getChildren().isEmpty()) return -1;
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        double best = 14 * 14;
        int bestIdx = -1;
        for (int i = 0; i < markerGroup.getChildren().size(); i++) {
            javafx.scene.Node ball = markerGroup.getChildren().get(i);
            double x = ball.getTranslateX(), y = ball.getTranslateY(),
                   z = ball.getTranslateZ();
            Point3D sceneP = world.localToScene(x, y, z);
            if (sceneP.subtract(eye).dotProduct(fwd) <= 0) continue;
            Point2D s = world.localToScreen(x, y, z);
            if (s == null) continue;
            double dx = s.getX() - screenX, dy = s.getY() - screenY;
            double d2 = dx * dx + dy * dy;
            if (d2 < best) {
                best = d2;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /** Grab-pan the orbit rig in the view plane: the world point under
     *  the cursor follows the mouse (world-meters-per-pixel from the
     *  CURRENT lens — perspective or the ortho 1° one). Shared by the
     *  ortho right-drag pan (v8.2) and the middle-drag pan (v16.11). */
    private void panView(double dx, double dy) {
        double d = -camZoom.getZ();
        double upp = 2 * d * Math.tan(Math.toRadians(
                orbitCam.getFieldOfView() / 2))
            / Math.max(1, sub.getHeight());   // world m per px
        Rotate ry = new Rotate(camYaw.getAngle(), Rotate.Y_AXIS);
        Rotate rx = new Rotate(camPitch.getAngle(), Rotate.X_AXIS);
        Point3D right = ry.transform(rx.transform(new Point3D(1, 0, 0)));
        Point3D down = ry.transform(rx.transform(new Point3D(0, 1, 0)));
        camPivot.setX(camPivot.getX() - (right.getX() * dx + down.getX() * dy) * upp);
        camPivot.setY(camPivot.getY() - (right.getY() * dx + down.getY() * dy) * upp);
        camPivot.setZ(camPivot.getZ() - (right.getZ() * dx + down.getZ() * dy) * upp);
        updateGridLOD(camPivot.getX() + gridCamX0, camPivot.getZ() + gridCamZ0);
        positionGizmo();
    }

    /** Nearest track point within ~25 px of a screen point (a cheap
     *  screen-space "tube" around the heartline — no mesh picking), or
     *  null on a miss. Samples BEHIND the camera are rejected:
     *  localToScreen happily projects those mirrored, which made clicks
     *  on empty sky hit invisible track. v13.5 fixes two half-the-time
     *  misses: the eye/forward now come from the ACTIVE camera's own
     *  transform (the old orbit-rig math misclassified the scene while
     *  the POV camera was rendering), and the distance is measured to
     *  the SEGMENTS between consecutive strided projections (near
     *  track projects them hundreds of px apart — clicks between the
     *  dots fell through the 25 px disc). */
    FVDIntegrator.Sample pickTrackSample(double screenX, double screenY) {
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return null;
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        double best = 25 * 25;
        FVDIntegrator.Sample bestSample = null;
        FVDIntegrator.Sample prev = null;
        double px = 0, py = 0;
        boolean prevOk = false;
        for (int i = 0; i < app.lastSamples.size(); i += 5) {
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            Vec3 p = sm.pos();
            Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
            Point2D s = sceneP.subtract(eye).dotProduct(fwd) > 0
                ? world.localToScreen(p.x(), -p.y(), p.z()) : null;
            if (s != null) {
                double dx = s.getX() - screenX, dy = s.getY() - screenY;
                double d2 = dx * dx + dy * dy;
                if (d2 < best) { best = d2; bestSample = sm; }
                // segment to the previous strided projection — skipped
                // across piece gaps (a phantom segment between detached
                // pieces would make empty space clickable)
                if (prevOk && prev != null
                        && sm.pos().sub(prev.pos()).length() < 6.0) {
                    double segX = s.getX() - px, segY = s.getY() - py;
                    double len2 = segX * segX + segY * segY;
                    if (len2 > 1e-9) {
                        double u = ((screenX - px) * segX
                            + (screenY - py) * segY) / len2;
                        if (u > 0 && u < 1) {
                            double qx = px + segX * u - screenX;
                            double qy = py + segY * u - screenY;
                            double d2s = qx * qx + qy * qy;
                            if (d2s < best) {
                                best = d2s;
                                bestSample = u < 0.5 ? prev : sm;
                            }
                        }
                    }
                }
                px = s.getX();
                py = s.getY();
            }
            prevOk = s != null;
            prev = sm;
        }
        return bestSample;
    }

    /** Shift+click (or C+click) in orbit mode: re-center the orbit pivot
     *  on the track point under the cursor. A miss moves nothing. */
    void snapPivotToTrack(double screenX, double screenY) {
        FVDIntegrator.Sample hit = pickTrackSample(screenX, screenY);
        if (hit == null) return;   // miss: camera stays, drag orbits normally
        Vec3 p = hit.pos();
        Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
        camPivot.setX(sceneP.getX());
        camPivot.setY(sceneP.getY());
        camPivot.setZ(sceneP.getZ());
        updateGridLOD(p.x(), p.z());
    }

    /** DOUBLE-click the track (v16.12 — was Ctrl+click): insert a node
     *  in the ACTIVE lane at that point's ride time — the 3D twin of
     *  clicking the graph curve (same insert rules: exact split, gap
     *  guards, selects the node and scrolls the graph to it). */
    void insertNodeAtTrack(double screenX, double screenY) {
        FVDIntegrator.Sample hit = pickTrackSample(screenX, screenY);
        if (hit == null) return;
        app.editor.insertAtTime(hit.t());
    }

    /** T / Shift+T: snap to a top or side view with a pseudo-orthographic
     *  lens. T again in the top view restores the saved perspective pose;
     *  Shift+T in the SIDE view RE-TANGENTS to the current selection
     *  instead of exiting (user spec, D53) — leave the side view by
     *  orbiting away (keeps angles, restores the lens) or via T, T.
     *  Top snaps to the grid; side aligns tangent to the track. */
    void toggleOrthoView(boolean top) {
        if (flyActive || povActive) return;
        if (orthoView && top && orthoTop) {   // T again in top view: exit
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
            double scale = Math.tan(Math.toRadians(app.settings.flyFov() / 2))
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
            // side elevation TANGENT to the track at the selected node
            // (D53): look perpendicular to its horizontal heading so the
            // track runs across the screen there. Of the two sides, keep
            // the one nearer the current camera. No selection (or a
            // vertical tangent): world X–Y elevation as before.
            double yaw = 0;
            Vec3 tan = app.editor != null ? app.editor.selectedTrackTangent() : null;
            if (tan != null && Math.hypot(tan.x(), tan.z()) > 1e-6) {
                double y1 = Math.toDegrees(Math.atan2(-tan.z(), tan.x()));
                yaw = angleDist(camYaw.getAngle(), y1) <= angleDist(camYaw.getAngle(), y1 + 180)
                    ? y1 : y1 + 180;
            }
            camYaw.setAngle(yaw);
            camPitch.setAngle(0);
            centerOrthoOnSelection();
        }
    }

    /** Smallest absolute difference between two angles in degrees. */
    static double angleDist(double a, double b) {
        double d = (a - b) % 360;
        if (d < -180) d += 360;
        if (d > 180) d -= 360;
        return Math.abs(d);
    }

    /** Entering the side view: center on the SELECTED node's track point
     *  (its marker shows white and larger); no selection = pivot stays. */
    void centerOrthoOnSelection() {
        if (app.editor == null) return;
        Vec3 p = app.editor.selectedTrackPoint();
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
    void exitOrthoKeepView() {
        if (!orthoView) return;
        double scale = Math.tan(Math.toRadians(ORTHO_FOV / 2))
                     / Math.tan(Math.toRadians(app.settings.flyFov() / 2));
        camZoom.setZ(FvdDesigner.clamp(camZoom.getZ() * scale, -1500, -10));
        exitOrthoLens();
    }

    void exitOrthoLens() {
        orbitCam.setFieldOfView(app.settings.flyFov());
        orbitCam.setFarClip(PERSP_FAR_CLIP);
        orthoView = false;
    }

    /** Space. From orbit: enter POV (uncaptured — right-click to take the
     *  W/S ride controls). From captured fly: switch to CAPTURED POV, mouse
     *  stays hidden. From captured POV: switch back to fly, still captured.
     *  From uncaptured POV: back to orbit. Right-click always releases. */
    void togglePov() {
        if (!povActive) {
            if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
            if (orthoView) exitOrthoKeepView();   // orbit must be sane on exit
            boolean keepCapture = flyActive;   // Space mid-flight keeps capture
            if (flyActive) toggleFly();        // hand the camera over cleanly
            povActive = true;
            // START POINT (v13.11, D85, user spec): a piece freshly
            // selected since POV last rode = start at ITS beginning;
            // otherwise RESUME where the ride left off (it used to
            // always restart at 0)
            int ap = app.editor != null ? app.editor.activePiece() : 0;
            if (ap != povFollowPiece) {
                povTime = app.editor != null
                    ? app.editor.pieceStartTime(ap) : 0;
                povFollowPiece = ap;
            } else {
                double rideEnd = app.lastSamples
                    .get(app.lastSamples.size() - 1).t();
                povTime = FvdDesigner.clamp(povTime, 0, rideEnd);
            }
            povLastNanos = 0;
            moveKeys.clear();
            povCaptured = keepCapture;
            if (povCaptured) sub.setCursor(Cursor.NONE);
            if (heartMesh != null) heartMesh.setVisible(false);
            if (combGroup != null) combGroup.setVisible(false);
            combLabel.setVisible(false);   // combs hidden in POV (v16.14)
            sub.setCamera(povCam);
            povTimer.start();
            app.editor.refresh();   // show the POV marker line
        } else if (povCaptured) {
            // captured POV -> captured fly (capture survives until right-click)
            povActive = false;
            povCaptured = false;
            povTimer.stop();
            moveKeys.clear();
            sub.setCursor(Cursor.DEFAULT);
            if (heartMesh != null) heartMesh.setVisible(true);
            if (combGroup != null) combGroup.setVisible(true);
            sub.setCamera(orbitCam);
            app.editor.refresh();
            toggleFly();
        } else {
            povActive = false;
            povTimer.stop();
            moveKeys.clear();
            if (heartMesh != null) heartMesh.setVisible(true);
            if (combGroup != null) combGroup.setVisible(true);
            sub.setCamera(orbitCam);
            app.editor.refresh();   // hide the POV marker line
        }
        refreshGizmo(false);   // gizmos hide while riding (v16.11)
        syncPovLabel();        // backward indicator (v16.16)
    }

    void toggleFly() {
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
        refreshGizmo(false);   // gizmos STAY in fly (v16.12) — rescale
    }

    /** Mouse look: measure the offset from the viewport centre, then warp
     *  the (hidden) cursor back — JavaFX cannot capture the pointer, so
     *  Robot-recentering is the standard FPS-camera workaround. */
    // where the cursor ACTUALLY rests after our last warp (v16.10
    // drift fix): Robot.mouseMove lands on a whole device pixel, but
    // the subscene centre is usually FRACTIONAL in screen coords —
    // measuring deltas against the ideal centre left a constant
    // sub-pixel bias in every event, and since the own-warp guard
    // below only skips events quiet on BOTH axes, sweeping the mouse
    // side to side applied the dy bias at full event rate: the view
    // pitched steadily on its own. NaN = not warped yet.
    private double flyRestX = Double.NaN, flyRestY;

    void flyLook(MouseEvent e) {
        if (!flyActive) return;
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c == null) return;
        double rx = Double.isNaN(flyRestX) ? c.getX() : flyRestX;
        double ry = Double.isNaN(flyRestX) ? c.getY() : flyRestY;
        // per-event delta capped: OS pointer acceleration makes fast flicks
        // arrive as huge single deltas, which felt twitchy — the cap evens
        // out slow-vs-fast response; base sensitivity is an Options slider
        double sens = app.settings.flySens();
        double dx = FvdDesigner.clamp(e.getScreenX() - rx, -35, 35);
        double dy = FvdDesigner.clamp(e.getScreenY() - ry, -35, 35);
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;  // our own warp event
        flyYaw.setAngle(flyYaw.getAngle() + dx * sens);
        flyPitch.setAngle(FvdDesigner.clamp(flyPitch.getAngle() - dy * sens, -89, 89));
        warpFlyCursor(c);
        positionGizmo();   // depth-based scale follows the look (v16.12)
    }

    /** Re-aim the parked POV camera immediately (v16.15 — the A/D
     *  reverse flip must not wait for a W/S tick). */
    void applyPovNow() {
        if (!povActive || app.lastSamples == null
                || app.lastSamples.isEmpty()) return;
        int idx = (int) FvdDesigner.clamp(
            povTime / FvdDesigner.INTEGRATE_DT, 0,
            app.lastSamples.size() - 1);
        applyPovCamera(app.lastSamples.get(idx));
    }

    void centerFlyCursor() {
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c != null) warpFlyCursor(c);
    }

    /** Warp to the centre and record where the cursor really landed —
     *  the reference the next look delta measures from. */
    private void warpFlyCursor(Point2D c) {
        flyRobot.mouseMove(c.getX(), c.getY());
        Point2D rest = flyRobot.getMousePosition();
        if (rest != null) {
            flyRestX = rest.getX();
            flyRestY = rest.getY();
        } else {
            flyRestX = Double.NaN;
        }
    }

    /** Unit view direction of the fly camera (local +Z through yaw+pitch). */
    Point3D flyForward() {
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
    void applyPovCamera(FVDIntegrator.Sample sm) {
        // REVERSED POV (v16.15): look along −T — up stays U, right
        // re-derives, so the frame keeps its handedness; W/S keep
        // moving the ride position in the same TRACK direction
        Vec3 tw = app.settings.povReversed() ? sm.T().scale(-1) : sm.T();
        Vec3 f = new Vec3(tw.x(), -tw.y(), tw.z());
        Vec3 d = new Vec3(-sm.U().x(), sm.U().y(), -sm.U().z()); // -flip(U)
        Vec3 r = d.cross(f);
        Vec3 eye = new Vec3(sm.pos().x(), -sm.pos().y(), sm.pos().z());
        Affine a = new Affine(
            r.x(), d.x(), f.x(), eye.x(),
            r.y(), d.y(), f.y(), eye.y(),
            r.z(), d.z(), f.z(), eye.z());
        povCam.getTransforms().setAll(a);
    }

    void rebuildTrack() {
        // the three-pass pipeline (Phase 1): geometry per section at its
        // design speed, then global dynamics + analysis. For the current
        // single-force-section document this reproduces the old direct
        // integrator call bit-for-bit (gated headlessly, D50).
        TrackCompiler.Result compiled = TrackCompiler.compile(app.coaster, FvdDesigner.INTEGRATE_DT);
        // SPEED RECONCILE (v14.1, D95): same-piece continuation
        // sections re-freeze vDesign to the speed that actually
        // reaches them — the design/true split never opens inside a
        // plain chain. AUTO RE-OPTIMIZE (v13.10, D84; on Coaster
        // since v13.14): upstream edits re-place optimize-mode
        // bridges; the signature check makes both passes no-ops on
        // every compile that didn't change their inputs. Heights
        // shift speeds second-order, so a few passes settle.
        for (int pass = 0; pass < 3; pass++) {
            boolean again = app.coaster.reconcileChainedSpeeds(compiled);
            if (app.coaster.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            compiled = TrackCompiler.compile(app.coaster, FvdDesigner.INTEGRATE_DT);
        }
        List<FVDIntegrator.Sample> samples = compiled.samples();
        if (samples.size() < 2) return;
        app.lastSamples = samples;
        app.resultRates = compiled.rates();
        app.trueV = compiled.trueV();
        app.trueFelt = compiled.trueFelt();
        app.trueRates = compiled.trueRates();
        app.sectionSpans = compiled.spans();
        app.updateBridgeControls();   // gap spinner tracks live moves

        // rails + cross ties PER PIECE (v13.2): meshes never span a
        // piece boundary (detached ends stay visually detached — a
        // clean bridge/attach seam abuts flush and reads as one track)
        // and inactive pieces render DESATURATED so the edited one pops
        List<javafx.scene.Node> parts = new ArrayList<>();
        List<TrackCompiler.Span> spans = compiled.spans();
        // the ACTIVE run renders full-color (v13.4: bridged pieces are
        // one track); detached pieces stay desaturated
        int runS = app.editor != null ? app.editor.runStartPiece() : 0;
        int runE = app.editor != null ? app.editor.runEndPiece() : 0;
        int base = 0;
        tiePieces.clear();
        combPoints.clear();
        Group heart = app.settings.showHeartline() ? new Group() : null;
        Group combs = app.settings.combMode() != AppSettings.COMB_NONE
            ? new Group() : null;
        for (int p = 0; p < app.coaster.pieces.size(); p++) {
            int nSec = app.coaster.pieces.get(p).sections.size();
            if (nSec == 0 || base + nSec > spans.size()) {
                base += nSec;
                continue;
            }
            int i0 = spans.get(base).i0();
            int i1 = Math.min(spans.get(base + nSec - 1).i1(), samples.size() - 1);
            base += nSec;
            if (i1 - i0 < 1) continue;
            List<FVDIntegrator.Sample> sub = samples.subList(i0, i1 + 1);
            // "Visualize Track Selection" OFF (v17.10): every piece
            // renders in its full active coloring — no desaturation
            boolean act = !app.settings.visualizeSelection()
                || (p >= runS && p <= runE);
            parts.add(buildRailMesh(sub, i0, -app.railGauge / 2, act));
            parts.add(buildRailMesh(sub, i0, app.railGauge / 2, act));
            // ties build through the highlight refresh below (the
            // active section's ties render white, v16.11)
            TiePiece tp = new TiePiece(sub, i0, act, new Group());
            tiePieces.add(tp);
            parts.add(tp.group());
            // heartline strand PER PIECE (v16.13): detached pieces
            // never read as one strand however close they park
            if (heart != null) heart.getChildren().add(buildHeartlineMesh(sub));
            // track COMBS (v16.13; three-way mode since v16.14 — the
            // G-force comb is vertical + lateral as one unit)
            if (combs != null) {
                if (app.settings.combMode() == AppSettings.COMB_GFORCE) {
                    combs.getChildren().add(buildCombMesh(sub, i0, 0));
                    combs.getChildren().add(buildCombMesh(sub, i0, 1));
                } else {
                    combs.getChildren().add(buildCombMesh(sub, i0, 2));
                }
            }
        }
        refreshTieHighlight(true);
        heartMesh = heart;
        if (heartMesh != null) {
            heartMesh.setVisible(!povActive);   // the camera rides ON it
            parts.add(heartMesh);
        }
        combGroup = combs;
        if (combGroup != null) {
            combGroup.setVisible(!povActive);   // teeth block the view
            parts.add(combGroup);
        }
        parts.add(markerGroup);
        trackGroup.getChildren().setAll(parts);
        markerSig = null;                          // geometry moved: force
        if (app.editor != null) app.editor.refreshMarkers();

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
        for (int i = 0; i < samples.size(); i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            maxGv = Math.max(maxGv, sm.Gv()); minGv = Math.min(minGv, sm.Gv());
            maxY = Math.max(maxY, sm.pos().y()); minY = Math.min(minY, sm.pos().y());
            // top speed is the TRUE speed (round A, §5.1) — identical
            // to Sample.v until a design speed drifts
            maxV = Math.max(maxV, app.trueV[i]);
        }
        FVDIntegrator.Sample last = samples.get(samples.size() - 1);
        // COASTER STATISTICS moved to the Coaster menu (v17.11, user
        // spec) — stash them for its rows; the bottom line is the live
        // POV POSITION now (true values, updates outside POV too)
        app.statRideTime = last.t();
        app.statTrackLen = last.s();
        app.statMinY = minY;
        app.statMaxY = maxY;
        app.statTopV = maxV;
        app.statMinGv = minGv;
        app.statMaxGv = maxGv;
        updatePovStats();
    }

    /** The bottom stats line = the CURRENT POV position (v17.11, user
     *  spec — replaces the coaster stats + shortcut hint): X/Y/Z,
     *  speed and vertical/lateral G at povTime, TRUE values, three
     *  decimals. povTime persists across POV exits, so the line stays
     *  live while editing; every rebuild and every ride frame
     *  refreshes it. */
    void updatePovStats() {
        if (app.lastSamples == null || app.lastSamples.isEmpty()
                || app.trueV == null || app.trueFelt == null) return;
        int idx = (int) FvdDesigner.clamp(
            povTime / FvdDesigner.INTEGRATE_DT, 0,
            Math.min(app.lastSamples.size(),
                Math.min(app.trueV.length, app.trueFelt.length)) - 1);
        FVDIntegrator.Sample sm = app.lastSamples.get(idx);
        app.statsLabel.setText(String.format(
            "POV   X: %.3f   Y: %.3f   Z: %.3f   |   Speed: %.3f m/s   |   Vertical G: %.3f   |   Lateral G: %.3f",
            sm.pos().x(), sm.pos().y(), sm.pos().z(),
            app.trueV[idx], app.trueFelt[idx][0], app.trueFelt[idx][1]));
    }

    /** A single rail tube: heartline offset down by heartOffset along U,
     *  sideways by `lateral` along R, radius 70 mm, colored by vertical G
     *  — the TRUE felt G since v17.12 (D153, user spec: the gradient
     *  reads like the G-force comb; base = the sublist's global sample
     *  offset into the true arrays). */
    MeshView buildRailMesh(List<FVDIntegrator.Sample> samples, int base,
                           double lateral, boolean active) {
        TriangleMesh mesh = new TriangleMesh();
        int nRings = 0;
        List<Vec3> ringPos = new ArrayList<>();
        for (int i = 0; i < samples.size(); i += SAMPLE_STRIDE) {
            FVDIntegrator.Sample sm = samples.get(i);
            Vec3 center = sm.pos()
                .sub(sm.U().scale(app.heartOffset))
                .add(sm.R().scale(lateral));
            for (int j = 0; j < RAIL_SIDES; j++) {
                double theta = 2 * Math.PI * j / RAIL_SIDES;
                Vec3 offset = sm.R().scale(Math.cos(theta) * RAIL_RADIUS)
                        .add(sm.U().scale(Math.sin(theta) * RAIL_RADIUS));
                Vec3 p = center.add(offset);
                mesh.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            }
            double gv = app.trueFelt != null
                    && base + i < app.trueFelt.length
                ? app.trueFelt[base + i][0] : sm.Gv();
            float u = (float) FvdDesigner.clamp((gv - FvdDesigner.G_MIN) / (FvdDesigner.G_MAX - FvdDesigner.G_MIN), 0.001, 0.999);
            mesh.getTexCoords().addAll(u, 0.5f);
            ringPos.add(sm.pos());
            nRings++;
        }
        for (int ring = 0; ring < nRings - 1; ring++) {
            // consecutive samples further apart than any legit step are
            // a PIECE GAP — no tube across it (the stretched-connector
            // bug between detached pieces, v13.1)
            if (ringPos.get(ring + 1).sub(ringPos.get(ring)).length()
                    > MESH_GAP_BREAK) continue;
            int r0 = ring * RAIL_SIDES, r1 = (ring + 1) * RAIL_SIDES;
            for (int j = 0; j < RAIL_SIDES; j++) {
                int jn = (j + 1) % RAIL_SIDES;
                int a = r0 + j, b = r0 + jn, c = r1 + j, d = r1 + jn;
                mesh.getFaces().addAll(a, ring, c, ring + 1, b, ring);
                mesh.getFaces().addAll(b, ring, c, ring + 1, d, ring + 1);
            }
        }
        PhongMaterial mat = new PhongMaterial();
        if (app.railSolid) {
            mat.setDiffuseColor(active ? app.railColor
                : app.railColor.deriveColor(0, 0.30, 0.80, 1));
        } else {
            mat.setDiffuseMap(buildGForceGradient(!active));
        }
        mat.setSpecularColor(active ? Color.rgb(200, 200, 200)
                                    : Color.rgb(90, 90, 95));
        MeshView view = new MeshView(mesh);
        view.setMaterial(mat);
        view.setCullFace(CullFace.NONE);
        return view;
    }

    /** The integrated heartline — the curve the physics actually lives on —
     *  as a hairline neon-yellow strand (Options toggle). JavaFX 3D has no
     *  polylines, so it's a minimal 4-sided tube; the self-illumination map
     *  makes it glow unlit at any distance. */
    MeshView buildHeartlineMesh(List<FVDIntegrator.Sample> samples) {
        final double R = 0.015;
        final int SIDES = 4;
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        int nRings = 0;
        List<Vec3> ringPos = new ArrayList<>();
        for (int i = 0; i < samples.size(); i += SAMPLE_STRIDE) {
            FVDIntegrator.Sample sm = samples.get(i);
            for (int j = 0; j < SIDES; j++) {
                double th = 2 * Math.PI * j / SIDES;
                Vec3 off = sm.R().scale(Math.cos(th) * R)
                        .add(sm.U().scale(Math.sin(th) * R));
                Vec3 p = sm.pos().add(off);
                mesh.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            }
            ringPos.add(sm.pos());
            nRings++;
        }
        for (int ring = 0; ring < nRings - 1; ring++) {
            if (ringPos.get(ring + 1).sub(ringPos.get(ring)).length()
                    > MESH_GAP_BREAK) continue;   // piece gap: no strand
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

    /** Per-piece tie context (v16.11): the sublist + its global sample
     *  base, kept so the ACTIVE-SECTION highlight can re-tint ties on
     *  activation changes without recompiling the track. */
    private record TiePiece(List<FVDIntegrator.Sample> samples, int base,
                            boolean active, Group group) { }
    private final List<TiePiece> tiePieces = new ArrayList<>();
    private String tieSig;

    /** Frame interpolated at an EXACT arc position between bracketing
     *  samples (v16.14): ties and combs used to quantize to the next
     *  sample AT/PAST each grid point — up to ~v·dt ≈ 0.26 m late per
     *  item, so adjacent gaps differed by up to half a meter (the
     *  user's "spacings significantly more than the others", there
     *  since ties existed — a quantization artifact, not a section/
     *  piece bug). loHint = a moving lower-bracket cursor (the grid
     *  only walks forward). */
    private record ArcHit(int lo, double frac, Vec3 pos,
                          Vec3 T, Vec3 U, Vec3 R) { }

    private static ArcHit arcHit(List<FVDIntegrator.Sample> samples,
                                 int loHint, double s) {
        int j = Math.max(0, loHint);
        while (j + 1 < samples.size() - 1 && samples.get(j + 1).s() < s) j++;
        FVDIntegrator.Sample a = samples.get(j);
        FVDIntegrator.Sample b = samples.get(Math.min(j + 1, samples.size() - 1));
        double span = b.s() - a.s();
        double u = span < 1e-12 ? 0
            : FvdDesigner.clamp((s - a.s()) / span, 0, 1);
        Vec3 pos = vLerp(a.pos(), b.pos(), u);
        Vec3 T = vLerp(a.T(), b.T(), u).normalize();
        Vec3 U = vLerp(a.U(), b.U(), u).normalize();
        return new ArcHit(j, u, pos, T, U, U.cross(T));
    }

    private static Vec3 vLerp(Vec3 a, Vec3 b, double u) {
        return a.add(b.sub(a).scale(u));
    }

    /** Rebuild each piece's tie group when the highlighted section
     *  changed (signature-guarded — called on every graph redraw via
     *  refreshTrackMarkers, and once per rebuildTrack). The ACTIVE
     *  section's ties render WHITE (v16.11, user spec). */
    void refreshTieHighlight(boolean force) {
        // "Visualize Track Selection" OFF (v17.10): no white ties —
        // every tie renders the plain dark color
        int[] hi = app.settings.visualizeSelection() && app.editor != null
            ? app.editor.activeSpanRange() : null;
        String sig = hi == null ? "-" : hi[0] + ":" + hi[1];
        if (!force && sig.equals(tieSig)) return;
        tieSig = sig;
        for (TiePiece tp : tiePieces) {
            tp.group().getChildren().setAll(
                buildTieMeshes(tp.samples(), tp.active(), tp.base(), hi));
        }
    }

    /** Cross ties: one thin box between the rails every `tieSpacing`
     *  metres of track — at EXACT arc positions since v16.14 (frames
     *  lerped between samples via arcHit; the old quantize-to-sample
     *  placement wobbled visibly). Ties whose nearest global sample
     *  falls in the ACTIVE section's span build into a second, WHITE
     *  mesh (v16.11 — the selected-section cue). */
    private List<MeshView> buildTieMeshes(List<FVDIntegrator.Sample> samples,
                                          boolean active, int base, int[] hi) {
        final double TIE_HALF_W = 0.045;  // along T (track direction)
        final double TIE_HALF_H = 0.03;   // along U
        TriangleMesh mesh = new TriangleMesh();     // normal ties
        TriangleMesh meshHi = new TriangleMesh();   // active-section ties
        mesh.getTexCoords().addAll(0, 0);
        meshHi.getTexCoords().addAll(0, 0);
        int v = 0, vHi = 0;
        double spacing = Math.max(0.1, app.tieSpacing);
        if (samples.size() < 2) return new ArrayList<>();
        // sample s is GLOBAL arc length — per-piece meshes (v13.2) start
        // mid-ride, so the tie grid anchors at the sublist's own start
        double s0 = samples.get(0).s();
        double sEnd = samples.get(samples.size() - 1).s();
        // ties end exactly at the rail centerlines: the tie cross-section
        // (0.09 x 0.06 m) hides inside the 0.07 m-radius tubes, nothing pokes out
        double halfLen = app.railGauge / 2;
        int lo = 0;
        for (double S = s0; S <= sEnd + 1e-9; S += spacing) {
            ArcHit h = arcHit(samples, lo, S);
            lo = h.lo();
            int near = base + h.lo() + (h.frac() < 0.5 ? 0 : 1);
            boolean white = hi != null && near >= hi[0] && near <= hi[1];
            TriangleMesh m = white ? meshHi : mesh;
            int vb = white ? vHi : v;
            Vec3 spine = h.pos().sub(h.U().scale(app.heartOffset));
            Vec3 a = spine.sub(h.R().scale(halfLen));
            Vec3 b = spine.add(h.R().scale(halfLen));
            Vec3 tw = h.T().scale(TIE_HALF_W);
            Vec3 uh = h.U().scale(TIE_HALF_H);
            // 8 corners: (a|b) ± tw ± uh, Y-flipped like all mesh vertices
            Vec3[] c = {
                a.sub(tw).sub(uh), a.add(tw).sub(uh), a.add(tw).add(uh), a.sub(tw).add(uh),
                b.sub(tw).sub(uh), b.add(tw).sub(uh), b.add(tw).add(uh), b.sub(tw).add(uh),
            };
            for (Vec3 p : c) m.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            int[][] quads = {
                {0,1,2,3}, {4,5,6,7},           // end caps
                {0,1,5,4}, {2,3,7,6},           // bottom, top
                {1,2,6,5}, {0,3,7,4},           // front, back
            };
            for (int[] q : quads) {
                m.getFaces().addAll(vb + q[0], 0, vb + q[1], 0, vb + q[2], 0);
                m.getFaces().addAll(vb + q[0], 0, vb + q[2], 0, vb + q[3], 0);
            }
            if (white) vHi = vb + 8; else v = vb + 8;
        }
        List<MeshView> out = new ArrayList<>(2);
        if (v > 0) {
            MeshView mv = new MeshView(mesh);
            PhongMaterial mat = new PhongMaterial(active
                ? Color.rgb(70, 74, 84) : Color.rgb(52, 55, 62));
            mat.setSpecularColor(active
                ? Color.rgb(90, 90, 90) : Color.rgb(60, 60, 64));
            mv.setMaterial(mat);
            mv.setCullFace(CullFace.NONE);
            out.add(mv);
        }
        if (vHi > 0) {
            MeshView mv = new MeshView(meshHi);
            // PURE white regardless of scene lighting (v16.12, "even
            // whiter"): a diffuse-only white tie still rendered grey
            // under the dim ambient — self-illumination lifts it
            PhongMaterial mat = new PhongMaterial(Color.WHITE);
            WritableImage glow = new WritableImage(1, 1);
            glow.getPixelWriter().setColor(0, 0, Color.rgb(210, 210, 214));
            mat.setSelfIlluminationMap(glow);
            mat.setSpecularColor(Color.rgb(200, 200, 205));
            mv.setMaterial(mat);
            mv.setCullFace(CullFace.NONE);
            out.add(mv);
        }
        return out;
    }

    // ==================== TRACK COMBS (v16.13 D125; reworked v16.14 D126) ====================
    // Perpendicular teeth along the track, FVD++-style: the G-FORCE
    // comb = VERTICAL felt G (outward along −U, colored by the
    // g-force gradient) + LATERAL felt G (rider-LEFT for +Gl since
    // v16.14, its own mirrored-absolute gradient) as one unit, or the
    // SPEED comb (upward along U, length only) — a three-way mode,
    // never both. Teeth originate at the HEARTLINE, sit at EXACT arc
    // positions (arcHit), scale by the View menu's percent setting,
    // hide in POV, and are mouse-transparent (they block nothing —
    // hover works in screen space). Values from the TRUE arrays.
    // Hovering anywhere on a tooth shows its value bottom-left.

    static final double COMB_SPACING = 0.5;    // m of track per tooth
    static final double COMB_VG_SCALE = 0.5;   // m per g (vertical)
    static final double COMB_LG_SCALE = 0.8;   // m per g (lateral)
    static final double COMB_V_SCALE = 0.05;   // m per m/s (speed)
    static final double COMB_MAX = 3.5;        // length cap (m, at 100%)

    private record CombPt(Vec3 base, Vec3 tip, double value, int kind) { }
    private final List<CombPt> combPoints = new ArrayList<>();
    Group combGroup;   // all comb meshes; hidden while riding POV
    final Label combLabel = new Label();   // bottom-left readout (v16.14)
    /** Bottom-left "riding backward" indicator (v16.16, user spec —
     *  same styling as the comb readout; A is the fixed key, not a
     *  rebindable action). Never up together with combLabel: combs
     *  hide in POV and the comb hover bails there. */
    final Label povLabel = new Label("Backwards POV (A)");
    {
        String overlay = "-fx-background-color: rgba(30,34,44,0.85);"
            + " -fx-text-fill: rgb(225,230,240); -fx-padding: 2 6 2 6;"
            + " -fx-font-size: 11px; -fx-background-radius: 4;";
        combLabel.setStyle(overlay);
        combLabel.setVisible(false);
        combLabel.setMouseTransparent(true);
        povLabel.setStyle(overlay);
        povLabel.setVisible(false);
        povLabel.setMouseTransparent(true);
    }

    /** The backward-POV indicator shows exactly while POV rides
     *  reversed (v16.16). */
    void syncPovLabel() {
        povLabel.setVisible(povActive && app.settings.povReversed());
    }

    /** One comb's teeth over a piece's samples (base = the sublist's
     *  global start index — the TRUE arrays are ride-global). */
    private MeshView buildCombMesh(List<FVDIntegrator.Sample> samples,
                                   int base, int kind) {
        final double HW = 0.03;    // tooth half-thickness (⊥ tooth)
        final double HW_T = 0.06;  // half-width ALONG the track —
                                   // doubled in v16.15 (user spec)
        TriangleMesh mesh = new TriangleMesh();
        int v = 0, tc = 0;
        double scale = FvdDesigner.clamp(
            app.settings.combScale(), 10, 500) / 100.0;
        if (samples.size() < 2) return new MeshView(mesh);
        double s0 = samples.get(0).s();
        double sEnd = samples.get(samples.size() - 1).s();
        int lo = 0;
        for (double S = s0; S <= sEnd + 1e-9; S += COMB_SPACING) {
            ArcHit h = arcHit(samples, lo, S);
            lo = h.lo();
            int gi = Math.min(base + h.lo(), app.trueV.length - 1);
            int gj = Math.min(gi + 1, app.trueV.length - 1);
            double val;
            Vec3 dir;
            double len;
            if (kind == 0) {
                val = app.trueFelt[gi][0]
                    + (app.trueFelt[gj][0] - app.trueFelt[gi][0]) * h.frac();
                dir = h.U().scale(-1);   // outward: loops comb outside
                len = val * COMB_VG_SCALE;
            } else if (kind == 1) {
                val = app.trueFelt[gi][1]
                    + (app.trueFelt[gj][1] - app.trueFelt[gi][1]) * h.frac();
                dir = h.R().scale(-1);   // rider-LEFT for +Gl (v16.14)
                len = val * COMB_LG_SCALE;
            } else {
                val = app.trueV[gi]
                    + (app.trueV[gj] - app.trueV[gi]) * h.frac();
                dir = h.U();
                len = val * COMB_V_SCALE;
            }
            len = FvdDesigner.clamp(len * scale,
                -COMB_MAX * scale, COMB_MAX * scale);
            if (Math.abs(len) < 0.02) continue;
            // teeth grow from the HEARTLINE (v16.14 — was the spine)
            Vec3 root = h.pos();
            Vec3 tip = root.add(dir.scale(len));
            combPoints.add(new CombPt(root, tip, val, kind));
            // gradient u for this tooth (kind 2 is solid: u unused)
            float u = 0.5f;
            if (kind == 0) {
                u = (float) FvdDesigner.clamp((val - FvdDesigner.G_MIN)
                    / (FvdDesigner.G_MAX - FvdDesigner.G_MIN), 0.001, 0.999);
            } else if (kind == 1) {
                // ABSOLUTE lateral colors (v16.14): mirrored about 0
                double hi = app.latStops[app.latStops.length - 1];
                u = (float) FvdDesigner.clamp(Math.abs(val)
                    / Math.max(1e-6, hi), 0.001, 0.999);
            }
            mesh.getTexCoords().addAll(u, 0.5f);
            // thin box from root to tip: cross-section T × (dir×T)
            Vec3 a = root, b = tip;
            Vec3 tw = h.T().scale(HW_T);
            Vec3 n = dir.cross(h.T());
            double nl = n.length();
            Vec3 uh = nl > 1e-9 ? n.scale(HW / nl) : h.U().scale(HW);
            Vec3[] c = {
                a.sub(tw).sub(uh), a.add(tw).sub(uh), a.add(tw).add(uh), a.sub(tw).add(uh),
                b.sub(tw).sub(uh), b.add(tw).sub(uh), b.add(tw).add(uh), b.sub(tw).add(uh),
            };
            for (Vec3 p : c) mesh.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
            int[][] quads = {
                {0,1,2,3}, {4,5,6,7},
                {0,1,5,4}, {2,3,7,6},
                {1,2,6,5}, {0,3,7,4},
            };
            for (int[] q : quads) {
                mesh.getFaces().addAll(v + q[0], tc, v + q[1], tc, v + q[2], tc);
                mesh.getFaces().addAll(v + q[0], tc, v + q[2], tc, v + q[3], tc);
            }
            v += 8;
            tc++;
        }
        MeshView mv = new MeshView(mesh);
        PhongMaterial mat = new PhongMaterial();
        if (kind == 0) {
            mat.setDiffuseMap(buildGForceGradient(false));
        } else if (kind == 1) {
            mat.setDiffuseMap(buildLatGradient());
        } else {
            mat.setDiffuseColor(Color.rgb(150, 210, 255));
        }
        mat.setSpecularColor(Color.rgb(90, 90, 95));
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        // teeth block NOTHING (v16.14): clicks/double-clicks pass
        // through to the track; the hover readout is screen-space
        mv.setMouseTransparent(true);
        return mv;
    }

    /** The LATERAL comb's gradient strip (v16.14, user spec: ABSOLUTE
     *  value — colors mirror about zero): the three per-coaster
     *  latColors/latStops over |Gl| ∈ [0, last stop]; below the first
     *  stop stays flat at its color. */
    WritableImage buildLatGradient() {
        int w = 256;
        WritableImage img = new WritableImage(w, 1);
        PixelWriter pw = img.getPixelWriter();
        double hi = app.latStops[app.latStops.length - 1];
        for (int i = 0; i < w; i++) {
            double g = hi * i / (w - 1.0);
            pw.setColor(i, 0, stopColor(g, app.latColors, app.latStops));
        }
        return img;
    }

    /** Readout while hovering ANYWHERE on a comb tooth (v16.14 — the
     *  v16.13 version measured to the TIP point only, a near-
     *  impossible target, and its cursor-following label was
     *  UNMANAGED in the StackPane, so it never got laid out to a
     *  size — 0×0 = invisible, the user's "hover isn't working"):
     *  distance to the tooth's projected root→tip SEGMENT within
     *  10 px; the value shows in a fixed label at the viewport's
     *  BOTTOM-LEFT (user spec). Wired on MOUSE_MOVED. */
    private void combHover(MouseEvent e) {
        if (combPoints.isEmpty() || flyActive || povActive) {
            combLabel.setVisible(false);
            return;
        }
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        double best = 10 * 10;
        CombPt hit = null;
        for (CombPt cp : combPoints) {
            Vec3 p = cp.tip();
            Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
            if (sceneP.subtract(eye).dotProduct(fwd) <= 0) continue;
            Point2D sTip = world.localToScreen(p.x(), -p.y(), p.z());
            Point2D sRoot = world.localToScreen(cp.base().x(),
                -cp.base().y(), cp.base().z());
            if (sTip == null || sRoot == null) continue;
            // point → segment distance in screen space
            double sx = sTip.getX() - sRoot.getX();
            double sy = sTip.getY() - sRoot.getY();
            double l2 = sx * sx + sy * sy;
            double px = e.getScreenX() - sRoot.getX();
            double py = e.getScreenY() - sRoot.getY();
            double t = l2 < 1e-9 ? 0
                : FvdDesigner.clamp((px * sx + py * sy) / l2, 0, 1);
            double dx = px - t * sx;
            double dy = py - t * sy;
            double d2 = dx * dx + dy * dy;
            if (d2 < best) {
                best = d2;
                hit = cp;
            }
        }
        if (hit == null) {
            combLabel.setVisible(false);
            return;
        }
        combLabel.setText(switch (hit.kind()) {
            case 0 -> String.format("%.2f g vert", hit.value());
            case 1 -> String.format("%.2f g lat", hit.value());
            default -> String.format("%.1f m/s  (%.0f km/h)",
                hit.value(), hit.value() * 3.6);
        });
        combLabel.setVisible(true);
    }

    /** Recenter the floor grid under the camera, quantized to 10 m steps so
     *  the 1 m / 10 m line pattern stays aligned with the world grid. */
    void updateGridLOD(double worldX, double worldZ) {
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
    Group buildGrid(double y) {
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

    WritableImage buildGForceGradient(boolean muted) {
        int w = 256;
        WritableImage img = new WritableImage(w, 1);
        PixelWriter pw = img.getPixelWriter();
        for (int i = 0; i < w; i++) {
            double g = FvdDesigner.G_MIN + (FvdDesigner.G_MAX - FvdDesigner.G_MIN) * i / (w - 1.0);
            Color c = stopColor(g, app.gradColors, app.gradStops);
            // inactive pieces wear the same gradient DESATURATED and a
            // touch darker (v13.2) — the active piece pops
            if (muted) c = c.deriveColor(0, 0.30, 0.80, 1);
            pw.setColor(i, 0, c);
        }
        return img;
    }

    /** Color at g on a stop list of ANY length (v16.14 — the vertical
     *  gradient grew a fifth, purple stop): flat outside the ends,
     *  lerped between. */
    private static Color stopColor(double g, Color[] cols, double[] stops) {
        int last = stops.length - 1;
        if (g <= stops[0]) return cols[0];
        if (g >= stops[last]) return cols[last];
        int k = 0;
        while (k < last - 1 && g > stops[k + 1]) k++;
        double span = stops[k + 1] - stops[k];
        return span < 1e-9 ? cols[k + 1]
            : lerpColor(cols[k], cols[k + 1], (g - stops[k]) / span);
    }

    static Color lerpColor(Color a, Color b, double t) {
        return Color.color(
            a.getRed() + (b.getRed() - a.getRed()) * t,
            a.getGreen() + (b.getGreen() - a.getGreen()) * t,
            a.getBlue() + (b.getBlue() - a.getBlue()) * t
        );
    }

    // ==================== 3D GIZMOS (v16.11 D123, reworked v16.12 D124) ====================
    // Placement gizmos, HIDDEN until revealed: clicking the ACTIVE
    // piece's ENTRY MARKER (the bigger sphere at its first node)
    // toggles them, and the reveal shows EVERY placement surface the
    // piece owns at once — the entry gizmo AND the trailing bridge's
    // end gizmo — whatever section is active (v16.12, user spec). A
    // locked or machine-owned axis builds NO part. The overlay Axes
    // toggle flips the TRANSLATION arrows between world X/Y/Z and the
    // entry frame; rotation rings always turn the model's own Euler
    // quantities (yaw about world Y, pitch about the heading's
    // horizontal right, roll about the heading — the exact numbers
    // the Placement spinners edit), so every ring is representable
    // under any lock combination.
    // DRAGS ARE ABSOLUTE FROM THE PRESS (v16.12): the screen mapping
    // and the base value are captured once at the press, so a part
    // that MOVES while dragged (an optimize-position bridge re-places
    // the target per event) cannot feed back into its own input — the
    // v16.11 incremental rings spiraled exactly that way. Ctrl snaps
    // translation + length by the element-length step, rings to 1°.

    final Group gizmoGroup = new Group();
    private String gizmoSig;
    private boolean gizmoDragging, gizmoPushed;
    private int gizmoPiece = -1;         // rebuild on piece switch

    /** Gizmos show whenever the View-menu check says so (v17.7, D144
     *  — the per-piece reveal-click gating is gone). */
    private boolean gizmosOn() {
        return app.settings.showGizmos();
    }
    private double gDragX, gDragY;       // screen point of the press

    /** One gizmo assembly (piece entry / bridge end): its own anchor,
     *  origin-pivot scale (the scale PROPERTIES pivot at the
     *  layout-bounds centre — wrong for the asymmetric arrow set) and
     *  live re-aim hooks; both rigs show at once when the piece has a
     *  trailing bridge (v16.12). */
    private final class Rig {
        final Group group = new Group();
        final javafx.scene.transform.Scale scaleT =
            new javafx.scene.transform.Scale(1, 1, 1);
        final java.util.function.Supplier<Vec3> anchor;
        final List<Runnable> aims = new ArrayList<>();
        double scale = 5;   // world m per gizmo unit (live)
        Rig(java.util.function.Supplier<Vec3> a) {
            anchor = a;
            group.getTransforms().add(scaleT);
        }
    }
    private final List<Rig> gizmoRigs = new ArrayList<>();
    private record RevealMark(Sphere ball,
        java.util.function.Supplier<Vec3> at) { }
    private final List<RevealMark> revealMarks = new ArrayList<>();

    // arrows X green / Y blue / Z red (v16.13, user spec — the red and
    // green of v16.12 swapped); the rings wear the GRAPH's lane
    // colors — yaw yellow, pitch teal, roll the roll-lane orange-red —
    // so they line up with the curves; the bridge LENGTH arrow stays
    // orange
    private static final Color GIZ_X = Color.rgb(95, 205, 95);
    private static final Color GIZ_Y = Color.rgb(90, 140, 235);
    private static final Color GIZ_Z = Color.rgb(225, 85, 85);
    private static final Color GIZ_LEN = Color.rgb(235, 165, 60);
    private static final Color RING_PITCH = FvdDesigner.GEO_LANE_COLORS[0];
    private static final Color RING_YAW = FvdDesigner.GEO_LANE_COLORS[1];
    private static final Color RING_ROLL = FvdDesigner.GEO_LANE_COLORS[2];

    /** Rebuild-or-follow, signature-guarded like the markers; a
     *  mid-drag call only repositions/re-aims (rebuilding the pressed
     *  part would kill the mouse capture — positionGizmo's aim hooks
     *  keep the parts tracking the pose in real time, v16.12). */
    void refreshGizmo(boolean force) {
        int ap = app.editor != null ? app.editor.activePiece() : -1;
        if (ap != gizmoPiece) {
            gizmoPiece = ap;
        }
        if (gizmoDragging) {
            positionGizmo();
            return;
        }
        String sig = gizmoSigNow();
        if (!force && sig.equals(gizmoSig)) {
            positionGizmo();
            return;
        }
        gizmoSig = sig;
        rebuildGizmo();
    }

    private String gizmoSigNow() {
        // POV hides everything; FLY keeps the gizmos visible (v16.12)
        if (povActive || app.editor == null
                || app.lastSamples == null || app.lastSamples.isEmpty()) {
            return "off";
        }
        int pi = app.editor.activePiece();
        if (pi < 0 || pi >= app.coaster.pieces.size()) return "off";
        TrackPiece p = app.coaster.pieces.get(pi);
        BridgeSection into = app.bridgeInto(pi);
        BridgeSection trail = app.bridgeInto(pi + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(app.settings.gizmoLocal() ? 'L' : 'G')
          .append(gizmosOn() ? 'R' : 'h')
          .append('|').append(pi)
          .append('|').append(into != null ? into.optMode : -1)
          .append('|').append(into != null && into.lockYaw)
          .append(into != null ? into.lockPitch : p.uiLockPitch)
          .append(into != null ? into.lockRoll : p.uiLockRoll)
          .append('|').append(app.editor.followsPrev(pi));   // v17.1
        if (app.editor.followsPrev(pi)) {
            // the SEAM gizmo's rings follow the lock toggles + held
            // angles live (v17.3, D140)
            sb.append('|').append(p.sLockYaw).append(p.sLockPitch)
              .append(p.sLockRoll).append('|').append(p.sYawDeg)
              .append(',').append(p.sPitchDeg)
              .append(',').append(p.sRollDeg);
        }
        appendPose(sb, p);
        if (trail != null) {
            sb.append("|T|").append(trail.optMode)
              .append('|').append(trail.optLen)
              .append('|').append(trail.lockYaw).append(trail.lockPitch)
              .append(trail.lockRoll);
            appendPose(sb, app.coaster.pieces.get(pi + 1));
        }
        return sb.toString();
    }

    private static void appendPose(StringBuilder sb, TrackPiece p) {
        sb.append('|').append(p.x).append(',').append(p.y)
          .append(',').append(p.z).append(',').append(p.yawDeg)
          .append(',').append(p.pitchDeg).append(',').append(p.rollDeg)
          .append(',').append(p.baseOrient.w()).append(',')
          .append(p.baseOrient.x()).append(',').append(p.baseOrient.y())
          .append(',').append(p.baseOrient.z());
    }

    private void rebuildGizmo() {
        gizmoGroup.getChildren().clear();
        gizmoRigs.clear();
        revealMarks.clear();
        if ("off".equals(gizmoSig)) {
            app.syncGizmoAxesBtn(false);
            return;
        }
        int pi = app.editor.activePiece();
        TrackPiece p = app.coaster.pieces.get(pi);
        BridgeSection trail = app.bridgeInto(pi + 1);
        TrackPiece next = trail != null
            && pi + 1 < app.coaster.pieces.size()
            ? app.coaster.pieces.get(pi + 1) : null;
        // reveal markers first: the piece's entry ("first node",
        // drawn bigger) and — with a trailing bridge — the bridge end.
        // A STRICT-SEAM follower's mark sits ON THE SEAM instead
        // (v17.3, D140 — the D136 ghost was the STALE stored x/y/z;
        // the true junction is a real surface now): single click
        // reveals the seam gizmo, DOUBLE click opens the seam
        // settings pop-out.
        if (!app.editor.followsPrev(pi)) {
            addRevealMark(() -> new Vec3(p.x, p.y, p.z), true, null);
        } else if (app.editor.seamWorldPos(pi) != null) {
            final int fpi = pi;
            addRevealMark(() -> {
                Vec3 v = app.editor.seamWorldPos(fpi);
                return v != null ? v : new Vec3(p.x, p.y, p.z);
            }, true, () -> app.editor.showStrictSeamSettings(fpi));
        }
        if (next != null) {
            TrackPiece n = next;
            addRevealMark(() -> new Vec3(n.x, n.y, n.z), false, null);
        }
        if (gizmosOn()) {
            boolean local = app.settings.gizmoLocal();
            buildPieceGizmo(local, pi, p);
            if (trail != null && next != null) {
                buildBridgeGizmo(local, trail, next);
            }
        }
        app.syncGizmoAxesBtn(gizmosOn() && !gizmoRigs.isEmpty());
        positionGizmo();
    }

    private Rig newRig(java.util.function.Supplier<Vec3> anchor) {
        Rig rig = new Rig(anchor);
        gizmoRigs.add(rig);
        gizmoGroup.getChildren().add(rig.group);
        return rig;
    }

    /** The reveal button (v16.12, user spec: "gizmo only reveals when
     *  you click the first node of the track piece"): a slightly
     *  bigger sphere at the piece entry — and at a trailing bridge's
     *  end — toggling the piece's gizmos. Clicking the ENTRY one open
     *  with the piece's first section active also selects lane node 0
     *  (it sits on the same spot as the node marker it covers). */
    private void addRevealMark(java.util.function.Supplier<Vec3> at,
                               boolean pieceEntry, Runnable onDouble) {
        Sphere ball = new Sphere(0.34, 14);
        Color idle = gizmosOn() ? Color.rgb(255, 248, 200)
                                : Color.rgb(205, 215, 235);
        PhongMaterial mat = new PhongMaterial(idle);
        ball.setMaterial(mat);
        ball.setOnMouseEntered(e -> mat.setDiffuseColor(Color.WHITE));
        ball.setOnMouseExited(e -> mat.setDiffuseColor(idle));
        ball.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) e.consume();
        });
        ball.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            e.consume();
            // DOUBLE-CLICK (v17.3, D140): the seam ball opens the
            // strict seam settings. Single clicks no longer toggle a
            // reveal (gizmos are always on, v17.7 D144) — the entry
            // ball still selects the covered node 0.
            if (e.getClickCount() == 2) {
                if (onDouble != null) onDouble.run();
                return;
            }
            if (e.getClickCount() != 1) return;
            if (pieceEntry && app.editor != null
                    && app.editor.activeSection() == 0) {
                app.editor.selectNodeFromViewport(0);
            }
        });
        revealMarks.add(new RevealMark(ball, at));
        gizmoGroup.getChildren().add(ball);
    }

    /** The ACTIVE piece's entry gizmo: translation arrows + the yaw
     *  ring; pitch/roll rings only where UNLOCKED (bridged-in pieces
     *  read the bridge's shared padlocks). A piece PLACED by an
     *  optimize-mode bridge builds none of it (machine-owned — the
     *  previous piece's bridge gizmo is the surface). */
    private void buildPieceGizmo(boolean local, int pi, TrackPiece p) {
        BridgeSection into = app.bridgeInto(pi);
        if (into != null && into.optMode != BridgeSection.OPT_NONE) return;
        // a STRICT-SEAM follower's entry is seam-owned (v17.1, D135):
        // its gizmo is the SEAM gizmo (v17.3, D140) — rings only for
        // the DISCONTINUOUS axes, anchored on the junction itself
        if (app.editor.followsPrev(pi)) {
            buildSeamGizmo(pi, p);
            return;
        }
        Rig rig = newRig(() -> new Vec3(p.x, p.y, p.z));
        addTranslationArrows(rig, local, () -> p.entryPose().orient(),
            new AxisSink() {
                private double bx, by, bz, bProj;
                private Vec3 d0;
                @Override public void begin(Vec3 dir0) {
                    bx = p.x;
                    by = p.y;
                    bz = p.z;
                    d0 = dir0;
                    bProj = new Vec3(bx, by, bz).dot(dir0);
                }
                @Override public void apply(double lam, boolean snap) {
                    if (snap) {   // absolute grid along the axis
                        double step = Math.max(0.01, app.settings.snapLen());
                        lam = Math.round((bProj + lam) / step) * step - bProj;
                    }
                    pushOnce();
                    p.x = bx + d0.x() * lam;
                    p.y = by + d0.y() * lam;
                    p.z = bz + d0.z() * lam;
                    app.rebuildTrack();
                }
            });
        boolean lockY = into != null && into.lockYaw;
        boolean lockP = into != null ? into.lockPitch : p.uiLockPitch;
        boolean lockR = into != null ? into.lockRoll : p.uiLockRoll;
        if (!lockY) {
            addRing(rig, 1.0, RING_YAW, () -> new Vec3(0, 1, 0),
                () -> p.yawDeg, deg -> {
                    pushOnce();
                    p.yawDeg = wrapDeg(deg);
                    app.rebuildTrack();
                });
        }
        if (!lockP) {
            addRing(rig, 0.85, RING_PITCH, () -> pitchAxis(p),
                () -> p.pitchDeg, deg -> {
                    pushOnce();
                    p.pitchDeg = FvdDesigner.clamp(deg, -85, 85);
                    app.rebuildTrack();
                });
        }
        if (!lockR) {
            addRing(rig, 0.7, RING_ROLL, () -> p.entryPose().orient().T(),
                () -> p.rollDeg, deg -> {
                    pushOnce();
                    p.rollDeg = wrapDeg(deg);
                    app.rebuildTrack();
                });
        }
    }

    /** A STRICT SEAM's orientation gizmo (v17.3, D140 — user spec):
     *  anchored ON the junction, rings ONLY for the DISCONTINUOUS
     *  axes (they hold an editable angle; a following axis has no
     *  number to turn — toggle it Discontinuous in the seam settings
     *  first). Writes go through the editor's seam path; one undo
     *  per gesture, the gizmo contract. */
    private void buildSeamGizmo(int pi, TrackPiece f) {
        Rig rig = newRig(() -> {
            Vec3 v = app.editor.seamWorldPos(pi);
            return v != null ? v : new Vec3(f.x, f.y, f.z);
        });
        if (f.sLockYaw) {
            addRing(rig, 1.0, RING_YAW, () -> new Vec3(0, 1, 0),
                () -> f.sYawDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 0, wrapDeg(deg));
                });
        }
        if (f.sLockPitch) {
            addRing(rig, 0.85, RING_PITCH, () -> seamPitchAxis(pi),
                () -> f.sPitchDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 1,
                        FvdDesigner.clamp(deg, -85, 85));
                });
        }
        if (f.sLockRoll) {
            addRing(rig, 0.7, RING_ROLL,
                () -> app.editor.seamEntryOrient(pi).T(),
                () -> f.sRollDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 2, wrapDeg(deg));
                });
        }
    }

    /** The seam frame's pitch axis: horizontal right of the EFFECTIVE
     *  entry heading (the follow contract's frame). */
    private Vec3 seamPitchAxis(int pi) {
        Vec3 t = app.editor.seamEntryOrient(pi).T();
        Vec3 r = new Vec3(-t.z(), 0, t.x());
        double len = r.length();
        return len > 1e-9 ? r.scale(1 / len) : new Vec3(1, 0, 0);
    }

    /** The trailing bridge's end gizmo = the NEXT piece's entry,
     *  shown while revealed whatever section is active (v16.12).
     *  Manual mode: arrows + unlocked rings; optimize modes: the
     *  march owns position — ONE orange tangent arrow drags the
     *  LENGTH (hidden under Optimize length); optimize-orientation
     *  also owns the unlocked rings, so none show. Writes go through
     *  the editor's trailing-bridge gizmo paths (undo pushed here,
     *  once per gesture). */
    private void buildBridgeGizmo(boolean local, BridgeSection bs,
                                  TrackPiece t) {
        Rig rig = newRig(() -> new Vec3(t.x, t.y, t.z));
        if (bs.optMode == BridgeSection.OPT_NONE) {
            addTranslationArrows(rig, local, () -> t.entryPose().orient(),
                new AxisSink() {
                    private double bx, by, bz, bProj;
                    private Vec3 d0;
                    @Override public void begin(Vec3 dir0) {
                        bx = t.x;
                        by = t.y;
                        bz = t.z;
                        d0 = dir0;
                        bProj = new Vec3(bx, by, bz).dot(dir0);
                    }
                    @Override public void apply(double lam, boolean snap) {
                        if (snap) {
                            double step = Math.max(0.01, app.settings.snapLen());
                            lam = Math.round((bProj + lam) / step) * step - bProj;
                        }
                        pushOnce();
                        t.x = bx + d0.x() * lam;
                        t.y = by + d0.y() * lam;
                        t.z = bz + d0.z() * lam;
                        app.editor.gizmoBridgeMoved();
                    }
                });
        } else if (!bs.optLen) {
            Group arrow = gizmoArrow(rig, () -> t.entryPose().orient().T(),
                GIZ_LEN);
            wireAxisDrag(arrow, rig, () -> t.entryPose().orient().T(),
                new AxisSink() {
                    private double base;
                    @Override public void begin(Vec3 dir0) {
                        base = app.editor.gizmoBridgeGap();
                    }
                    @Override public void apply(double lam, boolean snap) {
                        double gap = base + lam;
                        if (snap) {
                            double step = Math.max(0.01, app.settings.snapLen());
                            gap = Math.round(gap / step) * step;
                        }
                        pushOnce();
                        app.editor.gizmoSetBridgeGap(Math.max(1, gap));
                    }
                });
            rig.group.getChildren().add(arrow);
        }
        if (bs.optMode != BridgeSection.OPT_POS_YAW) {
            if (!bs.lockYaw) {
                addRing(rig, 1.0, RING_YAW, () -> new Vec3(0, 1, 0),
                    () -> t.yawDeg, deg -> {
                        pushOnce();
                        app.editor.gizmoSetBridgeOrient(0, deg);
                    });
            }
            if (!bs.lockPitch) {
                addRing(rig, 0.85, RING_PITCH, () -> pitchAxis(t),
                    () -> t.pitchDeg, deg -> {
                        pushOnce();
                        app.editor.gizmoSetBridgeOrient(1, deg);
                    });
            }
            if (!bs.lockRoll) {
                addRing(rig, 0.7, RING_ROLL,
                    () -> t.entryPose().orient().T(),
                    () -> t.rollDeg, deg -> {
                        pushOnce();
                        app.editor.gizmoSetBridgeOrient(2, deg);
                    });
            }
        }
    }

    /** The model's pitch axis at a piece's entry: horizontal right of
     *  the heading (TrackPiece.entryPose's exact axis). */
    private static Vec3 pitchAxis(TrackPiece p) {
        Vec3 t = p.entryPose().orient().T();
        Vec3 r = new Vec3(-t.z(), 0, t.x());
        double len = r.length();
        return len > 1e-9 ? r.scale(1 / len) : new Vec3(1, 0, 0);
    }

    private static double wrapDeg(double d) {
        return d - 720 * Math.floor((d + 360) / 720);
    }

    /** Absolute-from-press axis drag sink: begin() captures the base
     *  state and the press-time direction, apply() re-derives the
     *  pose from base + λ every event. */
    private interface AxisSink {
        void begin(Vec3 dir0);
        void apply(double lam, boolean snap);
    }

    /** One translation arrow per axis: world X/Y/Z, or the entry
     *  frame's right/up/forward when the Axes toggle says local. */
    private void addTranslationArrows(Rig rig, boolean local,
            java.util.function.Supplier<Quat> frame, AxisSink sink) {
        record Ax(java.util.function.Supplier<Vec3> dir, Color col) { }
        List<Ax> dirs = new ArrayList<>();
        if (local) {
            dirs.add(new Ax(() -> frame.get().U().cross(frame.get().T()),
                GIZ_X));                                       // right
            dirs.add(new Ax(() -> frame.get().U(), GIZ_Y));    // up
            dirs.add(new Ax(() -> frame.get().T(), GIZ_Z));    // forward
        } else {
            dirs.add(new Ax(() -> new Vec3(1, 0, 0), GIZ_X));
            dirs.add(new Ax(() -> new Vec3(0, 1, 0), GIZ_Y));
            dirs.add(new Ax(() -> new Vec3(0, 0, 1), GIZ_Z));
        }
        for (Ax ax : dirs) {
            Group arrow = gizmoArrow(rig, ax.dir(), ax.col());
            wireAxisDrag(arrow, rig, ax.dir(), sink);
            rig.group.getChildren().add(arrow);
        }
    }

    /** A ring (torus ⊥ its rotation axis) writing ABSOLUTE degrees:
     *  base value read at the press, applyAbs called with base +
     *  linear mouse movement (Ctrl = whole degrees). */
    private void addRing(Rig rig, double radius, Color col,
            java.util.function.Supplier<Vec3> axis,
            java.util.function.DoubleSupplier baseVal,
            java.util.function.DoubleConsumer applyAbs) {
        PhongMaterial mat = gizmoMat(col);
        MeshView ring = torus(radius, 0.06, mat);
        Group g = new Group(ring);
        Rotate rot = new Rotate();
        g.getTransforms().add(rot);
        Runnable aimIt = () -> aim(rot, axis.get());
        aimIt.run();
        rig.aims.add(aimIt);
        hoverGlow(g, mat, col);
        wireRingDrag(g, rig, axis, baseVal, applyAbs);
        rig.group.getChildren().add(g);
    }

    /** Shaft + cone tip along +local-Y, aimed along the (live) WORLD
     *  direction — thicker since v16.12 (grabbing needed too much
     *  precision). */
    private Group gizmoArrow(Rig rig,
            java.util.function.Supplier<Vec3> dir, Color col) {
        PhongMaterial mat = gizmoMat(col);
        Cylinder shaft = new Cylinder(0.05, 1.0);
        shaft.setMaterial(mat);
        shaft.setTranslateY(0.5);
        MeshView tip = cone(0.13, 1.0, 1.32, mat);
        Group g = new Group(shaft, tip);
        Rotate rot = new Rotate();
        g.getTransforms().add(rot);
        Runnable aimIt = () -> aim(rot, dir.get());
        aimIt.run();
        rig.aims.add(aimIt);
        hoverGlow(g, mat, col);
        return g;
    }

    /** Point a part's +local-Y along a WORLD direction (scene =
     *  Y-flipped world) — re-run live during drags so the parts
     *  track the pose in real time (v16.12). */
    private static void aim(Rotate rot, Vec3 dirWorld) {
        Point3D dir = new Point3D(dirWorld.x(), -dirWorld.y(),
            dirWorld.z()).normalize();
        Point3D from = new Point3D(0, 1, 0);
        double dot = FvdDesigner.clamp(from.dotProduct(dir), -1, 1);
        if (dot > 0.99999) {
            rot.setAngle(0);
            return;
        }
        rot.setAxis(dot < -0.99999 ? new Point3D(1, 0, 0)
                                   : from.crossProduct(dir));
        rot.setAngle(Math.toDegrees(Math.acos(dot)));
    }

    private static PhongMaterial gizmoMat(Color col) {
        PhongMaterial m = new PhongMaterial(col);
        m.setSpecularColor(Color.rgb(210, 210, 215));
        return m;
    }

    private static void hoverGlow(Group g, PhongMaterial mat, Color col) {
        g.setOnMouseEntered(e -> mat.setDiffuseColor(
            col.interpolate(Color.WHITE, 0.5)));
        g.setOnMouseExited(e -> mat.setDiffuseColor(col));
    }

    /** Axis drag, ABSOLUTE from the press (v16.12): the axis's screen
     *  image is captured once at the press, so per-event λ is a pure
     *  function of how far the mouse moved from the press point —
     *  parts that move while dragged cannot feed back. Ctrl hands the
     *  sink snap=true. Events are consumed so the orbit/click
     *  handlers never see them. */
    private void wireAxisDrag(Group part, Rig rig,
            java.util.function.Supplier<Vec3> axis, AxisSink sink) {
        double[] sv = new double[3];   // press-time screen axis: sx, sy, |s|²
        part.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            sub.requestFocus();
            Vec3 a = rig.anchor.get();
            Vec3 d = axis.get();
            Point2D p0 = projectWorld(a);
            Point2D p1 = projectWorld(a.add(d));
            if (p0 == null || p1 == null) return;
            sv[0] = p1.getX() - p0.getX();
            sv[1] = p1.getY() - p0.getY();
            sv[2] = sv[0] * sv[0] + sv[1] * sv[1];
            if (sv[2] < 4) return;   // edge-on: ungrabbable anyway
            gizmoDragging = true;
            gizmoPushed = false;
            gDragX = e.getScreenX();
            gDragY = e.getScreenY();
            sink.begin(d);
            e.consume();
        });
        part.setOnMouseDragged(e -> {
            if (!gizmoDragging) return;
            double lam = ((e.getScreenX() - gDragX) * sv[0]
                        + (e.getScreenY() - gDragY) * sv[1]) / sv[2];
            lam = FvdDesigner.clamp(lam, -60 * rig.scale, 60 * rig.scale);
            sink.apply(lam, e.isControlDown());
            e.consume();
        });
        part.setOnMouseReleased(e -> {
            endGizmoDrag();
            e.consume();
        });
    }

    /** Ring drag, LINEAR + ABSOLUTE (v16.12, user spec): the press
     *  captures the ring's screen-space tangent at the grab point and
     *  the base value; rotation = the distance the mouse moved along
     *  that FIXED line, one grabbed-radius of travel ≈ 57° — no
     *  circling the gizmo, and a part that moves mid-drag (an
     *  optimize-position bridge re-places the target per event)
     *  cannot spiral its own input, the v16.11 yaw-feedback bug. The
     *  scene is a Y-MIRROR of the world, so the applied delta is
     *  NEGATED (one documented sign — flip here if a ring ever runs
     *  backwards). Ctrl snaps the value to whole degrees. */
    private void wireRingDrag(Group part, Rig rig,
            java.util.function.Supplier<Vec3> axis,
            java.util.function.DoubleSupplier baseVal,
            java.util.function.DoubleConsumer applyAbs) {
        double[] st = new double[4];   // ux, uy, deg/px, base value
        part.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            sub.requestFocus();
            Vec3 c = rig.anchor.get();
            Vec3 ax = axis.get();
            Point3D cS = world.localToScene(c.x(), -c.y(), c.z());
            Point3D n = new Point3D(ax.x(), -ax.y(), ax.z()).normalize();
            Point3D hit = ringHit(e, c, ax);
            double ux = 1, uy = 0, degPerPx = 0.25;   // edge-on fallback
            if (hit != null) {
                Point3D tan = n.crossProduct(hit.subtract(cS));
                if (tan.magnitude() > 1e-9) {
                    tan = tan.normalize();
                    Point2D sA = screenOfScene(hit);
                    Point2D sB = screenOfScene(hit.add(
                        tan.multiply(0.25 * rig.scale)));
                    Point2D sC = screenOfScene(cS);
                    if (sA != null && sB != null) {
                        double dx = sB.getX() - sA.getX();
                        double dy = sB.getY() - sA.getY();
                        double len = Math.hypot(dx, dy);
                        if (len > 1e-6) {
                            ux = dx / len;
                            uy = dy / len;
                        }
                    }
                    if (sA != null && sC != null) {
                        double rPx = Math.max(30, Math.hypot(
                            sA.getX() - sC.getX(), sA.getY() - sC.getY()));
                        degPerPx = Math.toDegrees(1) / rPx;   // 1 rad/radius
                    }
                }
            }
            st[0] = ux;
            st[1] = uy;
            st[2] = degPerPx;
            st[3] = baseVal.getAsDouble();
            gizmoDragging = true;
            gizmoPushed = false;
            gDragX = e.getScreenX();
            gDragY = e.getScreenY();
            e.consume();
        });
        part.setOnMouseDragged(e -> {
            if (!gizmoDragging) return;
            double dist = (e.getScreenX() - gDragX) * st[0]
                        + (e.getScreenY() - gDragY) * st[1];
            double val = st[3] - dist * st[2];   // world = −scene (mirror)
            if (e.isControlDown()) val = Math.round(val);
            applyAbs.accept(val);
            e.consume();
        });
        part.setOnMouseReleased(e -> {
            endGizmoDrag();
            e.consume();
        });
    }

    /** The mouse ray's hit on the plane through cW ⊥ axW, in SCENE
     *  coords; null when edge-on or behind the camera. */
    private Point3D ringHit(MouseEvent e, Vec3 cW, Vec3 axW) {
        Point3D c = world.localToScene(cW.x(), -cW.y(), cW.z());
        Point3D n = new Point3D(axW.x(), -axW.y(), axW.z()).normalize();
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        Point3D right = cam.localToScene(1, 0, 0).subtract(eye).normalize();
        Point3D down = cam.localToScene(0, 1, 0).subtract(eye).normalize();
        Point2D local = sub.screenToLocal(e.getScreenX(), e.getScreenY());
        if (local == null) return null;
        // pixel ray through the current lens (vertical FOV — the
        // JavaFX default); identical math for the ortho 1° lens
        double f = (sub.getHeight() / 2)
            / Math.tan(Math.toRadians(camFov() / 2));
        Point3D dir = fwd.multiply(f)
            .add(right.multiply(local.getX() - sub.getWidth() / 2))
            .add(down.multiply(local.getY() - sub.getHeight() / 2))
            .normalize();
        double denom = dir.dotProduct(n);
        if (Math.abs(denom) < 1e-4) return null;
        double t = c.subtract(eye).dotProduct(n) / denom;
        if (t <= 0) return null;
        return eye.add(dir.multiply(t));
    }

    /** The rendering camera's FOV (fly cam while flying — gizmos stay
     *  visible there since v16.12). */
    private double camFov() {
        return sub.getCamera() instanceof PerspectiveCamera pc
            ? pc.getFieldOfView() : orbitCam.getFieldOfView();
    }

    private Point2D screenOfScene(Point3D scenePt) {
        Point3D loc = world.sceneToLocal(scenePt);
        return world.localToScreen(loc);
    }

    /** World point → screen, behind-camera filtered (the
     *  pickTrackSample discipline). */
    private Point2D projectWorld(Vec3 p) {
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
        if (sceneP.subtract(eye).dotProduct(fwd) <= 0) return null;
        return world.localToScreen(p.x(), -p.y(), p.z());
    }

    /** One undo snapshot + dirty mark per gizmo gesture (every sink
     *  calls it before its first write). */
    private void pushOnce() {
        if (!gizmoPushed) {
            app.editor.pushUndoSnapshot();
            app.markDirty();
            gizmoPushed = true;
        }
    }

    private void endGizmoDrag() {
        if (!gizmoDragging) return;
        gizmoDragging = false;
        app.syncPlacementSpinners();
        app.updateBridgeControls();
        refreshGizmo(true);
    }

    /** Anchor every rig + reveal marker at its pose, keep the rigs a
     *  constant APPARENT size (~10% of the visible height at the
     *  anchor's view DEPTH — depth, not euclidean distance: off-axis
     *  wide-FOV projection magnifies, which blew the gizmo up toward
     *  the screen edge, v16.12 fix) and run the live re-aim hooks. */
    private void positionGizmo() {
        if (gizmoRigs.isEmpty() && revealMarks.isEmpty()) return;
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        double k = 2 * Math.tan(Math.toRadians(camFov() / 2)) * 0.10;
        for (Rig rig : gizmoRigs) {
            Vec3 a = rig.anchor.get();
            rig.group.setTranslateX(a.x());
            rig.group.setTranslateY(-a.y());
            rig.group.setTranslateZ(a.z());
            Point3D at = world.localToScene(a.x(), -a.y(), a.z());
            double depth = Math.max(1, at.subtract(eye).dotProduct(fwd));
            rig.scale = Math.max(0.5, depth * k);
            rig.scaleT.setX(rig.scale);
            rig.scaleT.setY(rig.scale);
            rig.scaleT.setZ(rig.scale);
            for (Runnable aimIt : rig.aims) aimIt.run();
        }
        for (RevealMark rm : revealMarks) {
            Vec3 a = rm.at().get();
            rm.ball().setTranslateX(a.x());
            rm.ball().setTranslateY(-a.y());
            rm.ball().setTranslateZ(a.z());
        }
    }

    /** Torus of major radius R (in the local XZ plane, about +Y). */
    private static MeshView torus(double R, double r, PhongMaterial mat) {
        TriangleMesh m = new TriangleMesh();
        m.getTexCoords().addAll(0, 0);
        int nu = 48, nv = 8;
        for (int i = 0; i < nu; i++) {
            double a = 2 * Math.PI * i / nu;
            double ca = Math.cos(a), sa = Math.sin(a);
            for (int j = 0; j < nv; j++) {
                double b = 2 * Math.PI * j / nv;
                double rr = R + r * Math.cos(b);
                m.getPoints().addAll((float) (ca * rr),
                    (float) (r * Math.sin(b)), (float) (sa * rr));
            }
        }
        for (int i = 0; i < nu; i++) {
            int i2 = (i + 1) % nu;
            for (int j = 0; j < nv; j++) {
                int j2 = (j + 1) % nv;
                int a = i * nv + j, b = i * nv + j2;
                int c = i2 * nv + j, d = i2 * nv + j2;
                m.getFaces().addAll(a, 0, c, 0, b, 0);
                m.getFaces().addAll(b, 0, c, 0, d, 0);
            }
        }
        MeshView mv = new MeshView(m);
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    /** Cone along +local-Y: base ring at y0, apex at y1. */
    private static MeshView cone(double r, double y0, double y1,
                                 PhongMaterial mat) {
        TriangleMesh m = new TriangleMesh();
        m.getTexCoords().addAll(0, 0);
        int n = 16;
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            m.getPoints().addAll((float) (Math.cos(a) * r), (float) y0,
                (float) (Math.sin(a) * r));
        }
        m.getPoints().addAll(0, (float) y1, 0);
        m.getPoints().addAll(0, (float) y0, 0);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            m.getFaces().addAll(i, 0, n, 0, j, 0);
            m.getFaces().addAll(i, 0, j, 0, n + 1, 0);
        }
        MeshView mv = new MeshView(m);
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

}
