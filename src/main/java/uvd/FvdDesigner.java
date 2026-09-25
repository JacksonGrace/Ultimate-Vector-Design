package uvd;

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
import javafx.scene.control.ChoiceBox;
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
    // MULTI-COASTER document (v19.2, D230): `coasters` is the file,
    // `coaster` the ACTIVE one — every editor/viewport path reads it
    // live, so switching just re-points it (plus the look swap). An
    // EMPTY document keeps `coaster` on a zero-piece placeholder so no
    // dereference NPEs; hasCoaster() gates every path that indexes
    // pieces (the editor shows its no-coaster panel then).
    final List<Coaster> coasters = new ArrayList<>();
    int activeCoaster = 0;
    private final Coaster emptyCoaster = new Coaster();
    Coaster coaster = withDefaultFriction(
        Coaster.singleForce(lanes, 26, 0, 21, 0));
    { coasters.add(coaster); }

    boolean hasCoaster() { return !coasters.isEmpty(); }

    /** The startup document counts as a NEW document: it gets the
     *  NL2-like friction defaults (v18.0, D161 — Coaster's own field
     *  defaults stay 0 so headless gates and loaded files see the
     *  loss-free model unless a `friction` line says otherwise). */
    private static Coaster withDefaultFriction(Coaster c) {
        c.fricRoll = FRIC_ROLL_DEFAULT;
        c.fricAir = FRIC_AIR_DEFAULT;
        return c;
    }

    // ---------- track appearance settings (per-coaster, saved in .fvd) ----------
    double heartOffset = 1.0;  // m from heartline down to track (negative = inverted)
    double railGauge = 1.0;    // m between rail centers
    double tieSpacing = 1.0;   // m of track between cross ties

    // NL2-like friction defaults for NEW documents (v18.0, D161).
    // Loaded files keep their own `friction` line (missing = 0 0).
    static final double FRIC_ROLL_DEFAULT = 0.03;    // m/m
    static final double FRIC_AIR_DEFAULT = 1.0e-4;   // 1/m

    // File > New starts low and slow (v18.32, D204 — user spec): 5 m
    // above the ground grid at 5 m/s, a 5 s starting section (v18.38,
    // D210). The STARTUP document keeps its historic 21 m / 26 m/s —
    // it ships the demo layout, whose hills need that energy budget.
    /** THE DISPLAY NAME (v19.20, D249 — user spec: "Ultimate Vector
     *  Design", was "FVD Coaster Designer"): the window title
     *  ("<file>* — Ultimate Vector Design"), the Help title. The
     *  pure-Java layer mirrors it as LITERALS (AppSettings' properties
     *  header, Nl2ElemExporter's description) — it must not import
     *  the shell. The settings FILE name (~/.fvd-designer.properties),
     *  the package and pom stay (a rename plan lives in
     *  docs/PACKAGE-RENAME-PLAN.md). */
    static final String APP_NAME = "Ultimate Vector Design";
    static final double NEW_START_Y = 5;     // m above ground
    static final double NEW_START_SPEED = 5; // m/s
    static final double NEW_START_LEN = 5;   // s of held 1 g

    /** Fixed-decimal spinner converter (the air-resistance spinner
     *  needs 5 places — the default converter rounds 1e-4 away). */
    static javafx.util.StringConverter<Double> fixedDecimalConverter(int places) {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(Double v) {
                return v == null ? ""
                    : String.format(java.util.Locale.US, "%." + places + "f", v);
            }
            @Override public Double fromString(String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ex) {
                    return 0.0;
                }
            }
        };
    }

    // ---------- document-level undo state (Phase 1 milestone B, D51) ----------
    // one snapshot = everything that shapes the RIDDEN TRACK: the lanes plus
    // heart, gauge, start placement and v0 (user rule: "everything that
    // changes the track path, not just aesthetics"). Tie spacing and colors
    // stay menu-local-undo only.
    // mode: 0 force, 1 geo, 2 straight/curve (lanes null, elements
    // set), 3 bridge (lanes+elements null, bridge set). dlanes /
    // vAuthored / editInfl = the influence state (v15.0, D98; dlanes
    // null + vAuthored NaN = uninfluenced). dist/viewOther = the
    // authoring domain + cross-domain view flag (v16.0, D103 — the
    // view rides undo like editInfl, never the file)
    record SecState(List<List<BezierBaker.Node>> lanes,
                    List<StraightCurveSection.Element> elements,
                    double vDesign, int mode, boolean reversed,
                    boolean pinned, BridgeSection bridge,
                    List<List<BezierBaker.Node>> dlanes,
                    double vAuthored, boolean editInfl,
                    boolean dist, boolean viewOther,
                    boolean rollG, boolean ypG,
                    boolean inflRollG, boolean inflYpG,
                    boolean rateM, boolean rollAbs,
                    boolean ypAbs,   // v19.8, D237
                    // speed controls + speed-line diamonds (v18.0,
                    // D160 — path data, deep-copied like lanes)
                    List<SpeedControl> sctls, List<Double> smarks,
                    // per-lane END-FOLLOW flags (v19.19, D248;
                    // null for the non-lane modes)
                    boolean[] endFollows) {}

    /** Deep copy of a 3-lane node-list set (null-safe). */
    private static List<List<BezierBaker.Node>> copyLanes(
            List<List<BezierBaker.Node>> lanes) {
        if (lanes == null) return null;
        List<List<BezierBaker.Node>> copy = new ArrayList<>(3);
        for (List<BezierBaker.Node> lane : lanes) {
            List<BezierBaker.Node> laneCopy = new ArrayList<>(lane.size());
            for (BezierBaker.Node n : lane) laneCopy.add(n.copy());
            copy.add(laneCopy);
        }
        return copy;
    }

    // one per TrackPiece (v12.0; baseOrient v12.1; pitch/roll deltas
    // v14.0 — the v13.13 fields were MISSING here, so any undo zeroed
    // a piece's pitch/roll placement, D89)
    record PieceState(List<SecState> sections,
                      double x, double y, double z, double yaw,
                      double pitch, double roll, Quat base,
                      // the Placement padlocks ride the history too
                      // (v16.12, user rule: Ctrl+Z after a gizmo move
                      // must not RELOCK a padlock the user opened —
                      // rebuilding TrackPiece defaulted them shut, so
                      // every undo slammed the locks)
                      boolean lockPitch, boolean lockRoll,
                      // strict seam (v17.1, D135; values D136;
                      // smoothing v17.2, D139)
                      boolean followPrev, boolean sLockYaw,
                      boolean sLockPitch, boolean sLockRoll,
                      double sYaw, double sPitch, double sRoll,
                      double sSmooth,
                      // canonical generation pair (v18.27, D199 —
                      // undo of a split must restore it or the split
                      // piece loses its bitwise chain state)
                      double canonPsi, Quat canonOrient,
                      // two-tier orientation (v19.0, D221/D222); the
                      // 3-state edit-routing mode (v19.20, D249)
                      TrackPiece.OrientMode orientMode,
                      double designPitch, double designRoll,
                      // "Affect Subsequent Strict Seams" (D249 — the
                      // inverted rename of D248's Only-Affect flag)
                      boolean affectSeams) {}

    /** One coaster's undoable state (v19.2, D230): name, pieces, the
     *  path-shaping globals (heart/gauge/v0/friction — D51) and its
     *  look (restored ONLY onto a coaster an undo re-creates; the
     *  cosmetics of a live coaster stay outside undo, D51). */
    record CoasterState(String name, List<PieceState> pieces,
                        double heart, double gauge, double v0,
                        double fricRoll, double fricAir,
                        DesignIO.Cosmetics look) {}

    /** The whole document (D230): every coaster + which is active +
     *  the editor's active piece/section + the start wizard's target
     *  (null = none; see StartPanel). */
    record DocState(List<CoasterState> coasters, int activeCoaster,
                    int activePiece, int active, WizardState wizard) {}

    /** The start wizard's DOCUMENT state (v19.2, D231): which coaster/
     *  piece it is building and whether the section TYPE was picked
     *  yet (a pending piece renders nothing). Rides undo so Ctrl+Z
     *  right after opening the wizard removes its piece/coaster.
     *  lenTime/lenDist (v19.7, D236) = the Initial-size spinner's
     *  PER-DOMAIN memory — an edited 6 s survives a domain
     *  round-trip; s/c shares the distance slot. */
    record WizardState(int mode, int coasterIdx, int pieceIdx, boolean picked,
                       boolean sc, double lenTime, double lenDist) {}

    private boolean docApplying = false;   // suppress spinner listeners while
                                           // an undo/redo writes them back

    DocState snapshotDoc() {
        List<CoasterState> cs = new ArrayList<>();
        for (int i = 0; i < coasters.size(); i++) {
            Coaster c = coasters.get(i);
            boolean act = i == activeCoaster;
            DesignIO.Cosmetics look = act ? shellLook() : c.look;
            cs.add(new CoasterState(c.name, snapshotPieces(c),
                act ? heartOffset : lookOf(c).heart(),
                act ? railGauge : lookOf(c).gauge(),
                c.v0, c.fricRoll, c.fricAir, look));
        }
        return new DocState(cs, activeCoaster,
            editor != null ? editor.activePiece() : 0,
            editor != null ? editor.activeSection() : 0,
            wizardState);
    }

    /** Re-sync the (off-scene) Start-speed spinner to coaster.v0
     *  without a history push (v19.6, D235 — the delete-first-
     *  section re-anchor moves v0 onto the new first section). */
    void syncStartSpeedSpinner() {
        docApplying = true;
        startSpeedSpinner.getValueFactory().setValue(coaster.v0);
        docApplying = false;
    }

    /** Write the RIDE ENTRY's start speed WITHOUT a history push
     *  (v19.20, D249 — the live initial-speed window coalesces its
     *  own undo steps, so the Start-speed spinner's wireDocSpinner
     *  listener must stay muted): the spinner's factory clamps
     *  ([1,100] — the D198 entry rule), then v0 and section 0's
     *  vDesign take the clamped value, exactly what the listener's
     *  apply writes. */
    /** The value the Start-speed spinner's factory would take for v
     *  (its [1,100] clamp — the D198 entry rule), without writing
     *  anything: the live initial-speed window's no-op test (D249). */
    double clampStartSpeed(double v) {
        SpinnerValueFactory.DoubleSpinnerValueFactory f =
            (SpinnerValueFactory.DoubleSpinnerValueFactory)
                startSpeedSpinner.getValueFactory();
        return Math.max(f.getMin(), Math.min(f.getMax(), v));
    }

    void writeStartSpeed(double v) {
        boolean was = docApplying;
        docApplying = true;
        try {
            startSpeedSpinner.getValueFactory().setValue(v);
        } finally {
            docApplying = was;
        }
        Double cv = startSpeedSpinner.getValue();
        if (cv == null || !hasCoaster() || coaster.pieces.isEmpty()) return;
        coaster.v0 = cv;
        coaster.soleSection().vDesign(cv);
    }

    /** A coaster's look — its stash, or the shell's for the active one,
     *  or defaults. */
    DesignIO.Cosmetics lookOf(Coaster c) {
        if (c == coaster && hasCoaster()) return shellLook();
        return c.look != null ? c.look : DesignIO.Cosmetics.defaults();
    }

    List<PieceState> snapshotPieces(Coaster coaster) {
        List<PieceState> ps = new ArrayList<>();
        for (TrackPiece piece : coaster.pieces) {
            List<SecState> secs = new ArrayList<>();
            for (Section s : piece.sections) {
                if (s instanceof BridgeSection bs) {
                    secs.add(new SecState(null, null, bs.vDesign, 3,
                        bs.reversed, false, bs.copy(),
                        null, Double.NaN, false, false, false,
                        false, false, false, false, false, false,
                        false, null, null, null));
                    continue;
                }
                if (s instanceof StraightCurveSection sc) {
                    List<StraightCurveSection.Element> els =
                        new ArrayList<>(sc.elements.size());
                    for (StraightCurveSection.Element e : sc.elements) els.add(e.copy());
                    secs.add(new SecState(null, els, sc.vDesign, 2, sc.reversed,
                        sc.vPinned, null, null, Double.NaN, false,
                        true, sc.viewOtherDomain, false, false,
                        false, false, false, false, false,
                        SpeedControl.copyList(sc.speedCtls),
                        new ArrayList<>(sc.speedMarks), null));
                    continue;
                }
                LaneSection fs = (LaneSection) s;
                secs.add(new SecState(copyLanes(fs.lanes), null, fs.vDesign,
                    fs instanceof GeoSection ? 1 : 0, fs.reversed,
                    fs.vPinned, null,
                    copyLanes(fs.deltaLanes), fs.vAuthored, fs.editInfluence,
                    fs.domain == Section.Domain.DISTANCE, fs.viewOtherDomain,
                    fs.rollGlobal, fs.yawPitchGlobal,
                    fs.inflRollGlobal, fs.inflYawPitchGlobal,
                    fs.ratePerMeter, fs.rollAbsolute, fs.ypAbsolute,
                    SpeedControl.copyList(fs.speedCtls),
                    new ArrayList<>(fs.speedMarks),
                    fs.endFollows.clone()));
            }
            ps.add(new PieceState(secs, piece.x, piece.y, piece.z,
                piece.yawDeg, piece.pitchDeg, piece.rollDeg,
                piece.baseOrient, piece.uiLockPitch, piece.uiLockRoll,
                piece.followPrev, piece.sLockYaw,
                piece.sLockPitch, piece.sLockRoll,
                piece.sYawDeg, piece.sPitchDeg, piece.sRollDeg,
                piece.sSmoothLen, piece.canonPsiDeg, piece.canonOrient,
                piece.orientMode, piece.designPitchDeg,
                piece.designRollDeg, piece.affectSubsequentSeams));
        }
        return ps;
    }

    /** Restore a document snapshot: pieces, path settings, and the
     *  spinner displays (their listeners are suppressed — fields are
     *  set here; placement spinners show the ACTIVE piece). */
    /** Bumped whenever the document's piece OBJECTS are replaced
     *  wholesale (undo/redo/density-preview via applyDocState, File >
     *  New, Load). Viewport signatures include it (v18.33, D205): the
     *  gizmo/ball closures capture TrackPiece objects at build time,
     *  and a value-identical replacement — a convert preview's Apply,
     *  an undo that only touched lanes — used to leave them writing
     *  to an ORPHANED piece ("only the gizmo moved, not the track"). */
    int docGen;

    /** LIVE TOOL WINDOWS (v19.18, D247): every modeless window that
     *  shows model state registers a READ-ONLY resync here when it
     *  shows and removes it when it hides — the node editor's
     *  redrawHooks generalized to the shell. Fired at the end of every
     *  rebuildTrack (incl. its early returns — every gizmo drag event,
     *  wireDocSpinner edit, undo restore, density-preview tick and
     *  Coaster op rebuilds) and from GraphEditor.redraw() (graph-only
     *  edits). Hooks never pushUndo / markDirty / write the model, and
     *  never overwrite a box the user is typing in (each hook guards
     *  on its editor's focus; a spinner skipped while focused commits
     *  the USER's value on blur — their intent wins). Hooks that write
     *  their OWN controls set their own mute (seamSyncing, syncing[0],
     *  muted); the docApplying=true around the loop covers the
     *  wireDocSpinner-style shell listeners. */
    final List<Runnable> modelHooks = new ArrayList<>();
    private boolean inModelHooks;

    /** Run every registered live-window resync once (D247). Re-entry
     *  is blocked (a hook that triggers redraw() → fireModelHooks
     *  recursion no-ops), the list is iterated on a copy (a hook may
     *  hide its window and de-register itself), and a throwing hook
     *  is logged and skipped so it can never abort the rebuild's
     *  tail. */
    void fireModelHooks() {
        if (modelHooks.isEmpty() || inModelHooks) return;
        inModelHooks = true;
        boolean was = docApplying;
        docApplying = true;
        try {
            for (Runnable r : new ArrayList<>(modelHooks)) {
                try { r.run(); }
                catch (RuntimeException ex) {
                    System.err.println("model hook failed: " + ex);
                }
            }
        } finally {
            docApplying = was;
            inModelHooks = false;
        }
    }

    /** true while applyDocState restores a snapshot (D247): the
     *  focus-guarded live resyncs (setUnlessTyping) FORCE their write
     *  then — an undo must overwrite a box even if it holds focus,
     *  the node editor's docRestoreHooks semantics. */
    private boolean docRestoring;

    void applyDocState(DocState s) {
        docApplying = true;
        docRestoring = true;
        try {
            applyDocStateBody(s);
        } finally {
            docRestoring = false;
        }
    }

    private void applyDocStateBody(DocState s) {
        docGen++;
        // MULTI-COASTER restore (D230): rebuild every coaster's pieces;
        // coaster OBJECTS are reused by index (their looks stay), new
        // ones (an undone delete) take the snapshot's look; the active
        // coaster follows the snapshot with the look swap
        stashActiveLook();
        otherCompiles.clear();
        while (coasters.size() > s.coasters().size()) {
            coasters.remove(coasters.size() - 1);
        }
        for (int i = 0; i < s.coasters().size(); i++) {
            CoasterState cst = s.coasters().get(i);
            Coaster c;
            if (i < coasters.size()) {
                c = coasters.get(i);
            } else {
                c = new Coaster();
                c.look = cst.look();
                coasters.add(c);
            }
            c.name = cst.name();
            c.v0 = cst.v0();
            c.fricRoll = cst.fricRoll();
            c.fricAir = cst.fricAir();
            restorePieces(c, cst.pieces());
            if (c.look == null) c.look = cst.look();
            // heart/gauge ride undo per coaster (D51): into the stash
            // for inactive coasters, into the shell below for the
            // active one
            if (c.look != null) {
                DesignIO.Cosmetics lk = c.look;
                c.look = new DesignIO.Cosmetics(cst.heart(), cst.gauge(),
                    lk.tiespace(), lk.railSolid(), lk.railColor(),
                    lk.gradColors(), lk.gradStops(), lk.latColors(),
                    lk.latStops(), null);
            }
        }
        wizardState = s.wizard();
        // undo INTO a wizard-era state re-shows the panel (v19.7,
        // D236, user spec — Ctrl+Z right after Done reopens the
        // wizard; a snapshot without a wizard closes it)
        wizardOpen = wizardState != null;
        if (coasters.isEmpty()) {
            coaster = emptyCoaster;
            activeCoaster = 0;
        } else {
            activeCoaster = (int) clamp(s.activeCoaster(), 0, coasters.size() - 1);
            coaster = coasters.get(activeCoaster);
            applyLook(lookOf(coaster));
        }
        if (editor != null && hasCoaster()) {
            editor.restoreActive(s.activePiece(), s.active());
        }
        heartSpinner.getValueFactory().setValue(heartOffset);
        gaugeSpinner.getValueFactory().setValue(railGauge);
        startSpeedSpinner.getValueFactory().setValue(coaster.v0);
        fricRollSpinner.getValueFactory().setValue(coaster.fricRoll);
        fricAirSpinner.getValueFactory().setValue(coaster.fricAir);
        docApplying = false;
        syncPlacementSpinners();
        updatePieceButton();
        syncWizardPanel();
        syncNoCoasterPanel();
    }

    /** Rebuild one coaster's piece list from a snapshot (the deep
     *  copies popped off the stack are ADOPTED). */
    private void restorePieces(Coaster coaster, List<PieceState> pieces) {
        coaster.pieces.clear();
        for (PieceState pst : pieces) {
            TrackPiece piece = new TrackPiece(pst.x(), pst.y(), pst.z(), pst.yaw());
            piece.pitchDeg = pst.pitch();
            piece.rollDeg = pst.roll();
            piece.baseOrient = pst.base();
            piece.uiLockPitch = pst.lockPitch();
            piece.uiLockRoll = pst.lockRoll();
            piece.followPrev = pst.followPrev();
            piece.sLockYaw = pst.sLockYaw();
            piece.sLockPitch = pst.sLockPitch();
            piece.sLockRoll = pst.sLockRoll();
            piece.sYawDeg = pst.sYaw();
            piece.sPitchDeg = pst.sPitch();
            piece.sRollDeg = pst.sRoll();
            piece.sSmoothLen = pst.sSmooth();
            piece.canonPsiDeg = pst.canonPsi();
            piece.canonOrient = pst.canonOrient();
            piece.orientMode = pst.orientMode();             // D249
            piece.designPitchDeg = pst.designPitch();
            piece.designRollDeg = pst.designRoll();
            piece.affectSubsequentSeams = pst.affectSeams();   // D249
            for (SecState st : pst.sections()) {
                Section sec = switch (st.mode()) {
                    case 3  -> st.bridge();   // deep copy, adopted
                    case 2  -> new StraightCurveSection(st.elements(), st.vDesign());
                    case 1  -> new GeoSection(st.lanes(), st.vDesign());
                    default -> new ForceSection(st.lanes(), st.vDesign());
                };
                sec.reversed(st.reversed());
                sec.vPinned(st.pinned());
                if (sec instanceof LaneSection ls) {
                    // domain + view + axis flags ride the snapshot
                    ls.domain = st.dist() ? Section.Domain.DISTANCE
                                          : Section.Domain.TIME;
                    ls.viewOtherDomain = st.viewOther();
                    ls.rollGlobal = st.rollG();
                    ls.yawPitchGlobal = st.ypG();
                    ls.ratePerMeter = st.rateM();
                    ls.rollAbsolute = st.rollAbs();
                    ls.ypAbsolute = st.ypAbs();
                    if (st.endFollows() != null) {   // D248
                        System.arraycopy(st.endFollows(), 0, ls.endFollows, 0, 3);
                    }
                    if (st.dlanes() != null) {
                        // influence state rides the snapshot (v15.0, D98)
                        ls.deltaLanes = st.dlanes();
                        ls.vAuthored = st.vAuthored();
                        ls.editInfluence = st.editInfl();
                        ls.inflRollGlobal = st.inflRollG();
                        ls.inflYawPitchGlobal = st.inflYpG();
                    }
                } else if (sec instanceof StraightCurveSection scv) {
                    scv.viewOtherDomain = st.viewOther();
                }
                // speed controls ride the snapshot (v18.0 — adopt the
                // deep copies, the applyDocState pattern)
                if (st.sctls() != null && !(sec instanceof BridgeSection)) {
                    sec.speedControls().addAll(st.sctls());
                    sec.speedMarks().addAll(st.smarks());
                }
                piece.sections.add(sec);
            }
            coaster.pieces.add(piece);
        }
    }

    // -----------------------------------------------------------------
    // MULTI-COASTER document ops (v19.2, D230)
    // -----------------------------------------------------------------

    /** The shell's per-coaster look as a Cosmetics bundle (no camera). */
    DesignIO.Cosmetics shellLook() {
        double[] rail = { railColor.getRed(), railColor.getGreen(), railColor.getBlue() };
        return new DesignIO.Cosmetics(heartOffset, railGauge, tieSpacing,
            railSolid, rail, flatten(gradColors), gradStops.clone(),
            flatten(latColors), latStops.clone(), null);
    }

    /** Stash the shell's look onto the active coaster (before a switch,
     *  a save, a snapshot). */
    void stashActiveLook() {
        if (hasCoaster() && coaster != emptyCoaster) coaster.look = shellLook();
    }

    /** Load a coaster's look into the shell fields + spinners + rail
     *  menu (listeners suppressed). */
    void applyLook(DesignIO.Cosmetics cos) {
        boolean was = docApplying;
        docApplying = true;
        heartOffset = cos.heart();
        railGauge = cos.gauge();
        tieSpacing = cos.tiespace();
        railSolid = cos.railSolid();
        railColor = Color.color(cos.railColor()[0], cos.railColor()[1], cos.railColor()[2]);
        unflatten(cos.gradColors(), gradColors);
        unflatten(cos.latColors(), latColors);
        System.arraycopy(cos.gradStops(), 0, gradStops, 0, gradStops.length);
        System.arraycopy(cos.latStops(), 0, latStops, 0, latStops.length);
        if (heartSpinner != null) {
            heartSpinner.getValueFactory().setValue(heartOffset);
            gaugeSpinner.getValueFactory().setValue(railGauge);
            tieSpinner.getValueFactory().setValue(tieSpacing);
            (railSolid ? solidModeItem : gradientModeItem).setSelected(true);
        }
        docApplying = was;
    }

    /** Make coaster idx the active one (look swap + editor reset). */
    void switchCoaster(int idx) {
        if (idx < 0 || idx >= coasters.size()) return;
        if (idx == activeCoaster && coaster == coasters.get(idx)) return;
        stashActiveLook();
        otherCompiles.remove(coaster);   // it may have been edited while active
        activeCoaster = idx;
        coaster = coasters.get(idx);
        applyLook(lookOf(coaster));
        boolean was = docApplying;
        docApplying = true;
        startSpeedSpinner.getValueFactory().setValue(coaster.v0);
        fricRollSpinner.getValueFactory().setValue(coaster.fricRoll);
        fricAirSpinner.getValueFactory().setValue(coaster.fricAir);
        docApplying = was;
        editor.resetSections();
        editor.clearSelection();
        clearResultingPicks();
        rebuildTrack();
        editor.refresh();
        updatePieceButton();
        syncPlacementSpinners();
        syncWizardPanel();
        syncNoCoasterPanel();
        updateTitle();
    }

    /** ADD a coaster (one undo step, caller pushes) and activate it. */
    void addCoaster(Coaster c) {
        stashActiveLook();
        if (c.look == null) c.look = DesignIO.Cosmetics.defaults();
        coasters.add(c);
        markDirty();
        switchCoasterForce(coasters.size() - 1);
    }

    /** switchCoaster that also handles the empty→first transition. */
    private void switchCoasterForce(int idx) {
        activeCoaster = -1;   // defeat the same-index short-circuit
        switchCoaster(idx);
    }

    /** DELETE the active coaster (undoable — the caller pushes). The
     *  last one leaves an EMPTY document (legal since D230). */
    void deleteActiveCoaster() {
        if (!hasCoaster()) return;
        int idx = activeCoaster;
        otherCompiles.remove(coasters.get(idx));
        coasters.remove(idx);
        markDirty();
        if (coasters.isEmpty()) {
            coaster = emptyCoaster;
            activeCoaster = 0;
            editor.hideStartPanel();
            wizardState = null;
            rebuildTrack();
            editor.refresh();
            updatePieceButton();
            syncNoCoasterPanel();
            updateTitle();
            return;
        }
        switchCoasterForce(Math.min(idx, coasters.size() - 1));
    }

    void renameActiveCoaster(String name) {
        if (!hasCoaster() || name == null || name.isBlank()) return;
        editor.pushUndoSnapshot();
        coaster.name = name.trim();
        markDirty();
        updateTitle();
    }

    /** A fresh coaster name unique in the document. */
    String freshCoasterName() {
        for (int k = coasters.size() + 1; ; k++) {
            String nm = "Coaster " + k;
            boolean taken = false;
            for (Coaster c : coasters) if (c.name.equals(nm)) taken = true;
            if (!taken) return nm;
        }
    }

    /** The other coasters' compiled results for rendering/export —
     *  compiled on demand and cached until the coaster is next active
     *  (an inactive coaster cannot change). */
    private final java.util.Map<Coaster, TrackCompiler.Result> otherCompiles =
        new java.util.HashMap<>();

    TrackCompiler.Result compiledFor(Coaster c) {
        if (c == coaster) return null;   // the active one lives in the shell
        TrackCompiler.Result r = otherCompiles.get(c);
        if (r == null && !c.pieces.isEmpty()) {
            r = TrackCompiler.compile(c, INTEGRATE_DT);
            otherCompiles.put(c, r);
        }
        return r;
    }

    /** SWITCH COASTER for the ACTIVE piece (v19.8, D237 — user
     *  spec): move it under another coaster with the SAME friction
     *  coefficients — the compile reads only pieces + friction, so
     *  the piece stays BITWISE identical; only the parent's
     *  cosmetics (rail color, heartline, gauge) change. Positional
     *  links break on a list move, so they are detached first
     *  KEEPING EVERYTHING PUT: the piece's own strict follow bakes
     *  its effective placement, and so does a follower glued to its
     *  exit (detachStrictSeam — the piece never moves). Bridge
     *  connections refuse in the picker instead. The view follows
     *  the piece into its new coaster; one undo step. */
    void switchPieceCoaster(int destIdx) {
        if (!hasCoaster() || destIdx < 0 || destIdx >= coasters.size()) {
            return;
        }
        Coaster dest = coasters.get(destIdx);
        if (dest == coaster || coaster.pieces.isEmpty()) return;
        int ai = Math.min(editor.activePiece(), coaster.pieces.size() - 1);
        TrackPiece p = coaster.pieces.get(ai);
        editor.hideJunctionWindows();   // piece indices shift (D247 review fix; + the bridge window, D248)
        editor.pushUndoSnapshot();
        Coaster src = coaster;
        if (p.followPrev) src.detachStrictSeam(ai, INTEGRATE_DT);
        if (ai + 1 < src.pieces.size()
                && src.pieces.get(ai + 1).followPrev) {
            src.detachStrictSeam(ai + 1, INTEGRATE_DT);
        }
        src.pieces.remove(ai);
        dest.pieces.add(p);
        // v0 mirrors each ride entry (the D235 re-anchor precedent)
        if (dest.pieces.size() == 1 && !p.sections.isEmpty()) {
            dest.v0 = p.sections.get(0).vDesign();
        }
        if (ai == 0 && !src.pieces.isEmpty()
                && !src.pieces.get(0).sections.isEmpty()) {
            src.v0 = src.pieces.get(0).sections.get(0).vDesign();
        }
        otherCompiles.remove(dest);
        otherCompiles.remove(src);
        markDirty();
        switchCoasterForce(destIdx);   // follow the piece over
        editor.setActivePiece(dest.pieces.size() - 1);
        rebuildTrack();
        editor.refresh();
        updatePieceButton();
        syncPlacementSpinners();
        updateTitle();
    }

    /** Render bundles of every INACTIVE coaster (samples + look) — the
     *  viewport draws them in their solid rail color. */
    List<Object[]> otherCoasterRenders() {
        List<Object[]> out = new ArrayList<>();
        for (Coaster c : coasters) {
            if (c == coaster) continue;
            TrackCompiler.Result r = compiledFor(c);
            if (r == null || r.samples().size() < 2) continue;
            out.add(new Object[]{ r.samples(), lookOf(c), c });
        }
        return out;
    }

    /** Numeric-only editor filter allowing a leading minus (v13.5 —
     *  same rationale as the length spinner's: focused editors still
     *  receive ride keys' KEY_TYPED characters). */
    static javafx.scene.control.TextFormatter<String> signedNumFormatter() {
        return new javafx.scene.control.TextFormatter<>(ch ->
            ch.getControlNewText().matches("-?\\d*\\.?\\d*") ? ch : null);
    }

    /** Digits + one period only (v18.1, D166 — the systematic
     *  numeric-entry rule: no spaces, no letters, ever). */
    static javafx.scene.control.TextFormatter<String> numFormatter() {
        return new javafx.scene.control.TextFormatter<>(ch ->
            ch.getControlNewText().matches("\\d*\\.?\\d*") ? ch : null);
    }

    /** Install the numeric-only rule on a spinner's editor — signed
     *  for values that can go negative. Every numeric spinner in the
     *  app routes through here (v18.1 sweep). Since v18.20 (D191) it
     *  also COMMITS TYPED TEXT ON FOCUS LOSS — JavaFX only commits
     *  on ENTER, so typing then clicking away silently reverted.
     *  Since v18.40 (D212) the D192 blank→zero rule lives INSIDE the
     *  value factory's converter (every commit entry point, incl.
     *  Spinner's OWN internal focus-loss commit — whose empty→NULL
     *  transient used to NPE the appliers and corrupt custom
     *  factories) and scene-level PRESS filters commit the focused
     *  editor even where no focus event ever fires (the non-autohide
     *  popup panels, whose other controls are focus-untraversable —
     *  the user's "clicking off still doesn't set it to zero"). */
    static void numericEditor(Spinner<?> sp, boolean signed) {
        sp.getEditor().setTextFormatter(
            signed ? signedNumFormatter() : numFormatter());
        wrapBlankConverter(sp);
        sp.getProperties().put(NUM_SPINNER_TAG, Boolean.TRUE);
        // the press-commit backstop follows the editor's scene
        // (popup panels get their own scenes)
        installNumericPressCommit(sp.getEditor().getScene());
        sp.getEditor().sceneProperty().addListener(
            (o, was, sc) -> installNumericPressCommit(sc));
        sp.focusedProperty().addListener((o, was, is) -> {
            if (!is && sp.isEditable()) commitTyped(sp);
        });
        // ENTER on a blank editor took Spinner's internal commit,
        // whose converter returns NULL for empty text — the value
        // property went null and the box stayed empty (v18.21, D192)
        sp.getEditor().addEventFilter(
            javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
                if (ev.getCode() == javafx.scene.input.KeyCode.ENTER
                        && sp.isEditable() && isBlankEditor(sp)) {
                    commitTyped(sp);
                    ev.consume();
                }
            });
        installSpinnerSteps(sp);   // D237: wheel + Ctrl/Shift steps
    }

    /** GLOBAL SPINNER STEP MODIFIERS + WHEEL (v19.8, D237 — user
     *  spec, every spinner present and future; installed by
     *  numericEditor, the one route every numeric spinner takes;
     *  modifiers SWAPPED in v19.12, D241 — user spec): the scroll
     *  WHEEL steps any spinner by its increment; SHIFT (wheel or
     *  arrow click) steps at ONE TENTH of the increment; CTRL
     *  SNAPS to the next round multiple of the (possibly
     *  Shift-scaled) increment instead of blindly adding — 0.435
     *  steps up to 0.5, then 0.6. Plain arrow clicks keep the
     *  native skin path (auto-repeat); a consumed MODIFIER press
     *  arms our OWN HoldRepeat at the native cadence since v19.11
     *  (D240, user spec: click-and-hold steadily adjusts on every
     *  spinner — the consumed press never reaches the skin's
     *  Timeline, so we repeat ourselves; modifiers captured at
     *  press). The 1-2-5 snap-step spinners keep their own walker
     *  (the series IS their step); the wheel drives it directly.
     *  Wheel events always CONSUME — spinners sit over the plot and
     *  inside menu popups, and an unconsumed wheel would zoom or
     *  scroll whatever is behind. */
    private static final String STEP_MOD_TAG = "fvd-spinner-stepmods";

    /** Hold-to-repeat at the native SpinnerBehavior cadence
     *  (v19.11, D240 — verified vs javafx-controls 26: immediate
     *  step, 300 ms initial delay, then a step every 60 ms). One
     *  per control; start() ticks once at press. */
    static final class HoldRepeat {
        private static final double INITIAL_MS = 300, REPEAT_MS = 60;
        private javafx.animation.Timeline tl;

        void start(Runnable tick) {
            stop();
            tick.run();
            tl = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                    javafx.util.Duration.ZERO, e -> tick.run()),
                new javafx.animation.KeyFrame(
                    javafx.util.Duration.millis(REPEAT_MS)));
            tl.setDelay(javafx.util.Duration.millis(INITIAL_MS));
            tl.setCycleCount(javafx.animation.Animation.INDEFINITE);
            tl.playFromStart();
        }

        void stop() {
            if (tl != null) {
                tl.stop();
                tl = null;
            }
        }
    }

    static void installSpinnerSteps(Spinner<?> sp) {
        if (sp.getProperties().containsKey(STEP_MOD_TAG)) return;
        sp.getProperties().put(STEP_MOD_TAG, Boolean.TRUE);
        HoldRepeat rep = new HoldRepeat();
        sp.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
            e -> {
                if (!e.isControlDown() && !e.isShiftDown()) return;
                if (!(sp.getValueFactory() instanceof
                        SpinnerValueFactory.DoubleSpinnerValueFactory)) {
                    return;   // the 1-2-5 walkers keep native arrows
                }
                int dir = arrowDirOf(sp, e.getTarget());
                if (dir == 0) return;
                e.consume();
                sp.requestFocus();
                // hold-to-repeat with press-time modifiers (D240);
                // Shift = tenth, Ctrl = snap since D241 (swapped)
                final boolean slow = e.isShiftDown();
                final boolean snap = e.isControlDown();
                rep.start(() -> modStep(sp, dir, slow, snap));
            });
        sp.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED,
            e -> rep.stop());
        sp.disabledProperty().addListener((o, w, dis) -> {
            if (dis) rep.stop();
        });
        sp.sceneProperty().addListener((o, w, sc) -> {
            if (sc == null) rep.stop();
        });
        sp.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (sp.isDisabled()) return;
            // Shift+wheel arrives as HORIZONTAL scroll on Windows —
            // deltaX fallback (the house pattern), or Shift+snap dies
            double wheel = e.getDeltaY() != 0 ? e.getDeltaY()
                                              : e.getDeltaX();
            if (wheel == 0) return;
            e.consume();
            int dir = wheel > 0 ? 1 : -1;
            if (sp.getValueFactory() instanceof
                    SpinnerValueFactory.DoubleSpinnerValueFactory) {
                // Shift = tenth, Ctrl = snap since D241 (swapped)
                modStep(sp, dir, e.isShiftDown(), e.isControlDown());
            } else if (sp.getValueFactory() != null) {
                if (dir > 0) sp.getValueFactory().increment(1);
                else sp.getValueFactory().decrement(1);
            }
        });
    }

    /** +1/-1 when the press target sits inside the spinner's
     *  increment/decrement arrow button; 0 = elsewhere. */
    private static int arrowDirOf(Spinner<?> sp, Object target) {
        if (!(target instanceof javafx.scene.Node n)) return 0;
        for (javafx.scene.Node k = n; k != null && k != sp;
                k = k.getParent()) {
            if (k.getStyleClass().contains("increment-arrow-button")) {
                return 1;
            }
            if (k.getStyleClass().contains("decrement-arrow-button")) {
                return -1;
            }
        }
        return 0;
    }

    /** One modified step: slow = tenth-increments (Shift since
     *  D241), snap = the next round multiple (Ctrl since D241 —
     *  the pair swapped on user spec). Commits typed-but-uncommitted
     *  text first (the D191 discipline), kills float dust at 0.1%
     *  of the quantum (the LiveNum recipe — never fixed decimals,
     *  the 1e-5-step air-friction spinner needs the scale), clamps
     *  explicitly (programmatic setValue does NOT clamp), and writes
     *  through the factory so every existing value listener — undo
     *  wiring included — fires unchanged. */
    @SuppressWarnings("unchecked")
    private static void modStep(Spinner<?> spr, int dir,
                                boolean slow, boolean snap) {
        commitTyped(spr);
        Spinner<Double> sp = (Spinner<Double>) spr;
        SpinnerValueFactory.DoubleSpinnerValueFactory f =
            (SpinnerValueFactory.DoubleSpinnerValueFactory)
                sp.getValueFactory();
        double q = f.getAmountToStepBy();
        if (slow) q /= 10;
        Double cur = sp.getValue();
        double v = cur == null ? 0 : cur;
        double nv;
        if (snap) {
            nv = dir > 0 ? (Math.floor(v / q + 1e-9) + 1) * q
                         : (Math.ceil(v / q - 1e-9) - 1) * q;
        } else {
            nv = v + dir * q;
        }
        nv = Math.round(nv / (q * 0.001)) * (q * 0.001);
        nv = clamp(nv, f.getMin(), f.getMax());
        f.setValue(nv);
    }

    private static final String NUM_SPINNER_TAG = "fvd-numeric-spinner";
    private static final java.util.Set<javafx.scene.Scene> numCommitScenes =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** The D192 blank rule at EVERY commit entry point (v18.40,
     *  D212): blank reads as clamp-to-range(0) for double factories
     *  and as the CURRENT value for custom step-walkers (whose
     *  restore branch used to render "null"); a base converter's own
     *  null result restores too. Kills the null transient at the
     *  source — Spinner's internal commits included. */
    @SuppressWarnings("unchecked")
    static void wrapBlankConverter(Spinner<?> spr) {
        Spinner<Object> sp = (Spinner<Object>) spr;
        SpinnerValueFactory<Object> f = sp.getValueFactory();
        if (f == null || f.getConverter() == null
                || f.getConverter() instanceof BlankSafeConverter) {
            return;
        }
        f.setConverter(new BlankSafeConverter(f, f.getConverter()));
    }

    /** The converter wrapper behind wrapBlankConverter — a NAMED
     *  class so re-wrapping is idempotent and a caller that installs
     *  a custom converter (the friction spinners' fixed-decimal one)
     *  can be re-wrapped afterwards. */
    private static final class BlankSafeConverter
            extends javafx.util.StringConverter<Object> {
        private final SpinnerValueFactory<?> f;
        private final javafx.util.StringConverter<Object> base;
        BlankSafeConverter(SpinnerValueFactory<?> f,
                           javafx.util.StringConverter<Object> base) {
            this.f = f;
            this.base = base;
        }
        @Override public String toString(Object v) {
            return v == null ? "" : base.toString(v);
        }
        @Override public Object fromString(String s) {
            if (s == null || s.isBlank()) {
                if (f instanceof
                        SpinnerValueFactory.DoubleSpinnerValueFactory df) {
                    return Math.min(df.getMax(),
                        Math.max(df.getMin(), 0.0));
                }
                return f.getValue();
            }
            Object v = base.fromString(s);
            return v == null ? f.getValue() : v;
        }
    }

    /** Scene-level MOUSE_PRESSED backstop (v18.40, D212): commit the
     *  focused numeric editor when a press lands outside its spinner
     *  — in this scene AND in every other showing window (a popup
     *  panel's editor vs a click in the main window: JavaFX focus is
     *  per-Scene and the popup's focused flag rides the owner stage,
     *  so no focus event ever fires there). */
    static void installNumericPressCommit(javafx.scene.Scene sc) {
        if (sc == null || !numCommitScenes.add(sc)) return;
        sc.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
            e -> commitStrayNumericEditors(sc,
                e.getTarget() instanceof javafx.scene.Node n ? n : null));
    }

    /** Commit every tagged spinner whose editor holds its scene's
     *  focus but is not under the pressed node. Null scene/target =
     *  window deactivation sweep (commit everything focused). */
    static void commitStrayNumericEditors(javafx.scene.Scene pressed,
                                          javafx.scene.Node target) {
        for (javafx.stage.Window w :
                java.util.List.copyOf(javafx.stage.Window.getWindows())) {
            javafx.scene.Scene sc = w.getScene();
            if (sc == null || !w.isShowing()) continue;
            Spinner<?> sp = taggedSpinnerOf(sc.getFocusOwner());
            if (sp == null || !sp.isEditable()) continue;
            if (sc == pressed && target != null
                    && isInsideNode(target, sp)) {
                continue;   // press on the spinner itself: no commit
            }
            commitTyped(sp);
        }
    }

    private static Spinner<?> taggedSpinnerOf(javafx.scene.Node n) {
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
            if (p instanceof Spinner<?> sp
                    && sp.getProperties().containsKey(NUM_SPINNER_TAG)) {
                return sp;
            }
        }
        return null;
    }

    private static boolean isInsideNode(javafx.scene.Node n,
                                        javafx.scene.Node anc) {
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
            if (p == anc) return true;
        }
        return false;
    }

    private static boolean isBlankEditor(Spinner<?> sp) {
        String t = sp.getEditor().getText();
        return t == null || t.isBlank();
    }

    /** Commit the typed editor text (v18.20 D191; blank rule v18.21
     *  D192 — user spec): a CLEARED box commits ZERO, clamped into
     *  the factory's range; unparsable text falls back to the
     *  current value. The explicit setText matters — when the value
     *  doesn't change, no listener re-renders the empty editor.
     *  UNTOUCHED TEXT IS A NO-OP since v19.18 (D247, standing rule):
     *  when the editor still shows the converter's own rendering of
     *  the current value (the "#.##" text of a full-precision angle
     *  or position), nothing was typed and nothing commits — the old
     *  re-parse wrote the ROUNDED value back, and every value
     *  listener (wireDocSpinner, the seam pop-out, the speed panel)
     *  took that as a user edit: a phantom undo step + a sub-0.005°
     *  nudge on every click-away / window hide / undo teardown.
     *  Typed text keeps the D191/D212 focus-loss commit exactly. */
    static void commitTyped(Spinner<?> sp) {
        if (isBlankEditor(sp)) {
            if (sp.getValueFactory() instanceof
                    SpinnerValueFactory.DoubleSpinnerValueFactory f) {
                double z = Math.min(f.getMax(), Math.max(f.getMin(), 0));
                f.setValue(z);
                sp.getEditor().setText(f.getConverter() != null
                    ? f.getConverter().toString(z) : String.valueOf(z));
            } else {
                // custom factories (the snap-step walker): keep the
                // value, just restore the text
                sp.getEditor().setText(String.valueOf(sp.getValue()));
            }
            return;
        }
        if (isUntouchedEditor(sp)) return;   // D247
        try {
            sp.commitValue();
        } catch (RuntimeException ex) {
            sp.getEditor().setText(String.valueOf(sp.getValue()));
        }
    }

    /** True when the editor text equals the converter's rendering of
     *  the spinner's CURRENT value — i.e. nobody typed (D247). The
     *  BlankSafeConverter wrapper renders null as "", so a null
     *  value never matches non-blank text. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static boolean isUntouchedEditor(Spinner<?> sp) {
        SpinnerValueFactory<?> f = sp.getValueFactory();
        Object cur = sp.getValue();
        if (f == null || cur == null) return false;
        javafx.util.StringConverter conv = f.getConverter();
        if (conv == null) return false;
        String shown;
        try {
            shown = conv.toString(cur);
        } catch (RuntimeException ex) {
            return false;
        }
        String txt = sp.getEditor().getText();
        return shown != null && txt != null && txt.equals(shown);
    }

    /** Commit the tagged numeric spinner holding a scene's focus, if
     *  any (v19.18, D247): the explicit commit a tool window's Apply
     *  runs BEFORE its null-before-hide teardown, so a typed-but-
     *  unentered box still applies (a mouse press already committed
     *  via installNumericPressCommit; keyboard Enter reaches the
     *  window through LiveWindow's KEY_PRESSED filter — a Spinner
     *  editor consumes a bubbling Enter, D248 review fix — and the X
     *  needs this too). Untouched text is a no-op per commitTyped. */
    static void commitFocusedNumericEditor(javafx.scene.Scene sc) {
        if (sc == null) return;
        Spinner<?> sp = taggedSpinnerOf(sc.getFocusOwner());
        if (sp != null && sp.isEditable()) commitTyped(sp);
    }

    /** DISCARD the typed-but-unentered text of the tagged numeric
     *  spinner holding a scene's focus (D248 review fix — the Cancel
     *  path's twin of commitFocusedNumericEditor): the editor text is
     *  reset to the factory converter's rendering of the spinner's
     *  CURRENT value, so the hide-time focus-loss commit that follows
     *  a window close is commitTyped's untouched-text no-op instead
     *  of applying the typed value AFTER the caller's model revert.
     *  (TextInputControl.cancelEdit is not used: the numFormatter is
     *  filter-only, without a value converter.) */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    static void discardFocusedNumericEditor(javafx.scene.Scene sc) {
        if (sc == null) return;
        Spinner<?> sp = taggedSpinnerOf(sc.getFocusOwner());
        if (sp == null || !sp.isEditable()) return;
        SpinnerValueFactory<?> f = sp.getValueFactory();
        Object cur = sp.getValue();
        if (f == null || cur == null) return;
        javafx.util.StringConverter conv = f.getConverter();
        String shown;
        try {
            shown = conv != null ? conv.toString(cur) : String.valueOf(cur);
        } catch (RuntimeException ex) {
            shown = String.valueOf(cur);
        }
        if (shown != null && !shown.equals(sp.getEditor().getText())) {
            sp.getEditor().setText(shown);
        }
    }

    /** TYPE-ANYWHERE spinner (v18.20, D191 — user spec: every
     *  numeric box accepts typed input, not just its arrows). */
    static void typable(Spinner<?> sp, boolean signed) {
        sp.setEditable(true);
        numericEditor(sp, signed);
    }

    /** One axis of the bridge target's manual x/y/z (mode: none). */
    private Spinner<Double> bridgePosSpinner(int axis) {
        Spinner<Double> sp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                -100000, 100000, 0, 1));
        sp.setPrefWidth(86);
        typable(sp, true);
        sp.valueProperty().addListener((obs, o, v) -> {
            if (docApplying || v == null) return;
            if (convertBusy()) { revertSpinner(sp, o); return; }   // D247
            bridgeCall(() -> editor.setBridgeTargetPos(axis, v));
        });
        return sp;
    }

    /** Point the placement spinners at the ACTIVE piece's values
     *  (listener-safe: docApplying suppresses the undo push). */
    void syncPlacementSpinners() {
        if (editor == null || startXSpinner == null) return;
        if (!hasCoaster() || coaster.pieces.isEmpty()) return;   // D230
        TrackPiece p = coaster.pieces.get(
            Math.min(editor.activePiece(), coaster.pieces.size() - 1));
        boolean was = docApplying;
        docApplying = true;
        // the x/y/z rows read the PLACEMENT TARGET (D247): the run
        // head while a follower is active, else the active piece
        TrackPiece anchor = placementTargetRef();
        // FOCUS-GUARDED writes (D247): this runs per gizmo drag event
        // and per model hook — a box being typed into is left alone
        setUnlessTyping(startXSpinner, anchor.x);
        setUnlessTyping(startYSpinner, anchor.y);
        setUnlessTyping(startZSpinner, anchor.z);
        // ABSOLUTE display (D224): the boxes show the entry's real
        // heading/elevation/bank — a split piece's included — reported
        // yaw negated (D186)
        setUnlessTyping(yawSpinner, -p.dispYaw() + 0.0);   // no "-0"
        // a piece PLACED through a bridge grays whatever the bridge
        // owns (v16.13, user spec — the rows silently fought the
        // auto re-optimize before): position under any optimize
        // mode; yaw when locked or march-aligned. The grayHint
        // wrappers say to edit through the bridge.
        BridgeSection binto = bridgeInto(editor.activePiece());
        // a STRICT-SEAM follower (v17.1, D135): the seam owns its
        // position and every orientation row — locked axes hold the
        // stored angle (edit via its "Strict Seam n ▴" window), unlocked ones align
        // to the seam each compile. Since D247 the x/y/z rows then
        // place the RUN HEAD instead of graying: position is owned
        // only by an optimize-mode bridge into the TARGET piece (a
        // run head has none — runStart walks back over bridges)
        boolean follows = editor.followsPrev(editor.activePiece());
        BridgeSection bAnchor = bridgeInto(placementTargetIdx());
        boolean posOwned = bAnchor != null
            && bAnchor.optMode != BridgeSection.OPT_NONE;
        startXSpinner.setDisable(posOwned);
        startYSpinner.setDisable(posOwned);
        startZSpinner.setDisable(posOwned);
        // the label's hover explains the run-head rule while the rows
        // place a piece other than the active one (D248: always the
        // run head) — four-way per the head's orientation mode +
        // carry flag (D247 review fix + D248 + D249): rigid carry /
        // lockstep re-solve / the edit stops at the next junction /
        // design-only (the carry is inert there)
        if (anchorLbl != null) {
            boolean headElsewhere = placementTargetIdx() != Math.min(
                editor.activePiece(), coaster.pieces.size() - 1);
            Tooltip want = !headElsewhere ? null
                : anchor.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? anchorRunTipDesign
                : !anchor.shapeLocked() ? anchorRunTipUnlocked
                : !anchor.affectSubsequentSeams ? anchorRunTipOnly
                : anchorRunTip;
            for (Tooltip t : new Tooltip[]{ anchorRunTip,
                    anchorRunTipUnlocked, anchorRunTipOnly,
                    anchorRunTipDesign }) {
                if (want != t) Tooltip.uninstall(anchorLbl, t);
            }
            if (want != null) Tooltip.install(anchorLbl, want);
        }
        // a bridged-in piece's yaw: the bridge's ENABLE rule (D249 —
        // BridgeSection.axisEditable: held = typeable, free = the
        // march's under optimize-orientation only)
        yawSpinner.setDisable(follows
            || (binto != null && !binto.axisEditable(0)));
        if (piecePitchSpinner != null) {
            // pitch/roll rows (v15.3): values off the piece; lock
            // state off the bridge connecting into it when one exists
            // (shared padlocks), else the piece's own UI guard. THE
            // ENABLE RULE (v19.20, D249, the seam rule): a bridged-in
            // piece's HELD (🔒) axis is typeable in every mode, its
            // free axis only outside optimize-orientation (there the
            // march aligns it); a free piece's 🔒 is the edit guard
            // that freezes the box.
            // the box shows the tier the mode EDITS (D249): the design
            // elevation/bank under DESIGN_ONLY, the real ones otherwise
            // (the grey readout right of the ↺ shows the other tier)
            boolean dOnly = p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            setUnlessTyping(piecePitchSpinner,
                dOnly ? p.dispDesignPitch() : p.dispPitch());
            setUnlessTyping(pieceRollSpinner,
                dOnly ? p.dispDesignRoll() : p.dispRoll());
            BridgeSection bs = bridgeInto(editor.activePiece());
            boolean lp = bs != null ? bs.lockPitch : p.uiLockPitch;
            boolean lr = bs != null ? bs.lockRoll : p.uiLockRoll;
            pieceLockPitchBtn.setSelected(!lp);
            pieceLockRollBtn.setSelected(!lr);
            piecePitchSpinner.setDisable(follows
                || (bs != null ? !bs.axisEditable(1) : lp));
            pieceRollSpinner.setDisable(follows
                || (bs != null ? !bs.axisEditable(2) : lr));
            // the padlocks gray on a follower only (D247 review fix —
            // the seam owns the axes, the entry rig never builds);
            // over a march-owned piece they are LIVE again since D249:
            // a flip re-marches through setBridgeLock like the bridge
            // window's own padlock
            pieceLockPitchBtn.setDisable(follows);
            pieceLockRollBtn.setDisable(follows);
            pieceLockTip.setText(bs != null ? BRIDGE_LOCK_TIP : FREE_LOCK_TIP);
        }
        docApplying = was;
        syncDesignReadout();   // D222: design-orientation line
    }

    boolean isDirty() { return dirty; }

    /** Drop the dirty mark again (a refused op's rollback restores the
     *  pre-op document through applySnapshot, which marks dirty like
     *  every restore — D247 review fix). */
    void clearDirty() {
        if (dirty) {
            dirty = false;
            updateTitle();
        }
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
        if (stage != null) {
            // the ACTIVE coaster's name joins the title for multi-
            // coaster documents (D230)
            String c = coasters.size() > 1 && hasCoaster()
                ? " [" + coaster.name + "]" : "";
            stage.setTitle(docName() + (dirty ? "*" : "") + c
                + " — " + APP_NAME);
        }
    }

    // ---------- the 3D viewport (Viewport3D.java, D54) ----------
    Dialogs dialogs;   // Help/Shortcuts/Rail-colors/Options (Dialogs.java, D54)

    /** The piece the editor is pointed at (placement spinners write
     *  here; falls back to piece 0 before the editor exists). */
    TrackPiece activePieceRef() {
        int p = editor != null ? editor.activePiece() : 0;
        if (coaster.pieces.isEmpty()) return new TrackPiece(0, 0, 0, 0);   // D230 no-coaster
        return coaster.pieces.get(Math.min(p, coaster.pieces.size() - 1));
    }

    /** The piece the Track Piece window's x/y/z rows PLACE (v19.18,
     *  D247 — user spec: the run is "the track piece", its head's
     *  entry "the anchor"): ALWAYS the RUN HEAD since v19.19 (D248 —
     *  GraphEditor.runStartPiece, back over follow links AND
     *  trailing bridges; D247 generalized only for followers, a
     *  bridged-in active piece still edited itself, whose position
     *  the bridge owns anyway — its own x/y/z live in the bridge
     *  window's "Next piece x/y/z" row). The orientation rows keep
     *  editing the ACTIVE piece (grayed for a follower — the seam
     *  window owns it). */
    int placementTargetIdx() {
        if (coaster.pieces.isEmpty()) return 0;
        int a = Math.min(editor != null ? editor.activePiece() : 0,
            coaster.pieces.size() - 1);
        return editor != null ? editor.runStartPiece() : a;
    }

    /** The placementTargetIdx() piece (a throwaway before any
     *  coaster exists, like activePieceRef). */
    TrackPiece placementTargetRef() {
        if (coaster.pieces.isEmpty()) return new TrackPiece(0, 0, 0, 0);
        return coaster.pieces.get(placementTargetIdx());
    }

    // ---------- orientation link + rigid carry (v19.0, D221) ----------

    /** Run a USER placement write on piece `pieceIdx`; when the piece
     *  edits its REAL orientation only (orientMode REAL_ONLY) AND its
     *  "Affect Subsequent Strict Seams" flag is ON
     *  (Coaster.carriesDownstream — the D248 predicate, mode-keyed
     *  D249), carry the connected downstream chain by the same rigid
     *  delta (Coaster.carryDownstream) so the whole assembly keeps its
     *  shape and adopts the one design/real conflict. Everything else
     *  skips the carry — a LOCKSTEP shape re-solves (D222), DESIGN_ONLY
     *  never moves the real entry, and the flag OFF (the default)
     *  stops the edit at the piece's own exit junction: bridges
     *  re-solve/re-march, followers keep their held angles and re-aim
     *  their free axes (the historic pre-D221 behavior). Callers still
     *  push undo/rebuild; the carry rides their undo step. */
    void placementEdit(int pieceIdx, Runnable write) {
        try {
            if (pieceIdx < 0 || pieceIdx >= coaster.pieces.size()) {
                write.run();
                return;
            }
            TrackPiece p = coaster.pieces.get(pieceIdx);
            if (!Coaster.carriesDownstream(p)) {
                write.run();
                return;
            }
            TrackPose before = effEntryPose(pieceIdx);
            write.run();
            TrackPose after = effEntryPose(pieceIdx);
            coaster.carryAfterEdit(pieceIdx, before, after, sectionSpans);
        } finally {
            // gizmo drags update the wizard's Placement rows LIVE
            // (v19.7, D236 — syncFromModel is muted, no feedback)
            if (wizardPanel != null) wizardPanel.syncFromModel();
            // ... and the Track Piece window's rows too (v19.8, D237 —
            // tool windows stay visible during capture now and must
            // read live; docApplying suppresses the listeners)
            syncPlacementSpinners();
        }
    }

    /** Overload resolving the piece's index by identity (viewport
     *  sinks hold the TrackPiece object). Unfindable = just write. */
    void placementEdit(TrackPiece p, Runnable write) {
        placementEdit(coaster.pieces.indexOf(p), write);
    }

    /** The Track Piece window's ORIENTATION write on the active piece
     *  (v19.20, D249 — its yaw/pitch/roll boxes and ↺): a bridged-in
     *  piece takes the connecting bridge's ONE write tail
     *  (GraphEditor.bridgeTargetWrite — placementEdit + the per-mode
     *  re-place, so a held value typed here is met by the march
     *  exactly as one typed in the bridge window: the two windows are
     *  one route); a free piece = placementEdit alone (the caller's
     *  rebuild follows; over a bridged-in piece that rebuild is the
     *  fixed point's no-op). */
    private void pieceOrientWrite(Runnable write) {
        if (!hasCoaster() || coaster.pieces.isEmpty() || editor == null) {
            write.run();
            return;
        }
        int a = Math.min(editor.activePiece(), coaster.pieces.size() - 1);
        if (bridgeInto(a) != null) editor.bridgeTargetWrite(a - 1, write);
        else placementEdit(a, write);
    }

    /** The piece's EFFECTIVE entry pose for the carry: a strict
     *  follower's orientation reads through the follow contract
     *  anchored at the seam (its held-angle edits rotate it there);
     *  everything else is the stored placement. Null when a follower
     *  has no compiled seam yet (no carry — nothing to anchor). */
    private TrackPose effEntryPose(int pieceIdx) {
        TrackPiece p = coaster.pieces.get(pieceIdx);
        if (p.followPrev && pieceIdx > 0) {
            TrackPiece prev = coaster.pieces.get(pieceIdx - 1);
            boolean bridged = !prev.sections.isEmpty()
                && prev.sections.get(prev.sections.size() - 1)
                    instanceof BridgeSection;
            if (!bridged && !prev.sections.isEmpty()) {
                TrackPose ex = pieceExitPose(pieceIdx - 1);
                return ex == null ? null : Coaster.strictEntry(ex, p);
            }
        }
        return p.entryPose();
    }

    /** The classic Design → Real write (design := true; the shape
     *  re-solves at the real frame). One undo step. Shared by the
     *  no-absolute-lanes button path and the D240 dialog's
     *  all-Preserve pick. */
    void designToRealDirect(int pieceIdx) {
        designToRealDirect(pieceIdx, null);
    }

    /** designToRealDirect with an `afterPush` hook that runs RIGHT
     *  AFTER the undo snapshot and before the tier write (D248 review
     *  fix): the bridge window's SIG_STALE stamp used to land BEFORE
     *  the push, so the snapshot's bridge copy carried the stale
     *  signature (BridgeSection.copy keeps optSig) and Ctrl+Z restored
     *  a bridge that the next rebuild's auto pass re-marched — an undo
     *  that moved the target. A refused follower branch runs neither
     *  (no stamp without a push). */
    void designToRealDirect(int pieceIdx, Runnable afterPush) {
        if (pieceIdx >= coaster.pieces.size()) return;
        TrackPiece p = coaster.pieces.get(pieceIdx);
        if (coaster.followerActive(pieceIdx)) {
            // a strict-seam FOLLOWER (D247 review fix — the D4 fork
            // designToRealConvert already takes): its true tier is
            // DEAD (the real orientation is the follow contract), so
            // copying it would neither adopt the seam orientation nor
            // close the ≠o — the seam's Design → Real IS bakeFollow
            TrackCompiler.Result r = TrackCompiler.compile(coaster,
                INTEGRATE_DT);
            if (coaster.followContract(pieceIdx, r) == null) {
                statsLabel.setText("Design → Real refused — the seam"
                    + " has no compiled exit to read yet.");
                return;
            }
            editor.pushUndoSnapshot();
            if (afterPush != null) afterPush.run();
            coaster.bakeFollow(pieceIdx, r);
        } else {
            editor.pushUndoSnapshot();
            if (afterPush != null) afterPush.run();
            p.designPitchDeg = p.pitchDeg;
            p.designRollDeg = p.rollDeg;
        }
        markDirty();
        rebuildTrack();
        syncPlacementSpinners();
        updateBridgeControls();
        editor.redrawFromViewport();
        syncDesignReadout();
        if (editor.seamSync != null) editor.seamSync.run();
    }

    /** D222/D248: refresh every open live window's derived rows — the
     *  Track Piece window's per-row design readouts, resolve row and
     *  flag row, and the bridge window's (the D222 designTiltLbl +
     *  lockRowsSync pair is gone). Called wherever it always was:
     *  after syncPlacementSpinners, the resolve ops, Mirror, Copy,
     *  restores. */
    void syncDesignReadout() {
        if (trackPieceRowsSync != null) trackPieceRowsSync.run();
        if (bridgeRowsSync != null) bridgeRowsSync.run();
    }

    // ---------- THE LIVE WINDOWS (v19.19, D248) ----------
    // The Track Piece panel, the bridge window ("Bridge settings"
    // since D249) and the
    // strict seam pop-out (GraphEditor.showStrictSeamSettings) are ONE
    // kind of window now — LiveWindow Stages placed above the graph:
    // live values, index-resolved handlers, modelHooks resync,
    // Confirm / Cancel with an open-time record, null-before-hide
    // teardown. Toolbar: "Track Piece ▴" (always — the RUN HEAD's
    // anchor) + exactly one junction button, "Strict Seam n ▴" or
    // "Strict Seam n (bridge) ▴" (syncJunctionButton).

    /** The open Track Piece window (null = closed) + its hooks. */
    private Stage trackPieceWin;
    private Runnable trackPieceHook, trackPieceRestoreHook;
    /** The Track Piece window's derived-row sync (readouts, resolve
     *  row, flag row, the ↺ record re-capture); null while closed. */
    private Runnable trackPieceRowsSync;
    /** The Track Piece window's OPEN-TIME record: Cancel and the ↺
     *  buttons revert to it. RE-CAPTURED whenever the ACTIVE piece
     *  changes under the open window (the window follows the active
     *  piece, today's panel rule) — Cancel then reverts the current
     *  piece to the state it had when it became the window's. */
    private static final class TpRecord {
        /** The coaster the record was taken on (D248 review fix: a
         *  coaster switch under the open window re-bases the record —
         *  the same index on another coaster is another piece). */
        Coaster c;
        int piece, head;
        double x, y, z, yaw, pitch, roll, dPitch, dRoll;
        Quat base;
        TrackPiece.OrientMode mode;   // D249
        boolean affect, uiP, uiR;
        /** Cancel's FULL-SCOPE record (D249): every piece's placement
         *  + every trailing bridge's fields, restored by identity;
         *  gen = docGen at capture (a mismatch refuses the revert). */
        PlacementSnapshot snap;
        int gen;
    }
    private TpRecord tpRec;
    /** The open bridge window ("Bridge settings (Strict Seam n)"), the piece
     *  whose TRAILING bridge it edits (−1 = none: the shell's bridge
     *  rows then address the active section, the historic scoping —
     *  see GraphEditor.withBridge), its coaster, hooks and row sync. */
    private Stage bridgeWin;
    int bridgeWinPiece = -1;
    private Coaster bridgeWinCoaster;
    private Runnable bridgeHook, bridgeRestoreHook, bridgeRowsSync;
    /** The bridge window's open-time record (the bridge's own fields
     *  + the target piece's placement/tiers/flags). */
    private static final class BrRecord {
        int bp, optMode;
        boolean optLen, lockY, lockP, lockR;
        double lastOptDist, lenA, lenB;
        double[] noneStash;
        double x, y, z, yaw, pitch, roll, dPitch, dRoll;
        Quat base;
        TrackPiece.OrientMode mode;   // D249
        boolean affect;
        PlacementSnapshot snap;   // Cancel's full-scope record (D249)
        int gen;
    }
    private BrRecord brRec;
    /** The junction button's current kind: 0 = hidden, 1 = strict
     *  seam (follower = the active piece), 2 = bridge (trailing piece
     *  junctionPiece). */
    private int junctionKind;
    private int junctionPiece = -1;
    private final Tooltip junctionSeamTip = new Tooltip(
        "Orientation of the strict seam this section follows.");
    private final Tooltip junctionBridgeTip = new Tooltip(
        "The bridge junction: its placement mode, length and the next"
        + " piece's orientation.");
    /** The wizard's lockout of the placement buttons (syncWizardPanel
     *  sets it; syncJunctionButton reads it). */
    private boolean wizardBlocksPlacement;
    private static final String WIZARD_OWNS_MSG =
        "Placement is edited in the start screen while it is open.";
    private static final String ORPHAN_BRIDGE_MSG =
        "This bridge has no following piece.";
    private static final String BRIDGE_GONE_MSG =
        "The bridge window closed — its bridge no longer exists.";
    /** The "Anchor x/y/z (m)" label + the hover it wears while the
     *  rows place a piece OTHER than the active one (the run head —
     *  D247; ALWAYS the run head since D248): four-way per the head's
     *  orientation mode + carry flag (D249). */
    private Label anchorLbl;
    private final Tooltip anchorRunTip = new Tooltip(
        "Moves the run's first piece (the anchor) — every connected"
        + " piece follows rigidly.");
    private final Tooltip anchorRunTipUnlocked = new Tooltip(
        "Moves the run's first piece (the anchor); with the orientation"
        + " edited in lockstep, bridged pieces re-solve instead of"
        + " following.");
    private final Tooltip anchorRunTipOnly = new Tooltip(
        "Moves the run's first piece (the anchor); with Affect Subsequent"
        + " Strict Seams off, the pieces after its next strict seam or"
        + " bridge keep their own placement settings.");
    private final Tooltip anchorRunTipDesign = new Tooltip(
        "Moves the run's first piece (the anchor); while editing the"
        + " design orientation the pieces after its next strict seam or"
        + " bridge keep their own placement settings.");
    // the Track Piece window's per-row parts (built with the spinners)
    private Button yawRstBtn, pitchRstBtn, rollRstBtn;
    private LiveWindow.Readout pitchDesignLbl, rollDesignLbl;
    private HBox yawRow, pitchRow, rollRow;
    private LiveWindow.FlagRow tpFlags;
    private LiveWindow.ResolveRow tpResolve;
    private StackPane tpFlagsWrap;
    // the bridge window's per-row parts
    private Button bridgeYawRst, bridgePitchRst, bridgeRollRst;
    private LiveWindow.Readout bridgePitchDesign, bridgeRollDesign;
    private LiveWindow.FlagRow brFlags;
    private LiveWindow.ResolveRow brResolve;

    boolean docApplying() { return docApplying; }

    boolean docRestoring() { return docRestoring; }

    /** The junction button's screen x (the seam window's fallback
     *  anchor); NaN while hidden. */
    double junctionButtonAnchorX() {
        return strictSeamWrap != null && strictSeamWrap.isVisible()
            ? LiveWindow.anchorOf(strictSeamBtn) : Double.NaN;
    }

    /** Compiled exit pose of a piece's LAST section (the follow
     *  seam), from the current spans. */
    private TrackPose pieceExitPose(int pieceIdx) {
        if (sectionSpans == null) return null;
        int idx = -1;
        for (int q = 0; q <= pieceIdx && q < coaster.pieces.size(); q++) {
            idx += coaster.pieces.get(q).sections.size();
        }
        return idx >= 0 && idx < sectionSpans.size()
            ? sectionSpans.get(idx).exit() : null;
    }

    /** The toolbar "Track Piece ▴" click: TOGGLES the window — open =
     *  close WITHOUT revert (Confirm semantics; the D248 judgment
     *  matching the old second-click hide), else open. */
    void showPlacementPopup() {
        if (trackPieceWin != null) {
            closeTrackPieceWindow();
            return;
        }
        openTrackPieceWindow();
    }

    /** Open a placement window FRESH (D248 — gizmo double-clicks, the
     *  Orientation dialog's owner button): an open one is torn down
     *  first, so the click always OPENS the requested rows at the
     *  new anchor. forceBridge = the ACTIVE piece's trailing bridge
     *  window (the caller activated (p, last) first), else the Track
     *  Piece window. */
    void openPlacementPopup(boolean forceBridge) {
        if (forceBridge) {
            openBridgeWindow(editor != null ? editor.activePiece() : -1);
        } else {
            openTrackPieceWindow();
        }
    }

    /** The junction button's click: toggles ITS window (seam or
     *  bridge) — open = close without revert, else open. */
    private void toggleJunctionWindow() {
        if (!hasCoaster() || editor == null || junctionKind == 0) return;
        if (junctionKind == 1) {
            editor.toggleSeamWindow(editor.activePiece());
        } else if (bridgeWin != null && bridgeWinPiece == junctionPiece) {
            closeBridgeWindow();
        } else {
            openBridgeWindow(junctionPiece);
        }
    }

    // ---------- the TRACK PIECE window ----------

    private void captureTpRecord() {
        TpRecord r = new TpRecord();
        r.c = coaster;
        r.piece = Math.min(editor.activePiece(), coaster.pieces.size() - 1);
        r.head = placementTargetIdx();
        TrackPiece h = coaster.pieces.get(r.head);
        TrackPiece p = coaster.pieces.get(r.piece);
        r.x = h.x; r.y = h.y; r.z = h.z;
        r.yaw = p.yawDeg; r.pitch = p.pitchDeg; r.roll = p.rollDeg;
        r.dPitch = p.designPitchDeg; r.dRoll = p.designRollDeg;
        r.base = p.baseOrient;
        r.mode = p.orientMode; r.affect = p.affectSubsequentSeams;
        r.uiP = p.uiLockPitch; r.uiR = p.uiLockRoll;
        r.snap = PlacementSnapshot.capture(coaster);   // D249
        r.gen = docGen;
        tpRec = r;
    }

    /** The stats reason when a window's Cancel finds the document
     *  replaced underneath it (an undo the restore hooks did not
     *  re-capture, a load, a coaster switch) — D249. */
    static final String CANCEL_STALE_MSG = "Cancel skipped — the document"
        + " changed underneath this window; use Ctrl+Z to go back.";

    /** CANCEL / ESC of the three live windows (v19.20, D249 — user
     *  spec, superseding D248's own-object records): restore EVERY
     *  piece's open-time placement from the window's PlacementSnapshot
     *  (by object identity, DIRECT field writes — no placementEdit,
     *  no carry: the carry is not an exact inverse and the routing
     *  flags may have changed mid-window; pieces that vanished are
     *  skipped, pieces that appeared are untouched, graph edits made
     *  meanwhile stay; a mid-window Design → Real bake reverts too —
     *  baseOrient + the canonical pair are recorded). Returns 1 =
     *  restored (one undo step pushed, dirty), 0 = nothing differed,
     *  −1 = refused (another coaster / a document generation the
     *  hooks did not re-capture; the stats bar says so). The caller
     *  rebuilds and resyncs. */
    int cancelRestore(PlacementSnapshot snap, int gen) {
        if (snap == null) return 0;
        if (snap.coaster != coaster || gen != docGen) {
            statsLabel.setText(CANCEL_STALE_MSG);
            return -1;
        }
        if (!snap.differs()) return 0;
        editor.pushUndoSnapshot();
        snap.restore();
        markDirty();
        return 1;
    }

    /** The Track Piece window (D248 — the movable dark Popup of v17.8/
     *  D145 rebuilt as a LiveWindow): rows [Anchor x/y/z][Anchor yaw
     *  + ↺][separator][Anchor pitch + 🔒 + ↺ + readout][Anchor roll +
     *  🔒 + ↺ + readout][Edit Real and Design Orientation Independently
     *  → Edit real orientation (locks track shape) / Edit design
     *  orientation → Affect Subsequent Strict Seams (D249)]
     *  [Design → Real / Real → Design while conflicted][Reverse… /
     *  Mirror / Copy / Switch Coaster…][Confirm / Cancel]. x/y/z place
     *  the RUN HEAD, the orientation rows the ACTIVE piece (grayed
     *  with the junction's name for a strict follower — the seam
     *  window owns it; bridge-lock / march graying for a bridged-in
     *  piece); the pitch/roll boxes edit the tier the mode names and
     *  the grey readout shows the other one (click = swap, D249). The
     *  window FOLLOWS the active piece (the spinners are the shell's,
     *  resynced per hook); Cancel restores EVERY piece's open-time
     *  placement (cancelRestore — re-based on a piece switch, an undo
     *  restore, a document generation the hooks did not see, and the
     *  ops row's Mirror, which runs the restore hooks itself: Cancel
     *  never un-mirrors, Ctrl+Z is the path — D249 review fixes).
     *  Wizard refusal + convertBusy gating kept. */
    void openTrackPieceWindow() {
        if (!hasCoaster() || coaster.pieces.isEmpty()) {
            LiveWindow.dropAnchor(this);   // D248 review fix: no stale anchor
            return;
        }
        if (editor.startPanelActive()) {
            // the start screen's Placement rows own the piece while
            // the wizard shows (v19.7, D236 — covers the toolbar
            // button, ball double-clicks and the orientation dialog)
            statsLabel.setText(
                "Placement is edited in the start screen while it is open");
            popupAnchorX = popupAnchorY = null;
            return;
        }
        closeTrackPieceWindow();   // fresh (null-before-hide)
        syncPlacementSpinners();   // lock states follow the piece
        captureTpRecord();
        VBox box = new VBox(6);
        // the unified order (D248 spec): orientation rows, THEN the
        // x/y/z row, then the flag / resolve / ops rows
        box.getChildren().addAll(yawRow, LiveWindow.thinSeparator(),
            pitchRow, rollRow, placementRow, tpFlagsWrap,
            tpResolve.node, buildOpsRow());
        Runnable onConfirm = this::closeTrackPieceWindow;
        Runnable onCancel = () -> {
            if (trackPieceWin == null) return;
            if (convertBusy()) return;   // the F3 transaction guard
            TpRecord r = tpRec;
            // the FULL-SCOPE revert (D249): every piece back to the
            // state this open (or the last piece switch / undo
            // re-capture) found — one more undo step; the restored
            // bridge signatures match the open-time seams, so the
            // rebuild's auto pass re-marches only where graphs changed.
            // The record RE-BASES on a coaster switch (trackPieceRows
            // Sync), so no open-time coaster guard here (D249 review
            // fix — it made Cancel a silent close after a switch);
            // cancelRestore checks the record's own coaster + docGen
            // and posts the stale reason itself
            if (r != null && cancelRestore(r.snap, r.gen) > 0) {
                rebuildTrack();
                syncPlacementSpinners();
                updateBridgeControls();
                refreshGizmos(false);
                editor.redrawFromViewport();
            }
            closeTrackPieceWindow();
        };
        Stage win = LiveWindow.show(this, "Track Piece", box, onConfirm,
            onCancel, LiveWindow.anchorOf(placementBtn));
        trackPieceWin = win;
        // LIVE (D247/D248): every compile / gizmo drag / undo re-reads
        // the rows; the ops row's gray reasons re-derive per hook
        trackPieceHook = () -> {
            if (trackPieceWin != win) return;
            if (!hasCoaster() || coaster.pieces.isEmpty()) {
                closeTrackPieceWindow();
                return;
            }
            syncPlacementSpinners();   // focus-guarded (D247); ends
                                       // in syncDesignReadout
            if (opsState != null) opsState.run();
        };
        trackPieceRestoreHook = () -> {
            if (trackPieceWin != win) return;
            captureTpRecord();   // a restore re-bases the ↺ record
            trackPieceHook.run();
        };
        modelHooks.add(trackPieceHook);
        editor.docRestoreHooks.add(trackPieceRestoreHook);
        win.setOnHidden(ev -> {
            if (trackPieceWin == win) closeTrackPieceWindow();
        });
        syncDesignReadout();
    }

    /** NULL-BEFORE-HIDE teardown (D247): the field first, the hooks
     *  off, then close — the shell spinners' hide-time focus-loss
     *  commit is what commitTyped's untouched-text rule keeps from
     *  nudging the piece: Confirm committed the focused box before
     *  this (its text is then untouched), Cancel DISCARDED it
     *  (LiveWindow.doCancel → discardFocusedNumericEditor, D248
     *  review fix — the commit used to apply typed text AFTER the
     *  revert), and a toolbar toggle-close lets a typed box commit
     *  (Confirm semantics). After the close the shell spinners resync
     *  from the model — a box the Cancel button's press-commit had
     *  filled shows the reverted value again. */
    void closeTrackPieceWindow() {
        Stage old = trackPieceWin;
        if (old == null) return;
        trackPieceWin = null;
        if (trackPieceHook != null) modelHooks.remove(trackPieceHook);
        if (trackPieceRestoreHook != null && editor != null) {
            editor.docRestoreHooks.remove(trackPieceRestoreHook);
        }
        trackPieceHook = trackPieceRestoreHook = null;
        opsState = null;
        tpRec = null;
        old.close();
        syncPlacementSpinners();   // D248 review fix (see above)
    }

    /** The ops-row STATE recomputed per model hook while the Track
     *  Piece window is open (D247): Reverse / Mirror / Copy / Switch
     *  Coaster disable flags + their hover reasons. */
    private Runnable opsState;

    /** The Track Piece window's ops row (built per open — its
     *  handlers close the window through the null-before-hide path). */
    private HBox buildOpsRow() {
        Button rev = new Button("Reverse…");   // "Reverse piece…" until v19.18 (D247)
        rev.setFocusTraversable(false);
        rev.setStyle("-fx-font-size: 11px;");
        // grayed = explained (D235): the model refusals — LIVE since
        // D247: opsState re-derives every ops-row state per compile
        // off the index-resolved active piece; the grayHints read
        // these live cells
        final String[] swcWhy = { null };
        final String[] cpyWhy = { null };   // Copy (D247)
        final String[] revWhy = { null };
        final String[] mirWhy = { null };
        rev.setOnAction(ev2 -> {
            if (convertBusy()) return;   // the D247 transaction guard
            closeTrackPieceWindow();
            editor.reverseActivePiece();
        });
        // MIRROR PIECE (v19.8, D237): the exact horizontal flip —
        // graphs negate one-to-one, the shape mirrors bitwise
        Button mir = new Button("Mirror");   // "Mirror piece" until v19.18 (D247)
        mir.setFocusTraversable(false);
        mir.setStyle("-fx-font-size: 11px;");
        mir.setOnAction(ev2 -> {
            if (convertBusy()) return;   // the D247 transaction guard
            // the ACTIVE piece at click time (index-resolved, D247)
            int aiF = Math.min(editor.activePiece(),
                coaster.pieces.size() - 1);
            if (aiF < 0) return;
            editor.pushUndoSnapshot();
            if (coaster.mirrorPiece(aiF)) {
                markDirty();
                rebuildTrack();
                editor.refresh();
                syncPlacementSpinners();
                syncDesignReadout();
                // every live window's Cancel / ↺ record RE-BASES on
                // the mirrored placement, the undo path's rule (D249
                // review fix): mirrorPiece rebuilds baseOrient / the
                // canonical pair and negates the roll tiers + a
                // follower's held bank alongside the lane negation,
                // so a record taken BEFORE it reverted the placement
                // alone — a half-mirrored piece (lanes negated,
                // placement un-mirrored). Cancel never un-mirrors;
                // the mirror is its own undo step (Ctrl+Z is the path).
                for (Runnable r : new ArrayList<>(editor.docRestoreHooks)) {
                    r.run();
                }
            } else {
                statsLabel.setText("Mirror refused — delete or"
                    + " convert the trailing bridge first.");
            }
        });
        // SWITCH COASTER (v19.8, D237): move the piece under
        // another same-friction coaster, bitwise
        Button swc = new Button("Switch Coaster…");
        swc.setFocusTraversable(false);
        swc.setStyle("-fx-font-size: 11px;");
        // COPY (v19.18, D247): duplicate the active piece's whole
        // RUN 5 m along +Z (Coaster.copyRun) and edit the copy —
        // no ellipsis: nothing to ask, the offset is fixed
        Button cpy = new Button("Copy");
        cpy.setFocusTraversable(false);
        cpy.setStyle("-fx-font-size: 11px;");
        cpy.setTooltip(new Tooltip("Duplicate this piece with"
            + " everything joined to it 5 m to the side (+Z) and"
            + " edit the copy."));
        // DELETE (v19.20, D249): remove the active piece's whole RUN
        // (Coaster.deleteRun) after a confirmation — the ellipsis
        // says a dialog follows
        Button del = new Button("Delete…");
        del.setFocusTraversable(false);
        del.setStyle("-fx-font-size: 11px;");
        del.setTooltip(new Tooltip("Delete this piece with everything"
            + " joined to it (after a confirmation)."));
        final String[] delWhy = { null };
        Runnable state = () -> {
            int a = hasCoaster() && !coaster.pieces.isEmpty()
                ? Math.min(editor.activePiece(), coaster.pieces.size() - 1)
                : -1;
            boolean rc = false, rb = false, bi = false;
            if (a >= 0) {
                for (Section s : coaster.pieces.get(a).sections) {
                    if (s instanceof BridgeSection) rb = true;
                    if (!s.speedControls().isEmpty()) rc = true;
                }
                List<Section> prevS = a > 0
                    ? coaster.pieces.get(a - 1).sections : null;
                bi = prevS != null && !prevS.isEmpty()
                    && prevS.get(prevS.size() - 1) instanceof BridgeSection;
            }
            // every op grays under an open convert preview / split
            // line (D247 review fix — the transaction guard on the
            // window's own buttons; live per model hook)
            boolean busy = editor.convertUiActive();
            revWhy[0] = busy ? CONVERT_BUSY_MSG
                : rb ? "Delete or convert the bridge first."
                : rc ? "Remove the speed controls first." : null;
            mirWhy[0] = busy ? CONVERT_BUSY_MSG
                : rb ? "Delete or convert the trailing bridge first."
                : null;
            swcWhy[0] = busy ? CONVERT_BUSY_MSG
                : coasters.size() < 2
                ? "The file has no other coaster."
                : rb
                    ? "Delete or convert the trailing bridge first."
                    : bi
                        ? "The previous piece's bridge connects here —"
                            + " convert it first."
                        : null;
            rev.setDisable(revWhy[0] != null);
            mir.setDisable(mirWhy[0] != null);
            swc.setDisable(swcWhy[0] != null);
            // Copy refuses only an ORPHAN bridge at the LIST end
            // (D247 review fix — the copy appends there whatever
            // run is active, and the bridge would seize the copy
            // as its target and move the original track)
            cpyWhy[0] = busy ? CONVERT_BUSY_MSG
                : a < 0
                ? "No piece to copy."
                : coaster.listEndsInOrphanBridge()
                    ? "The last piece of the coaster ends in an orphaned"
                        + " bridge — delete or convert it first."
                    : null;
            cpy.setDisable(cpyWhy[0] != null);
            // Delete grays under the convert guard, without a piece,
            // and while the start screen still owns a piece of the
            // run (its own Cancel removes that one)
            String dw = busy ? CONVERT_BUSY_MSG
                : a < 0 ? "No piece to delete." : null;
            if (dw == null && wizardState != null) {
                int rs = coaster.runStartOf(a), re = coaster.runEndOf(a);
                for (int q = rs; q <= re; q++) {
                    if (isWizardPiece(activeCoaster, q)) {
                        dw = "Finish or cancel the open start screen first.";
                        break;
                    }
                }
            }
            delWhy[0] = dw;
            del.setDisable(dw != null);
        };
        opsState = state;
        state.run();
        del.setOnAction(ev2 -> {
            if (convertBusy()) return;   // the D247 transaction guard
            if (delWhy[0] != null || !hasCoaster() || coaster.pieces.isEmpty()) {
                return;
            }
            confirmDeleteRun(editor.runStartPiece(), editor.runEndPiece());
        });
        swc.setOnAction(ev2 -> {
            if (convertBusy()) return;   // the D247 transaction guard
            ContextMenu cm = new ContextMenu();
            for (int ci = 0; ci < coasters.size(); ci++) {
                if (ci == activeCoaster) continue;
                Coaster dc = coasters.get(ci);
                boolean fricOk = dc.fricRoll == coaster.fricRoll
                    && dc.fricAir == coaster.fricAir;
                if (fricOk) {
                    final int di = ci;
                    MenuItem it = new MenuItem(dc.name);
                    it.setOnAction(ev3 -> {
                        closeTrackPieceWindow();
                        switchPieceCoaster(di);
                    });
                    cm.getItems().add(it);
                } else {
                    cm.getItems().add(GraphEditor.grayItem(dc.name,
                        "Different friction settings — the piece"
                        + " would compile differently there."));
                }
            }
            cm.show(swc, javafx.geometry.Side.BOTTOM, 0, 0);
        });
        cpy.setOnAction(ev2 -> {
            if (convertBusy()) return;   // the D247 transaction guard
            if (!hasCoaster() || coaster.pieces.isEmpty()) return;
            // the ACTIVE piece's run at click time (index-resolved)
            int rs = editor.runStartPiece(), re = editor.runEndPiece();
            // the LIST-end orphan test (D247 review fix — the copy
            // appends at the list end whatever run is active)
            if (coaster.listEndsInOrphanBridge()) {
                statsLabel.setText("Copy refused — the last piece of"
                    + " the coaster ends in an orphaned bridge; delete"
                    + " or convert it first.");
                return;   // pre-checked: no undo step pushed
            }
            // null-before-hide (D247): the window's rows are bound to
            // the OLD piece, and the copy becomes the active one
            closeTrackPieceWindow();
            // the snapshot is pushed only once the copy landed
            // (D247 review fix — a refusal must not clear redo)
            DocState pre = snapshotDoc();
            int nh = coaster.copyRun(rs, re, Coaster.COPY_DZ);
            if (nh < 0) {
                statsLabel.setText("Copy refused — the last piece of"
                    + " the coaster ends in an orphaned bridge; delete"
                    + " or convert it first.");
                return;   // nothing moved, nothing pushed
            }
            editor.pushUndoState(pre);   // ONE step; captures the
                                         // original as active
            markDirty();
            editor.setActivePiece(nh);   // selector + spinners +
                                         // rebuild + redraw + wizard
            editor.refresh();
            syncDesignReadout();
            // the entry-ball click pair on the copy: reveal its
            // rig (AFTER the activation — the run-membership
            // reset would clear an out-of-run reveal) and select
            // its anchor node (a no-op on an s/c head)
            keyViewport().revealEntryGizmo(nh);
            editor.selectNodeFromViewport(0);
            int n = re - rs + 1;
            statsLabel.setText(String.format(Locale.US,
                "Copied %s %.0f m along +Z — editing the copy.",
                n > 1 ? "the run (" + n + " pieces)" : "the piece",
                Coaster.COPY_DZ));
        });
        HBox opsRow = new HBox(6,
            GraphEditor.grayHint(rev, () -> revWhy[0] == null
                ? "" : revWhy[0]),
            GraphEditor.grayHint(mir, () -> mirWhy[0] == null
                ? "" : mirWhy[0]),
            GraphEditor.grayHint(cpy, () -> cpyWhy[0] == null
                ? "" : cpyWhy[0]),
            GraphEditor.grayHint(swc, () -> swcWhy[0] == null
                ? "" : swcWhy[0]),
            GraphEditor.grayHint(del, () -> delWhy[0] == null
                ? "" : delWhy[0]));
        opsRow.setAlignment(Pos.CENTER_LEFT);
        return opsRow;
    }

    /** The Delete… confirmation (v19.20, D249): a small dark owned
     *  WINDOW_MODAL Stage (the range-end dialog's shape) — "Delete
     *  the whole track piece (N pieces)?" [Delete][Cancel], global
     *  undo keys (D227). The indices are re-validated when Delete
     *  fires (the modal took the undo keys — the document may have
     *  moved). No second sentence about a preceding bridge: a run
     *  HEAD is never preceded by a bridge-ending piece (it would be
     *  in that run) and an orphan bridge is the LIST's last piece,
     *  so nothing re-targets on a run delete. */
    private void confirmDeleteRun(int rs, int re) {
        int n = re - rs + 1;
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dlg.setTitle("Delete track piece");
        VBox box = new VBox(10);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: rgb(38,42,53);");
        Label head = new Label("Delete the whole track piece ("
            + n + (n == 1 ? " piece" : " pieces") + ")?");
        head.setStyle("-fx-text-fill: rgb(210,215,225);");
        head.setWrapText(true);
        head.setMaxWidth(320);
        Button ok = new Button("Delete");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(ev -> dlg.close());
        ok.setOnAction(ev -> {
            dlg.close();
            deleteRunNow(rs, re);
        });
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().addAll(head, buttons);
        Scene sc = new Scene(box);
        dlg.setScene(sc);
        installDocUndoKeys(sc);   // the D227 rule (undo keys global)
        installViewKeys(sc);      // ride keys live (v18.1)
        dlg.show();
    }

    /** DELETE THE RUN [rs..re] (v19.20, D249): re-validated against
     *  the live document, every window addressing the block pre-
     *  hidden (null-before-hide), every pane's reveal dropped (piece-
     *  INDEXED), ONE undo step, then Coaster.deleteRun; v0 follows
     *  the new first piece when run 0 goes (the Switch-Coaster
     *  precedent); the run after activates, else the previous run's
     *  head, else the empty-coaster placeholder (D239). */
    private void deleteRunNow(int rs, int re) {
        if (convertBusy()) return;
        if (!hasCoaster() || coaster.pieces.isEmpty() || editor == null) {
            statsLabel.setText("Delete refused — no piece to delete.");
            return;
        }
        int a = Math.min(editor.activePiece(), coaster.pieces.size() - 1);
        if (a < 0 || coaster.runStartOf(a) != rs || coaster.runEndOf(a) != re) {
            statsLabel.setText("Delete refused — the track piece changed "
                + "while the dialog was open.");
            return;
        }
        for (int q = rs; q <= re; q++) {
            if (isWizardPiece(activeCoaster, q)) {
                statsLabel.setText("Finish or cancel the open start screen first.");
                return;
            }
        }
        int n = re - rs + 1;
        closeTrackPieceWindow();
        editor.hideJunctionWindows();
        editor.hideSpeedPopup();
        editor.hideInitSpeedWindow();
        dropGizmoReveals();
        editor.pushUndoSnapshot();
        int nb = coaster.deleteRun(rs);
        if (nb == -2) {   // cannot happen after the checks above
            statsLabel.setText("Delete refused — the piece is not the "
                + "head of a track piece.");
            return;
        }
        if (rs == 0 && !coaster.pieces.isEmpty()
                && !coaster.pieces.get(0).sections.isEmpty()) {
            coaster.v0 = coaster.pieces.get(0).sections.get(0).vDesign();
            syncStartSpeedSpinner();
        }
        markDirty();
        if (nb >= 0) {
            editor.setActivePiece(nb);   // selector + spinners + rebuild + redraw
        } else {
            editor.resetSections();      // the empty coaster (D239)
        }
        rebuildTrack();
        editor.refresh();
        updatePieceButton();
        syncPlacementSpinners();
        syncNoCoasterPanel();
        refreshGizmos(true);
        statsLabel.setText("Deleted the track piece (" + n
            + (n == 1 ? " piece)." : " pieces)."));
    }

    /** The Track Piece window's derived rows + ↺ handlers + flag/
     *  resolve rows, built ONCE with the shell spinners (the rows are
     *  re-parented per open; every handler resolves the ACTIVE piece
     *  by index at click time). */
    private void buildTrackPieceRows(java.util.function.Supplier<String> tiltWhyP,
                                     java.util.function.Supplier<String> tiltWhyR,
                                     java.util.function.Supplier<String> lockWhy,
                                     java.util.function.Supplier<String> seamWhy) {
        java.util.function.Supplier<TrackPiece> live = () ->
            hasCoaster() && !coaster.pieces.isEmpty() ? activePieceRef() : null;
        java.util.function.BooleanSupplier follows = () ->
            hasCoaster() && editor != null && !coaster.pieces.isEmpty()
                && editor.followsPrev(editor.activePiece());
        String rstTip = "Back to the value from when this window was"
            + " opened (or from when this piece became its piece)";
        yawRstBtn = LiveWindow.revertBtn(rstTip);
        pitchRstBtn = LiveWindow.revertBtn(rstTip);
        rollRstBtn = LiveWindow.revertBtn(rstTip);
        // the yaw row's only other gray (D249): a bridged-in piece's
        // FREE yaw under optimize-orientation — its padlock lives on
        // the bridge window (this window has no yaw padlock)
        java.util.function.Supplier<String> yawWhy = () -> follows.getAsBoolean()
            ? seamWhy.get()
            : "The bridge aligns this axis — lock it in the bridge window"
                + " to hold a value.";
        java.util.function.IntConsumer revert = axis -> {
            if (trackPieceWin == null || tpRec == null) return;
            if (convertBusy()) return;   // the F3 transaction guard
            TrackPiece p = live.get();
            TpRecord r = tpRec;
            if (p == null || r.c != coaster || r.piece >= coaster.pieces.size()
                    || coaster.pieces.get(r.piece) != p) return;
            editor.pushUndoSnapshot();
            // through the SAME choke as the spinner: placementEdit +
            // the orientation-mode routing (editPitch/editRoll)
            // DESIGN_ONLY reverts the DESIGN tier to its open-time
            // values (editPitch/editRoll take the design delta there —
            // D249 review fix: the real record would have set design
            // := real); the other modes revert the real one
            boolean dOnly = p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            // a bridged-in piece reverts through the bridge's one
            // write tail (D249 — the per-mode re-place meets it)
            pieceOrientWrite(() -> {
                if (axis == 0) p.yawDeg = r.yaw;
                else if (axis == 1) p.editPitch(dOnly ? r.dPitch : r.pitch);
                else p.editRoll(dOnly ? r.dRoll : r.roll);
            });
            markDirty();
            rebuildTrack();
            syncPlacementSpinners();
            refreshGizmos(false);
            editor.redrawFromViewport();
        };
        yawRstBtn.setOnAction(e -> revert.accept(0));
        pitchRstBtn.setOnAction(e -> revert.accept(1));
        rollRstBtn.setOnAction(e -> revert.accept(2));
        // the OTHER-TIER readouts (D249): click = swap the mode
        // through the flag row's one writer (tpFlags resolves at
        // click time)
        pitchDesignLbl = new LiveWindow.Readout(() -> {
            if (tpFlags != null) tpFlags.swap();
        });
        rollDesignLbl = new LiveWindow.Readout(() -> {
            if (tpFlags != null) tpFlags.swap();
        });
        yawRow = LiveWindow.orientRow("Anchor yaw (°)",
            GraphEditor.grayHint(yawSpinner, yawWhy), null,
            GraphEditor.grayHint(yawRstBtn, yawWhy), null);
        pitchRow = LiveWindow.orientRow("Anchor pitch (°)",
            GraphEditor.grayHint(piecePitchSpinner, tiltWhyP),
            GraphEditor.grayHint(pieceLockPitchBtn, lockWhy),
            GraphEditor.grayHint(pitchRstBtn, tiltWhyP), pitchDesignLbl.node);
        rollRow = LiveWindow.orientRow("Anchor roll (°)",
            GraphEditor.grayHint(pieceRollSpinner, tiltWhyR),
            GraphEditor.grayHint(pieceLockRollBtn, lockWhy),
            GraphEditor.grayHint(rollRstBtn, tiltWhyR), rollDesignLbl.node);
        // the ORIENTATION-MODE row (D249) on the ACTIVE piece — grayed
        // as one row for a follower (the seam window carries its own);
        // the design radio grays on a bridge target under Optimize
        // position and orientation (the mutual exclusion); a flip
        // resyncs the boxes (they show the other tier now), the bridge
        // window into this piece and the rigs (the mode rides the sig)
        java.util.function.Supplier<String> designWhyTp = () -> {
            BridgeSection binto = hasCoaster() && editor != null
                && !coaster.pieces.isEmpty()
                ? bridgeInto(editor.activePiece()) : null;
            return binto != null && binto.optMode == BridgeSection.OPT_POS_YAW
                ? LiveWindow.DESIGN_RADIO_GRAY_PY : null;
        };
        tpFlags = LiveWindow.flagRow(live,
            () -> trackPieceWin != null && !docApplying && !convertBusy()
                && !follows.getAsBoolean(),
            () -> editor.pushUndoSnapshot(), this::markDirty, seamWhy,
            designWhyTp, () -> {
                syncPlacementSpinners();
                updateBridgeControls();
                refreshGizmos(false);
            });
        tpFlagsWrap = GraphEditor.grayHint(tpFlags.node, seamWhy);
        // Design ↔ Real: D→R = the D240 abs-lane dialog route else the
        // direct write; R→D = true := design through placementEdit;
        // ONLY R→D grays under an optimize-orientation bridge (D248 —
        // the Lock check and D→R stay enabled); a follower's are the
        // seam window's (grayed with its name)
        java.util.function.Supplier<String> r2dWhy = () -> {
            if (follows.getAsBoolean()) return seamWhy.get();
            BridgeSection binto = hasCoaster() && editor != null
                ? bridgeInto(editor.activePiece()) : null;
            return binto != null && binto.optMode == BridgeSection.OPT_POS_YAW
                ? LiveWindow.MARCH_OWNS_R2D : null;
        };
        java.util.function.Supplier<String> d2rWhy = () ->
            follows.getAsBoolean() ? seamWhy.get() : null;
        tpResolve = LiveWindow.resolveRow(
            () -> {
                if (trackPieceWin == null || docApplying) return;
                if (convertBusy() || follows.getAsBoolean()) return;
                int pi = Math.min(editor.activePiece(),
                    coaster.pieces.size() - 1);
                TrackPiece p = coaster.pieces.get(pi);
                // ABSOLUTE lanes pin their angles regardless of the
                // entry frame (v19.11, D240): a plain design := true
                // write SNAPS the track back to the design shape —
                // the per-quantity Preserve / Convert dialog
                boolean anyYp = false, anyBank = false;
                for (Section s : p.sections) {
                    if (s instanceof LaneSection ls && !ls.reversed()) {
                        if (ls instanceof GeoSection && ls.ypAbsolute
                                && ls.yawPitchGlobal) anyYp = true;
                        if (ls.rollAbsolute && ls.rollGlobal) anyBank = true;
                    }
                }
                if (anyYp || anyBank) {
                    editor.showDesignToRealDialog(pi, anyYp, anyBank);
                    return;
                }
                designToRealDirect(pi);
            },
            () -> {
                if (trackPieceWin == null || docApplying) return;
                if (convertBusy() || r2dWhy.get() != null) return;
                int pi = Math.min(editor.activePiece(),
                    coaster.pieces.size() - 1);
                editor.pushUndoSnapshot();
                placementEdit(pi, () -> {
                    TrackPiece p = coaster.pieces.get(pi);
                    p.pitchDeg = p.designPitchDeg;
                    p.rollDeg = p.designRollDeg;
                });
                markDirty();
                // a bridged-in piece re-marches on this rebuild by
                // itself: the seam signature carries the target head's
                // T.y / U.y, which a pitch/roll change moves
                rebuildTrack();
                syncPlacementSpinners();
                updateBridgeControls();
                editor.redrawFromViewport();
                syncDesignReadout();
            },
            d2rWhy, r2dWhy);
        trackPieceRowsSync = () -> {
            if (trackPieceWin == null || !hasCoaster()
                    || coaster.pieces.isEmpty()) return;
            int pi = Math.min(editor.activePiece(), coaster.pieces.size() - 1);
            TrackPiece p = coaster.pieces.get(pi);
            // the window FOLLOWS the active piece: a switch re-bases
            // the ↺ / Cancel record on the new piece — and so does a
            // document GENERATION the restore hooks never saw (D249
            // review fix: the density preview's pvRerun replaces the
            // piece objects through applyDocState without the
            // docRestoreHooks — only applySnapshot runs them — and
            // its Apply pushes undo without a rebuild, so Cancel found
            // gen != docGen and skipped with CANCEL_STALE_MSG for the
            // rest of the window's life). While the preview is LIVE
            // the record re-bases per hook, so it ends on the
            // committed post-conversion placement, not the pristine
            // mid-rerun one; the pre-preview values are unrecoverable
            // by design (the preview's own Cancel restores them).
            if (tpRec == null || tpRec.c != coaster || tpRec.piece != pi
                    || tpRec.head != placementTargetIdx()
                    || tpRec.gen != docGen || editor.convertUiActive()) {
                captureTpRecord();
            }
            boolean fol = follows.getAsBoolean();
            // a follower's readout lives in the seam window (D247 —
            // the seam tilt is the conflict there; the piece's own
            // tier pair can hold a stale tilt from a tilted split);
            // shown while conflicted OR while the independent check
            // is on (so the swap target exists), reading the OTHER
            // tier than the boxes edit (D249)
            boolean indep = p.orientMode != TrackPiece.OrientMode.LOCKSTEP;
            boolean dOnly = p.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            boolean show = !fol && (indep || p.orientConflict());
            boolean swap = !fol && LiveWindow.swappable(p, designWhyTp);
            String pre = LiveWindow.readoutPrefix(p);
            pitchDesignLbl.set(show, pre,
                dOnly ? p.dispPitch() : p.dispDesignPitch(), swap);
            rollDesignLbl.set(show, pre,
                dOnly ? p.dispRoll() : p.dispDesignRoll(), swap);
            tpResolve.setShown(editor.orientConflictAt(pi));
            tpResolve.d2r.setDisable(d2rWhy.get() != null);
            tpResolve.sync.run();
            tpFlags.sync.run();
            tpFlags.node.setDisable(fol);
            yawRstBtn.setDisable(yawSpinner.isDisabled());
            pitchRstBtn.setDisable(piecePitchSpinner.isDisabled());
            rollRstBtn.setDisable(pieceRollSpinner.isDisabled());
            LiveWindow.refit(this, trackPieceWin);   // managed rows changed?
        };
    }

    // ---------- the BRIDGE window: "Bridge settings (Strict Seam n)" ----------

    /** The bridge window's title — "Bridge settings (Strict Seam n)"
     *  (v19.20, D249; "Strict Seam n (bridge)" in D248), n = the
     *  junction AFTER piece bp — SectionNames.bridgeWindowTitle, so
     *  the live re-title follows the same source. */
    private String bridgeWindowTitle(int bp) {
        return SectionNames.bridgeWindowTitle(coaster, bp);
    }

    /** Is the bridge window's bridge still there? (index-based, on
     *  the window's coaster; the target must exist) */
    private boolean bridgeWinLive() {
        return bridgeWin != null && bridgeTargetLive();
    }

    /** The index/coaster half of bridgeWinLive — true from the moment
     *  bridgeWinPiece is set (openBridgeWindow syncs the rows once
     *  BEFORE its Stage exists). */
    private boolean bridgeTargetLive() {
        if (coaster != bridgeWinCoaster) return false;
        int bp = bridgeWinPiece;
        if (bp < 0 || bp + 1 >= coaster.pieces.size()) return false;
        List<Section> secs = coaster.pieces.get(bp).sections;
        return !secs.isEmpty()
            && secs.get(secs.size() - 1) instanceof BridgeSection;
    }

    private BridgeSection bridgeWinBridge() {
        if (!bridgeTargetLive()) return null;
        List<Section> secs = coaster.pieces.get(bridgeWinPiece).sections;
        return (BridgeSection) secs.get(secs.size() - 1);
    }

    private void captureBrRecord() {
        BridgeSection bs = bridgeWinBridge();
        if (bs == null) return;
        BrRecord r = new BrRecord();
        r.bp = bridgeWinPiece;
        r.optMode = bs.optMode;
        r.optLen = bs.optLen;
        r.lastOptDist = bs.lastOptDist;
        r.lenA = bs.lenA;
        r.lenB = bs.lenB;
        r.lockY = bs.lockYaw;
        r.lockP = bs.lockPitch;
        r.lockR = bs.lockRoll;
        r.noneStash = bs.noneStash == null ? null : bs.noneStash.clone();
        TrackPiece t = coaster.pieces.get(r.bp + 1);
        r.x = t.x; r.y = t.y; r.z = t.z;
        r.yaw = t.yawDeg; r.pitch = t.pitchDeg; r.roll = t.rollDeg;
        r.dPitch = t.designPitchDeg; r.dRoll = t.designRollDeg;
        r.base = t.baseOrient;
        r.mode = t.orientMode; r.affect = t.affectSubsequentSeams;
        r.snap = PlacementSnapshot.capture(coaster);   // D249
        r.gen = docGen;
        brRec = r;
    }

    /** Toggle-open the bridge window of piece bp's trailing bridge. */
    void toggleBridgeWindow(int bp) {
        if (bridgeWin != null && bridgeWinPiece == bp) {
            closeBridgeWindow();
            return;
        }
        openBridgeWindow(bp);
    }

    /** The BRIDGE window (D248; titled "Bridge settings (Strict Seam
     *  n)" since D249 — "Strict Seam n (bridge)" in D248: the Bridge
     *  properties panel of v13.5-v19.18 rebuilt as a LiveWindow, plus
     *  the target piece's orientation-mode row (D249 — was the Lock
     *  Track Shape / Only-Affect flags), other-tier readouts and
     *  Design ↔ Real — D222 had removed them, D248 brings them back
     *  for the TARGET piece): rows [Next piece placement
     *  radios][Length + Optimize length][separator][Next piece yaw +
     *  🔒 + ↺][separator][Next piece pitch + 🔒 + ↺ + design][Next
     *  piece roll + 🔒 + ↺ + design][Next piece x/y/z (manual mode)]
     *  [flags][resolve][Confirm / Cancel]. The bridge is addressed BY
     *  PIECE (GraphEditor.withBridge) — the window keeps editing ITS
     *  bridge whatever section is active — and self-validates per
     *  hook (index + coaster); an orphan refuses with a stats reason. */
    void openBridgeWindow(int bp) {
        if (!hasCoaster() || coaster.pieces.isEmpty()) {
            LiveWindow.dropAnchor(this);   // D248 review fix: no stale anchor
            return;
        }
        if (editor.startPanelActive()) {
            statsLabel.setText(
                "Placement is edited in the start screen while it is open");
            popupAnchorX = popupAnchorY = null;
            return;
        }
        BridgeSection bs = null;
        if (bp >= 0 && bp < coaster.pieces.size()) {
            List<Section> secs = coaster.pieces.get(bp).sections;
            if (!secs.isEmpty()
                    && secs.get(secs.size() - 1) instanceof BridgeSection b) {
                bs = b;
            }
        }
        if (bs == null) {
            statsLabel.setText("No bridge to edit — the piece has no"
                + " trailing bridge.");
            popupAnchorX = popupAnchorY = null;
            return;
        }
        if (bp + 1 >= coaster.pieces.size()) {
            statsLabel.setText(ORPHAN_BRIDGE_MSG);
            popupAnchorX = popupAnchorY = null;
            return;
        }
        closeBridgeWindow();   // fresh (null-before-hide)
        bridgeWinPiece = bp;
        bridgeWinCoaster = coaster;
        captureBrRecord();
        VBox box = new VBox(6);
        box.getChildren().addAll(bridgeModeRow, bridgeLenRowWrap,
            LiveWindow.thinSeparator(), bridgeYawRow,
            LiveWindow.thinSeparator(), bridgePitchRow, bridgeRollRow,
            bridgePosRowWrap, brFlags.node, brResolve.node);
        Runnable onConfirm = this::closeBridgeWindow;
        Runnable onCancel = () -> {
            if (bridgeWin == null) return;
            if (convertBusy()) return;   // the F3 transaction guard
            BrRecord r = brRec;
            BridgeSection b = bridgeWinBridge();
            // the FULL-SCOPE revert (D249): every piece + every bridge
            // back to the state this open found — one more undo step;
            // then this bridge re-applies its gap and stamps its
            // signature (the D248 tail: the march re-lands in the
            // optimize modes, a manual bridge re-captures)
            if (r != null && b != null && r.bp == bridgeWinPiece
                    && cancelRestore(r.snap, r.gen) > 0) {
                editor.reapplyBridgeAfterRestore(r.bp);
                updateBridgeControls();
                refreshGizmos(false);
                editor.redrawFromViewport();
            }
            closeBridgeWindow();
        };
        // the rows sync in the window's bridge context from here on
        updateBridgeControls();
        Stage win = LiveWindow.show(this, bridgeWindowTitle(bp), box,
            onConfirm, onCancel, LiveWindow.anchorOf(strictSeamBtn));
        bridgeWin = win;
        updateBridgeControls();   // once more with the Stage live
        bridgeHook = () -> {
            if (bridgeWin != win) return;
            if (!bridgeWinLive()) {
                closeBridgeWindow();
                statsLabel.setText(BRIDGE_GONE_MSG);
                return;
            }
            win.setTitle(bridgeWindowTitle(bridgeWinPiece));   // live n
            updateBridgeControls();   // in the window's context
        };
        bridgeRestoreHook = () -> {
            if (bridgeWin != win) return;
            bridgeHook.run();
            if (bridgeWin == win) captureBrRecord();   // re-based record
        };
        modelHooks.add(bridgeHook);
        editor.docRestoreHooks.add(bridgeRestoreHook);
        win.setOnHidden(ev -> {
            if (bridgeWin == win) closeBridgeWindow();
        });
    }

    /** NULL-BEFORE-HIDE teardown of the bridge window (D247/D248);
     *  the bridge API falls back to the active-section scoping. The
     *  typed-box rules of closeTrackPieceWindow apply (D248 review
     *  fix): Cancel discards a typed-but-unentered box before this
     *  runs, and the rows resync from the model after the close. */
    void closeBridgeWindow() {
        Stage old = bridgeWin;
        if (old == null) return;
        bridgeWin = null;
        bridgeWinPiece = -1;
        bridgeWinCoaster = null;
        if (bridgeHook != null) modelHooks.remove(bridgeHook);
        if (bridgeRestoreHook != null && editor != null) {
            editor.docRestoreHooks.remove(bridgeRestoreHook);
        }
        bridgeHook = bridgeRestoreHook = null;
        brRec = null;
        old.close();
        updateBridgeControls();   // D248 review fix (see above)
    }

    /** Run a bridge-row write in the open bridge window's context
     *  (its trailing piece), else in the active section's (the
     *  historic scoping — the strip end-line drag and the gizmo paths
     *  never changed). */
    private void bridgeCall(Runnable r) {
        if (editor == null) return;
        editor.withBridge(bridgeWinPiece, r);   // −1 = the active section
    }

    /** The bridge window's target-piece Design → Real (D248): the
     *  D240 abs-lane dialog route on the TARGET's sections, else the
     *  direct write — with the bridge stamped SIG_STALE first so the
     *  auto-reoptimize pass re-marches deterministically at the last
     *  requested length (the knee under Optimize length; the head's
     *  changed dynamics would move the signature anyway, the stamp
     *  makes the re-march certain — the D88 precedent). Under No
     *  optimization nothing marches: the septic re-captures the new
     *  head. The next re-march may REOPEN a shape-LOCKED target's
     *  conflict while the bridge optimizes orientation (accepted). */
    private void bridgeTargetDesignToReal() {
        BridgeSection bs = bridgeWinBridge();
        if (bs == null || docApplying) return;
        if (convertBusy()) return;
        int ti = bridgeWinPiece + 1;
        TrackPiece t = coaster.pieces.get(ti);
        boolean anyYp = false, anyBank = false;
        for (Section s : t.sections) {
            if (s instanceof LaneSection ls && !ls.reversed()) {
                if (ls instanceof GeoSection && ls.ypAbsolute
                        && ls.yawPitchGlobal) anyYp = true;
                if (ls.rollAbsolute && ls.rollGlobal) anyBank = true;
            }
        }
        if (anyYp || anyBank) {
            // INDEX-resolved stamp (D248 review fix): the dialog's
            // Convert route runs it inside the density preview's op,
            // after a restore that adopted NEW section objects — and
            // the preview's open hides this window (bridgeWinPiece is
            // −1 by then), so the piece index is captured here
            final int bp = bridgeWinPiece;
            editor.showDesignToRealDialog(ti, anyYp, anyBank, () -> {
                BridgeSection b = bridgeInto(bp + 1);   // bp's trailing bridge
                if (b != null) b.optSig = BridgeSection.SIG_STALE;
            });
            return;
        }
        // the stamp lands AFTER the undo push (D248 review fix — a
        // pre-push stamp rode into the snapshot's bridge copy and undo
        // re-marched the restored document); a refused follower branch
        // stamps nothing
        final int bp = bridgeWinPiece;
        designToRealDirect(ti, () -> {
            BridgeSection b = bridgeInto(bp + 1);   // bp's trailing bridge
            if (b != null) b.optSig = BridgeSection.SIG_STALE;   // certain re-march
        });   // one undo step + rebuild (auto pass)
        updateBridgeControls();
    }

    /** The bridge window's target-piece Real → Design (D248): true :=
     *  design on the target, then the bridge re-places per mode (an
     *  explicit resolve outranks the bridge padlocks — the resolve
     *  row's rule). Grayed under OPT_POS_YAW (the march owns the
     *  axes). */
    private void bridgeTargetRealToDesign() {
        BridgeSection bs = bridgeWinBridge();
        if (bs == null || docApplying) return;
        if (bs.optMode == BridgeSection.OPT_POS_YAW) return;
        if (convertBusy()) return;
        int bp = bridgeWinPiece;
        TrackPiece t = coaster.pieces.get(bp + 1);
        editor.pushUndoSnapshot();
        editor.bridgeTargetWrite(bp, () -> {
            t.pitchDeg = t.designPitchDeg;
            t.rollDeg = t.designRollDeg;
        });
        refreshGizmos(false);
        editor.redrawFromViewport();
        syncDesignReadout();
    }

    /** The bridge window's ↺ on a next-piece orientation row: back to
     *  the open-time value through the SAME choke as the spinner
     *  (bridgeTargetWrite — placementEdit + the orientation-mode
     *  routing + the per-mode re-place); one undo step. Refuses the
     *  spinner's own condition (a free axis under optimize-
     *  orientation — BridgeSection.axisEditable, D249). */
    private void bridgeRevert(int axis) {
        BridgeSection bs = bridgeWinBridge();
        BrRecord r = brRec;
        if (bs == null || r == null || r.bp != bridgeWinPiece) return;
        if (docApplying || convertBusy()) return;
        if (!bs.axisEditable(axis)) return;
        TrackPiece t = coaster.pieces.get(r.bp + 1);
        editor.pushUndoSnapshot();
        // DESIGN_ONLY reverts the design tier (D249 review fix — see
        // the Track Piece revert)
        boolean dOnly = t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        editor.bridgeTargetWrite(r.bp, () -> {
            if (axis == 0) t.yawDeg = r.yaw;
            else if (axis == 1) t.editPitch(dOnly ? r.dPitch : r.pitch);
            else t.editRoll(dOnly ? r.dRoll : r.roll);
        });
        refreshGizmos(false);
        editor.redrawFromViewport();
    }

    /** The bridge window's derived rows, built ONCE with the bridge
     *  spinners: the ↺ buttons, the design readouts, the target's flag
     *  row and the resolve row (all index-resolved on bridgeWinPiece). */
    private void buildBridgeWindowRows() {
        String rstTip = "Back to the value from when this window was opened";
        bridgeYawRst = LiveWindow.revertBtn(rstTip);
        bridgePitchRst = LiveWindow.revertBtn(rstTip);
        bridgeRollRst = LiveWindow.revertBtn(rstTip);
        bridgeYawRst.setOnAction(e -> bridgeRevert(0));
        bridgePitchRst.setOnAction(e -> bridgeRevert(1));
        bridgeRollRst.setOnAction(e -> bridgeRevert(2));
        // the OTHER-TIER readouts (D249): click = swap the target's
        // mode through the flag row's one writer
        bridgePitchDesign = new LiveWindow.Readout(() -> {
            if (brFlags != null) brFlags.swap();
        });
        bridgeRollDesign = new LiveWindow.Readout(() -> {
            if (brFlags != null) brFlags.swap();
        });
        java.util.function.Supplier<TrackPiece> live = () ->
            bridgeTargetLive() ? coaster.pieces.get(bridgeWinPiece + 1) : null;
        // the MUTUAL EXCLUSION (D249): the design radio grays while
        // this bridge optimizes orientation (and the PY radio grays
        // while the target edits its design orientation —
        // refreshBridgeRows); a flip resyncs the bridge rows (the
        // boxes show the other tier), the target's Track Piece window
        // and the rigs
        java.util.function.Supplier<String> designWhyBr = () -> {
            BridgeSection bs = bridgeWinBridge();
            return bs != null && bs.optMode == BridgeSection.OPT_POS_YAW
                ? LiveWindow.DESIGN_RADIO_GRAY_PY : null;
        };
        brFlags = LiveWindow.flagRow(live,
            () -> bridgeWinLive() && !docApplying && !convertBusy(),
            () -> editor.pushUndoSnapshot(), this::markDirty, null,
            designWhyBr, () -> {
                updateBridgeControls();
                syncPlacementSpinners();
                refreshGizmos(false);
            });
        java.util.function.Supplier<String> r2dWhy = () -> {
            BridgeSection bs = bridgeWinBridge();
            return bs != null && bs.optMode == BridgeSection.OPT_POS_YAW
                ? LiveWindow.MARCH_OWNS_R2D : null;
        };
        brResolve = LiveWindow.resolveRow(this::bridgeTargetDesignToReal,
            this::bridgeTargetRealToDesign, () -> null, r2dWhy);
        Tooltip.install(brResolve.d2r, new Tooltip(LiveWindow.D2R_TIP
            + "\nWhile the bridge optimizes orientation the next"
            + " re-optimization may reopen it."));
        bridgeRowsSync = () -> {
            TrackPiece t = live.get();
            if (t == null) return;
            // a document generation the restore hooks did not see
            // re-bases the record (the Track Piece rule, D249 review
            // fix; the preview closes this window, so this is the
            // defensive twin)
            if (brRec != null && brRec.bp == bridgeWinPiece
                    && brRec.gen != docGen) {
                captureBrRecord();
            }
            // readouts: the OTHER tier than the boxes edit, shown
            // while conflicted or while the independent check is on;
            // the resolve row only while conflicted (D249)
            boolean conflict = t.orientConflict();
            boolean indep = t.orientMode != TrackPiece.OrientMode.LOCKSTEP;
            boolean dOnly = t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            boolean show = conflict || indep;
            boolean swap = LiveWindow.swappable(t, designWhyBr);
            String pre = LiveWindow.readoutPrefix(t);
            bridgePitchDesign.set(show, pre,
                dOnly ? t.dispPitch() : t.dispDesignPitch(), swap);
            bridgeRollDesign.set(show, pre,
                dOnly ? t.dispRoll() : t.dispDesignRoll(), swap);
            brResolve.setShown(conflict);
            brResolve.sync.run();
            brFlags.sync.run();
            bridgeYawRst.setDisable(bridgeYawSpinner.isDisabled());
            bridgePitchRst.setDisable(bridgePitchSpinner.isDisabled());
            bridgeRollRst.setDisable(bridgeRollSpinner.isDisabled());
            LiveWindow.refit(this, bridgeWin);   // managed rows changed?
        };
    }

    /** Sync the bridge rows to the bridge they address (v13.2): the
     *  open bridge window's bridge (in its context, D248), else the
     *  ACTIVE section's (the strip end-line drag and the gizmo paths
     *  call this too). The value tracks the actual gap; docApplying
     *  suppresses the listeners so syncing never re-runs the
     *  optimization. Also refreshes the toolbar buttons. */
    void updateBridgeControls() {
        if (bridgeGapSpinner == null || editor == null) return;
        if (!hasCoaster() || coaster.pieces.isEmpty()) return;
        updatePieceButton();   // + the junction button (D247/D248)
        // keyed off the PIECE (not the Stage): openBridgeWindow syncs
        // once before its Stage exists
        if (bridgeWinPiece >= 0) {
            editor.withBridge(bridgeWinPiece, this::syncBridgeRows);
        } else {
            syncBridgeRows();
        }
    }

    private void syncBridgeRows() {
        if (!editor.activeBridgeWithTarget()) return;
        boolean was = docApplying;
        docApplying = true;
        setIfOff(bridgeGapSpinner, Math.max(1, editor.requestedBridgeGap()), 0.05);
        setIfOff(bridgeYawSpinner, -editor.bridgeTargetOrient(0), 0.01);   // reported yaw (D186)
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
        if (bridgeRowsSync != null) bridgeRowsSync.run();
    }

    /** Write a synced value into a spinner only when it genuinely
     *  differs — re-syncs fire on every compile, and rewriting the
     *  editor mid-typing fights the caret. */
    private void setIfOff(Spinner<Double> sp, double v, double eps) {
        if (typing(sp)) return;   // D247: never overwrite a typed box
        if (Math.abs(sp.getValue() - v) > eps) {
            sp.getValueFactory().setValue(Math.round(v * 100) / 100.0);
        }
    }

    /** true while the user is typing into a spinner's editor — a live
     *  resync must leave it alone (D247). An undo restore
     *  (docRestoring) outranks the guard: the restored value wins. */
    boolean typing(Spinner<?> sp) {
        return !docRestoring && sp.getEditor() != null
            && sp.getEditor().isFocused();
    }

    /** LIVE-RESYNC spinner write (v19.18, D247, the standing rule for
     *  every tool-window hook): write the model's value unless the
     *  user is typing in that box — numericEditor's focus-loss commit
     *  then applies THEIR value on blur (the user was typing, their
     *  intent wins). Equal values are a no-op (JavaFX only re-renders
     *  the editor on a value change). Callers mute their listeners
     *  (docApplying / their own flag) around it as before. */
    void setUnlessTyping(Spinner<Double> sp, double v) {
        if (sp == null || typing(sp)) return;
        Double cur = sp.getValue();
        if (cur != null && cur == v) return;
        sp.getValueFactory().setValue(v);
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
        // orientation spinners — THE ENABLE RULE (v19.20, D249, the
        // seam rule; was D87's "a locked axis is frozen everywhere"):
        // a HELD (🔒) axis is typeable in every mode — the typed value
        // is what the march meets; a free (🔓) axis is the march's
        // under optimize-orientation (grayed with the reason) and
        // typeable otherwise (BridgeSection.axisEditable). The
        // padlocks themselves stay clickable in every bridge mode.
        boolean orient = m == BridgeSection.OPT_POS_YAW;
        bridgeYawSpinner.setDisable(orient && !editor.bridgeLock(0));
        bridgePitchSpinner.setDisable(orient && !editor.bridgeLock(1));
        bridgeRollSpinner.setDisable(orient && !editor.bridgeLock(2));
        // all three locked = nothing to align, and a target editing
        // its DESIGN orientation excludes the mode (D249 — the march
        // would re-solve the design tier blindly): the orientation
        // mode grays out with the reason(s) — pyWhy — unless it is
        // the SELECTED mode (the user can always leave it)
        bridgeModePY.setDisable(pyWhy() != null
            && m != BridgeSection.OPT_POS_YAW);
        bridgePosRow.setDisable(m != BridgeSection.OPT_NONE);
    }

    /** Why "Optimize position and orientation" is grayed in the
     *  current bridge context (D249): all three axes locked, the
     *  target edits its design orientation, or both (the sentences
     *  joined with " · "); null = available. */
    private String pyWhy() {
        if (editor == null) return null;
        boolean allLocked = editor.bridgeLock(0) && editor.bridgeLock(1)
            && editor.bridgeLock(2);
        TrackPiece tgt = editor.bridgeTargetPiece();
        boolean dOnly = tgt != null
            && tgt.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        String a = allLocked ? PY_GRAY_ALL_LOCKED : null;
        String b = dOnly ? LiveWindow.PY_GRAY_DESIGN : null;
        if (a != null && b != null) return a + " · " + b;
        return a != null ? a : b;
    }

    /** The all-axes-locked half of the PY radio's gray reason. */
    static final String PY_GRAY_ALL_LOCKED =
        "Unlock at least one axis to optimize orientation.";

    /** ONE applier for the gizmo local/global choice (v16.13; the
     *  top-right overlay button is BACK since v18.16 D187 — per pane,
     *  visible only while a rig is revealed; refreshGizmos re-syncs
     *  every pane's button text). */
    void setGizmoAxesLocal(boolean loc) {
        settings.setGizmoLocal(loc);
        refreshGizmos(true);
    }

    /** Gizmo refresh across every pane (v18.10). */
    void refreshGizmos(boolean force) {
        for (Viewport3D vp : viewports) vp.refreshGizmo(force);
    }

    /** Drop every pane's ball-click reveal before a run REORDER
     *  (v19.19, D248 review fix — the reveal is piece-INDEXED; the
     *  rebuild that follows the move redraws the balls). */
    void dropGizmoReveals() {
        for (Viewport3D vp : viewports) vp.dropGizmoReveal();
    }

    /** Refresh the piece selector: label = the active piece, hidden
     *  entirely while the ride is a single piece (the common case). */
    void updatePieceButton() {
        if (pieceBtn == null) return;
        // RUN AS PIECE (v19.19, D248 — user vocabulary): the selector
        // counts RUNS (head..tail over strict follows + bridges), so a
        // strictly-split or bridged single run HIDES it — the Strict
        // Seam ▾ button and the seam lines are the internal-seam
        // surface; k keys off runStartOf(active) so a member piece
        // (3D click on a follower) reads its run's number
        boolean multi = hasCoaster() && coaster.runCount() > 1;
        pieceBtn.setVisible(multi);
        pieceBtn.setManaged(multi);
        if (multi && editor != null) {
            pieceBtn.setText("Piece " + coaster.runOrdinal(editor.activePiece())
                + "/" + coaster.runCount() + " ▾");
        }
        // the JUNCTION button (D247/D248) — this runs on every
        // activation, restore, New/Load, coaster switch AND per
        // rebuild (updateBridgeControls) so un-strict/detach/attach/
        // insert refresh it too
        syncJunctionButton();
    }

    /** The toolbar's ONE junction button (v19.19, D248 — user spec):
     *  exactly one of "Strict Seam n ▴" (the nearest junction left of
     *  the active section is a plain strict seam — the active piece
     *  follows) / "Strict Seam n (bridge) ▴" (the active section IS a
     *  bridge — its own exit junction, n = the piece's junction + 1;
     *  or the active piece is bridged-in — the junction to its left,
     *  opening the PREVIOUS piece's bridge window); none before the
     *  first junction (a run head with no bridge active). The label
     *  is exactly SectionNames.ownerButtonLabel. An ORPHAN bridge
     *  shows the (bridge) button GRAYED with its reason (D235 — never
     *  a silent nothing); the wizard grays both placement buttons. */
    private void syncJunctionButton() {
        if (strictSeamWrap == null) return;
        String label = null;
        String gray = wizardBlocksPlacement ? WIZARD_OWNS_MSG : null;
        int kind = 0, jp = -1;
        Tooltip tip = null;
        if (hasCoaster() && editor != null && !coaster.pieces.isEmpty()
                && editor.activePiece() < coaster.pieces.size()) {
            int a = editor.activePiece();
            String ol = editor.ownerButtonLabel(a, editor.activeSection());
            if (!ol.equals(SectionNames.TRACK_PIECE_BUTTON)) {
                label = ol;
                if (editor.activeSectionIsBridge()) {
                    kind = 2;
                    jp = a;
                    tip = junctionBridgeTip;
                    if (!editor.activeBridgeWithTarget() && gray == null) {
                        gray = ORPHAN_BRIDGE_MSG;
                    }
                } else if (coaster.bridgedInto(a)) {
                    kind = 2;
                    jp = a - 1;
                    tip = junctionBridgeTip;
                } else {
                    kind = 1;
                    jp = a;
                    tip = junctionSeamTip;
                }
            }
        }
        junctionKind = label == null ? 0 : kind;
        junctionPiece = jp;
        junctionGray = gray;
        boolean show = label != null;
        strictSeamWrap.setVisible(show);
        strictSeamWrap.setManaged(show);
        if (show) {
            if (!label.equals(strictSeamBtn.getText())) {
                strictSeamBtn.setText(label);
            }
            Tooltip.uninstall(strictSeamBtn, junctionSeamTip);
            Tooltip.uninstall(strictSeamBtn, junctionBridgeTip);
            if (tip != null) Tooltip.install(strictSeamBtn, tip);
        }
        strictSeamBtn.setDisable(gray != null);
    }

    /** The junction button's live gray reason (wizard / orphan). */
    private String junctionGray;

    /** Ride/fly MOVEMENT keys arriving at a POPUP's scene (v16.2 —
     *  the density window is its own window and can hold key focus,
     *  which parked W/S while it was open, user report): forward
     *  them to the viewport so the camera keeps moving. The popup's
     *  field is numeric-only, so consuming letters costs nothing. */
    void forwardViewKey(KeyEvent e, boolean pressed) {
        // typed text never fires actions (v19.6, D235): with a text
        // box focused in the dialog (the Coaster Options name field),
        // Space must type a space, not toggle POV, and digits must
        // type — the movement forwarding below only matters while a
        // pane is CAPTURED, and this guard keeps typed text safe even
        // now that tool windows stay VISIBLE during capture (v19.8,
        // D237 — capture used to hide them, D235).
        if (!e.isShortcutDown() && e.getSource() instanceof Scene ssc
                && ssc.getFocusOwner()
                    instanceof javafx.scene.control.TextInputControl) {
            return;
        }
        // the VIEW-MODE toggles work from any window too (v18.2, user
        // report: right-click into fly with the Speed Control panel
        // open, then Space did nothing) — POV and the ortho views are
        // exactly the keys the convert-UI lockout also keeps live.
        // Numeric-only entry boxes make the letters harmless; Space
        // never types into them either.
        // key ROUTING (v18.10, D181): the captured or hovered pane
        Viewport3D kv = keyViewport();
        if (pressed) {
            if (matches("pov", e)) {
                togglePov();
                e.consume();
                return;
            }
            if (handleViewDigit(e)) {
                e.consume();
                return;
            }
        }
        if (!(kv.flyActive
                || (kv.povActive && kv.povCaptured))) return;
        KeyCode c = e.getCode();
        if (kv.povActive && kv.povCaptured
                && (c == KeyCode.A || c == KeyCode.D)) {
            // the POV view-direction keys (v16.15) work from popup
            // scenes too, like the movement keys
            if (pressed) {
                setPovReversed(c == KeyCode.A && !keyViewport().povReversed);
            }
            e.consume();
            return;
        }
        boolean movement = c == KeyCode.W || c == KeyCode.S
            || c == KeyCode.SHIFT || c == KeyCode.CONTROL
            || (kv.flyActive && (c == KeyCode.A || c == KeyCode.D));
        if (!movement) return;
        if (pressed) kv.moveKeys.add(c);
        else kv.moveKeys.remove(c);
        e.consume();
    }

    /** The POV view direction is PER PANE since v18.11 (user
     *  report: A flipped every pane): flip only the pane that owns
     *  the keys. */
    void setPovReversed(boolean rev) {
        Viewport3D vp = keyViewport();
        vp.povReversed = rev;
        vp.applyPovNow();    // instant flip, even parked
        vp.syncPovLabel();   // the bottom-left indicator (v16.16)
    }

    /** THE pipeline entry (v18.10, D181 — the document phase moved
     *  here from Viewport3D so every pane rebuilds from ONE compile):
     *  three-pass compile + speed reconcile + auto re-optimize, the
     *  shared arrays, the Coaster-menu stats, then per-pane meshes. */
    /** TRUE while a pointer gesture (graph canvas or 3D gizmo drag)
     *  is live: per-event rebuilds run the FAST reconcile only and
     *  the EXACT settle waits for the release (v18.28, D200 — the
     *  exact cascade costs one compile per chain-depth section, too
     *  much per mouse event, invisible once per gesture). */
    boolean gestureHot = false;
    /** TRUE while a pointer button is down on the graph canvas or a
     *  gizmo — the universal key-burst end must not settle (or
     *  un-hot) a live mouse gesture (D200 verify round). */
    boolean pointerDown = false;
    /** false = the document may sit inside the fast pass's ±1e-3
     *  tube; the release/save paths call settleIfPending(). */
    private boolean docSettled = false;

    void rebuildTrack() {
        // EMPTY document / coaster (v19.2, D230): nothing to compile —
        // drop the last results, clear the meshes, leave the shell in
        // its no-coaster mode
        if (!hasCoaster() || coaster.pieces.isEmpty()) {
            lastSamples = null;
            sectionSpans = null;
            resultRates = null;
            trueV = null;
            trueFelt = null;
            trueRates = null;
            docSettled = true;
            for (Viewport3D vp : viewports) vp.rebuildMeshes();
            syncNoCoasterPanel();   // the D239 empty-piece button
            fireModelHooks();       // D247: open windows blank/close
            return;
        }
        TrackCompiler.Result compiled =
            TrackCompiler.compile(coaster, INTEGRATE_DT);
        // iterate to QUIESCENCE (v18.25, D197 — was a 3-pass cap):
        // the reconcile/re-optimize write-backs must reach their
        // fixed point INSIDE one rebuild, or the displayed track is
        // mid-cascade and the next click "edits" it — the user's
        // reopened-file-keeps-changing report. With the reconcile's
        // cascade carry a pass walks the whole chain, so real docs
        // settle in 2-4; the cap only guards a pathological cycle
        for (int pass = 0; pass < 12; pass++) {
            boolean again = coaster.reconcileChainedSpeeds(compiled);
            if (coaster.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            compiled = TrackCompiler.compile(coaster, INTEGRATE_DT);
        }
        // EXACT settle (v18.28, D200 — closes the fast pass's ±1e-3
        // tube): at rest, land every reconciled speed ON its derived
        // bits so the settled document is a pure function of the
        // graphs — a hand-reverted edit returns to its original bits.
        // Terminates in at most chain-depth passes (triangular chain;
        // the cap is a pathological-cycle guard, same idea as above).
        boolean quiesced = false;
        if (!gestureHot) {
            for (int pass = 0; pass < 40; pass++) {
                boolean again = coaster.reconcileChainedSpeeds(compiled, true);
                if (coaster.autoReoptimizeBridges(compiled)) again = true;
                if (!again) { quiesced = true; break; }
                compiled = TrackCompiler.compile(coaster, INTEGRATE_DT);
            }
        }
        // HONEST flag (D200 verify round): a cap-exhausted exact loop
        // must NOT report settled — settleIfPending would no-op and a
        // save could write a mid-cascade document
        docSettled = quiesced;
        List<FVDIntegrator.Sample> samples = compiled.samples();
        if (samples.size() < 2) { fireModelHooks(); return; }   // D247
        lastSamples = samples;
        resultRates = compiled.rates();
        trueV = compiled.trueV();
        trueFelt = compiled.trueFelt();
        trueRates = compiled.trueRates();
        sectionSpans = compiled.spans();
        updateBridgeControls();   // gap spinner tracks live moves
        syncDesignReadout();      // D222: design-orientation line
        double maxGv = -99, minGv = 99, maxY = -1e9, minY = 1e9, maxV = 0;
        for (int i = 0; i < samples.size(); i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            maxGv = Math.max(maxGv, sm.Gv());
            minGv = Math.min(minGv, sm.Gv());
            maxY = Math.max(maxY, sm.pos().y());
            minY = Math.min(minY, sm.pos().y());
            // top speed is the TRUE speed (round A, §5.1)
            maxV = Math.max(maxV, trueV[i]);
        }
        FVDIntegrator.Sample last = samples.get(samples.size() - 1);
        statRideTime = last.t();
        statTrackLen = last.s();
        statMinY = minY;
        statMaxY = maxY;
        statTopV = maxV;
        statMinGv = minGv;
        statMaxGv = maxGv;
        for (Viewport3D vp : viewports) vp.rebuildMeshes();
        updatePovStats();
        // the empty-coaster placeholder follows every mutation —
        // incl. the FLOATING wizard's placeholder piece appearing/
        // discarding and Switch Coaster emptying a source (D239)
        syncNoCoasterPanel();
        // LIVE TOOL WINDOWS (D247): every open modeless window resyncs
        // from the settled result — LAST, so hooks read the spans /
        // trueV / stats written above
        fireModelHooks();
    }

    /** An undo/redo restore's rebuild (D247, GraphEditor.applySnapshot):
     *  the live-window hooks fire from it with docRestoring held, so a
     *  restored value overwrites even a FOCUSED box — the undo is the
     *  user's stated intent, the node editor's docRestoreHooks rule. */
    void rebuildTrackRestoring() {
        docRestoring = true;
        try { rebuildTrack(); }
        finally { docRestoring = false; }
    }

    /** Gesture-end / pre-save hook (v18.28, D200): if the last
     *  rebuild ran while a gesture was hot (fast reconcile only),
     *  run one settled rebuild now — the document must never REST,
     *  and can never be SAVED, inside the fast pass's ±1e-3 tube.
     *  FORCES the settle (D200 verify round): a stale/stuck
     *  gestureHot must never make the save-path settle advisory —
     *  when this runs, whatever gesture set the flag is over (or the
     *  caller outranks it; a still-held key burst simply re-sets the
     *  flag on its next repeat). */
    void settleIfPending() {
        if (docSettled) return;
        gestureHot = false;
        rebuildTrack();
    }

    /** Bracketing sample pair {i, j, frac} for an exact ride time —
     *  by the samples' OWN t fields (the s/c sub-sample phase rule:
     *  never assume sample i sits at i·dt), clamped into every
     *  parallel array. Shared by the POV stats and the ride advance
     *  (v18.22, D193). */
    private double[] povBracket(double t) {
        int hi = Math.min(lastSamples.size(),
            Math.min(trueV.length, trueFelt.length)) - 1;
        int i = (int) clamp(t / INTEGRATE_DT, 0, hi);
        while (i > 0 && lastSamples.get(i).t() > t) i--;
        while (i + 1 <= hi && lastSamples.get(i + 1).t() <= t) i++;
        int j = Math.min(i + 1, hi);
        double t0 = lastSamples.get(i).t(), t1 = lastSamples.get(j).t();
        double f = t1 > t0 ? clamp((t - t0) / (t1 - t0), 0, 1) : 0;
        return new double[]{ i, j, f };
    }

    /** TRUE speed at the exact ride time — LINEAR between the
     *  bracketing samples (v18.22, D193 — his stall-approach stair:
     *  on a drifted section the design-spaced samples sit ~0.3 m
     *  apart while the true speed loses whole m/s per sample, so
     *  every nearest-sample consumer stepped visibly; the D167 pose
     *  interpolation applied to the VALUES). */
    double trueVAt(double t) {
        double[] b = povBracket(t);
        return trueV[(int) b[0]]
            + (trueV[(int) b[1]] - trueV[(int) b[0]]) * b[2];
    }

    /** DESIGN speed at the exact ride time (the samples' recorded
     *  profile), same interpolation. */
    double designVAt(double t) {
        double[] b = povBracket(t);
        double a = lastSamples.get((int) b[0]).v();
        return a + (lastSamples.get((int) b[1]).v() - a) * b[2];
    }

    /** The bottom stats line = the CURRENT (shared) POV position
     *  (v17.11; pane-shared since v18.10): X/Y/Z, speed,
     *  vertical/lateral G, and — since v18.39 (D211, user spec) —
     *  the absolute GLOBAL pitch/yaw/roll angles with their global
     *  rates: elevation, heading (REPORTED left-positive, D186) and
     *  bank via eulerOf, rates via worldRates on the TRUE arrays.
     *  All interpolated at the exact ride time (D193); vertical
     *  track shows "—" where heading/bank are undefined. */
    void updatePovStats() {
        if (lastSamples == null || lastSamples.isEmpty()
                || trueV == null || trueFelt == null) return;
        double[] b = povBracket(povTime);
        int i = (int) b[0], j = (int) b[1];
        double f = b[2];
        FVDIntegrator.Sample sa = lastSamples.get(i), sb = lastSamples.get(j);
        Vec3 pa = sa.pos(), pb = sb.pos();
        double[] ea = Coaster.eulerOf(Quat.fromFrame(sa.T(), sa.U()));
        double[] eb = Coaster.eulerOf(Quat.fromFrame(sb.T(), sb.U()));
        double[] wa = trueRates != null && j < trueRates.length
            ? Coaster.worldRates(sa, trueRates[i][1], trueRates[i][2],
                trueRates[i][3])
            : new double[]{ Double.NaN, Double.NaN, Double.NaN };
        double[] wb = trueRates != null && j < trueRates.length
            ? Coaster.worldRates(sb, trueRates[j][1], trueRates[j][2],
                trueRates[j][3])
            : wa;
        // FIXED SLOTS (v19.10, D239): each value renders into its
        // own pre-sized label — see buildPovRow
        povSet(0, String.format("%.3f", z3(pa.x() + (pb.x() - pa.x()) * f)));
        povSet(1, String.format("%.3f", z3(pa.y() + (pb.y() - pa.y()) * f)));
        povSet(2, String.format("%.3f", z3(pa.z() + (pb.z() - pa.z()) * f)));
        povSet(3, String.format("%.3f m/s",
            z3(trueV[i] + (trueV[j] - trueV[i]) * f)));
        povSet(4, String.format("%.3f",
            z3(trueFelt[i][0] + (trueFelt[j][0] - trueFelt[i][0]) * f)));
        povSet(5, String.format("%.3f",
            z3(trueFelt[i][1] + (trueFelt[j][1] - trueFelt[i][1]) * f)));
        // elevation raw; heading NEGATED to reported-left (the
        // D186 display boundary); bank raw; worldRates' rows
        // are {elevation', heading', bank'}, already reported
        povSet(6, angTxt(lerpAngle(ea[1], eb[1], f),
            lerpNan(wa[0], wb[0], f)));
        povSet(7, angTxt(-lerpAngle(ea[0], eb[0], f),
            lerpNan(wa[1], wb[1], f)));
        povSet(8, angTxt(lerpAngle(ea[2], eb[2], f),
            lerpNan(wa[2], wb[2], f)));
        if (povRow != null) {
            povRow.setVisible(true);
            statsLabel.setVisible(false);
        }
    }

    // ---- the fixed-slot POV bar (v19.10, D239 — user spec: values
    // must not jump around horizontally between signs/digit counts).
    // One label per field; each slot's width is measured from a
    // worst-case TEMPLATE in the actual font, values left-anchor
    // inside it, and an extreme value RATCHETS its slot wider once
    // (never narrower), so overlap can't happen and the layout moves
    // at most once per extreme — never per frame. Captions are their
    // own fixed labels, so a value's sign flip or digit change never
    // moves any caption. The full bar width is available (his note:
    // no more real-time statistics planned). ----
    static final Font STATS_FONT = Font.font(12);
    private HBox povRow;
    private Label[] povVal;
    private double[] povW;

    private static double statsTextW(String s) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        t.setFont(STATS_FONT);
        return Math.ceil(t.getLayoutBounds().getWidth()) + 2;
    }

    private void buildPovRow() {
        String[] caps = { "X:", "Y:", "Z:", "Speed:",
            "Vertical G:", "Lateral G:", "Pitch:", "Yaw:", "Roll:" };
        String[] tmpl = { "-888.888", "-888.888", "-888.888",
            "888.888 m/s", "-88.888", "-88.888",
            "-888.888° (-888.888°/s)", "-888.888° (-888.888°/s)",
            "-888.888° (-888.888°/s)" };
        // "|" separators close the classic groups: after Z, and
        // between every later field
        java.util.Set<Integer> sepAfter = java.util.Set.of(2, 3, 4, 5, 6, 7);
        povVal = new Label[caps.length];
        povW = new double[caps.length];
        povRow = new HBox();
        povRow.setAlignment(Pos.CENTER_LEFT);
        povRow.setPadding(new Insets(4, 8, 4, 8));
        povRow.setMinWidth(0);
        Label tag = new Label("POV");
        tag.setTextFill(Color.rgb(200, 205, 215));
        tag.setFont(STATS_FONT);
        tag.setPadding(new Insets(0, 10, 0, 0));
        povRow.getChildren().add(tag);
        for (int k = 0; k < caps.length; k++) {
            Label cap = new Label(caps[k]);
            cap.setTextFill(Color.rgb(200, 205, 215));
            cap.setFont(STATS_FONT);
            cap.setPadding(new Insets(0, 4, 0, k == 0 ? 0 : 10));
            Label val = new Label();
            val.setTextFill(Color.rgb(200, 205, 215));
            val.setFont(STATS_FONT);
            val.setAlignment(Pos.CENTER_LEFT);
            povW[k] = statsTextW(tmpl[k]);
            val.setMinWidth(povW[k]);
            val.setPrefWidth(povW[k]);
            povVal[k] = val;
            povRow.getChildren().addAll(cap, val);
            if (sepAfter.contains(k)) {
                Label sep = new Label("|");
                sep.setTextFill(Color.rgb(120, 125, 140));
                sep.setFont(STATS_FONT);
                sep.setPadding(new Insets(0, 0, 0, 10));
                povRow.getChildren().add(sep);
            }
        }
        povRow.setVisible(false);   // statsLabel owns the bar until
                                    // the first POV update
    }

    private void povSet(int k, String s) {
        if (povVal == null) return;
        double need = statsTextW(s);
        if (need > povW[k]) {   // ratchet — never shrink
            povW[k] = need;
            povVal[k].setMinWidth(need);
            povVal[k].setPrefWidth(need);
        }
        povVal[k].setText(s);
    }

    /** Wrap-aware lerp of two angle readings (degrees, ±180); NaN on
     *  either side yields the other (vertical-track hold-off). */
    private static double lerpAngle(double a, double b, double f) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        double d = b - a;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        double r = a + d * f;
        if (r > 180) r -= 360;
        if (r < -180) r += 360;
        return r;
    }

    private static double lerpNan(double a, double b, double f) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return a + (b - a) * f;
    }

    /** "12.345° (4.567°/s)" — em-dash where the quantity is
     *  undefined. Three decimals like the rest of the stats line
     *  (v18.48, user spec). */
    private static String angTxt(double ang, double rate) {
        return (Double.isNaN(ang) ? "—"
                : String.format("%.3f°", z3(ang)))
            + " (" + (Double.isNaN(rate) ? "—"
                : String.format("%.3f°/s", z3(rate))) + ")";
    }

    void refreshTrackMarkers(int lane, Set<Integer> sel, int selIdx,
                             double[] nodeTimes) {
        for (Viewport3D vp : viewports) {
            vp.refreshTrackMarkers(lane, sel, selIdx, nodeTimes);
        }
    }

    void togglePov() { keyViewport().togglePov(); }
    boolean railSolid = false; // false = vertical-G gradient, true = solid color
    Color railColor = Color.rgb(220, 60, 50);
    // G-force gradient stops (per-coaster, saved in .fvd): each stop is a
    // COLOR at a G VALUE; flat outside the ends, lerped between stops.
    // FIVE stops since v16.14 (user spec): PURPLE joins below the blue
    // (blue moved to −0.7) so strong negative G reads distinctly
    final Color[] gradColors = {
        Color.color(0.55, 0.15, 0.85),   // purple  -1.5
        Color.color(0.2, 0.4, 1.0),      // blue    -1   (v16.15)
        Color.color(0.15, 0.8, 0.9),     // cyan    -0.5 (v16.15)
        Color.color(0.2, 0.9, 0.3),      // green    1
        Color.color(1.0, 0.9, 0.1),      // yellow   3
        Color.color(1.0, 0.15, 0.1),     // red      5
    };
    final double[] gradStops = { -1.5, -1, -0.5, 1, 3, 5 };
    // LATERAL comb stops (v16.13; ABSOLUTE VALUE since v16.14, user
    // spec): colors mirror about zero — |Gl| hits green 0.5,
    // yellow 1.0, red 1.5 by default
    final Color[] latColors = {
        Color.color(0.2, 0.9, 0.3),
        Color.color(1.0, 0.9, 0.1),
        Color.color(1.0, 0.15, 0.1),
    };
    final double[] latStops = { 0.5, 1.0, 1.5 };
    // render range widened −1→−2 in v16.14 so the −1.5 purple shows
    static final double G_MIN = -2.0, G_MAX = 5.0;
    static final double INTEGRATE_DT = Coaster.INTEGRATE_DT;   // D249: one constant

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
    void setLaneButtonMode(int mode) { setLaneButtonMode(mode, false); }

    /** delta = the INFLUENCE surface is being edited (v15.0, D98): the
     *  buttons wear the other mode's names with a Δ prefix, and the
     *  Resulting picks — which show TRUE values there — say so. */
    void setLaneButtonMode(int mode, boolean delta) {
        boolean geo = mode == 1;
        String[] names = geo ? GEO_LANE_BTN : FORCE_LANE_BTN;
        Color[] cols = geo ? GEO_LANE_COLORS : LANE_COLORS;
        // ABSOLUTE lanes wear their own names + colors — Elevation /
        // Heading (v19.9, D238) and Bank (v19.10, D239 — the roll
        // button used to keep "Roll" with only the tint changing)
        boolean ypAbs = mode == 1 && !delta && editor != null
            && editor.activeAbsYp();
        boolean rollAbs = mode != 2 && editor != null
            && editor.activeAbsRoll();
        for (int i = 0; i < 3; i++) {
            if (laneButtons[i] == null) continue;
            // roll takes no deltas (v15.4, D102): the influence
            // surface offers Gv/Gl or pitch/yaw only
            laneButtons[i].setText((delta && i < 2 ? "Δ " : "")
                + (ypAbs && i < 2 ? (i == 0 ? "Elevation" : "Heading")
                    : rollAbs && i == 2 ? "Bank"
                    : names[i]));
            laneButtons[i].setDisable(mode == 2 || (delta && i == 2));
            // an ABSOLUTE-BANK section's lane-2 button wears the bank
            // color like its curve (v18.2, user spec)
            Color bc = i == 2 && rollAbs ? BANK_COLOR
                : ypAbs && i < 2
                ? (i == 0 ? ELEV_ABS_COLOR : HEAD_ABS_COLOR)
                : cols[i];
            laneButtons[i].setStyle(TOOLBTN_STYLE + String.format(
                " -fx-text-fill: rgb(%d,%d,%d);",
                (int) (bc.getRed() * 255), (int) (bc.getGreen() * 255),
                (int) (bc.getBlue() * 255)));
        }
        if (resPitchCheck != null) {
            resLastGeo = geo;
            resLastDelta = delta;
            syncResChecks();
        }
    }
    // the Resulting checks' last-applied mode (v17.6 — the settings
    // dialog re-titles them live through syncResChecks)
    private boolean resLastGeo, resLastDelta;

    /** Re-title the Resulting overlay checks per the active mode AND
     *  the resulting-graph settings (v17.6, D143): unit suffixes
     *  follow the rate-units setting, the roll check reads "Bank (°)"
     *  under absolute behavior, and it grays over geo sections (the
     *  roll overlay is force-view only). */
    void syncResChecks() {
        if (resPitchCheck == null) return;
        String tru = resLastDelta ? "True " : "";
        String ru = settings.resRateM() ? "°/m" : "°/s";
        // absolute yaw/pitch behavior (v19.9, D238): comps 1/2 are
        // elevation/heading ANGLES in the force view
        boolean ypAbs = !resLastGeo && settings.resYpAbs()
            && settings.resYpG();
        resPitchCheck.setText(tru
            + (resLastGeo ? "Vertical G (g)"
                : ypAbs ? "Elevation (°)" : "Pitch rate (" + ru + ")"));
        resYawCheck.setText(tru
            + (resLastGeo ? "Lateral G (g)"
                : ypAbs ? "Heading (°)" : "Yaw rate (" + ru + ")"));
        resRollCheck.setText(tru
            + (settings.resRollAbs() && settings.resRollG()
                ? "Bank (°)" : "Roll rate (" + ru + ")"));
        // grayed only while it would DUPLICATE the active section's
        // own roll graph (v17.7, D144 — both views otherwise). Graying
        // also DESELECTS since v18.2 (user spec — the v17.7 sticky
        // check is gone: a grayed-but-checked box read as broken).
        boolean redundant = editor != null && editor.resRollRedundant();
        resRollCheck.setDisable(redundant);
        if (redundant && resRollCheck.isSelected()) {
            resRollCheck.setSelected(false);
            settings.setResRoll(false);
            if (editor != null) editor.refresh();
        }
        tintResChecks();
    }

    /** Fill a checked Resulting box with its curve's color (v17.8,
     *  D145 — user spec). The .box sub-node exists only after the
     *  skin applies (lazy lookup, the color-picker precedent). */
    private void tintResCheck(CheckBox cb, Color col) {
        javafx.scene.Node box = cb.lookup(".box");
        if (box == null) return;
        box.setStyle(cb.isSelected()
            ? String.format(
                "-fx-background-color: rgb(%d,%d,%d);"
                + " -fx-mark-color: white;",
                (int) (col.getRed() * 255),
                (int) (col.getGreen() * 255),
                (int) (col.getBlue() * 255))
            : "");
    }

    void tintResChecks() {
        if (resPitchCheck == null) return;
        Color[] pal = resLastGeo ? LANE_COLORS : GEO_LANE_COLORS;
        boolean ypAbs = !resLastGeo && settings.resYpAbs()
            && settings.resYpG();   // D238
        tintResCheck(resPitchCheck, ypAbs ? ELEV_ABS_COLOR : pal[0]);
        tintResCheck(resYawCheck, ypAbs ? HEAD_ABS_COLOR : pal[1]);
        tintResCheck(resRollCheck,
            settings.resRollAbs() && settings.resRollG()
                ? BANK_COLOR : pal[2]);
    }

    // TIME is light purple throughout the graph UI (v10.2, was yellow —
    // freed for the geo yaw lane): the "Time (s)" axis label, the
    // horizontal (time-changing) guide line, the POV position marker
    static final Color TIME_COLOR = Color.rgb(200, 165, 255);
    // BANK color — the resulting bank overlay AND real absolute-bank
    // lanes (v17.9, D146): red-orange, nudged MORE ORANGE in v19.10
    // (D239, user spec — still darker/hotter than the roll lane's
    // pale orange rgb(255,170,90))
    static final Color BANK_COLOR = Color.rgb(240, 110, 35);
    // ABSOLUTE elevation/heading lane colors (v19.9, D238 — user
    // spec: elevation darker/bluer than the pitch-rate teal; heading
    // re-specced YELLOW-GREEN in v19.10 (D239 — was amber): between
    // the yaw yellow rgb(245,215,80) and the Lat-G green
    // rgb(120,220,130), distinct from both — the BANK_COLOR pattern
    // for lanes 0/1, worn by curves, buttons AND resulting overlays)
    static final Color ELEV_ABS_COLOR = Color.rgb(40, 150, 200);
    static final Color HEAD_ABS_COLOR = Color.rgb(180, 225, 45);


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
    private Label rippleTLabel;   // relabels time/distance per axis (v18.0)

    /** The horizontal ripple check reads "time" or "distance" per the
     *  plot's display domain (v18.0, D162). */
    void syncRippleLabel(boolean dist) {
        if (rippleTLabel != null) {
            rippleTLabel.setText(dist ? "distance" : "time");
        }
    }
    boolean slideKey;           // S held: slide nodes/handles along a line
    boolean cKey;               // C held: click the track = recenter orbit
    private Spinner<Double> heartSpinner, gaugeSpinner, tieSpinner;
    // Phase 3 friction (v17.0, D133): rolling μ + air drag (shown
    // per km — the raw 1/m coefficient is unreadable at spinner steps)
    private Spinner<Double> fricRollSpinner, fricAirSpinner;
    private Spinner<Double> startXSpinner, startYSpinner, startZSpinner;
    private Spinner<Double> yawSpinner;   // active piece's heading (v12.0)
    // piece START pitch/roll + padlocks (v15.3, D101 — the rigid-tilt
    // placement knobs; locks shared with a bridge connecting in)
    private Spinner<Double> piecePitchSpinner, pieceRollSpinner;
    private ToggleButton pieceLockPitchBtn, pieceLockRollBtn;
    /** The Track Piece padlocks' LIVE tooltip (v19.20, D249): over a
     *  bridged-in piece they ARE the connecting bridge's padlocks
     *  (held/typeable), over a free piece the edit guard. */
    private final Tooltip pieceLockTip = new Tooltip(FREE_LOCK_TIP);
    Spinner<Double> startSpeedSpinner;
    private Button pieceBtn;              // piece selector (hidden at 1 piece)
    // piece placement/reverse live in the toolbar's Track Piece
    // WINDOW since v13.2 (user: piece settings belong by the graph,
    // not the Coaster menu); the x/y/z row is a shell field, the
    // orientation rows are built by buildTrackPieceRows (D248)
    private HBox placementRow;
    private Label bridgeGapLbl;           // bridge LENGTH spinner (v13.2;
    private Spinner<Double> bridgeGapSpinner;   // in the Placement dropdown
                                                // since v13.5)
    // bridge placement modes (v13.5): the Placement dropdown edits the
    // NEXT piece through the bridge — mode radios + per-mode spinners
    private VBox bridgeModeRow;
    private StackPane bridgeLenRowWrap, bridgePosRowWrap;   // D235 hints
    private HBox bridgeLenRow, bridgeYawRow, bridgePitchRow,
        bridgeRollRow, bridgePosRow;
    private RadioButton bridgeModePY, bridgeModeP, bridgeModeN;
    private CheckBox bridgeOptLenCheck;   // v13.8: auto length (D82)
    private Button placementBtn;   // "Track Piece ▴" (always, D248)
    /** The JUNCTION button (D247 "Strict Seam ▾"; "Strict Seam n ▴" /
     *  "Strict Seam n (bridge) ▴" since D248) + its grayHint wrapper,
     *  the toolbar child whose visibility syncJunctionButton owns. */
    private Button strictSeamBtn;
    private javafx.scene.layout.StackPane strictSeamWrap;
    private ToggleButton bridgeLockYawBtn, bridgeLockPitchBtn,
        bridgeLockRollBtn;         // v13.13: orientation locks (D87)
    private Spinner<Double> bridgeYawSpinner, bridgePitchSpinner,
        bridgeRollSpinner, bridgeXSpinner, bridgeYSpinner, bridgeZSpinner;

    /** An orientation padlock (v13.13): SELECTED = unlocked (🔓,
     *  editable / optimizer-aligned), unselected = locked (🔒,
     *  frozen — the optimization meets the value instead). Tinted
     *  since v14.0 (D91): GREEN = unlocked, RED = locked — the glyphs
     *  alone read too alike; SATURATED since v14.4 (D96, user: the
     *  faded 0.30-alpha washes read as "grayed out"/disabled). */
    /** OUTLINED-BUTTON style (v19.4, D233 — the recurring
     *  misaligned-outline fix, the seam padlocks' screenshot): a
     *  border drawn over JavaFX's DEFAULT button background never
     *  lines up — the default is multi-layer with unequal insets and
     *  its own radii. The clean pattern, used for EVERY outlined /
     *  highlighted button from now on: ONE background layer,
     *  `-fx-background-insets: 0`, and the SAME radius on background
     *  and border. */
    static String outlineStyle(String extra, String borderColor) {
        return "-fx-background-color: rgb(225,227,232);"
            + " -fx-background-insets: 0; -fx-background-radius: 4;"
            + " -fx-border-insets: 0; -fx-border-radius: 4;"
            + " -fx-border-width: 2;"
            + " -fx-border-color: " + borderColor + ";"
            + (extra == null ? "" : " " + extra);
    }

    /** outlineStyle over an ARBITRARY single background (v19.11,
     *  D240) — the same D233 alignment rule for non-button controls
     *  (a TextField keeps its own inner background via the
     *  -fx-control-inner-background lookup). */
    /** Normalize ESSENTIALLY-zero values to exact +0.0 before
     *  display (D244; threshold re-specced in v19.16, D245 — user:
     *  sub-display dust KEEPS its sign so "-0.000" honestly means
     *  "a little negative"): only |v| < 1e-6 — true zero, −0.0 (a
     *  flat handle's slope is 0/negative-effInDt), and deep dust —
     *  collapses to "0.000"; anything from 1e-6 up still formats
     *  with its real sign. Use before any %.3f-class format of a
     *  value that can hit zero. */
    static double z3(double v) {
        return Math.abs(v) < 1e-6 ? 0.0 : v;
    }

    static String outlineStyleBg(String bg, double radius,
                                 String borderColor) {
        return "-fx-background-color: " + bg + ";"
            + " -fx-background-insets: 0;"
            + " -fx-background-radius: " + radius + ";"
            + " -fx-border-insets: 0; -fx-border-radius: " + radius + ";"
            + " -fx-border-width: 2;"
            + " -fx-border-color: " + borderColor + ";";
    }

    /** The BRIDGE padlocks' meaning (v19.20, D249 — the seam rule,
     *  user spec: 🔒 = held at the typed value, 🔓 = the bridge's
     *  under optimize-orientation) — the bridge window's padlocks and
     *  the target's Track Piece padlocks share the text. */
    static final String BRIDGE_LOCK_TIP =
        "Locked = hold this axis at the typed angle (the bridge bends to it).\n"
        + "Unlocked = free: under Optimize position and orientation the bridge\n"
        + "aligns it; in the other modes it stays editable.";
    /** The gray reason of a free axis under optimize-orientation
     *  (D249) — the bridge window's rows and the target's Track Piece
     *  pitch/roll rows. */
    static final String BRIDGE_ALIGNS_AXIS =
        "The bridge aligns this axis — lock it to hold a value.";
    /** A FREE piece's Track Piece padlocks stay pure edit guards
     *  (🔒 = frozen box) — the residual opposite sense, D249. */
    static final String FREE_LOCK_TIP =
        "Guards the box against accidental edits.";

    // package-private since v19.19 (D248): LiveWindow.lockToggle builds
    // the seam window's padlocks in this same look
    static final String LOCK_BASE_STYLE =
        "-fx-font-size: 10px; -fx-padding: 1 4 1 4;";
    static final String LOCK_OPEN_STYLE = LOCK_BASE_STYLE
        + " -fx-base: rgb(40,130,52); -fx-background-color: rgba(58,190,76,0.75);";
    static final String LOCK_SHUT_STYLE = LOCK_BASE_STYLE
        + " -fx-base: rgb(140,42,42); -fx-background-color: rgba(215,58,58,0.75);";

    private ToggleButton bridgeLockBtn(int axis) {
        ToggleButton b = new ToggleButton("🔒");
        b.setFocusTraversable(false);
        b.setStyle(LOCK_SHUT_STYLE);
        b.selectedProperty().addListener((obs, o, sel) -> {
            b.setText(sel ? "🔓" : "🔒");
            b.setStyle(sel ? LOCK_OPEN_STYLE : LOCK_SHUT_STYLE);
        });
        b.setOnAction(e -> {
            if (docApplying) return;
            if (convertBusy()) {   // the F3 transaction guard (D248
                updateBridgeControls();   // review fix): re-sync the padlock
                return;
            }
            bridgeCall(() -> {   // the window's bridge (D248)
                editor.setBridgeLock(axis, !b.isSelected());
                refreshBridgeRows();
            });
            refreshGizmos(false);   // rings follow the locks live (D188)
        });
        Tooltip.install(b, new Tooltip(BRIDGE_LOCK_TIP));   // D249
        return b;
    }

    /** The live bridge CONNECTING INTO piece pieceIdx (= the previous
     *  piece's trailing bridge), or null — its lock fields govern the
     *  Placement pitch/roll padlocks so the two panels show one state
     *  (v15.3, D101). */
    BridgeSection bridgeInto(int pieceIdx) {   // viewport gizmos read it too (v16.11)
        if (pieceIdx <= 0 || pieceIdx >= coaster.pieces.size()) return null;
        List<Section> prev = coaster.pieces.get(pieceIdx - 1).sections;
        return !prev.isEmpty()
            && prev.get(prev.size() - 1) instanceof BridgeSection bs
            ? bs : null;
    }

    /** Placement-row padlock (v15.3, D101): the ACTIVE piece's start
     *  pitch (axis 1) / roll (axis 2). A bridged-in piece's padlock IS
     *  the connecting bridge's (🔒 = held/typeable, the seam rule) and
     *  routes through the bridge window's ONE lock choke since v19.20
     *  (D249 — GraphEditor.setBridgeLock: undo, the capture, the
     *  optimize-orientation re-march; it used to write the flag raw
     *  with no re-march, and the D247 review fix had grayed it under
     *  a march-owned piece as "changed nothing" — a flip changes
     *  what the next re-march aligns, so it re-marches now). A free
     *  piece keeps its UI-only edit guard (🔒 = frozen box). */
    private ToggleButton placementLockBtn(int axis) {
        ToggleButton b = new ToggleButton("🔒");
        b.setFocusTraversable(false);
        b.setStyle(LOCK_SHUT_STYLE);
        b.selectedProperty().addListener((obs, o, sel) -> {
            b.setText(sel ? "🔓" : "🔒");
            b.setStyle(sel ? LOCK_OPEN_STYLE : LOCK_SHUT_STYLE);
        });
        b.setOnAction(e -> {
            if (docApplying) return;
            if (convertBusy()) {   // the F3 transaction guard (D248
                syncPlacementSpinners();   // review fix): re-sync the padlock
                return;
            }
            boolean locked = !b.isSelected();
            int a = Math.min(editor.activePiece(), coaster.pieces.size() - 1);
            BridgeSection bs = bridgeInto(a);
            if (bs != null) {
                // the previous piece's trailing bridge, in ITS context
                editor.withBridge(a - 1, () -> editor.setBridgeLock(axis, locked));
            } else {
                TrackPiece p = activePieceRef();
                if (axis == 1) p.uiLockPitch = locked;
                else p.uiLockRoll = locked;
            }
            syncPlacementSpinners();
            refreshGizmos(false);   // rings follow the locks live (D188)
        });
        Tooltip.install(b, pieceLockTip);   // live text (D249)
        return b;
    }
    private SplitPane mainSplit;           // 3D view over graph panel
    SplitPane viewSplit;                   // primary + extra view panes (v18.9)
    private double lastDividerPos = 0.6;   // restore point for the collapse toggle

    // FULL-PARITY VIEW PANES (v18.10, D181 — user spec: "every view
    // works exactly like the main pane"): each pane is a COMPLETE
    // Viewport3D (own meshes/cameras/gizmos/markers/combs/compass/
    // POV/fly). Equal importance — ANY pane can be removed except
    // the last. Keys route to keyViewport(); the POV ride position
    // is SHARED (one graph marker, one stats line).
    final List<Viewport3D> viewports = new ArrayList<>();
    Viewport3D hoverViewport;   // last pane under the mouse
    double povTime;             // shared ride position (s)
    int povFollowPiece = -1;    // shared POV piece-follow anchor

    boolean mouseOverView;    // cursor currently over a 3D pane
    boolean mouseOverGraph;   // cursor currently over the graph canvas

    /** One-shot screen anchor for the NEXT settings popup (v18.19,
     *  D190 — a ball/rig double-click opens its panel near the click
     *  instead of under the toolbar button); consumed on show. */
    Double popupAnchorX, popupAnchorY;

    /** Whether any view pane has a gizmo drag live (D248 — the
     *  mid-drag modifier-key guard in handleKey). */
    boolean anyGizmoDragging() {
        for (Viewport3D v : viewports) if (v.isGizmoDragging()) return true;
        return false;
    }

    void nextPopupAnchor(double x, double y) {
        popupAnchorX = x;
        popupAnchorY = y;
    }

    /** The pane holding a mouse capture (fly / captured POV), or
     *  null — at most one (releaseOtherCaptures). */
    Viewport3D capturedViewport() {
        for (Viewport3D vp : viewports) {
            if (vp.flyActive || (vp.povActive && vp.povCaptured)) return vp;
        }
        return null;
    }

    /** The pane that owns view keys: the CAPTURED pane first, else
     *  the hovered / last-hovered pane. */
    Viewport3D keyViewport() {
        Viewport3D cap = capturedViewport();
        if (cap != null) return cap;
        if (hoverViewport != null && viewports.contains(hoverViewport)) {
            return hoverViewport;
        }
        return viewports.get(0);
    }

    /** The 1-6 VIEW-DIRECTION keys (v18.11, user spec — FIXED digit
     *  keys, the POV A/D precedent; they replace T/Shift+T),
     *  numbered in DROPDOWN order since v19.13 (D242): 1 Top,
     *  2 Front, 3 Back, 4 Right, 5 Left, 6 Tangent to Selection.
     *  1-5 fire only while a pane is CAPTURED or the mouse is over
     *  a view — over the graph, 1/2/3 stay lane switches. 6 fires
     *  from the graph too, aimed at the last-hovered pane. Returns
     *  true when handled. */
    boolean handleViewDigit(KeyEvent e) {
        if (e.isShortcutDown() || e.isAltDown() || e.isShiftDown()
                || e.isMetaDown()) {
            return false;
        }
        int d = switch (e.getCode()) {
            case DIGIT1 -> 1;
            case DIGIT2 -> 2;
            case DIGIT3 -> 3;
            case DIGIT4 -> 4;
            case DIGIT5 -> 5;
            case DIGIT6 -> 6;
            default -> 0;
        };
        if (d == 0 || viewports.isEmpty()) return false;
        Viewport3D cap = capturedViewport();
        boolean overView = mouseOverView && hoverViewport != null
            && viewports.contains(hoverViewport);
        Viewport3D target;
        if (cap != null) {
            target = cap;
        } else if (overView) {
            target = hoverViewport;
        } else if (d == 6 && mouseOverGraph) {
            // 6 from the graph switches the LAST-hovered pane
            target = keyViewport();
        } else {
            return false;   // the graph keeps 1/2/3 as lane switches
        }
        target.exitRideModes();
        // DROPDOWN-ORDER numbering since v19.13 (D242, user spec):
        // 1 Top, 2 Front, 3 Back, 4 Right, 5 Left, 6 Tangent (was
        // 1-4 sides, 5 Top)
        if (d == 1) {
            if (!(target.orthoView && target.orthoTop)) {
                target.orthoEnterTop();
            }
        } else {
            target.setOrthoSideMode(d == 6 ? 4 : d - 2);
        }
        return true;
    }

    /** Entering fly/captured-POV in one pane releases every other
     *  pane's capture (two Robots would fight over one cursor). */
    void releaseOtherCaptures(Viewport3D except) {
        for (Viewport3D vp : viewports) {
            if (vp != except) vp.releaseCapture();
        }
    }

    /** CAPTURE-HIDE (v19.6, D235 — user report: a modeless dialog
     *  under the warped hidden fly cursor stole the mouse-move
     *  stream, the look froze and the cursor re-appeared over the
     *  window — the D189 overlay problem at OS-window scale): while
     *  a pane owns the mouse (fly / captured POV) every open TOOL
     *  window hides; release brings them back where they were.
     *  External applications (Alt-Tab, the Windows key) are
     *  untouched — the focus-loss story stays as it was. Idempotent;
     *  every capture entry/exit calls it. */
    private final List<CaptureRec> captureWired = new ArrayList<>();
    private javafx.collections.ListChangeListener<javafx.stage.Window>
        captureWinWatch;

    /** One wired tool window's capture state (v19.11, D240): the
     *  filters, the scene cursor, every NODE cursor we nulled (the
     *  text-caret I-beam beats a scene-level NONE — the deepest
     *  non-null cursor wins), and the Stage's resizable flag (a
     *  decorated Stage's native resize borders show OS cursors
     *  JavaFX can't suppress; non-resizable kills those zones). */
    private static final class CaptureRec {
        final javafx.scene.Scene sc;
        final javafx.event.EventHandler<javafx.scene.input.MouseEvent>
            move, press;
        final javafx.event.EventHandler<javafx.scene.input.ScrollEvent>
            scroll;
        final javafx.scene.Cursor prevScene;
        final java.util.IdentityHashMap<javafx.scene.Node,
            javafx.scene.Cursor> stash = new java.util.IdentityHashMap<>();
        final Stage stg;
        final boolean wasResizable;
        int moveCount;

        CaptureRec(javafx.scene.Scene sc,
                javafx.event.EventHandler<javafx.scene.input.MouseEvent> move,
                javafx.event.EventHandler<javafx.scene.input.MouseEvent> press,
                javafx.event.EventHandler<javafx.scene.input.ScrollEvent> scroll,
                javafx.scene.Cursor prevScene, Stage stg,
                boolean wasResizable) {
            this.sc = sc;
            this.move = move;
            this.press = press;
            this.scroll = scroll;
            this.prevScene = prevScene;
            this.stg = stg;
            this.wasResizable = wasResizable;
        }
    }

    /** Tool windows during fly/POV capture (v19.8, D237 — user spec,
     *  superseding D235's hide-and-reshow): every open tool window
     *  STAYS VISIBLE and live-updating. While a pane owns the mouse,
     *  each non-autohide Popup's and owned Stage's scene hides the
     *  cursor and FORWARDS mouse moves into the capturing pane's
     *  look handler (flyLook reads screen coordinates only, so a
     *  window sitting under the warped hidden cursor no longer
     *  starves the view — the D235 freeze), a RIGHT-click over one
     *  releases the capture like anywhere else, and every other
     *  press/scroll is swallowed. Release unwires and restores
     *  everything. HARDENED in v19.11 (D240 — the user's cursor
     *  flashes): every NODE-level cursor in the window is stashed
     *  and nulled (the TextField I-beam came from CSS and outranked
     *  the scene NONE), resizable Stages go non-resizable for the
     *  capture (the OS resize-border cursors), OWNER-CHAIN Stages
     *  wire too (a color picker owned by Rail Settings), windows
     *  OPENED MID-CAPTURE wire via a Window-list watch (Ctrl+Z
     *  rebuilds the seam pop-out; Ctrl+E opens Export), and the
     *  move filter re-sweeps node cursors every 25th event (skins
     *  can grow styled sub-nodes late). */
    void syncCaptureWindows() {
        boolean captured = capturedViewport() != null;
        if (captured) {
            for (javafx.stage.Window w
                    : new ArrayList<>(javafx.stage.Window.getWindows())) {
                wireCaptureWindow(w);
            }
            if (captureWinWatch == null) {
                captureWinWatch = ch -> {
                    while (ch.next()) {
                        if (!ch.wasAdded()) continue;
                        for (javafx.stage.Window w : ch.getAddedSubList()) {
                            javafx.application.Platform.runLater(() -> {
                                if (capturedViewport() != null) {
                                    wireCaptureWindow(w);
                                }
                            });
                        }
                    }
                };
                javafx.stage.Window.getWindows()
                    .addListener(captureWinWatch);
            }
        } else if (!captureWired.isEmpty() || captureWinWatch != null) {
            if (captureWinWatch != null) {
                javafx.stage.Window.getWindows()
                    .removeListener(captureWinWatch);
                captureWinWatch = null;
            }
            for (CaptureRec rec : captureWired) {
                rec.sc.removeEventFilter(
                    javafx.scene.input.MouseEvent.MOUSE_MOVED, rec.move);
                rec.sc.removeEventFilter(
                    javafx.scene.input.MouseEvent.MOUSE_DRAGGED, rec.move);
                rec.sc.removeEventFilter(
                    javafx.scene.input.MouseEvent.MOUSE_PRESSED, rec.press);
                rec.sc.removeEventFilter(
                    javafx.scene.input.ScrollEvent.SCROLL, rec.scroll);
                for (var en : rec.stash.entrySet()) {
                    en.getKey().setCursor(en.getValue());
                }
                rec.sc.setCursor(rec.prevScene);
                if (rec.stg != null) rec.stg.setResizable(rec.wasResizable);
            }
            captureWired.clear();
        }
    }

    /** Does this window take the capture wiring? Non-autohide
     *  Popups, and Stages owned (transitively, D240) by the main
     *  stage. */
    private boolean captureQualifies(javafx.stage.Window w) {
        if (w == stage || !w.isShowing()) return false;
        if (w instanceof javafx.stage.Popup p) {
            return !p.isAutoHide();   // autohide dies on the capture
                                      // click by itself
        }
        if (w instanceof Stage s) {
            for (javafx.stage.Window o = s.getOwner(); o != null;
                    o = o instanceof Stage st ? st.getOwner() : null) {
                if (o == stage) return true;
            }
        }
        return false;
    }

    private void wireCaptureWindow(javafx.stage.Window w) {
        if (!captureQualifies(w)) return;
        javafx.scene.Scene sc = w.getScene();
        if (sc == null) return;
        for (CaptureRec r : captureWired) {
            if (r.sc == sc) return;   // already wired
        }
        Stage stg = w instanceof Stage s ? s : null;
        boolean wasRes = stg != null && stg.isResizable();
        javafx.event.EventHandler<javafx.scene.input.MouseEvent> press =
            e -> {
                if (e.getButton()
                        == javafx.scene.input.MouseButton.SECONDARY) {
                    Viewport3D vp = capturedViewport();
                    if (vp != null) vp.releaseCapture();
                }
                e.consume();
            };
        javafx.event.EventHandler<javafx.scene.input.ScrollEvent> scroll =
            javafx.event.Event::consume;
        javafx.scene.Cursor prev = sc.getCursor();
        CaptureRec[] hole = new CaptureRec[1];
        javafx.event.EventHandler<javafx.scene.input.MouseEvent> move =
            e -> {
                Viewport3D vp = capturedViewport();
                if (vp != null) vp.flyLook(e);
                // cheap periodic re-sweep: a mouse-move is exactly
                // the moment a stray cursor could show (D240)
                CaptureRec rec = hole[0];
                if (rec != null && ++rec.moveCount % 25 == 0) {
                    sweepCursors(sc.getRoot(), rec.stash);
                }
                e.consume();
            };
        CaptureRec rec = new CaptureRec(sc, move, press, scroll, prev,
            stg, wasRes);
        hole[0] = rec;
        sc.addEventFilter(
            javafx.scene.input.MouseEvent.MOUSE_MOVED, move);
        sc.addEventFilter(
            javafx.scene.input.MouseEvent.MOUSE_DRAGGED, move);
        sc.addEventFilter(
            javafx.scene.input.MouseEvent.MOUSE_PRESSED, press);
        sc.addEventFilter(
            javafx.scene.input.ScrollEvent.SCROLL, scroll);
        sc.setCursor(javafx.scene.Cursor.NONE);
        sweepCursors(sc.getRoot(), rec.stash);
        if (stg != null) stg.setResizable(false);
        captureWired.add(rec);
    }

    /** Null out every node-level cursor under n, stashing the old
     *  values for the unwire restore (D240 — putIfAbsent keeps the
     *  first stash across re-sweeps). */
    private static void sweepCursors(javafx.scene.Node n,
            java.util.Map<javafx.scene.Node, javafx.scene.Cursor> stash) {
        javafx.scene.Cursor c = n.getCursor();
        if (c != null && c != javafx.scene.Cursor.NONE) {
            stash.putIfAbsent(n, c);
            n.setCursor(javafx.scene.Cursor.NONE);
        }
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node k : p.getChildrenUnmodifiable()) {
                sweepCursors(k, stash);
            }
        }
    }

    /** "Add View" (v18.9; full-parity v18.10): a new complete pane
     *  on the right, dividers re-spread evenly. The pane arrives in
     *  FLY/ORBIT at the SPAWNING pane's perspective pose (v18.11,
     *  user spec — an ortho spawner hands over its saved
     *  perspective pose instead). */
    void addViewPane(Viewport3D from) {
        Viewport3D vp = new Viewport3D(this);
        viewports.add(vp);
        viewSplit.getItems().add(vp.buildPane());
        vp.rebuildMeshes();   // centers the pane's world FIRST — the
                              // scene<->world offsets exist after this
        // new panes CENTER ON THE COASTER (v18.46, D218 — user spec,
        // replacing the D182 spawner-pose copy; that copy was also
        // subtly wrong in SCENE coords whenever the panes' worlds
        // were centered on different documents' midpoints): default
        // orbit angles, distance framing the whole track
        vp.applyCameraWorld(frameCoasterPose());
        vp.startAxesRef();
        int n = viewSplit.getItems().size();
        double[] pos = new double[n - 1];
        for (int i = 1; i < n; i++) pos[i - 1] = (double) i / n;
        viewSplit.setDividerPositions(pos);
        placeCollapseBtn();
    }

    /** WORLD-coordinate orbit pose framing the WHOLE coaster
     *  (v18.46, D218): pivot at the compiled track's bounding
     *  center, the default orbit angles, distance fitting the
     *  bounding radius in the current FOV. Falls back to the
     *  new-document framing before a compile. */
    double[] frameCoasterPose() {
        if (lastSamples == null || lastSamples.isEmpty()) {
            return new double[]{ NEW_START_SPEED * NEW_START_LEN / 2,
                NEW_START_Y, 0, -30, -20, 35 };
        }
        double nx = Double.MAX_VALUE, xx = -Double.MAX_VALUE;
        double ny = Double.MAX_VALUE, xy = -Double.MAX_VALUE;
        double nz = Double.MAX_VALUE, xz = -Double.MAX_VALUE;
        for (FVDIntegrator.Sample s : lastSamples) {
            Vec3 p = s.pos();
            nx = Math.min(nx, p.x()); xx = Math.max(xx, p.x());
            ny = Math.min(ny, p.y()); xy = Math.max(xy, p.y());
            nz = Math.min(nz, p.z()); xz = Math.max(xz, p.z());
        }
        double cx = (nx + xx) / 2, cy = (ny + xy) / 2, cz = (nz + xz) / 2;
        double r = Math.max(5, Math.hypot(Math.hypot(xx - nx, xy - ny),
            xz - nz) / 2);
        // 0.8× the fit-the-sphere distance (v18.48, D220 — user
        // spec "a little bit closer"; coasters are flat-ish, so the
        // bounding sphere overestimates)
        double dist = clamp(
            r / Math.tan(Math.toRadians(settings.flyFov() / 2)) * 0.8 + 5,
            15, Viewport3D.ORTHO_FAR_CLIP * 0.4);
        return new double[]{ cx, cy, cz, -30, -20, dist };
    }

    /** "Remove View": drops THAT pane (any of them — the last one
     *  stays); the SplitPane reflows the rest into the gap. */
    void removeViewPane(Viewport3D vp) {
        if (viewports.size() <= 1) return;
        vp.releaseCapture();
        vp.shutdown();
        int idx = viewports.indexOf(vp);
        if (idx < 0) return;
        viewports.remove(idx);
        viewSplit.getItems().remove(idx);
        if (hoverViewport == vp) hoverViewport = null;
        placeCollapseBtn();
    }

    /** A drawn circle-arrow icon replacing the ↺/↻ FONT glyphs
     *  (v18.11, user report: the glyphs render with a FLAT-bottomed
     *  circle): a fully round arc with only the arrowhead gap at the
     *  top, centered in a size×size canvas. cw = the ↻ direction. */
    static javafx.scene.canvas.Canvas circleArrowIcon(double size,
            boolean cw, Color col) {
        javafx.scene.canvas.Canvas c =
            new javafx.scene.canvas.Canvas(size, size);
        javafx.scene.canvas.GraphicsContext g = c.getGraphicsContext2D();
        if (cw) {   // mirror the CCW drawing
            g.translate(size, 0);
            g.scale(-1, 1);
        }
        double cx = size / 2, cy = size / 2, r = size * 0.32;
        double gap = 70;   // degrees, centered at 12 o'clock
        g.setStroke(col);
        g.setLineWidth(Math.max(1.0, size * 0.075));   // thinner (v18.11b)
        g.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90 + gap / 2,
            360 - gap, javafx.scene.shape.ArcType.OPEN);
        // arrowhead at the CCW arc's end (right edge of the gap),
        // pointing along the counterclockwise tangent
        double th = Math.toRadians(90 - gap / 2);
        double px = cx + r * Math.cos(th), py = cy - r * Math.sin(th);
        double tx = -Math.sin(th), ty = -Math.cos(th);
        double nx = Math.cos(th), ny = -Math.sin(th);
        double ah = size * 0.26, aw = size * 0.24;
        g.setFill(col);
        g.fillPolygon(
            new double[]{ px + tx * ah, px + nx * aw / 2, px - nx * aw / 2 },
            new double[]{ py + ty * ah, py + ny * aw / 2, py - ny * aw / 2 },
            3);
        return c;
    }

    /** The standard dark-on-light reset icon most dialogs use. */
    static javafx.scene.canvas.Canvas resetIcon() {
        return circleArrowIcon(13, false, Color.rgb(55, 58, 66));
    }

    /** A horizontal double-headed arrow in the circle-arrow icons'
     *  style (v18.11 — the tangent Reverse View button's ↔ glyph
     *  clashed with the drawn refresh icon beside it). */
    static javafx.scene.canvas.Canvas doubleArrowIcon(double size,
            Color col) {
        javafx.scene.canvas.Canvas c =
            new javafx.scene.canvas.Canvas(size, size);
        javafx.scene.canvas.GraphicsContext g = c.getGraphicsContext2D();
        double cy = size / 2, x0 = size * 0.12, x1 = size * 0.88;
        double ah = size * 0.24, aw = size * 0.22;
        g.setStroke(col);
        g.setLineWidth(Math.max(1.0, size * 0.075));
        g.strokeLine(x0 + ah * 0.6, cy, x1 - ah * 0.6, cy);
        g.setFill(col);
        g.fillPolygon(new double[]{ x0, x0 + ah, x0 + ah },
                      new double[]{ cy, cy - aw / 2, cy + aw / 2 }, 3);
        g.fillPolygon(new double[]{ x1, x1 - ah, x1 - ah },
                      new double[]{ cy, cy - aw / 2, cy + aw / 2 }, 3);
        return c;
    }

    /** The graph collapse arrow always lives in the RIGHTMOST pane
     *  (v18.10, user spec). */
    private Button collapseBtn;

    void placeCollapseBtn() {
        if (collapseBtn == null || viewports.isEmpty()) return;
        for (Viewport3D vp : viewports) {
            if (vp.pane != null) vp.pane.getChildren().remove(collapseBtn);
        }
        StackPane right = viewports.get(viewports.size() - 1).pane;
        if (right != null) right.getChildren().add(collapseBtn);
    }
    // (the View menu's gizmo axis dots are GONE, v18.16 D187 — the
    // per-pane top-right button is the whole surface)
    // (viewPovRevCheck is GONE, v17.11 — the View menu row was
    // removed on user spec; A/D in captured POV + the bottom-left
    // "Backwards POV (A)" tag are the whole surface)

    // COASTER STATISTICS (v17.11, user spec — the Coaster menu's
    // submenu rows read these; Viewport3D.rebuildTrack stashes them)
    double statRideTime, statTrackLen, statMinY, statMaxY,
           statTopV, statMinGv, statMaxGv;
    // rail-mode radios, VIEW-menu residents since v16.14 (Dialogs
    // builds and assigns them; setRailMode/loads re-sync as ever)
    RadioButton gradientModeItem, solidModeItem;
    // the settings rows (v19.3, D232 — they LEFT the Coaster menu for
    // the Coaster Options / Rail Settings dialogs; the row objects
    // survive here and re-parent per dialog open)
    HBox heartRow, gaugeRow, tieRow, fricRollRow, fricAirRow;
    private CheckBox resPitchCheck, resYawCheck, resRollCheck;   // retitled per mode

    /** Every file OPEN (New / Load / the startup Untitled) begins
     *  with the Resulting overlays OFF (v18.10, user spec) — the
     *  picks still stick for the session once re-checked. */
    void clearResultingPicks() {
        settings.setResPitch(false);
        settings.setResYaw(false);
        settings.setResRoll(false);
        if (resPitchCheck != null) resPitchCheck.setSelected(false);
        if (resYawCheck != null) resYawCheck.setSelected(false);
        if (resRollCheck != null) resRollCheck.setSelected(false);
        tintResChecks();
        if (editor != null) editor.refresh();
    }

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

        // (The v16.15 custom-color Window watcher is GONE, v16.16:
        // no amount of refocusing beat the OS activation race — the
        // built-in Custom Color window is owned by the picker's
        // long-hidden POPUP. Every ColorPicker was replaced by a
        // swatch + Dialogs.pickColor, an ordinary OWNED window that
        // returns focus like any other dialog in the app.)

        // 3D viewport (cameras, meshes, grid, markers): Viewport3D.java
        // the FIRST view pane (v18.10: panes are full Viewport3D
        // instances in `viewports`; buildPane() assembles each pane's
        // SubScene + overlays with min sizes zeroed — the old
        // subHolder rules live there now)
        viewports.add(new Viewport3D(this));
        dialogs = new Dialogs(this);

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
            // grayed = explained on hover (v19.6, D235): the reason
            // re-resolves live (s/c / bridge / influence roll)
            final int bi = i;
            toolbar.getChildren().add(GraphEditor.grayHint(b,
                () -> editor.laneDisableWhy(bi)));
        }
        laneButtons[0].setSelected(true);

        Label rippleLbl = new Label("Ripple:");
        rippleLbl.setTextFill(Color.rgb(200, 205, 215));
        HBox rippleTCheck = makeCheck("time", v -> rippleT = v);
        HBox rippleVCheck = makeCheck("value", v -> rippleV = v);
        // the horizontal ripple relabels with the plot's axis domain
        // (v18.0, D162 — user spec: "distance" over a meters axis);
        // the check's label is its second child (makeCheck's layout)
        rippleTLabel = (Label) rippleTCheck.getChildren().get(1);
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
            tintResChecks();
            editor.refresh();
        });
        resYawCheck = new CheckBox("Yaw rate (°/s)");
        resYawCheck.setSelected(settings.resYaw());
        resYawCheck.setStyle("-fx-text-fill: rgb(210,215,225);");
        resYawCheck.setOnAction(e -> {
            settings.setResYaw(resYawCheck.isSelected());
            tintResChecks();
            editor.refresh();
        });
        // ROLL/BANK overlay (v17.6, D143): force view only — over a
        // geo section the roll lane already IS the shared quantity
        resRollCheck = new CheckBox("Roll rate (°/s)");
        resRollCheck.setSelected(settings.resRoll());
        resRollCheck.setStyle("-fx-text-fill: rgb(210,215,225);");
        resRollCheck.setOnAction(e -> {
            settings.setResRoll(resRollCheck.isSelected());
            tintResChecks();
            editor.refresh();
        });
        Button resSet = new Button("Resulting graph settings…");
        resSet.setFocusTraversable(false);
        resSet.setStyle("-fx-font-size: 11px;");
        resSet.setOnAction(e -> editor.showResultingSettings());
        // opening any file starts with NO overlays (v18.10, user
        // spec) — the startup document counts as an open too
        clearResultingPicks();
        VBox resBox = new VBox(8, resPitchCheck, resYawCheck,
            GraphEditor.grayHint(resRollCheck,
                "Duplicates the active section's own roll lane."),
            resSet);
        resBox.setPadding(new Insets(10, 16, 10, 12));
        resBox.setStyle("-fx-background-color: rgb(38,42,53);"
            + " -fx-background-radius: 6;"
            + " -fx-border-color: rgb(95,102,120); -fx-border-radius: 6;");
        Popup resPopup = new Popup();
        resPopup.getContent().add(resBox);
        resPopup.setAutoHide(true);   // backstop: clicks elsewhere close it
        resPopup.setOnShown(e ->
            installViewKeys(resPopup.getScene()));   // v18.1: keep riding
        // hover dismissal, verified against the REAL cursor: when the
        // popup materializes under an already-moving mouse, JavaFX can
        // skip MOUSE_ENTERED entirely — the exit-timer from leaving the
        // button then hid the menu mid-hover (v10.4 fix for the "fast
        // click closes it" report). So the timer's finish re-checks the
        // actual pointer via Robot and re-arms instead of hiding while
        // it sits over the popup or the button.
        Robot resRobot = new Robot();
        // MOVABLE (v17.9, D146 — the Track Piece pattern): grab any
        // non-control spot; once dragged, the hover dismiss stands
        // down for that showing (click-off still closes it)
        boolean[] resDragged = { false };
        double[] resGrab = new double[2];
        resBox.setOnMousePressed(e -> {
            resGrab[0] = e.getScreenX() - resPopup.getX();
            resGrab[1] = e.getScreenY() - resPopup.getY();
        });
        resBox.setOnMouseDragged(e -> {
            resDragged[0] = true;
            resPopup.setX(e.getScreenX() - resGrab[0]);
            resPopup.setY(e.getScreenY() - resGrab[1]);
        });
        PauseTransition resHide = new PauseTransition(Duration.millis(250));
        resHide.setOnFinished(e -> {
            if (!resPopup.isShowing() || resDragged[0]) return;
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
                resDragged[0] = false;   // fresh showing (v17.9)
                resPopup.show(resBtn, b.getMinX(), b.getMaxY() - 1);
                tintResChecks();   // skins exist once shown (v17.8)
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
        // (no tooltip — the toolbar dropdowns dropped their hover
        // descriptions in v18.8, user spec)

        // piece selector (v12.0): hidden while the ride is one piece;
        // lists every piece, click to make it the graph's piece
        pieceBtn = new Button("Piece 1 ▾");
        pieceBtn.setFocusTraversable(false);
        pieceBtn.setStyle(btnStyle);
        pieceBtn.setOnAction(e -> {
            // one entry per RUN (D248): ✓ on the ACTIVE run (keyed off
            // runStartOf), the whole run's length, the HEAD's anchor;
            // picking the active run is a no-op (no zoom jump), another
            // run activates its head's section 0 with auto-fit. The
            // wizard's pending piece and an open convert preview gray
            // with their reasons (never a silent click, D235)
            ContextMenu cm = new ContextMenu();
            int ah = coaster.pieces.isEmpty() ? -1
                : coaster.runStartOf(editor.activePiece());
            boolean busy = editor.convertUiActive();
            for (int h : coaster.runHeads()) {
                TrackPiece tp = coaster.pieces.get(h);
                String label = String.format(Locale.US,
                    "%sPiece %d — %s at (%.0f, %.0f, %.0f)",
                    h == ah ? "✓ " : "   ",
                    coaster.runOrdinal(h), runLengthLabel(h), tp.x, tp.y, tp.z);
                MenuItem it;
                if (busy) {
                    it = GraphEditor.grayItem(label, CONVERT_BUSY_MSG);
                } else if (h != ah && isWizardPiece(activeCoaster, h)) {
                    // the OPEN wizard's piece, picked or not (D248
                    // review fix — isPendingPiece is the render gate)
                    it = GraphEditor.grayItem(label, GraphEditor.WIZARD_OPEN_WHY);
                } else {
                    it = new MenuItem(label);
                    final int idx = h;
                    it.setOnAction(ev -> {
                        if (coaster.pieces.isEmpty()) return;
                        if (idx == coaster.runStartOf(editor.activePiece())) {
                            return;   // already the active run
                        }
                        editor.setActivePiece(idx);
                    });
                }
                cm.getItems().add(it);
            }
            cm.show(pieceBtn, javafx.geometry.Side.BOTTOM, 0, 0);
        });
        updatePieceButton();

        // the Track Piece WINDOW's button (v13.2 — moved here from the
        // Coaster menu per user; a LiveWindow Stage since v19.19, D248:
        // "Track Piece ▴" ALWAYS — the RUN HEAD's anchor + the active
        // piece's orientation; the bridge rows moved to the junction
        // button's "Strict Seam n (bridge) ▴" window — the v13.10
        // relabel is gone). The click TOGGLES the window.
        placementBtn = new Button(SectionNames.TRACK_PIECE_BUTTON);
        placementBtn.setFocusTraversable(false);
        placementBtn.setStyle(btnStyle);
        placementBtn.setOnAction(e -> showPlacementPopup());
        // the JUNCTION button (v19.18, D247 "Strict Seam ▾"; D248: ONE
        // of "Strict Seam n ▴" / "Strict Seam n (bridge) ▴", see
        // syncJunctionButton) — the seam line's menu, the badge menu
        // and the balls' double-clicks remain the other routes
        strictSeamBtn = new Button(SectionNames.STRICT_SEAM.trim()
            + SectionNames.OWNER_ARROW);
        strictSeamBtn.setFocusTraversable(false);
        strictSeamBtn.setStyle(btnStyle);
        strictSeamBtn.setOnAction(e -> toggleJunctionWindow());
        // the grayHint WRAPPER is the toolbar child — visibility toggles
        // on it (an unmanaged button inside a managed wrapper would
        // still cost the toolbar's spacing), the wizard / orphan
        // disable on the button (the wrapper carries the live reason)
        strictSeamWrap = GraphEditor.grayHint(strictSeamBtn,
            () -> junctionGray == null ? "" : junctionGray);
        strictSeamWrap.setVisible(false);
        strictSeamWrap.setManaged(false);

        // bridge LENGTH spinner (v13.2, length semantics v13.4): shown
        // while a bridge with a live target is active; every change
        // re-runs the placement optimization at that length
        bridgeGapLbl = new Label("Length (m)");
        bridgeGapLbl.setStyle("-fx-text-fill: rgb(200,205,215);");
        bridgeGapSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 1000, 20, 1));
        bridgeGapSpinner.setPrefWidth(80);
        bridgeGapSpinner.setFocusTraversable(false);
        // numeric-only editor (v13.4): ride/fly movement keys are handled
        // by scene FILTERS, but the focused editor still received their
        // KEY_TYPED characters — letters kept appearing in the box.
        // typable = the same rule + focus-loss commit (v18.20, D191)
        typable(bridgeGapSpinner, false);
        bridgeGapSpinner.valueProperty().addListener((obs, o, v) -> {
            if (docApplying || v == null) return;
            if (convertBusy()) { revertSpinner(bridgeGapSpinner, o); return; }   // D247
            bridgeCall(() -> editor.setBridgeGap(v));   // the window's bridge (D248)
        });
        Tooltip.install(bridgeGapSpinner, new Tooltip(
            "Bridge length along the track — each change slides the\n"
            + "next piece along the natural continuation and re-solves."));
        updateBridgeControls();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // (the toolbar "POV" button LEFT in v19.8, D237 — redundant
        // with Space and the view-label dropdown, user spec)

        // just "Append" (v11.1): it appends whatever the active section
        // authors — seconds on lane sections, ELEMENTS on straight/curve.
        // The "(A)" shortcut tag + short tooltip are D241 (user spec).
        Button appendBtn = new Button(
            "Append (" + settings.shortcut("append") + ")");
        appendBtn.setFocusTraversable(false);
        appendBtn.setStyle(btnStyle);
        appendBtn.setOnAction(e -> editor.appendAction());
        Tooltip.install(appendBtn, new Tooltip(
            "Extend the section — 1 s (distance: 10 m), as a flat hold\n"
            + "after the selected node; s/c: append a 10 m straight."));

        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(rippleLbl, rippleTCheck, rippleVCheck,
                                     resBtn, pieceBtn,
                                     GraphEditor.grayHint(placementBtn,
                                         "Placement is edited in the start"
                                         + " screen while it is open."),
                                     strictSeamWrap,
                                     spacer, appendBtn);

        MenuBar menuBar = buildMenuBar();

        statsLabel.setTextFill(Color.rgb(200, 205, 215));
        statsLabel.setFont(STATS_FONT);
        statsLabel.setPadding(new Insets(4, 8, 4, 8));
        statsLabel.setStyle("-fx-background-color: rgb(25,28,36);");
        statsLabel.setMaxWidth(Double.MAX_VALUE);
        // FIXED-SLOT POV row (v19.10, D239 — user spec: the readouts
        // must not jump around horizontally): the POV stats render
        // into per-field labels with slot widths pre-measured from
        // worst-case templates, stacked over the plain statsLabel.
        // Any message written to statsLabel (refusal reasons, save/
        // load lines) reclaims the bar until the next POV update.
        buildPovRow();
        StackPane statsBar = new StackPane(povRow, statsLabel);
        statsBar.setStyle("-fx-background-color: rgb(25,28,36);");
        statsBar.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(povRow, Pos.CENTER_LEFT);
        StackPane.setAlignment(statsLabel, Pos.CENTER_LEFT);
        statsLabel.textProperty().addListener((o, a, b2) -> {
            statsLabel.setVisible(true);
            povRow.setVisible(false);
        });

        BorderPane bottom = new BorderPane();
        bottom.setTop(new VBox(statsBar, toolbar));
        bottom.setCenter(editor);
        bottom.setMinHeight(0);       // allow the divider to crush it fully
        editor.setMinHeight(0);

        // collapse/restore toggle lives ON the 3D view so it stays
        // reachable when the graph panel is fully minimized — in the
        // RIGHTMOST pane since v18.10 (placeCollapseBtn)
        collapseBtn = new Button("▼");
        collapseBtn.setFocusTraversable(false);
        collapseBtn.setStyle("-fx-background-color: rgba(45,50,62,0.75); -fx-text-fill: rgb(210,215,225);");
        StackPane.setAlignment(collapseBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(collapseBtn, new Insets(8));
        collapseBtn.setOnAction(e -> toggleGraphPanel());
        // (the top-right gizmo-AXES overlay toggle is GONE, v18.10
        // D181 — the View menu's inline Local/Global dots remain)

        // VIEW PANES (v18.9; FULL PARITY v18.10, D181): the 3D area
        // is a horizontal SplitPane of complete Viewport3D panes —
        // each assembles its own overlays in buildPane()
        viewSplit = new SplitPane(viewports.get(0).buildPane());
        viewports.get(0).startAxesRef();
        viewSplit.setOrientation(Orientation.HORIZONTAL);
        viewSplit.setMinSize(0, 0);
        placeCollapseBtn();
        mainSplit = new SplitPane(viewSplit, bottom);
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.setDividerPositions(lastDividerPos);
        mainSplit.getDividers().get(0).positionProperty().addListener(
            (obs, o, v) -> {
                collapseBtn.setText(v.doubleValue() > 0.97 ? "▲" : "▼");
                // expanding out of the collapsed state repaints what
                // the unloaded editor skipped (v18.36, D208)
                if (o.doubleValue() > 0.97 && v.doubleValue() <= 0.97
                        && editor != null) {
                    editor.refresh();
                }
            });

        BorderPane layout = new BorderPane();
        layout.setTop(menuBar);
        layout.setCenter(mainSplit);

        rebuildTrack();
        // the STARTUP view centers on the coaster too (v18.48, D220
        // — the default view was the never-centered pivot-at-origin
        // rig; loads still restore their saved camera on top)
        if (!viewports.isEmpty()) {
            viewports.get(0).applyCameraWorld(frameCoasterPose());
        }

        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        double w = Math.min(1400, vb.getWidth() * 0.95);
        double h = Math.min(880, vb.getHeight() * 0.90);

        Scene scene = new Scene(layout, w, h, true);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            for (Viewport3D vp : viewports) {
                if (vp.flyActive || vp.povActive) vp.moveKeys.remove(e.getCode());
            }
            if (e.getCode() == KeyCode.S) slideKey = false;
            if (e.getCode() == KeyCode.C) cKey = false;
            if (e.getCode() == KeyCode.SHIFT) editor.shiftHover(false);
            if (e.getCode() == KeyCode.CONTROL) editor.ctrlHover(false);
            // UNIVERSAL key-burst end (D200 + its verify round): any
            // key release while an edit-key gesture is hot settles
            // the document — UNLESS a pointer gesture is still live
            // (a stray arrow release mid-drag must neither settle
            // mid-gesture nor un-hot the rest of the drag)
            if (gestureHot && !pointerDown) {
                gestureHot = false;
                settleIfPending();
            }
        });
        // an edit-key burst whose release lands in another window
        // (Alt+Tab, a modeless panel) never reaches the filter above
        // — the focus edge is the backstop, or gestureHot would stay
        // stuck and every later rebuild would run fast-only (D200
        // verify round)
        stage.focusedProperty().addListener((o, was, is) -> {
            if (!is) {
                // commit any half-typed numeric editor BEFORE the
                // settle reads the document (v18.40, D212 — the
                // window-deactivation leg of the blank-spinner fix)
                commitStrayNumericEditors(null, null);
                pointerDown = false;
                gestureHot = false;
                settleIfPending();
            }
        });
        updateTitle();   // "Untitled — Ultimate Vector Design" until saved/loaded
        // closing the app runs the SAME unsaved-changes gate as New/
        // Load (v19.6, D235 — user spec): Save/Don't Save close,
        // Cancel keeps the app open
        stage.setOnCloseRequest(e -> {
            if (!confirmSaveDiscard()) e.consume();
        });
        stage.setScene(scene);
        stage.show();
        stage.centerOnScreen();
        devShots();
        devMenu();
        gridShots();
        gridSweep();
        winShots();
        pickProbe();
    }

    /** DEV HOOK (v19.19, D248): `-Dfvd.pickprobe=1` — drive the
     *  primary pane's SCREEN-SPACE gizmo picker on the default
     *  document (Viewport3D.pickProbe: registration, pick bands, a
     *  drag-reveal + a ring drag through synthetic mouse events on
     *  the pane filter). Prints PICKPROBE lines and exits (0 = all
     *  green). Inert without the property. */
    private void pickProbe() {
        if (System.getProperty("fvd.pickprobe") == null) return;
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.out.println("PICKPROBE-EX " + ex);
            ex.printStackTrace();
        });
        devAfter(900, () -> {
            int fails;
            try {
                fails = viewports.get(0).pickProbe(System.out);
            } catch (Exception ex) {
                System.out.println("PICKPROBE-EX " + ex);
                ex.printStackTrace();
                fails = 99;
            }
            System.out.println("PICKPROBE fails=" + fails);
            System.exit(fails == 0 ? 0 : 1);
        });
    }

    /** DEV HOOK (v19.5): `-Dfvd.devmenu=1` — Robot-drive the Coaster
     *  menu end to end: press the menu-bar button, verify the popup
     *  opened, press the coaster ChoiceBox, verify ITS dropdown
     *  opened, pick the second coaster via DOWN/ENTER and verify the
     *  switch. Prints one MENUTEST line and exits (0 = all green).
     *  Inert without the property. */
    ChoiceBox<String> devMenuPick;   // the live selector (rebuilt per showing)

    private void devMenu() {
        if (System.getProperty("fvd.devmenu") == null) return;
        Coaster c2 = new Coaster();
        c2.name = "Second";
        TrackPiece tp = new TrackPiece(0, 21, 0, 0);
        tp.sections.add(Coaster.holdSection(1, 0, 0, 26));
        c2.pieces.add(tp);
        coasters.add(c2);
        stage.setAlwaysOnTop(true);
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.out.println("MENUTEST-EX " + ex);
            ex.printStackTrace();
        });
        javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
        boolean[] menuOpen = new boolean[1];
        boolean[] choiceOpen = new boolean[1];
        devAfter(400, () -> {
            stage.requestFocus();
            // Menu.getStyleableNode() is null on a MenuBar menu —
            // find the real MenuBarButton by its label instead
            javafx.scene.Node btn = null;
            for (javafx.scene.Node n
                    : stage.getScene().getRoot().lookupAll(".menu-button")) {
                if (n instanceof javafx.scene.control.Labeled lb
                        && "Coaster".equals(lb.getText())) {
                    btn = n;
                    break;
                }
            }
            System.out.println("MENUTEST-DBG btn=" + btn
                + " bounds=" + (btn == null ? null
                    : btn.localToScreen(btn.getBoundsInLocal()))
                + " focused=" + stage.isFocused()
                + " items=" + coasterMenu.getItems().size());
            devClick(robot, btn);
            devAfter(500, () -> {
                menuOpen[0] = coasterMenu.isShowing();
                System.out.println("MENUTEST-DBG afterClick showing="
                    + menuOpen[0] + " focused=" + stage.isFocused()
                    + " mouse=" + robot.getMousePosition());
                if (!menuOpen[0]) coasterMenu.show();   // structural probe
                devAfter(300, () -> {
                if (devMenuPick != null) devClick(robot, devMenuPick);
                devAfter(500, () -> {
                    choiceOpen[0] = devMenuPick != null
                        && devMenuPick.isShowing();
                    System.out.println("MENUTEST-DBG pick=" + devMenuPick
                        + " open=" + choiceOpen[0]
                        + " menuStill=" + coasterMenu.isShowing());
                    if (stage.isFocused() || choiceOpen[0]) {
                        robot.keyType(KeyCode.DOWN);
                        robot.keyType(KeyCode.ENTER);
                    }
                    devAfter(600, () -> {
                        boolean switched = activeCoaster == 1;
                        System.out.println("MENUTEST menu=" + menuOpen[0]
                            + " choice=" + choiceOpen[0]
                            + " switched=" + switched);
                        System.exit(menuOpen[0] && choiceOpen[0]
                            && switched ? 0 : 1);
                    });
                });
                });
            });
        });
    }

    private static void devAfter(double ms, Runnable r) {
        javafx.animation.PauseTransition p = new javafx.animation
            .PauseTransition(javafx.util.Duration.millis(ms));
        p.setOnFinished(e -> r.run());
        p.play();
    }

    private static void devClick(javafx.scene.robot.Robot robot,
                                 javafx.scene.Node n) {
        if (n == null) return;
        javafx.geometry.Bounds b = n.localToScreen(n.getBoundsInLocal());
        if (b == null) return;
        robot.mouseMove(b.getMinX() + b.getWidth() / 2,
            b.getMinY() + b.getHeight() / 2);
        robot.mouseClick(javafx.scene.input.MouseButton.PRIMARY);
    }

    /** DEV HOOK (v19.1): `-Dfvd.devshot=<dir>` renders the START
     *  SCREEN's three states (pick / lane form / s-c form) to PNGs in
     *  <dir> straight from JavaFX and exits — a headless-ish UI check
     *  for displays too small to show the graph panel. Inert without
     *  the property. */
    private void devShots() {
        String dir = System.getProperty("fvd.devshot");
        if (dir == null || dir.isBlank()) return;
        java.io.File d = new java.io.File(dir);
        d.mkdirs();
        // the wizard opens on the REAL document (a new coaster); the
        // panel object is re-parented into the shot stage
        openNewCoasterWizard(true);
        editor.hideStartPanel();
        StartPanel[] panel = { new StartPanel(this, wizardState) };
        Stage s = new Stage();
        s.setTitle("devshot");
        Scene sc = new Scene(panel[0], 1180, 320);
        s.setScene(sc);
        s.show();
        java.util.function.BiConsumer<String, Runnable> snap = (name, next) -> {
            javafx.application.Platform.runLater(() ->
                javafx.application.Platform.runLater(() -> {
                    try {
                        javafx.scene.image.WritableImage img = sc.getRoot().snapshot(null, null);
                        int w = (int) img.getWidth(), h = (int) img.getHeight();
                        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                            w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        javafx.scene.image.PixelReader pr = img.getPixelReader();
                        for (int yy = 0; yy < h; yy++) {
                            for (int xx = 0; xx < w; xx++) {
                                bi.setRGB(xx, yy, pr.getArgb(xx, yy));
                            }
                        }
                        javax.imageio.ImageIO.write(bi, "png",
                            new java.io.File(d, name + ".png"));
                    } catch (Exception ex) {
                        System.err.println("devshot: " + ex);
                    }
                    next.run();
                }));
        };
        snap.accept("start-pick", () -> {
            panel[0].devPick(false);
            snap.accept("start-lane", () -> {
                panel[0].devPick(true);
                snap.accept("start-sc", () -> {
                    javafx.application.Platform.exit();
                    System.exit(0);
                });
            });
        });
    }

    /** DEV HOOK (v19.19, D248): `-Dfvd.winshot=<dir>` builds a
     *  three-junction fixture on the live document (head piece with
     *  two force sections → strict seam → follower ending in an
     *  OPT_POS bridge → target piece), opens each of the three
     *  placement WINDOWS in turn, snapshots its scene to a PNG in
     *  <dir> (win-trackpiece / win-seam / win-bridge, + the toolbar
     *  per activation as toolbar-*.png), prints the junction button's
     *  label per activation and exits — the one way to see the
     *  windows on a display too small for the graph panel. Inert
     *  without the property. */
    private void winShots() {
        String dir = System.getProperty("fvd.winshot");
        if (dir == null || dir.isBlank()) return;
        java.io.File d = new java.io.File(dir);
        d.mkdirs();
        // ---- fixture: a second (flat) section on piece 0, split into
        // a strict seam, a floating target and an OPT_POS bridge
        TrackPiece p0 = coaster.pieces.get(0);
        List<List<BezierBaker.Node>> flat = new ArrayList<>();
        for (int l = 0; l < 3; l++) {
            List<BezierBaker.Node> ln = new ArrayList<>();
            ln.add(new BezierBaker.Node(0, l == 0 ? 1 : 0));
            ln.add(new BezierBaker.Node(4, l == 0 ? 1 : 0));
            flat.add(ln);
        }
        p0.sections.add(new ForceSection(flat, p0.sections.get(0).vDesign()));
        rebuildTrack();
        int f = coaster.makeSeamStrict(0, 1, INTEGRATE_DT);
        rebuildTrack();
        TrackPiece t = new TrackPiece(p0.x + 60, p0.y - 8, p0.z + 25, 30);
        List<List<BezierBaker.Node>> flat2 = new ArrayList<>();
        for (int l = 0; l < 3; l++) {
            List<BezierBaker.Node> ln = new ArrayList<>();
            ln.add(new BezierBaker.Node(0, l == 0 ? 1 : 0));
            ln.add(new BezierBaker.Node(3, l == 0 ? 1 : 0));
            flat2.add(ln);
        }
        t.sections.add(new ForceSection(flat2, 24));
        coaster.pieces.add(t);
        TrackCompiler.Result r0 = TrackCompiler.compile(coaster, INTEGRATE_DT);
        TrackPiece fp = coaster.pieces.get(f);
        int spanIdx = p0.sections.size() + fp.sections.size() - 1;
        BridgeSection bs = BridgeSection.solve(r0.spans().get(spanIdx).exit(),
            t.entryPose(), r0.samples().get(r0.spans().get(spanIdx).i1()).v());
        bs.optMode = BridgeSection.OPT_POS;
        fp.sections.add(bs);
        coaster.optimizeBridge(bs, f + 1, 30, false);
        editor.setActivePiece(0);
        rebuildTrack();
        editor.refresh();
        java.util.function.BiConsumer<Stage, String> snapWin = (w, name) -> {
            try {
                if (w == null || w.getScene() == null) {
                    System.err.println("winshot: no window for " + name);
                    return;
                }
                javafx.scene.image.WritableImage img =
                    w.getScene().getRoot().snapshot(null, null);
                int wd = (int) img.getWidth(), ht = (int) img.getHeight();
                java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                    wd, ht, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                javafx.scene.image.PixelReader pr = img.getPixelReader();
                for (int yy = 0; yy < ht; yy++) {
                    for (int xx = 0; xx < wd; xx++) {
                        bi.setRGB(xx, yy, pr.getArgb(xx, yy));
                    }
                }
                javax.imageio.ImageIO.write(bi, "png",
                    new java.io.File(d, name + ".png"));
                System.err.println("winshot: " + name + " " + wd + "x" + ht
                    + " title=\"" + w.getTitle() + "\" at " + (int) w.getX()
                    + "," + (int) w.getY());
            } catch (Exception ex) {
                System.err.println("winshot: " + name + " " + ex);
            }
        };
        Runnable toolbarState = () -> System.err.println("winshot: active "
            + editor.activePiece() + "/" + editor.activeSection()
            + " placementBtn=\"" + placementBtn.getText() + "\" junction="
            + (strictSeamWrap.isVisible() ? "\"" + strictSeamBtn.getText()
                + "\"" + (strictSeamBtn.isDisabled() ? " (grayed: "
                + junctionGray + ")" : "") : "hidden"));
        java.util.function.Consumer<Runnable> later = r ->
            javafx.application.Platform.runLater(() ->
                javafx.application.Platform.runLater(r));
        later.accept(() -> {
            toolbarState.run();
            openTrackPieceWindow();
            later.accept(() -> {
                snapWin.accept(trackPieceWin, "win-trackpiece");
                closeTrackPieceWindow();
                editor.setActivePiece(f);
                toolbarState.run();
                editor.showStrictSeamSettings(f);
                later.accept(() -> {
                    snapWin.accept(editor.seamWindow(), "win-seam");
                    editor.hideSeamPopup();
                    editor.activatePieceSection(f, fp.sections.size() - 1);
                    toolbarState.run();
                    openBridgeWindow(f);
                    later.accept(() -> later.accept(() -> {
                        // the Labeled clip probe (D248): every radio /
                        // check must render its FULL text
                        for (javafx.scene.control.Labeled lb :
                                new javafx.scene.control.Labeled[]{ bridgeModePY,
                                    bridgeModeP, bridgeModeN, bridgeOptLenCheck,
                                    brFlags.indep, brFlags.realR,
                                    brFlags.designR, brFlags.affect }) {
                            javafx.scene.Node tx = lb.lookup(".text");
                            String shown = tx == null ? "none"
                                : ((javafx.scene.text.Text) tx).getText();
                            System.err.println("winshot: labeled \"" + lb.getText()
                                + "\" shows \"" + shown + "\""
                                + (shown.equals(lb.getText()) ? "" : "  CLIPPED"));
                        }
                        snapWin.accept(bridgeWin, "win-bridge");
                        closeBridgeWindow();
                        editor.setActivePiece(f + 1);
                        toolbarState.run();
                        javafx.application.Platform.exit();
                        System.exit(0);
                    }));
                });
            });
        });
    }

    /** DEV HOOK (v19.13, D242): `-Dfvd.gridshot=<dir>` walks the
     *  primary viewport through a ladder of camera poses over the
     *  startup layout — low grazing, mid orbit, high, very high
     *  (base-decade flips), a boundary straddle, and the top-ortho
     *  lens — snapshotting the pane to PNGs in <dir>, then exits.
     *  The floor grid's whole envelope in one headless-ish run.
     *  SECOND HALF since v19.19 (D248 — the see-through floor): the
     *  demo piece is dropped so its lowest heartline point sits at
     *  y = −15 (a placement move, bitwise-rigid) and five poses
     *  look at the buried valley from above, at grazing height,
     *  from BELOW ground, far away and through the top-ortho lens;
     *  a last pose cranks the rolling friction until the true march
     *  stalls mid-ride so the GHOST track renders over the floor.
     *  Inert without the property. */
    private void gridShots() {
        String dir = System.getProperty("fvd.gridshot");
        if (dir == null || dir.isBlank()) return;
        java.io.File d = new java.io.File(dir);
        d.mkdirs();
        Viewport3D vp = viewports.get(0);
        // {name, pivotX, pivotY, pivotZ, yaw, pitch, dist} + "ortho";
        // "under-" poses (D248) are RELATIVE to the fixture's lowest
        // sample in x/z (pivot y absolute)
        Object[][] poses = {
            { "low-graze",  0.0, 5.0, 0.0, 25.0, -6.0, 40.0 },
            { "mid-orbit",  0.0, 15.0, 0.0, 40.0, -25.0, 160.0 },
            { "high",       0.0, 20.0, 0.0, 10.0, -45.0, 700.0 },
            { "very-high",  0.0, 20.0, 0.0, 10.0, -55.0, 3000.0 },
            { "base-below", 0.0, 0.0, 0.0, 30.0, -90.0, 480.0 },
            { "base-above", 0.0, 0.0, 0.0, 30.0, -90.0, 560.0 },
            { "pov-height", 0.0, 2.0, 0.0, 70.0, -3.0, 12.0 },
            { "ortho-top",  0.0, 0.0, 0.0, 0.0, 0.0, 300.0 },
            { "ortho-far",  0.0, 0.0, 0.0, 0.0, 0.0, 3000.0 },
            // eye ≈ 24 m up over the buried valley
            { "under-above", 0.0, -6.0, 0.0, 40.0, -30.0, 60.0 },
            // eye ≈ 0.7 m: the sheet nearly edge-on
            { "under-graze", 0.0, 0.0, 0.0, 30.0, -1.0, 40.0 },
            // eye ≈ −25 m looking UP through the floor
            { "under-below", 0.0, -8.0, 0.0, 40.0, 25.0, 40.0 },
            // the lift-cap region: majors + axes at range
            { "under-far",   0.0, -6.0, 0.0, 10.0, -45.0, 700.0 },
            // the sheet vs the track under the parallel lens
            { "under-ortho-top", 0.0, 0.0, 0.0, 0.0, 0.0, 300.0 },
            // ghost (stalled) track over the floor
            { "under-ghost", 0.0, -6.0, 0.0, 40.0, -30.0, 60.0 },
        };
        double[] low = { 0, 0 };            // lowest sample x / z
        Runnable[] prep = new Runnable[poses.length];
        prep[9] = () -> {
            // DEV-ONLY direct writes (no undo, the run exits after)
            TrackPiece p0 = coaster.pieces.get(0);
            p0.y -= (statMinY + 15);
            rebuildTrack();
            FVDIntegrator.Sample lo = lastSamples.get(0);
            for (FVDIntegrator.Sample sm : lastSamples) {
                if (sm.pos().y() < lo.pos().y()) lo = sm;
            }
            low[0] = lo.pos().x();
            low[1] = lo.pos().z();
        };
        prep[14] = () -> {
            coaster.fricRoll = 0.15;   // stalls the true march mid-ride
            rebuildTrack();
        };
        java.util.concurrent.atomic.AtomicInteger step =
            new java.util.concurrent.atomic.AtomicInteger();
        Runnable[] adv = { null };
        adv[0] = () -> {
            int i = step.getAndIncrement();
            if (i >= poses.length) {
                javafx.application.Platform.exit();
                System.exit(0);
                return;
            }
            if (prep[i] != null) prep[i].run();
            Object[] p = poses[i];
            String name = (String) p[0];
            boolean ortho = name.contains("ortho");
            boolean under = name.startsWith("under");
            // set the PERSPECTIVE pose first, then enter the ortho
            // lens — orthoEnter derives its zoom from the live pose
            if (vp.orthoView) vp.exitOrthoRestorePose();
            vp.applyCameraWorld(new double[]{
                (Double) p[1] + (under ? low[0] : 0), (Double) p[2],
                (Double) p[3] + (under ? low[1] : 0),
                (Double) p[4], (Double) p[5], (Double) p[6] });
            if (ortho) vp.orthoEnterTop();
            // let a few timer frames land the alphas/rebuilds
            javafx.animation.PauseTransition pt =
                new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(400));
            pt.setOnFinished(ev -> {
                try {
                    javafx.scene.image.WritableImage img =
                        vp.pane.snapshot(null, null);
                    int w = (int) img.getWidth(), h = (int) img.getHeight();
                    java.awt.image.BufferedImage bi =
                        new java.awt.image.BufferedImage(w, h,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    javafx.scene.image.PixelReader pr = img.getPixelReader();
                    for (int yy = 0; yy < h; yy++) {
                        for (int xx = 0; xx < w; xx++) {
                            bi.setRGB(xx, yy, pr.getArgb(xx, yy));
                        }
                    }
                    javax.imageio.ImageIO.write(bi, "png",
                        new java.io.File(d, String.format(
                            "grid-%02d-%s.png", i, name)));
                } catch (Exception ex) {
                    System.err.println("gridshot: " + ex);
                }
                adv[0].run();
            });
            pt.play();
        };
        javafx.application.Platform.runLater(adv[0]);
    }

    /** DEV HOOK (v19.20, D249): `-Dfvd.gridshot.sweep=<dir>` — the
     *  gridshot ladder's TEMPORAL twin. The D248 depth-tested floor
     *  flickered only while the camera MOVED and lost its grid at
     *  particular REST poses — what 15 static stills could never
     *  show. Three sequences over the startup layout, one PNG per
     *  frame plus a printed LINE-PIXEL COUNT (pixels of the lower
     *  frame half whose luminance exceeds that half's median by
     *  > 0.08 — a coplanar layer dropped by a depth test reads as a
     *  large swing between consecutive frames; the D246 sub-pixel
     *  phase only drifts): s1 a low eye (≈ 2.6 m) panning 0.25 m per
     *  frame along x; s2 a grazing eye (≈ 30 m, the 10 m decade
     *  dominant) yawing 1° per frame, then nine REST stills (pitch
     *  −5/−8/−12 × yaw 10/20/35 — the "stopped and the grid is gone"
     *  family); s3 the ladder's pov-height and low-graze poses as
     *  0.1 m micro-pans. Each sequence ends with its max
     *  consecutive-frame ratio. Inert without the property. */
    private void gridSweep() {
        String dir = System.getProperty("fvd.gridshot.sweep");
        if (dir == null || dir.isBlank()) return;
        java.io.File d = new java.io.File(dir);
        d.mkdirs();
        Viewport3D vp = viewports.get(0);
        // {sequence, pivotX, pivotY, pivotZ, yaw, pitch, dist}
        List<Object[]> frames = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            frames.add(new Object[] { "s1-panlow", 0.25 * i, 2.0, 0.0,
                0.0, -3.0, 12.0 });
        }
        for (int i = 0; i < 24; i++) {
            frames.add(new Object[] { "s2-yawgraze", 0.0, 0.0, 0.0,
                10.0 + i, -8.0, 220.0 });
        }
        for (double pitch : new double[] { -5, -8, -12 }) {
            for (double yaw : new double[] { 10, 20, 35 }) {
                frames.add(new Object[] { String.format(Locale.US,
                    "s2-still-p%.0f-y%.0f", -pitch, yaw),
                    0.0, 0.0, 0.0, yaw, pitch, 220.0 });
            }
        }
        for (int i = 0; i < 8; i++) {
            frames.add(new Object[] { "s3-panpov", 0.1 * i, 2.0, 0.0,
                70.0, -3.0, 12.0 });
        }
        for (int i = 0; i < 8; i++) {
            frames.add(new Object[] { "s3-pangraze", 0.1 * i, 5.0, 0.0,
                25.0, -6.0, 40.0 });
        }
        java.util.concurrent.atomic.AtomicInteger step =
            new java.util.concurrent.atomic.AtomicInteger();
        String[] seq = { "" };
        double[] stat = { -1, 1 };   // previous count, max ratio
        Runnable[] adv = { null };
        adv[0] = () -> {
            int i = step.getAndIncrement();
            if (i >= frames.size()) {
                if (stat[0] >= 0) {
                    System.out.printf(Locale.US, "sweep %s max-ratio=%.2f%n",
                        seq[0], stat[1]);
                }
                javafx.application.Platform.exit();
                System.exit(0);
                return;
            }
            Object[] p = frames.get(i);
            String name = (String) p[0];
            if (!name.equals(seq[0])) {
                if (stat[0] >= 0) {
                    System.out.printf(Locale.US, "sweep %s max-ratio=%.2f%n",
                        seq[0], stat[1]);
                }
                seq[0] = name;
                stat[0] = -1;
                stat[1] = 1;
            }
            if (vp.orthoView) vp.exitOrthoRestorePose();
            vp.applyCameraWorld(new double[]{
                (Double) p[1], (Double) p[2], (Double) p[3],
                (Double) p[4], (Double) p[5], (Double) p[6] });
            PauseTransition pt = new PauseTransition(Duration.millis(250));
            pt.setOnFinished(ev -> {
                try {
                    javafx.scene.image.WritableImage img =
                        vp.pane.snapshot(null, null);
                    int w = (int) img.getWidth(), h = (int) img.getHeight();
                    java.awt.image.BufferedImage bi =
                        new java.awt.image.BufferedImage(w, h,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    javafx.scene.image.PixelReader pr = img.getPixelReader();
                    int[] hist = new int[256];
                    int y0 = h / 2, n = 0;
                    float[] lum = new float[w * (h - y0)];
                    for (int yy = 0; yy < h; yy++) {
                        for (int xx = 0; xx < w; xx++) {
                            int argb = pr.getArgb(xx, yy);
                            bi.setRGB(xx, yy, argb);
                            if (yy < y0) continue;
                            float l = (0.299f * ((argb >> 16) & 255)
                                + 0.587f * ((argb >> 8) & 255)
                                + 0.114f * (argb & 255)) / 255f;
                            lum[n++] = l;
                            hist[(int) (l * 255)]++;
                        }
                    }
                    int half = n / 2, acc = 0, med = 0;
                    for (; med < 256; med++) {
                        acc += hist[med];
                        if (acc >= half) break;
                    }
                    float thr = med / 255f + 0.08f;
                    int lines = 0;
                    for (int k = 0; k < n; k++) if (lum[k] > thr) lines++;
                    if (stat[0] >= 0) {
                        double r = Math.max(lines, stat[0])
                            / Math.max(1, Math.min(lines, stat[0]));
                        stat[1] = Math.max(stat[1], r);
                    }
                    stat[0] = lines;
                    System.out.printf(Locale.US, "sweep %s %02d lines=%d%n",
                        name, i, lines);
                    javax.imageio.ImageIO.write(bi, "png",
                        new java.io.File(d, String.format(
                            "sweep-%02d-%s.png", i, name)));
                } catch (Exception ex) {
                    System.err.println("gridsweep: " + ex);
                }
                adv[0].run();
            });
            pt.play();
        };
        javafx.application.Platform.runLater(adv[0]);
    }

    private void handleKey(KeyEvent e) {
        // TYPED TEXT NEVER FIRES ACTIONS (v19.6, D235 — user rule:
        // a space typed into the rename box switched the viewport):
        // with any text box focused, bare keys TYPE; only shortcut-
        // modified combos (Ctrl+S, Ctrl+Z…) still act. Capture pulls
        // focus to the canvas, so ride keys are unaffected.
        if (!e.isShortcutDown() && stage.getScene() != null
                && stage.getScene().getFocusOwner()
                    instanceof javafx.scene.control.TextInputControl) {
            return;
        }
        // S (held, unmodified) = slide constraint for node/handle drags;
        // never while a camera mode owns WASD (uncaptured POV does not).
        // MID-DRAG the modifier check is waived: Ctrl is already held for
        // snapping, and pressing S then means snap+slide, not Ctrl+S.
        Viewport3D kv = keyViewport();
        if (e.getCode() == KeyCode.S && !kv.flyActive
                && !(kv.povActive && kv.povCaptured)
                && (!e.isShortcutDown() || editor.isDragging())) {
            slideKey = true;   // not consumed: bound actions still fire
        }
        // C held = click-the-track-recenters (FVD++ muscle memory; the
        // Shift+click gesture stays too). Tracked like the S slide key.
        if (e.getCode() == KeyCode.C && !kv.flyActive
                && !(kv.povActive && kv.povCaptured)
                && !e.isShortcutDown()) {
            cKey = true;
        }
        if (e.getCode() == KeyCode.SHIFT) editor.shiftHover(true);
        if (e.getCode() == KeyCode.CONTROL) editor.ctrlHover(true);
        if (kv.flyActive || (kv.povActive && kv.povCaptured)) {   // camera owns movement keys
            KeyCode c = e.getCode();
            // A/D in CAPTURED POV = view direction (v16.15, user
            // spec): A toggles the BACKWARD view, A again or D
            // returns forward; W/S keep their track direction, so
            // reversed W reads as riding backward
            if (kv.povActive && kv.povCaptured
                    && (c == KeyCode.A || c == KeyCode.D)) {
                setPovReversed(c == KeyCode.A && !keyViewport().povReversed);
                e.consume();
                return;
            }
            boolean movement = c == KeyCode.W || c == KeyCode.S
                    || c == KeyCode.SHIFT || c == KeyCode.CONTROL
                    || (kv.flyActive && (c == KeyCode.A || c == KeyCode.D));
            if (movement) {
                kv.moveKeys.add(c);
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
        // A GIZMO drag counts too (v19.19, D248): Ctrl+Z mid-drag
        // replaced the TrackPiece objects (docGen) under the pressed
        // closures, which then wrote into an orphaned piece until the
        // release (placementEdit's index -1 "just write" path).
        if ((editor.isDragging() || slideKey || anyGizmoDragging())
                && (e.isControlDown() || e.isShiftDown() || e.isAltDown() || e.isMetaDown())) {
            return;
        }
        // the convert UI owns the graph (v14.4, D96): while the split
        // line or the density preview is up, edit/file/undo actions
        // are dead — an open convert is an open gesture (view keys
        // like POV/ortho stay live below)
        // the START SCREEN owns the graph the same way (D226) — and it
        // hosts text boxes in the MAIN scene, so a focused editor gets
        // every key untouched
        if (editor.startPanelActive()) {
            javafx.scene.Node fo = stage.getScene() == null ? null
                : stage.getScene().getFocusOwner();
            if (fo instanceof javafx.scene.control.TextInputControl) return;
            // undo/redo stay live — every wizard setting is a document
            // step (D231) — plus file ops, the view keys and DELETE
            // (v19.6, D235: the wizard's placeholder piece is live
            // track — Delete on a selected speed control/node worked
            // by mouse but died here, the user's "couldn't delete
            // speed controls" bug)
            if (matches("undo", e)) editor.undo();
            else if (matches("redo", e)) editor.redo();
            else if (matches("save", e)) saveDesign(false);
            else if (matches("pov", e)) togglePov();
            else if (matches("delNode", e)) editor.deleteSelected();
            else if (handleViewDigit(e)) { /* view keys stay live */ }
            return;
        }
        // NO COASTER in the document (D230): only file ops, undo/redo
        // (a deleted coaster comes back) and the view keys
        if (!hasCoaster()) {
            if (matches("undo", e)) editor.undo();
            else if (matches("redo", e)) editor.redo();
            else if (matches("save", e)) saveDesign(false);
            else if (matches("load", e)) loadDesign();
            else if (matches("newfile", e)) newDesign();
            else if (handleViewDigit(e)) { /* view keys */ }
            return;
        }
        if (editor.convertUiActive()) {
            if (matches("pov", e)) togglePov();
            else if (handleViewDigit(e)) { /* view keys stay live */ }
            // a RANGE CHAIN's preview takes Ctrl+Z as "step back to
            // the previous section's preview" (v19.18, D247 — plain
            // previews keep undo dead, the v14.4 rule)
            else if (matches("undo", e) && editor.chainActive()) {
                editor.chainStepBack();
            }
            // lane SWITCHING stays live while the density preview is
            // up (v16.7, user spec — inspect the fitted carpet on
            // every lane before Apply; still read-only, like the
            // toolbar lane buttons)
            else if (matches("lane1", e)) editor.setActiveLane(0);
            else if (matches("lane2", e)) editor.setActiveLane(1);
            else if (matches("lane3", e)) editor.setActiveLane(2);
            return;
        }
        if      (matches("undo", e))    editor.undo();
        else if (matches("redo", e))    editor.redo();
        else if (matches("save", e))    saveDesign(false);
        else if (matches("load", e))    loadDesign();
        else if (matches("newfile", e)) newDesign();
        else if (matches("export", e))  exportDialog();
        else if (matches("smooth", e))  { gestureHot = true; editor.toggleSmooth(); }
        else if (matches("matchAll", e)) { gestureHot = true; editor.matchBoundaryAll(); }
        else if (matches("flath", e))   editor.levelSelected();
        else if (matches("levelSel", e)) editor.toggleLevelLock();
        else if (matches("align", e))   editor.straightenSelected();
        else if (matches("alignLock", e)) editor.toggleStraightLock();
        else if (matches("lockv", e))   editor.toggleAxisLock(true);
        else if (matches("lockh", e))   editor.toggleAxisLock(false);
        else if (matches("delNode", e)) editor.deleteSelected();
        else if (matches("append", e)) {
            // A over a 3D view must NOT append (v18.19, D190 — the
            // user spec; the graph keeps its shortcut). Held-key
            // repeats are a GESTURE like the arrows (D200 verify
            // round) — the KEY_RELEASED burst-end settles.
            if (!mouseOverView) { gestureHot = true; editor.appendAction(); }
        }
        else if (matches("pov", e))     togglePov();
        else if (handleViewDigit(e)) { /* 1-6 view directions */ }
        else if (matches("lane1", e))   editor.setActiveLane(0);
        else if (matches("lane2", e))   editor.setActiveLane(1);
        else if (matches("lane3", e))   editor.setActiveLane(2);
        else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT
              || e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
            // arrows step the selected node(s); OS key-repeat = continuous
            KeyCode a = e.getCode();
            int dt = a == KeyCode.LEFT ? -1 : a == KeyCode.RIGHT ? 1 : 0;
            int dv = a == KeyCode.UP ? 1 : a == KeyCode.DOWN ? -1 : 0;
            // a nudge burst is a GESTURE (D200): rebuilds run the
            // fast reconcile per keystroke, the arrow's KEY_RELEASED
            // settles exactly — key-repeat glides stay fluid
            gestureHot = true;
            // Shift = a tenth of the step, Ctrl = snap to the next
            // round multiple, both combine (v19.19, D248 — the global
            // spinner conventions on nodes)
            if (!editor.nudgeSelected(dt, dv, e.isShiftDown(),
                    e.isControlDown())) return;
        }
        else return;
        e.consume();
    }

    /** Does the event match the user's current binding for this action?
     *  Combo format: optional Ctrl+/Alt+/Shift+/Meta+ prefixes, then a KeyCode name. */
    boolean matches(String actionId, KeyEvent e) {   // package: the pv popup's undo filter (D247)
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


    /** The graph panel is collapsed (the ▲/▼ corner toggle, or the
     *  divider dragged shut): the editor unloads — redraw() is a hard
     *  no-op — so a busy zoomed-out plot costs nothing while it is
     *  off screen (v18.36, D208). Expanding repaints in full via the
     *  divider listener + layoutChildren. */
    boolean graphCollapsed() {
        return mainSplit != null
            && mainSplit.getDividerPositions()[0] > 0.97;
    }

    /** Make the graph editor at least h px tall (D231 — the start
     *  screen's comfortable minimum): moves the divider up when the
     *  editor is smaller (or collapsed); never shrinks it. */
    void growEditorTo(double h) {
        if (mainSplit == null || mainSplit.getHeight() <= 0) return;
        double total = mainSplit.getHeight();
        double topH = 0;
        if (mainSplit.getItems().size() > 1
                && mainSplit.getItems().get(1) instanceof BorderPane bp
                && bp.getTop() != null) {
            topH = bp.getTop().getLayoutBounds().getHeight();
        }
        double need = h + topH + 8;
        double pos = Math.max(0.15, 1 - need / total);
        if (mainSplit.getDividerPositions()[0] > pos) {
            lastDividerPos = pos;
            mainSplit.setDividerPositions(pos);
        }
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

    /** DOCUMENT undo keys for every window that can change the TRACK
     *  (v19.2, D227 — user rule: menu-local Ctrl+Z is for cosmetic /
     *  settings menus only): the bound undo/redo combos route to the
     *  GLOBAL history exactly as in the main window, and are consumed
     *  before any TextField's own text-undo sees them. Idempotent per
     *  scene; ride keys forwarded too. */
    private final java.util.Set<Scene> docKeyScenes =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    void installDocUndoKeys(Scene s) {
        if (s == null || !docKeyScenes.add(s)) return;
        s.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean undo = matches("undo", e), redo = matches("redo", e);
            if (!undo && !redo) return;
            // the SAME gate as handleKey's convert-UI branch (D247
            // review fix): while a preview / split line owns the
            // document, undo is dead — except a range chain's Ctrl+Z,
            // which steps back to the previous section's preview
            if (editor.convertUiActive()) {
                if (undo && editor.chainActive()) editor.chainStepBack();
                else statsLabel.setText(CONVERT_BUSY_MSG);
                e.consume();
                return;
            }
            if (redo) editor.redo(); else editor.undo();
            e.consume();
        });
        installViewKeys(s);
    }

    /** Route Ctrl+Z / Ctrl+Shift+Z inside a menu popup or dialog scene to
     *  the menu-local history (idempotent per scene) — COSMETIC/settings
     *  menus only since D227 (View menu, Snapping, colors, Export). */
    void installMenuUndoKeys(Scene s) {
        if (s == null || !menuKeyScenes.add(s)) return;
        s.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.Z && e.isShortcutDown()) {
                if (e.isShiftDown()) menuRedo(); else menuUndo();
                e.consume();
            }
        });
        installViewKeys(s);   // v18.1: menus never steal the ride keys
    }

    /** Ride/fly keys keep working while ANY window/popup holds key
     *  focus (v18.1, D166 — user spec: "with any window open, WASD
     *  still rides"): the pv popup's forwarder, installed on every
     *  secondary Scene. Registered once per scene. */
    private final java.util.Set<Scene> viewKeyScenes =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    void installViewKeys(Scene s) {
        if (s == null || !viewKeyScenes.add(s)) return;
        s.addEventFilter(KeyEvent.KEY_PRESSED, e -> forwardViewKey(e, true));
        s.addEventFilter(KeyEvent.KEY_RELEASED, e -> forwardViewKey(e, false));
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
    /** DENSITY-PREVIEW TRANSACTION guard (v19.18, D247): while a
     *  convert preview or split line owns the document, the
     *  placement/bridge spinners and the 3D gizmo presses REFUSE with
     *  this reason on the stats bar (the D235 rule — never a silent
     *  no-op). pvRerun restores the pre-preview snapshot on every
     *  tick, so such an edit would show for one frame and snap back,
     *  and a live-synced window would expose exactly that. */
    static final String CONVERT_BUSY_MSG =
        "Finish or cancel the open convert preview first.";

    /** true (and the stats reason posted) while the convert UI owns
     *  the document — see CONVERT_BUSY_MSG. */
    boolean convertBusy() {
        if (editor == null || !editor.convertUiActive()) return false;
        statsLabel.setText(CONVERT_BUSY_MSG);
        return true;
    }

    /** A refused spinner edit puts the OLD value back (muted) so the
     *  box never shows a value the model doesn't hold (D247). */
    <T> void revertSpinner(Spinner<T> sp, T old) {   // package: the editor's popups (D247 review fix)
        if (old == null) return;
        boolean was = docApplying;
        docApplying = true;
        try { sp.getValueFactory().setValue(old); }
        finally { docApplying = was; }
    }

    private void wireDocSpinner(Spinner<Double> sp, java.util.function.DoubleConsumer apply) {
        sp.valueProperty().addListener((obs, o, v) -> {
            if (v == null) return;     // null transient hardening (D212)
            if (docApplying) return;   // undo/redo writes the display only
            if (convertBusy()) { revertSpinner(sp, o); return; }   // D247
            // ONE history only (D227): a document spinner used to ALSO
            // push a menu-local edit — a menu Ctrl+Z replayed the
            // spinner (menuApplying, no doc push) while the doc stack
            // kept its snapshot, so the next main-window Ctrl+Z re-
            // applied a state already current ("undo did nothing")
            editor.pushUndoSnapshot();
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

    void changeRailMode(boolean solid) {   // View-menu radios call it (v16.14)
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
        MenuItem exportItem = new MenuItem("Export…");
        exportItem.setOnAction(e -> exportDialog());
        Menu fileMenu = new Menu("File", null, newItem, saveItem, saveAsItem,
            loadItem, new SeparatorMenuItem(), exportItem);

        // --- Edit: app-wide settings, persisted across runs (a D241
        // "Preferences" rename was REVERSED same round, D242) ---
        MenuItem shortcutsItem = new MenuItem("Shortcuts…");
        shortcutsItem.setOnAction(e -> dialogs.showShortcutsDialog());
        // renamed Options→Snapping in v16.14: after the viewport rows
        // moved to the View menu, snapping is all that's left
        MenuItem optionsItem = new MenuItem("Snapping…");
        optionsItem.setOnAction(e -> dialogs.showOptionsDialog());
        // Resolution Scale (v19.12, D241): whole-UI scale factor,
        // applied by uvd.Main at the next launch
        MenuItem scaleItem = new MenuItem("Resolution Scale…");
        scaleItem.setOnAction(e -> dialogs.showResolutionScaleDialog());
        Menu editMenu = new Menu("Edit", null, shortcutsItem,
            optionsItem, scaleItem);

        // --- Coaster: per-coaster settings, saved in the .fvd file ---
        heartSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-3.0, 3.0, heartOffset, 0.1));
        heartSpinner.setPrefWidth(82);
        typable(heartSpinner, true);
        wireDocSpinner(heartSpinner, v -> heartOffset = v);
        gaugeSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.3, 2.5, railGauge, 0.1));
        gaugeSpinner.setPrefWidth(82);
        typable(gaugeSpinner, false);
        wireDocSpinner(gaugeSpinner, v -> railGauge = v);
        tieSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.2, 10.0, tieSpacing, 0.1));
        tieSpinner.setPrefWidth(82);
        typable(tieSpinner, false);
        wireMenuSpinner(tieSpinner, v -> tieSpacing = v);
        // placement spinners edit the ACTIVE piece (v12.0) — piece 0
        // for a single-piece document, exactly the old "Start" behavior
        TrackPiece piece0 = coaster.pieces.get(0);
        startXSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.x, 1));
        startXSpinner.setPrefWidth(82);
        typable(startXSpinner, true);
        // placement writes route through placementEdit (v19.0, D221):
        // an orientation-RIGID piece carries its downstream chain —
        // on the PLACEMENT TARGET since D247 (the run head while a
        // strict-seam follower is active; the active piece otherwise)
        wireDocSpinner(startXSpinner, v -> placementEdit(
            placementTargetIdx(), () -> placementTargetRef().x = v));
        startYSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.y, 1));
        startYSpinner.setPrefWidth(82);
        typable(startYSpinner, true);
        wireDocSpinner(startYSpinner, v -> placementEdit(
            placementTargetIdx(), () -> placementTargetRef().y = v));
        startZSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-500, 500, piece0.z, 1));
        startZSpinner.setPrefWidth(82);
        typable(startZSpinner, true);
        wireDocSpinner(startZSpinner, v -> placementEdit(
            placementTargetIdx(), () -> placementTargetRef().z = v));
        // yaw ANGLES read/type LEFT-positive since v18.15 (D186,
        // pairing with the D185 rates): the UI negates at this
        // boundary — the stored yawDeg (and the file) keep the
        // historic internal sense, so nothing ever moves
        yawSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360, -piece0.dispYaw() + 0.0, 5));   // + 0.0: no "-0" (D248)
        yawSpinner.setPrefWidth(82);   // the clipped-"3" report (D191)
        typable(yawSpinner, true);
        wireDocSpinner(yawSpinner, v -> pieceOrientWrite(
            () -> activePieceRef().setDispYaw(-v)));   // D249: one route
        startSpeedSpinner = new Spinner<>(new SpinnerValueFactory.DoubleSpinnerValueFactory(1, 100, coaster.v0, 1));
        startSpeedSpinner.setPrefWidth(82);
        typable(startSpeedSpinner, false);
        // the spinner is v0 AND the sole section's design speed — they
        // stay coupled until Phase 3 gives dynamics its own controls
        wireDocSpinner(startSpeedSpinner, v -> {
            coaster.v0 = v;
            coaster.soleSection().vDesign(v);
        });
        // PHASE 3 FRICTION (v17.0, D133; NL2-style units + defaults
        // v18.0, D161): the coaster's two loss coefficients —
        // per-coaster, saved in the .fvd, in document undo (they
        // change the track path: design profiles shed). Rolling edits
        // in m/m (step 0.001), air directly in m⁻¹ (step 1e-5 — the
        // old per-km ×1000 display is gone).
        fricRollSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 0.2, coaster.fricRoll, 0.001));
        fricRollSpinner.setPrefWidth(82);
        typable(fricRollSpinner, false);
        ((SpinnerValueFactory.DoubleSpinnerValueFactory)
            fricRollSpinner.getValueFactory()).setConverter(
                fixedDecimalConverter(3));
        wrapBlankConverter(fricRollSpinner);   // re-wrap (D212)
        wireDocSpinner(fricRollSpinner, v -> coaster.fricRoll = v);
        fricAirSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 0.02, coaster.fricAir, 0.00001));
        fricAirSpinner.setPrefWidth(90);
        typable(fricAirSpinner, false);
        ((SpinnerValueFactory.DoubleSpinnerValueFactory)
            fricAirSpinner.getValueFactory()).setConverter(
                fixedDecimalConverter(5));
        wrapBlankConverter(fricAirSpinner);   // re-wrap (D212)
        wireDocSpinner(fricAirSpinner, v -> coaster.fricAir = v);

        Label heartLbl = new Label("Heart (m)");
        heartLbl.setTextFill(Color.BLACK);   // menu popup is light; default fill washed out
        Label gaugeLbl = new Label("Gauge (m)");
        gaugeLbl.setTextFill(Color.BLACK);
        Label tieLbl = new Label("Tie every (m)");
        tieLbl.setTextFill(Color.BLACK);
        // "Piece" rows (v12.0) live in the toolbar's Placement dropdown
        // since v13.2 (user: piece settings belong by the graph); they
        // are built here with the other spinners and stored as fields
        // "Anchor …" since v19.18 (D247, user spec): the rows place the
        // RUN's entry — the user's "anchor" — not just one piece
        Label startLbl = new Label("Anchor x/y/z (m)");
        startLbl.setMinWidth(LiveWindow.LABEL_W);   // the windows' label column (D248)
        startLbl.setPrefWidth(LiveWindow.LABEL_W);
        anchorLbl = startLbl;   // wears the run-head hover (D247)
        Label speedLbl = new Label("Start speed (m/s)");
        speedLbl.setTextFill(Color.BLACK);
        heartRow = new HBox(8, heartLbl, heartSpinner);
        heartRow.setAlignment(Pos.CENTER_LEFT);
        gaugeRow = new HBox(8, gaugeLbl, gaugeSpinner);
        gaugeRow.setAlignment(Pos.CENTER_LEFT);
        tieRow = new HBox(8, tieLbl, tieSpinner);
        tieRow.setAlignment(Pos.CENTER_LEFT);
        // gray-out explainers (v16.13): a bridged-in piece's owned
        // values say to edit through the BRIDGE instead; a strict-
        // seam FOLLOWER's orientation rows point at its junction
        // window BY NAME ("Strict Seam n ▴", D248 — its x/y/z rows
        // never gray: they place the run head, see
        // placementTargetIdx). LIVE Suppliers: the rows can stay
        // disabled across a bridged-in → follower switch.
        String bridgeOwned = "The bridge connecting into this piece "
            + "owns this value — edit it through the bridge rows.";
        java.util.function.Supplier<String> seamOwned = () -> {
            String jt = hasCoaster() && editor != null
                ? editor.junctionTitle(editor.activePiece()) : null;
            return "This section follows a strict seam — its "
                + "orientation is edited in "
                + (jt != null ? jt : SectionNames.STRICT_SEAM.trim())
                + SectionNames.OWNER_ARROW + ". Anchor orientation is"
                + " editable while a section before the first strict"
                + " seam is selected.";
        };
        placementRow = new HBox(8, startLbl,
            GraphEditor.grayHint(startXSpinner, bridgeOwned),
            GraphEditor.grayHint(startYSpinner, bridgeOwned),
            GraphEditor.grayHint(startZSpinner, bridgeOwned));
        placementRow.setAlignment(Pos.CENTER_LEFT);
        // piece START pitch/roll rows (v15.3, D101 — user spec): the
        // rigid tilt (D100) made these shape-preserving placement
        // knobs; padlocked like the bridge window (default LOCKED),
        // and for a bridged-in piece the padlocks ARE the bridge's —
        // one shared state, whichever window flips them
        // ABSOLUTE boxes (D224): canonical elevation ±90 / bank ±360
        // of the real entry; writes route through the D222 lock
        piecePitchSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-90, 90,
                piece0.dispPitch(), 1));
        piecePitchSpinner.setPrefWidth(80);
        typable(piecePitchSpinner, true);
        wireDocSpinner(piecePitchSpinner, v -> pieceOrientWrite(
            () -> activePieceRef().setDispPitch(v, true)));
        pieceRollSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360,
                piece0.dispRoll(), 1));
        pieceRollSpinner.setPrefWidth(80);
        typable(pieceRollSpinner, true);
        wireDocSpinner(pieceRollSpinner, v -> pieceOrientWrite(
            () -> activePieceRef().setDispRoll(v, true)));
        pieceLockPitchBtn = placementLockBtn(1);
        pieceLockRollBtn = placementLockBtn(2);
        String tiltGray = "Locked — open the padlock to edit.";
        // three causes, one live reason PER AXIS (D247; D249): follower
        // → the seam window; a bridged-in piece → the bridge's enable
        // rule (a free axis under optimize-orientation is the
        // march's, a held one is typeable — BridgeSection.axisEditable);
        // a free piece → its edit-guard padlock
        java.util.function.IntFunction<java.util.function.Supplier<String>>
            tiltWhyFor = axis -> () -> {
                if (!hasCoaster() || editor == null) return tiltGray;
                if (editor.followsPrev(editor.activePiece())) return seamOwned.get();
                BridgeSection bs = bridgeInto(editor.activePiece());
                if (bs != null) return bs.axisEditable(axis) ? "" : BRIDGE_ALIGNS_AXIS;
                return tiltGray;
            };
        // the PADLOCKS gray with the seam reason only (D247 review fix
        // — on a follower the guard flag is dead; a bridged-in piece's
        // padlocks are the bridge's and LIVE in every mode since D249)
        java.util.function.Supplier<String> lockWhy = () ->
            hasCoaster() && editor != null
                && editor.followsPrev(editor.activePiece())
                ? seamOwned.get() : "";
        // the Track Piece window's orientation rows + ↺ + readouts +
        // flag / resolve rows (D248 — the D222 designTiltLbl line
        // became per-row "design …°" readouts)
        buildTrackPieceRows(tiltWhyFor.apply(1), tiltWhyFor.apply(2),
            lockWhy, seamOwned);

        // bridge placement panel (v13.5): while a live bridge is
        // active, the Placement dropdown edits the NEXT piece through
        // it — a mode choice plus the controls each mode leaves free
        Label bridgeModeLbl = new Label("Next piece placement");
        ToggleGroup modeGroup = new ToggleGroup();
        bridgeModePY = new RadioButton("Optimize position and orientation");
        bridgeModeP = new RadioButton("Optimize position");
        bridgeModeN = new RadioButton("No optimization");
        Tooltip.install(bridgeModePY, new Tooltip(
            "Place the next piece on the natural continuation, aligning\n"
            + "its unlocked axes; locked axes hold and the bridge bends\n"
            + "to them."));
        RadioButton[] modeBtns = { bridgeModePY, bridgeModeP, bridgeModeN };
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            modeBtns[m].setToggleGroup(modeGroup);
            // never an ellipsis (the "No optimizati…" winshot, D248):
            // a Labeled laid out at EXACTLY its pref width can lose a
            // sub-pixel to snapping and truncate — let the radios
            // fill the row instead (left-aligned, looks the same)
            modeBtns[m].setMaxWidth(Double.MAX_VALUE);

            modeBtns[m].setOnAction(e -> {
                if (docApplying) return;
                if (convertBusy()) {   // the F3 transaction guard (D248
                    updateBridgeControls();   // review fix): re-select
                    return;
                }
                bridgeCall(() -> {   // the window's bridge (D248)
                    editor.setBridgeOptMode(mode);
                    refreshBridgeRows();
                });
            });
        }
        // the PY radio's LIVE gray reason (D249): evaluated in the
        // open bridge window's context (its bridge), else the active
        // section's — the same scoping as the rows
        java.util.function.Supplier<String> pyWhyLive = () -> {
            if (editor == null) return "";
            String w = editor.withBridge(bridgeWinPiece, this::pyWhy);
            return w == null ? "" : w;
        };
        bridgeModeRow = new VBox(4, bridgeModeLbl,
            GraphEditor.grayHint(bridgeModePY, pyWhyLive),
            bridgeModeP, bridgeModeN);
        bridgeGapLbl.setStyle("");   // a light LiveWindow since D248
        // "Optimize length" (v13.8, D82): the length becomes the knee
        // of the smoothness curve — the shortest length at which the
        // transition stops improving; the spinner disables while on.
        // Switching INTO an optimize mode turns it on (user spec).
        bridgeOptLenCheck = new CheckBox("Optimize length");
        bridgeOptLenCheck.setFocusTraversable(false);
        bridgeOptLenCheck.setOnAction(e -> {
            if (docApplying) return;
            if (convertBusy()) {   // the F3 transaction guard (D248
                updateBridgeControls();   // review fix): re-sync the check
                return;
            }
            bridgeCall(() -> {   // the window's bridge (D248)
                editor.setBridgeOptLen(bridgeOptLenCheck.isSelected());
                refreshBridgeRows();
            });
        });
        Tooltip.install(bridgeOptLenCheck, new Tooltip(
            "Pick the length automatically — the shortest bridge that\n"
            + "is (near-)as smooth as any longer one."));
        bridgeLenRow = new HBox(8, bridgeGapLbl,
            GraphEditor.grayHint(bridgeGapSpinner,
                "Uncheck Optimize length to type a length."),
            bridgeOptLenCheck);
        bridgeLenRow.setAlignment(Pos.CENTER_LEFT);
        bridgeLenRowWrap = GraphEditor.grayHint((javafx.scene.Node)
            bridgeLenRow, "No-optimization mode has no length to run.");
        // (v13.10-dev briefly exposed three per-bridge optimizer
        // spinners here — REMOVED in the same version, D84: the user
        // found them confusing and unpredictable; the optimizer's
        // constants are internal again)
        bridgeYawSpinner = new Spinner<>(   // arrows step 1° (v16.12,
            // user spec — pitch/roll already did; the gizmo rings'
            // Ctrl-snap is 1° to match)
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360, 0, 1));
        bridgeYawSpinner.setPrefWidth(90);
        typable(bridgeYawSpinner, true);
        bridgeYawSpinner.valueProperty().addListener((obs, o, v) -> {
            if (docApplying || v == null) return;
            if (convertBusy()) { revertSpinner(bridgeYawSpinner, o); return; }   // D247
            // typed yaw is REPORTED (+ = left, D186): internal negates
            bridgeCall(() -> editor.setBridgeTargetOrient(0, -v));
        });
        // orientation LOCKS (v13.13, D87; the SEAM RULE since v19.20,
        // D249): a locked axis is HELD at the typed value — editable
        // in every mode, the optimization MEETS it; an unlocked axis
        // is free — in optimize-orientation mode the march ALIGNS it
        // (its box grays), otherwise it stays editable. Defaults: yaw
        // unlocked, pitch + roll locked = the historic "position and
        // yaw".
        bridgeLockYawBtn = bridgeLockBtn(0);
        bridgeLockPitchBtn = bridgeLockBtn(1);
        bridgeLockRollBtn = bridgeLockBtn(2);
        // the bridge window's ↺ / readouts / flag + resolve rows (D248)
        buildBridgeWindowRows();
        // the ONE gray reason a row can have now (D249): a free axis
        // under optimize-orientation (the D87 "Click the padlock to
        // unlock." was false there — unlocking handed the axis to
        // the march and the box stayed gray)
        java.util.function.Supplier<String> bYawWhy = () -> BRIDGE_ALIGNS_AXIS;
        bridgeYawRow = LiveWindow.orientRow("Next piece yaw (°)",
            GraphEditor.grayHint(bridgeYawSpinner, bYawWhy),
            bridgeLockYawBtn, GraphEditor.grayHint(bridgeYawRst, bYawWhy),
            null);
        bridgePitchSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-90, 90, 0, 1));
        bridgePitchSpinner.setPrefWidth(90);
        typable(bridgePitchSpinner, true);
        bridgePitchSpinner.valueProperty().addListener((obs, o, v) -> {
            if (docApplying || v == null) return;
            if (convertBusy()) { revertSpinner(bridgePitchSpinner, o); return; }   // D247
            bridgeCall(() -> editor.setBridgeTargetOrient(1, v));
        });
        java.util.function.Supplier<String> bPitchWhy = () -> BRIDGE_ALIGNS_AXIS;
        bridgePitchRow = LiveWindow.orientRow("Next piece pitch (°)",
            GraphEditor.grayHint(bridgePitchSpinner, bPitchWhy),
            bridgeLockPitchBtn,
            GraphEditor.grayHint(bridgePitchRst, bPitchWhy),
            bridgePitchDesign.node);
        bridgeRollSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(-360, 360, 0, 1));
        bridgeRollSpinner.setPrefWidth(90);
        typable(bridgeRollSpinner, true);
        bridgeRollSpinner.valueProperty().addListener((obs, o, v) -> {
            if (docApplying || v == null) return;
            if (convertBusy()) { revertSpinner(bridgeRollSpinner, o); return; }   // D247
            bridgeCall(() -> editor.setBridgeTargetOrient(2, v));
        });
        java.util.function.Supplier<String> bRollWhy = () -> BRIDGE_ALIGNS_AXIS;
        bridgeRollRow = LiveWindow.orientRow("Next piece roll (°)",
            GraphEditor.grayHint(bridgeRollSpinner, bRollWhy),
            bridgeLockRollBtn,
            GraphEditor.grayHint(bridgeRollRst, bRollWhy),
            bridgeRollDesign.node);
        bridgeXSpinner = bridgePosSpinner(0);
        bridgeYSpinner = bridgePosSpinner(1);
        bridgeZSpinner = bridgePosSpinner(2);
        bridgePosRow = LiveWindow.labeledRow("Next piece x/y/z (m)",
            bridgeXSpinner, bridgeYSpinner, bridgeZSpinner);
        bridgePosRowWrap = GraphEditor.grayHint((javafx.scene.Node)
            bridgePosRow, "The optimize mode places the piece — "
            + "switch to No optimization.");
        // the Start-speed row LEFT the Coaster menu in v18.6 (D175,
        // user spec) — the spinner remains the v0 write-through choke
        // (undo + rebuild wiring): the ride entry's node-0 dialog and
        // loads/News still set it programmatically
        HBox speedRow = new HBox(8, speedLbl, startSpeedSpinner);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        // friction rows (v17.0): tooltips carry the model
        Label fricRollLbl = new Label("Friction (m/m)");
        fricRollLbl.setTextFill(Color.BLACK);
        fricRollRow = new HBox(8, fricRollLbl, fricRollSpinner);
        fricRollRow.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(fricRollRow, new Tooltip(
            "Rolling losses: constant deceleration μ·g per meter.\n"
            + "Default 0.03 ≈ NL2; 0 = loss-free."));
        Label fricAirLbl = new Label("Air resistance (m⁻¹)");
        fricAirLbl.setTextFill(Color.BLACK);
        fricAirRow = new HBox(8, fricAirLbl, fricAirSpinner);
        fricAirRow.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(fricAirRow, new Tooltip(
            "Air drag: deceleration k·v² (k in 1/m).\n"
            + "Default 1.0×10⁻⁴ ≈ NL2."));

        // (the rail-color radios live in THIS menu's "Rail Color"
        // section since v17.10 — Dialogs.railColorItems assigns the
        // gradientModeItem/solidModeItem RadioButtons; they spent
        // v16.14-v17.9 in the View menu)

        // reversal is a PIECE operation (v12.1, D71), REWORKED as a
        // CONVERT in v17.0 (D134, user re-spec): the compiled geometry
        // re-fits as ordinary forward sections running the other way
        // through the density preview — entry speed = the recorded
        // end speed (padlocked row; unlock for custom), no speed
        // conflict in the result. Its ONLY surface is the Track Piece
        // window's "Reverse…" button (openTrackPieceWindow) — the
        // Coaster-menu MenuItem that once carried it was dead since
        // the D232 restructure and was deleted in v19.18 (D247).

        // RESTRUCTURED Coaster menu (v19.3, D232 — user spec): the
        // coaster SELECTOR, then Coaster Options… (rename + friction
        // + statistics), New Coaster…, Delete Coaster, then Rail
        // Settings… (rail color + heart + gauge + tie spacing). The
        // settings ROWS built above re-parent into those dialogs;
        // dialogs.railSettingsContent() assigns the rail-mode radios.
        coasterMenu = new Menu("Coaster");
        dialogs.railSettingsContent();   // creates + assigns the radios
        // POPULATE NOW and refresh on every showing (v19.5, D234 —
        // THE "Coaster menu does nothing" bug: a JavaFX Menu with
        // ZERO items never opens its popup at all — the very trick
        // the Help menu below exploits — so the old onShown-only
        // rebuild could never fire: empty → never shows → never
        // populates. onShowing fires BEFORE display, so the list is
        // fresh each open.)
        coasterMenu.setOnShowing(e -> rebuildCoasterMenu());
        rebuildCoasterMenu();
        // --- Help: clicking the menu itself opens the dialog directly
        // (a Label graphic takes the click; the empty menu never opens) ---
        Menu helpMenu = new Menu();
        Label helpLbl = new Label("Help");
        helpLbl.setOnMouseClicked(e -> dialogs.showHelpDialog());
        helpMenu.setGraphic(helpLbl);

        // View menu (v16.13): every viewport setting, between Edit
        // and Coaster per user spec — built in Dialogs with the
        // option-row helpers
        return new MenuBar(fileMenu, editMenu, dialogs.buildViewMenu(),
            coasterMenu, helpMenu);
    }


    // POV/fly cameras, meshes, grid: Viewport3D.java (D54).

    /** Rebuild the RESTRUCTURED Coaster menu (v19.3, D232 — replaces
     *  the D230 list rows): coaster SELECTOR dropdown · Coaster
     *  Options… · New Coaster… · Delete Coaster · Rail Settings….
     *  Everything left-aligned; global Ctrl+Z (D227) via the row's
     *  scene once shown. */
    private void rebuildCoasterMenu() {
        // the SELECTOR is a ChoiceBox row again (v19.5, D234 — user
        // spec; D233's submenu replacement rested on a misdiagnosis:
        // the menu itself never opened because it had NO ITEMS until
        // onShown, see buildMenuBar. A ChoiceBox opens fine inside a
        // menu popup — its dropdown is a child popup of the menu's
        // window, Robot-verified in the -Dfvd.devmenu hook —
        // hideOnClick(false) keeps the row from dismissing the menu;
        // a pick hides it deliberately.)
        Label pickLbl = new Label("Coaster:");
        pickLbl.setTextFill(Color.BLACK);
        ChoiceBox<String> pick = new ChoiceBox<>();
        devMenuPick = pick;
        for (Coaster c : coasters) pick.getItems().add(c.name);
        if (hasCoaster()) pick.getSelectionModel().select(activeCoaster);
        pick.setDisable(coasters.isEmpty());
        pick.setPrefWidth(160);
        // listener attached AFTER the initial select — rebuilds never
        // re-enter switchCoaster
        pick.getSelectionModel().selectedIndexProperty()
            .addListener((o, ov, nv) -> {
                int idx = nv.intValue();
                if (idx < 0 || idx == activeCoaster) return;
                coasterMenu.hide();
                switchCoaster(idx);
            });
        HBox pickRow = new HBox(8, pickLbl, pick);
        pickRow.setAlignment(Pos.CENTER_LEFT);
        CustomMenuItem pickItem = new CustomMenuItem(pickRow, false);

        // gray reasons ride hover TOOLTIPS since v19.7 (D236 —
        // GraphEditor.grayItem, replacing the label parentheticals);
        // Coaster Options also grays while ITS coaster is still in
        // the start wizard (item: "created before editing these")
        boolean inWizard = wizardOpen && wizardState != null
            && wizardState.mode() == StartPanel.Mode.NEW_COASTER.ordinal()
            && wizardState.coasterIdx() == activeCoaster;
        MenuItem options;
        if (!hasCoaster()) {
            options = GraphEditor.grayItem("Coaster Options…",
                "No coaster exists yet — create one first.");
        } else if (inWizard) {
            options = GraphEditor.grayItem("Coaster Options…",
                "Finish the start screen first.");
        } else {
            options = new MenuItem("Coaster Options…");
            options.setOnAction(ev -> showCoasterOptionsDialog());
        }
        MenuItem add = new MenuItem("New Coaster…");
        add.setOnAction(ev -> openNewCoasterWizard(true));
        MenuItem del;
        if (!hasCoaster()) {
            del = GraphEditor.grayItem("Delete Coaster",
                "No coaster exists yet.");
        } else {
            del = new MenuItem("Delete Coaster");
        }
        del.setOnAction(ev -> {
            if (!hasCoaster()) return;
            // CONFIRM by name (D232, user spec)
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to delete \"" + coaster.name + "\"?",
                new ButtonType("Delete", ButtonBar.ButtonData.YES),
                ButtonType.CANCEL);
            a.initOwner(stage);
            a.setTitle("Delete coaster");
            a.setHeaderText(null);
            if (a.showAndWait().orElse(ButtonType.CANCEL)
                    .getButtonData() == ButtonBar.ButtonData.YES) {
                editor.pushUndoSnapshot();
                deleteActiveCoaster();
            }
        });
        MenuItem rail = new MenuItem("Rail Settings…");
        rail.setOnAction(ev -> dialogs.showRailSettingsDialog());
        // Rail Settings sits DIRECTLY under Coaster Options since
        // v19.7 (D236, user spec)
        coasterMenu.getItems().setAll(pickItem, options, rail,
            new SeparatorMenuItem(), add, del);
        // the D227 undo rule needs the popup's scene — available only
        // once a row is attached; runLater lands after the show
        javafx.application.Platform.runLater(() -> {
            if (options.getStyleableNode() != null
                    && options.getStyleableNode().getScene() != null) {
                installDocUndoKeys(options.getStyleableNode().getScene());
            }
        });
    }

    /** COASTER OPTIONS dialog (v19.3, D232): rename + the two
     *  friction rows + the statistics block — modeless, global
     *  Ctrl+Z. */
    private Stage coasterOptionsDlg;

    void showCoasterOptionsDialog() {
        if (!hasCoaster()) return;
        if (coasterOptionsDlg != null) coasterOptionsDlg.close();
        Stage dlg = new Stage();
        coasterOptionsDlg = dlg;
        dlg.initOwner(stage);
        dlg.setTitle("Coaster Options");
        TextField name = new TextField(coaster.name);
        name.setPrefColumnCount(14);
        Runnable commitName = () -> {
            if (!name.getText().isBlank()
                    && !name.getText().trim().equals(coaster.name)) {
                renameActiveCoaster(name.getText());
            }
        };
        name.setOnAction(e -> commitName.run());
        name.focusedProperty().addListener((o, was, is) -> {
            if (!is) commitName.run();
        });
        HBox nameRow = new HBox(8, new Label("Name"), name);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label statsHead = new Label("Statistics");
        statsHead.setStyle("-fx-font-weight: bold;");
        Label stats = new Label(coasterStatsText());
        VBox box = new VBox(8, nameRow, fricRollRow, fricAirRow,
            new javafx.scene.control.Separator(), statsHead, stats);
        box.setPadding(new Insets(14));
        Scene sc = new Scene(box);
        dlg.setScene(sc);
        // LIVE (D247): the statistics follow every compile and the
        // name box follows an undo/redo rename — unless it is being
        // typed into (its focus-loss commit then renames as before)
        Runnable live = () -> {
            if (coasterOptionsDlg != dlg) return;
            String st = coasterStatsText();
            if (!st.equals(stats.getText())) stats.setText(st);
            if (hasCoaster() && !name.isFocused()
                    && !name.getText().equals(coaster.name)) {
                name.setText(coaster.name);
            }
        };
        modelHooks.add(live);
        dlg.setOnHidden(e -> {
            modelHooks.remove(live);
            if (coasterOptionsDlg == dlg) coasterOptionsDlg = null;
        });
        dlg.show();
        installDocUndoKeys(sc);   // friction/rename shape the track (D227)
    }

    /** The Coaster Options statistics block off the last compile
     *  (extracted for the live hook, D247). */
    private String coasterStatsText() {
        return String.format(Locale.US,
            "Ride time: %.1f s%nTrack length: %.0f m%n"
            + "Height: %.1f to %.1f m%nTop speed: %.0f km/h%n"
            + "Vertical G: %.1f to %.1f",
            statRideTime, statTrackLen, statMinY, statMaxY,
            statTopV * 3.6, statMinGv, statMaxGv);
    }


    // (the stats-bar shortcut hint is GONE, v17.11 — user spec: the
    // bottom line is the live POV position now; shortcuts live in
    // Edit > Shortcuts and the Help dialog)


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
        // never save inside the fast pass's ±1e-3 tube (D200): the
        // file must be the exactly-settled session so a reload
        // reproduces it bitwise (the D198 contract). BEFORE the
        // PrintWriter — it truncates the target, and the settle runs
        // whole compiles whose exception surface must not be able to
        // destroy the user's file (D200 verify round)
        settleIfPending();
        try (PrintWriter out = new PrintWriter(f, "UTF-8")) {
            // FVDD3/FVDD4 via DesignIO (Phase 1, D50; multi-coaster
            // v19.2 D230) — cosmetics travel as plain doubles so the
            // writer stays JavaFX-free; every coaster with its look
            stashActiveLook();
            List<DesignIO.Cosmetics> looks = new ArrayList<>();
            for (Coaster c : coasters) looks.add(lookOf(c));
            DesignIO.saveAll(coasters, looks, uiCosmetics().camera(), out);
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

    /** RESTART the app in place (v19.13, D242 — the Resolution Scale
     *  dialog's "Apply and Restart"): the scale property is baked in
     *  at toolkit boot (platform fact, D241), so the closest thing to
     *  a live apply is a fresh instance — same JVM, same JVM args
     *  (module path included), same classpath, launched on
     *  uvd.Main. Runs the unsaved-changes gate first; a spawn
     *  failure leaves this instance running with a stats reason. */
    void restartApp() {
        if (!confirmSaveDiscard()) return;
        try {
            String javaBin = new java.io.File(
                System.getProperty("java.home"),
                "bin" + java.io.File.separator + "java.exe")
                .getAbsolutePath();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.addAll(java.lang.management.ManagementFactory
                .getRuntimeMXBean().getInputArguments());
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add("uvd.Main");
            new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
            javafx.application.Platform.exit();
        } catch (Exception ex) {
            statsLabel.setText("Restart failed (" + ex.getMessage()
                + ") — relaunch manually to apply the scale.");
        }
    }

    /** File > New (Ctrl+N): offer to save, then start a fresh EMPTY
     *  document and open the START SCREEN for its first coaster (v19.2,
     *  D230/D231 — the document may legally stay empty if the wizard is
     *  cancelled): default look, empty history, "Untitled" title. */
    private void newDesign() {
        if (!confirmSaveDiscard()) return;
        docApplying = true;   // spinners get set programmatically below
        editor.hideStartPanel();
        wizardState = null;
        wizardOpen = false;
        wizardPanel = null;
        coasters.clear();
        otherCompiles.clear();
        coaster = emptyCoaster;
        activeCoaster = 0;
        docGen++;   // fresh piece objects (v18.33, D205)
        applyLook(DesignIO.Cosmetics.defaults());
        startXSpinner.getValueFactory().setValue(0.0);
        startYSpinner.getValueFactory().setValue(NEW_START_Y);
        startZSpinner.getValueFactory().setValue(0.0);
        yawSpinner.getValueFactory().setValue(0.0);
        docApplying = false;

        editor.clearHistory();
        editor.clearSelection();
        currentFile = null;
        clearResultingPicks();   // opens start overlay-free (v18.10)
        rebuildTrack();
        editor.refresh();
        // the first coaster's wizard (no undo push: the history is empty
        // — Ctrl+Z on a brand-new document has nothing to return to)
        openNewCoasterWizard(false);
        editor.clearHistory();
        dirty = false;
        updateTitle();
        statsLabel.setText("New design");
    }
    // -----------------------------------------------------------------
    // START WIZARD glue (v19.1, D226): the StartPanel's model writes
    // funnel through here so undo/dirty/spinner sync stay in one place
    // -----------------------------------------------------------------

    /** Show the start screen: FLOATING = a new detached piece in the
     *  active coaster; NEW_COASTER = a new coaster in the document. */
    void showStartWizard(StartPanel.Mode mode) {
        if (mode == StartPanel.Mode.FLOATING) openFloatingWizard();
        else openNewCoasterWizard(true);
    }

    /** "123.4 m" for the whole RUN headed by `head` — every member
     *  piece incl. its bridges (v19.19, D248: the selector and the
     *  '+' lists name runs); "? m" before a compile covers it. */
    String runLengthLabel(int head) {
        return runLengthLabel(coaster, sectionSpans, head);
    }

    /** Same for any coaster + its spans (the export picker). */
    String runLengthLabel(Coaster c, List<TrackCompiler.Span> spans, int head) {
        double len = Coaster.runLengthM(c, spans, head);
        return Double.isNaN(len) ? "? m"
            : String.format(Locale.US, "%.1f m", len);
    }

    /** {x, y, z, reportedYaw, trueV} 5 m past the end of the ACTIVE
     *  RUN (its tail piece's last sample — D248: "5 m past the track
     *  piece's end" under the run vocabulary; was the active MEMBER's
     *  end) along its heading (level) — the floating wizard's default;
     *  null without a compile. Pure math on Coaster.runTailEnd
     *  (RunGate h). */
    double[] activePieceEnd() {
        if (lastSamples == null || sectionSpans == null) return null;
        return Coaster.runTailEnd(coaster, lastSamples, sectionSpans, trueV,
            editor.activePiece());
    }

    // ---- the START WIZARD's document model (v19.2, D231) ----
    // The wizard's target piece EXISTS from the moment the wizard opens
    // (a placeholder 5 s force section, PENDING = invisible until the
    // section type is picked); the WizardState rides the document undo
    // (DocState.wizard), so Ctrl+Z right after opening removes the
    // piece/coaster and every setting change is its own step.
    // wizardOpen is SESSION state: false once the user pressed Done or
    // Cancel — after that undo restores values but keeps the panel
    // closed, unless it lands on a still-PENDING state (then the panel
    // must return: an invisible piece with no way to pick would be a
    // trap). The panel is bound to its target: it shows only while
    // that coaster (and, for a floating piece, that piece) is active —
    // select anything else and the normal graphs are back; return and
    // the panel is as you left it.
    WizardState wizardState;
    boolean wizardOpen;
    private StartPanel wizardPanel;

    /** Placeholder for a pending piece: the classic 5 s / 1 g hold. */
    private static ForceSection placeholderSection(double speed) {
        List<List<BezierBaker.Node>> nl = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> lane = new ArrayList<>(2);
            lane.add(new BezierBaker.Node(0, i == 0 ? 1 : 0));
            lane.add(new BezierBaker.Node(NEW_START_LEN, i == 0 ? 1 : 0));
            BezierBaker.applyDefaultHandles(lane);
            nl.add(lane);
        }
        // pristine by construction — every lane follows the end (D248)
        return (ForceSection) new ForceSection(nl, speed).endFollows(true);
    }

    /** OPEN the wizard for a NEW COASTER (File > New's first coaster,
     *  or Coaster > New coaster…): the coaster + its pending piece are
     *  created NOW (one undo step, pushed by the caller when there is
     *  history to keep). */
    /** One wizard at a time: an open one takes the user back to its
     *  target instead of stacking a second (its pending piece would
     *  otherwise stay invisible forever). True = handled. */
    private boolean focusOpenWizard() {
        if (wizardState == null || wizardPiece() == null) return false;
        if (wizardState.coasterIdx() != activeCoaster) {
            switchCoaster(wizardState.coasterIdx());
        }
        if (wizardState.mode() == StartPanel.Mode.FLOATING.ordinal()) {
            editor.setActivePiece(wizardState.pieceIdx());
        }
        wizardOpen = true;
        syncWizardPanel();
        statsLabel.setText("Finish or cancel the open start screen first");
        return true;
    }

    void openNewCoasterWizard(boolean pushUndo) {
        if (focusOpenWizard()) return;
        if (pushUndo) editor.pushUndoSnapshot();
        Coaster c = new Coaster();
        c.name = freshCoasterName();
        c.v0 = NEW_START_SPEED;
        c.fricRoll = FRIC_ROLL_DEFAULT;
        c.fricAir = FRIC_AIR_DEFAULT;
        c.look = DesignIO.Cosmetics.defaults();
        TrackPiece piece = new TrackPiece(0, NEW_START_Y, 0, 0);
        // the wizard edits BOTH orientation tiers (v19.8, D237 — the
        // gizmo rings act in lockstep, matching the panel's own
        // yaw/pitch/roll rows) and the pitch/roll
        // rings BUILD (rings skip padlocked axes); Done restores the
        // default locks
        piece.orientMode = TrackPiece.OrientMode.LOCKSTEP;   // D249
        piece.uiLockPitch = false;
        piece.uiLockRoll = false;
        piece.sections.add(placeholderSection(NEW_START_SPEED));
        c.pieces.add(piece);
        addCoaster(c);
        // Force/Geometric is PICKED BY DEFAULT since D232 — the
        // placeholder 5 s force section IS that pick, so the track is
        // visible from the first frame and Done always works
        wizardState = new WizardState(StartPanel.Mode.NEW_COASTER.ordinal(),
            activeCoaster, 0, true, false,
            NEW_START_LEN, Coaster.CONT_HOLD_DIST);
        wizardOpen = true;
        wizardPanel = null;
        rebuildTrack();
        editor.defaultLane();   // a new coaster opens on lane 0 (D248)
        editor.refresh();
        syncWizardPanel();
        if (!viewports.isEmpty()) {
            viewports.get(0).applyCameraWorld(frameCoasterPose());
        }
    }

    /** OPEN the wizard for a FLOATING piece in the active coaster. */
    void openFloatingWizard() {
        if (!hasCoaster()) return;
        if (focusOpenWizard()) return;
        // the wizard piece appends at the LIST END: an orphan bridge
        // there would seize it as its target and move the ORIGINAL
        // track (the copyRun rule, D248) — refused BEFORE the undo
        // push; the '+' item is grayed with the same sentence
        if (coaster.listEndsInOrphanBridge()) {
            statsLabel.setText(GraphEditor.ORPHAN_END_WHY);
            return;
        }
        editor.pushUndoSnapshot();
        double[] end = activePieceEnd();
        double x = 0, y = NEW_START_Y, z = 0, yaw = 0, v = NEW_START_SPEED;
        if (end != null) {
            x = end[0]; y = end[1]; z = end[2]; yaw = end[3];
            v = Math.max(1, end[4]);
        }
        TrackPiece piece = new TrackPiece(x, y, z, 0);
        piece.orientMode = TrackPiece.OrientMode.LOCKSTEP;   // both tiers while the wizard owns it (D237/D249)
        piece.uiLockPitch = false;   // the rings build for the wizard piece
        piece.uiLockRoll = false;
        wizardOrient(piece, yaw, 0, 0);
        piece.sections.add(placeholderSection(v));
        coaster.pieces.add(piece);
        int idx = coaster.pieces.size() - 1;
        markDirty();
        wizardState = new WizardState(StartPanel.Mode.FLOATING.ordinal(),
            activeCoaster, idx, true, false,   // default pick (D232)
            NEW_START_LEN, Coaster.CONT_HOLD_DIST);
        wizardOpen = true;
        wizardPanel = null;
        editor.setActivePiece(idx);   // rebuilds
        rebuildTrack();
        editor.defaultLane();   // a new piece opens on lane 0 (D248)
        editor.refresh();
        syncWizardPanel();
    }

    /** Is (coasterIdx, pieceIdx) the wizard's still-PENDING piece?
     *  (renders nothing) */
    boolean isPendingPiece(int coasterIdx, int pieceIdx) {
        return wizardState != null && !wizardState.picked()
            && wizardState.coasterIdx() == coasterIdx
            && wizardState.pieceIdx() == pieceIdx;
    }

    /** Is (coasterIdx, pieceIdx) the OPEN wizard's piece — picked or
     *  not (v19.19, D248 review fix)? isPendingPiece is the RENDER
     *  gate (a picked piece draws), but the wizard OWNS its piece
     *  until Done/Cancel and WizardState.pieceIdx is a raw index —
     *  and since D232 the Force/Geometric tile is picked from open,
     *  so the selector / '+' attach / bridge lists must gray on THIS
     *  test: a reorder would strand the record on another piece and
     *  turn the wizard's placeholder into real track. */
    boolean isWizardPiece(int coasterIdx, int pieceIdx) {
        return wizardState != null
            && wizardState.coasterIdx() == coasterIdx
            && wizardState.pieceIdx() == pieceIdx;
    }

    /** The wizard's piece, or null when it is gone/none. */
    TrackPiece wizardPiece() {
        if (wizardState == null) return null;
        if (wizardState.coasterIdx() >= coasters.size()) return null;
        Coaster c = coasters.get(wizardState.coasterIdx());
        if (wizardState.pieceIdx() >= c.pieces.size()) return null;
        return c.pieces.get(wizardState.pieceIdx());
    }

    Coaster wizardCoaster() {
        if (wizardState == null || wizardState.coasterIdx() >= coasters.size()) {
            return null;
        }
        return coasters.get(wizardState.coasterIdx());
    }

    /** Show/hide the wizard panel per the document + session state and
     *  the current selection (call after every activation change and
     *  every restore). */
    /** The start wizard's model hook (D247): registered while the
     *  panel shows, removed when it hides; syncFromModel no-ops
     *  without a wizard state and skips focused boxes. */
    private final Runnable wizardHook = () -> {
        if (wizardPanel != null && wizardState != null) {
            wizardPanel.syncFromModel();
        }
    };

    void syncWizardPanel() {
        if (editor == null) return;
        boolean valid = wizardState != null && wizardPiece() != null;
        if (valid && !wizardState.picked()) wizardOpen = true;   // the trap rule
        boolean targetActive = valid && wizardState.coasterIdx() == activeCoaster
            && (wizardState.mode() == StartPanel.Mode.NEW_COASTER.ordinal()
                || editor.activePiece() == wizardState.pieceIdx());
        if (!valid) {
            wizardOpen = false;
            wizardPanel = null;
        }
        boolean show = valid && wizardOpen && targetActive;
        if (show) {
            if (wizardPanel == null || !wizardPanel.targets(wizardState)) {
                wizardPanel = new StartPanel(this, wizardState);
            }
            editor.showStartPanel(wizardPanel);
            wizardPanel.syncFromModel();
            // LIVE (D247): non-placement edits (Coaster Options
            // friction, undo) reach the wizard's boxes too
            if (!modelHooks.contains(wizardHook)) modelHooks.add(wizardHook);
        } else {
            editor.hideStartPanel();
            modelHooks.remove(wizardHook);
        }
        // the wizard's Placement rows own the piece while the panel
        // shows — the Track Piece dropdown grays (v19.7, D236; the
        // grayHint wrapper carries the hover reason)
        wizardBlocksPlacement = show;   // the junction button reads it (D248)
        if (placementBtn != null) placementBtn.setDisable(show);
        if (show) {
            // the wizard's rows own the piece: the placement windows
            // that REFUSE to open while it shows close too (D248
            // review fix — Confirm semantics, nothing reverts)
            closeTrackPieceWindow();
            closeBridgeWindow();
        }
        syncJunctionButton();
    }

    /** Orientation as the wizard means it: yaw REPORTED (D186), pitch/
     *  roll on BOTH tiers — the design frame, no lock conflict. */
    private static void wizardOrient(TrackPiece p, double yaw,
                                     double pitch, double roll) {
        p.yawDeg = -yaw;
        p.pitchDeg = pitch;
        p.designPitchDeg = pitch;
        p.rollDeg = roll;
        p.designRollDeg = roll;
    }

    // ---- wizard edits (each = one undo step, pushed here) ----

    void wizardPickType(boolean sc, Section sec) {
        TrackPiece p = wizardPiece();
        if (p == null) return;
        editor.pushUndoSnapshot();
        if (p.sections.isEmpty()) p.sections.add(sec);
        else p.sections.set(0, sec);
        wizardState = wizardWithLen(new WizardState(wizardState.mode(),
            wizardState.coasterIdx(), wizardState.pieceIdx(), true, sc,
            wizardState.lenTime(), wizardState.lenDist()), sec);
        markDirty();
        editor.repointActive();
        rebuildTrack();
        editor.refresh();
    }

    void wizardReplaceSection(Section sec) {
        TrackPiece p = wizardPiece();
        if (p == null) return;
        editor.pushUndoSnapshot();
        if (p.sections.isEmpty()) p.sections.add(sec);
        else p.sections.set(0, sec);
        wizardState = wizardWithLen(wizardState, sec);
        markDirty();
        editor.repointActive();
        rebuildTrack();
        editor.refresh();
    }

    /** Fold the built section's length back into the wizard record's
     *  per-domain size memory (v19.7, D236 — the Initial-size
     *  spinner; s/c lands in the distance slot). */
    private static WizardState wizardWithLen(WizardState ws, Section sec) {
        double len = -1;
        boolean dist = true;
        if (sec instanceof StraightCurveSection scs) {
            len = scs.totalLen();
        } else if (sec instanceof LaneSection ls) {
            dist = ls.domain() == Section.Domain.DISTANCE;
            len = 0;
            for (List<BezierBaker.Node> ln : ls.lanes) {
                if (!ln.isEmpty()) {
                    len = Math.max(len, ln.get(ln.size() - 1).t);
                }
            }
        }
        if (len <= 0) return ws;
        return new WizardState(ws.mode(), ws.coasterIdx(), ws.pieceIdx(),
            ws.picked(), ws.sc(),
            dist ? ws.lenTime() : len, dist ? len : ws.lenDist());
    }

    void wizardPlace(double x, double y, double z,
                     double yaw, double pitch, double roll) {
        TrackPiece p = wizardPiece();
        if (p == null) return;
        editor.pushUndoSnapshot();
        p.x = x;
        p.y = y;
        p.z = z;
        wizardOrient(p, yaw, pitch, roll);
        markDirty();
        rebuildTrack();
        syncPlacementSpinners();
        editor.refresh();
    }

    void wizardSetSpeed(double speed) {
        TrackPiece p = wizardPiece();
        Coaster c = wizardCoaster();
        if (p == null || c == null) return;
        editor.pushUndoSnapshot();
        if (!p.sections.isEmpty()) p.sections.get(0).vDesign(speed);
        if (wizardState.mode() == StartPanel.Mode.NEW_COASTER.ordinal()) {
            c.v0 = speed;
            if (c == coaster) {
                boolean was = docApplying;
                docApplying = true;
                startSpeedSpinner.getValueFactory().setValue(speed);
                docApplying = was;
            }
        }
        markDirty();
        rebuildTrack();
        editor.refresh();
    }

    void wizardSetFriction(double mu, double kair) {
        Coaster c = wizardCoaster();
        if (c == null) return;
        editor.pushUndoSnapshot();
        c.fricRoll = mu;
        c.fricAir = kair;
        if (c == coaster) {
            boolean was = docApplying;
            docApplying = true;
            fricRollSpinner.getValueFactory().setValue(mu);
            fricAirSpinner.getValueFactory().setValue(kair);
            docApplying = was;
        }
        markDirty();
        rebuildTrack();
        editor.refresh();
    }

    void wizardSetName(String name) {
        Coaster c = wizardCoaster();
        if (c == null || name == null || name.isBlank()) return;
        if (c.name.equals(name.trim())) return;
        editor.pushUndoSnapshot();
        c.name = name.trim();
        markDirty();
        updateTitle();
    }

    /** DONE: the piece/coaster stays; the panel closes for good
     *  (session), the wizard record is cleared from the document. */
    void wizardFinish() {
        if (wizardState == null) return;
        TrackPiece wp = wizardPiece();
        editor.pushUndoSnapshot();
        // the wizard edited both tiers freely (LOCKSTEP + open
        // padlocks, the gizmo parity rule, D237); the finished piece
        // ships with the default PADLOCKS — LOCKSTEP stays (D248/D249:
        // it IS the default)
        if (wp != null) {
            wp.uiLockPitch = true;
            wp.uiLockRoll = true;
        }
        wizardState = null;
        wizardOpen = false;
        wizardPanel = null;
        editor.hideStartPanel();
        rebuildTrack();
        editor.defaultLane();   // belt: Done lands on lane 0 too (D248)
        editor.refresh();
        syncPlacementSpinners();
        updatePieceButton();
        // recompute EVERY wizard-owned lockout (the Track Piece
        // button stayed stuck disabled when this was skipped — the
        // user's "settings not selectable after Create" bug, D237)
        syncWizardPanel();
    }

    /** CANCEL: the wizard's piece (or whole coaster) is removed — one
     *  undo step, like every other wizard edit. */
    void wizardCancel() {
        if (wizardState == null) return;
        editor.pushUndoSnapshot();
        WizardState ws = wizardState;
        wizardState = null;
        wizardOpen = false;
        wizardPanel = null;
        editor.hideStartPanel();
        if (ws.mode() == StartPanel.Mode.NEW_COASTER.ordinal()) {
            if (ws.coasterIdx() < coasters.size()) {
                if (ws.coasterIdx() != activeCoaster) switchCoasterForce(ws.coasterIdx());
                deleteActiveCoaster();
            }
        } else if (ws.coasterIdx() < coasters.size()) {
            Coaster c = coasters.get(ws.coasterIdx());
            if (ws.pieceIdx() < c.pieces.size()) {
                c.pieces.remove(ws.pieceIdx());
                markDirty();
                if (c == coaster) editor.resetSections();
            }
        }
        rebuildTrack();
        editor.refresh();
        syncWizardPanel();   // release the wizard lockouts (D237)
        syncPlacementSpinners();
        updatePieceButton();
        syncNoCoasterPanel();
    }

    /** No-coaster panel sync (D230): the editor shows a placeholder
     *  while the document has no coaster. Since v19.10 (D239) it
     *  also drives the EMPTY-COASTER placeholder — a coaster with
     *  zero pieces (its last piece moved away via Switch Coaster)
     *  offers a centered "Add Floating Piece" button; the wizard's
     *  placeholder piece clears the condition while it is open. */
    void syncNoCoasterPanel() {
        if (editor == null) return;
        editor.setNoCoaster(!hasCoaster());
        editor.setEmptyPiece(hasCoaster() && coaster.pieces.isEmpty());
    }

    /** Snapshot the UI-side per-coaster cosmetics for DesignIO. */
    private DesignIO.Cosmetics uiCosmetics() {
        double[] rail = { railColor.getRed(), railColor.getGreen(), railColor.getBlue() };
        return new DesignIO.Cosmetics(heartOffset, railGauge, tieSpacing,
            railSolid, rail, flatten(gradColors), gradStops.clone(),
            flatten(latColors), latStops.clone(),
            // the PRIMARY pane's fly/orbit pose rides the file
            // (v18.38, D210); loading brings the view back here
            viewports.isEmpty() ? null : viewports.get(0).cameraWorld());
    }

    private static double[] flatten(Color[] cols) {
        double[] out = new double[cols.length * 3];
        for (int i = 0; i < cols.length; i++) {
            out[i * 3]     = cols[i].getRed();
            out[i * 3 + 1] = cols[i].getGreen();
            out[i * 3 + 2] = cols[i].getBlue();
        }
        return out;
    }

    private static void unflatten(double[] rgb, Color[] into) {
        for (int i = 0; i < into.length; i++) {
            into[i] = Color.color(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]);
        }
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

            editor.hideStartPanel();   // a pending start screen dies (D226)
            wizardState = null;
            wizardOpen = false;
            wizardPanel = null;
            editor.pushUndoSnapshot(); // loading is undoable
            // MULTI-COASTER (D230): the file's coasters REPLACE the
            // document; each keeps its look; the first becomes active
            // (an empty FVDD4 file = an empty document, no exception)
            coasters.clear();
            otherCompiles.clear();
            docGen++;   // fresh piece objects (v18.33, D205)
            for (int i = 0; i < r.coasters().size(); i++) {
                Coaster c = r.coasters().get(i);
                c.look = i < r.looks().size() ? r.looks().get(i)
                                              : DesignIO.Cosmetics.defaults();
                coasters.add(c);
            }
            if (coasters.isEmpty()) {
                coaster = emptyCoaster;
                activeCoaster = 0;
                applyLook(DesignIO.Cosmetics.defaults());
            } else {
                activeCoaster = 0;
                coaster = coasters.get(0);
                applyLook(lookOf(coaster));
                boolean was = docApplying;
                docApplying = true;
                startSpeedSpinner.getValueFactory().setValue(coaster.v0);
                fricRollSpinner.getValueFactory().setValue(coaster.fricRoll);
                fricAirSpinner.getValueFactory().setValue(coaster.fricAir);
                docApplying = was;
                editor.resetSections();   // active section 0, lanes re-pointed
            }
            syncPlacementSpinners();   // active piece (0 after reset)
            updatePieceButton();
            editor.clearSelection();
            clearResultingPicks();   // opens start overlay-free (v18.10)
            rebuildTrack();      // spans before the first graph layout:
            editor.refresh();    // s/c strip widths come from the compile
            syncNoCoasterPanel();
            // the saved camera pose returns (v18.38, D210 — after the
            // rebuild so the pane's world offsets are current); files
            // without a camera line leave the view where it is
            if (r.camera() != null && !viewports.isEmpty()) {
                viewports.get(0).applyCameraWorld(r.camera());
            }
            currentFile = f;
            // a FAIL-SOFT load (v18.44, D216) repaired the file:
            // say so and mark dirty — the next save writes it clean
            if (r.warnings() > 0) {
                dirty = true;
                updateTitle();
                statsLabel.setText("Loaded " + f.getName() + " with "
                    + r.warnings() + " damaged line(s) skipped/healed — "
                    + "check the layout; saving writes a clean file.");
            } else {
                dirty = false;
                updateTitle();
                statsLabel.setText("Loaded: " + f.getName());
            }
        } catch (Exception ex) {
            statsLabel.setText("Load failed: " + ex.getMessage());
        }
    }

    /** File > Export… (or Ctrl+E): resample the current track for
     *  NoLimits 2 — the spline CSV or an .nl2elem ELEMENT (v18.29,
     *  D201, the FVD++ "NoLimits 2 Exporter" format). The settings
     *  (format, which line, spacing) are app-wide and persist across
     *  runs; the math lives in Nl2CsvExporter / Nl2ElemExporter. */
    private void exportDialog() {
        // the export is a derived artifact of REST — never snapshot a
        // fast-only compile (D200 verify round)
        settleIfPending();
        if (lastSamples == null || lastSamples.size() < 2) return;

        Stage dlg = new Stage();
        dlg.initOwner(stage);
        // modeless since v18.1 (D166): the viewport stays reachable
        dlg.setTitle("Export");

        ToggleGroup fmtGroup = new ToggleGroup();
        RadioButton csvBtn = new RadioButton("Spline (CSV)");
        csvBtn.setToggleGroup(fmtGroup);
        RadioButton elemBtn = new RadioButton("NL2 Element");
        elemBtn.setToggleGroup(fmtGroup);
        (settings.nl2Elem() ? elemBtn : csvBtn).setSelected(true);
        fmtGroup.selectedToggleProperty().addListener((obs, o, sel) -> {
            boolean elem = sel == elemBtn;
            pushMenuEdit(() -> { (elem ? csvBtn : elemBtn).setSelected(true); settings.setNl2Elem(!elem); },
                         () -> { (elem ? elemBtn : csvBtn).setSelected(true); settings.setNl2Elem(elem); });
            settings.setNl2Elem(elem);
        });

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
        numericEditor(segSp, false);   // v18.1 numeric sweep
        segSp.setPrefWidth(80);
        dialogs.wireOptionSpinner(segSp, settings::setNl2SegLen);

        // COASTER picker (D230): a multi-coaster document exports one
        // coaster at a time — the active one by default; an inactive
        // one uses its cached compile
        final Coaster[] expC = { coaster };
        final List<FVDIntegrator.Sample>[] expSamples = new List[]{ lastSamples };
        final List<TrackCompiler.Span>[] expSpans = new List[]{ sectionSpans };
        // LIVE TRACK (v19.18, D247): the dialog is modeless, so the
        // exported samples are RE-READ from the current compile at
        // export time and on every model hook — the open-time capture
        // exported a stale track after any edit. A coaster that
        // vanished under the dialog (an undo) falls back to the
        // active one.
        Runnable reread = () -> {
            if (!coasters.contains(expC[0])) expC[0] = coaster;
            if (expC[0] == coaster) {
                expSamples[0] = lastSamples;
                expSpans[0] = sectionSpans;
            } else {
                TrackCompiler.Result r = compiledFor(expC[0]);
                expSamples[0] = r != null ? r.samples() : null;
                expSpans[0] = r != null ? r.spans() : null;
            }
        };
        ChoiceBox<String> coasterBox = new ChoiceBox<>();
        for (Coaster c : coasters) coasterBox.getItems().add(c.name);
        coasterBox.getSelectionModel().select(Math.max(0, activeCoaster));
        coasterBox.setFocusTraversable(false);
        HBox coasterRow = new HBox(8, new Label("Coaster:"), coasterBox);
        coasterRow.setAlignment(Pos.CENTER_LEFT);
        coasterRow.setVisible(coasters.size() > 1);
        coasterRow.setManaged(coasters.size() > 1);

        // TRACK PIECE picker (D223, user spec; RUNS since v19.19,
        // D248): "Whole track" or one track piece = a connected RUN,
        // which exports its contiguous compiled sample range (every
        // member piece incl. its bridges + the seam sample a continued
        // head drops — Coaster.runSampleRange). Hidden for one-run
        // docs. expHeads mirrors the list so the export action maps
        // the pick to its head.
        ChoiceBox<String> pieceBox = new ChoiceBox<>();
        pieceBox.setFocusTraversable(false);
        HBox pieceRow = new HBox(8, new Label("Track piece:"), pieceBox);
        pieceRow.setAlignment(Pos.CENTER_LEFT);
        List<Integer> expHeads = new ArrayList<>();
        Runnable fillPieces = () -> {
            List<Integer> heads = expC[0].runHeads();
            int nr = heads.size();
            List<String> items = new ArrayList<>();
            items.add("Whole track");
            for (int i = 0; i < nr; i++) {
                items.add(String.format(Locale.US,
                    "Piece %d/%d  (%s)", i + 1, nr,
                    runLengthLabel(expC[0], expSpans[0], heads.get(i))));
            }
            // a live refresh (D247) keeps the pick while the HEAD LIST
            // holds (D248 — a reorder that keeps the run count would
            // otherwise export a different run under the kept index);
            // equal labels + heads are a no-op
            boolean sameHeads = heads.equals(expHeads);
            expHeads.clear();
            expHeads.addAll(heads);
            if (sameHeads && items.equals(pieceBox.getItems())) return;
            int keep = sameHeads && pieceBox.getItems().size() == items.size()
                ? pieceBox.getSelectionModel().getSelectedIndex() : 0;
            pieceBox.getItems().setAll(items);
            pieceBox.getSelectionModel().select(Math.max(0, keep));
            pieceRow.setVisible(nr > 1);
            pieceRow.setManaged(nr > 1);
        };
        fillPieces.run();
        coasterBox.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
            int i = b.intValue();
            if (i < 0 || i >= coasters.size()) return;
            expC[0] = coasters.get(i);
            reread.run();
            fillPieces.run();
        });
        // the piece labels (lengths) follow every compile (D247)
        Runnable live = () -> {
            if (!dlg.isShowing()) return;
            reread.run();
            fillPieces.run();
        };
        modelHooks.add(live);
        // de-registered on hide through an ADDED handler (D247 review
        // fix): Stage.onHidden is one property, and the endMenuSession
        // setOnHidden further down replaced a setOnHidden here — the
        // hook then leaked for the session, one more per Ctrl+E
        dlg.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN,
            e -> modelHooks.remove(live));

        Button exportBtn = new Button("Export…");
        exportBtn.setDefaultButton(true);
        exportBtn.setOnAction(e -> {
            boolean elem = elemBtn.isSelected();
            // the export is a derived artifact of REST — settle, then
            // read the CURRENT track (D200 / D247); the pick reads
            // AFTER the settle (D248 review fix: a settle-time hook
            // refill that changed the head list resets the box to
            // "Whole track", and a pre-read index would have named
            // another run in the refilled expHeads)
            settleIfPending();
            reread.run();
            int pick = pieceBox.getSelectionModel().getSelectedIndex() - 1;
            List<FVDIntegrator.Sample> src = expSamples[0];
            List<TrackCompiler.Span> spans = expSpans[0];
            Coaster ec = expC[0];
            if (src == null) {
                statsLabel.setText("Export: that coaster has no track");
                return;
            }
            String base = coasters.size() > 1
                ? ec.name.replaceAll("[^A-Za-z0-9._-]+", "_") : "coaster";
            if (pick >= 0 && pick < expHeads.size() && spans != null) {
                // the picked RUN's contiguous range (D248; the D84
                // seam-sample rule lives in Coaster.runSampleRange)
                int[] range = Coaster.runSampleRange(ec, spans, expHeads.get(pick));
                if (range != null) {
                    src = src.subList(range[0], range[1] + 1);
                    base = base + "-piece" + (pick + 1);
                }
            }
            if (src.size() < 2) {
                statsLabel.setText("Export: nothing to export for that piece");
                return;
            }
            final List<FVDIntegrator.Sample> exportSrc = src;
            FileChooser fc = new FileChooser();
            fc.setTitle("Export");
            fc.getExtensionFilters().add(elem
                ? new FileChooser.ExtensionFilter("NL2 element (*.nl2elem)", "*.nl2elem")
                : new FileChooser.ExtensionFilter("NL2 track (*.csv)", "*.csv"));
            fc.setInitialFileName(base + (elem ? ".nl2elem" : ".csv"));
            File f = fc.showSaveDialog(dlg);
            if (f == null) return;
            try (PrintWriter out = new PrintWriter(f, "UTF-8")) {
                int points;
                double stepUsed, lenUsed;
                if (elem) {
                    Nl2ElemExporter.Result r = Nl2ElemExporter.export(
                        exportSrc, heartBtn.isSelected(), heartOffset, segSp.getValue());
                    out.print(r.xml());
                    points = r.points();
                    stepUsed = r.step();
                    lenUsed = r.length();
                } else {
                    Nl2CsvExporter.Result r = Nl2CsvExporter.export(
                        exportSrc, heartBtn.isSelected(), heartOffset, segSp.getValue());
                    out.print(r.csv());
                    points = r.points();
                    stepUsed = r.step();
                    lenUsed = r.length();
                }
                statsLabel.setText(String.format(Locale.US,
                    "Exported: %s (%d points, %.2f m spacing, %.0f m)",
                    f.getName(), points, stepUsed, lenUsed));
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

        VBox box = new VBox(8, coasterRow, pieceRow, new Label("Format:"), csvBtn, elemBtn,
            new Label("Track points:"), heartBtn, spineBtn,
            segRow, buttons);
        box.setPadding(new Insets(14));

        Scene sc = new Scene(box);
        installMenuUndoKeys(sc);
        dlg.setScene(sc);
        beginMenuSession();
        dlg.setOnHidden(e -> endMenuSession());
        dlg.show();
        installViewKeys(dlg.getScene());
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
        // delegate to the D241 launcher so the Resolution Scale
        // applies even here — though a direct `java coaster.
        // FvdDesigner` can never honor it (the java launcher
        // pre-initializes the toolkit for an Application main class
        // before main() runs); pom.xml launches uvd.Main.
        Main.main(args);
    }
}

