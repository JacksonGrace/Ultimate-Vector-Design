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
import javafx.scene.ParallelCamera;
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
    // ortho top/side views: TRUE orthographic since v18.8 — a
    // ParallelCamera MIRRORS the orbit rig (pivot/yaw/pitch) with a
    // per-axis Scale whose x/y = world-units-per-pixel (the same
    // 2·d·tan(fov/2)/h the 1°-lens pseudo-ortho showed, so zoom/pan/
    // restore machinery is untouched and camZoom still IS the zoom
    // state). JavaFX ignores a ParallelCamera's near/farClip and
    // clips a fixed ±(viewport w/2) window in CAMERA units (probed,
    // JavaFX 26) — the transform's larger Z-scale widens that to
    // ±(w/2)·ORTHO_DEPTH world meters around the pivot plane; depth
    // testing survives the non-uniform scale (probed). The 1°-FOV
    // constants remain the zoom/pan bookkeeping.
    static final double ORTHO_FOV = 1.0;
    static final double ORTHO_DEPTH = 10;   // world m of depth per px of width
    static final double ORTHO_FAR_CLIP = 200000, PERSP_FAR_CLIP = 5000;
    final javafx.scene.ParallelCamera orthoCam =
        new javafx.scene.ParallelCamera();
    private final Translate orthoPivotT = new Translate();
    private final Rotate orthoYawR = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate orthoPitchR = new Rotate(0, Rotate.X_AXIS);
    private final javafx.scene.transform.Scale orthoScaleS =
        new javafx.scene.transform.Scale(1, 1, ORTHO_DEPTH);
    private final Translate orthoCenterT = new Translate();
    private double[] orthoSig = new double[8];
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
    // PER-PANE POV direction since v18.11 (user report: A flipped
    // EVERY pane's POV through the shared setting; the legacy
    // povReversed settings key is written no more and ignored)
    boolean povReversed = false;
    boolean povCaptured = false;   // right-click: hide mouse, W/S ride;
                                           // uncaptured POV leaves W/S (and the
                                           // S slide key) to the graph editor
    // app.povTime + app.povFollowPiece are SHARED across view panes since
    // v18.10 (D181) — they live on FvdDesigner (one ride position,
    // one graph marker, one stats line; two POV panes show the same
    // spot)
    long povLastNanos;
    AnimationTimer povTimer;
    final Group markerGroup = new Group();   // on-track node markers
    String markerSig = "";                   // skip no-op rebuilds
    Group povHolder;    // the POV camera's world-riding holder
    Group root3D;       // subscene root (identity transform — the
                        // scene-coordinate projection anchor, v18.9)

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
        // ONE FOV since v18.9 (user spec): every camera reads the
        // shared flyFov; the legacy povFov key stays in the file,
        // ignored (the D84 stale-key precedent)
        povCam.setFieldOfView(app.settings.flyFov());
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
        // to the POV camera exactly as it does to the track mesh; the
        // fixed-size reveal balls are part of the TRACK layer (v18.17,
        // D188 — normal depth, occludable like any track geometry)
        world.getChildren().addAll(trackGroup, povHolder, trackBallGroup);
        this.povHolder = povHolder;
        root3D = new Group(world, cameraRig, flyHolder, ambient, sun);

        sub = new SubScene(root3D, 800, 400, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.rgb(15, 18, 24));
        sub.setCamera(orbitCam);

        // GIZMO OVERLAY SUBSCENE (v18.17, D188 — replacing D187's
        // DepthTest.DISABLE, whose submission-order rendering read as
        // inverted normals and drew rings over arrows): the rigs
        // render in their OWN transparent SubScene with a real depth
        // buffer — proper self-occlusion within the gizmo, still
        // drawn over the whole world. gizmoWorld mirrors `world`'s
        // centering translate; the cameras mirror the active one per
        // frame (syncGizmoCam off the axes timer). pickOnBounds(false)
        // lets events pass through to the main scene wherever no
        // gizmo geometry is hit.
        gizmoWorld.getChildren().add(gizmoGroup);
        AmbientLight gAmbient = new AmbientLight(Color.rgb(120, 120, 130));
        PointLight gSun = new PointLight(Color.WHITE);
        gSun.setTranslateX(-150);
        gSun.setTranslateY(-300);
        gSun.setTranslateZ(-100);
        gizmoPCam = new PerspectiveCamera(true);
        gizmoPCam.setNearClip(0.05);
        gizmoPCam.setFarClip(5000);
        gizmoOCam = new ParallelCamera();
        Group gizmoRoot = new Group(gizmoWorld, gAmbient, gSun);
        gizmoSub = new SubScene(gizmoRoot, 800, 400, true,
            SceneAntialiasing.BALANCED);
        gizmoSub.setFill(null);
        gizmoSub.setCamera(gizmoPCam);
        gizmoSub.setPickOnBounds(false);

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
                    // one capture across all panes (v18.10)
                    if (povCaptured) app.releaseOtherCaptures(this);
                    syncPovLabel();   // the "(A)" hint is capture-only
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
                // CLICK-OFF hides a revealed gizmo (v18.16, D187 —
                // gizmo parts consume their presses, so any primary
                // press landing here is off the rig)
                if (gizmoReveal != 0) {
                    gizmoReveal = 0;
                    refreshGizmo(false);
                }
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
            // a clean click NOT on a marker deselects (v18.19, D190
            // — graph-editor parity), then the track pick proceeds
            app.editor.deselectFromViewport();
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
                app.povTime = ((app.povTime + wheel * 0.02) % rideTime + rideTime) % rideTime;
                app.povTime = povClampStall(app.povTime, wheel);   // solid track only (v18.0)
                app.editor.refresh();   // POV marker always on (v16.14)
                app.updatePovStats();       // live POV line (v17.11)
            } else if (orthoView) {
                // ortho zoom is multiplicative (the camera sits ~72x out;
                // the additive step and its -1500 clamp would wreck it)
                // — TWICE the v18.8 rate since v18.11 (user spec), and
                // zooming IN anchors the point under the CURSOR (the
                // pivot slides so it stays put); zooming out stays
                // center-anchored
                double f = Math.pow(1.003, -wheel);
                double uppB = orthoUpp();
                camZoom.setZ(FvdDesigner.clamp(camZoom.getZ() * f, -ORTHO_FAR_CLIP * 0.5, -200));
                if (wheel > 0) {
                    double du = uppB - orthoUpp();   // post-clamp honest
                    double dx = e.getX() - sub.getWidth() / 2;
                    double dy = e.getY() - sub.getHeight() / 2;
                    Rotate ry = new Rotate(camYaw.getAngle(), Rotate.Y_AXIS);
                    Rotate rx = new Rotate(camPitch.getAngle(), Rotate.X_AXIS);
                    Point3D right = ry.transform(rx.transform(new Point3D(1, 0, 0)));
                    Point3D down = ry.transform(rx.transform(new Point3D(0, 1, 0)));
                    camPivot.setX(camPivot.getX()
                        + (right.getX() * dx + down.getX() * dy) * du);
                    camPivot.setY(camPivot.getY()
                        + (right.getY() * dx + down.getY() * dy) * du);
                    camPivot.setZ(camPivot.getZ()
                        + (right.getZ() * dx + down.getZ() * dy) * du);
                }
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
                    // — INTERPOLATED at the exact povTime since v18.22
                    // (D193: on a tilted/drifted section the design-
                    // spaced samples sit decimeters apart while the
                    // true speed loses whole m/s per sample — the
                    // nearest-sample ratio made the ride advance and
                    // speed readout step visibly approaching a stall,
                    // his "locked to distance increments").
                    // The RIDE keeps its 0.1 m/s crawl floor (v18.7,
                    // D176): the records are HONEST since v18.6, so
                    // an unfloored ratio → 0 approaching a stall and
                    // app.povTime froze just short of the stall range —
                    // the wrap never fired (the user's "stuck at
                    // zero"). The floor restores the historic feel
                    // and guarantees the range is reached.
                    double ratio = app.trueV == null ? 1
                        : Math.max(0.1, app.trueVAt(app.povTime))
                            / Math.max(0.1, app.designVAt(app.povTime));
                    app.povTime = ((app.povTime + dir * dt * mult * ratio) % rideTime + rideTime) % rideTime;
                    // stalled track is unreachable: jump the gap in
                    // the direction of travel (v18.0, D163)
                    app.povTime = povClampStall(app.povTime, dir);
                    app.editor.refresh();   // marker follows (always on, v16.14)
                }
                int idx = (int) FvdDesigner.clamp(app.povTime / FvdDesigner.INTEGRATE_DT, 0, app.lastSamples.size() - 1);
                // camera INTERPOLATES between samples (v18.1 — the
                // quantize-to-sample camera stepped visibly at slow
                // ride speeds, the user's "jittering" with Ctrl)
                applyPovCamera(povPoseAt(app.povTime));
                updateGridLOD(app.lastSamples.get(idx).pos().x(), app.lastSamples.get(idx).pos().z());
                app.updatePovStats();   // live POV line (v17.11)
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
                    if (pc >= 0 && pc != app.povFollowPiece) {
                        app.povFollowPiece = pc;
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
        // the ENTRY BALL is node 0's representation when it covers it
        // (v18.13, user spec — the two spheres z-fought before)
        boolean entryBall = app.editor != null
            && app.editor.activeSection() == 0
            && !app.editor.followsPrev(app.editor.activePiece())
            && !povActive;
        StringBuilder sb = new StringBuilder();
        sb.append(lane).append('|').append(selIdx).append('|').append(sel)
          .append('|').append(laneCol)
          .append('|').append(System.identityHashCode(app.lastSamples))
          .append('|').append(app.settings.trackMarkers())
          .append('|').append(entryBall)
          .append('|').append(povActive);   // marker height flips (v18.7)
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
            if (i == 0 && entryBall) continue;   // the ball covers it
            int idx = (int) FvdDesigner.clamp(Math.round(nodeTimes[i] / FvdDesigner.INTEGRATE_DT),
                                  0, app.lastSamples.size() - 1);
            FVDIntegrator.Sample sm = app.lastSamples.get(idx);
            // ORBIT/FLY: the markers ride the HEARTLINE (v18.7, D176
            // — user spec); POV keeps the spine-level spheres (the
            // camera travels the heartline itself)
            Vec3 p = povActive
                ? sm.pos().sub(sm.U().scale(app.heartOffset))
                : sm.pos();
            boolean isSel = i == selIdx || sel.contains(i);
            // selection reads from any distance: white AND bigger
            Sphere ball = new Sphere(isSel ? 0.32 : 0.22, 12);
            ball.setMaterial(new PhongMaterial(isSel ? Color.WHITE : laneCol));
            ball.setTranslateX(p.x());
            ball.setTranslateY(-p.y());   // Y-flip discipline
            ball.setTranslateZ(p.z());
            // the NODE index rides the sphere (v18.17, D188): the
            // entry ball can replace node 0's marker, so child-list
            // position != node index — the pick must not assume it
            // (clicking any marker selected the PREVIOUS node)
            ball.setUserData(i);
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
        eye = effEye(cam, eye, fwd);
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
                // the sphere carries its NODE index (v18.17, D188 —
                // list position shifted whenever the entry ball
                // replaced node 0's marker)
                bestIdx = (Integer) ball.getUserData();
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
        eye = effEye(cam, eye, fwd);
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
    // (toggleOrthoView is GONE, v18.11 — T/Shift+T were replaced by
    // the 1-6 view-direction keys; FvdDesigner.handleViewDigit routes
    // to orthoEnterTop/setOrthoSideMode.)

    private void orthoEnter(boolean top) {
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
            // TRUE ortho (v18.8): render through the rig-mirroring
            // ParallelCamera — the orbit rig stays the state
            if (orthoCam.getTransforms().isEmpty()) {
                orthoCam.getTransforms().addAll(orthoPivotT, orthoYawR,
                    orthoPitchR, orthoScaleS, orthoCenterT);
            }
            syncOrthoCam();
            sub.setCamera(orthoCam);
        }
        orthoTop = top;
        syncViewMode();
        if (top) {
            // plan view LOCKS to the grid: yaw snaps to the nearest 90°
            camPitch.setAngle(-90);
            camYaw.setAngle(Math.round(camYaw.getAngle() / 90.0) * 90);
        } else {
            applySideMode();
        }
        refreshGizmo(false);   // scale + plane-arrow hiding follow the
                               // new lens NOW (v18.11 — fly→side left
                               // over/undersized gizmos)
    }

    /** SIDE sub-modes (v18.9, user spec): 0 = Front (looking along
     *  world +Z), 1 = Back, 2 = Right (−X), 3 = Left (+X), 4 =
     *  Tangent to Selection — the D53 behavior; camera forward =
     *  (sin yaw, 0, cos yaw) in scene coords. */
    int orthoSideMode = 4;

    private void applySideMode() {
        double yaw;
        switch (orthoSideMode) {
            case 0 -> yaw = 0;      // Front: Z away
            case 1 -> yaw = 180;    // Back
            case 2 -> yaw = -90;    // Right
            case 3 -> yaw = 90;     // Left: X away
            default -> {
                // side elevation TANGENT to the track at the selected
                // node (D53): look perpendicular to its horizontal
                // heading so the track runs across the screen there.
                // Of the two sides, keep the one nearer the current
                // camera. No selection (or a vertical tangent): world
                // X–Y elevation as before.
                yaw = 0;
                Vec3 tan = app.editor != null
                    ? app.editor.selectedTrackTangent() : null;
                if (tan != null && Math.hypot(tan.x(), tan.z()) > 1e-6) {
                    double y1 = Math.toDegrees(Math.atan2(-tan.z(), tan.x()));
                    yaw = angleDist(camYaw.getAngle(), y1)
                        <= angleDist(camYaw.getAngle(), y1 + 180)
                        ? y1 : y1 + 180;
                }
            }
        }
        camYaw.setAngle(yaw);
        camPitch.setAngle(0);
        if (orthoSideMode == 4) {
            centerOrthoOnSelection();
            // the tangent grid's horizontal ORIGIN (v18.10, user
            // spec): anchored where the node is at view entry /
            // refresh — later node moves don't slide the grid
            tangAnchorX = camPivot.getX();
            tangAnchorZ = camPivot.getZ();
        }
    }

    // grid-overlay anchor for the tangent side view (scene coords)
    double tangAnchorX = 0, tangAnchorZ = 0;

    /** The side sub-mode dropdown's entry point: remembers the pick
     *  and (re-)enters the side view with it. */
    void setOrthoSideMode(int m) {
        orthoSideMode = m;
        if (flyActive || povActive) return;
        if (!orthoView || orthoTop) orthoEnter(false);
        else applySideMode();
        syncViewMode();
        refreshGizmo(false);   // the plane arrow follows the sub-mode
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
        if (sub.getCamera() == orthoCam) sub.setCamera(orbitCam);
        syncViewMode();
        refreshGizmo(false);   // scale + arrows follow the restored lens
    }

    /** Keep the true-ortho ParallelCamera glued to the orbit rig
     *  (v18.8): pivot/yaw/pitch copied, x/y scale = the world-units-
     *  per-pixel the zoom state implies, view centered on the pivot.
     *  Signature-guarded and driven off the axes-compass timer, so
     *  zoom/pan/resize all land without touching their handlers. */
    void syncOrthoCam() {
        if (!orthoView || sub == null) return;
        double w = Math.max(1, sub.getWidth()), h = Math.max(1, sub.getHeight());
        double s = orthoUpp();
        double[] sig = { camPivot.getX(), camPivot.getY(), camPivot.getZ(),
                         camYaw.getAngle(), camPitch.getAngle(), s, w, h };
        if (java.util.Arrays.equals(sig, orthoSig)) return;
        orthoSig = sig;
        orthoPivotT.setX(camPivot.getX());
        orthoPivotT.setY(camPivot.getY());
        orthoPivotT.setZ(camPivot.getZ());
        orthoYawR.setAngle(camYaw.getAngle());
        orthoPitchR.setAngle(camPitch.getAngle());
        orthoScaleS.setX(s);
        orthoScaleS.setY(s);
        orthoCenterT.setX(-w / 2);
        orthoCenterT.setY(-h / 2);
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
            if (ap != app.povFollowPiece) {
                app.povTime = app.editor != null
                    ? app.editor.pieceStartTime(ap) : 0;
                app.povFollowPiece = ap;
            } else {
                double rideEnd = app.lastSamples
                    .get(app.lastSamples.size() - 1).t();
                app.povTime = FvdDesigner.clamp(app.povTime, 0, rideEnd);
            }
            app.povTime = povClampStall(app.povTime, 1);   // solid track (v18.0)
            povLastNanos = 0;
            moveKeys.clear();
            povCaptured = keepCapture;
            if (povCaptured) {
                sub.setCursor(Cursor.NONE);
                app.releaseOtherCaptures(this);   // one capture (v18.10)
            }
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
        // marker HEIGHT flips with the mode (v18.7) — sync DIRECTLY:
        // editor.refresh() only reaches the marker sync through a
        // canvas redraw, which early-returns while the graph panel is
        // COLLAPSED, so riding POV with the panel down kept the
        // orbit-time heartline markers (v18.8 — the user's report;
        // sig-guarded, so the extra call is free when redraw ran)
        if (app.editor != null) app.editor.refreshMarkers();
        syncPovLabel();        // backward indicator (v16.16)
        syncViewMode();        // top-left mode label (v18.8)
    }

    void toggleFly() {
        if (povActive) return;
        if (!flyActive) {
            if (orthoView) exitOrthoKeepView();   // hand off a sane pose
            flyActive = true;
            app.releaseOtherCaptures(this);   // one capture (v18.10)
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
        applyPovCamera(povPoseAt(app.povTime));
    }

    /** The POV pose at an exact ride time — frame LERPED between the
     *  bracketing samples (v18.1: the camera used to snap to the
     *  0.01 s sample grid, a visible judder at ×0.25 speeds; the
     *  same timeHit lerp the combs use). */
    private FVDIntegrator.Sample povPoseAt(double t) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        int idx = (int) FvdDesigner.clamp(t / FvdDesigner.INTEGRATE_DT,
            0, ss.size() - 1);
        ArcHit h = timeHit(ss, Math.max(0, idx - 2), t);
        FVDIntegrator.Sample near = ss.get(idx);
        return new FVDIntegrator.Sample(t, near.s(), h.pos(), h.T(),
            h.U(), h.R(), near.v(), near.Gv(), near.Gl());
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
        Vec3 tw = povReversed ? sm.T().scale(-1) : sm.T();
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

    /** Rebuild THIS pane's scene from the app's compiled arrays
     *  (v18.10, D181: the document phase — compile/reconcile/stats —
     *  moved to FvdDesigner.rebuildTrack, which loops every pane
     *  through here). */
    void rebuildMeshes() {
        List<FVDIntegrator.Sample> samples = app.lastSamples;
        if (samples == null || samples.size() < 2) return;
        computeStallRanges(samples);      // v18.0, D163: unreachable track
        computePlateRanges(samples, app.sectionSpans);   // speed-control plates

        // rails + cross ties PER PIECE (v13.2): meshes never span a
        // piece boundary (detached ends stay visually detached — a
        // clean bridge/attach seam abuts flush and reads as one track)
        // and inactive pieces render DESATURATED so the edited one pops
        List<javafx.scene.Node> parts = new ArrayList<>();
        List<TrackCompiler.Span> spans = app.sectionSpans;
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
            // STALLED track renders TRANSPARENT (v18.0, D163): past
            // the sample where the true march hit the crawl floor the
            // train never arrives — rails/ties ghost from there to
            // the run's end (the piece splits at the stall sample)
            int stall = stallIdxIn(i0, i1);
            if (stall < 0 || stall >= i1) {          // fully alive
                parts.add(buildRailMesh(sub, i0, -app.railGauge / 2, act, false));
                parts.add(buildRailMesh(sub, i0, app.railGauge / 2, act, false));
                TiePiece tp = new TiePiece(sub, i0, act, false, new Group());
                tiePieces.add(tp);
                parts.add(tp.group());
            } else if (stall <= i0) {                // fully unreachable
                parts.add(buildRailMesh(sub, i0, -app.railGauge / 2, act, true));
                parts.add(buildRailMesh(sub, i0, app.railGauge / 2, act, true));
                TiePiece tp = new TiePiece(sub, i0, act, true, new Group());
                tiePieces.add(tp);
                parts.add(tp.group());
            } else {                                 // split at the stall
                List<FVDIntegrator.Sample> alive =
                    samples.subList(i0, stall + 1);
                List<FVDIntegrator.Sample> dead =
                    samples.subList(stall, i1 + 1);
                parts.add(buildRailMesh(alive, i0, -app.railGauge / 2, act, false));
                parts.add(buildRailMesh(alive, i0, app.railGauge / 2, act, false));
                parts.add(buildRailMesh(dead, stall, -app.railGauge / 2, act, true));
                parts.add(buildRailMesh(dead, stall, app.railGauge / 2, act, true));
                TiePiece tpa = new TiePiece(alive, i0, act, false, new Group());
                TiePiece tpd = new TiePiece(dead, stall, act, true, new Group());
                tiePieces.add(tpa);
                tiePieces.add(tpd);
                parts.add(tpa.group());
                parts.add(tpd.group());
            }
            // SPEED-CONTROL PLATES (v18.0, D160): a flat plate down
            // the tie centers over every controlled stretch, colored
            // by the control type (green = speed, blue = accel)
            parts.addAll(buildPlateMeshes(sub));
            // heartline strand PER PIECE (v16.13): detached pieces
            // never read as one strand however close they park
            if (heart != null) heart.getChildren().add(buildHeartlineMesh(sub));
            // track COMBS (v16.13; three-way mode since v16.14 — the
            // G-force comb is vertical + lateral as one unit; FIVE
            // modes since v17.16 D157: + GEOMETRIC (curvature, three
            // sub-modes) and TIME (0.1 s ticks, whole seconds white))
            if (combs != null) {
                int cmode = app.settings.combMode();
                if (cmode == AppSettings.COMB_GFORCE) {
                    combs.getChildren().add(buildCombMesh(sub, i0, 0));
                    combs.getChildren().add(buildCombMesh(sub, i0, 1));
                } else if (cmode == AppSettings.COMB_SPEED) {
                    combs.getChildren().add(buildCombMesh(sub, i0, 2));
                } else if (cmode == AppSettings.COMB_GEO) {
                    int gm = app.settings.combGeoMode();
                    if (gm == AppSettings.COMB_GEO_LOCAL) {
                        combs.getChildren().add(buildCombMesh(sub, i0, 3));
                        combs.getChildren().add(buildCombMesh(sub, i0, 4));
                    } else if (gm == AppSettings.COMB_GEO_GLOBAL) {
                        combs.getChildren().add(buildCombMesh(sub, i0, 5));
                        combs.getChildren().add(buildCombMesh(sub, i0, 6));
                    } else {
                        combs.getChildren().add(buildCombMesh(sub, i0, 7));
                    }
                } else {
                    combs.getChildren().add(buildCombMesh(sub, i0, 8));
                    combs.getChildren().add(buildCombMesh(sub, i0, 9));
                    combs.getChildren().add(buildCombMesh(sub, i0, 10));
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
            // the gizmo overlay's world mirrors the centering translate
            // (v18.17, D188) — both layers share one scene space
            gizmoWorld.getTransforms().add(
                new Translate(-mid.pos().x(), 0, -mid.pos().z()));
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

    }

    /** A single rail tube: heartline offset down by heartOffset along U,
     *  sideways by `lateral` along R, radius 70 mm, colored by vertical G
     *  — the TRUE felt G since v17.12 (D153, user spec: the gradient
     *  reads like the G-force comb; base = the sublist's global sample
     *  offset into the true arrays). */
    MeshView buildRailMesh(List<FVDIntegrator.Sample> samples, int base,
                           double lateral, boolean active) {
        return buildRailMesh(samples, base, lateral, active, false);
    }

    /** Adaptive tube tessellation (v18.1, D167 — the user's "tubes
     *  around tight corners look like connected straight sections"):
     *  ring samples are picked by TURN ANGLE — a ring lands whenever
     *  the tangent has swung past the angle budget since the last one
     *  (with an arc floor so ultra-tight twists can't explode the
     *  mesh, and an arc cap so straights still get rings). The View
     *  menu's Mesh Quality % scales the budget: higher = smoother. */
    private List<Integer> railRings(List<FVDIntegrator.Sample> samples) {
        // fixed budget (v18.2 — the Mesh Quality slider was removed on
        // user verdict; the 100% tuning IS the behavior)
        double angMax = Math.toRadians(2.0);       // turn per ring
        double arcMax = 1.2;                       // m, straight-track cap
        double arcMin = 0.06;                      // m, density floor
        List<Integer> out = new ArrayList<>();
        out.add(0);
        FVDIntegrator.Sample last = samples.get(0);
        for (int i = 1; i < samples.size() - 1; i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            double arc = sm.s() - last.s();
            double dot = Math.max(-1, Math.min(1, sm.T().dot(last.T())));
            double ang = Math.acos(dot);
            if ((ang >= angMax && arc >= arcMin) || arc >= arcMax) {
                out.add(i);
                last = sm;
            }
        }
        out.add(samples.size() - 1);
        return out;
    }

    /** ghost = the stalled-unreachable stretch (v18.0, D163): a flat
     *  translucent grey tube, whatever the rail-color mode. */
    MeshView buildRailMesh(List<FVDIntegrator.Sample> samples, int base,
                           double lateral, boolean active, boolean ghost) {
        TriangleMesh mesh = new TriangleMesh();
        int nRings = 0;
        List<Vec3> ringPos = new ArrayList<>();
        for (int i : railRings(samples)) {
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
        if (ghost) {
            // unreachable track (v18.0, D163): see-through grey
            mat.setDiffuseColor(Color.rgb(150, 155, 168, 0.18));
            mat.setSpecularColor(Color.rgb(40, 40, 44));
        } else if (app.railSolid) {
            mat.setDiffuseColor(active ? app.railColor
                : app.railColor.deriveColor(0, 0.30, 0.80, 1));
        } else {
            mat.setDiffuseMap(buildGForceGradient(!active));
        }
        if (!ghost) {
            mat.setSpecularColor(active ? Color.rgb(200, 200, 200)
                                        : Color.rgb(90, 90, 95));
        }
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
     *  activation changes without recompiling the track. ghost = the
     *  stalled-unreachable stretch (v18.0, D163 — renders see-through). */
    private record TiePiece(List<FVDIntegrator.Sample> samples, int base,
                            boolean active, boolean ghost, Group group) { }
    private final List<TiePiece> tiePieces = new ArrayList<>();
    private String tieSig;

    // ---------- STALL RANGES (v18.0, D163) ----------
    // Per RUN: the first sample where Pass D's true march hit the
    // 0.1 m/s crawl floor — the train never really passes it, so
    // everything after (to the run's end) is UNREACHABLE: rails/ties
    // render transparent and the POV jumps across.
    private final List<int[]> stallRanges = new ArrayList<>();
    // speed-control PLATE ranges {global s0, s1, type} (v18.0, D160)
    private final List<double[]> plateRanges = new ArrayList<>();

    private void computeStallRanges(List<FVDIntegrator.Sample> samples) {
        stallRanges.clear();
        if (app.trueV == null) return;
        // run starts mirror the compiler's rule: a piece not bridged-
        // in and not a strict-seam follower opens a new run
        List<Integer> starts = new ArrayList<>();
        boolean prevEndsBridge = false;
        int base = 0;
        List<TrackCompiler.Span> spans = app.sectionSpans;
        for (int p = 0; p < app.coaster.pieces.size(); p++) {
            TrackPiece piece = app.coaster.pieces.get(p);
            int nSec = piece.sections.size();
            if (nSec == 0 || spans == null || base + nSec > spans.size()) {
                base += nSec;
                continue;
            }
            boolean bridgedIn = prevEndsBridge;
            boolean followed = piece.followPrev && p > 0 && !bridgedIn
                && !app.coaster.pieces.get(p - 1).sections.isEmpty();
            if (!bridgedIn && !followed) {
                starts.add(spans.get(base).i0());
            }
            prevEndsBridge = piece.sections
                .get(nSec - 1) instanceof BridgeSection;
            base += nSec;
        }
        starts.add(samples.size());
        int n = Math.min(samples.size(), app.trueV.length);
        for (int r = 0; r + 1 < starts.size(); r++) {
            int i0 = starts.get(r), i1 = Math.min(starts.get(r + 1), n);
            for (int i = i0; i < i1; i++) {
                if (app.trueV[i] <= 0.1 + 1e-6) {
                    stallRanges.add(new int[]{ i, i1 - 1 });
                    break;
                }
            }
        }
    }

    /** First stalled sample within [i0, i1], or −1. */
    private int stallIdxIn(int i0, int i1) {
        for (int[] r : stallRanges) {
            if (r[1] < i0 || r[0] > i1) continue;
            return Math.max(r[0], i0);
        }
        return -1;
    }

    /** POV positions stay on SOLID track (v18.0, D163; re-specced
     *  v18.2 — his clarification: the stall point behaves like the
     *  END OF THE TRACK, which WRAPS): riding forward into a stalled
     *  range jumps to the NEXT piece's start — or, when the stall
     *  sits in the last run, back to the ride's beginning, exactly
     *  like riding off the end. Riding backward from beyond lands on
     *  the last solid sample and continues. */
    double povClampStall(double t, double dir) {
        if (stallRanges.isEmpty() || app.lastSamples == null
                || app.lastSamples.isEmpty()) return t;
        int idx = (int) FvdDesigner.clamp(t / FvdDesigner.INTEGRATE_DT,
            0, app.lastSamples.size() - 1);
        for (int[] r : stallRanges) {
            // forward travel wraps the INSTANT the speed reads zero
            // (v18.23, D194 — user spec): ON the first stalled
            // sample, or in the gap just before it once the
            // interpolated true speed dies — the old idx > r[0]
            // gate made the ride crawl a full design-sample past
            // the zero point at the floored ratio (whole seconds
            // of frozen POV on a drifted section) first.
            boolean inside = dir > 0 ? idx >= r[0] && idx <= r[1]
                                     : idx > r[0] && idx <= r[1];
            boolean atZero = dir > 0 && idx == r[0] - 1
                && app.trueV != null && app.trueVAt(t) <= 5e-4;
            if (!inside && !atZero) continue;
            if (dir > 0) {
                return r[1] + 1 < app.lastSamples.size()
                    ? app.lastSamples.get(r[1] + 1).t()
                    : app.lastSamples.get(0).t();   // end-of-track wrap
            }
            return app.lastSamples.get(r[0]).t();
        }
        return t;
    }

    /** Resolve each section's speed controls to GLOBAL ARC ranges
     *  (the plates' geometry; TIME-domain positions map through the
     *  section's own samples). */
    private void computePlateRanges(List<FVDIntegrator.Sample> samples,
                                    List<TrackCompiler.Span> spans) {
        plateRanges.clear();
        int flat = 0;
        for (TrackPiece piece : app.coaster.pieces) {
            for (Section sec : piece.sections) {
                int idx = flat++;
                if (!sec.speedControllable()
                        || sec.speedControls().isEmpty()
                        || idx >= spans.size()) continue;
                TrackCompiler.Span sp = spans.get(idx);
                boolean nd = sec.domain() == Section.Domain.DISTANCE;
                for (SpeedControl c : sec.speedControls()) {
                    double a, b;
                    if (nd) {
                        a = sp.s0() + c.x0;
                        b = sp.s0() + c.x0 + c.len;
                    } else {
                        a = arcAtTime(samples, sp, c.x0);
                        b = arcAtTime(samples, sp, c.x0 + c.len);
                    }
                    if (b - a > 0.05) {
                        plateRanges.add(new double[]{ a, b, c.type });
                    }
                }
            }
        }
    }

    /** Global arc at section-local TIME lt (linear between samples). */
    private static double arcAtTime(List<FVDIntegrator.Sample> samples,
                                    TrackCompiler.Span sp, double lt) {
        double t = sp.t0() + lt;
        int lo = sp.i0(), hi = Math.min(sp.i1(), samples.size() - 1);
        if (t <= samples.get(lo).t()) return samples.get(lo).s();
        if (t >= samples.get(hi).t()) return samples.get(hi).s();
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).t() <= t) lo = mid; else hi = mid;
        }
        double u = (t - samples.get(lo).t())
            / Math.max(1e-12, samples.get(hi).t() - samples.get(lo).t());
        return samples.get(lo).s()
            + u * (samples.get(hi).s() - samples.get(lo).s());
    }

    /** Flat plates down the middle of the ties over each controlled
     *  stretch inside this sublist (v18.0, D160): a thin ribbon at
     *  tie level, green for speed controls, blue for acceleration —
     *  the friction-wheel/booster read. */
    private List<MeshView> buildPlateMeshes(List<FVDIntegrator.Sample> sub) {
        List<MeshView> out = new ArrayList<>();
        if (sub.size() < 2 || plateRanges.isEmpty()) return out;
        double sA = sub.get(0).s(), sB = sub.get(sub.size() - 1).s();
        final double HALF_W = 0.16;   // m, plate half-width along R
        final double LIFT = 0.045;    // m above the spine (tie tops)
        for (double[] pr : plateRanges) {
            double a = Math.max(pr[0], sA), b = Math.min(pr[1], sB);
            if (b - a < 0.05) continue;
            TriangleMesh mesh = new TriangleMesh();
            mesh.getTexCoords().addAll(0, 0);
            int v = 0, lo = 0;
            int steps = Math.max(2, (int) Math.ceil((b - a) / 0.6));
            Vec3 prev = null;
            for (int k = 0; k <= steps; k++) {
                double S = a + (b - a) * k / steps;
                ArcHit h = arcHit(sub, lo, S);
                lo = h.lo();
                Vec3 center = h.pos().sub(h.U().scale(app.heartOffset))
                    .add(h.U().scale(LIFT));
                if (prev != null && center.sub(prev).length()
                        > MESH_GAP_BREAK) {
                    break;   // piece gap safety
                }
                prev = center;
                Vec3 l = center.sub(h.R().scale(HALF_W));
                Vec3 r = center.add(h.R().scale(HALF_W));
                mesh.getPoints().addAll(
                    (float) l.x(), (float) -l.y(), (float) l.z(),
                    (float) r.x(), (float) -r.y(), (float) r.z());
                if (k > 0) {
                    int p0 = v - 2, p1 = v - 1, p2 = v, p3 = v + 1;
                    mesh.getFaces().addAll(p0, 0, p2, 0, p1, 0);
                    mesh.getFaces().addAll(p1, 0, p2, 0, p3, 0);
                }
                v += 2;
            }
            MeshView mv = new MeshView(mesh);
            Color col = pr[2] == SpeedControl.ACCEL
                ? Color.rgb(90, 160, 255) : Color.rgb(100, 220, 110);
            PhongMaterial mat = new PhongMaterial(col);
            WritableImage glow = new WritableImage(1, 1);
            glow.getPixelWriter().setColor(0, 0,
                col.deriveColor(0, 1, 0.55, 1));
            mat.setSelfIlluminationMap(glow);   // readable in shadow
            mv.setMaterial(mat);
            mv.setCullFace(CullFace.NONE);
            out.add(mv);
        }
        return out;
    }

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

    /** arcHit's twin over RIDE TIME (v17.16, D157 — the time comb's
     *  ticks lerp by t between bracketing samples, so the s/c
     *  sub-sample phase never quantizes them). */
    private static ArcHit timeHit(List<FVDIntegrator.Sample> samples,
                                  int loHint, double t) {
        int j = Math.max(0, loHint);
        while (j + 1 < samples.size() - 1 && samples.get(j + 1).t() < t) j++;
        FVDIntegrator.Sample a = samples.get(j);
        FVDIntegrator.Sample b = samples.get(Math.min(j + 1, samples.size() - 1));
        double span = b.t() - a.t();
        double u = span < 1e-12 ? 0
            : FvdDesigner.clamp((t - a.t()) / span, 0, 1);
        Vec3 pos = vLerp(a.pos(), b.pos(), u);
        Vec3 T = vLerp(a.T(), b.T(), u).normalize();
        Vec3 U = vLerp(a.U(), b.U(), u).normalize();
        return new ArcHit(j, u, pos, T, U, U.cross(T));
    }

    private static Vec3 vLerp(Vec3 a, Vec3 b, double u) {
        return a.add(b.sub(a).scale(u));
    }

    /** Arc length at ride time t within a sublist (lerped — the time
     *  comb's density measure, v18.0 D164). */
    private static double arcAtT(List<FVDIntegrator.Sample> samples,
                                 double t) {
        int lo = 0, hi = samples.size() - 1;
        if (t <= samples.get(0).t()) return samples.get(0).s();
        if (t >= samples.get(hi).t()) return samples.get(hi).s();
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).t() <= t) lo = mid; else hi = mid;
        }
        double u = (t - samples.get(lo).t())
            / Math.max(1e-12, samples.get(hi).t() - samples.get(lo).t());
        return samples.get(lo).s()
            + u * (samples.get(hi).s() - samples.get(lo).s());
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
                buildTieMeshes(tp.samples(), tp.active(), tp.base(), hi,
                    tp.ghost()));
        }
    }

    /** Cross ties: one thin box between the rails every `tieSpacing`
     *  metres of track — at EXACT arc positions since v16.14 (frames
     *  lerped between samples via arcHit; the old quantize-to-sample
     *  placement wobbled visibly). Ties whose nearest global sample
     *  falls in the ACTIVE section's span build into a second, WHITE
     *  mesh (v16.11 — the selected-section cue). */
    private List<MeshView> buildTieMeshes(List<FVDIntegrator.Sample> samples,
                                          boolean active, int base, int[] hi,
                                          boolean ghost) {
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
            PhongMaterial mat = new PhongMaterial(ghost
                ? Color.rgb(120, 124, 134, 0.15)   // unreachable (v18.0)
                : active ? Color.rgb(70, 74, 84) : Color.rgb(52, 55, 62));
            mat.setSpecularColor(ghost ? Color.rgb(30, 30, 32)
                : active ? Color.rgb(90, 90, 90) : Color.rgb(60, 60, 64));
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
    // Perpendicular teeth along the track, FVD++-style — FIVE
    // exclusive modes (v17.16, D157): the G-FORCE comb = VERTICAL
    // felt G (outward along −U, colored by the g-force gradient) +
    // LATERAL felt G (rider-LEFT for +Gl since v16.14, its own
    // mirrored-absolute gradient) as one unit; the SPEED comb
    // (upward along U, GREEN); the GEOMETRIC comb (solid orange
    // curvature °/m pointing OUTWARD from the curve — sub-modes
    // Local = body pitch+yaw, Global = elevation+heading rates,
    // Combined = one tooth along the curve normal); the TIME comb
    // (0.1 s ticks in the old speed blue, whole seconds WHITE).
    // Teeth originate at the HEARTLINE, sit at EXACT arc/time
    // positions (arcHit/timeHit), scale by the View menu's percent
    // setting, hide in POV, and are mouse-transparent (they block
    // nothing — hover works in screen space). Force/speed values
    // from the TRUE arrays; curvature straight off the frames.
    // Hovering anywhere on a tooth shows its value bottom-left.

    static final double COMB_SPACING = 0.5;    // m of track per tooth
    static final double COMB_VG_SCALE = 0.5;   // m per g (vertical)
    static final double COMB_LG_SCALE = 0.8;   // m per g (lateral)
    static final double COMB_V_SCALE = 0.05;   // m per m/s (speed)
    static final double COMB_K_SCALE = 0.5;    // m per °/m (geometric)
    static final double COMB_TIME_STEP = 0.1;  // s of ride per time tooth
    static final double COMB_TIME_LEN = 0.5;   // m — time tooth height
    static final double COMB_MAX = 3.5;        // length cap (m, at 100%)

    // tooth KINDS: 0 vert G, 1 lat G, 2 speed (GREEN since v17.16),
    // 3 geo local pitch, 4 geo local yaw, 5 geo global elevation,
    // 6 geo global heading, 7 geo combined curvature (all SOLID
    // ORANGE, values °/m, teeth pointing OUTWARD from the curve),
    // 8 time ticks (the old speed blue), 9 whole-second ticks (WHITE)

    private record CombPt(Vec3 base, Vec3 tip, double value, int kind) { }
    private final List<CombPt> combPoints = new ArrayList<>();
    Group combGroup;   // all comb meshes; hidden while riding POV
    final Label combLabel = new Label();   // bottom-left readout (v16.14)

    // AXES REFERENCE (v18.7, D176 — user spec): a bottom-right mini
    // compass showing the WORLD X/Y/Z directions under the CURRENT
    // view, gizmo-arrow colors, synced by its own lightweight timer
    // (redraws only when the camera rotation actually changes)
    final Canvas axesCanvas = new Canvas(96, 96);
    private double[] axesSig = new double[9];
    private boolean axesStarted = false;

    private AnimationTimer axesTimer;

    void startAxesRef() {
        if (axesStarted) return;
        axesStarted = true;
        axesCanvas.setMouseTransparent(true);
        axesTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                syncOrthoCam();   // true-ortho camera follows the rig
                syncGizmoCam();   // overlay camera mirrors it (D188)
                updateAxesRef();
                syncGroundLine();
                drawOrthoGrid();
            }
        };
        axesTimer.start();
    }

    // NL2-STYLE ORTHO GRID OVERLAY (v18.10, user spec): adaptive
    // world-aligned lines for the Top and Side views — top = world
    // X/Z, side = height (world Y) + the view-plane horizontal axis;
    // the TANGENT side's horizontal origin sits at the anchored
    // selection point (its axis isn't a global one). Redrawn only
    // when the camera signature moves.
    final Canvas orthoGridCanvas = new Canvas(1, 1);
    private double[] gridSig = new double[9];

    private void drawOrthoGrid() {
        boolean show = orthoView && !povActive && !flyActive;
        if (!show) {
            if (orthoGridCanvas.getWidth() > 1) {
                orthoGridCanvas.setWidth(1);
                orthoGridCanvas.setHeight(1);
                gridSig = new double[9];
            }
            return;
        }
        double w = Math.max(1, sub.getWidth()), h = Math.max(1, sub.getHeight());
        double upp = orthoUpp();
        double yaw = Math.toRadians(camYaw.getAngle());
        double[] sig = { w, h, upp, yaw, camPivot.getX(), camPivot.getY(),
                         camPivot.getZ(), orthoTop ? 1 : 0,
                         orthoSideMode == 4 && !orthoTop ? tangAnchorX : 0,
                         gridCamX0, gridCamZ0 };
        if (java.util.Arrays.equals(sig, gridSig)) return;
        gridSig = sig;
        orthoGridCanvas.setWidth(w);
        orthoGridCanvas.setHeight(h);
        GraphicsContext g = orthoGridCanvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        // POWERS OF TEN with 1 m squares as the floor (v18.11, user
        // spec — was a 1-2-5 series): step up ×10 while the minors
        // would crowd under ~7 px
        double minor = 1;
        while (minor / upp < 7) minor *= 10;
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        // camera basis in scene coords (pitch 0 for side, −90 for top)
        double[] right = { cy, 0, -sy };
        double[] down = orthoTop ? new double[]{ -sy, 0, -cy }
                                 : new double[]{ 0, 1, 0 };
        double[] piv = { camPivot.getX(), camPivot.getY(), camPivot.getZ() };
        // WORLD alignment (v18.15, D186): the pivot lives in SCENE
        // coords, but the world group carries an XZ centering
        // translate (scene + gridCamX0/Z0 = world) — measuring the
        // families from scene 0 put the X/Z lines off the ground
        // grid by that offset (height was fine: Y never translates,
        // his "aligned vertically, not horizontally"). Each family's
        // origin scalar now sits on the WORLD origin.
        if (orthoTop) {
            gridFamily(g, new double[]{ 1, 0, 0 }, -gridCamX0, piv, right,
                down, upp, w, h, minor);
            gridFamily(g, new double[]{ 0, 0, 1 }, -gridCamZ0, piv, right,
                down, upp, w, h, minor);
        } else {
            // horizontal axis = the view plane's right direction; the
            // tangent sub-mode measures it from the anchored node
            // (a scene point — no world offset there)
            double o = orthoSideMode == 4
                ? tangAnchorX * right[0] + tangAnchorZ * right[2]
                : -(gridCamX0 * right[0] + gridCamZ0 * right[2]);
            gridFamily(g, right, o, piv, right, down, upp, w, h, minor);
            // height lines (scene y; world y = −scene y, same spacing)
            gridFamily(g, new double[]{ 0, 1, 0 }, 0, piv, right, down,
                upp, w, h, minor);
        }
    }

    /** One line family: planes of constant coordinate along `axis`
     *  (scene unit vector, parallel to a screen axis in every ortho
     *  mode), measured from origin scalar o. */
    private static void gridFamily(GraphicsContext g, double[] axis,
            double o, double[] piv, double[] right, double[] down,
            double upp, double w, double h, double minor) {
        double dR = axis[0] * right[0] + axis[1] * right[1] + axis[2] * right[2];
        double dD = axis[0] * down[0] + axis[1] * down[1] + axis[2] * down[2];
        double pc = axis[0] * piv[0] + axis[1] * piv[1] + axis[2] * piv[2] - o;
        boolean vertical = Math.abs(dR) > Math.abs(dD);
        double dir = vertical ? (dR > 0 ? 1 : -1) : (dD > 0 ? 1 : -1);
        double span = (vertical ? w : h) * upp / 2 + minor;
        int k0 = (int) Math.floor((pc - span) / minor);
        int k1 = (int) Math.ceil((pc + span) / minor);
        for (int k = k0; k <= k1; k++) {
            boolean major = Math.floorMod(k, 10) == 0;
            g.setStroke(Color.rgb(150, 160, 185, major ? 0.28 : 0.13));
            g.setLineWidth(1);
            double off = (k * minor - pc) * dir / upp;
            if (vertical) {
                double x = w / 2 + off;
                if (x >= 0 && x <= w) g.strokeLine(x, 0, x, h);
            } else {
                double y = h / 2 + off;
                if (y >= 0 && y <= h) g.strokeLine(0, y, w, y);
            }
        }
    }


    // SIDE-VIEW GROUND LINE (v18.9, user spec): the true-ortho side
    // view sees the y=0 ground plane edge-on (zero pixels) — a subtle
    // horizontal overlay line marks it. World y=0 == scene y=0 (the
    // world group's centering translate is XZ-only), and with pitch 0
    // the plane projects to an exactly horizontal line.
    final javafx.scene.shape.Line groundLine = new javafx.scene.shape.Line();

    // FULL-PARITY PANES (v18.10, D181): each Viewport3D assembles its
    // OWN StackPane — SubScene + every overlay (labels, compass,
    // ground line, comb/pov readouts). FvdDesigner keeps the panes in
    // a horizontal SplitPane and re-parents the single graph-collapse
    // button into the rightmost one.
    StackPane pane;

    StackPane buildPane() {
        pane = new StackPane(sub);
        sub.widthProperty().bind(pane.widthProperty());
        sub.heightProperty().bind(pane.heightProperty());
        // the gizmo overlay renders right above the main SubScene
        // (v18.17, D188); empty regions pass mouse events through
        gizmoSub.widthProperty().bind(pane.widthProperty());
        gizmoSub.heightProperty().bind(pane.heightProperty());
        pane.getChildren().add(gizmoSub);
        pane.setMinSize(0, 0);
        // the compass STAYS PUT (v18.11, user spec — the v18.8 dodge
        // is gone): bottom-left readouts simply draw OVER it (added
        // after = higher in the StackPane z-order)
        StackPane.setAlignment(axesCanvas, Pos.BOTTOM_LEFT);
        StackPane.setMargin(axesCanvas, new Insets(6));
        pane.getChildren().add(axesCanvas);
        StackPane.setAlignment(combLabel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(combLabel, new Insets(8));
        pane.getChildren().add(combLabel);
        StackPane.setAlignment(povLabel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(povLabel, new Insets(8));
        pane.getChildren().add(povLabel);
        initViewModeLabel();
        StackPane.setAlignment(viewModeLabel, Pos.TOP_LEFT);
        StackPane.setMargin(viewModeLabel, new Insets(8));
        pane.getChildren().add(viewModeLabel);
        // (the Side sub-dropdown is GONE, v18.16 D187 — the main list
        // carries the side modes; the tangent tools move up)
        StackPane.setAlignment(tangBtnRow, Pos.TOP_LEFT);
        StackPane.setMargin(tangBtnRow, new Insets(34, 8, 8, 8));
        pane.getChildren().add(tangBtnRow);
        // gizmo axes toggle: top-right, left of the collapse arrow,
        // visible only while a rig is revealed (v18.16, D187)
        gizmoAxesBtn.setStyle(OVERLAY_LABEL_STYLE);
        gizmoAxesBtn.setCursor(Cursor.HAND);
        gizmoAxesBtn.setVisible(false);
        gizmoAxesBtn.setManaged(false);
        gizmoAxesBtn.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            app.setGizmoAxesLocal(!app.settings.gizmoLocal());
            e.consume();
        });
        StackPane.setAlignment(gizmoAxesBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(gizmoAxesBtn, new Insets(8, 40, 8, 8));
        pane.getChildren().add(gizmoAxesBtn);
        initGroundLine(groundLine);
        pane.getChildren().add(groundLine);
        // NL2-STYLE ORTHO GRID (v18.10, user spec): a world-aligned
        // 2D overlay for Top/Side views, behind the labels
        orthoGridCanvas.setMouseTransparent(true);
        pane.getChildren().add(1, orthoGridCanvas);
        // key routing: the pane under the mouse owns the view keys
        // until another pane captures (keyViewport); the digit view
        // keys also need to know the mouse is over a VIEW right now
        // (v18.11 — 1/2/3 stay lane switches over the graph)
        sub.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
            app.hoverViewport = this;
            app.mouseOverView = true;
        });
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            app.hoverViewport = this;
            app.mouseOverView = true;
        });
        sub.addEventHandler(MouseEvent.MOUSE_EXITED,
            e -> app.mouseOverView = false);
        return pane;
    }

    /** Drop any mouse capture this pane holds (fly, captured POV) —
     *  another pane is taking it, or the pane is going away. */
    void releaseCapture() {
        if (flyActive) toggleFly();
        if (povActive && povCaptured) {
            povCaptured = false;
            sub.setCursor(Cursor.DEFAULT);
            syncPovLabel();   // the "(A)" hint is capture-only
        }
    }

    /** Stop this pane's timers before removal (v18.10). */
    void shutdown() {
        if (povTimer != null) povTimer.stop();
        if (flyTimer != null) flyTimer.stop();
        if (axesTimer != null) axesTimer.stop();
    }

    void initGroundLine(javafx.scene.shape.Line line) {
        line.setStroke(Color.rgb(150, 160, 180, 0.6));
        line.setStrokeWidth(1.5);
        line.setMouseTransparent(true);
        line.setManaged(false);
        line.setVisible(false);
    }

    private void syncGroundLine() {
        syncGroundLineFor(groundLine, sub, root3D,
            orthoView && !orthoTop && !povActive && !flyActive, camPivot);
    }

    /** Shared by the primary pane and the mini views: project the
     *  ground under the pivot to a full-width horizontal line in the
     *  line's parent coordinates. */
    private static void syncGroundLineFor(javafx.scene.shape.Line line,
            SubScene s, Group root, boolean show, Translate pivot) {
        if (!show || line.getParent() == null || root == null
                || root.getScene() == null) {
            line.setVisible(false);
            return;
        }
        Point2D scr = root.localToScreen(pivot.getX(), 0, pivot.getZ());
        if (scr == null) {
            line.setVisible(false);
            return;
        }
        Point2D loc = line.getParent().screenToLocal(scr);
        if (loc == null) {
            line.setVisible(false);
            return;
        }
        line.setStartX(0);
        line.setEndX(Math.max(1, s.getWidth()));
        line.setStartY(loc.getY());
        line.setEndY(loc.getY());
        line.setVisible(true);
    }

    private void updateAxesRef() {
        javafx.scene.Camera cam = sub.getCamera();
        if (cam == null) return;
        javafx.scene.transform.Transform tr = cam.getLocalToSceneTransform();
        double[] m = { tr.getMxx(), tr.getMxy(), tr.getMxz(),
                       tr.getMyx(), tr.getMyy(), tr.getMyz(),
                       tr.getMzx(), tr.getMzy(), tr.getMzz() };
        boolean same = true;
        for (int i = 0; i < 9; i++) {
            if (Math.abs(m[i] - axesSig[i]) > 1e-9) {
                same = false;
                break;
            }
        }
        if (same) return;
        axesSig = m;
        // camera basis in SCENE coords = the rotation's columns —
        // NORMALIZED (v18.8: the true-ortho camera carries a Scale,
        // whose raw columns would shrink the compass)
        Vec3 camX = new Vec3(m[0], m[3], m[6]).normalize();
        Vec3 camY = new Vec3(m[1], m[4], m[7]).normalize();
        Vec3 camZ = new Vec3(m[2], m[5], m[8]).normalize();
        // world axes in scene coords (the Y-flip discipline: world
        // +Y up = scene −Y); screen-right = ·camX, screen-down = ·camY
        Vec3[] axes = { new Vec3(1, 0, 0), new Vec3(0, -1, 0),
                        new Vec3(0, 0, 1) };
        Color[] cols = { GIZ_X, GIZ_Y, GIZ_Z };
        String[] names = { "X", "Y", "Z" };
        GraphicsContext gc = axesCanvas.getGraphicsContext2D();
        double w = axesCanvas.getWidth(), h = axesCanvas.getHeight();
        gc.clearRect(0, 0, w, h);
        double cx = w / 2, cy = h / 2, len = 30;
        // back-facing axes draw first (and dimmer) so the ones
        // pointing at the viewer overlay them
        Integer[] order = { 0, 1, 2 };
        java.util.Arrays.sort(order, (a, b) -> Double.compare(
            axes[b].dot(camZ), axes[a].dot(camZ)));
        gc.setFont(Font.font(11));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (int k : order) {
            double dx = axes[k].dot(camX), dy = axes[k].dot(camY);
            boolean away = axes[k].dot(camZ) > 0;   // into the screen
            gc.setGlobalAlpha(away ? 0.45 : 1.0);
            gc.setStroke(cols[k]);
            gc.setLineWidth(2);
            gc.strokeLine(cx, cy, cx + dx * len, cy + dy * len);
            gc.setFill(cols[k]);
            gc.fillText(names[k], cx + dx * (len + 9), cy + dy * (len + 9));
        }
        gc.setGlobalAlpha(1.0);
        gc.setFill(Color.rgb(200, 205, 215));
        gc.fillOval(cx - 2, cy - 2, 4, 4);
    }
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
     *  reversed (v16.16). The "(A)" key hint only applies while
     *  CAPTURED (v18.11, user spec — A does nothing hands-off). */
    void syncPovLabel() {
        povLabel.setText(povCaptured ? "Backwards POV (A)"
                                     : "Backwards POV");
        povLabel.setVisible(povActive && povReversed);
    }

    // VIEW-MODE LABEL (v18.8, user spec): top-left overlay naming the
    // current camera — "Fly/Orbit" | "POV" | "Top" | "Side" — and a
    // click-to-switch list. Fly and orbit share one entry (they share
    // the perspective lens and hand off seamlessly).
    final Label viewModeLabel = new Label("Fly/Orbit ▾");
    private ContextMenu viewModeMenu;

    static final String OVERLAY_LABEL_STYLE =
        "-fx-background-color: rgba(45,50,62,0.75);"
        + " -fx-text-fill: rgb(210,215,225); -fx-padding: 3 8 3 8;"
        + " -fx-font-size: 11px; -fx-background-radius: 4;";
    static final String[] SIDE_MODE_NAMES =
        { "Front", "Back", "Right", "Left", "Tangent to Selection" };

    void initViewModeLabel() {
        viewModeLabel.setStyle(OVERLAY_LABEL_STYLE);
        viewModeLabel.setCursor(Cursor.HAND);
        viewModeLabel.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (viewModeMenu != null) viewModeMenu.hide();
            viewModeMenu = new ContextMenu();
            // ONE flat list since v18.16 (D187, user spec — the
            // "Side" sub-dropdown is gone): modes 3-7 are the five
            // side sub-modes directly
            int cur = currentViewIdx();
            String[] names = { "Fly/Orbit", "POV", "Top", "Front",
                "Back", "Right", "Left", "Tangent to Selection" };
            for (int m = 0; m < names.length; m++) {
                MenuItem it = new MenuItem(
                    (m == cur ? "● " : "   ") + names[m]);
                final int mode = m;
                it.setOnAction(ev -> switchViewMode(mode));
                viewModeMenu.getItems().add(it);
            }
            // ADD/REMOVE VIEW (v18.9; FULL-PARITY panes v18.10 D181):
            // Remove drops THIS pane — every pane is equal
            viewModeMenu.getItems().add(new SeparatorMenuItem());
            MenuItem add = new MenuItem("   Add View");
            add.setOnAction(ev -> app.addViewPane(this));
            viewModeMenu.getItems().add(add);
            if (app.viewports.size() > 1) {
                MenuItem rem = new MenuItem("   Remove View");
                rem.setOnAction(ev -> app.removeViewPane(this));
                viewModeMenu.getItems().add(rem);
            }
            viewModeMenu.show(viewModeLabel, Side.BOTTOM, 0, 4);
            e.consume();
        });
        // TANGENT tools (v18.10, user spec): under the view label
        // while Tangent is live — ↻ re-tangents to the
        // CURRENT selection (and re-anchors the grid); ↔ spins the
        // view 180° in place (same pivot, same grid, other side)
        tangRefreshBtn.setStyle(OVERLAY_LABEL_STYLE);
        tangRefreshBtn.setGraphic(FvdDesigner.circleArrowIcon(
            13, true, Color.rgb(210, 215, 225)));   // v18.11 round icon
        tangRefreshBtn.setCursor(Cursor.HAND);
        Tooltip.install(tangRefreshBtn, new Tooltip("Refresh"));
        tangRefreshBtn.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (orthoView && !orthoTop && orthoSideMode == 4) {
                applySideMode();
            }
            e.consume();
        });
        tangReverseBtn.setStyle(OVERLAY_LABEL_STYLE);
        tangReverseBtn.setGraphic(FvdDesigner.doubleArrowIcon(
            13, Color.rgb(210, 215, 225)));   // matches the refresh icon
        tangReverseBtn.setCursor(Cursor.HAND);
        Tooltip.install(tangReverseBtn, new Tooltip("Reverse View"));
        tangReverseBtn.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (orthoView && !orthoTop) {
                camYaw.setAngle(camYaw.getAngle() + 180);
            }
            e.consume();
        });
        tangBtnRow.getChildren().addAll(tangRefreshBtn, tangReverseBtn);
        tangBtnRow.setVisible(false);
        tangBtnRow.setManaged(false);
        tangBtnRow.setPickOnBounds(false);
    }

    final Label tangRefreshBtn = new Label();
    final Label tangReverseBtn = new Label();
    final HBox tangBtnRow = new HBox(6);
    // top-right gizmo axes toggle (v18.16, D187 — back from the
    // v18.10 removal; shows only while a rig is revealed)
    final Label gizmoAxesBtn = new Label();

    /** The label list's index of the live view (v18.16, D187 — one
     *  flat list): 0 fly/orbit, 1 POV, 2 Top, 3-7 the side modes. */
    private int currentViewIdx() {
        return povActive ? 1
            : orthoView ? (orthoTop ? 2 : 3 + orthoSideMode)
            : 0;
    }

    private String currentViewName() {
        if (povActive) return "POV";
        if (!orthoView) return "Fly/Orbit";
        if (orthoTop) return "Top";
        // the label wears the short form; the list says it in full
        return orthoSideMode == 4 ? "Tangent"
            : SIDE_MODE_NAMES[orthoSideMode];
    }

    /** Keep the top-left label naming the live camera — called from
     *  every mode transition (fly↔orbit share the label text). */
    void syncViewMode() {
        viewModeLabel.setText(currentViewName() + " ▾");
        boolean side = orthoView && !orthoTop && !povActive && !flyActive;
        boolean tang = side && orthoSideMode == 4;
        tangBtnRow.setVisible(tang);
        tangBtnRow.setManaged(tang);
    }

    /** Switch to a view mode from the label's flat list (0 =
     *  fly/orbit, 1 = POV, 2 = top, 3-7 = Front/Back/Right/Left/
     *  Tangent, v18.16 D187) — routes through the exact same toggles
     *  the keys use, releasing capture/ortho state first so each
     *  transition lands where the picker says. */
    void switchViewMode(int mode) {
        switch (mode) {
            case 0 -> {
                if (povActive || flyActive) {
                    exitRideModes();
                } else if (orthoView) {
                    // the restore path: saved perspective pose
                    camYaw.setAngle(savedYaw);
                    camPitch.setAngle(savedPitch);
                    camZoom.setZ(savedZoom);
                    exitOrthoLens();
                }
            }
            case 1 -> {
                if (!povActive) togglePov();   // handles fly/ortho itself
            }
            case 2 -> {
                if (orthoView && orthoTop) return;   // already there
                exitRideModes();
                orthoEnter(true);
            }
            default -> {
                int m = mode - 3;
                if (orthoView && !orthoTop && orthoSideMode == m
                        && !povActive && !flyActive) {
                    return;   // already there (Tangent re-picks via ↻)
                }
                exitRideModes();
                setOrthoSideMode(m);   // enters/re-aims the side view
            }
        }
        syncViewMode();
    }

    /** Leave POV/fly cleanly (capture released) — shared by the
     *  label switcher and the 1-6 view keys (v18.11). */
    void exitRideModes() {
        if (povActive) {
            povCaptured = false;   // plain exit, not the fly flip
            sub.setCursor(Cursor.DEFAULT);
            togglePov();
        }
        if (flyActive) toggleFly();
    }

    /** The 5 key: enter the TOP ortho view (v18.11). */
    void orthoEnterTop() {
        orthoEnter(true);
    }

    /** One comb's teeth over a piece's samples (base = the sublist's
     *  global start index — the TRUE arrays are ride-global). */
    private MeshView buildCombMesh(List<FVDIntegrator.Sample> samples,
                                   int base, int kind) {
        TriangleMesh mesh = new TriangleMesh();
        int v = 0, tc = 0;
        double scale = FvdDesigner.clamp(
            app.settings.combScale(), 10, 500) / 100.0;
        if (samples.size() < 2) return new MeshView(mesh);
        int lo = 0;
        if (kind == 9 || kind == 10) {
            // TIME comb, WHOLE-SECOND ticks (v17.16, D157; recolored
            // v18.1, D168 — user spec): exactly on every integer
            // second, 1.5× the filler height, DEEP BLUE — and every
            // 10th second (10, 20, …) RED (kind 10's mesh)
            double t0 = samples.get(0).t();
            double tEnd = samples.get(samples.size() - 1).t();
            for (long k = (long) Math.ceil(t0 - 1e-9);
                    k <= tEnd + 1e-9; k++) {
                // every FIFTH second red (v18.2 — was every 10th)
                boolean red5 = k != 0 && k % 5 == 0;
                if (red5 != (kind == 10)) continue;
                double t = k;
                ArcHit h = timeHit(samples, lo, t);
                lo = h.lo();
                Vec3 root = h.pos();
                Vec3 tip = root.add(h.U().scale(
                    COMB_TIME_LEN * 1.5 * scale));
                combPoints.add(new CombPt(root, tip, t, kind));
                mesh.getTexCoords().addAll(0.5f, 0.5f);
                v = addToothBox(mesh, root, tip, h.T(), h.U(), h.U(),
                    v, tc++);
            }
        } else if (kind == 8) {
            // TIME comb FILLERS (v18.0, D164 — user spec): between
            // consecutive whole seconds, N evenly TIME-spaced ticks
            // with N picked so the tooth density roughly matches the
            // other combs' 0.5 m arc spacing (the fixed 0.1 s grid
            // was sparse at speed and dense in a crawl); the
            // whole-second ticks above stay exactly where they were.
            double t0 = samples.get(0).t();
            double tEnd = samples.get(samples.size() - 1).t();
            for (long k = (long) Math.floor(t0 + 1e-9);
                    k < tEnd - 1e-9; k++) {
                double sa = arcAtT(samples, Math.max(k, t0));
                double sb = arcAtT(samples, Math.min(k + 1, tEnd));
                double frac = Math.min(k + 1, tEnd) - Math.max(k, t0);
                double secArc = frac > 1e-9 ? (sb - sa) / frac : 0;
                int nFill = (int) FvdDesigner.clamp(
                    Math.round(secArc / COMB_SPACING) - 1, 0, 99);
                for (int j = 1; j <= nFill; j++) {
                    double t = k + (double) j / (nFill + 1);
                    if (t < t0 - 1e-9 || t > tEnd + 1e-9) continue;
                    ArcHit h = timeHit(samples, lo, t);
                    lo = h.lo();
                    Vec3 root = h.pos();
                    Vec3 tip = root.add(h.U().scale(COMB_TIME_LEN * scale));
                    combPoints.add(new CombPt(root, tip, t, kind));
                    mesh.getTexCoords().addAll(0.5f, 0.5f);
                    v = addToothBox(mesh, root, tip, h.T(), h.U(), h.U(),
                        v, tc++);
                }
            }
        } else {
        double s0 = samples.get(0).s();
        double sEnd = samples.get(samples.size() - 1).s();
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
                dir = h.R();   // rider-RIGHT for +Gl (v18.13, D184)
                len = val * COMB_LG_SCALE;
            } else if (kind == 2) {
                val = app.trueV[gi]
                    + (app.trueV[gj] - app.trueV[gi]) * h.frac();
                dir = h.U();
                len = val * COMB_V_SCALE;
            } else {
                // GEOMETRIC comb (v17.16, D157): curvature per METER
                // straight off the bracketing frames (dT/ds ⊥ T),
                // teeth pointing OUTWARD from the curve — away from
                // its center, the −U-for-positive-G convention the
                // G comb set. Values °/m; roll deliberately excluded
                // (the user's spec: pitch and yaw).
                int ja = h.lo();
                int jb = Math.min(ja + 1, samples.size() - 1);
                while (jb + 1 < samples.size()
                        && samples.get(jb).s() - samples.get(ja).s()
                           < 1e-4) {
                    jb++;   // D147 landing runts: widen past them
                }
                double ds = samples.get(jb).s() - samples.get(ja).s();
                if (ds < 1e-9) continue;
                Vec3 Ta = samples.get(ja).T(), Tb = samples.get(jb).T();
                Vec3 kv = Tb.sub(Ta).scale(1 / ds);
                kv = kv.sub(h.T().scale(kv.dot(h.T())));
                if (kind == 3) {          // LOCAL pitch (κ·U, about R)
                    val = Math.toDegrees(kv.dot(h.U()));
                    dir = h.U().scale(-1);
                } else if (kind == 4) {   // LOCAL yaw (κ·R, about U)
                    val = Math.toDegrees(kv.dot(h.R()));
                    dir = h.R().scale(-1);
                } else if (kind == 5) {   // GLOBAL elevation rate
                    Vec3 vup = new Vec3(-h.T().x() * h.T().y(),
                        1 - h.T().y() * h.T().y(),
                        -h.T().z() * h.T().y());
                    double vl = vup.length();
                    if (vl < 1e-6) continue;   // vertical: undefined
                    val = Math.toDegrees(
                        Math.asin(FvdDesigner.clamp(Tb.y(), -1, 1))
                        - Math.asin(FvdDesigner.clamp(Ta.y(), -1, 1)))
                        / ds;
                    dir = vup.scale(-1 / vl);
                } else if (kind == 6) {   // GLOBAL heading rate
                    double ha = Ta.x() * Ta.x() + Ta.z() * Ta.z();
                    double hb = Tb.x() * Tb.x() + Tb.z() * Tb.z();
                    if (ha < 1e-12 || hb < 1e-12) continue;
                    double dpsi = Math.atan2(-Tb.z(), Tb.x())
                                - Math.atan2(-Ta.z(), Ta.x());
                    while (dpsi > Math.PI) dpsi -= 2 * Math.PI;
                    while (dpsi < -Math.PI) dpsi += 2 * Math.PI;
                    val = Math.toDegrees(dpsi) / ds;
                    // horizontal right of travel = outward for a
                    // LEFT (+ψ') turn
                    Vec3 hr = new Vec3(-h.T().z(), 0, h.T().x());
                    double hl = hr.length();
                    if (hl < 1e-6) continue;
                    dir = hr.scale(1 / hl);
                } else {                  // COMBINED |κ|, outward
                    double km = kv.length();
                    if (km < 1e-7) continue;
                    val = Math.toDegrees(km);
                    dir = kv.scale(-1 / km);
                }
                len = val * COMB_K_SCALE;
            }
            len = FvdDesigner.clamp(len * scale,
                -COMB_MAX * scale, COMB_MAX * scale);
            if (Math.abs(len) < 0.02) continue;
            // teeth grow from the HEARTLINE (v16.14 — was the spine)
            Vec3 root = h.pos();
            Vec3 tip = root.add(dir.scale(len));
            combPoints.add(new CombPt(root, tip, val, kind));
            // gradient u for this tooth (kinds ≥ 2 are solid: unused)
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
            v = addToothBox(mesh, root, tip, h.T(), dir, h.U(), v, tc++);
        }
        }
        MeshView mv = new MeshView(mesh);
        PhongMaterial mat = new PhongMaterial();
        if (kind == 0) {
            mat.setDiffuseMap(buildGForceGradient(false));
        } else if (kind == 1) {
            mat.setDiffuseMap(buildLatGradient());
        } else if (kind == 2) {
            // GREEN since v17.16 (user spec — the old light blue
            // moved to the time comb)
            mat.setDiffuseColor(Color.rgb(100, 220, 110));
        } else if (kind == 4 || kind == 6) {
            // YAW-family teeth (local yaw + global heading) = RED
            // (v17.17 re-spec; pitch/elevation keep the orange)
            mat.setDiffuseColor(Color.rgb(235, 60, 45));
        } else if (kind <= 7) {
            // pitch/elevation solid orange (the geo palette's roll);
            // COMBINED = the RED-ORANGE midpoint of the two
            mat.setDiffuseColor(kind == 7 ? Color.rgb(245, 115, 65)
                : FvdDesigner.GEO_LANE_COLORS[2]);
        } else if (kind == 8) {
            mat.setDiffuseColor(Color.rgb(150, 210, 255));
        } else if (kind == 9) {
            // whole seconds DEEP BLUE, decades RED (v18.1, D168 —
            // was white)
            mat.setDiffuseColor(Color.rgb(35, 70, 220));
        } else {
            mat.setDiffuseColor(Color.rgb(235, 60, 45));
        }
        mat.setSpecularColor(Color.rgb(90, 90, 95));
        mv.setMaterial(mat);
        mv.setCullFace(CullFace.NONE);
        // teeth block NOTHING (v16.14): clicks/double-clicks pass
        // through to the track; the hover readout is screen-space
        mv.setMouseTransparent(true);
        return mv;
    }

    /** One tooth's thin box (8 verts, 12 faces): cross-section spans
     *  HW_T along the track and HW along dir×T. Returns the new
     *  vertex count. (Extracted v17.16 — the time loop shares it.) */
    private static int addToothBox(TriangleMesh mesh, Vec3 a, Vec3 b,
                                   Vec3 T, Vec3 dir, Vec3 U,
                                   int v, int tc) {
        final double HW = 0.03;    // tooth half-thickness (⊥ tooth)
        final double HW_T = 0.06;  // half-width ALONG the track —
                                   // doubled in v16.15 (user spec)
        Vec3 tw = T.scale(HW_T);
        Vec3 n = dir.cross(T);
        double nl = n.length();
        Vec3 uh = nl > 1e-9 ? n.scale(HW / nl) : U.scale(HW);
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
        return v + 8;
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
        eye = effEye(cam, eye, fwd);
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
            case 2 -> String.format("%.1f m/s  (%.0f km/h)",
                hit.value(), hit.value() * 3.6);
            case 3 -> String.format("pitch %.2f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 4 -> String.format("yaw %.2f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 5 -> String.format("elev %.2f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 6 -> String.format("heading %.2f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 7 -> String.format("curv %.2f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            default -> String.format("t = %.1f s", hit.value());
        });
        combLabel.setVisible(true);
    }

    /** The curvature-tooth hover's radius parenthetical (v17.18, user
     *  spec — EVERY geometric tooth reads its radius now): a zero
     *  component — straight track in that plane — reads (r=∞). */
    private static String radiusTxt(double degPerM) {
        return Math.abs(degPerM) < 1e-6 ? "(r=∞)"
            : String.format("(r≈%.0f m)", 57.29578 / Math.abs(degPerM));
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
    // the gizmo overlay layer (v18.17, D188): rigs render in their own
    // depth-buffered transparent SubScene over the main one; the
    // fixed reveal balls stay in `world` (trackBallGroup)
    final Group gizmoWorld = new Group();
    final Group trackBallGroup = new Group();
    SubScene gizmoSub;
    private PerspectiveCamera gizmoPCam;
    private ParallelCamera gizmoOCam;

    /** The overlay eats mouse events only when it should (v18.18,
     *  D189): during FLY and captured POV it goes fully transparent —
     *  the warped hidden cursor crossing gizmo geometry used to hover
     *  the overlay instead of `sub`, un-hiding the mouse and starving
     *  the fly-look handler of move events (his "mouse reappears on
     *  the gizmo, view a bit shaky"). Empty overlay = transparent too
     *  (the D188 pass-through belt-and-suspenders). */
    void syncGizmoSubMouse() {
        if (gizmoSub == null) return;
        gizmoSub.setMouseTransparent(flyActive || povActive
            || gizmoGroup.getChildren().isEmpty());
    }

    /** Mirror the active main camera onto the overlay SubScene's —
     *  full localToScene transform + lens; runs every frame off the
     *  axes timer, so the overlay tracks orbit/fly/ortho exactly. */
    private void syncGizmoCam() {
        if (gizmoSub == null) return;
        javafx.scene.Camera cam = sub.getCamera();
        javafx.scene.Camera tgt;
        if (cam == orthoCam) {
            tgt = gizmoOCam;
        } else {
            if (cam instanceof PerspectiveCamera pc) {
                gizmoPCam.setFieldOfView(pc.getFieldOfView());
                gizmoPCam.setNearClip(pc.getNearClip());
                gizmoPCam.setFarClip(pc.getFarClip());
            }
            tgt = gizmoPCam;
        }
        tgt.getTransforms().setAll(new javafx.scene.transform.Affine(
            cam.getLocalToSceneTransform()));
        if (gizmoSub.getCamera() != tgt) gizmoSub.setCamera(tgt);
    }
    private String gizmoSig;
    private boolean gizmoDragging, gizmoPushed;
    private int gizmoPiece = -1;         // rebuild on piece switch

    /** REVEAL state (v18.16, D187 — the ball-click gating returns,
     *  per pane; the v17.7 "Show Gizmos" check is GONE): 0 = balls
     *  only, 1 = the entry/seam rig, 2 = the bridge-end rig. A
     *  single click on a white ball toggles its rig; any primary
     *  press off the gizmo hides it; piece switches reset it. */
    private int gizmoReveal = 0;
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
        Runnable onDouble;  // dbl-click ANYWHERE on the rig = the
                            // owner's settings (v18.13, user spec)
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
        syncGizmoSubMouse();
        int ap = app.editor != null ? app.editor.activePiece() : -1;
        if (ap != gizmoPiece) {
            gizmoPiece = ap;
            gizmoReveal = 0;   // a piece switch hides the rig (D187)
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
          .append(orthoPlaneAxis())   // fixed-ortho arrow hiding (v18.11)
          .append(gizmoReveal)        // ball-click reveal (v18.16, D187)
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
        trackBallGroup.getChildren().clear();
        gizmoRigs.clear();
        revealMarks.clear();
        if ("off".equals(gizmoSig)) {
            syncGizmoSubMouse();
            syncGizmoAxesBtn();
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
        BridgeSection intoP = app.bridgeInto(pi);
        if (!app.editor.followsPrev(pi)) {
            // the ENTRY ball: drags the piece in the VIEW PLANE
            // (v18.11, user spec) unless a bridge owns the placement;
            // double-click = the Track Piece panel
            boolean movable = intoP == null
                || intoP.optMode == BridgeSection.OPT_NONE;
            addRevealMark(() -> new Vec3(p.x, p.y, p.z), true,
                () -> app.showPlacementPopup(Boolean.FALSE),
                !movable ? null : v -> {
                    p.x = v.x();
                    p.y = v.y();
                    p.z = v.z();
                    app.rebuildTrack();
                });
        } else if (app.editor.seamWorldPos(pi) != null) {
            final int fpi = pi;
            addRevealMark(() -> {
                Vec3 v = app.editor.seamWorldPos(fpi);
                return v != null ? v : new Vec3(p.x, p.y, p.z);
            }, true, () -> app.editor.showStrictSeamSettings(fpi), null);
        }
        if (next != null) {
            TrackPiece n = next;
            // the BRIDGE-END ball: planar drag only in manual mode
            // (optimize modes own the position); double-click = the
            // bridge properties panel
            boolean movable = trail != null
                && trail.optMode == BridgeSection.OPT_NONE;
            addRevealMark(() -> new Vec3(n.x, n.y, n.z), false,
                () -> app.showPlacementPopup(Boolean.TRUE),
                !movable ? null : v -> {
                    n.x = v.x();
                    n.y = v.y();
                    n.z = v.z();
                    app.editor.gizmoBridgeMoved();
                });
        }
        // rigs build only for the REVEALED ball (v18.16, D187)
        buildRigsForReveal();
        syncGizmoAxesBtn();
        positionGizmo();
    }

    /** Build the revealed rig (+ its scaling center ball) into the
     *  OVERLAY layer — split out of rebuildGizmo (v18.17, D188) so a
     *  drag on a track ball can reveal mid-gesture without rebuilding
     *  the pressed ball (replacing it would kill the mouse capture). */
    private void buildRigsForReveal() {
        if (gizmoReveal == 0 || "off".equals(gizmoSig)) {
            syncGizmoSubMouse();
            return;
        }
        int pi = app.editor.activePiece();
        TrackPiece p = app.coaster.pieces.get(pi);
        BridgeSection trail = app.bridgeInto(pi + 1);
        TrackPiece next = trail != null
            && pi + 1 < app.coaster.pieces.size()
            ? app.coaster.pieces.get(pi + 1) : null;
        boolean local = app.settings.gizmoLocal();
        java.util.function.Consumer<Vec3> ctrMove = null;
        if (gizmoReveal == 1) {
            buildPieceGizmo(local, pi, p);
            BridgeSection intoP = app.bridgeInto(pi);
            if (!app.editor.followsPrev(pi)
                    && (intoP == null
                        || intoP.optMode == BridgeSection.OPT_NONE)) {
                ctrMove = v -> {
                    p.x = v.x();
                    p.y = v.y();
                    p.z = v.z();
                    app.rebuildTrack();
                };
            }
        } else if (gizmoReveal == 2 && trail != null && next != null) {
            buildBridgeGizmo(local, trail, next);
            if (trail.optMode == BridgeSection.OPT_NONE) {
                TrackPiece n = next;
                ctrMove = v -> {
                    n.x = v.x();
                    n.y = v.y();
                    n.z = v.z();
                    app.editor.gizmoBridgeMoved();
                };
            }
        }
        // the SCALING center ball is part of the rig (v18.17, D188 —
        // real depth among the arrows/rings now, and it grows with
        // them; the fixed track ball underneath stays put)
        for (Rig rig : gizmoRigs) addRigCenterBall(rig, ctrMove);
        syncGizmoSubMouse();
    }

    /** Reveal a ball's rig mid-drag (v18.17): rig-only build — the
     *  pressed track ball survives, keeping the drag alive. */
    private void revealDuringDrag(int mine) {
        if (gizmoReveal == mine) return;
        gizmoReveal = mine;
        gizmoGroup.getChildren().clear();
        gizmoRigs.clear();
        gizmoSig = gizmoSigNow();
        buildRigsForReveal();
        syncGizmoAxesBtn();
        positionGizmo();
    }

    private void addRigCenterBall(Rig rig,
            java.util.function.Consumer<Vec3> moveApply) {
        // 0.272 = the fixed ball's 0.34 × the D186 0.8 rig ratio; the
        // rig group's scale transform grows it with the arrows
        Sphere ball = new Sphere(0.272, 14);
        wireBall(ball, rig.anchor, rig.onDouble, moveApply, null,
            () -> {
                gizmoReveal = 0;   // click the center = hide (D187 toggle)
                refreshGizmo(false);
            });
        rig.group.getChildren().add(ball);
    }

    /** The top-right Local/Global overlay button (v18.16, D187 —
     *  back from its v18.10 removal, now visible only while a rig is
     *  revealed); text tracks the shared axes choice. */
    void syncGizmoAxesBtn() {
        if (gizmoAxesBtn == null) return;
        boolean show = gizmoReveal != 0 && !"off".equals(gizmoSig);
        gizmoAxesBtn.setVisible(show);
        gizmoAxesBtn.setManaged(show);
        if (show) {
            gizmoAxesBtn.setText(app.settings.gizmoLocal()
                ? "Gizmos: Local" : "Gizmos: Global");
        }
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
                               boolean pieceEntry, Runnable onDouble,
                               java.util.function.Consumer<Vec3> moveApply) {
        // the FIXED-SIZE ball (v18.17, D188 — his clarification of
        // "originally not scaling"): part of the TRACK layer, never
        // scales with view distance; click reveals the rig, and a
        // DRAG both moves the owner and reveals
        Sphere ball = new Sphere(0.34, 14);
        int mine = pieceEntry ? 1 : 2;
        wireBall(ball, at, onDouble, moveApply,
            () -> revealDuringDrag(mine),
            () -> {
                // SINGLE CLICK toggles the ball's rig (v18.16, D187):
                // entry/seam ball = rig 1, bridge-end ball = rig 2;
                // the entry ball still selects the covered node 0
                gizmoReveal = gizmoReveal == mine ? 0 : mine;
                refreshGizmo(false);
                if (pieceEntry && app.editor != null
                        && app.editor.activeSection() == 0) {
                    app.editor.selectNodeFromViewport(0);
                }
            });
        // the track ball HIDES while its own rig is shown (v18.18,
        // D189 — the rig's scaling center ball replaces it); set at
        // build time only, so a reveal-by-drag keeps the pressed
        // ball alive until the release rebuild
        ball.setVisible(gizmoReveal != mine);
        revealMarks.add(new RevealMark(ball, at));
        trackBallGroup.getChildren().add(ball);
    }

    /** Shared white-ball behavior (v18.17, D188 — one wiring for the
     *  fixed track balls AND the rigs' scaling center balls): warm
     *  hover, PLANAR DRAG in the view plane (absolute from the press;
     *  Ctrl snaps — GLOBAL = the world grid, LOCAL = step
     *  increments), double-click = the owner's settings, plus a
     *  per-ball single-click action. */
    private void wireBall(Sphere ball,
                          java.util.function.Supplier<Vec3> at,
                          Runnable onDouble,
                          java.util.function.Consumer<Vec3> moveApply,
                          Runnable onFirstDragMove,
                          Runnable onSingleClick) {
        Color idle = Color.rgb(248, 249, 252);
        PhongMaterial mat = new PhongMaterial(idle);
        ball.setMaterial(mat);
        ball.setOnMouseEntered(e ->
            mat.setDiffuseColor(Color.rgb(255, 240, 170)));
        ball.setOnMouseExited(e -> mat.setDiffuseColor(idle));
        double[] st = new double[9];   // rightW, downW, upp, px, py
        Vec3[] base = new Vec3[1];
        boolean[] moved = new boolean[1];
        ball.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            e.consume();
            if (moveApply == null) return;
            sub.requestFocus();
            javafx.scene.Camera cam = sub.getCamera();
            Point3D eye0 = cam.localToScene(0, 0, 0);
            Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye0).normalize();
            Point3D rS = cam.localToScene(1, 0, 0).subtract(eye0).normalize();
            Point3D dS = cam.localToScene(0, 1, 0).subtract(eye0).normalize();
            double upp;
            if (cam == orthoCam) {
                upp = orthoUpp();
            } else {
                Vec3 a = at.get();
                Point3D as = world.localToScene(a.x(), -a.y(), a.z());
                double depth = Math.max(1, as.subtract(
                    effEye(cam, eye0, fwd)).dotProduct(fwd));
                upp = 2 * depth * Math.tan(Math.toRadians(camFov() / 2))
                    / Math.max(1, sub.getHeight());
            }
            // scene direction -> world (undo the Y flip)
            st[0] = rS.getX(); st[1] = -rS.getY(); st[2] = rS.getZ();
            st[3] = dS.getX(); st[4] = -dS.getY(); st[5] = dS.getZ();
            st[6] = upp;
            st[7] = e.getScreenX();
            st[8] = e.getScreenY();
            base[0] = at.get();
            moved[0] = false;
            gizmoPushed = false;
        });
        ball.setOnMouseDragged(e -> {
            if (moveApply == null || base[0] == null) return;
            e.consume();
            double ddx = e.getScreenX() - st[7], ddy = e.getScreenY() - st[8];
            if (!moved[0] && Math.hypot(ddx, ddy) <= 3) return;
            if (!moved[0]) {
                // first real move: a track ball reveals its rig here
                // (the rig-only build keeps THIS ball alive, D188)
                if (onFirstDragMove != null) onFirstDragMove.run();
                moved[0] = true;
            }
            gizmoDragging = true;
            app.gestureHot = true;   // fast reconcile per event (D200)
            app.pointerDown = true;
            double step = Math.max(0.01, app.settings.snapLen());
            double mx = ddx * st[6], my = ddy * st[6];
            if (e.isControlDown() && app.settings.gizmoLocal()) {
                mx = Math.round(mx / step) * step;
                my = Math.round(my / step) * step;
            }
            Vec3 t = base[0]
                .add(new Vec3(st[0], st[1], st[2]).scale(mx))
                .add(new Vec3(st[3], st[4], st[5]).scale(my));
            if (e.isControlDown() && !app.settings.gizmoLocal()) {
                t = new Vec3(Math.round(t.x() / step) * step,
                             Math.round(t.y() / step) * step,
                             Math.round(t.z() / step) * step);
            }
            pushOnce();
            moveApply.accept(t);
        });
        ball.setOnMouseReleased(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            e.consume();
            if (moved[0]) {          // a real drag: no click actions
                base[0] = null;
                moved[0] = false;
                endGizmoDrag();
                return;
            }
            base[0] = null;
            // DOUBLE-CLICK (v17.3, D140; EVERY ball since v18.11):
            // opens the owner's settings — seam pop-out, Track Piece
            // panel, bridge properties
            if (e.getClickCount() == 2) {
                if (onDouble != null) {
                    // the settings open near the click (v18.19, D190)
                    app.nextPopupAnchor(e.getScreenX(), e.getScreenY());
                    onDouble.run();
                }
                return;
            }
            if (e.getClickCount() != 1) return;
            if (onSingleClick != null) onSingleClick.run();
        });
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
        rig.onDouble = () -> app.showPlacementPopup(Boolean.FALSE);
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
                    double step = Math.max(0.01, app.settings.snapLen());
                    if (snap && app.settings.gizmoLocal()) {
                        lam = Math.round(lam / step) * step;   // increments
                    }
                    pushOnce();
                    double nx = bx + d0.x() * lam;
                    double ny = by + d0.y() * lam;
                    double nz = bz + d0.z() * lam;
                    if (snap && !app.settings.gizmoLocal()) {
                        // GLOBAL: the whole position lands ON the
                        // world grid (v18.13, user spec)
                        nx = Math.round(nx / step) * step;
                        ny = Math.round(ny / step) * step;
                        nz = Math.round(nz / step) * step;
                    }
                    p.x = nx;
                    p.y = ny;
                    p.z = nz;
                    app.rebuildTrack();
                }
            });
        boolean lockY = into != null && into.lockYaw;
        boolean lockP = into != null ? into.lockPitch : p.uiLockPitch;
        boolean lockR = into != null ? into.lockRoll : p.uiLockRoll;
        // LOCAL rings aim along the ENTRY FRAME (v18.18, D189 — the
        // NL2 look) and their drags are TRUE rotations about the
        // drawn axis (v18.19, D190: compose + decompose, all
        // unlocked deltas written); Global keeps the model Euler
        // axes with the classic one-Euler-per-ring writes.
        java.util.function.Supplier<Vec3> yawAx = () -> local
            ? p.entryPose().orient().U() : new Vec3(0, 1, 0);
        java.util.function.Supplier<Vec3> pitAx = () -> local
            ? rightOf(p.entryPose().orient()) : pitchAxis(p);
        java.util.function.Supplier<Vec3> rolAx =
            () -> p.entryPose().orient().T();
        if (!lockY) {
            addRing(rig, 1.0, RING_YAW, yawAx,
                () -> p.yawDeg, deg -> {
                    pushOnce();
                    p.yawDeg = wrapDeg(deg);
                    app.rebuildTrack();
                }, local ? pieceLocal(p, yawAx, lockY, lockP, lockR)
                         : null);
        }
        if (!lockP) {
            addRing(rig, 0.85, RING_PITCH, pitAx,
                () -> p.pitchDeg, deg -> {
                    pushOnce();
                    p.pitchDeg = FvdDesigner.clamp(deg, -85, 85);
                    app.rebuildTrack();
                }, local ? pieceLocal(p, pitAx, lockY, lockP, lockR)
                         : null);
        }
        if (!lockR) {
            addRing(rig, 0.7, RING_ROLL, rolAx,
                () -> p.rollDeg, deg -> {
                    pushOnce();
                    p.rollDeg = wrapDeg(deg);
                    app.rebuildTrack();
                }, local ? pieceLocal(p, rolAx, lockY, lockP, lockR)
                         : null);
        }
    }

    /** The piece rig's TRUE-local sink (v18.19, D190): begin captures
     *  the entry frame + drawn axis; rotate composes the target and
     *  writes the DECOMPOSED deltas — unlocked quantities only, so a
     *  locked axis holds and the object follows the ring as closely
     *  as the delta model allows. */
    private RingLocal pieceLocal(TrackPiece p,
            java.util.function.Supplier<Vec3> axis,
            boolean lockY, boolean lockP, boolean lockR) {
        return new RingLocal() {
            private Quat q0;
            private Vec3 a0;
            @Override public void begin() {
                q0 = p.entryPose().orient();
                Vec3 a = axis.get();
                double len = a.length();
                a0 = len > 1e-9 ? a.scale(1 / len) : new Vec3(0, 1, 0);
            }
            @Override public void rotate(double deltaDeg) {
                Quat tgt = Quat.aboutAxis(a0,
                    Math.toRadians(deltaDeg)).mul(q0);
                double[] d = TrackPiece.decomposeEntry(p.baseOrient, tgt);
                if (d == null) return;   // heading undefined: hold
                pushOnce();
                if (!lockY) p.yawDeg = wrapDeg(d[0]);
                if (!lockP) p.pitchDeg = FvdDesigner.clamp(d[1], -85, 85);
                if (!lockR) p.rollDeg = wrapDeg(d[2]);
                app.rebuildTrack();
            }
        };
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
        rig.onDouble = () -> app.editor.showStrictSeamSettings(pi);
        // LOCAL rings ride the follow-contract frame (v18.18, D189)
        // with TRUE about-the-axis drags (v18.19, D190: eulerOf
        // decomposition — the held angles ARE euler quantities)
        boolean local = app.settings.gizmoLocal();
        java.util.function.Supplier<Vec3> yawAx = () -> local
            ? app.editor.seamEntryOrient(pi).U() : new Vec3(0, 1, 0);
        java.util.function.Supplier<Vec3> pitAx = () -> local
            ? rightOf(app.editor.seamEntryOrient(pi)) : seamPitchAxis(pi);
        java.util.function.Supplier<Vec3> rolAx =
            () -> app.editor.seamEntryOrient(pi).T();
        if (f.sLockYaw) {
            addRing(rig, 1.0, RING_YAW, yawAx,
                () -> f.sYawDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 0, wrapDeg(deg));
                }, local ? seamLocal(pi, yawAx) : null);
        }
        if (f.sLockPitch) {
            addRing(rig, 0.85, RING_PITCH, pitAx,
                () -> f.sPitchDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 1,
                        FvdDesigner.clamp(deg, -85, 85));
                }, local ? seamLocal(pi, pitAx) : null);
        }
        if (f.sLockRoll) {
            addRing(rig, 0.7, RING_ROLL, rolAx,
                () -> f.sRollDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 2, wrapDeg(deg));
                }, local ? seamLocal(pi, rolAx) : null);
        }
    }

    /** Bridge-rig local sink (v18.19): decompose against the NEXT
     *  piece's base orientation, write through the editor's combined
     *  bridge path (locks + optimize re-place handled there). */
    private RingLocal bridgeLocal(TrackPiece t,
            java.util.function.Supplier<Vec3> axis) {
        return new RingLocal() {
            private Quat q0;
            private Vec3 a0;
            @Override public void begin() {
                q0 = t.entryPose().orient();
                Vec3 a = axis.get();
                double len = a.length();
                a0 = len > 1e-9 ? a.scale(1 / len) : new Vec3(0, 1, 0);
            }
            @Override public void rotate(double deltaDeg) {
                Quat tgt = Quat.aboutAxis(a0,
                    Math.toRadians(deltaDeg)).mul(q0);
                double[] d = TrackPiece.decomposeEntry(t.baseOrient, tgt);
                if (d == null) return;
                pushOnce();
                app.editor.gizmoSetBridgeOrientAll(wrapDeg(d[0]),
                    d[1], wrapDeg(d[2]));
            }
        };
    }

    /** Seam-rig local sink (v18.19): the held angles are eulerOf
     *  quantities, so the rotated frame decomposes via eulerOf;
     *  DISCONTINUOUS axes get the values, following axes just
     *  follow (they hold no number). */
    private RingLocal seamLocal(int pi,
            java.util.function.Supplier<Vec3> axis) {
        return new RingLocal() {
            private Quat q0;
            private Vec3 a0;
            @Override public void begin() {
                q0 = app.editor.seamEntryOrient(pi);
                Vec3 a = axis.get();
                double len = a.length();
                a0 = len > 1e-9 ? a.scale(1 / len) : new Vec3(0, 1, 0);
            }
            @Override public void rotate(double deltaDeg) {
                Quat tgt = Quat.aboutAxis(a0,
                    Math.toRadians(deltaDeg)).mul(q0);
                double[] e = Coaster.eulerOf(tgt);
                if (Double.isNaN(e[0]) || Double.isNaN(e[2])) return;
                pushOnce();
                app.editor.gizmoSetSeamOrientAll(pi, e[0], e[1], e[2]);
            }
        };
    }

    /** A frame's RIGHT axis (R = U x T, the model convention) —
     *  the LOCAL pitch-ring axis (v18.18, D189). */
    private static Vec3 rightOf(Quat q) {
        return q.U().cross(q.T());
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
        rig.onDouble = () -> app.showPlacementPopup(Boolean.TRUE);
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
                        double step = Math.max(0.01, app.settings.snapLen());
                        if (snap && app.settings.gizmoLocal()) {
                            lam = Math.round(lam / step) * step;
                        }
                        pushOnce();
                        double nx = bx + d0.x() * lam;
                        double ny = by + d0.y() * lam;
                        double nz = bz + d0.z() * lam;
                        if (snap && !app.settings.gizmoLocal()) {
                            nx = Math.round(nx / step) * step;   // grid
                            ny = Math.round(ny / step) * step;
                            nz = Math.round(nz / step) * step;
                        }
                        t.x = nx;
                        t.y = ny;
                        t.z = nz;
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
            // LOCAL rings ride the next piece's entry frame (v18.18,
            // D189) with TRUE about-the-axis drags (v18.19, D190)
            java.util.function.Supplier<Vec3> yawAx = () -> local
                ? t.entryPose().orient().U() : new Vec3(0, 1, 0);
            java.util.function.Supplier<Vec3> pitAx = () -> local
                ? rightOf(t.entryPose().orient()) : pitchAxis(t);
            java.util.function.Supplier<Vec3> rolAx =
                () -> t.entryPose().orient().T();
            if (!bs.lockYaw) {
                addRing(rig, 1.0, RING_YAW, yawAx,
                    () -> t.yawDeg, deg -> {
                        pushOnce();
                        app.editor.gizmoSetBridgeOrient(0, deg);
                    }, local ? bridgeLocal(t, yawAx) : null);
            }
            if (!bs.lockPitch) {
                addRing(rig, 0.85, RING_PITCH, pitAx,
                    () -> t.pitchDeg, deg -> {
                        pushOnce();
                        app.editor.gizmoSetBridgeOrient(1, deg);
                    }, local ? bridgeLocal(t, pitAx) : null);
            }
            if (!bs.lockRoll) {
                addRing(rig, 0.7, RING_ROLL, rolAx,
                    () -> t.rollDeg, deg -> {
                        pushOnce();
                        app.editor.gizmoSetBridgeOrient(2, deg);
                    }, local ? bridgeLocal(t, rolAx) : null);
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

    /** The world axis pointing IN/OUT of a fixed ortho view's plane
     *  (0 = X, 1 = Y, 2 = Z), or -1 in perspective / the tangent
     *  side view — that axis's GLOBAL arrow is useless there and
     *  hides (v18.11, user spec), revealing the center ball. */
    int orthoPlaneAxis() {
        if (!orthoView) return -1;
        if (orthoTop) return 1;
        return switch (orthoSideMode) {
            case 0, 1 -> 2;    // Front/Back look along Z
            case 2, 3 -> 0;    // Right/Left look along X
            default -> -1;     // tangent: not axis-aligned
        };
    }

    /** One translation arrow per axis: world X/Y/Z, or the entry
     *  frame's right/up/forward when the Axes toggle says local. In
     *  a fixed ortho view with GLOBAL axes, the out-of-plane arrow
     *  doesn't build (v18.11). */
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
        int plane = local ? -1 : orthoPlaneAxis();
        for (int i = 0; i < dirs.size(); i++) {
            if (i == plane) continue;
            Ax ax = dirs.get(i);
            Group arrow = gizmoArrow(rig, ax.dir(), ax.col(), true);
            wireAxisDrag(arrow, rig, ax.dir(), sink);
            rig.group.getChildren().add(arrow);
        }
    }

    /** A ring (torus ⊥ its rotation axis) writing ABSOLUTE degrees:
     *  base value read at the press, applyAbs called with base +
     *  linear mouse movement (Ctrl = whole degrees). */
    /** TRUE local rotation (v18.19, D190): in LOCAL mode a ring
     *  gesture rotates the OBJECT about the drawn axis — begin()
     *  captures the press-time frame, rotate(deltaDeg) composes the
     *  target orientation and writes the decomposed deltas. Null =
     *  the classic one-Euler-per-ring linear write. */
    interface RingLocal {
        void begin();
        void rotate(double deltaDeg);
    }

    private void addRing(Rig rig, double radius, Color col,
            java.util.function.Supplier<Vec3> axis,
            java.util.function.DoubleSupplier baseVal,
            java.util.function.DoubleConsumer applyAbs) {
        addRing(rig, radius, col, axis, baseVal, applyAbs, null);
    }

    private void addRing(Rig rig, double radius, Color col,
            java.util.function.Supplier<Vec3> axis,
            java.util.function.DoubleSupplier baseVal,
            java.util.function.DoubleConsumer applyAbs,
            RingLocal localSink) {
        // FIXED ortho views keep only the IN-PLANE ring — the one
        // whose rotation axis runs along the view direction; the
        // others are edge-on slivers that bury the center (v18.13,
        // user spec). The gizmo sig carries orthoPlaneAxis + poses,
        // so view/pose changes rebuild the set.
        int plane = orthoPlaneAxis();
        if (plane >= 0) {
            Vec3 a = axis.get();
            double along = plane == 0 ? a.x() : plane == 1 ? a.y() : a.z();
            if (Math.abs(along) < 0.9 * Math.max(1e-9, a.length())) {
                return;
            }
        }
        PhongMaterial mat = gizmoMat(col);
        MeshView ring = torus(radius, 0.06, mat);
        Group g = new Group(ring);
        Rotate rot = new Rotate();
        g.getTransforms().add(rot);
        Runnable aimIt = () -> aim(rot, axis.get());
        aimIt.run();
        rig.aims.add(aimIt);
        hoverGlow(g, mat, col);
        wireRingDrag(g, rig, axis, baseVal, applyAbs, localSink);
        rig.group.getChildren().add(g);
    }

    /** Shaft + cone tip along +local-Y, aimed along the (live) WORLD
     *  direction — thicker since v16.12 (grabbing needed too much
     *  precision). */
    private Group gizmoArrow(Rig rig,
            java.util.function.Supplier<Vec3> dir, Color col) {
        return gizmoArrow(rig, dir, col, false);
    }

    /** twoSided (v18.7, D176 — user spec): TRANSLATION arrows point
     *  BOTH ways from the anchor (a mirrored shaft+tip half); the
     *  bridge LENGTH tangent stays one-sided (its direction means
     *  "longer"). The drag math is unchanged — either side grabs. */
    private Group gizmoArrow(Rig rig,
            java.util.function.Supplier<Vec3> dir, Color col,
            boolean twoSided) {
        PhongMaterial mat = gizmoMat(col);
        Cylinder shaft = new Cylinder(0.05, 1.0);
        shaft.setMaterial(mat);
        shaft.setTranslateY(0.5);
        MeshView tip = cone(0.13, 1.0, 1.32, mat);
        Group g = new Group(shaft, tip);
        if (twoSided) {
            Cylinder shaft2 = new Cylinder(0.05, 1.0);
            shaft2.setMaterial(mat);
            shaft2.setTranslateY(0.5);
            MeshView tip2 = cone(0.13, 1.0, 1.32, mat);
            Group neg = new Group(shaft2, tip2);
            neg.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
            g.getChildren().add(neg);
        }
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
            app.gestureHot = true;   // fast reconcile per event (D200)
            app.pointerDown = true;
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
            boolean was = gizmoPushed;
            endGizmoDrag();
            // dbl-click anywhere on the rig = its settings (v18.13);
            // a real drag (something was pushed) never counts
            if (!was && e.getClickCount() == 2 && rig.onDouble != null) {
                app.nextPopupAnchor(e.getScreenX(), e.getScreenY());
                rig.onDouble.run();
            }
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
            java.util.function.DoubleConsumer applyAbs,
            RingLocal localSink) {
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
            if (localSink != null) localSink.begin();
            gizmoDragging = true;
            app.gestureHot = true;   // fast reconcile per event (D200)
            app.pointerDown = true;
            gizmoPushed = false;
            gDragX = e.getScreenX();
            gDragY = e.getScreenY();
            e.consume();
        });
        part.setOnMouseDragged(e -> {
            if (!gizmoDragging) return;
            double dist = (e.getScreenX() - gDragX) * st[0]
                        + (e.getScreenY() - gDragY) * st[1];
            if (localSink != null) {
                // rotate about the DRAWN axis (v18.19): same mouse
                // mapping, the delta feeds the quaternion compose
                double delta = -dist * st[2];   // world = −scene mirror
                if (e.isControlDown()) delta = Math.round(delta);
                localSink.rotate(delta);
                e.consume();
                return;
            }
            double val = st[3] - dist * st[2];   // world = −scene (mirror)
            if (e.isControlDown()) val = Math.round(val);
            applyAbs.accept(val);
            e.consume();
        });
        part.setOnMouseReleased(e -> {
            boolean was = gizmoPushed;
            endGizmoDrag();
            // dbl-click anywhere on the rig = its settings (v18.13)
            if (!was && e.getClickCount() == 2 && rig.onDouble != null) {
                app.nextPopupAnchor(e.getScreenX(), e.getScreenY());
                rig.onDouble.run();
            }
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
        Point3D dir, orig;
        if (cam == orthoCam) {
            // TRUE ortho (v18.8): the pixel ray starts ON the
            // projection plane (camera local (px, py, 0)) and runs
            // along the view direction; t may go negative — the
            // parallel window renders behind the plane too
            orig = cam.localToScene(local.getX(), local.getY(), 0);
            dir = fwd;
        } else {
            // pixel ray through the current perspective lens
            // (vertical FOV — the JavaFX default)
            double f = (sub.getHeight() / 2)
                / Math.tan(Math.toRadians(camFov() / 2));
            dir = fwd.multiply(f)
                .add(right.multiply(local.getX() - sub.getWidth() / 2))
                .add(down.multiply(local.getY() - sub.getHeight() / 2))
                .normalize();
            orig = eye;
        }
        double denom = dir.dotProduct(n);
        if (Math.abs(denom) < 1e-4) return null;
        double t = c.subtract(orig).dotProduct(n) / denom;
        if (cam != orthoCam && t <= 0) return null;
        return orig.add(dir.multiply(t));
    }

    /** The rendering camera's FOV (fly cam while flying — gizmos stay
     *  visible there since v16.12). */
    private double camFov() {
        return sub.getCamera() instanceof PerspectiveCamera pc
            ? pc.getFieldOfView() : orbitCam.getFieldOfView();
    }

    /** World-units-per-pixel the ortho zoom state implies (v18.8 —
     *  shared by the camera sync, gizmo scale and pixel rays). */
    private double orthoUpp() {
        return 2 * Math.max(1e-6, -camZoom.getZ())
            * Math.tan(Math.toRadians(ORTHO_FOV / 2))
            / Math.max(1, sub.getHeight());
    }

    /** Effective EYE for behind-camera filtering (v18.8): the
     *  true-ortho ParallelCamera's local origin sits ON the
     *  projection plane, and parallel projection still renders the
     *  depth window BEHIND it — back the reference off so visible
     *  geometry never fails the filters. */
    private Point3D effEye(javafx.scene.Camera cam, Point3D eye0,
                           Point3D fwd) {
        if (cam != orthoCam) return eye0;
        return eye0.subtract(fwd.multiply(
            Math.max(1, sub.getWidth()) / 2 * ORTHO_DEPTH));
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
        eye = effEye(cam, eye, fwd);
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
        // gizmo move handlers rebuilt hot per event — settle the
        // document on its exact reconciled bits now (D200)
        app.pointerDown = false;
        app.gestureHot = false;
        app.settleIfPending();
        app.syncPlacementSpinners();
        app.updateBridgeControls();
        refreshGizmo(true);
        // the graphs follow the finished gesture immediately (v18.20,
        // D191 — they used to wait for the next graph hover/click)
        if (app.editor != null) app.editor.redrawFromViewport();
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
        // TRUE ortho (v18.8): apparent size is depth-independent —
        // 10% of the visible height, straight from the zoom scale
        double orthoScale = cam == orthoCam
            ? Math.max(0.5, sub.getHeight() * orthoUpp() * 0.10) : -1;
        for (Rig rig : gizmoRigs) {
            Vec3 a = rig.anchor.get();
            rig.group.setTranslateX(a.x());
            rig.group.setTranslateY(-a.y());
            rig.group.setTranslateZ(a.z());
            Point3D at = world.localToScene(a.x(), -a.y(), a.z());
            double depth = Math.max(1, at.subtract(eye).dotProduct(fwd));
            rig.scale = orthoScale >= 0 ? orthoScale
                : Math.max(0.5, depth * k);
            rig.scaleT.setX(rig.scale);
            rig.scaleT.setY(rig.scale);
            rig.scaleT.setZ(rig.scale);
            for (Runnable aimIt : rig.aims) aimIt.run();
        }
        // track balls are FIXED SIZE (v18.17, D188 — his
        // clarification: the un-revealed ball should NOT scale with
        // distance; the SCALING white ball is the rig's center now,
        // riding rig.scaleT like every other part)
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
