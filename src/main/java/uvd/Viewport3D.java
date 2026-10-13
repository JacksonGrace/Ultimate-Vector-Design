package uvd;

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
    // STALLED (ghost) track — LAST child of world, DepthTest.DISABLE
    // (v19.19, D248): see the constructor's floor-layer comment; below
    // ground the whole track block swaps before the floor (D249)
    final Group ghostGroup = new Group();
    // GROUND-SPLIT track layer (v19.20, D249): the UNDERGROUND halves
    // of every rail/tie/plate/heartline/comb mesh (trackUnder, depth-
    // enabled among themselves) and of the ghost meshes (ghostUnder,
    // DISABLE) — drawn BEFORE the floor so the sheet + lines + axes
    // composite over them; the two track blocks swap around the floor
    // while the eye is below ground (setFloorOrder)
    final Group trackUnder = new Group();
    final Group ghostUnder = new Group();
    // GLYPH TWINS (batch 37): the node markers, control disks,
    // boundary disks and un-revealed reveal balls are routed PER ITEM
    // by their center (FloorDepth.itemUnder — one tone per glyph) into
    // these under-the-ground twins of markerGroup / ctlDiskGroup /
    // boundaryGroup / trackBallGroup. markerUnder, ctlDiskUnder and
    // boundaryUnder are children of trackUnder, trackBallUnder is a
    // world child of the same block, so setFloorOrder's swap covers
    // eye crossings with no per-item work. ctlDiskUnder's and
    // boundaryUnder's visibility is BOUND to their source group —
    // never setVisible a twin, only the source
    final Group markerUnder = new Group();
    final Group ctlDiskUnder = new Group();
    final Group trackBallUnder = new Group();
    Group boundaryUnder;
    private boolean floorEyeBelow = false;
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
    double savedPivX, savedPivY, savedPivZ; // + its pivot (D241 — ortho
                                            // pans must not relocate fly)
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
    /** The POV ride's pulse clock (batch 36 — see PulseClock: the
     *  raw pulse-start delta sawtooths 16…32 ms on Windows while the
     *  frames display one per vblank; the median-plus-drift advance
     *  is what the ride moves by). */
    final PulseClock povClock = new PulseClock();
    AnimationTimer povTimer;
    final Group markerGroup = new Group();   // on-track node markers
    String markerSig = "";                   // skip no-op rebuilds
    // SPEED-CONTROL DISKS (v19.19, D248, item S1 — user spec: every
    // control's start and end as a disk ON THE HEARTLINE, in a darker
    // shade of the strip's type color, click+draggable along the
    // track). A disk IS its strip diamond: the press/drag/release run
    // through GraphEditor.spViewportPress/DragTo/Release — the
    // strip's own selection, gesture, clamps and undo — with a native
    // x read off the track (trackNativeAt), so the disk can never move
    // further than the diamond can and the two always coincide.
    // Persistent group re-added per rebuild like markerGroup;
    // signature-guarded (refreshCtlDisks runs on every graph redraw).
    // Hidden in POV (the comb/heartline rule — the camera rides
    // through them).
    final Group ctlDiskGroup = new Group();
    String ctlDiskSig = "";
    /** One pickable disk: the control by INDEX, kind 1 = START disk,
     *  2 = END disk, its heartline center (world) and the ring's rim
     *  points (ViewDisks.rimPoints — the WHOLE-disk pick's projected
     *  bound, v19.20, D249, item S3). */
    record CtlDisk(int piece, int sec, int ctl, int kind, Vec3 pos,
                   Vec3[] rim) { }
    /** Pick order = the strip's spFindDiamond: sections in flat order,
     *  controls in list order, END before START — coincident abutting
     *  disks resolve to the EARLIER control's END (strict nearest). */
    private final List<CtlDisk> ctlDisks = new ArrayList<>();
    // the live disk gesture (per pane): the pressed disk, the press
    // point, the 3 px deadband flag; diskHovering = the HAND cursor
    // is ours to restore
    private CtlDisk diskDrag;
    private double diskPressX, diskPressY;
    private boolean diskMoved, diskHovering;
    // Shift = a tenth of the disk's travel in NATIVE units, re-based:
    // prev = the previous event's raw native x (the disk's OWN x at
    // the press), debt = raw − effective (Gestures.slowDebt; a 0.0
    // debt = the D248 "disk write == strip write" path bitwise)
    private double diskPrevN, diskDebtN;
    // SECTION-BOUNDARY DISKS (D248, item S1; the SINGLE-LINE set since
    // v19.20, D249): gray disks on the heartline at the plain interior
    // section boundaries of a piece — the exit of every section but
    // the last, incl. the entry into a trailing bridge; never the
    // piece entry/exit, so no run head/end, strict seam or bridge
    // exit (the graph's double lines) — deduped
    // (ViewDisks.boundarySamples); mouse-transparent, never hovered,
    // rebuilt with the meshes, hidden in POV like the combs
    Group boundaryGroup;
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
    /** The fly camera's pulse clock (batch 36 — the same sawtooth
     *  moved WASD travel unevenly). */
    final PulseClock flyClock = new PulseClock();
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
        // D188 — normal depth, occludable like any track geometry).
        // The FLOOR GRID + MAIN AXES are created HERE since v19.13
        // (D242 — not in the first non-empty rebuild, which left empty
        // coasters and fresh panes with NO grid at all). WORLD ORDER
        // since v19.20 (D249 — the floor back to the D242-D246 build
        // BITWISE, the see-through look by ORDER alone; see the
        // FloorDepth postmortem for the retired D248 depth-tested
        // floor): [underground track: trackUnder (the mesh under-
        // halves + markerUnder + boundaryUnder + ctlDiskUnder),
        // trackBallUnder, ghostUnder] →
        // [FLOOR: gridGroup = sheet + line decades, axisGroup — both
        // DepthTest.DISABLE, so no read, no write: pure submission
        // order, no coplanar layer can ever fight another] → [track:
        // trackGroup, povHolder, trackBallGroup] → [ghostGroup]. Every
        // track-layer mesh is cut EXACTLY at the ground (addSplit —
        // FloorDepth.clipAtGround since batch 37; D249 routed whole
        // faces by their centroid, which cut a ground-crossing comb
        // tooth along its side quads' DIAGONAL) and every GLYPH —
        // node marker, boundary / control disk, un-revealed reveal
        // ball — is routed whole by its center (FloorDepth.itemUnder,
        // batch 37; D249 left them all after the floor, so a buried
        // glyph was untinted from above and washed from below): the
        // underground part draws FIRST, so the sheet + lines + axes
        // composite over it — underground track reads as behind the
        // floor — while above-ground track overdraws the floor
        // exactly as D246 did.
        // The depth tests between the two halves are honest without
        // any coplanar case: for an eye above ground every above-
        // ground point on a ray precedes the ground crossing and every
        // underground one follows it; below ground the two blocks swap
        // (setFloorOrder). The stalled GHOST track stays LAST with
        // DepthTest.DISABLE (D248: composited over floor and track
        // alike); its underground half (ghostUnder, DISABLE, after
        // trackUnder) is composited over the underground layer the
        // same way. (Nothing under `world` is a JavaFX mouse target —
        // batch 37, below; the track is picked in screen space.)
        gridGroup = new Group();
        gridGroup.setMouseTransparent(true);
        gridGroup.setDepthTest(javafx.scene.DepthTest.DISABLE);
        axisGroup = new Group();
        axisGroup.setMouseTransparent(true);
        axisGroup.setDepthTest(javafx.scene.DepthTest.DISABLE);
        trackUnder.setDepthTest(javafx.scene.DepthTest.ENABLE);
        ghostUnder.setDepthTest(javafx.scene.DepthTest.DISABLE);
        ghostGroup.setDepthTest(javafx.scene.DepthTest.DISABLE);
        // the disks' POV hide reaches the underground twin (batch 37;
        // togglePov + the rebuild only ever toggle the SOURCE group)
        ctlDiskUnder.visibleProperty().bind(ctlDiskGroup.visibleProperty());
        this.povHolder = povHolder;
        setFloorOrder(false);
        root3D = new Group(world, cameraRig, flyHolder, ambient, sun);
        // NOTHING under `world` is a mouse target (batch 37): every pick
        // in this pane is screen-space (pickCtlDisk, pickMarkerIndex,
        // pickTrackSample, pickPart), so JavaFX's node pick only chose
        // the press-drag-release TARGET. A target removed DURING the
        // MOUSE_PRESSED dispatch orphans the whole gesture:
        // Scene.MouseHandler sets pdrInProgress only after the press
        // was delivered and handleNodeRemoval re-targets only while it
        // is set, so every DRAGGED and the RELEASED go to the detached
        // node and never reach `sub` or the pane filter. A disk press
        // on an unselected control rebuilt the disks (their sig signs
        // the selection) — the first drag did nothing and the lost
        // release left diskDrag + the strip's drag kind stuck. `sub`
        // has a fill, so it is the target now and is never removed.
        world.setMouseTransparent(true);

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
        // frame (syncGizmoCam off the axes timer). The overlay takes
        // NO mouse events at all since v19.19 (D248): every rig part
        // and track ball is picked in SCREEN SPACE by the pane-level
        // gizmoMouseFilter (pickPart), so pickOnBounds(false) only
        // documents the old pass-through — the SubScene is
        // mouseTransparent for good (syncGizmoSubMouse).
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
        gizmoSub.setMouseTransparent(true);   // screen-space picking (D248)

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
                    if (povCaptured) {
                        app.releaseOtherCaptures(this);
                        // CONFINE like fly (v19.11, D240): warp to
                        // center so the hidden cursor can't leave
                        // the pane until the next right-click (the
                        // pane may never have flown — init the Robot)
                        if (flyRobot == null) flyRobot = new Robot();
                        centerFlyCursor();
                    }
                    syncPovLabel();   // the "(A)" hint is capture-only
                    app.syncCaptureWindows();   // D235: tool windows
                                                // hide while captured
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
                // BELT (batch 37): a disk gesture whose release never
                // arrived ends here, so the next press starts clean —
                // a stuck diskDrag kept isGizmoDragging() true (every
                // modifier shortcut dropped) and the strip's drag kind
                // set (the next plot drag swallowed). moved = false +
                // 0 clicks = no popup, no undo step: only the strip's
                // release tail runs
                if (diskDrag != null) {
                    diskDrag = null;
                    diskMoved = false;
                    if (app.editor != null) {
                        app.editor.spViewportRelease(false,
                            e.getScreenX(), e.getScreenY(), 0);
                    }
                }
                // CLICK-OFF hides a revealed gizmo (v18.16, D187 —
                // gizmo parts consume their presses, so any primary
                // press landing here is off the rig)
                if (gizmoReveal != null) {
                    gizmoReveal = null;
                    refreshGizmo(false);
                }
                // a SPEED-CONTROL DISK under the press (v19.19, D248,
                // item S1) starts the strip's diamond gesture on it —
                // anywhere on the disk (its projected rim, 12 px floor
                // — D249) it wins over the node markers' 14 px RELEASE
                // pick; clickX stays NaN so the release
                // never activates the track or deselects under the
                // fresh control selection (a refused press — convert
                // preview / start screen — posts its reason and
                // starts nothing)
                CtlDisk disk = pickCtlDisk(e.getScreenX(), e.getScreenY());
                if (disk != null) {
                    clickX = Double.NaN;
                    if (app.editor != null && app.editor.spViewportPress(
                            disk.piece(), disk.sec(), disk.ctl(),
                            disk.kind())) {
                        diskDrag = disk;
                        diskPressX = e.getScreenX();
                        diskPressY = e.getScreenY();
                        diskMoved = false;
                        diskPrevN = diskNativeX(disk);   // slow from the disk's own x
                        diskDebtN = 0;
                    }
                    lastMouseX = e.getSceneX(); lastMouseY = e.getSceneY();
                    return;
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
            // a live DISK gesture ends FIRST (D248, item S1) — before
            // the clean-click bail, and even if fly/POV began mid-drag
            // (a right-click mid-gesture): the editor's release tail
            // settles the document (D200); an unmoved double-click
            // opens the control's options popup
            if (diskDrag != null) {
                if (e.getButton() != MouseButton.PRIMARY) return;
                boolean moved = diskMoved;
                CtlDisk released = diskDrag;
                diskDrag = null;
                diskMoved = false;
                clickX = Double.NaN;
                if (app.editor != null) {
                    app.editor.spViewportRelease(moved, e.getScreenX(),
                        e.getScreenY(), e.getClickCount());
                    if (moved) app.editor.redrawFromViewport();
                    // the settled position (D249, item S2 — the last
                    // drag event may precede the settle)
                    diskReadout(released);
                }
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY || flyActive
                    || (povActive && povCaptured)
                    || Double.isNaN(clickX)
                    || Math.hypot(e.getSceneX() - clickX,
                                  e.getSceneY() - clickY) > 3) {
                clickX = Double.NaN;
                return;
            }
            clickX = Double.NaN;
            // node MARKERS take the click before the track does
            // (v16.11, user spec): a clean click on one selects that
            // node in the graph and brings it into view — and since
            // v19.19 (D248) the marker pick runs BEFORE the double-
            // click branch: a DOUBLE-click on a marker opens that
            // node's editor (the graph's node double-click), so within
            // 14 px of a marker the track insert no longer fires
            int mi = pickMarkerIndex(e.getScreenX(), e.getScreenY());
            if (e.getClickCount() == 2) {
                if (mi >= 0) {
                    // the first click of the pair already selected
                    // the node; the wrapper refuses under the convert
                    // preview / start wizard with a stats reason
                    app.editor.openNodeEditorFromViewport(mi);
                    return;
                }
                // DOUBLE-click = insert a node at that track point
                // (v16.12 — moved off Ctrl+click; the first click
                // already activated the piece + section, so the
                // insert lands in the right lane, and the graph view
                // snaps to the new node like a marker click)
                insertNodeAtTrack(e.getScreenX(), e.getScreenY());
                return;
            }
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
            } else {
                // a click on an INACTIVE coaster's track offers the
                // switch (v19.7, D236, user spec)
                Object[] other = pickOtherCoaster(e.getScreenX(),
                    e.getScreenY());
                if (other != null) {
                    showSwitchPrompt((Coaster) other[0],
                        (FVDIntegrator.Sample) other[1],
                        e.getScreenX(), e.getScreenY());
                }
            }
            // (the v16.13 empty-click gizmo hide is gone — gizmos are
            // always on for the active piece since v17.7, D144; the
            // View menu's check is the off switch)
        });
        sub.setOnMouseDragged(e -> {
            if (povActive || flyActive) return;
            // DISK drag (D248, item S1): past a 3 px deadband the
            // grabbed end follows the nearest projected track point,
            // read back as the section's native coordinate and handed
            // to the strip's own write path (Ctrl = the strip's snap —
            // the DISPLAY-axis grid under a cross-domain view, D249);
            // every event rebuilds the track and redraws the strip
            // live, and the disk lands wherever the CLAMPED diamond
            // does
            if (diskDrag != null) {
                if (!e.isPrimaryButtonDown()) return;
                if (!diskMoved && Math.hypot(e.getScreenX() - diskPressX,
                        e.getScreenY() - diskPressY) <= 3) return;
                diskMoved = true;
                if (app.editor != null) {
                    double raw = trackNativeAt(diskDrag, e.getScreenX(),
                        e.getScreenY());
                    double nx = raw;
                    boolean snap = e.isControlDown();
                    if (!Double.isNaN(raw)) {
                        // Shift = a tenth of the travel ALONG the track,
                        // re-based per event; NaN (nothing projected)
                        // leaves the debt and the control alone
                        if (Double.isNaN(diskPrevN)) diskPrevN = raw;
                        diskDebtN = Gestures.slowDebt(diskDebtN, diskPrevN,
                            raw, e.isShiftDown());
                        diskPrevN = raw;
                        nx = raw - diskDebtN;
                    }
                    // Ctrl+Shift = the strip's own snap at a TENTH of
                    // its step — the DISPLAY-axis grid like plain Ctrl
                    // (D254 review: the first cut rounded natively here)
                    app.editor.spViewportDragTo(nx, snap,
                        snap && e.isShiftDown());
                    // the readout follows the drag LIVE (D249, item S2)
                    diskReadout(diskDrag);
                }
                return;
            }
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
            // perspective lens but keeps the current angles — and
            // from the TOP view the orbit pivot lands on the GROUND
            // POINT under the middle-click (v19.14, D243, user spec:
            // XZ of the cursor, y = 0), so orbiting starts around
            // what you grabbed
            if (orthoView) {
                if (orthoTop) {
                    double u = orthoUpp();
                    double yawR = Math.toRadians(camYaw.getAngle());
                    double cy = Math.cos(yawR), sy = Math.sin(yawR);
                    double offR = (e.getX() - sub.getWidth() / 2) * u;
                    double offD = (e.getY() - sub.getHeight() / 2) * u;
                    // right = {cy,0,−sy}, top-view down = {−sy,0,−cy}
                    camPivot.setX(camPivot.getX()
                        + offR * cy + offD * -sy);
                    camPivot.setZ(camPivot.getZ()
                        + offR * -sy + offD * -cy);
                    camPivot.setY(0);
                }
                exitOrthoKeepView();
            }
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
        // a DISK under the cursor takes the hover (hand cursor + the
        // bottom-left readout, D248 item S1 — no silent affordance);
        // else the comb teeth. A LIT GIZMO PART outranks the disk
        // (batch 37): the pane filter ran first and set gHover for
        // this very event — that part would take the press, so the
        // disk must not advertise one (hand cursor + its readout)
        sub.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (gHover != null) {
                clearDiskHover();
                combHover(e);
                return;
            }
            if (!diskHover(e)) combHover(e);
        });
        sub.addEventHandler(MouseEvent.MOUSE_EXITED, e -> clearDiskHover());

        // POV: W rides forward at the simulated speed, S backward;
        // SHIFT x4, CTRL x0.25; no key = hold position (follows live edits)
        povTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
                double rideTime = app.lastSamples.get(app.lastSamples.size() - 1).t();
                if (rideTime <= 0) return;
                // the SMOOTHED pulse period (batch 36 — the raw
                // pulse-start delta read 16 ms × 24 then 32 ms every
                // ~0.4 s on Windows while the frames displayed one per
                // vblank: the ride ran 4 % slow, then skipped two
                // frames — the user's periodic POV skip; PulseClock)
                double dt = povClock.advance(now);
                if (dt < 0) return;   // seeding pulse
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
                double dt = flyClock.advance(now);   // smoothed (batch 36)
                if (dt < 0) return;   // seeding pulse
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
                positionGizmo();   // gizmos stay visible in fly (v16.12)
            }
        };
    }

    /** Rebuild the on-track node markers (viewport editing part 1 —
     *  READ-ONLY). One small sphere per ACTIVE-lane node at its ride
     *  time, sitting on the spine like the ties; selected nodes are
     *  white. Signature-guarded: the graph calls this on every redraw,
     *  but the scene graph only churns when something relevant changed. */
    /** Node-marker radii (m) + the selected marker's white mix (D248). */
    static final double MARKER_R = 0.22, MARKER_R_SEL = 0.26,
        MARKER_SEL_MIX = 0.4;

    /** Does a white JUNCTION BALL sit on piece p's node 0 (v19.20,
     *  D249 — the node-0 sphere is skipped then, the ball is its
     *  anchor)? Only while section 0 is active and not in POV (balls
     *  hide there, markers ride the spine). A run head — and a
     *  bridged-in piece, which is never a strict follower — takes the
     *  historic v18.13 entry-ball rule (the bridge-end ball built at
     *  its x/y/z covers it the same way); a strict-seam FOLLOWER's
     *  node 0 is its seam ball, which exists iff a compile placed the
     *  junction (seamWorldPos != null — the ball builder's own test);
     *  before that compile the sphere shows. Node 0 stays editable
     *  from the graph; a ball click reveals the gizmo / opens the
     *  seam window (the D248 rule: a ball click selects nothing). */
    boolean junctionBallCoversNode0(int p) {
        if (app.editor == null || app.editor.activeSection() != 0
                || povActive) return false;
        if (!app.editor.followsPrev(p)) return true;
        return app.editor.seamWorldPos(p) != null;
    }

    void refreshTrackMarkers(int lane, Set<Integer> sel, int selIdx,
                             double[] nodeTimes) {
        // activation changes arrive here on every graph redraw — the
        // tie highlight and the gizmo ride along (each has its own
        // signature guard, v16.11)
        refreshTieHighlight(false);
        refreshGizmo(false);
        refreshCtlDisks();   // speed-control disks (D248, item S1)
        List<BezierBaker.Node> laneNodes = app.lanes.get(lane);
        int count = Math.min(laneNodes.size(), nodeTimes.length);
        Color laneCol = app.editor.laneColor(lane);   // mode palette (geo: teal/yellow)
        // a JUNCTION BALL is node 0's representation when it covers it
        // (v18.13, user spec — the two spheres z-fought before; every
        // junction of the run since v19.20, D249)
        boolean entryBall = app.editor != null
            && junctionBallCoversNode0(app.editor.activePiece());
        StringBuilder sb = new StringBuilder();
        sb.append(lane).append('|').append(selIdx).append('|').append(sel)
          .append('|').append(laneCol)
          .append('|').append(System.identityHashCode(app.lastSamples))
          .append('|').append(app.settings.trackMarkers())
          .append('|').append(entryBall)
          .append('|').append(app.isPendingPiece(app.activeCoaster,
              app.editor.activePiece()))   // wizard pending (D231)
          .append('|').append(povActive);   // marker height flips (v18.7)
        // nodeTimes = each node's GLOBAL RIDE TIME, computed by the
        // editor (v16.0 — distance-domain lanes' x is meters, so the
        // old tOff + n.t no longer meant time)
        for (int i = 0; i < count; i++) sb.append(';').append(nodeTimes[i]);
        String sig = sb.toString();
        if (sig.equals(markerSig)) return;
        markerSig = sig;
        markerGroup.getChildren().clear();
        markerUnder.getChildren().clear();   // the underground twin (batch 37)
        if (!app.settings.trackMarkers() || app.lastSamples == null || app.lastSamples.isEmpty()) return;
        // the wizard's PENDING piece shows nothing (D231)
        if (app.isPendingPiece(app.activeCoaster, app.editor.activePiece())) return;
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
            // selection: SLIGHTLY bigger + the lane color lifted
            // toward white (v19.19, D248 — the big white sphere read
            // as a second entry ball; interpolate 0.4 sits a notch
            // under the rigs' 0.5 hover glow so a hovered rig and a
            // selected node never look alike)
            Sphere ball = new Sphere(isSel ? MARKER_R_SEL : MARKER_R, 12);
            Color mc = isSel ? laneCol.interpolate(Color.WHITE, MARKER_SEL_MIX)
                             : laneCol;
            ball.setMaterial(new PhongMaterial(mc));
            ball.setTranslateX(p.x());
            ball.setTranslateY(-p.y());   // Y-flip discipline
            ball.setTranslateZ(p.z());
            // the NODE index rides the sphere (v18.17, D188): the
            // entry ball can replace node 0's marker, so child-list
            // position != node index — the pick must not assume it
            // (clicking any marker selected the PREVIOUS node)
            ball.setUserData(i);
            // routed PER MARKER by its own center (batch 37): a buried
            // marker joins the before-floor block — washed by the floor
            // from above, plain from below — like the buried rails
            (FloorDepth.itemUnder(p.y()) ? markerUnder : markerGroup)
                .getChildren().add(ball);
        }
    }

    /** Nearest node MARKER within ~14 px of a screen point (v16.11):
     *  markerGroup + markerUnder (the underground twin, batch 37)
     *  hold one sphere per active-lane node, translated to Y-flipped
     *  world coords — project each and pick the closest, behind-camera
     *  filtered like pickTrackSample. Returns the node index (the
     *  sphere's userData — never a list position), or -1 on a miss. */
    private int pickMarkerIndex(double screenX, double screenY) {
        List<javafx.scene.Node> balls =
            new ArrayList<>(markerGroup.getChildren());
        balls.addAll(markerUnder.getChildren());
        if (balls.isEmpty()) return -1;
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        eye = effEye(cam, eye, fwd);
        double best = 14 * 14;
        int bestIdx = -1;
        for (javafx.scene.Node ball : balls) {
            double x = ball.getTranslateX(), y = ball.getTranslateY(),
                   z = ball.getTranslateZ();
            Point3D sceneP = world.localToScene(x, y, z);
            if (sceneP.subtract(eye).dotProduct(fwd) <= 0) continue;
            Point2D s = world.localToScreen(x, y, z);
            if (s == null) continue;
            double dx = s.getX() - screenX, dy = s.getY() - screenY;
            double d2 = dx * dx + dy * dy;
            // the sphere carries its NODE index (v18.17, D188 —
            // list position shifted whenever the entry ball
            // replaced node 0's marker); an exact tie keeps the
            // EARLIER node, as the single in-order list did (batch 37
            // — the walk is above-ground markers, then buried ones)
            int idx = (Integer) ball.getUserData();
            if (d2 < best || (d2 == best && bestIdx >= 0 && idx < bestIdx)) {
                best = d2;
                bestIdx = idx;
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
        return pickSampleIn(app.lastSamples, screenX, screenY);
    }

    /** The pickTrackSample math over ANY compiled sample list (v19.7,
     *  D236 — the inactive-coaster click reuses it verbatim). */
    private FVDIntegrator.Sample pickSampleIn(
            java.util.List<FVDIntegrator.Sample> smps,
            double screenX, double screenY) {
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        eye = effEye(cam, eye, fwd);
        double best = 25 * 25;
        FVDIntegrator.Sample bestSample = null;
        FVDIntegrator.Sample prev = null;
        double px = 0, py = 0;
        boolean prevOk = false;
        for (int i = 0; i < smps.size(); i += 5) {
            FVDIntegrator.Sample sm = smps.get(i);
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

    /** A clean click on an INACTIVE coaster's track (v19.7, D236):
     *  the same screen-space pick over every other coaster's cached
     *  compile. Returns {Coaster, Sample} or null. */
    private Object[] pickOtherCoaster(double screenX, double screenY) {
        for (Object[] b : app.otherCoasterRenders()) {
            @SuppressWarnings("unchecked")
            java.util.List<FVDIntegrator.Sample> smp =
                (java.util.List<FVDIntegrator.Sample>) b[0];
            FVDIntegrator.Sample hit = pickSampleIn(smp, screenX, screenY);
            if (hit != null) return new Object[]{ b[2], hit };
        }
        return null;
    }

    /** "Switch to editing X" (v19.7, D236, user spec): a small dark
     *  popup at the click — Switch activates that coaster with the
     *  clicked piece selected; Cancel/click-off leaves everything
     *  put. */
    private void showSwitchPrompt(Coaster c, FVDIntegrator.Sample hit,
                                  double screenX, double screenY) {
        javafx.stage.Popup pop = new javafx.stage.Popup();
        pop.setAutoHide(true);
        Label msg = new Label("Switch to editing \"" + c.name + "\"?");
        msg.setTextFill(Color.WHITE);
        Button ok = new Button("Switch");
        ok.setFocusTraversable(false);
        ok.setStyle("-fx-font-size: 11px;"
            + " -fx-background-color: rgb(78,140,220);"
            + " -fx-text-fill: white; -fx-background-radius: 4;");
        ok.setOnAction(ev -> {
            pop.hide();
            int idx = app.coasters.indexOf(c);
            if (idx < 0) return;
            app.switchCoaster(idx);
            app.editor.activateAt(hit.t());
        });
        Button cancel = new Button("Cancel");
        cancel.setFocusTraversable(false);
        cancel.setStyle("-fx-font-size: 11px;");
        cancel.setOnAction(ev -> pop.hide());
        HBox btns = new HBox(8, ok, cancel);
        btns.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, msg, btns);
        box.setPadding(new Insets(10, 14, 10, 12));
        box.setStyle("-fx-background-color: rgb(38,42,53);"
            + " -fx-background-radius: 6;"
            + " -fx-border-color: rgb(95,102,120);"
            + " -fx-border-radius: 6;");
        pop.getContent().add(box);
        pop.show(sub.getScene().getWindow(), screenX + 6, screenY + 6);
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

    /** The orbit rig's pose in WORLD coordinates — {pivot x, y, z,
     *  yaw°, pitch°, distance m}, the per-file `camera` line (v18.38,
     *  D210). Scene→world: the world group's centering translate
     *  (gridCamX0/Z0) and the scene Y-mirror undo here. An ortho pane
     *  reports its saved perspective pose; a flying pane reports the
     *  orbit pose it will return to. */
    double[] cameraWorld() {
        return new double[]{
            camPivot.getX() + gridCamX0,
            -camPivot.getY(),
            camPivot.getZ() + gridCamZ0,
            orthoView ? savedYaw : camYaw.getAngle(),
            orthoView ? savedPitch : camPitch.getAngle(),
            -(orthoView ? savedZoom : camZoom.getZ()) };
    }

    /** Apply a WORLD-coordinate pose to the orbit rig (file load /
     *  File > New's standard framing, v18.38 D210). An ortho pane
     *  takes the angles/zoom as the perspective pose it restores to
     *  (its pivot moves live — shared state). */
    void applyCameraWorld(double[] cm) {
        if (cm == null || cm.length < 6) return;
        camPivot.setX(cm[0] - gridCamX0);
        camPivot.setY(-cm[1]);
        camPivot.setZ(cm[2] - gridCamZ0);
        double z = -Math.max(1, Math.min(ORTHO_FAR_CLIP * 0.5, cm[5]));
        if (orthoView) {
            savedYaw = cm[3];
            savedPitch = cm[4];
            savedZoom = z;
            savedPivX = camPivot.getX();   // the applied pose supersedes
            savedPivY = camPivot.getY();   // the ortho-entry stash (D241)
            savedPivZ = camPivot.getZ();
        } else {
            camYaw.setAngle(cm[3]);
            camPitch.setAngle(cm[4]);
            camZoom.setZ(z);
        }
        // (the floor grid needs no nudge here since v19.12, D241 —
        // updateFloorGrid re-anchors it on the eye every frame, which
        // also retired the whole D218 "programmatic jump left the
        // grid parked" class)
    }

    private void orthoEnter(boolean top) {
        if (flyActive || povActive) return;
        if (orthoView && top && orthoTop) {   // T again in top view: exit
            exitOrthoRestorePose();
            return;
        }
        if (!orthoView) {
            savedYaw = camYaw.getAngle();
            savedPitch = camPitch.getAngle();
            savedZoom = camZoom.getZ();
            savedPivX = camPivot.getX();   // full pose (D241): ortho
            savedPivY = camPivot.getY();   // pans/centers must not move
            savedPivZ = camPivot.getZ();   // the restored fly/orbit view
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
            // plan view at ONE fixed orientation since v19.12 (D241,
            // user spec): always as if tilted down from the FRONT
            // view — world Z up the screen, X right (the old
            // nearest-90° yaw snap made the map's orientation depend
            // on where the camera happened to point)
            camPitch.setAngle(-90);
            camYaw.setAngle(0);
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

    /** Leave ortho and RESTORE the full perspective pose saved at
     *  ortho entry — angles, zoom AND pivot (v19.12, D241, user spec:
     *  every ortho→fly/orbit transition returns to where the camera
     *  was BEFORE ortho, never to the top/side direction; the pose
     *  is the entry stash, or applyCameraWorld's if one landed since). */
    void exitOrthoRestorePose() {
        if (!orthoView) return;
        camYaw.setAngle(savedYaw);
        camPitch.setAngle(savedPitch);
        camZoom.setZ(savedZoom);
        camPivot.setX(savedPivX);
        camPivot.setY(savedPivY);
        camPivot.setZ(savedPivZ);
        exitOrthoLens();
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
            if (orthoView) exitOrthoRestorePose();   // D241: back to the
                                                     // pre-ortho orbit pose
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
            povClock.reset();
            moveKeys.clear();
            povCaptured = keepCapture;
            if (povCaptured) {
                sub.setCursor(Cursor.NONE);
                app.releaseOtherCaptures(this);   // one capture (v18.10)
                // continuous confinement across the fly→POV flip
                // (v19.11, D240 — flyLook's confine-only branch)
                if (flyRobot == null) flyRobot = new Robot();
                centerFlyCursor();
            }
            if (heartMesh != null) heartMesh.setVisible(false);
            if (combGroup != null) combGroup.setVisible(false);
            if (boundaryGroup != null) boundaryGroup.setVisible(false);
            ctlDiskGroup.setVisible(false);   // disks hide in POV (D248)
            clearDiskHover();
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
            if (boundaryGroup != null) boundaryGroup.setVisible(true);
            ctlDiskGroup.setVisible(true);
            sub.setCamera(orbitCam);
            app.editor.refresh();
            toggleFly();
        } else {
            povActive = false;
            povTimer.stop();
            moveKeys.clear();
            if (heartMesh != null) heartMesh.setVisible(true);
            if (combGroup != null) combGroup.setVisible(true);
            if (boundaryGroup != null) boundaryGroup.setVisible(true);
            ctlDiskGroup.setVisible(true);
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
        app.syncCaptureWindows();   // D235: tool windows follow capture
    }

    void toggleFly() {
        if (povActive) return;
        if (!flyActive) {
            if (orthoView) exitOrthoRestorePose();   // D241: fly starts at
                                                     // the pre-ortho pose
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
            flyClock.reset();
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
        refreshGizmo(false);   // gizmos STAY in fly (v16.12) — rescale
        app.syncCaptureWindows();   // tool windows hide while captured
                                    // (v19.6, D235)
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
        // captured POV CONFINES the mouse exactly like fly since
        // v19.11 (D240, user spec) — same warp machinery, but mouse
        // movement never moves the camera (orientation follows the
        // track)
        boolean confineOnly = povActive && povCaptured;
        if (!flyActive && !confineOnly) return;
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
        if (!confineOnly) {
            flyYaw.setAngle(flyYaw.getAngle() + dx * sens);
            flyPitch.setAngle(FvdDesigner.clamp(flyPitch.getAngle() - dy * sens, -89, 89));
        }
        warpFlyCursor(c);
        if (!confineOnly) {
            positionGizmo();   // depth-based scale follows the look (v16.12)
        }
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
        long profT = Prof.t();
        try { rebuildMeshesBody(); }
        finally { Prof.add("rebuildMeshes", profT); }
    }

    private void rebuildMeshesBody() {
        List<FVDIntegrator.Sample> samples = app.lastSamples;
        if (samples == null || samples.size() < 2) {
            // EMPTY document / coaster (v19.2, D230): the old meshes go
            // — only the ground and the OTHER coasters remain
            tiePieces.clear();
            combPoints.clear();
            heartMesh = null;
            combGroup = null;
            List<javafx.scene.Node> rest = new ArrayList<>();
            List<javafx.scene.Node> restUnder = new ArrayList<>();
            rest.add(markerGroup);
            rest.add(ctlDiskGroup);   // disks (D248, item S1)
            // the glyph twins stay parented while empty (batch 37), so
            // a later refresh shows its buried glyphs
            restUnder.add(markerUnder);
            restUnder.add(ctlDiskUnder);
            boundaryGroup = null;
            boundaryUnder = null;
            for (javafx.scene.Node o : buildOtherCoasters()) {
                addSplit(rest, restUnder, o);   // ground split (D249)
            }
            trackGroup.getChildren().setAll(rest);
            trackUnder.getChildren().setAll(restUnder);
            ghostGroup.getChildren().clear();
            ghostUnder.getChildren().clear();
            markerGroup.getChildren().clear();
            markerUnder.getChildren().clear();
            ctlDiskGroup.getChildren().clear();
            ctlDiskUnder.getChildren().clear();
            ctlDisks.clear();
            ctlDiskSig = "";
            return;
        }
        computeStallRanges(samples);      // v18.0, D163: unreachable track
        computePlateRanges(samples, app.sectionSpans);   // speed-control plates

        // rails + cross ties PER PIECE (v13.2): meshes never span a
        // piece boundary (detached ends stay visually detached — a
        // clean bridge/attach seam abuts flush and reads as one track)
        // and inactive pieces render DESATURATED so the edited one pops
        List<javafx.scene.Node> parts = new ArrayList<>();
        // the stalled GHOST meshes render in ghostGroup — after the
        // floor, depth-disabled (v19.19, D248; see the constructor)
        List<javafx.scene.Node> ghostParts = new ArrayList<>();
        // the UNDERGROUND halves (v19.20, D249 — addSplit cuts every
        // track-layer mesh at the ground; a mesh with nothing
        // underground lands in `parts` untouched, so above-ground
        // files build the D246 scene graph — the under lists then
        // hold only the glyph twins, empty groups, since batch 37)
        List<javafx.scene.Node> underParts = new ArrayList<>();
        List<javafx.scene.Node> ghostUnderParts = new ArrayList<>();
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
        // SECTION-BOUNDARY DISKS (D248, item S1): one batched mesh per
        // piece, in a fresh mouse-transparent group (the comb rule)
        Group bounds = new Group();
        bounds.setMouseTransparent(true);
        // its UNDERGROUND twin (batch 37): the buried disks batch into
        // their own mesh per piece; the POV hide rides the binding
        Group boundsUnder = new Group();
        boundsUnder.setMouseTransparent(true);
        boundsUnder.visibleProperty().bind(bounds.visibleProperty());
        for (int p = 0; p < app.coaster.pieces.size(); p++) {
            int nSec = app.coaster.pieces.get(p).sections.size();
            if (nSec == 0 || base + nSec > spans.size()) {
                base += nSec;
                continue;
            }
            int b0 = base;   // the piece's first FLAT span index
            int i0 = spans.get(base).i0();
            int i1 = Math.min(spans.get(base + nSec - 1).i1(), samples.size() - 1);
            base += nSec;
            if (i1 - i0 < 1) continue;
            // the start wizard's still-PENDING piece renders nothing
            // (v19.2, D231 — it appears the moment its type is picked)
            if (app.isPendingPiece(app.activeCoaster, p)) continue;
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
                addSplit(parts, underParts,
                    buildRailMesh(sub, i0, -app.railGauge / 2, act, false));
                addSplit(parts, underParts,
                    buildRailMesh(sub, i0, app.railGauge / 2, act, false));
                TiePiece tp = new TiePiece(sub, i0, act, false,
                    new Group(), new Group());
                tiePieces.add(tp);
                parts.add(tp.group());
                underParts.add(tp.under());
            } else if (stall <= i0) {                // fully unreachable
                addSplit(ghostParts, ghostUnderParts,
                    buildRailMesh(sub, i0, -app.railGauge / 2, act, true));
                addSplit(ghostParts, ghostUnderParts,
                    buildRailMesh(sub, i0, app.railGauge / 2, act, true));
                TiePiece tp = new TiePiece(sub, i0, act, true,
                    new Group(), new Group());
                tiePieces.add(tp);
                ghostParts.add(tp.group());
                ghostUnderParts.add(tp.under());
            } else {                                 // split at the stall
                List<FVDIntegrator.Sample> alive =
                    samples.subList(i0, stall + 1);
                List<FVDIntegrator.Sample> dead =
                    samples.subList(stall, i1 + 1);
                addSplit(parts, underParts,
                    buildRailMesh(alive, i0, -app.railGauge / 2, act, false));
                addSplit(parts, underParts,
                    buildRailMesh(alive, i0, app.railGauge / 2, act, false));
                addSplit(ghostParts, ghostUnderParts,
                    buildRailMesh(dead, stall, -app.railGauge / 2, act, true));
                addSplit(ghostParts, ghostUnderParts,
                    buildRailMesh(dead, stall, app.railGauge / 2, act, true));
                TiePiece tpa = new TiePiece(alive, i0, act, false,
                    new Group(), new Group());
                TiePiece tpd = new TiePiece(dead, stall, act, true,
                    new Group(), new Group());
                tiePieces.add(tpa);
                tiePieces.add(tpd);
                parts.add(tpa.group());
                underParts.add(tpa.under());
                ghostParts.add(tpd.group());
                ghostUnderParts.add(tpd.under());
            }
            // SPEED-CONTROL PLATES (v18.0, D160): a flat plate down
            // the tie centers over every controlled stretch, colored
            // by the control type (green = speed, blue = accel)
            for (MeshView pm : buildPlateMeshes(sub)) {
                addSplit(parts, underParts, pm);
            }
            // the piece's boundary disks (the plain interior section
            // boundaries only since D249 — ViewDisks.boundarySamples)
            // — routed PER DISK by its center (batch 37)
            MeshView bd = buildBoundaryDisks(samples, spans, b0, nSec,
                false);
            if (bd != null) bounds.getChildren().add(bd);
            MeshView bdU = buildBoundaryDisks(samples, spans, b0, nSec,
                true);
            if (bdU != null) boundsUnder.getChildren().add(bdU);
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
            addSplit(parts, underParts, heartMesh);
        }
        combGroup = combs;
        if (combGroup != null) {
            combGroup.setVisible(!povActive);   // teeth block the view
            addSplit(parts, underParts, combGroup);
        }
        parts.add(markerGroup);
        underParts.add(markerUnder);   // buried markers (batch 37)
        // the disks (D248, item S1): boundary disks rebuilt here per
        // piece, the speed-control disks re-derived by the marker
        // refresh below (their sig is forced like the markers'); the
        // buried ones ride their twins in the under block (batch 37 —
        // visibility bound to the source groups set here)
        boundaryGroup = bounds;
        boundaryGroup.setVisible(!povActive);
        parts.add(boundaryGroup);
        boundaryUnder = boundsUnder;
        underParts.add(boundaryUnder);
        ctlDiskGroup.setVisible(!povActive);
        parts.add(ctlDiskGroup);
        underParts.add(ctlDiskUnder);
        // the OTHER coasters of the document (v19.2, D230): each in its
        // own SOLID rail color, never desaturated, never picked
        for (javafx.scene.Node o : buildOtherCoasters()) {
            addSplit(parts, underParts, o);
        }
        trackGroup.getChildren().setAll(parts);
        trackUnder.getChildren().setAll(underParts);
        ghostGroup.getChildren().setAll(ghostParts);
        ghostUnder.getChildren().setAll(ghostUnderParts);
        markerSig = null;                          // geometry moved: force
        ctlDiskSig = null;
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
            // (the ADAPTIVE grid + main axes are constructor-built
            // since v19.13, D242 — empty coasters and fresh panes get
            // their floor too; they sit right behind the underground
            // track halves in `world` since D249, setFloorOrder; this
            // block keeps only the one-shot centering capture)
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

    /** The document's INACTIVE coasters (v19.2, D230): rails + ties per
     *  piece in the coaster's own look — SOLID rail color whatever its
     *  rail mode (user spec), its own heart/gauge/tie spacing. The
     *  builders read the shell's look fields, so the shell's are
     *  swapped for the build and restored (the trueFelt gradient read
     *  is bypassed by forcing solid). A pending wizard piece of that
     *  coaster is skipped like the active one's. */
    private List<javafx.scene.Node> buildOtherCoasters() {
        List<javafx.scene.Node> out = new ArrayList<>();
        List<Object[]> others = app.otherCoasterRenders();
        if (others.isEmpty()) return out;
        double sh = app.heartOffset, sg = app.railGauge, st = app.tieSpacing;
        boolean ss = app.railSolid;
        Color sc = app.railColor;
        double[][] sf = app.trueFelt;
        try {
            for (Object[] o : others) {
                @SuppressWarnings("unchecked")
                List<FVDIntegrator.Sample> smp = (List<FVDIntegrator.Sample>) o[0];
                DesignIO.Cosmetics look = (DesignIO.Cosmetics) o[1];
                Coaster c = (Coaster) o[2];
                int cIdx = app.coasters.indexOf(c);
                app.heartOffset = look.heart();
                app.railGauge = look.gauge();
                app.tieSpacing = look.tiespace();
                app.railSolid = true;
                app.railColor = Color.color(look.railColor()[0],
                    look.railColor()[1], look.railColor()[2]);
                app.trueFelt = null;
                TrackCompiler.Result r = app.compiledFor(c);
                List<TrackCompiler.Span> spans = r != null ? r.spans() : null;
                if (spans == null) continue;
                int base = 0;
                Group g = new Group();
                for (int p = 0; p < c.pieces.size(); p++) {
                    int nSec = c.pieces.get(p).sections.size();
                    if (nSec == 0 || base + nSec > spans.size()) {
                        base += nSec;
                        continue;
                    }
                    int i0 = spans.get(base).i0();
                    int i1 = Math.min(spans.get(base + nSec - 1).i1(), smp.size() - 1);
                    base += nSec;
                    if (i1 - i0 < 1) continue;
                    if (app.isPendingPiece(cIdx, p)) continue;
                    List<FVDIntegrator.Sample> sub = smp.subList(i0, i1 + 1);
                    g.getChildren().add(buildRailMesh(sub, i0, -app.railGauge / 2, true, false));
                    g.getChildren().add(buildRailMesh(sub, i0, app.railGauge / 2, true, false));
                    g.getChildren().addAll(buildTieMeshes(sub, true, i0, null, false));
                }
                g.setMouseTransparent(true);
                out.add(g);
            }
        } finally {
            app.heartOffset = sh;
            app.railGauge = sg;
            app.tieSpacing = st;
            app.railSolid = ss;
            app.railColor = sc;
            app.trueFelt = sf;
        }
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
                            boolean active, boolean ghost, Group group,
                            // the UNDERGROUND half's group (v19.20,
                            // D249 — lives in trackUnder/ghostUnder;
                            // refreshTieHighlight refills both)
                            Group under) { }
    private final List<TiePiece> tiePieces = new ArrayList<>();

    // ---------- GROUND SPLIT (v19.20, D249) ----------

    /** Route a finished track-layer node: its underground half — the
     *  mesh CUT EXACTLY at world y = −FloorDepth.GROUND_DEADBAND
     *  (FloorDepth.clipAtGround since batch 37: a straddling face is
     *  split along the plane, ONE rule for every mesh; D249 routed
     *  whole faces by their centroid, which cut a ground-crossing
     *  comb tooth along its side quads' diagonal and left rail faces
     *  up to 1.2 m long on the wrong side) — goes to `under`, the
     *  node itself — keeping the part above — to `above`. A node with
     *  nothing deeper than the deadband is added to `above` UNTOUCHED
     *  (the D246 object graph for every above-ground file; a rail
     *  resting ON the ground stays one clean tube). Groups split
     *  recursively into a twin Group whose visibility BINDS to the
     *  source's (the POV toggles of heartMesh/combGroup reach both
     *  halves) and whose mouse transparency is copied. The GLYPHS —
     *  node markers, boundary / control disks, reveal balls — are not
     *  cut here: each is routed WHOLE by its center into its twin
     *  group (FloorDepth.itemUnder, batch 37 — refreshTrackMarkers,
     *  refreshCtlDisks, buildBoundaryDisks, positionGizmo). */
    static void addSplit(List<javafx.scene.Node> above,
                         List<javafx.scene.Node> under,
                         javafx.scene.Node node) {
        javafx.scene.Node u = underHalf(node);
        above.add(node);
        if (u != null) under.add(u);
    }

    /** The underground half of a node, or null when nothing of it
     *  lies below the deadband — the node is then untouched. A
     *  MeshView's half is a new MeshView over a new TriangleMesh
     *  (both meshes take the clip's grown points/texCoords — the
     *  source's own entries first, unchanged, the cut vertices
     *  appended — the under faces moved, smoothing groups mapped
     *  through each output face's SOURCE face; material/cull/draw
     *  mode/mouse flags/userData shared; the source keeps the rest).
     *  NOTE: a TriangleMesh shared by several MeshViews (none today —
     *  every builder news its own) would be cut once per view. */
    static javafx.scene.Node underHalf(javafx.scene.Node node) {
        if (node instanceof MeshView mv
                && mv.getMesh() instanceof TriangleMesh tm
                // the split reads 6 ints per face (POINT_TEXCOORD —
                // every builder's format); any other layout is left
                // whole rather than mis-indexed (D249 review belt)
                && tm.getVertexFormat()
                    == javafx.scene.shape.VertexFormat.POINT_TEXCOORD) {
            float[] pts = tm.getPoints().toArray(null);
            int[] faces = tm.getFaces().toArray(null);
            // the EXACT cut (batch 37) — null = nothing underground,
            // the mesh stays untouched
            FloorDepth.Clip c = FloorDepth.clipAtGround(pts,
                tm.getTexCoords().toArray(null), faces,
                FloorDepth.GROUND_DEADBAND);
            if (c == null) return null;
            int n = faces.length / 6;
            int[] sg = tm.getFaceSmoothingGroups().toArray(null);
            boolean withSg = sg.length == n;
            TriangleMesh um = new TriangleMesh(tm.getVertexFormat());
            // ORDER matters on both meshes: the grown points +
            // texCoords FIRST, then the faces that index them, then
            // the smoothing groups at the NEW face count (a mesh whose
            // arrays disagree renders nothing)
            um.getPoints().setAll(c.pts());
            um.getTexCoords().setAll(c.tex());
            if (tm.getNormals().size() > 0) {
                um.getNormals().setAll(tm.getNormals().toArray(null));
            }
            um.getFaces().setAll(c.under());
            tm.getPoints().setAll(c.pts());
            tm.getTexCoords().setAll(c.tex());
            tm.getFaces().setAll(c.above());
            if (withSg) {
                int[] su = new int[c.srcUnder().length];
                for (int f = 0; f < su.length; f++) su[f] = sg[c.srcUnder()[f]];
                int[] sa = new int[c.srcAbove().length];
                for (int f = 0; f < sa.length; f++) sa[f] = sg[c.srcAbove()[f]];
                um.getFaceSmoothingGroups().setAll(su);
                tm.getFaceSmoothingGroups().setAll(sa);
            }
            MeshView uv = new MeshView(um);
            uv.setMaterial(mv.getMaterial());
            uv.setCullFace(mv.getCullFace());
            uv.setDrawMode(mv.getDrawMode());
            uv.setMouseTransparent(mv.isMouseTransparent());
            uv.setUserData(mv.getUserData());
            uv.visibleProperty().bind(mv.visibleProperty());
            return uv;
        }
        if (node instanceof Group g) {
            Group ug = null;
            for (javafx.scene.Node c : g.getChildren()) {
                javafx.scene.Node u = underHalf(c);
                if (u == null) continue;
                if (ug == null) ug = new Group();
                ug.getChildren().add(u);
            }
            if (ug == null) return null;
            ug.setMouseTransparent(g.isMouseTransparent());
            ug.visibleProperty().bind(g.visibleProperty());
            return ug;
        }
        return null;
    }

    /** The world's child order around the floor (D249): the track
     *  block whose points lie on the FAR side of the ground from the
     *  eye is drawn before the floor, the other after it
     *  (FloorDepth.beforeFloor — the truth table); a scene-graph
     *  reorder only, applied on sign flips. The under block carries
     *  the GLYPH twins since batch 37: markerUnder / boundaryUnder /
     *  ctlDiskUnder inside trackUnder, trackBallUnder beside it
     *  (before ghostUnder — the ghost composites over the balls
     *  below ground as it does above). */
    private void setFloorOrder(boolean eyeBelow) {
        floorEyeBelow = eyeBelow;
        if (eyeBelow) {
            world.getChildren().setAll(trackGroup, povHolder, trackBallGroup,
                ghostGroup, gridGroup, axisGroup, trackUnder, trackBallUnder,
                ghostUnder);
        } else {
            world.getChildren().setAll(trackUnder, trackBallUnder, ghostUnder,
                gridGroup, axisGroup, trackGroup, povHolder, trackBallGroup,
                ghostGroup);
        }
    }
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
                    // the native→arc map lives on ViewDisks since
                    // D248 (item S1 — the disks share it, gated)
                    double a = ViewDisks.arcAtNative(samples, sp, nd, c.x0);
                    double b = ViewDisks.arcAtNative(samples, sp, nd,
                        c.x0 + c.len);
                    if (b - a > 0.05) {
                        plateRanges.add(new double[]{ a, b, c.type });
                    }
                }
            }
        }
    }

    // (arcAtTime — the TIME-domain native→arc lerp — moved to
    // ViewDisks.arcAtNative in D248, item S1, bitwise: the plates and
    // the speed-control disks share one gated mapping.)

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
            // the strip's own type colors (shared since D248, item S1)
            Color col = pr[2] == SpeedControl.ACCEL
                ? GraphEditor.SP_ACCEL_COLOR : GraphEditor.SP_SPEED_COLOR;
            mv.setMaterial(glowMaterial(col, 0.55));   // readable in shadow
            mv.setCullFace(CullFace.NONE);
            out.add(mv);
        }
        return out;
    }

    /** A PhongMaterial with a 1×1 self-illumination map at the color
     *  dimmed to `glow` brightness — the plates' "readable in shadow"
     *  pattern, shared by the disks (D248, item S1). */
    private static PhongMaterial glowMaterial(Color col, double glow) {
        PhongMaterial mat = new PhongMaterial(col);
        WritableImage img = new WritableImage(1, 1);
        img.getPixelWriter().setColor(0, 0, col.deriveColor(0, 1, glow, 1));
        mat.setSelfIlluminationMap(img);
        return mat;
    }

    // ---------------------------------------------------------------
    // SPEED-CONTROL DISKS + SECTION-BOUNDARY DISKS (v19.19, D248,
    // item S1 — see the field comments). Geometry: a solid disk =
    // two triangle-fan caps + a rim strip about the heartline center
    // in the plane ⊥ T (rim = U·cos θ + R·sin θ), the tie-box vertex
    // discipline (Y-flipped points, CullFace.NONE). A control disk is
    // a darker CORE plus a thin lighter outer RING ("slightly
    // stylized"); the SELECTED control's two disks wear the full type
    // color with the ring LIGHTENED by ViewDisks.CTL_SEL_RING_LIGHTEN
    // (v19.20, D249, item S3 — user spec: slightly brighter, not the
    // D248 white).
    // ---------------------------------------------------------------

    /** Shade of the type color for an unselected control's core. */
    private static final double CTL_DISK_SHADE = 0.72;
    /** Self-illumination brightness for the control disks. */
    private static final double CTL_DISK_GLOW = 0.45;
    /** Boundary disk color + its faint glow — DARKER than the
     *  light-gray D248 rgb(190,194,202)/0.40 since v19.20 (D249, user
     *  spec: darker and larger than the speed-control disks); named
     *  for a one-line retune with ViewDisks.BOUNDARY_R/HT. */
    private static final Color BOUNDARY_COLOR = Color.rgb(105, 110, 122);
    private static final double BOUNDARY_GLOW = 0.30;

    private static void addPt(TriangleMesh m, Vec3 p) {
        m.getPoints().addAll((float) p.x(), (float) -p.y(), (float) p.z());
    }

    /** Append one solid disk (radius r, half-thickness ht along T,
     *  n rim points) to `mesh`. */
    private static void addDisk(TriangleMesh mesh, Vec3 c, Vec3 T,
                                Vec3 U, Vec3 R, double r, double ht, int n) {
        int base = mesh.getPoints().size() / 3;
        Vec3 ca = c.add(T.scale(ht)), cb = c.sub(T.scale(ht));
        addPt(mesh, ca);            // base     = cap-A center
        addPt(mesh, cb);            // base + 1 = cap-B center
        for (int i = 0; i < n; i++) {   // base + 2 + i     = rim A
            double th = 2 * Math.PI * i / n;
            Vec3 off = U.scale(r * Math.cos(th)).add(R.scale(r * Math.sin(th)));
            addPt(mesh, ca.add(off));
        }
        for (int i = 0; i < n; i++) {   // base + 2 + n + i = rim B
            double th = 2 * Math.PI * i / n;
            Vec3 off = U.scale(r * Math.cos(th)).add(R.scale(r * Math.sin(th)));
            addPt(mesh, cb.add(off));
        }
        for (int i = 0; i < n; i++) {
            int ia = base + 2 + i, ia1 = base + 2 + (i + 1) % n;
            int ib = base + 2 + n + i, ib1 = base + 2 + n + (i + 1) % n;
            mesh.getFaces().addAll(base, 0, ia, 0, ia1, 0);        // cap A
            mesh.getFaces().addAll(base + 1, 0, ib1, 0, ib, 0);    // cap B
            mesh.getFaces().addAll(ia, 0, ib, 0, ia1, 0);          // rim
            mesh.getFaces().addAll(ia1, 0, ib, 0, ib1, 0);
        }
    }

    private static MeshView diskView(Vec3 c, Vec3 T, Vec3 U, Vec3 R,
                                     double r, double ht, Color col,
                                     double glow) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        addDisk(mesh, c, T, U, R, r, ht, ViewDisks.DISK_N);
        MeshView mv = new MeshView(mesh);
        mv.setMaterial(glowMaterial(col, glow));
        mv.setCullFace(CullFace.NONE);
        return mv;
    }

    /** The FLAT span index of (piece, section), or −1. */
    private int flatSpanIndex(int piece, int sec) {
        int flat = 0;
        for (int p = 0; p < app.coaster.pieces.size(); p++) {
            int n = app.coaster.pieces.get(p).sections.size();
            if (p == piece) return sec >= 0 && sec < n ? flat + sec : -1;
            flat += n;
        }
        return -1;
    }

    /** Frame at an exact GLOBAL arc inside a span (arcHit over the
     *  span's own samples plus its seam sample — ViewDisks.spanLo —
     *  so a continuation's x = 0 disk sits ON the seam). */
    private static ArcHit spanHit(List<FVDIntegrator.Sample> samples,
                                  TrackCompiler.Span sp, double arc) {
        int lo = ViewDisks.spanLo(samples, sp);
        int hi = Math.min(sp.i1(), samples.size() - 1);
        return arcHit(samples.subList(lo, hi + 1), 0, arc);
    }

    /** Rebuild the speed-control disks when anything they show
     *  changed (called on EVERY graph redraw via refreshTrackMarkers
     *  — the signature keeps hover-rate redraws free): the document
     *  generation, the sample list identity, POV, the strip's selected
     *  control, the pending-piece flags and every control's
     *  (type, x0, len). Nothing builds in POV. */
    void refreshCtlDisks() {
        List<FVDIntegrator.Sample> samples = app.lastSamples;
        List<TrackCompiler.Span> spans = app.sectionSpans;
        GraphEditor.CtlRef sel = app.editor != null
            ? app.editor.spSelectedCtl() : null;
        StringBuilder sb = new StringBuilder();
        sb.append(app.docGen).append('|')
          .append(System.identityHashCode(samples)).append('|')
          .append(povActive).append('|').append(sel);
        int flat = 0;
        for (int p = 0; p < app.coaster.pieces.size(); p++) {
            TrackPiece piece = app.coaster.pieces.get(p);
            boolean pending = app.isPendingPiece(app.activeCoaster, p);
            for (int k = 0; k < piece.sections.size(); k++) {
                Section sec = piece.sections.get(k);
                int idx = flat++;
                if (!sec.speedControllable() || sec.speedControls().isEmpty()
                        || pending) continue;
                sb.append(';').append(p).append(':').append(k).append(':')
                  .append(idx);
                for (SpeedControl c : sec.speedControls()) {
                    sb.append(',').append(c.type).append(':').append(c.x0)
                      .append(':').append(c.len);
                }
            }
        }
        String sig = sb.toString();
        if (sig.equals(ctlDiskSig)) return;
        ctlDiskSig = sig;
        ctlDiskGroup.getChildren().clear();
        ctlDiskUnder.getChildren().clear();   // the underground twin (batch 37)
        ctlDisks.clear();
        if (povActive || samples == null || samples.size() < 2
                || spans == null) return;
        flat = 0;
        for (int p = 0; p < app.coaster.pieces.size(); p++) {
            TrackPiece piece = app.coaster.pieces.get(p);
            boolean pending = app.isPendingPiece(app.activeCoaster, p);
            for (int k = 0; k < piece.sections.size(); k++) {
                Section sec = piece.sections.get(k);
                int idx = flat++;
                if (!sec.speedControllable() || sec.speedControls().isEmpty()
                        || pending || idx >= spans.size()) continue;
                TrackCompiler.Span sp = spans.get(idx);
                if (sp.i0() >= samples.size()) continue;
                boolean nd = sec.domain() == Section.Domain.DISTANCE;
                List<SpeedControl> ctls = sec.speedControls();
                for (int ci = 0; ci < ctls.size(); ci++) {
                    SpeedControl c = ctls.get(ci);
                    boolean isSel = sel != null && sel.piece() == p
                        && sel.sec() == k && sel.ctl() == ci;
                    Color base = c.type == SpeedControl.ACCEL
                        ? GraphEditor.SP_ACCEL_COLOR : GraphEditor.SP_SPEED_COLOR;
                    Color core = isSel ? base
                        : base.deriveColor(0, 1, CTL_DISK_SHADE, 1);
                    // selected ring = the type color lightened
                    // (brightness only, saturation kept — D249)
                    Color ring = isSel
                        ? base.deriveColor(0, 1, ViewDisks.CTL_SEL_RING_LIGHTEN, 1)
                        : base;
                    // END disk first, then START — spFindDiamond's
                    // pick order (the pick list IS this build order)
                    for (int kind = 2; kind >= 1; kind--) {
                        double x = kind == 1 ? c.x0 : c.x0 + c.len;
                        ArcHit h = spanHit(samples, sp,
                            ViewDisks.arcAtNative(samples, sp, nd, x));
                        // HEARTLINE center (the D176 marker rule); the
                        // disk + its ring are routed as ONE item by
                        // that center (batch 37 — a buried disk joins
                        // the before-floor block; the PICK list below
                        // is unchanged, so build order = pick order)
                        Group dst = FloorDepth.itemUnder(h.pos().y())
                            ? ctlDiskUnder : ctlDiskGroup;
                        dst.getChildren().add(diskView(h.pos(),
                            h.T(), h.U(), h.R(), ViewDisks.CTL_DISK_R,
                            ViewDisks.CTL_DISK_HT, core, CTL_DISK_GLOW));
                        dst.getChildren().add(diskView(h.pos(),
                            h.T(), h.U(), h.R(), ViewDisks.CTL_RING_R,
                            ViewDisks.CTL_RING_HT, ring, CTL_DISK_GLOW));
                        ctlDisks.add(new CtlDisk(p, k, ci, kind, h.pos(),
                            ViewDisks.rimPoints(h.pos(), h.U(), h.R(),
                                ViewDisks.CTL_RING_R, ViewDisks.RIM_N)));
                    }
                }
            }
        }
    }

    /** The disk under a screen point, or null — the WHOLE disk since
     *  v19.20 (D249, item S3): the pick region is the projected ring's
     *  bounding radius (ViewDisks.pickRadiusPx over the rim points,
     *  behind-camera rim points skipped) with the 12 px PICK_PX floor
     *  (a far disk stays a point pick); disks rank by their NORMALIZED
     *  distance d²/pickR² (ViewDisks.pickScore), strict nearest in
     *  build order (= the strip's pick order) on ties, so exactly
     *  coincident abutting disks resolve to the earlier control's END
     *  and a big near disk cannot steal a far one; a behind-camera
     *  CENTER skips the disk. */
    private CtlDisk pickCtlDisk(double sx, double sy) {
        if (ctlDisks.isEmpty() || povActive || flyActive) return null;
        double best = 1.0;   // normalized: inside the region < 1
        CtlDisk hit = null;
        for (CtlDisk d : ctlDisks) {
            Point2D s = projectWorld(d.pos());
            if (s == null) continue;
            Vec3[] rim = d.rim();
            double[] rx = new double[rim.length], ry = new double[rim.length];
            for (int k = 0; k < rim.length; k++) {
                Point2D q = projectWorld(rim[k]);
                rx[k] = q == null ? Double.NaN : q.getX();
                ry[k] = q == null ? Double.NaN : q.getY();
            }
            double pickR = ViewDisks.pickRadiusPx(s.getX(), s.getY(),
                rx, ry, ViewDisks.PICK_PX);
            double dx = s.getX() - sx, dy = s.getY() - sy;
            double score = ViewDisks.pickScore(dx * dx + dy * dy, pickR);
            if (score < best) {
                best = score;
                hit = d;
            }
        }
        return hit;
    }

    /** The disk's section-native coordinate under a screen point: the
     *  nearest point on the section's PROJECTED sample polyline (no
     *  radius cap — the cursor may leave the track), read back as an
     *  exact arc and converted through ViewDisks.nativeAtArc. NaN when
     *  nothing projects (every sample behind the camera) — the
     *  control then stays put. */
    private double trackNativeAt(CtlDisk d, double sx, double sy) {
        List<FVDIntegrator.Sample> samples = app.lastSamples;
        List<TrackCompiler.Span> spans = app.sectionSpans;
        int idx = flatSpanIndex(d.piece(), d.sec());
        if (samples == null || spans == null || idx < 0
                || idx >= spans.size()) return Double.NaN;
        TrackCompiler.Span sp = spans.get(idx);
        int lo = ViewDisks.spanLo(samples, sp);   // seam sample included
        int hi = Math.min(sp.i1(), samples.size() - 1);
        if (hi < lo) return Double.NaN;
        // strided above 3000 samples, the END sample always the last
        // vertex (ViewDisks.strideIndices — the wall stays reachable)
        int[] vi = ViewDisks.strideIndices(lo, hi, hi - lo > 3000 ? 2 : 1);
        int n = vi.length;
        double[] xs = new double[n], ys = new double[n];
        for (int k = 0; k < n; k++) {
            Point2D s = projectWorld(samples.get(vi[k]).pos());
            xs[k] = s == null ? Double.NaN : s.getX();
            ys[k] = s == null ? Double.NaN : s.getY();
        }
        double[] near = ViewDisks.nearestOnPolyline(xs, ys, sx, sy);
        if (near == null) return Double.NaN;
        int k0 = (int) near[0];
        int i = vi[k0];
        int j = vi[Math.min(k0 + 1, n - 1)];
        double arc = samples.get(i).s()
            + near[1] * (samples.get(j).s() - samples.get(i).s());
        Section sec = app.coaster.pieces.get(d.piece()).sections.get(d.sec());
        return ViewDisks.nativeAtArc(samples, sp,
            sec.domain() == Section.Domain.DISTANCE, arc);
    }

    /** Hover a disk: HAND cursor + the bottom-left readout
     *  "t = 3.20 s" / "s = 12.5 m" (v19.20, D249, item S2 — the
     *  strip's own position readout in DISPLAY units, the D248
     *  "speed control start — " prefix dropped; the value read LIVE
     *  off the model by index). Returns whether a disk took the
     *  hover. */
    private boolean diskHover(MouseEvent e) {
        CtlDisk d = diskDrag != null ? diskDrag
            : pickCtlDisk(e.getScreenX(), e.getScreenY());
        if (d == null || !diskReadout(d)) {
            clearDiskHover();
            return false;
        }
        if (!diskHovering) {
            diskHovering = true;
            sub.setCursor(Cursor.HAND);
        }
        return true;
    }

    /** Write the disk's position readout into combLabel from the LIVE
     *  model (GraphEditor.spPosReadout — display units through the
     *  strip's axis mapping); false when the control is gone. Called
     *  from the hover, from EVERY drag event (MOUSE_MOVED never fires
     *  while a button is down — the D248 label froze at the press
     *  value) and from the release (the last drag event can precede
     *  the settle); setText only, so a live drag never toggles the
     *  label's visibility per event (D249, item S2). */
    private boolean diskReadout(CtlDisk d) {
        List<TrackPiece> pcs = app.coaster.pieces;
        if (d == null || app.editor == null || d.piece() >= pcs.size()
                || d.sec() >= pcs.get(d.piece()).sections.size()) {
            return false;
        }
        Section sec = pcs.get(d.piece()).sections.get(d.sec());
        if (d.ctl() >= sec.speedControls().size()) return false;
        SpeedControl c = sec.speedControls().get(d.ctl());
        double x = d.kind() == 1 ? c.x0 : c.x0 + c.len;
        combLabel.setText(app.editor.spPosReadout(d.piece(), d.sec(), x));
        if (!combLabel.isVisible()) combLabel.setVisible(true);
        return true;
    }

    /** The disk's own NATIVE x off the live model (NaN when gone). */
    private double diskNativeX(CtlDisk d) {
        List<TrackPiece> pcs = app.coaster.pieces;
        if (d == null || d.piece() >= pcs.size()
                || d.sec() >= pcs.get(d.piece()).sections.size()) {
            return Double.NaN;
        }
        Section sec = pcs.get(d.piece()).sections.get(d.sec());
        if (d.ctl() >= sec.speedControls().size()) return Double.NaN;
        SpeedControl c = sec.speedControls().get(d.ctl());
        return d.kind() == 1 ? c.x0 : c.x0 + c.len;
    }


    /** Drop the disk hover's HAND cursor (only when it was ours). */
    private void clearDiskHover() {
        if (!diskHovering) return;
        diskHovering = false;
        if (!flyActive && !(povActive && povCaptured)) {
            sub.setCursor(Cursor.DEFAULT);
        }
    }

    /** One batched mesh of the piece's boundary disks (dark gray,
     *  faint glow, heartline centers in each sample's own frame), or
     *  null when the piece has none — one per PLAIN section boundary
     *  inside the piece (ViewDisks.boundarySamples, D249: exactly
     *  where the graph draws a single seam line; never the piece's
     *  entry/exit). `under` picks the half (batch 37): each disk is
     *  routed WHOLE by its center (FloorDepth.itemUnder), the buried
     *  ones batch into the under twin's mesh. */
    private MeshView buildBoundaryDisks(List<FVDIntegrator.Sample> samples,
                                        List<TrackCompiler.Span> spans,
                                        int b0, int nSec, boolean under) {
        List<Integer> idxs = ViewDisks.boundarySamples(samples, spans, b0, nSec);
        if (idxs.isEmpty()) return null;
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);
        int added = 0;
        for (int i : idxs) {
            FVDIntegrator.Sample sm = samples.get(i);
            if (FloorDepth.itemUnder(sm.pos().y()) != under) continue;
            addDisk(mesh, sm.pos(), sm.T(), sm.U(), sm.R(),
                ViewDisks.BOUNDARY_R, ViewDisks.BOUNDARY_HT, ViewDisks.DISK_N);
            added++;
        }
        if (added == 0) return null;
        MeshView mv = new MeshView(mesh);
        mv.setMaterial(glowMaterial(BOUNDARY_COLOR, BOUNDARY_GLOW));
        mv.setCullFace(CullFace.NONE);
        mv.setMouseTransparent(true);
        return mv;
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
            List<javafx.scene.Node> above = new ArrayList<>();
            List<javafx.scene.Node> under = new ArrayList<>();
            for (MeshView mv : buildTieMeshes(tp.samples(), tp.active(),
                    tp.base(), hi, tp.ghost())) {
                addSplit(above, under, mv);   // ground split (D249)
            }
            tp.group().getChildren().setAll(above);
            tp.under().getChildren().setAll(under);
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

    // 1.5× the historic 0.5 m tooth density since v18.48 (D220,
    // user spec) — the TIME comb's fillers follow (their count is
    // derived from this spacing); whole-second ticks stay on seconds
    static final double COMB_SPACING = 1.0 / 3; // m of track per tooth
    static final double COMB_VG_SCALE = 0.5;   // m per g (vertical)
    static final double COMB_LG_SCALE = 0.8;   // m per g (lateral)
    static final double COMB_V_SCALE = 0.05;   // m per m/s (speed)
    static final double COMB_K_SCALE = 0.5;    // m per °/m (geometric)
    static final double COMB_TIME_STEP = 0.1;  // s of ride per time tooth
    static final double COMB_TIME_LEN = 0.5;   // m — time tooth height
    static final double COMB_MAX = 3.5;        // length cap (m, at 100%)
    // the old 140% look IS the new 100% (v18.48, D220 — user spec:
    // "listed at 100%, really just making all combs 1.4x bigger")
    static final double COMB_SIZE_BASE = 1.4;

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
                updateFloorGrid();   // D241: eye-anchored adaptive floor
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
        // spec — was a 1-2-5 series). CONTINUOUS since v19.12 (D241):
        // three decades stroke per family, each at ONE pure-function-
        // of-its-own-px alpha (orthoGridAlpha) — a fresh minor decade
        // fades in from 0 instead of popping, and a demoted decade
        // keeps the exact alpha it had (the 1 m ↔ 10 m side-view
        // seam of the user's report)
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
        // TOP view strokes WHITE like the 3D views' grid (v19.14,
        // D243, user spec); the side views keep their grey-blue
        Color oc = orthoTop ? Color.rgb(235, 240, 250)
                            : Color.rgb(150, 160, 185);
        if (orthoTop) {
            gridFamily(g, new double[]{ 1, 0, 0 }, -gridCamX0, piv, right,
                down, upp, w, h, minor, oc);
            gridFamily(g, new double[]{ 0, 0, 1 }, -gridCamZ0, piv, right,
                down, upp, w, h, minor, oc);
        } else {
            // horizontal axis = the view plane's right direction; the
            // tangent sub-mode measures it from the anchored node
            // (a scene point — no world offset there)
            double o = orthoSideMode == 4
                ? tangAnchorX * right[0] + tangAnchorZ * right[2]
                : -(gridCamX0 * right[0] + gridCamZ0 * right[2]);
            gridFamily(g, right, o, piv, right, down, upp, w, h, minor, oc);
            // height lines (scene y; world y = −scene y, same spacing)
            gridFamily(g, new double[]{ 0, 1, 0 }, 0, piv, right, down,
                upp, w, h, minor, oc);
        }
    }

    /** The 2D overlay's alpha law (D241): a decade whose cells
     *  project to px pixels strokes at this alpha — 0 below 7 px,
     *  ≈0.13 (today's minors) once readable, ramping to 0.28
     *  (today's majors) as cells reach hundreds of px. Pure in px ⇒
     *  decade promotions/demotions are continuous. */
    private static double orthoGridAlpha(double px) {
        // amplitudes raised in D242 (the ortho views lost the 3D
        // grid's contribution — the overlay is the whole grid there)
        return 0.18 * gridSmooth((px - 7) / 14)
             + 0.20 * gridSmooth((px - 7) / 200);
    }

    /** One line family: planes of constant coordinate along `axis`
     *  (scene unit vector, parallel to a screen axis in every ortho
     *  mode), measured from origin scalar o. THREE decades since
     *  v19.12 (minor, 10×, 100× — the finer two skip every 10th
     *  line, the next decade owns it), each at its own continuous
     *  alpha. */
    private static void gridFamily(GraphicsContext g, double[] axis,
            double o, double[] piv, double[] right, double[] down,
            double upp, double w, double h, double minor, Color col) {
        for (int j = 0; j < 3; j++) {
            double q = minor * Math.pow(10, j);
            double a = orthoGridAlpha(q / upp);
            if (a < 0.004) continue;
            gridFamilyDecade(g, axis, o, piv, right, down, upp, w, h,
                q, a, j < 2, col);
        }
    }

    private static void gridFamilyDecade(GraphicsContext g, double[] axis,
            double o, double[] piv, double[] right, double[] down,
            double upp, double w, double h, double q, double alpha,
            boolean skipTenth, Color col) {
        double dR = axis[0] * right[0] + axis[1] * right[1] + axis[2] * right[2];
        double dD = axis[0] * down[0] + axis[1] * down[1] + axis[2] * down[2];
        double pc = axis[0] * piv[0] + axis[1] * piv[1] + axis[2] * piv[2] - o;
        boolean vertical = Math.abs(dR) > Math.abs(dD);
        double dir = vertical ? (dR > 0 ? 1 : -1) : (dD > 0 ? 1 : -1);
        double span = (vertical ? w : h) * upp / 2 + q;
        int k0 = (int) Math.floor((pc - span) / q);
        int k1 = (int) Math.ceil((pc + span) / q);
        g.setStroke(Color.color(col.getRed(), col.getGreen(),
            col.getBlue(), Math.min(1, alpha)));
        g.setLineWidth(1);
        for (int k = k0; k <= k1; k++) {
            if (skipTenth && Math.floorMod(k, 10) == 0) continue;
            double off = (k * q - pc) * dir / upp;
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
        // (v18.17, D188); it is mouse-transparent — the pane FILTER
        // below picks rig parts in screen space (v19.19, D248)
        gizmoSub.widthProperty().bind(pane.widthProperty());
        gizmoSub.heightProperty().bind(pane.heightProperty());
        pane.getChildren().add(gizmoSub);
        pane.setMinSize(0, 0);
        // SCREEN-SPACE GIZMO PICKER (D248): a capturing filter on the
        // pane — it runs before every child's handlers, consumes ONLY
        // primary presses that land in a rig part's pick band (and
        // the drag/release of that gesture), so click-off hide,
        // marker clicks, track activation and the 2D overlay
        // controls all keep their events
        pane.addEventFilter(MouseEvent.ANY, this::gizmoMouseFilter);
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
        // NL2-STYLE ORTHO GRID (v18.10, user spec): a world-aligned
        // 2D overlay for Top/Side views, behind the labels
        orthoGridCanvas.setMouseTransparent(true);
        pane.getChildren().add(1, orthoGridCanvas);
        // the side-view ground line sits right above the grid canvas
        // and UNDER the gizmo overlay + every label (batch 37 — it
        // was the LAST pane child and drew over a revealed rig; pane
        // order: sub, orthoGridCanvas, groundLine, gizmoSub, labels)
        pane.getChildren().add(2, groundLine);
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
        app.syncCaptureWindows();   // D235: reshow hidden tool windows
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
            // shortcut tags (v19.12, D241, user spec): the fixed 1-6
            // view digits + the LIVE POV binding (Space by default —
            // it flips between Fly/Orbit and POV)
            String sp = " (" + app.settings.shortcut("pov") + ")";
            // digits in DROPDOWN order since v19.13 (D242, user spec)
            String[] names = { "Fly/Orbit" + sp, "POV" + sp, "Top (1)",
                "Front (2)", "Back (3)", "Right (4)", "Left (5)",
                "Tangent to Selection (6)" };
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
                    // the restore path: full saved perspective pose
                    exitOrthoRestorePose();
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
            app.settings.combScale(), 10, 500) / 100.0 * COMB_SIZE_BASE;
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
        // THREE decimals on every comb readout (v18.48, user spec)
        combLabel.setText(switch (hit.kind()) {
            case 0 -> String.format("%.3f g vert", hit.value());
            case 1 -> String.format("%.3f g lat", hit.value());
            case 2 -> String.format("%.3f m/s  (%.3f km/h)",
                hit.value(), hit.value() * 3.6);
            case 3 -> String.format("pitch %.3f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 4 -> String.format("yaw %.3f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 5 -> String.format("elev %.3f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 6 -> String.format("heading %.3f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            case 7 -> String.format("curv %.3f °/m  %s",
                hit.value(), radiusTxt(hit.value()));
            default -> String.format("t = %.3f s", hit.value());
        });
        combLabel.setVisible(true);
    }

    /** The curvature-tooth hover's radius parenthetical (v17.18, user
     *  spec — EVERY geometric tooth reads its radius now): a zero
     *  component — straight track in that plane — reads (r=∞).
     *  Plain "=" since v18.48: the radius is the exact reciprocal of
     *  the displayed °/m value — identical accuracy, nothing
     *  approximate about the conversion. */
    private static String radiusTxt(double degPerM) {
        return Math.abs(degPerM) < 1e-6 ? "(r=∞)"
            : String.format("(r=%.3f m)", 57.29578 / Math.abs(degPerM));
    }

    // ---------- ADAPTIVE FLOOR GRID (v19.12, D241 — Blender-style,
    // replacing the fixed 1 m/10 m band tables): FOUR DECADE MESHES
    // (spacing 10^n … 10^(n+3), every 10th line skipped — the next
    // decade owns it), each split into 6 concentric alpha RINGS.
    // EVERY ring's alpha is ONE pure function of its projected cell
    // size in px — so the level cross-fade AND the radial fade both
    // emerge from the same formula with no hand-tuned tables, a mesh
    // enters/leaves at exactly alpha 0 (its brightest ring sits at
    // the visibility threshold when the base decade shifts), the
    // ortho projection (constant m/px) comes out UNIFORM with no
    // special case, and density depends ONLY on camera height/zoom —
    // never view angle. Anchored on the CAMERA EYE per frame (the
    // orbit grid used to follow the PIVOT and never updated on
    // rotate/zoom at all — the user's report), each decade group
    // translated in multiples of 10× its spacing so the pattern
    // stays world-aligned. Driven off the axes timer; per frame it
    // costs a transform read + ~24 bucketed material checks. ----------
    private static final double[] GRID_EDGE =      // ring bounds × spacing
        // GEOMETRICALLY divided INNER rings (D242, review-probed):
        // the radial falloff runs over the EYE-HEIGHT scale, which
        // for the coarser decades is a small fraction of their first
        // ring — coarse inner rings banded into visible squares.
        // Extent 384q since D245 (the reach tripled again); rings
        // whose inner edge sits past the far clip are never built.
        // The ladder LIVES on FloorDepth since v19.19 (D248) so the
        // gate sweeps the real ring bounds.
        FloorDepth.RING_EDGE;
    private static final int GRID_RINGS = GRID_EDGE.length - 1;
    // THREE decades, coarsest capped at 100 m (v19.14, D243 — user
    // spec: "only have 1 m, 10 m and 100 m grids")
    private static final int GRID_DECADES = 3;
    private static final double GRID_Q_MAX = 100;
    private final Group[] gridDecades = new Group[GRID_DECADES];
    // ONE MeshView per ring again since v19.20 (D249 — the D248 x/z
    // two-mesh split existed only for the retired depth test)
    private final MeshView[][] gridRings =
        new MeshView[GRID_DECADES][GRID_RINGS];
    private Group gridSheetGroup;
    private int gridBase = Integer.MIN_VALUE;      // n of the built meshes
    private final int[] gridAlphaBk = new int[GRID_DECADES * GRID_RINGS];
    // near-white: the fade is PREMULTIPLIED (see below), so the line
    // color at strength o is o·LINE + (1−o)·background — the old
    // (0.70,0.75,0.85) washed out against the lit blue sheet
    private static final Color GRID_LINE = Color.color(0.93, 0.95, 1.0);

    // gridSmooth / gridAlpha / gridHw + the per-ring strength LIVE ON
    // FloorDepth since v19.19 (D248 — moved verbatim, bitwise; the
    // gate evaluates the real law); these wrappers keep the call
    // sites readable
    private static double gridSmooth(double t) {
        return FloorDepth.smooth(t);
    }

    /** The one strength law: a line family whose cells cover pxEff
     *  effective pixels draws at this strength. pxEff is the
     *  projected cell size times the GRAZING factor dRef/dist (far
     *  ground is seen edge-on — its squares compress on screen,
     *  which is what actually breeds the no-mipmap shimmer forests
     *  the old band tables were hand-tuned against). The fast ramp
     *  is the minor-line fade-in, the slow one brightens majors —
     *  and the HIGH-side rolloff retires a decade as its cells pass
     *  a few hundred px: its sparse lines would only render as fat
     *  stripes there, and the next-finer decade owns that view.
     *  Pure in pxEff ⇒ a decade keeps its strength when the base
     *  re-labels — no pops. */
    private static double gridAlpha(double px, double f) {
        // f = clean-tuning proximity (1 = steep view or a
        // near-ground eye, 0 = edge-on from height): the D245
        // "reach ×3" lives at LOW f — the horizon-ward fade
        // stretches — while high f keeps the clean D244 tuning
        // (a flat ×3 threshold drop let the FINEST decade survive
        // as a mid-range speckle carpet, and near the ground the
        // deep tail flickered whole lines — both probed visually)
        return FloorDepth.gridAlpha(px, f);
    }

    /** Line HALF-width per decade spacing (q^0.6 since D243; HALVED
     *  in D244, user spec): 1 m: 1.5 cm; 10 m: 6 cm; 100 m: 24 cm. */
    private static double gridHw(double q) {
        return FloorDepth.gridHw(q);
    }

    /** Per-frame driver (the axes timer): eye + zoom → base decade,
     *  per-decade anchors, per-ring alphas — and, since v19.20
     *  (D249), the eye-side of the ground that orders the two track
     *  blocks around the floor. */
    private void updateFloorGrid() {
        if (gridGroup == null || sub == null) return;
        double subH = Math.max(1, sub.getHeight());
        double upp, dRef, ex, ez;
        double eyeY = 0;                 // SIGNED eye height (world)
        boolean persp = !orthoView;
        if (orthoView) {
            upp = orthoUpp();
            dRef = 0;
            ex = camPivot.getX() + gridCamX0;
            ez = camPivot.getZ() + gridCamZ0;
        } else {
            // the eye, uniformly for orbit/fly/POV: the active
            // camera's localToScene origin (scene + gridCam offsets =
            // world; world Y = −scene Y)
            javafx.scene.transform.Transform tr =
                sub.getCamera().getLocalToSceneTransform();
            ex = tr.getTx() + gridCamX0;
            ez = tr.getTz() + gridCamZ0;
            eyeY = -tr.getTy();
            dRef = Math.max(2, Math.abs(eyeY));
            upp = 2 * dRef
                * Math.tan(Math.toRadians(app.settings.flyFov() / 2))
                / subH;
        }
        if (!(upp > 1e-9) || !Double.isFinite(upp)) return;
        // base decade with HYSTERESIS (D242 — a raw ceil() rebuilt
        // 60 meshes every frame while an orbit zoom hovered on the
        // boundary): up when the finest decade's center cells drop
        // below 1 px, down half a decade later; the flip itself is
        // alpha-invisible either way (the swapped decade sits in
        // the dead zone at both thresholds)
        int base = gridBase == Integer.MIN_VALUE
            ? Math.max(0, (int) Math.ceil(Math.log10(upp) - 1e-12))
            : gridBase;
        while (upp > Math.pow(10, base) * (1 + 1e-12)) base++;
        while (base > 0 && upp < 0.5 * Math.pow(10, base - 1)) base--;
        // coarsest spacing is 100 m (D243) — base 2 = the 100 m grid
        // alone at extreme zoom
        base = Math.max(0, Math.min(2, base));
        if (base != gridBase) rebuildFloorGrid(base);
        double tanH = persp
            ? Math.tan(Math.toRadians(app.settings.flyFov() / 2)) : 0;
        for (int d = 0; d < GRID_DECADES; d++) {
            double q = Math.pow(10, base + d);
            if (gridDecades[d].getChildren().isEmpty()) continue;
            // quantum q, NOT 10q (D242, review find D1): ring alphas
            // are eye-centered, so a 10q quantum parked the bright
            // field up to 7q beside the camera and HOPPED it 10q per
            // step — the live "grid vanishes / pulses" report. With
            // no skipped lines (below), multiples of q keep every
            // decade world-aligned and the decenter is ≤ 0.71q.
            gridDecades[d].setTranslateX(Math.round(ex / q) * q);
            gridDecades[d].setTranslateZ(Math.round(ez / q) * q);
            for (int k = 0; k < GRID_RINGS; k++) {
                if (gridRings[d][k] == null) continue;   // past far clip
                double a;
                if (persp) {
                    // projected cell size × grazing: q/upp(dist) ·
                    // dRef/dist = q·subH·dRef/(2·dist²·tan), then
                    // the WIDTH-COVERAGE attenuation (D242, RELAXED
                    // in D243 — "make the grid go out way further; a
                    // little horizon artifact is OK"): a quad much
                    // thinner than a pixel rasterizes as dash
                    // chains, so each decade fades where its own
                    // line width goes sub-pixel (sqrt-grazing,
                    // dead below ~0.2 px, full by ~0.7); the
                    // DENSITY gate (D243): a decade whose spacing
                    // dwarfs the eye height draws huge close-up
                    // stripes — the 100 m grid now appears only
                    // once the camera is well away; GRAZING-AWARE
                    // thresholds (D245): the width floor slides
                    // 0.04 px (steep, = D244) down to 0.008 px
                    // edge-on — the horizon-ward reach roughly
                    // triples while steep views keep their clean
                    // speckle-free tuning. HEIGHT-GATED since D246
                    // (his flicker report): near the ground the
                    // deep-sub-pixel tail rasterizes as whole lines
                    // popping in/out of the MSAA lattice
                    // (stationary = entire lines missing), so the
                    // long reach only unlocks as the eye rises —
                    // smooth in height, seamless expand/contract
                    // (his own suggested trade). The arithmetic
                    // lives on FloorDepth.ringStrength since D248
                    // (verbatim — bitwise).
                    a = FloorDepth.ringStrength(q, k, dRef, subH, tanH);
                } else {
                    // ORTHO lens (D242): the 2D canvas overlay IS
                    // the grid there — the 3D line field is uniform
                    // under a parallel projection and just ended in
                    // a hard square edge over it (the user's
                    // "obvious boundaries"); only the sheet stays
                    a = 0;
                }
                // 1/256 buckets (D242 — 1/64 steps popped visibly
                // on a whole annulus during slow zooms)
                int bk = (int) Math.round(a * 256);
                if (bk != gridAlphaBk[d * GRID_RINGS + k]) {
                    gridAlphaBk[d * GRID_RINGS + k] = bk;
                    double o = bk / 256.0;
                    // PREMULTIPLIED color (D242, probe-proven): the
                    // 3D pipeline blends src·1 + dst·(1−α) — the
                    // material's alpha never dims the LINE itself,
                    // only what's behind it, so every "faded" ring
                    // rendered full brightness (the real "no fading
                    // at all"). diffuse = o·LINE with alpha o is
                    // exact OVER compositing — and a zero-strength
                    // ring must be INVISIBLE, not alpha-0 (an α=0
                    // quad still adds its full source color).
                    gridRings[d][k].setVisible(bk > 0);
                    ((PhongMaterial) gridRings[d][k].getMaterial())
                        .setDiffuseColor(Color.color(
                            GRID_LINE.getRed() * o,
                            GRID_LINE.getGreen() * o,
                            GRID_LINE.getBlue() * o, o));
                }
            }
        }
        if (gridSheetGroup != null) {
            // the sheet FOLLOWS THE EYE CONTINUOUSLY (D242): it has
            // no lines, so nothing needs world alignment — and its
            // fade rings are static relative to its center, so the
            // whole far-field vanishes smoothly with zero churn
            // (harmless again since D249: nothing depth-tests it).
            // Hidden in SIDE ortho (edge-on it's a shimmering 1 px
            // line; the groundLine overlay owns that view).
            gridSheetGroup.setVisible(!orthoView || orthoTop);
            gridSheetGroup.setTranslateX(ex);
            gridSheetGroup.setTranslateZ(ez);
        }
        // the D232 main axes ride the same eye anchor now
        updateGroundAxesLOD(ex, ez);
        // GROUND-SPLIT order (D249): the ortho lenses count as an eye
        // above ground (the top lens looks down, side hides the sheet)
        boolean below = persp && FloorDepth.eyeBelowGround(eyeY);
        if (below != floorEyeBelow) setFloorOrder(below);
    }

    /** Rebuild the four decade meshes for base level n (decade
     *  transitions only — the appearing/vanishing decade sits in the
     *  dead zone at both hysteresis thresholds, so the swap is
     *  invisible by construction). The self-fading SHEET is built
     *  once (first call) and survives rebuilds. */
    private void rebuildFloorGrid(int base) {
        gridBase = base;
        java.util.Arrays.fill(gridAlphaBk, -1);
        gridGroup.getChildren().clear();
        if (gridSheetGroup == null) {
            // ONE ±3500 m ground quad (inside the 5000 m far clip,
            // so the far-clip plane can never cut a sweeping line
            // across it — the old 24 km sheet's artifact; a graded
            // annulus taper was tried and TERRACED at grazing).
            // Historic material — the bright-blue ground look.
            TriangleMesh m = new TriangleMesh();
            m.getTexCoords().addAll(0, 0);
            addFloorQuad(m, 0, -3500, -3500, 3500, 3500, 0);
            MeshView mv = new MeshView(m);
            mv.setMaterial(new PhongMaterial(
                Color.color(0.45, 0.50, 0.60, 0.10)));
            mv.setCullFace(CullFace.NONE);
            gridSheetGroup = new Group(mv);
        }
        gridGroup.getChildren().add(gridSheetGroup);
        for (int d = 0; d < GRID_DECADES; d++) {
            double q = Math.pow(10, base + d);
            Group dec = new Group();
            gridDecades[d] = dec;
            gridGroup.getChildren().add(dec);
            if (q > GRID_Q_MAX + 1e-9) continue;   // D243: 100 m cap
            double hw = gridHw(q);
            for (int k = 0; k < GRID_RINGS; k++) {
                // never build a ring wholly past the far clip
                // (D245 — the 384q ladder at q=100 is 38 km)
                if (q * GRID_EDGE[k] > 5500) {
                    gridRings[d][k] = null;
                    continue;
                }
                // flat y = 0 for every decade (D242): the grid is
                // DEPTH-DISABLED — blending is pure submission
                // order, no coplanar z-fighting exists to dodge
                // (D248's depth-tested floor and its lifts are
                // retired, D249 — see FloorDepth).
                // skipTenth = FALSE everywhere (review find D1: the
                // skip slots live in MESH coordinates, so they only
                // land on the coarser decade's lines under 10q
                // translate quanta — and all four decades skipping
                // i==0 left the x=0/z=0 lines beyond the colored
                // axes drawn by NOBODY); the doubled line under a
                // major reads as its brighter core.
                MeshView mv = buildGridBand(0, q * GRID_EDGE[k],
                    q * GRID_EDGE[k + 1], k == GRID_RINGS - 1, q, hw,
                    Color.color(0, 0, 0, 0), false);
                mv.setVisible(false);
                gridRings[d][k] = mv;
                dec.getChildren().add(mv);
            }
        }
    }

    /** Re-fit the main-axis meshes to the camera (D232 — the fixed
     *  600 m quads SHIMMERED far away, the same sub-pixel problem the
     *  grid's fade bands solve): each axis draws only within ±240 m
     *  of the camera along itself, split into segments whose alpha
     *  falls with 2D distance from the camera (the far ends and an
     *  axis the camera is far ABEAM of fade to nothing). Quantized
     *  10 m like the grid; rebuilt only when the anchor moves. */
    private void updateGroundAxesLOD(double worldX, double worldZ) {
        if (axisGroup == null) return;
        double ax = Math.round(worldX / FloorDepth.AXIS_ANCHOR_Q)
            * FloorDepth.AXIS_ANCHOR_Q;
        double az = Math.round(worldZ / FloorDepth.AXIS_ANCHOR_Q)
            * FloorDepth.AXIS_ANCHOR_Q;
        if (ax == axisAnchorX && az == axisAnchorZ
                && !axisGroup.getChildren().isEmpty()) {
            return;
        }
        axisAnchorX = ax;
        axisAnchorZ = az;
        axisGroup.getChildren().setAll(
            buildAxisMesh(true, ax, az), buildAxisMesh(false, ax, az));
    }

    private Group axisGroup;
    private double axisAnchorX = Double.NaN, axisAnchorZ = Double.NaN;

    // segment boundaries out from the camera anchor (m) — geometric
    // past the near field, ±6.4 km total (D243); on FloorDepth since
    // v19.19 (D248) so FloorGate sweeps the real axis pieces
    private static final double[] AXIS_SEG = FloorDepth.AXIS_SEG;

    /** One main axis (D229/D232; REBUILT v19.14, D243 — user spec:
     *  the colored zero lines are the ONLY lines on the world axes
     *  and they SCALE like the grid's): xAxis = the world X axis
     *  (z = 0) in the gizmo's X green, else the Z axis in its Z red.
     *  Segments run ±6.4 km from the camera anchor in a geometric
     *  ladder; each segment's HALF-WIDTH grows with its distance
     *  (≥ the 100 m decade's line width, so the white zero lines
     *  underneath never peek out, and ~1-2 px on screen far away
     *  instead of vanishing at the old fixed 0.09 m), its strength
     *  fading smoothly to the far end. Premultiplied (D242). Quads
     *  at y = 0.005 — the D243-D246 5 mm lift over the depth-
     *  disabled grid, back since v19.20 (D249: cosmetic under pure
     *  submission order, kept for a bitwise-D246 axes render). */
    private javafx.scene.Node buildAxisMesh(boolean xAxis,
                                            double camX, double camZ) {
        Group g = new Group();
        double along = xAxis ? camX : camZ;       // camera along the axis
        double abeam = Math.abs(xAxis ? camZ : camX);   // distance off it
        Color base = xAxis ? Color.color(0.30, 0.85, 0.35)
                           : Color.color(0.95, 0.35, 0.35);
        for (int side = -1; side <= 1; side += 2) {
            for (int s = 0; s < AXIS_SEG.length - 1; s++) {
                double s0 = along + side * AXIS_SEG[s];
                double s1 = along + side * AXIS_SEG[s + 1];
                double mid = (s0 + s1) / 2;
                double d = Math.hypot(mid - along, abeam);
                double a = 0.85 * (1 - gridSmooth(d / 6400));
                if (a <= 0.02) continue;
                // ~1.5 px at its own distance, floored at the
                // historic near width; wide enough to cover the
                // grid's own zero line wherever the 100 m decade
                // shows (its lines only reach strength at range,
                // where this width has grown past theirs)
                double hw = Math.max(0.09, 0.0016 * d);
                double lo = Math.min(s0, s1), hi = Math.max(s0, s1);
                TriangleMesh m = new TriangleMesh();
                m.getTexCoords().addAll(0, 0);
                if (xAxis) addFloorQuad(m, 0, lo, -hw, hi, hw, 0.005);
                else addFloorQuad(m, 0, -hw, lo, hw, hi, 0.005);
                MeshView mv = new MeshView(m);
                // PREMULTIPLIED (D242): the 3D blend never dims the
                // source by alpha — the color must scale too
                mv.setMaterial(new PhongMaterial(Color.color(
                    base.getRed() * a, base.getGreen() * a,
                    base.getBlue() * a, a)));
                mv.setCullFace(CullFace.NONE);
                g.getChildren().add(mv);
            }
        }
        g.setMouseTransparent(true);
        return g;
    }

    // (the fixed buildGrid band tables are GONE, v19.12 D241 — the
    // adaptive decade meshes above replaced them; buildGridBand and
    // addFloorQuad live on as their geometry primitives)

    /** Lines of one weight clipped to the square ring [inner, outer). A line
     *  whose own coordinate lies inside the ring runs full length; a line
     *  closer to the centre contributes only its two flanks past `inner`.
     *  skipTenth leaves out every 10th line so the major mesh can draw it
     *  (unused since D242 — the callers pass false). */
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

    /** The overlay NEVER takes mouse events since v19.19 (D248 — the
     *  screen-space picker owns every rig part; D189's fly/POV rule
     *  is subsumed: the warped hidden cursor always hovers `sub`, so
     *  the "mouse reappears on the gizmo" symptom cannot return). Kept
     *  for its callers; pins the invariant. */
    void syncGizmoSubMouse() {
        if (gizmoSub == null) return;
        gizmoSub.setMouseTransparent(true);
    }

    // ---------------------------------------------------------------
    // SCREEN-SPACE GIZMO PICKING (v19.19, D248 — user spec: bigger,
    // predictable grab regions). The rigs' 3D geometry stays purely
    // visual; every interactive part registers a GPart (its handlers
    // + the analytic shape it was built from), and gizmoMouseFilter
    // picks on the pane in TWO TIERS since batch 37 (user spec: "grab
    // what is drawn in front" — Gestures.pickFront): (1) DEPTH —
    // among the parts DIRECTLY under the cursor (the cursor ray,
    // cursorRay, pierces the drawn sphere / shaft + cone / ring tube,
    // each radius + PICK_DIRECT_PAD_PX) the FRONT-MOST along the ray
    // wins: a ring's near arc or an arrow's emerged half drawn over
    // the center ball is grabbed there, the arrow roots inside the
    // ball and a ring's far arc behind it lose to the ball; the
    // overlay rig outranks the WORLD layer (an un-revealed track ball
    // under a rig part drawn over it); an exact depth tie goes to a
    // ball, else to the first-registered part. (2) BANDS — only when
    // nothing is directly under the cursor, the D249 rule verbatim:
    // the NEAREST part by SCREEN PIXELS among those inside their band
    // (v19.20, D249, superseding D248's balls-first two-pass): a ball
    // measures from its projected SILHOUETTE EDGE, max(0, d − rPx)
    // (Gestures.ballEdgeDist), band rPx·(1.5 − 1) + 6 px from the
    // edge = the D248 zone's outer reach; an arrow = segment distance
    // ≤ 9 px; a ring = 48-point projected polyline distance ≤ 6 px;
    // an exact tie goes to a ball, else to the first-registered part.
    // An arrow whose press would REFUSE (arrowGrabbable = the press's
    // own arrowAxis: NO half can steer — aimed at the camera, its
    // projected length under max(3 px, 6 % of its in-plane length), a
    // scale-free test; or running behind the eye plane, no screen
    // image. The +half is asked first, a two-sided arrow's mirrored
    // half second) is
    // outranked by every grabbable part and picked only when nothing
    // else is in reach. The gesture then rides gPress in the
    // filter, never the pressed node — hiding or even replacing the
    // pressed 3D node mid-drag cannot lose the drag (the D124/D188
    // capture lesson is moot). Hover glow is central too
    // (setGizmoHover), on the same rule — what lights is what a press
    // grabs.
    // ---------------------------------------------------------------
    /** One pickable part: kind 0 = ball (radius in WORLD m when rig ==
     *  null — the fixed track ball — else in rig units), 1 = arrow
     *  (dir = its live world direction, radius = its length in rig
     *  units, twoSided = the mirrored half), 2 = ring (dir = the
     *  rotation axis, radius = the torus major radius in rig units). */
    private record GPart(int kind, Rig rig, javafx.scene.Node node,
        PhongMaterial mat, Color idle, Color hot,
        java.util.function.Supplier<Vec3> anchor,
        java.util.function.Supplier<Vec3> dir,
        double radius, boolean twoSided,
        javafx.event.EventHandler<MouseEvent> onPress,
        javafx.event.EventHandler<MouseEvent> onDrag,
        javafx.event.EventHandler<MouseEvent> onRelease) { }
    private final List<GPart> gizmoParts = new ArrayList<>();
    private GPart gPress, gHover;   // the gesture's part / the lit part
    /** Pick bands (px) — the FALLBACK tier since batch 37 (they decide
     *  only when no part is directly under the cursor): arrow segment
     *  half-width, ring polyline half-width, and the ball band
     *  measured from the SILHOUETTE
     *  EDGE = projected radius × (PICK_BALL_MUL − 1) + PICK_BALL_PAD
     *  (D249 — the same outer reach as D248's center-measured zone
     *  radius × MUL + PAD; Gestures.ballEdgeBand). Visual half-widths
     *  are ~2.5 px (shaft 0.05 units) and ~1.5 px (ring tube 0.06) at
     *  a 500 px pane — the bands roughly triple them; adjacent rings
     *  (radii 1.0/0.85/0.7) overlap bands and the nearest-by-px rule
     *  resolves it. */
    static final double PICK_ARROW_PX = 9, PICK_RING_PX = 6,
        PICK_BALL_MUL = 1.5, PICK_BALL_PAD = 6;
    /** Slack (px) added to an arrow's / ring's DRAWN half-thickness
     *  when testing whether it is directly under the cursor (batch 37
     *  — what is drawn plus anti-alias slack; a ball uses its true
     *  sphere). Feel-tunable: larger values start letting an arrow
     *  root win at the ball's center. */
    static final double PICK_DIRECT_PAD_PX = 1;
    /** Arrow length in rig units (shaft 1.0 + cone tip to 1.32) —
     *  Gestures' own, so drawn == picked (batch 37). */
    static final double ARROW_LEN = Gestures.ARROW_LEN;
    /** Ring polyline resolution — the torus's own nu (torus()). */
    private static final int RING_PICK_N = 48;

    /** Registration site for gizmoArrow's build data, read back by
     *  wireAxisDrag (the arrow Group's userData). */
    private record ArrowMeta(PhongMaterial mat, Color col,
        java.util.function.Supplier<Vec3> dir, boolean twoSided) { }

    /** True when a mouse event's target is the 3D content — `sub`,
     *  the overlay, or any node under root3D — so the picker never
     *  touches the 2D overlay controls (view label, tangent buttons,
     *  the Gizmos button, the graph-collapse button). */
    private boolean targetIs3D(javafx.event.EventTarget tg) {
        if (tg == sub || tg == gizmoSub || tg == pane) return true;
        if (!(tg instanceof javafx.scene.Node n)) return false;
        javafx.scene.Node top = n;
        while (top.getParent() != null) top = top.getParent();
        return top == root3D;
    }

    /** The pane-level capturing filter: hover on MOVED, gesture on
     *  PRESSED/DRAGGED/RELEASED. Fly and POV take nothing (fly: the
     *  D189 rule — gizmos render but are not interactive; POV: the
     *  sig is "off" anyway). */
    private void gizmoMouseFilter(MouseEvent e) {
        javafx.event.EventType<? extends MouseEvent> t = e.getEventType();
        if (flyActive || povActive) {
            if (gHover != null) setGizmoHover(null);
            // a gesture that was LIVE when fly/POV began (a right-
            // click or Space mid-drag — sub's handlers still see
            // non-primary presses and bare keys) must still END on
            // its release (D248 review fix: the early return left
            // gPress + gizmoDragging set for good — every later press
            // was ignored and the shell's modifier-key guard stayed
            // armed); its remaining drag events are swallowed, the
            // warped fly cursor is no pointer
            if (gPress != null && t == MouseEvent.MOUSE_RELEASED) {
                GPart p = gPress;
                gPress = null;
                p.onRelease().handle(e);
                e.consume();
            } else if (gPress != null && t == MouseEvent.MOUSE_DRAGGED) {
                e.consume();
            }
            return;
        }
        if (t == MouseEvent.MOUSE_MOVED) {
            setGizmoHover(gPress == null && targetIs3D(e.getTarget())
                ? pickPart(e.getScreenX(), e.getScreenY()) : null);
            return;
        }
        if (t == MouseEvent.MOUSE_EXITED) {
            // the PANE itself was left (at the target the _TARGET
            // variant arrives as plain MOUSE_EXITED; leaving `sub`
            // for a 2D overlay child is a MOUSE_EXITED_TARGET on the
            // child's behalf, and the next MOVED over it clears the
            // glow anyway)
            setGizmoHover(null);
            return;
        }
        if (t == MouseEvent.MOUSE_PRESSED) {
            if (e.getButton() != MouseButton.PRIMARY || gPress != null
                    || !targetIs3D(e.getTarget())) return;
            GPart hit = pickPart(e.getScreenX(), e.getScreenY());
            if (hit == null) return;
            // pull focus off toolbar editors like sub's own press
            // handler does (v13.4) — this press never reaches it
            sub.requestFocus();
            // GIZMO SELECT DESELECTS THE GRAPH NODE (v19.19, D248,
            // item S1, user rule): every white ball and every rig
            // part press clears selIdx/selSet — view state, so it
            // runs BEFORE the convert gate (the entry ball's D247
            // node-0 select is gone: a ball click selects nothing).
            // SAFE mid-press (batch 37): the press TARGET is always
            // `sub` — `world` and the overlay are mouse-transparent —
            // so this redraw may rebuild the node markers, the control
            // disks or even every mesh under the press without
            // orphaning the gesture (a JavaFX target removed during
            // the MOUSE_PRESSED dispatch loses every DRAGGED and the
            // RELEASED — see the constructor), and the gesture itself
            // rides gPress, never a node
            if (app.editor != null) app.editor.deselectFromViewport();
            // a DENSITY PREVIEW owns the document (D247): the part
            // handlers refuse silently; the picker posts the reason
            // once per refused press (D235 — never a silent click)
            if (app.convertBusy()) {
                app.statsLabel.setText(FvdDesigner.CONVERT_BUSY_MSG);
                e.consume();
                return;
            }
            gPress = hit;
            hit.onPress().handle(e);
            e.consume();
            return;
        }
        if (gPress == null) return;
        if (t == MouseEvent.MOUSE_DRAGGED) {
            gPress.onDrag().handle(e);
            e.consume();
        } else if (t == MouseEvent.MOUSE_RELEASED) {
            GPart p = gPress;
            gPress = null;
            p.onRelease().handle(e);
            e.consume();
        }
    }

    /** Light one part (rings/arrows: color toward white 0.5; balls:
     *  the warm rgb(255,240,170)), restoring the previous one. */
    private void setGizmoHover(GPart h) {
        if (h == gHover) return;
        if (gHover != null) gHover.mat().setDiffuseColor(gHover.idle());
        gHover = h;
        if (h != null) h.mat().setDiffuseColor(h.hot());
    }

    /** Forget every registered part (a rebuild discards their nodes
     *  and materials); `rigOnly` keeps the fixed track balls — the
     *  drag-reveal's rig-only build. The lit part goes with them; the
     *  PRESSED part is kept: the live gesture rides its closures. */
    private void clearGizmoParts(boolean rigOnly) {
        if (rigOnly) {
            gizmoParts.removeIf(p -> p.rig() != null);
            if (gHover != null && gHover.rig() != null) gHover = null;
        } else {
            gizmoParts.clear();
            gHover = null;
        }
    }

    /** World m per screen px at a world point: the ortho zoom's, or
     *  the perspective lens at that point's view DEPTH (wireBall's
     *  press formula). */
    private double uppAt(Vec3 a) {
        javafx.scene.Camera cam = sub.getCamera();
        if (cam == orthoCam) return orthoUpp();
        Point3D eye0 = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye0).normalize();
        Point3D as = world.localToScene(a.x(), -a.y(), a.z());
        double depth = Math.max(1, as.subtract(
            effEye(cam, eye0, fwd)).dotProduct(fwd));
        return 2 * depth * Math.tan(Math.toRadians(camFov() / 2))
            / Math.max(1, sub.getHeight());
    }

    /** The pick ray through a SCREEN point in WORLD coords (Vec3, Y
     *  up): {origin, unit dir} — ringHit's construction (batch 37):
     *  the perspective lens, or TRUE ortho with the origin ON the
     *  projection plane (a part may then sit at a negative ray
     *  parameter). `world` carries only a translate, so scene → world
     *  is rigid (y negated). Null when the point maps nowhere. */
    private Vec3[] cursorRay(double sx, double sy) {
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        Point2D local = sub.screenToLocal(sx, sy);
        if (local == null) return null;
        Point3D dir, orig;
        if (cam == orthoCam) {
            orig = cam.localToScene(local.getX(), local.getY(), 0);
            dir = fwd;
        } else {
            Point3D right = cam.localToScene(1, 0, 0).subtract(eye).normalize();
            Point3D down = cam.localToScene(0, 1, 0).subtract(eye).normalize();
            double f = (sub.getHeight() / 2)
                / Math.tan(Math.toRadians(camFov() / 2));
            dir = fwd.multiply(f)
                .add(right.multiply(local.getX() - sub.getWidth() / 2))
                .add(down.multiply(local.getY() - sub.getHeight() / 2))
                .normalize();
            orig = eye;
        }
        Point3D o = world.sceneToLocal(orig);
        Point3D o1 = world.sceneToLocal(orig.add(dir));
        Vec3 d = new Vec3(o1.getX() - o.getX(), -(o1.getY() - o.getY()),
            o1.getZ() - o.getZ()).normalize();
        if (!(d.length() > 0.5)) return null;   // degenerate / NaN
        return new Vec3[] { new Vec3(o.getX(), -o.getY(), o.getZ()), d };
    }

    /** The part under a screen point, or null — TWO TIERS since batch
     *  37 (Gestures.pickFront): (1) among the visible parts DIRECTLY
     *  under the cursor (partFrontDepth — the cursor ray pierces the
     *  drawn shape) the FRONT-MOST wins, the overlay rig before the
     *  world-layer track balls; (2) when nothing is directly under
     *  the cursor, the D249 rule verbatim: the NEAREST by px among
     *  those inside their band, a ball measured from its projected
     *  silhouette EDGE (partDistPx / partBandPx), an exact tie to a
     *  ball, else to the first-registered part (the center ball
     *  registers LAST — addRigCenterBall after the arrows/rings).
     *  PASS 0 leaves out every arrow whose press would refuse
     *  (arrowGrabbable — aimed at the camera, its cone drawn over the
     *  ball's center: projected length under max(3 px, 6 % of its
     *  in-plane length), whatever the zoom — or running behind the
     *  eye plane; a two-sided arrow is left out only when NEITHER
     *  half can steer, arrowAxis): a grabbable part in reach takes
     *  the press
     *  instead of the arrow swallowing it; pass 1 runs only when pass
     *  0 found nothing and an arrow was left out — the D249 outcome.
     *  No ray (cursorRay null) = every depth NaN = the D249 rule. */
    private GPart pickPart(double sx, double sy) {
        Vec3[] ray = cursorRay(sx, sy);
        boolean persp = sub.getCamera() != orthoCam;
        for (int pass = 0; pass < 2; pass++) {
            List<GPart> live = new ArrayList<>();
            List<Gestures.PickCand> cand = new ArrayList<>();
            boolean skipped = false;
            for (GPart p : gizmoParts) {
                if (!p.node().isVisible()) continue;
                if (pass == 0 && p.kind() == 1 && !arrowGrabbable(p)) {
                    skipped = true;
                    continue;
                }
                double depth = ray == null ? Double.NaN
                    : partFrontDepth(p, ray[0], ray[1], persp);
                live.add(p);
                // LAYER: rig == null = a fixed track ball in `world`,
                // drawn UNDER the overlay's rig parts
                cand.add(new Gestures.PickCand(p.kind(),
                    p.rig() == null ? 1 : 0, partDistPx(p, sx, sy),
                    partBandPx(p), depth));
            }
            int i = Gestures.pickFront(cand);
            if (i >= 0) return live.get(i);
            if (!skipped) return null;
        }
        return null;
    }

    /** A part's FALLBACK band (px): the arrow / ring half-widths, a
     *  ball's band from its silhouette edge (Gestures.ballEdgeBand). */
    private double partBandPx(GPart p) {
        if (p.kind() == 1) return PICK_ARROW_PX;
        if (p.kind() == 2) return PICK_RING_PX;
        Vec3 a = p.anchor().get();
        double rW = p.rig() == null ? p.radius()
                                    : p.radius() * p.rig().scale;
        return Gestures.ballEdgeBand(rW / uppAt(a), PICK_BALL_MUL,
            PICK_BALL_PAD);
    }

    /** Ray parameter of the part's FRONT surface under the cursor,
     *  NaN when the cursor is not on its drawn shape: a ball = its
     *  true sphere; an arrow = shaft + cone, a ring = its tube, each
     *  radius + PICK_DIRECT_PAD_PX (converted at the anchor's depth).
     *  Perspective drops anything behind the eye. */
    private double partFrontDepth(GPart p, Vec3 o, Vec3 d, boolean persp) {
        Vec3 a = p.anchor().get();
        double t;
        if (p.kind() == 0) {
            t = Gestures.raySphereFront(o, d, a, p.rig() == null
                ? p.radius() : p.radius() * p.rig().scale);
        } else {
            Vec3 ax = p.dir().get().normalize();
            if (!(ax.length() > 0.5)) return Double.NaN;
            double sc = p.rig().scale;
            double pad = PICK_DIRECT_PAD_PX * uppAt(a);
            t = p.kind() == 1
                ? Gestures.arrowFront(o, d, a, ax, sc, p.twoSided(), pad)
                : Gestures.ringFront(o, d, a, ax, p.radius() * sc,
                    Gestures.RING_TUBE_R * sc + pad, RING_PICK_N);
        }
        return persp && !(t > 0) ? Double.NaN : t;
    }

    /** Whether an arrow's press would start a drag — wireAxisDrag's
     *  own test, the SAME call (arrowAxis): what lights is what a
     *  press grabs. */
    private boolean arrowGrabbable(GPart p) {
        return arrowAxis(p.rig(), p.dir().get(), p.twoSided()).why()
            == AX_OK;
    }

    /** An arrow foreshortened to under this fraction of its drawn
     *  length (aimed within ~3.4° of the view direction) cannot be
     *  dragged — its screen axis is too short to steer by. */
    static final double ARROW_GRAB_MIN = 0.06;

    /** Can the arrow HALF drawn along `dir` steer a drag right now?
     *  SCALE-FREE since batch 37: the half's own projected length
     *  against the length it would show lying in the view plane (the
     *  rig keeps a constant apparent size), floored at 3 px; a half
     *  whose tip lies behind the eye plane has no image to steer by.
     *  The test it replaces measured a 1 m world vector (`< 2 px`),
     *  which also refused EVERY arrow once 1 m projected under 2 px —
     *  about 134 m of view depth at the default pane, i.e. the
     *  startup framing. arrowAxis asks it per half and measures the
     *  drag law's screen axis. */
    private boolean arrowDraggable(Rig rig, Vec3 dir) {
        if (rig == null || dir == null || dir.length() < 1e-9) return false;
        Vec3 a = rig.anchor.get();
        double len = rig.scale * ARROW_LEN;   // the drawn length, world m
        Point2D p0 = projectWorld(a);
        Point2D p1 = projectWorld(a.add(dir.normalize().scale(len)));
        if (p0 == null || p1 == null) return false;
        double full = len / uppAt(a);         // px, if it lay in the view plane
        return Math.hypot(p1.getX() - p0.getX(), p1.getY() - p0.getY())
            >= Math.max(3, ARROW_GRAB_MIN * full);
    }

    /** arrowAxis's outcomes: a drag starts, or why the press refuses —
     *  the arrow is foreshortened (it points at the camera; a
     *  one-sided arrow may point straight away from it instead), it
     *  runs behind the eye plane, or it has no direction at all. */
    private static final int AX_OK = 0, AX_FACING = 1, AX_BEHIND = 2,
        AX_NONE = 3, AX_AWAY = 4;

    /** What a refused arrow press says on the stats bar, per outcome
     *  (D235 — never a silent click; each is true for its case). */
    static final String ARROW_FACING_MSG = "That arrow points at the camera"
        + " — orbit a little to drag it.";
    static final String ARROW_AWAY_MSG = "That arrow points away from the"
        + " camera — orbit a little to drag it.";
    static final String ARROW_BEHIND_MSG = "That arrow runs behind the"
        + " camera — back away a little to drag it.";
    static final String ARROW_NONE_MSG = "That arrow has no direction to"
        + " drag along right now.";

    /** An arrow's press-time SCREEN AXIS: the image of 1 m along +dir
     *  in px (sx, sy) when why == AX_OK, else only the reason. */
    private record ArrowAxis(int why, double sx, double sy) {
        String refusal() {
            return why == AX_BEHIND ? ARROW_BEHIND_MSG
                : why == AX_AWAY ? ARROW_AWAY_MSG
                : why == AX_NONE ? ARROW_NONE_MSG : ARROW_FACING_MSG;
        }
    }

    /** A world point's VIEW DEPTH: metres in front of the eye plane
     *  along the camera's forward axis — projectWorld's own filter
     *  (≤ 0 = behind the camera, no screen image). */
    private double viewDepth(Vec3 p) {
        javafx.scene.Camera cam = sub.getCamera();
        Point3D eye = cam.localToScene(0, 0, 0);
        Point3D fwd = cam.localToScene(0, 0, 1).subtract(eye).normalize();
        return world.localToScene(p.x(), -p.y(), p.z())
            .subtract(effEye(cam, eye, fwd)).dotProduct(fwd);
    }

    /** THE arrow press test + the drag law's screen axis (batch 37
     *  review) — one call for the press (wireAxisDrag) and for the
     *  picker's pass 0 (arrowGrabbable).
     *  WHICH HALF: the +dir half when it can steer (arrowDraggable).
     *  A TWO-SIDED arrow whose +dir half cannot — its tip behind the
     *  eye plane, or foreshortened — is measured on its mirrored half
     *  when that one can; the axis keeps its sign, so λ stays
     *  positive along +dir. A one-sided arrow (the bridge LENGTH
     *  tangent) has no other half.
     *  THE MEASURING STEP, by VIEW DEPTH: 1 m — the historic image,
     *  bitwise — wherever the 1 m point sits comfortably in front of
     *  the eye (at least half the anchor's view depth; always under
     *  the parallel lens, which has no perspective divide). Nearer
     *  the eye plane the divide blows a 1 m chord up (a point 3 mm in
     *  front of the eye projects ~49,000 px out: 40 px of mouse moved
     *  the piece 0.8 mm), so the step shrinks to one the drawn arrow
     *  covers — half its length (1 m at most), whose far end lies
     *  beyond half the anchor's depth whenever the half's tip
     *  projects, so its image is at most twice the local px per
     *  metre at the anchor — and scales back to px per metre. The
     *  mirrored half always measures over that step. */
    private ArrowAxis arrowAxis(Rig rig, Vec3 d, boolean twoSided) {
        if (rig == null || d == null || d.length() < 1e-9) {
            return new ArrowAxis(AX_NONE, 0, 0);
        }
        Vec3 a = rig.anchor.get();
        boolean plus = arrowDraggable(rig, d);
        if (!plus && !(twoSided && arrowDraggable(rig, d.scale(-1)))) {
            // nothing to steer by — say which way. BEHIND: the anchor,
            // or a one-sided arrow's tip, has no screen image. Else a
            // half projects and is too short: the axis lies along the
            // line of sight — a two-sided arrow always has a half
            // toward the eye (with the anchor in front of it one tip
            // lies deeper still, the other nearer), a one-sided one
            // may point straight away instead
            Vec3 tip = a.add(d.normalize().scale(rig.scale * ARROW_LEN));
            int why = projectWorld(a) == null
                    || (!twoSided && projectWorld(tip) == null) ? AX_BEHIND
                : !twoSided && viewDepth(tip) > viewDepth(a) ? AX_AWAY
                : AX_FACING;
            return new ArrowAxis(why, 0, 0);
        }
        double h = 1;
        Point2D p0 = projectWorld(a);
        Point2D p1;
        if (!plus) {
            h = -Math.min(1, 0.5 * rig.scale * ARROW_LEN);
            p1 = projectWorld(a.add(d.scale(h)));
        } else if (sub.getCamera() != orthoCam
                && viewDepth(a.add(d)) < 0.5 * viewDepth(a)) {
            h = Math.min(1, 0.5 * rig.scale * ARROW_LEN);
            p1 = projectWorld(a.add(d.scale(h)));
        } else {
            p1 = projectWorld(a.add(d));
        }
        if (p0 == null || p1 == null) return new ArrowAxis(AX_BEHIND, 0, 0);
        double sx = (p1.getX() - p0.getX()) / h;
        double sy = (p1.getY() - p0.getY()) / h;
        return sx * sx + sy * sy > 1e-12 ? new ArrowAxis(AX_OK, sx, sy)
            : new ArrowAxis(AX_FACING, 0, 0);
    }

    /** Point-to-segment distance in screen px. */
    private static double segDistPx(Point2D a, Point2D b,
                                    double sx, double sy) {
        double vx = b.getX() - a.getX(), vy = b.getY() - a.getY();
        double wx = sx - a.getX(), wy = sy - a.getY();
        double vv = vx * vx + vy * vy;
        double u = vv < 1e-12 ? 0
            : FvdDesigner.clamp((wx * vx + wy * vy) / vv, 0, 1);
        return Math.hypot(wx - u * vx, wy - u * vy);
    }

    /** Screen distance to an arrow's projected shaft (both halves when
     *  two-sided); behind-camera ends skip their segment. */
    private double arrowDistPx(GPart p, double sx, double sy) {
        Vec3 a = p.anchor().get();
        Vec3 d = p.dir().get().normalize();
        if (d.length() < 0.5) return Double.MAX_VALUE;
        double len = p.radius() * p.rig().scale;
        Point2D s0 = projectWorld(a);
        if (s0 == null) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        Point2D s1 = projectWorld(a.add(d.scale(len)));
        if (s1 != null) best = segDistPx(s0, s1, sx, sy);
        if (p.twoSided()) {
            Point2D s2 = projectWorld(a.sub(d.scale(len)));
            if (s2 != null) best = Math.min(best, segDistPx(s0, s2, sx, sy));
        }
        return best;
    }

    /** Screen distance to a ring's projected centerline: the circle
     *  of radius R·rig.scale about the anchor ⊥ the axis, walked as
     *  a RING_PICK_N-point closed polyline (the torus's own nu). */
    private double ringDistPx(GPart p, double sx, double sy) {
        if (p.dir().get().normalize().length() < 0.5) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        Point2D prev = null;
        for (int i = 0; i <= RING_PICK_N; i++) {
            Point2D s = projectWorld(ringPoint(p, 2 * Math.PI * i / RING_PICK_N));
            if (prev != null && s != null) {
                best = Math.min(best, segDistPx(prev, s, sx, sy));
            }
            prev = s;
        }
        return best;
    }

    /** A ring part's centerline point at angle th (world): anchor +
     *  R·rig.scale·(cos th·u + sin th·v), u/v any basis ⊥ the axis. */
    private static Vec3 ringPoint(GPart p, double th) {
        Vec3 c = p.anchor().get();
        Vec3 ax = p.dir().get().normalize();
        Vec3 h = Math.abs(ax.y()) < 0.9 ? new Vec3(0, 1, 0)
                                        : new Vec3(1, 0, 0);
        Vec3 u = ax.cross(h).normalize();
        Vec3 v = ax.cross(u);
        double R = p.radius() * p.rig().scale;
        return c.add(u.scale(R * Math.cos(th))).add(v.scale(R * Math.sin(th)));
    }

    /** DEV PROBE (v19.19, D248 — `-Dfvd.pickprobe=1`, driven by
     *  FvdDesigner.pickProbe on the live default document): (a) every
     *  rig part kind registers (track ball, center ball, 3 two-sided
     *  arrows, the yaw ring); (b) the pick bands — a point inside
     *  each part's band picks THAT part, the rig center picks the
     *  ball over the arrow roots, a point 3× the ring band off a ring
     *  picks nothing; (b2, batch 37) the DEPTH rule — the ray canary
     *  (default view, zoomed, after a pitch change), an arrow's
     *  emerged near half over the ball picks the arrow and its far
     *  half the ball, the yaw ring's near arc over the ball picks the
     *  ring and its far arc the ball (probeDepth); (d, batch 37) the
     *  same under the true-ortho Top lens (probeOrtho, run last);
     *  (c) synthetic mouse events through the pane
     *  filter: a press + drag on the UN-REVEALED track ball reveals
     *  the rig mid-gesture with the pressed ball HIDDEN and the drag
     *  still alive (the piece moves per event), the release rebuilds
     *  (ball hidden while its rig shows, one visible center ball);
     *  then a ring press + tangent drag + release turns the yaw. All
     *  in-process, no Robot — screen-space math on the real panes.
     *  Returns the failure count; prints PICKPROBE lines. */
    int pickProbe(java.io.PrintStream out) {
        int fails = 0;
        TrackPiece p0 = app.coaster.pieces.get(0);
        gizmoReveal = new Reveal(0, 1);
        refreshGizmo(true);
        int balls = 0, arrows = 0, rings = 0;
        for (GPart g : gizmoParts) {
            if (g.kind() == 0) balls++;
            else if (g.kind() == 1) arrows++;
            else rings++;
        }
        out.println("PICKPROBE parts balls=" + balls + " arrows=" + arrows
            + " rings=" + rings + " pane=" + pane.getWidth() + "x"
            + pane.getHeight());
        if (balls != 2 || arrows != 3 || rings < 1) {
            out.println("PICKPROBE FAIL registration (expect 2 balls, 3 arrows, >=1 ring)");
            fails++;
        }
        // (b) bands
        for (GPart g : gizmoParts) {
            if (!g.node().isVisible()) continue;   // the hidden track ball
            Vec3 a = g.anchor().get();
            Point2D s0 = projectWorld(a);
            if (s0 == null) {
                out.println("PICKPROBE FAIL part behind camera kind=" + g.kind());
                fails++;
                continue;
            }
            double px, py;
            String what;
            if (g.kind() == 0) {
                double rPx = g.radius() * g.rig().scale / uppAt(a);
                px = s0.getX() + rPx * PICK_BALL_MUL + PICK_BALL_PAD - 1;
                py = s0.getY();
                what = "ball zone edge (rPx=" + String.format("%.1f", rPx) + ")";
            } else if (g.kind() == 1) {
                Vec3 d = g.dir().get().normalize();
                Point2D s1 = projectWorld(a.add(d.scale(0.8 * g.radius()
                    * g.rig().scale)));
                if (s1 == null) continue;
                double vx = s1.getX() - s0.getX(), vy = s1.getY() - s0.getY();
                double L = Math.max(1e-9, Math.hypot(vx, vy));
                px = s1.getX() - vy / L * (PICK_ARROW_PX - 2);
                py = s1.getY() + vx / L * (PICK_ARROW_PX - 2);
                what = "arrow 7 px off its shaft";
            } else {
                // a ring point away from the ball zone, 4 px outward
                // (45° — the θ=0 point lies ON the −Z arrow: u = Y×X)
                Point2D s1 = projectWorld(ringPoint(g, Math.PI / 4));
                if (s1 == null) continue;
                double vx = s1.getX() - s0.getX(), vy = s1.getY() - s0.getY();
                double L = Math.max(1e-9, Math.hypot(vx, vy));
                px = s1.getX() + vx / L * (PICK_RING_PX - 2);
                py = s1.getY() + vy / L * (PICK_RING_PX - 2);
                what = "ring 4 px outside its line";
                GPart far = pickPart(s1.getX() + vx / L * PICK_RING_PX * 3,
                                     s1.getY() + vy / L * PICK_RING_PX * 3);
                if (far != null && far.kind() == 2 && far == g) {
                    out.println("PICKPROBE FAIL ring picked 18 px off");
                    fails++;
                }
            }
            GPart hit = pickPart(px, py);
            // the rule under test: the NEAREST part inside its band —
            // where two bands overlap (an arrow crossing a ring) the
            // closer one wins, and that is correct; so is a DIFFERENT
            // part drawn directly under the point (batch 37, tier 1)
            boolean nearest = hit != null && hit != g && hit.kind() != 0
                && partDistPx(hit, px, py) <= partDistPx(g, px, py) + 1e-9;
            Vec3[] pr = cursorRay(px, py);
            boolean front = hit != null && hit != g && pr != null
                && !Double.isNaN(partFrontDepth(hit, pr[0], pr[1],
                    sub.getCamera() != orthoCam));
            boolean ok = hit == g || nearest || front;
            out.println("PICKPROBE band " + what + " -> "
                + (hit == null ? "null" : "kind " + hit.kind())
                + (hit == g ? " OK" : nearest ? " OK (nearer part)"
                    : front ? " OK (part in front)" : " FAIL"));
            if (!ok) fails++;
        }
        {
            Point2D c = projectWorld(gizmoRigs.get(0).anchor.get());
            GPart hit = c == null ? null : pickPart(c.getX(), c.getY());
            boolean ok = hit != null && hit.kind() == 0;
            out.println("PICKPROBE center (arrow roots cross it) -> "
                + (hit == null ? "null" : "kind " + hit.kind())
                + (ok ? " OK" : " FAIL"));
            if (!ok) fails++;
        }
        // (b2) DEPTH (batch 37): what is drawn in FRONT under the
        // cursor wins — the rig still revealed; the camera pose is
        // restored before (c), whose numbers depend on the view
        fails += probeDepth(out);
        // (c) drag-reveal through the filter
        gizmoReveal = null;
        refreshGizmo(true);
        Sphere track = revealMarks.get(0).ball();
        Point2D bc = projectWorld(revealMarks.get(0).at().get());
        if (bc == null || !track.isVisible()) {
            out.println("PICKPROBE FAIL track ball not visible/projectable");
            return fails + 1;
        }
        double x0 = p0.x, y0 = p0.y;
        fire(MouseEvent.MOUSE_PRESSED, bc.getX(), bc.getY(), true);
        boolean pressed = gPress != null && gPress.kind() == 0 && gPress.rig() == null;
        fire(MouseEvent.MOUSE_DRAGGED, bc.getX() + 2, bc.getY(), true);
        boolean stillVis = track.isVisible() && gizmoReveal == null;   // deadband
        fire(MouseEvent.MOUSE_DRAGGED, bc.getX() + 30, bc.getY() + 20, true);
        boolean revealed = gizmoReveal != null && !track.isVisible()
            && gizmoRigs.size() == 1 && gizmoDragging;
        boolean moved1 = p0.x != x0 || p0.y != y0;
        fire(MouseEvent.MOUSE_DRAGGED, bc.getX() + 60, bc.getY() + 20, true);
        boolean moved2 = (p0.x != x0 || p0.y != y0) && gizmoDragging;
        fire(MouseEvent.MOUSE_RELEASED, bc.getX() + 60, bc.getY() + 20, false);
        int visBalls = 0;
        for (GPart g : gizmoParts) if (g.kind() == 0 && g.node().isVisible()) visBalls++;
        boolean released = !gizmoDragging && gPress == null
            && gizmoReveal != null && !revealMarks.get(0).ball().isVisible()
            && visBalls == 1;
        out.println("PICKPROBE dragReveal press=" + pressed + " deadband="
            + stillVis + " revealedHidden=" + revealed + " moved=" + moved1
            + "/" + moved2 + " released=" + released
            + " dx=" + String.format("%.3f", p0.x - x0));
        if (!(pressed && stillVis && revealed && moved1 && moved2 && released)) fails++;
        // ring drag: the yaw ring, tangent travel
        GPart ring = null;
        for (GPart g : gizmoParts) if (g.kind() == 2) { ring = g; break; }
        if (ring != null) {
            Point2D rc = projectWorld(ring.anchor().get());
            Point2D r0 = projectWorld(ringPoint(ring, 0));
            Point2D r1 = projectWorld(ringPoint(ring, 0.05));
            if (rc != null && r0 != null && r1 != null) {
                double yaw0 = p0.yawDeg;
                double tx = r1.getX() - r0.getX(), ty = r1.getY() - r0.getY();
                double L = Math.max(1e-9, Math.hypot(tx, ty));
                fire(MouseEvent.MOUSE_PRESSED, r0.getX(), r0.getY(), true);
                boolean rp = gPress == ring && gizmoDragging;
                fire(MouseEvent.MOUSE_DRAGGED, r0.getX() + tx / L * 40,
                     r0.getY() + ty / L * 40, true);
                boolean turned = p0.yawDeg != yaw0;
                fire(MouseEvent.MOUSE_RELEASED, r0.getX() + tx / L * 40,
                     r0.getY() + ty / L * 40, false);
                out.println("PICKPROBE ringDrag press=" + rp + " turned="
                    + turned + " dyaw=" + String.format("%.2f", p0.yawDeg - yaw0)
                    + " ended=" + (!gizmoDragging && gPress == null));
                if (!(rp && turned && !gizmoDragging)) fails++;
            }
        }
        // (d) the depth rule under the TRUE-ortho lens (batch 37)
        fails += probeOrtho(out);
        return fails;
    }

    /** pickProbe's framing for the depth cases (batch 37): world m
     *  per px at the rig, the orbit pivot ON its anchor — the rig
     *  sits at the pane's center at a known distance instead of
     *  wherever the startup view left it. The rig's PIXEL size does
     *  not depend on it: positionGizmo holds the apparent size (a
     *  part of r rig units spans r × 10 % of the pane height
     *  wherever rig.scale is off its 0.5 floor — every orbit zoom
     *  about the anchor), so the probe prints the same ball radius
     *  rPx in the default view and in this one. */
    private static final double PROBE_UPP = 0.15;

    /** pickProbe's RAY CANARY (batch 37): cursorRay cast at the
     *  projection of a world point must pass through that point — it
     *  pins cursorRay against projectWorld (the lens, the Y flip, the
     *  ortho origin) and catches a projection that lags a camera
     *  change. Two points: the rig anchor and one rig unit along +X. */
    private int probeRay(java.io.PrintStream out, String tag, Rig rig) {
        int fails = 0;
        Vec3 a = rig.anchor.get();
        Vec3[] ws = { a, a.add(new Vec3(1, 0, 0).scale(rig.scale)) };
        for (Vec3 w : ws) {
            Point2D s = projectWorld(w);
            Vec3[] r = s == null ? null : cursorRay(s.getX(), s.getY());
            double miss = Double.NaN;
            if (r != null) {
                Vec3 ow = w.sub(r[0]);
                miss = ow.sub(r[1].scale(ow.dot(r[1]))).length();
            }
            boolean ok = miss <= 1e-3 * rig.scale;
            out.println(String.format(java.util.Locale.US,
                "PICKPROBE ray (%s) miss=%.2e m%s", tag, miss,
                ok ? " OK" : " FAIL"));
            if (!ok) fails++;
        }
        return fails;
    }

    /** One probe pick: prints the line, returns 1 on a miss. `want`
     *  = the exact part, or null for "any part of kind `kind`". */
    private int probePick(java.io.PrintStream out, String what,
                          Point2D at, GPart want, int kind) {
        GPart hit = at == null ? null : pickPart(at.getX(), at.getY());
        boolean ok = hit != null && hit.kind() == kind
            && (want == null || hit == want);
        out.println("PICKPROBE " + what + " -> "
            + (hit == null ? "null" : "kind " + hit.kind())
            + (ok ? " OK" : " FAIL"));
        return ok ? 0 : 1;
    }

    /** pickProbe (b2), perspective: the ray canary; an arrow's
     *  EMERGED near half drawn over the ball picks the arrow, its far
     *  half behind the ball picks the ball; with the yaw ring tilted
     *  to cross the ball (camera pitch −8°) its NEAR arc picks the
     *  ring, its FAR arc the ball. Runs zoomed onto the rig
     *  (PROBE_UPP) and restores the camera pose. */
    private int probeDepth(java.io.PrintStream out) {
        int fails = 0;
        if (gizmoRigs.isEmpty()) {
            out.println("PICKPROBE FAIL depth cases: no rig");
            return 1;
        }
        Rig rig = gizmoRigs.get(0);
        fails += probeRay(out, "default view", rig);
        double pvX = camPivot.getX(), pvY = camPivot.getY(),
               pvZ = camPivot.getZ(), zm = camZoom.getZ(),
               pit = camPitch.getAngle();
        try {
            Vec3 a = rig.anchor.get();
            Point3D as = world.localToScene(a.x(), -a.y(), a.z());
            camPivot.setX(as.getX());
            camPivot.setY(as.getY());
            camPivot.setZ(as.getZ());
            camZoom.setZ(-PROBE_UPP * Math.max(1, sub.getHeight())
                / (2 * Math.tan(Math.toRadians(camFov() / 2))));
            positionGizmo();
            fails += probeRay(out, "zoomed", rig);
            Point2D sc = projectWorld(a);
            Vec3[] rc = sc == null ? null : cursorRay(sc.getX(), sc.getY());
            if (rc == null) {
                out.println("PICKPROBE FAIL depth cases: no ray at the rig");
                return fails + 1;
            }
            double rPx = CENTER_BALL_R * rig.scale / uppAt(a);
            // the arrow aimed most nearly at the camera
            GPart arrow = null;
            double bestDot = -1;
            for (GPart g : gizmoParts) {
                if (g.kind() != 1 || g.rig() != rig) continue;
                double dt = Math.abs(g.dir().get().normalize().dot(rc[1]));
                if (dt > bestDot) { bestDot = dt; arrow = g; }
            }
            boolean found = false;
            if (arrow != null) {
                Vec3 dir = arrow.dir().get().normalize();
                Vec3 nearDir = dir.scale(dir.dot(rc[1]) > 0 ? -1 : 1);
                for (int k = 0; k <= 35 && !found; k++) {
                    double s = 0.30 + 0.02 * k;
                    Point2D sq = projectWorld(
                        a.add(nearDir.scale(s * rig.scale)));
                    if (sq == null || Math.hypot(sq.getX() - sc.getX(),
                            sq.getY() - sc.getY()) > rPx - 1.5) continue;
                    Vec3[] r = cursorRay(sq.getX(), sq.getY());
                    if (r == null) continue;
                    double ta = Gestures.arrowFront(r[0], r[1], a, nearDir,
                        rig.scale, false, 0);
                    double tb = Gestures.raySphereFront(r[0], r[1], a,
                        CENTER_BALL_R * rig.scale);
                    if (!(ta < tb)) continue;
                    found = true;
                    fails += probePick(out, String.format(java.util.Locale.US,
                        "front arrow over the ball (s=%.2f, %.1f px from"
                        + " center, rPx=%.1f)", s, Math.hypot(
                            sq.getX() - sc.getX(), sq.getY() - sc.getY()),
                        rPx), sq, arrow, 1);
                    fails += probePick(out, "arrow half behind the ball",
                        projectWorld(a.sub(nearDir.scale(s * rig.scale))),
                        null, 0);
                }
            }
            if (!found) {
                out.println("PICKPROBE FAIL no emerged arrow point inside"
                    + " the ball's silhouette");
                fails++;
            }
            // the yaw ring crossing the ball
            camPitch.setAngle(-8);
            positionGizmo();
            fails += probeRay(out, "pitch -8", rig);
            GPart ring = null;
            for (GPart g : gizmoParts) {
                if (g.kind() == 2 && g.rig() == rig) { ring = g; break; }
            }
            sc = projectWorld(a);
            rc = sc == null ? null : cursorRay(sc.getX(), sc.getY());
            if (ring == null || rc == null) {
                out.println("PICKPROBE FAIL ring arcs: no ring / no ray");
                return fails + 1;
            }
            rPx = CENTER_BALL_R * rig.scale / uppAt(a);
            int iNear = 0;
            double dNear = Double.MAX_VALUE;
            for (int i = 0; i < RING_PICK_N; i++) {
                Vec3 w = ringPoint(ring, 2 * Math.PI * i / RING_PICK_N);
                double dpt = w.sub(rc[0]).dot(rc[1]);
                if (dpt < dNear) { dNear = dpt; iNear = i; }
            }
            double thN = 2 * Math.PI * iNear / RING_PICK_N;
            Point2D sN = projectWorld(ringPoint(ring, thN));
            Point2D sF = projectWorld(ringPoint(ring, thN + Math.PI));
            boolean inside = sN != null && sF != null
                && Math.hypot(sN.getX() - sc.getX(), sN.getY() - sc.getY())
                    <= rPx - 1.5
                && Math.hypot(sF.getX() - sc.getX(), sF.getY() - sc.getY())
                    <= rPx - 1.5;
            if (!inside) {
                out.println("PICKPROBE FAIL ring arcs not inside the"
                    + " ball's silhouette");
                fails++;
            } else {
                fails += probePick(out, "ring near arc over the ball",
                    sN, ring, 2);
                fails += probePick(out, "ring far arc behind the ball",
                    sF, null, 0);
            }
        } finally {
            camPitch.setAngle(pit);
            camPivot.setX(pvX);
            camPivot.setY(pvY);
            camPivot.setZ(pvZ);
            camZoom.setZ(zm);
            positionGizmo();
        }
        return fails;
    }

    /** pickProbe (d), TRUE ortho (the Top view, zoomed onto the rig):
     *  the ray canary (the origin ON the projection plane); the rig
     *  center picks the ball; along an in-plane arrow the point
     *  inside the ball picks the ball, the point past it the arrow.
     *  Leaves through the restore path. */
    private int probeOrtho(java.io.PrintStream out) {
        int fails = 0;
        switchViewMode(2);
        gizmoReveal = new Reveal(0, 1);
        refreshGizmo(true);
        if (sub.getCamera() != orthoCam || gizmoRigs.isEmpty()) {
            out.println("PICKPROBE FAIL ortho: no ortho lens / no rig");
            return 1;
        }
        Vec3 a = gizmoRigs.get(0).anchor.get();
        Point3D as = world.localToScene(a.x(), -a.y(), a.z());
        camPivot.setX(as.getX());
        camPivot.setY(as.getY());
        camPivot.setZ(as.getZ());
        camZoom.setZ(-PROBE_UPP * Math.max(1, sub.getHeight())
            / (2 * Math.tan(Math.toRadians(ORTHO_FOV / 2))));
        syncOrthoCam();
        refreshGizmo(true);   // scale + the in-plane part set
        Rig rig = gizmoRigs.get(0);
        fails += probeRay(out, "ortho", rig);
        Point2D sc = projectWorld(a);
        Vec3[] rc = sc == null ? null : cursorRay(sc.getX(), sc.getY());
        fails += probePick(out, "ortho center", sc, null, 0);
        // the arrow lying flattest in the view plane
        GPart arrow = null;
        double bestDot = 2;
        for (GPart g : gizmoParts) {
            if (g.kind() != 1 || g.rig() != rig || rc == null) continue;
            double dt = Math.abs(g.dir().get().normalize().dot(rc[1]));
            if (dt < bestDot) { bestDot = dt; arrow = g; }
        }
        if (arrow == null) {
            out.println("PICKPROBE FAIL ortho: no arrow");
            fails++;
        } else {
            Vec3 dir = arrow.dir().get().normalize();
            fails += probePick(out, "ortho arrow inside the ball",
                projectWorld(a.add(dir.scale(0.20 * rig.scale))), null, 0);
            fails += probePick(out, "ortho arrow past the ball",
                projectWorld(a.add(dir.scale(0.60 * rig.scale))), arrow, 1);
        }
        switchViewMode(0);   // back to the pre-ortho pose
        return fails;
    }

    // ---------- DEV PROBE helpers (v19.28, D257 —
    // `-Dfvd.bridgeringprobe=1`, driven by FvdDesigner.bridgeRingProbe:
    // the bridge-end rig's RING SET per mode × locks and a ring drag
    // through the real pane filter) ----------

    /** Reveal piece pi's BRIDGE-END rig (kind 2), frame the camera on
     *  it (the probeDepth framing) and report which rings it built —
     *  bit 0 yaw, bit 1 pitch, bit 2 roll (by ring color). */
    int probeBridgeRings(int pi) {
        gizmoReveal = new Reveal(pi, 2);
        refreshGizmo(true);
        if (!gizmoRigs.isEmpty()) {
            Vec3 a = gizmoRigs.get(0).anchor.get();
            Point3D as = world.localToScene(a.x(), -a.y(), a.z());
            camPivot.setX(as.getX());
            camPivot.setY(as.getY());
            camPivot.setZ(as.getZ());
            camZoom.setZ(-PROBE_UPP * Math.max(1, sub.getHeight())
                / (2 * Math.tan(Math.toRadians(camFov() / 2))));
            positionGizmo();
        }
        return probeRingMask();
    }

    private int probeRingMask() {
        int mask = 0;
        for (GPart g : gizmoParts) {
            if (g.kind() != 2) continue;
            if (g.idle().equals(RING_YAW)) mask |= 1;
            else if (g.idle().equals(RING_PITCH)) mask |= 2;
            else if (g.idle().equals(RING_ROLL)) mask |= 4;
        }
        return mask;
    }

    /** Press the revealed rig's ring of `axis` (0 yaw / 1 pitch /
     *  2 roll) at a point of its line where the picker returns THAT
     *  ring, drag `px` pixels along the line's screen tangent in two
     *  events (running `mid` after them, mid-gesture), release.
     *  Returns 1 = pressed, dragged and ended; 0 = no such ring;
     *  -1 = no pickable point on it; -2 = the press missed; -3 = the
     *  gesture did not end on the release. */
    int probeBridgeRingDrag(int axis, double px, Runnable mid) {
        Color want = axis == 0 ? RING_YAW : axis == 1 ? RING_PITCH : RING_ROLL;
        GPart ring = null;
        for (GPart g : gizmoParts) {
            if (g.kind() == 2 && g.idle().equals(want)) { ring = g; break; }
        }
        if (ring == null) return 0;
        for (int i = 0; i < RING_PICK_N; i++) {
            double th = 2 * Math.PI * i / RING_PICK_N;
            Point2D s0 = projectWorld(ringPoint(ring, th));
            Point2D s1 = projectWorld(ringPoint(ring, th + 0.05));
            if (s0 == null || s1 == null) continue;
            if (pickPart(s0.getX(), s0.getY()) != ring) continue;
            double tx = s1.getX() - s0.getX(), ty = s1.getY() - s0.getY();
            double L = Math.hypot(tx, ty);
            if (L < 1e-6) continue;
            double ex = s0.getX() + tx / L * px, ey = s0.getY() + ty / L * px;
            fire(MouseEvent.MOUSE_PRESSED, s0.getX(), s0.getY(), true);
            if (gPress != ring || !gizmoDragging) {
                fire(MouseEvent.MOUSE_RELEASED, s0.getX(), s0.getY(), false);
                return -2;
            }
            fire(MouseEvent.MOUSE_DRAGGED, s0.getX() + tx / L * px / 2,
                 s0.getY() + ty / L * px / 2, true);
            fire(MouseEvent.MOUSE_DRAGGED, ex, ey, true);
            if (mid != null) mid.run();
            fire(MouseEvent.MOUSE_RELEASED, ex, ey, false);
            return !gizmoDragging && gPress == null ? 1 : -3;
        }
        return -1;
    }

    private double partDistPx(GPart p, double sx, double sy) {
        if (p.kind() == 0) {
            // a BALL: its silhouette-edge distance — pickPart's own
            // metric (the probe's "nearer part" test used to route a
            // ball through ringDistPx, whose null dir() threw and
            // aborted the probe before its drag sections ran)
            Vec3 a = p.anchor().get();
            Point2D s = projectWorld(a);
            if (s == null) return Double.MAX_VALUE;
            double rW = p.rig() == null ? p.radius()
                                        : p.radius() * p.rig().scale;
            return Gestures.ballEdgeDist(
                Math.hypot(s.getX() - sx, s.getY() - sy), rW / uppAt(a));
        }
        return p.kind() == 1 ? arrowDistPx(p, sx, sy) : ringDistPx(p, sx, sy);
    }

    /** Synthesize a primary-button mouse event at SCREEN (sx, sy) and
     *  fire it at the pane (the probe's Robot-free path through
     *  gizmoMouseFilter — the handlers read screen coords only). */
    private void fire(javafx.event.EventType<MouseEvent> type,
                      double sx, double sy, boolean down) {
        Point2D sc = pane.localToScene(pane.screenToLocal(sx, sy));
        javafx.event.Event.fireEvent(pane, new MouseEvent(type,
            sc.getX(), sc.getY(), sx, sy, MouseButton.PRIMARY, 1,
            false, false, false, false, down, false, false,
            false, false, false, null));
    }

    /** Whether a gizmo gesture is live in this pane (the shell's
     *  mid-drag modifier-key guard, D248): a drag past the deadband
     *  OR a part still PRESSED — a ball's first 3 px never set
     *  gizmoDragging, and a Ctrl+Z there would replace the piece under
     *  the pressed closures just the same (D248 review fix). */
    boolean isGizmoDragging() {
        // a live speed-control DISK drag counts too (D248, item S1):
        // a mid-gesture Ctrl+Z would replace the control under the
        // dragged (piece, sec, ctl) indices
        return gizmoDragging || gPress != null || diskDrag != null;
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

    /** REVEAL state (v18.16, D187 — the ball-click gating returns,
     *  per pane; the v17.7 "Show Gizmos" check is GONE; a (piece,
     *  kind) RECORD since v19.18, D247 — every junction of the RUN
     *  wears a ball, so the revealed rig names its piece): null =
     *  balls only, kind 1 = the entry/seam rig of `piece`, kind 2 =
     *  the bridge-end rig of the bridge TRAILING `piece`. A single
     *  click on a white ball toggles its rig (and ACTIVATES that
     *  piece/section); any primary press off the gizmo hides it;
     *  LEAVING THE RUN resets it (D187's piece-switch reset is gone
     *  — switching within the run via a ball is the feature). The
     *  piece INDEX, never the object: applyDocState/Load replace
     *  TrackPiece objects (D205). */
    private record Reveal(int piece, int kind) { }
    private Reveal gizmoReveal = null;
    private double gDragX, gDragY;       // screen point of the press
    // SHIFT = a tenth of the mouse travel, RE-BASED per event: the
    // previous event's raw screen point + the accumulated debt raw −
    // effective per axis (Gestures.slowDebt); a 0.0 debt = the
    // historic law bitwise. One live gesture per pane (gPress), so
    // arrows, rings and balls share the fields.
    private double gPrevX, gPrevY, gDebtX, gDebtY;

    /** Arm the slow-travel state at a press. */
    private void beginSlowTravel(double px, double py) {
        gPrevX = px;
        gPrevY = py;
        gDebtX = 0;
        gDebtY = 0;
    }

    /** This event's EFFECTIVE screen travel {dx, dy} from the press
     *  point (px, py): the raw travel minus the Shift debt, advanced
     *  here per event (Gestures.slowDebt). */
    private double[] slowTravel(MouseEvent e, double px, double py) {
        double x = e.getScreenX(), y = e.getScreenY();
        boolean slow = e.isShiftDown();
        gDebtX = Gestures.slowDebt(gDebtX, gPrevX, x, slow);
        gDebtY = Gestures.slowDebt(gDebtY, gPrevY, y, slow);
        gPrevX = x;
        gPrevY = y;
        return new double[] { x - px - gDebtX, y - py - gDebtY };
    }

    /** The gizmo Ctrl-snap step: the element-length step, a TENTH of
     *  it under Ctrl+Shift (the app's keyboard convention — Shift =
     *  tenths, Ctrl = snap, both combine). */
    private double gizmoStep(boolean fine) {
        double step = Math.max(0.01, app.settings.snapLen());
        return fine ? step / 10 : step;
    }

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

    /** Drop this pane's ball-click reveal (v19.19, D248 review fix):
     *  Reveal(piece, kind) holds a piece INDEX, and a run REORDER
     *  (Coaster.moveRun — bridge-to / attach) can leave that index on
     *  another piece of the now-merged run, which the run-membership
     *  reset below would KEEP. The next rebuild's refreshGizmo redraws
     *  the bare balls. */
    void dropGizmoReveal() {
        gizmoReveal = null;
    }

    /** Rebuild-or-follow, signature-guarded like the markers; a
     *  mid-drag call only repositions/re-aims (positionGizmo's aim
     *  hooks keep the parts tracking the pose in real time, v16.12). */
    void refreshGizmo(boolean force) {
        long profT = Prof.t();
        try { refreshGizmoBody(force); }
        finally { Prof.add("refreshGizmo", profT); }
    }

    private void refreshGizmoBody(boolean force) {
        syncGizmoSubMouse();
        // RUN-MEMBERSHIP reset (v19.18, D247 — replaces D187's
        // piece-switch reset): the reveal survives any activation
        // INSIDE the run (a ball click activates another piece of it
        // and must keep its freshly revealed rig — this runs from
        // activation's rebuildTrack BEFORE the click handler's own
        // refresh); it clears when the revealed piece leaves the run
        // or no longer exists (docGen replacing pieces).
        if (gizmoReveal != null) {
            boolean keep = app.editor != null
                && gizmoReveal.piece() >= 0
                && gizmoReveal.piece() < app.coaster.pieces.size()
                && gizmoReveal.piece() >= app.editor.runStartPiece()
                && gizmoReveal.piece() <= app.editor.runEndPiece();
            if (!keep) gizmoReveal = null;
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
        List<TrackPiece> pcs = app.coaster.pieces;
        if (pi < 0 || pi >= pcs.size()) return "off";
        // the whole RUN is signed (v19.18, D247 — one ball per
        // junction of the run, so every member's placement/locks/
        // held angles/bridge mode rebuilds the set); the wizard's
        // PENDING piece is skipped PER PIECE (D231 — it used to turn
        // the whole sig 'off', but a pending piece is always its own
        // run of one, so the outcome is the same) and its flag is
        // signed here because picked() flips with no docGen bump
        int rs = app.editor.runStartPiece();
        int re = Math.min(app.editor.runEndPiece(), pcs.size() - 1);
        StringBuilder sb = new StringBuilder();
        // the DOCUMENT GENERATION (v18.33, D205): applyDocState/Load/
        // New replace the TrackPiece objects — often with identical
        // VALUES (a convert preview's Apply, a lanes-only undo), so a
        // value signature alone kept the ball/rig closures wired to
        // an orphaned piece: drags moved the gizmo, never the track
        sb.append(app.docGen).append('~')
          .append(app.settings.gizmoLocal() ? 'L' : 'G')
          .append(orthoPlaneAxis())   // fixed-ortho arrow hiding (v18.11)
          // ball-click reveal (v18.16, D187; (piece:kind) since D247)
          .append(gizmoReveal == null ? "-"
              : gizmoReveal.piece() + ":" + gizmoReveal.kind())
          .append('|').append(pi)     // the active piece: node-0 marker
                                      // coupling + panel owner rows
          .append('|').append(rs).append('-').append(re);
        for (int q = rs; q <= re; q++) {
            TrackPiece p = pcs.get(q);
            boolean pending = app.isPendingPiece(app.activeCoaster, q);
            sb.append("|P").append(q).append(pending ? '!' : ':');
            if (pending) continue;
            BridgeSection into = app.bridgeInto(q);
            BridgeSection trail = app.bridgeInto(q + 1);
            boolean follows = app.editor.followsPrev(q);
            sb.append(into != null ? into.optMode : -1)
              .append('|').append(into != null && into.lockYaw)
              .append(into != null ? into.lockPitch : p.uiLockPitch)
              .append(into != null ? into.lockRoll : p.uiLockRoll)
              .append('|').append(follows);   // v17.1
            if (follows) {
                // the SEAM gizmo's rings follow the lock toggles + held
                // angles live (v17.3, D140); the seam ball exists only
                // once a compile places the junction (seamWorldPos)
                sb.append('|').append(p.sLockYaw).append(p.sLockPitch)
                  .append(p.sLockRoll).append('|').append(p.sYawDeg)
                  .append(',').append(p.sPitchDeg)
                  .append(',').append(p.sRollDeg)
                  .append('|').append(app.editor.seamWorldPos(q) != null);
            }
            appendPose(sb, p);
            if (trail != null && q + 1 < pcs.size()) {
                sb.append("|T|").append(trail.optMode)
                  .append('|').append(trail.optLen)
                  .append('|').append(trail.lockYaw).append(trail.lockPitch)
                  .append(trail.lockRoll);
                appendPose(sb, pcs.get(q + 1));
            }
        }
        return sb.toString();
    }

    private static void appendPose(StringBuilder sb, TrackPiece p) {
        sb.append('|').append(p.x).append(',').append(p.y)
          .append(',').append(p.z).append(',').append(p.yawDeg)
          .append(',').append(p.pitchDeg).append(',').append(p.rollDeg)
          .append(',').append(p.baseOrient.w()).append(',')
          .append(p.baseOrient.x()).append(',').append(p.baseOrient.y())
          .append(',').append(p.baseOrient.z())
          // the orientation MODE + the design tier (v19.20, D249): a
          // DESIGN_ONLY rig aims its rings at the DESIGN frame and
          // re-aims per design edit; a mode flip / readout swap /
          // unchecking the independent check rebuilds the rig so it
          // snaps back to the real frame
          .append(',').append(p.orientMode.ordinal())
          .append(',').append(p.designPitchDeg)
          .append(',').append(p.designRollDeg);
    }

    /** The frame a piece's rig aims its rings at (D249): the DESIGN
     *  pose while the piece edits its design orientation, else the
     *  real entry pose. */
    private static Quat rigFrame(TrackPiece p) {
        return p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
            ? p.designPose().orient() : p.entryPose().orient();
    }

    private void rebuildGizmo() {
        gizmoGroup.getChildren().clear();
        trackBallGroup.getChildren().clear();
        trackBallUnder.getChildren().clear();   // the buried balls (batch 37)
        gizmoRigs.clear();
        revealMarks.clear();
        clearGizmoParts(false);   // every part's node is gone (D248)
        if ("off".equals(gizmoSig)) {
            syncGizmoSubMouse();
            syncGizmoAxesBtn();
            return;
        }
        // ONE BALL PER JUNCTION OF THE RUN (v19.18, D247 — user spec:
        // every white ball of the run shows whatever section is
        // active, and clicking one activates that piece/section):
        // the head's entry ball, each strict-seam follower's SEAM
        // ball (v17.3, D140 — the D136 ghost was the STALE stored
        // x/y/z; the true junction is a real surface), and each
        // trailing bridge's END ball at the next piece's x/y/z. A
        // piece bridged in from within the run gets NO separate entry
        // ball — the previous piece's bridge-end ball IS its junction
        // (same spot; two coincident balls would z-fight and its own
        // entry rig builds nothing under optimize modes anyway).
        // Single click = reveal + activate; DOUBLE click = activate
        // + the owner's settings; the wizard's PENDING piece shows
        // nothing (D231).
        List<TrackPiece> pcs = app.coaster.pieces;
        int rs = app.editor.runStartPiece();
        int re = Math.min(app.editor.runEndPiece(), pcs.size() - 1);
        for (int q = rs; q <= re; q++) {
            if (app.isPendingPiece(app.activeCoaster, q)) continue;
            final int pi = q;
            final TrackPiece p = pcs.get(pi);
            if (pi == rs) {
                // the HEAD's ENTRY ball: drags the piece in the VIEW
                // PLANE (v18.11, user spec) unless a bridge owns the
                // placement (never at a run head, kept for safety);
                // double-click = the Track Piece window (D248)
                BridgeSection intoP = app.bridgeInto(pi);
                boolean movable = intoP == null
                    || intoP.optMode == BridgeSection.OPT_NONE;
                addRevealMark(pi, 1, () -> new Vec3(p.x, p.y, p.z),
                    () -> {
                        app.editor.activatePieceSection(pi, 0);
                        app.openPlacementPopup(false);   // fresh, at the anchor (D248)
                    },
                    !movable ? null : v -> {
                        app.placementEdit(p, () -> {   // D221 rigid carry
                            p.x = v.x();
                            p.y = v.y();
                            p.z = v.z();
                        });
                        app.rebuildTrack();
                    });
            } else if (app.editor.followsPrev(pi)) {
                // the SEAM ball (only once a compile has placed the
                // junction): single click reveals the seam gizmo,
                // double click opens the seam settings pop-out
                if (app.editor.seamWorldPos(pi) != null) {
                    addRevealMark(pi, 1, () -> {
                        Vec3 v = app.editor.seamWorldPos(pi);
                        return v != null ? v : new Vec3(p.x, p.y, p.z);
                    }, () -> {
                        app.editor.activatePieceSection(pi, 0);
                        app.editor.showStrictSeamSettings(pi);
                    }, null);
                }
            }
            BridgeSection trail = app.bridgeInto(pi + 1);
            if (trail != null && pi + 1 < pcs.size()) {
                final TrackPiece n = pcs.get(pi + 1);
                // the BRIDGE-END ball: planar drag only in manual
                // mode (optimize modes own the position); double-
                // click = the bridge properties panel, which syncs
                // only while the bridge SECTION itself is active —
                // so it activates (pi, last) first
                boolean movable = trail.optMode == BridgeSection.OPT_NONE;
                addRevealMark(pi, 2, () -> new Vec3(n.x, n.y, n.z),
                    () -> {
                        app.editor.activatePieceSection(pi,
                            p.sections.size() - 1);
                        app.openBridgeWindow(pi);   // "Bridge Settings (Strict Seam n)" (D249/D254)
                    },
                    !movable ? null : v -> {
                        // through placementEdit like the rig center
                        // ball (D247): rigid carry + wizard sync —
                        // the direct field writes skipped both
                        app.placementEdit(n, () -> {
                            n.x = v.x();
                            n.y = v.y();
                            n.z = v.z();
                        });
                        app.editor.gizmoBridgeMoved(pi);
                    });
            }
        }
        // rigs build only for the REVEALED ball (v18.16, D187)
        buildRigsForReveal();
        syncGizmoAxesBtn();
        positionGizmo();
    }

    /** Build the revealed rig (+ its scaling center ball) into the
     *  OVERLAY layer — split out of rebuildGizmo (v18.17, D188) so a
     *  drag on a track ball can reveal mid-gesture without rebuilding
     *  the pressed ball (historic: replacing the pressed node killed
     *  JavaFX mouse capture — moot since the gesture rides gPress and
     *  nothing under `world` is a press target, D248 / D255). */
    private void buildRigsForReveal() {
        Reveal rv = gizmoReveal;
        List<TrackPiece> pcs = app.coaster.pieces;
        // the rig belongs to the REVEALED piece, not the active one
        // (v19.18, D247 — a ball click activates, a drag reveal does
        // not; either way the rig is that ball's)
        if (rv == null || "off".equals(gizmoSig)
                || rv.piece() < 0 || rv.piece() >= pcs.size()
                || app.isPendingPiece(app.activeCoaster, rv.piece())) {
            syncGizmoSubMouse();
            return;
        }
        int pi = rv.piece();
        TrackPiece p = pcs.get(pi);
        BridgeSection trail = app.bridgeInto(pi + 1);
        TrackPiece next = trail != null && pi + 1 < pcs.size()
            ? pcs.get(pi + 1) : null;
        boolean local = app.settings.gizmoLocal();
        java.util.function.Consumer<Vec3> ctrMove = null;
        if (rv.kind() == 1) {
            buildPieceGizmo(local, pi, p);
            BridgeSection intoP = app.bridgeInto(pi);
            if (!app.editor.followsPrev(pi)
                    && (intoP == null
                        || intoP.optMode == BridgeSection.OPT_NONE)) {
                ctrMove = v -> {
                    app.placementEdit(p, () -> {   // D221 rigid carry
                        p.x = v.x();
                        p.y = v.y();
                        p.z = v.z();
                    });
                    app.rebuildTrack();
                };
            }
        } else if (rv.kind() == 2 && trail != null && next != null) {
            buildBridgeGizmo(local, pi, trail, next);
            if (trail.optMode == BridgeSection.OPT_NONE) {
                TrackPiece n = next;
                ctrMove = v -> {
                    app.placementEdit(n, () -> {   // D221 rigid carry
                        n.x = v.x();
                        n.y = v.y();
                        n.z = v.z();
                    });
                    app.editor.gizmoBridgeMoved(pi);
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
     *  pressed track ball's NODE survives (activation would re-point
     *  lanes and rebuild, D247/D188), but since v19.19 (D248) it is
     *  HIDDEN the moment the rig builds: the rig's scaling center
     *  ball replaces it exactly as the build-time rule in
     *  addRevealMark does, so a close-up drag-reveal shows ONE ball
     *  (the fixed 0.34 m ball used to peek out from under the 0.272
     *  × rig.scale center ball whenever rig.scale < 1.25, ≈ 11 m at
     *  60° FOV). Safe because the gesture rides gPress in the pane
     *  filter, not the node's visibility (a MOUSE_RELEASED reaches
     *  the pane whatever became of the pressed node); the release
     *  rebuild recreates every ball with the right visibility. */
    private void revealDuringDrag(Reveal mine, Sphere pressed) {
        if (mine.equals(gizmoReveal)) return;
        gizmoReveal = mine;
        gizmoGroup.getChildren().clear();
        gizmoRigs.clear();
        clearGizmoParts(true);   // rig parts only; the pressed ball stays registered
        gizmoSig = gizmoSigNow();
        buildRigsForReveal();
        if (pressed != null) pressed.setVisible(false);
        syncGizmoAxesBtn();
        positionGizmo();
    }

    /** The fixed track ball's radius (world m) and the rig center
     *  ball's (rig units = 0.34 × the D186 0.8 ratio). */
    static final double TRACK_BALL_R = 0.34, CENTER_BALL_R = 0.272;

    private void addRigCenterBall(Rig rig,
            java.util.function.Consumer<Vec3> moveApply) {
        // 0.272 = the fixed ball's 0.34 × the D186 0.8 rig ratio; the
        // rig group's scale transform grows it with the arrows
        Sphere ball = new Sphere(CENTER_BALL_R, 14);
        wireBall(ball, rig, CENTER_BALL_R, rig.anchor, rig.onDouble,
            moveApply, null,
            () -> {
                gizmoReveal = null;   // click the center = hide (D187 toggle)
                refreshGizmo(false);
            });
        rig.group.getChildren().add(ball);
    }

    /** The top-right Local/Global overlay button (v18.16, D187 —
     *  back from its v18.10 removal, now visible only while a rig is
     *  revealed); text tracks the shared axes choice. */
    void syncGizmoAxesBtn() {
        if (gizmoAxesBtn == null) return;
        boolean show = gizmoReveal != null && !"off".equals(gizmoSig);
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
     *  bigger sphere at a junction of the run — the head's entry,
     *  each strict seam, each trailing bridge's end (D247) — toggling
     *  that junction's gizmo. `kind` 1 = the entry/seam rig of
     *  `pieceIdx`, 2 = the bridge-end rig of the bridge trailing it.
     *  A single click also ACTIVATES the piece/section right after
     *  the junction (kind 1 → (pieceIdx, 0), kind 2 → (pieceIdx+1,
     *  0) — like clicking the track there); a ball click selects NO
     *  node since v19.19 (D248, item S1 — the pane filter DESELECTS
     *  the graph node at every gizmo press). */
    private void addRevealMark(int pieceIdx, int kind,
                               java.util.function.Supplier<Vec3> at,
                               Runnable onDouble,
                               java.util.function.Consumer<Vec3> moveApply) {
        // the FIXED-SIZE ball (v18.17, D188 — his clarification of
        // "originally not scaling"): part of the TRACK layer, never
        // scales with view distance; click reveals the rig, and a
        // DRAG both moves the owner and reveals
        Sphere ball = new Sphere(TRACK_BALL_R, 14);
        Reveal mine = new Reveal(pieceIdx, kind);
        wireBall(ball, null, TRACK_BALL_R, at, onDouble, moveApply,
            () -> revealDuringDrag(mine, ball),   // hides `ball` (D248)
            () -> {
                // SINGLE CLICK toggles the ball's rig (v18.16, D187)
                // and activates its piece/section (v19.18, D247):
                // the reveal is set BEFORE activation — activation's
                // rebuildTrack refreshes every pane, and the run-
                // membership rule keeps the reveal through the
                // in-run switch; the explicit refresh below covers
                // the no-activation-change path. Under an open convert
                // preview the reveal toggle refuses too (D247 review
                // fix — wireBall gates the click first; belt and
                // braces for a programmatic caller)
                if (app.convertBusy()) return;
                gizmoReveal = mine.equals(gizmoReveal) ? null : mine;
                if (app.editor != null) {
                    app.editor.activatePieceSection(
                        kind == 1 ? pieceIdx : pieceIdx + 1, 0);
                }
                refreshGizmo(false);
                // (the D247 node-0 select on a kind-1 click is GONE
                // since v19.19, D248 item S1: a gizmo select DESELECTS
                // the graph node — the pane filter clears the
                // selection at the press — and selects nothing; node
                // 0 stays reachable in the graph)
            });
        // the track ball HIDES while its own rig is shown (v18.18,
        // D189 — the rig's scaling center ball replaces it); a
        // reveal-by-drag hides the pressed ball the same way since
        // D248 (revealDuringDrag), the release rebuild re-derives it
        ball.setVisible(!mine.equals(gizmoReveal));
        revealMarks.add(new RevealMark(ball, at));
        // (positionGizmo — every build ends with it — moves a buried
        // ball into trackBallUnder, batch 37)
        trackBallGroup.getChildren().add(ball);
    }

    /** Reveal piece `pieceIdx`'s entry/seam rig programmatically
     *  (v19.18, D247 — Copy's "edit the copy" handoff, item C): set
     *  AFTER the activation that puts the piece in the run, or the
     *  membership reset clears it at once. */
    void revealEntryGizmo(int pieceIdx) {
        if (pieceIdx < 0 || pieceIdx >= app.coaster.pieces.size()) return;
        gizmoReveal = new Reveal(pieceIdx, 1);
        refreshGizmo(false);   // the sig carries the reveal
    }

    /** Shared white-ball behavior (v18.17, D188 — one wiring for the
     *  fixed track balls AND the rigs' scaling center balls): warm
     *  hover, PLANAR DRAG in the view plane (absolute from the press;
     *  Ctrl snaps — GLOBAL = the world grid, LOCAL = step
     *  increments), double-click = the owner's settings, plus a
     *  per-ball single-click action. */
    private void wireBall(Sphere ball, Rig rig, double radius,
                          java.util.function.Supplier<Vec3> at,
                          Runnable onDouble,
                          java.util.function.Consumer<Vec3> moveApply,
                          Runnable onFirstDragMove,
                          Runnable onSingleClick) {
        Color idle = Color.rgb(248, 249, 252);
        PhongMaterial mat = new PhongMaterial(idle);
        ball.setMaterial(mat);
        // handlers live in a GPart for the screen-space picker (D248
        // — the node itself takes no mouse events); bodies unchanged
        double[] st = new double[9];   // rightW, downW, upp, px, py
        Vec3[] base = new Vec3[1];
        boolean[] moved = new boolean[1];
        javafx.event.EventHandler<MouseEvent> press = e -> {
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
            beginSlowTravel(st[7], st[8]);
            base[0] = at.get();
            moved[0] = false;
            gizmoPushed = false;
        };
        javafx.event.EventHandler<MouseEvent> drag = e -> {
            if (moveApply == null || base[0] == null) return;
            e.consume();
            double ddx = e.getScreenX() - st[7], ddy = e.getScreenY() - st[8];
            if (!moved[0] && Math.hypot(ddx, ddy) <= 3) return;
            // a DENSITY PREVIEW owns the document (D247): the drag is
            // refused with the stats reason — pvRerun would snap the
            // move back on its next tick (clicks refuse too since the
            // D247 review fix — see the released handler)
            if (app.convertBusy()) return;
            if (!moved[0]) {
                // first real move: a track ball reveals its rig here
                // (the rig-only build keeps THIS ball alive, D188)
                if (onFirstDragMove != null) onFirstDragMove.run();
                moved[0] = true;
            }
            gizmoDragging = true;
            app.gestureHot = true;   // fast reconcile per event (D200)
            app.pointerDown = true;
            double step = gizmoStep(e.isShiftDown());   // Ctrl+Shift = a tenth
            // Shift = a tenth of the travel, re-based (the 3 px
            // deadband above reads the RAW travel)
            double[] tr = slowTravel(e, st[7], st[8]);
            double mx = tr[0] * st[6], my = tr[1] * st[6];
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
        };
        javafx.event.EventHandler<MouseEvent> release = e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            e.consume();
            if (moved[0]) {          // a real drag: no click actions
                base[0] = null;
                moved[0] = false;
                endGizmoDrag();
                return;
            }
            base[0] = null;
            // a DENSITY PREVIEW owns the document (D247 review fix —
            // the F3 gate on CLICKS too): a single click activates
            // the ball's piece, and an activation off the previewed
            // section cancels the preview (and ends a range chain)
            // silently; a double-click would open a panel over it
            if (app.convertBusy()) return;
            // DOUBLE-CLICK (v17.3, D140; EVERY ball since v18.11):
            // opens the owner's settings — seam window, Track Piece
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
        };
        gizmoParts.add(new GPart(0, rig, ball, mat, idle,
            Color.rgb(255, 240, 170), at, null, radius, false,
            press, drag, release));
    }

    /** The ACTIVE piece's entry gizmo: translation arrows + the yaw
     *  ring; pitch/roll rings only where "Edit with gizmo" is checked
     *  in the Track Piece window (uiLock* false — D254; the D101
     *  padlocks until then).
     *  A bridged-in piece takes the BRIDGE's enable rule since v19.28
     *  (D257: a ring per EDITABLE axis — BridgeSection.axisEditable,
     *  the bridge-end rig's rule; D249 read the padlocks as "held
     *  axes only") — reachable only through a manual bridge's
     *  target, and D247's run-wide balls give such a piece no entry
     *  ball of its own, so the bridge-end rig is the surface in
     *  practice. A piece PLACED by an optimize-mode bridge builds
     *  none of it (machine-owned — the previous piece's bridge gizmo
     *  is the surface). */
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
        // a ball double-click activates its piece (D247); the Track
        // Piece window itself shows the run HEAD whatever is selected
        // (batch 37)
        rig.onDouble = () -> {
            app.editor.activatePieceSection(pi, 0);
            app.openPlacementPopup(false);   // fresh, at the anchor (D248)
        };
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
                @Override public void apply(double lam, boolean snap,
                                            boolean fine) {
                    double step = gizmoStep(fine);
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
                    final double fx = nx, fy = ny, fz = nz;
                    app.placementEdit(p, () -> {   // D221 rigid carry
                        p.x = fx;
                        p.y = fy;
                        p.z = fz;
                    });
                    app.rebuildTrack();
                }
            });
        // "lockX" = NO ring / not written: a free piece's UNCHECKED
        // "Edit with gizmo" (uiLock true, D254), a bridged-in piece's
        // NON-EDITABLE axis (the bridge's enable rule since D257 —
        // every axis is editable under the manual mode this branch
        // admits, so all three rings build there)
        boolean lockY = into != null && !into.axisEditable(0);
        boolean lockP = into != null ? !into.axisEditable(1) : p.uiLockPitch;
        boolean lockR = into != null ? !into.axisEditable(2) : p.uiLockRoll;
        // LOCAL rings aim along the ENTRY FRAME (v18.18, D189 — the
        // NL2 look) and their drags are TRUE rotations about the
        // drawn axis (v18.19, D190: compose + decompose, all
        // unlocked deltas written); Global keeps the model Euler
        // axes with the classic one-Euler-per-ring writes. Under
        // DESIGN_ONLY the frame is the DESIGN pose (D249 — rigFrame;
        // the global pitch axis is the yawed base heading's
        // horizontal right, identical for both tiers since yaw is
        // shared).
        java.util.function.Supplier<Vec3> yawAx = () -> local
            ? rigFrame(p).U() : new Vec3(0, 1, 0);
        java.util.function.Supplier<Vec3> pitAx = () -> local
            ? rightOf(rigFrame(p)) : pitchAxis(p);
        java.util.function.Supplier<Vec3> rolAx =
            () -> rigFrame(p).T();
        // GLOBAL rings drive the RAW absolute Euler coordinate (D224:
        // base + delta, unreduced) — absolute-from-press stays
        // continuous THROUGH the pole (no ±85 clamp; the panel shows
        // the canonical, pole-flipped numbers)
        if (!lockY) {
            addRing(rig, 1.0, RING_YAW, yawAx,
                () -> p.rawAbsYaw(), deg -> {
                    pushOnce();
                    app.placementEdit(p,
                        () -> p.setRawAbsYaw(deg, true));   // D221 carry
                    app.rebuildTrack();
                }, local ? pieceLocal(p, yawAx, lockY, lockP, lockR)
                         : null);
        }
        if (!lockP) {
            // the ring's baseVal is the RAW of the tier the mode edits
            // (D249 review fix: under DESIGN_ONLY setRawAbs* derives
            // the design delta from `deg`, so the base must be the
            // design raw or the tier would jump onto real + drag;
            // item O re-aims the axes at the design frame)
            addRing(rig, 0.85, RING_PITCH, pitAx,
                () -> p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? p.rawAbsDesignPitch() : p.rawAbsPitch(), deg -> {
                    pushOnce();
                    app.placementEdit(p,
                        () -> p.setRawAbsPitch(deg, true));   // D222
                    app.rebuildTrack();
                }, local ? pieceLocal(p, pitAx, lockY, lockP, lockR)
                         : null);
        }
        if (!lockR) {
            addRing(rig, 0.7, RING_ROLL, rolAx,
                () -> p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? p.rawAbsDesignRoll() : p.rawAbsRoll(), deg -> {
                    pushOnce();
                    app.placementEdit(p,
                        () -> p.setRawAbsRoll(deg, true));   // D222
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
            private boolean dOnly() {
                return p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            }
            @Override public void begin() {
                // the frame of the tier the mode edits (D249 review
                // fix: DESIGN_ONLY rotates the DESIGN pose and the
                // decomposed deltas are design deltas — editPitch/
                // editRoll take those there)
                q0 = dOnly() ? p.designPose().orient()
                             : p.entryPose().orient();
                Vec3 a = axis.get();
                double len = a.length();
                a0 = len > 1e-9 ? a.scale(1 / len) : new Vec3(0, 1, 0);
            }
            @Override public void rotate(double deltaDeg) {
                Quat tgt = Quat.aboutAxis(a0,
                    Math.toRadians(deltaDeg)).mul(q0);
                // BRANCH-CONTINUOUS decompose (D224): the triple
                // nearest the current deltas — through the vertical
                // the pitch runs past 90 instead of yaw/roll jumping
                // 180 (the old clamp+canonical pair was the "wobble")
                boolean dO = dOnly();
                double[] d = TrackPiece.decomposeNear(p.baseOrient, tgt,
                    p.yawDeg, dO ? p.designPitchDeg : p.pitchDeg,
                    dO ? p.designRollDeg : p.rollDeg);
                if (d == null) return;   // heading undefined: hold
                pushOnce();
                app.placementEdit(p, () -> {   // D222 routing + carry
                    if (!lockY) p.yawDeg = wrapDeg(d[0]);
                    if (!lockP) p.editPitch(d[1]);
                    if (!lockR) p.editRoll(d[2]);
                });
                app.rebuildTrack();
            }
        };
    }

    /** A STRICT SEAM's orientation gizmo (v17.3, D140 — user spec):
     *  anchored ON the junction, rings ONLY for the DISCONTINUOUS
     *  axes (they hold an editable angle; a following axis has no
     *  number to turn — toggle it Discontinuous in the seam settings
     *  first) — except in DESIGN mode (D249), where the pitch/roll
     *  rings edit the design frame regardless of the padlocks.
     *  Writes go through the editor's seam path; one undo per
     *  gesture, the gizmo contract. */
    private void buildSeamGizmo(int pi, TrackPiece f) {
        Rig rig = newRig(() -> {
            Vec3 v = app.editor.seamWorldPos(pi);
            return v != null ? v : new Vec3(f.x, f.y, f.z);
        });
        rig.onDouble = () -> {
            app.editor.activatePieceSection(pi, 0);   // D247: activate first
            app.editor.showStrictSeamSettings(pi);
        };
        // LOCAL rings ride the follow-contract frame (v18.18, D189)
        // with TRUE about-the-axis drags (v18.19, D190: eulerOf
        // decomposition — the held angles ARE euler quantities).
        // DESIGN MODE (v19.20, D249 — item O): while the follower
        // edits its DESIGN orientation the rig SNAPS to the design
        // frame (designPose — local axes off it, the global pitch
        // axis = the design heading's horizontal right), the
        // pitch/roll rings build REGARDLESS of the padlocks with
        // baseVal = the design elevation/bank and write the design
        // tier absolutely (GraphEditor.gizmoSetSeamOrient →
        // Coaster.seamDesignSet — the real entry never moves); the
        // yaw ring stays the held REAL heading's (sLockYaw-gated —
        // heading is placement, never a design quantity). The mode
        // and the design tier ride the gizmo sig, so a flip rebuilds
        // the rig back onto the real frame.
        boolean local = app.settings.gizmoLocal();
        boolean design = f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        java.util.function.Supplier<Quat> frame = () -> design
            ? f.designPose().orient() : app.editor.seamEntryOrient(pi);
        java.util.function.Supplier<Vec3> yawAx = () -> local
            ? frame.get().U() : new Vec3(0, 1, 0);
        java.util.function.Supplier<Vec3> pitAx = () -> local
            ? rightOf(frame.get())
            : design ? horizontalRight(f.designPose().orient().T())
                     : seamPitchAxis(pi);
        java.util.function.Supplier<Vec3> rolAx = () -> frame.get().T();
        if (f.sLockYaw) {
            addRing(rig, 1.0, RING_YAW, yawAx,
                () -> f.sYawDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 0, wrapDeg(deg));
                }, local ? seamLocal(pi, yawAx) : null);
        }
        if (design || f.sLockPitch) {
            addRing(rig, 0.85, RING_PITCH, pitAx,
                () -> design ? designEuler(f, 1) : f.sPitchDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 1,
                        FvdDesigner.clamp(deg, -85, 85));
                }, local ? seamLocal(pi, pitAx) : null);
        }
        if (design || f.sLockRoll) {
            addRing(rig, 0.7, RING_ROLL, rolAx,
                () -> design ? designEuler(f, 2) : f.sRollDeg, deg -> {
                    pushOnce();
                    app.editor.gizmoSetSeamOrient(pi, 2, wrapDeg(deg));
                }, local ? seamLocal(pi, rolAx) : null);
        }
    }

    /** A follower's DESIGN elevation (axis 1) / bank (axis 2) — the
     *  seam rig's ring baseVal in design mode (D249); 0 when the
     *  design frame is vertical (the write then refuses with the
     *  editor's stats reason). */
    private static double designEuler(TrackPiece f, int axis) {
        double[] g = Coaster.eulerOf(f.designPose().orient());
        return Double.isNaN(g[axis]) ? 0 : g[axis];
    }

    /** The horizontal right of a heading T (the global pitch-ring
     *  axis for a frame); world X when T is vertical. */
    private static Vec3 horizontalRight(Vec3 t) {
        Vec3 r = new Vec3(-t.z(), 0, t.x());
        double len = r.length();
        return len > 1e-9 ? r.scale(1 / len) : new Vec3(1, 0, 0);
    }

    /** Bridge-rig local sink (v18.19): decompose against the NEXT
     *  piece's base orientation, write through the editor's combined
     *  bridge path (the editable-axis filter + the optimize re-place
     *  handled there — every axis under manual / position mode, the
     *  held ones under optimize-orientation, D257). */
    private RingLocal bridgeLocal(int pi, TrackPiece t,
            java.util.function.Supplier<Vec3> axis) {
        return new RingLocal() {
            private Quat q0;
            private Vec3 a0;
            private boolean dOnly() {
                return t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            }
            @Override public void begin() {
                // the tier the mode edits (D249 review fix — the piece
                // rig's rule): DESIGN_ONLY rotates the design pose
                q0 = dOnly() ? t.designPose().orient()
                             : t.entryPose().orient();
                Vec3 a = axis.get();
                double len = a.length();
                a0 = len > 1e-9 ? a.scale(1 / len) : new Vec3(0, 1, 0);
            }
            @Override public void rotate(double deltaDeg) {
                Quat tgt = Quat.aboutAxis(a0,
                    Math.toRadians(deltaDeg)).mul(q0);
                boolean dO = dOnly();
                double[] d = TrackPiece.decomposeNear(t.baseOrient, tgt,
                    t.yawDeg, dO ? t.designPitchDeg : t.pitchDeg,
                    dO ? t.designRollDeg : t.rollDeg);   // D224
                if (d == null) return;
                pushOnce();
                app.editor.gizmoSetBridgeOrientAll(pi, wrapDeg(d[0]),
                    d[1], wrapDeg(d[2]));
            }
        };
    }

    /** Seam-rig local sink (v18.19): the held angles are eulerOf
     *  quantities, so the rotated frame decomposes via eulerOf;
     *  DISCONTINUOUS axes get the values, following axes just
     *  follow (they hold no number). DESIGN MODE (D249): the sink
     *  rotates the DESIGN pose instead — its elevation/bank land on
     *  the design tier absolutely and the rotation's HEADING DELTA
     *  (off the press-time design heading) goes to the held real yaw
     *  when that axis is locked (GraphEditor.gizmoSetSeamDesignAll). */
    private RingLocal seamLocal(int pi,
            java.util.function.Supplier<Vec3> axis) {
        return new RingLocal() {
            private Quat q0;
            private Vec3 a0;
            private boolean design;
            private double h0, y0;
            @Override public void begin() {
                TrackPiece f = pi < app.coaster.pieces.size()
                    ? app.coaster.pieces.get(pi) : null;
                design = f != null
                    && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
                q0 = design ? f.designPose().orient()
                            : app.editor.seamEntryOrient(pi);
                if (design) {
                    h0 = Coaster.eulerOf(q0)[0];
                    y0 = f.sYawDeg;
                }
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
                if (design) {
                    double heldYaw = Double.isNaN(h0) ? Double.NaN
                        : y0 + TrackPiece.wrap180(e[0] - h0);
                    app.editor.gizmoSetSeamDesignAll(pi, heldYaw, e[1],
                        e[2]);
                    return;
                }
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
     *  Manual mode: translation arrows; optimize modes: the march
     *  owns position — ONE orange tangent arrow drags the LENGTH
     *  (hidden under Optimize length). A RING PER EDITABLE AXIS
     *  since v19.28 (D257, user spec: "only if the value in the box
     *  is grayed out should you hide its respective gizmo" —
     *  BridgeSection.axisEditable, the bridge window's own enable
     *  rule): EVERY axis under manual and position mode, locked or
     *  not (nothing aligns it there, the box is typeable), and the
     *  HELD (🔒) axes under optimize-orientation (the free ones are
     *  the march's — their boxes gray). D249 built the held axes'
     *  rings only, in manual and position mode, and none under
     *  optimize-orientation; pre-D249 the rings were the UNLOCKED
     *  axes' and manual-only. Writes go
     *  through the editor's trailing-bridge gizmo paths (undo pushed
     *  here, once per gesture; the optimize modes re-place per
     *  event through gizmoReplace) — PIECE-INDEXED since v19.18
     *  (D247): `pi` is the piece the bridge trails, so a rig
     *  revealed from a non-active piece's bridge-end ball writes to
     *  THAT bridge. */
    private void buildBridgeGizmo(boolean local, int pi, BridgeSection bs,
                                  TrackPiece t) {
        Rig rig = newRig(() -> new Vec3(t.x, t.y, t.z));
        // the bridge window's rows sync only while the bridge
        // SECTION itself is active: activate (pi, last) first (D247)
        rig.onDouble = () -> {
            if (pi >= app.coaster.pieces.size()) return;   // stale rig
            app.editor.activatePieceSection(pi,
                app.coaster.pieces.get(pi).sections.size() - 1);
            app.openBridgeWindow(pi);   // "Bridge Settings (Strict Seam n)" (D249/D254)
        };
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
                    @Override public void apply(double lam, boolean snap,
                                                boolean fine) {
                        double step = gizmoStep(fine);
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
                        final double fx = nx, fy = ny, fz = nz;
                        app.placementEdit(t, () -> {   // D221 carry
                            t.x = fx;
                            t.y = fy;
                            t.z = fz;
                        });
                        app.editor.gizmoBridgeMoved(pi);
                    }
                });
        } else if (!bs.optLen) {
            Group arrow = gizmoArrow(rig, () -> t.entryPose().orient().T(),
                GIZ_LEN);
            wireAxisDrag(arrow, rig, () -> t.entryPose().orient().T(),
                new AxisSink() {
                    private double base;
                    @Override public void begin(Vec3 dir0) {
                        base = app.editor.gizmoBridgeGap(pi);
                    }
                    @Override public void apply(double lam, boolean snap,
                                                boolean fine) {
                        double gap = base + lam;
                        if (snap) {
                            double step = gizmoStep(fine);
                            gap = Math.round(gap / step) * step;
                        }
                        pushOnce();
                        app.editor.gizmoSetBridgeGap(pi, Math.max(1, gap));
                    }
                });
            rig.group.getChildren().add(arrow);
        }
        // LOCAL rings ride the next piece's entry frame (v18.18,
        // D189) with TRUE about-the-axis drags (v18.19, D190) —
        // the DESIGN frame while the target edits its design
        // orientation (D249, rigFrame; the piece rig's rule)
        java.util.function.Supplier<Vec3> yawAx = () -> local
            ? rigFrame(t).U() : new Vec3(0, 1, 0);
        java.util.function.Supplier<Vec3> pitAx = () -> local
            ? rightOf(rigFrame(t)) : pitchAxis(t);
        java.util.function.Supplier<Vec3> rolAx =
            () -> rigFrame(t).T();
        // raw absolute coordinates (D224) — continuous through the
        // pole like the piece rig; a ring per EDITABLE axis (D257 —
        // the box's own enable rule; the sig signs the mode + locks,
        // so a radio or padlock flip rebuilds the set)
        if (bs.axisEditable(0)) {
            addRing(rig, 1.0, RING_YAW, yawAx,
                () -> t.rawAbsYaw(), deg -> {
                    pushOnce();
                    app.editor.gizmoSetBridgeOrient(pi, 0, deg);
                }, local ? bridgeLocal(pi, t, yawAx) : null);
        }
        if (bs.axisEditable(1)) {
            // baseVal = the raw of the tier the mode edits (D249
            // review fix — the piece rig's rule)
            addRing(rig, 0.85, RING_PITCH, pitAx,
                () -> t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? t.rawAbsDesignPitch() : t.rawAbsPitch(), deg -> {
                    pushOnce();
                    app.editor.gizmoSetBridgeOrient(pi, 1, deg);
                }, local ? bridgeLocal(pi, t, pitAx) : null);
        }
        if (bs.axisEditable(2)) {
            addRing(rig, 0.7, RING_ROLL, rolAx,
                () -> t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? t.rawAbsDesignRoll() : t.rawAbsRoll(), deg -> {
                    pushOnce();
                    app.editor.gizmoSetBridgeOrient(pi, 2, deg);
                }, local ? bridgeLocal(pi, t, rolAx) : null);
        }
    }

    /** The model's pitch axis at a piece's entry: horizontal right of
     *  the heading (TrackPiece.entryPose's exact axis). */
    private static Vec3 pitchAxis(TrackPiece p) {
        // the YAWED BASE heading's horizontal right (D224): identical
        // to the true T's for |pitch| < 90 (pitching keeps heading)
        // and, unlike it, continuous THROUGH the pole — the raw
        // pitch coordinate the global ring drives rotates about
        // exactly this axis
        Vec3 t = p.genPose().orient().T();
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
        /** snap = Ctrl (the element-length step), fine = Shift (the
         *  snap at a tenth of the step; λ itself arrives slowed). */
        void apply(double lam, boolean snap, boolean fine);
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
        MeshView ring = torus(radius, Gestures.RING_TUBE_R, mat);
        Group g = new Group(ring);
        Rotate rot = new Rotate();
        g.getTransforms().add(rot);
        Runnable aimIt = () -> aim(rot, axis.get());
        aimIt.run();
        rig.aims.add(aimIt);
        // hover + drag through the screen-space picker (D248)
        wireRingDrag(g, rig, mat, col, radius, axis, baseVal, applyAbs,
            localSink);
        rig.group.getChildren().add(g);
    }

    /** Shaft + cone tip along +local-Y, aimed along the (live) WORLD
     *  direction — thicker since v16.12 (grabbing needed too much
     *  precision); since D248 the grab band is the picker's
     *  PICK_ARROW_PX, independent of the drawn width. The drawn
     *  radii/lengths are the Gestures ARROW_* constants (batch 37):
     *  the depth-aware pick casts its ray at the SAME shape. */
    private Group gizmoArrow(Rig rig,
            java.util.function.Supplier<Vec3> dir, Color col) {
        return gizmoArrow(rig, dir, col, false);
    }

    /** twoSided (v18.7, D176 — user spec): TRANSLATION arrows point
     *  BOTH ways from the anchor (a mirrored shaft+tip half); the
     *  bridge LENGTH tangent stays one-sided (its direction means
     *  "longer"). One axis, one drag law — either side grabs; the
     *  axis's screen image is measured on the half that can steer
     *  (arrowAxis). */
    private Group gizmoArrow(Rig rig,
            java.util.function.Supplier<Vec3> dir, Color col,
            boolean twoSided) {
        PhongMaterial mat = gizmoMat(col);
        Cylinder shaft = new Cylinder(Gestures.ARROW_SHAFT_R,
            Gestures.ARROW_SHAFT_LEN);
        shaft.setMaterial(mat);
        shaft.setTranslateY(Gestures.ARROW_SHAFT_LEN / 2);
        MeshView tip = cone(Gestures.ARROW_TIP_R, Gestures.ARROW_SHAFT_LEN,
            Gestures.ARROW_LEN, mat);
        Group g = new Group(shaft, tip);
        if (twoSided) {
            Cylinder shaft2 = new Cylinder(Gestures.ARROW_SHAFT_R,
                Gestures.ARROW_SHAFT_LEN);
            shaft2.setMaterial(mat);
            shaft2.setTranslateY(Gestures.ARROW_SHAFT_LEN / 2);
            MeshView tip2 = cone(Gestures.ARROW_TIP_R,
                Gestures.ARROW_SHAFT_LEN, Gestures.ARROW_LEN, mat);
            Group neg = new Group(shaft2, tip2);
            neg.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
            g.getChildren().add(neg);
        }
        Rotate rot = new Rotate();
        g.getTransforms().add(rot);
        Runnable aimIt = () -> aim(rot, dir.get());
        aimIt.run();
        rig.aims.add(aimIt);
        // the picker's build data — wireAxisDrag registers the part
        // (hover glow is central too, D248)
        g.setUserData(new ArrowMeta(mat, col, dir, twoSided));
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

    /** The rigs' hover tint (arrows/rings): the color half-way to
     *  white — applied by setGizmoHover (D248; the per-node
     *  MOUSE_ENTERED/EXITED glow of v16.12 is gone with the nodes'
     *  mouse role). */
    private static Color hoverTint(Color col) {
        return col.interpolate(Color.WHITE, 0.5);
    }

    /** Axis drag, ABSOLUTE from the press (v16.12): the axis's screen
     *  image is captured once at the press, so per-event λ is a pure
     *  function of how far the mouse moved from the press point —
     *  parts that move while dragged cannot feed back. Ctrl hands the
     *  sink snap=true. Events are consumed so the orbit/click
     *  handlers never see them. */
    private void wireAxisDrag(Group part, Rig rig,
            java.util.function.Supplier<Vec3> axis, AxisSink sink) {
        ArrowMeta meta = (ArrowMeta) part.getUserData();   // gizmoArrow's
        double[] sv = new double[3];   // press-time screen axis: sx, sy, |s|²
        javafx.event.EventHandler<MouseEvent> press = e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            sub.requestFocus();
            if (app.convertBusy()) return;   // preview owns the doc (D247)
            Vec3 d = axis.get();
            // the press-time image of 1 m along the axis — measured
            // on the half that can steer, over a step chosen by view
            // depth (arrowAxis; review rounds: with the eye within a
            // metre or two of the junction the 1 m point lies behind
            // the eye, or just in front of it where the perspective
            // divide blows its image up, while the drawn arrow is in
            // plain view)
            ArrowAxis ax = arrowAxis(rig, d, meta.twoSided());
            // no screen axis to steer by: ungrabbable — pickPart
            // outranks such an arrow (arrowGrabbable = this very
            // call), so this fires only when nothing else was in
            // reach; it says why (batch 37 — the old `1 m spans
            // < 2 px` test also swallowed EVERY arrow press once the
            // camera was far away, silently)
            if (ax.why() != AX_OK) {
                app.statsLabel.setText(ax.refusal());
                return;
            }
            sv[0] = ax.sx();
            sv[1] = ax.sy();
            sv[2] = sv[0] * sv[0] + sv[1] * sv[1];
            gizmoDragging = true;
            app.gestureHot = true;   // fast reconcile per event (D200)
            app.pointerDown = true;
            gizmoPushed = false;
            gDragX = e.getScreenX();
            gDragY = e.getScreenY();
            beginSlowTravel(gDragX, gDragY);
            sink.begin(d);
            e.consume();
        };
        javafx.event.EventHandler<MouseEvent> drag = e -> {
            if (!gizmoDragging) return;
            double[] tr = slowTravel(e, gDragX, gDragY);   // Shift = a tenth
            double lam = (tr[0] * sv[0] + tr[1] * sv[1]) / sv[2];
            lam = FvdDesigner.clamp(lam, -60 * rig.scale, 60 * rig.scale);
            // Ctrl snaps the SLOWED λ; Ctrl+Shift at a tenth of the step
            sink.apply(lam, e.isControlDown(), e.isShiftDown());
            e.consume();
        };
        javafx.event.EventHandler<MouseEvent> release = e -> {
            boolean was = gizmoPushed;
            endGizmoDrag();
            // dbl-click anywhere on the rig = its settings (v18.13);
            // a real drag (something was pushed) never counts
            if (!was && e.getClickCount() == 2 && rig.onDouble != null) {
                app.nextPopupAnchor(e.getScreenX(), e.getScreenY());
                rig.onDouble.run();
            }
            e.consume();
        };
        gizmoParts.add(new GPart(1, rig, part, meta.mat(), meta.col(),
            hoverTint(meta.col()), rig.anchor, meta.dir(), ARROW_LEN,
            meta.twoSided(), press, drag, release));
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
    private void wireRingDrag(Group part, Rig rig, PhongMaterial mat,
            Color col, double radius,
            java.util.function.Supplier<Vec3> axis,
            java.util.function.DoubleSupplier baseVal,
            java.util.function.DoubleConsumer applyAbs,
            RingLocal localSink) {
        double[] st = new double[4];   // ux, uy, deg/px, base value
        javafx.event.EventHandler<MouseEvent> press = e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            sub.requestFocus();
            if (app.convertBusy()) return;   // preview owns the doc (D247)
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
            beginSlowTravel(gDragX, gDragY);
            e.consume();
        };
        javafx.event.EventHandler<MouseEvent> drag = e -> {
            if (!gizmoDragging) return;
            double[] tr = slowTravel(e, gDragX, gDragY);   // Shift = a tenth
            double dist = tr[0] * st[0] + tr[1] * st[1];
            // Ctrl snaps to whole degrees, Ctrl+Shift to tenths
            double q = e.isShiftDown() ? 10 : 1;
            if (localSink != null) {
                // rotate about the DRAWN axis (v18.19): same mouse
                // mapping, the delta feeds the quaternion compose
                double delta = -dist * st[2];   // world = −scene mirror
                if (e.isControlDown()) delta = Math.round(delta * q) / q;
                localSink.rotate(delta);
                e.consume();
                return;
            }
            double val = st[3] - dist * st[2];   // world = −scene (mirror)
            if (e.isControlDown()) val = Math.round(val * q) / q;
            applyAbs.accept(val);
            e.consume();
        };
        javafx.event.EventHandler<MouseEvent> release = e -> {
            boolean was = gizmoPushed;
            endGizmoDrag();
            // dbl-click anywhere on the rig = its settings (v18.13)
            if (!was && e.getClickCount() == 2 && rig.onDouble != null) {
                app.nextPopupAnchor(e.getScreenX(), e.getScreenY());
                rig.onDouble.run();
            }
            e.consume();
        };
        gizmoParts.add(new GPart(2, rig, part, mat, col, hoverTint(col),
            rig.anchor, axis, radius, false, press, drag, release));
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
            // routed by its center like every glyph (batch 37) — HERE,
            // because a live gizmo drag only re-positions (no rebuild):
            // the ball re-parents on the event it crosses the ground.
            // Safe mid-gesture: the drag rides gPress, never the node
            Group want = FloorDepth.itemUnder(a.y())
                ? trackBallUnder : trackBallGroup;
            if (rm.ball().getParent() != want) {
                trackBallGroup.getChildren().remove(rm.ball());
                trackBallUnder.getChildren().remove(rm.ball());
                want.getChildren().add(rm.ball());
            }
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
