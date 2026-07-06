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
    MeshView heartMesh;   // neon heartline; hidden while riding POV
    PerspectiveCamera orbitCam, povCam;
    boolean povActive = false;
    boolean povCaptured = false;   // right-click: hide mouse, W/S ride;
                                           // uncaptured POV leaves W/S (and the
                                           // S slide key) to the graph editor
    double povTime;        // current ride position (s); W/S move it
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
                } else if (e.isShiftDown() || app.cKey) {
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
            double s = app.settings.flySens() * 2;
            double dx = e.getSceneX() - lastMouseX, dy = e.getSceneY() - lastMouseY;
            camYaw.setAngle(camYaw.getAngle() + dx * s);
            camPitch.setAngle(FvdDesigner.clamp(camPitch.getAngle() - dy * s, -89, 89));
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
                if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
                double rideTime = app.lastSamples.get(app.lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                povTime = ((povTime + wheel * 0.02) % rideTime + rideTime) % rideTime;
                if (app.settings.povMarker()) app.editor.refresh();
            } else if (orthoView) {
                // ortho zoom is multiplicative (the camera sits ~72x out;
                // the additive step and its -1500 clamp would wreck it)
                double f = Math.pow(1.0015, -wheel);
                camZoom.setZ(FvdDesigner.clamp(camZoom.getZ() * f, -ORTHO_FAR_CLIP * 0.5, -200));
            } else {
                camZoom.setZ(FvdDesigner.clamp(camZoom.getZ() + wheel * 0.5, -1500, -10));
            }
        });
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, this::flyLook);
        sub.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::flyLook);

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
                    povTime = ((povTime + dir * dt * mult) % rideTime + rideTime) % rideTime;
                    if (app.settings.povMarker()) app.editor.refresh();   // marker follows
                }
                int idx = (int) FvdDesigner.clamp(povTime / FvdDesigner.INTEGRATE_DT, 0, app.lastSamples.size() - 1);
                applyPovCamera(app.lastSamples.get(idx));
                updateGridLOD(app.lastSamples.get(idx).pos().x(), app.lastSamples.get(idx).pos().z());
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
    }

    /** Rebuild the on-track node markers (viewport editing part 1 —
     *  READ-ONLY). One small sphere per ACTIVE-lane node at its ride
     *  time, sitting on the spine like the ties; selected nodes are
     *  white. Signature-guarded: the graph calls this on every redraw,
     *  but the scene graph only churns when something relevant changed. */
    void refreshTrackMarkers(int lane, Set<Integer> sel, int selIdx) {
        List<BezierBaker.Node> laneNodes = app.lanes.get(lane);
        StringBuilder sb = new StringBuilder();
        sb.append(lane).append('|').append(selIdx).append('|').append(sel)
          .append('|').append(System.identityHashCode(app.lastSamples))
          .append('|').append(app.settings.trackMarkers());
        for (BezierBaker.Node n : laneNodes) sb.append(';').append(n.t);
        String sig = sb.toString();
        if (sig.equals(markerSig)) return;
        markerSig = sig;
        markerGroup.getChildren().clear();
        if (!app.settings.trackMarkers() || app.lastSamples == null || app.lastSamples.isEmpty()) return;
        for (int i = 0; i < laneNodes.size(); i++) {
            int idx = (int) FvdDesigner.clamp(Math.round(laneNodes.get(i).t / FvdDesigner.INTEGRATE_DT),
                                  0, app.lastSamples.size() - 1);
            FVDIntegrator.Sample sm = app.lastSamples.get(idx);
            Vec3 p = sm.pos().sub(sm.U().scale(app.heartOffset));   // spine, like ties
            boolean isSel = i == selIdx || sel.contains(i);
            // selection reads from any distance: white AND bigger
            Sphere ball = new Sphere(isSel ? 0.32 : 0.22, 12);
            ball.setMaterial(new PhongMaterial(isSel ? Color.WHITE : FvdDesigner.LANE_COLORS[lane]));
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
    FVDIntegrator.Sample pickTrackSample(double screenX, double screenY) {
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return null;
        double yaw = camYaw.getAngle(), pitch = camPitch.getAngle();
        Point3D fwd = new Rotate(yaw, Rotate.Y_AXIS).transform(
                      new Rotate(pitch, Rotate.X_AXIS).transform(new Point3D(0, 0, 1)));
        Point3D camPos = new Point3D(camPivot.getX(), camPivot.getY(), camPivot.getZ())
                .subtract(fwd.multiply(-camZoom.getZ()));
        double best = 25 * 25;
        FVDIntegrator.Sample bestSample = null;
        for (int i = 0; i < app.lastSamples.size(); i += 5) {
            Vec3 p = app.lastSamples.get(i).pos();
            Point3D sceneP = world.localToScene(p.x(), -p.y(), p.z());
            if (sceneP.subtract(camPos).dotProduct(fwd) <= 0) continue;  // behind
            Point2D s = world.localToScreen(p.x(), -p.y(), p.z());
            if (s == null) continue;
            double dx = s.getX() - screenX, dy = s.getY() - screenY;
            double d2 = dx * dx + dy * dy;
            if (d2 < best) { best = d2; bestSample = app.lastSamples.get(i); }
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

    /** Ctrl+click the track: insert a node in the ACTIVE lane at that
     *  point's ride time — the 3D twin of clicking the graph curve
     *  (same insert rules: exact split, gap guards, selects the node). */
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
            povTime = 0;
            povLastNanos = 0;
            moveKeys.clear();
            povCaptured = keepCapture;
            if (povCaptured) sub.setCursor(Cursor.NONE);
            if (heartMesh != null) heartMesh.setVisible(false);
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
            sub.setCamera(orbitCam);
            app.editor.refresh();
            toggleFly();
        } else {
            povActive = false;
            povTimer.stop();
            moveKeys.clear();
            if (heartMesh != null) heartMesh.setVisible(true);
            sub.setCamera(orbitCam);
            app.editor.refresh();   // hide the POV marker line
        }
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
    }

    /** Mouse look: measure the offset from the viewport centre, then warp
     *  the (hidden) cursor back — JavaFX cannot capture the pointer, so
     *  Robot-recentering is the standard FPS-camera workaround. */
    void flyLook(MouseEvent e) {
        if (!flyActive) return;
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c == null) return;
        // per-event delta capped: OS pointer acceleration makes fast flicks
        // arrive as huge single deltas, which felt twitchy — the cap evens
        // out slow-vs-fast response; base sensitivity is an Options slider
        double sens = app.settings.flySens();
        double dx = FvdDesigner.clamp(e.getScreenX() - c.getX(), -35, 35);
        double dy = FvdDesigner.clamp(e.getScreenY() - c.getY(), -35, 35);
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) return;  // our own warp event
        flyYaw.setAngle(flyYaw.getAngle() + dx * sens);
        flyPitch.setAngle(FvdDesigner.clamp(flyPitch.getAngle() - dy * sens, -89, 89));
        flyRobot.mouseMove(c.getX(), c.getY());
    }

    void centerFlyCursor() {
        Point2D c = sub.localToScreen(sub.getWidth() / 2, sub.getHeight() / 2);
        if (c != null) flyRobot.mouseMove(c.getX(), c.getY());
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

    void rebuildTrack() {
        // the three-pass pipeline (Phase 1): geometry per section at its
        // design speed, then global dynamics + analysis. For the current
        // single-force-section document this reproduces the old direct
        // integrator call bit-for-bit (gated headlessly, D50).
        TrackCompiler.Result compiled = TrackCompiler.compile(app.coaster, FvdDesigner.INTEGRATE_DT);
        List<FVDIntegrator.Sample> samples = compiled.samples();
        if (samples.size() < 2) return;
        app.lastSamples = samples;
        app.resultRates = compiled.rates();

        // two rails + cross ties, offset from the heartline
        MeshView leftRail = buildRailMesh(samples, -app.railGauge / 2);
        MeshView rightRail = buildRailMesh(samples, app.railGauge / 2);
        MeshView ties = buildTieMesh(samples);

        List<javafx.scene.Node> parts = new ArrayList<>(List.of(leftRail, rightRail, ties));
        heartMesh = app.settings.showHeartline() ? buildHeartlineMesh(samples) : null;
        if (heartMesh != null) {
            heartMesh.setVisible(!povActive);   // the camera rides ON it
            parts.add(heartMesh);
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
        for (FVDIntegrator.Sample sm : samples) {
            maxGv = Math.max(maxGv, sm.Gv()); minGv = Math.min(minGv, sm.Gv());
            maxY = Math.max(maxY, sm.pos().y()); minY = Math.min(minY, sm.pos().y());
            maxV = Math.max(maxV, sm.v());
        }
        FVDIntegrator.Sample last = samples.get(samples.size() - 1);
        app.statsLabel.setText(String.format(
            "Ride: %.1f s   |   Track: %.0f m   |   Height: %.1f to %.1f m   |   Top speed: %.0f km/h   |   Gv: %.1f to %.1f      %s",
            last.t(), last.s(), minY, maxY, maxV * 3.6, minGv, maxGv, app.shortcutHint()));
    }

    /** A single rail tube: heartline offset down by heartOffset along U,
     *  sideways by `lateral` along R, radius 70 mm, colored by vertical G. */
    MeshView buildRailMesh(List<FVDIntegrator.Sample> samples, double lateral) {
        TriangleMesh mesh = new TriangleMesh();
        int nRings = 0;
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
            float u = (float) FvdDesigner.clamp((sm.Gv() - FvdDesigner.G_MIN) / (FvdDesigner.G_MAX - FvdDesigner.G_MIN), 0.001, 0.999);
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
        if (app.railSolid) mat.setDiffuseColor(app.railColor);
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
    MeshView buildHeartlineMesh(List<FVDIntegrator.Sample> samples) {
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
    MeshView buildTieMesh(List<FVDIntegrator.Sample> samples) {
        final double TIE_HALF_W = 0.045;  // along T (track direction)
        final double TIE_HALF_H = 0.03;   // along U
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        int v = 0;
        double nextS = 0;
        // ties end exactly at the rail centerlines: the tie cross-section
        // (0.09 x 0.06 m) hides inside the 0.07 m-radius tubes, nothing pokes out
        double halfLen = app.railGauge / 2;
        for (FVDIntegrator.Sample sm : samples) {
            if (sm.s() < nextS) continue;
            nextS += Math.max(0.1, app.tieSpacing);
            Vec3 spine = sm.pos().sub(sm.U().scale(app.heartOffset));
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

    WritableImage buildGForceGradient() {
        int w = 256;
        WritableImage img = new WritableImage(w, 1);
        PixelWriter pw = img.getPixelWriter();
        for (int i = 0; i < w; i++) {
            double g = FvdDesigner.G_MIN + (FvdDesigner.G_MAX - FvdDesigner.G_MIN) * i / (w - 1.0);
            Color c;
            if (g <= app.gradStops[0]) {
                c = app.gradColors[0];
            } else if (g >= app.gradStops[3]) {
                c = app.gradColors[3];
            } else {
                int k = 0;
                while (k < 2 && g > app.gradStops[k + 1]) k++;
                double span = app.gradStops[k + 1] - app.gradStops[k];
                c = span < 1e-9 ? app.gradColors[k + 1]
                    : lerpColor(app.gradColors[k], app.gradColors[k + 1], (g - app.gradStops[k]) / span);
            }
            pw.setColor(i, 0, c);
        }
        return img;
    }

    static Color lerpColor(Color a, Color b, double t) {
        return Color.color(
            a.getRed() + (b.getRed() - a.getRed()) * t,
            a.getGreen() + (b.getGreen() - a.getGreen()) * t,
            a.getBlue() + (b.getBlue() - a.getBlue()) * t
        );
    }

}
