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
import javafx.scene.control.Control;
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
import javafx.scene.control.Toggle;
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
 * The force-curve GRAPH EDITOR (Phase 1 milestone B split, D52) —
 * extracted verbatim from FvdDesigner with outer references made
 * explicit through `app`. All behavior decisions and their history
 * live in docs/DECISIONS.md; this class is the node/handle editing
 * surface: lanes, selection, drags, undo history, node dialog.
 */
class GraphEditor extends Region {

    /** The shell: document, settings, viewport and pipeline access. */
    private final FvdDesigner app;

    // ---------- sections (Phase 1 final, D55) ----------
    // the piece's sections render as strips along one shared time axis;
    // the ACTIVE one is editable, greyed ones activate on click, and a
    // '+' affordance past the ride end appends a new section. All node
    // math stays SECTION-LOCAL: ltToX/xToLt add/remove the section's
    // global start, so with one section (tOff = 0) everything below is
    // bit-identical to the pre-section editor.
    private int activeSection = 0;
    private double tOff = 0;                         // active section's global start
    private double addBtnX = -999, addBtnY = -999;   // '+' hit centre (px)

    // ---------- pieces (v12.0, D70) ----------
    // the graph shows ONE piece's sections at a time (SECTIONS-DESIGN
    // §8); the toolbar selector / 3D Ctrl+click switch pieces. All
    // compiled lookups stay in GLOBAL ride time (pieces concatenate on
    // the timeline), so the active piece's strips live in the window
    // [pieceT0, pieceT0 + pieceDur] and everything derived just works.
    private int activePiece = 0;

    int activePiece() { return activePiece; }

    private List<Section> sections() {
        // NO-COASTER mode (D230): an empty document has no pieces — the
        // editor is covered by its placeholder, but per-rebuild reads
        // still land here; an empty list keeps them harmless
        if (app.coaster.pieces.isEmpty()) return NO_SECTIONS;
        return app.coaster.pieces.get(
            (int) FvdDesigner.clamp(activePiece, 0, app.coaster.pieces.size() - 1))
            .sections;
    }

    private static final List<Section> NO_SECTIONS = new ArrayList<>();

    /** Flattened span index of piece p's first section. */
    private int pieceBase(int p) {
        int n = 0;
        for (int q = 0; q < p && q < app.coaster.pieces.size(); q++) {
            n += app.coaster.pieces.get(q).sections.size();
        }
        return n;
    }

    /** Flattened span index of the active piece's first section. */
    private int spanBase() {
        return pieceBase(activePiece);
    }

    /** Span of the active piece's section k, or null (pre-compile /
     *  stale). Structural edits rebuild BEFORE relayout, so live use
     *  always sees fresh spans. k may reach past the piece (negative /
     *  ≥ size): the flattened index lands on the neighboring piece's
     *  spans — cross-piece boundary matching inside a run rides on
     *  this. */
    private TrackCompiler.Span spanOf(int k) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        int idx = spanBase() + k;
        return spans != null && idx >= 0 && idx < spans.size()
            ? spans.get(idx) : null;
    }

    /** Global sample range {i0, i1} of the ACTIVE section's span —
     *  the viewport's white-tie highlight (v16.11); null before a
     *  compile. */
    int[] activeSpanRange() {
        TrackCompiler.Span sp = spanOf(activeSection);
        return sp == null ? null : new int[]{ sp.i0(), sp.i1() };
    }

    /** A clean left-click on a 3D node marker (v16.11, user spec):
     *  select that node of the active lane and bring it into the
     *  graph view — a manual zoom window re-centers on the node when
     *  it sits outside; auto-fit already shows everything. */
    /** Gizmo gesture-end refresh (v18.20, D191 — user report: the
     *  resulting/derived graph curves lagged behind piece moves until
     *  the next graph hover): recompute strip offsets and redraw. */
    void redrawFromViewport() {
        refreshOffset();
        redraw();
    }

    /** Viewport empty-click deselect (v18.19, D190 — graph-editor
     *  parity: a clean click not on a marker clears the selection).
     *  Also a PLOT-SIDE TAKE-OVER (v19.20, D249): a lit speed control
     *  / mark unlights too — takePlotSelection runs BEFORE the early
     *  return (gizmo/ball presses and empty-track clicks land here). */
    void deselectFromViewport() {
        boolean strip = takePlotSelection();
        if (selIdx < 0 && selSet.isEmpty()) {
            if (strip) redraw();   // the strip must unlight
            return;
        }
        selIdx = -1;
        selSet.clear();
        refreshMarkers();
        redraw();
    }

    /** A node MARKER's single click: the node owns the selection —
     *  takePlotSelection first (D249: under a lit control spFocus was
     *  left true, so the index landed on the STRIP surface — the
     *  "markers unselectable while a control is selected" bug). */
    void selectNodeFromViewport(int idx) {
        List<BezierBaker.Node> ln = active();
        if (idx < 0 || idx >= ln.size()) return;
        takePlotSelection();
        refreshOffset();
        selIdx = idx;
        selSet.clear();
        selSet.add(idx);
        ensureSelectedVisible();
        redraw();
    }

    /** A node MARKER's double-click (v19.19, D248): select the node
     *  and open its LIVE editor — the graph's own node double-click.
     *  Refuses with a stats reason while the density preview / split
     *  line owns the document (the graph path refuses silently at its
     *  press guard; D235 wants the sentence) and while the start
     *  screen is up (its rows own the piece's speed and placement —
     *  node 0's row would write v0 behind the WizardState). Clears
     *  the strip focus first: spDoubleClick shares selIdx with the
     *  main lane, and the marker set is always the main lane's. */
    void openNodeEditorFromViewport(int idx) {
        if (app.convertBusy()) {
            app.statsLabel.setText(FvdDesigner.CONVERT_BUSY_MSG);
            return;
        }
        if (startPanelActive()) {
            app.statsLabel.setText(WIZARD_OPEN_WHY);
            return;
        }
        List<BezierBaker.Node> ln = active();
        if (idx < 0 || idx >= ln.size()) return;
        takePlotSelection();   // the one choke since D249 (was a bare spFocus = false)
        selectNodeFromViewport(idx);
        showNodeEditor(idx);
    }

    /** Re-center a MANUAL zoom window on the selected node when it
     *  sits outside (auto-fit already shows everything) — marker
     *  clicks and 3D double-click inserts share it (v16.12). */
    private void ensureSelectedVisible() {
        List<BezierBaker.Node> ln = active();
        if (viewT1 < 0 || selIdx < 0 || selIdx >= ln.size()) return;
        double x = tOff + warpFwd(ln.get(selIdx).t);
        if (x >= viewT0 && x <= viewT1) return;
        double w = viewT1 - viewT0;
        double n0 = FvdDesigner.clamp(x - w / 2, runT0(),
            Math.max(runT0(), runT0() + domain() - w));
        viewT0 = n0;
        viewT1 = n0 + w;
    }

    /** Span of piece p's section k, or null. */
    private TrackCompiler.Span spanAt(int p, int k) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        if (spans == null) return null;
        int idx = pieceBase(p) + k;
        return idx >= 0 && idx < spans.size() ? spans.get(idx) : null;
    }

    /** AXIS coordinate where the active piece begins (global ride time
     *  or global arc length, per the display domain). */
    private double pieceT0() {
        if (activePiece == 0) return 0;
        TrackCompiler.Span sp = spanOf(0);
        return sp != null ? (axisDist() ? sp.s0() : sp.t0()) : 0;
    }

    // ---------- bridged RUNS (v13.4) ----------
    // pieces chained by trailing bridges read and edit as ONE track:
    // the graph shows every section of the run containing the active
    // piece — strips, badges, boundary matching and the '+' all span
    // it. A single unbridged piece is a run of one (bit-identical to
    // the old behavior).

    private boolean endsWithBridge(int p) {
        return app.coaster.endsWithBridge(p);   // ONE definition (D248)
    }

    /** Piece p is a STRICT-SEAM follower of p−1 (v17.1, D135) — the
     *  same rule the compiler applies: follow is ignored when
     *  bridged-in or orphaned. Delegates to Coaster since v19.19
     *  (D248 — the run topology has one definition). */
    boolean followsPrev(int p) {
        return app.coaster.followsPrev(p);
    }

    /** First piece of the connected run containing the active piece —
     *  pieces chained by trailing bridges OR strict seams (v17.1). */
    private int runStart() {
        return app.coaster.runStartOf(activePiece);
    }

    /** Last piece of the connected run containing the active piece. */
    private int runEnd() {
        return app.coaster.runEndOf(activePiece);
    }

    /** Run bounds for the viewport (v13.4): the WHOLE bridged run
     *  renders full-color — it reads as one track. */
    int runStartPiece() { return runStart(); }

    int runEndPiece() { return runEnd(); }

    // ---- NAMING (v19.19, D248 — user spec; SectionNames holds the
    // text, LabelGate pins it) ----

    /** "Force Section n" / "Geometric Section n" / "S/C Section n" /
     *  "Bridge Section n": the per-TYPE ordinal of (p, k) across the
     *  WHOLE run in run order. Badges, the badge menu's title row,
     *  the range/Convert/Orientation/Insert dialogs and the resolve
     *  gray reasons all read it. */
    String sectionTitle(int p, int k) {
        return SectionNames.sectionTitle(app.coaster, p, k);
    }

    /** 0 = piece p is its run's head (no junction), else the 1-based
     *  index of the boundary entering p among the run's boundaries —
     *  plain strict seams AND bridge exits counted together. */
    int junctionOrdinal(int p) {
        return SectionNames.junctionOrdinal(app.coaster, p);
    }

    /** "Strict Seam n" / "Strict Seam n (bridge)"; null at a run head. */
    String junctionTitle(int p) {
        return SectionNames.junctionTitle(app.coaster, p);
    }

    /** EXACTLY the toolbar text that owns (p, k)'s placement:
     *  "Track Piece ▴" with no junction left of the section, else the
     *  junction's title + " ▴" (a bridge section: its own exit). */
    String ownerButtonLabel(int p, int k) {
        return SectionNames.ownerButtonLabel(app.coaster, p, k);
    }

    /** Extent of piece p's section k in the current AXIS units (the
     *  sectionDur recipe, any piece): seconds on the time axis, meters
     *  on the distance axis (v16.0, D103). Native-axis sections read
     *  their authored extent exactly; the rest read the compiled span
     *  (with pre-compile estimates). */
    private double durOf(int p, int k) {
        Section s = app.coaster.pieces.get(p).sections.get(k);
        if (axisDist()) {
            if (s instanceof StraightCurveSection sc) {
                return Math.max(sc.totalLen(), 1e-6);   // native meters
            }
            if (s instanceof LaneSection ls
                    && ls.domain() == Section.Domain.DISTANCE) {
                return Math.max(BezierBaker.rideEnd(ls.lanes), 1e-6);
            }
            TrackCompiler.Span sp = spanAt(p, k);
            if (sp != null) return Math.max(sp.sLen(), 1e-6);
            if (s instanceof LaneSection ls) {   // pre-compile estimate
                return Math.max(1e-6, BezierBaker.rideEnd(ls.lanes)
                    * Math.max(0.5, ls.vDesign));
            }
            BridgeSection bs = (BridgeSection) s;
            return Math.max(0.1,
                bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length());
        }
        if (s instanceof LaneSection ls) {
            if (ls.influenced()) {
                // an influenced section's duration is the RE-TIMED
                // clock (t*_end) — read the compiled span like s/c;
                // pre-compile, estimate by the speed ratio
                TrackCompiler.Span isp = spanAt(p, k);
                if (isp != null) return isp.t1() - isp.t0();
                return BezierBaker.rideEnd(ls.lanes)
                    * ls.vAuthored / Math.max(0.5, ls.vDesign);
            }
            if (ls.domain() == Section.Domain.DISTANCE) {
                // a DISTANCE section's DURATION exists only after a
                // compile — the s/c rule exactly
                TrackCompiler.Span dsp = spanAt(p, k);
                if (dsp != null) return dsp.t1() - dsp.t0();
                return BezierBaker.rideEnd(ls.lanes)
                    / Math.max(0.5, ls.vDesign);
            }
            return BezierBaker.rideEnd(ls.lanes);
        }
        TrackCompiler.Span sp = spanAt(p, k);
        if (sp != null) return sp.t1() - sp.t0();
        if (s instanceof StraightCurveSection sc) return sc.estDuration();
        BridgeSection bs = (BridgeSection) s;   // pre-compile estimate
        return Math.max(0.1,
            bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length()
                / Math.max(0.5, bs.vDesign));
    }

    /** AXIS coordinate where the RUN begins. */
    private double runT0() {
        int rs = runStart();
        if (rs == 0) return 0;
        TrackCompiler.Span sp = spanAt(rs, 0);
        return sp != null ? (axisDist() ? sp.s0() : sp.t0()) : 0;
    }

    /** Total duration of the run (the graph window's width basis). */
    private double runDur() {
        double d = 0;
        for (int p = runStart(); p <= runEnd(); p++) {
            for (int k = 0; k < app.coaster.pieces.get(p).sections.size(); k++) {
                d += durOf(p, k);
            }
        }
        return d;
    }

    /** One strip of the run's flattened layout. */
    private record RunSec(int piece, int section, Section sec,
                          double t0, double dur) {}

    /** The run's sections in timeline order with their global starts —
     *  the strip/badge/hit layer iterates this. */
    private List<RunSec> runLayout() {
        List<RunSec> out = new ArrayList<>();
        double off = runT0();
        for (int p = runStart(); p <= runEnd(); p++) {
            List<Section> secs = app.coaster.pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++) {
                double d = durOf(p, k);
                out.add(new RunSec(p, k, secs.get(k), off, d));
                off += d;
            }
        }
        return out;
    }

    // ---------- STRICT SEAMS (v17.1, D135) ----------
    // clicking a boundary LINE between strips opens the seam menu:
    // make-strict / settings / un-strict / split. Bridge-adjacent
    // boundaries are the bridge's own surface and take no seam menu.

    /** The runLayout boundary index within grab range of (x, y): the
     *  boundary AFTER rl[i] (between rl[i] and rl[i+1]); −1 = none.
     *  Bridge-adjacent boundaries are excluded. */
    private int hitSeam(double x, double y) {
        if (y < MARGIN_TOP || y > MARGIN_TOP + plotHeight()) return -1;
        List<RunSec> rl = runLayout();
        for (int i = 0; i < rl.size() - 1; i++) {
            double bx = tToX(rl.get(i).t0() + rl.get(i).dur());
            if (Math.abs(x - bx) > 4) continue;
            if (rl.get(i).sec() instanceof BridgeSection
                    || rl.get(i + 1).sec() instanceof BridgeSection) {
                return -1;   // the bridge's own surface
            }
            return i;
        }
        return -1;
    }

    /** Is the boundary after rl[i] a STRICT seam (a follower piece's
     *  entry)? */
    private boolean seamStrict(List<RunSec> rl, int i) {
        return i + 1 < rl.size() && rl.get(i + 1).section() == 0
            && followsPrev(rl.get(i + 1).piece());
    }

    /** The seam menu's "Insert section…" row (v19.18, D247): pL/kL =
     *  the upstream piece + its left section, insertIdx = where the
     *  section lands in pL, strict = the boundary is a strict seam.
     *  Pre-checked per the D235 rule — an uncompiled boundary grays
     *  with the reason (the hold recipe needs the seam sample). The
     *  action ACTIVATES the upstream section first (the Connect
     *  handler's pattern) so the dialog's reads address pL. */
    private MenuItem insertSectionItem(int pL, int kL, int insertIdx,
                                       boolean strict) {
        if (spanAt(pL, kL) == null) {
            return grayItem("Insert section…",
                "This boundary isn't compiled yet.");
        }
        MenuItem insert = new MenuItem("Insert section…");
        insert.setOnAction(ev -> {
            activateRunSec(pL, kL);
            showInsertSectionDialog(pL, kL, insertIdx, strict);
        });
        return insert;
    }

    /** The seam dropdown (user spec): make-strict on ordinary section
     *  boundaries; settings / un-strict on strict ones; split always. */
    private void showSeamMenu(int i, double screenX, double screenY) {
        List<RunSec> rl = runLayout();
        if (i < 0 || i + 1 >= rl.size()) return;
        RunSec right = rl.get(i + 1);
        boolean strict = seamStrict(rl, i);
        ContextMenu cm = new ContextMenu();
        if (strict) {
            // "Strict Seam n…" — the junction's run ordinal, the seam
            // window's own title (v19.19, D248; "Strict seam
            // orientation…" until then)
            MenuItem settings = new MenuItem(seamTitle(right.piece()) + "…");
            settings.setOnAction(ev ->
                showStrictSeamSettings(right.piece()));
            // INSERT SECTION… (v19.18, D247): on a strict seam the
            // section APPENDS to the UPSTREAM piece before the seam —
            // the follower keeps its shape and held angles, its
            // position follows the new exit
            int upL = right.piece() - 1;
            int upK = app.coaster.pieces.get(upL).sections.size() - 1;
            MenuItem insert = insertSectionItem(upL, upK, upK + 1, true);
            // ENABLE/DISABLE smoothing (v17.4, D141 — user spec): off
            // by default on hand-made strict seams; bridge converts
            // and piece connects arrive with it ON at 1 m
            TrackPiece fsm = app.coaster.pieces.get(right.piece());
            // smoothing is NOT an option on the edge of an s/c
            // section (v19.3, D232 — user spec: its own entry ease
            // conflicts and the window has no lane form); an already-
            // smoothed old file can still DISABLE. Gray reasons ride
            // hover tooltips since v19.7 (D236, grayItem).
            boolean scEdge = seamScEdge(right.piece());
            MenuItem smTog;
            if (scEdge && fsm.sSmoothLen <= 0.01) {
                smTog = grayItem("Enable smoothing",
                    "Smoothing cannot be enabled on a straight/curve"
                    + " edge — the s/c entry ease owns that seam.");
            } else {
                smTog = new MenuItem(fsm.sSmoothLen > 0.01
                    ? "Disable smoothing"
                    : "Enable smoothing (10 m)");
                smTog.setOnAction(ev -> {
                    pushUndoSnapshot();
                    fsm.sSmoothLen = fsm.sSmoothLen > 0.01 ? 0 : 10;
                    app.markDirty();
                    app.rebuildTrack();
                    redraw();
                });
            }
            // MAKE SMOOTHING REAL (v19.3, D232): the blend window
            // becomes authored lanes of the upstream section's type,
            // guarded by a fresh strict seam at the window's end —
            // through the density preview like every convert.
            // PRE-CHECKED at menu build since v19.6 (D235, user rule
            // "never a click that does nothing"): the dry-run's
            // refusal grays the item with a SHORT reason.
            String smWhy = fsm.sSmoothLen <= 0.01
                ? "Enable smoothing on this seam first."
                : app.coaster.makeSmoothingRealWhy(
                    right.piece(), FvdDesigner.INTEGRATE_DT);
            MenuItem smReal;
            if (smWhy != null) {
                // the FULL dry-run sentence rides the tooltip now
                // (D236 — shortWhy's ≤6-word compression retired)
                smReal = grayItem("Make smoothing real…", smWhy);
            } else {
                smReal = new MenuItem("Make smoothing real…");
                smReal.setOnAction(ev ->
                    makeSmoothingRealPreview(right.piece()));
            }
            MenuItem un = new MenuItem("Un-strict seam");   // (D232:
                // the explainer parenthetical left the label)
            un.setOnAction(ev -> {
                // the seam is about to vanish (D247 review fix) AND the
                // pieces after it shift down one — the index-resolved
                // bridge window closes too (D248 review fix)
                hideJunctionWindows();
                pushUndoSnapshot();
                int fp = right.piece();
                if (app.coaster.unstrictSeam(fp)) {
                    if (activePiece >= fp) {
                        activePiece = Math.max(0, activePiece - 1);
                    }
                    activeSection = (int) FvdDesigner.clamp(activeSection,
                        0, sections().size() - 1);
                    app.lanes = lanesFor(sections().get(activeSection));
                    syncLaneButtons();
                    app.markDirty();
                    app.rebuildTrack();
                    app.updatePieceButton();
                    app.syncPlacementSpinners();
                    refreshOffset();
                } else {
                    // never silent (D235)
                    app.statsLabel.setText("Un-strict refused — the "
                        + "previous piece must not end with a bridge.");
                }
                redraw();
            });
            MenuItem split = new MenuItem(
                "Split track at seam (detach piece)");
            split.setOnAction(ev -> {
                hideSeamPopup();   // the seam is about to vanish (D247 review fix)
                pushUndoSnapshot();
                if (app.coaster.detachStrictSeam(right.piece(),
                        FvdDesigner.INTEGRATE_DT)) {
                    app.markDirty();
                    app.rebuildTrack();
                    app.syncPlacementSpinners();
                    refreshOffset();
                } else {
                    app.statsLabel.setText("Split refused — nothing "
                        + "compiled at this seam yet.");   // D235
                }
                redraw();
            });
            cm.getItems().addAll(settings, insert, smTog, smReal, un, split);
        } else if (right.section() > 0) {
            // CONNECT the two sections into one (D136 — the badge's
            // "Connect with previous section", now living here):
            // like sections only, the mergeSections rules (an
            // unmatched seam keeps BOTH values as a steep step since
            // D247 — no averaging)
            List<Section> ps =
                app.coaster.pieces.get(right.piece()).sections;
            Section sa = ps.get(right.section() - 1);
            Section sb = ps.get(right.section());
            boolean like = sa instanceof LaneSection la
                && sb instanceof LaneSection lb
                && sa.getClass() == sb.getClass()
                && sa.domain() == sb.domain()
                && la.rollGlobal == lb.rollGlobal
                && la.yawPitchGlobal == lb.yawPitchGlobal
                && la.ratePerMeter == lb.ratePerMeter
                && la.rollAbsolute == lb.rollAbsolute
                && la.ypAbsolute == lb.ypAbsolute
                && !sa.reversed() && !sb.reversed()
                && !la.influenced() && !lb.influenced();
            MenuItem conn;
            if (like) {
                conn = new MenuItem("Connect sections (merge into one)");
                final int rp = right.piece(), rk = right.section();
                conn.setOnAction(ev -> {
                    activateRunSec(rp, rk);
                    connectSections(rk - 1);
                });
            } else {
                conn = grayItem("Connect sections (merge into one)",
                    "Needs two adjacent sections of the same type,"
                    + " domain and axis settings.");
            }
            cm.getItems().add(conn);
            // INSERT SECTION… (v19.18, D247): lands at this boundary
            // inside the piece; its "Lock Subsequent Track Shape"
            // makes the seam after it strict first
            cm.getItems().add(insertSectionItem(right.piece(),
                right.section() - 1, right.section(), false));
            MenuItem make = new MenuItem(
                "Make seam strict (freeze downstream shape)…");
            make.setOnAction(ev -> {
                // the split inserts a piece: every later index shifts,
                // so the index-resolved junction windows close first
                // (D248 review fix)
                hideJunctionWindows();
                pushUndoSnapshot();
                int np = app.coaster.makeSeamStrict(right.piece(),
                    right.section(), FvdDesigner.INTEGRATE_DT);
                if (np >= 0) {
                    activateRunSec(np, 0);
                    app.markDirty();
                    app.rebuildTrack();
                    app.updatePieceButton();
                    app.syncPlacementSpinners();
                    refreshOffset();
                    redraw();
                    showStrictSeamSettings(np);
                } else {
                    app.statsLabel.setText("Make strict refused — this "
                        + "boundary isn't compiled yet.");   // D235
                    redraw();   // refused: the snapshot is a no-op
                }
            });
            MenuItem split = new MenuItem("Split track at seam");
            split.setOnAction(ev -> {
                activateRunSec(right.piece(), right.section());
                splitAt(right.section());
            });
            cm.getItems().addAll(make, split);
        } else {
            return;   // detached-piece junctions aren't seams
        }
        // click feedback (D136): the seam stays highlighted while its
        // menu is open
        openSeam = i;
        cm.setOnHidden(ev -> {
            openSeam = -1;
            redraw();
        });
        cm.show(canvas, screenX, screenY);
        openMenu = cm;
        redraw();
    }

    /** The open strict seam WINDOW (v19.19, D248 — an owned Stage via
     *  LiveWindow; a Popup until then) + the coaster it opened on;
     *  null = closed. NULL-BEFORE-HIDE through hideSeamPopup. */
    private Stage seamWin;
    private Coaster seamWinCoaster;
    /** The open seam window's FOLLOWER index (D248 review fix — the
     *  toolbar toggle compares it, the bridgeWinPiece pattern); −1
     *  while closed. */
    private int seamWinPiece = -1;
    private boolean seamSyncing;
    /** The open seam window's ONE read-only resync (v19.18, D247):
     *  every row (padlock, value, enabled state), the design
     *  readouts and the resolve buttons' visibility refresh from it.
     *  Spinner writes inside it are SKIPPED while that spinner's
     *  editor holds focus (a typed value is never overwritten). Set
     *  by showStrictSeamSettings, nulled by hideSeamPopup; registered
     *  as a model hook (seamHook) on show. Null = no window. Never
     *  pushes undo or marks dirty. */
    Runnable seamSync;
    /** The FORCED twin (D248): runs from docRestoreHooks after an
     *  undo/redo restore — the restored values land even in a focused
     *  box (index-resolved handlers replaced the D247 in-place
     *  re-open). */
    private Runnable seamForceSync;

    /** The ONE stable model-hook Runnable for the seam window (D247):
     *  registered in FvdDesigner.modelHooks when the window shows,
     *  removed in hideSeamPopup; runs the current seamSync (which is
     *  itself identity-guarded, so a stale window's sync is inert). */
    private final Runnable seamHook = () -> {
        Runnable s = seamSync;
        if (s != null) s.run();
    };

    /** Its docRestoreHooks twin (D248). */
    private final Runnable seamRestoreHook = () -> {
        Runnable s = seamForceSync;
        if (s != null) s.run();
    };

    /** The strict seam's WORLD position — the follower's first
     *  compiled sample; the 3D seam gizmo anchors here (v17.3,
     *  D140). Null without a compile. */
    Vec3 seamWorldPos(int followerIdx) {
        TrackCompiler.Span sp = spanAt(followerIdx, 0);
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) return null;
        return app.lastSamples.get(sp.i0()).pos();
    }

    /** The follower's EFFECTIVE entry orientation (the follow
     *  contract) — the seam gizmo's ring axes read it (v17.3). */
    Quat seamEntryOrient(int followerIdx) {
        TrackPiece f = app.coaster.pieces.get(followerIdx);
        if (followerIdx > 0 && f.followPrev) {
            TrackPiece prev = app.coaster.pieces.get(followerIdx - 1);
            TrackCompiler.Span psp = prev.sections.isEmpty() ? null
                : spanAt(followerIdx - 1, prev.sections.size() - 1);
            if (psp != null) {
                return Coaster.strictEntry(psp.exit(), f).orient();
            }
        }
        return f.entryPose().orient();
    }

    /** ORIENTATION CONFLICT of piece p (v19.18, D247 — the one
     *  predicate the badge's ≠o, the seam window's readout/buttons
     *  and the range-resolve trigger key off): a strict-seam FOLLOWER
     *  is conflicted when its seam tilt (real follow contract vs its
     *  design orientation — Coaster.seamConflictTilt off the previous
     *  piece's compiled exit) has a horizontal axis component; a
     *  pure heading re-aim is rigid and never counts. False before
     *  the first compile (no span to read). Every other piece keeps
     *  TrackPiece.orientConflict() (the two stored tiers). */
    boolean orientConflictAt(int p) {
        if (p < 0 || p >= app.coaster.pieces.size()) return false;
        if (followsPrev(p)) {
            TrackPiece prev = app.coaster.pieces.get(p - 1);
            TrackCompiler.Span psp = spanAt(p - 1, prev.sections.size() - 1);
            if (psp == null) return false;
            return Coaster.isSeamOrientConflict(
                app.coaster.seamConflictTilt(p, psp.exit()));
        }
        return app.coaster.pieces.get(p).orientConflict();
    }

    /** THE seam write choke (v19.18, D247; mode-keyed v19.20, D249):
     *  run a follower's seam write (held angles / locks) through
     *  app.placementEdit with the orientation-mode routing on top —
     *  the follower's REAL {heading, elevation, bank} (followerEuler,
     *  the follow contract) is read before and after the write, and
     *  under LOCKSTEP the DESIGN tier moves by the same Euler delta
     *  (Coaster.seamDesignLockstep: the design/real difference stays
     *  constant, the shape re-solves — the D222 invariance on the
     *  seam). REAL_ONLY = the real angles move alone and the seam's
     *  conflict absorbs the edit — today's locked behavior bitwise
     *  (no design write at all). DESIGN_ONLY moves the design frame
     *  ONLY: a write that changes just the held pitch/roll VALUES
     *  (locks and yaw as before) has its real Euler delta applied to
     *  the design tier (Coaster.applyDesignEulerDelta) and the held
     *  values RESTORED, so the real entry never moves and the held
     *  angles stay untouched; padlock toggles and yaw are real-side
     *  in every mode (the locks are the real contract, heading is
     *  shared). The seam window's design boxes use the explicit
     *  seamEditDesign path instead (an unlocked axis's held value
     *  cannot move the real, so there is no delta to divert).
     *  Heading never touches the design tier. Every seam write routes
     *  here: the pop-out's spinners, padlocks (a toggle-OFF jumps the
     *  real angle onto the upstream value = an adjustment), ↺ and
     *  Cancel, and both gizmo paths below. Callers push undo /
     *  markDirty / rebuild themselves. */
    void seamEdit(int fi, Runnable write) {
        if (fi < 0 || fi >= app.coaster.pieces.size()) {
            write.run();
            return;
        }
        TrackPiece f = app.coaster.pieces.get(fi);
        TrackPiece.OrientMode mode = f.orientMode;
        double[] before = mode == TrackPiece.OrientMode.REAL_ONLY
            ? null : followerEuler(fi);
        // the DESIGN_ONLY divert reads the held state around the write
        final double hp0 = f.sPitchDeg, hr0 = f.sRollDeg;
        final boolean lp0 = f.sLockPitch, lr0 = f.sLockRoll,
            ly0 = f.sLockYaw;
        app.placementEdit(fi, () -> {
            write.run();
            if (mode == TrackPiece.OrientMode.LOCKSTEP && before != null
                    && !Coaster.seamDesignLockstep(f, before,
                        followerEuler(fi))) {
                // the pole guard (D247 review fix): the canonical
                // triples can't attribute a crossing, so the tier
                // stays put and the seam window is the place to fix it
                app.statsLabel.setText(SEAM_POLE_MSG);
            } else if (mode == TrackPiece.OrientMode.DESIGN_ONLY
                    && before != null
                    && f.sLockPitch == lp0 && f.sLockRoll == lr0
                    && f.sLockYaw == ly0
                    && (f.sPitchDeg != hp0 || f.sRollDeg != hr0)) {
                double[] after = followerEuler(fi);
                f.sPitchDeg = hp0;   // the real entry never moves
                f.sRollDeg = hr0;
                if (!Coaster.applyDesignEulerDelta(f, before, after)) {
                    app.statsLabel.setText(SEAM_POLE_MSG);
                }
            }
        });
    }

    /** The seam window's yaw-row tooltip (D249): heading is
     *  placement — one number for both tiers. */
    static final String YAW_SHARED_TIP = "Heading is shared by the real"
        + " and design orientation — this row always edits the held/real"
        + " heading.";
    /** The seam window's design-box gray / refusal (D249). */
    static final String DESIGN_VERTICAL_MSG = "The design orientation"
        + " points straight up/down — set the real orientation first.";

    /** The pole guard's stats reason (D247 review fix; shared by the
     *  LOCKSTEP and DESIGN_ONLY seam branches since D249). */
    static final String SEAM_POLE_MSG = "Seam edit crossed straight"
        + " up/down — the design tier was left alone;"
        + " adjust it in the seam window.";

    /** DESIGN-tier seam write (v19.20, D249 — the seam window's
     *  pitch/roll boxes and the seam rig's rings while the follower
     *  edits its design orientation): design elevation (axis 1) /
     *  bank (axis 2) := deg through Coaster.seamDesignSet; held
     *  angles, locks and the real entry untouched, no carry (the real
     *  never moves). A vertical design frame refuses with a stats
     *  reason. Callers push undo / markDirty / rebuild themselves;
     *  returns whether anything was written. */
    boolean seamEditDesign(int fi, int axis, double deg) {
        if (fi < 0 || fi >= app.coaster.pieces.size()) return false;
        TrackPiece f = app.coaster.pieces.get(fi);
        if (!Coaster.seamDesignSet(f, axis, deg)) {
            // ONE sentence for the vertical refusal (D249 review fix:
            // the box's gray, the ↺ and this write said it two ways)
            app.statsLabel.setText(axis == 0
                ? "Heading is shared by both tiers — edit it as the"
                    + " real yaw."
                : DESIGN_VERTICAL_MSG);
            return false;
        }
        return true;
    }

    /** Seam-gizmo write path (v17.3, D140): set a DISCONTINUOUS
     *  axis's held angle. No undo of its own — the viewport pushes
     *  one per gesture, the gizmo contract. Routes through seamEdit
     *  since D247 (the Lock-Track-Shape lockstep). */
    void gizmoSetSeamOrient(int pieceIdx, int axis, double deg) {
        if (pieceIdx < 0 || pieceIdx >= app.coaster.pieces.size()) {
            return;
        }
        TrackPiece f = app.coaster.pieces.get(pieceIdx);
        if (!f.followPrev) return;
        double v = FvdDesigner.clamp(deg, -360, 360);
        boolean designAxis = axis != 0
            && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        if (!designAxis && (axis == 0 ? !f.sLockYaw
                : axis == 1 ? !f.sLockPitch : !f.sLockRoll)) {
            return;
        }
        if (designAxis) {
            // DESIGN_ONLY (v19.20, D249 — item O): the seam rig is
            // re-aimed at the DESIGN frame and its pitch/roll rings
            // build regardless of the padlocks with baseVal = the
            // design elevation/bank (Viewport3D.buildSeamGizmo), so
            // `deg` IS the absolute design angle — Coaster.seamDesignSet
            // through seamEditDesign; the real entry stays put, the
            // held angles and locks are untouched
            if (!seamEditDesign(pieceIdx, axis, v)) return;
        } else {
            seamEdit(pieceIdx, () -> {   // D221 rigid carry + D247 lockstep
                if (axis == 0) f.sYawDeg = v;
                else if (axis == 1) f.sPitchDeg = v;
                else f.sRollDeg = v;
            });
        }
        app.markDirty();
        app.rebuildTrack();
        redraw();
    }

    /** The local ring's COMBINED seam write (v18.19, D190): all
     *  three euler values in one apply — only DISCONTINUOUS axes
     *  take theirs (following axes hold no number), one rebuild. */
    void gizmoSetSeamOrientAll(int pieceIdx, double yaw, double pitch,
                               double roll) {
        if (pieceIdx < 0 || pieceIdx >= app.coaster.pieces.size()) {
            return;
        }
        TrackPiece f = app.coaster.pieces.get(pieceIdx);
        if (!f.followPrev) return;
        if (f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY) {
            // DESIGN_ONLY (D249, item O): the local sink rotates the
            // DESIGN pose (Viewport3D.seamLocal) and hands over the
            // rotated frame's eulers — the design-frame write path
            gizmoSetSeamDesignAll(pieceIdx, yaw, pitch, roll);
            return;
        }
        seamEdit(pieceIdx, () -> {   // D221 rigid carry + D247 lockstep
            if (f.sLockYaw) {
                f.sYawDeg = FvdDesigner.clamp(yaw, -360, 360);
            }
            if (f.sLockPitch) {
                f.sPitchDeg = FvdDesigner.clamp(pitch, -360, 360);
            }
            if (f.sLockRoll) {
                f.sRollDeg = FvdDesigner.clamp(roll, -360, 360);
            }
        });
        app.markDirty();
        app.rebuildTrack();
        redraw();
    }

    /** The seam rig's COMBINED write under DESIGN_ONLY (v19.20, D249
     *  — item O): the local sink rotated the DESIGN pose about the
     *  drawn axis and passes the rotated frame's eulers — heldYaw =
     *  the held heading the press-time yaw plus the rotation's
     *  heading delta (written to the REAL-side held yaw when that
     *  axis is locked: heading is placement, never a design
     *  quantity), elev / bank = the ABSOLUTE design elevation / bank
     *  (Coaster.seamDesignSet, elevation first so the bank write
     *  keeps it exact). The real entry never moves through the design
     *  writes; a vertical design frame refuses with the stats reason.
     *  One rebuild; no undo of its own (the gizmo contract). */
    void gizmoSetSeamDesignAll(int pieceIdx, double heldYaw, double elev,
                               double bank) {
        if (pieceIdx < 0 || pieceIdx >= app.coaster.pieces.size()) {
            return;
        }
        TrackPiece f = app.coaster.pieces.get(pieceIdx);
        if (!f.followPrev) return;
        boolean any = false;
        // the held yaw writes only when it actually CHANGED (D249
        // review fix): the roll ring — and the pitch ring on an
        // unbanked design frame or under Global axes — hands back the
        // press-time heading plus eulerOf's ~1e-14° dust, and the
        // write ran through seamEdit → placementEdit on every drag
        // event, re-aiming the REAL entry the design mode promises
        // never moves (plus a carry under affectSubsequentSeams). The
        // Local-mode banked pitch ring's heading delta (degrees —
        // heading is placement) still lands; floating dust never does.
        if (f.sLockYaw && !Double.isNaN(heldYaw)
                && Math.abs(TrackPiece.wrap180(heldYaw - f.sYawDeg)) > 1e-9) {
            seamEdit(pieceIdx, () ->
                f.sYawDeg = FvdDesigner.clamp(heldYaw, -360, 360));
            any = true;
        }
        if (!Double.isNaN(elev) && seamEditDesign(pieceIdx, 1, elev)) {
            any = true;
        }
        if (!Double.isNaN(bank) && seamEditDesign(pieceIdx, 2, bank)) {
            any = true;
        }
        if (!any) return;
        app.markDirty();
        app.rebuildTrack();
        redraw();
    }

    /** The follower's CURRENT effective entry orientation as
     *  {heading, elevation, bank} (deg). Read ANALYTICALLY from the
     *  follow contract (previous piece's exit through strictEntry)
     *  since v17.2 — with seam SMOOTHING on, the compiled entry
     *  sample sits inside the blend window and showed (and toggle-ON
     *  captured) the half-blended angle. Sample read = the fallback;
     *  last resort the discontinuous values. Package-private since
     *  D247 (the seam window's real readout + seamEdit's lockstep
     *  read it; the design readout is eulerOf(designPose())). */
    double[] followerEuler(int followerIdx) {
        TrackPiece f = app.coaster.pieces.get(followerIdx);
        if (followerIdx > 0 && f.followPrev) {
            TrackPiece prev = app.coaster.pieces.get(followerIdx - 1);
            TrackCompiler.Span psp = prev.sections.isEmpty() ? null
                : spanAt(followerIdx - 1, prev.sections.size() - 1);
            if (psp != null) {
                double[] e = Coaster.eulerOf(
                    Coaster.strictEntry(psp.exit(), f).orient());
                if (!Double.isNaN(e[0]) && !Double.isNaN(e[2])) return e;
            }
        }
        TrackCompiler.Span sp = spanAt(followerIdx, 0);
        if (sp != null && app.lastSamples != null
                && !app.lastSamples.isEmpty()) {
            int i0 = Math.min(sp.i0(), app.lastSamples.size() - 1);
            FVDIntegrator.Sample sm = app.lastSamples.get(i0);
            double[] e = Coaster.eulerOf(Quat.fromFrame(sm.T(), sm.U()));
            if (!Double.isNaN(e[0]) && !Double.isNaN(e[2])) return e;
        }
        return new double[]{ f.sYawDeg, f.sPitchDeg, f.sRollDeg };
    }

    // (shortWhy — D235's ≤6-word parenthetical compressor — retired
    // in v19.7, D236: grayItem rows carry the FULL dry-run sentence
    // on a hover tooltip instead.)

    /** Tear the strict seam window down NULL-BEFORE-HIDE with the
     *  sync mute held across the close (v19.18, D247 — THE undo bug's
     *  root cause: closing un-focuses the window's focus owner, the
     *  auto-focused spinner editor, whose focus-loss commit re-parsed
     *  the "#.##" text into a value that differed from the stored
     *  full-precision angle; the value listener then ran as a user
     *  edit — pushing a snapshot and clearing the redo branch: undo
     *  worked once, then Ctrl+Z / Ctrl+Shift+Z were dead). With the
     *  field nulled first every handler's identity guard (win !=
     *  seamWin, via seamLive) rejects the stray event, and
     *  seamSyncing covers the value listener's first check. Every
     *  teardown site routes through here; the live-window hooks
     *  de-register here (D247/D248). An owned Stage needs no
     *  app-focus hide (the D235 hookSeamFocus pattern is gone). */
    void hideSeamPopup() {
        Stage old = seamWin;
        if (old == null) return;
        seamWin = null;
        seamWinCoaster = null;
        seamWinPiece = -1;
        seamSync = null;   // the window's resyncs die with it (D247)
        seamForceSync = null;
        app.modelHooks.remove(seamHook);   // live-window de-registration
        docRestoreHooks.remove(seamRestoreHook);
        seamSyncing = true;
        try {
            old.close();
        } finally {
            seamSyncing = false;
        }
    }

    /** Hide BOTH junction windows before a structural op that shifts
     *  piece indices (D248 — the seam window and the bridge window
     *  are index-resolved; a shifted index would point them at the
     *  wrong junction). */
    void hideJunctionWindows() {
        hideSeamPopup();
        app.closeBridgeWindow();
    }

    /** Pre-hide the speed-control popup before a structural op that
     *  removes its section (D249 — the Track Piece window's Delete…;
     *  null-before-hide, the spAfterRebuild self-hide would only
     *  catch it AFTER the rebuild). */
    void hideSpeedPopup() { spHideCtlPopup(); }

    /** The strict seam window's SELF-VALIDATION (D247 review fix —
     *  the spAfterRebuild pattern; INDEX-based since D248): true only
     *  while `win` is still the live window AND piece `idx` of the
     *  window's coaster still follows a strict seam. A window
     *  outlived by its seam (a structural op that shifted the piece
     *  list, a piece that stopped following, a coaster switch) tears
     *  itself down here with a stats reason instead of reading — or
     *  writing — the wrong piece; a window that was already torn
     *  down (win != seamWin) is simply inert. */
    private boolean seamLive(Stage win, int idx) {
        if (win == null || win != seamWin) return false;
        if (app.coaster == seamWinCoaster && idx >= 0
                && idx < app.coaster.pieces.size() && followsPrev(idx)) {
            return true;
        }
        hideSeamPopup();
        app.statsLabel.setText("The strict seam window closed — its seam"
            + " no longer exists.");
        return false;
    }

    /** The seam window's "Design → Real Orientation" (v19.18, D247):
     *  adopt the follower's REAL orientation (the follow contract) as
     *  its design — Coaster.bakeFollow: baseOrient := the effective
     *  entry, both tiers zero, the follow + locks + held angles KEPT
     *  (it does not detach). The graphs stay, the shape re-solves at
     *  the real frame, the seam's ≠o closes. With ABSOLUTE lanes on
     *  the follower the D240 Preserve/Convert dialog intervenes (the
     *  Track Piece window's rule — Coaster.designToRealConvert bakes
     *  the follow for an active follower). One undo step. */
    void seamDesignToReal(int fi) {
        if (!app.coaster.followerActive(fi)) {
            app.statsLabel.setText("Design → Real refused — this piece"
                + " no longer follows a strict seam.");
            return;
        }
        TrackPiece f = app.coaster.pieces.get(fi);
        boolean anyYp = false, anyBank = false;
        for (Section s : f.sections) {
            if (s instanceof LaneSection ls && !ls.reversed()) {
                if (ls instanceof GeoSection && ls.ypAbsolute
                        && ls.yawPitchGlobal) {
                    anyYp = true;
                }
                if (ls.rollAbsolute && ls.rollGlobal) anyBank = true;
            }
        }
        if (anyYp || anyBank) {
            showDesignToRealDialog(fi, anyYp, anyBank);
            return;
        }
        // refusal checked BEFORE the snapshot (D247 review fix — a
        // refused op must not clear the redo branch)
        TrackCompiler.Result r = TrackCompiler.compile(app.coaster,
            FvdDesigner.INTEGRATE_DT);
        if (app.coaster.followContract(fi, r) == null) {
            app.statsLabel.setText("Design → Real refused — the seam"
                + " has no compiled exit to read yet.");
            return;
        }
        pushUndoSnapshot();
        app.coaster.bakeFollow(fi, r);
        app.markDirty();
        app.rebuildTrack();
        app.syncPlacementSpinners();
        app.updateBridgeControls();
        redrawFromViewport();
        if (seamSync != null) seamSync.run();
    }

    /** The seam window's "Real → Design Orientation" (v19.18, D247):
     *  hold the follower's pitch and roll AT the design elevation/bank
     *  (Coaster.seamRealToDesign — both padlocks lock, yaw untouched),
     *  so the seam tilt becomes heading-only: shape kept as a rigid
     *  image, ≠o closed. Runs through placementEdit like the panel's
     *  twin — a shape-locked follower carries its own downstream by
     *  the same rigid delta. Refuses with a stats reason when the
     *  design frame is vertical (no angles to hold). One undo step. */
    void seamRealToDesign(int fi) {
        if (!app.coaster.followerActive(fi)) {
            app.statsLabel.setText("Real → Design refused — this piece"
                + " no longer follows a strict seam.");
            return;
        }
        TrackPiece f = app.coaster.pieces.get(fi);
        double[] g = Coaster.eulerOf(f.designPose().orient());
        if (Double.isNaN(g[0]) || Double.isNaN(g[2])) {
            app.statsLabel.setText("Real → Design refused — the design"
                + " orientation points straight up or down, so there"
                + " is no elevation/bank to hold at the seam.");
            return;
        }
        pushUndoSnapshot();
        app.placementEdit(fi, () -> app.coaster.seamRealToDesign(fi));
        app.markDirty();
        app.rebuildTrack();
        app.syncPlacementSpinners();
        app.updateBridgeControls();
        redrawFromViewport();
        if (seamSync != null) seamSync.run();
    }

    /** The seam window's title (v19.19, D248): "Strict Seam n" — the
     *  junction's run ordinal; the bare prefix if the piece is no
     *  follower any more (undo mid-refresh — seamLive tears the
     *  window down on the next hook). */
    String seamTitle(int followerIdx) {
        String t = junctionTitle(followerIdx);
        return t != null ? t : SectionNames.STRICT_SEAM.trim();
    }

    /** THE STRICT SEAM WINDOW — "Strict Seam n" (v19.19, D248 — user
     *  spec: the D136/D204/D247 dark pop-out rebuilt as a LiveWindow
     *  Stage in the unified three-window layout). One row per axis,
     *  VERTICAL: [Yaw (heading °)][spinner][🔒][↺], a thin separator,
     *  [Pitch (°)][spinner][🔒][↺][design elevation], [Roll (°)]
     *  [spinner][🔒][↺][design bank]; then the ORIENTATION-MODE row
     *  (v19.20, D249: "Edit Real and Design Orientation
     *  Independently" → "Edit real orientation (locks track shape)" /
     *  "Edit design orientation" → "Affect Subsequent Strict Seams" —
     *  the follower's own mode; under DESIGN_ONLY the pitch/roll
     *  boxes show and edit the DESIGN elevation/bank — enabled
     *  regardless of the padlocks, Coaster.seamDesignSet, the real
     *  entry and held angles untouched — the readouts read "real …"
     *  (followerEuler) and the yaw row still edits the held/real
     *  heading; the grey readouts show whenever the check is on or
     *  the seam is conflicted and CLICK-SWAP the mode), the Design ↔
     *  Real row (shown while orientConflictAt), and the
     *  [Confirm][Cancel] footer. The seam PADLOCK SEMANTICS are kept
     *  (🔒 red = the axis holds an editable angle, 🔓 green = it
     *  follows the seam — and the bridge padlocks mean the SAME since
     *  v19.20, D249: 🔒 = held/typeable, 🔓 = the bridge's); locking ALWAYS captures
     *  the CURRENT value (nothing jumps, D235); ↺ returns the value
     *  from WHEN THIS WINDOW WAS OPENED, an axis unlocked at open
     *  falling back to the stored placement's angle (a direct attach:
     *  the orientation from BEFORE the attach). Every held-angle
     *  write goes through seamEdit (the D247 lockstep — with the
     *  shape UNLOCKED, the default since D248, the design tier moves
     *  with the seam edit and the follower re-solves). INDEX-RESOLVED
     *  (D248): every handler resolves the follower BY INDEX (`live`)
     *  and the window survives undo/redo through its hooks — the
     *  D247 in-place re-open is gone; seamLive tears it down with a
     *  stats reason when the seam no longer exists or another
     *  coaster is active. Confirm / Enter / X keep, Cancel / Esc
     *  restore EVERY piece's open-time placement (the D249 full-scope
     *  PlacementSnapshot through FvdDesigner.cancelRestore, re-captured
     *  after an undo restore) as one more undo step. Every write
     *  refuses under convertBusy(). */
    void showStrictSeamSettings(int followerIdx) {
        if (followerIdx < 0 || followerIdx >= app.coaster.pieces.size()) {
            LiveWindow.dropAnchor(app);   // D248 review fix: no stale anchor
            return;
        }
        if (!followsPrev(followerIdx)) {
            LiveWindow.dropAnchor(app);
            return;
        }
        hideSeamPopup();   // null-before-hide (v19.18, D247)
        final Coaster openC = app.coaster;
        TrackPiece f0 = openC.pieces.get(followerIdx);
        // the state this open found — Cancel and ↺ revert to it (D235)
        final boolean[] openLocks = { f0.sLockYaw, f0.sLockPitch, f0.sLockRoll };
        final double[] openDegs = { f0.sYawDeg, f0.sPitchDeg, f0.sRollDeg };
        // + the design tier and both routing flags (D247/D248) — and
        // the base the tier was captured AGAINST: a Design → Real bake
        // (direct or through the D240 preview) replaces baseOrient,
        // and restoring the old deltas onto a NEW base would invent a
        // tilt that never existed, so Cancel restores the tier only
        // while the base is the open-time object
        // the open-time DESIGN elevation/bank — ↺ under DESIGN_ONLY
        // returns the design box to it (absolute world angles, valid
        // whatever happened to baseOrient meanwhile — D249)
        final double[] openDesignEuler =
            Coaster.eulerOf(f0.designPose().orient());
        // Cancel's FULL-SCOPE record (D249): every piece + bridge, by
        // identity; re-captured after an undo restore (seamForceSync)
        final PlacementSnapshot[] openSnap = { PlacementSnapshot.capture(openC) };
        final int[] openGen = { app.docGen };
        // LIVE piece resolution (D248): by index, on the open-time
        // coaster — null once the window's seam is gone
        final java.util.function.Supplier<TrackPiece> live = () ->
            app.coaster == openC && followerIdx < app.coaster.pieces.size()
                ? app.coaster.pieces.get(followerIdx) : null;
        final Stage[] winRef = new Stage[1];
        final java.util.function.BooleanSupplier alive = () ->
            seamLive(winRef[0], followerIdx);
        VBox box = new VBox(6);
        // ONE resync for the whole window (D247): the row syncs take a
        // FORCE flag — false (the live hook / a rebuild) skips a
        // spinner whose editor holds focus, true (an explicit action
        // in this window, an undo restore) always writes; tailSync =
        // readouts + button visibility + the flag row
        List<java.util.function.Consumer<Boolean>> rowSyncs =
            new ArrayList<>();
        Runnable[] tailSync = new Runnable[1];
        java.util.function.Consumer<Boolean> syncAll = force -> {
            for (java.util.function.Consumer<Boolean> rs : rowSyncs) {
                rs.accept(force);
            }
            if (tailSync[0] != null) tailSync[0].run();
        };
        final String[] labels = { "Yaw (heading °)", "Pitch (°)", "Roll (°)" };
        // the OTHER-TIER readouts (D249): click = swap the mode through
        // the flag row's one writer (built below; resolved at click)
        final LiveWindow.FlagRow[] flagsRef = new LiveWindow.FlagRow[1];
        final LiveWindow.Readout[] designLbls = { null,
            new LiveWindow.Readout(() -> {
                if (flagsRef[0] != null) flagsRef[0].swap();
            }),
            new LiveWindow.Readout(() -> {
                if (flagsRef[0] != null) flagsRef[0].swap();
            }) };
        for (int a = 0; a < 3; a++) {
            final int axis = a;
            // the seam PADLOCK (v18.32, D204; bridge-style look since
            // D248): 🔒 red = the axis holds an editable angle,
            // 🔓 green = it follows the seam — the bridge padlocks
            // read the same way since D249 (🔒 = held/typeable)
            ToggleButton disc = LiveWindow.lockToggle(
                "Locked = hold the typed angle; unlocked = follow the seam");
            Spinner<Double> sp = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(
                    -360, 360, 0, 1));
            // the seam keeps its ±360 factory on EVERY axis — a loaded
            // held pitch past ±90 (pole passes) must never clip
            sp.setPrefWidth(LiveWindow.SPIN_W);
            sp.setEditable(true);
            FvdDesigner.numericEditor(sp, true);   // + focus commit (D191)
            if (axis == 0) {
                // heading is placement — one tier in every mode (D249)
                Tooltip.install(sp, new Tooltip(YAW_SHARED_TIP));
            }
            Button rst = LiveWindow.revertBtn(
                "Back to the value from when this window was opened"
                + " (an axis locked during this session: back to its"
                + " stored placement angle)");
            // the DESIGN box (D249): under DESIGN_ONLY a pitch/roll row
            // shows the design elevation/bank and is editable regardless
            // of the padlock; a vertical design frame grays it
            java.util.function.Consumer<Boolean> sync = force -> {
                TrackPiece f = live.get();
                if (f == null) return;
                boolean on = axis == 0 ? f.sLockYaw
                    : axis == 1 ? f.sLockPitch : f.sLockRoll;
                boolean dAxis = axis != 0
                    && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
                double[] dg = dAxis ? Coaster.eulerOf(f.designPose().orient())
                                    : null;
                boolean dVert = dAxis && (Double.isNaN(dg[axis])
                    || Math.abs(dg[1]) > 89.9);
                seamSyncing = true;
                try {
                    disc.setSelected(!on);   // selected = 🔓 = follows
                    // a LIVE resync never overwrites a box being typed
                    // into (D247 — the live-window rule; an explicit
                    // action in this window, or an undo restore,
                    // forces the write)
                    if (force || app.docRestoring()
                            || !sp.getEditor().isFocused()) {
                        // yaw DISPLAYS left-positive (v18.15, D186):
                        // the UI negates at this boundary; sYawDeg
                        // stays internal
                        double dv = dAxis ? (dVert ? 0 : dg[axis])
                            : on
                            ? (axis == 0 ? f.sYawDeg
                                : axis == 1 ? f.sPitchDeg : f.sRollDeg)
                            : followerEuler(followerIdx)[axis];
                        sp.getValueFactory().setValue(axis == 0 ? -dv : dv);
                    }
                } finally {
                    seamSyncing = false;
                }
                sp.setDisable(dAxis ? dVert : !on);
                rst.setDisable(dAxis ? dVert : !on);
            };
            rowSyncs.add(sync);
            disc.setOnAction(ev -> {
                // IDENTITY GUARD + self-validation (v19.18, D247): a
                // torn-down window's handlers are inert; + the
                // density-preview transaction guard
                if (!alive.getAsBoolean()) return;
                TrackPiece f = live.get();
                if (f == null) return;
                if (app.convertBusy()) { sync.accept(true); return; }
                pushUndoSnapshot();
                boolean on = !disc.isSelected();
                // the write runs through the D221 carry: unlocking an
                // axis re-aims the follower onto the seam, and a
                // RIGID follower carries its own downstream rigidly —
                // via the seamEdit choke since D247 (a toggle-OFF
                // jumps the real angle onto the upstream value = an
                // adjustment the unlocked-shape lockstep follows)
                seamEdit(followerIdx, () -> {
                    if (on) {
                        // locking ALWAYS holds the CURRENT angle —
                        // nothing ever jumps (v19.6, D235)
                        double cur = followerEuler(followerIdx)[axis];
                        if (axis == 0) f.sYawDeg = cur;
                        else if (axis == 1) f.sPitchDeg = cur;
                        else f.sRollDeg = cur;
                    }
                    if (axis == 0) f.sLockYaw = on;
                    else if (axis == 1) f.sLockPitch = on;
                    else f.sLockRoll = on;
                });
                app.markDirty();
                app.rebuildTrack();
                app.syncPlacementSpinners();
                syncAll.accept(true);
                app.refreshGizmos(false);   // rings follow the locks (D188)
                redraw();
            });
            sp.valueProperty().addListener((o, was, val) -> {
                // seamSyncing mutes the syncs' own writes and the
                // hide-time focus-loss commit of a teardown (D247)
                if (seamSyncing || val == null) return;
                if (!alive.getAsBoolean()) return;
                TrackPiece f = live.get();
                if (f == null) return;
                boolean on = axis == 0 ? f.sLockYaw
                    : axis == 1 ? f.sLockPitch : f.sLockRoll;
                boolean dAxis = axis != 0
                    && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
                if (!on && !dAxis) return;
                if (app.convertBusy()) {
                    // the F3 transaction guard: revert the box muted
                    seamSyncing = true;
                    sp.getValueFactory().setValue(was);
                    seamSyncing = false;
                    return;
                }
                if (dAxis) {
                    // the DESIGN write (D249): absolute design
                    // elevation/bank through Coaster.seamDesignSet —
                    // held angles, locks and the real entry untouched;
                    // a vertical design frame is refused BEFORE the
                    // undo push (the box reverts muted + stats)
                    double[] g = Coaster.eulerOf(f.designPose().orient());
                    if (Double.isNaN(g[1]) || Double.isNaN(g[2])
                            || Math.abs(g[1]) > 89.9) {
                        seamSyncing = true;
                        sp.getValueFactory().setValue(was);
                        seamSyncing = false;
                        app.statsLabel.setText(DESIGN_VERTICAL_MSG);
                        return;
                    }
                    pushUndoSnapshot();
                    seamEditDesign(followerIdx, axis, val);
                    app.markDirty();
                    app.rebuildTrack();
                    redraw();
                    syncAll.accept(false);
                    return;
                }
                pushUndoSnapshot();
                seamEdit(followerIdx, () -> {   // the D247 choke
                    if (axis == 0) {
                        f.sYawDeg = -val;   // typed = reported (D186)
                    } else if (axis == 1) {
                        f.sPitchDeg = val;
                    } else {
                        f.sRollDeg = val;
                    }
                });
                app.markDirty();
                app.rebuildTrack();
                redraw();
                // the readout/buttons follow (the lockstep may have
                // moved the design tier); this box keeps its text
                syncAll.accept(false);
            });
            rst.setOnAction(ev -> {
                if (!alive.getAsBoolean()) return;
                TrackPiece f = live.get();
                if (f == null) return;
                if (app.convertBusy()) return;   // the F3 transaction guard
                if (axis != 0
                        && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY) {
                    // ↺ on the DESIGN box (D249): back to the open-time
                    // design elevation/bank (absolute; refused before
                    // the undo push when either frame is vertical)
                    double v0 = openDesignEuler[axis];
                    double[] g = Coaster.eulerOf(f.designPose().orient());
                    if (Double.isNaN(v0) || Double.isNaN(g[1])
                            || Double.isNaN(g[2]) || Math.abs(g[1]) > 89.9) {
                        app.statsLabel.setText(DESIGN_VERTICAL_MSG);
                        return;
                    }
                    pushUndoSnapshot();
                    seamEditDesign(followerIdx, axis, v0);
                    app.markDirty();
                    app.rebuildTrack();
                    syncAll.accept(true);
                    redraw();
                    return;
                }
                pushUndoSnapshot();
                seamEdit(followerIdx, () -> {   // the D247 choke
                    double v;
                    if (openLocks[axis]) {
                        // held when this window opened → back to that
                        // value (v19.6, D235 — was the creation angle)
                        v = openDegs[axis];
                    } else {
                        // locked during THIS session → back to the
                        // STORED placement's angle (a direct attach:
                        // the orientation from BEFORE the attach)
                        double[] orig = Coaster.eulerOf(
                            f.entryPose().orient());
                        v = axis == 0
                            ? (Double.isNaN(orig[0]) ? 0 : orig[0])
                            : axis == 1 ? orig[1]
                            : (Double.isNaN(orig[2]) ? 0 : orig[2]);
                    }
                    if (axis == 0) f.sYawDeg = v;
                    else if (axis == 1) f.sPitchDeg = v;
                    else f.sRollDeg = v;
                });
                app.markDirty();
                app.rebuildTrack();
                syncAll.accept(true);
                redraw();
            });
            sync.accept(true);
            // grayed-while-following = explained on hover (D235); the
            // design box's own gray = a vertical design frame (D249)
            java.util.function.Supplier<String> spWhy = () -> {
                TrackPiece f = live.get();
                return f != null && axis != 0
                    && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? DESIGN_VERTICAL_MSG
                    : "Following the seam — lock 🔒 to hold an angle.";
            };
            java.util.function.Supplier<String> rstWhy = () -> {
                TrackPiece f = live.get();
                return f != null && axis != 0
                    && f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY
                    ? DESIGN_VERTICAL_MSG
                    : "Lock 🔒 first — ↺ then restores the stored"
                        + " placement's angle.";
            };
            box.getChildren().add(LiveWindow.orientRow(labels[a],
                grayHint(sp, spWhy), disc, grayHint(rst, rstWhy),
                designLbls[a] == null ? null : designLbls[a].node));
            if (a == 0) box.getChildren().add(LiveWindow.thinSeparator());
        }
        // the ORIENTATION-MODE row (D249) — the follower's own; a pure
        // flip moves nothing and rebuilds nothing, one undo step each;
        // a follower is never a bridge target, so the design radio has
        // no exclusion here; a flip re-reads every box (the pitch/roll
        // rows swap tiers), the Track Piece window and the seam rig
        LiveWindow.FlagRow flags = LiveWindow.flagRow(live,
            () -> alive.getAsBoolean() && !app.docApplying()
                && !app.convertBusy(),
            this::pushUndoSnapshot, app::markDirty, null, null, () -> {
                syncAll.accept(true);
                app.syncPlacementSpinners();
                app.refreshGizmos(false);
            });
        flagsRef[0] = flags;
        box.getChildren().add(flags.node);
        // Design ↔ Real (D247), shown while the seam is conflicted;
        // a follower is never march-owned (a bridged-in piece does
        // not follow), so Real → Design never grays here
        LiveWindow.ResolveRow resolve = LiveWindow.resolveRow(
            () -> {
                if (!alive.getAsBoolean() || app.convertBusy()) return;
                seamDesignToReal(followerIdx);
                if (winRef[0] == seamWin) syncAll.accept(true);
            },
            () -> {
                if (!alive.getAsBoolean() || app.convertBusy()) return;
                seamRealToDesign(followerIdx);
                if (winRef[0] == seamWin) syncAll.accept(true);
            },
            () -> null, () -> null);
        box.getChildren().add(resolve.node);
        tailSync[0] = () -> {
            TrackPiece f = live.get();
            if (f == null) return;
            boolean conflict = orientConflictAt(followerIdx);
            // the readouts show the OTHER tier than the boxes edit
            // (D249): the follower's DESIGN orientation = designPose()
            // (base⊕yaw⊕design deltas) [1] elevation, [2] bank — or,
            // under DESIGN_ONLY, the REAL one (followerEuler, the
            // follow contract); shown while conflicted or while the
            // independent check is on, click = swap the mode
            boolean dOnly = f.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
            boolean show = conflict
                || f.orientMode != TrackPiece.OrientMode.LOCKSTEP;
            double[] g = !show ? null
                : dOnly ? followerEuler(followerIdx)
                : Coaster.eulerOf(f.designPose().orient());
            boolean swap = LiveWindow.swappable(f, null);
            String pre = LiveWindow.readoutPrefix(f);
            designLbls[1].set(show, pre, g == null ? Double.NaN : g[1], swap);
            designLbls[2].set(show, pre, g == null ? Double.NaN : g[2], swap);
            resolve.setShown(conflict);
            resolve.sync.run();
            flags.sync.run();
            LiveWindow.refit(app, winRef[0]);   // managed rows changed?
        };
        tailSync[0].run();
        Runnable onConfirm = () -> hideSeamPopup();
        Runnable onCancel = () -> {
            if (!alive.getAsBoolean()) return;
            if (app.convertBusy()) return;   // the F3 transaction guard
            // back to the state this open (or the last undo restore)
            // found — EVERY piece's placement, by identity, direct
            // writes (the D249 full-scope record; the live edits
            // pushed their own undo steps, the revert is one more). A
            // typed-but-unentered box is DISCARDED first (D247)
            if (app.cancelRestore(openSnap[0], openGen[0]) > 0) {
                app.rebuildTrack();
                app.syncPlacementSpinners();
                app.updateBridgeControls();
                app.refreshGizmos(false);
            }
            hideSeamPopup();
            redraw();
        };
        // anchor at the seam's on-screen x (the follower's first
        // strip) when it is on screen, else the toolbar's junction
        // button; a pending gizmo anchor wins inside LiveWindow
        double ax = Double.NaN;
        for (RunSec rs : runLayout()) {
            if (rs.piece() == followerIdx && rs.section() == 0) {
                double sx = tToX(rs.t0());
                if (sx >= MARGIN_LEFT && sx <= canvas.getWidth()) {
                    Point2D at = canvas.localToScreen(sx, 0);
                    if (at != null) ax = at.getX();
                }
                break;
            }
        }
        if (Double.isNaN(ax)) ax = app.junctionButtonAnchorX();
        if (Double.isNaN(ax)) {
            double[] cb = canvasScreenBox();
            ax = cb != null ? cb[0] + MARGIN_LEFT : 0;
        }
        Stage win = LiveWindow.show(app, seamTitle(followerIdx), box,
            onConfirm, onCancel, ax);
        winRef[0] = win;
        seamWin = win;
        seamWinCoaster = openC;
        seamWinPiece = followerIdx;
        seamSync = () -> {
            // a stale hook is inert; a window whose seam is gone tears
            // itself down (D247 review fix)
            if (!seamLive(win, followerIdx)) return;
            win.setTitle(seamTitle(followerIdx));   // D248: live numbering
            syncAll.accept(false);
        };
        seamForceSync = () -> {
            if (!seamLive(win, followerIdx)) return;
            win.setTitle(seamTitle(followerIdx));
            syncAll.accept(true);   // an undo restore outranks typing
            // the restore replaced the piece objects: Cancel's record
            // re-bases on them (the Track Piece / bridge windows'
            // re-capture rule, D249)
            openSnap[0] = PlacementSnapshot.capture(app.coaster);
            openGen[0] = app.docGen;
        };
        // LIVE (D247): the window follows seam ring drags, Track Piece
        // edits and undo through the shell's model hooks — stable
        // Runnables (seamHook / seamRestoreHook) that run whatever
        // seamSync / seamForceSync currently are; de-registered in
        // hideSeamPopup
        if (!app.modelHooks.contains(seamHook)) app.modelHooks.add(seamHook);
        if (!docRestoreHooks.contains(seamRestoreHook)) {
            docRestoreHooks.add(seamRestoreHook);
        }
        // closed by other means (the app closing its owned windows):
        // the same teardown, identity-guarded
        win.setOnHidden(ev -> { if (seamWin == win) hideSeamPopup(); });
    }

    /** The graph canvas's SCREEN box {minX, minY, maxX, maxY} — the
     *  live windows place themselves just above its top edge
     *  (LiveWindow.place, D248); null before the canvas is on
     *  screen. */
    double[] canvasScreenBox() {
        if (canvas.getScene() == null) return null;
        javafx.geometry.Bounds b = canvas.localToScreen(
            canvas.getBoundsInLocal());
        return b == null ? null
            : new double[]{ b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY() };
    }

    /** The screen x of the active lane's node `idx` (the node editor's
     *  placement anchor, D248); NaN when off screen. */
    private double nodeScreenX(int idx) {
        List<BezierBaker.Node> ln = active();
        if (idx < 0 || idx >= ln.size() || canvas.getScene() == null) {
            return Double.NaN;
        }
        double x = ltToX(ln.get(idx).t);
        if (x < MARGIN_LEFT || x > canvas.getWidth()) return Double.NaN;
        Point2D at = canvas.localToScreen(x, 0);
        return at == null ? Double.NaN : at.getX();
    }

    /** The run strip under global time t, or null. */
    private RunSec runSecAt(double globalT) {
        for (RunSec rs : runLayout()) {
            if (globalT >= rs.t0() - 1e-9
                    && globalT <= rs.t0() + rs.dur() + 1e-9) return rs;
        }
        return null;
    }

    /** Activate a section anywhere in the CURRENT run without resetting
     *  the zoom (the run is one continuous editing surface); falls back
     *  to the full piece switch for out-of-run targets. */
    private void activateRunSec(int p, int k) {
        if (p == activePiece) {
            setActiveSection(k);
            return;
        }
        if (p < runStart() || p > runEnd()) {
            setActivePiece(p);
            setActiveSection(k);
            return;
        }
        dropActiveView();   // leaving = back to the true domain (v16.2)
        activePiece = p;
        activeSection = (int) FvdDesigner.clamp(k, 0, sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        canvas.setCursor(Cursor.DEFAULT);
        syncLaneButtons();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        app.rebuildTrack();   // inactive-piece desaturation follows
        refreshOffset();
        redraw();
        app.syncWizardPanel();   // the wizard follows its piece (D231)
    }

    /** Activate piece p's section k from the viewport's REVEAL BALLS
     *  (v19.18, D247 — every junction of the run wears a ball, and a
     *  click activates that piece/section): the in-run switch keeps
     *  the zoom, out-of-run targets take the full piece switch
     *  (activateRunSec's own fallback). NOT activateAt(seam time):
     *  activateGlobal picks the first span with t ≤ t1, and a
     *  follower's first sample time equals the previous span's t1
     *  (D86), so the UPSTREAM section would activate. */
    void activatePieceSection(int p, int k) {
        if (p < 0 || p >= app.coaster.pieces.size()) return;
        activateRunSec(p, k);
    }

    void setActivePiece(int p) {
        if (app.coaster.pieces.isEmpty()) return;   // no coaster (D230)
        p = (int) FvdDesigner.clamp(p, 0, app.coaster.pieces.size() - 1);
        // same-index calls still re-point when app.lanes is stale
        // (v19.11, D240 — the setActiveSection rule at :1743): a
        // zero-piece coaster leaves activePiece 0 + NO_LANES, and
        // the FLOATING wizard's first piece lands at index 0 — the
        // bare == return left a ForceSection paired with empty
        // immutable lanes and redraw threw before the wizard could
        // show (the "Add Floating Piece does nothing" bug)
        if (p == activePiece && activeSection < sections().size()
                && app.lanes == lanesFor(sections().get(activeSection))) {
            return;
        }
        dropActiveView();   // leaving = back to the true domain (v16.2)
        activePiece = p;
        activeSection = 0;
        app.lanes = lanesFor(sections().get(0));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        canvas.setCursor(Cursor.DEFAULT);
        viewT0 = 0;
        viewT1 = -1;          // auto-fit the new piece's stretch
        syncLaneButtons();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        app.rebuildTrack();   // inactive pieces desaturate (v13.2)
        refreshOffset();
        redraw();
        app.syncWizardPanel();   // the wizard follows its piece (D231)
    }

    // ---------- section MODE (Phase 2: force / geo lanes, s/c elements) ----------
    // one editor edits both lane-based modes; only the lane SEMANTICS
    // switch — names, units, default scales, snap steps, lane buttons.
    // A STRAIGHT/CURVE section (v11.0) has no lanes at all: app.lanes
    // points at NO_LANES (every node path naturally empty) and the strip
    // shows the ELEMENT WIDGET instead; the plot stays in FORCE view
    // (activeGeo() false), so other strips and overlays read as felt G.

    /** Shared empty lanes for the no-lane modes: hit tests, markers and
     *  lock enforcement all iterate to nothing. */
    private static final List<List<BezierBaker.Node>> NO_LANES = List.of(
        List.of(), List.of(), List.of());

    private List<List<BezierBaker.Node>> lanesFor(Section s) {
        // INFLUENCE editing (v15.0, D98): the strip edits the DELTA
        // lanes through the same binding — every node gesture, snap,
        // lock and undo path works on them verbatim
        if (s instanceof LaneSection ls) {
            return ls.editInfluence && ls.influenced() ? ls.deltaLanes
                                                       : ls.lanes;
        }
        return NO_LANES;
    }

    // ---------- INFLUENCE editing state (v15.0, D98; §5.4/5.5) ----------
    // While a section's INFLUENCE surface is active, the plot flips to
    // the OTHER mode's quantities (the delta lanes' units) and offers
    // two visuals: "Influence view" (deltas drawn around zero, the
    // resulting graphs dotted behind) and "Resulting view" (nodes RIDE
    // base+delta — purely visual, drags still write the delta).

    /** The active LaneSection while its INFLUENCE surface is being
     *  edited, else null. */
    private LaneSection activeInfl() {
        if (surfStrip) return null;   // controls never sit on influence
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof LaneSection ls
            && ls.editInfluence && ls.influenced() ? ls : null;
    }

    // ---------- PRISTINE-LANE END FOLLOW (v19.19, D248) ----------
    // LaneSection.endFollows: a hold lane created untouched FOLLOWS
    // the section end — after any gesture that moves a lane's last
    // node, every still-following lane's last node lands on the new
    // end (growing AND shrinking the section). The editor owns the
    // semantics: touch*() clears a lane's flag at its first write (the
    // one clearing choke, called at every lane-writing sink),
    // syncFollowingEnds() performs the write (at the sinks that can
    // move a last node, BEFORE their redraw + rebuild — never during
    // an undo restore), and the top of every redraw runs ONLY
    // clearStaleFollows() — the belt clears a follower that stopped
    // being hold-shaped and never moves a node: a redraw can't
    // rebuild, so a move there would leave the graph ahead of the
    // compiled track (D248 review fix; the brief's "auto-cleared,
    // never moved"). The arithmetic lives in LaneSection so
    // EndFollowGate drives it.

    /** The active section as a LaneSection (any surface), else null. */
    private LaneSection activeLaneSec() {
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof LaneSection ls ? ls : null;
    }

    /** Clear the END-FOLLOW flag of `lane` when it is one of the
     *  active section's own lanes (identity — a delta lane, a
     *  speed-control graph or a foreign list is a no-op). */
    private void touchLane(List<BezierBaker.Node> lane) {
        LaneSection.touchLane(activeLaneSec(), lane);
    }

    /** touchLane on the editing surface's lane (strip = no-op). */
    private void touchActive() {
        if (surfStrip) return;
        touchLane(active());
    }

    /** The follow WRITE on the active section's OWN lanes (never the
     *  delta surface's). Guarded against undo restores. */
    private void syncFollowingEnds() {
        if (app.docApplying() || app.docRestoring()) return;
        LaneSection ls = activeLaneSec();
        if (ls == null) return;
        LaneSection.syncFollowingEnds(ls, minGapOf(ls));
    }

    /** The redraw BELT (D248 review fix): CLEAR only — see
     *  LaneSection.clearStaleFollows. Guarded like the write. */
    private void clearStaleFollows() {
        if (app.docApplying() || app.docRestoring()) return;
        LaneSection ls = activeLaneSec();
        if (ls == null) return;
        LaneSection.clearStaleFollows(ls);
    }

    /** The active section as an influenced LaneSection (whatever
     *  surface is showing), else null. */
    private LaneSection activeInfluenced() {
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof LaneSection ls
            && ls.influenced() ? ls : null;
    }

    /** Piece p's REAL orientation differs from its DESIGN orientation
     *  (v19.19, D248 — the one predicate the true-stroke reference,
     *  the inactive-strip rule and the B-match analytic guard key
     *  off; was pieceTilted = the pre-v19.0 true-tier test `pitchDeg
     *  != 0 || rollDeg != 0`, which missed a DESIGN-tier conflict
     *  (D222: rollDeg 0, designRollDeg 30) and a strict follower's
     *  held-seam tilt (D247) — the compile tilts rigidly in both
     *  cases while the drawing kept comparing the true felt against
     *  the already-tilted compiled felt: the invisible lateral-G
     *  band): a free piece's two tiers (TrackPiece.orientConflict)
     *  or a follower's seam tilt with a horizontal component
     *  (Coaster.isSeamOrientConflict) — TrackCompiler applies a
     *  rigid tiltLine there, so every force lane and every geo
     *  world-axis/absolute lane deviates from the authored lanes
     *  (LaneSection.tiltDeviates decides per lane). */
    private boolean pieceConflicted(int p) {
        return orientConflictAt(p);
    }

    // ---------- x-AXIS DOMAIN (v16.0, D103) ----------
    // The plot's x-axis follows the ACTIVE section's DISPLAY domain:
    // its native domain, flipped by its cross-domain view toggle. All
    // layout (runLayout/tOff/tToX windows) runs in AXIS units — seconds
    // or meters — and strips of the other parameterization WARP onto it
    // by plotting compiled samples at their other coordinate (every
    // sample carries both t and s). A bridge strip reads as TIME.

    /** Is the plot's x-axis DISTANCE (meters)? */
    private boolean axisDist() {
        if (surfStrip) {   // the strip surface speaks its own section
            return surfRs.sec().domain() == Section.Domain.DISTANCE;
        }
        List<Section> secs = sections();
        if (activeSection >= secs.size()) return false;
        Section s = secs.get(activeSection);
        if (s instanceof BridgeSection) return false;
        boolean nd = s.domain() == Section.Domain.DISTANCE;
        return s.viewOtherDomain() ? !nd : nd;
    }

    /** Is the ACTIVE section natively DISTANCE-parameterized (its node
     *  math — lane x, walls, gaps — runs in meters)? */
    private boolean activeDistNative() {
        if (surfStrip) {
            return surfRs.sec().domain() == Section.Domain.DISTANCE;
        }
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof LaneSection ls
            && ls.domain() == Section.Domain.DISTANCE;
    }

    /** Compiled sample i's coordinate on the current x-axis. */
    private double gAxis(int i) {
        return axisDist() ? app.lastSamples.get(i).s()
                          : app.resultRates[i][0];
    }

    /** The SEAM_TRIM band around sample i in AXIS units: 2.5·dt of
     *  ride either way — on a meters axis that is 2.5·dt·v of track. */
    private double axisTrim(int i) {
        return axisDist()
            ? SEAM_TRIM * Math.max(0.5, app.lastSamples.get(i).v())
            : SEAM_TRIM;
    }

    /** Index of the compiled sample nearest axis coordinate a — the
     *  axis-generic sibling of rateIdxAt (0 before a compile). */
    /** A per-sample quantity at an AXIS coordinate, LINEARLY
     *  interpolated between the bracketing samples (v19.4, D233 —
     *  user report: hover readouts on resulting/derived curves stuck
     *  to piecewise nearest-sample heights, visibly inside smoothing
     *  windows; the dot now follows the drawn graph). */
    private double interpAtAxis(double a,
            java.util.function.IntToDoubleFunction f) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        if (ss == null || ss.size() < 2) return f.applyAsDouble(0);
        boolean dist = axisDist();
        int lo = 0, hi = ss.size() - 1;
        double first = dist ? ss.get(0).s() : ss.get(0).t();
        double last = dist ? ss.get(hi).s() : ss.get(hi).t();
        if (a <= first) return f.applyAsDouble(0);
        if (a >= last) return f.applyAsDouble(hi);
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            double m = dist ? ss.get(mid).s() : ss.get(mid).t();
            if (m <= a) lo = mid;
            else hi = mid;
        }
        double x0 = dist ? ss.get(lo).s() : ss.get(lo).t();
        double x1 = dist ? ss.get(hi).s() : ss.get(hi).t();
        double fr = x1 - x0 < 1e-12 ? 0 : (a - x0) / (x1 - x0);
        double v0 = f.applyAsDouble(lo);
        double v1 = f.applyAsDouble(hi);
        return v0 + fr * (v1 - v0);
    }

    private int sampleIdxAtAxis(double a) {
        if (app.resultRates == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return 0;
        if (!axisDist()) return rateIdxAt(a);
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        int lo = 0, hi = ss.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ss.get(mid).s() <= a) lo = mid; else hi = mid;
        }
        return a - ss.get(lo).s() <= ss.get(hi).s() - a ? lo : hi;
    }

    /** Convert a GLOBAL axis coordinate to the OTHER domain's through
     *  the compiled samples (t↔s), linearly extrapolated past the
     *  ride ends — zoom windows survive an axis flip with their ends
     *  lined up (v16.1). fromDist = the coordinate's current domain. */
    private double mapGlobalAxis(double x, boolean fromDist) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        if (ss == null || ss.size() < 2) return x;
        int lo = 0, hi = ss.size() - 1;
        double first = fromDist ? ss.get(0).s() : ss.get(0).t();
        double last = fromDist ? ss.get(hi).s() : ss.get(hi).t();
        if (x <= first) {
            hi = 1;
        } else if (x >= last) {
            lo = hi - 1;
        } else {
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                double v = fromDist ? ss.get(mid).s() : ss.get(mid).t();
                if (v <= x) lo = mid; else hi = mid;
            }
        }
        double a = fromDist ? ss.get(lo).s() : ss.get(lo).t();
        double b = fromDist ? ss.get(hi).s() : ss.get(hi).t();
        double ya = fromDist ? ss.get(lo).t() : ss.get(lo).s();
        double yb = fromDist ? ss.get(hi).t() : ss.get(hi).s();
        double u = Math.abs(b - a) < 1e-12 ? 0 : (x - a) / (b - a);
        return ya + u * (yb - ya);   // u outside [0,1] extrapolates
    }

    /** Global ride time → the current axis coordinate (POV marker and
     *  other time-sourced positions). Identity on the time axis. */
    private double axisOfTime(double gt) {
        if (!axisDist() || app.lastSamples == null
                || app.lastSamples.isEmpty()
                || app.resultRates == null) return gt;
        return app.lastSamples.get(rateIdxAt(gt)).s();
    }

    // ---------- cross-domain WARP of the ACTIVE strip (v16.0) ----------
    // While the active LANE section displays the OTHER domain, node
    // math stays NATIVE and ltToX/xToLt translate native x ↔ the axis
    // through the section's compiled {t,s,v} map — FROZEN during node/
    // handle gestures so the live rebuild never moves the map under
    // the cursor (the auto-fit freeze rule). s/c strips have their own
    // map path (scAxisAtS); bridges never warp.

    private double[][] warpMap;   // Coaster.sectionMap rows, or null
    private boolean warpToDist;   // native TIME shown on a DISTANCE axis

    /** Rebuild the warp map off the last compile (no-gesture moments
     *  only — redraw calls this; drags keep the captured map). */
    private void refreshWarp() {
        List<Section> secs = sections();
        Section s = activeSection < secs.size() ? secs.get(activeSection) : null;
        if (!(s instanceof LaneSection ls) || !ls.viewOtherDomain()) {
            warpMap = null;
            return;
        }
        TrackCompiler.Span sp = spanOf(activeSection);
        if (sp == null || app.lastSamples == null
                || sp.i1() >= app.lastSamples.size()
                || sp.i1() - sp.i0() < 2) {
            warpMap = null;
            return;
        }
        warpMap = Coaster.sectionMap(app.lastSamples, sp, ls.reversed());
        warpToDist = ls.domain() == Section.Domain.TIME;
    }

    /** Native lane x → the strip-local AXIS coordinate (identity
     *  without a live warp; linear extrapolation past the map ends so
     *  end-of-section drags keep meaning something out there). */
    private double warpFwd(double x) {
        // STRIP surface (v18.3): control-local x → the shared plot
        // axis, so ltToX = tToX(tOff + warpFwd) holds on both surfaces
        if (surfStrip) {
            return spAxisOf(surfRs, surfCtl.x0 + x) - tOff;
        }
        return warpMap == null ? x
            : Coaster.mapCol(warpMap, warpToDist ? 0 : 1,
                warpToDist ? 1 : 0, x);
    }

    /** Strip-local axis coordinate → native lane x (warpFwd's inverse). */
    private double warpInv(double a) {
        if (surfStrip) {
            return spNativeAtAxis(surfRs, a + tOff) - surfCtl.x0;
        }
        return warpMap == null ? a
            : Coaster.mapCol(warpMap, warpToDist ? 1 : 0,
                warpToDist ? 0 : 1, a);
    }

    /** Horizontal Ctrl-snap step in AXIS units: seconds step on a time
     *  axis, the Options element-length step (the app's one distance
     *  step) on a meters axis. Native == axis when unwarped, so this
     *  is also the native step. */
    private double snapXStep() {
        if (surfStrip) return spSnapStep(surfRs.sec());
        return axisDist() ? app.settings.snapLen() : snapT();
    }

    /** Minimum transition length in the active section's NATIVE x
     *  units (0.01 s of ride ≈ 0.1 m of track at typical speeds). */
    private double minGap() {
        if (surfStrip) return minGapOf(surfRs.sec());
        return activeDistNative() ? 0.1 : MIN_GAP;
    }

    /** Insert clearance in native x units (INSERT_GAP's sibling). */
    private double insertGap() {
        if (surfStrip) {
            return surfRs.sec().domain() == Section.Domain.DISTANCE
                ? 0.2 : INSERT_GAP;
        }
        return activeDistNative() ? 0.2 : INSERT_GAP;
    }

    /** Display-domain unit for x readouts. */
    private String xUnit() { return axisDist() ? "m" : "s"; }

    /** The Append/hold step in native x units: 1 s of ride or 10 m of
     *  track (the s/c default's scale). */
    private double appendStep() {
        return activeDistNative() ? Coaster.CONT_HOLD_DIST : 1.0;
    }

    /** Global ride TIME of the active section's node at native x —
     *  the 3D marker / sample bridge (the model's clock, whatever the
     *  plot's axis shows). */
    private double nodeGlobalTime(double nativeX) {
        TrackCompiler.Span sp = activeSpan();
        if (sp == null) return nativeX;
        if (!activeDistNative()) return sp.t0() + nativeX;
        List<Section> secs = sections();
        boolean rev = activeSection < secs.size()
            && secs.get(activeSection).reversed();
        double tot = Math.max(sp.sLen(), 1e-9);
        double sTrav = rev ? tot - nativeX : nativeX;
        return timeAtSpanS(sp, FvdDesigner.clamp(sTrav, 0, tot));
    }

    /** DISPLAY offset for lane values at local time t — 0 always
     *  since v17.17 (D158): the influence RESULTING VIEW (nodes
     *  riding base + delta) was REMOVED on user verdict — its edge
     *  cases (opposite orientations, inverted stretches) read as
     *  buggy/unintuitive, and the single remaining view edits deltas
     *  around zero. The choke point stays threaded through draw/hit/
     *  drag so a future display warp has its seam. */
    private double dispOff(int lane, double localT) {
        return 0;
    }

    /** NATIVE value → DISPLAYED value at section-local x (v16.7
     *  D119; completed v16.9 D121 — user clarification: the view DOES
     *  edit in the other units, the v16.7 bug was strokeLaneCurve
     *  drawing raw control points so the curve sat at native scale
     *  under the warped nodes): the influence-resulting offset adds
     *  first, then everything scales by the rate-view factor. Scale 1
     *  + offset 0 = the identity every plain section takes. */
    private double dispVal(int lane, double localT, double val) {
        return (val + dispOff(lane, localT))
            * rateViewScale(lane, localT);
    }

    /** DISPLAYED value → native (the drag write-back). */
    private double nativeVal(int lane, double localT, double disp) {
        return disp / rateViewScale(lane, localT)
            - dispOff(lane, localT);
    }

    /** The cross-rate view's display factor at section-local x — 1
     *  unless the ACTIVE section views its rate lanes in the other
     *  units. v(x) reads the compiled span (1 before a compile). */
    private double rateViewScale(int lane, double localX) {
        if (surfStrip) return 1;   // no view warps on the strip
        if (!(sections().get(activeSection) instanceof LaneSection ls)
                || !ls.viewOtherRate() || !laneIsRate(ls, lane)) {
            return 1;
        }
        TrackCompiler.Span sp = spanOf(activeSection);
        if (sp == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return 1;
        boolean distN = ls.domain() == Section.Domain.DISTANCE;
        double g = distN ? sp.s0() + localX : sp.t0() + localX;
        int lo = sp.i0(), hi = Math.min(sp.i1(),
            app.lastSamples.size() - 1);
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            double c = distN ? app.lastSamples.get(mid).s()
                             : app.lastSamples.get(mid).t();
            if (c <= g) lo = mid; else hi = mid;
        }
        double v = Math.max(0.5, app.lastSamples.get(lo).v());
        return ls.ratePerMeter ? v : 1 / v;
    }

    /** Is this lane a RATE on the current editing surface? (deltas
     *  are always rates; an absolute roll lane never is) */
    private boolean laneIsRate(LaneSection ls, int lane) {
        if (lane == 2 && ls.rollAbsolute && ls.rollGlobal) return false;
        // absolute yaw/pitch lanes hold ANGLES (v19.8, D237)
        if (lane < 2 && ls.ypAbsolute && ls.yawPitchGlobal
                && ls instanceof GeoSection) return false;
        if (activeInfl() != null) return true;
        return ls instanceof GeoSection || lane == 2;
    }

    /** Is the ACTIVE section a geo (body-rate) section? Influence
     *  editing FLIPS this: the delta lanes carry the OTHER mode's
     *  quantities, and everything keyed off the active mode — names,
     *  units, palette, scales, snap steps, the Resulting popup, the
     *  B-matching conversions — follows the edited surface. */
    private boolean activeGeo() {
        List<Section> secs = sections();
        boolean geo = activeSection < secs.size()
            && secs.get(activeSection) instanceof GeoSection;
        return activeInfl() != null ? !geo : geo;
    }

    /** The active STRAIGHT/CURVE section, or null (v11.0). */
    private StraightCurveSection activeSc() {
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof StraightCurveSection sc ? sc : null;
    }

    /** The active BRIDGE section, or null (v13.0) — like s/c it has no
     *  lanes; unlike s/c it has no widget either (frozen geometry): the
     *  strip shows the derived curves and the badge menu does the rest. */
    private BridgeSection activeBridge() {
        List<Section> secs = sections();
        return activeSection < secs.size()
            && secs.get(activeSection) instanceof BridgeSection b ? b : null;
    }

    private String laneName(int lane) {
        if (surfStrip) {
            return surfCtl.type != SpeedControl.ACCEL ? "Speed"
                : !surfCtl.tied && surfCtl.showDecel
                    ? "Deceleration" : "Acceleration";
        }
        // ABSOLUTE lanes are ANGLES, not rates — their own names
        // (v19.9, D238: Elevation/Heading per the user's naming
        // rule; Bank fixes the old "Roll rate (deg/s) (°)" title)
        if (lane == 2 && activeRollAbs()) return "Bank";
        if (lane < 2 && activeYpAbs()) {
            return lane == 0 ? "Elevation" : "Heading";
        }
        // the influence surface labels its deltas honestly with Δ
        String base = activeGeo() ? GEO_LANE_NAMES[lane] : LANE_NAMES[lane];
        return activeInfl() != null ? "Δ " + base : base;
    }

    private String laneUnit(int lane) {
        if (surfStrip) {
            return surfCtl.type == SpeedControl.ACCEL ? " g" : " m/s";
        }
        if (lane == 2 && activeRollAbs()) return " °";
        if (lane < 2 && activeYpAbs()) return " °";   // D237
        if (laneShownPM(lane)) return " °/m";
        return activeGeo() ? " °/s" : LANE_UNITS[lane];
    }

    /** SHORT quantity name for the node editor's value row (v19.13,
     *  D242 — user spec: Vertical G / Lateral G / Roll / Pitch / Yaw
     *  / Bank / Elevation / Heading; the units live in the title's
     *  own parenthetical, so no "(deg/s)" tails here). */
    private String laneQtyName(int lane) {
        if (surfStrip) return laneName(lane);   // Speed/Acceleration…
        if (lane == 2 && activeRollAbs()) return "Bank";
        if (lane < 2 && activeYpAbs()) {
            return lane == 0 ? "Elevation" : "Heading";
        }
        if (lane == 2) return "Roll";
        return activeGeo() ? (lane == 0 ? "Pitch" : "Yaw")
                           : (lane == 0 ? "Vertical G" : "Lateral G");
    }

    /** DISPLAYED units for a lane: native XOR the cross-rate view
     *  (v16.7 D119, kept in v16.9 D121 — the axis label, default
     *  scales and readouts follow what the plot SHOWS). */
    private boolean laneShownPM(int lane) {
        if (surfStrip) return false;
        boolean nat = activeRatePM(lane);
        if (sections().get(activeSection) instanceof LaneSection ls
                && ls.viewOtherRate() && laneIsRate(ls, lane)) {
            return !nat;
        }
        return nat;
    }

    /** Is this ACTIVE-section lane displayed in the OTHER rate units
     *  (the cross-rate view warp applies to it)? */
    private boolean laneWarped(int lane) {
        if (surfStrip) return false;
        return sections().get(activeSection) instanceof LaneSection ls
            && ls.viewOtherRate() && laneIsRate(ls, lane);
    }

    /** Is the ACTIVE section's roll lane an ABSOLUTE bank angle
     *  (v16.7, D118)? */
    private boolean activeRollAbs() {
        if (surfStrip) return false;
        return sections().get(activeSection) instanceof LaneSection ls
            && ls.rollAbsolute && ls.rollGlobal;
    }

    /** Is the ACTIVE section's yaw/pitch pair ABSOLUTE angles
     *  (v19.8, D237)? Geo + global yaw/pitch only; the influence
     *  surface never is (abs sections refuse influence). */
    private boolean activeYpAbs() {
        if (surfStrip || activeInfl() != null) return false;
        return sections().get(activeSection) instanceof GeoSection ls
            && ls.ypAbsolute && ls.yawPitchGlobal;
    }

    /** The active section's roll lane is an ABSOLUTE bank — the
     *  shell tints the Roll button BANK_COLOR to match (v18.2). */
    boolean activeAbsRoll() {
        boolean o = surfStrip;
        surfStrip = false;
        try {
            return activeRollAbs();
        } finally {
            surfStrip = o;
        }
    }

    /** activeAbsRoll's yaw/pitch twin (v19.9, D238): the shell's
     *  lane buttons wear Elevation/Heading + the abs colors. */
    boolean activeAbsYp() {
        boolean o = surfStrip;
        surfStrip = false;
        try {
            return activeYpAbs();
        } finally {
            surfStrip = o;
        }
    }

    /** Is the cross-rate right-edge axis up? The view is on and the
     *  ACTIVE lane is warped — the axis shows that lane's NATIVE
     *  units beside the warped main scale (v16.9, D121). */
    private boolean rateViewAxisOn() {
        return laneWarped(activeLane);
    }

    /** The cross-rate view's CONSTANT conversion factor, native →
     *  displayed units at the section's design speed (nominal — the
     *  speed profile varies over the span; the geometry warp and the
     *  hover readout convert exactly per x instead). */
    private double rateViewFactor() {
        if (!(sections().get(activeSection) instanceof LaneSection ls)) {
            return 1;
        }
        double v = Math.max(0.5, ls.vDesign());
        return ls.ratePerMeter ? v : 1 / v;
    }

    /** Is this lane of the given section authored PER METER (v16.6,
     *  D115)? Rate lanes only — geo pitch/yaw/roll, force roll. */
    static boolean laneRatePM(LaneSection ls, int lane) {
        // an ABSOLUTE roll lane holds degrees — rate units don't
        // apply to it (v16.7, D118); absolute yaw/pitch lanes the
        // same (v19.8, D237 — the D213 rule editor-side)
        if (lane == 2 && ls.rollAbsolute && ls.rollGlobal) return false;
        if (lane < 2 && ls.ypAbsolute && ls.yawPitchGlobal
                && ls instanceof GeoSection) return false;
        return ls.ratePerMeter
            && (ls instanceof GeoSection || lane == 2);
    }

    /** The ACTIVE section's lane, per-meter? On the INFLUENCE
     *  surface every delta lane is a rate (v16.7 — a °/m force
     *  section's Δpitch/Δyaw author °/m). */
    private boolean activeRatePM(int lane) {
        if (!(sections().get(activeSection) instanceof LaneSection ls)) {
            return false;
        }
        if (activeInfl() != null) return ls.ratePerMeter;
        return laneRatePM(ls, lane);
    }

    /** The active mode's lane accent color (geo: teal/yellow/orange) —
     *  curves, nodes, buttons, guides and 3D markers all follow it. */
    Color laneColor(int lane) {
        if (surfStrip) {
            return surfCtl.type == SpeedControl.ACCEL
                ? SP_ACCEL_COLOR : SP_SPEED_COLOR;
        }
        // an ABSOLUTE-bank lane wears the resulting bank's color
        // (v17.9, D146 — user spec: real and resulting bank match)
        if (lane == 2 && activeRollAbs()) return FvdDesigner.BANK_COLOR;
        // ABSOLUTE elevation/heading lanes wear their own colors
        // (v19.9, D238 — the BANK_COLOR pattern for lanes 0/1)
        if (lane < 2 && activeYpAbs()) {
            return lane == 0 ? FvdDesigner.ELEV_ABS_COLOR
                             : FvdDesigner.HEAD_ABS_COLOR;
        }
        return (activeGeo() ? FvdDesigner.GEO_LANE_COLORS
                            : FvdDesigner.LANE_COLORS)[lane];
    }

    private static double[] modeDefMin(boolean geo) { return geo ? GEO_DEF_MIN : DEF_MIN; }
    private static double[] modeDefMax(boolean geo) { return geo ? GEO_DEF_MAX : DEF_MAX; }

    private double defMin(int lane) {
        // °/m lanes live at ~1/v the °/s numbers: their own default
        // (minimum) scales — ±5 °/m for every lane (v17.7, D144;
        // was roll ±15 / pitch-yaw ±10); an ABSOLUTE roll lane is
        // degrees, default ±90. The DISPLAYED units govern (the
        // cross-rate view rescales).
        if (lane == 2 && activeRollAbs()) return -90;
        if (lane < 2 && activeYpAbs()) return lane == 0 ? -90 : -180;
        if (laneShownPM(lane)) return -5;
        return modeDefMin(activeGeo())[lane];
    }
    private double defMax(int lane) {
        if (lane == 2 && activeRollAbs()) return 90;
        if (lane < 2 && activeYpAbs()) return lane == 0 ? 90 : 180;
        if (laneShownPM(lane)) return 5;
        return modeDefMax(activeGeo())[lane];
    }

    /** Lane buttons show the active mode's lane names + colors (mode 2
     *  = the no-lane modes: straight/curve AND bridge). The bridge GAP
     *  spinner's visibility rides along (v13.2) — this runs on every
     *  section/piece switch, load, add and delete. */
    private void syncLaneButtons() {
        app.setLaneButtonMode(activeGeo() ? 1
            : activeSc() != null || activeBridge() != null ? 2 : 0,
            activeInfl() != null);
        app.updateBridgeControls();
        // the partial-convert mode is per-strip: any activation change
        // away from its section cancels it (v13.11; any mode v14.0)
        if (pcSec >= 0 && pcSec != activeSection) {
            pcSec = -1;
            pcDragLine = false;
        }
        // the density preview owns its converted section the same way:
        // an activation change out from under it (piece selector, 3D
        // click) cancels — the pristine state comes back (v14.0, D90).
        // pvApplying: the preview's own restore/re-run passes through
        // restoreActive and must not self-cancel.
        if (pvBefore != null && !pvApplying
                && (activePiece != pvPiece || activeSection != pvSec)) {
            cancelDensityPreview();
        }
    }

    /** The piece whose compiled span contains global time gt; -1 when
     *  unmapped (the POV piece-follow, v13.11). */
    int pieceAtTime(double gt) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        if (spans == null || spans.isEmpty()) return -1;
        int idx = -1;
        for (int i = 0; i < spans.size(); i++) {
            if (gt <= spans.get(i).t1() + 1e-9) { idx = i; break; }
        }
        if (idx < 0) return -1;
        int p = 0, base = 0;
        List<TrackPiece> pieces = app.coaster.pieces;
        while (p < pieces.size() - 1
                && base + pieces.get(p).sections.size() <= idx) {
            base += pieces.get(p).sections.size();
            p++;
        }
        return p;
    }

    /** Global ride time where piece p begins (0 before a compile).
     *  NOT Span.t0 — that is by definition the PREVIOUS section's
     *  last sample time, which parked POV at the end of the previous
     *  piece (v13.12, D86): the piece's own FIRST sample is the
     *  honest start. */
    double pieceStartTime(int p) {
        TrackCompiler.Span sp = spanAt(p, 0);
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) return 0;
        return app.lastSamples.get(sp.i0()).t();
    }

    int activeSection() { return activeSection; }

    double activeSectionStart() { refreshOffset(); return tOff; }

    /** A lane section's duration is its authored ride end; a DISTANCE-
     *  domain section's duration exists only after compilation — read
     *  it from the last compile's span (estimate before the first). */
    private double sectionDur(int k) {
        return durOf(activePiece, k);
    }

    private void refreshOffset() {
        double o = pieceT0();
        for (int k = 0; k < activeSection && k < sections().size(); k++) {
            o += sectionDur(k);
        }
        tOff = o;
    }

    /** End of the ACTIVE section in its local time — its own ride end
     *  (the old single-section maxT() semantics, now per section).
     *  The INFLUENCE surface's end is the compiled duration: delta
     *  lanes modify the section, they never extend it. */
    private double sectionEnd() {
        // the strip surface's wall is the CONTROL's extent (v18.1:
        // graph nodes can never leave the control)
        if (surfStrip) return surfCtl.len;
        if (activeSc() != null) return sectionDur(activeSection);
        LaneSection infl = activeInfl();
        if (infl != null) {
            // NATIVE units always (v16.1 — sectionDur is axis units,
            // which diverge under a cross-domain view): the delta
            // wall is the section's own extent — authored length for
            // DISTANCE, the compiled duration for TIME
            if (infl.domain() == Section.Domain.DISTANCE) {
                return BezierBaker.rideEnd(infl.lanes);
            }
            TrackCompiler.Span isp = spanOf(activeSection);
            return isp != null ? isp.t1() - isp.t0()
                : BezierBaker.rideEnd(infl.lanes);
        }
        return BezierBaker.rideEnd(app.lanes);
    }

    private double ltToX(double localT) { return tToX(tOff + warpFwd(localT)); }

    private double xToLt(double x) { return warpInv(xToT(x) - tOff); }

    /** Leaving a section drops its cross-domain view (v16.2, user
     *  rule: returning to a section always shows its TRUE domain) —
     *  and the cross-RATE view with it (v16.7, D119). */
    private void dropActiveView() {
        List<Section> secs = sections();
        if (activeSection < secs.size()) {
            secs.get(activeSection).viewOtherDomain(false);
            if (secs.get(activeSection) instanceof LaneSection lv) {
                lv.viewOtherRate = false;
            }
        }
    }

    void setActiveSection(int k) {
        List<Section> secs = sections();
        k = (int) FvdDesigner.clamp(k, 0, secs.size() - 1);
        if (k == activeSection && app.lanes == lanesFor(secs.get(k))) return;
        if (k != activeSection) dropActiveView();
        activeSection = k;
        app.lanes = lanesFor(secs.get(k));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        canvas.setCursor(Cursor.DEFAULT);
        syncLaneButtons();
        refreshOffset();
        redraw();
    }

    /** After load/new: the piece/section lists were replaced wholesale. */
    void resetSections() {
        activePiece = 0;
        activeSection = 0;
        if (sections().isEmpty()) {   // no coaster (D230)
            app.lanes = NO_LANES;
            selIdx = -1;
            selSet.clear();
            return;
        }
        app.lanes = lanesFor(sections().get(0));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;
        syncLaneButtons();
        refreshOffset();
    }

    /** Undo/redo restore: re-point at the snapshot's piece + section;
     *  the caller (applySnapshot) fixes selection against the lanes. */
    void restoreActive(int piece, int section) {
        if (app.coaster.pieces.isEmpty()) {   // no coaster (D230)
            activePiece = 0;
            activeSection = 0;
            app.lanes = NO_LANES;
            return;
        }
        activePiece = (int) FvdDesigner.clamp(piece, 0,
            app.coaster.pieces.size() - 1);
        List<Section> secs = sections();
        activeSection = (int) FvdDesigner.clamp(section, 0, secs.size() - 1);
        app.lanes = lanesFor(secs.get(activeSection));
        StraightCurveSection sc = activeSc();
        elSel = sc == null ? -1
            : Math.min(elSel, sc.elements.size() - 1);
        syncLaneButtons();
        refreshOffset();
    }

    // the open '+'/badge context menu — remembered so a press ON THE
    // GRAPH dismisses it like a press anywhere else (v10.1: JavaFX's
    // auto-hide doesn't fire for clicks landing on this canvas)
    private ContextMenu openMenu;

    private void showMenu(ContextMenu cm, double screenX, double screenY) {
        openMenu = cm;
        cm.show(canvas, screenX, screenY);
    }

    /** The '+' menu: force, geo and straight/curve sections (Phase 2),
     *  attaching a floating piece (v12.0), and BRIDGING to the next
     *  piece (v13.0). A piece already ending with a bridge cannot be
     *  extended — its end is glued to the next piece's entry. */
    private void showAddSectionMenu(double screenX, double screenY) {
        ContextMenu cm = new ContextMenu();
        boolean endsBridge = !sections().isEmpty()
            && sections().get(sections().size() - 1) instanceof BridgeSection;
        // '+' after a SPEED-CONFLICTED end forks first (v15.2, D101 —
        // user spec): "Continue speed conflict" lines the new section
        // up with the DESIGN graphs (the historic behavior);
        // "Transition to real" starts it at the REAL speed with the
        // REAL end values — exactly like adding after an unconflicted
        // section, treating the true curves as the graphs.
        // ONE combined lane-section entry since v16.2 (user spec): the
        // dialog holds every option — force/geo, domain, axes, the
        // conflict fork, and the (lockable) starting design speed
        // gray reasons ride hover TOOLTIPS since v19.7 (D236, grayItem)
        String bridgeWhy = "This piece ends with a bridge — its end is"
            + " glued to the next piece; extend the NEXT piece instead.";
        MenuItem lane;
        if (endsBridge) {
            lane = grayItem("Add force/geometric section…", bridgeWhy);
        } else {
            lane = new MenuItem("Add force/geometric section…");
            lane.setOnAction(ev -> showAddLaneDialog());
        }
        if (!endsBridge && pieceEndDrifted()) {
            // ONE s/c entry even over a conflicted end since v16.5
            // (user spec — two top-level items doubled the menu):
            // clicking/hovering it reveals the same two seam
            // choices the force/geo dialog forks on
            Menu sc = new Menu("Add straight/curve section");
            MenuItem scC = new MenuItem("Continue speed conflict");
            scC.setOnAction(ev -> addSection(2, false));
            MenuItem scR = new MenuItem("Set to Real");
            scR.setOnAction(ev -> addSection(2, true));
            sc.getItems().addAll(scC, scR);
            cm.getItems().addAll(lane, sc);
        } else {
            MenuItem sc;
            if (endsBridge) {
                sc = grayItem("Add straight/curve section",
                    bridgeWhy);
            } else {
                sc = new MenuItem(
                    "Add straight/curve section");
                sc.setOnAction(ev -> addSection(2));
            }
            cm.getItems().addAll(lane, sc);
        }
        // RUN AS PIECE (v19.19, D248 — user vocabulary: a connected
        // run IS "the track piece"): both lists offer the OTHER RUNS
        // by their heads — a pick moves the WHOLE run (head..tail,
        // Coaster.moveRun) to come next on the timeline. Pieces
        // INSIDE this run are never targets (v13.4): they're
        // connected — reordering one would tear the chain apart.
        // Pre-checked per entry (never a silent click, D235): the
        // wizard's pending piece and a run ending in an orphan bridge
        // that would not land at the list end gray with their reasons.
        int rs = runStart(), re = runEnd();
        List<Integer> heads = app.coaster.runHeads();
        boolean hasOutside = heads.size() > 1;
        if (endsBridge) {
            cm.getItems().add(grayItem("Bridge to piece", bridgeWhy));
        } else if (hasOutside) {
            Menu bridge = new Menu("Bridge to piece");
            for (int h : heads) {
                if (h >= rs && h <= re) continue;
                String label = String.format(Locale.US, "Piece %d — %s%s",
                    app.coaster.runOrdinal(h), app.runLengthLabel(h),
                    h == re + 1 ? " (next)" : "");
                String why = runPickWhy(h, re);
                if (why != null) {
                    bridge.getItems().add(grayItem(label, why));
                    continue;
                }
                MenuItem it = new MenuItem(label);
                final int idx = h;
                it.setOnAction(ev -> addBridgeTo(idx));
                bridge.getItems().add(it);
            }
            cm.getItems().add(bridge);
        } else {
            cm.getItems().add(grayItem("Bridge to piece",
                "Needs another track piece outside this one to bridge to."));
        }
        if (!hasOutside) {
            cm.getItems().add(grayItem("Attach floating piece",
                "No detached track piece exists to attach."));
        } else {
            // attaching = the run's sections CONTINUE this piece's
            // chain through a strict seam (its head's placement is
            // discarded — §0 keeps every section's local shape)
            if (endsBridge) {
                cm.getItems().add(
                    grayItem("Attach floating piece", bridgeWhy));
            } else {
                Menu attach = new Menu("Attach floating piece");
                for (int h : heads) {
                    if (h >= rs && h <= re) continue;
                    String label = String.format(Locale.US, "Piece %d — %s",
                        app.coaster.runOrdinal(h), app.runLengthLabel(h));
                    String why = runPickWhy(h, re);
                    if (why != null) {
                        attach.getItems().add(grayItem(label, why));
                        continue;
                    }
                    MenuItem it = new MenuItem(label);
                    final int idx = h;
                    it.setOnAction(ev -> attachFloating(idx));
                    attach.getItems().add(it);
                }
                cm.getItems().add(attach);
            }
        }
        // ADD FLOATING PIECE (v19.1, D226; below Attach since v19.2 —
        // user order; renamed from "…section…" in v19.10, D239): the
        // start screen minus friction — a new detached piece with its
        // own first section. It appends at the LIST END, so an orphan
        // bridge there would seize it as its target (the copyRun rule,
        // D248) — grayed with the reason.
        MenuItem floating;
        if (app.coaster.listEndsInOrphanBridge()) {
            floating = grayItem("Add floating piece…", ORPHAN_END_WHY);
        } else {
            floating = new MenuItem("Add floating piece…");
            floating.setOnAction(ev -> app.showStartWizard(StartPanel.Mode.FLOATING));
        }
        cm.getItems().add(floating);
        showMenu(cm, screenX, screenY);
    }

    /** The '+' lists' gray reason for picking the run headed by `h`
     *  to follow the run tail `re` — null when the pick is allowed
     *  (v19.19, D248): the wizard's PENDING piece (attaching it would
     *  strand WizardState.pieceIdx and turn the placeholder into real
     *  track), then Coaster.canMoveRun's refusals — a run ending in an
     *  orphan bridge that would not land at the list end (the landing
     *  piece would be seized as that bridge's target). A pick that
     *  already sits right after the tail moves nothing and is always
     *  allowed (the "(next)" case). */
    private String runPickWhy(int h, int re) {
        // the OPEN wizard's piece, picked or not (D248 review fix —
        // isPendingPiece is the RENDER gate, and the tile is picked
        // from open since D232, so it never fired here)
        if (app.isWizardPiece(app.activeCoaster, h)) return WIZARD_OPEN_WHY;
        if (h == re + 1) return null;
        if (!app.coaster.canMoveRun(h, re)) return ORPHAN_BLOCK_WHY;
        return null;
    }

    /** The apply-time stats sentence for a Coaster.canMoveRun refusal
     *  (the menu was built, the model changed under it — D247's
     *  "ops that can still fail at apply time post the full
     *  sentence"); mirrors canMoveRun's cases in order. */
    private String moveRefuseWhy(int head, int after) {
        Coaster c = app.coaster;
        int n = c.pieces.size();
        if (head < 0 || head >= n || after < 0 || after >= n) {
            return "that track piece no longer exists.";
        }
        if (c.runStartOf(head) != head) {
            return "that piece is not the head of its track piece.";
        }
        if (after >= head && after <= c.runEndOf(head)) {
            return "that track piece already contains this piece.";
        }
        if (c.endsWithBridge(after)) {
            return "this piece ends with a bridge; extend the next piece instead.";
        }
        if (c.runEndOf(after) != after) {
            return "that piece is not the end of its track piece.";
        }
        return ORPHAN_BLOCK_WHY;
    }

    /** The '+' lists' and selector's pending-wizard-piece reason. */
    static final String WIZARD_OPEN_WHY =
        "Finish or cancel the open start screen first.";
    /** A picked run ending in an orphan bridge that can't land last. */
    static final String ORPHAN_BLOCK_WHY = "This track piece ends in an"
        + " orphaned bridge — delete or convert it first.";
    /** The list's LAST piece ends in an orphan bridge — nothing may
     *  be appended after it (the floating wizard, Copy). */
    static final String ORPHAN_END_WHY = "The coaster's last track piece"
        + " ends in an orphaned bridge — delete or convert it first.";

    /** Append a live-following bridge from the ACTIVE piece's end to
     *  the RUN headed by `target` (v13.0 D72, any-piece + live v13.1
     *  D73; WHOLE RUNS since v19.19, D248). A non-adjacent target run
     *  REORDERS as a block (Coaster.moveRun) to come right after this
     *  piece — the piece order is the ride timeline, and connecting
     *  means "this one follows". Pre-checked BEFORE the undo push
     *  (D247: a refusal pushes nothing, never clears redo); the
     *  index-resolved seam / speed windows pre-hide (the list shifts
     *  under them). One undo step. */
    private void addBridgeTo(int target) {
        List<TrackPiece> pieces = app.coaster.pieces;
        if (target == activePiece || target >= pieces.size()) return;
        TrackCompiler.Span endSp = spanOf(sections().size() - 1);
        if (endSp == null) return;
        boolean moves = target != activePiece + 1;
        if (app.isWizardPiece(app.activeCoaster, target)) {
            app.statsLabel.setText(WIZARD_OPEN_WHY);   // apply-time twin
            return;                                    // of the gray (D248)
        }
        if (moves && !app.coaster.canMoveRun(target, activePiece)) {
            app.statsLabel.setText("Bridge refused — "
                + moveRefuseWhy(target, activePiece));
            return;   // pre-checked: no undo step pushed
        }
        double vEnd = app.lastSamples != null
            && endSp.i1() < app.lastSamples.size()
            ? app.lastSamples.get(endSp.i1()).v() : sections()
                .get(sections().size() - 1).vDesign();
        hideJunctionWindows();   // piece indices shift (D248 — the
        spHideCtlPopup();    // attachFloating precedent; index-resolved
                             // windows would resync onto another piece)
        if (moves) app.dropGizmoReveals();   // Reveal(piece, kind) is an
                                             // INDEX too (D248 review fix)
        pushUndoSnapshot();
        TrackPose end = endSp.exit();   // capture BEFORE the reorder
        TrackPiece tp = pieces.get(target);
        if (moves) {
            int nh = app.coaster.moveRun(target, activePiece);
            // the target lands right after the active tail, whatever
            // moved: the active index is one before it (a block from
            // BELOW shifted us down by its whole length)
            activePiece = nh - 1;
        }
        BridgeSection br = BridgeSection.solve(end, tp.entryPose(), vEnd);
        // a FRESH bridge moves nothing, so its placement mode says so
        // (v13.7 user rule — the dropdown used to claim "optimize
        // position and yaw" over a deliberately unoptimized track);
        // switching to an optimize mode stays the explicit action and
        // applies immediately. Old files keep the documented
        // missing-field default (OPT_POS_YAW).
        br.optMode = BridgeSection.OPT_NONE;
        sections().add(br);
        activeSection = sections().size() - 1;
        app.lanes = lanesFor(br);
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;          // auto-fit — same rule as addSection
        syncLaneButtons();
        app.rebuildTrack();   // the compile live-captures poses + G² acc
        app.updatePieceButton();
        refreshOffset();
        redraw();
    }

    /** CONVERT section k of the active piece into an editable force or
     *  geo section re-authoring its compiled shape (v13.7, D81 — the
     *  v13.5 bridge "unfreeze" unified: force↔geo, s/c→either,
     *  bridge→either, one feature, one engine). A bridge converts its
     *  solved geometry — the piece then no longer ends with a bridge,
     *  so the run splits and the next piece keeps its placement (the
     *  seam keeps only the fit residual, TESTING #20/#23). Other
     *  sections re-run their own passG locally from their entry pose.
     *  Reversed sections refuse (their lanes author the opposite
     *  direction — same round-1 rule as §5.3). One undo step returns
     *  the original. */
    private void convertSection(int k, boolean toGeo) {
        convertSection(k, toGeo, Section.Domain.TIME, false, false, false);
    }

    private void convertSection(int k, boolean toGeo, Section.Domain dom,
                                boolean rollG, boolean ypG, boolean rateM) {
        convertSection(k, toGeo, dom, rollG, ypG, rateM, false);
    }

    /** Full-settings whole convert (v16.6, D116 — the Convert
     *  dialog): type/domain/axes/rate units/roll behavior in one
     *  engine call. */
    private void convertSection(int k, boolean toGeo, Section.Domain dom,
                                boolean rollG, boolean ypG, boolean rateM,
                                boolean rollAbs) {
        convertSection(k, toGeo, dom, rollG, ypG, rateM, rollAbs, false);
    }

    /** strictSeam (v17.1, D135 — bridge whole-converts only): keep
     *  the next piece as a strict-seam FOLLOWER — shape frozen, the
     *  bridge's locks carried — instead of the classic attach+pin. */
    private void convertSection(int k, boolean toGeo, Section.Domain dom,
                                boolean rollG, boolean ypG, boolean rateM,
                                boolean rollAbs, boolean strictSeam) {
        convertSection(k, toGeo, dom, rollG, ypG, rateM, rollAbs,
            strictSeam, false);
    }

    /** + ABSOLUTE yaw/pitch target (v19.8, D237). */
    private void convertSection(int k, boolean toGeo, Section.Domain dom,
                                boolean rollG, boolean ypG, boolean rateM,
                                boolean rollAbs, boolean strictSeam,
                                boolean ypAbs) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        Section sec = secs.get(k);
        if (sec.reversed() && !(sec instanceof BridgeSection)) return;
        int piece = activePiece;
        // the model twin runs the sampling + replacement so the
        // density preview can re-run it per change (v14.0, D90); a
        // BRIDGE whole-convert routes to convertBridgeWhole in there —
        // the next piece attaches (or strict-follows) and the track
        // stays connected (D89/D135)
        beginDensityPreview(k, toGeo, false,
            dom == Section.Domain.DISTANCE,
            (sp, tol) ->
            app.coaster.convertWholeSection(piece, k, toGeo,
                FvdDesigner.INTEGRATE_DT, sp, tol, dom, rollG, ypG, rateM,
                rollAbs, strictSeam, ypAbs),
            () -> {
                activePiece = piece;
                activeSection = k;
            });
    }

    /** Is a live BRIDGE the thing directly before section k on the
     *  run? Only that blocks the resolve ops now (v15.2, D101 — the
     *  v14.5 whole-run block was relaxed on user rule: with
     *  transition-to-real, chains resolve safely LEFT TO RIGHT; a
     *  direct bridge neighbor still loops — it re-blends onto
     *  whatever the resolution changes). */
    private boolean leftIsBridge(int k) {
        if (k > 0) {
            return sections().get(k - 1) instanceof BridgeSection;
        }
        if (activePiece > runStart()) {
            List<Section> prev =
                app.coaster.pieces.get(activePiece - 1).sections;
            return !prev.isEmpty()
                && prev.get(prev.size() - 1) instanceof BridgeSection;
        }
        return false;
    }


    /** Does the ACTIVE piece end speed-conflicted (design vs true at
     *  its last sample)? The '+' menu forks there (v15.2, D101). */
    private boolean pieceEndDrifted() {
        return seamDrifted(sections().size() - 1);
    }

    /** Does the ACTIVE piece's section kLeft END speed-conflicted
     *  (design vs true at its last sample)? pieceEndDrifted
     *  generalized to any seam (v19.18, D247 — the seam-insert
     *  dialog's fork row reads the seam it inserts at). */
    private boolean seamDrifted(int kLeft) {
        TrackCompiler.Span sp = spanOf(kLeft);
        if (sp == null || !trueArraysReady()
                || sp.i1() >= app.trueV.length) return false;
        int e = Math.min(sp.i1(), app.lastSamples.size() - 1);
        return Math.abs(app.trueV[e] - app.lastSamples.get(e).v()) > DRIFT_EPS;
    }


    /** "Convert Real to Design" (§5 round B, v14.5 D97 as "Make
     *  graphs real"; renamed D234): resolve this
     *  section's design/true split KEEPING the shape — lanes re-fit
     *  at the true entry speed through the density preview; s/c just
     *  re-freezes (shape speed-independent, no preview needed).
     *  Since v19.18 (D247) this is the PURE-SPEED path — an
     *  orientation-conflicted piece routes to resolveRangeReal. */
    private void makeGraphsReal(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        Section sec = secs.get(k);
        int piece = activePiece;
        if (sec instanceof StraightCurveSection) {
            // pushed only on success (the D247 rule; D249 review fix)
            FvdDesigner.DocState pre = snapshot();
            if (app.coaster.makeGraphsReal(piece, k,
                    FvdDesigner.INTEGRATE_DT,
                    Coaster.NODE_SPACING_DEFAULT, 0)) {
                pushUndo(pre);
                app.markDirty();
                app.rebuildTrack();
                refreshOffset();
            } else {
                rollbackTo(pre);
                app.statsLabel.setText("Convert refused — nothing "
                    + "compiled for this section yet.");   // D235
            }
            redraw();
            return;
        }
        beginDensityPreview(k, sec instanceof GeoSection, true,
            (sp, tol) -> app.coaster.makeGraphsReal(piece, k,
                FvdDesigner.INTEGRATE_DT, sp, tol),
            () -> {
                activePiece = piece;
                activeSection = k;
            });
    }

    /** "Re-freeze design speed" (v14.5, D97): the OTHER split
     *  resolution — graphs kept, geometry re-derives at the true
     *  entry speed. One undo step. Since v19.18 (D247) this is the
     *  PURE-SPEED path — an orientation-conflicted piece routes to
     *  resolveRangeRefreeze. */
    private void refreezeSpeed(int k) {
        if (k < 0 || k >= sections().size()) return;
        // snapshot first, pushed only on success (the D247 rule; D249
        // review fix — a refusal left a pushed step behind, and the
        // op's tilt/follow bakes precede its refusal, so the rollback
        // also undoes a partial bake)
        FvdDesigner.DocState pre = snapshot();
        if (app.coaster.refreezeSpeed(activePiece, k,
                FvdDesigner.INTEGRATE_DT)) {
            pushUndo(pre);
            app.markDirty();
            app.rebuildTrack();
            refreshOffset();
        } else {
            rollbackTo(pre);
            app.statsLabel.setText("Set Design to Real refused — "
                + "nothing compiled for this section yet.");   // D235
        }
        redraw();
    }

    // ---------- RANGE-SCOPED orientation resolve (v19.18, D247) ----------
    // An orientation conflict is PIECE-wide, and today's resolve ops
    // bake the whole piece — every sibling section reshaped. On a
    // conflicted piece the badge's two resolve items run the RANGE
    // flow instead (user spec): sections [k..m] of the piece are
    // isolated behind inserted strict seams (Coaster.isolateRange),
    // "Set Design to Real" bakes + refreezes the range piece in one
    // step (Coaster.refreezeRange), and "Convert Real to Design" is a
    // CHAINED density preview — one section per Apply, each
    // intermediate document honest, Ctrl+Z stepping back a section.
    // A pure speed conflict keeps today's single-section ops bitwise.

    // (badgeBase — the unnumbered "Geo·m ◀ fx" badge text — went in
    // v19.19, D248, and its FORM is back as SectionNames.badgeLabel
    // since v19.20, D249 — the drawn badge reverted on user hand
    // test; sectionTitle + tags name the range dialog's sections.)

    /** Why the range resolve can't run from section k of piece p —
     *  the gray-out reason (D235 rule), null = it can. Checked at
     *  menu build AND again when the dialog's Resolve fires. */
    private String rangeResolveWhy(int p, int k) {
        List<TrackPiece> pcs = app.coaster.pieces;
        if (p < 0 || p >= pcs.size()) return "The piece is gone.";
        int last = app.coaster.lastNonBridge(p);
        List<Section> secs = pcs.get(p).sections;
        if (k < 0 || k > last) {
            return "Only a section before the piece's trailing bridge "
                + "can start a range.";
        }
        // (the D247 smoothed-seam refusals are GONE since v19.20,
        // D249: the range ops run under Coaster.withSeamsSuspended —
        // the inserted seams are cut and the follow bakes read the
        // same unsmoothed frames the fit reads, and the smoothing
        // comes back around the resolved range)
        for (int i = k; i <= last; i++) {
            Section s = secs.get(i);
            if (s.reversed()) {
                return sectionTitle(p, i) + " is reversed — a reversed "
                    + "section can't be re-fitted (reversal is the "
                    + "convert-based rebuild; reverse the piece back "
                    + "first).";
            }
            TrackCompiler.Span sp = spanAt(p, i);
            if (sp == null) return "This piece isn't compiled yet.";
            if (s instanceof LaneSection && sp.i1() - sp.i0() < 4) {
                return sectionTitle(p, i) + " is too short to measure — "
                    + "lengthen it first.";
            }
        }
        return null;
    }

    /** Badge > Resolve conflict > "Set Design to Real" on an
     *  orientation-conflicted piece: pick the range end, then bake +
     *  refreeze the isolated range (one undo step). */
    private void resolveRangeRefreeze(int k) {
        final int piece = activePiece;
        withRangeEnd(piece, k, m -> runRefreezeRange(piece, k, m));
    }

    /** Badge > Resolve conflict > "Convert Real to Design" on an
     *  orientation-conflicted piece: pick the range end, then run the
     *  chained preview. */
    private void resolveRangeReal(int k) {
        final int piece = activePiece;
        withRangeEnd(piece, k, m -> startRangeChain(piece, k, m));
    }

    /** k == the piece's last non-bridge section (one-section pieces
     *  included) resolves alone — no dialog; otherwise the end-section
     *  dialog picks m ∈ [k, last] (default last). */
    private void withRangeEnd(int piece, int k,
                              java.util.function.IntConsumer go) {
        int last = app.coaster.lastNonBridge(piece);
        if (k < 0 || last < 0 || k > last) return;
        if (k == last) {
            go.accept(k);
            return;
        }
        showRangeEndDialog(piece, k, last, go);
    }

    /** The end-section picker (D247): a small dark owned modal Stage
     *  — "Resolve from section k+1 through:" + a ChoiceBox of the
     *  candidate end sections labeled by their badge text. */
    private void showRangeEndDialog(int piece, int k, int last,
                                    java.util.function.IntConsumer go) {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Resolve orientation conflict");
        VBox box = new VBox(8);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: rgb(38,42,53);");
        String txt = "-fx-text-fill: rgb(210,215,225);";
        // run-wide section titles + badge tags (v19.19, D248)
        Label head = new Label("Resolve from " + sectionTitle(piece, k)
            + " through:");
        head.setStyle(txt);
        javafx.scene.control.ChoiceBox<String> end =
            new javafx.scene.control.ChoiceBox<>();
        List<Section> secs = app.coaster.pieces.get(piece).sections;
        for (int j = k; j <= last; j++) {
            end.getItems().add(sectionTitle(piece, j)
                + SectionNames.tags(secs.get(j), false));
        }
        end.getSelectionModel().select(last - k);   // default: the last
        end.setMaxWidth(Double.MAX_VALUE);
        Tooltip.install(end, new Tooltip("The last section of the range "
            + "to resolve — sections after it keep their shape."));
        Label note = new Label("A strict seam after the chosen section "
            + "keeps the rest of the piece exactly as it is.");
        note.setStyle(txt + " -fx-font-size: 11px;");
        note.setWrapText(true);
        note.setMaxWidth(320);
        Button ok = new Button("Resolve");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(ev -> dlg.close());
        ok.setOnAction(ev -> {
            int m = k + Math.max(0, end.getSelectionModel().getSelectedIndex());
            dlg.close();
            go.accept(m);
        });
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().addAll(head, end, note, buttons);
        Scene sc = new Scene(box);
        dlg.setScene(sc);
        app.installDocUndoKeys(sc);   // the D227 rule (undo keys global)
        app.installViewKeys(sc);      // ride keys live (v18.1)
        dlg.show();
    }

    /** Put a REFUSED op's pre-op snapshot back: the document may be
     *  partially mutated (the range flow's structural steps), so the
     *  snapshot goes back in place with NO history entry — the honest
     *  "nothing happened". The snapshot is taken BEFORE the op and
     *  pushed only on success (D247 review fix: a pushed-then-popped
     *  refusal still cleared the redo branch and marked the document
     *  dirty). */
    private void rollbackTo(FvdDesigner.DocState pre) {
        boolean wasDirty = app.isDirty();
        applySnapshot(pre);   // marks dirty like every restore
        if (!wasDirty) app.clearDirty();
    }

    /** SET DESIGN TO REAL over [k..m] (D247): isolate, then
     *  Coaster.refreezeRange — one undo step, then the make-strict
     *  refresh sequence on the range piece's first section. */
    private void runRefreezeRange(int piece, int k, int m) {
        String why = rangeResolveWhy(piece, k);   // the doc may have moved
        if (why != null || m > app.coaster.lastNonBridge(piece)) {
            app.statsLabel.setText("Set Design to Real refused — "
                + (why != null ? why : "the piece changed while the "
                    + "dialog was open."));
            redraw();
            return;
        }
        // the modal took the global undo keys, so the tilt that raised
        // the ≠o may be gone by now (D247 review fix — item F's rule)
        if (!orientConflictAt(piece)) {
            app.statsLabel.setText("Set Design to Real refused — the "
                + "piece's design and real orientations already agree.");
            redraw();
            return;
        }
        hideJunctionWindows();   // piece indices shift under the new seams (D248)
        FvdDesigner.DocState pre = snapshot();
        // both steps under ONE suspension of the piece's own seam and
        // the seam after it (D249): the cut at the range end, the cut
        // before k and the range piece's follow bake then read the
        // same unsmoothed frames (the model ops suspend again inside
        // — nested = a no-op)
        boolean ok = app.coaster.withSeamsSuspended(piece, () -> {
            int rp = app.coaster.isolateRange(piece, k, m,
                FvdDesigner.INTEGRATE_DT);
            return rp >= 0 && app.coaster.refreezeRange(rp,
                FvdDesigner.INTEGRATE_DT);
        });
        int rp = k > 0 ? piece + 1 : piece;   // isolateRange's result
        if (!ok) {
            rollbackTo(pre);
            app.statsLabel.setText("Set Design to Real refused — a "
                + "boundary of the range isn't compiled yet.");
            redraw();
            return;
        }
        pushUndo(pre);   // one undo step, pushed only on success
        activateRunSec(rp, 0);
        app.markDirty();
        app.rebuildTrack();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
        app.statsLabel.setText(k == m
            ? "Resolved section " + (k + 1) + " — the rest of the piece "
              + "keeps its shape behind strict seams."
            : "Resolved sections " + (k + 1) + "–" + (m + 1) + " — the "
              + "rest of the piece keeps its shape behind strict seams.");
    }

    /** A CHAINED "Convert Real to Design" over [k..m] (D247): step pos
     *  converts section k+pos; `conv` = the piece accumulating the
     *  converted sections ([k..k+pos] after step pos's Apply); the
     *  unconverted rest [k+pos+1..m] waits as the locked follower
     *  conv+1 until absorbed. */
    private static final class RangeChain {
        final int piece, k, m, conv;
        int pos;
        RangeChain(int piece, int k, int m) {
            this.piece = piece;
            this.k = k;
            this.m = m;
            this.conv = k > 0 ? piece + 1 : piece;   // isolateRange's result
        }
        int count() { return m - k + 1; }
    }
    private RangeChain chain;
    /** True while the chain itself opens/cancels a preview — the
     *  cancel path must not end the chain then. */
    private boolean chainOpening;

    /** Is a range chain's preview up (the convert-UI key branch's
     *  Ctrl+Z routes to chainStepBack then)? */
    boolean chainActive() { return chain != null && pvBefore != null; }

    private void startRangeChain(int piece, int k, int m) {
        String why = rangeResolveWhy(piece, k);   // the doc may have moved
        if (why != null || m > app.coaster.lastNonBridge(piece)) {
            app.statsLabel.setText("Convert Real to Design refused — "
                + (why != null ? why : "the piece changed while the "
                    + "dialog was open."));
            redraw();
            return;
        }
        // the modal took the global undo keys: the ≠o may be gone, or
        // the piece may have left the active run (D247 review fix)
        if (!orientConflictAt(piece)) {
            app.statsLabel.setText("Convert Real to Design refused — the "
                + "piece's design and real orientations already agree.");
            redraw();
            return;
        }
        if (piece < runStart() || piece > runEnd()) {
            app.statsLabel.setText("Convert Real to Design refused — the "
                + "piece is no longer part of the run being edited.");
            redraw();
            return;
        }
        hideJunctionWindows();   // piece indices shift under the new seams (D248)
        chain = new RangeChain(piece, k, m);
        chainStep();
    }

    /** The composite ConvertOp of the chain's current step — run by
     *  pvRerun against pvBefore on every density change, so the
     *  structural work re-executes deterministically per run:
     *  step 0 = isolate the range + (if more follow) split [k+1..m]
     *  off as a locked follower keeping the conflict + make section k
     *  real; step pos > 0 = split [i+1..m] off the waiting follower +
     *  make its section i real (bakes the follow) + absorb it into the
     *  converted piece. */
    private ConvertOp chainOp(RangeChain ch) {
        final double dt = FvdDesigner.INTEGRATE_DT;
        final int i = ch.k + ch.pos;
        // every step runs under Coaster.withSeamsSuspended (D249): the
        // structural cuts (isolateRange, the extra makeSeamStrict) and
        // the follow bakes read the same unsmoothed frames the fit
        // reads; pieces are resolved BY INDEX per run (pvRerun restores
        // pvBefore and adopts fresh objects — the stash is taken fresh
        // each time and restored on the objects it was taken from)
        if (ch.pos == 0) {
            return (sp, tol) -> app.coaster.withSeamsSuspended(ch.piece,
                () -> {
                    int rp = app.coaster.isolateRange(ch.piece, ch.k, ch.m,
                        dt);
                    if (rp != ch.conv) return false;
                    if (ch.m > ch.k
                            && app.coaster.makeSeamStrict(rp, 1, dt) < 0) {
                        return false;
                    }
                    return app.coaster.makeGraphsReal(rp, 0, dt, sp, tol);
                });
        }
        return (sp, tol) -> {
            int f = ch.conv + 1;
            if (f >= app.coaster.pieces.size()) return false;
            // the converted piece's OWN seam (step 0 cut under it)
            // plus the waiting follower's and the one after it
            return app.coaster.withSeamsSuspended(ch.conv,
                () -> app.coaster.withSeamsSuspended(f, () -> {
                    if (i < ch.m && app.coaster.makeSeamStrict(f, 1, dt) < 0) {
                        return false;
                    }
                    if (!app.coaster.makeGraphsReal(f, 0, dt, sp, tol)) {
                        return false;
                    }
                    return app.coaster.absorbFollower(ch.conv, f);
                }));
        };
    }

    /** Open the chain's current step: a LANE section gets the density
     *  preview (Apply advances the chain from commitDensityPreview),
     *  an s/c section applies directly (one undo step, its shape is
     *  speed-independent — nothing to tune) and advances at once. */
    private void chainStep() {
        RangeChain ch = chain;
        if (ch == null) return;
        int sp = ch.pos == 0 ? ch.piece : ch.conv + 1;   // holds section i now
        int sk = ch.pos == 0 ? ch.k : 0;
        List<TrackPiece> pcs = app.coaster.pieces;
        if (sp >= pcs.size() || sk >= pcs.get(sp).sections.size()) {
            chain = null;
            app.statsLabel.setText("Range resolve stopped — the range's "
                + "next section is gone.");
            redraw();
            return;
        }
        Section sec = pcs.get(sp).sections.get(sk);
        ConvertOp op = chainOp(ch);
        Runnable act = () -> {
            activePiece = ch.conv;
            activeSection = ch.pos;
        };
        if (sec instanceof StraightCurveSection) {
            FvdDesigner.DocState pre = snapshot();
            if (!op.apply(Coaster.NODE_SPACING_DEFAULT, 0)) {
                rollbackTo(pre);
                chain = null;
                // the run-wide title (brief L.6; D248 review fix — the
                // raw piece-local index matched no label)
                app.statsLabel.setText("Range resolve stopped — "
                    + sectionTitle(sp, sk) + " isn't compiled yet.");
                redraw();
                return;
            }
            pushUndo(pre);   // pushed only on success (D247 review fix)
            act.run();
            activePiece = (int) FvdDesigner.clamp(activePiece, 0,
                pcs.size() - 1);
            activeSection = (int) FvdDesigner.clamp(activeSection, 0,
                sections().size() - 1);
            app.lanes = lanesFor(sections().get(activeSection));
            selIdx = -1;
            selSet.clear();
            elSel = -1;
            syncLaneButtons();
            app.markDirty();
            app.rebuildTrack();
            app.updatePieceButton();
            app.syncPlacementSpinners();
            refreshOffset();
            redraw();
            if (ch.pos + 1 < ch.count()) {
                ch.pos++;
                chainStep();
            } else {
                chain = null;
                app.statsLabel.setText("Range resolved — every section "
                    + "of the range is converted; the seams around it "
                    + "stay strict.");
            }
            return;
        }
        // the preview's reference curves read spanOf(sk) on the ACTIVE
        // piece — activate the step's section first (pvBefore is null
        // here, so the activation-change cancel can't fire)
        activateRunSec(sp, sk);
        chainOpening = true;
        try {
            beginDensityPreview(sk, sec instanceof GeoSection, true, op,
                act);
        } finally {
            chainOpening = false;
        }
        if (!previewActive()) {
            chain = null;   // the refused-run message already posted
            return;
        }
        app.statsLabel.setText(String.format(Locale.US,
            "Range resolve — %s (%d of %d): tune the node density, "
            + "then Apply (Ctrl+Z steps back to the previous section).",
            sectionTitle(sp, sk), ch.pos + 1, ch.count()));
    }

    /** Ctrl+Z inside a chained preview (D247): cancel the open
     *  preview, undo the PREVIOUS section's Apply and re-open that
     *  section's preview (step 0 re-opens the composite op, so the
     *  seams come back with it); s/c steps had no preview and are
     *  stepped over; at step 0 the chain just ends (nothing changed). */
    void chainStepBack() {
        RangeChain ch = chain;
        if (ch == null) return;
        chainOpening = true;   // keep the chain across the cancel
        try {
            cancelDensityPreview();
        } finally {
            chainOpening = false;
        }
        boolean undone = false;
        while (true) {
            if (ch.pos == 0) {
                chain = null;
                app.statsLabel.setText(undone
                    ? "Range resolve ended — stepped back past its first "
                      + "section (the document is as it was before it)."
                    : "Range resolve cancelled — nothing changed.");
                redraw();
                return;
            }
            ch.pos--;
            undo();   // the previous step's Apply (or direct s/c step)
            undone = true;
            int sp = ch.pos == 0 ? ch.piece : ch.conv + 1;
            int sk = ch.pos == 0 ? ch.k : 0;
            List<TrackPiece> pcs = app.coaster.pieces;
            boolean sc = sp < pcs.size()
                && sk < pcs.get(sp).sections.size()
                && pcs.get(sp).sections.get(sk)
                    instanceof StraightCurveSection;
            if (!sc) break;   // an s/c step has no preview to re-open
        }
        chainStep();
    }

    /** BRIDGE CONTEXT (v19.19, D248): the piece whose TRAILING bridge
     *  the bridge API below addresses while a bridge WINDOW is open on
     *  it — the window is an owned Stage that outlives activation
     *  changes (a graph click, a POV ride), and its rows must keep
     *  editing THEIR bridge; −1 = the active section (the historic
     *  scoping, bitwise). Set only inside withBridge; the drawing /
     *  hit-test code keeps reading activeBridge() (the active
     *  section) — it never runs under a context. */
    private int bridgeCtx = -1;

    /** The piece the bridge API addresses (see bridgeCtx). */
    int bridgePieceCtx() {
        return bridgeCtx >= 0 && bridgeCtx < app.coaster.pieces.size()
            ? bridgeCtx : activePiece;
    }

    /** Run `body` with the bridge API scoped to piece p's trailing
     *  bridge (p < 0 = the active section, a plain call). */
    void withBridge(int p, Runnable body) {
        int was = bridgeCtx;
        bridgeCtx = p;
        try {
            body.run();
        } finally {
            bridgeCtx = was;
        }
    }

    <T> T withBridge(int p, java.util.function.Supplier<T> body) {
        int was = bridgeCtx;
        bridgeCtx = p;
        try {
            return body.get();
        } finally {
            bridgeCtx = was;
        }
    }

    /** The bridge the API addresses: the context piece's trailing
     *  bridge (orphan allowed — bridgeOrphan says), else the active
     *  section's. */
    private BridgeSection ctxBridge() {
        if (bridgeCtx < 0) return activeBridge();
        if (bridgeCtx >= app.coaster.pieces.size()) return null;
        List<Section> secs = app.coaster.pieces.get(bridgeCtx).sections;
        return !secs.isEmpty()
            && secs.get(secs.size() - 1) instanceof BridgeSection b ? b : null;
    }

    /** The context bridge's compiled span (the active section's
     *  without a context). */
    private TrackCompiler.Span ctxBridgeSpan() {
        if (bridgeCtx < 0) return spanOf(activeSection);
        if (bridgeCtx >= app.coaster.pieces.size()) return null;
        return spanAt(bridgeCtx,
            app.coaster.pieces.get(bridgeCtx).sections.size() - 1);
    }

    /** The bridge lost its target piece (deleted/attached): it stays
     *  frozen at its last capture — the badge's warning state. */
    private boolean bridgeOrphan(BridgeSection bs) {
        return bridgePieceCtx() + 1 >= app.coaster.pieces.size();
    }

    /** Is the ACTIVE section a bridge (orphan or not)? — the toolbar's
     *  junction button reads it (D248); never context-scoped. */
    boolean activeSectionIsBridge() {
        return activeBridge() != null;
    }

    /** The open strict seam window, or null (the winshot dev hook). */
    Stage seamWindow() { return seamWin; }

    /** The toolbar junction button's seam toggle (D248): open = close
     *  WITHOUT revert (Confirm semantics), else open follower idx's
     *  window. */
    void toggleSeamWindow(int followerIdx) {
        // the SAME follower's window closes; another follower's is
        // torn down by showStrictSeamSettings and the requested one
        // opens (D248 review fix — a bare null test closed piece 2's
        // window when piece 4's button was clicked and opened nothing;
        // the bridgeWinPiece pattern)
        if (seamWin != null && seamWinPiece == followerIdx
                && app.coaster == seamWinCoaster) {
            hideSeamPopup();
            return;
        }
        showStrictSeamSettings(followerIdx);
    }

    /** After the bridge window's Cancel restored piece p's trailing
     *  bridge + target fields (D248): re-land the march at the
     *  restored requested length in the optimize modes (optimizeBridge
     *  stamps the signature, so the next rebuild is a no-op —
     *  determinism contract b), a plain rebuild for a manual bridge
     *  (the septic re-captures). */
    void reapplyBridgeAfterRestore(int p) {
        BridgeSection bs = trailingBridge(p);
        if (bs != null && bs.optMode != BridgeSection.OPT_NONE) {
            applyBridgeGap(p, bs, bs.lastOptDist > 0 ? bs.lastOptDist
                : Math.max(1, gizmoBridgeGap(p)));
        } else {
            app.rebuildTrack();
            refreshOffset();
            redraw();
        }
    }

    /** The toolbar GAP spinner shows for an active, non-orphaned
     *  bridge (v13.2) — the context bridge under withBridge (D248). */
    boolean activeBridgeWithTarget() {
        BridgeSection bs = ctxBridge();
        return bs != null && !bridgeOrphan(bs);
    }

    /** The active bridge's LENGTH along the track (the spinner value,
     *  v13.4 — was the straight-line chord): the compiled span's arc
     *  extent, so a bridge that curves around a turn reads its real
     *  length; chord fallback before the first compile. */
    /** The length the SPINNER and end-line drag read and step (v14.2,
     *  D95 — the one v14-round bridge change KEPT on revert: it fixes
     *  the user's stuck arrow keys and changes no geometry): an
     *  optimized bridge's REQUESTED march length. The compiled arc
     *  equals it while the march lands, but diverges to the septic's
     *  own arc when it misses — the spinner re-synced to that arc and
     *  arrow-stepping fought the re-sync ("the values do weird things
     *  and get stuck"). Manual bridges keep the honest compiled
     *  arc. */
    double requestedBridgeGap() {
        BridgeSection bs = ctxBridge();
        if (bs != null && !bridgeOrphan(bs)
                && bs.optMode != BridgeSection.OPT_NONE
                && bs.lastOptDist > 0) {
            return bs.lastOptDist;
        }
        return activeBridgeGap();
    }

    double activeBridgeGap() {
        BridgeSection bs = ctxBridge();
        if (bs == null || bridgeOrphan(bs)) return 0;
        TrackCompiler.Span sp = ctxBridgeSpan();
        if (sp != null && sp.sLen() > 1e-6) return sp.sLen();
        return app.coaster.pieces.get(bridgePieceCtx() + 1).entryPose().pos()
            .sub(bs.solvedEntry.pos()).length();
    }

    private long lastGapNanos = 0;   // coalesces spinner-arrow undo steps

    /** The length spinner's write path (v13.2): every change RE-RUNS
     *  the placement optimization at the requested length — the target
     *  piece slides along the seams' natural continuation (v13.4:
     *  around a split turn's own circle) and the live bridge follows.
     *  Arrow bursts coalesce into one undo step. */
    void setBridgeGap(double dist) {
        BridgeSection bs = ctxBridge();
        if (bs == null || bridgeOrphan(bs) || dist <= 0
                || bs.optMode == BridgeSection.OPT_NONE
                || bs.optLen) return;   // auto length owns the spinner
        long now = System.nanoTime();
        if (now - lastGapNanos > 800_000_000L) pushUndoSnapshot();
        lastGapNanos = now;
        applyBridgeGap(bs, dist);
    }

    /** Re-run the placement optimization at length `dist` (no undo
     *  push — the spinner and the end-line drag each manage their
     *  own). v13.8 (D82): the tangent lengths come from
     *  Coaster.optimizeBridge's FIXED grid — deterministic per
     *  (geometry, dist). The old multiplicative rescale
     *  (lens ×= requested/realized arc) compounded a non-inverting
     *  ratio: every length round trip drifted the sweep a little and
     *  the shape got rougher — the user's "different each time". */
    private void applyBridgeGap(BridgeSection bs, double dist) {
        applyBridgeGap(bridgePieceCtx(), bs, dist);   // bitwise: same target
    }

    /** Piece-indexed twin (v19.18, D247 — the bridge-end ball of ANY
     *  piece of the run reveals its rig, so the gizmo paths resolve
     *  the bridge by the piece it trails, not the active one): the
     *  target is piece p+1; the POV clamp (v17.11) applies only while
     *  the edited bridge is the ACTIVE piece's active section — the
     *  case the bridge window's POV clamp guards. */
    private void applyBridgeGap(int p, BridgeSection bs, double dist) {
        boolean povOn = p == activePiece && povInsideActiveBridge();
        app.coaster.optimizeBridge(bs, p + 1, dist,
            bs.optMode == BridgeSection.OPT_POS_YAW);
        app.rebuildTrack();
        if (povOn) clampPovIntoActiveBridge();
        refreshOffset();
        redraw();
    }

    /** Is the POV position currently inside the ACTIVE bridge's span? */
    private boolean povInsideActiveBridge() {
        if (activeBridge() == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return false;
        TrackCompiler.Span sp = spanOf(activeSection);
        if (sp == null) return false;
        int i1 = Math.min(sp.i1(), app.lastSamples.size() - 1);
        double t = app.povTime;
        return t >= app.lastSamples.get(sp.i0()).t() - 1e-9
            && t <= app.lastSamples.get(i1).t() + 1e-9;
    }

    /** Keep the POV position ON the active bridge while its settings
     *  are being edited (v17.11, user spec): shrinking the length
     *  used to drop the parked POV into the NEXT piece, whose ride
     *  follow then activated it and closed the bridge properties
     *  panel mid-edit. Runs synchronously inside the edit event, so
     *  the POV timer never sees the position off the bridge. */
    private void clampPovIntoActiveBridge() {
        if (activeBridge() == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return;
        TrackCompiler.Span sp = spanOf(activeSection);
        if (sp == null) return;
        int i1 = Math.min(sp.i1(), app.lastSamples.size() - 1);
        double t0 = app.lastSamples.get(sp.i0()).t();
        double t1 = app.lastSamples.get(i1).t();
        double t = app.povTime;
        if (t < t0 || t > t1) {
            app.povTime = Math.max(t0, Math.min(t1 - 1e-6, t));
            app.updatePovStats();
        }
    }

    boolean bridgeOptLen() {
        BridgeSection bs = ctxBridge();
        return bs != null && bs.optLen;
    }

    /** The "Optimize length" check (v13.8, D82): on = the length is
     *  Coaster.optimalBridgeLength's knee — the spinner and end-line
     *  drag disable; off = the length is the user's again. */
    void setBridgeOptLen(boolean on) {
        BridgeSection bs = ctxBridge();
        if (bs == null || bs.optLen == on) return;
        pushUndoSnapshot();
        bs.optLen = on;
        app.markDirty();
        if (on && !bridgeOrphan(bs)
                && bs.optMode != BridgeSection.OPT_NONE) {
            applyOptimalLength(bs);
        } else {
            redraw();
        }
        app.updateBridgeControls();
    }

    /** Apply the optimal length. The search bottoming out at its 1 m
     *  floor means the seams continue each other smoothly and ZERO is
     *  the natural optimum (v13.8 second round — purely the
     *  calculation's verdict, wherever the piece currently sits, not
     *  a proximity check): offer the direct attach; "No" keeps
     *  everything as it is with the length check off. */
    private void applyOptimalLength(BridgeSection bs) {
        double dOpt = app.coaster.optimalBridgeLength(bs,
            bs.optMode == BridgeSection.OPT_POS_YAW);
        if (dOpt <= 1 + 1e-9) {
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "This would create a zero-distance bridge section.\n"
                + "Connect the pieces directly instead?",
                javafx.scene.control.ButtonType.YES,
                javafx.scene.control.ButtonType.NO);
            a.setHeaderText(null);
            java.util.Optional<javafx.scene.control.ButtonType> r =
                a.showAndWait();
            if (r.isPresent() && r.get() == javafx.scene.control.ButtonType.YES) {
                attachThroughBridge();
                return;
            }
            bs.optLen = false;
            app.updateBridgeControls();
            redraw();
            return;
        }
        applyBridgeGap(bs, dOpt);
    }

    /** Replace the active piece's trailing bridge with a DIRECT
     *  connection of the next piece (the zero-distance answer) —
     *  a STRICT SEAM since v17.1 (D135): the bridge goes, the next
     *  piece FOLLOWS with every axis unlocked (the seamless re-join,
     *  shape frozen), and the seam settings pop up. */
    private void attachThroughBridge() {
        int bp = bridgePieceCtx();   // the context piece's bridge (D248)
        List<Section> secs = app.coaster.pieces.get(bp).sections;
        int k = secs.size() - 1;
        if (!(secs.get(k) instanceof BridgeSection bs2)
                || bp + 1 >= app.coaster.pieces.size()) return;
        hideSeamPopup();   // the seam set changes (D247 review fix)
        app.closeBridgeWindow();   // its bridge is about to vanish (D248)
        secs.remove(k);
        // seam Discontinuous defaults = the BRIDGE's locks (v17.12,
        // D153, user spec: the axes the bridge held stay held)
        int np = app.coaster.connectStrict(bp, bp + 1,
            bs2.lockYaw, bs2.lockPitch, bs2.lockRoll);
        if (bp == activePiece) {
            activeSection = Math.min(k, sections().size() - 1);
            app.lanes = lanesFor(sections().get(activeSection));
            selIdx = -1;
            selSet.clear();
            elSel = -1;
        }
        syncLaneButtons();
        app.rebuildTrack();
        app.updatePieceButton();
        refreshOffset();
        redraw();
        if (np >= 0) showStrictSeamSettings(np);
    }

    // ---------- bridge placement modes (v13.5) ----------
    // the Placement dropdown edits the NEXT piece through the bridge:
    // mode 0 = length re-runs the full continuation march, 1 = the
    // march places position only (the user owns yaw), 2 = fully
    // manual x/y/z + yaw. Spinner bursts coalesce into one undo step
    // via the shared lastGapNanos clock.

    int bridgeOptMode() {
        BridgeSection bs = ctxBridge();
        return bs == null ? BridgeSection.OPT_POS_YAW : bs.optMode;
    }

    /** setBridgeOptMode's stats reason for the D249 exclusion. */
    static final String PY_REFUSED_DESIGN_MSG = "Optimize position and"
        + " orientation refused — the next piece edits its design"
        + " orientation; choose its real orientation first.";

    void setBridgeOptMode(int mode) {
        BridgeSection bs = ctxBridge();
        if (bs == null || bs.optMode == mode) return;
        final int bp = bridgePieceCtx();   // the target is bp + 1 (D248)
        if (mode == BridgeSection.OPT_POS_YAW) {
            // the MUTUAL EXCLUSION belt (D249): a target editing its
            // design orientation excludes optimize-orientation (the
            // radio is grayed; a race with the hover lands here)
            TrackPiece tgt = bridgeTargetPiece();
            if (tgt != null
                    && tgt.orientMode == TrackPiece.OrientMode.DESIGN_ONLY) {
                app.statsLabel.setText(PY_REFUSED_DESIGN_MSG);
                app.updateBridgeControls();   // re-select the real mode
                return;
            }
        }
        int wasMode = bs.optMode;
        pushUndoSnapshot();
        bs.optMode = mode;
        app.markDirty();
        if (mode != BridgeSection.OPT_NONE && !bridgeOrphan(bs)) {
            if (wasMode == BridgeSection.OPT_NONE) {
                // LEAVING manual mode (v14.5, D97 — user spec): an
                // UNTOUCHED round-trip is a no-op — restore the
                // stashed Optimize-length state exactly; a HAND-MOVED
                // placement comes back AT the manual bridge's own
                // arc, length check off (the placement will move onto
                // the march at that length — the user owns the
                // length, the mode owns the placement). Bridges with
                // no stash (fresh — manual is the v13.7 default)
                // keep the v13.8 entry rule: knee + check on.
                double[] st = bs.noneStash;
                bs.noneStash = null;
                boolean untouched = false;
                if (st != null
                        && bp + 1 < app.coaster.pieces.size()) {
                    TrackPiece t = app.coaster.pieces.get(bp + 1);
                    untouched = Math.abs(t.x - st[1]) < 1e-9
                        && Math.abs(t.y - st[2]) < 1e-9
                        && Math.abs(t.z - st[3]) < 1e-9
                        && Math.abs(t.yawDeg - st[4]) < 1e-9
                        && Math.abs(t.pitchDeg - st[5]) < 1e-9
                        && Math.abs(t.rollDeg - st[6]) < 1e-9;
                }
                if (st == null) {
                    bs.optLen = true;
                    applyOptimalLength(bs);
                } else if (untouched) {
                    bs.optLen = st[0] == 1;
                    if (bs.optLen) {
                        applyOptimalLength(bs);
                    } else {
                        applyBridgeGap(bs, bs.lastOptDist > 0
                            ? bs.lastOptDist
                            : Math.max(1, activeBridgeGap()));
                    }
                } else {
                    bs.optLen = false;
                    applyBridgeGap(bs, Math.max(1, activeBridgeGap()));
                }
            } else {
                // switching BETWEEN optimize modes keeps the CURRENT
                // length (v13.12, D86 — re-running the knee ladder
                // per mode moved the length and the shape even with
                // the yaw already aligned: the user's "even though
                // the Yaw hasn't changed, there seems to be a
                // difference"; at the same length an aligned yaw
                // makes both modes' placements identical)
                applyBridgeGap(bs, bs.lastOptDist > 0 ? bs.lastOptDist
                    : Math.max(1, activeBridgeGap()));
            }
        } else {
            // ENTERING manual mode from an optimize mode: stash the
            // state so an untouched round-trip restores it (v14.5)
            if (mode == BridgeSection.OPT_NONE
                    && wasMode != BridgeSection.OPT_NONE
                    && !bridgeOrphan(bs)
                    && bp + 1 < app.coaster.pieces.size()) {
                TrackPiece t = app.coaster.pieces.get(bp + 1);
                bs.noneStash = new double[]{ bs.optLen ? 1 : 0,
                    t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg };
            }
            redraw();
        }
        app.updateBridgeControls();
    }

    /** Next-piece DISPLAYED orientation (axis 0 = yaw, 1 = pitch, 2 =
     *  roll — v13.13, D87; the canonical ABSOLUTE heading/elevation/
     *  bank of its entry since v19.1 D224 — the rows show the real
     *  starting orientation, a split piece's included). */
    double bridgeTargetOrient(int axis) {
        if (!activeBridgeWithTarget()) return 0;
        TrackPiece t = app.coaster.pieces.get(bridgePieceCtx() + 1);
        // the pitch/roll boxes show the tier the target's mode EDITS
        // (D249): the design elevation/bank under DESIGN_ONLY
        boolean dOnly = t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        return axis == 0 ? t.dispYaw()
            : axis == 1 ? (dOnly ? t.dispDesignPitch() : t.dispPitch())
            : (dOnly ? t.dispDesignRoll() : t.dispRoll());
    }

    /** The context bridge's TARGET piece (the piece after it), or
     *  null without a live, non-orphaned bridge (D249 — the bridge
     *  window's PY-exclusion reads its orientation mode). */
    TrackPiece bridgeTargetPiece() {
        if (!activeBridgeWithTarget()) return null;
        int ti = bridgePieceCtx() + 1;
        return ti < app.coaster.pieces.size() ? app.coaster.pieces.get(ti)
                                              : null;
    }

    /** Write a next-piece DISPLAYED orientation and re-apply per mode
     *  (v13.13): reachable for every EDITABLE axis since v19.20
     *  (D249, the seam rule — BridgeSection.axisEditable: a HELD
     *  (locked) axis in every mode = the typed value is what the
     *  march meets, a free axis outside optimize-orientation; a free
     *  axis under optimize-orientation is the march's and refuses —
     *  its box is grayed with the reason). ROUTED through the
     *  target's orientation mode since v19.19 (D248 — setDispPitch/
     *  Roll(…, true) = editPitch/editRoll; the 3-state mode D249):
     *  LOCKSTEP (the default) moves BOTH tiers, so the target
     *  RE-SOLVES and the bridge re-marches once; REAL_ONLY = the real
     *  tier alone, the conflict opens (the D222 classic); DESIGN_ONLY
     *  = the design tier alone. The MARCH's own alignment
     *  (Coaster.alignPieceForBridge) takes the SAME route since D249
     *  — one tier rule for every writer. */
    void setBridgeTargetOrient(int axis, double deg) {
        BridgeSection bs = ctxBridge();
        if (bs == null || bridgeOrphan(bs)) return;
        if (!bs.axisEditable(axis)) return;   // the march's axis (D249)
        long now = System.nanoTime();
        if (now - lastGapNanos > 800_000_000L) pushUndoSnapshot();
        lastGapNanos = now;
        int bp = bridgePieceCtx();
        TrackPiece t = app.coaster.pieces.get(bp + 1);
        bridgeTargetWrite(bp, () -> {
            if (axis == 0) t.setDispYaw(deg);
            else if (axis == 1) t.setDispPitch(deg, true);
            else t.setDispRoll(deg, true);
        });
    }

    /** ONE tail for every window write to the TARGET piece (D248 —
     *  the bridge window's spinners, its ↺ reverts and Real → Design;
     *  since v19.20 (D249) the target's Track Piece window rows + ↺
     *  too, so the two windows are ONE route): the write runs through
     *  placementEdit(bp + 1) (the D221 carry per the target's own
     *  flags), then the bridge re-places per mode — BOTH optimize
     *  modes at the current (or freshly kneed) length: OPT_POS
     *  re-lands the position (the setBridgeTargetOrient recipe),
     *  OPT_POS_YAW re-marches with alignYaw so the hump/twist MEETS a
     *  freshly typed HELD value (D249 — a held axis is typeable there
     *  now; the free axes re-align, the value the user just typed is
     *  a held one by the enable rule) — else a plain rebuild; the
     *  grayed Length box tracks a manual bridge live (v14.5).
     *  Callers push undo / refuse non-editable axes themselves. */
    void bridgeTargetWrite(int bp, Runnable write) {
        BridgeSection bs = withBridge(bp, () -> ctxBridge());
        if (bs == null || bp + 1 >= app.coaster.pieces.size()) return;
        withBridge(bp, () -> {
            app.placementEdit(bp + 1, write);
            app.markDirty();
            if (bs.optMode != BridgeSection.OPT_NONE && !bridgeOrphan(bs)) {
                if (bs.optLen) applyOptimalLength(bs);
                else applyBridgeGap(bs, bs.lastOptDist > 0 ? bs.lastOptDist
                    : Math.max(1, activeBridgeGap()));
            } else {
                app.rebuildTrack();
                refreshOffset();
                redraw();
            }
        });
        app.updateBridgeControls();
    }

    boolean bridgeLock(int axis) {
        BridgeSection bs = ctxBridge();
        if (bs == null) return axis != 0;   // defaults for a dead panel
        return axis == 0 ? bs.lockYaw : axis == 1 ? bs.lockPitch
                                                  : bs.lockRoll;
    }

    /** Toggle an orientation lock (v13.13) — THE ONE lock choke since
     *  v19.20 (D249): the bridge window's padlock AND the target's
     *  Track Piece padlock (routed here through withBridge) share it,
     *  so a flip behaves the same from either window. LOCKING
     *  CAPTURES the current angle (the seam pattern, D235): the held
     *  value IS the target piece's own yaw/pitch/roll field, so the
     *  capture needs no write — the value the axis shows at the click
     *  is the value the march meets from now on, and nothing jumps
     *  (bitwise, PlacementGate h1c/h7). In optimize-orientation mode
     *  the change re-applies immediately at the last requested length
     *  (bitwise the historic re-march): unlocking hands the axis to
     *  the march (it re-aligns and jumps to its natural value; under
     *  LOCKSTEP both tiers move, under REAL_ONLY the conflict opens —
     *  item M), locking makes the march meet the captured value
     *  instead. In the other two modes a flip moves nothing — it only
     *  changes which rings the bridge-end rig builds. One undo step. */
    void setBridgeLock(int axis, boolean locked) {
        BridgeSection bs = ctxBridge();
        if (bs == null || bridgeLock(axis) == locked) return;
        pushUndoSnapshot();
        if (axis == 0) bs.lockYaw = locked;
        else if (axis == 1) bs.lockPitch = locked;
        else bs.lockRoll = locked;
        app.markDirty();
        if (bs.optMode == BridgeSection.OPT_POS_YAW && !bridgeOrphan(bs)) {
            applyBridgeGap(bs, bs.lastOptDist > 0 ? bs.lastOptDist
                : Math.max(1, activeBridgeGap()));
        } else {
            redraw();
        }
        app.updateBridgeControls();
        app.syncPlacementSpinners();   // the target's Track Piece padlock (D249)
    }


    double bridgeTargetPos(int axis) {
        if (!activeBridgeWithTarget()) return 0;
        TrackPiece t = app.coaster.pieces.get(bridgePieceCtx() + 1);
        return axis == 0 ? t.x : axis == 1 ? t.y : t.z;
    }

    void setBridgeTargetPos(int axis, double v) {
        BridgeSection bs = ctxBridge();
        if (bs == null || bridgeOrphan(bs)
                || bs.optMode != BridgeSection.OPT_NONE) return;
        long now = System.nanoTime();
        if (now - lastGapNanos > 800_000_000L) pushUndoSnapshot();
        lastGapNanos = now;
        int bp = bridgePieceCtx();
        TrackPiece t = app.coaster.pieces.get(bp + 1);
        app.placementEdit(bp + 1, () -> {   // D221 rigid carry
            if (axis == 0) t.x = v;
            else if (axis == 1) t.y = v;
            else t.z = v;
        });
        app.rebuildTrack();
        refreshOffset();
        redraw();
        // the grayed Length box tracks the manual bridge live (v14.5)
        app.updateBridgeControls();
    }

    // ---------- trailing-bridge GIZMO write paths (v16.12) ----------
    // the gizmo reveal shows the piece's trailing bridge whatever
    // SECTION is active (user spec), so these resolve the bridge by
    // PIECE, not by the active section, and push NO undo — the
    // viewport pushes once per gesture. PIECE-INDEXED since v19.18
    // (D247 — every junction of the run wears a reveal ball, so the
    // bridge-end ball of a NON-active piece reveals a rig whose
    // writes must reach THAT piece's trailing bridge): the indexed
    // forms take the piece the bridge trails (target = p + 1); the
    // no-arg forms delegate to the active piece BITWISE (the toolbar
    // spinner / end-line paths never changed).

    private BridgeSection trailingBridge() {
        return trailingBridge(activePiece);
    }

    /** Piece p's trailing bridge with a live target, or null. */
    private BridgeSection trailingBridge(int p) {
        if (p < 0 || p >= app.coaster.pieces.size()) return null;
        List<Section> secs = app.coaster.pieces.get(p).sections;
        return !secs.isEmpty()
            && secs.get(secs.size() - 1) instanceof BridgeSection bs
            && p + 1 < app.coaster.pieces.size() ? bs : null;
    }

    /** The trailing bridge's length as the gizmo reads it (the
     *  requestedBridgeGap rule, piece-scoped). */
    double gizmoBridgeGap() {
        return gizmoBridgeGap(activePiece);
    }

    double gizmoBridgeGap(int p) {
        BridgeSection bs = trailingBridge(p);
        if (bs == null) return 1;
        if (bs.optMode != BridgeSection.OPT_NONE && bs.lastOptDist > 0) {
            return bs.lastOptDist;
        }
        TrackCompiler.Span sp =
            spanAt(p, app.coaster.pieces.get(p).sections.size() - 1);
        if (sp != null && sp.sLen() > 1e-6) return sp.sLen();
        return p + 1 < app.coaster.pieces.size()
            ? app.coaster.pieces.get(p + 1).entryPose().pos()
                .sub(bs.solvedEntry.pos()).length() : 1;
    }

    void gizmoSetBridgeGap(double dist) {
        gizmoSetBridgeGap(activePiece, dist);
    }

    void gizmoSetBridgeGap(int p, double dist) {
        BridgeSection bs = trailingBridge(p);
        if (bs == null || dist <= 0
                || bs.optMode == BridgeSection.OPT_NONE || bs.optLen) return;
        app.markDirty();
        applyBridgeGap(p, bs, dist);
        app.updateBridgeControls();
    }

    /** Manual-mode target moved by the gizmo (the fields are already
     *  written — one rebuild + panel re-sync). */
    void gizmoBridgeMoved() {
        gizmoBridgeMoved(activePiece);
    }

    void gizmoBridgeMoved(int p) {
        // the POV clamp (v17.11) guards the bridge window
        // open on the ACTIVE bridge section — another piece's bridge
        // has no panel to protect (D247)
        boolean povOn = p == activePiece && povInsideActiveBridge();
        app.markDirty();
        app.rebuildTrack();
        if (povOn) clampPovIntoActiveBridge();   // v17.11
        refreshOffset();
        redraw();
        app.updateBridgeControls();
    }

    void gizmoSetBridgeOrient(int axis, double deg) {
        gizmoSetBridgeOrient(activePiece, axis, deg);
    }

    /** A bridge-end ring write (v16.12): the rig builds rings for the
     *  HELD (locked) axes only since v19.20 (D249 — the seam rig's
     *  rule; manual + position modes, optimize-orientation keeps the
     *  tangent arrow alone), so a ring drag IS a held-value write;
     *  a free axis has no ring and refuses here defensively. */
    void gizmoSetBridgeOrient(int p, int axis, double deg) {
        BridgeSection bs = trailingBridge(p);
        if (bs == null || bs.optMode == BridgeSection.OPT_POS_YAW) return;
        if (!bs.axisLocked(axis)) return;   // no ring for a free axis (D249)
        if (p + 1 >= app.coaster.pieces.size()) return;
        TrackPiece t = app.coaster.pieces.get(p + 1);
        // RAW absolute coordinates from the ring (D224 — continuous
        // through the pole), ROUTED through the target's orientation
        // mode since v19.19 (D248/D249: LOCKSTEP = both tiers)
        app.placementEdit(p + 1, () -> {   // D221 rigid carry
            if (axis == 0) t.setRawAbsYaw(deg, true);
            else if (axis == 1) t.setRawAbsPitch(deg, true);
            else t.setRawAbsRoll(deg, true);
        });
        app.markDirty();
        if (bs.optMode == BridgeSection.OPT_POS) {
            // re-place at the current (or freshly kneed) length —
            // NEVER applyOptimalLength here: its zero-distance attach
            // PROMPT must not pop mid-drag
            double d = bs.optLen
                ? Math.max(1, app.coaster.optimalBridgeLength(bs, false))
                : Math.max(1, gizmoBridgeGap(p));
            applyBridgeGap(p, bs, d);
        } else {
            app.rebuildTrack();
            refreshOffset();
            redraw();
        }
        app.updateBridgeControls();
    }

    /** The local ring's COMBINED bridge write (v18.19, D190): all
     *  three next-piece deltas in one apply — the HELD (locked) axes
     *  take the decomposed values since v19.20 (D249, the seam rig's
     *  rule: a free axis has no ring and is left alone, like a
     *  following seam axis), same re-place tail as the per-axis
     *  path. */
    void gizmoSetBridgeOrientAll(double yaw, double pitch, double roll) {
        gizmoSetBridgeOrientAll(activePiece, yaw, pitch, roll);
    }

    void gizmoSetBridgeOrientAll(int p, double yaw, double pitch,
                                 double roll) {
        BridgeSection bs = trailingBridge(p);
        if (bs == null || bs.optMode == BridgeSection.OPT_POS_YAW) return;
        if (p + 1 >= app.coaster.pieces.size()) return;
        TrackPiece t = app.coaster.pieces.get(p + 1);
        app.placementEdit(p + 1, () -> {   // D221 rigid carry
            if (bs.lockYaw) t.yawDeg = yaw;
            // routed through the orientation mode (D248/D249):
            // editPitch/editRoll — no clamp either way (D224)
            if (bs.lockPitch) t.editPitch(pitch);
            if (bs.lockRoll) t.editRoll(roll);
        });
        app.markDirty();
        if (bs.optMode == BridgeSection.OPT_POS) {
            double d = bs.optLen
                ? Math.max(1, app.coaster.optimalBridgeLength(bs, false))
                : Math.max(1, gizmoBridgeGap(p));
            applyBridgeGap(p, bs, d);
        } else {
            app.rebuildTrack();
            refreshOffset();
            redraw();
        }
        app.updateBridgeControls();
    }

    /** Is (x, y) on the ACTIVE bridge's end boundary line (the piece
     *  end)? Grab range mirrors the s/c separators; only a bridge with
     *  a live target resizes (v13.3 — the draggable gap). */
    // ---------- PARTIAL bridge convert (v13.11, D85) ----------
    // a bridge's badge-menu convert opens a SPLIT-LINE mode on its
    // strip instead of converting whole: drag the line (percent
    // readout), click a side to choose it (default left, tinted +
    // brief flash), then the [Convert …] chip applies — the chosen
    // side becomes editable lanes, the other side remains a live
    // bridge (Coaster.partialConvertBridge).
    private int pcSec = -1;           // section index in mode; -1 off
    private boolean pcToGeo;          // requested lane mode
    // requested output settings (v16.6, D116 — the Convert dialog)
    private Section.Domain pcDom = Section.Domain.TIME;
    private boolean pcRollG, pcYpG, pcRateM, pcRollAbs, pcYpAbs;
    private double pcFrac = 0.5;      // split fraction (arc or time)
    private boolean pcLeft = true;    // which side converts
    private boolean pcDragLine = false;
    private long pcFlashNanos = 0;    // side-click flash decay
    private double[] pcSideRect, pcApplyRect, pcCancelRect;   // chip
                                    // hit rects (side toggle, D239)

    /** ALL convertible sections take the split-line mode since v14.0
     *  (D89 — was bridge-only): lane sections cut by TIME (and SNAP
     *  to clicked nodes), bridge + s/c by ARC (the s/c line clamps
     *  outside blend/ease windows via Coaster.scCutClamp). */
    void beginPartialConvert(int k, boolean toGeo) {
        beginPartialConvert(k, toGeo, Section.Domain.TIME,
            false, false, false);
    }

    void beginPartialConvert(int k, boolean toGeo, Section.Domain dom,
                             boolean rollG, boolean ypG, boolean rateM) {
        beginPartialConvert(k, toGeo, dom, rollG, ypG, rateM, false);
    }

    /** Full-settings variant (v16.6, D116): the converted side comes
     *  out with the requested type/domain/axes/rate units/roll
     *  behavior. */
    void beginPartialConvert(int k, boolean toGeo, Section.Domain dom,
                             boolean rollG, boolean ypG, boolean rateM,
                             boolean rollAbs) {
        beginPartialConvert(k, toGeo, dom, rollG, ypG, rateM, rollAbs,
            false);
    }

    /** + ABSOLUTE yaw/pitch target (v19.8, D237). */
    void beginPartialConvert(int k, boolean toGeo, Section.Domain dom,
                             boolean rollG, boolean ypG, boolean rateM,
                             boolean rollAbs, boolean ypAbs) {
        if (k < 0 || k >= sections().size()) return;
        Section sec = sections().get(k);
        if (sec.reversed()) return;
        if (!(sec instanceof BridgeSection || sec instanceof LaneSection
                || sec instanceof StraightCurveSection)) return;
        // the split-line mode runs on the NATIVE axis (v16.0): a live
        // cross-domain view would put the line's fraction and the
        // model's cut in different units — drop the view first
        if (sec.viewOtherDomain()) sec.viewOtherDomain(false);
        pcSec = k;
        pcToGeo = toGeo;
        pcDom = dom;
        pcRollG = rollG;
        pcYpG = ypG;
        pcRateM = rateM;
        pcRollAbs = rollAbs;
        pcYpAbs = ypAbs;
        pcFrac = 0.5;
        pcLeft = true;
        pcDragLine = false;
        pcFlashNanos = System.nanoTime();
        redraw();
    }

    private void cancelPartialConvert() {
        pcSec = -1;
        pcDragLine = false;
        redraw();
    }

    private void applyPartialConvert() {
        int k = pcSec;
        boolean left = pcLeft, toGeo = pcToGeo;
        double frac = pcFrac;
        int piece = activePiece;
        pcSec = -1;
        pcDragLine = false;
        if (k < 0 || k >= sections().size()) {
            redraw();
            return;
        }
        boolean bridge = sections().get(k) instanceof BridgeSection;
        Section.Domain dom = pcDom;
        boolean rollG = pcRollG, ypG = pcYpG, rateM = pcRateM;
        boolean rollAbs = pcRollAbs, ypAbs = pcYpAbs;
        ConvertOp op = bridge
            ? (sp, tol) -> app.coaster.partialConvertBridge(piece, k,
                frac, left, toGeo, FvdDesigner.INTEGRATE_DT, sp, tol,
                dom, rollG, ypG, rateM, rollAbs, ypAbs)
            : (sp, tol) -> app.coaster.partialConvertSection(piece, k,
                frac, left, toGeo, FvdDesigner.INTEGRATE_DT, sp, tol,
                dom, rollG, ypG, rateM, rollAbs, ypAbs);
        beginDensityPreview(k, toGeo, false,
            dom == Section.Domain.DISTANCE, op, () -> {
            if (bridge && !left
                    && piece + 1 < app.coaster.pieces.size()) {
                activePiece = piece + 1;   // lanes prepend to the next piece
                activeSection = 0;
            } else {
                activePiece = piece;
                activeSection = left ? k : k + 1;   // the converted side
            }
        });
    }

    // ---------- density preview (v14.0, D90) ----------
    // EVERY convert commits through this: the op applies at the
    // current density immediately, a small popup tunes it live
    // (restore -> re-run per change — applyDocState ADOPTS a
    // snapshot's objects, so the pristine state is re-captured after
    // every restore), the ORIGINAL compiled curves stay behind as a
    // dashed reference, Apply pushes ONE undo step (the pre-convert
    // state), Cancel restores it exactly. Canvas presses are consumed
    // while the popup is open (the preview owns the section).

    private interface ConvertOp {
        boolean apply(double spacing, double adaptTol);
    }

    private FvdDesigner.DocState pvBefore;  // pristine pre-convert state
    private ConvertOp pvOp;
    private Runnable pvActivate;
    private javafx.stage.Popup pvPopup;
    private double[][] pvRef;               // {globalT, v0, v1, v2} rows
    private int pvPiece = -1, pvSec = -1;   // the preview's own activation
    private boolean pvApplying;             // restore-in-progress guard
    // REVERSE-PIECE preview (v17.0, D134 — reversal IS a convert):
    // the popup grows a padlocked entry-speed row; the guide inputs
    // are STASHED from the pristine compile at begin time (the live
    // arrays hold the applied result during the preview)
    private double pvRevBase = Double.NaN;  // recorded end speed;
                                            // NaN = not a reversal
    private double pvRevCustom = Double.NaN; // unlocked value; NaN = auto
    private List<FVDIntegrator.Sample> pvRevSamples;  // pristine mirror
    private double[][] pvRevRates;          // design rates, rev order
    private double pvRevT0;                 // piece's global start time
    private int pvRevQMax;                  // new section 0's rev extent
    private boolean pvRevGeo;               // its mode (guide quantities)
    private double pvSpacing = Coaster.NODE_SPACING_DEFAULT;   // sticky
    // DISTANCE-target fits space nodes in METERS (v16.1, D104) —
    // separate sticky value + its own slider range
    private double pvSpacingDist = Coaster.NODE_SPACING_DEFAULT_DIST;
    private boolean pvDist = false;         // target domain is DISTANCE
    private boolean pvAdaptive = false;     // "Optimal placement"
    private double pvTol = 0.01;            // fraction of the lane range
    private TextField pvField;
    private Slider pvSlider;
    private Label pvLabel, pvCount;
    private boolean pvSyncing;

    boolean previewActive() { return pvBefore != null; }

    /** The convert UI owns the graph (v14.4, user spec): while the
     *  split line or the density preview is up, bound graph/file key
     *  actions are dead — same philosophy as mid-gesture keys (Ctrl+S
     *  must not save mid-drag). Mouse edits are already consumed by
     *  the modes' own press handling. */
    boolean convertUiActive() { return pvBefore != null || pcSec >= 0; }

    private void beginDensityPreview(int k, boolean toGeo, ConvertOp op,
                                     Runnable activate) {
        beginDensityPreview(k, toGeo, false, false, op, activate);
    }

    private void beginDensityPreview(int k, boolean toGeo, boolean useTrue,
                                     ConvertOp op, Runnable activate) {
        beginDensityPreview(k, toGeo, useTrue, false, op, activate);
    }

    /** useTrue: draw the TRUE curves as the reference — for ops whose
     *  fit targets the real ride (bridge converts, make-real; v14.5,
     *  D97) rather than the design profile. distTarget: the fit
     *  spaces nodes in METERS (domain conversions to DISTANCE,
     *  v16.1 — the popup relabels and re-ranges). */
    private void beginDensityPreview(int k, boolean toGeo, boolean useTrue,
                                     boolean distTarget,
                                     ConvertOp op, Runnable activate) {
        cancelDensityPreview();          // one preview at a time
        // the strict-seam pop-out comes down first (D247 review fix —
        // the range flow's pattern): every pvRerun tick restores
        // pvBefore and REPLACES the piece objects, so its captured
        // follower would be an orphan for the preview's whole life
        // both index-resolved junction windows (D248 review fix — a
        // whole-bridge convert attaches the target and shifts every
        // later piece; the seam window always hid here)
        hideJunctionWindows();
        // EVERY open starts at "Optimal placement" (v18.32, D204 —
        // user spec): the adaptive fit is the default whatever the
        // last preview was left on; unchecking is per-open
        pvAdaptive = true;
        pvDist = distTarget;
        captureReference(k, toGeo, useTrue);
        pvBefore = snapshot();
        pvOp = op;
        pvActivate = activate;
        if (!pvRerun()) {                // apply at the current density
            // a REFUSED first run puts the pristine document BACK
            // (v19.18, D247 — the leak: an op that mutates before
            // refusing — the range flow's composite inserts seams
            // first — used to leave those mutations in the doc with
            // no undo entry; pvRerun re-snapshotted pvBefore right
            // before the op, so it is exactly the pre-op state)
            FvdDesigner.DocState st = pvBefore;
            pvBefore = null;
            pvOp = null;
            pvActivate = null;
            pvRef = null;
            if (st != null) applySnapshot(st);
            // never refuse SILENTLY (v15.4, D102 — the "did nothing"
            // lesson): say why nothing happened
            app.statsLabel.setText(
                "Convert refused — the section is too short to measure.");
            redraw();
            return;
        }
        viewT0 = 0;
        viewT1 = -1;                     // structure changed: re-fit
        openPvPopup();
    }

    /** Restore the pristine state, re-capture it fresh, re-run the op
     *  at the current density, then the standard post-edit refresh. */
    private boolean pvRerun() {
        pvApplying = true;
        app.applyDocState(pvBefore);
        pvApplying = false;
        pvBefore = snapshot();
        boolean ok = pvOp.apply(pvDist ? pvSpacingDist : pvSpacing,
            pvAdaptive ? pvTol : 0);
        if (ok && pvActivate != null) pvActivate.run();
        activePiece = (int) FvdDesigner.clamp(activePiece, 0,
            app.coaster.pieces.size() - 1);
        activeSection = (int) FvdDesigner.clamp(activeSection, 0,
            sections().size() - 1);
        pvPiece = activePiece;
        pvSec = activeSection;
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        syncLaneButtons();
        // the preview slider re-applies the convert per DRAG EVENT —
        // a gesture (D200 verify round): rebuild fast; Apply/Cancel
        // settle exactly (bound keys are dead while the preview is
        // up, so no save can observe the tube state)
        boolean wasHot = app.gestureHot;
        app.gestureHot = true;
        app.rebuildTrack();
        app.gestureHot = wasHot;
        app.updatePieceButton();
        refreshOffset();
        if (pvCount != null) pvCount.setText(pvCountText());
        // REVERSAL stall telltale (v17.0, D134): friction is lost in
        // BOTH directions, so a piece reversed at its natural end
        // speed often can't climb back over what the forward ride
        // descended — the flow floors at the creep speed and the
        // tail goes garbage-but-finite. Say so instead of leaving
        // the user to puzzle out a ballooned strip.
        if (ok && !Double.isNaN(pvRevBase)) {
            boolean stalled = false;
            if (app.trueV != null) {
                for (double v : app.trueV) stalled |= v < 0.6;
            }
            app.statsLabel.setText(stalled
                ? "The reversed ride STALLS partway — friction is paid "
                  + "both ways. Unlock the entry speed and give it more."
                : "Reversing the piece — tune the node density, then "
                  + "Apply.");
        }
        redraw();
        return ok;
    }

    /** Commit: the pre-convert state becomes the single undo step. */
    private void commitDensityPreview() {
        if (pvBefore == null) return;
        pushUndo(pvBefore);
        clearPv(false);
        // the last pvRerun rebuilt HOT — the committed doc must not
        // rest inside the tube (D200; cancel settles via its
        // applySnapshot rebuild)
        app.settleIfPending();
        redraw();
        // a RANGE CHAIN advances to its next section (v19.18, D247):
        // this Apply was one undo step; the next section's preview
        // opens against the committed document
        if (chain != null) {
            if (chain.pos + 1 < chain.count()) {
                chain.pos++;
                chainStep();
            } else {
                chain = null;
                app.statsLabel.setText("Range resolved — every section "
                    + "of the range is converted; the seams around it "
                    + "stay strict.");
            }
        }
    }

    /** Cancel: put the pristine state back, no history entry. Also
     *  ENDS a range chain (D247) — the earlier Applies stay (each was
     *  its own honest undo step), the rest of the range keeps its
     *  conflict as a locked follower. */
    void cancelDensityPreview() {
        if (!chainOpening) chain = null;
        if (pvBefore == null) {
            clearPv(true);
            return;
        }
        FvdDesigner.DocState st = pvBefore;
        clearPv(false);
        applySnapshot(st);
    }

    private void clearPv(boolean popupOnly) {
        if (pvPopup != null) {
            // null-before-hide (v19.18, D247 — the tool-window
            // teardown rule; the press filter already committed a
            // typed spacing box before any button handler runs)
            javafx.stage.Popup old = pvPopup;
            pvPopup = null;
            old.hide();
        }
        pvFocusHidden = false;
        if (popupOnly) return;
        pvBefore = null;
        pvOp = null;
        pvActivate = null;
        pvRef = null;
        pvPiece = -1;
        pvSec = -1;
        pvRevBase = Double.NaN;   // reversal preview state (D134)
        pvRevCustom = Double.NaN;
        pvRevSamples = null;
        pvRevRates = null;
    }

    private String pvCountText() {
        Section sec = sections().get(activeSection);
        if (!(sec instanceof LaneSection ls)) return "";
        return String.format(Locale.US, "%d / %d / %d nodes",
            ls.lanes.get(0).size(), ls.lanes.get(1).size(),
            ls.lanes.get(2).size());
    }

    /** The tuning popup: typed value + log-scale slider (node spacing
     *  in seconds, or accuracy in %-of-range with "Optimal placement"
     *  checked), live re-fit per change, Apply / Cancel. */
    private void openPvPopup() {
        pvPopup = new javafx.stage.Popup();
        pvPopup.setAutoHide(false);
        pvLabel = new Label();
        pvLabel.setStyle("-fx-text-fill: rgb(200,205,215);");
        pvField = new TextField();
        pvField.setPrefColumnCount(4);
        pvField.setStyle("-fx-font-size: 11px;");
        // numeric-only, like the bridge length spinner (v13.4): a
        // focused editor still receives ride keys' KEY_TYPED letters
        pvField.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
            ch -> ch.getControlNewText().matches("\\d*\\.?\\d*") ? ch : null));
        pvSlider = new Slider();
        pvSlider.setPrefWidth(170);
        CheckBox optimal = new CheckBox("Optimal placement");
        optimal.setSelected(pvAdaptive);
        optimal.setStyle("-fx-text-fill: rgb(200,205,215);"
            + " -fx-font-size: 11px;");
        pvCount = new Label(pvCountText());
        pvCount.setStyle("-fx-text-fill: rgb(150,156,170);"
            + " -fx-font-size: 10px;");
        Button apply = new Button("Apply");
        Button cancel = new Button("Cancel");
        apply.setStyle("-fx-font-size: 11px;");
        cancel.setStyle("-fx-font-size: 11px;");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> commitDensityPreview());
        cancel.setOnAction(e -> cancelDensityPreview());
        pvSlider.valueProperty().addListener((o, ov, nv) -> {
            if (pvSyncing) return;
            setPvValue(Math.pow(10, nv.doubleValue()));
        });
        pvField.setOnAction(e -> {
            try {
                setPvValue(Double.parseDouble(pvField.getText().trim()));
            } catch (NumberFormatException ex) {
                syncPvControls();
            }
        });
        optimal.setOnAction(e -> {
            pvAdaptive = optimal.isSelected();
            syncPvControls();
            pvRerun();
        });
        HBox row1 = new HBox(6, pvLabel, pvField, pvSlider);
        row1.setAlignment(Pos.CENTER_LEFT);
        HBox row2 = new HBox(8, optimal, pvCount, apply, cancel);
        row2.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pvCount, Priority.ALWAYS);
        pvCount.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(7, row1, row2);
        // REVERSE-piece preview (v17.0, D134): a padlocked ENTRY
        // SPEED row on top — locked shows the recorded end speed
        // (what the ride reaches the piece's end with), unlock to
        // type a custom one; every change re-runs the reversal live
        if (!Double.isNaN(pvRevBase)) {
            Label sl = new Label("Entry speed (m/s)");
            sl.setStyle("-fx-text-fill: rgb(200,205,215);"
                + " -fx-font-size: 11px;");
            TextField sf = new TextField();
            sf.setPrefColumnCount(4);
            sf.setStyle("-fx-font-size: 11px;");
            sf.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                ch -> ch.getControlNewText().matches("\\d*\\.?\\d*")
                    ? ch : null));
            ToggleButton lock = new ToggleButton("🔒");
            lock.setFocusTraversable(false);
            lock.setStyle("-fx-font-size: 10px; -fx-padding: 1 5 1 5;");
            Runnable syncRow = () -> {
                boolean unlocked = lock.isSelected();
                lock.setText(unlocked ? "🔓" : "🔒");
                sf.setDisable(!unlocked);
                sf.setText(String.format(Locale.US, "%.1f",
                    Double.isNaN(pvRevCustom) ? pvRevBase : pvRevCustom));
            };
            lock.setOnAction(ev -> {
                if (!lock.isSelected()) pvRevCustom = Double.NaN;
                syncRow.run();
                rebuildReversedGuides();
                pvRerun();
            });
            sf.setOnAction(ev -> {
                try {
                    pvRevCustom = FvdDesigner.clamp(
                        Double.parseDouble(sf.getText().trim()), 1, 100);
                } catch (NumberFormatException ex) {
                    // keep the previous value
                }
                syncRow.run();
                rebuildReversedGuides();
                pvRerun();
            });
            syncRow.run();
            Tooltip.install(sl, new Tooltip(
                "The reversed run's starting speed — locked = the speed\n"
                + "the ride reaches this end with; unlock to type one."));
            HBox rowS = new HBox(6, sl, lock, sf);
            rowS.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(0, rowS);
        }
        box.setStyle("-fx-background-color: rgb(38,40,46);"
            + " -fx-padding: 10; -fx-border-color: rgb(95,100,112);"
            + " -fx-border-radius: 6; -fx-background-radius: 6;");
        // DRAGGABLE (v14.4, user spec — it sat over the nodes): grab
        // any non-control spot (labels, padding) and move it
        double[] grab = new double[2];
        box.setOnMousePressed(e -> {
            grab[0] = e.getScreenX() - pvPopup.getX();
            grab[1] = e.getScreenY() - pvPopup.getY();
        });
        box.setOnMouseDragged(e -> {
            pvPopup.setX(e.getScreenX() - grab[0]);
            pvPopup.setY(e.getScreenY() - grab[1]);
        });
        pvPopup.getContent().add(box);
        syncPvControls();
        // default spot: ABOVE the plot (over the 3D view's bottom
        // edge), clear of the converted strip's nodes (v14.4)
        Point2D at = canvas.localToScreen(
            Math.max(MARGIN_LEFT, tToX(tOff)) + 10, 0);
        if (at != null) {
            pvPopup.show(canvas.getScene().getWindow(), at.getX(), at.getY());
            pvPopup.setY(at.getY() - pvPopup.getHeight() - 8);
        }
        // ride/fly keys keep working while the popup holds key focus
        // (v16.2, user report — forwarded to the viewport)
        if (pvPopup.getScene() != null) {
            // UNDO KEYS under the preview (D247 review fix): the popup
            // takes key focus on open and its spacing box swallowed
            // Ctrl+Z as text undo, so a range chain's advertised
            // "Ctrl+Z steps back" was dead until the user clicked the
            // graph. A scene FILTER runs before the TextField's own
            // handler: a chain steps back, plain undo/redo stay dead
            // (the v14.4 rule) with the busy reason posted.
            pvPopup.getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                boolean undo = app.matches("undo", e);
                if (!undo && !app.matches("redo", e)) return;
                if (undo && chainActive()) chainStepBack();
                else app.statsLabel.setText(FvdDesigner.CONVERT_BUSY_MSG);
                e.consume();
            });
            pvPopup.getScene().addEventFilter(KeyEvent.KEY_PRESSED,
                e -> app.forwardViewKey(e, true));
            pvPopup.getScene().addEventFilter(KeyEvent.KEY_RELEASED,
                e -> app.forwardViewKey(e, false));
        }
        hookPvFocus();
    }

    // a JavaFX Popup floats ABOVE OTHER APPLICATIONS (it is its own
    // window): hide it when the app loses focus and bring it back —
    // same spot, still open — when the app returns (v14.4, user spec;
    // partial occlusion by another window is fine, that's normal
    // stacking). Registered once; guards make it a no-op while no
    // preview is up.
    private boolean pvFocusHooked, pvFocusHidden;
    private double pvHidX, pvHidY;

    private void hookPvFocus() {
        if (pvFocusHooked || canvas.getScene() == null
                || canvas.getScene().getWindow() == null) return;
        pvFocusHooked = true;
        canvas.getScene().getWindow().focusedProperty()
            .addListener((o, was, is) -> {
                if (!is && pvPopup != null && pvPopup.isShowing()) {
                    pvHidX = pvPopup.getX();
                    pvHidY = pvPopup.getY();
                    pvPopup.hide();
                    pvFocusHidden = true;
                } else if (is && pvFocusHidden) {
                    pvFocusHidden = false;
                    if (pvPopup != null && pvBefore != null) {
                        pvPopup.show(canvas.getScene().getWindow(),
                            pvHidX, pvHidY);
                    }
                }
            });
    }

    /** Slider/field ranges per mode: spacing 0.02–2 s / 0.2–20 m,
     *  accuracy 0.01–10 % of the lane's value range — all
     *  log-scaled (the D136 widening was walked back on user feel,
     *  v17.3). */
    private void syncPvControls() {
        if (pvSlider == null) return;
        pvSyncing = true;
        if (pvAdaptive) {
            pvLabel.setText("Accuracy (% of range)");
            pvSlider.setMin(Math.log10(0.01));
            pvSlider.setMax(Math.log10(10));
            pvSlider.setValue(Math.log10(FvdDesigner.clamp(
                pvTol * 100, 0.01, 10)));
            pvField.setText(String.format(Locale.US, "%.2f", pvTol * 100));
        } else if (pvDist) {
            // DISTANCE target (v16.1): meters, the ×10-ish range
            pvLabel.setText("Node spacing (m)");
            pvSlider.setMin(Math.log10(0.2));
            pvSlider.setMax(Math.log10(20.0));
            pvSlider.setValue(Math.log10(FvdDesigner.clamp(
                pvSpacingDist, 0.2, 20.0)));
            pvField.setText(String.format(Locale.US, "%.2f", pvSpacingDist));
        } else {
            pvLabel.setText("Node spacing (s)");
            pvSlider.setMin(Math.log10(0.02));
            pvSlider.setMax(Math.log10(2.0));
            pvSlider.setValue(Math.log10(FvdDesigner.clamp(
                pvSpacing, 0.02, 2.0)));
            pvField.setText(String.format(Locale.US, "%.2f", pvSpacing));
        }
        pvSyncing = false;
    }

    private void setPvValue(double v) {
        if (pvAdaptive) {
            pvTol = FvdDesigner.clamp(v, 0.01, 10) / 100.0;
        } else if (pvDist) {
            pvSpacingDist = FvdDesigner.clamp(v, 0.2, 20.0);
        } else {
            pvSpacing = FvdDesigner.clamp(v, 0.02, 2.0);
        }
        syncPvControls();
        pvRerun();
    }

    /** Sample the ORIGINAL compiled curves over section k's span in
     *  the TARGET mode's units — the dashed accuracy reference behind
     *  the preview (force view: felt Gv/Gl + resulting roll; geo
     *  view: the three resulting rates — same channels derivedAt
     *  shows). Ends trimmed a hair against bodyRates seam smear. */
    private void captureReference(int k, boolean toGeo, boolean useTrue) {
        pvRef = null;
        TrackCompiler.Span sp = spanOf(k);
        if (sp == null || app.lastSamples == null
                || app.resultRates == null
                || sp.i1() >= app.resultRates.length) return;
        boolean tru = useTrue && app.trueFelt != null
            && app.trueRates != null
            && sp.i1() < app.trueRates.length;
        int i0 = sp.i0() + 3, i1 = sp.i1() - 3;
        if (i1 - i0 < 4) return;
        // EVERY compiled sample (v19.11, D240 — the old 400-point
        // cap strided long sections into visible straight chords,
        // the user's "bunch of lines" report; captured ONCE per
        // preview open, and the ColPath stroke below keeps the draw
        // cost pixel-bounded like the Resulting overlays)
        int n = i1 - i0;
        double[][] ref = new double[n + 1][4];
        for (int i = 0; i <= n; i++) {
            int idx = i0 + i;
            FVDIntegrator.Sample sm = app.lastSamples.get(idx);
            ref[i][0] = sm.t();
            if (toGeo) {
                double[][] src = tru ? app.trueRates : app.resultRates;
                ref[i][1] = src[idx][1];
                ref[i][2] = src[idx][2];
                ref[i][3] = src[idx][3];
            } else {
                ref[i][1] = tru ? app.trueFelt[idx][0] : sm.Gv();
                ref[i][2] = tru ? app.trueFelt[idx][1] : sm.Gl();
                ref[i][3] = (tru ? app.trueRates : app.resultRates)[idx][3];
            }
        }
        pvRef = ref;
    }

    /** The reference polylines, dashed in the lane colors behind the
     *  live fit — per-sample through ColPath since v19.11 (D240:
     *  view-culled + pixel-column compacted, the Resulting overlays'
     *  machinery, so full density draws at pixel-bounded cost). */
    private void drawPvRef(GraphicsContext gc) {
        if (pvRef == null) return;
        gc.setLineWidth(1.2);
        gc.setLineDashes(5, 4);
        gc.setGlobalAlpha(0.55);
        double v0 = viewStart(), v1 = viewEnd();
        for (int lane = 0; lane < LANES; lane++) {
            gc.setStroke(laneColor(lane));
            ColPath path = new ColPath(gc);
            for (int i = 0; i < pvRef.length; i++) {
                // rows are keyed by ride TIME; a distance-target
                // preview plots on the meters axis (v16.1)
                double a = axisOfTime(pvRef[i][0]);
                // cull to the view window, keeping ONE row past
                // each edge so the entering/exiting chord draws
                if (a < v0 && i + 1 < pvRef.length
                        && axisOfTime(pvRef[i + 1][0]) < v0) {
                    continue;   // both this row and the next sit left
                }
                path.add(tToX(a), valToY(lane, pvRef[i][lane + 1]));
                if (a > v1) break;   // one row past the right edge
            }
            path.stroke();
        }
        gc.setLineDashes((double[]) null);
        gc.setGlobalAlpha(1.0);
        gc.setLineWidth(2);
    }

    /** 1 = Confirm, 2 = Cancel, 3 = the side toggle (v19.10, D239),
     *  0 = none. */
    private int pcChipHit(double x, double y) {
        if (pcApplyRect != null && x >= pcApplyRect[0]
                && x <= pcApplyRect[0] + pcApplyRect[2]
                && y >= pcApplyRect[1]
                && y <= pcApplyRect[1] + pcApplyRect[3]) return 1;
        if (pcCancelRect != null && x >= pcCancelRect[0]
                && x <= pcCancelRect[0] + pcCancelRect[2]
                && y >= pcCancelRect[1]
                && y <= pcCancelRect[1] + pcCancelRect[3]) return 2;
        if (pcSideRect != null && x >= pcSideRect[0]
                && x <= pcSideRect[0] + pcSideRect[2]
                && y >= pcSideRect[1]
                && y <= pcSideRect[1] + pcSideRect[3]) return 3;
        return 0;
    }

    /** Is the split fraction ARC-based (bridge, s/c, DISTANCE-domain
     *  lanes — v16.0) or TIME-based (time lanes)? Must match what the
     *  model op cuts by. */
    private boolean pcArcBased() {
        Section sec = pcSec >= 0 && pcSec < sections().size()
            ? sections().get(pcSec) : null;
        return sec instanceof BridgeSection
            || sec instanceof StraightCurveSection
            || (sec instanceof LaneSection ls
                && ls.domain() == Section.Domain.DISTANCE);
    }

    /** The split line's GLOBAL time for pcFrac: lane fractions map
     *  directly; ARC fractions map through the span's samples so the
     *  line sits exactly where the cut will land (v14.0 — it used to
     *  sit at the TIME fraction while the model cut the ARC fraction:
     *  visibly off wherever speed drifts along the section). */
    private double pcLineT() {
        double dur = Math.max(sectionDur(activeSection), 1e-6);
        // fraction axis == display axis: time-frac on the time axis,
        // arc-frac on the meters axis (v16.0) — both map linearly;
        // only an arc fraction shown on the TIME axis needs the
        // sample map (the historic bridge/s-c case)
        if (!pcArcBased() || axisDist()) return tOff + pcFrac * dur;
        TrackCompiler.Span sp = spanOf(pcSec);
        List<FVDIntegrator.Sample> s = app.lastSamples;
        if (sp == null || s == null || sp.sLen() < 1e-6) {
            return tOff + pcFrac * dur;
        }
        double arc = sp.s0() + pcFrac * sp.sLen();
        int lo = sp.i0(), hi = Math.min(sp.i1(), s.size() - 1);
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        double ds = s.get(hi).s() - s.get(lo).s();
        double u = ds < 1e-12 ? 0 : (arc - s.get(lo).s()) / ds;
        return s.get(lo).t() + u * (s.get(hi).t() - s.get(lo).t());
    }

    private double pcLineX() {
        return tToX(pcLineT());
    }

    /** Cursor x → split fraction (time or arc), 5–95 % clamped and,
     *  on s/c, shifted out of blend/ease windows (Coaster.scCutClamp
     *  — the same clamp the model applies, so the LINE sits where the
     *  cut can actually land). */
    private double pcFracAtX(double x) {
        double dur = Math.max(sectionDur(activeSection), 1e-6);
        double lt = FvdDesigner.clamp(xToT(x) - tOff, 0, dur);
        double f = lt / dur;
        if (pcArcBased() && !axisDist()) {
            TrackCompiler.Span sp = spanOf(pcSec);
            List<FVDIntegrator.Sample> s = app.lastSamples;
            if (sp != null && s != null && sp.sLen() > 1e-6) {
                double gt = tOff + lt;
                int lo = sp.i0(), hi = Math.min(sp.i1(), s.size() - 1);
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    if (s.get(mid).t() <= gt) lo = mid; else hi = mid;
                }
                double dtg = s.get(hi).t() - s.get(lo).t();
                double u = dtg < 1e-12 ? 0 : (gt - s.get(lo).t()) / dtg;
                double arc = s.get(lo).s()
                    + u * (s.get(hi).s() - s.get(lo).s());
                f = (arc - sp.s0()) / sp.sLen();
            }
        }
        f = FvdDesigner.clamp(f, 0.05, 0.95);
        if (sections().get(pcSec) instanceof StraightCurveSection sc
                && sc.totalLen() > 1e-6) {
            double a = Coaster.scCutClamp(sc, f * sc.totalLen());
            if (a >= 0) f = a / sc.totalLen();
        }
        return f;
    }

    /** Node-snap for the split line, ACTIVE lane only (v14.4, user
     *  spec — every lane's nodes snapped before, and a click anywhere
     *  on a node's vertical line grabbed it). Click mode (y >= 0):
     *  requires a DIRECT hit on the node body (x AND y within the
     *  node radius). Drag mode (y < 0): magnetizes on x alone within
     *  5 px. The model then cuts by partitioning the node lists — no
     *  inserted node. NaN = none in range. */
    private double pcNodeSnapT(double x, double y) {
        if (pcSec != activeSection
                || !(sections().get(pcSec) instanceof LaneSection)) {
            return Double.NaN;
        }
        double dur = Math.max(sectionDur(activeSection), 1e-6);
        double best = y >= 0 ? 7.0 : 5.0, bestT = Double.NaN;
        for (BezierBaker.Node nd : active()) {
            if (nd.t / dur < 0.05 || nd.t / dur > 0.95) continue;
            double dx = Math.abs(ltToX(nd.t) - x);
            if (y >= 0 && Math.abs(valToY(activeLane, nd.val) - y) > 7.0) {
                continue;   // click snap needs the node BODY, not its column
            }
            if (dx < best) {
                best = dx;
                bestT = nd.t;
            }
        }
        return bestT;
    }

    /** The split-line overlay: side tints (selection + flash), the
     *  draggable line, the percent readout, and the two chips. */
    private void drawPartialConvert(GraphicsContext gc, double dur) {
        double w = canvas.getWidth();
        double x0 = Math.max(tToX(tOff), MARGIN_LEFT);
        double x1 = Math.min(tToX(tOff + dur), w - marginRight());
        if (x1 - x0 < 20) {
            pcSideRect = pcApplyRect = pcCancelRect = null;
            return;
        }
        double lx = FvdDesigner.clamp(pcLineX(), x0, x1);
        double top = MARGIN_TOP, h = plotHeight();
        double flash = Math.max(0,
            1 - (System.nanoTime() - pcFlashNanos) / 3.5e8);
        gc.setFill(Color.rgb(255, 255, 255, 0.05));
        gc.fillRect(x0, top, x1 - x0, h);
        gc.setFill(Color.rgb(120, 200, 255, 0.10 + 0.12 * flash));
        if (pcLeft) gc.fillRect(x0, top, Math.max(0, lx - x0), h);
        else gc.fillRect(lx, top, Math.max(0, x1 - lx), h);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        gc.setGlobalAlpha(0.85);
        gc.strokeLine(lx, top, lx, top + h);
        gc.setGlobalAlpha(1.0);
        if (pcDragLine) {
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            gc.fillText(String.format(Locale.US, "%.0f%% of %s",
                pcFrac * 100, pcArcBased()
                    && sections().get(pcSec) instanceof BridgeSection
                    ? "bridge" : "section"),
                Math.min(lx + 8, x1 - 90), top + h * 0.45);
        }
        // chips, centered below the badge row — TWO ROWS since
        // v19.10 (D239, user spec): the side chip TOGGLES which
        // half converts (like clicking a side); [Confirm] below it
        // applies, [Cancel] to Confirm's right
        String at = "Convert " + (pcLeft ? "left" : "right") + " part";
        double aw = at.length() * 6 + 16;
        double bw = "Confirm".length() * 6 + 16;
        double cw = "Cancel".length() * 6 + 16;
        double sx = FvdDesigner.clamp((x0 + x1) / 2 - aw / 2,
            x0 + 2, Math.max(x0 + 2, x1 - aw - 2));
        double cy = top + 26;
        double cx = FvdDesigner.clamp((x0 + x1) / 2 - (bw + cw + 8) / 2,
            x0 + 2, Math.max(x0 + 2, x1 - bw - cw - 10));
        double cy2 = cy + 17 + 6;
        pcSideRect = new double[]{ sx, cy, aw, 17 };
        pcApplyRect = new double[]{ cx, cy2, bw, 17 };
        pcCancelRect = new double[]{ cx + bw + 8, cy2, cw, 17 };
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setLineWidth(1);
        // the side toggle wears the side-tint blue — it reads as
        // the selection it flips
        gc.setFill(Color.rgb(50, 80, 110));
        gc.fillRoundRect(sx, cy, aw, 17, 8, 8);
        gc.setStroke(Color.rgb(120, 200, 255));
        gc.strokeRoundRect(sx, cy, aw, 17, 8, 8);
        gc.setFill(Color.WHITE);
        gc.fillText(at, sx + aw / 2, cy + 8.5);
        gc.setFill(Color.rgb(60, 110, 70));
        gc.fillRoundRect(cx, cy2, bw, 17, 8, 8);
        gc.setStroke(Color.rgb(150, 220, 160));
        gc.strokeRoundRect(cx, cy2, bw, 17, 8, 8);
        gc.setFill(Color.WHITE);
        gc.fillText("Confirm", cx + bw / 2, cy2 + 8.5);
        gc.setFill(Color.rgb(70, 60, 60));
        gc.fillRoundRect(cx + bw + 8, cy2, cw, 17, 8, 8);
        gc.setStroke(Color.rgb(200, 160, 150));
        gc.strokeRoundRect(cx + bw + 8, cy2, cw, 17, 8, 8);
        gc.setFill(Color.WHITE);
        gc.fillText("Cancel", cx + bw + 8 + cw / 2, cy2 + 8.5);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    private boolean hitBridgeEnd(double x, double y) {
        if (pcSec >= 0) return false;   // the split mode owns the strip
        if (!activeBridgeWithTarget()
                || activeBridge().optMode == BridgeSection.OPT_NONE
                || activeBridge().optLen) {
            return false;   // manual mode / auto length: nothing to drag
        }
        if (y < MARGIN_TOP || y > MARGIN_TOP + plotHeight()) return false;
        return Math.abs(x - tToX(tOff + sectionDur(activeSection))) <= 5;
    }

    /** The running bridge end-line drag (v13.3): the cursor's fraction
     *  of the current strip maps to a new GAP distance and re-runs the
     *  same placement optimization as the toolbar spinner — the next
     *  piece slides along the chain end's tangent, the live bridge
     *  follows, and the recompile re-maps the cursor each event (the
     *  s/c boundary-drag pattern). Ctrl snaps the gap by the Options
     *  element-length step. */
    private void bridgeDragTo(double x, boolean snap) {
        BridgeSection bs = activeBridge();
        if (bs == null || bridgeOrphan(bs)) return;
        double dur = Math.max(sectionDur(activeSection), 1e-6);
        double frac = Math.max(0.05, (xToT(x) - tOff) / dur);
        double gap = requestedBridgeGap() * frac;
        if (snap) {
            double step = app.settings.snapLen();
            gap = Math.round(gap / step) * step;
        }
        gap = FvdDesigner.clamp(gap, 1, 1000);   // the spinner's range
        applyBridgeGap(bs, gap);
    }

    /** Connect floating piece p after the ACTIVE piece (v12.0;
     *  a STRICT SEAM since v17.1, D135): the piece reorders to come
     *  next, snaps onto this piece's end with yaw following and its
     *  SHAPE frozen rigid, and the seam settings pop up. One undo
     *  step. */
    private void attachFloating(int p) {
        // WHOLE RUNS since v19.19 (D248): the picked run moves as a
        // block (Coaster.connectStrictRun); pre-checked BEFORE the
        // undo push (D247 — a refusal pushes nothing)
        if (app.isWizardPiece(app.activeCoaster, p)) {
            app.statsLabel.setText(WIZARD_OPEN_WHY);   // apply-time twin
            return;                                    // of the gray (D248)
        }
        if (!app.coaster.canMoveRun(p, activePiece)) {
            app.statsLabel.setText("Attach refused — "
                + moveRefuseWhy(p, activePiece));
            return;
        }
        hideJunctionWindows();   // piece indices shift (D247 review fix; + the bridge window, D248)
        spHideCtlPopup();   // the speed popup is index-resolved too (D248)
        app.dropGizmoReveals();   // Reveal(piece, kind) holds an INDEX —
                                  // a reorder would rig another piece
                                  // of the merged run (D248 review fix)
        pushUndoSnapshot();
        // PITCH + ROLL always LOCK at the piece's PRE-ATTACH angles
        // (v19.8, D237, user spec — superseding D153's padlock
        // seeding): the attached piece keeps exactly the orientation
        // it had, so a conflict-free layout stays conflict-free —
        // only the honest speed split at the seam remains. Yaw stays
        // unlocked (follows the seam heading, the attach semantics).
        int np = app.coaster.connectStrictRun(activePiece, p,
            false, true, true);
        // the follower lands right after the active piece whatever
        // moved (a block from BELOW shifted us down by its length)
        activePiece = np - 1;
        app.lanes = lanesFor(sections().get(activeSection));   // same
                                                 // objects; re-pointed
                                                 // by rule (D240)
        app.rebuildTrack();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
        if (np >= 0) showStrictSeamSettings(np);
    }

    /** Split the ACTIVE piece before section k (v12.0; exact since
     *  v12.1): sections k.. move to a NEW floating piece placed at the
     *  compiled boundary POSE — position and FULL orientation (into
     *  baseOrient), so the track does not move AT ALL, whatever the
     *  boundary's pitch or bank (user rule, gated bitwise). If the
     *  section being edited moved, the editor follows it. One undo
     *  step. */
    private void splitAt(int k) {
        if (k <= 0 || k >= sections().size()) return;
        pushUndoSnapshot();
        splitAtCore(k);
    }

    /** The piece-split body without the undo push (v16.18 — the
     *  node-anchored split chains it after its own section cut,
     *  inside ONE undo step). */
    private void splitAtCore(int k) {
        if (k <= 0 || k >= sections().size()) return;
        TrackCompiler.Span sp = spanOf(k - 1);
        if (sp == null) {
            app.statsLabel.setText("Couldn't split — this boundary "
                + "isn't compiled yet.");   // never silent (D235)
            return;
        }
        hideJunctionWindows();   // a new piece shifts every later index (D248 review fix)
        int np = app.coaster.splitPiece(activePiece, k, sp);
        if (np < 0) {
            app.statsLabel.setText("Couldn't split here.");   // D235
            return;
        }
        if (np >= 0 && activeSection >= k) {
            activePiece = np;         // follow the section being edited
            activeSection -= k;
        }
        activeSection = (int) FvdDesigner.clamp(activeSection, 0,
            sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        syncLaneButtons();
        app.rebuildTrack();
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
    }

    /** NODE-ANCHORED SPLIT (v16.18, D130 — the last Phase-2 item):
     *  cut the ACTIVE section in two at active-lane node idx —
     *  exactly the chain authoring two sections there would give
     *  (Coaster.splitSectionAt; the new seam carries the standard
     *  grid-truncation every seam has) — and optionally split the
     *  PIECE at the fresh boundary too (the D71 rigid pose copy).
     *  The editor stays on the LEFT half with the boundary node
     *  selected. One undo step; refusals post their reason. */
    void splitAtNode(int idx, boolean alsoPiece) {
        List<BezierBaker.Node> lane = active();
        if (activeInfl() != null) {
            app.statsLabel.setText("Splitting edits the design lanes — "
                + "leave the influence surface first.");
            return;
        }
        Section sec = sections().get(activeSection);
        if (!(sec instanceof LaneSection) || sec.reversed()) {
            app.statsLabel.setText(sec.reversed()
                ? "A reversed section's lanes read against the train — "
                    + "reverse the piece back to split it."
                : "Only force/geo sections split at a node.");
            return;
        }
        if (idx <= 0 || idx >= lane.size()
                || lane.get(idx).t >= sectionEnd() - 1e-9) {
            app.statsLabel.setText("Split at an INTERIOR node — the "
                + "section ends are boundaries already.");
            return;
        }
        double xc = lane.get(idx).t;
        pushUndoSnapshot();
        if (!app.coaster.splitSectionAt(activePiece, activeSection, xc,
                FvdDesigner.INTEGRATE_DT)) {
            app.statsLabel.setText("Couldn't split here.");
            return;
        }
        // the editor keeps the LEFT half; its last active-lane node
        // is the fresh boundary
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = Math.min(idx, active().size() - 1);
        selSet.clear();
        selSet.add(selIdx);
        elSel = -1;
        app.rebuildTrack();   // fresh spans before a piece split
        if (alsoPiece) {
            splitAtCore(activeSection + 1);
        } else {
            syncLaneButtons();
            app.updatePieceButton();
            app.syncPlacementSpinners();
            refreshOffset();
            redraw();
        }
    }

    /** Reverse the ACTIVE piece (REWORKED v17.0, D134 — reversal IS
     *  a convert, user re-spec): the whole flow runs through the
     *  standard density preview driving Coaster.reversePiece — the
     *  compiled geometry re-fits as forward sections running the
     *  other way, entry speed = the recorded end speed (the popup's
     *  padlocked row; unlock to type a custom one), NO speed
     *  conflict in the result. Apply = one undo step; Cancel
     *  restores exactly. */
    /** MAKE SMOOTHING REAL through the density preview (v19.3, D232 —
     *  the reversal pattern: pvOp re-applies per density against the
     *  pristine restore; Apply = one undo step, Cancel exact). The
     *  editor lands on the new window section (the upstream piece's
     *  last). */
    void makeSmoothingRealPreview(int followerIdx) {
        cancelDensityPreview();          // one preview at a time
        int fp = followerIdx;
        Section sPrev = app.coaster.pieces.get(fp - 1).sections
            .get(app.coaster.pieces.get(fp - 1).sections.size() - 1);
        pvDist = sPrev.domain() == Section.Domain.DISTANCE;
        pvRevSamples = null;             // no reversal guides here
        pvRef = null;
        pvBefore = snapshot();
        pvOp = (sp, tol) -> {
            String why = app.coaster.makeSmoothingReal(fp,
                FvdDesigner.INTEGRATE_DT, sp, tol);
            if (why != null) app.statsLabel.setText(
                "Make smoothing real refused — " + why);
            return why == null;
        };
        pvActivate = () -> {
            activePiece = fp - 1;
            activeSection = Math.max(0,
                app.coaster.pieces.get(fp - 1).sections.size() - 1);
        };
        if (!pvRerun()) {
            clearPv(false);
            redraw();
            return;
        }
        viewT0 = 0;
        viewT1 = -1;                     // structure changed: re-fit
        openPvPopup();
    }

    void reverseActivePiece() {
        for (Section s : sections()) {
            if (s instanceof BridgeSection) {
                // a bridge's poses are world-anchored A→B; running the
                // piece backward through it has no defined meaning
                app.statsLabel.setText("Reversing a piece with a bridge"
                    + " isn't supported — delete or convert the bridge"
                    + " first.");
                return;
            }
            if (!s.speedControls().isEmpty()) {
                // the model refuses these (D160) — until v19.6 (D235)
                // the failure fell through to the density preview and
                // got MISATTRIBUTED as "too short to re-fit"
                app.statsLabel.setText("Reverse refused — a lift/brake"
                    + " on the mirrored flow is ill-defined; remove the"
                    + " speed controls first.");
                return;
            }
        }
        TrackCompiler.Span lastSp = spanOf(sections().size() - 1);
        if (lastSp == null || app.lastSamples == null
                || app.trueV == null || app.lastSamples.isEmpty()) {
            app.statsLabel.setText("Nothing compiled to reverse yet.");
            return;
        }
        int e = Math.min(lastSp.i1(),
            Math.min(app.lastSamples.size(), app.trueV.length) - 1);
        int piece = activePiece;
        cancelDensityPreview();          // one preview at a time
        pvDist = false;
        pvRevBase = FvdDesigner.clamp(app.trueV[e], 1, 100);
        pvRevCustom = Double.NaN;
        stashReversedGuides();
        pvBefore = snapshot();
        pvOp = (sp, tol) -> app.coaster.reversePiece(piece,
            FvdDesigner.INTEGRATE_DT, sp, tol, pvRevCustom);
        pvActivate = () -> {
            activePiece = piece;
            activeSection = 0;
        };
        if (!pvRerun()) {
            clearPv(false);
            app.statsLabel.setText(
                "Reverse refused — the piece is too short to re-fit.");
            redraw();
            return;
        }
        viewT0 = 0;
        viewT1 = -1;                     // structure changed: re-fit
        openPvPopup();
    }

    /** Stash the PRISTINE inputs the reversal guides need (the live
     *  arrays show the applied result once the preview runs): the
     *  mirrored samples, the design rates in mirror order, the
     *  piece's global start time and the first new section's extent. */
    private void stashReversedGuides() {
        pvRevSamples = null;
        pvRef = null;
        List<Section> secs = sections();
        TrackCompiler.Span firstSp = spanOf(0);
        TrackCompiler.Span lastSp = spanOf(secs.size() - 1);
        if (firstSp == null || lastSp == null
                || app.resultRates == null) return;
        int i0 = firstSp.i0();
        int i1 = Math.min(lastSp.i1(), app.lastSamples.size() - 1);
        if (i1 - i0 < 8 || i1 >= app.resultRates.length) return;
        pvRevSamples = Coaster.reversedSamples(app.lastSamples, i0, i1);
        pvRevRates = new double[i1 - i0 + 1][];
        for (int q = 0; q <= i1 - i0; q++) {
            pvRevRates[q] = app.resultRates[i1 - q];
        }
        pvRevT0 = firstSp.t0();
        int aBound = Math.max(i0, lastSp.i0() - 1);
        pvRevQMax = i1 - aBound;
        // the original LAST section's mode = the new FIRST section's
        // (s/c re-authors as force lanes)
        pvRevGeo = secs.get(secs.size() - 1) instanceof GeoSection;
        rebuildReversedGuides();
    }

    /** The reversed ride's curves for the active (first) new section,
     *  from the stashed mirror + the CURRENT entry speed — the mirror
     *  identities (κ_U kept, κ_R flipped, roll traversal-invariant,
     *  rates ∝ the new profile speed; felt via the §5.1 scaling). */
    private void rebuildReversedGuides() {
        pvRef = null;
        if (pvRevSamples == null || pvRevQMax < 4) return;
        double vE = Double.isNaN(pvRevCustom) ? pvRevBase : pvRevCustom;
        double[] prof = Coaster.reversedProfile(pvRevSamples, vE,
            app.coaster.fric());
        double[] tq = new double[pvRevQMax + 1];
        for (int q = 1; q <= pvRevQMax; q++) {
            double ds = pvRevSamples.get(q).s()
                - pvRevSamples.get(q - 1).s();
            tq[q] = tq[q - 1]
                + ds / Math.max(0.5, (prof[q] + prof[q - 1]) / 2);
        }
        // full density (v19.11, D240 — the captureReference rule);
        // re-runs only on entry-speed edits, ColPath bounds the draw
        int n = pvRevQMax;
        double[][] ref = new double[n + 1][4];
        for (int i = 0; i <= n; i++) {
            int q = i;
            FVDIntegrator.Sample sm = pvRevSamples.get(q);
            double ratio = prof[q] / Math.max(0.1, sm.v());
            ref[i][0] = pvRevT0 + tq[q];
            if (pvRevGeo) {
                // the mirror already lives in the stashed sample list
                // (Gl negated); rates come from the ORIGINAL arrays,
                // so yaw flips here, pitch/roll carry (D67)
                ref[i][1] = pvRevRates[q][1] * ratio;
                ref[i][2] = -pvRevRates[q][2] * ratio;
                ref[i][3] = pvRevRates[q][3] * ratio;
            } else {
                double r2 = ratio * ratio;
                ref[i][1] = r2 * (sm.Gv() - sm.U().y()) + sm.U().y();
                ref[i][2] = r2 * (sm.Gl() + sm.R().y()) - sm.R().y();   // D184
                ref[i][3] = pvRevRates[q][3] * ratio;
            }
        }
        pvRef = ref;
    }

    // (modeName is GONE since v19.19, D248 — sectionTitle names
    // sections everywhere.)

    /** Badge press anywhere in the run: a badge on ANOTHER piece's
     *  strip activates that section first (its menu actions — split,
     *  delete, bridge tools — operate on the active piece), then the
     *  ordinary per-section menu opens (v13.4). */
    private void showRunBadgeMenu(int runIdx, double screenX, double screenY) {
        List<RunSec> rl = runLayout();
        if (runIdx < 0 || runIdx >= rl.size()) return;
        RunSec rs = rl.get(runIdx);
        if (rs.piece() != activePiece || rs.section() != activeSection) {
            // the FIRST click on an inactive badge only ACTIVATES the
            // section (v16.4, user rule: section settings open only on
            // a highlighted section) — click again for the menu
            activateRunSec(rs.piece(), rs.section());
            return;
        }
        showSectionMenu(rs.section(), screenX, screenY);
    }

    /** '+' press: the menu appends to the RUN's end — activate its
     *  last piece first when the editor is parked upstream (v13.4). */
    private void plusPressed(double screenX, double screenY) {
        int re = runEnd();
        if (re != activePiece) {
            activateRunSec(re,
                app.coaster.pieces.get(re).sections.size() - 1);
        }
        showAddSectionMenu(screenX, screenY);
    }

    /** The section BADGE menu (the strip's type tag doubles as its
     *  per-section actions handle — reverse + delete, plus append
     *  element on a straight/curve section). */
    private void showSectionMenu(int k, double screenX, double screenY) {
        Section sec = sections().get(k);
        ContextMenu cm = new ContextMenu();
        boolean drifted = sectionDrifted(activePiece, k);
        boolean orientC = sectionOrientConflict(activePiece, k);
        // TITLE ROW (v19.19, D248 — user spec): the PURE run-wide
        // section title (no tags — the badge above wears them),
        // BOLD, never highlighted or focused-looking, and clicks on
        // it never close the menu (labelItem, inert). NO tooltip
        // since v19.20 (D249 — the D248 numbers tooltip removed on
        // user hand test; the speeds live in "Initial design speed…"
        // and the orientation angles in the placement windows).
        Label title = new Label(sectionTitle(activePiece, k));
        title.setStyle("-fx-font-weight: bold;"
            + " -fx-text-fill: -fx-text-base-color;");
        CustomMenuItem info = labelItem(title, null, true);
        // the per-section Reversed check moved to piece scope in v12.1
        // (Coaster menu > Reverse piece, D71); the ◀ badge still marks
        // sections whose flag is set — that is reversal's MECHANISM
        // the COASTER keeps >= 1 section (its ACTIVE piece's list ×
        // its piece count — multi-coaster docs can hold plenty more,
        // D230); a piece's last section deletes the piece itself
        // when others remain (v12.0)
        boolean delBlocked = sections().size() <= 1
            && app.coaster.pieces.size() <= 1;
        MenuItem del;
        if (delBlocked) {
            del = grayItem("Delete section",
                "This coaster's only section cannot be deleted.");
        } else {
            del = new MenuItem("Delete section");
            del.setOnAction(ev -> deleteSection(k));
        }
        // ("Split piece before this section" and "Connect with
        // previous section" left the badge menu in D136 — user spec:
        // they live on the SEAM (click the boundary line) and on the
        // section's first node's right-click menu now.)
        cm.getItems().add(info);
        // (the D247 ORIENTATION CONFLICT info line — design vs real
        // angles — rode the title row's tooltip in v19.19, D248, and
        // is GONE with it since v19.20, D249: the badge's ≠o glyph +
        // the placement windows' readouts carry the angles)
        // SECTION SETTINGS (v16.0, D103 — user spec: the type/domain
        // options live with the section, where "force"/"geo" do): the
        // authoring DOMAIN — the radios CONVERT the lanes through the
        // exact node map, shape + speeds untouched — and the
        // cross-domain VIEW toggle (edit the native x-axis while the
        // plot shows the other domain's, warped). s/c is inherently
        // DISTANCE (radios read-only); bridges have neither. The
        // global/local axis options land here when they arrive.
        if (sec instanceof BridgeSection bsp) {
            // the BRIDGE WINDOW from the badge too (v17.14, D155,
            // user request): the same window the toolbar's junction
            // button opens — "Bridge settings…" since v19.20 (D249,
            // user spec; "Strict Seam n (bridge)…" in v19.19/D248,
            // "Bridge properties…" before that): mode radios, length,
            // next-piece rows. Badges are two-click, so the section
            // is active by the time this menu shows; an orphaned
            // bridge has nothing to place (grayed, the badge-menu
            // discoverability rule, D132).
            MenuItem props;
            String bTitle = SectionNames.BRIDGE_SETTINGS_ITEM;
            if (bridgeOrphan(bsp)) {
                props = grayItem(bTitle,
                    "This bridge has no following piece.");
            } else {
                props = new MenuItem(bTitle);
                final int bp = activePiece;
                props.setOnAction(ev -> app.openBridgeWindow(bp));
            }
            cm.getItems().add(props);
        }
        if (!(sec instanceof BridgeSection)) {
            boolean isSc = sec instanceof StraightCurveSection;
            boolean infl = sec instanceof LaneSection li0 && li0.influenced();
            boolean nd = sec.domain() == Section.Domain.DISTANCE;
            // conversion is an ACTION, not a state to toggle (v16.2,
            // user spec): one button for the only direction that
            // exists — s/c is inherently meters and shows none
            // (the separate "Convert to … domain…" item was subsumed
            // by the Convert… dialog's Domain row in v16.6, D116)
            CheckMenuItem viewOther = new CheckMenuItem("View in "
                + (nd ? "time" : "distance") + " domain");
            viewOther.setSelected(sec.viewOtherDomain());
            viewOther.setOnAction(ev ->
                setViewOtherDomain(k, !sec.viewOtherDomain()));
            cm.getItems().add(viewOther);
            // CROSS-RATE VIEW (v16.7, D119): display the rate lanes
            // in the other units, warped through the speed profile;
            // native lanes untouched, edits write back through it
            if (sec instanceof LaneSection lrv
                    && !(lrv.rollAbsolute && lrv.rollGlobal
                        && !(lrv instanceof GeoSection))
                    // a geo section with BOTH abs yp and abs roll has
                    // no rate lane left to view-warp (v19.8, D237)
                    && !(lrv instanceof GeoSection
                        && lrv.ypAbsolute && lrv.yawPitchGlobal
                        && lrv.rollAbsolute && lrv.rollGlobal)) {
                if (k != activeSection) {
                    cm.getItems().add(grayItem("View rates in "
                        + (lrv.ratePerMeter ? "°/s" : "°/m"),
                        "Activate the section first."));
                } else {
                    CheckMenuItem viewRate = new CheckMenuItem(
                        "View rates in " + (lrv.ratePerMeter
                            ? "°/s" : "°/m"));
                    viewRate.setSelected(lrv.viewOtherRate);
                    viewRate.setOnAction(ev -> {
                        lrv.viewOtherRate = !lrv.viewOtherRate;
                        refreshOffset();
                        redraw();
                    });
                    cm.getItems().add(viewRate);
                }
            }
            // ORIENTATION SETTINGS (v16.4, user spec): the axis
            // radios + bake actions condensed into ONE dialog.
            // While the strip EDITS INFLUENCE (v16.5, D112 — user
            // spec) the same entry relabels and configures the
            // INFLUENCE integration's axes instead — and then sits
            // DIRECTLY below "Edit influence" (v19.8, D237, user
            // spec) instead of up here.
            if (sec instanceof LaneSection lor
                    && !(lor.influenced() && lor.editInfluence)) {
                MenuItem orient = new MenuItem("Orientation settings…");
                orient.setOnAction(ev -> showOrientationDialog(k));
                cm.getItems().add(orient);
            }
        }
        // RESOLVE SPEED CONFLICT (v14.5 D97; reworked v15.2 D101;
        // simplified v15.4 D102 — user: the explicit transition-to-
        // real RESOLVE op is gone, its job is the two-step
        // "Re-freeze, then B/Shift+B": re-freeze works even directly
        // right of another conflicted section, and B against a
        // drifted neighbor matches the TRUE curves once the speed is
        // re-anchored. The '+' menu's "Transition to real ▸" fork for
        // ADDING sections stays — user likes it there.) MAKE GRAPHS
        // REAL keeps the shape and re-fits; it still waits for the
        // previous section's conflict (its fit reads true curves that
        // resolution would move). Only a bridge DIRECTLY before the
        // section blocks everything (it re-blends to chase any
        // resolution — convert it first).
        // INITIAL DESIGN SPEED (v18.0, D162 — user spec): type the
        // section's starting design speed directly; the ride entry
        // routes to the Start-speed spinner, elsewhere it PINS (a
        // deliberate conflict the reconcile leaves alone). ABOVE the
        // resolve block and "Initial design speed…" since v19.19
        // (D248, item S2 — user spec; was "Set initial design
        // speed…" below it).
        if (!(sec instanceof BridgeSection)) {
            MenuItem initV = new MenuItem("Initial design speed…");
            initV.setOnAction(ev -> showInitSpeedDialog(k));
            cm.getItems().add(initV);
        }
        // TWO CONFLICT KINDS since v19.18 (D247): speedC = the
        // entry-only ≠v, orientC = the piece-wide ≠o (design vs real
        // orientation). Either opens the submenu; both ops resolve
        // BOTH kinds (makeGraphsReal / refreezeSpeed bake the
        // piece's tilt / follow before the speed work), and the
        // submenu TITLE says what this click will resolve (the D235
        // no-silent-click rule). The D141 smoothing
        // hint replaces the submenu only for a PURE speed conflict —
        // smoothing never creates an orientation conflict (the seam
        // tilt is stamped before the smoothing pass), so with ≠o up
        // the ops must stay reachable.
        boolean speedC = drifted;
        if (speedC && !orientC && !(sec instanceof BridgeSection)
                && !sec.reversed()
                && k == 0 && followsPrev(activePiece)
                && smoothingCausedDrift(activePiece)) {
            // the conflict exists ONLY because of the seam smoothing
            // (v17.4, D141): the fix is the window, not a resolve op
            // (reworded v19.20, D249 — names the ◇ control)
            MenuItem why = new MenuItem("Speed conflict caused by seam "
                + "smoothing — shrink the ◇ window instead");
            why.setDisable(true);
            cm.getItems().add(why);
        } else if ((speedC || orientC) && !(sec instanceof BridgeSection)
                && !sec.reversed()) {
            // the submenu TITLE names what the ops resolve (v19.19,
            // D248 — user spec; the D247 disabled "Resolves: …" first
            // line is gone; an orientation resolve's RANGE scope is
            // stated by the end-section dialog + Help; D248's
            // "Resolve SECTION conflict" reverted to "Resolve
            // conflict" in v19.20, D249, on user hand test)
            Menu resolve = new Menu("Resolve conflict: "
                + (speedC && orientC ? "speed + orientation"
                    : orientC ? "orientation" : "speed"));
            BridgeSection binto = app.bridgeInto(activePiece);
            boolean machineOrient = orientC && binto != null
                && binto.optMode == BridgeSection.OPT_POS_YAW;
            // labels re-specced v19.5 (D234, user): the fit op
            // CONVERTS the real curves into the design ("Make
            // graphs real" until then), the re-freeze SETS the
            // design speed to the real one
            String realLbl = "Convert Real to Design (keep track shape)…";
            String refrLbl = "Set Design to Real (keep graphs, reshape)";
            if (leftIsBridge(k)) {
                // the two ops, grayed with the bridge sentence as
                // their hover reason (D248 — was a lone disabled
                // explainer line; the D235 gray-with-reason rule)
                String bridgeWhy = "The bridge before this section "
                    + "re-blends to follow any resolution — convert the "
                    + "bridge first.";
                resolve.getItems().addAll(grayItem(realLbl, bridgeWhy),
                    grayItem(refrLbl, bridgeWhy));
            } else if (machineOrient) {
                // an optimize-ORIENTATION bridge writes this piece's
                // true tier every compile (the march's alignment
                // convention) — a resolve would bake a tilt the next
                // auto re-optimize re-creates; the Track Piece
                // panel's sentence (D247)
                String own = "The optimize-orientation bridge owns this "
                    + "piece's pitch/roll — switch the bridge mode or "
                    + "lock its axes to hand-edit the orientation.";
                resolve.getItems().addAll(grayItem(realLbl, own),
                    grayItem(refrLbl, own));
            } else {
                // make-real works right of another conflict too since
                // v16.2 (user rule) — it fits THIS section's true
                // curves as they are now; the left conflict stays its
                // own, downstream conflicts stay theirs (pins +
                // piece-firsts survive the reconcile), and downstream
                // track re-chains with only the fit-residual
                // reorientation. Only a direct bridge still blocks.
                // an ORIENTATION-conflicted piece takes the RANGE flow
                // (v19.18, D247 — see the range block above); a pure
                // speed conflict keeps today's single-section ops
                String rangeWhy = orientC
                    ? rangeResolveWhy(activePiece, k) : null;
                if (rangeWhy != null) {
                    resolve.getItems().addAll(grayItem(realLbl, rangeWhy),
                        grayItem(refrLbl, rangeWhy));
                } else {
                    MenuItem real = new MenuItem(realLbl);
                    MenuItem refr = new MenuItem(refrLbl);
                    if (orientC) {
                        real.setOnAction(ev -> resolveRangeReal(k));
                        refr.setOnAction(ev -> resolveRangeRefreeze(k));
                    } else {
                        real.setOnAction(ev -> makeGraphsReal(k));
                        refr.setOnAction(ev -> refreezeSpeed(k));
                    }
                    resolve.getItems().addAll(real, refr);
                }
            }
            cm.getItems().add(resolve);
        }
        cm.getItems().addAll(new SeparatorMenuItem(), del);
        if (sec instanceof StraightCurveSection s) {
            MenuItem addEl = new MenuItem("Append element (10 m straight)");
            addEl.setOnAction(ev -> {
                pushUndoSnapshot();
                s.elements.add(StraightCurveSection.Element.straight(10, SC_SMOOTH_DEFAULT));
                if (k == activeSection) elSel = s.elements.size() - 1;
                app.rebuildTrack();
                refreshOffset();
                redraw();
            });
            cm.getItems().add(addEl);
        }
        if (sec instanceof BridgeSection bs && bridgeOrphan(bs)) {
            MenuItem status = new MenuItem(
                "! no following piece — frozen at its last shape");
            status.setDisable(true);
            cm.getItems().add(status);
        }
        // (the badge's explicit "Optimize placement of piece N" item
        // and the "Bridge tangents…" dialog were REMOVED in v13.13,
        // D87 — grandfathered from the pre-mode era: the mode radios
        // apply on switch, the Length/orientation controls re-run per
        // edit, auto re-optimize covers upstream changes, and an
        // optimized bridge's geometry is the march itself — there are
        // no user tangents left to sweep)
        // INFLUENCE (v15.0, D98; SECTIONS-DESIGN §5.4/5.5): sculpt the
        // section's RESULTING other-mode graphs with additive delta
        // lanes. The group offers entry, the edit-surface toggle, the
        // two views, bake (= convert to the OWN mode: the influence
        // effect is in the compiled shape) and discard. Reversed
        // sections refuse entry (§5.3's rule); a reversed piece
        // carrying influence keeps applying it, editing locked.
        if (sec instanceof LaneSection lsec) {
            cm.getItems().add(new SeparatorMenuItem());
            boolean geoSec = lsec instanceof GeoSection;
            if (!lsec.influenced()) {
                // distance sections take influence since v16.1 (the
                // two-pass runs over arc); GLOBAL-AXIS sections since
                // v16.5 (D112 — the base converts representations)
                boolean absRoll = (lsec.rollAbsolute && lsec.rollGlobal)
                    || (lsec.ypAbsolute && lsec.yawPitchGlobal);
                // SPEED CONTROLS no longer block entry (v19.6, D235 —
                // one Ctl drives the whole solve, D160's reason gone)
                // just "Influence force/geo graphs" (v19.15, D244,
                // user spec — the resulting/felt qualifiers gone)
                String enterLbl = geoSec
                    ? "Influence force graphs"
                    : "Influence geo graphs";
                MenuItem enter;
                if (absRoll) {
                    enter = grayItem(enterLbl,
                        "Absolute-angle sections take no influence —"
                        + " rate sections only.");
                } else if (lsec.reversed) {
                    enter = grayItem(enterLbl,
                        "Legacy reversed sections take no influence.");
                } else {
                    enter = new MenuItem(enterLbl);
                    enter.setOnAction(ev -> enterInfluenceUi(k));
                }
                cm.getItems().add(enter);
            } else {
                String revWhy = "Legacy reversed sections lock their"
                    + " influence editing.";
                String editLbl = geoSec
                    ? "Edit influence (felt force deltas)"
                    : "Edit influence (geo rate deltas)";
                MenuItem editInf;
                if (lsec.reversed) {
                    editInf = grayItem(editLbl, revWhy);
                } else {
                    CheckMenuItem ei = new CheckMenuItem(editLbl);
                    ei.setSelected(lsec.editInfluence);
                    ei.setOnAction(ev ->
                        setEditInfluence(k, !lsec.editInfluence));
                    editInf = ei;
                }
                // (the Influence/Resulting VIEW radios were REMOVED in
                // v17.17, D158 — user verdict: the resulting view's
                // edge cases (opposite orientations, inverted
                // stretches) read buggy/unintuitive; ONE view now —
                // deltas edit around zero with the resulting curves
                // dotted behind)
                MenuItem bake;
                if (lsec.reversed) {
                    bake = grayItem("Bake influence into design graphs…",
                        revWhy);
                } else {
                    bake = new MenuItem(
                        "Bake influence into design graphs…");
                    // the bake keeps the section's OWN domain and axis
                    // flags (v19.6, D235 — the 2-arg overload reset
                    // them to TIME/local, silently re-domaining)
                    bake.setOnAction(ev -> convertSection(k, geoSec,
                        lsec.domain(), lsec.rollGlobal,
                        lsec.yawPitchGlobal, lsec.ratePerMeter));
                }
                MenuItem disc = new MenuItem("Discard influence");
                disc.setOnAction(ev -> discardInfluenceUi(k));
                if (lsec.editInfluence) {
                    // the influence surface's axis dialog rides right
                    // below "Edit influence" (v19.8, D237, user spec)
                    MenuItem iOrient = new MenuItem(
                        "Influence orientation settings…");
                    iOrient.setOnAction(ev -> showOrientationDialog(k));
                    cm.getItems().addAll(editInf, iOrient, bake, disc);
                } else {
                    cm.getItems().addAll(editInf, bake, disc);
                }
            }
        }
        // CONVERT (v13.7 D81; whole + partial for EVERY convertible
        // mode since v14.0, D89): re-author the shape — or PART of it
        // ("Convert part …" opens the split-line mode) — as editable
        // lanes in the other mode(s); everything commits through the
        // density preview (D90). A whole-bridge convert ATTACHES the
        // next piece: the track stays connected (the v13.7 whole
        // convert split the run — user rule). Reversed sections
        // refuse: their lanes author the opposite direction (§5.3).
        // Influenced sections refuse the PARTIAL cut (v15.0: the kept
        // side's ref cannot reproduce the original's sub-arc — bake
        // or discard the influence first); whole convert bakes it.
        cm.getItems().add(new SeparatorMenuItem());
        // ONE "Convert…" entry since v16.6 (D116, user spec): the
        // dialog holds every output setting — whole/part, type,
        // domain, axes, rate units — and every combination converts
        // to every other in one engine call
        MenuItem convOne;
        if (sec.reversed()) {
            convOne = grayItem("Convert…",
                "Legacy reversed sections cannot convert — their"
                + " lanes author the opposite direction.");
        } else {
            convOne = new MenuItem("Convert…");
            convOne.setOnAction(ev -> showConvertDialog(k));
        }
        cm.getItems().add(convOne);
        showMenu(cm, screenX, screenY);
    }

    /** ENTER influence on section k (v15.0, D98): freeze the authored
     *  pair, reconcile the operating speed to the true entry, start
     *  with flat-zero deltas — the fast path keeps the compile
     *  bitwise until a delta is sculpted. One undo step. */
    private void enterInfluenceUi(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)
                || ls.influenced() || ls.reversed
                || (ls.rollAbsolute && ls.rollGlobal)
                || (ls.ypAbsolute && ls.yawPitchGlobal)) return;
        pushUndoSnapshot();
        if (k != activeSection) setActiveSection(k);
        // speeds untouched (v15.4, D102); the delta lanes span the
        // section's NATIVE axis — a node at 0 and at the end (the
        // compiled duration for TIME sections, the authored length
        // for DISTANCE ones, v16.1)
        Coaster.enterInfluence(ls,
            ls.domain() == Section.Domain.DISTANCE
                ? BezierBaker.rideEnd(ls.lanes)
                : durOf(activePiece, k));
        if (activeLane == 2) setActiveLane(0);   // roll has no deltas
        app.lanes = lanesFor(ls);
        selIdx = -1;
        selSet.clear();
        syncLaneButtons();
        app.rebuildTrack();      // base curves + spans
        refreshOffset();
        redraw();
    }

    /** Toggle which surface section k's strip edits (design lanes vs
     *  influence deltas) — both stay live, the influence keeps
     *  applying either way (user spec: "able to go back and edit
     *  original design mode"). */
    private void setEditInfluence(int k, boolean on) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)
                || !ls.influenced() || ls.reversed) return;
        if (k != activeSection) setActiveSection(k);
        if (on && activeLane == 2) setActiveLane(0);   // no roll deltas
        ls.editInfluence = on;
        app.lanes = lanesFor(ls);
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        syncLaneButtons();
        refreshOffset();
        redraw();
    }

    /** DISCARD influence on section k: deltas + operating speed drop,
     *  the authored pair governs — bitwise the un-influenced document
     *  (gated). One undo step. */
    private void discardInfluenceUi(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)
                || !ls.influenced()) return;
        pushUndoSnapshot();
        if (k != activeSection) setActiveSection(k);
        Coaster.discardInfluence(ls);
        app.lanes = lanesFor(ls);
        selIdx = -1;
        selSet.clear();
        syncLaneButtons();
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** CONVERT section k's authoring domain (v16.1, D104 — user
     *  re-spec: a same-mode carpet REFIT through the density preview,
     *  replacing v16.0's exact node map; the exact-shape role belongs
     *  to the cross-domain VIEW). Influenced sections convert their
     *  RESULTING curves (influence captured, not preserved); reversed
     *  refuse, the convert rule. A manual zoom survives: the window's
     *  ends line up in the new units. */
    private void switchDomainUi(int k, Section.Domain to) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)
                || ls.domain() == to || ls.reversed()) return;
        boolean wasDist = axisDist();
        double w0 = viewT0, w1 = viewT1;
        int piece = activePiece;
        boolean toGeo = ls instanceof GeoSection;   // same mode
        beginDensityPreview(k, toGeo, false,
            to == Section.Domain.DISTANCE,
            (sp, tol) -> app.coaster.convertDomain(piece, k,
                FvdDesigner.INTEGRATE_DT, sp, tol),
            () -> {
                activePiece = piece;
                activeSection = k;
            });
        // domain settings keep the zoom (v16.1, user rule): the
        // preview's auto-fit reset is undone with the old window's
        // ends mapped into the new axis units
        if (pvBefore != null && w1 > 0) {
            boolean nowDist = axisDist();
            viewT0 = nowDist == wasDist ? w0 : mapGlobalAxis(w0, wasDist);
            viewT1 = nowDist == wasDist ? w1 : mapGlobalAxis(w1, wasDist);
            if (viewT1 - viewT0 < 1e-9) {
                viewT0 = 0;
                viewT1 = -1;
            }
            lastAxisDist = nowDist;
            redraw();
        }
    }

    /** BAKE section k's shape into the other axis semantics (v16.2 —
     *  user spec): the convert engine re-fits the compiled curves as
     *  lanes carrying the target flags, through the density preview.
     *  Same mode, same domain; zoom kept like the domain convert. */
    private void bakeAxesUi(int k, boolean toRollG, boolean toYpG) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)
                || ls.reversed()) return;
        int piece = activePiece;
        boolean toGeo = ls instanceof GeoSection;
        beginDensityPreview(k, toGeo, false,
            ls.domain() == Section.Domain.DISTANCE,
            (sp, tol) -> app.coaster.bakeAxes(piece, k, toRollG, toYpG,
                FvdDesigner.INTEGRATE_DT, sp, tol),
            () -> {
                activePiece = piece;
                activeSection = k;
            });
    }

    /** Set section k's axis flags (v16.1, D107; influenced sections
     *  switch too since v16.5 — the two-pass ref honors the flags);
     *  null = leave alone. One undo step; no-ops skip the push. */
    private void setAxisFlags(int k, Boolean rollGlobal, Boolean ypGlobal) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)) return;
        boolean nr = rollGlobal != null ? rollGlobal : ls.rollGlobal;
        boolean ny = ypGlobal != null ? ypGlobal : ls.yawPitchGlobal;
        if (nr == ls.rollGlobal && ny == ls.yawPitchGlobal) return;
        pushUndoSnapshot();
        ls.rollGlobal = nr;
        ls.yawPitchGlobal = ny;
        // ABSOLUTE roll has no local meaning (v16.7, D118): switching
        // roll to local reverts the lane to a RATE reading
        if (!nr) ls.rollAbsolute = false;
        // same rule for absolute yaw/pitch (v19.8, D237): local
        // yaw/pitch reverts lanes 0/1 to RATE readings
        if (!ny) ls.ypAbsolute = false;
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** Set section k's INFLUENCE axis flags (v16.5, D112) — the
     *  semantics the two-pass integrates base + delta under; null =
     *  leave alone. One undo step; no-ops skip the push. */
    private void setInflAxisFlags(int k, Boolean rollGlobal, Boolean ypGlobal) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)
                || !ls.influenced()) return;
        boolean nr = rollGlobal != null ? rollGlobal : ls.inflRollGlobal;
        boolean ny = ypGlobal != null ? ypGlobal : ls.inflYawPitchGlobal;
        if (nr == ls.inflRollGlobal && ny == ls.inflYawPitchGlobal) return;
        pushUndoSnapshot();
        ls.inflRollGlobal = nr;
        ls.inflYawPitchGlobal = ny;
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** The ORIENTATION SETTINGS dialog (v16.4, user spec — the axis
     *  radios + bake actions condensed to one window): pick the
     *  target semantics, then SWITCH (reinterpret the lanes — the
     *  shape changes) or BAKE (the convert engine keeps the track
     *  and re-fits the lanes). Influenced sections switch too since
     *  v16.5 (the two-pass honors the flags); reversed can switch
     *  but not bake (the fit rule). On the INFLUENCE-editing surface
     *  (v16.5, D112 — user spec) the dialog configures the INFLUENCE
     *  integration's axes instead: a force section's geo deltas pick
     *  roll + yaw/pitch, a geo section's force deltas pick roll (the
     *  force integrator's only axis choice) — switch only, no bake
     *  (the delta lanes are yours; "Bake influence into design
     *  graphs…" is the exit that converts them). */
    private void showOrientationDialog(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()
                || !(secs.get(k) instanceof LaneSection ls)) return;
        boolean geo = ls instanceof GeoSection;
        boolean infl = ls.influenced();
        boolean inflSurf = infl && ls.editInfluence;
        // which semantics the radios describe: the section's own, or
        // the influence integration's (= the OTHER mode's)
        boolean ypRow = inflSurf ? !geo : geo;
        boolean rollNow = inflSurf ? ls.inflRollGlobal : ls.rollGlobal;
        boolean ypNow = inflSurf ? ls.inflYawPitchGlobal
                                 : ls.yawPitchGlobal;

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle((inflSurf ? "Influence orientation settings — "
                               : "Orientation settings — ")
            + sectionTitle(activePiece, k));   // run-wide title (D248)
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;

        ToggleGroup rg = new ToggleGroup();
        RadioButton rL = new RadioButton("Local (about the track)");
        RadioButton rG = new RadioButton("Global (bank vs horizon)");
        rL.setToggleGroup(rg);
        rG.setToggleGroup(rg);
        (rollNow ? rG : rL).setSelected(true);
        wireOriginal(rg, rollNow ? rG : rL, null);
        grid.addRow(row++, new Label("Roll"), new HBox(10, rL, rG));

        RadioButton yL = new RadioButton("Local (track axes)");
        RadioButton yG = new RadioButton("Global (heading/elevation)");
        if (ypRow) {
            ToggleGroup yg = new ToggleGroup();
            yL.setToggleGroup(yg);
            yG.setToggleGroup(yg);
            (ypNow ? yG : yL).setSelected(true);
            wireOriginal(yg, ypNow ? yG : yL, null);
            grid.addRow(row++, new Label("Yaw/pitch"), new HBox(10, yL, yG));
        }

        // (the gray explainer that used to sit here was REMOVED in
        // v16.6 — user: redundant with the buttons' parentheticals)
        Button apply = new Button();
        Button bake = new Button();
        Button close = new Button("Close");
        close.setCancelButton(true);
        // fixed labels (v16.4 final, user wording); the explanations
        // sit UNDER each button in gray since v16.7 (user spec — they
        // were parentheticals in the button text)
        apply.setText("Switch to This Orientation");
        bake.setText("Bake This Orientation");
        Label applyCap = grayCaption("same graphs, change shape");
        Label bakeCap = grayCaption("convert graphs, keep shape");
        Runnable sync = () -> {
            boolean nr = rG.isSelected();
            boolean ny = ypRow && yG.isSelected();
            boolean changed = nr != rollNow || ny != ypNow;
            apply.setDisable(!changed);
            bake.setDisable(!changed || ls.reversed);
        };
        rL.setOnAction(e2 -> sync.run());
        rG.setOnAction(e2 -> sync.run());
        yL.setOnAction(e2 -> sync.run());
        yG.setOnAction(e2 -> sync.run());
        apply.setOnAction(e2 -> {
            dlg.close();
            if (inflSurf) {
                setInflAxisFlags(k, rG.isSelected(),
                    ypRow ? yG.isSelected() : null);
            } else {
                setAxisFlags(k, rG.isSelected(),
                    geo ? yG.isSelected() : null);
            }
        });
        bake.setOnAction(e2 -> {
            dlg.close();
            bakeAxesUi(k, rG.isSelected(),
                geo ? yG.isSelected() : ls.yawPitchGlobal);
        });
        close.setOnAction(e2 -> dlg.close());
        // grayed = explained on hover (the D235 rule)
        VBox applyBox = new VBox(2,
            grayHint(apply, "Pick a different orientation first."),
            applyCap);
        applyBox.setAlignment(Pos.CENTER);
        VBox bakeBox = new VBox(2,
            grayHint(bake, ls.reversed
                ? "Legacy reversed sections refuse baking."
                : "Pick a different orientation first."),
            bakeCap);
        bakeBox.setAlignment(Pos.CENTER);
        HBox buttons = inflSurf ? new HBox(8, applyBox, close)
                                : new HBox(8, applyBox, bakeBox, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);
        // OWNER BUTTON (v19.19, D248 — user spec): the window that
        // owns this section's PLACEMENT, labeled EXACTLY like its
        // toolbar button (ownerButtonLabel — "Track Piece ▴" /
        // "Strict Seam n ▴" / "Strict Seam n (bridge) ▴"). It CLOSES
        // this dialog first: the bridge route activates the bridge
        // section, and this dialog's k is active-piece-relative.
        // Left-aligned under the action row so it reads as a link.
        final int ownerPiece = activePiece;
        Button owner = new Button(ownerButtonLabel(ownerPiece, k));
        owner.setFocusTraversable(false);
        Tooltip.install(owner, new Tooltip(
            "Opens the window that owns this section's placement."));
        // the wizard's Placement rows own a piece while it shows
        // (D236) — grayed with the toolbar buttons' reason
        owner.setDisable(startPanelActive());
        owner.setOnAction(e2 -> {
            dlg.close();
            openOwnerWindow(ownerPiece, k);
        });
        HBox ownerRow = new HBox(grayHint(owner,
            "Placement is edited in the start screen while it is open."));
        ownerRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(ownerRow, 0, row + 1, 2, 1);
        sync.run();
        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installDocUndoKeys(dlg.getScene());   // D227: global undo + ride keys
    }

    /** Open the window that owns piece p's placement (v19.19, D248):
     *  a run head → the Track Piece window; a strict follower → the
     *  seam window; a bridged-in piece → the BRIDGE panel of the
     *  previous piece's trailing bridge, activated first (the bridge
     *  rows edit the ACTIVE bridge) — refused with CONVERT_BUSY_MSG
     *  while a convert preview owns the document, since an activation
     *  change would cancel it. Each target re-checks the wizard
     *  itself (openTrackPieceWindow's stats sentence). The Track Piece
     *  window shows the ACTIVE piece, so the run-head route activates
     *  piece p (section k) first when the modeless dialog outlived
     *  an activation change — the same convertBusy guard — and every
     *  route opens its window FRESH (openPlacementPopup /
     *  openBridgeWindow / showStrictSeamSettings: the toolbar
     *  buttons' toggles would have CLOSED an open window — a click
     *  doing the opposite of its label; D248 review fix). */
    void openOwnerWindow(int p) {
        openOwnerWindow(p, 0);
    }

    void openOwnerWindow(int p, int k) {
        if (p < 0 || p >= app.coaster.pieces.size()) {
            app.statsLabel.setText("The piece is gone.");
            return;
        }
        if (junctionOrdinal(p) == 0) {
            if (p != activePiece) {
                if (app.convertBusy()) return;   // posts CONVERT_BUSY_MSG
                activateRunSec(p, (int) FvdDesigner.clamp(k, 0,
                    app.coaster.pieces.get(p).sections.size() - 1));
            }
            app.openPlacementPopup(false);
        } else if (app.coaster.bridgedInto(p)) {
            if (app.convertBusy()) return;   // posts CONVERT_BUSY_MSG
            int bp = p - 1;
            activateRunSec(bp, app.coaster.pieces.get(bp).sections.size() - 1);
            app.openBridgeWindow(bp);   // "Bridge settings (Strict Seam n)" (D249)
        } else {
            showStrictSeamSettings(p);
        }
    }

    /** Small gray caption under a dialog button (v16.7). */
    private static Label grayCaption(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: rgb(130,135,150); -fx-font-size: 10px;");
        return l;
    }

    /** Toggle section k's cross-domain VIEW (v16.0): purely visual —
     *  the native lanes/elements stay put, the plot's axis flips and
     *  the strip warps onto it. Session state (DocState, not saved),
     *  no undo push — the editInfluence precedent. */
    private void setViewOtherDomain(int k, boolean on) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        Section sec = secs.get(k);
        if (sec instanceof BridgeSection) return;
        sec.viewOtherDomain(on);
        // no zoom reset (v16.1): redraw's axis-flip mapper lines the
        // window ends up in the new units
        refreshOffset();
        redraw();
    }

    /** DESIGN → REAL preserve/convert dialog (v19.11, D240 — user
     *  spec, his "Design to real not working" file): the Track Piece
     *  panel's button routes here when the piece carries ABSOLUTE
     *  lanes, whose angles re-assert themselves whatever the entry
     *  frame — a plain design := true write snaps the track back to
     *  the design shape. Per absolute quantity the user picks
     *  Preserve (that snap, informed) or Convert (refit the lane
     *  from the current true curves — the track keeps its shape);
     *  any Convert pick commits through the density preview. */
    void showDesignToRealDialog(int pieceIdx, boolean anyYp,
                                boolean anyBank) {
        showDesignToRealDialog(pieceIdx, anyYp, anyBank, null);
    }

    /** + an `after` hook run at the dialog's APPLY, before either
     *  route commits (v19.19, D248 — the bridge window stamps its
     *  bridge SIG_STALE there so the target's Design → Real is
     *  followed by a deterministic re-march; a cancelled dialog
     *  stamps nothing). */
    void showDesignToRealDialog(int pieceIdx, boolean anyYp,
                                boolean anyBank, Runnable after) {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        // (the explainer paragraph was removed on user spec, D241 —
        // the title carries the context)
        dlg.setTitle("Design → Real Absolute-Angle Conversion");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;
        ToggleGroup yg = new ToggleGroup();
        RadioButton ypConv = new RadioButton("Convert to current shape");
        RadioButton ypKeep = new RadioButton("Preserve");
        ypConv.setToggleGroup(yg);
        ypKeep.setToggleGroup(yg);
        ypConv.setSelected(true);
        if (anyYp) {
            grid.addRow(row++, new Label("Elevation & heading"),
                new HBox(10, ypConv, ypKeep));
        }
        ToggleGroup bg = new ToggleGroup();
        RadioButton bkConv = new RadioButton("Convert to current shape");
        RadioButton bkKeep = new RadioButton("Preserve");
        bkConv.setToggleGroup(bg);
        bkKeep.setToggleGroup(bg);
        bkConv.setSelected(true);
        if (anyBank) {
            grid.addRow(row++, new Label("Bank"),
                new HBox(10, bkConv, bkKeep));
        }
        Button ok = new Button("Apply");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(ev -> dlg.close());
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);
        ok.setOnAction(ev -> {
            dlg.close();
            // index-resolved target (D247): the piece may be gone or
            // no longer conflicted after an undo under the dialog
            if (pieceIdx >= app.coaster.pieces.size()) {
                app.statsLabel.setText("Design → Real not applied — "
                    + "that piece no longer exists.");
                return;
            }
            if (!orientConflictAt(pieceIdx)) {
                app.statsLabel.setText("Design → Real not applied — "
                    + "the piece's design and real orientations "
                    + "already agree.");
                return;
            }
            boolean cy = anyYp && ypConv.isSelected();
            boolean cb = anyBank && bkConv.isSelected();
            if (!cy && !cb) {
                // D248: the caller's stamp lands RIGHT before the write
                // and AFTER its undo push (review fixes — stamping ahead
                // of setActivePiece's rebuild below let the auto pass
                // consume it before anything was written, and a stamp
                // ahead of the push rode into the snapshot's bridge
                // copy so Ctrl+Z re-marched)
                app.designToRealDirect(pieceIdx, after);   // all-Preserve =
                return;                                    // the classic snap
            }
            // anchor the preview on the piece's first convertible
            // absolute section (reference curves + restore follow it)
            setActivePiece(pieceIdx);
            List<Section> secs = sections();
            int kAbs = 0;
            for (int k2 = 0; k2 < secs.size(); k2++) {
                if (secs.get(k2) instanceof LaneSection ls
                        && !ls.reversed() && !ls.influenced()
                        && ((cy && ls instanceof GeoSection
                                && ls.ypAbsolute && ls.yawPitchGlobal)
                            || (cb && ls.rollAbsolute && ls.rollGlobal))) {
                    kAbs = k2;
                    break;
                }
            }
            setActiveSection(kAbs);
            Section sec = sections().get(kAbs);
            boolean toGeo = sec instanceof GeoSection;
            boolean dist = sec.domain() == Section.Domain.DISTANCE;
            final int fp = pieceIdx, fk = kAbs;
            // the caller's stamp runs INSIDE the preview op (D248 review
            // fix): pvRerun restores the pristine snapshot (adopting
            // NEW section objects) before every re-apply, so a stamp
            // made earlier would sit on a dead object — and the
            // snapshot precedes the op, so Cancel restores unstamped
            beginDensityPreview(fk, toGeo, true, dist,
                (sp2, tol) -> {
                    if (after != null) after.run();
                    return app.coaster.designToRealConvert(fp, cy, cb,
                        FvdDesigner.INTEGRATE_DT, sp2, tol);
                },
                () -> {
                    activePiece = fp;
                    activeSection = fk;
                });
        });
        dlg.setScene(new Scene(grid));
        dlg.show();
        // the D227 rule (D247 review fix): the dialog changes the track
        // and its index-resolved re-checks presume undo works under
        // it — global undo keys + ride keys (installDocUndoKeys
        // installs the view keys too)
        app.installDocUndoKeys(dlg.getScene());
    }

    /** Shared gray-out explainer (v16.10): why Absolute bank grays
     *  in the Convert and Add-section dialogs. */
    private static final String YP_ABS_HOW = "Absolute angles author "
        + "the elevation vs the horizon and the piece-relative heading "
        + "— set Type to Geometric and Yaw/pitch axis to Global.";

    private static final String ABS_HOW = "Absolute bank targets the "
        + "bank vs the horizon — set Roll axis to Global to enable it.";

    /** The unified CONVERT dialog (v16.6, D116 — user spec: all
     *  convert options in one Add-style window): Whole/Part scope,
     *  Type, Domain, Roll axis, Yaw/pitch axis (grayed for force),
     *  Rate units — each row pre-set to the section's CURRENT value
     *  where one exists, with a ↺ button putting that row back.
     *  Whole commits through the density preview; Part closes into
     *  the split-line mode carrying the chosen output settings.
     *  Influenced sections gray the Part scope (bake or discard
     *  first — the kept half's ref rule, D98). */
    private void showConvertDialog(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        Section sec = secs.get(k);
        if (sec.reversed()) return;
        LaneSection ls = sec instanceof LaneSection l ? l : null;
        boolean influenced = ls != null && ls.influenced();
        boolean curGeo = sec instanceof GeoSection;
        boolean curDist = sec.domain() == Section.Domain.DISTANCE;
        boolean curRollG = ls != null && ls.rollGlobal;
        boolean curYpG = ls != null && ls.yawPitchGlobal;
        boolean curRateM = ls != null && ls.ratePerMeter;

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Convert — " + sectionTitle(activePiece, k));   // D248
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;

        ToggleGroup sg = new ToggleGroup();
        RadioButton sWhole = new RadioButton("Whole");
        RadioButton sPart = new RadioButton("Part…");
        sWhole.setToggleGroup(sg);
        sPart.setToggleGroup(sg);
        sWhole.setSelected(true);
        sPart.setDisable(influenced);
        wireOriginal(sg, sWhole, null);
        grid.addRow(row++, new Label("Convert"),
            new HBox(10, sWhole, grayHint(sPart, "Part conversion "
                + "needs the plain design lanes — bake or discard this "
                + "section's influence first (badge menu).")));

        // (the per-row ↺ reset buttons are GONE — v19.9, D238, user
        // spec: the dialog matches the Add-section dialog's format;
        // the orange changed-row dot from wireOriginal remains)
        ToggleGroup mg = new ToggleGroup();
        RadioButton mForce = new RadioButton("Force");
        RadioButton mGeo = new RadioButton("Geometric");
        mForce.setToggleGroup(mg);
        mGeo.setToggleGroup(mg);
        (curGeo ? mGeo : mForce).setSelected(true);
        if (ls != null) {
            wireOriginal(mg, curGeo ? mGeo : mForce, null);
        }
        grid.addRow(row++, new Label("Type"),
            new HBox(10, mForce, mGeo));

        ToggleGroup dg = new ToggleGroup();
        RadioButton dTime = new RadioButton("Time");
        RadioButton dDist = new RadioButton("Distance");
        dTime.setToggleGroup(dg);
        dDist.setToggleGroup(dg);
        (curDist ? dDist : dTime).setSelected(true);
        wireOriginal(dg, curDist ? dDist : dTime, null);
        grid.addRow(row++, new Label("Domain"),
            new HBox(10, dTime, dDist));

        ToggleGroup ug = new ToggleGroup();
        RadioButton uSec = new RadioButton("Per second (°/s)");
        RadioButton uMet = new RadioButton("Per meter (°/m)");
        uSec.setToggleGroup(ug);
        uMet.setToggleGroup(ug);
        (curRateM ? uMet : uSec).setSelected(true);
        wireOriginal(ug, curRateM ? uMet : uSec, null);
        grid.addRow(row++, new Label("Rate units"),
            new HBox(10, uSec, uMet));

        ToggleGroup yg = new ToggleGroup();
        RadioButton yLocal = new RadioButton("Local");
        RadioButton yGlobal = new RadioButton("Global (heading/elevation)");
        yLocal.setToggleGroup(yg);
        yGlobal.setToggleGroup(yg);
        (curYpG ? yGlobal : yLocal).setSelected(true);
        wireOriginal(yg, curYpG ? yGlobal : yLocal, null);
        String ypHow = "Yaw/pitch axes exist on geometric sections — "
            + "set Type to Geometric to choose them.";
        grid.addRow(row++, new Label("Yaw/pitch axis"),
            new HBox(10, grayHint(yLocal, ypHow),
                grayHint(yGlobal, ypHow)));
        Runnable syncYp = () -> {
            boolean geoOn = mGeo.isSelected();
            yLocal.setDisable(!geoOn);
            yGlobal.setDisable(!geoOn);
            // D241: graying must not leave Global SELECTED — the dot
            // sat on a choice that no longer applied (the behavior
            // row's Abs→Rate precedent)
            if (!geoOn && yGlobal.isSelected()) yLocal.setSelected(true);
        };
        mg.selectedToggleProperty().addListener((o, w, n2) -> syncYp.run());
        yg.selectedToggleProperty().addListener((o, w, n2) -> syncYp.run());
        syncYp.run();

        // YAW/PITCH BEHAVIOR (v19.8, D237 — the D118 twin for lanes
        // 0/1, directly below the Yaw/pitch axis per user spec):
        // absolute needs Geometric type AND global yaw/pitch
        boolean curYpAbsF = ls != null && ls.ypAbsolute
            && ls.yawPitchGlobal;
        ToggleGroup yag = new ToggleGroup();
        RadioButton yaRate = new RadioButton("Rate");
        RadioButton yaAbs = new RadioButton("Absolute angles (°)");
        yaRate.setToggleGroup(yag);
        yaAbs.setToggleGroup(yag);
        (curYpAbsF ? yaAbs : yaRate).setSelected(true);
        wireOriginal(yag, curYpAbsF ? yaAbs : yaRate, null);
        // the WHOLE row grays under Force (v19.10, D239 — user spec:
        // yaw/pitch behavior doesn't apply to force sections, like
        // the axis row above); Absolute additionally needs Global
        String yaRateHow = "Yaw/pitch behavior exists on geometric "
            + "sections — set Type to Geometric to choose it.";
        grid.addRow(row++, new Label("Yaw/pitch behavior"),
            new HBox(10, grayHint(yaRate, yaRateHow),
                grayHint(yaAbs, YP_ABS_HOW)));
        Runnable syncYpAbs = () -> {
            boolean geoOn = mGeo.isSelected();
            boolean on = geoOn && yGlobal.isSelected();
            yaRate.setDisable(!geoOn);
            yaAbs.setDisable(!on);
            if (!on && yaAbs.isSelected()) yaRate.setSelected(true);
        };
        mg.selectedToggleProperty().addListener(
            (o, w, n2) -> syncYpAbs.run());
        yg.selectedToggleProperty().addListener(
            (o, w, n2) -> syncYpAbs.run());
        syncYpAbs.run();
        ToggleGroup rg = new ToggleGroup();
        RadioButton rLocal = new RadioButton("Local");
        RadioButton rGlobal = new RadioButton("Global (bank vs horizon)");
        rLocal.setToggleGroup(rg);
        rGlobal.setToggleGroup(rg);
        (curRollG ? rGlobal : rLocal).setSelected(true);
        wireOriginal(rg, curRollG ? rGlobal : rLocal, null);
        grid.addRow(row++, new Label("Roll axis"),
            new HBox(10, rLocal, rGlobal));

        // ROLL BEHAVIOR (v16.7, D118) — absolute needs GLOBAL roll
        boolean curAbs = ls != null && ls.rollAbsolute && ls.rollGlobal;
        ToggleGroup ag = new ToggleGroup();
        RadioButton aRate = new RadioButton("Roll rate");
        RadioButton aAbs = new RadioButton("Absolute bank (°)");
        aRate.setToggleGroup(ag);
        aAbs.setToggleGroup(ag);
        (curAbs ? aAbs : aRate).setSelected(true);
        wireOriginal(ag, curAbs ? aAbs : aRate, null);
        grid.addRow(row++, new Label("Roll behavior"),
            new HBox(10, aRate, grayHint(aAbs, ABS_HOW)));
        Runnable syncAbs = () -> {
            boolean glob = rGlobal.isSelected();
            aAbs.setDisable(!glob);
            if (!glob && aAbs.isSelected()) aRate.setSelected(true);
        };
        rg.selectedToggleProperty().addListener((o, w, n2) -> syncAbs.run());
        syncAbs.run();

        // STRICT SEAM on a whole-bridge convert (v17.1, D135 — user
        // spec, checked by default): keep the following track's shape
        // frozen behind a strict seam instead of stitching it into
        // this piece. Unchecked = the classic attach + pinned seam.
        javafx.scene.control.CheckBox strictCb = null;
        if (sec instanceof BridgeSection
                && activePiece + 1 < app.coaster.pieces.size()) {
            strictCb = new javafx.scene.control.CheckBox(
                "Make following seam strict (freeze following track shape)");
            strictCb.setSelected(true);
            Tooltip.install(strictCb, new Tooltip(
                "Checked: the next piece keeps its exact shape behind a\n"
                + "strict seam (nothing moves). Unchecked: it stitches\n"
                + "into this piece."));
            grid.add(strictCb, 0, row++, 2, 1);
        }
        final javafx.scene.control.CheckBox strictRef = strictCb;

        Button go = new Button("Convert");
        go.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, go, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);
        go.setOnAction(ev -> {
            dlg.close();
            boolean toGeo = mGeo.isSelected();
            Section.Domain dom = dDist.isSelected()
                ? Section.Domain.DISTANCE : Section.Domain.TIME;
            boolean rollG = rGlobal.isSelected();
            boolean ypG = toGeo && yGlobal.isSelected();
            boolean rateM = uMet.isSelected();
            boolean rollAbs = aAbs.isSelected() && rollG;
            boolean ypAbsSel = yaAbs.isSelected() && ypG;   // D237
            boolean strict = strictRef != null && strictRef.isSelected();
            // PER-LANE preserve (v19.9, D238 — generalizing the
            // v18.2 roll-only case): unchanged-meaning lanes keep
            // their authored nodes (Coaster.laneKeepMap) and the
            // preview opens ON the first lane that actually re-fits
            if (ls != null) {
                boolean[] keep = Coaster.laneKeepMap(ls, toGeo, dom,
                    rollG, ypG, rateM, rollAbs, ypAbsSel);
                for (int L = 0; keep != null && L < 3; L++) {
                    if (!keep[L]) {
                        setActiveLane(L);
                        break;
                    }
                }
            }
            if (sWhole.isSelected()) {
                convertSection(k, toGeo, dom, rollG, ypG, rateM, rollAbs,
                    strict, ypAbsSel);
            } else {
                beginPartialConvert(k, toGeo, dom, rollG, ypG, rateM,
                    rollAbs, ypAbsSel);
            }
        });
        cancel.setOnAction(ev -> dlg.close());
        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installViewKeys(dlg.getScene());   // ride keys live (v18.1)
    }

    // (resetBtn — the Convert dialog's per-row ↺ — was removed in
    // v19.9, D238: the settings ↺ resets in Snapping/Shortcuts/View/
    // colors/Export and the strict-seam pop-out are a different
    // contract — reset-to-a-SETTING — and stay.)

    /** Wire a radio row to its ORIGINAL selection (v16.7, user
     *  spec): the ↺ grays while the row sits at the original, and a
     *  selection that DIFFERS tints orange — on the radio DOT, not
     *  the label text (v16.9, user spec). reset may be null (rows
     *  without a ↺). */
    private static void wireOriginal(ToggleGroup g, Toggle original,
                                     Button reset) {
        Runnable sync = () -> {
            Toggle sel = g.getSelectedToggle();
            boolean atOrig = sel == original;
            if (reset != null) reset.setDisable(atOrig);
            for (Toggle t : g.getToggles()) {
                if (t instanceof RadioButton rb) {
                    // mark color ONLY — overriding the highlight
                    // layer too changed the dot's layered geometry
                    // vs the default black dot (v16.9, user report)
                    rb.setStyle(t == sel && !atOrig
                        ? "-fx-mark-color: rgb(235,150,60);" : "");
                }
            }
        };
        g.selectedToggleProperty().addListener((o, w, n2) -> sync.run());
        sync.run();
    }

    /** Wrap a dialog control that can GRAY under a rule with a hover
     *  explainer saying how to un-gray it (v16.10, user spec).
     *  Disabled JavaFX controls swallow mouse events — tooltips set
     *  on them never show — so the tip rides this enabled wrapper,
     *  installed only WHILE the control is disabled (enabled
     *  controls stay tooltip-free). Place the wrapper in the row
     *  where the control would go. (Package-visible since v16.13 —
     *  the Placement rows wear it too. LEFT-aligned since v19.9,
     *  D238: StackPane defaults to CENTER, and a fill-width VBox
     *  stretching the wrapper CENTERED its one child against its
     *  left-aligned siblings — the Resulting popup's drifted roll
     *  check, the user's recurring-misalignment report; HBox call
     *  sites size the wrapper to content, unaffected.) */
    static StackPane grayHint(Control c, String how) {
        StackPane wrap = new StackPane(c);
        wrap.setAlignment(Pos.CENTER_LEFT);
        Tooltip tip = new Tooltip(how);
        tip.setShowDelay(Duration.millis(300));
        Runnable sync = () -> {
            if (c.isDisabled()) Tooltip.install(wrap, tip);
            else Tooltip.uninstall(wrap, tip);
        };
        c.disabledProperty().addListener((o, was, dis) -> sync.run());
        sync.run();
        return wrap;
    }

    /** grayHint for a container ROW (v19.6, D235): the same disabled-
     *  hover behavior on any Node — an HBox row that grays as one
     *  unit carries one shared reason. */
    static StackPane grayHint(javafx.scene.Node n, String how) {
        StackPane wrap = new StackPane(n);
        wrap.setAlignment(Pos.CENTER_LEFT);   // the D238 rule above
        Tooltip tip = new Tooltip(how);
        tip.setShowDelay(Duration.millis(300));
        Runnable sync = () -> {
            if (n.isDisabled()) Tooltip.install(wrap, tip);
            else Tooltip.uninstall(wrap, tip);
        };
        n.disabledProperty().addListener((o, was, dis) -> sync.run());
        sync.run();
        return wrap;
    }

    /** grayHint for a container ROW with a LIVE reason (v19.19, D248 —
     *  the Track Piece window's flag row grays with the follower's
     *  junction NAME, which renumbers under edits). */
    static StackPane grayHint(javafx.scene.Node n,
                              java.util.function.Supplier<String> how) {
        StackPane wrap = new StackPane(n);
        wrap.setAlignment(Pos.CENTER_LEFT);
        Tooltip tip = new Tooltip();
        tip.setShowDelay(Duration.millis(300));
        tip.setOnShowing(ev -> tip.setText(how.get()));
        Runnable sync = () -> {
            if (n.isDisabled()) {
                tip.setText(how.get());
                Tooltip.install(wrap, tip);
            } else {
                Tooltip.uninstall(wrap, tip);
            }
        };
        n.disabledProperty().addListener((o, was, dis) -> sync.run());
        sync.run();
        return wrap;
    }

    /** grayHint with a LIVE reason (v19.6, D235 — one control, more
     *  than one possible cause): the tooltip text re-resolves every
     *  time the control's disabled state flips on — AND at every
     *  show since v19.18 (D247: the Track Piece panel's rows stay
     *  disabled across a bridged-in → follower activation switch, so
     *  the flip-time resolve alone left the previous reason). */
    static StackPane grayHint(Control c,
                              java.util.function.Supplier<String> how) {
        StackPane wrap = new StackPane(c);
        wrap.setAlignment(Pos.CENTER_LEFT);   // the D238 rule above
        Tooltip tip = new Tooltip();
        tip.setShowDelay(Duration.millis(300));
        tip.setOnShowing(ev -> tip.setText(how.get()));
        Runnable sync = () -> {
            if (c.isDisabled()) {
                tip.setText(how.get());
                Tooltip.install(wrap, tip);
            } else {
                Tooltip.uninstall(wrap, tip);
            }
        };
        c.disabledProperty().addListener((o, was, dis) -> sync.run());
        sync.run();
        return wrap;
    }

    /** A grayed MENU row with a HOVER reason (v19.7, D236 — the
     *  program-wide successor to the D235 label parenthetical): a
     *  disabled MenuItem swallows hover so its tooltip can never
     *  fire — the row is instead an ENABLED CustomMenuItem holding
     *  a gray Label. The tooltip carries the full reason, clicks do
     *  nothing and never close the menu (hideOnClick false). */
    static MenuItem grayItem(String label, String why) {
        Label l = new Label(label);
        l.setStyle("-fx-text-fill: #999;");
        Tooltip tip = new Tooltip(why);
        tip.setShowDelay(Duration.millis(300));
        return labelItem(l, tip, false);
    }

    /** A CustomMenuItem holding a Label (v19.19, D248 — factored out
     *  of grayItem for the badge menu's TITLE row): no padding of its
     *  own (the .menu-item container carries the row padding —
     *  doubling it misaligned + fattened the row, v19.8 D237), full
     *  width, hideOnClick false, an optional tooltip installed on the
     *  WHOLE row, and the self-measuring left pad. inert = the row
     *  never LOOKS focused: an inline transparent background on the
     *  skin's .menu-item container beats modena's :focused highlight
     *  (inline styles outrank every stylesheet rule, pseudo-classes
     *  included) and the label's inline text fill beats
     *  ".menu-item:focused .label"; the cursor stays the default. */
    static CustomMenuItem labelItem(Label l, Tooltip tip, boolean inert) {
        l.setPadding(Insets.EMPTY);
        l.setMaxWidth(Double.MAX_VALUE);
        if (tip != null) Tooltip.install(l, tip);
        // the tooltip must fire over the WHOLE highlighted row, not
        // just the text — install on the .menu-item container once
        // the skin parents the label (D237)
        l.parentProperty().addListener((o, was, par) -> {
            if (par != null) {
                if (tip != null) Tooltip.install(par, tip);
                if (inert) {
                    par.setStyle("-fx-background-color: transparent;");
                    par.setCursor(Cursor.DEFAULT);
                }
            }
            if (was != null && tip != null) Tooltip.uninstall(was, tip);
        });
        // SELF-MEASURING left pad (v19.10, D239 — the user's THIRD
        // alignment report on these rows, root-caused for real this
        // time): the skin puts a CustomMenuItem's content at the
        // bare left inset, while native item TEXT sits after the
        // menu's check/graphic COLUMN — which exists only in menus
        // holding Check/Radio rows (the badge menu's "View in …"
        // check; every check-free menu has offset 0, which is why
        // D237's fixed padding was wrong for those and zero padding
        // is wrong for the badge menu). Measure a native sibling
        // row's label after layout and pad to match — exact for any
        // menu and font. Re-measures per layout pass; converges
        // (the pad lives INSIDE the label, so the measured x's
        // never change once set).
        Runnable align = () -> {
            javafx.scene.Parent par = l.getParent();
            if (par == null || par.getParent() == null) return;
            for (javafx.scene.Node sib
                    : par.getParent().getChildrenUnmodifiable()) {
                if (sib == par
                        || !sib.getStyleClass().contains("menu-item")
                        || sib.getStyleClass().contains("custom-menu-item")
                        || !(sib instanceof javafx.scene.Parent sp)) {
                    continue;
                }
                javafx.scene.Node txt = sp.lookup(".label");
                if (txt == null) continue;   // separators etc.
                // both x's are container-local; the label's own
                // padding is INSIDE it and never moves its layoutX
                double pad = txt.getLayoutX() - l.getLayoutX();
                if (pad > 0.5
                        && Math.abs(pad - l.getPadding().getLeft()) > 0.5) {
                    l.setPadding(new Insets(0, 0, 0, pad));
                }
                return;
            }
        };
        // runLater: the bounds change fires DURING the layout pass,
        // when a sibling laid out after us still sits at x 0 — the
        // hop measures once the whole pulse's layout is done
        l.boundsInParentProperty().addListener((o2, a2, b2) ->
            javafx.application.Platform.runLater(align));
        return new CustomMenuItem(l, false);
    }

    /** Why lane button i is disabled right now (D235 hover reasons —
     *  the toolbar wraps each button in the live grayHint). */
    String laneDisableWhy(int i) {
        if (activeSc() != null) {
            return "Straight/curve sections have no lanes.";
        }
        if (activeBridge() != null) {
            return "A bridge's curves are solver-owned.";
        }
        return "Roll takes no influence deltas.";
    }

    /** Remove section k of the ACTIVE piece; downstream sections
     *  re-chain from the new previous exit (their LOCAL shapes
     *  untouched, §0). Deleting a piece's LAST section deletes the
     *  piece when others remain (v12.0). One undo step. */
    private void deleteSection(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        boolean lastInPiece = secs.size() <= 1;
        if (lastInPiece && app.coaster.pieces.size() <= 1) return;
        pushUndoSnapshot();
        boolean wasActive = k == activeSection;
        if (lastInPiece) {
            hideJunctionWindows();   // piece indices shift (D247 review fix; + the bridge window, D248)
            List<TrackPiece> pcs = app.coaster.pieces;
            pcs.remove(activePiece);
            // the successor's follow flag becomes INERT when it now
            // heads the list or sits behind a bridge-ending piece —
            // normalize it so a later run move / attach can't turn
            // the stale flag into a live follow (D248; a successor
            // behind a plain piece re-glues onto it as before)
            if (activePiece < pcs.size() && pcs.get(activePiece).followPrev
                    && (activePiece == 0
                        || app.coaster.endsWithBridge(activePiece - 1))) {
                pcs.get(activePiece).followPrev = false;
            }
            activePiece = (int) FvdDesigner.clamp(activePiece, 0,
                pcs.size() - 1);
            activeSection = 0;
            wasActive = true;
        } else {
            // deleting the FIRST section RE-ANCHORS the piece at the
            // old section-0/section-1 boundary (v19.6, D235 — user
            // spec: the remaining track must not move; bitwise via
            // the D199 canonical pair). Followers skip — their entry
            // is glued to the seam. The ride entry's v0 follows the
            // new first section's design speed so the start speed
            // reads what actually enters.
            if (k == 0) {
                TrackPiece pc = app.coaster.pieces.get(activePiece);
                TrackCompiler.Span sp0 = spanOf(0);
                if (!pc.followPrev && sp0 != null) {
                    app.coaster.reanchorAtBoundary(pc, sp0);
                }
                secs.remove(0);
                if (activePiece == 0 && !secs.isEmpty()) {
                    app.coaster.v0 = secs.get(0).vDesign();
                    app.syncStartSpeedSpinner();
                }
            } else {
                secs.remove(k);
            }
            if (activeSection > k) activeSection--;
            activeSection = (int) FvdDesigner.clamp(activeSection, 0,
                sections().size() - 1);
        }
        app.lanes = lanesFor(sections().get(activeSection));
        if (wasActive) {
            selIdx = -1;
            selSet.clear();
            elSel = -1;
            axisLock = 0;
            handleViewExpand = false;
        }
        syncLaneButtons();
        app.rebuildTrack();   // spans first: an s/c strip's width may shift
        app.updatePieceButton();
        app.syncPlacementSpinners();
        refreshOffset();
        redraw();
    }

    /** Append a 1 s section HOLDING the ride's end state, so the
     *  transition is seamless whatever came before (v10.1 user spec).
     *  Its vDesign freezes at the design speed of the piece's current
     *  end (§0: later v0 edits must never reshape it). One undo step.
     *  Same-mode: the previous section's final lane values. Force
     *  after geo: the felt end forces (compiled Gv/Gl at design speed)
     *  + the final roll rate. Geo after force: the RESULTING end
     *  pitch/yaw rates + the authored roll rate. */
    private void addSection(int mode) { addSection(mode, false); }

    /** real = the "Transition to real" fork (v15.2, D101): the new
     *  section starts at the TRUE end speed holding the TRUE end
     *  state — as if the conflicted section's real curves were its
     *  design graphs. false = "Continue speed conflict", the historic
     *  design-profile continuation. Identical when nothing drifted. */
    private void addSection(int mode, boolean real) {
        pushUndoSnapshot();
        List<Section> secs = sections();
        Section prevSec = secs.get(secs.size() - 1);
        LaneSection prev = prevSec instanceof LaneSection ls ? ls : null;
        // the ACTIVE PIECE's end sample (v12.0) — with pieces after
        // this one, the ride's last sample belongs to somebody else
        TrackCompiler.Span endSp = spanOf(secs.size() - 1);
        int endIdx = endSp != null && app.lastSamples != null
            && endSp.i1() < app.lastSamples.size() ? endSp.i1() : -1;
        FVDIntegrator.Sample end = endIdx >= 0 ? app.lastSamples.get(endIdx) : null;
        boolean tru = real && endIdx >= 0 && trueArraysReady()
            && endIdx < app.trueV.length;
        double vEnd = tru ? Math.max(0.1, app.trueV[endIdx])
            : end != null ? end.v() : prevSec.vDesign();
        // authored roll only reads WITH the chain from a non-reversed,
        // UN-influenced lane section on the DESIGN fork; otherwise
        // (reversed D67, influenced D98, s/c, or the REAL fork) use
        // the compiled resulting/true roll
        double rollEnd = !tru && prev != null && !prev.reversed
                && !prev.influenced()
            ? prev.lanes.get(2).get(prev.lanes.get(2).size() - 1).val
            : (endIdx >= 0 && app.resultRates != null
                && endIdx < app.resultRates.length
                ? (tru ? app.trueRates : app.resultRates)[endIdx][3] : 0);
        Section added;
        if (mode == 2) {
            // straight/curve: a straight IS the seamless continuation
            // (buildScStraight recomputes this same vEnd — bitwise;
            // the seam insert shares it, D247)
            added = buildScStraight(secs.size() - 1, real, 10);
        } else if (!tru && prev != null && !prev.reversed && !prev.influenced()
                && (mode == 1) == prev instanceof GeoSection) {
            added = Coaster.continuation(prev, vEnd);
        } else if (mode == 1) {
            double[] endRates = app.resultRates != null && endIdx >= 0
                && endIdx < app.resultRates.length
                ? (tru ? app.trueRates : app.resultRates)[endIdx]
                : new double[4];
            added = new GeoSection(
                LaneSection.holdLanes(endRates[1], endRates[2], rollEnd), vEnd)
                .endFollows(true);   // a fresh hold follows the end (D248)
        } else {
            double gvEnd = tru ? app.trueFelt[endIdx][0]
                : end != null ? end.Gv() : 1;
            double glEnd = tru ? app.trueFelt[endIdx][1]
                : end != null ? end.Gl() : 0;
            added = Coaster.holdSection(gvEnd, glEnd, rollEnd, vEnd);
        }
        secs.add(added);
        activeSection = sections().size() - 1;
        app.lanes = lanesFor(added);
        defaultLane();   // a new section opens on lane 0 (D248)
        elSel = mode == 2 ? 0 : -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;          // auto-fit: the new ACTIVE section must be
                              // on screen, or the next click maps through
                              // a window that ends before it
        syncLaneButtons();
        app.rebuildTrack();   // spans before layout: s/c widths need them
        refreshOffset();
        redraw();
    }

    /** The ADD FORCE/GEOMETRIC SECTION dialog (v16.2, user spec — one
     *  entry, every option): mode, domain, axis semantics, the
     *  conflict fork (shown only after a drifted end), and the
     *  starting DESIGN SPEED behind a padlock — locked (default) it
     *  tracks what the choice implies (design end vs true end);
     *  unlocked, a custom value creates a DELIBERATE conflict (the
     *  section pins so the reconcile leaves it alone; B/Shift+B then
     *  line the REAL graphs up across the seam). Add-only for now —
     *  existing sections keep their badge-menu settings. */
    private void showAddLaneDialog() {
        List<Section> secs = sections();
        Section prevSec = secs.get(secs.size() - 1);
        LaneSection prev = prevSec instanceof LaneSection pl ? pl : null;
        // auto speeds for the padlock display
        TrackCompiler.Span endSp = spanOf(secs.size() - 1);
        int endIdx = endSp != null && app.lastSamples != null
            && endSp.i1() < app.lastSamples.size() ? endSp.i1() : -1;
        double vCont = endIdx >= 0 ? app.lastSamples.get(endIdx).v()
                                   : prevSec.vDesign();
        double vReal = endIdx >= 0 && trueArraysReady()
            && endIdx < app.trueV.length ? app.trueV[endIdx] : vCont;
        boolean drifted = pieceEndDrifted();

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Add force/geometric section");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;

        ToggleGroup mg = new ToggleGroup();
        RadioButton mForce = new RadioButton("Force");
        RadioButton mGeo = new RadioButton("Geometric");
        mForce.setToggleGroup(mg);
        mGeo.setToggleGroup(mg);
        (prev instanceof GeoSection ? mGeo : mForce).setSelected(true);
        wireOriginal(mg, prev instanceof GeoSection ? mGeo : mForce, null);
        grid.addRow(row++, new Label("Type"), new HBox(10, mForce, mGeo));

        ToggleGroup dg = new ToggleGroup();
        RadioButton dTime = new RadioButton("Time");
        RadioButton dDist = new RadioButton("Distance");
        dTime.setToggleGroup(dg);
        dDist.setToggleGroup(dg);
        (prev != null && prev.domain() == Section.Domain.DISTANCE
            ? dDist : dTime).setSelected(true);
        wireOriginal(dg, prev != null
            && prev.domain() == Section.Domain.DISTANCE ? dDist : dTime,
            null);
        grid.addRow(row++, new Label("Domain"), new HBox(10, dTime, dDist));

        // RATE UNITS (v16.6, D115): °/s vs °/m for the rate lanes
        ToggleGroup ug = new ToggleGroup();
        RadioButton uSec = new RadioButton("Per second (°/s)");
        RadioButton uMet = new RadioButton("Per meter (°/m)");
        uSec.setToggleGroup(ug);
        uMet.setToggleGroup(ug);
        (prev != null && prev.ratePerMeter ? uMet : uSec).setSelected(true);
        wireOriginal(ug, prev != null && prev.ratePerMeter ? uMet : uSec,
            null);
        grid.addRow(row++, new Label("Rate units"), new HBox(10, uSec, uMet));

        ToggleGroup yg = new ToggleGroup();
        RadioButton yLocal = new RadioButton("Local");
        RadioButton yGlobal = new RadioButton("Global (heading/elevation)");
        yLocal.setToggleGroup(yg);
        yGlobal.setToggleGroup(yg);
        (prev != null && prev.yawPitchGlobal ? yGlobal : yLocal).setSelected(true);
        wireOriginal(yg, prev != null && prev.yawPitchGlobal
            ? yGlobal : yLocal, null);
        Label ypLbl = new Label("Yaw/pitch axis");
        String ypHow = "Yaw/pitch axes exist on geometric sections — "
            + "set Type to Geometric to choose them.";
        HBox ypRow = new HBox(10, grayHint(yLocal, ypHow),
            grayHint(yGlobal, ypHow));
        grid.addRow(row++, ypLbl, ypRow);
        Runnable syncYp = () -> {
            boolean geoOn = mGeo.isSelected();
            yLocal.setDisable(!geoOn);
            yGlobal.setDisable(!geoOn);
            // D241: graying must not leave Global SELECTED — the dot
            // sat on a choice that no longer applied (the behavior
            // row's Abs→Rate precedent)
            if (!geoOn && yGlobal.isSelected()) yLocal.setSelected(true);
        };
        syncYp.run();

        // YAW/PITCH BEHAVIOR (v19.8, D237): rate vs ABSOLUTE
        // heading/elevation angles — needs Geometric + global
        // yaw/pitch, directly below the axis row (user spec)
        ToggleGroup yag = new ToggleGroup();
        RadioButton ypaRate = new RadioButton("Rate");
        RadioButton ypaAbs = new RadioButton("Absolute angles (°)");
        ypaRate.setToggleGroup(yag);
        ypaAbs.setToggleGroup(yag);
        boolean prevYpAbs = prev != null && prev.ypAbsolute
            && prev.yawPitchGlobal;
        (prevYpAbs ? ypaAbs : ypaRate).setSelected(true);
        wireOriginal(yag, prevYpAbs ? ypaAbs : ypaRate, null);
        // the whole row grays under Force (v19.10, D239 — the axis
        // row's rule); Absolute additionally needs Global
        String ypaRateHow = "Yaw/pitch behavior exists on geometric "
            + "sections — set Type to Geometric to choose it.";
        grid.addRow(row++, new Label("Yaw/pitch behavior"),
            new HBox(10, grayHint(ypaRate, ypaRateHow),
                grayHint(ypaAbs, YP_ABS_HOW)));
        Runnable syncYpA = () -> {
            boolean geoOn = mGeo.isSelected();
            boolean on = geoOn && yGlobal.isSelected();
            ypaRate.setDisable(!geoOn);
            ypaAbs.setDisable(!on);
            if (!on && ypaAbs.isSelected()) ypaRate.setSelected(true);
        };
        mGeo.selectedProperty().addListener((o, w, n2) -> syncYpA.run());
        yGlobal.selectedProperty().addListener((o, w, n2) -> syncYpA.run());
        syncYpA.run();
        ToggleGroup rg = new ToggleGroup();
        RadioButton rLocal = new RadioButton("Local");
        RadioButton rGlobal = new RadioButton("Global (bank vs horizon)");
        rLocal.setToggleGroup(rg);
        rGlobal.setToggleGroup(rg);
        (prev != null && prev.rollGlobal ? rGlobal : rLocal).setSelected(true);
        wireOriginal(rg, prev != null && prev.rollGlobal ? rGlobal : rLocal,
            null);
        grid.addRow(row++, new Label("Roll axis"), new HBox(10, rLocal, rGlobal));


        // ROLL BEHAVIOR (v16.7, D118): rate vs ABSOLUTE bank angle —
        // absolute needs GLOBAL roll (the angle IS the projection
        // target); picking Local flips it back to Rate and grays it
        ToggleGroup ag = new ToggleGroup();
        RadioButton aRate = new RadioButton("Roll rate");
        RadioButton aAbs = new RadioButton("Absolute bank (°)");
        aRate.setToggleGroup(ag);
        aAbs.setToggleGroup(ag);
        boolean prevAbs = prev != null && prev.rollAbsolute
            && prev.rollGlobal;
        (prevAbs ? aAbs : aRate).setSelected(true);
        wireOriginal(ag, prevAbs ? aAbs : aRate, null);
        grid.addRow(row++, new Label("Roll behavior"),
            new HBox(10, aRate, grayHint(aAbs, ABS_HOW)));
        Runnable syncAbs = () -> {
            boolean glob = rGlobal.isSelected();
            aAbs.setDisable(!glob);
            if (!glob && aAbs.isSelected()) aRate.setSelected(true);
        };
        rg.selectedToggleProperty().addListener((o, w, n2) -> syncAbs.run());
        syncAbs.run();

        ToggleGroup cg = new ToggleGroup();
        RadioButton cCont = new RadioButton("Continue speed conflict");
        RadioButton cReal = new RadioButton("Set to Real");
        cCont.setToggleGroup(cg);
        cReal.setToggleGroup(cg);
        cCont.setSelected(true);
        wireOriginal(cg, cCont, null);
        if (drifted) {
            grid.addRow(row++, new Label("Initial design speed"),
                new HBox(10, cCont, cReal));
        }

        TextField vField = new TextField(
            String.format(Locale.US, "%.2f", vCont));
        vField.setPrefColumnCount(6);
        vField.setTextFormatter(FvdDesigner.numFormatter());   // v18.1
        vField.setDisable(true);
        ToggleButton vLock = new ToggleButton("🔒");
        vLock.setFocusTraversable(false);
        vLock.selectedProperty().addListener((o, was, sel) -> {
            vLock.setText(sel ? "🔓" : "🔒");
            vField.setDisable(!sel);
            if (!sel) {   // re-locked: track the auto value again
                vField.setText(String.format(Locale.US, "%.2f",
                    cReal.isSelected() ? vReal : vCont));
            }
        });
        cg.selectedToggleProperty().addListener((o, was, sel) -> {
            // changing the seam choice RE-LOCKS the speed (v16.4,
            // user rule) — the padlock snaps back to tracking the
            // new fork's implied value
            if (vLock.isSelected()) {
                vLock.setSelected(false);   // its listener refreshes
            } else {
                vField.setText(String.format(Locale.US, "%.2f",
                    cReal.isSelected() ? vReal : vCont));
            }
        });
        mg.selectedToggleProperty().addListener((o, was, sel) -> syncYp.run());
        grid.addRow(row++, new Label("Design speed (m/s)"),
            new HBox(6, grayHint(vField, "Tracks the speed the seam "
                + "implies — click the padlock to type a custom "
                + "speed (a deliberate, pinned conflict)."), vLock));

        Button add = new Button("Add");
        add.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, add, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);
        // Apply-only dialog (D247): the fork row's premises (drifted /
        // the auto speeds) were read at open — re-check at Add and
        // refuse with a reason if the piece end changed meanwhile
        // (the seed fields deliberately stay as opened)
        final int addPiece = activePiece, addCount = secs.size();
        add.setOnAction(ev -> {
            if (activePiece != addPiece
                    || sections().size() != addCount
                    || pieceEndDrifted() != drifted) {
                dlg.close();
                app.statsLabel.setText("Section not added — the piece "
                    + "end changed while the dialog was open; open "
                    + "Add section again.");
                return;
            }
            double vCustom = Double.NaN;
            if (vLock.isSelected()) {
                vCustom = FvdDesigner.clamp(
                    parseOr(vField, cReal.isSelected() ? vReal : vCont),
                    1, 100);
            }
            addLaneSection(mGeo.isSelected(),
                drifted && cReal.isSelected(), dDist.isSelected(),
                rGlobal.isSelected(),
                mGeo.isSelected() && yGlobal.isSelected(),
                uMet.isSelected(),
                aAbs.isSelected() && rGlobal.isSelected(),
                ypaAbs.isSelected() && mGeo.isSelected()
                    && yGlobal.isSelected(),
                vCustom);
            dlg.close();
        });
        cancel.setOnAction(ev -> dlg.close());
        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installDocUndoKeys(dlg.getScene());   // D227: global undo + ride keys
    }

    /** SESSION memory of the seam-insert dialog's "Initial length" per
     *  domain (v19.18, D247 — the wizard's lenTime/lenDist behavior
     *  with session scope: a non-live dialog has no document record
     *  to ride; remembered across opens, never saved). Defaults = the
     *  wizard's (NEW_START_LEN 5 s / CONT_HOLD_DIST 10 m); a
     *  straight/curve section shares the meters slot. */
    private double insLenTime = FvdDesigner.NEW_START_LEN;
    private double insLenDist = Coaster.CONT_HOLD_DIST;

    /** THE INSERT SECTION DIALOG (v19.18, D247 — the seam menu's
     *  "Insert section…", user spec: every option of the Add dialog
     *  plus the wizard's initial length): pL = the UPSTREAM piece (the
     *  active piece — the menu action activates it first so the hold
     *  recipe reads its arrays), kL = the seam's left section in it,
     *  insertIdx = kL + 1, strictSeam = the boundary is a strict seam
     *  (the section then APPENDS to pL before it — the follower keeps
     *  its shape and held angles, its position follows). Rows in the
     *  D143 canonical order with a THIRD Type radio "Straight / Curve"
     *  (the wizard's tile — its lane rows gray with a reason), the
     *  fork row when the seam is speed-conflicted (seamDrifted), the
     *  padlocked design speed (custom clamp [0.1, 1e4] per D198), the
     *  "Initial length" spinner (label follows the domain: seconds,
     *  or meters for DISTANCE and s/c; per-domain session memory —
     *  a flip folds the current value into the outgoing slot and
     *  loads the target's) and, on NON-strict seams only, "Lock
     *  Subsequent Track Shape" (a strict seam after the inserted
     *  section, made BEFORE the insert so the downstream freezes at
     *  its present shape). Apply-only: the targets re-resolve at Add
     *  and a changed seam refuses with a stats reason (D247, item F).
     *  One undo step per Add. */
    private void showInsertSectionDialog(int pL, int kL, int insertIdx,
                                         boolean strictSeam) {
        if (activePiece != pL || kL < 0 || kL >= sections().size()) return;
        List<Section> secs = sections();
        Section prevSec = secs.get(kL);
        LaneSection prev = prevSec instanceof LaneSection pl ? pl : null;
        // auto speeds for the padlock display — the SEAM sample
        TrackCompiler.Span endSp = spanOf(kL);
        int endIdx = endSp != null && app.lastSamples != null
            && endSp.i1() < app.lastSamples.size() ? endSp.i1() : -1;
        double vCont = endIdx >= 0 ? app.lastSamples.get(endIdx).v()
                                   : prevSec.vDesign();
        double vReal = endIdx >= 0 && trueArraysReady()
            && endIdx < app.trueV.length ? app.trueV[endIdx] : vCont;
        boolean drifted = seamDrifted(kL);

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Insert section after " + sectionTitle(pL, kL));   // D248
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;

        // TYPE — three radios: the Add dialog's pair + the wizard's
        // straight/curve tile (J1: one dialog, the lane rows gray)
        ToggleGroup mg = new ToggleGroup();
        RadioButton mForce = new RadioButton("Force");
        RadioButton mGeo = new RadioButton("Geometric");
        RadioButton mSc = new RadioButton("Straight / Curve");
        mForce.setToggleGroup(mg);
        mGeo.setToggleGroup(mg);
        mSc.setToggleGroup(mg);
        RadioButton mOrig = prevSec instanceof StraightCurveSection ? mSc
            : prev instanceof GeoSection ? mGeo : mForce;
        mOrig.setSelected(true);
        wireOriginal(mg, mOrig, null);
        grid.addRow(row++, new Label("Type"), new HBox(10, mForce, mGeo, mSc));
        String scHow = "Straight/curve sections have no lane options — "
            + "set Type to Force or Geometric to choose them.";

        ToggleGroup dg = new ToggleGroup();
        RadioButton dTime = new RadioButton("Time");
        RadioButton dDist = new RadioButton("Distance");
        dTime.setToggleGroup(dg);
        dDist.setToggleGroup(dg);
        (prev != null && prev.domain() == Section.Domain.DISTANCE
            ? dDist : dTime).setSelected(true);
        wireOriginal(dg, prev != null
            && prev.domain() == Section.Domain.DISTANCE ? dDist : dTime,
            null);
        grid.addRow(row++, new Label("Domain"),
            new HBox(10, grayHint(dTime, scHow), grayHint(dDist, scHow)));

        ToggleGroup ug = new ToggleGroup();
        RadioButton uSec = new RadioButton("Per second (°/s)");
        RadioButton uMet = new RadioButton("Per meter (°/m)");
        uSec.setToggleGroup(ug);
        uMet.setToggleGroup(ug);
        (prev != null && prev.ratePerMeter ? uMet : uSec).setSelected(true);
        wireOriginal(ug, prev != null && prev.ratePerMeter ? uMet : uSec,
            null);
        grid.addRow(row++, new Label("Rate units"),
            new HBox(10, grayHint(uSec, scHow), grayHint(uMet, scHow)));

        ToggleGroup yg = new ToggleGroup();
        RadioButton yLocal = new RadioButton("Local");
        RadioButton yGlobal = new RadioButton("Global (heading/elevation)");
        yLocal.setToggleGroup(yg);
        yGlobal.setToggleGroup(yg);
        (prev != null && prev.yawPitchGlobal ? yGlobal : yLocal).setSelected(true);
        wireOriginal(yg, prev != null && prev.yawPitchGlobal
            ? yGlobal : yLocal, null);
        String ypHow = "Yaw/pitch axes exist on geometric sections — "
            + "set Type to Geometric to choose them.";
        grid.addRow(row++, new Label("Yaw/pitch axis"),
            new HBox(10, grayHint(yLocal, ypHow), grayHint(yGlobal, ypHow)));
        Runnable syncYp = () -> {
            boolean geoOn = mGeo.isSelected();
            yLocal.setDisable(!geoOn);
            yGlobal.setDisable(!geoOn);
            if (!geoOn && yGlobal.isSelected()) yLocal.setSelected(true);
        };
        syncYp.run();

        ToggleGroup yag = new ToggleGroup();
        RadioButton ypaRate = new RadioButton("Rate");
        RadioButton ypaAbs = new RadioButton("Absolute angles (°)");
        ypaRate.setToggleGroup(yag);
        ypaAbs.setToggleGroup(yag);
        boolean prevYpAbs = prev != null && prev.ypAbsolute
            && prev.yawPitchGlobal;
        (prevYpAbs ? ypaAbs : ypaRate).setSelected(true);
        wireOriginal(yag, prevYpAbs ? ypaAbs : ypaRate, null);
        String ypaRateHow = "Yaw/pitch behavior exists on geometric "
            + "sections — set Type to Geometric to choose it.";
        grid.addRow(row++, new Label("Yaw/pitch behavior"),
            new HBox(10, grayHint(ypaRate, ypaRateHow),
                grayHint(ypaAbs, YP_ABS_HOW)));
        Runnable syncYpA = () -> {
            boolean geoOn = mGeo.isSelected();
            boolean on = geoOn && yGlobal.isSelected();
            ypaRate.setDisable(!geoOn);
            ypaAbs.setDisable(!on);
            if (!on && ypaAbs.isSelected()) ypaRate.setSelected(true);
        };
        mGeo.selectedProperty().addListener((o, w, n2) -> syncYpA.run());
        yGlobal.selectedProperty().addListener((o, w, n2) -> syncYpA.run());
        syncYpA.run();

        ToggleGroup rg = new ToggleGroup();
        RadioButton rLocal = new RadioButton("Local");
        RadioButton rGlobal = new RadioButton("Global (bank vs horizon)");
        rLocal.setToggleGroup(rg);
        rGlobal.setToggleGroup(rg);
        (prev != null && prev.rollGlobal ? rGlobal : rLocal).setSelected(true);
        wireOriginal(rg, prev != null && prev.rollGlobal ? rGlobal : rLocal,
            null);
        grid.addRow(row++, new Label("Roll axis"),
            new HBox(10, grayHint(rLocal, scHow), grayHint(rGlobal, scHow)));

        ToggleGroup ag = new ToggleGroup();
        RadioButton aRate = new RadioButton("Roll rate");
        RadioButton aAbs = new RadioButton("Absolute bank (°)");
        aRate.setToggleGroup(ag);
        aAbs.setToggleGroup(ag);
        boolean prevAbs = prev != null && prev.rollAbsolute
            && prev.rollGlobal;
        (prevAbs ? aAbs : aRate).setSelected(true);
        wireOriginal(ag, prevAbs ? aAbs : aRate, null);
        grid.addRow(row++, new Label("Roll behavior"),
            new HBox(10, grayHint(aRate, scHow),
                grayHint(aAbs, () -> mSc.isSelected() ? scHow : ABS_HOW)));
        Runnable syncAbs = () -> {
            boolean glob = rGlobal.isSelected() && !mSc.isSelected();
            aAbs.setDisable(!glob);
            if (!glob && aAbs.isSelected()) aRate.setSelected(true);
        };
        rg.selectedToggleProperty().addListener((o, w, n2) -> syncAbs.run());
        syncAbs.run();
        // the s/c pick grays every lane row (it has none)
        Runnable syncSc = () -> {
            boolean sc = mSc.isSelected();
            for (RadioButton rb : new RadioButton[]{ dTime, dDist, uSec,
                    uMet, rLocal, rGlobal, aRate }) {
                rb.setDisable(sc);
            }
            syncYp.run();
            syncYpA.run();
            syncAbs.run();
        };

        ToggleGroup cg = new ToggleGroup();
        RadioButton cCont = new RadioButton("Continue speed conflict");
        RadioButton cReal = new RadioButton("Set to Real");
        cCont.setToggleGroup(cg);
        cReal.setToggleGroup(cg);
        cCont.setSelected(true);
        wireOriginal(cg, cCont, null);
        if (drifted) {
            grid.addRow(row++, new Label("Initial design speed"),
                new HBox(10, cCont, cReal));
        }

        TextField vField = new TextField(
            String.format(Locale.US, "%.2f", vCont));
        vField.setPrefColumnCount(6);
        vField.setTextFormatter(FvdDesigner.numFormatter());
        vField.setDisable(true);
        ToggleButton vLock = new ToggleButton("🔒");
        vLock.setFocusTraversable(false);
        vLock.selectedProperty().addListener((o, was, sel) -> {
            vLock.setText(sel ? "🔓" : "🔒");
            vField.setDisable(!sel);
            if (!sel) {   // re-locked: track the auto value again
                vField.setText(String.format(Locale.US, "%.2f",
                    cReal.isSelected() ? vReal : vCont));
            }
        });
        cg.selectedToggleProperty().addListener((o, was, sel) -> {
            // the fork change RE-LOCKS the speed (v16.4, user rule)
            if (vLock.isSelected()) {
                vLock.setSelected(false);
            } else {
                vField.setText(String.format(Locale.US, "%.2f",
                    cReal.isSelected() ? vReal : vCont));
            }
        });
        mg.selectedToggleProperty().addListener((o, was, sel) -> syncSc.run());
        syncSc.run();
        grid.addRow(row++, new Label("Design speed (m/s)"),
            new HBox(6, grayHint(vField, "Tracks the speed the seam "
                + "implies — click the padlock to type a custom "
                + "speed (a deliberate, pinned conflict)."), vLock));

        // INITIAL LENGTH — the wizard's spinner (D236 behavior: the
        // label follows the domain, s/c counts as meters, per-domain
        // memory) built like StartPanel.spin
        final boolean[] lenDist = { mSc.isSelected() || dDist.isSelected() };
        Spinner<Double> lenSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 10000,
                lenDist[0] ? insLenDist : insLenTime, 1));
        lenSp.setPrefWidth(86);
        FvdDesigner.typable(lenSp, false);
        Tooltip.install(lenSp, new Tooltip("Length of the inserted "
            + "section in its own units — seconds for a time section, "
            + "meters for a distance or straight/curve section. It "
            + "holds the seam's end state flat over that length."));
        Label lenLbl = new Label(lenDist[0]
            ? "Initial length (m)" : "Initial length (s)");
        Runnable syncLen = () -> {
            boolean d = mSc.isSelected() || dDist.isSelected();
            if (d != lenDist[0]) {
                // fold the outgoing slot, load the target's
                double cur = lenSp.getValue();
                if (lenDist[0]) insLenDist = cur; else insLenTime = cur;
                lenSp.getValueFactory().setValue(d ? insLenDist : insLenTime);
                lenDist[0] = d;
            }
            lenLbl.setText(d ? "Initial length (m)" : "Initial length (s)");
        };
        mg.selectedToggleProperty().addListener((o, w, n2) -> syncLen.run());
        dg.selectedToggleProperty().addListener((o, w, n2) -> syncLen.run());
        grid.addRow(row++, lenLbl, lenSp);

        // LOCK SUBSEQUENT TRACK SHAPE — non-strict seams only (a strict
        // seam already freezes the downstream; the row is hidden
        // there, the D132 inapplicable-row rule)
        CheckBox lock = new CheckBox("Lock Subsequent Track Shape");
        Tooltip.install(lock, new Tooltip("Adds a strict seam after the "
            + "inserted section so the track after it keeps its shape "
            + "(it moves to the new exit)."));
        if (!strictSeam) grid.add(lock, 0, row++, 2, 1);

        Button add = new Button("Add");
        add.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, add, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);
        final int openCount = secs.size();
        add.setOnAction(ev -> {
            // a typed-but-unentered length commits first (Enter on
            // the default button moves no focus — item E's lesson)
            FvdDesigner.commitFocusedNumericEditor(dlg.getScene());
            // index-resolve the targets at Add (item F): the piece,
            // its section count and the seam's premises must hold
            boolean stillStrict = pL + 1 < app.coaster.pieces.size()
                && followsPrev(pL + 1);
            if (activePiece != pL || sections().size() != openCount
                    || (strictSeam && !stillStrict)
                    || seamDrifted(kL) != drifted) {
                dlg.close();
                app.statsLabel.setText("Section not inserted — the seam "
                    + "changed while the dialog was open; open Insert "
                    + "section again.");
                return;
            }
            if (spanOf(kL) == null) {
                dlg.close();
                app.statsLabel.setText("Insert refused — this boundary "
                    + "isn't compiled yet.");
                return;
            }
            double len = lenSp.getValue();
            if (lenDist[0]) insLenDist = len; else insLenTime = len;
            boolean sc = mSc.isSelected();
            boolean real = drifted && cReal.isSelected();
            double vCustom = Double.NaN;
            if (vLock.isSelected()) {
                vCustom = FvdDesigner.clamp(
                    parseOr(vField, cReal.isSelected() ? vReal : vCont),
                    0.1, 1e4);   // D198: admit a near-stall seam speed
            }
            boolean lockDown = !strictSeam && lock.isSelected();
            if (lockDown) hideJunctionWindows();   // piece indices shift (D248)
            // the snapshot is pushed only once the insert landed (D247
            // review fix — a refusal must not clear the redo branch)
            FvdDesigner.DocState pre = snapshot();
            Section added;
            if (sc) {
                StraightCurveSection s = buildScStraight(kL, real, len);
                if (!Double.isNaN(vCustom)) {
                    double vAuto = s.vDesign();
                    s.vDesign(vCustom);
                    if (Math.abs(vCustom - vAuto) > 1e-6) s.vPinned = true;
                }
                added = s;
            } else {
                added = buildLaneHold(kL, mGeo.isSelected(), real,
                    dDist.isSelected(), rGlobal.isSelected(),
                    mGeo.isSelected() && yGlobal.isSelected(),
                    uMet.isSelected(),
                    aAbs.isSelected() && rGlobal.isSelected(),
                    ypaAbs.isSelected() && mGeo.isSelected()
                        && yGlobal.isSelected(),
                    vCustom, len);
            }
            int[] at = app.coaster.insertSectionAtSeam(pL,
                strictSeam ? sections().size() : insertIdx, added,
                lockDown, FvdDesigner.INTEGRATE_DT);
            dlg.close();
            if (at == null) {
                app.statsLabel.setText("Insert refused — this boundary "
                    + "isn't compiled yet.");
                redraw();   // refused: nothing mutated, nothing pushed
                return;
            }
            pushUndo(pre);   // one undo step
            // the union of the '+' and make-strict refresh sequences
            activePiece = at[0];
            activeSection = at[1];
            app.lanes = lanesFor(added);
            defaultLane();   // an inserted section opens on lane 0 (D248)
            elSel = sc ? 0 : -1;
            axisLock = 0;
            handleViewExpand = false;
            viewT0 = 0;
            viewT1 = -1;
            syncLaneButtons();
            app.markDirty();
            app.rebuildTrack();
            app.updatePieceButton();
            app.syncPlacementSpinners();
            refreshOffset();
            redraw();
            app.statsLabel.setText(strictSeam
                ? "Inserted a section before the strict seam — the track "
                    + "after it kept its shape."
                : lockDown
                ? "Inserted a section — the track after it is now behind "
                    + "a strict seam and kept its shape."
                : "Inserted a section — the track after it re-solves "
                    + "from the new exit.");
        });
        cancel.setOnAction(ev -> dlg.close());
        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installDocUndoKeys(dlg.getScene());   // D227: global undo + ride keys
    }

    /** Append a lane section with the dialog's full option set
     *  (v16.2). Hold values: the compiled (or true) end state in the
     *  new section's own semantics — GLOBAL-axis lanes hold ZERO
     *  rates (the straight/held-bank continuation, the s/c-straight
     *  precedent); a clean same-mode same-axis design continuation
     *  still copies the authored end values exactly. vCustom NaN =
     *  the auto speed; a custom speed PINS the section (a deliberate
     *  conflict the reconcile must not close). */
    private void addLaneSection(boolean geo, boolean real, boolean dist,
                                boolean rollG, boolean ypG, boolean rateM,
                                boolean rollAbs, boolean ypAbs,
                                double vCustom) {
        pushUndoSnapshot();
        List<Section> secs = sections();
        // the '+' hold: after the LAST section, 1 s / 10 m (bitwise
        // the pre-D247 recipe — the length is the only new parameter)
        LaneSection added = buildLaneHold(secs.size() - 1, geo, real, dist,
            rollG, ypG, rateM, rollAbs, ypAbs, vCustom,
            dist ? Coaster.CONT_HOLD_DIST : 1);
        secs.add(added);
        activeSection = sections().size() - 1;
        app.lanes = lanesFor(added);
        defaultLane();   // a new section opens on lane 0 (D248)
        elSel = -1;
        viewT0 = 0;
        viewT1 = -1;
        syncLaneButtons();
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** THE HOLD RECIPE (v19.18, D247 — split out of addLaneSection so
     *  the seam-menu insert shares it): a lane section holding the
     *  end state of the ACTIVE piece's section prevSecIdx in the
     *  requested semantics, over `len` of its own axis (seconds for
     *  TIME, meters for DISTANCE — LaneSection.holdLanes puts the
     *  two nodes at x = 0 and x = len). Reads the compiled arrays at
     *  spanOf(prevSecIdx).i1() and pieceEntryHeadingDeg(activePiece)
     *  — the caller activates the right piece first and calls at
     *  rest. No undo, no list change: the caller places the
     *  section. A custom speed PINS it (deliberate conflict). */
    private LaneSection buildLaneHold(int prevSecIdx, boolean geo,
                                      boolean real, boolean dist,
                                      boolean rollG, boolean ypG,
                                      boolean rateM, boolean rollAbs,
                                      boolean ypAbs, double vCustom,
                                      double len) {
        ypAbs = ypAbs && geo && ypG;   // geo + global only (D237)
        List<Section> secs = sections();
        Section prevSec = secs.get(prevSecIdx);
        LaneSection prev = prevSec instanceof LaneSection pl ? pl : null;
        TrackCompiler.Span endSp = spanOf(prevSecIdx);
        int endIdx = endSp != null && app.lastSamples != null
            && endSp.i1() < app.lastSamples.size() ? endSp.i1() : -1;
        FVDIntegrator.Sample end = endIdx >= 0
            ? app.lastSamples.get(endIdx) : null;
        boolean tru = real && endIdx >= 0 && trueArraysReady()
            && endIdx < app.trueV.length;
        double vAuto = tru ? Math.max(0.1, app.trueV[endIdx])
            : end != null ? end.v() : prevSec.vDesign();
        double vEnd = Double.isNaN(vCustom) ? vAuto : vCustom;
        // ABSOLUTE roll hold (v16.7): the lane holds the CURRENT
        // bank angle (°) — a flat line at the end state's bank.
        // Since v19.9 (D238 — parity with the abs yaw/pitch hold):
        // a same-language neighbor's authored end value copies
        // BITWISE, and a measured bank lands on the representative
        // nearest the neighbor's branch (an over-±180 authored
        // lane keeps it) instead of always the principal.
        double bankEnd = 0;
        boolean prevRollAbs = prev != null && prev.rollAbsolute
            && prev.rollGlobal && !prev.reversed && !prev.influenced()
            && !prev.lanes.get(2).isEmpty();
        if (rollAbs && end != null) {
            double b = FVDIntegrator.bankOfTU(end.T(), end.U());
            if (!Double.isNaN(b)) {
                bankEnd = Math.toDegrees(b);
                if (prevRollAbs) {
                    double near = prev.lanes.get(2)
                        .get(prev.lanes.get(2).size() - 1).val;
                    bankEnd += 360 * Math.round((near - bankEnd) / 360);
                }
            }
        }
        if (rollAbs && prevRollAbs && !tru) {
            bankEnd = prev.lanes.get(2)
                .get(prev.lanes.get(2).size() - 1).val;
        }
        // ABSOLUTE yaw/pitch hold (v19.8, D237; PIECE-relative
        // heading since v19.9, D238): the elevation lane holds the
        // CURRENT angle; the heading lane holds the ride end's
        // heading in the PIECE frame — continue straight at the
        // incoming direction, at its honest value. Continues a
        // same-language neighbor at the representative nearest ITS
        // end value (an out-of-range authored lane keeps its
        // branch); otherwise principal. Angles are speed-invariant
        // — no κ-scale, no °/m division.
        double elevEnd = 0, headEnd = 0;
        if (ypAbs && end != null) {
            elevEnd = Math.toDegrees(
                FVDIntegrator.elevationOfT(end.T()));
            double hd = FVDIntegrator.headingOfT(end.T());
            double ph = pieceEntryHeadingDeg(activePiece);
            if (!Double.isNaN(hd) && !Double.isNaN(ph)) {
                headEnd = Math.toDegrees(hd) - ph;
                if (prev != null && prev.ypAbsolute
                        && prev.yawPitchGlobal && !prev.reversed
                        && !prev.lanes.get(1).isEmpty()) {
                    double near = prev.lanes.get(1)
                        .get(prev.lanes.get(1).size() - 1).val;
                    headEnd += 360 * Math.round((near - headEnd) / 360);
                } else {
                    headEnd = Math.IEEEremainder(headEnd, 360);
                }
            }
        }
        // the end state's WORLD-angle rates (v17.6, D143 — user
        // re-spec of D108's straight-continuation zeros: "the graphs
        // should line up"): GLOBAL-axis holds take the incoming
        // elevation'/heading'/bank' at the seam, so every combination
        // of axis flags continues the resulting graphs — the old
        // all-zero holds left a sudden transition whenever a global
        // flag was on (worst with BOTH: every lane flat zero).
        // Vertical seams (NaN world rates) fall back to the old zero.
        double[] wrEnd = null;
        if (end != null && endIdx >= 0 && app.resultRates != null
                && endIdx < app.resultRates.length) {
            double[][] br = tru ? app.trueRates : app.resultRates;
            wrEnd = Coaster.worldRates(end,
                br[endIdx][1], br[endIdx][2], br[endIdx][3]);
        }
        double rollEnd = rollG
            ? (wrEnd != null && !Double.isNaN(wrEnd[2]) ? wrEnd[2] : 0)
            : !tru && prev != null && !prev.reversed && !prev.influenced()
                && !prev.rollGlobal
                // an authored copy only means the same thing in the
                // same rate units (v16.6)
                && prev.ratePerMeter == rateM
            ? prev.lanes.get(2).get(prev.lanes.get(2).size() - 1).val
            : (endIdx >= 0 && app.resultRates != null
                && endIdx < app.resultRates.length
                ? (tru ? app.trueRates : app.resultRates)[endIdx][3] : 0);
        // resultRates are °/s: a per-meter hold divides by the end
        // speed (only when the value came from the compiled arrays —
        // a GLOBAL bank-rate hold is compiled-sourced too, v17.6)
        boolean rollFromRates = !(!rollG && !tru && prev != null
            && !prev.reversed && !prev.influenced() && !prev.rollGlobal
            && prev.ratePerMeter == rateM);
        double v0h, v1h;
        boolean cleanSame = !tru && prev != null && !prev.reversed
            && !prev.influenced() && geo == prev instanceof GeoSection
            && prev.rollGlobal == rollG
            && prev.yawPitchGlobal == (geo && ypG)
            && prev.ratePerMeter == rateM
            // abs-angle lanes hold degrees, rate lanes rates — an
            // authored copy only means the same thing when the
            // behaviors match too (v19.8, D237)
            && (prev.ypAbsolute && prev.yawPitchGlobal) == ypAbs;
        if (cleanSame) {
            // same mode + same flags: the exact authored copy wins
            // (bitwise; before v17.6 the ypG zero-hold outranked it)
            v0h = prev.lanes.get(0).get(prev.lanes.get(0).size() - 1).val;
            v1h = prev.lanes.get(1).get(prev.lanes.get(1).size() - 1).val;
        } else if (geo && ypG) {
            // CONTINUOUS global hold (v17.6): the incoming elevation
            // + heading rates — zero only on vertical fallback
            v0h = wrEnd != null && !Double.isNaN(wrEnd[0]) ? wrEnd[0] : 0;
            v1h = wrEnd != null && !Double.isNaN(wrEnd[1]) ? wrEnd[1] : 0;
        } else if (geo) {
            double[] er = app.resultRates != null && endIdx >= 0
                && endIdx < app.resultRates.length
                ? (tru ? app.trueRates : app.resultRates)[endIdx]
                : new double[4];
            v0h = er[1];
            v1h = er[2];
        } else {
            v0h = tru ? app.trueFelt[endIdx][0] : end != null ? end.Gv() : 1;
            v1h = tru ? app.trueFelt[endIdx][1] : end != null ? end.Gl() : 0;
        }
        // κ-MATCHED hold values (v16.3, user spec): a CUSTOM design
        // speed re-expresses the held end state at that speed so the
        // REAL graphs line up with the previous section from the
        // start — rates scale by v_d/v_t, felt targets by (v_d/v_t)²
        // about the gravity components (the same identity B uses).
        // B/Shift+B afterwards only re-aims handles, never positions.
        double rH = vEnd / Math.max(0.1, vAuto);
        if (Math.abs(rH - 1) > 1e-9) {
            if (geo) {
                // a per-meter authored copy is already speed-invariant
                // — κ-matching would double-scale it (v16.6)
                if (!(rateM && cleanSame)) {
                    v0h *= rH;
                    v1h *= rH;
                }
            } else {
                double uy = end != null ? end.U().y() : 1;
                double ry = end != null ? end.R().y() : 0;
                v0h = (v0h - uy) * rH * rH + uy;
                v1h = (v1h + ry) * rH * rH - ry;   // lateral mirrored (D184)
            }
            if (rollFromRates) rollEnd *= rH;
        }
        // PER-METER holds (v16.6): values sourced from the compiled
        // °/s arrays divide by the section's design speed (the rH
        // κ-scale composes to ω/vAuto — exactly the °/m value); a
        // per-meter authored copy needs nothing
        if (rateM) {
            double vDiv = Math.max(0.5, vEnd);
            if (geo && !cleanSame) {
                v0h /= vDiv;
                v1h /= vDiv;
            }
            if (rollFromRates) rollEnd /= vDiv;
        }
        // a clean same-language neighbor's authored end values win
        // bitwise for the abs lanes too (the cleanSame rule, D238)
        if (ypAbs && cleanSame) {
            elevEnd = prev.lanes.get(0)
                .get(prev.lanes.get(0).size() - 1).val;
            headEnd = prev.lanes.get(1)
                .get(prev.lanes.get(1).size() - 1).val;
        }
        List<List<BezierBaker.Node>> lanes = LaneSection.holdLanes(
            ypAbs ? elevEnd : v0h, ypAbs ? headEnd : v1h,
            rollAbs ? bankEnd : rollEnd, len);
        LaneSection added = geo ? new GeoSection(lanes, vEnd)
                                : new ForceSection(lanes, vEnd);
        added.domain(dist ? Section.Domain.DISTANCE : Section.Domain.TIME);
        added.rollGlobal = rollG;
        added.yawPitchGlobal = geo && ypG;
        added.ratePerMeter = rateM;
        added.rollAbsolute = rollAbs;
        added.ypAbsolute = ypAbs;
        added.endFollows(true);   // a fresh hold follows the end (D248)
        if (!Double.isNaN(vCustom) && Math.abs(vCustom - vAuto) > 1e-6) {
            added.vPinned = true;   // deliberate conflict — keep it
        }
        return added;
    }

    /** The s/c STRAIGHT continuation of the active piece's section
     *  prevSecIdx (v19.18, D247 — split out of addSection so the
     *  seam-menu insert shares it): one straight of `len` m at the
     *  section's end design speed (real = the TRUE end speed, the
     *  "Transition to real" fork), with the D233 2 m entry ease. A
     *  straight IS the seamless continuation whatever came before.
     *  No undo, no list change. */
    private StraightCurveSection buildScStraight(int prevSecIdx,
                                                 boolean real, double len) {
        List<Section> secs = sections();
        Section prevSec = secs.get(prevSecIdx);
        TrackCompiler.Span endSp = spanOf(prevSecIdx);
        int endIdx = endSp != null && app.lastSamples != null
            && endSp.i1() < app.lastSamples.size() ? endSp.i1() : -1;
        FVDIntegrator.Sample end = endIdx >= 0
            ? app.lastSamples.get(endIdx) : null;
        boolean tru = real && endIdx >= 0 && trueArraysReady()
            && endIdx < app.trueV.length;
        double vEnd = tru ? Math.max(0.1, app.trueV[endIdx])
            : end != null ? end.v() : prevSec.vDesign();
        return StraightCurveSection.straight(len, vEnd);
    }

    // inactive-strip visibility (v9.6 user feel pass: 0.13 was too faint)
    private static final double INACTIVE_SECTION_ALPHA = 0.20;

    /** Greyed strips for inactive sections + boundary lines. UNIFIED
     *  VIEW (v10.1 user spec): the whole graph shows ONE mode's
     *  quantities — the active section's. Same-mode strips draw their
     *  authored lanes; a strip of the OTHER mode draws what it DOES in
     *  the active mode's units, derived from the compiled track (geo
     *  view of a force strip = its resulting pitch/yaw/roll rates;
     *  force view of a geo strip = its felt Gv/Gl + resulting roll).
     *  Everything shares the active value scales, so curves join up
     *  visually across every boundary. */
    private void drawInactiveSections(GraphicsContext gc) {
        List<RunSec> rl = runLayout();   // the whole bridged run (v13.4)
        boolean actGeo = activeGeo();
        LaneSection actAx = sections().get(activeSection)
            instanceof LaneSection a2 ? a2 : null;
        boolean actRollG = actAx != null && actAx.rollGlobal;
        boolean actYpG = actAx != null && actAx.yawPitchGlobal;
        boolean actPM = actAx != null && actAx.ratePerMeter;
        for (int i = 0; i < rl.size(); i++) {
            RunSec rs = rl.get(i);
            if (rs.piece() != activePiece || rs.section() != activeSection) {
                gc.setGlobalAlpha(INACTIVE_SECTION_ALPHA);
                gc.setLineWidth(1.5);
                // REVERSED strips draw derived too: their authored lanes
                // read back-to-front against the chain (D67); a
                // straight/curve strip has no lanes — always derived,
                // plus its faint element separators
                if (rs.sec() instanceof LaneSection ls
                        && (ls instanceof GeoSection) == actGeo
                        && !ls.reversed && !ls.influenced()
                        // authored lanes ARE the plot's quantities only
                        // when the strip's AXIS flags match the active
                        // section's (v18.9 — was a blanket
                        // !globalAxes(): two same-flag global strips
                        // fell to derived body rates and read as a
                        // false step)
                        && ls.rollGlobal == actRollG
                        && ls.yawPitchGlobal == actYpG
                        // per-meter values only overlay an axis in the
                        // same units (v16.6) — derived otherwise; same
                        // for the absolute-roll representation (v16.7)
                        && ls.ratePerMeter == actPM
                        && (ls.rollAbsolute && ls.rollGlobal)
                            == activeRollAbs()
                        && (ls.ypAbsolute && ls.yawPitchGlobal)
                            == activeYpAbs()
                        // an ABSOLUTE heading lane speaks ITS OWN
                        // piece's frame (v19.9, D238 — piece-relative
                        // heading): a cross-piece strip draws DERIVED
                        // in the ACTIVE piece's frame instead, so the
                        // curves still join at strict seams
                        && (!activeYpAbs() || rs.piece() == activePiece)
                        // authored lanes draw directly only when their
                        // native domain IS the plot's axis (v16.0) —
                        // otherwise the compiled samples carry the
                        // warp (both coordinates ride every sample)
                        && (ls.domain() == Section.Domain.DISTANCE) == axisDist()
                        // an orientation-CONFLICTED piece's strips
                        // draw DERIVED — the truth — wherever a lane
                        // deviates under the rigid tilt (v15.2 for
                        // force felt; v19.19 D248 extends it to any
                        // conflict KIND — design tier / held seam —
                        // and to geo WORLD-axis / absolute lanes);
                        // local-rate geo strips are rotation-
                        // invariant and keep their authored lanes
                        && !(pieceConflicted(rs.piece())
                            && LaneSection.tiltDeviatesAny(ls))) {
                    // an INFLUENCED strip's authored lanes no longer
                    // tell the truth — draw derived, like reversed
                    drawAuthoredStrip(gc, ls, rs.t0(), rs.dur());
                } else {
                    drawDerivedStrip(gc, rs.t0(), rs.dur(),
                        spanAt(rs.piece(), rs.section()));
                    if (rs.sec() instanceof StraightCurveSection sc) {
                        drawScSeparators(gc, sc, spanAt(rs.piece(), rs.section()));
                    }
                }
                gc.setGlobalAlpha(1.0);
            }
            if (i < rl.size() - 1) {   // boundary between sections
                double bx = tToX(rs.t0() + rs.dur());
                // hover/open highlight (D136): the seam reads as
                // clickable — brighter + wider under the cursor, and
                // it STAYS lit while its menu is open
                boolean hot = i == hoverSeam || i == openSeam;
                if (seamStrict(rl, i)) {
                    drawSeamSmoothBand(gc, rl, i);   // under the line
                }
                gc.setGlobalAlpha(hot ? 0.95 : 0.55);
                gc.setStroke(hot ? Color.rgb(225, 230, 245)
                                 : Color.rgb(150, 156, 175));
                gc.setLineWidth(hot ? 2 : 1);
                if (seamStrict(rl, i)) {
                    // STRICT seam (v17.1, D135): a DOUBLE line — the
                    // user's visual for "the downstream is frozen"
                    gc.strokeLine(bx - 1.5, MARGIN_TOP,
                        bx - 1.5, MARGIN_TOP + plotHeight());
                    gc.strokeLine(bx + 1.5, MARGIN_TOP,
                        bx + 1.5, MARGIN_TOP + plotHeight());
                    drawSeamCarets(gc, rl, i, bx);
                    drawSeamDiamond(gc, rl, i, bx);
                } else if (rs.sec() instanceof BridgeSection) {
                    // a bridge's EXIT seam (v17.17, D158, user spec):
                    // the target piece rides the bridge exactly like a
                    // follower rides a strict seam, so it wears the
                    // same frozen-downstream DOUBLE line — visual
                    // only: no carets (a live bridge is value- and
                    // slope-continuous by construction) and no
                    // smoothing diamond (nothing to size)
                    gc.strokeLine(bx - 1.5, MARGIN_TOP,
                        bx - 1.5, MARGIN_TOP + plotHeight());
                    gc.strokeLine(bx + 1.5, MARGIN_TOP,
                        bx + 1.5, MARGIN_TOP + plotHeight());
                } else {
                    gc.strokeLine(bx, MARGIN_TOP,
                        bx, MARGIN_TOP + plotHeight());
                }
                gc.setGlobalAlpha(1.0);
                gc.setLineWidth(1);
            }
        }
    }

    /** A seam-spike caret needs at least this much instant angle
     *  (rad) in its component -- continuous track turns well under it
     *  per sample, so smooth axes stay quiet. */
    private static final double CARET_KINK_MIN = Math.toRadians(2);

    /** Spike components of a strict seam's instant angular jump
     *  (v17.3, D140 -- user re-spec of D138): dPhi = the axis-angle
     *  rotation between the seam's two drawn frames, projected the
     *  way Omega decomposes (PHYSICS 2) -- {-dPhi.R, dPhi.U, dPhi.T}.
     *  Sign k is the way lane k's RATE GRAPH spikes AT the seam: an
     *  instant pitch-up = +inf vertical force (component 0 positive =
     *  up caret), an instant negative-direction roll step = -inf roll
     *  rate (down). The same three signs serve felt AND rate lanes in
     *  both views, and components 0/1 are the Resulting overlays'
     *  too. Null = no usable frames. */
    private double[] seamSpikes(List<RunSec> rl, int i) {
        // CONTRACT frames since v17.6 (D143 — user rule: enabling or
        // disabling smoothing must never move or hide a caret): the
        // upstream span's exit pose and the follower's effective
        // entry are the junction's SHARP states whatever the samples
        // around the seam look like (a smoothed window blends them).
        TrackCompiler.Span psp =
            spanAt(rl.get(i).piece(), rl.get(i).section());
        if (psp == null) return null;
        Quat ql = psp.exit().orient();
        Quat qr = seamEntryOrient(rl.get(i + 1).piece());
        Vec3 dphi = TrackCompiler.axisAngle(qr.mul(ql.conjugate()));
        Vec3 t = ql.T(), u = ql.U();
        Vec3 r = u.cross(t);
        // component 1 NEGATED (v18.14 — owed since D184 and doubled
        // by D185: both the felt-Gl and the yaw-rate graphs spike
        // opposite the internal U-rotation now, in both views)
        return new double[]{ -dphi.dot(r), -dphi.dot(u), dphi.dot(t) };
    }

    /** One placed seam caret (D249): its y on the line, direction,
     *  color and the small (sub-threshold) form. */
    private record CaretSpec(double y, boolean up, Color col, boolean small) { }

    /** The strict seam's carets as PLACED (v19.20, D249 — factored
     *  out of drawSeamCarets so the smoothing diamond can dodge them
     *  too): the LANES carets + the enabled resulting overlays', with
     *  the same caretY/upR/dnR rung arithmetic and no drawing. Empty
     *  before the true arrays exist or without a seam spike. */
    private List<CaretSpec> seamCarets(List<RunSec> rl, int i, double bx) {
        List<CaretSpec> out = new ArrayList<>();
        if (!trueArraysReady()) return out;
        double[] spike = seamSpikes(rl, i);
        if (spike == null) return out;
        // stacks at the LINE'S edges (v17.4, D141 — user spec: fixed
        // positions signaling DIRECTION only, never riding the
        // curves: up-spikes near the top edge, down-spikes near the
        // bottom)
        // every lane's caret ALWAYS draws (v17.5, D142 — user spec);
        // components under the CARET_KINK_MIN threshold draw SMALLER
        // and thinner (v17.7, D144 — was translucent). Carets dodge
        // nodes rung by rung like the '+' button, but always stay as
        // close to their OWN edge (top for up-spikes, bottom for
        // down) as the clearance allows.
        int[] upR = { 0 }, dnR = { 0 };
        for (int L = 0; L < LANES; L++) {
            boolean up = spike[L] > 0;
            out.add(new CaretSpec(caretY(bx, up, upR, dnR), up,
                laneColor(L), Math.abs(spike[L]) < CARET_KINK_MIN));
        }
        // enabled Resulting overlays: their components, their palette
        // (NOT the roll/bank overlay, v17.9 D146 — its spike is the
        // roll lane's own caret already)
        // ...and not the ABSOLUTE elevation/heading overlays either
        // (v19.9, D238 — angles are continuous across a rate-kink
        // seam; the comp-3 abs-bank exclusion's twin)
        if (resAxisOn && !resYpAbsOn()) {
            for (int comp = 1; comp <= 2; comp++) {
                if (!resCompOn(comp)) continue;
                boolean up = spike[comp - 1] > 0;
                out.add(new CaretSpec(caretY(bx, up, upR, dnR), up,
                    overlayColor(comp),
                    Math.abs(spike[comp - 1]) < CARET_KINK_MIN));
            }
        }
        return out;
    }

    /** Per-lane SPIKE CARETS on a strict seam (v17.2 D138, re-spec'd
     *  v17.3 D140): a chevron DIRECTLY on the seam line, per lane
     *  whose rate graph would spike there, pointing the spike's sign
     *  (seamSpikes), at the lane curve's own height. Enabled
     *  Resulting overlays wear theirs on the overlay scale in the
     *  overlay palette. Smoothing never moves or hides a caret (the
     *  D143 contract frames — seamSpikes). Placement = seamCarets
     *  (D249); this only draws. */
    private void drawSeamCarets(GraphicsContext gc, List<RunSec> rl,
                                int i, double bx) {
        for (CaretSpec c : seamCarets(rl, i, bx)) {
            drawCaret(gc, bx, c.y(), c.up(), c.col(), c.small());
        }
    }

    /** The next caret rung for a direction: from the line's own edge
     *  inward, skipping rungs a node sits on (v17.7, D144 — the
     *  '+'-button dodge, but edge-anchored so a caret stays as far
     *  from the middle as possible the way it points). */
    private double caretY(double bx, boolean up, int[] upR, int[] dnR) {
        int[] r = up ? upR : dnR;
        double top = MARGIN_TOP + 10, bot = MARGIN_TOP + plotHeight() - 10;
        while (true) {
            double y = up ? top + 12 * r[0] : bot - 12 * r[0];
            r[0]++;
            if (y < top || y > bot) return up ? bot : top;   // overran
            if (nearestNodePx(bx, y) >= 14) return y;
        }
    }

    private static void drawCaret(GraphicsContext gc, double cx,
                                  double cy, boolean up, Color col,
                                  boolean small) {
        double w = small ? 3 : 4.5;
        double h = (small ? 2.5 : 4) * (up ? 1 : -1);
        gc.setGlobalAlpha(0.95);
        gc.setLineWidth(small ? 1.2 : 2);
        gc.setStroke(col);
        gc.strokePolyline(
            new double[]{ cx - w, cx, cx + w },
            new double[]{ cy + h, cy - h, cy + h }, 3);
        gc.setGlobalAlpha(1.0);
        gc.setLineWidth(1);
    }

    /** drawCaret's sideways twin (v19.8, D237 — the seam diamond's
     *  hover drag affordance): a small ◀/▶ chevron. Caller sets
     *  stroke/alpha/width. */
    private static void drawCaretH(GraphicsContext gc, double cx,
                                   double cy, boolean left) {
        double w = 3;
        double h = 2.5 * (left ? 1 : -1);
        gc.strokePolyline(
            new double[]{ cx + h, cx - h, cx + h },
            new double[]{ cy - w, cy, cy + w }, 3);
    }

    // ---------- strict-seam SMOOTHING widget (v17.3, D140 -- the s/c
    // diamond pattern: drag the diamond ON the seam to size the
    // geometric blend; Ctrl snaps by the element-length step; under
    // 0.5 m it snaps shut; the shaded band is the blend window) -----

    /** The diamond's vertical clearance from a seam caret (px): its
     *  7 px hit radius + the caret's 4.5 px half-height (D249). */
    static final double SEAM_DIA_CARET_CLEAR = 12;

    /** The diamond's y on a seam line: the s/c diamonds' height,
     *  walked down the '+'-button ladder off any node it overlaps
     *  (v17.5, D142 — the same dodge rule; draw and hit share it) —
     *  and off the seam's CARETS since v19.20 (D249: the first rung
     *  MARGIN_TOP+48 sat on an up-caret's third rung, and node-dodged
     *  carets landed anywhere): pass 1 = the first rung ≥ 22 px from
     *  every node AND ≥ SEAM_DIA_CARET_CLEAR from every caret; pass 2
     *  = the node-clear rung with the largest caret clearance; pass 3
     *  = the historic best-node-distance fallback — nodes keep
     *  priority, caret overlap is the accepted last resort. With no
     *  carets pass 1 is the D142 walk bitwise. */
    private double seamDiaYAt(List<RunSec> rl, int i, double bx) {
        double[] ladder = { MARGIN_TOP + 48,
                            MARGIN_TOP + plotHeight() * 0.30,
                            MARGIN_TOP + plotHeight() * 0.50,
                            MARGIN_TOP + plotHeight() * 0.70,
                            MARGIN_TOP + plotHeight() - 16 };
        List<CaretSpec> carets = seamCarets(rl, i, bx);
        double best = ladder[0], bestD = -1;
        double best2 = Double.NaN, best2C = -1;
        for (double cand : ladder) {
            double d = nearestNodePx(bx, cand);
            if (d >= 22) {
                double c = Double.POSITIVE_INFINITY;
                for (CaretSpec cs : carets) {
                    c = Math.min(c, Math.abs(cand - cs.y()));
                }
                if (c >= SEAM_DIA_CARET_CLEAR) return cand;   // pass 1
                if (c > best2C) { best2C = c; best2 = cand; }
            }
            if (d > bestD) { bestD = d; best = cand; }
        }
        if (!Double.isNaN(best2)) return best2;   // pass 2
        return best;                              // pass 3
    }
    private int seamDragPiece = -1;    // follower piece being sized
    private double seamDragAxisX;      // the seam's axis coordinate
    private boolean seamDragRel;       // diamond = relative (v17.5)
    private int seamDragSide;          // edge drag: -1 left / +1 right (D248)
    private double seamDragBase;       // length at the press
    private double seamDragPressS;     // arc at the press
    private double seamPressX, seamPressY;   // screen press (v17.17:
                                       // clean click = open settings)

    /** Which strict seam's smoothing diamond is under (x, y); -1 =
     *  none. Since v19.8 (D237) DISABLED seams hit too — dragging
     *  the hollow diamond ENABLES smoothing at the dragged length —
     *  except on s/c edges, where smoothing is banned (D232). */
    private int hitSeamDiamond(double x, double y) {
        List<RunSec> rl = runLayout();
        for (int i = 0; i < rl.size() - 1; i++) {
            if (!seamStrict(rl, i)) continue;
            if (app.coaster.pieces.get(rl.get(i + 1).piece())
                        .sSmoothLen <= 0.01
                    && seamScEdge(rl.get(i + 1).piece())) {
                continue;   // the menu's grayed row explains
            }
            double bx = tToX(rl.get(i).t0() + rl.get(i).dur());
            if (Math.hypot(x - bx, y - seamDiaYAt(rl, i, bx)) <= 7) {
                return i;
            }
        }
        return -1;
    }

    /** Is the strict seam INTO followerPiece an s/c edge (either side
     *  a StraightCurveSection)? Smoothing cannot be enabled there
     *  (D232) — factored from the seam menu in v19.8. */
    private boolean seamScEdge(int followerPiece) {
        if (followerPiece <= 0
                || followerPiece >= app.coaster.pieces.size()) {
            return false;
        }
        List<Section> aSecs =
            app.coaster.pieces.get(followerPiece - 1).sections;
        List<Section> bSecs =
            app.coaster.pieces.get(followerPiece).sections;
        return (!aSecs.isEmpty()
                && aSecs.get(aSecs.size() - 1)
                    instanceof StraightCurveSection)
            || (!bSecs.isEmpty()
                && bSecs.get(0) instanceof StraightCurveSection);
    }

    /** A strict seam's blend-band EDGE within grab range (v17.4,
     *  D141); −1 = none. Deliberately the LOWEST interactive rank —
     *  nodes, knobs, menus, the seam line and the curve insert all
     *  win first. */
    private int seamEdgeHit(double x, double y) {
        if (y < MARGIN_TOP || y > MARGIN_TOP + plotHeight()) return -1;
        List<RunSec> rl = runLayout();
        for (int i = 0; i < rl.size() - 1; i++) {
            if (!seamStrict(rl, i)) continue;
            int[] w = seamWindow(rl, i);
            if (w == null) continue;
            if (Math.abs(x - tToX(gAxis(w[0]))) <= 4
                    || Math.abs(x - tToX(gAxis(w[1]))) <= 4) return i;
        }
        return -1;
    }

    /** Press on a blend-band edge: an ABSOLUTE sizing drag of the
     *  grabbed side (v19.19, D248 — one-sided: the window is twice
     *  the arc from the seam to the cursor on that side, and 0 once
     *  the cursor crosses the seam; the diamond keeps its relative
     *  drag). */
    private boolean seamEdgePressed(MouseEvent e) {
        int i = seamEdgeHit(e.getX(), e.getY());
        if (i < 0) return false;
        List<RunSec> rl = runLayout();
        beginGesture();
        seamDragPiece = rl.get(i + 1).piece();
        seamDragAxisX = rl.get(i).t0() + rl.get(i).dur();
        seamDragRel = false;   // an edge drags the window absolutely
        // which edge was grabbed (v19.19, D248): the drag is ONE-SIDED
        // — past the seam the window is off and stays off
        seamDragSide = e.getX() < tToX(seamDragAxisX) ? -1 : 1;
        return true;
    }

    /** An s/c blend-band EDGE within grab range (v17.4): the element
     *  whose entry blend it belongs to; 0 = the ENTRY-ease band's
     *  far edge (v19.5, D234 — the strict-seam pattern: the band is
     *  grabbable, not just its diamond); −1 = none. */
    private int scEdgeHit(double x, double y) {
        StraightCurveSection sc = activeSc();
        if (sc == null || y < MARGIN_TOP
                || y > MARGIN_TOP + plotHeight()) return -1;
        double[] b = scBoundsS(sc);
        ScIntegrator.Profile prof = new ScIntegrator.Profile(sc);
        for (int j = 1; j < b.length - 1; j++) {
            if (prof.half[j] <= 1e-9) continue;
            if (Math.abs(x - tToX(scAxisAtS(sc, b[j] - prof.half[j]))) <= 4
                    || Math.abs(x
                        - tToX(scAxisAtS(sc, b[j] + prof.half[j]))) <= 4) {
                return j;
            }
        }
        // the entry-ease band ends at s = ease; its edge drags the
        // ease absolutely (the same elDragSmooth == 0 branch as the
        // entry diamond)
        if (!sc.elements.isEmpty()) {
            double ease = Math.min(sc.elements.get(0).smoothIn,
                sc.elements.get(0).arcLen());
            if (ease > 1e-9
                    && Math.abs(x - tToX(scAxisAtS(sc, ease))) <= 4) {
                return 0;
            }
        }
        return -1;
    }

    /** Press on a smoothing diamond: begin the sizing drag (one undo
     *  step per gesture, the s/c contract). RELATIVE since v17.5
     *  (D142): the drag ADJUSTS the current length from the press
     *  point — rightward grows, leftward shrinks — instead of
     *  restarting from zero at the seam. A clean CLICK (≤ 3 px press
     *  to release) opens the seam settings instead — SINGLE-click
     *  since v17.17, D158 (was double); the drag deadband in the
     *  mouse-dragged handler keeps a click from committing a
     *  micro-resize first. */
    private boolean seamDiamondPressed(MouseEvent e) {
        int i = hitSeamDiamond(e.getX(), e.getY());
        if (i < 0) return false;
        List<RunSec> rl = runLayout();
        beginGesture();
        seamDragPiece = rl.get(i + 1).piece();
        seamDragAxisX = rl.get(i).t0() + rl.get(i).dur();
        seamDragRel = true;
        seamDragBase = app.coaster.pieces.get(seamDragPiece).sSmoothLen;
        seamDragPressS = arcAtAxis(xToT(e.getX()));
        seamPressX = e.getX();
        seamPressY = e.getY();
        return true;
    }

    /** Arc length (m) at an axis coordinate, off the compiled
     *  samples. NaN without a compile. */
    private double arcAtAxis(double ax) {
        if (app.lastSamples == null || app.lastSamples.isEmpty()) {
            return Double.NaN;
        }
        int i = sampleIdxAtAxis(ax);
        if (i < 0 || i >= app.lastSamples.size()) return Double.NaN;
        return app.lastSamples.get(i).s();
    }

    /** The running smoothing drag: window length = twice the ARC
     *  between the cursor and the seam (read off the compiled samples
     *  so it works on time AND distance axes). */
    private void seamDragTo(double x, boolean snap) {
        if (seamDragPiece < 0
                || seamDragPiece >= app.coaster.pieces.size()
                || app.lastSamples == null
                || app.lastSamples.isEmpty()) return;
        TrackPiece f = app.coaster.pieces.get(seamDragPiece);
        int ic = sampleIdxAtAxis(xToT(x));
        int is = sampleIdxAtAxis(seamDragAxisX);
        int n = app.lastSamples.size();
        if (ic < 0 || is < 0 || ic >= n || is >= n) return;
        double len;
        if (seamDragRel && !Double.isNaN(seamDragPressS)) {
            // diamond (v17.5, D142): adjust the CURRENT length —
            // dragging right grows, left shrinks
            len = seamDragBase + 2 * (app.lastSamples.get(ic).s()
                - seamDragPressS);
        } else {
            // an EDGE: twice the arc from the seam on the grabbed side;
            // crossing the seam turns smoothing OFF and the window
            // stays off however far the cursor continues (v19.19,
            // D248 — the historic |.| re-grew it on the far side)
            len = Gestures.edgeWindowLen(app.lastSamples.get(ic).s(),
                app.lastSamples.get(is).s(), seamDragSide);
        }
        if (snap) {
            double step = app.settings.snapLen();
            len = Math.round(len / step) * step;
        }
        double next = len < 0.5 ? 0 : Math.min(len, SEAM_SMOOTH_MAX);
        if (Math.abs(next - f.sSmoothLen) < 1e-9) {
            redraw();
            return;
        }
        commitGestureSnapshot();
        f.sSmoothLen = next;
        app.markDirty();
        app.rebuildTrack();
        redraw();
    }

    /** The smoothing window's sample range for drawing -- the
     *  compiler's clamps mirrored; null = no active window. */
    private int[] seamWindow(List<RunSec> rl, int i) {
        TrackPiece f = app.coaster.pieces.get(rl.get(i + 1).piece());
        if (f.sSmoothLen <= 0.01 || app.lastSamples == null
                || app.lastSamples.isEmpty()) return null;
        TrackCompiler.Span sa =
            spanAt(rl.get(i).piece(), rl.get(i).section());
        TrackCompiler.Span sb =
            spanAt(rl.get(i + 1).piece(), rl.get(i + 1).section());
        if (sa == null || sb == null) return null;
        int n = app.lastSamples.size();
        if (sa.i1() >= n || sb.i1() >= n) return null;
        double sSeam = app.lastSamples.get(sa.i1()).s();
        double hUp = Math.min(f.sSmoothLen / 2, sa.sLen() * 0.5);
        double hDn = Math.min(f.sSmoothLen / 2, sb.sLen() * 0.5);
        int iA = sa.i1();
        while (iA > sa.i0() + 1
                && sSeam - app.lastSamples.get(iA - 1).s() <= hUp) iA--;
        int iB = sa.i1() + 1;
        while (iB < sb.i1() - 1
                && app.lastSamples.get(iB + 1).s() - sSeam <= hDn) iB++;
        return new int[]{ iA, iB };
    }

    /** The shaded blend-zone band (the s/c look) — tinted RED when
     *  the smoothing itself is what opened the follower's speed
     *  conflict (v17.4, D141). */
    private void drawSeamSmoothBand(GraphicsContext gc, List<RunSec> rl,
                                    int i) {
        int[] w = seamWindow(rl, i);
        if (w == null) return;
        double xa = tToX(gAxis(w[0])), xb = tToX(gAxis(w[1]));
        gc.setFill(smoothingCausedDrift(rl.get(i + 1).piece())
            ? Color.rgb(255, 70, 70, 0.12)
            : Color.rgb(255, 255, 255, 0.07));
        gc.fillRect(Math.min(xa, xb), MARGIN_TOP,
            Math.abs(xb - xa), plotHeight());
    }

    // smoothing-caused-drift cache: one smoothing-suspended compile
    // answers it, re-used until the next rebuild (keyed on the sample
    // list identity + the window length)
    private Object smcGen;
    private int smcPiece = -1;
    private double smcLen;
    private boolean smcVal;

    /** The seam smoothing DRAG cap (m) — 100 since v19.20 (D249,
     *  user spec; 80 from v17.3/D140). The loader admits 0..200 (a
     *  bigger loaded value drags down to this on the next drag only);
     *  the compiler's own halves clamp (half a neighbor section) is
     *  the geometric limit. */
    static final double SEAM_SMOOTH_MAX = 100;

    /** Is piece p's ≠v caused ONLY by its seam smoothing (v17.4,
     *  D141)? True when the follower's first section drifts as
     *  compiled but would NOT drift with the window at zero — the
     *  badge menu then says to shrink the window instead of offering
     *  the resolve ops, and the band tints red. */
    private boolean smoothingCausedDrift(int p) {
        if (p < 0 || p >= app.coaster.pieces.size()) return false;
        TrackPiece f = app.coaster.pieces.get(p);
        if (!followsPrev(p) || f.sSmoothLen <= 0.01
                || !sectionDrifted(p, 0)) return false;
        if (smcGen == app.lastSamples && smcPiece == p
                && smcLen == f.sSmoothLen) return smcVal;
        smcGen = app.lastSamples;
        smcPiece = p;
        smcLen = f.sSmoothLen;
        double stash = f.sSmoothLen;
        f.sSmoothLen = 0;
        TrackCompiler.Result r;
        try {
            r = TrackCompiler.compile(app.coaster,
                FvdDesigner.INTEGRATE_DT);
        } finally {
            f.sSmoothLen = stash;
        }
        int spanIdx = 0;
        for (int q = 0; q < p; q++) {
            spanIdx += app.coaster.pieces.get(q).sections.size();
        }
        // ENTRY sample only since v19.18 (D247 — the badge's ≠v is
        // entry-only now, and this measure must agree with it so the
        // red band tint and the menu hint never disagree with the
        // glyph); same cache keys, the true read floored like
        // entrySpeeds
        double split = 0;
        if (spanIdx < r.spans().size()) {
            TrackCompiler.Span sp = r.spans().get(spanIdx);
            int i0 = sp.i0();
            if (i0 < r.trueV().length && i0 < r.samples().size()) {
                split = Math.abs(Math.max(0.1, r.trueV()[i0])
                    - r.samples().get(i0).v());
            }
        }
        smcVal = split <= DRIFT_EPS;
        return smcVal;
    }

    /** The diamond itself (filled = smoothing on, HOLLOW while off —
     *  always drawn on strict seams since v19.8, D237, user spec: a
     *  drag from the hollow diamond ENABLES smoothing; hover shows a
     *  gray center dot while off plus small side carets both ways)
     *  + the live readout while its drag runs. */
    private void drawSeamDiamond(GraphicsContext gc, List<RunSec> rl,
                                 int i, double bx) {
        TrackPiece f = app.coaster.pieces.get(rl.get(i + 1).piece());
        // the one seam that still hides it: an s/c edge, where
        // smoothing cannot be enabled at all (D232)
        if (f.sSmoothLen <= 0.01 && seamScEdge(rl.get(i + 1).piece())) {
            return;
        }
        double dy = seamDiaYAt(rl, i, bx);
        boolean hov = i == hoverSeamDia
            && seamDragPiece != rl.get(i + 1).piece();
        gc.setGlobalAlpha(0.85);
        gc.beginPath();
        gc.moveTo(bx, dy - 5);
        gc.lineTo(bx + 5, dy);
        gc.lineTo(bx, dy + 5);
        gc.lineTo(bx - 5, dy);
        gc.closePath();
        if (f.sSmoothLen > 0.01) {
            gc.setFill(Color.WHITE);
            gc.fill();
        } else if (hov) {
            // gray center in the hollow diamond: "clickable" (D237)
            gc.setFill(Color.rgb(150, 156, 175));
            gc.fillOval(bx - 2, dy - 2, 4, 4);
        }
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1);
        gc.stroke();
        if (hov) {
            // drag affordance: ◀ ▶ beside the diamond (both states)
            gc.setGlobalAlpha(0.7);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.2);
            drawCaretH(gc, bx - 11, dy, true);
            drawCaretH(gc, bx + 11, dy, false);
        }
        gc.setGlobalAlpha(1.0);
        if (seamDragPiece == rl.get(i + 1).piece()) {
            String txt = f.sSmoothLen < 0.01 ? "smoothing off"
                : String.format(Locale.US, "smoothing %.1f m",
                    f.sSmoothLen);
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            double tw = txt.length() * 6.5;
            gc.fillText(txt, FvdDesigner.clamp(bx + 10, MARGIN_LEFT + 4,
                canvas.getWidth() - marginRight() - tw - 4), dy + 10);
        }
    }

    /** A same-mode inactive strip: its authored lanes on the shared
     *  scales, dotted hold to the section end, zero-line fill. */
    private void drawAuthoredStrip(GraphicsContext gc, LaneSection sec,
                                   double off, double dur) {
        for (int L = 0; L < LANES; L++) {
            List<double[]> poly = BezierBaker.polyline(sec.lanes.get(L));
            List<double[]> pts = new ArrayList<>(poly.size() + 1);
            for (double[] p : poly) {
                pts.add(new double[]{ tToX(off + p[0]), valToY(L, p[1]) });
            }
            double[] lastP = poly.get(poly.size() - 1);
            boolean held = lastP[0] < dur - 1e-9;
            if (held) {   // fill runs under the hold too
                pts.add(new double[]{ tToX(off + dur), valToY(L, lastP[1]) });
            }
            fillUnderSeries(gc, pts, L);
            gc.setStroke(laneColor(L));
            gc.beginPath();
            int n = poly.size();   // stroke the authored part solid...
            for (int i = 0; i < n; i++) {
                double[] p = pts.get(i);
                if (i == 0) gc.moveTo(p[0], p[1]); else gc.lineTo(p[0], p[1]);
            }
            gc.stroke();
            if (held) {            // ...and the hold dotted, as ever
                gc.setLineDashes(3, 7);
                gc.strokeLine(pts.get(n - 1)[0], pts.get(n - 1)[1],
                              pts.get(n)[0], pts.get(n)[1]);
                gc.setLineDashes((double[]) null);
            }
        }
    }

    // bodyRates' central differences smear a section-boundary jump over
    // ±2 samples; derived curves trim that band off their ends so seams
    // read as direct connections, not tiny angled ramps (v10.2)
    // 0.6·dt since v18.35 (D207 — was 2.5·dt): rates difference per
    // SECTION SPAN now, so seam-adjacent samples are clean and the
    // band only keeps hover readouts/scale fits off the boundary
    // sample's edge. The DRAWN curves no longer trim at all — they
    // run to the seam and break exactly ON it (seam-crossing breaks).
    private static final double SEAM_TRIM = 0.6 * FvdDesigner.INTEGRATE_DT;

    /** First/last compiled-sample indices whose axis coordinates can
     *  fall inside [a0, a1], padded two samples past each edge so the
     *  polyline still runs off the plot border (v18.36, D208 — the
     *  draw loops used to walk the WHOLE sample array per curve). */
    private int[] axisIdxRange(double a0, double a1) {
        int n = app.resultRates.length;
        return new int[]{ Math.max(0, sampleIdxAtAxis(a0) - 2),
                          Math.min(n - 1, sampleIdxAtAxis(a1) + 2) };
    }

    /** Walks the seam list alongside an ascending-axis sample loop:
     *  crossed(a) reports each boundary passed — the caller breaks
     *  its path there, so curves end ON the seam sample and resume
     *  at the next section's first sample with no trimmed band
     *  (v18.35, D207). The 1e-6 slack keeps the boundary sample
     *  itself (its coordinate IS the seam) on the LEFT side
     *  whatever the last-bit dust says. */
    private static final class SeamWalk {
        private final List<Double> seams;
        private int j;
        SeamWalk(List<Double> seams, double a) {
            this.seams = seams;
            while (j < seams.size() && seams.get(j) < a - 1e-6) j++;
        }
        boolean crossed(double a) {
            boolean c = false;
            while (j < seams.size() && a > seams.get(j) + 1e-6) {
                j++;
                c = true;
            }
            return c;
        }
    }

    /** Column-compacted polyline writer (v18.36, D208): consecutive
     *  points landing in one ~pixel column collapse to enter/min/max/
     *  exit, so the stroked coverage is identical while the path
     *  stays O(pixels) however many samples the view holds — the
     *  zoomed-out redraw lag. Points arrive in ascending x within a
     *  run; endRun() breaks the polyline (next add() starts a new
     *  moveTo). At normal zoom (≤1 point per column) every point
     *  passes through verbatim. */
    private static final class ColPath {
        private final GraphicsContext gc;
        private boolean run, pend;
        private double col, x0, yEnter, yMin, yMax, yExit;
        ColPath(GraphicsContext gc) {
            this.gc = gc;
            gc.beginPath();
        }
        void add(double x, double y) {
            double c = Math.floor(x);
            if (pend && c == col) {
                if (y < yMin) yMin = y;
                if (y > yMax) yMax = y;
                yExit = y;
                return;
            }
            flush();
            col = c;
            x0 = x;
            yEnter = yMin = yMax = yExit = y;
            pend = true;
        }
        private void flush() {
            if (!pend) return;
            if (!run) {
                gc.moveTo(x0, yEnter);
                run = true;
            } else {
                gc.lineTo(x0, yEnter);
            }
            if (yMax - yMin > 0.01) {
                gc.lineTo(x0, yMin);
                gc.lineTo(x0, yMax);
            }
            if (Math.abs(yExit - yMax) > 0.01) gc.lineTo(x0, yExit);
            pend = false;
        }
        void endRun() {
            flush();
            run = false;
        }
        void stroke() {
            flush();
            gc.stroke();
        }
    }

    /** compactCols: the ColPath bucketing for point LISTS that feed
     *  a fill AND a stroke (drawDerivedStrip) — ≤4 points per pixel
     *  column, coverage identical. */
    private static List<double[]> compactCols(List<double[]> pts) {
        if (pts.size() < 8) return pts;
        List<double[]> out = new ArrayList<>();
        int i = 0;
        while (i < pts.size()) {
            double col = Math.floor(pts.get(i)[0]);
            double[] first = pts.get(i);
            double[] last = first;
            double minY = first[1], maxY = first[1];
            int j = i;
            while (j + 1 < pts.size()
                    && Math.floor(pts.get(j + 1)[0]) == col) {
                j++;
                last = pts.get(j);
                if (last[1] < minY) minY = last[1];
                if (last[1] > maxY) maxY = last[1];
            }
            out.add(first);
            if (j > i) {
                if (maxY - minY > 0.01) {
                    out.add(new double[]{ first[0], minY });
                    out.add(new double[]{ first[0], maxY });
                }
                if (last != first) out.add(last);
            }
            i = j + 1;
        }
        return out;
    }

    /** [lo, hi] index window for slope differencing around sample
     *  ic, clamped to ic's own section span (v18.35, D207 — rates
     *  are span-clean now, and a ±radius window reaching across a
     *  seam would difference two different sections' curves). */
    private int[] slopeWin(int ic, int radius) {
        int n = app.resultRates.length;
        int lo = Math.max(0, ic - radius);
        int hi = Math.min(n - 1, ic + radius);
        if (app.sectionSpans != null) {
            for (TrackCompiler.Span sp : app.sectionSpans) {
                if (ic >= sp.i0() && ic <= sp.i1()) {
                    lo = Math.max(lo, sp.i0());
                    hi = Math.min(hi, Math.min(sp.i1(), n - 1));
                    break;
                }
            }
        }
        return new int[]{ lo, hi };
    }

    /** An other-mode (or other-domain, v16.0) inactive strip: the
     *  active mode's quantities over its stretch, straight off the
     *  compiled samples — plotted at each sample's AXIS coordinate, so
     *  the warp between domains falls out for free. off/dur are axis
     *  units. */
    private void drawDerivedStrip(GraphicsContext gc, double off, double dur,
                                  TrackCompiler.Span sp) {
        if (app.resultRates == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return;
        // Off-screen strips skip outright, on-screen ones walk their
        // OWN span's index range (v18.36, D208 — every strip used to
        // scan the whole sample array per lane), and the curves run
        // to the strip's seams with no trim band (v18.35, D207 —
        // span-differenced rates are seam-clean).
        double vt0 = viewStart(), vt1 = viewEnd();
        if (off > vt1 || off + dur < vt0) return;
        int n = app.resultRates.length;
        int[] vr = axisIdxRange(Math.max(off, vt0), Math.min(off + dur, vt1));
        int i0 = vr[0], i1 = vr[1];
        if (sp != null) {
            i0 = Math.max(i0, sp.i0());
            i1 = Math.min(i1, Math.min(sp.i1(), n - 1));
        }
        for (int L = 0; L < LANES; L++) {
            List<double[]> pts = new ArrayList<>();
            for (int i = i0; i <= i1; i++) {
                double a = gAxis(i);
                // spanless callers keep the stretch filter (trim-free)
                if (sp == null && (a < off - 1e-6 || a > off + dur + 1e-6)) {
                    continue;
                }
                pts.add(new double[]{ tToX(a), valToY(L, derivedDisp(i, L)) });
            }
            if (pts.size() < 2) continue;
            List<double[]> cp = compactCols(pts);
            fillUnderSeries(gc, cp, L);
            gc.setStroke(laneColor(L));
            gc.beginPath();
            for (int i = 0; i < cp.size(); i++) {
                double[] p = cp.get(i);
                if (i == 0) gc.moveTo(p[0], p[1]); else gc.lineTo(p[0], p[1]);
            }
            gc.stroke();
        }
    }

    /** derivedAt in DISPLAYED units — the cross-rate view flips a
     *  warped lane per sample (v16.9, D121). Drawing + hover only:
     *  B-matching reads the NATIVE derivedAt/trueAt directly (a
     *  session view must never change what B writes into lanes). */
    private double derivedDisp(int i, int L) {
        if (!laneWarped(L)) return derivedAt(i, L);
        double v = Math.max(0.5, app.lastSamples.get(i).v());
        return derivedAt(i, L) * (activeRatePM(L) ? v : 1 / v);
    }

    /** trueAt in DISPLAYED units (the flip converts at the TRUE
     *  speed — a °/m read of the true ride divides by trueV). */
    private double trueDisp(int i, int L) {
        if (!laneWarped(L)) return trueAt(i, L);
        double v = app.trueV != null && i < app.trueV.length
            ? Math.max(0.5, app.trueV[i])
            : Math.max(0.5, app.lastSamples.get(i).v());
        return trueAt(i, L) * (activeRatePM(L) ? v : 1 / v);
    }

    /** The active mode's lane-L value at compiled sample i (unified
     *  strips + cross-mode boundary matching). Geo view: resulting
     *  pitch/yaw/roll rates. Force view: felt Gv/Gl (the geo
     *  integrator records them at design speed) + resulting roll. */
    private double derivedAt(int i, int L) {
        // an ABSOLUTE roll axis shows every strip's BANK ANGLE (°) —
        // the axis quantity, not a rate (v16.7, D118; vertical = 0)
        if (L == 2 && activeRollAbs()) {
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
            return Double.isNaN(b) ? 0 : Math.toDegrees(b);
        }
        if (L < 2 && activeYpAbs()) {
            // absolute yaw/pitch view (v19.8, D237): the ANGLE at
            // the sample — elevation (L 0) / heading (L 1,
            // PIECE-relative principal since v19.9, D238)
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            if (L == 0) {
                return Math.toDegrees(
                    FVDIntegrator.elevationOfT(sm.T()));
            }
            return pieceRelHeadingDeg(sm);
        }
        // a WORLD-axis active lane shows every strip in ITS quantity
        // (v18.9, user report: a smooth ypG seam read as a visual
        // step against derived BODY rates): elevation'/heading' for
        // yaw-pitch-global lanes 0/1, bank rate for effective-global
        // roll — NaN near vertical draws 0, the abs precedent
        if (activeLaneWorldAxis(L)) {
            double[] wr = Coaster.worldRates(app.lastSamples.get(i),
                app.resultRates[i][1], app.resultRates[i][2],
                app.resultRates[i][3]);
            double sc = activeRatePM(L)
                ? 1 / Math.max(0.5, app.lastSamples.get(i).v()) : 1;
            return (Double.isNaN(wr[L]) ? 0 : wr[L]) * sc;
        }
        // ACTIVE per-meter lanes show every strip in °/m (v16.6): the
        // compiled rates are °/s — divide by that sample's own speed
        double sc = activeRatePM(L)
            ? 1 / Math.max(0.5, app.lastSamples.get(i).v()) : 1;
        if (activeGeo()) return app.resultRates[i][L + 1] * sc;
        if (L == 0) return app.lastSamples.get(i).Gv();
        if (L == 1) return app.lastSamples.get(i).Gl();
        return app.resultRates[i][3] * sc;
    }

    // ---------- TRUE-speed display (round A, SECTIONS-DESIGN §5) ----
    // design-vs-true drift made visible: wherever the Pass-A true
    // curve visibly leaves the design curve, a THIN solid companion
    // stroke draws in the lane color — the strokes splitting IS the
    // drift indicator, and they rejoin when a seam is reconciled.

    /** Entry drift (m/s) worth flagging on a badge. */
    static final double DRIFT_EPS = 0.2;
    /** Pixel gap where the true stroke starts drawing. */
    private static final double TRUE_SPLIT_PX = 1.5;
    private static final double TRUE_STROKE_ALPHA = 0.85;
    // s/c + bridge strips have no active lane: all three true strokes
    // draw at a MIDDLE weight — between a lane strip's bold selected
    // stroke and its faint unselected ones (v16.6, user spec; they
    // used to draw all-bold — the v16.2 dimming never reached them)
    private static final double TRUE_STROKE_MID = 0.55;

    /** derivedAt's sibling on the TRUE arrays: the active mode's
     *  lane-L value at compiled sample i, at the Pass-D true speed. */
    private double trueAt(int i, int L) {
        if (L == 2 && activeRollAbs()) {
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
            return Double.isNaN(b) ? 0 : Math.toDegrees(b);
        }
        if (L < 2 && activeYpAbs()) {
            // absolute yaw/pitch view (v19.8, D237): the ANGLE at
            // the sample — elevation (L 0) / heading (L 1,
            // PIECE-relative principal since v19.9, D238)
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            if (L == 0) {
                return Math.toDegrees(
                    FVDIntegrator.elevationOfT(sm.T()));
            }
            return pieceRelHeadingDeg(sm);
        }
        // world-axis active lanes: the TRUE curve in the same world
        // quantities (v18.9 — derivedAt's sibling branch)
        if (activeLaneWorldAxis(L)) {
            double[] wr = Coaster.worldRates(app.lastSamples.get(i),
                app.trueRates[i][1], app.trueRates[i][2],
                app.trueRates[i][3]);
            double sc = activeRatePM(L) && app.trueV != null
                ? 1 / Math.max(0.5, app.trueV[i]) : 1;
            return (Double.isNaN(wr[L]) ? 0 : wr[L]) * sc;
        }
        double sc = activeRatePM(L) && app.trueV != null
            ? 1 / Math.max(0.5, app.trueV[i]) : 1;
        if (activeGeo()) return app.trueRates[i][L + 1] * sc;
        if (L == 0) return app.trueFelt[i][0];
        if (L == 1) return app.trueFelt[i][1];
        return app.trueRates[i][3] * sc;
    }

    /** Does the ACTIVE editing surface author lane L in WORLD-axis
     *  semantics (v18.9)? Drives derivedAt/trueAt so inactive strips
     *  draw the quantity the active lane authors. Off on the strip
     *  and influence surfaces (their lanes aren't the section's) and
     *  for the abs-bank case (handled first — it shows the ANGLE). */
    private boolean activeLaneWorldAxis(int L) {
        if (surfStrip || activeInfl() != null) return false;
        List<Section> secs = sections();
        if (!(activeSection < secs.size()
                && secs.get(activeSection) instanceof LaneSection ls)) {
            return false;
        }
        // lanes 0/1 are world only in the GEO view; roll is the two
        // modes' shared lane — its effective semantics apply in both
        if (L < 2 && !activeGeo()) return false;
        return laneWorld(ls, L);
    }

    /** derivedAt/trueAt's sibling for the SAMPLED boundary path, in
     *  PER-SECOND rate semantics — boundaryStatePerSec's contract
     *  (its wrapper alone converts °/s → our °/m). The display
     *  helpers pre-scale rates by 1/v for an active per-meter lane,
     *  and routing them through the wrapper divided by the seam
     *  speed TWICE — every sampled-path B/Shift+B target on a °/m
     *  lane (global-axis, reversed, influenced, s/c and bridge
     *  neighbors, real-split seams) collapsed to ~0 value + flat
     *  slope (v16.10: the user's geo pitch node "went to zero"
     *  against a global-roll force neighbor). */
    private double sampledPerSec(int i, int L, boolean real) {
        if (L == 2 && activeRollAbs()) {
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
            return Double.isNaN(b) ? 0 : Math.toDegrees(b);
        }
        if (L < 2 && activeYpAbs()) {
            // absolute yaw/pitch view (v19.8, D237): the ANGLE at
            // the sample — elevation (L 0) / heading (L 1,
            // PIECE-relative principal since v19.9, D238)
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            if (L == 0) {
                return Math.toDegrees(
                    FVDIntegrator.elevationOfT(sm.T()));
            }
            return pieceRelHeadingDeg(sm);
        }
        if (activeGeo()) return (real ? app.trueRates
                                      : app.resultRates)[i][L + 1];
        if (L == 0) return real ? app.trueFelt[i][0]
                                : app.lastSamples.get(i).Gv();
        if (L == 1) return real ? app.trueFelt[i][1]
                                : app.lastSamples.get(i).Gl();
        return (real ? app.trueRates : app.resultRates)[i][3];
    }

    private boolean trueArraysReady() {
        return app.trueFelt != null && app.trueRates != null
            && app.resultRates != null && app.lastSamples != null
            && !app.lastSamples.isEmpty()
            && app.trueFelt.length == app.lastSamples.size();
    }

    /** Max design/true SPEED split anywhere in piece p section k's
     *  span (v16.1, D106): the WHOLE-SPAN measure — a TILTED
     *  run-START piece has a matching entry speed but tilted heights
     *  everywhere after it. Since v19.18 (D247) the BADGE's ≠v no
     *  longer reads this (an interior-only split is an ORIENTATION
     *  conflict and wears its own ≠o glyph); the span measure stays
     *  for the span-based consumers — B's real-split fallback, which
     *  must aim at the real curves wherever they split. Callers
     *  exclude bridges (their design-side speed blend legitimately
     *  leaves the pure-energy profile mid-span). */
    private double sectionDrift(int p, int k) {
        TrackCompiler.Span sp = spanAt(p, k);
        if (sp == null || app.trueV == null || app.lastSamples == null) {
            return 0;
        }
        int i1 = Math.min(sp.i1(),
            Math.min(app.trueV.length, app.lastSamples.size()) - 1);
        double worst = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            worst = Math.max(worst,
                Math.abs(app.trueV[i] - app.lastSamples.get(i).v()));
        }
        return worst;
    }

    /** Is piece p section k speed-split ANYWHERE in its span (the
     *  D106 whole-span test)? Renamed from sectionDrifted in v19.18
     *  (D247) — the badge's ≠v is entry-only now (sectionDrifted);
     *  this span form serves B's real-split fallback. Bridges never
     *  are — their mid-span design blend is by design. */
    private boolean sectionDriftedSpan(int p, int k) {
        return !(app.coaster.pieces.get(p).sections.get(k)
                instanceof BridgeSection)
            && sectionDrift(p, k) > DRIFT_EPS;
    }

    /** SPEED CONFLICT of piece p section k (v19.18, D247 — user
     *  spec, superseding D106's whole-span test): the section ENTERS
     *  at a true speed other than its design speed — |true − design|
     *  at the span's first sample > DRIFT_EPS (entrySpeeds, the raw
     *  entry gap; the reconcile keeps its own amplified seam gate).
     *  A split that develops only INSIDE the span is no speed
     *  conflict any more — it is the piece's orientation tilting
     *  the heights, which wears the ≠o glyph (sectionOrientConflict)
     *  and resolves piece-wide. Bridges never are — their mid-span
     *  design blend is by design. */
    private boolean sectionDrifted(int p, int k) {
        if (app.coaster.pieces.get(p).sections.get(k)
                instanceof BridgeSection) return false;
        double[] es = entrySpeeds(p, k);
        return es != null && Math.abs(es[1] - es[0]) > DRIFT_EPS;
    }

    /** ORIENTATION CONFLICT of piece p section k (v19.18, D247 —
     *  the badge's " ≠o"): the piece's real orientation differs from
     *  its design orientation (orientConflictAt — a free piece's two
     *  tiers, a follower's seam tilt with a horizontal axis). Every
     *  non-bridge section of the piece wears it together, since the
     *  tilt is per piece and the resolve ops bake it piece-wide.
     *  Bridges are pure geometry and never tilt. */
    private boolean sectionOrientConflict(int p, int k) {
        return !(app.coaster.pieces.get(p).sections.get(k)
                instanceof BridgeSection)
            && orientConflictAt(p);
    }

    /** {design v, true v} at piece p section k's first compiled
     *  sample, or null before a compile. The two differing is the §5
     *  DRIFT: the section's design speed no longer matches the energy
     *  actually reaching it. */
    private double[] entrySpeeds(int p, int k) {
        TrackCompiler.Span sp = spanAt(p, k);
        if (sp == null || app.trueV == null || app.lastSamples == null
                || sp.i0() >= app.trueV.length) return null;
        // the true read floors where the array was historically
        // clamped (v18.6, D175) — seam matching and refreezes keep
        // their old near-stall behavior while the DISPLAY is honest
        return new double[]{ app.lastSamples.get(sp.i0()).v(),
                             Math.max(0.1, app.trueV[sp.i0()]) };
    }

    /** The thin TRUE companion strokes over the whole run — one per
     *  lane, drawn only where the true value visibly splits off the
     *  design curve (> TRUE_SPLIT_PX on the lane's scale). Felt
     *  components draw straight through seams (no differencing — true
     *  curves are seamless by construction, §5.0); rate-sourced ones
     *  (roll, and all lanes in geo view) trim the SEAM_TRIM band like
     *  every bodyRates consumer. */
    private void drawTrueStrokes(GraphicsContext gc) {
        if (!trueArraysReady()) return;
        List<Double> seams = sectionBoundaries();
        // clamp to the CURRENT RUN (v13.11, D85): the timeline
        // continues into detached pieces, and their samples bled in
        // at the right edge of the run's window
        double rT0 = runT0(), rT1 = rT0 + runDur();
        double vt0 = viewStart(), vt1 = viewEnd();
        // DESIGN view of an INFLUENCED section (v15.0, D98): the
        // drawn design curves are the AUTHORED lanes, which the
        // influence deliberately deviates from — the split reference
        // inside its strip is the authored curve, so the true stroke
        // shows exactly what influencing added (the user's "see the
        // laterals along with the original graph of no laterals").
        // derivedAt (== the compiled curves) equals the truth there
        // and would never split.
        // ORIENTATION-CONFLICTED piece (v15.2 D100; every conflict
        // KIND + per-lane since v19.19, D248 — see splitRefSection):
        // the drawn lanes are the authored values, which the rigid
        // tilt deviates from — same authored-reference rule as
        // influence. ONE selector (splitRef) serves this loop AND the
        // hover's trueSuffix, so the two can never disagree again.
        LaneSection refSec = splitRefSection();
        TrackCompiler.Span refSpan = refSec != null
            ? spanOf(activeSection) : null;
        boolean[] refLanes = splitRefLanes(refSec);
        // SMOOTHED STRICT SEAMS (v17.10, user spec; BOTH SIDES from
        // EITHER neighbor since v19.6, D235 — user spec): inside a
        // smoothing window the drawn curves are the authored lanes
        // while the compiled ride is the compiler's geometric blend
        // — the same authored-reference rule as influence/tilt
        // above. When the active section touches a smoothed seam,
        // the true stroke now draws the blended curve across the
        // WHOLE window — the neighbor's half included (it used to
        // clip to the active span, so each side only ever showed
        // its own half). The neighbor's half needs matching units
        // (same mode + axis flags) to read its authored lanes.
        // Computed whenever the INFLUENCE surface is down (D248 —
        // it used to be suppressed while the tilt branch fired, so
        // a conflicted follower with a smoothed seam lost the
        // neighbor half's reference; smoothedWindowParts is already
        // null for an influenced active section).
        List<Object[]> smParts = activeInfl() == null
            ? smoothedWindowParts() : null;
        gc.setLineWidth(1);
        // the ACTIVE lane's true stroke draws at full strength; the
        // other lanes' fade like their design curves do (v16.2, user
        // rule — full-strength strokes on unselected lanes cluttered
        // the plot). No lane surface (s/c, bridge) = no active lane:
        // all three at the MIDDLE weight (v16.6, user spec).
        boolean laneSurface = activeSc() == null && activeBridge() == null;
        // view-culled index range (v18.36, D208) — the loop used to
        // walk the whole array per lane
        if (app.resultRates.length == 0) return;
        int[] ir = axisIdxRange(Math.max(vt0, rT0), Math.min(vt1, rT1));
        for (int L = 0; L < LANES; L++) {
            gc.setGlobalAlpha(!laneSurface
                ? TRUE_STROKE_ALPHA * TRUE_STROKE_MID
                : L != activeLane
                    ? TRUE_STROKE_ALPHA * INACTIVE_ALPHA
                    : TRUE_STROKE_ALPHA);
            boolean rateSrc = activeGeo() || L == 2;
            gc.setStroke(laneColor(L));
            ColPath path = new ColPath(gc);
            SeamWalk sw = new SeamWalk(seams, gAxis(ir[0]));
            for (int i = ir[0]; i <= ir[1]; i++) {
                double a = gAxis(i);          // axis coordinate (v16.0)
                // rate-sourced strokes break exactly ON seams now
                // (v18.35, D207 — span-clean rates, no trim band);
                // felt components stay seamless by construction
                if (rateSrc && sw.crossed(a)) path.endRun();
                if (a < rT0 - 1e-6 || a > rT1 + 1e-6) {
                    path.endRun();
                    continue;
                }
                double ref = splitRef(i, L, smParts, refSec, refSpan,
                    refLanes);
                double dy = valToY(L, ref);
                double ty = valToY(L, trueDisp(i, L));
                if (Math.abs(ty - dy) <= TRUE_SPLIT_PX) {
                    path.endRun();
                    continue;
                }
                path.add(tToX(a), ty);
            }
            path.stroke();
        }
        gc.setGlobalAlpha(1.0);
        gc.setLineWidth(2);
    }

    /** The ACTIVE section whose AUTHORED lanes are the true stroke's
     *  split reference inside its own span (v19.19, D248 — the one
     *  place the choice is made, shared by drawTrueStrokes and the
     *  hover's trueSuffix): a non-reversed LaneSection, off the
     *  influence surface, that is EITHER influenced (the D98 design
     *  view — the drawn lanes are what influencing deviates from) OR
     *  sits on an orientation-CONFLICTED piece (pieceConflicted —
     *  the rigid tilt deviates the recorded felt / world quantities
     *  from the drawn lanes). Null = the derived (compiled) reference
     *  everywhere: the influence surface (its deltas aren't the
     *  measured quantity — the dotted resulting curve carries the
     *  truth), s/c and bridge sections (their drawn curve IS the
     *  derived felt), legacy reversed sections (authored lanes read
     *  back-to-front — accepted corner), and before the first compile.
     *  Which LANES of it take the authored reference is
     *  splitRefLanes' call. */
    private LaneSection splitRefSection() {
        if (surfStrip || activeInfl() != null) return null;
        List<Section> secs = sections();
        if (activeSection >= secs.size()
                || !(secs.get(activeSection) instanceof LaneSection ls)
                || ls.reversed) return null;
        if (spanOf(activeSection) == null) return null;
        if (ls.influenced()) return ls;
        return pieceConflicted(activePiece) ? ls : null;
    }

    /** Per-lane: does lane L of the reference section read its
     *  AUTHORED value (true) or the derived one (false)? An
     *  INFLUENCED reference keeps the D98 rule (all three authored);
     *  a merely conflicted one takes LaneSection.tiltDeviates —
     *  every force lane (the historic D100 rule, roll rate included
     *  per the D248 brief), geo world-axis and absolute lanes deviate
     *  under the tilt; geo local body rates are rotation-invariant
     *  and keep the derived reference (an authored reference there
     *  would only risk one-sample differencing dots at rate kinks).
     *  All-false for a null reference. */
    private boolean[] splitRefLanes(LaneSection refSec) {
        boolean[] out = new boolean[LANES];
        if (refSec == null) return out;
        for (int L = 0; L < LANES; L++) {
            out[L] = refSec.influenced()
                || LaneSection.tiltDeviates(refSec, L);
        }
        return out;
    }

    /** An AUTHORED lane value in DISPLAYED units at compiled sample
     *  i — the cross-rate view (v16.9, D121) draws a warped lane
     *  through the speed profile, so the reference scales exactly
     *  like derivedDisp (× v or × 1/v at the sample's design speed;
     *  identity off the warp). Closes the native-vs-warped mismatch
     *  the old tilt branch and the smoothed-window rows had (D248). */
    private double authDisp(int i, int L, double val) {
        if (!laneWarped(L)) return val;
        double v = Math.max(0.5, app.lastSamples.get(i).v());
        return val * (activeRatePM(L) ? v : 1 / v);
    }

    /** THE split reference (v19.19, D248): the DISPLAY-unit value the
     *  drawn design curve has at compiled sample i on lane L — what
     *  the thin true stroke (drawTrueStrokes) and the " (true …)"
     *  hover (trueSuffix) compare the true value against. Priority:
     *  (a) a smoothed-seam window row covering i → that row's
     *  authored lane (the neighbor half included, D235); (b) the
     *  reference section's authored lane while i sits inside its
     *  span (time membership ±1e-9 — the seam sample at t0 reads the
     *  section's x = 0, the historic influence-view rule) and lane L
     *  is one of its authored-reference lanes; (c) the derived
     *  (compiled) value. Authored reads take the section's own domain
     *  (arc from s0, or time from t0) and the display warp. */
    private double splitRef(int i, int L, List<Object[]> smParts,
            LaneSection refSec, TrackCompiler.Span refSpan,
            boolean[] refLanes) {
        double t = app.resultRates[i][0];
        if (smParts != null) {
            for (Object[] w : smParts) {
                if (i >= (Integer) w[0] && i <= (Integer) w[1]) {
                    TrackCompiler.Span wsp = (TrackCompiler.Span) w[2];
                    LaneSection wls = (LaneSection) w[3];
                    return authDisp(i, L, BezierBaker.evalAt(
                        wls.lanes.get(L),
                        wls.domain() == Section.Domain.DISTANCE
                            ? app.lastSamples.get(i).s() - wsp.s0()
                            : t - wsp.t0()));
                }
            }
        }
        if (refSec != null && refSpan != null && refLanes[L]
                && t >= refSpan.t0() - 1e-9
                && t <= refSpan.t1() + 1e-9) {
            return authDisp(i, L, BezierBaker.evalAt(refSec.lanes.get(L),
                refSec.domain() == Section.Domain.DISTANCE
                    ? app.lastSamples.get(i).s() - refSpan.s0()
                    : t - refSpan.t0()));
        }
        return derivedDisp(i, L);
    }

    /** The smoothed-strict-seam windows touching the ACTIVE section
     *  whose authored lanes can feed the active plot — {lo, hi, Span,
     *  LaneSection} rows, both sides from either neighbor (D235).
     *  Factored out of drawTrueStrokes in v19.7 (D236) so the hover's
     *  true-suffix shares the same authored-reference override.
     *  Null = none. */
    private List<Object[]> smoothedWindowParts() {
        if (activeSection >= sections().size()
                || !(sections().get(activeSection) instanceof LaneSection lsA)
                || lsA.reversed() || lsA.influenced()) return null;
        List<Object[]> smParts = null;
        List<RunSec> rl = runLayout();
        for (int j = 0; j + 1 < rl.size(); j++) {
            if (!seamStrict(rl, j)) continue;
            boolean actA = rl.get(j).piece() == activePiece
                && rl.get(j).section() == activeSection;
            boolean actB = rl.get(j + 1).piece() == activePiece
                && rl.get(j + 1).section() == activeSection;
            if (!actA && !actB) continue;
            int[] w = seamWindow(rl, j);
            if (w == null) continue;
            for (int side = 0; side < 2; side++) {
                RunSec rs = rl.get(j + side);
                TrackCompiler.Span sp = spanAt(rs.piece(), rs.section());
                Section sec = app.coaster.pieces.get(rs.piece())
                    .sections.get(rs.section());
                if (sp == null
                        || !(sec instanceof LaneSection ls2)
                        || ls2.reversed() || ls2.influenced()) {
                    continue;
                }
                // foreign units can't feed the active plot
                if ((ls2 instanceof GeoSection)
                            != (lsA instanceof GeoSection)
                        || ls2.rollGlobal != lsA.rollGlobal
                        || ls2.yawPitchGlobal != lsA.yawPitchGlobal
                        || ls2.ratePerMeter != lsA.ratePerMeter
                        || ls2.rollAbsolute != lsA.rollAbsolute
                        || ls2.ypAbsolute != lsA.ypAbsolute) {
                    continue;
                }
                // an ABSOLUTE heading lane speaks its own PIECE's
                // frame (v19.9, D238): a strict seam is cross-piece,
                // so the FAR side's authored values sit a constant
                // off the active frame — its half falls back to the
                // derived reference instead of feeding a phantom
                // (or hidden) split
                if (activeYpAbs() && rs.piece() != activePiece) {
                    continue;
                }
                int lo = Math.max(w[0], sp.i0());
                int hi2 = Math.min(w[1], sp.i1());
                if (lo > hi2) continue;
                if (smParts == null) smParts = new ArrayList<>();
                smParts.add(new Object[]{ lo, hi2, sp, ls2 });
            }
        }
        return smParts;
    }

    /** " (true …)" hover suffix at compiled sample i on lane L — empty
     *  until the true curve visibly splits from the design curve
     *  there (round A, §5.2). slope = the Shift readout, differenced
     *  by INDEX (±2 samples) and reported per DISPLAY-axis unit. */
    private String trueSuffix(int i, int L, boolean slope) {
        if (!trueArraysReady() || i >= app.resultRates.length) return "";
        // the reference is THE stroke's own (splitRef, v19.19 D248 —
        // one selector for both surfaces): inside a smoothed strict
        // seam's window the AUTHORED lane (v19.7, D236, user spec:
        // the compiled samples ARE the blend there, so a derived-vs-
        // true test never split without a ≠v and the hover hid the
        // blended value the stroke was drawing); on an influenced
        // section's design view or an orientation-CONFLICTED piece
        // the authored lane on every deviating lane (D248 — the hover
        // used to stay silent near a conflicted piece's entry while
        // the stroke drew 0.5 g away); derived elsewhere.
        LaneSection refSec = splitRefSection();
        double dv = splitRef(i, L,
            activeInfl() == null ? smoothedWindowParts() : null,
            refSec, refSec != null ? spanOf(activeSection) : null,
            splitRefLanes(refSec));
        double tv = trueDisp(i, L);
        if (Math.abs(valToY(L, tv) - valToY(L, dv)) <= TRUE_SPLIT_PX) return "";
        if (!slope) return String.format(" (true %.2f)", tv);
        int[] w2 = slopeWin(i, 2);   // span-clamped (D207)
        int i0 = w2[0], i1 = w2[1];
        double span = gAxis(i1) - gAxis(i0);
        double sl = span < 1e-9 ? 0 : (trueDisp(i1, L) - trueDisp(i0, L)) / span;
        return String.format(" (true %.2f)", sl);
    }

    // zero-line fill under every drawn lane curve (v10.1 visual pass;
    // the read-only "Resulting" overlays deliberately stay unfilled)
    private static final double LANE_FILL_ALPHA = 0.13;

    /** Translucent fill between a lane's pixel-space series and its
     *  value-zero line (inherits the caller's globalAlpha, so dimmed
     *  lanes/strips get proportionally fainter fills). */
    private void fillUnderSeries(GraphicsContext gc, List<double[]> pts, int L) {
        if (pts.size() < 2) return;
        double zy = valToY(L, 0);
        gc.setFill(laneColor(L).deriveColor(0, 1, 1, LANE_FILL_ALPHA));
        gc.beginPath();
        gc.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) gc.lineTo(pts.get(i)[0], pts.get(i)[1]);
        gc.lineTo(pts.get(pts.size() - 1)[0], zy);
        gc.lineTo(pts.get(0)[0], zy);
        gc.closePath();
        gc.fill();
    }

    /** Pixel distance from a point to the nearest ACTIVE-section node
     *  (any lane — dimmed-lane nodes are clickable too). */
    private double nearestNodePx(double x, double y) {
        double best = 1e9;
        for (int L = 0; L < LANES; L++) {
            for (BezierBaker.Node n : app.lanes.get(L)) {
                best = Math.min(best, Math.hypot(x - ltToX(n.t),
                    y - valToY(L, dispVal(L, n.t, n.val))));
            }
        }
        return best;
    }

    /** The vertical line + circled '+' at the ACTIVE PIECE's end (user
     *  spec): click opens the add-section menu. */
    private void drawAddButton(GraphicsContext gc, double w) {
        double x = tToX(runT0() + runDur());   // the RUN's end (v13.4)
        if (x < MARGIN_LEFT - 1 || x > w - marginRight() + 1) {
            addBtnX = -999;
            return;
        }
        gc.setGlobalAlpha(0.55);
        gc.setStroke(Color.rgb(150, 156, 175));
        gc.setLineWidth(1);
        gc.setLineDashes(4, 6);
        gc.strokeLine(x, MARGIN_TOP, x, MARGIN_TOP + plotHeight());
        gc.setLineDashes((double[]) null);
        // the ride's END nodes sit exactly on this line — and the OTHER
        // lanes' value-0 end nodes map to mid-plot on their scales (Gl 0
        // on ±1, roll 0 on ±150), so a single top-vs-mid comparison never
        // dodged (D57). Walk a ladder of spots downward and take the
        // first CLEAR one; if none is clear, the clearest.
        double cy = MARGIN_TOP + 16;
        double[] ladder = { MARGIN_TOP + 16,
                            MARGIN_TOP + plotHeight() * 0.30,
                            MARGIN_TOP + plotHeight() * 0.50,
                            MARGIN_TOP + plotHeight() * 0.70,
                            MARGIN_TOP + plotHeight() - 16 };
        double bestD = -1;
        for (double cand : ladder) {
            double d = nearestNodePx(x, cand);
            if (d >= 26) { cy = cand; bestD = Double.MAX_VALUE; break; }
            if (d > bestD) { bestD = d; cy = cand; }
        }
        gc.setGlobalAlpha(0.9);
        gc.setFill(Color.rgb(45, 50, 62));
        gc.fillOval(x - 9, cy - 9, 18, 18);
        gc.strokeOval(x - 9, cy - 9, 18, 18);
        gc.setLineWidth(1.6);
        gc.strokeLine(x - 4.5, cy, x + 4.5, cy);
        gc.strokeLine(x, cy - 4.5, x, cy + 4.5);
        gc.setGlobalAlpha(1.0);
        addBtnX = x;
        addBtnY = cy;
    }

    // section type badges (v10.1): a small "force"/"geo" tag at the top
    // of every strip — the type indicator doubles as the per-section
    // menu handle (delete section). Rebuilt each redraw; rows {x,y,w,h,k}.
    private final List<double[]> badgeRects = new ArrayList<>();

    private void drawSectionBadges(GraphicsContext gc, double w) {
        badgeRects.clear();
        List<RunSec> rl = runLayout();   // badges across the run (v13.4)
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (int k = 0; k < rl.size(); k++) {
            RunSec rs = rl.get(k);
            double off = rs.t0();
            double dur = rs.dur();
            boolean dirty = rs.sec() instanceof BridgeSection bs
                && rs.piece() + 1 >= app.coaster.pieces.size();
            // §5.2 speed-conflict glyph ≠v — ENTRY-ONLY since v19.18
            // (D247, user spec: the section enters at a speed other
            // than its design speed; D106's whole-span test is gone)
            // — plus the ORIENTATION-conflict glyph ≠o (piece design
            // != real; a follower's held seam angles vs its design
            // orientation), spelled with a LOWERCASE o since v19.19
            // (D248, user spec — was the degree sign). Both can show.
            String glyphs
                = (sectionDrifted(rs.piece(), rs.section()) ? " ≠v" : "")
                + (sectionOrientConflict(rs.piece(), rs.section())
                    ? " ≠o" : "");
            // THE PRE-D248 FORM again since v19.20 (D249 — user hand
            // test reversed D248's numbered title + width ladder):
            // the short type word + tags + glyphs ("Geo ·m ◀ fx ≠v
            // ≠o", "Bridge !"), no number; one form, no short
            // fallback — a strip narrower than the box shows NO
            // badge (the historic rule). The width stays MEASURED
            // with a real Text (D248's fix over the length×6
            // estimate is kept).
            String label = SectionNames.badgeLabel(rs.sec(), dirty)
                + glyphs;
            double bw = badgeWidth(label);
            double x0 = Math.max(tToX(off), MARGIN_LEFT);
            double x1 = Math.min(tToX(off + dur), w - marginRight());
            if (x1 - x0 >= bw + 8) {   // strip too narrow on screen = no badge
                double bx = x0 + 4, by = MARGIN_TOP + 4;
                boolean isActive = rs.piece() == activePiece
                    && rs.section() == activeSection;
                // HOVER highlight (D223, user spec — "make it obvious
                // it can be clicked"): the seam-line pattern —
                // brighter fill/edge + full alpha under the cursor.
                // TYPE COLORS since v19.12 (D241, user spec): blue
                // Force / orange Geo / purple S/C; bridges keep the
                // neutral gray (and the dirty warning edge)
                boolean hot = k == hoverBadge;
                // NEUTRAL again (v19.14, D243 — the D241 type colors
                // reversed on user spec) but LIGHTER than the
                // historic gray, with a thicker edge so the button
                // stands out
                gc.setGlobalAlpha(hot ? 1.0 : isActive ? 0.9 : 0.6);
                gc.setFill(hot ? Color.rgb(84, 92, 112)
                               : Color.rgb(56, 62, 76));
                gc.fillRoundRect(bx, by, bw, 15, 7, 7);
                // a dirty bridge wears the warning color (v13.0)
                gc.setStroke(dirty ? Color.rgb(255, 140, 90)
                    : hot ? Color.rgb(235, 240, 250)
                          : Color.rgb(180, 186, 205));
                gc.setLineWidth(hot ? 2.2 : 1.6);
                gc.strokeRoundRect(bx, by, bw, 15, 7, 7);
                gc.setFill(dirty ? Color.rgb(255, 170, 130)
                    : hot ? Color.WHITE : Color.rgb(205, 210, 220));
                gc.fillText(label, bx + bw / 2, by + 8);
                badgeRects.add(new double[]{ bx, by, bw, 15, k });
            }
        }
        gc.setGlobalAlpha(1.0);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    /** Text measurer for the badge width (D248; the ladder it served
     *  is gone since D249) — the badge font is Font.font(10), set
     *  once. */
    private final javafx.scene.text.Text badgeMeas = badgeMeasText();

    private static javafx.scene.text.Text badgeMeasText() {
        javafx.scene.text.Text t = new javafx.scene.text.Text();
        t.setFont(Font.font(10));
        return t;
    }

    /** Badge box width for a label: the measured text width + 10 px
     *  of side padding (the historic length×6+10 estimate under-
     *  measured long titles). */
    private double badgeWidth(String label) {
        badgeMeas.setText(label);
        return Math.ceil(badgeMeas.getLayoutBounds().getWidth()) + 10;
    }

    /** Which RUN strip's badge is under this point; -1 for none (the
     *  stored index is into runLayout(), v13.4). */
    private int hitBadge(double x, double y) {
        for (double[] r : badgeRects) {
            if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
                return (int) r[4];
            }
        }
        return -1;
    }

    // ---------- straight/curve ELEMENT STRIP (v11.0, D68) ----------
    // the active s/c section's strip REPLACES the lanes with the element
    // widget: a translucent band per element (labeled chip at the top),
    // dashed separators between them, and a smoothing DIAMOND on every
    // internal boundary. Clicking a band selects; dragging a separator
    // (or the section end) resizes the element left of it (Ctrl snaps by
    // the Options length step); dragging a diamond sizes the blend;
    // right-click = insert/delete menu; double-click = element dialog.
    // The felt-G curves draw behind at SC_CURVE_ALPHA for context.
    private int elSel = -1;            // selected element of the active s/c
    private int elDragBoundary = -1;   // element whose LENGTH is dragging
    private int elDragSmooth = -1;     // element whose ENTRY blend is dragging
    private int scDragSide = 0;        // interior blend EDGE drag: -1/+1,
                                       // 0 = the diamond (symmetric) — D248
    private boolean bridgeDragGap = false;   // bridge end line = gap drag (v13.3)
    private int rClickElem = -1;       // deferred right-click menu (pan-safe)

    private static final double SC_CURVE_ALPHA = 0.45;
    static final double SC_SMOOTH_DEFAULT = 2;   // m, new elements' entry blend
    private static final double MIN_ELEM_LEN = 0.5;   // m
    private static final Color SC_STRAIGHT_COLOR = Color.rgb(150, 160, 185);
    private static final Color SC_CURVE_COLOR = Color.rgb(235, 130, 180);

    /** Cumulative AUTHORED arc-length boundaries: [0, L0, L0+L1, …]. */
    private static double[] scBoundsS(StraightCurveSection sc) {
        double[] b = new double[sc.elements.size() + 1];
        for (int i = 0; i < sc.elements.size(); i++) {
            b[i + 1] = b[i] + sc.elements.get(i).arcLen();
        }
        return b;
    }

    /** The active section's span from the last compile, or null. */
    private TrackCompiler.Span activeSpan() {
        return spanOf(activeSection);
    }

    /** Global ride time where the compiled track crosses TRAVERSAL arc
     *  length sTrav inside span sp (binary search + lerp on samples). */
    private double timeAtSpanS(TrackCompiler.Span sp, double sTrav) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        double target = sp.s0() + sTrav;
        int lo = Math.min(sp.i0(), ss.size() - 1);
        int hi = Math.min(sp.i1(), ss.size() - 1);
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ss.get(mid).s() <= target) lo = mid; else hi = mid;
        }
        FVDIntegrator.Sample a = ss.get(lo), b = ss.get(hi);
        double ds = b.s() - a.s();
        double u = ds < 1e-12 ? 0 : FvdDesigner.clamp((target - a.s()) / ds, 0, 1);
        return a.t() + (b.t() - a.t()) * u;
    }

    /** AXIS coordinate of AUTHORED arc length s in the active s/c
     *  section. On the native METERS axis (v16.0) this is LINEAR —
     *  elements lay out at their true lengths; in the time view it
     *  maps through the compiled samples (the pre-v16.0 rendering).
     *  Reversal runs the authored shape back-to-front, so authored s
     *  maps to traversal (total − s) — element 0 draws at the strip's
     *  right edge, matching what the train does. */
    private double scAxisAtS(StraightCurveSection sc, double sLocal) {
        double sTrav = sc.reversed ? sc.totalLen() - sLocal : sLocal;
        if (axisDist()) return tOff + sTrav;
        TrackCompiler.Span sp = activeSpan();
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) {
            double tot = Math.max(sc.totalLen(), 1e-6);
            return tOff + sectionDur(activeSection) * (sTrav / tot);
        }
        return timeAtSpanS(sp, sTrav);
    }

    /** AUTHORED arc length under axis coordinate gt (inverse map).
     *  EXTRAPOLATES past both span ends at the boundary sample's speed
     *  (v11.1; exact 1:1 on the meters axis): the cursor keeps meaning
     *  something beyond the current section end, so a boundary drag
     *  can EXTEND the section, not just shorten it (the live rebuild
     *  re-maps every event). */
    private double scSAtT(StraightCurveSection sc, double gt) {
        double tot = Math.max(sc.totalLen(), 1e-6);
        if (axisDist()) {
            double sTrav = gt - tOff;   // linear, extends past both ends
            return sc.reversed ? tot - sTrav : sTrav;
        }
        TrackCompiler.Span sp = activeSpan();
        double sTrav;
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) {
            double dur = Math.max(sectionDur(activeSection), 1e-6);
            sTrav = tot * Math.max(0, (gt - tOff) / dur);
        } else {
            List<FVDIntegrator.Sample> ss = app.lastSamples;
            int lo = Math.min(sp.i0(), ss.size() - 1);
            int hi = Math.min(sp.i1(), ss.size() - 1);
            FVDIntegrator.Sample first = ss.get(lo), last = ss.get(hi);
            if (gt >= last.t()) {
                sTrav = tot + (gt - last.t()) * Math.max(0.5, last.v());
            } else if (gt <= first.t()) {
                // negative: before the entry (extends a REVERSED
                // section, whose authored end sits at the strip's left)
                sTrav = (gt - first.t()) * Math.max(0.5, first.v());
            } else {
                while (hi - lo > 1) {
                    int mid = (lo + hi) >>> 1;
                    if (ss.get(mid).t() <= gt) lo = mid; else hi = mid;
                }
                FVDIntegrator.Sample a = ss.get(lo), b = ss.get(hi);
                double dt = b.t() - a.t();
                double u = dt < 1e-12 ? 0 : FvdDesigner.clamp((gt - a.t()) / dt, 0, 1);
                sTrav = FvdDesigner.clamp(a.s() + (b.s() - a.s()) * u - sp.s0(), 0, tot);
            }
        }
        return sc.reversed ? tot - sTrav : sTrav;
    }

    /** Element index under global ride time t. */
    private int elementAtT(StraightCurveSection sc, double gt) {
        double sAuth = scSAtT(sc, gt);
        double[] b = scBoundsS(sc);
        for (int i = 0; i < sc.elements.size(); i++) {
            if (sAuth <= b[i + 1] + 1e-9) return i;
        }
        return sc.elements.size() - 1;
    }

    /** Which element BOUNDARY (1..n; n = the section end) is within
     *  grab range of (x, y); 0/-1 = none (the entry is not draggable).
     *  Nearest wins so tiny elements stay resizable. */
    private int hitScBoundary(double x, double y) {
        StraightCurveSection sc = activeSc();
        if (sc == null || y < MARGIN_TOP || y > MARGIN_TOP + plotHeight()) return -1;
        double[] b = scBoundsS(sc);
        int best = -1;
        double bestD = 5;
        for (int j = 1; j < b.length; j++) {
            double d = Math.abs(x - tToX(scAxisAtS(sc, b[j])));
            if (d <= bestD) { bestD = d; best = j; }
        }
        return best;
    }

    private double scDiamondY() { return MARGIN_TOP + 48; }

    /** Which element's entry-smoothing diamond is under (x, y); -1 =
     *  none. Diamonds sit on internal boundaries only (1..n-1). */
    private int hitScDiamond(double x, double y) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return -1;
        double[] b = scBoundsS(sc);
        // j = 0 is the SECTION-ENTRY ease diamond (v19.4, D233)
        for (int j = 0; j < b.length - 1; j++) {
            if (Math.hypot(x - tToX(scAxisAtS(sc, b[j])), y - scDiamondY()) <= 7) {
                return j;
            }
        }
        return -1;
    }

    private static String elemLabel(StraightCurveSection.Element e) {
        String base = e.kind == StraightCurveSection.Kind.STRAIGHT
            ? "— " + fmtVal(e.length) + " m"
            : "R" + fmtVal(e.radius) + " " + fmtVal(Math.abs(e.angleDeg)) + "°"
                + dirArrow(e);
        // non-default roll rides the chip: ∠ = held global bank,
        // ↻ = body roll rate (global 0 — level — is the quiet norm)
        if (Math.abs(e.rollVal) > 1e-9) {
            base += e.rollMode == StraightCurveSection.RollMode.GLOBAL
                ? " ∠" + fmtVal(e.rollVal) + "°"
                : " ↻" + fmtVal(e.rollVal) + "°/s";
        }
        return base;
    }

    /** A tiny CLOCKWISE circle-arrow stroked straight onto a canvas
     *  (v18.11 — the chip's ↻ replacement; round bottom, gap only at
     *  the arrowhead). */
    private static void strokeMiniCircleArrow(GraphicsContext gc,
            double cx, double cy, double r, Color colr) {
        gc.setStroke(colr);
        gc.setLineWidth(1.1);
        double gap = 80;
        gc.strokeArc(cx - r, cy - r, 2 * r, 2 * r, 90 + gap / 2,
            360 - gap, javafx.scene.shape.ArcType.OPEN);
        double th = Math.toRadians(90 + gap / 2);
        double px = cx + r * Math.cos(th), py = cy - r * Math.sin(th);
        double tx = Math.sin(th), ty = Math.cos(th);   // CW tangent
        double nx = Math.cos(th), ny = -Math.sin(th);
        gc.setFill(colr);
        gc.fillPolygon(
            new double[]{ px + tx * 2.6, px + nx * 1.3, px - nx * 1.3 },
            new double[]{ py + ty * 2.6, py + ny * 1.3, py - ny * 1.3 }, 3);
    }

    /** Turn-plane arrow for a curve chip: the effective direction
     *  (angle sign folds into dir ± 180) rounded to the nearest 45°.
     *  Horizontal sense MIRRORED since v18.16 (D187: dir 0 + positive
     *  angle pulls LEFT; the vertical octants are unchanged). */
    private static String dirArrow(StraightCurveSection.Element e) {
        String[] arrows = { "←", "↖", "↑", "↗", "→", "↘", "↓", "↙" };
        double eff = e.angleDeg >= 0 ? e.dirDeg : e.dirDeg + 180;
        int oct = (int) Math.round(((eff % 360) + 360) % 360 / 45.0) % 8;
        return arrows[oct];
    }

    /** The active s/c strip: derived felt-G context curves + the
     *  element widget (bands, chips, separators, smoothing diamonds,
     *  live drag readout). */
    private void drawScStrip(GraphicsContext gc) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return;
        double dur = sectionDur(activeSection);
        gc.setGlobalAlpha(SC_CURVE_ALPHA);
        gc.setLineWidth(1.5);
        drawDerivedStrip(gc, tOff, dur, spanOf(activeSection));
        gc.setGlobalAlpha(1.0);

        double[] b = scBoundsS(sc);
        // the same clamped blend halves the integrator uses
        ScIntegrator.Profile prof = new ScIntegrator.Profile(sc);
        double top = MARGIN_TOP, bot = MARGIN_TOP + plotHeight();
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < sc.elements.size(); i++) {
            StraightCurveSection.Element e = sc.elements.get(i);
            double xa = tToX(scAxisAtS(sc, b[i]));
            double xb = tToX(scAxisAtS(sc, b[i + 1]));
            double x0 = Math.min(xa, xb), x1 = Math.max(xa, xb);
            Color col = e.kind == StraightCurveSection.Kind.STRAIGHT
                ? SC_STRAIGHT_COLOR : SC_CURVE_COLOR;
            gc.setFill(col.deriveColor(0, 1, 1, i == elSel ? 0.17 : 0.09));
            gc.fillRect(x0, top, x1 - x0, bot - top);
            if (i == elSel) {
                gc.setStroke(col.deriveColor(0, 1, 1, 0.85));
                gc.setLineWidth(1.5);
                gc.strokeRect(x0 + 1, top + 1, x1 - x0 - 2, bot - top - 2);
            }
            String label = elemLabel(e);
            double bw = label.length() * 6 + 12;
            if (x1 - x0 >= bw + 6) {
                double bx = (x0 + x1) / 2 - bw / 2, by = top + 24;
                gc.setGlobalAlpha(0.92);
                gc.setFill(Color.rgb(45, 50, 62));
                gc.fillRoundRect(bx, by, bw, 15, 7, 7);
                gc.setStroke(i == elSel ? Color.WHITE : col.deriveColor(0, 1, 1, 0.7));
                gc.setLineWidth(1);
                gc.strokeRoundRect(bx, by, bw, 15, 7, 7);
                Color tcol = col.brighter();
                gc.setFill(tcol);
                int gi = label.indexOf('↻');
                if (gi < 0) {
                    gc.fillText(label, bx + bw / 2, by + 8);
                } else {
                    // the rate marker draws as a ROUND mini
                    // circle-arrow (v18.11 — the font glyph's circle
                    // is flat-bottomed); a space holds its slot in
                    // the centered layout
                    gc.fillText(label.substring(0, gi) + " "
                        + label.substring(gi + 1), bx + bw / 2, by + 8);
                    double slotX = bx + bw / 2
                        + (gi + 0.5 - label.length() / 2.0) * 6;
                    strokeMiniCircleArrow(gc, slotX, by + 7.5, 3.4, tcol);
                }
                gc.setGlobalAlpha(1.0);
            }
        }
        // the SECTION-ENTRY roll ease (v19.4, D233): its own band
        // [0, ease] + a diamond ON the entry boundary — the same
        // surface as every internal blend
        {
            double ease = Math.min(sc.elements.get(0).smoothIn,
                sc.elements.get(0).arcLen());
            if (ease > 1e-9) {
                double za = tToX(scAxisAtS(sc, 0));
                double zb = tToX(scAxisAtS(sc, ease));
                gc.setFill(Color.rgb(255, 255, 255, 0.07));
                gc.fillRect(Math.min(za, zb), top, Math.abs(zb - za), bot - top);
            }
            double bx = tToX(scAxisAtS(sc, 0));
            double dy = scDiamondY();
            gc.setGlobalAlpha(0.85);
            gc.beginPath();
            gc.moveTo(bx, dy - 5);
            gc.lineTo(bx + 5, dy);
            gc.lineTo(bx, dy + 5);
            gc.lineTo(bx - 5, dy);
            gc.closePath();
            if (ease > 1e-9) { gc.setFill(Color.WHITE); gc.fill(); }
            gc.setStroke(Color.WHITE);
            gc.stroke();
            gc.setGlobalAlpha(1.0);
        }
        for (int j = 1; j < b.length; j++) {
            double bx = tToX(scAxisAtS(sc, b[j]));
            boolean internal = j < b.length - 1;
            if (internal && prof.half[j] > 1e-9) {   // the blend zone
                double za = tToX(scAxisAtS(sc, b[j] - prof.half[j]));
                double zb = tToX(scAxisAtS(sc, b[j] + prof.half[j]));
                gc.setFill(Color.rgb(255, 255, 255, 0.07));
                gc.fillRect(Math.min(za, zb), top, Math.abs(zb - za), bot - top);
            }
            gc.setStroke(Color.rgb(200, 206, 222));
            gc.setGlobalAlpha(internal ? 0.5 : 0.35);
            gc.setLineWidth(1);
            gc.setLineDashes(2, 5);
            gc.strokeLine(bx, top, bx, bot);
            gc.setLineDashes((double[]) null);
            gc.setGlobalAlpha(1.0);
            if (internal) {   // smoothing diamond: filled = blending
                double dy = scDiamondY();
                boolean on = sc.elements.get(j).smoothIn > 1e-9;
                gc.setGlobalAlpha(0.85);
                gc.beginPath();
                gc.moveTo(bx, dy - 5);
                gc.lineTo(bx + 5, dy);
                gc.lineTo(bx, dy + 5);
                gc.lineTo(bx - 5, dy);
                gc.closePath();
                if (on) { gc.setFill(Color.WHITE); gc.fill(); }
                gc.setStroke(Color.WHITE);
                gc.stroke();
                gc.setGlobalAlpha(1.0);
            }
        }
        // live readout for the running element drag, node-drag style
        String readout = null;
        double rx = 0;
        if (elDragBoundary >= 0 && elDragBoundary < sc.elements.size()) {
            StraightCurveSection.Element e = sc.elements.get(elDragBoundary);
            readout = e.kind == StraightCurveSection.Kind.STRAIGHT
                ? String.format(Locale.US, "length %.1f m", e.length)
                : String.format(Locale.US, "arc %.1f m  (%.0f°)", e.arcLen(), e.angleDeg);
            rx = tToX(scAxisAtS(sc, b[elDragBoundary + 1]));
        } else if (elDragSmooth == 0 && !sc.elements.isEmpty()) {
            double sm = sc.elements.get(0).smoothIn;
            readout = sm < 1e-9 ? "entry ease off"
                : String.format(Locale.US, "entry ease %.1f m", sm);
            rx = tToX(scAxisAtS(sc, 0));
        } else if (elDragSmooth >= 1 && elDragSmooth < sc.elements.size()) {
            double sm = sc.elements.get(elDragSmooth).smoothIn;
            readout = sm < 1e-9 ? "smooth off"
                : String.format(Locale.US, "smooth %.1f m", sm);
            rx = tToX(scAxisAtS(sc, b[elDragSmooth]));
        }
        if (readout != null) {
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            double tw = readout.length() * 6.5;
            gc.fillText(readout,
                FvdDesigner.clamp(rx + 8, MARGIN_LEFT + 4,
                    canvas.getWidth() - marginRight() - tw - 4),
                MARGIN_TOP + plotHeight() * 0.5);
        }
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.setLineWidth(2);
    }

    /** The active BRIDGE strip (v13.0): the felt-G derived curves at
     *  s/c-widget alpha — the geometry itself is solver-owned (the
     *  badge menu re-tangents; the END LINE drags the gap, v13.3) —
     *  plus a centered status line when the endpoints have drifted. */
    private void drawBridgeStrip(GraphicsContext gc) {
        BridgeSection bs = activeBridge();
        if (bs == null) return;
        double dur = sectionDur(activeSection);
        gc.setGlobalAlpha(SC_CURVE_ALPHA);
        gc.setLineWidth(1.5);
        drawDerivedStrip(gc, tOff, dur, spanOf(activeSection));
        gc.setGlobalAlpha(1.0);
        if (pcSec == activeSection) {
            drawPartialConvert(gc, dur);   // split-line mode (v13.11)
        }
        if (bridgeDragGap) {   // live readout, element-drag style
            String readout = String.format(Locale.US, "length %.1f m",
                requestedBridgeGap());
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            double tw = readout.length() * 6.5;
            gc.fillText(readout,
                FvdDesigner.clamp(tToX(tOff + dur) + 8, MARGIN_LEFT + 4,
                    canvas.getWidth() - marginRight() - tw - 4),
                MARGIN_TOP + plotHeight() * 0.5);
        }
        if (bridgeOrphan(bs)) {
            gc.setFill(Color.rgb(255, 170, 130));
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("no following piece — frozen at its last shape",
                (tToX(tOff) + tToX(tOff + dur)) / 2,
                MARGIN_TOP + plotHeight() * 0.5);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
        }
        gc.setLineWidth(2);
    }

    /** Faint element separators over an INACTIVE s/c strip (inherits
     *  the caller's strip alpha). Linear on the meters axis (v16.0). */
    private void drawScSeparators(GraphicsContext gc, StraightCurveSection sc,
                                  TrackCompiler.Span sp) {
        if (sp == null || app.lastSamples == null
                || sp.i0() >= app.lastSamples.size()) return;
        double[] b = scBoundsS(sc);
        gc.setStroke(Color.rgb(200, 206, 222));
        gc.setLineWidth(1);
        gc.setLineDashes(2, 5);
        for (int j = 1; j < b.length - 1; j++) {
            double sTrav = sc.reversed ? sc.totalLen() - b[j] : b[j];
            double bx = tToX(axisDist() ? sp.s0() + sTrav
                                        : timeAtSpanS(sp, sTrav));
            gc.strokeLine(bx, MARGIN_TOP, bx, MARGIN_TOP + plotHeight());
        }
        gc.setLineDashes((double[]) null);
    }

    /** Primary press while a straight/curve section is active. Press
     *  rank mirrors the node editor's: diamonds (the "knobs") > '+' >
     *  badges > boundary grabs > band select > strip activation. */
    private void scPrimaryPressed(MouseEvent e) {
        StraightCurveSection sc = activeSc();
        int d = hitScDiamond(e.getX(), e.getY());
        if (d >= 0) {   // 0 = the entry-ease diamond (D233)
            beginGesture();
            elDragSmooth = d;
            scDragSide = 0;   // the diamond keeps its symmetric drag
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            dragTimerLast = 0;
            dragGrowTimer.start();
            return;
        }
        if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
            plusPressed(e.getScreenX(), e.getScreenY());
            return;
        }
        int badge = hitBadge(e.getX(), e.getY());
        if (badge >= 0) {
            showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
            return;
        }
        int bIdx = hitScBoundary(e.getX(), e.getY());
        if (bIdx >= 1) {
            beginGesture();
            elDragBoundary = bIdx - 1;
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            dragTimerLast = 0;
            dragGrowTimer.start();
            return;
        }
        // blend-band EDGES (v17.4) — under everything above, over
        // the element select (which would otherwise eat every click)
        if (seamEdgePressed(e)) return;
        int je = scEdgeHit(e.getX(), e.getY());
        if (je >= 0) {   // 0 = the entry-ease band edge (D234)
            beginGesture();
            elDragSmooth = je;
            // an interior band EDGE drags one-sided since v19.19 (D248
            // — the strict-seam rule for parity): the grabbed side
            // sizes the blend, crossing the boundary turns it off. The
            // side is read in AUTHORED arc (scSAtT), the coordinate
            // scDragTo compares against — a legacy REVERSED strip draws
            // element 0 at the right, so a pixel side would be mirrored
            // there (review fix, D248)
            scDragSide = je >= 1
                ? (scSAtT(sc, xToT(e.getX())) < scBoundsS(sc)[je] ? -1 : 1)
                : 0;
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            dragTimerLast = 0;
            dragGrowTimer.start();
            return;
        }
        double gt = xToT(e.getX());
        if (gt >= tOff - 1e-9 && gt <= tOff + sectionEnd() + 1e-9
                && e.getY() >= MARGIN_TOP && e.getY() <= MARGIN_TOP + plotHeight()) {
            int i = elementAtT(sc, gt);
            if (i != elSel) {
                elSel = i;
                redraw();
            }
            return;
        }
        if (seamDiamondPressed(e)) return;
        // seam lines outside the active s/c strip (v17.1) — the strip's
        // own boundaries were claimed by hitScBoundary above
        int seam = hitSeam(e.getX(), e.getY());
        if (seam >= 0) {
            showSeamMenu(seam, e.getScreenX(), e.getScreenY());
            return;
        }
        RunSec rs = runSecAt(gt);
        if (rs != null && (rs.piece() != activePiece
                || rs.section() != activeSection)) {
            activateRunSec(rs.piece(), rs.section());
            return;
        }
        if (elSel != -1) {
            elSel = -1;
            redraw();
        }
    }

    /** The running element drag: separators resize the element left of
     *  them (straight = length, curve = angle at fixed radius); the
     *  diamond sizes its boundary's blend, snapping shut near zero.
     *  Ctrl snaps by the Options length step. Live recompile keeps the
     *  x→arc-length map honest while the strip stretches. */
    private void scDragTo(double x, boolean snap) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return;
        double[] b = scBoundsS(sc);
        double sAuth = scSAtT(sc, xToT(x));
        if (elDragBoundary >= 0 && elDragBoundary < sc.elements.size()) {
            double newLen = sAuth - b[elDragBoundary];
            if (snap) {
                double step = app.settings.snapLen();
                newLen = Math.round(newLen / step) * step;
            }
            applyElemLen(sc.elements.get(elDragBoundary),
                Math.max(MIN_ELEM_LEN, newLen));
        } else if (elDragSmooth == 0 && !sc.elements.isEmpty()) {
            // the ENTRY ease (D233): absolute — the ease runs [0, len]
            // INTO the section; snaps shut near zero (0 = sharp)
            double len = Math.max(0, sAuth);
            if (snap) {
                double step = app.settings.snapLen();
                len = Math.round(len / step) * step;
            }
            len = Math.min(len, sc.elements.get(0).arcLen());
            sc.elements.get(0).smoothIn = len < 0.2 ? 0 : len;
        } else if (elDragSmooth >= 1 && elDragSmooth < sc.elements.size()) {
            // diamond: symmetric; band edge: one-sided (D248)
            double len = Gestures.edgeWindowLen(sAuth, b[elDragSmooth],
                scDragSide);
            if (snap) {
                double step = app.settings.snapLen();
                len = Math.round(len / step) * step;
            }
            sc.elements.get(elDragSmooth).smoothIn = len < 0.2 ? 0 : len;
        } else {
            return;
        }
        app.rebuildTrack();
        redraw();
    }

    /** Set an element's arc length: a straight's length directly; a
     *  curve keeps its radius and re-angles (sign preserved). */
    private static void applyElemLen(StraightCurveSection.Element el, double len) {
        if (el.kind == StraightCurveSection.Kind.STRAIGHT) {
            el.length = len;
        } else {
            double sign = el.angleDeg < 0 ? -1 : 1;
            el.radius = Math.max(0.1, el.radius);
            el.angleDeg = sign * Math.toDegrees(len / el.radius);
        }
    }

    /** Right-click menu of one element (deferred to a clean release so
     *  right-drag still pans). */
    private void showElementMenu(int i, double screenX, double screenY) {
        StraightCurveSection sc = activeSc();
        if (sc == null || i < 0 || i >= sc.elements.size()) return;
        takePlotSelection();   // an element selection (the release's
                               // secondary press skipped the choke,
                               // D249 review fix)
        elSel = i;
        redraw();
        ContextMenu cm = new ContextMenu();
        MenuItem edit = new MenuItem("Edit element…");
        edit.setOnAction(ev -> showElementDialog(i));
        MenuItem insSB = new MenuItem("Insert straight before");
        insSB.setOnAction(ev -> insertElement(i, true));
        MenuItem insCB = new MenuItem("Insert curve before");
        insCB.setOnAction(ev -> insertElement(i, false));
        MenuItem insSA = new MenuItem("Insert straight after");
        insSA.setOnAction(ev -> insertElement(i + 1, true));
        MenuItem insCA = new MenuItem("Insert curve after");
        insCA.setOnAction(ev -> insertElement(i + 1, false));
        MenuItem del;
        if (sc.elements.size() <= 1) {
            del = grayItem("Delete element",
                "The section's only element cannot be deleted.");
        } else {
            del = new MenuItem("Delete element");
            del.setOnAction(ev -> deleteElement(i));
        }
        cm.getItems().addAll(edit, new SeparatorMenuItem(),
            insSB, insCB, insSA, insCA, new SeparatorMenuItem(), del);
        showMenu(cm, screenX, screenY);
    }

    /** Insert an element at list position `at` (one undo step). New
     *  elements arrive with the default entry blend — smoothing is a
     *  first-class citizen here, not an afterthought. */
    private void insertElement(int at, boolean straight) {
        StraightCurveSection sc = activeSc();
        if (sc == null) return;
        pushUndoSnapshot();
        sc.elements.add(at, straight
            ? StraightCurveSection.Element.straight(10, SC_SMOOTH_DEFAULT)
            : StraightCurveSection.Element.curve(20, 90, 0, SC_SMOOTH_DEFAULT));
        elSel = at;
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    private void deleteElement(int i) {
        StraightCurveSection sc = activeSc();
        if (sc == null || sc.elements.size() <= 1
                || i < 0 || i >= sc.elements.size()) return;
        pushUndoSnapshot();
        sc.elements.remove(i);
        elSel = Math.min(elSel, sc.elements.size() - 1);
        app.rebuildTrack();
        refreshOffset();
        redraw();
    }

    /** Double-click an element (or its right-click "Edit element…"):
     *  type the parameters directly. The kind radios switch the visible
     *  rows; switching keeps the arc length where it can. */
    private void showElementDialog(int idx) {
        StraightCurveSection sc = activeSc();
        if (sc == null || idx < 0 || idx >= sc.elements.size()) return;
        StraightCurveSection.Element el = sc.elements.get(idx);
        boolean isStraight = el.kind == StraightCurveSection.Kind.STRAIGHT;

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Element " + (idx + 1) + " — straight/curve section");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));

        ToggleGroup kindGrp = new ToggleGroup();
        RadioButton rbS = new RadioButton("Straight");
        RadioButton rbC = new RadioButton("Curve");
        rbS.setToggleGroup(kindGrp);
        rbC.setToggleGroup(kindGrp);
        (isStraight ? rbS : rbC).setSelected(true);

        TextField lenF = new TextField(String.format(Locale.US, "%.3f",
            isStraight ? el.length : el.arcLen()));
        TextField radF = new TextField(String.format(Locale.US, "%.3f",
            el.radius > 0 ? el.radius : 20));
        TextField angF = new TextField(String.format(Locale.US, "%.3f",
            !isStraight ? el.angleDeg : 90));
        TextField dirF = new TextField(String.format(Locale.US, "%.3f", el.dirDeg));
        TextField smF = new TextField(String.format(Locale.US, "%.3f", el.smoothIn));
        dirF.setTooltip(new Tooltip(
            "Curve plane: 0 = flat turn (positive angle = left), 90 = pull up,"
            + " 180 = flat turn right, 270 = drop; negative angle mirrors"));

        // roll (v11.1): body rate like the roll lane, or a bank held
        // against the HORIZON (global 0 = rails stay level through
        // slopes and turns — the station/brake default)
        ToggleGroup rollGrp = new ToggleGroup();
        RadioButton rbRate = new RadioButton("Rate (°/s)");
        RadioButton rbGlob = new RadioButton("Global bank (°)");
        rbRate.setToggleGroup(rollGrp);
        rbGlob.setToggleGroup(rollGrp);
        rbRate.setTooltip(new Tooltip(
            "Body roll about the track at this rate, like the roll lane"
            + " (0 = never twist: bank drifts through pitched turns)"));
        rbGlob.setTooltip(new Tooltip(
            "Hold this bank against the horizon (0 = rails stay level),\n"
            + "eased in over the entry smoothing."));
        (el.rollMode == StraightCurveSection.RollMode.GLOBAL ? rbGlob : rbRate)
            .setSelected(true);
        TextField rollF = new TextField(String.format(Locale.US, "%.3f", el.rollVal));

        int row = 0;
        grid.add(new Label("Kind"), 0, row);
        grid.add(new HBox(10, rbS, rbC), 1, row++);
        Label lenL = new Label("Length (m)");
        grid.addRow(row++, lenL, lenF);
        Label radL = new Label("Radius (m)");
        grid.addRow(row++, radL, radF);
        Label angL = new Label("Angle (°)");
        grid.addRow(row++, angL, angF);
        Label dirL = new Label("Direction (°)");
        grid.addRow(row++, dirL, dirF);
        Label smL = new Label("Entry smoothing (m)");
        grid.addRow(row++, smL, smF);
        grid.add(new Label("Roll"), 0, row);
        grid.add(new HBox(10, rbRate, rbGlob), 1, row++);
        Label rollL = new Label("Roll value");
        grid.addRow(row++, rollL, rollF);
        if (idx == 0) {
            // EDITABLE since v19.3 (D232, user spec — "the small
            // smooth transition at the start"): element 0's value is
            // the SECTION-ENTRY roll-ease length (global-bank
            // elements ease from the incoming bank over it,
            // VERBATIM since D233 — 0 = an instant snap; only NEW
            // sections are created at 2 m). The κ profile's entry
            // stays sharp — the seam's continuity comes from the
            // chain.
            smL.setText("Entry roll ease (m)");
            smL.setTooltip(new Tooltip(
                "How far the bank blends in from the incoming bank at\n"
                + "the section's entry; 0 = an instant snap."));
        }
        for (javafx.scene.Node c : grid.getChildren()) {
            if (c instanceof TextField tf) {
                tf.setPrefColumnCount(7);
                // numeric-only entry (v18.1, user spec — every number
                // box accepts digits, sign and period alone)
                tf.setTextFormatter(FvdDesigner.signedNumFormatter());
            }
        }

        Runnable sync = () -> {
            boolean st = rbS.isSelected();
            lenF.setDisable(!st);
            lenL.setDisable(!st);
            radF.setDisable(st);
            radL.setDisable(st);
            angF.setDisable(st);
            angL.setDisable(st);
            dirF.setDisable(st);
            dirL.setDisable(st);
        };
        sync.run();
        rbS.setOnAction(ev -> sync.run());
        rbC.setOnAction(ev -> sync.run());

        Button ok = new Button("Apply");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 2, 1);

        // Apply-only dialog: the element is RE-RESOLVED BY INDEX at
        // Apply (v19.18, D247) — an undo/redo replaces the Section /
        // Element objects, and writing the open-time capture was a
        // silent orphan write. The seed fields deliberately stay as
        // opened (a live overwrite would fight the user's typing).
        final int elPiece = activePiece, elSec = activeSection;
        ok.setOnAction(ev -> {
            StraightCurveSection.Element e2 = null;
            if (elPiece < app.coaster.pieces.size()) {
                List<Section> ss = app.coaster.pieces.get(elPiece).sections;
                if (elSec < ss.size()
                        && ss.get(elSec) instanceof StraightCurveSection s2
                        && idx < s2.elements.size()) {
                    e2 = s2.elements.get(idx);
                }
            }
            if (e2 == null) {
                dlg.close();
                app.statsLabel.setText("Element not applied — that "
                    + "element no longer exists (the section changed "
                    + "while the dialog was open).");
                return;
            }
            final StraightCurveSection.Element el2 = e2;
            pushUndoSnapshot();
            if (rbS.isSelected()) {
                double keep = el2.kind == StraightCurveSection.Kind.STRAIGHT
                    ? el2.length : el2.arcLen();
                el2.kind = StraightCurveSection.Kind.STRAIGHT;
                el2.length = Math.max(MIN_ELEM_LEN, parseOr(lenF, keep));
            } else {
                el2.kind = StraightCurveSection.Kind.CURVE;
                el2.radius = Math.max(0.1, parseOr(radF, el2.radius > 0 ? el2.radius : 20));
                double ang = parseOr(angF, el2.angleDeg != 0 ? el2.angleDeg : 90);
                if (Math.abs(ang) < 0.5) ang = ang < 0 ? -0.5 : 0.5;
                el2.angleDeg = ang;
                el2.dirDeg = parseOr(dirF, el2.dirDeg);
            }
            el2.smoothIn = Math.max(0, parseOr(smF, el2.smoothIn));   // elem 0
                // = the entry roll ease since v19.3 (D232)
            el2.rollMode = rbGlob.isSelected()
                ? StraightCurveSection.RollMode.GLOBAL
                : StraightCurveSection.RollMode.RATE;
            el2.rollVal = parseOr(rollF, el2.rollVal);
            if (activePiece == elPiece && activeSection == elSec) elSel = idx;
            app.rebuildTrack();
            refreshOffset();
            redraw();
            dlg.close();
        });
        cancel.setOnAction(ev -> dlg.close());

        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installDocUndoKeys(dlg.getScene());   // D227: global undo + ride keys
    }

    private static final int LANES = 3;
    private static final String[] LANE_NAMES = { "Vertical G", "Lateral G", "Roll rate (deg/s)" };
    private static final String[] GEO_LANE_NAMES = { "Pitch rate (deg/s)", "Yaw rate (deg/s)", "Roll rate (deg/s)" };
    private static final String[] LANE_UNITS = { " g", " g", " °/s" };
    // default visible ranges; the axis auto-expands (1-2-5 rounded)
    // whenever the curve exceeds them within the current time window
    private static final double[] DEF_MIN = { -1, -1, -150 };
    private static final double[] DEF_MAX = {  4,  1,  150 };
    // geo mode: all three lanes are rates — pitch/yaw default tighter
    // than roll (±150 would dwarf typical pitch curves)
    private static final double[] GEO_DEF_MIN = { -60, -60, -150 };
    private static final double[] GEO_DEF_MAX = {  60,  60,  150 };
    private final double[] curMin = DEF_MIN.clone();
    private final double[] curMax = DEF_MAX.clone();

    private static final double MARGIN_LEFT = 58, MARGIN_RIGHT = 14;
    // widened right margin while the RESULTING axis is shown (v10.3)
    private static final double MARGIN_RIGHT_RES = 48;

    // the resulting overlays' OWN value scale (v10.3): one shared axis
    // for the enabled curves (they share units per mode — °/s over a
    // force section, g over a geo one), fitted like the lane scales and
    // labeled on the RIGHT edge. resAxisOn also widens the margin.
    private boolean resAxisOn = false;
    private double resLo = -60, resHi = 60;
    // the ROLL/BANK overlay's OWN normalization pair (v18.42, D214):
    // whenever comp 3's units differ from comps 1/2 (geo view = g vs
    // °/s; abs bank = ° vs rates) it fits itself instead of
    // stretching the shared axis — a legit −112 °/s roll rate used
    // to blow the felt axis to "−125 g" over geo sections
    private double resLo3 = -60, resHi3 = 60;

    private double marginRight() {
        return resAxisOn || rateViewAxisOn() ? MARGIN_RIGHT_RES
                                             : MARGIN_RIGHT;
    }

    // hover readout on a resulting curve: comp 0 = none, 1/2 = the
    // pitch-or-Gv / yaw-or-Gl overlay under the cursor
    private int hoverResComp = 0;
    private double hoverResT, hoverResVal;
    // hover readout on a DERIVED strip curve (v13.5): a bridge/s-c
    // section's felt-G/roll curves answer like lanes — value, or the
    // slope while Shift is held. -1 = none.
    private int hoverDerLane = -1;
    private double hoverDerT, hoverDerVal;
    private static final double MARGIN_TOP = 8, MARGIN_BOTTOM = 32;
    private static final double NODE_RADIUS = 5, NODE_HIT = 9;
    private static final double KNOB_RADIUS = 4.5, KNOB_HIT = 9;
    private static final double HANDLE_ALPHA = 0.45;
    private static final double INACTIVE_ALPHA = 0.25;
    private static final double MIN_GAP = 0.01;   // minimum transition length
    private static final double INSERT_GAP = 0.02; // insert may get almost as close
    private static final double MIN_FRAC = 0.02;

    // node-drag snap (with Ctrl): TRANSITION length in time, value on a
    // GLOBAL absolute grid — the steps are adjustable in Edit > Snapping.
    // In a geo section every lane is a rate, so all snap by the roll step.
    private double snapT() { return app.settings.snapT(); }
    private double snapVal(int lane) {
        if (surfStrip) {   // 1 m/s speed grid, 0.1 g accel grid (v18.1)
            return surfCtl.type == SpeedControl.ACCEL
                ? SP_SNAP_A : SP_SNAP_V;
        }
        // a lane DISPLAYED in °/m takes its own, finer step (v16.10,
        // user spec — the DISPLAYED units govern, so the cross-rate
        // view counts: Ctrl-snap grids live in display units)
        if (laneShownPM(lane)) return app.settings.snapRatePM();
        return (lane == 2 || activeGeo())
            ? app.settings.snapRoll() : app.settings.snapG();
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

    // the NAMED ease fractions alone — the node editor's handle
    // boxes stop on them while stepping (v19.11, D240; capitalized
    // for the box tag)
    private static final double[] EASE_STOP_VALS;
    private static final String[] EASE_STOP_NAMES;
    static {
        int n = 0;
        for (String s : HANDLE_PRESET_NAMES) {
            if (s != null) n++;
        }
        EASE_STOP_VALS = new double[n];
        EASE_STOP_NAMES = new String[n];
        int j = 0;
        for (int i = 0; i < HANDLE_PRESET_NAMES.length; i++) {
            if (HANDLE_PRESET_NAMES[i] != null) {
                EASE_STOP_VALS[j] = HANDLE_PRESETS[i];
                EASE_STOP_NAMES[j] =
                    Character.toUpperCase(HANDLE_PRESET_NAMES[i].charAt(0))
                    + HANDLE_PRESET_NAMES[i].substring(1);
                j++;
            }
        }
    }

    private final Canvas canvas = new Canvas();

    private int activeLane = 0;
    private int selIdx = -1;

    private int dragNodeIdx = -1;
    private int dragHandleIdx = -1;
    private boolean dragHandleIsOut = false;
    private double hoverT = Double.NaN;   // insert preview position on the curve
    // SEAM hover/click feedback (D136): the boundary index under the
    // cursor, and the one whose menu is open (stays highlighted)
    private int hoverSeam = -1;
    private int openSeam = -1;
    // seam-DIAMOND hover (v19.8, D237): gray center + side carets
    private int hoverSeamDia = -1;
    // BADGE hover (D223): the runLayout index whose section-settings
    // badge is under the cursor
    private int hoverBadge = -1;
    private int hoverNodeIdx = -1;        // grey ring + readout (v16.15)
    private boolean hoverSlope = false;   // Shift while hovering: show the slope

    // x-axis view window in AXIS units (seconds or meters, v16.0);
    // viewT1 < 0 = auto (whole ride, follows edits)
    private double viewT0 = 0, viewT1 = -1;
    // last redraw's axis domain — a flip resets to auto-fit (the
    // window's units just changed meaning)
    private boolean lastAxisDist = false;
    private double panLastX = Double.NaN;
    // window frozen for the duration of a drag gesture so the mapping
    // under the cursor stays stable while the ride length changes
    private double dragWinT0 = Double.NaN, dragWinT1;

    // horizontal WALL for the live node drag (v10.2): the section end as
    // established by the OTHER lanes — see endLimitFor(). MAX = no wall.
    private double dragEndLimit = Double.MAX_VALUE;

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
            if (surfGesture) {
                // STRIP-surface gestures grow the STRIP's value scale
                // instead of the plot axes (v18.6, D175 — the main
                // plot's edge-growth behavior on the second surface):
                // overshoot past the strip's top/bottom expands the
                // edited control's scale at a proportional rate. The
                // x axes stay put — the control is a hard box.
                if (dragTimerLast == 0) { dragTimerLast = now; return; }
                double sdt = Math.min((now - dragTimerLast) / 1e9, 0.05);
                dragTimerLast = now;
                if (dragNodeIdx < 0 && dragHandleIdx < 0) return;
                SpeedControl c = spSel();
                if (c == null) return;
                double top = spTop() + 4, bot = spBottom() - 4;
                double hgt = Math.max(40, bot - top);
                boolean grew = false;
                if (lastDragY < top) {
                    double f = 1 + (top - lastDragY) * 3 * sdt / hgt;
                    if (c.type == SpeedControl.ACCEL) spAHi *= f;
                    else spVHi *= f;
                    grew = true;
                } else if (lastDragY > bot
                        && c.type == SpeedControl.ACCEL) {
                    // below the line only the accel scale has room —
                    // the speed scale's zero sits ON the line
                    spALo -= (lastDragY - bot) * 3 * sdt
                        * (spAHi - spALo) / hgt;
                    grew = true;
                }
                if (grew) {
                    commitGestureSnapshot();
                    if (dragNodeIdx >= 0) {
                        withSurf(() -> dragNodeTo(lastDragX, lastDragY,
                            lastDragSnap, lastDragShift));
                    } else {
                        withSurf(() -> dragHandleTo(lastDragX, lastDragY,
                            lastDragShift, lastDragSnap));
                    }
                }
                return;
            }
            if (dragTimerLast == 0) { dragTimerLast = now; return; }
            double dt = Math.min((now - dragTimerLast) / 1e9, 0.05);
            dragTimerLast = now;
            if (dragNodeIdx < 0 && dragHandleIdx < 0
                    && elDragBoundary < 0 && elDragSmooth < 1
                    && !bridgeDragGap) return;
            double plotRight = MARGIN_LEFT + plotWidth();
            double plotBottom = MARGIN_TOP + plotHeight();
            double winW = dragWinT1 - dragWinT0;
            double vRange = curMax[activeLane] - curMin[activeLane];
            boolean grew = false;
            if (lastDragX > plotRight) {
                dragWinT1 += (lastDragX - plotRight) * 3 * dt * winW / plotWidth();
                grew = true;
            } else if (lastDragX < MARGIN_LEFT && dragWinT0 > runT0()) {
                dragWinT0 = Math.max(runT0(),
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
                } else if (dragHandleIdx >= 0) {
                    dragHandleTo(lastDragX, lastDragY, lastDragShift, lastDragSnap);
                } else if (bridgeDragGap) {
                    bridgeDragTo(lastDragX, lastDragSnap);
                } else {
                    scDragTo(lastDragX, lastDragSnap);
                }
            }
        }
    };

    // box select (rubber band on empty plot; shift adds to selection)
    private double boxX0 = Double.NaN, boxY0, boxX1, boxY1;
    private boolean boxAdd;

    // right-click on a segment opens its lock menu — deferred to a
    // clean RELEASE so right-drag pan still works from the curve (v10.7)
    private int rClickSeg = -1;
    private double rPressX, rPressY;

    // Ctrl held while hovering the curve: the insert dot snaps in TIME
    // (the horizontal snap reference applies; value rides the curve)
    private boolean ctrlHover = false;
    private double hoverRawT = Double.NaN;   // unsnapped hover position

    // an off-screen handle draws as a dashed stub with a hollow marker
    // at the plot edge; clicking the marker expands the value scale to
    // bring the whole handle into view (cleared when selection changes)
    private boolean handleViewExpand = false;
    private double[] outEdgeMarker, inEdgeMarker;   // set during drawHandles

    // multi-selection (shift+click adds); selIdx stays the primary node
    private final java.util.Set<Integer> selSet = new HashSet<>();

    private long lastNudgeNanos = 0;   // coalesces arrow-key undo snapshots

    // DOCUMENT-level history (D51): snapshots carry lanes + the
    // path-shaping settings (heart/gauge/start/v0), not lanes alone
    private final Deque<FvdDesigner.DocState> undoStack = new ArrayDeque<>();
    private final Deque<FvdDesigner.DocState> redoStack = new ArrayDeque<>();
    private FvdDesigner.DocState pendingSnapshot = null;
    private boolean gestureMoved = false;
    // press point of the current canvas gesture — the CLICKED
    // handler's drag deadband (v18.34, D206: a dragged "second
    // click" must not open the node dialog)
    private double clkPressX = Double.NaN, clkPressY = Double.NaN;
    private static final int UNDO_LIMIT = 100;

    GraphEditor(FvdDesigner app) {
        this.app = app;
        setPrefHeight(260);
        setStyle("-fx-background-color: rgb(20,22,28);");
        getChildren().add(canvas);

        // the 1-6 view keys' hover context (v18.11): over the GRAPH,
        // 1/2/3 stay lane switches and 6 aims at the last-hovered pane
        canvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED,
            e -> { app.mouseOverGraph = true; app.mouseOverView = false; });
        canvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED,
            e -> { app.mouseOverGraph = true; app.mouseOverView = false; });
        canvas.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED,
            e -> app.mouseOverGraph = false);

        canvas.setOnMousePressed(e -> {
            canvas.requestFocus();   // pull focus off toolbar editors
            // per-event rebuilds inside this gesture run the FAST
            // reconcile only; the release settles exactly (D200)
            app.gestureHot = true;
            app.pointerDown = true;
            clkPressX = e.getX();   // the clicked-handler's drag guard
            clkPressY = e.getY();   // (v18.34, D206)
            hoverT = Double.NaN;   // no hover previews while interacting
            hoverRawT = Double.NaN;
            hoverResComp = 0;
            hoverDerLane = -1;
            hoverNodeIdx = -1;
            // an open '+'/badge menu: this press just dismisses it (JavaFX
            // auto-hides on clicks anywhere EXCEPT this canvas — v10.1)
            if (openMenu != null && openMenu.isShowing()) {
                openMenu.hide();
                openMenu = null;
                return;
            }
            // SPEED CONTROL STRIP presses (v18.0; shared pipeline
            // v18.3): the strip owns everything inside it
            if (spPress(e)) return;
            if (e.getButton() == MouseButton.PRIMARY
                    && e.getY() >= MARGIN_TOP
                    && e.getY() <= MARGIN_TOP + plotHeight()) {
                // a PLOT press takes the selection over — the shared
                // node selection belongs to one surface at a time
                // (v18.3); THE one choke since v19.20 (D249): the
                // strip's control/mark unlights as well, not just
                // spFocus (node and control selection are exclusive).
                // PRIMARY only (D249 review fix): a right-drag pan, a
                // middle press or a right-click menu open selects
                // nothing on the plot — the plot's own node selection
                // deliberately survives a pan (deselect on RELEASE ≤
                // 3 px), so a lit control survives it too; the
                // secondary presses that DO select (the node menu, an
                // element menu) call the choke themselves.
                takePlotSelection();
            }
            if (e.getButton() == MouseButton.PRIMARY) {
                // the density preview owns the plot while its popup is
                // open: consume plot presses wholesale — tune or
                // Apply/Cancel there; view pan/zoom stay live, they
                // don't touch the document (v14.0, D90)
                if (pvBefore != null && e.getY() >= MARGIN_TOP
                        && e.getY() <= MARGIN_TOP + plotHeight()) {
                    // lane-SWITCH clicks stay live (v19.11, D240 —
                    // the v16.7 1/2/3-key rule extended to the
                    // mouse: inspect the fitted carpet on every
                    // lane before Apply): a dimmed lane's node or
                    // curve inside the PREVIEW'S OWN strip switches
                    // the viewed lane — read-only, no selection, no
                    // gesture; everything else stays consumed
                    double pgt = xToT(e.getX());
                    if (activeSc() == null && activeBridge() == null
                            && pgt >= tOff - 1e-9
                            && pgt <= tOff + sectionEnd() + 1e-9) {
                        int[] pvOther = hitOtherLane(e.getX(), e.getY());
                        if (pvOther != null) setActiveLane(pvOther[0]);
                    }
                    return;
                }
                // partial-convert mode owns its strip in ANY mode
                // (v13.11 bridges; v14.0 all): '+' and badges keep
                // their priority (the v13.11 rank), then chips, the
                // line, the lane-strip NODE SNAP, then side selection
                if (pcSec == activeSection && pcSec >= 0) {
                    if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
                        plusPressed(e.getScreenX(), e.getScreenY());
                        return;
                    }
                    int badge = hitBadge(e.getX(), e.getY());
                    if (badge >= 0) {
                        showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    if (e.getY() >= MARGIN_TOP
                            && e.getY() <= MARGIN_TOP + plotHeight()) {
                        int chip = pcChipHit(e.getX(), e.getY());
                        if (chip == 1) { applyPartialConvert(); return; }
                        if (chip == 2) { cancelPartialConvert(); return; }
                        if (chip == 3) {
                            // the side chip TOGGLES which half
                            // converts (v19.10, D239 — like clicking
                            // the other side; same flash)
                            pcLeft = !pcLeft;
                            pcFlashNanos = System.nanoTime();
                            redraw();
                            return;
                        }
                        if (Math.abs(e.getX() - pcLineX()) <= 6) {
                            pcDragLine = true;
                            return;
                        }
                        double st0 = tToX(tOff);
                        double st1 = tToX(tOff + sectionDur(activeSection));
                        if (e.getX() >= st0 && e.getX() <= st1) {
                            double snapT = pcNodeSnapT(e.getX(), e.getY());
                            if (!Double.isNaN(snapT)) {
                                // clicking directly ON an active-lane
                                // node moves the LINE onto it (v14.0;
                                // direct-hit + active lane only since
                                // v14.4) — the cut then partitions
                                // the lanes exactly there
                                pcFrac = snapT
                                    / Math.max(sectionDur(activeSection), 1e-6);
                                pcFlashNanos = System.nanoTime();
                                redraw();
                                return;
                            }
                            pcLeft = e.getX() < pcLineX();
                            pcFlashNanos = System.nanoTime();
                            redraw();
                            return;
                        }
                    }
                }
                // a straight/curve section has its own surface (v11.0)
                if (activeSc() != null) {
                    scPrimaryPressed(e);
                    return;
                }
                // a bridge has no node surface: '+', badges, the
                // draggable END LINE (gap, v13.3) and strip switches
                if (activeBridge() != null) {
                    if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
                        plusPressed(e.getScreenX(), e.getScreenY());
                        return;
                    }
                    int badge = hitBadge(e.getX(), e.getY());
                    if (badge >= 0) {
                        showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    if (hitBridgeEnd(e.getX(), e.getY())) {
                        beginGesture();
                        bridgeDragGap = true;
                        lastDragX = e.getX();
                        lastDragY = e.getY();
                        lastDragSnap = e.isControlDown();
                        dragTimerLast = 0;
                        dragGrowTimer.start();
                        return;
                    }
                    if (seamDiamondPressed(e)) return;
                    // seam lines elsewhere in the run (v17.1; bridge-
                    // adjacent boundaries are excluded by the hit)
                    int bseam = hitSeam(e.getX(), e.getY());
                    if (bseam >= 0) {
                        showSeamMenu(bseam, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    if (seamEdgePressed(e)) return;
                    RunSec rs = runSecAt(xToT(e.getX()));
                    if (rs != null && (rs.piece() != activePiece
                            || rs.section() != activeSection)) {
                        activateRunSec(rs.piece(), rs.section());
                    }
                    return;
                }
                if (pressNodesKnobs(e)) return;
                {
                    // the '+' add-section affordance — tested AFTER the
                    // node/knob hits so an overlapping node stays
                    // grabbable (D57); the button's exposed edge still
                    // opens the menu
                    if (Math.hypot(e.getX() - addBtnX, e.getY() - addBtnY) <= 10) {
                        plusPressed(e.getScreenX(), e.getScreenY());
                        return;
                    }
                    // section type badges (same press rank as the '+')
                    int badge = hitBadge(e.getX(), e.getY());
                    if (badge >= 0) {
                        showRunBadgeMenu(badge, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    if (seamDiamondPressed(e)) return;
                    // a SEAM line between strips (v17.1, D135) — after
                    // nodes/knobs/badges (edge nodes stay grabbable),
                    // before the curve insert (inserting exactly ON a
                    // boundary is refused by INSERT_GAP anyway)
                    int seam = hitSeam(e.getX(), e.getY());
                    if (seam >= 0) {
                        showSeamMenu(seam, e.getScreenX(), e.getScreenY());
                        return;
                    }
                    // a click ON the active curve inserts a node there —
                    // and keeps DRAGGING it in the same gesture (v10.1:
                    // press-insert-drag is one motion; Ctrl held from the
                    // start snaps the drag as usual). The insert's undo
                    // snapshot covers the whole gesture.
                    if (pressCurveInsert(e)) return;
                    // smoothing-band EDGES (v17.4, D141): the lowest
                    // interactive rank — after nodes/knobs, '+',
                    // badges, the seam line and the curve insert
                    if (seamEdgePressed(e)) return;
                    // a press inside a GREYED section's strip ALWAYS
                    // activates that section — checked BEFORE the dimmed-
                    // lane test (v10.2): the active section's lanes hold
                    // their end values across the whole axis, so a click
                    // on another strip's curve used to get eaten as a
                    // lane switch whenever it lined up with a held value
                    double gt = xToT(e.getX());
                    if (gt < tOff - 1e-9 || gt > tOff + sectionEnd() + 1e-9) {
                        RunSec rs = runSecAt(gt);
                        if (rs != null && (rs.piece() != activePiece
                                || rs.section() != activeSection)) {
                            activateRunSec(rs.piece(), rs.section());
                            return;
                        }
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
                        pressBoxStart(e);
                    }
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                if (activeBridge() != null) {   // pan only — no nodes
                    rPressX = e.getX();
                    rPressY = e.getY();
                    panLastX = e.getX();
                    return;
                }
                if (activeSc() != null) {
                    // right-click an ELEMENT = its menu, deferred to a
                    // clean release exactly like the segment lock menu
                    double gt = xToT(e.getX());
                    rClickElem = gt >= tOff - 1e-9
                            && gt <= tOff + sectionEnd() + 1e-9
                            && e.getY() >= MARGIN_TOP
                            && e.getY() <= MARGIN_TOP + plotHeight()
                        ? elementAtT(activeSc(), gt) : -1;
                    rPressX = e.getX();
                    rPressY = e.getY();
                    panLastX = e.getX();   // right-drag = pan
                    return;
                }
                int hit = hitNode(e.getX(), e.getY());
                if (hit >= 0) {
                    // right-click a node = its OPTIONS menu (v16.18,
                    // user spec — v10.7's direct dialog lives inside
                    // it as "Edit node…"). The clicked node joins or
                    // starts the selection the ops apply to — a
                    // SELECTING secondary press, so the choke runs
                    // here (D249 review fix: the press-time choke is
                    // primary-only)
                    takePlotSelection();
                    selIdx = hit;
                    if (!selSet.contains(hit)) {
                        selSet.clear();
                        selSet.add(hit);
                    }
                    redraw();
                    showNodeMenu(hit, e.getScreenX(), e.getScreenY());
                    return;
                }
                // right-click a SEGMENT = its lock menu — but only on a
                // clean click: the menu is deferred to release so that
                // right-DRAG still pans even when it starts on the curve
                rClickSeg = segmentAt(curveHitT(e.getX(), e.getY()));
                rPressX = e.getX();
                rPressY = e.getY();
                panLastX = e.getX();   // right-drag = pan
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
            double n0 = FvdDesigner.clamp(tm - (tm - t0) / width * newW,
                runT0(), runT0() + domain() - newW);
            viewT0 = n0;
            viewT1 = n0 + newW;
            redraw();
        });

        canvas.setOnMouseMoved(e -> {
            hoverSlope = e.isShiftDown();
            ctrlHover = e.isControlDown();
            // SPEED STRIP hover/cursor (v18.0; shared hover v18.3)
            if (spMove(e)) return;
            if (spHoverSurf) {
                spHoverSurf = false;   // hover left the strip
                hoverT = Double.NaN;
                hoverRawT = Double.NaN;
                hoverNodeIdx = -1;
            }
            boolean pcLine = pcSec == activeSection && pcSec >= 0
                && Math.abs(e.getX() - pcLineX()) <= 6
                && e.getY() >= MARGIN_TOP
                && e.getY() <= MARGIN_TOP + plotHeight()
                // the chips outrank the line on press (D239's second
                // row can sit on the line's column) — no resize
                // cursor over them
                && pcChipHit(e.getX(), e.getY()) == 0;
            // SEAM hover (D136, user spec): the boundary line
            // highlights + the cursor turns to a hand so it reads as
            // clickable. Drag surfaces keep priority (their cursor
            // wins below); nodes/knobs win at press time anyway.
            int hs = pcSec >= 0 ? -1 : hitSeam(e.getX(), e.getY());
            if (hs != hoverSeam) {
                hoverSeam = hs;
                redraw();
            }
            // BADGE hover (D223): the section settings button lights
            // up + hand cursor, same rules as the seam line
            int hb = pcSec >= 0 ? -1 : hitBadge(e.getX(), e.getY());
            if (hb != hoverBadge) {
                hoverBadge = hb;
                redraw();
            }
            // seam-DIAMOND hover (v19.8, D237): the hollow diamond
            // grows its gray center + side carets
            int hd = pcSec >= 0 ? -1 : hitSeamDiamond(e.getX(), e.getY());
            if (hd != hoverSeamDia) {
                hoverSeamDia = hd;
                redraw();
            }
            // node/knob computed BEFORE the cursor (D237, user
            // report): nodes outrank the band edge at PRESS time, so
            // the cursor must not read H_RESIZE over a node sitting
            // on a smoothing-window edge
            int hn = activeSc() == null && activeBridge() == null
                ? hitNode(e.getX(), e.getY()) : -1;
            boolean overKnob = hn < 0 && selIdx >= 0
                && activeSc() == null && activeBridge() == null
                && hitKnob(e.getX(), e.getY()) != 0;
            boolean hand = hs >= 0 || hb >= 0;
            if (pcLine || (hn < 0 && !overKnob
                    && (hd >= 0
                        || seamEdgeHit(e.getX(), e.getY()) >= 0))) {
                // the split line + the seam smoothing diamond/edges
                // (v17.3/v17.4)
                canvas.setCursor(Cursor.H_RESIZE);
            } else if (activeSc() != null) {   // grab cursor over drag targets
                boolean grab = hitScBoundary(e.getX(), e.getY()) >= 1
                    || hitScDiamond(e.getX(), e.getY()) >= 0
                    || scEdgeHit(e.getX(), e.getY()) >= 0;
                canvas.setCursor(grab ? Cursor.H_RESIZE
                    : hand ? Cursor.HAND : Cursor.DEFAULT);
            } else if (activeBridge() != null) {   // the end line drags
                canvas.setCursor(hitBridgeEnd(e.getX(), e.getY())
                    ? Cursor.H_RESIZE
                    : hand ? Cursor.HAND : Cursor.DEFAULT);
            } else {
                canvas.setCursor(hand ? Cursor.HAND : Cursor.DEFAULT);
            }
            double t = (activeSc() == null && activeBridge() == null
                    && hn < 0
                    && (selIdx < 0 || hitKnob(e.getX(), e.getY()) == 0))
                    ? curveHitT(e.getX(), e.getY()) : Double.NaN;
            hoverRawT = t;
            t = snappedHover(t);
            // resulting-curve hover (v10.3): when the insert dot isn't
            // claiming the spot, a resulting overlay within a few px
            // vertically shows its value readout instead
            int rc = 0;
            double rt = 0, rv = 0;
            if (Double.isNaN(t) && resAxisOn) {
                double gt = xToT(e.getX());   // AXIS coordinate (v16.0)
                double rT0 = runT0(), rT1 = rT0 + runDur();
                int i = sampleIdxAtAxis(gt);
                double trim = axisTrim(i);
                if (gt > rT0 + trim && gt < rT1 - trim
                        && !nearSeam(gt, sectionBoundaries(), trim)) {
                    double best = 8;
                    for (int comp = 1; comp <= 3; comp++) {
                        if (!resCompOn(comp)) continue;
                        final int fc = comp;
                        // interpolated (D233): the dot follows the
                        // drawn curve, not the nearest sample's step
                        double v = interpAtAxis(gt, j -> resValueAt(j, fc));
                        double d = Math.abs(e.getY() - resToY(comp, v));
                        if (d < best) { best = d; rc = comp; rt = gt; rv = v; }
                    }
                }
            }
            // a bridge/s-c strip's DERIVED curves take hover readouts
            // too (v13.5) — unless the cursor owns a drag handle there.
            // The influence surface's dotted RESULTING curves join in
            // (v15.0: the compiled curves ARE base+delta) — Influence
            // view only; in Resulting view the lane hover owns the sum.
            int dl = -1;
            double dlt = 0, dlv = 0;
            if ((activeSc() != null || activeBridge() != null
                    || (activeInfl() != null && Double.isNaN(t)))
                    && canvas.getCursor() != Cursor.H_RESIZE
                    && app.resultRates != null && app.lastSamples != null
                    && app.resultRates.length > 0
                    && e.getY() >= MARGIN_TOP
                    && e.getY() <= MARGIN_TOP + plotHeight()) {
                double gt = xToT(e.getX());   // AXIS coordinate (v16.0)
                double dur = sectionDur(activeSection);
                int i = sampleIdxAtAxis(gt);
                double trim = axisTrim(i);
                if (gt > tOff + trim && gt < tOff + dur - trim) {
                    double bestPx = 8;
                    for (int L = 0; L < LANES; L++) {
                        final int fl = L;
                        // interpolated (D233) — see the overlay hover
                        double v = interpAtAxis(gt, j -> derivedAt(j, fl));
                        double d = Math.abs(e.getY() - valToY(L, v));
                        if (d < bestPx) {
                            bestPx = d;
                            dl = L;
                            dlt = gt;
                            dlv = v;
                        }
                    }
                }
            }
            boolean was = !Double.isNaN(hoverT) || hoverResComp != 0
                || hoverDerLane >= 0 || hoverNodeIdx >= 0;
            boolean is = !Double.isNaN(t) || rc != 0 || dl >= 0 || hn >= 0;
            hoverT = t;
            hoverNodeIdx = hn;
            hoverResComp = rc;
            hoverResT = rt;
            hoverResVal = rv;
            hoverDerLane = dl;
            hoverDerT = dlt;
            hoverDerVal = dlv;
            if (was || is) redraw();
        });
        canvas.setOnMouseExited(e -> {
            if (!Double.isNaN(hoverT) || hoverResComp != 0
                    || hoverDerLane >= 0 || hoverNodeIdx >= 0
                    || hoverBadge >= 0 || hoverSeam >= 0
                    || hoverSeamDia >= 0) {
                hoverT = Double.NaN;
                hoverRawT = Double.NaN;
                hoverResComp = 0;
                hoverDerLane = -1;
                hoverNodeIdx = -1;
                hoverBadge = -1;
                hoverSeam = -1;
                hoverSeamDia = -1;
                redraw();
            }
        });

        canvas.setOnMouseDragged(e -> {
            if (spDrag(e)) return;   // divider + diamond drags (v18.0)
            if (pcDragLine && e.isPrimaryButtonDown()) {
                // the line MAGNETIZES onto nearby ACTIVE-lane nodes on
                // lane strips (v14.0; 5 px + active lane only, v14.4)
                double snapT = pcNodeSnapT(e.getX(), -1);
                if (!Double.isNaN(snapT)) {
                    pcFrac = snapT
                        / Math.max(sectionDur(activeSection), 1e-6);
                } else {
                    pcFrac = pcFracAtX(e.getX());
                }
                redraw();
                return;
            }
            if (seamDragPiece >= 0 && e.isPrimaryButtonDown()) {
                // 3 px deadband on the DIAMOND (v17.17, D158): a
                // clean click opens the settings on release and must
                // not commit a micro-resize on the way; band edges
                // (seamDragRel false) keep dragging immediately
                if (seamDragRel && Math.hypot(e.getX() - seamPressX,
                        e.getY() - seamPressY) <= 3) {
                    return;
                }
                seamDragTo(e.getX(), e.isControlDown());
                return;
            }
            if ((elDragBoundary >= 0 || elDragSmooth >= 0 || bridgeDragGap)
                    && e.isPrimaryButtonDown()) {
                commitGestureSnapshot();
                lastDragX = e.getX();
                lastDragY = e.getY();
                lastDragSnap = e.isControlDown();
                lastDragShift = app.slideKey;
                if (bridgeDragGap) bridgeDragTo(e.getX(), e.isControlDown());
                else scDragTo(e.getX(), e.isControlDown());
                return;
            }
            if (!Double.isNaN(boxX0) && e.isPrimaryButtonDown()) {
                boxX1 = e.getX();
                boxY1 = e.getY();
                redraw();
                return;
            }
            if (!Double.isNaN(panLastX) && e.isSecondaryButtonDown()) {
                if (Math.hypot(e.getX() - rPressX, e.getY() - rPressY) > 3) {
                    rClickSeg = -1;   // it's a pan, not a segment/element click
                    rClickElem = -1;
                }
                double t0 = viewStart(), t1 = viewEnd();
                if (t1 - t0 < domain()) {   // full view has nowhere to pan
                    double shift = (panLastX - e.getX()) * (t1 - t0) / plotWidth();
                    double n0 = FvdDesigner.clamp(t0 + shift,
                        runT0(), runT0() + domain() - (t1 - t0));
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
                lastDragShift = app.slideKey;
                // free by default; S (held) slides in-line, CTRL snaps
                if (surfGesture) {   // strip-surface gesture (v18.3)
                    withSurf(() -> dragHandleTo(e.getX(), e.getY(),
                        app.slideKey, e.isControlDown()));
                } else {
                    dragHandleTo(e.getX(), e.getY(), app.slideKey,
                        e.isControlDown());
                }
            } else if (dragNodeIdx >= 0) {
                commitGestureSnapshot();
                lastDragX = e.getX();
                lastDragY = e.getY();
                lastDragSnap = e.isControlDown();
                lastDragShift = app.slideKey;
                if (surfGesture) {   // strip-surface gesture (v18.3)
                    withSurf(() -> dragNodeTo(e.getX(), e.getY(),
                        e.isControlDown(), app.slideKey));
                } else {
                    dragNodeTo(e.getX(), e.getY(), e.isControlDown(),
                        app.slideKey);
                }
            }
        });

        canvas.setOnMouseReleased(e -> {
            dragGrowTimer.stop();
            // the gesture is over BEFORE any release logic runs (some
            // paths return early below): land the document on its
            // exact reconciled bits if the drag rebuilt hot (D200)
            app.pointerDown = false;
            app.gestureHot = false;
            app.settleIfPending();
            if (spRelease(e)) return;   // strip releases (v18.0)
            if (e.getButton() == MouseButton.SECONDARY && rClickElem >= 0
                    && Math.hypot(e.getX() - rPressX, e.getY() - rPressY) <= 3) {
                int el = rClickElem;
                rClickElem = -1;
                panLastX = Double.NaN;
                showElementMenu(el, e.getScreenX(), e.getScreenY());
                return;
            }
            rClickElem = -1;
            // SINGLE-CLICK on a smoothing diamond = the seam settings
            // (v17.17, D158 — was double-click); an actual drag
            // resized instead and never opens the window
            if (seamDragPiece >= 0 && seamDragRel
                    && e.getButton() == MouseButton.PRIMARY
                    && Math.hypot(e.getX() - seamPressX,
                        e.getY() - seamPressY) <= 3) {
                int fp = seamDragPiece;
                seamDragPiece = -1;
                // the diamond's clean click opens the full SEAM MENU
                // since v19.3 (D232, user clarification — it went
                // straight to the settings pop-out before)
                List<RunSec> rl = runLayout();
                for (int b = 0; b + 1 < rl.size(); b++) {
                    if (rl.get(b + 1).piece() == fp
                            && rl.get(b + 1).section() == 0) {
                        showSeamMenu(b, e.getScreenX(), e.getScreenY());
                        break;
                    }
                }
                return;
            }
            seamDragPiece = -1;
            elDragBoundary = -1;
            elDragSmooth = -1;
            scDragSide = 0;
            bridgeDragGap = false;
            pcDragLine = false;
            if (e.getButton() == MouseButton.SECONDARY && rClickSeg >= 0
                    && Math.hypot(e.getX() - rPressX, e.getY() - rPressY) <= 3) {
                int seg = rClickSeg;
                rClickSeg = -1;
                panLastX = Double.NaN;
                showSegmentLockMenu(seg, e.getScreenX(), e.getScreenY());
                return;
            }
            rClickSeg = -1;
            if (!Double.isNaN(boxX0)) {
                if (surfGesture) withSurf(this::finishBoxSelect);
                else finishBoxSelect();
            }
            dragNodeIdx = -1;
            dragHandleIdx = -1;
            dragEndLimit = Double.MAX_VALUE;
            dragPresetName = null;
            panLastX = Double.NaN;
            dragWinT0 = Double.NaN;   // unfreeze the time window
            shiftAxis = 0;
            pendingSnapshot = null;
            gestureMoved = false;
            surfGesture = false;      // the strip's gesture flag (v18.3)
            redraw();                 // view re-fits after the drag
        });

        canvas.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton() == MouseButton.PRIMARY) {
                // the density preview owns the plot (v19.11, D240 —
                // the press guard's double-click companion: the D240
                // lane-switch clicks made node-adjacent clicking
                // likelier, and the node editor's live writes would
                // be clobbered by the next preview re-run)
                if (pvBefore != null) return;
                if (spDoubleClick(e)) return;   // strip (v18.0/v18.3)
                // a DRAG is not a click (v18.34, D206): JavaFX counts
                // a press toward the double-click pair even when the
                // gesture then drags — after a lane-switch click, the
                // very next click-AND-DRAG of that node arrived here
                // with clickCount 2 and popped the node dialog on
                // release. The 3 px press-to-release deadband is the
                // seam-diamond convention (D158).
                if (Math.hypot(e.getX() - clkPressX,
                        e.getY() - clkPressY) > 3) {
                    return;
                }
                StraightCurveSection sc = activeSc();
                if (sc != null) {
                    double gt = xToT(e.getX());
                    if (gt >= tOff - 1e-9 && gt <= tOff + sectionEnd() + 1e-9
                            && e.getY() >= MARGIN_TOP
                            && e.getY() <= MARGIN_TOP + plotHeight()) {
                        showElementDialog(elementAtT(sc, gt));
                    }
                    return;
                }
                int hit = hitNode(e.getX(), e.getY());
                if (hit >= 0) showNodeEditor(hit);
            }
        });
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        // the START SCREEN overlay covers the whole editor (D226)
        if (startPanel != null) {
            startPanel.resizeRelocate(0, 0, getWidth(), getHeight());
        }
        if (noCoasterPanel != null) {
            noCoasterPanel.resizeRelocate(0, 0, getWidth(), getHeight());
        }
        if (emptyPiecePanel != null) {
            emptyPiecePanel.resizeRelocate(0, 0, getWidth(), getHeight());
        }
        redraw();
    }

    // -----------------------------------------------------------------
    // START SCREEN hosting (v19.1, D226): a JavaFX panel over the
    // canvas; while up, the graph's key actions are dead (the shell's
    // convert-UI rule) and canvas mouse never fires (it's covered)
    // -----------------------------------------------------------------
    private StartPanel startPanel;

    void showStartPanel(StartPanel p) {
        if (startPanel == p) return;   // idempotent (the sync calls it often)
        hideStartPanel();
        startPanel = p;
        getChildren().add(p);
        // MIN HEIGHT while the wizard shows (D231, user spec): the
        // editor never shrinks below the panel's comfortable size —
        // and grows to it at once when it was smaller. Pin to the
        // panel's REAL pref height since v19.12 (D241 — the fixed 240
        // dated from the D232 3-row grid; the D237/D238 rows grew the
        // content past it and the centered title rendered half above
        // the dark background, the "squished wizard" report).
        p.applyCss();
        double need = Math.max(StartPanel.MIN_HEIGHT, p.prefHeight(-1));
        setMinHeight(need);
        if (getHeight() < need) app.growEditorTo(need);
        requestLayout();
    }

    void hideStartPanel() {
        if (startPanel == null) return;
        getChildren().remove(startPanel);
        startPanel = null;
        setMinHeight(0);
        redraw();
    }

    boolean startPanelActive() { return startPanel != null; }

    // ---- NO-COASTER placeholder (v19.2, D230): the document holds no
    // coaster — the canvas is covered by a hint + "New coaster…" ----
    private StackPane noCoasterPanel;

    void setNoCoaster(boolean on) {
        if (on == (noCoasterPanel != null)) return;
        if (on) {
            Label l = new Label("No coaster in this file");
            l.setTextFill(Color.WHITE);
            l.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
            Label s = new Label("Coaster ▸ New coaster… adds one (Ctrl+Z restores a deleted one).");
            s.setTextFill(Color.rgb(160, 168, 190));
            s.setStyle("-fx-font-size: 11px;");
            Button b = new Button("New coaster…");
            b.setFocusTraversable(false);
            b.setOnAction(e -> app.openNewCoasterWizard(true));
            VBox box = new VBox(8, l, s, b);
            box.setAlignment(Pos.CENTER);
            noCoasterPanel = new StackPane(box);
            noCoasterPanel.setStyle("-fx-background-color: rgb(20,22,28);");
            getChildren().add(noCoasterPanel);
        } else {
            getChildren().remove(noCoasterPanel);
            noCoasterPanel = null;
        }
        requestLayout();
    }

    // ---- EMPTY-COASTER placeholder (v19.10, D239 — user spec: a
    // coaster whose last piece left, e.g. via Switch Coaster, had
    // no way to add one): a centered "Add Floating Piece" button
    // over the otherwise blank plot; clicking opens the New Track
    // Piece wizard (the FLOATING mode already falls back to the
    // origin/5 m/5 m/s placement with no piece to continue). ----
    private StackPane emptyPiecePanel;

    void setEmptyPiece(boolean on) {
        if (on == (emptyPiecePanel != null)) return;
        if (on) {
            Label l = new Label("This coaster has no track");
            l.setTextFill(Color.WHITE);
            l.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
            Button b = new Button("Add Floating Piece");
            b.setFocusTraversable(false);
            b.setOnAction(e -> app.showStartWizard(
                StartPanel.Mode.FLOATING));
            VBox box = new VBox(8, l, b);
            box.setAlignment(Pos.CENTER);
            emptyPiecePanel = new StackPane(box);
            emptyPiecePanel.setStyle(
                "-fx-background-color: rgb(20,22,28);");
            getChildren().add(emptyPiecePanel);
        } else {
            getChildren().remove(emptyPiecePanel);
            emptyPiecePanel = null;
        }
        requestLayout();
    }

    boolean noCoaster() { return app.coaster.pieces.isEmpty(); }

    /** Re-point the editing lanes at the (possibly replaced) active
     *  section — the wizard swaps section objects under the editor. */
    void repointActive() {
        if (app.coaster.pieces.isEmpty()) return;
        activePiece = (int) FvdDesigner.clamp(activePiece, 0,
            app.coaster.pieces.size() - 1);
        activeSection = (int) FvdDesigner.clamp(activeSection, 0,
            sections().size() - 1);
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        axisLock = 0;
        handleViewExpand = false;
        viewT0 = 0;
        viewT1 = -1;
        hoverT = Double.NaN;      // a swapped section under a live hover
        hoverRawT = Double.NaN;
        hoverNodeIdx = -1;
        syncLaneButtons();
        refreshOffset();
        redraw();
    }

    private List<BezierBaker.Node> active() {
        // THE editing-surface choke (v18.3, D172): the strip surface
        // retargets the whole node pipeline at a control's graph
        // editLane(): the untied complex ACCEL control's displayed
        // graph — accel or decel (v18.45, D217)
        return surfStrip ? surfCtl.editLane() : app.lanes.get(activeLane);
    }

    void refresh() {
        spAfterRebuild();   // the options popup follows compiles
        redraw();
    }

    /** A node or handle drag gesture is in progress (keyboard combos
     *  formed by held drag modifiers are suppressed while true). */
    boolean isDragging() { return dragNodeIdx >= 0 || dragHandleIdx >= 0; }

    /** Sync the 3D node markers with this editor's lane/selection. */
    void refreshMarkers() {
        if (app.coaster.pieces.isEmpty()) return;   // no coaster (D230)
        refreshOffset();
        // per-node GLOBAL RIDE TIMES (v16.0): distance-domain lanes'
        // node x is meters — the viewport needs the model's clock,
        // whatever the plot's axis shows
        List<BezierBaker.Node> lane = active();
        double[] times = new double[lane.size()];
        for (int i = 0; i < lane.size(); i++) {
            times[i] = nodeGlobalTime(lane.get(i).t);
        }
        app.refreshTrackMarkers(activeLane,
            spFocus ? java.util.Collections.emptySet() : selSet,
            spFocus ? -1 : selIdx, times);
    }

    /** Spine-level track position of the primary selected node, or
     *  null when nothing is selected (side-view centering). */
    Vec3 selectedTrackPoint() {
        FVDIntegrator.Sample sm = selectedSample();
        return sm == null ? null : sm.pos().sub(sm.U().scale(app.heartOffset));
    }

    /** Track direction at the primary selected node, or null (the side
     *  view aligns its lens tangent to this — D53). */
    Vec3 selectedTrackTangent() {
        FVDIntegrator.Sample sm = selectedSample();
        return sm == null ? null : sm.T();
    }

    private FVDIntegrator.Sample selectedSample() {
        if (selIdx < 0 || selIdx >= active().size()) return null;
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return null;
        refreshOffset();
        int idx = (int) FvdDesigner.clamp(Math.round(
            nodeGlobalTime(active().get(selIdx).t) / FvdDesigner.INTEGRATE_DT),
            0, app.lastSamples.size() - 1);
        return app.lastSamples.get(idx);
    }

    /** Ctrl+click on the 3D track: insert at that RIDE time — the
     *  containing PIECE + section activate (v12.0: any piece's track
     *  is clickable), then the insert runs locally (same rules as
     *  clicking the graph curve). */
    void insertAtTime(double globalT) {
        if (!activateGlobal(globalT)) return;
        // s/c + bridge: activate only, no nodes to insert
        if (activeSc() != null || activeBridge() != null) return;
        // native lane x at this ride time (v16.0): seconds directly,
        // or the sample's arc for a distance-domain section
        double lx;
        TrackCompiler.Span sp = activeSpan();
        if (activeDistNative() && sp != null && app.lastSamples != null) {
            double sTrav = app.lastSamples.get(rateIdxAt(globalT)).s() - sp.s0();
            boolean rev = sections().get(activeSection).reversed();
            lx = rev ? sp.sLen() - sTrav : sTrav;
        } else {
            lx = globalT - (sp != null ? sp.t0() : tOff);
        }
        if (insertNodeOnCurve(lx) >= 0) {
            // the 3D double-click insert brings its node into a
            // manual zoom window, like a marker click (v16.12)
            ensureSelectedVisible();
            redraw();
        }
    }

    /** 3D plain-click: activate the piece + section at this ride time
     *  (v13.1) — the graph follows what you click in the viewport. */
    void activateAt(double globalT) {
        activateGlobal(globalT);
    }

    /** Map a GLOBAL ride time to its piece + section via the compile
     *  spans and activate both; false when unmapped (no compile yet). */
    private boolean activateGlobal(double globalT) {
        List<TrackCompiler.Span> spans = app.sectionSpans;
        if (spans == null || spans.isEmpty()) return false;
        int idx = -1;
        for (int i = 0; i < spans.size(); i++) {
            if (globalT <= spans.get(i).t1() + 1e-9) { idx = i; break; }
        }
        if (idx < 0) return false;
        int p = 0, base = 0;
        List<TrackPiece> pieces = app.coaster.pieces;
        while (p < pieces.size() - 1
                && base + pieces.get(p).sections.size() <= idx) {
            base += pieces.get(p).sections.size();
            p++;
        }
        int sec = Math.min(idx - base, pieces.get(p).sections.size() - 1);
        if (p >= runStart() && p <= runEnd()) {
            // same bridged run: keep the zoom (one editing surface)
            activateRunSec(p, sec);
        } else {
            setActivePiece(p);
            setActiveSection(sec);
        }
        return true;
    }

    /** Shift pressed/released while hovering: toggle the slope readout
     *  without waiting for the next mouse move. */
    void shiftHover(boolean on) {
        if (hoverSlope == on) return;
        hoverSlope = on;
        if (!Double.isNaN(hoverT) || hoverResComp != 0
                || hoverDerLane >= 0 || hoverNodeIdx >= 0
                || spHoverKind != 0
                || dragNodeIdx >= 0) redraw();   // mid-drag twins (v16.16)
    }

    void clearSelection() {
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        spClearSel();        // strip selection (v18.0)
        spHideCtlPopup();    // + its popup — Load/New/switch (explicit since D249)
    }

    // ---------- toolbar actions ----------

    void setActiveLane(int lane) {
        if (lane == activeLane) return;
        // the influence surface has NO roll deltas (v15.4, D102):
        // lane 2 is inert there — the button disables, and every
        // other path (1/2/3 keys, dimmed-lane clicks) refuses too
        if (lane == 2 && activeInfl() != null) return;
        activeLane = lane;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        app.laneButtons[lane].setSelected(true);
        redraw();
    }

    /** CREATION DEFAULT (v19.19, D248, user spec): a new coaster, a new
     *  track piece (wizard open + Done) and every '+' add / seam
     *  Insert of a force/geo section open on LANE 0 — Vertical G /
     *  Pitch (Elevation under absolute yaw/pitch) — whatever lane was
     *  last edited (activeLane was session state nobody reset: a user
     *  editing Roll got every new section with Roll active). Writes
     *  the field + the toolbar button only; the caller's own refresh
     *  redraws (setActiveLane would redraw mid-sequence, before the
     *  new section's compile). Harmless under an s/c pick (lane
     *  buttons in mode 2 — the next lane section still opens on 0). */
    void defaultLane() {
        activeLane = 0;
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
        if (app.laneButtons[0] != null) app.laneButtons[0].setSelected(true);
    }

    /** Append action: with a node selected, insert a 1 s flat hold right
     *  after it (everything later shifts 1 s, regardless of the ripple
     *  toggles); otherwise extend the ride end by 1 s — ACTIVE lane only
     *  (the others keep their dotted hold, no nodes added). */
    void appendAction() {
        if (noCoaster() || startPanel != null) return;   // D230/D231
        StraightCurveSection sc = activeSc();
        if (sc != null) {   // A appends an element instead of a second
            insertElement(elSel >= 0 ? elSel + 1 : sc.elements.size(), true);
            return;
        }
        if (activeBridge() != null) {   // frozen: nothing to append to
            app.statsLabel.setText(
                "A bridge is frozen — extend the next piece instead.");
            return;
        }
        if (selIdx >= 0) insertHoldAfterSelected();
        else appendSecond();
    }

    private void insertHoldAfterSelected() {
        if (activeInfl() != null) {
            // the hold-insert SHIFTS everything later — deltas can't
            // extend the section, so the shift has nowhere to go
            app.statsLabel.setText("Influence deltas never extend the "
                + "section — Append adds nodes up to its end instead.");
            return;
        }
        pushUndoSnapshot();
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node sel = lane.get(selIdx);
        double tc = sel.t;
        double stepX = appendStep();   // 1 s of ride / 10 m of track (v16.0)
        // every lane's later nodes move by the hold; a FOREIGN segment
        // spanning tc stretches — its facing handles keep their slopes
        // (v16.1, D105). The active lane's spanning segment is rebuilt
        // by the hold insertion below and skips the rescale.
        for (List<BezierBaker.Node> ln : app.lanes) {
            double[] gaps = ln == lane ? null : BezierBaker.segGaps(ln);
            for (BezierBaker.Node m : ln) {
                if (m.t > tc + 1e-9) m.t += stepX;
            }
            if (gaps != null) BezierBaker.rescaleDvToGaps(ln, gaps);
        }
        // the selected node's outgoing transition moves to the new node
        // (its lock rides along — the insert-on-curve split rule, D248);
        // selected -> new holds the value: a smooth sloped selection's
        // out CONTINUES its in slope and the hold node's in is
        // re-angled to its inherited out, so neither node kinks under a
        // circle knob (v19.19, D248 — the historic flat-out/flat-in
        // pair left both "smooth" nodes kinked)
        BezierBaker.Node next = selIdx + 1 < lane.size()
            ? lane.get(selIdx + 1) : null;
        BezierBaker.Node hold = new BezierBaker.Node(tc + stepX, sel.val);
        hold.outFrac = sel.outFrac;
        hold.outDv = sel.outDv;
        hold.straightOut = sel.straightOut;
        hold.levelOut = sel.levelOut;
        Gestures.continueOut(selIdx > 0 ? lane.get(selIdx - 1) : null,
            sel, hold, SINE_FRAC);
        Gestures.matchInToOut(sel, hold, next, SINE_FRAC);
        lane.add(selIdx + 1, hold);
        // only the ACTIVE lane stops following (D248): the shifted
        // lanes' last nodes rode to the new end above — unless the
        // selection was this lane's LAST node (they sat AT tc, not
        // past it), which the sync closes
        touchLane(lane);
        syncFollowingEnds();
        selSet.clear();
        selSet.add(selIdx);
        redraw();
        app.rebuildTrack();
    }

    void appendSecond() {
        // only the ACTIVE lane gets a real node; the other lanes keep
        // holding their final value (dotted line) without extra nodes.
        // 1 s of ride, or 10 m of track on a distance section (v16.0)
        double newEnd = BezierBaker.rideEnd(app.lanes) + appendStep();
        List<BezierBaker.Node> lane = active();
        if (activeInfl() != null) {
            // the influence wall is absolute: append lands AT most on
            // the section end, and refuses when there is no room
            double wall = sectionEnd();
            if (lane.get(lane.size() - 1).t >= wall - minGap()) {
                app.statsLabel.setText(
                    "The influence surface ends with its section.");
                return;
            }
            newEnd = Math.min(newEnd, wall);
        }
        pushUndoSnapshot();
        BezierBaker.Node last = lane.get(lane.size() - 1);
        BezierBaker.Node np = new BezierBaker.Node(newEnd, last.val);
        // appended transitions default to the sinusoidal ease; a
        // SMOOTH last node's out handle CONTINUES its in slope (C1,
        // v19.19 D248 — a B-matched end used to leave flat under a
        // circle knob); the new node holds the value with a flat in
        Gestures.continueOut(lane.size() >= 2 ? lane.get(lane.size() - 2)
            : null, last, np, SINE_FRAC);
        np.inFrac = SINE_FRAC;    np.inDv = 0;
        lane.add(np);
        touchLane(lane);
        syncFollowingEnds();   // the untouched lanes follow the append (D248)
        redraw();
        app.rebuildTrack();
    }

    // ---------- shared press pipeline (v18.3, D172) ----------
    // Extracted verbatim from the press handler so the SPEED STRIP
    // surface runs the identical node/knob/insert/box ladder.

    /** The node/knob/edge-marker press ladder. True = consumed (a
     *  gesture began or the view expanded). */
    private boolean pressNodesKnobs(javafx.scene.input.MouseEvent e) {
        int hit = hitNode(e.getX(), e.getY());
        // the node body ALWAYS outranks its own knobs, so a node
        // overlapping its handles stays grabbable
        if (selIdx >= 0 && hit != selIdx) {
            // an off-screen handle's edge marker: expand the view
            // to bring the whole handle in reach
            if (hitEdgeMarker(e.getX(), e.getY())) {
                handleViewExpand = true;
                redraw();
                return true;
            }
            int knob = hitKnob(e.getX(), e.getY());
            if (knob != 0) {
                beginGesture();
                dragHandleIdx = selIdx;
                dragHandleIsOut = knob > 0;
                lastDragX = e.getX();
                lastDragY = e.getY();
                lastDragSnap = e.isControlDown();
                lastDragShift = app.slideKey;
                dragTimerLast = 0;
                dragGrowTimer.start();
                return true;
            }
        }
        if (hit >= 0) {
            beginGesture();
            gestureStartX = e.getX();
            gestureStartY = e.getY();
            gestureNodeT = active().get(hit).t;
            gestureNodeV = dispVal(activeLane,
                active().get(hit).t, active().get(hit).val);
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
            dragEndLimit = endLimitFor(hit);
            selIdx = hit;
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            lastDragShift = app.slideKey;
            dragTimerLast = 0;
            dragGrowTimer.start();
            redraw();
            return true;
        }
        return false;
    }

    /** A click ON the active curve inserts a node there — and keeps
     *  DRAGGING it in the same gesture (v10.1). True = the press was
     *  on the curve (whether or not the insert succeeded). */
    private boolean pressCurveInsert(javafx.scene.input.MouseEvent e) {
        double t = curveHitT(e.getX(), e.getY());
        if (!Double.isNaN(t) && e.isControlDown()) {
            // Ctrl: the insert lands on the snapped spot the
            // hover dot showed (time only; value on-curve)
            double s = snapInsertT(t);
            t = insertRoomAt(s) ? s : Double.NaN;
        }
        if (Double.isNaN(t)) return false;
        int ni = insertNodeOnCurve(t);
        if (ni >= 0) {
            dragNodeIdx = ni;
            dragEndLimit = endLimitFor(ni);
            gestureStartX = e.getX();
            gestureStartY = e.getY();
            gestureNodeT = active().get(ni).t;
            gestureNodeV = dispVal(activeLane,
                active().get(ni).t, active().get(ni).val);
            dragWinT0 = viewStart();   // freeze the window,
            dragWinT1 = viewEnd();     // like beginGesture
            pendingSnapshot = null;    // insert already pushed
            gestureMoved = true;
            lastDragX = e.getX();
            lastDragY = e.getY();
            lastDragSnap = e.isControlDown();
            lastDragShift = app.slideKey;
            dragTimerLast = 0;
            dragGrowTimer.start();
        }
        return true;
    }

    /** Begin a box select on empty surface (deselection happens on
     *  release if the box stays tiny). */
    private void pressBoxStart(javafx.scene.input.MouseEvent e) {
        boxX0 = e.getX(); boxY0 = e.getY();
        boxX1 = boxX0;    boxY1 = boxY0;
        boxAdd = e.isShiftDown();
    }

    /** Box-select completion — shared by the plot and the strip
     *  surface (v18.3): tiny box = deselect, otherwise select the
     *  caught nodes with the EARLIEST as primary. */
    private void finishBoxSelect() {
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
            int first = -1;   // primary = EARLIEST node in the box
            for (int i = 0; i < lane.size(); i++) {
                double px = ltToX(lane.get(i).t);
                double py = valToY(activeLane, dispVal(activeLane,
                    lane.get(i).t, lane.get(i).val));
                if (px >= x0 && px <= x1 && py >= y0 && py <= y1) {
                    selSet.add(i);
                    if (first < 0) first = i;
                }
            }
            if (first >= 0) selIdx = first;
            else if (selSet.isEmpty()) selIdx = -1;
            axisLock = 0;
        }
        boxX0 = Double.NaN;
    }

    // =================================================================
    // SPEED CONTROL STRIP (Phase 3, v18.0 D160; parity rounds v18.1
    // D165 / v18.2 D170; SHARED-PIPELINE rebuild v18.3 D172): a lane
    // above the x-axis, toggled by the "+V" box at the graph's bottom
    // left, sharing the plot's x mapping (domain views included). The
    // thin strip is the SPEED CONTROL LINE: diamonds partition it,
    // double/right-clicking a diamond (or the line after one) turns
    // the following stretch into a SPEED CONTROL and opens its
    // options popup. The strip's top edge is a draggable height
    // DIVIDER; tall, it shows the resulting TRUE speed plus complex
    // control graphs — which edit through the MAIN editor's OWN
    // pipeline on the strip "surface" (see the surface core below):
    // the same hit tests, gestures, snapping, guides, readouts,
    // handles, dialog, ripple and locks, at strip-sized nodes.
    // Bridges take no controls (the baseline draws dashed there).
    // =================================================================

    private static final double SP_H_THIN = 26;
    // package-private since v19.19 (D248, item S1): Viewport3D's speed-
    // control PLATES and DISKS wear the strip's own type colors
    static final Color SP_SPEED_COLOR = Color.rgb(100, 220, 110);
    static final Color SP_ACCEL_COLOR = Color.rgb(90, 160, 255);
    // the resulting TRUE-speed line's OWN brighter green (v19.19,
    // D248, item S2 — SP_SPEED_COLOR became the DESIGN line's color
    // and still colors the +V box, control segments, diamonds and the
    // drag preview; the styles live in SpeedStrip so StripGate pins
    // them)
    private static final Color SP_TRUE_COLOR = Color.rgb(
        SpeedStrip.TRUE_RGB[0], SpeedStrip.TRUE_RGB[1],
        SpeedStrip.TRUE_RGB[2]);
    private static final Color SP_LINE_COLOR = Color.rgb(115, 125, 140);
    // Ctrl-snap steps for complex-graph VALUES (x snaps by the axis
    // step like everything else)
    private static final double SP_SNAP_V = 1.0;    // m/s
    private static final double SP_SNAP_A = 0.1;    // g

    // selection: which control/mark is lit (Delete removes it)
    private int spSelPiece = -1, spSelSec = -1, spSelCtl = -1, spSelMark = -1;
    // the last press landed in the strip → Delete and the selection
    // actions target IT, not the plot's node selection
    private boolean spFocus = false;
    // diamond/mark drags: 1 = BODY (move), 2 = control end (resize),
    // 3 = mark, SP_DRAG_START = the START diamond (v19.19, D248 —
    // resize from the LEFT, end fixed; it was a move until then)
    private int spDragKind = 0;
    private static final int SP_DRAG_START = 5;
    // native-x offset between the grab point and c.x0 for a MOVE —
    // 0 from the start diamond, the grabbed spot from a BODY drag
    // (v18.9, user spec)
    private double spGrabOff = 0;
    // when the last plain diamond was click-added — a fast second
    // click REPLACES it with a control over its neighbors (v18.13)
    private long spMarkAddedAt = 0;
    private double spPressX, spPressY;
    private boolean spPendingAdd = false;   // clean click on empty line
    private int spRClick = 0;               // right-press target
    private boolean spDragDivider = false;  // the strip-height divider
    // hover readout for the resulting TRUE-speed curve (lane hovers
    // ride the shared overlays)
    private int spHoverKind = 0;   // 1 = true speed, 2 = longitudinal G,
                                   // 3 = a diamond's POSITION (v19.20,
                                   // D249, item S2 — spHoverVal holds the
                                   // native x, the strip below)
    private double spHoverX, spHoverY, spHoverVal;
    private int spHoverPiece = -1, spHoverSec = -1;
    // the sample under a kind-1 OR kind-2 hover (kind 1 since v19.19,
    // D248: the true-line readout appends the DESIGN speed at that
    // sample)
    private int spHoverIdx = 0;
    // click-DRAG on the empty control line creates a control from
    // press to release (v18.4, D173; spDragKind 4 while live):
    // the pressed section + the free interval around the press
    private int spNewPiece = -1, spNewSec = -1;
    private double spNewX0, spNewX1, spNewLo, spNewHi;
    // ZERO SITS ON THE CONTROL LINE (v18.2): both value scales are
    // linear about the line — v·k above it for speed, a·k for accel
    // (negative accel dips below)
    private double spVHi = 40, spALo = -1, spAHi = 2;
    private javafx.stage.Popup spPopup;
    private Runnable spPopupSync;
    /** The options popup's OWN control binding (v19.20, D249): set
     *  from the selection when it shows, resolved by spPopSel /
     *  spPopRunSec / spPopSection — the panel outlives the selection
     *  (a node/marker/gizmo select clears spSel*, the panel stays,
     *  live on its control) and FOLLOWS a newly selected control
     *  (spAfterRebuild re-shows it bound to that one). */
    private int spPopPiece = -1, spPopSec = -1, spPopCtl = -1;

    // ---------- the EDITING SURFACE core (v18.3, D172) ----------
    // The user's verdict on the v18.1/2 parallel mini-editor:
    // "recycle the EXACT graph-editor code". The whole main pipeline
    // runs on ONE active lane through a small set of CHOKE-POINT
    // helpers (active/ltToX/xToLt/valToY/yToVal/dispVal/laneColor/
    // laneUnit/snaps/gaps/end-wall/node sizes/…). surfStrip retargets
    // them at a complex speed control's lane inside the strip —
    // everything else is the SAME code, so behavior matches by
    // construction. Every guard defaults to the original expression:
    // the main editor is bitwise-untouched while the surface is off.
    private boolean surfStrip = false;
    private SpeedControl surfCtl;
    private RunSec surfRs;
    private boolean surfGesture = false;   // the live gesture is the strip's
    private boolean spHoverSurf = false;   // hoverT belongs to the strip

    private void withSurf(Runnable r) {
        if (surfStrip) {
            r.run();
            return;
        }
        surfCtl = spSel();
        surfRs = spSelRunSec();
        if (surfCtl == null || surfRs == null) return;
        surfStrip = true;
        try {
            r.run();
        } finally {
            surfStrip = false;
        }
    }

    /** The strip surface owns the selection (a complex control's
     *  node is selected there). */
    private boolean stripHasNodeSel() {
        return spFocus && selIdx >= 0 && spSel() != null
            && spSel().complex && spSelRunSec() != null;
    }

    /** Route a selection action onto the strip surface when it owns
     *  the selection (v18.3): re-enters the SAME method with
     *  surfStrip set — one implementation, two surfaces. */
    private boolean stripRoute(Runnable self) {
        if (surfStrip || !stripHasNodeSel()) return false;
        withSurf(self);
        return true;
    }

    // edit-region bounds — the plot rect or the strip rect
    private double editL() { return MARGIN_LEFT; }
    private double editR() { return canvas.getWidth() - marginRight(); }
    private double editT() { return surfStrip ? spTop() : MARGIN_TOP; }
    private double editB() {
        return surfStrip ? spBottom() : MARGIN_TOP + plotHeight();
    }

    // node/knob sizes — the strip keeps its smaller look (the user's
    // one deliberate visual difference)
    private double nodeR() { return surfStrip ? 3.5 : NODE_RADIUS; }
    private double nodeHit() { return surfStrip ? 8 : NODE_HIT; }
    private double knobR() { return surfStrip ? 3.2 : KNOB_RADIUS; }
    private double knobHit() { return surfStrip ? 8 : KNOB_HIT; }

    // ---------- strip geometry ----------

    private boolean spVisible() { return app.settings.showSpeedStrip(); }

    private double speedStripH() {
        if (!spVisible()) return 0;
        return app.settings.speedStripTall()
            ? FvdDesigner.clamp(app.settings.speedStripSize(), 44, 420)
            : SP_H_THIN;
    }

    private double spTop() { return MARGIN_TOP + plotHeight() + 1; }

    private double spBottom() { return spTop() + speedStripH() - 2; }

    /** The speed-control LINE: the tall view's ZERO line (graphs grow
     *  up from it, negative accel dips below — v18.2, user spec);
     *  mid-strip when collapsed. PINNED under scaling since v18.10
     *  (user spec): outside a selected COMPLEX control nothing lives
     *  below the line, so growing the strip keeps the line at its
     *  collapsed height and the value scale grows UP from it; only
     *  complex editing takes the historic 72% split (below-line room
     *  for negative accel and the long-G curve). */
    private double spLineY() {
        if (!app.settings.speedStripTall()) {
            return (spTop() + spBottom()) / 2;
        }
        SpeedControl c = spSel();
        if (c != null && c.complex) {
            return spTop() + (spBottom() - spTop()) * 0.72;
        }
        return spBottom() - SP_H_THIN / 2;
    }

    /** The strip-height DIVIDER band: the strip's top edge (v18.2 —
     *  drag like the main SplitPane divider; from the thin strip it
     *  expands, below the threshold it collapses back). */
    private boolean spDividerHit(double x, double y) {
        return spVisible() && x >= MARGIN_LEFT
            && x <= canvas.getWidth() - marginRight()
            && Math.abs(y - (spTop() - 1)) <= 4;
    }

    /** The "+V" toggle box (always drawn, bottom-left). */
    private boolean spBoxHit(double x, double y) {
        double h = canvas.getHeight();
        return x >= 4 && x <= 34 && y >= h - 26 && y <= h - 6;
    }

    /** The expand/collapse arrow at the strip's left gutter. */
    private boolean spArrowHit(double x, double y) {
        if (!spVisible()) return false;
        // STABLE zone (v18.11, user report: the arrow MOVES on a
        // toggle, so a rapid second click fell past it into the
        // strip and created a control): the whole left-gutter column
        // of the strip hits, wherever the arrow currently sits
        return x >= MARGIN_LEFT - 18 && x <= MARGIN_LEFT - 2
            && y >= spTop() - 6 && y <= spBottom() + 2;
    }

    private boolean spInStrip(double y) {
        return spVisible() && y >= spTop() && y <= spBottom();
    }

    // ---------- x mapping (any run strip) ----------

    /** Section-local native x → pixel, any run strip. */
    private double spXOf(RunSec rs, double nx) {
        return tToX(spAxisOf(rs, nx));
    }

    private double spAxisOf(RunSec rs, double nx) {
        // never through warpFwd while the SURFACE is the strip —
        // warpFwd's surface leg calls back here (v18.3)
        boolean act = rs.piece() == activePiece
            && rs.section() == activeSection && !surfStrip;
        if (act) return tOff + warpFwd(nx);
        boolean nd = rs.sec().domain() == Section.Domain.DISTANCE;
        if (nd == axisDistPlain()) return rs.t0() + nx;
        TrackCompiler.Span sp = spanAt(rs.piece(), rs.section());
        if (sp == null || app.lastSamples == null) return rs.t0() + nx;
        double[][] map = Coaster.sectionMap(app.lastSamples, sp, false);
        return rs.t0() + Coaster.mapCol(map, nd ? 1 : 0, nd ? 0 : 1, nx);
    }

    /** Pixel → section-local native x for a given strip. */
    private double spNativeAt(RunSec rs, double px) {
        return spNativeAtAxis(rs, xToTPlain(px));
    }

    /** AXIS coordinate → section-local native x (warpInv's strip leg
     *  shares it with the pixel path, v18.3). */
    private double spNativeAtAxis(RunSec rs, double a) {
        boolean act = rs.piece() == activePiece
            && rs.section() == activeSection && !surfStrip;
        if (act) return warpInv(a - tOff);
        boolean nd = rs.sec().domain() == Section.Domain.DISTANCE;
        if (nd == axisDistPlain()) return a - rs.t0();
        TrackCompiler.Span sp = spanAt(rs.piece(), rs.section());
        if (sp == null || app.lastSamples == null) return a - rs.t0();
        double[][] map = Coaster.sectionMap(app.lastSamples, sp, false);
        return Coaster.mapCol(map, nd ? 0 : 1, nd ? 1 : 0, a - rs.t0());
    }

    /** The PLOT axis mappings regardless of the editing surface —
     *  spAxisOf/spNativeAt* always speak the shared x-axis. */
    private double xToTPlain(double px) {
        boolean o = surfStrip;
        surfStrip = false;
        try {
            return xToT(px);
        } finally {
            surfStrip = o;
        }
    }

    private boolean axisDistPlain() {
        boolean o = surfStrip;
        surfStrip = false;
        try {
            return axisDist();
        } finally {
            surfStrip = o;
        }
    }

    // ---------- model accessors ----------

    /** The section's native extent (control positions clamp inside). */
    private double spSecEnd(Section s) {
        if (s instanceof StraightCurveSection sc) return sc.totalLen();
        if (s instanceof LaneSection ls) return BezierBaker.rideEnd(ls.lanes);
        return 0;
    }

    /** Stretch BOTH complex graphs with their control (v18.45, D217
     *  — the fraction-semantics rescale, shared by spClampControls,
     *  the end-diamond resize and the popup Length spinner). */
    private static void spRescaleCtlLanes(SpeedControl c, double f) {
        c.rescaleLanes(f);   // the body lives on the model since D248
    }

    /** Max LENGTH the strip surface's control may take before its
     *  section end or the next control (v18.43, D215 — the last-node
     *  length drag's wall; matches the end-diamond resize bounds,
     *  non-ripple flavor). */
    private double surfCtlMaxLen() {
        double cap = spSecEnd(surfRs.sec()) - surfCtl.x0;
        for (SpeedControl c : surfRs.sec().speedControls()) {
            if (c != surfCtl && c.x0 > surfCtl.x0 + 1e-9) {
                cap = Math.min(cap, c.x0 - surfCtl.x0);
                break;   // the list stays x-sorted (v18.1, D165)
            }
        }
        return Math.max(cap, minGapOf(surfRs.sec()) * 2);
    }

    /** The native Ctrl-snap step for a section's axis. */
    private double spSnapStep(Section s) {
        return s.domain() == Section.Domain.DISTANCE
            ? app.settings.snapLen() : app.settings.snapT();
    }

    /** Is a strip's x DISPLAYED through a non-identity mapping —
     *  the ACTIVE section viewed in the other domain (a live warp
     *  map; a degenerate map falls back to native) or an INACTIVE
     *  strip whose native domain differs from the plot axis (drawn
     *  through Coaster.sectionMap) — v19.20, D249, item S1. Mirrors
     *  spAxisOf's own branches exactly, so "false" means spAxisOf is
     *  literally rs.t0() + nx. */
    private boolean spSnapViaAxis(RunSec rs) {
        boolean act = rs.piece() == activePiece && rs.section() == activeSection;
        if (act) return rs.sec().viewOtherDomain() && warpMap != null;
        boolean nd = rs.sec().domain() == Section.Domain.DISTANCE;
        return nd != axisDistPlain()
            && spanAt(rs.piece(), rs.section()) != null
            && app.lastSamples != null;
    }

    /** The strip's Ctrl-snap of a NATIVE x (v19.20, D249, item S1 —
     *  the node path's D103 rule applied to controls): where the strip
     *  is displayed through a map, quantize on the DISPLAY axis (the
     *  section-local absolute grid in the displayed units, step =
     *  the axis's own snap step) and map back; otherwise the historic
     *  native snap round(nx / step)·step, LITERALLY — the unwarped
     *  path never goes through the map, so it stays bitwise (the D248
     *  "disk write == strip write" gate rides on it). rs null =
     *  native snap on s. */
    private double spSnapNative(RunSec rs, Section s, double nx) {
        if (rs != null && spSnapViaAxis(rs)) {
            double a = spAxisOf(rs, nx), a0 = spAxisOf(rs, 0);
            double step = axisDistPlain() ? app.settings.snapLen()
                                          : app.settings.snapT();
            return spNativeAtAxis(rs, SpeedStrip.snapDisplay(a, a0, step));
        }
        double st = spSnapStep(s);
        return Math.round(nx / st) * st;
    }

    /** A strip's native x as its SECTION-LOCAL DISPLAY coordinate (the
     *  axis units the plot shows: the cross-domain view / the plot
     *  axis for inactive strips; identity to the native x when
     *  nothing maps) — the readouts' and the popup twins' value
     *  (v19.20, D249). */
    private double spDispOf(RunSec rs, double nx) {
        return spAxisOf(rs, nx) - spAxisOf(rs, 0);
    }

    /** The run strip of (piece, section), or null when it is not in
     *  the current run. */
    private RunSec spRunSec(int piece, int sec) {
        for (RunSec rs : runLayout()) {
            if (rs.piece() == piece && rs.section() == sec) return rs;
        }
        return null;
    }

    /** The native x of a spFindDiamond hit on its strip: start / end
     *  diamond of a control, or a plain mark. */
    private double spDiamondNative(RunSec rs, int[] dia) {
        if (dia[2] == 3) return rs.sec().speedMarks().get(dia[1]);
        SpeedControl c = rs.sec().speedControls().get(dia[1]);
        return dia[2] == 1 ? c.x0 : c.x0 + c.len;
    }

    /** The DISPLAY-unit position readout "t = 3.20 s" / "s = 12.5 m"
     *  of a native x on (piece, sec) — v19.20, D249, item S2, the 3D
     *  disks' bottom-left label: a section IN the run reads through
     *  the strip's own axis mapping (cross-domain view + plot axis),
     *  one outside the run its NATIVE units. */
    String spPosReadout(int piece, int sec, double nativeX) {
        RunSec rs = spRunSec(piece, sec);
        if (rs != null) {
            return SpeedStrip.posReadout(spDispOf(rs, nativeX), axisDistPlain());
        }
        List<TrackPiece> pcs = app.coaster.pieces;
        boolean m = piece >= 0 && piece < pcs.size() && sec >= 0
            && sec < pcs.get(piece).sections.size()
            && pcs.get(piece).sections.get(sec).domain()
                == Section.Domain.DISTANCE;
        return SpeedStrip.posReadout(nativeX, m);
    }

    private double minGapOf(Section s) {
        return s.domain() == Section.Domain.DISTANCE ? 0.1 : MIN_GAP;
    }

    private RunSec spRunSecAt(double px) {
        RunSec rs = runSecAt(xToTPlain(px));
        return rs != null && rs.sec().speedControllable() ? rs : null;
    }

    private Section spSelSection() {
        if (spSelPiece < 0 || spSelPiece >= app.coaster.pieces.size()) return null;
        List<Section> secs = app.coaster.pieces.get(spSelPiece).sections;
        return spSelSec >= 0 && spSelSec < secs.size()
            ? secs.get(spSelSec) : null;
    }

    private SpeedControl spSel() {
        Section s = spSelSection();
        if (s == null || spSelCtl < 0
                || spSelCtl >= s.speedControls().size()) return null;
        return s.speedControls().get(spSelCtl);
    }

    private RunSec spSelRunSec() {
        for (RunSec rs : runLayout()) {
            if (rs.piece() == spSelPiece && rs.section() == spSelSec) {
                return rs;
            }
        }
        return null;
    }

    // the options POPUP's binding twins (D249) — its control, not the
    // selection's
    private Section spPopSection() {
        if (spPopPiece < 0 || spPopPiece >= app.coaster.pieces.size()) return null;
        List<Section> secs = app.coaster.pieces.get(spPopPiece).sections;
        return spPopSec >= 0 && spPopSec < secs.size()
            ? secs.get(spPopSec) : null;
    }

    private SpeedControl spPopSel() {
        Section s = spPopSection();
        if (s == null || spPopCtl < 0
                || spPopCtl >= s.speedControls().size()) return null;
        return s.speedControls().get(spPopCtl);
    }

    private RunSec spPopRunSec() {
        for (RunSec rs : runLayout()) {
            if (rs.piece() == spPopPiece && rs.section() == spPopSec) {
                return rs;
            }
        }
        return null;
    }

    /** Is the popup bound to the SELECTED control? */
    private boolean spPopBoundToSel() {
        return spPopPiece == spSelPiece && spPopSec == spSelSec
            && spPopCtl == spSelCtl;
    }

    /** The options popup's Edit Speed / Acceleration / Deceleration
     *  SELECT the control the panel shows (D249 review fix): since
     *  D249 the popup outlives the selection, and editing resolves the
     *  SELECTED control only (withSurf), so the button opened the tall
     *  strip over an inert, dimmed graph until a second click on the
     *  control — a click that did nothing useful. Mirrors the strip's
     *  select sites (spTakeStripSelection first: node and control
     *  selection are exclusive); a bound popup keeps its selection. */
    private void spSelectPopupControl() {
        if (spPopSel() == null || spPopBoundToSel()) return;
        spTakeStripSelection();
        spSelPiece = spPopPiece;
        spSelSec = spPopSec;
        spSelCtl = spPopCtl;
        spSelMark = -1;
        spFocus = true;
    }

    /** Global-axis intervals covered by COMPLEX SPEED-type controls
     *  (v18.4, D173; complex-only since v18.5, D174 — user spec): the
     *  resulting TRUE-speed curve and its hover hide there — the
     *  control's own GRAPH is the speed. A plain constant control's
     *  flat segment sits on the line, so the true curve stays. */
    private List<double[]> spSpeedCtlAxisRanges() {
        List<double[]> iv = new ArrayList<>();
        for (RunSec rs : runLayout()) {
            if (!rs.sec().speedControllable()) continue;
            for (SpeedControl c : rs.sec().speedControls()) {
                if (c.type != SpeedControl.SPEED || !c.complex) continue;
                iv.add(new double[]{ spAxisOf(rs, c.x0),
                    spAxisOf(rs, c.x0 + c.len) });
            }
        }
        return iv;
    }

    private static boolean spInRanges(List<double[]> iv, double a) {
        for (double[] r : iv) {
            if (a >= r[0] - 1e-9 && a <= r[1] + 1e-9) return true;
        }
        return false;
    }

    // samples skipped at a speed control's ends before reading the
    // longitudinal force: the boundary snap is an instant Δv whose
    // difference quotient is meaningless (v18.4, user spec)
    private static final int SP_LONGG_EDGE = 4;

    /** Is axis coordinate a inside a complex const-SPEED control's
     *  longitudinal-G span (edges excluded)? The owning control, or
     *  null. */
    private SpeedControl spLongGAt(double a) {
        int i = sampleIdxAtAxis(a);
        for (RunSec rs : runLayout()) {
            if (!rs.sec().speedControllable()) continue;
            for (SpeedControl c : rs.sec().speedControls()) {
                if (!c.complex || c.type != SpeedControl.SPEED) continue;
                int iA = sampleIdxAtAxis(spAxisOf(rs, c.x0))
                    + SP_LONGG_EDGE;
                int iB = sampleIdxAtAxis(spAxisOf(rs, c.x0 + c.len))
                    - SP_LONGG_EDGE;
                if (i >= iA && i <= iB) return c;
            }
        }
        return null;
    }

    /** Felt LONGITUDINAL G at sample i off the TRUE profile:
     *  (d(v²/2)/ds + g·T.y)/g — the D160 accel-control quantity. */
    private double spLongG(int i) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        int n = Math.min(ss.size(), app.trueV.length);
        if (i < 1 || i > n - 2) return Double.NaN;
        double ds = ss.get(i + 1).s() - ss.get(i - 1).s();
        if (ds < 1e-9) return Double.NaN;
        double a = (app.trueV[i + 1] * app.trueV[i + 1]
            - app.trueV[i - 1] * app.trueV[i - 1]) / (2 * ds);
        return (a + FVDIntegrator.G0 * ss.get(i).T().y())
            / FVDIntegrator.G0;
    }

    /** Derived longitudinal-G curve inside a complex const-SPEED
     *  control (v18.4, D173): what the clamp makes the rider feel
     *  along the track — accel-control BLUE, dotted like every
     *  derived curve, edges excluded. */
    private void spStrokeLongG(GraphicsContext gc, RunSec rs,
                               SpeedControl c) {
        if (app.trueV == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return;
        int n = Math.min(app.lastSamples.size(), app.trueV.length);
        int iA = sampleIdxAtAxis(spAxisOf(rs, c.x0)) + SP_LONGG_EDGE;
        int iB = sampleIdxAtAxis(spAxisOf(rs, c.x0 + c.len))
            - SP_LONGG_EDGE;
        if (iB - iA < 2) return;
        gc.setStroke(SP_ACCEL_COLOR);
        gc.setLineWidth(1.2);
        gc.setLineDashes(3, 4);
        gc.setGlobalAlpha(0.85);
        gc.beginPath();
        boolean started = false;
        for (int i = Math.max(1, iA); i <= Math.min(n - 2, iB); i++) {
            double gL = spLongG(i);
            if (Double.isNaN(gL)) {
                started = false;
                continue;
            }
            double px = tToX(gAxis(i));
            double py = spAToY(gL);
            if (!started) {
                gc.moveTo(px, py);
                started = true;
            } else {
                gc.lineTo(px, py);
            }
        }
        gc.stroke();
        gc.setLineDashes((double[]) null);
        gc.setGlobalAlpha(1);
    }

    /** Clear the strip's control/mark selection. The options POPUP is
     *  NOT hidden here since v19.20 (D249 — user spec: an open panel
     *  is not a selection; it is bound to its own control via spPop*
     *  and hides only through ✕, +V-off, a control delete, the
     *  structural clears and spAfterRebuild when ITS control vanished
     *  — those call spHideCtlPopup themselves). */
    private void spClearSel() {
        spSelPiece = spSelSec = spSelCtl = spSelMark = -1;
        spDropNodeSel();
        spFocus = false;
    }

    /** THE PLOT-SIDE SELECTION CHOKE (v19.20, D249 — user spec: node
     *  and speed-control selection are mutually exclusive): anything
     *  selected on the plot/viewport side — a plot press, a marker
     *  click, a gizmo/ball press, an s/c element — first drops the
     *  strip's control/mark selection and hands spFocus back to the
     *  plot (a strip-owned node selection goes with it). Before D249
     *  only spFocus flipped (the plot press) or nothing at all (the
     *  viewport paths): the control stayed lit (spDraw lights from
     *  spSel* alone), and a marker click under spFocus set selIdx on
     *  the STRIP surface — refreshMarkers passed −1, spSanitize
     *  clamped it against the control's node count, H/F/B/Delete
     *  routed to the strip. The options popup is untouched (see
     *  spClearSel). Returns whether any strip state was cleared. */
    private boolean takePlotSelection() {
        boolean had = spSelPiece >= 0 || spSelCtl >= 0 || spSelMark >= 0
            || spFocus;
        if (!had) return false;
        spSelPiece = spSelSec = spSelCtl = spSelMark = -1;
        spDropNodeSel();   // the strip-owned node selection (gated on spFocus)
        spFocus = false;
        return true;
    }

    /** The MIRROR choke (D249): selecting a strip control / mark /
     *  complex curve drops the PLOT's node selection UNCONDITIONALLY
     *  — spDropNodeSel is gated on spFocus, still false while the
     *  plot owned the selection, which left a plot node selected
     *  beside a lit control (the old one-way asymmetry). Every
     *  select site calls it right before `spFocus = true`. */
    private void spTakeStripSelection() {
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        handleViewExpand = false;
    }

    /** Selecting a control/mark (or clearing) drops any strip NODE
     *  selection — the shared selIdx belongs to the strip while
     *  spFocus (v18.3). */
    private void spDropNodeSel() {
        if (spFocus) {
            selIdx = -1;
            selSet.clear();
            axisLock = 0;
            handleViewExpand = false;
        }
    }

    /** Stale-state sanitation, called from redraw. */
    private void spSanitize() {
        spClampControls();
        if (spSelPiece < 0) return;
        Section s = spSelSection();
        if (s == null
                || (spSelCtl >= 0 && spSelCtl >= s.speedControls().size())
                || (spSelMark >= 0 && spSelMark >= s.speedMarks().size())) {
            spClearSel();
            return;
        }
        SpeedControl c = spSel();
        int nMax = c == null ? 0 : c.editLane().size();
        if (spFocus) {
            selSet.removeIf(i -> i >= nMax);
            if (selIdx >= nMax) selIdx = -1;
        }
    }

    /** Controls can NEVER sit past their section (v18.1, user spec —
     *  a shortened section pulls them back in; independently clamped
     *  controls may then overlap, stacked at the end, and the hit
     *  tests' list order picks the FIRST). Lists stay x-sorted so
     *  "first" is deterministic. A clamp changes the physics —
     *  rebuild once, deferred out of the draw pass. */
    private void spClampControls() {
        boolean changed = false;
        for (RunSec rs : runLayout()) {
            Section s = rs.sec();
            if (!s.speedControllable()) continue;
            List<SpeedControl> cs = s.speedControls();
            List<Double> marks = s.speedMarks();
            if (cs.isEmpty() && marks.isEmpty()) continue;
            double end = spSecEnd(s);
            double minLen = Math.min(minGapOf(s) * 2, Math.max(end, 0.01));
            SpeedControl sel = spSel();
            for (SpeedControl c : cs) {
                double nl = FvdDesigner.clamp(c.len, minLen,
                    Math.max(minLen, end));
                double nx = FvdDesigner.clamp(c.x0, 0,
                    Math.max(0, end - nl));
                if (nl != c.len || nx != c.x0) {
                    if (c.complex && c.len > 1e-9 && nl != c.len) {
                        spRescaleCtlLanes(c, nl / c.len);
                    }
                    c.len = nl;
                    c.x0 = nx;
                    changed = true;
                }
            }
            for (int i = 0; i < marks.size(); i++) {
                double m = FvdDesigner.clamp(marks.get(i), 0, end);
                if (m != marks.get(i)) {
                    marks.set(i, m);
                    changed = true;
                }
            }
            boolean sorted = true;
            for (int i = 1; i < cs.size(); i++) {
                if (cs.get(i).x0 < cs.get(i - 1).x0) { sorted = false; break; }
            }
            if (!sorted) {
                cs.sort(java.util.Comparator.comparingDouble(c -> c.x0));
                if (sel != null && s == spSelSection()) {
                    spSelCtl = cs.indexOf(sel);   // selection follows
                }
                changed = true;
            }
        }
        if (changed) {
            javafx.application.Platform.runLater(app::rebuildTrack);
        }
    }

    // ---------- drawing ----------

    void spDraw(GraphicsContext gc, double w, double h) {
        // the "+V" toggle box, always visible at the bottom left
        boolean on = spVisible();
        gc.setFill(on ? Color.rgb(45, 75, 50) : Color.rgb(38, 42, 53));
        gc.fillRoundRect(4, h - 26, 30, 20, 5, 5);
        gc.setStroke(on ? SP_SPEED_COLOR : Color.rgb(110, 118, 134));
        gc.setLineWidth(1);
        gc.strokeRoundRect(4, h - 26, 30, 20, 5, 5);
        gc.setFill(on ? SP_SPEED_COLOR : Color.rgb(160, 168, 182));
        gc.setFont(Font.font(11));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText("+V", 19, h - 16);
        if (!on) return;

        boolean tall = app.settings.speedStripTall();
        double top = spTop(), bot = spBottom(), lineY = spLineY();
        double plotRight = w - marginRight();
        // strip background
        gc.setFill(Color.rgb(24, 27, 35));
        gc.fillRect(MARGIN_LEFT, top, plotRight - MARGIN_LEFT, bot - top);
        // the height DIVIDER grip on the strip's top edge (v18.2)
        gc.setStroke(Color.rgb(95, 102, 120));
        gc.setLineWidth(1);
        gc.strokeLine(MARGIN_LEFT, top - 1, plotRight, top - 1);
        gc.setStroke(Color.rgb(150, 158, 175));
        gc.setLineWidth(3);
        double gcx = (MARGIN_LEFT + plotRight) / 2;
        gc.strokeLine(gcx - 12, top - 1, gcx + 12, top - 1);
        gc.setLineWidth(1);
        // expand/collapse arrow in the left gutter
        gc.setFill(Color.rgb(160, 168, 182));
        gc.setFont(Font.font(10));
        gc.fillText(tall ? "▼" : "▲", MARGIN_LEFT - 10, lineY);

        spFitScales();

        gc.save();
        gc.beginPath();
        gc.rect(MARGIN_LEFT, top, plotRight - MARGIN_LEFT, bot - top);
        gc.clip();

        List<RunSec> rl = runLayout();

        // STRIP VALUE AXIS (v18.3 — the main plot's grid/label
        // pattern on the selected control's scale; speed scale
        // otherwise): faded lines + labels, ZERO on the control line
        if (tall) {
            SpeedControl axC = spSel() != null && spSel().complex
                ? spSel() : null;
            boolean acc = axC != null
                && axC.type == SpeedControl.ACCEL;
            double hiV = acc ? spAHi : spVHi;
            double loV = acc ? Math.min(spALo, 0) : 0;
            double gStep = niceStep(Math.max(1e-6, hiV - loV)
                * 30 / Math.max(30, bot - top));
            gc.setLineWidth(1);
            gc.setStroke(Color.rgb(42, 47, 60));
            for (double v2 = Math.ceil(loV / gStep) * gStep;
                    v2 <= hiV + 1e-9; v2 += gStep) {
                double gy = acc ? spAToY(v2) : spVToY(v2);
                if (gy < top + 2 || gy > bot - 2) continue;
                gc.strokeLine(MARGIN_LEFT, gy, plotRight, gy);
            }
            gc.setFill(Color.rgb(118, 124, 140));
            gc.setFont(Font.font(9));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setTextBaseline(VPos.CENTER);
            for (double v2 = Math.ceil(loV / gStep) * gStep;
                    v2 <= hiV + 1e-9; v2 += gStep) {
                double gy = acc ? spAToY(v2) : spVToY(v2);
                if (gy < top + 2 || gy > bot - 2) continue;
                gc.fillText(fmtVal(v2), MARGIN_LEFT - 4, gy);
            }
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
        }

        // resulting TRUE speed, green dotted, tall view only —
        // HIDDEN inside SPEED-type controls since v18.4 (D173, user
        // spec: it just overlapped the control's own line/graph).
        // Drawn AFTER the axis grid since v18.48 (D220 — the strip
        // layered it UNDER the gridlines, the main plot's inverse:
        // a near-flat curve hugging a gridline read as DIM, the
        // user's washed-out 5 m/s new-file graph) and at full alpha
        // like every other curve.
        // The DESIGN speed draws BENEATH it as a second dotted line
        // wherever it differs from the true speed by more than
        // TRUE_SPLIT_PX on the strip scale (v19.19, D248, item S2 —
        // user spec; the lanes' drawTrueStrokes rule, purely
        // pixel-based: tilted pieces, followers, bridges and pinned
        // sections all show it, an unconflicted chain never does):
        // dimmer and thinner — the old true style, 1.5× finer dots —
        // and the true line brighter still (SpeedStrip). Hidden with
        // the true line inside complex SPEED controls; drawn through
        // stalled stretches (the design profile keeps its energy).
        if (tall && app.trueV != null && app.lastSamples != null
                && app.trueV.length == app.lastSamples.size()) {
            double rT0 = runT0(), rT1 = rT0 + runDur();
            List<double[]> hideIv = spSpeedCtlAxisRanges();
            gc.setStroke(SP_SPEED_COLOR);
            gc.setLineWidth(SpeedStrip.DESIGN_W);
            gc.setLineDashes(SpeedStrip.DESIGN_DASH);
            gc.beginPath();
            boolean started = false;
            for (int i = 0; i < app.trueV.length; i++) {
                double a = gAxis(i);
                if (a < rT0 || a > rT1 || spInRanges(hideIv, a)) {
                    started = false;
                    continue;
                }
                double py = spVToY(app.lastSamples.get(i).v());
                if (!SpeedStrip.split(py, spVToY(app.trueV[i]),
                        TRUE_SPLIT_PX)) {
                    started = false;
                    continue;
                }
                double px = tToX(a);
                if (!started) { gc.moveTo(px, py); started = true; }
                else gc.lineTo(px, py);
            }
            gc.stroke();
            gc.setStroke(SP_TRUE_COLOR);
            gc.setLineWidth(SpeedStrip.TRUE_W);
            gc.setLineDashes(SpeedStrip.TRUE_DASH);
            gc.beginPath();
            started = false;
            for (int i = 0; i < app.trueV.length; i++) {
                double a = gAxis(i);
                if (a < rT0 || a > rT1 || spInRanges(hideIv, a)) {
                    started = false;
                    continue;
                }
                double px = tToX(a);
                double py = spVToY(app.trueV[i]);
                if (!started) { gc.moveTo(px, py); started = true; }
                else gc.lineTo(px, py);
            }
            gc.stroke();
            gc.setLineDashes((double[]) null);
        }

        // the speed-control line per strip: solid where controls can
        // live, dashed where they can't (bridges, influenced, legacy-
        // reversed)
        for (RunSec rs : rl) {
            double x0 = tToX(rs.t0());
            double x1 = tToX(rs.t0() + rs.dur());
            boolean can = rs.sec().speedControllable();
            gc.setStroke(SP_LINE_COLOR);
            gc.setLineWidth(1.4);
            if (!can) gc.setLineDashes(2, 5);
            gc.strokeLine(Math.max(x0, MARGIN_LEFT), lineY,
                Math.min(x1, plotRight), lineY);
            gc.setLineDashes((double[]) null);
            if (!can) continue;
            Section s = rs.sec();
            // control segments — brighter and thicker, type-colored
            List<SpeedControl> ctls = s.speedControls();
            for (int ci = 0; ci < ctls.size(); ci++) {
                SpeedControl c = ctls.get(ci);
                double cx0 = spXOf(rs, c.x0);
                double cx1 = spXOf(rs, c.x0 + c.len);
                boolean sel = spSelPiece == rs.piece()
                    && spSelSec == rs.section() && spSelCtl == ci;
                Color col = c.type == SpeedControl.ACCEL
                    ? SP_ACCEL_COLOR : SP_SPEED_COLOR;
                if (sel) {
                    gc.setStroke(Color.rgb(255, 255, 255, 0.35));
                    gc.setLineWidth(7);
                    gc.strokeLine(cx0, lineY, cx1, lineY);
                }
                gc.setStroke(col);
                gc.setLineWidth(3.2);
                gc.strokeLine(cx0, lineY, cx1, lineY);
                // complex graph in the tall view (the DISPLAYED one;
                // an untied ACCEL control's other graph draws dimmed
                // behind it — v18.45, D217)
                if (tall && c.complex && !c.editLane().isEmpty()) {
                    spStrokeCtlLane(gc, rs, c, sel);
                }
                // derived LONGITUDINAL G inside a complex const-
                // speed control (v18.4, D173) — blue dotted
                if (tall && c.complex && c.type == SpeedControl.SPEED) {
                    spStrokeLongG(gc, rs, c);
                }
                // end diamonds
                spDiamond(gc, cx0, lineY, col, sel);
                spDiamond(gc, cx1, lineY, col, sel);
            }
            // plain marks
            List<Double> marks = s.speedMarks();
            for (int mi = 0; mi < marks.size(); mi++) {
                boolean sel = spSelPiece == rs.piece()
                    && spSelSec == rs.section() && spSelMark == mi;
                spDiamond(gc, spXOf(rs, marks.get(mi)), lineY,
                    Color.rgb(185, 192, 205), sel);
            }
        }

        // drag-to-CREATE preview (v18.4, D173): the pending control
        // as a translucent segment + end diamonds + length readout
        if (spDragKind == 4) {
            RunSec nrs = spNewRunSec();
            if (nrs != null) {
                double nx0 = spXOf(nrs, Math.min(spNewX0, spNewX1));
                double nx1 = spXOf(nrs, Math.max(spNewX0, spNewX1));
                gc.setGlobalAlpha(0.6);
                gc.setStroke(SP_SPEED_COLOR);
                gc.setLineWidth(3.2);
                gc.strokeLine(nx0, lineY, nx1, lineY);
                gc.setGlobalAlpha(1);
                spDiamond(gc, nx0, lineY, SP_SPEED_COLOR, false);
                spDiamond(gc, nx1, lineY, SP_SPEED_COLOR, false);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(10));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.BOTTOM);
                // COLLAPSED strip: lineY − 8 sits above the strip's
                // clip rect and the glyphs were cut to slivers
                // (v18.43, D215 — the user's covered hover message);
                // drop the readout just under the line instead.
                // DISPLAY units since v19.20 (D249, item S1): the
                // length as the plot axis shows it (identity to the
                // native delta where nothing maps)
                gc.fillText("length " + SpeedStrip.posValue(
                    Math.abs(spDispOf(nrs, spNewX1) - spDispOf(nrs, spNewX0)),
                    axisDistPlain()),
                    nx0 + 4, tall ? lineY - 8 : bot - 2);
            }
        }

        // the SHARED EDITING OVERLAYS on the selected complex control
        // (v18.3, D172): the main plot's nodes, rings, readouts,
        // insert dot, axis-lock guides, handles and box band —
        // IDENTICAL code on the strip surface, strip-sized nodes
        SpeedControl ovC = spSel();
        if (tall && ovC != null && ovC.complex
                && spSelRunSec() != null) {
            withSurf(() -> drawEditOverlays(gc, w));
        }

        // POSITION readout (v19.20, D249, item S2): hovering a diamond
        // (kind 3 — the hit is stored as (piece, sec, native x) and
        // resolved HERE, so a view flip re-units it without a mouse
        // move) or DRAGGING one (the live model's own diamond — hover
        // state is zeroed at gesture start) prints "t = 3.20 s" /
        // "s = 12.5 m" in DISPLAY units beside the diamond; under the
        // line on a collapsed strip (the D215 clip lesson), above it
        // when tall. The create drag keeps its "length …" text.
        {
            RunSec prs = null;
            double pnx = 0;
            boolean drag = spDragKind == 1 || spDragKind == 2
                || spDragKind == 3 || spDragKind == SP_DRAG_START;
            if (drag) {
                RunSec srs = spSelRunSec();
                if (srs != null) {
                    if (spDragKind == 3) {
                        List<Double> mk = srs.sec().speedMarks();
                        if (spSelMark >= 0 && spSelMark < mk.size()) {
                            prs = srs;
                            pnx = mk.get(spSelMark);
                        }
                    } else {
                        SpeedControl dc = spSel();
                        if (dc != null) {
                            prs = srs;
                            pnx = spDragKind == 2 ? dc.x0 + dc.len : dc.x0;
                        }
                    }
                }
            } else if (spHoverKind == 3) {
                prs = spRunSec(spHoverPiece, spHoverSec);
                pnx = spHoverVal;
                // the diamond must STILL be there (D249 review fix):
                // an undo under a resting cursor removes or moves the
                // hovered control without a mouse move, and the
                // stored native x would print beside nothing
                if (prs != null && !SpeedStrip.diamondAt(
                        prs.sec().speedControls(), prs.sec().speedMarks(),
                        pnx, 1e-9)) {
                    prs = null;
                }
            }
            if (prs != null) {
                String txt = SpeedStrip.posReadout(spDispOf(prs, pnx),
                    axisDistPlain());
                double px = spXOf(prs, pnx);
                gc.setGlobalAlpha(0.9);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(10));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.BOTTOM);
                double tx = FvdDesigner.clamp(px + 8,
                    MARGIN_LEFT + 2, plotRight - txt.length() * 6.0 - 4);
                gc.fillText(txt, tx, tall ? lineY - 8 : bot - 2);
                gc.setGlobalAlpha(1);
            }
        }

        // derived-curve hover readouts (each curve's own color — the
        // derived-curve rule; lane hovers ride the shared overlays):
        // kind 1 = resulting TRUE speed, kind 2 = longitudinal G
        // inside a complex const-speed control (v18.4); kind 3 (a
        // diamond's position) drew above
        if (spHoverKind == 1 || spHoverKind == 2) {
            Color hc = spHoverKind == 2 ? SP_ACCEL_COLOR
                                        : SP_TRUE_COLOR;
            gc.setGlobalAlpha(0.55);
            gc.setFill(hc);
            gc.fillOval(spHoverX - 4, spHoverY - 4, 8, 8);
            gc.setGlobalAlpha(0.9);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            String txt;
            if (spHoverKind == 2) {
                // composed at DRAW time so a Shift press mid-hover
                // flips value↔slope without a mouse move (the main
                // plot's shiftHover contract)
                if (hoverSlope) {
                    int n2 = Math.min(app.lastSamples.size(),
                        app.trueV.length);
                    int i0 = Math.max(1, spHoverIdx - 2);
                    int i1 = Math.min(n2 - 2, spHoverIdx + 2);
                    double span = gAxis(i1) - gAxis(i0);
                    double sl = span < 1e-9 ? 0
                        : (spLongG(i1) - spLongG(i0)) / span;
                    txt = String.format("long G %.2f g/%s", sl, xUnit());
                } else {
                    txt = String.format("long G = %.2f g", spHoverVal);
                }
            } else {
                txt = String.format("v = %.2f m/s", spHoverVal);
                // + the DESIGN speed at this sample, only where the
                // design line is drawn (v19.19, D248 — the lanes'
                // ' (true …)' rule; the index was captured at hit
                // time, never re-derived after a rebuild)
                if (app.trueV != null && app.lastSamples != null
                        && spHoverIdx >= 0
                        && spHoverIdx < Math.min(app.trueV.length,
                            app.lastSamples.size())) {
                    double dv = app.lastSamples.get(spHoverIdx).v();
                    if (SpeedStrip.split(spVToY(dv),
                            spVToY(app.trueV[spHoverIdx]),
                            TRUE_SPLIT_PX)) {
                        txt += SpeedStrip.designSuffix(dv);
                    }
                }
            }
            double tx = FvdDesigner.clamp(spHoverX + 8,
                MARGIN_LEFT + 2, plotRight - txt.length() * 6.0 - 4);
            // clamp INTO the strip's clip rect (v18.9, his screenshot:
            // the true-speed curve hugs the top edge and the readout
            // drew half outside the clip)
            double ty = FvdDesigner.clamp(spHoverY - 5, top + 12, bot - 2);
            gc.fillText(txt, tx, ty);
            gc.setGlobalAlpha(1);
        }
        gc.restore();
    }

    private void spDiamond(GraphicsContext gc, double x, double y,
                           Color col, boolean sel) {
        double r = sel ? 6 : 4.5;
        gc.setFill(col);
        gc.fillPolygon(new double[]{ x, x + r, x, x - r },
                       new double[]{ y - r, y, y + r, y }, 4);
        gc.setStroke(sel ? Color.WHITE : Color.rgb(20, 22, 28));
        gc.setLineWidth(sel ? 1.5 : 1);
        gc.strokePolygon(new double[]{ x, x + r, x, x - r },
                         new double[]{ y - r, y, y + r, y }, 4);
    }

    /** Fit the tall view's speed + accel scales (frozen mid-gesture).
     *  Both scales are LINEAR ABOUT ZERO = the control line (v18.2);
     *  the shared handleViewExpand (the off-screen-handle click)
     *  additionally reaches the selected node's handle positions. */
    private void spFitScales() {
        if (surfGesture || spDragKind != 0
                || (spFocus && (dragNodeIdx >= 0 || dragHandleIdx >= 0))) {
            return;
        }
        double vHi = 10, aLo = -0.5, aHi = 1;
        if (app.trueV != null && app.lastSamples != null) {
            // TRUE and DESIGN speeds both (v19.19, D248 — the design
            // line must stay in view like every derived curve, D174)
            double rT0 = runT0(), rT1 = rT0 + runDur();
            vHi = SpeedStrip.fitHi(vHi, app.trueV, app.lastSamples,
                this::gAxis, rT0, rT1);
        }
        for (RunSec rs : runLayout()) {
            for (SpeedControl c : rs.sec().speedControls()) {
                if (c.type == SpeedControl.SPEED) {
                    vHi = Math.max(vHi, c.speed);
                    if (c.complex) {
                        for (BezierBaker.Node n : c.lane) {
                            vHi = Math.max(vHi, n.val);
                        }
                    }
                } else {
                    aHi = Math.max(aHi, c.accel);
                    if (!c.tied) aHi = Math.max(aHi, c.decel);   // D217
                    if (c.complex) {
                        for (BezierBaker.Node n : c.lane) {
                            aHi = Math.max(aHi, n.val);
                            aLo = Math.min(aLo, n.val);
                        }
                        for (BezierBaker.Node n : c.decelLane) {
                            aHi = Math.max(aHi, n.val);
                            aLo = Math.min(aLo, n.val);
                        }
                    }
                }
                // the derived longitudinal-G curve joins the accel
                // fit (v18.5, D174 — user spec: it must always stay
                // in view vertically)
                if (c.complex && c.type == SpeedControl.SPEED
                        && app.trueV != null && app.lastSamples != null
                        && !app.lastSamples.isEmpty()) {
                    int n2 = Math.min(app.lastSamples.size(),
                        app.trueV.length);
                    int iA = sampleIdxAtAxis(spAxisOf(rs, c.x0))
                        + SP_LONGG_EDGE;
                    int iB = sampleIdxAtAxis(spAxisOf(rs, c.x0 + c.len))
                        - SP_LONGG_EDGE;
                    for (int i = Math.max(1, iA);
                            i <= Math.min(n2 - 2, iB); i++) {
                        double gL = spLongG(i);
                        if (Double.isNaN(gL)) continue;
                        aHi = Math.max(aHi, gL);
                        aLo = Math.min(aLo, gL);
                    }
                }
            }
        }
        // the expand click: bring the selected node's handles in view
        SpeedControl sc = spSel();
        if (handleViewExpand && spFocus && sc != null && sc.complex
                && selIdx >= 0 && selIdx < sc.editLane().size()) {
            BezierBaker.Node n = sc.editLane().get(selIdx);
            if (sc.type == SpeedControl.SPEED) {
                vHi = Math.max(vHi, Math.max(n.val + n.outDv,
                    n.val + n.inDv));
            } else {
                aHi = Math.max(aHi, Math.max(n.val + n.outDv,
                    n.val + n.inDv));
                aLo = Math.min(aLo, Math.min(n.val + n.outDv,
                    n.val + n.inDv));
            }
        }
        spVHi = vHi * 1.12;
        spALo = Math.min(aLo - 0.2, -0.2);
        spAHi = aHi + 0.3;
    }

    private double spVToY(double v) {
        double top = spTop() + 4;
        double k = (spLineY() - top) / Math.max(1e-9, spVHi);
        return spLineY() - v * k;
    }

    private double spYToV(double y) {
        double top = spTop() + 4;
        double k = (spLineY() - top) / Math.max(1e-9, spVHi);
        return (spLineY() - y) / Math.max(1e-9, k);
    }

    /** Accel g → y: linear about zero = the line; the units-per-px
     *  slope also fits the below-line dip room. */
    private double spAK() {
        double top = spTop() + 4, bot = spBottom() - 4;
        double up = (spLineY() - top) / Math.max(1e-9, spAHi);
        double dn = spALo < -1e-9
            ? (bot - spLineY()) / (-spALo) : Double.MAX_VALUE;
        return Math.min(up, dn);
    }

    private double spAToY(double v) {
        return spLineY() - v * spAK();
    }

    private double spYToA(double y) {
        return (spLineY() - y) / Math.max(1e-9, spAK());
    }

    private double spValToY(SpeedControl c, double v) {
        return c.type == SpeedControl.ACCEL ? spAToY(v) : spVToY(v);
    }

    private double spYToVal(SpeedControl c, double y) {
        return c.type == SpeedControl.ACCEL ? spYToA(y) : spYToV(y);
    }

    /** Stroke a complex control's graph — curve + the zero-line fill
     *  shadow only (v18.3: nodes/rings/handles draw through the
     *  SHARED overlays — one editor, two surfaces; the strip keeps
     *  its smaller line by design). An UNTIED complex ACCEL control
     *  first draws its OTHER graph dimmed for context (v18.45,
     *  D217), then the displayed one in full. */
    private void spStrokeCtlLane(GraphicsContext gc, RunSec rs,
                                 SpeedControl c, boolean sel) {
        if (c.type == SpeedControl.ACCEL && c.complex && !c.tied) {
            List<BezierBaker.Node> other =
                c.showDecel ? c.lane : c.decelLane;
            if (!other.isEmpty()) {
                spStrokeCtlLane(gc, rs, c, false, other, false);
            }
        }
        spStrokeCtlLane(gc, rs, c, sel, c.editLane(), true);
    }

    private void spStrokeCtlLane(GraphicsContext gc, RunSec rs,
                                 SpeedControl c, boolean sel,
                                 List<BezierBaker.Node> ln,
                                 boolean primary) {
        if (ln.isEmpty()) return;
        Color col = c.type == SpeedControl.ACCEL
            ? SP_ACCEL_COLOR : SP_SPEED_COLOR;
        List<double[]> poly = BezierBaker.polyline(ln);
        List<double[]> pts = new ArrayList<>(poly.size() + 2);
        double lx0 = ln.get(0).t;
        double lx1 = ln.get(ln.size() - 1).t;
        if (lx0 > 1e-9) {
            pts.add(new double[]{ spXOf(rs, c.x0),
                spValToY(c, ln.get(0).val), 0 });
        }
        for (double[] p : poly) {
            pts.add(new double[]{ spXOf(rs, c.x0 + p[0]),
                spValToY(c, p[1]), p[0] });
        }
        boolean tail = lx1 < c.len - 1e-9;
        if (tail) {
            pts.add(new double[]{ spXOf(rs, c.x0 + c.len),
                spValToY(c, ln.get(ln.size() - 1).val), c.len });
        }
        if (!primary) {
            // the un-displayed twin: a faint context curve only
            gc.setGlobalAlpha(0.35);
            gc.setStroke(col);
            gc.setLineWidth(1.0);
            gc.beginPath();
            gc.moveTo(pts.get(0)[0], pts.get(0)[1]);
            for (int i = 1; i < pts.size(); i++) {
                gc.lineTo(pts.get(i)[0], pts.get(i)[1]);
            }
            gc.stroke();
            gc.setGlobalAlpha(1.0);
            return;
        }
        double zy = spValToY(c, 0);
        gc.setFill(col.deriveColor(0, 1, 1, 0.13));
        gc.beginPath();
        gc.moveTo(pts.get(0)[0], zy);
        for (double[] p : pts) gc.lineTo(p[0], p[1]);
        gc.lineTo(pts.get(pts.size() - 1)[0], zy);
        gc.closePath();
        gc.fill();
        gc.setStroke(col);
        gc.setLineWidth(sel ? 2 : 1.4);
        int strokeEnd = pts.size() - (tail ? 1 : 0);
        gc.beginPath();
        gc.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < strokeEnd; i++) {
            // locked segments drop the curve stroke (v18.4, D173):
            // they wear the MINI twin lines below, the main plot's
            // "pinned" look at strip size
            if (spSegLocked(ln,
                    (pts.get(i - 1)[2] + pts.get(i)[2]) / 2)) {
                gc.moveTo(pts.get(i)[0], pts.get(i)[1]);
            } else {
                gc.lineTo(pts.get(i)[0], pts.get(i)[1]);
            }
        }
        gc.stroke();
        if (tail) {
            // hold-to-end continuation DOTTED like the lane strips
            // (v18.5, D174): the graph past its last node holds the
            // final value to the control's end
            gc.setLineDashes(3, 7);
            gc.beginPath();
            gc.moveTo(pts.get(strokeEnd - 1)[0], pts.get(strokeEnd - 1)[1]);
            gc.lineTo(pts.get(strokeEnd)[0], pts.get(strokeEnd)[1]);
            gc.stroke();
            gc.setLineDashes((double[]) null);
        }
        // mini Shift+F / Shift+H visuals (v18.4): twin skinny lines
        // along the chord normal; LEVEL locks tint the band between
        gc.setLineWidth(0.8);
        for (int i = 0; i < ln.size() - 1; i++) {
            BezierBaker.Node a = ln.get(i);
            if (!a.lockedOut()) continue;
            BezierBaker.Node b = ln.get(i + 1);
            double ax = spXOf(rs, c.x0 + a.t), ay = spValToY(c, a.val);
            double bx = spXOf(rs, c.x0 + b.t), by = spValToY(c, b.val);
            double dx = bx - ax, dy = by - ay;
            double len2 = Math.hypot(dx, dy);
            if (len2 < 1e-6) continue;
            double ox = -dy / len2 * 1.1, oy = dx / len2 * 1.1;
            if (a.levelOut) {   // level segments are horizontal
                gc.setFill(col.deriveColor(0, 1, 1, 0.35));
                gc.fillRect(Math.min(ax, bx), ay - Math.abs(oy),
                            Math.abs(bx - ax), Math.abs(oy) * 2);
            }
            gc.strokeLine(ax + ox, ay + oy, bx + ox, by + oy);
            gc.strokeLine(ax - ox, ay - oy, bx - ox, by - oy);
        }
        gc.setLineWidth(1);
    }

    /** Does lane-local x sit inside a LOCKED segment (Shift+F/H)? */
    private static boolean spSegLocked(List<BezierBaker.Node> lane,
                                       double x) {
        for (int i = 0; i < lane.size() - 1; i++) {
            if (lane.get(i).lockedOut()
                    && x >= lane.get(i).t - 1e-9
                    && x <= lane.get(i + 1).t + 1e-9) return true;
        }
        return false;
    }

    // ---------- hit tests ----------

    /** {rs-index-in-runLayout, ctlIdx, which} where which: 1 = start
     *  diamond, 2 = end diamond, 3 = mark (ctlIdx = mark index);
     *  null = none. */
    private int[] spFindDiamond(double x, double y) {
        if (Math.abs(y - spLineY()) > 8) return null;
        List<RunSec> rl = runLayout();
        for (int r = 0; r < rl.size(); r++) {
            RunSec rs = rl.get(r);
            if (!rs.sec().speedControllable()) continue;
            List<SpeedControl> ctls = rs.sec().speedControls();
            for (int ci = 0; ci < ctls.size(); ci++) {
                SpeedControl c = ctls.get(ci);
                if (Math.abs(x - spXOf(rs, c.x0 + c.len)) <= 7) {
                    return new int[]{ r, ci, 2 };
                }
                if (Math.abs(x - spXOf(rs, c.x0)) <= 7) {
                    return new int[]{ r, ci, 1 };
                }
            }
            List<Double> marks = rs.sec().speedMarks();
            for (int mi = 0; mi < marks.size(); mi++) {
                if (Math.abs(x - spXOf(rs, marks.get(mi))) <= 7) {
                    return new int[]{ r, mi, 3 };
                }
            }
        }
        return null;
    }

    /** Control whose SEGMENT the point sits on: {rs-index, ctlIdx}. */
    private int[] spFindSegment(double x, double y) {
        if (Math.abs(y - spLineY()) > 6) return null;
        List<RunSec> rl = runLayout();
        for (int r = 0; r < rl.size(); r++) {
            RunSec rs = rl.get(r);
            if (!rs.sec().speedControllable()) continue;
            List<SpeedControl> ctls = rs.sec().speedControls();
            for (int ci = 0; ci < ctls.size(); ci++) {
                SpeedControl c = ctls.get(ci);
                double x0 = spXOf(rs, c.x0), x1 = spXOf(rs, c.x0 + c.len);
                if (x >= x0 + 4 && x <= x1 - 4) return new int[]{ r, ci };
            }
        }
        return null;
    }

    /** Any complex curve (unselected controls included): {rs, ctl}. */
    private int[] spHitComplexCurve(double x, double y) {
        List<RunSec> rl = runLayout();
        for (int r = 0; r < rl.size(); r++) {
            RunSec rs = rl.get(r);
            if (!rs.sec().speedControllable()) continue;
            List<SpeedControl> ctls = rs.sec().speedControls();
            for (int ci = 0; ci < ctls.size(); ci++) {
                SpeedControl c = ctls.get(ci);
                if (!c.complex || c.editLane().isEmpty()) continue;
                double lx = spNativeAt(rs, x) - c.x0;
                if (lx < 0 || lx > c.len) continue;
                // the DISPLAYED graph owns the hit (v18.45, D217)
                double cv = BezierBaker.exactAt(c.editLane(), lx);
                if (Math.abs(y - spValToY(c, cv)) <= 6) {
                    return new int[]{ r, ci };
                }
            }
        }
        return null;
    }

    // ---------- interactions ----------

    /** PRIMARY/SECONDARY press in the strip region; true = consumed. */
    private boolean spPress(javafx.scene.input.MouseEvent e) {
        double x = e.getX(), y = e.getY();
        if (e.getButton() == MouseButton.PRIMARY && spBoxHit(x, y)) {
            boolean opening = !spVisible();
            app.settings.setShowSpeedStrip(opening);
            // +V always opens COLLAPSED (v18.9, user spec) — the ▲
            // arrow / divider / Edit button re-expand
            if (opening && app.settings.speedStripTall()) {
                app.settings.setSpeedStripTall(false);
            }
            if (!spVisible()) {
                spClearSel();
                spHideCtlPopup();   // +V-off closes the panel (D249)
            }
            redraw();
            return true;
        }
        if (!spVisible()) return false;
        // the height DIVIDER outranks everything on the edge (v18.2)
        if (e.getButton() == MouseButton.PRIMARY && spDividerHit(x, y)) {
            spDragDivider = true;
            spHoverKind = 0;
            return true;
        }
        if (e.getButton() == MouseButton.PRIMARY && spArrowHit(x, y)) {
            app.settings.setSpeedStripTall(!app.settings.speedStripTall());
            redraw();
            return true;
        }
        if (!spInStrip(y)) return false;
        // GUTTER presses never reach the strip's line/segments
        // (v18.11 — xToTPlain happily maps x left of the plot, so a
        // gutter click could add a diamond): consume and stand down
        if (x < MARGIN_LEFT || x > canvas.getWidth() - marginRight()) {
            return true;
        }
        // an open density preview / partial-convert mode owns the doc
        if (pvBefore != null || pcSec >= 0) return true;
        if (!spFocus) {
            // focus arrives from the plot: the shared node selection
            // belongs to ONE surface at a time (v18.3)
            selIdx = -1;
            selSet.clear();
            axisLock = 0;
            handleViewExpand = false;
        }
        spPressX = x;
        spPressY = y;
        spPendingAdd = false;
        spRClick = 0;
        spHoverKind = 0;     // no stale dot under a gesture (v18.2)
        if (e.getButton() == MouseButton.SECONDARY) {
            // clean release opens create/options (right-drag still pans)
            spRClick = 1;
            rPressX = x;
            rPressY = y;
            return false;   // let the pan setup run
        }
        if (e.getButton() != MouseButton.PRIMARY) return true;
        // the SHARED editor pipeline owns the selected control's
        // graph (v18.3, D172): nodes/knobs/edge-markers take the main
        // plot's press rank — the same code, on the strip surface
        if (app.settings.speedStripTall() && spSel() != null
                && spSel().complex && spSelRunSec() != null) {
            boolean[] used = { false };
            withSurf(() -> used[0] = pressNodesKnobs(e));
            if (used[0]) {
                spFocus = true;
                surfGesture = true;
                return true;
            }
        }
        int[] dia = spFindDiamond(x, y);
        if (dia != null) {
            RunSec rs = runLayout().get(dia[0]);
            spSelPiece = rs.piece();
            spSelSec = rs.section();
            if (dia[2] == 3) {
                spSelMark = dia[1];
                spSelCtl = -1;
            } else {
                spSelCtl = dia[1];
                spSelMark = -1;
            }
            spTakeStripSelection();   // exclusive both ways (D249)
            spFocus = true;
            beginGesture();
            // the START diamond RESIZES from the left since v19.19
            // (D248, user spec — both diamonds size their own end;
            // the BODY is the move handle)
            spDragKind = dia[2] == 1 ? SP_DRAG_START : dia[2];
            spGrabOff = 0;
            redraw();
            return true;
        }
        int[] seg = spFindSegment(x, y);
        if (seg != null) {
            RunSec rs = runLayout().get(seg[0]);
            spSelPiece = rs.piece();
            spSelSec = rs.section();
            spSelCtl = seg[1];
            spSelMark = -1;
            spTakeStripSelection();   // exclusive both ways (D249)
            spFocus = true;
            // dragging the BODY MOVES the control like its start
            // diamond (v18.9, user spec), anchored where it was
            // grabbed — the 3 px deadband keeps a plain click a
            // selection; end-diamond resize is untouched
            beginGesture();
            spDragKind = 1;
            SpeedControl bodyC = spSel();
            spGrabOff = bodyC != null
                ? spNativeAt(rs, x) - bodyC.x0 : 0;
            redraw();
            return true;
        }
        // clicking another complex control's CURVE selects it
        if (app.settings.speedStripTall()) {
            int[] hitC = spHitComplexCurve(x, y);
            if (hitC != null) {
                RunSec rs = runLayout().get(hitC[0]);
                if (rs.piece() != spSelPiece || rs.section() != spSelSec
                        || hitC[1] != spSelCtl) {
                    spSelPiece = rs.piece();
                    spSelSec = rs.section();
                    spSelCtl = hitC[1];
                    spSelMark = -1;
                    spTakeStripSelection();   // exclusive both ways (D249)
                    spFocus = true;
                    redraw();
                    return true;
                }
            }
        }
        // empty line: a clean click adds a diamond on release; a
        // DRAG creates a control press→release (v18.4, D173)
        RunSec addRs = Math.abs(y - spLineY()) <= 8 ? spRunSecAt(x) : null;
        if (addRs != null) {
            spPendingAdd = true;
            spNewPiece = -1;
            double nx0 = spNativeAt(addRs, x);
            double end = spSecEnd(addRs.sec());
            if (nx0 > 1e-6 && nx0 < end - 1e-6) {
                boolean free = true;
                double lo = 0, hi = end;
                for (SpeedControl c : addRs.sec().speedControls()) {
                    if (nx0 >= c.x0 - 1e-9 && nx0 <= c.x0 + c.len + 1e-9) {
                        free = false;
                        break;
                    }
                    if (c.x0 + c.len <= nx0) lo = Math.max(lo, c.x0 + c.len);
                    if (c.x0 >= nx0) hi = Math.min(hi, c.x0);
                }
                if (free) {   // remember the free interval for the drag
                    spNewPiece = addRs.piece();
                    spNewSec = addRs.section();
                    spNewX0 = nx0;
                    spNewX1 = nx0;
                    spNewLo = lo;
                    spNewHi = hi;
                }
            }
            return true;
        }
        // curve INSERT + box select through the shared pipeline
        // (v18.3): the insert keeps dragging in-gesture, exactly like
        // clicking the main plot's curve; empty tall space starts the
        // box band
        SpeedControl bc = spSel();
        if (app.settings.speedStripTall() && bc != null && bc.complex
                && spSelRunSec() != null) {
            withSurf(() -> {
                if (!pressCurveInsert(e)) pressBoxStart(e);
            });
            spFocus = true;
            surfGesture = true;
            return true;
        }
        spClearSel();
        redraw();
        return true;
    }

    /** Strip drags; true = consumed. (Node/handle/box drags of the
     *  complex graphs run through the MAIN drag handler on the strip
     *  surface since v18.3.) */
    private boolean spDrag(javafx.scene.input.MouseEvent e) {
        if (spDragDivider) {
            // the strip's top edge follows the cursor (v18.2): below
            // the threshold the strip collapses to the thin line;
            // dragging the thin strip's edge up expands it
            double newH = canvas.getHeight() - MARGIN_BOTTOM - e.getY();
            if (newH < 40) {
                if (app.settings.speedStripTall()) {
                    app.settings.setSpeedStripTall(false);
                }
            } else {
                if (!app.settings.speedStripTall()) {
                    app.settings.setSpeedStripTall(true);
                }
                app.settings.setSpeedStripSize(
                    FvdDesigner.clamp(newH, 44, 420));
            }
            redraw();
            return true;
        }
        // drag-to-CREATE (v18.4, D173): a pending empty-line click
        // that moves past the deadband becomes a new-control drag
        if (spPendingAdd && spNewPiece >= 0
                && Math.hypot(e.getX() - spPressX, e.getY() - spPressY) > 3) {
            spPendingAdd = false;
            beginGesture();
            spDragKind = 4;
        }
        if (spDragKind == 4) {
            RunSec rs = spNewRunSec();
            if (rs != null) {
                double nx = spNativeAt(rs, e.getX());
                // Ctrl: the DISPLAY-axis snap (D249, item S1)
                if (e.isControlDown()) nx = spSnapNative(rs, rs.sec(), nx);
                spNewX1 = FvdDesigner.clamp(nx, spNewLo, spNewHi);
                redraw();
            }
            return true;
        }
        if (spDragKind != 0) {
            if (Math.hypot(e.getX() - spPressX, e.getY() - spPressY) <= 3
                    && !gestureMoved) {
                return true;   // click deadband — selection only
            }
            commitGestureSnapshot();
            spDragDiamondTo(e.getX(), e.isControlDown());
            return true;
        }
        return false;
    }

    private RunSec spNewRunSec() {
        for (RunSec rs : runLayout()) {
            if (rs.piece() == spNewPiece && rs.section() == spNewSec) {
                return rs;
            }
        }
        return null;
    }

    private void spDragDiamondTo(double x, boolean snap) {
        Section s = spSelSection();
        RunSec rs = spSelRunSec();
        if (s == null || rs == null) return;
        double nx = spNativeAt(rs, x);
        // body-grab anchor (v18.9): a MOVE holds the grabbed spot
        // under the cursor; the offset applies BEFORE the snap so
        // Ctrl still lands x0 on the grid
        if (spDragKind == 1) nx -= spGrabOff;
        spDragDiamondNative(nx, snap);
    }

    /** The diamond drag's NATIVE-x body (v19.19, D248, item S1 —
     *  split out of spDragDiamondTo MECHANICALLY: the strip's pixel
     *  wrapper above resolves the cursor and the body-grab offset,
     *  this method snaps, clamps, writes and rebuilds exactly as
     *  before). The 3D speed-control DISKS feed it a native x read
     *  straight off the track (spViewportDragTo), so a disk drag and a
     *  diamond drag are ONE write path — the disk can never move
     *  further than the diamond can. */
    private void spDragDiamondNative(double nx, boolean snap) {
        Section s = spSelSection();
        if (s == null) return;
        // Ctrl: the DISPLAY-axis snap (D249, item S1 — native and
        // bitwise where nothing is warped)
        if (snap) nx = spSnapNative(spSelRunSec(), s, nx);
        double end = spSecEnd(s);
        List<SpeedControl> ctls = s.speedControls();
        if (spDragKind == 3 && spSelMark >= 0
                && spSelMark < s.speedMarks().size()) {
            double old = s.speedMarks().get(spSelMark);
            double nv = FvdDesigner.clamp(nx, 0, end);
            s.speedMarks().set(spSelMark, nv);
            if (app.rippleT && nv != old) {
                spRipple(s, old + 1e-9, nv - old, end);
            }
            redraw();
            return;
        }
        SpeedControl c = spSel();
        if (c == null) return;
        if (spDragKind == 1) {
            // MOVE the control (length kept); clamp against neighbors
            double lo = 0, hi = end - c.len;
            for (SpeedControl o : ctls) {
                if (o == c) continue;
                if (o.x0 + o.len <= c.x0 + 1e-9) {
                    lo = Math.max(lo, o.x0 + o.len);
                }
                if (o.x0 >= c.x0 + c.len - 1e-9 && !app.rippleT) {
                    hi = Math.min(hi, o.x0 - c.len);
                }
            }
            double x0new = FvdDesigner.clamp(nx, lo, Math.max(lo, hi));
            double dx = x0new - c.x0;
            if (app.rippleT && dx != 0) {
                dx = spRippleLimit(s, c, dx, end);
                x0new = c.x0 + dx;
            }
            c.x0 = x0new;
            if (app.rippleT && dx != 0) spRipple(s, c.x0 - dx + 1e-12, dx, end);
        } else if (spDragKind == 2) {
            // RESIZE: the end diamond sets the length — the clamp
            // (section end / next control unless ripple / minLen)
            // lives on Gestures since D248 so the viewport disk's
            // write is gated as the strip's (bitwise the old inline
            // arithmetic)
            double lenNew = Gestures.resizeEndLen(c, nx, ctls,
                minGapOf(s) * 2, end, app.rippleT);
            double dx = lenNew - c.len;
            if (app.rippleT && dx != 0) {
                dx = spRippleLimit(s, c, dx, end);
                lenNew = c.len + dx;
            }
            double oldEnd = c.x0 + c.len;
            c.len = lenNew;
            if (c.complex && c.len > 1e-9) {
                // the graphs stretch with the control (fractions of
                // the extent — the handle-fraction philosophy; BOTH
                // lanes since v18.45, D217)
                spRescaleCtlLanes(c, c.len / Math.max(1e-9, oldEnd - c.x0));
            }
            if (app.rippleT && dx != 0) spRipple(s, oldEnd, dx, end);
        } else if (spDragKind == SP_DRAG_START) {
            // RESIZE FROM THE LEFT (v19.19, D248): the end stays put,
            // x0 follows the cursor between the previous control's end
            // and end − minLen; complex graphs stretch with the extent
            // (the shared fraction semantics); no ripple — nothing
            // after the fixed end moves
            Gestures.resizeStart(c, nx, ctls, minGapOf(s) * 2);
        }
        app.rebuildTrack();
        redraw();
    }

    // ---------- the 3D SPEED-CONTROL DISKS' entry points ----------
    // (v19.19, D248, item S1 — Viewport3D draws every control's start
    // and end as a disk on the heartline; a disk IS its diamond: the
    // viewport picks the disk, reads a native x off the track and
    // routes through the strip's own selection/gesture/write chokes
    // below, so the strip's clamps, undo model and live redraw apply
    // verbatim.)

    /** A speed control by INDEX (piece, section, control) — the 3D
     *  disks' selection-highlight key. */
    record CtlRef(int piece, int sec, int ctl) {}

    /** The strip's selected CONTROL as a CtlRef, or null. */
    CtlRef spSelectedCtl() {
        return spSel() != null
            ? new CtlRef(spSelPiece, spSelSec, spSelCtl) : null;
    }

    /** A 3D DISK press on control (piece, sec, ctl): the strip's
     *  diamond-press recipe — kind 1 = the START disk (SP_DRAG_START:
     *  resize from the left, exactly the start diamond since item G),
     *  2 = the END disk (resize). Refuses with a stats reason under
     *  the convert preview (CONVERT_BUSY_MSG) and the start screen
     *  (WIZARD_OPEN_WHY — the strip is unreachable under the panel, a
     *  disk must not become a back door). A control on a piece
     *  OUTSIDE the active run activates that piece + section FIRST (a
     *  track click's semantics; the zoom resets here, never
     *  mid-drag); inside the run nothing activates (strip parity). A
     *  hidden strip opens COLLAPSED (the +V rule) so the mirrored
     *  diamond is visible. One undo step per gesture (beginGesture
     *  here, pushed on the first real move by spViewportDragTo; a
     *  clean click pushes nothing). Returns whether a gesture
     *  started. */
    boolean spViewportPress(int piece, int sec, int ctl, int kind) {
        if (app.convertBusy()) {
            app.statsLabel.setText(FvdDesigner.CONVERT_BUSY_MSG);
            return false;
        }
        if (startPanelActive()) {
            app.statsLabel.setText(WIZARD_OPEN_WHY);
            return false;
        }
        if (kind != 1 && kind != 2) return false;
        if (piece < 0 || piece >= app.coaster.pieces.size()) return false;
        List<Section> secs = app.coaster.pieces.get(piece).sections;
        if (sec < 0 || sec >= secs.size()) return false;
        Section s = secs.get(sec);
        if (!s.speedControllable() || ctl < 0
                || ctl >= s.speedControls().size()) return false;
        if (piece < runStart() || piece > runEnd()) {
            activatePieceSection(piece, sec);
            if (piece < runStart() || piece > runEnd()) return false;
        }
        if (!spVisible()) {
            app.settings.setShowSpeedStrip(true);
            if (app.settings.speedStripTall()) {
                app.settings.setSpeedStripTall(false);
            }
        }
        // the shared node selection belongs to ONE surface at a time
        // (v18.3): the unconditional clear (the D249 mirror choke)
        spTakeStripSelection();
        spSelPiece = piece;
        spSelSec = sec;
        spSelCtl = ctl;
        spSelMark = -1;
        spFocus = true;
        spPendingAdd = false;
        spRClick = 0;
        spHoverKind = 0;     // no stale dot under a gesture (v18.2)
        beginGesture();
        spDragKind = kind == 1 ? SP_DRAG_START : 2;
        spGrabOff = 0;
        redraw();
        return true;
    }

    /** A 3D disk drag event: the control's grabbed end follows the
     *  NATIVE x the viewport read off the track (NaN = nothing
     *  projected, the control stays put); snap = Ctrl (the strip's
     *  own spSnapNative — the DISPLAY-axis grid under a cross-domain
     *  view, D249). The first real move pushes the gesture's one undo
     *  snapshot; the per-event rebuild runs hot (D200) until
     *  spViewportRelease settles. */
    void spViewportDragTo(double nativeX, boolean snap) {
        if (spDragKind != SP_DRAG_START && spDragKind != 2) return;
        if (Double.isNaN(nativeX)) return;
        commitGestureSnapshot();
        app.gestureHot = true;   // fast reconcile per event (D200)
        app.pointerDown = true;
        spDragDiamondNative(nativeX, snap);
    }

    /** A 3D disk release: ends the gesture (the strip's spRelease
     *  tail — no snapshot on a clean click), settles the document
     *  (D200) and, on an unmoved DOUBLE-click, opens the control's
     *  options popup at the click (the diamond's double-click). */
    void spViewportRelease(boolean moved, double sx, double sy,
                           int clicks) {
        boolean was = spDragKind != 0;
        spDragKind = 0;
        pendingSnapshot = null;
        gestureMoved = false;
        dragWinT0 = Double.NaN;   // unfreeze the time window (no canvas
                                  // release follows a viewport gesture)
        app.pointerDown = false;
        app.gestureHot = false;
        app.settleIfPending();
        if (!moved && clicks == 2 && !app.convertBusy()) {
            spShowCtlPopup(sx, sy);
        }
        if (was) redraw();
    }

    /** Ripple headroom: the largest |dx| every later control/mark can
     *  absorb without leaving the section. */
    private double spRippleLimit(Section s, SpeedControl moved,
                                 double dx, double end) {
        if (dx <= 0) return dx;
        double room = Double.MAX_VALUE;
        for (SpeedControl o : s.speedControls()) {
            if (o == moved || o.x0 < moved.x0) continue;
            room = Math.min(room, end - (o.x0 + o.len));
        }
        for (double m : s.speedMarks()) {
            if (m >= moved.x0) room = Math.min(room, end - m);
        }
        return Math.min(dx, Math.max(0, room));
    }

    /** Shift every control/mark at or beyond `from` by dx (the
     *  horizontal ripple applied to the speed line). */
    private void spRipple(Section s, double from, double dx, double end) {
        for (SpeedControl o : s.speedControls()) {
            if (o != spSel() && o.x0 >= from - 1e-9) {
                o.x0 = FvdDesigner.clamp(o.x0 + dx, 0, end - o.len);
            }
        }
        List<Double> marks = s.speedMarks();
        for (int i = 0; i < marks.size(); i++) {
            if (i == spSelMark && spDragKind == 3) continue;
            if (marks.get(i) >= from - 1e-9) {
                marks.set(i, FvdDesigner.clamp(marks.get(i) + dx, 0, end));
            }
        }
    }

    /** Release in/for the strip; true = consumed. (Node/handle/box
     *  releases run through the MAIN release handler since v18.3.) */
    private boolean spRelease(javafx.scene.input.MouseEvent e) {
        if (spDragDivider) {
            spDragDivider = false;
            return true;
        }
        // drag-to-CREATE lands (v18.4, D173): the dragged stretch
        // becomes a SPEED control (min length enforced, marks the
        // range swallowed are consumed), selected — options via the
        // usual double/right-click
        if (spDragKind == 4) {
            spDragKind = 0;
            RunSec nrs = spNewRunSec();
            if (nrs != null && Math.abs(spNewX1 - spNewX0) > 1e-9) {
                Section s = nrs.sec();
                double minLen = minGapOf(s) * 2;
                double lo = Math.min(spNewX0, spNewX1);
                double hi = Math.max(spNewX0, spNewX1);
                if (hi - lo < minLen) {   // grow into the free room
                    hi = Math.min(spNewHi, lo + minLen);
                    lo = Math.max(spNewLo, hi - minLen);
                }
                if (hi - lo >= minLen - 1e-9) {
                    commitGestureSnapshot();
                    SpeedControl c = new SpeedControl(lo, hi - lo);
                    c.speed = Math.max(0.5,   // 2 decimals (D237)
                        Math.round(spDesignSpeedAt(nrs, lo) * 100) / 100.0);
                    s.speedControls().add(c);
                    final double mLo = lo, mHi = hi;
                    s.speedMarks().removeIf(
                        m -> m >= mLo - 1e-9 && m <= mHi + 1e-9);
                    spSelPiece = nrs.piece();
                    spSelSec = nrs.section();
                    spSelCtl = s.speedControls().indexOf(c);
                    spSelMark = -1;
                    spTakeStripSelection();   // exclusive both ways (D249)
                    spFocus = true;
                    app.rebuildTrack();
                }
            }
            spNewPiece = -1;
            spPendingAdd = false;
            pendingSnapshot = null;
            gestureMoved = false;
            dragWinT0 = Double.NaN;   // unfreeze the time window (the
                                      // canvas release returns before
                                      // its own unfreeze, D248 review)
            redraw();
            return true;
        }
        boolean was = spDragKind != 0;
        spDragKind = 0;
        // clean RIGHT-click: create control / open options (the pan
        // deadband rule)
        if (spRClick != 0 && e.getButton() == MouseButton.SECONDARY
                && Math.hypot(e.getX() - spPressX, e.getY() - spPressY) <= 3) {
            spRClick = 0;
            spPromoteOrOptions(e.getX(), e.getY(), e.getScreenX(),
                e.getScreenY());
            return true;
        }
        spRClick = 0;
        if (spPendingAdd && e.getButton() == MouseButton.PRIMARY
                && Math.hypot(e.getX() - spPressX, e.getY() - spPressY) <= 3) {
            spPendingAdd = false;
            spNewPiece = -1;
            RunSec rs = spRunSecAt(e.getX());
            if (rs != null) {
                double nx = spNativeAt(rs, e.getX());
                double end = spSecEnd(rs.sec());
                if (nx > 1e-6 && nx < end - 1e-6) {
                    // not inside an existing control
                    boolean free = true;
                    for (SpeedControl c : rs.sec().speedControls()) {
                        if (nx > c.x0 - 1e-9 && nx < c.x0 + c.len + 1e-9) {
                            free = false;
                            break;
                        }
                    }
                    if (free) {
                        pushUndoSnapshot();
                        rs.sec().speedMarks().add(nx);
                        spSelPiece = rs.piece();
                        spSelSec = rs.section();
                        spSelMark = rs.sec().speedMarks().size() - 1;
                        spSelCtl = -1;
                        spTakeStripSelection();   // exclusive both ways (D249)
                        spFocus = true;
                        spMarkAddedAt = System.currentTimeMillis();
                        redraw();
                    }
                }
            }
            return true;
        }
        spPendingAdd = false;
        spNewPiece = -1;
        if (was) {
            pendingSnapshot = null;
            gestureMoved = false;
            dragWinT0 = Double.NaN;   // unfreeze (a diamond/mark drag
                                      // froze it in beginGesture and the
                                      // canvas release never reached its
                                      // own unfreeze — D248 review fix)
            redraw();
            return true;
        }
        return false;
    }

    /** Double-click in the strip: node dialog on a graph node (the
     *  shared editor's typed dialog, v18.3), else promote/options;
     *  true = consumed. */
    private boolean spDoubleClick(javafx.scene.input.MouseEvent e) {
        if (!spVisible() || !spInStrip(e.getY())) return false;
        // the expand/collapse arrow + gutters own their columns
        // (v18.11 second pass — the guard sat in spPress, but the
        // DOUBLE-click's promote routes through THIS handler, so
        // rapid arrow clicks still created a control, his re-report)
        if (spArrowHit(e.getX(), e.getY()) || e.getX() < MARGIN_LEFT
                || e.getX() > canvas.getWidth() - marginRight()) {
            return true;
        }
        SpeedControl dc = spSel();
        if (app.settings.speedStripTall() && dc != null && dc.complex
                && spSelRunSec() != null) {
            int[] hit = { -1 };
            withSurf(() -> hit[0] = hitNode(e.getX(), e.getY()));
            if (hit[0] >= 0) {
                final int hn = hit[0];
                spFocus = true;
                selIdx = hn;
                selSet.clear();
                selSet.add(hn);
                withSurf(() -> showNodeEditor(hn));
                return true;
            }
        }
        // DOUBLE-CLICK CREATES on the free line (v18.13, user spec —
        // "same as right-clicking"): the FIRST click just dropped a
        // mark there, so that mark is removed and the control spans
        // its former neighbors (or the section ends)
        int[] jm = spFindDiamond(e.getX(), e.getY());
        if (jm != null && jm[2] == 3
                && System.currentTimeMillis() - spMarkAddedAt < 600) {
            RunSec jrs = runLayout().get(jm[0]);
            List<Double> jmarks = jrs.sec().speedMarks();
            if (jm[1] < jmarks.size()) {
                double mx = jmarks.get(jm[1]);
                jmarks.remove(jm[1]);
                spSelMark = -1;
                spCreateControlAt(jrs, mx, e.getScreenX(),
                    e.getScreenY(), false);   // the add's snapshot
                                              // already covers this
                return true;
            }
        }
        spPromoteOrOptions(e.getX(), e.getY(), e.getScreenX(),
            e.getScreenY());
        return true;
    }

    /** The promote/options entry (double-click or clean right-click on
     *  a diamond, a control, or the line): an existing control opens
     *  its Speed Control options; a plain stretch after a diamond
     *  becomes a NEW control first. */
    private void spPromoteOrOptions(double x, double y, double sx,
                                    double sy) {
        int[] seg = spFindSegment(x, y);
        int[] dia = spFindDiamond(x, y);
        List<RunSec> rl = runLayout();
        if (seg != null || (dia != null && dia[2] != 3)) {
            int r = seg != null ? seg[0] : dia[0];
            int ci = seg != null ? seg[1] : dia[1];
            RunSec rs = rl.get(r);
            spSelPiece = rs.piece();
            spSelSec = rs.section();
            spSelCtl = ci;
            spSelMark = -1;
            spTakeStripSelection();   // exclusive both ways (D249)
            spFocus = true;
            redraw();
            spShowCtlPopup(sx, sy);
            return;
        }
        RunSec rs = dia != null ? rl.get(dia[0]) : spRunSecAt(x);
        if (rs == null) {
            if (runSecAt(xToTPlain(x)) != null) {
                // influenced sections take controls since v19.6 (D235)
                app.statsLabel.setText("Speed controls aren't available "
                    + "on this section (bridge / legacy-reversed).");
            }
            return;
        }
        double nx = dia != null && dia[2] == 3
            ? rs.sec().speedMarks().get(dia[1]) + 1e-6
            : spNativeAt(rs, x);
        spCreateControlAt(rs, nx, sx, sy);
    }

    /** Turn the plain stretch containing native x into a control:
     *  boundaries = the nearest marks/control edges (section ends
     *  included), the consumed marks become the control's own
     *  diamonds. Opens the options popup. */
    private void spCreateControlAt(RunSec rs, double nx, double sx,
                                   double sy) {
        spCreateControlAt(rs, nx, sx, sy, true);
    }

    private void spCreateControlAt(RunSec rs, double nx, double sx,
                                   double sy, boolean push) {
        Section s = rs.sec();
        double end = spSecEnd(s);
        if (end < 0.05) return;
        double lo = 0, hi = end;
        for (SpeedControl c : s.speedControls()) {
            if (nx >= c.x0 - 1e-9 && nx <= c.x0 + c.len + 1e-9) {
                // inside an existing control: open it instead
                spSelPiece = rs.piece();
                spSelSec = rs.section();
                spSelCtl = s.speedControls().indexOf(c);
                spSelMark = -1;
                spTakeStripSelection();   // exclusive both ways (D249)
                spFocus = true;
                redraw();
                spShowCtlPopup(sx, sy);
                return;
            }
            if (c.x0 + c.len <= nx) lo = Math.max(lo, c.x0 + c.len);
            if (c.x0 >= nx) hi = Math.min(hi, c.x0);
        }
        Integer loMark = null, hiMark = null;
        List<Double> marks = s.speedMarks();
        for (int i = 0; i < marks.size(); i++) {
            double m = marks.get(i);
            if (m <= nx && m > lo) { lo = m; loMark = i; }
            if (m > nx && m < hi) { hi = m; hiMark = i; }
        }
        if (hi - lo < minGapOf(s) * 2) return;
        if (push) pushUndoSnapshot();
        SpeedControl c = new SpeedControl(lo, hi - lo);
        // target defaults to the design speed arriving at the control
        // — TWO decimals since v19.8 (D237; was whole m/s)
        c.speed = Math.max(0.5,
            Math.round(spDesignSpeedAt(rs, lo) * 100) / 100.0);
        s.speedControls().add(c);
        // the boundary marks become the control's own diamonds
        if (hiMark != null && loMark != null) {
            marks.remove(Math.max(loMark, hiMark));
            marks.remove(Math.min(loMark, hiMark));
        } else if (hiMark != null) {
            marks.remove((int) hiMark);
        } else if (loMark != null) {
            marks.remove((int) loMark);
        }
        spSelPiece = rs.piece();
        spSelSec = rs.section();
        spSelCtl = s.speedControls().size() - 1;
        spSelMark = -1;
        spTakeStripSelection();   // exclusive both ways (D249)
        spFocus = true;
        app.rebuildTrack();
        redraw();
        spShowCtlPopup(sx, sy);
    }

    /** Design speed at a section-local native x (compiled samples). */
    private double spDesignSpeedAt(RunSec rs, double nx) {
        TrackCompiler.Span sp = spanAt(rs.piece(), rs.section());
        if (sp == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) {
            return rs.sec().vDesign();
        }
        boolean nd = rs.sec().domain() == Section.Domain.DISTANCE;
        int i = Math.max(0, Math.min(app.lastSamples.size() - 1,
            sampleIdxAtCoord(nd ? sp.s0() + nx : sp.t0() + nx, nd)));
        return app.lastSamples.get(i).v();
    }

    /** Sample index nearest a GLOBAL t or s coordinate. */
    private int sampleIdxAtCoord(double v, boolean dist) {
        List<FVDIntegrator.Sample> ss = app.lastSamples;
        int lo = 0, hi = ss.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            double c = dist ? ss.get(mid).s() : ss.get(mid).t();
            if (c <= v) lo = mid; else hi = mid;
        }
        double a = dist ? ss.get(lo).s() : ss.get(lo).t();
        double b = dist ? ss.get(hi).s() : ss.get(hi).t();
        return v - a <= b - v ? lo : hi;
    }

    /** Design + true speeds at the selected control's START or END
     *  (the options popup's reference readouts — both since v18.7,
     *  D176). */
    private double[] spCtlSpeeds(RunSec rs, SpeedControl c, boolean end) {
        TrackCompiler.Span sp = spanAt(rs.piece(), rs.section());
        if (sp == null || app.lastSamples == null || app.trueV == null) {
            return new double[]{ Double.NaN, Double.NaN };
        }
        boolean nd = rs.sec().domain() == Section.Domain.DISTANCE;
        double gx = (nd ? sp.s0() : sp.t0()) + c.x0 + (end ? c.len : 0);
        int i = Math.max(0, Math.min(
            Math.min(app.lastSamples.size(), app.trueV.length) - 1,
            sampleIdxAtCoord(gx, nd)));
        return new double[]{ app.lastSamples.get(i).v(), app.trueV[i] };
    }

    /** "Start: 12.3 m/s (true 11.8)" — the popup's speed readout
     *  format (v18.7, D176: his exact labels). */
    private static String spSpeedLbl(String name, double[] sv) {
        if (Double.isNaN(sv[0])) return name + ": —";
        return Math.abs(sv[0] - sv[1]) > 0.05
            ? String.format("%s: %.1f m/s (true %.1f)",
                name, sv[0], sv[1])
            : String.format("%s: %.1f m/s", name, sv[0]);
    }

    /** Strip hover: divider cursor, the SHARED lane-hover pipeline on
     *  the selected control's graph, and the resulting TRUE-speed
     *  readout; true = the cursor/hover was handled here. */
    private boolean spMove(javafx.scene.input.MouseEvent e) {
        // the strip-height divider cursor (v18.2)
        if (spDragDivider || spDividerHit(e.getX(), e.getY())) {
            canvas.setCursor(Cursor.V_RESIZE);
            return true;
        }
        int hk = 0;
        double hx = 0, hy = 0, hv = 0;
        if (spVisible() && (spInStrip(e.getY())
                || spBoxHit(e.getX(), e.getY())
                || spArrowHit(e.getX(), e.getY()))) {
            int[] dia = spFindDiamond(e.getX(), e.getY());
            boolean overDia = dia != null;
            canvas.setCursor(overDia ? Cursor.H_RESIZE
                : spFindSegment(e.getX(), e.getY()) != null
                    || spBoxHit(e.getX(), e.getY())
                    || spArrowHit(e.getX(), e.getY())
                    ? Cursor.HAND : Cursor.DEFAULT);
            if (overDia) {
                // the diamond's POSITION readout (v19.20, D249, item
                // S2) — the diamond owns the cursor, so it wins over
                // the true-speed / long-G curves below; the display
                // value is derived at draw time
                RunSec drs = runLayout().get(dia[0]);
                double dnx = spDiamondNative(drs, dia);
                hk = 3;
                hx = spXOf(drs, dnx);
                hy = spLineY();
                hv = dnx;
                spHoverPiece = drs.piece();
                spHoverSec = drs.section();
            }
            if (app.settings.speedStripTall() && spInStrip(e.getY())) {
                // the SHARED hover pipeline on the selected control's
                // graph (v18.3): insert dot, Ctrl snap, node pre-light
                // and Shift-slope all come from the main plot's own
                // code via the overlays
                SpeedControl hcv = spSel();
                if (hcv != null && hcv.complex && spSelRunSec() != null) {
                    boolean[] did = { false };
                    withSurf(() -> {
                        int hn2 = hitNode(e.getX(), e.getY());
                        double t = hn2 < 0 && (selIdx < 0 || !spFocus
                                || hitKnob(e.getX(), e.getY()) == 0)
                            ? curveHitT(e.getX(), e.getY()) : Double.NaN;
                        hoverRawT = t;
                        t = snappedHover(t);
                        hoverT = t;
                        hoverNodeIdx = hn2;
                        did[0] = hn2 >= 0 || !Double.isNaN(t);
                    });
                    if (did[0]) {
                        if (!spHoverSurf) spHoverSurf = true;
                        spHoverKind = 0;
                        redraw();
                        return true;
                    }
                    if (spHoverSurf) {
                        spHoverSurf = false;
                        hoverT = Double.NaN;
                        hoverRawT = Double.NaN;
                        hoverNodeIdx = -1;
                        redraw();
                    }
                }
                if (app.trueV != null && app.lastSamples != null
                        && !app.lastSamples.isEmpty()) {
                    double a = xToTPlain(e.getX());
                    int gi = sampleIdxAtAxis(a);
                    // longitudinal-G hover inside complex const-
                    // speed controls (v18.4) — the blue dotted curve
                    // (a diamond hover, kind 3, already won)
                    if (hk == 0 && spLongGAt(a) != null) {
                        double gL = spLongG(gi);
                        if (!Double.isNaN(gL)) {
                            double py = spAToY(gL);
                            if (Math.abs(e.getY() - py) <= 8) {
                                hk = 2;
                                hx = e.getX();
                                hy = py;
                                hv = gL;
                                spHoverIdx = gi;
                            }
                        }
                    }
                    // TRUE-speed hover — hidden inside SPEED-type
                    // controls like the curve itself (v18.4)
                    if (hk == 0 && a >= runT0()
                            && a <= runT0() + runDur()
                            && !spInRanges(spSpeedCtlAxisRanges(), a)
                            && gi >= 0 && gi < app.trueV.length) {
                        double py = spVToY(app.trueV[gi]);
                        if (Math.abs(e.getY() - py) <= 8) {
                            hk = 1;
                            hx = e.getX();
                            hy = py;
                            hv = app.trueV[gi];
                            spHoverIdx = gi;   // the design suffix (D248)
                        }
                    }
                }
            }
        } else if (!spInStrip(e.getY())) {
            if (spHoverKind != 0) {
                spHoverKind = 0;
                redraw();
            }
            return false;
        }
        if (hk != spHoverKind || Math.abs(hx - spHoverX) > 0.5
                || Math.abs(hv - spHoverVal) > 1e-9) {
            spHoverKind = hk;
            spHoverX = hx;
            spHoverY = hy;
            spHoverVal = hv;
            redraw();
        }
        return spInStrip(e.getY());
    }

    /** Delete with the strip focused (v18.3): graph nodes route
     *  through the SHARED lane deletion (the main rules — node 0
     *  stays, last node shortens the graph); otherwise the selected
     *  CONTROL or MARK deletes. True = handled. */
    boolean spDeleteSelected() {
        Section s = spSelSection();
        if (s == null) return false;
        SpeedControl c = spSel();
        // a STRAY node index beside a selected control used to make
        // Delete a silent no-op (v19.6, D235 — deleteSelected's
        // stripHasNodeSel() check already routed LIVE node selections
        // to node deletion before we ever get here, so a leftover
        // selIdx means the control is what the user selected)
        if (c != null && (selIdx < 0 || !stripHasNodeSel())) {
            pushUndoSnapshot();
            s.speedControls().remove(spSelCtl);
            spClearSel();
            spHideCtlPopup();   // a delete shifts control indices (D249)
            app.rebuildTrack();
            redraw();
            return true;
        }
        if (c == null && spSelMark >= 0
                && spSelMark < s.speedMarks().size()) {
            pushUndoSnapshot();
            s.speedMarks().remove(spSelMark);
            spClearSel();
            spHideCtlPopup();   // (D249: delete = hide)
            redraw();
            return true;
        }
        return false;
    }

    /** Match a complex const-speed graph's end node to the TRUE
     *  speed just OUTSIDE the control — value and slope in the
     *  section's native axis units. */
    private boolean spMatchRealEnd(SpeedControl c, RunSec rs,
                                   boolean start) {
        TrackCompiler.Span sp = spanAt(rs.piece(), rs.section());
        if (sp == null || app.trueV == null || app.lastSamples == null
                || app.lastSamples.size() < 8) return false;
        boolean nd = rs.sec().domain() == Section.Domain.DISTANCE;
        double gx = (nd ? sp.s0() : sp.t0()) + c.x0 + (start ? 0 : c.len);
        int at = sampleIdxAtCoord(gx, nd);
        int n = Math.min(app.lastSamples.size(), app.trueV.length);
        // read ~3 samples OUTSIDE the control (the inside is the
        // clamp's own doing)
        int a = start ? Math.max(0, at - 5) : Math.min(n - 1, at + 2);
        int b = start ? Math.max(0, at - 2) : Math.min(n - 1, at + 5);
        if (b <= a) return false;
        double ca = nd ? app.lastSamples.get(a).s()
                       : app.lastSamples.get(a).t();
        double cb = nd ? app.lastSamples.get(b).s()
                       : app.lastSamples.get(b).t();
        if (cb - ca < 1e-9) return false;
        double vT = app.trueV[start ? b : a];   // nearest the boundary,
                                                // outside
        double slope = (app.trueV[b] - app.trueV[a]) / (cb - ca);
        pushUndoSnapshot();
        BezierBaker.Node node = c.lane.get(start ? 0
            : c.lane.size() - 1);
        node.val = FvdDesigner.clamp(vT, 0.5, 200);
        node.smooth = true;
        if (start && c.lane.size() > 1) {
            node.outDv = slope
                * BezierBaker.effOutDt(node, c.lane.get(1));
        } else if (!start && c.lane.size() > 1) {
            node.inDv = slope * BezierBaker.effInDt(
                c.lane.get(c.lane.size() - 2), node);
        }
        app.rebuildTrack();
        redraw();
        return true;
    }

    // ---------- the Speed Control options popup ----------

    /** NULL-BEFORE-HIDE (v19.18, D247 — the seam pop-out's undo bug
     *  had its twin here: spAfterRebuild tears this panel down after
     *  an undo removed the selected control, and the hide-time
     *  focus-loss commit of a drag-set x0/len box's "#.##" text ran
     *  the listeners as a user edit — a stray snapshot that cleared
     *  redo). The field is nulled first so every listener's
     *  `pop != spPopup` guard rejects the stray event. */
    private void spHideCtlPopup() {
        javafx.stage.Popup old = spPopup;
        if (old == null) return;
        spPopup = null;
        spPopupSync = null;
        spPopPiece = spPopSec = spPopCtl = -1;   // the binding goes with it (D249)
        app.modelHooks.remove(spHook);   // live-window de-registration (D247)
        old.hide();
    }

    /** The speed-control panel's ONE stable model hook (D247):
     *  registered by spShowCtlPopup, removed by spHideCtlPopup;
     *  spAfterRebuild re-syncs the open panel or hides it when its
     *  control is gone (an undo past its creation). */
    private final Runnable spHook = this::spAfterRebuild;

    void spShowCtlPopup(double sx, double sy) {
        spHideCtlPopup();
        // INDEX-RESOLVED control (v19.18, D247): the open-time objects
        // c0/rs0 seed the widgets only; every handler and the sync
        // re-resolve through the panel's OWN binding spPopSel()/
        // spPopRunSec() (v19.20, D249 — a copy of the SELECTION indices
        // taken here; they survive applyDocState) — after an undo the
        // panel writes the LIVE control, never the orphaned capture; a
        // vanished control hides the panel; a selection moving to a
        // node/marker/gizmo leaves it open on its control
        spPopPiece = spSelPiece;
        spPopSec = spSelSec;
        spPopCtl = spSelCtl;
        SpeedControl c0 = spPopSel();
        RunSec rs0 = spPopRunSec();
        if (c0 == null || rs0 == null) {
            spPopPiece = spPopSec = spPopCtl = -1;
            return;
        }
        java.util.function.Supplier<SpeedControl> cs = this::spPopSel;
        java.util.function.Supplier<RunSec> rss = this::spPopRunSec;
        javafx.stage.Popup pop = new javafx.stage.Popup();
        pop.setAutoHide(false);   // Apply-free live panel; ✕ closes
        spPopup = pop;
        VBox box = new VBox(7);
        box.setPadding(new javafx.geometry.Insets(10, 14, 10, 12));
        box.setStyle("-fx-background-color: rgb(38,42,53);"
            + " -fx-background-radius: 6;"
            + " -fx-border-color: rgb(95,102,120);"
            + " -fx-border-radius: 6;");

        boolean[] syncing = { false };
        String unit = rs0.sec().domain() == Section.Domain.DISTANCE
            ? "m" : "s";

        Label title = new Label("Speed Control");
        title.setTextFill(Color.WHITE);
        title.setStyle("-fx-font-weight: bold;");
        Button close = new Button("✕");
        close.setFocusTraversable(false);
        close.setStyle("-fx-font-size: 9px; -fx-padding: 1 5 1 5;");
        close.setOnAction(ev -> spHideCtlPopup());
        javafx.scene.layout.Region tSpacer = new javafx.scene.layout.Region();
        HBox.setHgrow(tSpacer, javafx.scene.layout.Priority.ALWAYS);
        HBox titleRow = new HBox(8, title, tSpacer, close);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup tg = new ToggleGroup();
        // "Constant" dropped from both since v19.19 (D248, user spec);
        // the parenthetical hints stay
        RadioButton rSpeed = new RadioButton("Speed (lift, transport)");
        RadioButton rAccel = new RadioButton("Acceleration (launch, brake)");
        for (RadioButton rb : new RadioButton[]{ rSpeed, rAccel }) {
            rb.setToggleGroup(tg);
            rb.setTextFill(Color.WHITE);
            rb.setStyle("-fx-font-size: 11px;");
        }
        CheckBox complex = new CheckBox("Complex control (editable graph)");
        complex.setTextFill(Color.WHITE);
        complex.setStyle("-fx-font-size: 11px;");

        Label speedLbl = new Label("Speed (m/s)");
        speedLbl.setTextFill(Color.WHITE);
        speedLbl.setStyle("-fx-font-size: 11px;");
        Spinner<Double> speedSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0.5, 200, c0.speed, 1));
        speedSp.setEditable(true);
        speedSp.setPrefWidth(76);
        FvdDesigner.numericEditor(speedSp, false);   // v18.1: digits only
        Button editGraph = new Button("Edit Speed");
        editGraph.setFocusTraversable(false);
        editGraph.setStyle("-fx-font-size: 11px;");
        CheckBox maxC = new CheckBox("Max speed");
        CheckBox minC = new CheckBox("Min speed");
        for (CheckBox cb : new CheckBox[]{ maxC, minC }) {
            cb.setTextFill(Color.WHITE);
            cb.setStyle("-fx-font-size: 11px;");
        }
        HBox speedRow = new HBox(8);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        HBox limitRow = new HBox(10, maxC, minC);
        limitRow.setAlignment(Pos.CENTER_LEFT);

        Label accelLbl = new Label("Acceleration (g)");
        accelLbl.setTextFill(Color.WHITE);
        accelLbl.setStyle("-fx-font-size: 11px;");
        Spinner<Double> accelSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 20, c0.accel, 0.05));
        accelSp.setEditable(true);
        accelSp.setPrefWidth(76);
        FvdDesigner.numericEditor(accelSp, false);
        HBox accelRow = new HBox(8);
        accelRow.setAlignment(Pos.CENTER_LEFT);

        // ACCEL/DECEL on ONE line (v18.45 D217; chain-link round
        // v18.47 D219 — user spec): Acceleration and Deceleration
        // are ALWAYS visible with a 🔗 LINK button between them.
        // Linked (default) = editing either spinner writes both —
        // the classic one-magnitude behavior; unlink to brake at
        // its own felt G. Complex swaps the spinners for Edit
        // buttons in the same layout (▸ marks the displayed graph).
        Label decelLbl = new Label("Deceleration (g)");
        decelLbl.setTextFill(Color.WHITE);
        decelLbl.setStyle("-fx-font-size: 11px;");
        Spinner<Double> decelSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 20, c0.decel, 0.05));
        decelSp.setEditable(true);
        decelSp.setPrefWidth(76);
        FvdDesigner.numericEditor(decelSp, false);
        javafx.scene.control.ToggleButton link =
            new javafx.scene.control.ToggleButton("🔗");
        link.setFocusTraversable(false);
        Tooltip.install(link, new Tooltip(
            "Link Acceleration and Deceleration"));
        Button editDecel = new Button("Edit Deceleration");
        editDecel.setFocusTraversable(false);
        editDecel.setStyle("-fx-font-size: 11px;");

        Label startLbl = new Label("Start (" + unit + ")");
        startLbl.setTextFill(Color.WHITE);
        startLbl.setStyle("-fx-font-size: 11px;");
        Spinner<Double> startSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                0, 100000, c0.x0, unit.equals("m") ? 1 : 0.1));
        // the box MOVES the control (a position); the strip's start
        // diamond RESIZES from the left since v19.19 (D248)
        Tooltip.install(startSp, new Tooltip("Where the control starts "
            + "— moves it, length kept. On the strip, the start diamond "
            + "resizes the control from the left instead."));
        startSp.setEditable(true);
        startSp.setPrefWidth(76);
        FvdDesigner.numericEditor(startSp, false);
        Label lenLbl = new Label("Length (" + unit + ")");
        lenLbl.setTextFill(Color.WHITE);
        lenLbl.setStyle("-fx-font-size: 11px;");
        Spinner<Double> lenSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                // v18.1: minimum length = 2× the domain's min node gap
                unit.equals("m") ? 0.2 : 0.02, 100000, c0.len,
                unit.equals("m") ? 1 : 0.1));
        lenSp.setEditable(true);
        lenSp.setPrefWidth(76);
        FvdDesigner.numericEditor(lenSp, false);
        // DISPLAY-unit TWINS (v19.20, D249, item S1): the Start/Length
        // boxes stay NATIVE (they write model coordinates — a
        // display-unit box would need a live inverse warp per
        // keystroke); while the control's section is VIEWED in the
        // other domain a grey "= 12.5 m" / "= 3.20 s" beside each box
        // shows the displayed value (the node editor's D241 twin
        // pattern, read-only), hidden otherwise
        Label startTwin = new Label();
        Label lenTwin = new Label();
        for (Label tw : new Label[]{ startTwin, lenTwin }) {
            tw.setTextFill(Color.rgb(150, 156, 170));
            tw.setStyle("-fx-font-size: 11px;");
            tw.setVisible(false);
            tw.setManaged(false);
        }
        Tooltip.install(startTwin, new Tooltip(
            "The start as the graph's axis shows it."));
        Tooltip.install(lenTwin, new Tooltip(
            "The length as the graph's axis shows it."));
        HBox posRow = new HBox(8, startLbl, startSp, startTwin,
            lenLbl, lenSp, lenTwin);
        posRow.setAlignment(Pos.CENTER_LEFT);

        Label startSpdLbl = new Label();
        startSpdLbl.setTextFill(Color.rgb(160, 220, 170));
        // constant transparent border so the hover outline never
        // shifts the layout (v19.8, D237)
        String enterBase = "-fx-font-size: 11px; -fx-border-width: 1;"
            + " -fx-border-radius: 3; -fx-padding: 0 3 0 3;"
            + " -fx-border-color: transparent;";
        String enterHot = "-fx-font-size: 11px; -fx-border-width: 1;"
            + " -fx-border-radius: 3; -fx-padding: 0 3 0 3;"
            + " -fx-border-color: rgb(160,220,170);";
        startSpdLbl.setStyle(enterBase);
        // CLICK = set the control's target speed to the enter speed
        // (v19.8, D237, user spec) — not for complex SPEED controls
        // (their graph IS the speed; the label stays a plain readout)
        Tooltip enterTip = new Tooltip(
            "Make control speed equal to enter speed");
        enterTip.setShowDelay(Duration.millis(300));
        java.util.function.Supplier<Boolean> enterClickable = () -> {
            SpeedControl c = cs.get();
            return c != null && !(c.complex && c.type != SpeedControl.ACCEL);
        };
        startSpdLbl.setOnMouseEntered(ev -> {
            if (enterClickable.get()) startSpdLbl.setStyle(enterHot);
        });
        startSpdLbl.setOnMouseExited(ev ->
            startSpdLbl.setStyle(enterBase));
        Label endLbl = new Label();
        endLbl.setTextFill(Color.rgb(160, 220, 170));
        endLbl.setStyle("-fx-font-size: 11px;");
        // Enter + End on ONE line, Enter first (v18.8, user spec —
        // they were stacked rows in v18.7; renamed Start → Enter in
        // v19.8, D237)
        HBox refRow = new HBox(14, startSpdLbl, endLbl);
        refRow.setAlignment(Pos.CENTER_LEFT);
        startSpdLbl.setOnMouseClicked(ev -> {
            if (!enterClickable.get()) return;
            SpeedControl c = cs.get();
            RunSec rs = rss.get();
            if (c == null || rs == null) return;
            double[] sv = spCtlSpeeds(rs, c, false);
            if (Double.isNaN(sv[0])) return;
            speedSp.getValueFactory().setValue(Math.max(0.5,
                Math.round(sv[0] * 100) / 100.0));
        });

        // wrap ONCE (grayHint hooks disabledProperty — wrapping inside
        // the relayout would stack a listener per sync)
        StackPane editDecelWrap = grayHint(editDecel,
            "Unlink 🔗 for a separate brake graph.");
        StackPane decelSpWrap = grayHint(decelSp,
            "Unlink 🔗 for a separate brake value.");
        Runnable relayout = () -> {
            SpeedControl c = cs.get();
            if (c == null) return;
            speedRow.getChildren().clear();
            accelRow.getChildren().clear();
            boolean acc = c.type == SpeedControl.ACCEL;
            if (c.complex && !acc) {
                editGraph.setText("Edit Speed");
                speedRow.getChildren().addAll(speedLbl, editGraph);
            } else {
                speedLbl.setText(acc ? "Target speed (m/s)" : "Speed (m/s)");
                speedRow.getChildren().addAll(speedLbl, speedSp);
            }
            speedRow.getChildren().add(limitRow);
            if (acc) {
                // ONE line: accel · 🔗 · decel (v18.47, D219); the ▸
                // marks the DISPLAYED graph — while linked the accel
                // graph serves both directions and the decel side is
                // grayed (v18.48, D220)
                if (c.complex) {
                    editGraph.setText((c.tied || !c.showDecel ? "▸ " : "")
                        + "Edit Acceleration");
                    editDecel.setText((!c.tied && c.showDecel ? "▸ " : "")
                        + "Edit Deceleration");
                    // grayed-while-linked = explained on hover (D235)
                    accelRow.getChildren().addAll(editGraph, link,
                        editDecelWrap);
                } else {
                    accelRow.getChildren().addAll(accelLbl, accelSp,
                        link, decelLbl, decelSpWrap);
                }
                accelRow.setVisible(true);
                accelRow.setManaged(true);
            } else {
                accelRow.setVisible(false);
                accelRow.setManaged(false);
            }
        };

        Runnable sync = () -> {
            SpeedControl c = cs.get();
            RunSec rs = rss.get();
            if (c == null || rs == null) { spHideCtlPopup(); return; }
            syncing[0] = true;
            (c.type == SpeedControl.ACCEL ? rAccel : rSpeed)
                .setSelected(true);
            complex.setSelected(c.complex);
            // FOCUS-GUARDED spinner writes (D247): a box being typed
            // into is never overwritten by a live resync
            app.setUnlessTyping(speedSp, c.speed);
            app.setUnlessTyping(accelSp, c.accel);
            app.setUnlessTyping(decelSp, c.decel);
            link.setSelected(c.tied);
            // small button, glyph-size chain, NEON-YELLOW highlight
            // while linked, no outline (v18.48, D220 — user spec;
            // the chain glyph itself stays black either way)
            link.setStyle("-fx-font-size: 11px; -fx-padding: 0 3 0 3;"
                + " -fx-background-radius: 4; -fx-text-fill: black;"
                + " -fx-background-color: "
                // bright PURE yellow (v18.48 — the first pick's green
                // cast came from G > R)
                + (c.tied ? "rgb(255,235,0);" : "rgb(190,194,204);"));
            // linked = the decel side is DISPLAY-ONLY (v18.48, D220
            // — user spec: gray it out; the mirrored value still
            // updates live as acceleration is edited)
            decelSp.setDisable(c.tied);
            decelLbl.setOpacity(c.tied ? 0.45 : 1.0);
            editDecel.setDisable(c.tied);
            maxC.setSelected(c.maxLimit);
            minC.setSelected(c.minLimit);
            app.setUnlessTyping(startSp, c.x0);
            app.setUnlessTyping(lenSp, c.len);
            // the display-unit twins follow the VIEW flag live (the
            // sync runs per redraw through spHook — a view flip, a
            // drag, an undo all re-read; only the active section can
            // be viewed, so leaving it hides them)
            boolean viewed = rs.sec().viewOtherDomain();
            if (viewed) {
                boolean m = axisDistPlain();
                double d0 = spDispOf(rs, c.x0);
                double d1 = spDispOf(rs, c.x0 + c.len);
                startTwin.setText("= " + SpeedStrip.posValue(d0, m));
                lenTwin.setText("= " + SpeedStrip.posValue(d1 - d0, m));
            }
            for (Label tw : new Label[]{ startTwin, lenTwin }) {
                tw.setVisible(viewed);
                tw.setManaged(viewed);
            }
            // Enter + End reference readouts (v18.7, D176; renamed
            // v19.8, D237 — Enter is CLICKABLE: target := enter
            // speed, except complex SPEED controls)
            startSpdLbl.setText(spSpeedLbl("Enter",
                spCtlSpeeds(rs, c, false)));
            endLbl.setText(spSpeedLbl("End", spCtlSpeeds(rs, c, true)));
            if (enterClickable.get()) {
                Tooltip.install(startSpdLbl, enterTip);
            } else {
                Tooltip.uninstall(startSpdLbl, enterTip);
                startSpdLbl.setStyle(enterBase);
            }
            relayout.run();
            syncing[0] = false;
        };
        spPopupSync = sync;
        // LIVE (D247): the panel follows every compile through the
        // shell's model hooks (spAfterRebuild hides it when its
        // control is gone); de-registered in spHideCtlPopup
        if (!app.modelHooks.contains(spHook)) app.modelHooks.add(spHook);

        // every discrete change: one undo step, live rebuild
        Runnable changed = () -> {
            if (syncing[0]) return;
            app.rebuildTrack();
            redraw();
            sync.run();
        };
        rSpeed.setOnAction(ev -> {
            if (syncing[0]) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { sync.run(); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.type = SpeedControl.SPEED;
            if (c.complex) c.freshLane(c.speed);
            if (c.showDecel) spDropNodeSel();
            c.showDecel = false;   // SPEED has one graph (D217)
            changed.run();
        });
        rAccel.setOnAction(ev -> {
            if (syncing[0]) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { sync.run(); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.type = SpeedControl.ACCEL;
            if (c.complex) {
                c.freshLane(c.accel);
                if (!c.tied) c.freshDecelLane(c.decel);
            }
            changed.run();
        });
        complex.setOnAction(ev -> {
            if (syncing[0]) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { sync.run(); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.complex = complex.isSelected();
            if (c.complex && c.lane.size() < 2) {
                c.freshLane(c.type == SpeedControl.ACCEL
                    ? c.accel : c.speed);
            }
            if (c.complex && !c.tied && c.type == SpeedControl.ACCEL
                    && c.decelLane.size() < 2) {
                c.freshDecelLane(c.decel);   // D217
            }
            changed.run();
        });
        link.setOnAction(ev -> {
            if (syncing[0]) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { sync.run(); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.tied = link.isSelected();
            // both directions leave the values EQUAL at the flip:
            // re-linking snaps decel onto accel (the mirror resumes),
            // unlinking seeds the decel side from the accel side so
            // nothing changes at the split (v18.45, D217; the accel
            // graph displays first — user spec)
            c.decel = c.accel;
            if (!c.tied && c.complex) {
                c.decelLane.clear();
                for (BezierBaker.Node n : c.lane) {
                    c.decelLane.add(n.copy());
                }
            }
            if (c.showDecel) spDropNodeSel();
            c.showDecel = false;
            changed.run();
        });
        editGraph.setOnAction(ev -> {
            SpeedControl c = cs.get();
            if (c == null) return;
            spSelectPopupControl();   // the strip edits THIS control (D249 review)
            // untied complex: this button also SELECTS the accel
            // graph for display (v18.45, D217)
            if (c.showDecel) {
                spDropNodeSel();
                c.showDecel = false;
            }
            if (!app.settings.speedStripTall()) {
                app.settings.setSpeedStripTall(true);
            }
            if (!spVisible()) app.settings.setShowSpeedStrip(true);
            redraw();
            if (spPopupSync != null) spPopupSync.run();
            spMovePopupClear(pop);
        });
        editDecel.setOnAction(ev -> {
            SpeedControl c = cs.get();
            if (c == null || !c.complex) return;
            spSelectPopupControl();   // the strip edits THIS control (D249 review)
            // LINKED: one graph serves both directions — this button
            // just opens it, exactly like Edit Acceleration (v18.47)
            if (!c.tied && !c.showDecel) {
                spDropNodeSel();
                c.showDecel = true;
            }
            if (!app.settings.speedStripTall()) {
                app.settings.setSpeedStripTall(true);
            }
            if (!spVisible()) app.settings.setShowSpeedStrip(true);
            redraw();
            if (spPopupSync != null) spPopupSync.run();
            spMovePopupClear(pop);
        });
        // every listener below carries the `pop != spPopup` IDENTITY
        // GUARD (v19.18, D247): after spHideCtlPopup nulls the field
        // a hide-time focus-loss commit can no longer write the stale
        // control or push a stray snapshot
        decelSp.valueProperty().addListener((o, a, v) -> {
            if (syncing[0] || v == null || pop != spPopup) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { app.revertSpinner(decelSp, a); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.decel = v;
            if (c.tied) c.accel = v;   // linked: mirror (v18.47, D219)
            changed.run();
        });
        speedSp.valueProperty().addListener((o, a, v) -> {
            if (syncing[0] || v == null || pop != spPopup) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { app.revertSpinner(speedSp, a); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.speed = v;
            changed.run();
        });
        accelSp.valueProperty().addListener((o, a, v) -> {
            if (syncing[0] || v == null || pop != spPopup) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { app.revertSpinner(accelSp, a); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.accel = v;
            if (c.tied) c.decel = v;   // linked: mirror (v18.47, D219)
            changed.run();
        });
        maxC.setOnAction(ev -> {
            if (syncing[0] || pop != spPopup) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { sync.run(); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.maxLimit = maxC.isSelected();
            if (!c.maxLimit && !c.minLimit) c.minLimit = true;  // one stays
            changed.run();
        });
        minC.setOnAction(ev -> {
            if (syncing[0] || pop != spPopup) return;
            SpeedControl c = cs.get();
            if (c == null) return;
            if (app.convertBusy()) { sync.run(); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.minLimit = minC.isSelected();
            if (!c.maxLimit && !c.minLimit) c.maxLimit = true;
            changed.run();
        });
        startSp.valueProperty().addListener((o, a, v) -> {
            if (syncing[0] || v == null || pop != spPopup) return;
            Section s = spPopSection();   // the panel's own control (D249)
            SpeedControl c = cs.get();
            if (s == null || c == null) return;
            if (app.convertBusy()) { app.revertSpinner(startSp, a); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            c.x0 = FvdDesigner.clamp(v, 0,
                Math.max(0, spSecEnd(s) - c.len));
            changed.run();
        });
        lenSp.valueProperty().addListener((o, a, v) -> {
            if (syncing[0] || v == null || pop != spPopup) return;
            Section s = spPopSection();   // the panel's own control (D249)
            SpeedControl c = cs.get();
            if (s == null || c == null) return;
            if (app.convertBusy()) { app.revertSpinner(lenSp, a); return; }   // F3 (D247 review fix)
            pushUndoSnapshot();
            double oldLen = c.len;
            c.len = FvdDesigner.clamp(v, minGapOf(s) * 2,
                Math.max(minGapOf(s) * 2, spSecEnd(s) - c.x0));
            if (c.complex && oldLen > 1e-9) {
                spRescaleCtlLanes(c, c.len / oldLen);   // both (D217)
            }
            changed.run();
        });

        // the Start/End reference line sits directly UNDER the
        // target-speed row (v18.1's placement, inlined v18.8)
        box.getChildren().addAll(titleRow, rSpeed, rAccel, complex,
            speedRow, refRow, accelRow, posRow);
        double[] grab = new double[2];
        box.setOnMousePressed(ev -> {
            grab[0] = ev.getScreenX() - pop.getX();
            grab[1] = ev.getScreenY() - pop.getY();
        });
        box.setOnMouseDragged(ev -> {
            pop.setX(ev.getScreenX() - grab[0]);
            pop.setY(ev.getScreenY() - grab[1]);
        });
        pop.getContent().add(box);
        sync.run();
        pop.show(canvas.getScene().getWindow(), sx + 12, sy - 40);
        // ride/fly keys keep working while the panel holds key focus
        // (v18.1 — the pv popup's forwarder)
        app.installDocUndoKeys(pop.getScene());   // D227: global undo + ride keys
        hookSpFocus();
    }

    // the Speed Control panel hides when the APP loses focus and
    // returns — same spot, still open — when it comes back (v18.1,
    // user spec; the pv popup's v14.4 pattern: a Popup is its own OS
    // window and would float above other applications otherwise).
    private boolean spFocusHooked, spFocusHidden;
    private double spHidX, spHidY;

    private void hookSpFocus() {
        if (spFocusHooked || canvas.getScene() == null
                || canvas.getScene().getWindow() == null) return;
        spFocusHooked = true;
        canvas.getScene().getWindow().focusedProperty()
            .addListener((o, was, is) -> {
                if (!is && spPopup != null && spPopup.isShowing()) {
                    spHidX = spPopup.getX();
                    spHidY = spPopup.getY();
                    spPopup.hide();
                    spFocusHidden = true;
                } else if (is && spFocusHidden) {
                    spFocusHidden = false;
                    if (spPopup != null && spPopSel() != null) {   // its own control (D249)
                        spPopup.show(canvas.getScene().getWindow(),
                            spHidX, spHidY);
                    }
                }
            });
    }

    /** Nudge the options popup out of the strip's way (the Edit
     *  Speed/Accel button just expanded it). */
    private void spMovePopupClear(javafx.stage.Popup pop) {
        javafx.geometry.Point2D p = canvas.localToScreen(0, spTop());
        if (p != null && pop.getY() + pop.getHeight() > p.getY() - 4) {
            pop.setY(Math.max(20, p.getY() - pop.getHeight() - 8));
        }
    }

    /** The popup follows compiles (end-speed label + stale checks) —
     *  and, since v19.20 (D249), FOLLOWS a newly SELECTED control: when
     *  the selection names a control other than the panel's own it is
     *  re-shown at its current position bound to that control (the
     *  pre-D249 follow came for free from resolving the selection; the
     *  rebuild also refreshes the domain unit, which a cross-domain
     *  follow used to leave stale). Never mid-gesture (spDragKind ==
     *  0). Its own control vanished → hide. */
    private void spAfterRebuild() {
        if (spPopup != null && spPopup.isShowing()) {
            if (spSelCtl >= 0 && spDragKind == 0 && !spPopBoundToSel()
                    && spSel() != null && spSelRunSec() != null) {
                double x = spPopup.getX(), y = spPopup.getY();
                spShowCtlPopup(x - 12, y + 40);   // spShowCtlPopup offsets (+12, −40)
                return;
            }
            if (spPopSel() == null) spHideCtlPopup();
            else if (spPopupSync != null) spPopupSync.run();
        }
    }

    /** The LIVE initial-speed window (v19.20, D249): one at a time,
     *  null-before-hide; its hook de-registers with it. */
    private Stage initSpeedWin;
    private Runnable initSpeedHook;
    /** Its docRestoreHooks twin (D249 review fix): an undo restore
     *  re-bases the Cancel record, the placement windows' rule. */
    private Runnable initSpeedRestoreHook;

    void hideInitSpeedWindow() {
        Stage old = initSpeedWin;
        if (old == null) return;
        initSpeedWin = null;
        if (initSpeedHook != null) app.modelHooks.remove(initSpeedHook);
        if (initSpeedRestoreHook != null) {
            docRestoreHooks.remove(initSpeedRestoreHook);
        }
        initSpeedHook = initSpeedRestoreHook = null;
        old.close();
    }

    /** INITIAL DESIGN SPEED (v18.0, D162 — user spec): type the
     *  section's starting design speed directly, any time after
     *  creation. LIVE since v19.20 (D249, user spec — the node
     *  editor's model): every spinner change APPLIES AT ONCE — the
     *  ride entry (piece 0, section 0) writes v0 + section 0's
     *  vDesign through the muted Start-speed spinner (its [1,100]
     *  factory clamp = the D198 entry rule, no history push of its
     *  own); any other section writes vDesign + PINS (a deliberate
     *  conflict the reconcile leaves alone — a value re-typed EQUAL
     *  still pins, D242) — with the undo coalesced per 0.8 s typing
     *  burst (the liveUndo recipe). "Set Design to Real" runs
     *  Coaster.refreezeSpeedOnly AT ONCE (one undo step: the exact
     *  seam speed, un-pinned so it follows the real flow; an
     *  ORIENTATION conflict is left standing — the badge menu's
     *  resolve op alone bakes tilt/follow; D241's fill-the-box +
     *  D242's unmodified-Apply rule are superseded: with live
     *  editing there is no Apply moment); the ride entry grays it
     *  (its real entry speed IS v0). A LiveWindow: Confirm / Enter /
     *  the X keep (the focused box commits first), Cancel / Esc
     *  restore the open-time vDesign + vPinned (+ v0 for the ride
     *  entry) as one more undo step — skipped with CANCEL_STALE_MSG
     *  when the coaster switched or a generation the hooks did not
     *  re-capture (D249 review fix: the record RE-BASES on every undo
     *  restore through a docRestoreHooks twin and, in the model hook,
     *  on any document generation it has not seen — the density
     *  preview's Apply path; the three placement windows' rule. Cancel
     *  then reverts to the state the last restore left, and edits
     *  typed after an undo cancel normally). The hook re-seeds the box
     *  from the model (undo outranks typing, and an undo ends the
     *  coalescing burst) and tears the window down when its section
     *  is gone. */
    private void showInitSpeedDialog(int k) {
        List<Section> secs = sections();
        if (k < 0 || k >= secs.size()) return;
        hideInitSpeedWindow();
        final int piece = activePiece;             // modeless: capture
        final Coaster openC = app.coaster;
        final int[] openGen = { app.docGen };      // re-based (D249 review)
        // INDEX-RESOLVED section (v19.18, D247): every use re-reads
        // the live document — an undo/redo replaces the Section
        // objects; the capture below only seeds the box.
        java.util.function.Supplier<Section> secAt = () -> {
            if (app.coaster != openC) return null;
            if (piece < 0 || piece >= app.coaster.pieces.size()) return null;
            List<Section> ss = app.coaster.pieces.get(piece).sections;
            return k < ss.size() ? ss.get(k) : null;
        };
        Section sec = secs.get(k);
        final boolean entry = piece == 0 && k == 0;   // the ride entry
        // the OPEN-TIME record — Cancel's field-level revert; MUTABLE:
        // an undo restore / an unseen document generation re-bases it
        // (D249 review fix — it was captured once, so every Cancel
        // after an undo skipped with CANCEL_STALE_MSG)
        final double[] openV = { sec.vDesign() };
        final boolean[] openPin = { sec.vPinned() };
        final double[] openV0 = { app.coaster.v0 };
        Runnable rebase = () -> {
            Section s0 = secAt.get();
            if (s0 == null) return;
            openV[0] = s0.vDesign();
            openPin[0] = s0.vPinned();
            openV0[0] = app.coaster.v0;
            openGen[0] = app.docGen;
        };
        final boolean[] syncing = { false };
        // UNDO COALESCING (the node editor's liveUndo recipe): edits
        // within 0.8 s share one step; a fresh key / the button /
        // Cancel / an undo restore end the burst
        final String[] undoKey = { null };
        final long[] undoNanos = { 0 };
        Runnable endBurst = () -> undoKey[0] = null;
        java.util.function.Consumer<String> liveUndo = key -> {
            long now = System.nanoTime();
            if (!key.equals(undoKey[0]) || now - undoNanos[0] > 800_000_000L) {
                pushUndoSnapshot();
            }
            undoKey[0] = key;
            undoNanos[0] = now;
        };
        final Stage[] winRef = { null };
        Runnable teardown = () -> {
            if (winRef[0] != null && winRef[0] == initSpeedWin) {
                hideInitSpeedWindow();
            }
        };
        Label lbl = new Label("Initial design speed (m/s)");
        // a TYPABLE SPINNER since v19.19 (D248 — the global spinner
        // conventions: wheel/arrows step 1 m/s, Shift a tenth, Ctrl
        // snaps to whole multiples, holding repeats; %.2f display,
        // typed values apply at full precision; the factory range IS
        // the D198 admit-what-the-app-writes clamp)
        Spinner<Double> vSp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(
                Gestures.INIT_SPEED_MIN, Gestures.INIT_SPEED_MAX,
                Gestures.clampInitSpeed(sec.vDesign()), 1));
        // the converter goes on BEFORE typable(): numericEditor wraps
        // whatever converter it finds with the blank-safe one (D212)
        vSp.getValueFactory().setConverter(
            FvdDesigner.fixedDecimalConverter(2));
        vSp.setPrefWidth(86);
        FvdDesigner.typable(vSp, false);
        // the LIVE apply — every value change, muted only by the
        // window's own programmatic writes
        java.util.function.Consumer<Double> apply = v -> {
            Section live0 = secAt.get();
            if (live0 == null) {
                teardown.run();
                app.statsLabel.setText("The initial design speed window "
                    + "closed — its section no longer exists.");
                return;
            }
            double cv = Gestures.clampInitSpeed(v);
            if (entry) {
                // the ride entry's value is what the Start-speed
                // spinner will take ([1,100]) — compare THAT, so a
                // typed 0.5 over a v0 of 1.0 is the no-op it is and
                // pushes no empty undo step (D249 review fix)
                cv = app.clampStartSpeed(cv);
                if (Math.abs(cv - app.coaster.v0) < 1e-9
                        && Math.abs(cv - live0.vDesign()) < 1e-9) {
                    return;
                }
                liveUndo.accept("v");
                app.writeStartSpeed(cv);   // v0 + section 0, muted
            } else {
                // equal AND already pinned = nothing to do; equal but
                // unpinned still PINS (D242's re-typed-equal rule)
                if (Math.abs(cv - live0.vDesign()) < 1e-9
                        && live0.vPinned()) {
                    return;
                }
                liveUndo.accept("v");
                live0.vDesign(cv);
                live0.vPinned(true);   // deliberate: reconcile leaves it
            }
            app.markDirty();
            app.rebuildTrack();
            refreshOffset();
            redraw();
        };
        vSp.valueProperty().addListener((o, a, b) -> {
            if (syncing[0] || b == null) return;
            // the NULL-BEFORE-HIDE identity guard (D247): a torn-down
            // window's editor loses focus on close, and that commit
            // must never write through an orphaned listener
            if (winRef[0] != null && winRef[0] != initSpeedWin) return;
            if (app.convertBusy()) {   // the D247 transaction guard
                syncing[0] = true;
                try {
                    app.revertSpinner(vSp, a);
                } finally {
                    syncing[0] = false;
                }
                return;
            }
            apply.accept(b);
        });
        // a box that loses focus re-seeds from the MODEL (after
        // numericEditor's focus-loss commit, registered earlier, ran
        // the apply): the ride entry's [1,100] clamp can leave the
        // typed text above/below what the model took
        vSp.getEditor().focusedProperty().addListener((o, a, f) -> {
            if (f || syncing[0]) return;
            if (winRef[0] != null && winRef[0] != initSpeedWin) return;
            Section s0 = secAt.get();
            if (s0 == null) return;
            syncing[0] = true;
            try {
                app.setUnlessTyping(vSp, Gestures.clampInitSpeed(s0.vDesign()));
            } finally {
                syncing[0] = false;
            }
        });
        Button toReal = new Button("Set Design to Real");
        toReal.setFocusTraversable(false);
        // enabled-state hover (the grayHint wrapper below owns the
        // DISABLED-state reason)
        Tooltip.install(toReal, new Tooltip(
            "Sets this section's design speed to the real entry speed "
            + "at once, resolving a speed conflict. Does not resolve an "
            + "orientation conflict"));
        // the gray reason is LIVE (D247): re-evaluated per compile
        java.util.function.Supplier<String> toRealWhy = () -> {
            if (entry) {
                return "The ride entry's design speed is the real one — "
                    + "nothing to set.";
            }
            boolean have = secAt.get() != null && app.trueV != null
                && app.sectionSpans != null
                && pieceBase(piece) + k < app.sectionSpans.size();
            return have ? null : "Nothing compiled for this section yet.";
        };
        toReal.setDisable(toRealWhy.get() != null);
        toReal.setOnAction(ev -> {
            if (app.convertBusy()) return;   // the D247 transaction guard
            if (toRealWhy.get() != null) return;
            Section live0 = secAt.get();
            if (live0 == null) {
                teardown.run();
                app.statsLabel.setText("The initial design speed window "
                    + "closed — its section no longer exists.");
                return;
            }
            // the SPEED-ONLY resolve at once (D242's semantics, one
            // undo step pushed only on success — the D247 rule)
            endBurst.run();
            FvdDesigner.DocState pre = snapshot();
            if (app.coaster.refreezeSpeedOnly(piece, k,
                    FvdDesigner.INTEGRATE_DT)) {
                pushUndo(pre);
                app.markDirty();
                app.rebuildTrack();   // the hook mirrors the box
                refreshOffset();
            } else {
                app.statsLabel.setText("Set Design to Real refused "
                    + "— nothing compiled for this section yet.");
            }
            redraw();
        });
        HBox row = new HBox(8, lbl, vSp, grayHint(toReal, () -> {
            String w = toRealWhy.get();
            return w == null ? "" : w;
        }));
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, row);
        Runnable onConfirm = teardown;   // the focused box committed first
        Runnable onCancel = () -> {
            // field-level revert to the open-time values, ONE more undo
            // step — skipped when the document's objects were replaced
            // meanwhile (the O-item cancelRestore rule)
            if (app.coaster != openC || app.docGen != openGen[0]) {
                app.statsLabel.setText(FvdDesigner.CANCEL_STALE_MSG);
            } else {
                Section s0 = secAt.get();
                if (s0 != null) {
                    boolean diff = entry
                        ? Math.abs(app.coaster.v0 - openV0[0]) > 0
                            || Math.abs(s0.vDesign() - openV[0]) > 0
                        : Math.abs(s0.vDesign() - openV[0]) > 0
                            || s0.vPinned() != openPin[0];
                    if (diff) {
                        endBurst.run();
                        pushUndoSnapshot();
                        if (entry) {
                            app.writeStartSpeed(openV0[0]);
                            s0.vDesign(openV[0]);   // exact (the spinner clamps)
                        } else {
                            s0.vDesign(openV[0]);
                            s0.vPinned(openPin[0]);
                        }
                        app.markDirty();
                        app.rebuildTrack();
                        refreshOffset();
                        redraw();
                    }
                }
            }
            teardown.run();
        };
        double[] cb = canvasScreenBox();
        double ax = cb != null ? cb[0] + MARGIN_LEFT : Double.NaN;
        Stage win = LiveWindow.show(app, "Initial design speed", box,
            onConfirm, onCancel, ax);
        winRef[0] = win;
        initSpeedWin = win;
        // LIVE (D247): the box follows the section's design speed
        // (a reconcile after an upstream edit, an undo — docRestoring
        // outranks the typing guard and ends the coalescing burst);
        // the button's gray reason follows the compile; a vanished
        // section tears the window down with a reason
        initSpeedHook = () -> {
            if (winRef[0] == null || winRef[0] != initSpeedWin) return;
            Section s0 = secAt.get();
            if (s0 == null) {
                hideInitSpeedWindow();
                app.statsLabel.setText("The initial design speed window "
                    + "closed — its section no longer exists.");
                return;
            }
            if (app.docRestoring()) endBurst.run();
            // a document generation this window has not seen (the
            // density preview's rerun / Apply — no restore hook runs
            // there) re-bases the Cancel record (D249 review fix)
            if (app.docGen != openGen[0]) rebase.run();
            syncing[0] = true;
            try {
                app.setUnlessTyping(vSp, Gestures.clampInitSpeed(s0.vDesign()));
            } finally {
                syncing[0] = false;
            }
            toReal.setDisable(toRealWhy.get() != null);
        };
        app.modelHooks.add(initSpeedHook);
        // the docRestoreHooks twin: an undo/redo restore re-bases the
        // record on the restored objects (the seam / Track Piece /
        // bridge windows' rule, D249 review fix)
        initSpeedRestoreHook = () -> {
            if (winRef[0] == null || winRef[0] != initSpeedWin) return;
            rebase.run();
        };
        docRestoreHooks.add(initSpeedRestoreHook);
    }


    // ---------- undo ----------

    private FvdDesigner.DocState snapshot() { return app.snapshotDoc(); }

    void pushUndoSnapshot() { pushUndo(snapshot()); }

    /** Push a snapshot taken BEFORE an op, once the op succeeded (D247
     *  review fix — a refused op must leave the redo branch alone). */
    void pushUndoState(FvdDesigner.DocState pre) { pushUndo(pre); }

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

    private void pushUndo(FvdDesigner.DocState snap) {
        undoStack.push(snap);
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        redoStack.clear();   // a fresh edit invalidates the redo branch
        app.markDirty();
    }

    void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(snapshot());        // current state becomes redoable
        applySnapshot(undoStack.pop());
    }

    /** Ctrl+Shift+Z, mirroring the menu-local sessions. Any new edit
     *  (pushUndo) clears the redo branch. */
    void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(snapshot());        // direct: must NOT clear redo
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        applySnapshot(redoStack.pop());
    }

    private void applySnapshot(FvdDesigner.DocState snap) {
        // the strict seam window and the bridge window STAY OPEN
        // across a restore since v19.19 (D248): their handlers and
        // hooks resolve the piece BY INDEX (the D247 pop-out captured
        // the object and had to be torn down + re-opened in place);
        // both self-validate on the restore's hooks and close with a
        // stats reason when their junction is gone
        app.applyDocState(snap);
        if (app.coaster.pieces.isEmpty()) {   // restored an EMPTY document
            selIdx = -1;
            selSet.clear();
            app.markDirty();
            app.rebuildTrackRestoring();   // hooks force-write (D247)
            redraw();
            app.syncWizardPanel();
            return;
        }
        if (selIdx >= active().size()) selIdx = -1;
        selSet.removeIf(i -> i >= active().size());
        app.markDirty();
        // spans first: s/c strip layout needs them; the live-window
        // hooks fire from this rebuild with the restore flag held, so
        // a restored value lands even in a focused box (D247)
        app.rebuildTrackRestoring();
        redraw();
        // open LIVE panels re-read the restored model (D227): the node
        // editor's boxes, the seam / bridge / Track Piece windows'
        // forced resyncs (D248)
        for (Runnable r : new ArrayList<>(docRestoreHooks)) r.run();
    }

    /** Refresh callbacks of live dialogs (D227) — run after every
     *  undo/redo restore; a dialog removes its own on close. */
    final List<Runnable> docRestoreHooks = new ArrayList<>();
    /** Run at the top of EVERY redraw (v19.14, D243): the node
     *  editor's live resync — cheap no-ops at the steady state. */
    final List<Runnable> redrawHooks = new ArrayList<>();
    private boolean inRedrawHooks = false;   // re-entry guard

    /** New document: history starts over. */
    void clearHistory() {
        undoStack.clear();
        redoStack.clear();
        pendingSnapshot = null;
    }

    /** Zoomed time window back to auto-fit (new document). */
    void resetView() {
        viewT0 = 0;
        viewT1 = -1;
    }

    // ---------- smooth toggle ----------

    void toggleSmooth() {
        // strip surface (v18.3): a const-speed control's END nodes
        // match the REAL speed outside the control; interior nodes
        // take the SHARED smooth/broken toggle on the strip surface
        if (!surfStrip && stripHasNodeSel()) {
            withSurf(this::toggleSmooth);
            return;
        }
        if (surfStrip && selIdx >= 0
                && (selIdx == 0 || selIdx == active().size() - 1)) {
            if (surfCtl.type == SpeedControl.SPEED) {
                spMatchRealEnd(surfCtl, surfRs, selIdx == 0);
            }
            return;
        }
        // section-EDGE nodes have no smooth/broken meaning (one handle) —
        // there B RETURNS the node to the boundary value + slope (v10.1)
        if (selIdx == 0 || selIdx == active().size() - 1) {
            matchBoundary(selIdx);
            return;
        }
        if (selIdx < 0) return;
        pushUndoSnapshot();
        touchActive();   // D248
        BezierBaker.Node n = active().get(selIdx);
        n.smooth = !n.smooth;
        if (n.smooth) {
            // keep the IN handle, re-angle the OUT to match its slope
            double inDt = BezierBaker.effInDt(active().get(selIdx - 1), n);
            double outDt = BezierBaker.effOutDt(n, active().get(selIdx + 1));
            n.outDv = (n.inDv / inDt) * outDt;
        }
        redraw();
        app.rebuildTrack();
    }

    /** B (or the node dialog's button) on a node at a section EDGE:
     *  RETURN it to the boundary (v10.1 user spec — value AND slope,
     *  not slope alone): the node's value snaps to the neighboring
     *  section's value at the shared boundary and its single handle
     *  takes the neighboring slope, so the curve continues seamlessly.
     *  Works across MODES too — a same-mode neighbor is matched from
     *  its authored lane (exact: a cubic's endpoint slope IS its
     *  handle-line slope, and a lane holding past its last node is
     *  FLAT there); an other-mode neighbor is matched from its
     *  RESULTING curve in the active units (boundaryState). A one-time
     *  move, not a lock (a straight-locked segment re-chords over it);
     *  no adjacent section = no-op. */
    /** Does the active section have a boundary neighbor on this side —
     *  in its own piece, or across a bridged piece boundary within the
     *  run (v13.4: the neighbor is then the bridge itself, matched from
     *  its compiled curves)? */
    private boolean hasNeighborBefore() {
        return activeSection > 0 || activePiece > runStart();
    }

    private boolean hasNeighborAfter() {
        return activeSection < sections().size() - 1
            || activePiece < runEnd();
    }

    /** Is the ACTIVE section's next in-piece neighbor an
     *  ABSOLUTE-bank section (v16.10)? Matching TOWARD it refuses:
     *  its entry state is enforced by its OWN bank lane — the seam
     *  smooths from ITS first node, and our-side targets would read
     *  its projection snap (by design, D118/D120). */
    private boolean nextAbsRoll() {
        List<Section> secs = sections();
        return activeSection + 1 < secs.size()
            && secs.get(activeSection + 1) instanceof LaneSection nl
            && nl.rollAbsolute && nl.rollGlobal;
    }

    /** ... and the ABSOLUTE-yaw/pitch twin (v19.8, D237): the D122
     *  rule for lanes 0/1 — matching toward an absolute-angle
     *  neighbor refuses on every lane. */
    private boolean nextAbsYp() {
        List<Section> secs = sections();
        return activeSection + 1 < secs.size()
            && secs.get(activeSection + 1) instanceof GeoSection nl
            && nl.ypAbsolute && nl.yawPitchGlobal;
    }

    private static final String YP_NEXT_HINT = "The next section's "
        + "absolute yaw/pitch sets the seam state at its own start — "
        + "press B/Shift+B on the OTHER side of the boundary (its "
        + "first node) to smooth this transition.";

    private static final String ABS_NEXT_HINT = "The next section's "
        + "absolute bank sets the seam state at its own start — press "
        + "B/Shift+B on the OTHER side of the boundary (its first "
        + "node) to smooth this transition.";

    void matchBoundary(int idx) {
        List<BezierBaker.Node> lane = active();
        boolean infl = activeInfl() != null;
        // on the INFLUENCE surface edge nodes are actionable even
        // without a neighbor (v18.37, D209: with one, B lines the
        // RESULTING graphs up; without one — ride start/end — it
        // returns the delta to zero, the old D102 rule)
        boolean start = idx == 0 && lane.size() >= 2
            && (infl || hasNeighborBefore());
        boolean end = idx == lane.size() - 1 && idx > 0
            && (infl || hasNeighborAfter());
        if (!start && !end) {
            // B never fails silently (v16.3, user rule)
            app.statsLabel.setText("B matches a section-EDGE node to its "
                + "boundary — select a lane's FIRST or LAST node, with a "
                + "neighboring section on that side.");
            return;
        }
        if (!infl && end && !start && (nextAbsRoll() || nextAbsYp())) {
            app.statsLabel.setText(nextAbsRoll()
                ? ABS_NEXT_HINT : YP_NEXT_HINT);
            return;
        }
        refreshOffset();
        if (!infl) {
            // (the v15.2 real-anchored refusal is GONE, v16.3: both
            // sides of every seam real-match now; the left side may
            // shift the seam and re-press to converge — documented)
            matchHint = null;
            if (boundaryState(start ? activeSection - 1 : activeSection + 1,
                    !start, activeLane) == null) {
                app.statsLabel.setText(matchHint != null ? matchHint
                    : "Nothing to match yet — no compiled curves at "
                    + "this boundary.");
                return;
            }
        }
        pushUndoSnapshot();
        if (!infl) refreezeSeamSpeed(start);
        applyBoundaryMatch(activeLane, start);
        redraw();
        app.rebuildTrack();
    }

    /** Boundary matching first makes the seam's DESIGN SPEEDS agree
     *  (v13.5, the seam-jolt root cause): the DOWNSTREAM section's
     *  vDesign re-freezes to the upstream side's design-end speed —
     *  the same rule '+' applies at creation, re-applied after edits
     *  drift the chain. Rates matched at a COMMON seam speed are also
     *  CURVATURE-matched, so the felt-G lanes, the rate graphs and
     *  the true ride all join; with drifted speeds, rate-only
     *  matching left the felt Gv/Gl display (and the rider) a value
     *  step by ω·Δv/g exactly on the boundary line. Bridge neighbors
     *  are skipped — a bridge's speed profile already blends onto its
     *  target's design speed (D74), so there is nothing to re-freeze
     *  and chasing its tail-end speed would loop through our own. */
    private void refreezeSeamSpeed(boolean startEdge) {
        if (app.lastSamples == null || app.lastSamples.isEmpty()) return;
        Section down;
        TrackCompiler.Span upSpan;
        if (startEdge) {   // upstream = previous section, downstream = US
            int k = activeSection - 1;
            Section up = k >= 0 ? sections().get(k)
                : null;   // cross-piece: the previous piece's bridge
            if (up == null || up instanceof BridgeSection) return;
            down = sections().get(activeSection);
            upSpan = spanOf(k);
        } else {           // downstream = the NEXT section
            if (activeSection + 1 >= sections().size()) return;
            down = sections().get(activeSection + 1);
            if (down instanceof BridgeSection) return;   // auto-captures
            upSpan = spanOf(activeSection);
        }
        if (upSpan == null) return;
        // a PINNED design speed is a DELIBERATE conflict (custom-speed
        // adds, stitched bridge converts) — B must line the REAL
        // graphs up around it, never close it (v16.2, user rule)
        if (down.vPinned()) return;
        int i1 = Math.min(upSpan.i1(), app.lastSamples.size() - 1);
        double v = app.lastSamples.get(i1).v();
        // REAL-anchORED downstream (v15.2, D101): a section lined up
        // with a drifted neighbor's TRUE curves (transition-to-real /
        // the '+' real fork) keeps chasing the REAL seam speed — the
        // design profile is the side it deliberately left. Which
        // anchor a section follows is read off which speed its
        // vDesign already sits nearer. AMPLIFICATION-AWARE since
        // v19.8 (D237 — the reconcile's twin gate, same fix): a seam
        // gap under DRIFT_EPS can still read over it at the
        // section's slowest point (energy offset ÷ 2·vMin), and this
        // entry-only test used to snap such a real anchor back onto
        // the design chain.
        if (trueArraysReady() && i1 < app.trueV.length) {
            double vTrue = app.trueV[i1];
            TrackCompiler.Span dSpan = spanOf(startEdge
                ? activeSection : activeSection + 1);
            double vMin = Math.max(0.1, vTrue);
            if (dSpan != null) {
                int e = Math.min(dSpan.i1(), app.trueV.length - 1);
                for (int i = dSpan.i0(); i <= e; i++) {
                    vMin = Math.min(vMin, Math.max(0.1, app.trueV[i]));
                }
            }
            if (Math.abs(vTrue - v) * (Math.max(0.1, vTrue) / vMin)
                        > DRIFT_EPS
                    && Math.abs(down.vDesign() - vTrue)
                       < Math.abs(down.vDesign() - v)) {
                v = vTrue;
            }
        }
        // admit what the app itself produces (D198 — the old [1,100]
        // clamp silently rewrote near-stall seam speeds to 1.0)
        v = FvdDesigner.clamp(v, 0.1, 1e4);
        if (Math.abs(v - down.vDesign()) > 1e-9) down.vDesign(v);
    }

    /** Shift+B with a section-EDGE node selected: the boundary return
     *  on EVERY lane at once — each lane's edge node takes its own
     *  neighbor value/slope, exactly like pressing B on each in turn
     *  (v10.4 user spec), as ONE undo step. */
    void matchBoundaryAll() {
        if (!surfStrip && stripHasNodeSel()) {
            toggleSmooth();   // one lane on the strip — B's move
            return;
        }
        List<BezierBaker.Node> lane = active();
        if (selIdx < 0) {
            app.statsLabel.setText("Shift+B needs a selected section-EDGE "
                + "node (a lane's first or last).");
            return;
        }
        boolean infl = activeInfl() != null;
        boolean start = selIdx == 0
            && (infl || hasNeighborBefore());
        boolean end = selIdx == active().size() - 1 && selIdx > 0
            && (infl || hasNeighborAfter());
        if (!start && !end) {
            app.statsLabel.setText("Shift+B matches section-EDGE nodes to "
                + "their boundary — select a lane's FIRST or LAST node, "
                + "with a neighboring section on that side.");
            return;
        }
        if (!infl && end && !start && (nextAbsRoll() || nextAbsYp())) {
            app.statsLabel.setText(nextAbsRoll()
                ? ABS_NEXT_HINT : YP_NEXT_HINT);
            return;
        }
        refreshOffset();
        if (!infl) {
            // nullity is uniform across lanes (it only means "no
            // compiled data yet" at a cross-mode boundary) — probe once
            matchHint = null;
            if (boundaryState(start ? activeSection - 1 : activeSection + 1,
                    !start, 0) == null) {
                app.statsLabel.setText(matchHint != null ? matchHint
                    : "Nothing to match yet — no compiled curves at "
                    + "this boundary.");
                return;
            }
        }
        pushUndoSnapshot();
        if (!infl) refreezeSeamSpeed(start);
        for (int L = 0; L < LANES; L++) applyBoundaryMatch(L, start);
        redraw();
        app.rebuildTrack();
    }

    /** The boundary return for ONE lane's edge node (no undo push):
     *  value + handle slope from the neighbor at the shared boundary.
     *  Returns false when that lane has nothing to match. */
    private boolean applyBoundaryMatch(int laneIdx, boolean startEdge) {
        List<BezierBaker.Node> ln = app.lanes.get(laneIdx);
        if (ln.size() < 2) return false;
        // INFLUENCE surface (v18.37, D209 — user re-spec reversing
        // the v15.5/D102 zero rule): the edge delta node takes the
        // value + slope that line the RESULTING graphs (base + delta)
        // up with the neighbor's real curves at the seam.
        if (activeInfl() != null) {
            return applyInfluenceMatch(ln, laneIdx, startEdge);
        }
        // DESIGN surface of an INFLUENCED section (v19.6, D235 —
        // user report: B lined the AUTHORED curve up with the
        // neighbor's true curve, leaving the RESULTING graphs
        // offset by the deltas and the base re-time): correction
        // form — nudge the authored edge by (target − what our
        // resulting curve currently does at the seam), so the TRUE
        // curves line up; a re-press converges (the standing
        // pattern).
        if (activeInfluenced() != null && laneIdx < 2) {
            double[] vs = startEdge
                ? (hasNeighborBefore()
                    ? boundaryState(activeSection - 1, false, laneIdx)
                    : null)
                : (hasNeighborAfter()
                    ? boundaryState(activeSection + 1, true, laneIdx)
                    : null);
            if (vs == null) return false;
            double[] own = ownEdgeState(startEdge, laneIdx);
            if (own == null) {
                matchHint = "No compiled data on this side yet — "
                    + "rebuild and re-press.";
                return false;
            }
            BezierBaker.Node n = startEdge ? ln.get(0)
                                           : ln.get(ln.size() - 1);
            touchLane(ln);   // D248
            n.val += vs[0] - own[0];
            if (startEdge) {
                n.outDv += (vs[1] - own[1])
                    * BezierBaker.effOutDt(n, ln.get(1));
            } else {
                n.inDv += (vs[1] - own[1])
                    * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
            }
            return true;
        }
        if (startEdge) {
            if (!hasNeighborBefore()) return false;
            double[] vs = boundaryState(activeSection - 1, false, laneIdx);
            if (vs == null) return false;
            BezierBaker.Node n = ln.get(0);
            touchLane(ln);   // D248
            n.val = vs[0];
            n.outDv = vs[1] * BezierBaker.effOutDt(n, ln.get(1));
            return true;
        }
        if (!hasNeighborAfter()) return false;
        double[] vs = boundaryState(activeSection + 1, true, laneIdx);
        if (vs == null) return false;
        BezierBaker.Node n = ln.get(ln.size() - 1);
        touchLane(ln);   // D248
        n.val = vs[0];
        n.inDv = vs[1] * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
        return true;
    }

    /** The influence-surface boundary return (v18.37, D209 — user
     *  re-spec reversing D102's zero rule): resulting == base + delta
     *  EXACTLY, so the delta that lines the resulting graphs up with
     *  the neighbor at the seam is neighborTarget − base — value AND
     *  slope, in the delta lanes' units on the lane's own axis. The
     *  neighbor target rides the standard sampled machinery through
     *  boundaryState (real-split κ conversion included — like every
     *  B, it aims at the REAL curves; the influence-mode routing
     *  skips the authored/analytic shortcuts, whose lane semantics
     *  are the section's, not the surface's). Fallbacks keep the old
     *  D102 rule — no neighbor on that side (ride start/end), no
     *  compiled data yet, or the inert roll lane: ZERO influence
     *  with a flat facing handle. No seam-speed re-freeze here:
     *  an influenced section's operating speed moves only by
     *  explicit action (the D95/D98 reconcile contract). */
    private boolean applyInfluenceMatch(List<BezierBaker.Node> ln,
                                        int laneIdx, boolean startEdge) {
        LaneSection infl = activeInfl();
        BezierBaker.Node n = ln.get(startEdge ? 0 : ln.size() - 1);
        double[] tgt = null;
        if (laneIdx < 2 && infl != null
                && (startEdge ? hasNeighborBefore() : hasNeighborAfter())) {
            matchHint = null;
            tgt = boundaryState(startEdge ? activeSection - 1
                : activeSection + 1, !startEdge, laneIdx);
        }
        if (tgt == null) {
            n.val = 0;
            if (startEdge) n.outDv = 0; else n.inDv = 0;
            return true;
        }
        // base value/slope at the seam: the rows live on the
        // section-local OPERATING clock in the delta lanes' units, so
        // the edge reads are exact (t = 0 / the last row's t); a
        // distance-native lane's handle slope is per METER — the
        // per-second row slope converts at the seam speed
        double bEnd = infl.baseCurves != null && infl.baseCurves.length > 0
            ? infl.baseCurves[infl.baseCurves.length - 1][0] : 0;
        double bt = startEdge ? 0 : bEnd;
        double bv = infl.baseValAt(laneIdx, bt);
        double bs = infl.baseSlopeAt(laneIdx, bt);
        if (activeDistNative()) {
            bs /= Math.max(0.5, seamDesignV(!startEdge));
        }
        n.val = tgt[0] - bv;
        double ds = tgt[1] - bs;
        if (startEdge) {
            n.outDv = ln.size() >= 2
                ? ds * BezierBaker.effOutDt(n, ln.get(1)) : 0;
        } else {
            n.inDv = ln.size() >= 2
                ? ds * BezierBaker.effInDt(ln.get(ln.size() - 2), n) : 0;
        }
        return true;
    }

    /** ABSOLUTE-roll boundary target (v16.7 D118; REWORKED v16.8
     *  D120): {bank°, bank rate} of the NEIGHBOR's side of the seam.
     *  Our own edge sample is useless — the absolute projection has
     *  already snapped it to OUR lane's current value (reading it
     *  made B parrot the lane back at itself, with the one-step snap
     *  transient as the "slope"), so the value reads the sample just
     *  ACROSS the seam and the slope reads the neighbor's bank rate
     *  clear of the ±2-sample bodyRates smear. The atan2 principal
     *  bank UNWRAPS to the representative nearest the edge node's
     *  current value (the user's cutback: a lane authored past ±180°
     *  got handed the mirrored equivalent — same physical
     *  orientation, wrong graph). Bank is speed-invariant, so this
     *  target also serves real-split seams. Vertical seams refuse. */
    private double[] absRollTarget(boolean atStart) {
        TrackCompiler.Span act = spanOf(activeSection);
        if (act == null || app.lastSamples == null
                || app.lastSamples.isEmpty()
                || app.resultRates == null) return null;
        int last = app.lastSamples.size() - 1;
        int ib = Math.max(0, Math.min(atStart ? act.i1() + 1
                                              : act.i0() - 1, last));
        FVDIntegrator.Sample sm = app.lastSamples.get(ib);
        double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
        if (Double.isNaN(b)) {
            matchHint = "No defined bank on vertical track — the "
                + "absolute roll lane can't read this seam.";
            return null;
        }
        int is = Math.max(0, Math.min(atStart ? ib + 3 : ib - 3,
            Math.min(last, app.resultRates.length - 1)));
        FVDIntegrator.Sample ss = app.lastSamples.get(is);
        double vSafe = Math.max(0.5, ss.v());
        double pitB = Math.toDegrees(FVDIntegrator.G0
            * (ss.Gv() - ss.U().y()) / vSafe);
        double yawB = Math.toDegrees(FVDIntegrator.G0
            * (ss.Gl() + ss.R().y()) / vSafe);   // REPORTED (D184+D185)
        double phi = Coaster.worldRates(ss, pitB, yawB,
            app.resultRates[is][3])[2];
        double slope = Double.isNaN(phi) ? 0
            : domSlope(phi, false, activeDistNative(),
                seamDesignV(atStart));
        double bd = Math.toDegrees(b);
        // the neighbor-side sample sits one step OFF the seam —
        // extrapolate the bank onto the boundary time (rate × dt;
        // 0.25° at a 25°/s seam otherwise)
        if (!Double.isNaN(phi)) {
            double tB = atStart ? act.t1() : act.t0();
            bd += phi * (tB - sm.t());
        }
        if (sections().get(activeSection) instanceof LaneSection als
                && als.lanes.size() > 2 && !als.lanes.get(2).isEmpty()) {
            bd = absRep(bd, als.lanes.get(2), atStart);
        }
        return new double[]{ bd, slope };
    }

    /** The representative an absolute-angle B target lands on (v19.9,
     *  D238 — user spec, superseding D120's unwrap-to-the-edge-node):
     *  values stay in the PRINCIPAL (−180, 180] range — all ±360
     *  representatives are the same orientation and the slope is
     *  translation-invariant, so the seam still matches smoothly even
     *  when the two sides show 360-apart numbers — UNLESS the lane's
     *  ADJACENT interior node sits more than 180° away from the
     *  wrapped value (a deliberately out-of-range authored lane, the
     *  D120 cutback): then continuity wins and the target unwraps to
     *  the representative nearest that neighbor instead. */
    private static double absRep(double ang, List<BezierBaker.Node> lane,
                                 boolean atStart) {
        // the node the edge's handle spans to (the other end node on
        // a 2-node lane)
        double near = lane.size() >= 2
            ? (atStart ? lane.get(lane.size() - 2) : lane.get(1)).val
            : ang;
        double w = Math.IEEEremainder(ang, 360);   // (−180, 180]
        if (Math.abs(w - near) > 180 + 1e-9) {
            return ang + 360 * Math.round((near - ang) / 360);
        }
        return w;
    }

    /** ABSOLUTE-yaw/pitch boundary target (v19.8, D237 — the D120
     *  recipe applied to lanes 0/1): {angle°, rate} of the
     *  NEIGHBOR's side of the seam — our own edge sample is already
     *  the projection's snap. lane 0 = elevation, lane 1 = heading
     *  (reported-left), the heading UNWRAPPED to the representative
     *  nearest the edge node's current value (heading crosses ±180
     *  on any half-turn — the D120 mirror, common case here).
     *  Vertical seams refuse with the stats reason. */
    private double[] absYpTarget(boolean atStart, int laneIdx) {
        TrackCompiler.Span act = spanOf(activeSection);
        if (act == null || app.lastSamples == null
                || app.lastSamples.isEmpty()
                || app.resultRates == null) return null;
        int last = app.lastSamples.size() - 1;
        int ib = Math.max(0, Math.min(atStart ? act.i1() + 1
                                              : act.i0() - 1, last));
        FVDIntegrator.Sample sm = app.lastSamples.get(ib);
        double ang;
        if (laneIdx == 0) {
            ang = Math.toDegrees(FVDIntegrator.elevationOfT(sm.T()));
        } else {
            double hd = FVDIntegrator.headingOfT(sm.T());
            if (Double.isNaN(hd)) {
                matchHint = "No defined heading on vertical track — "
                    + "the absolute yaw lane can't read this seam.";
                return null;
            }
            ang = Math.toDegrees(hd);
        }
        int is = Math.max(0, Math.min(atStart ? ib + 3 : ib - 3,
            Math.min(last, app.resultRates.length - 1)));
        FVDIntegrator.Sample ss = app.lastSamples.get(is);
        double vSafe = Math.max(0.5, ss.v());
        double pitB = Math.toDegrees(FVDIntegrator.G0
            * (ss.Gv() - ss.U().y()) / vSafe);
        double yawB = Math.toDegrees(FVDIntegrator.G0
            * (ss.Gl() + ss.R().y()) / vSafe);   // REPORTED (D184+D185)
        double wr = Coaster.worldRates(ss, pitB, yawB,
            app.resultRates[is][3])[laneIdx];
        double slope = Double.isNaN(wr) ? 0
            : domSlope(wr, false, activeDistNative(),
                seamDesignV(atStart));
        if (!Double.isNaN(wr)) {
            // extrapolate onto the exact boundary time (rate × dt)
            double tB = atStart ? act.t1() : act.t0();
            ang += wr * (tB - sm.t());
        }
        if (laneIdx == 1) {
            // heading lane values are PIECE-relative (v19.9, D238 —
            // continuous across the piece's chain like elevation and
            // bank): subtract OUR PIECE's placed heading (its first
            // sample), then land on the principal representative
            double ph = pieceEntryHeadingDeg(activePiece);
            if (!Double.isNaN(ph)) ang -= ph;
            if (sections().get(activeSection) instanceof LaneSection als
                    && !als.lanes.get(1).isEmpty()) {
                ang = absRep(ang, als.lanes.get(1), atStart);
            }
        }
        return new double[]{ ang, slope };
    }

    /** The placed world heading (deg) of piece p — the heading of
     *  its FIRST compiled sample (v19.9, D238: the absolute heading
     *  lane's reference; every heading the lane speaks is relative
     *  to this). A vertical entry scans forward within the piece for
     *  the first defined heading; NaN before a compile / none. */
    private double pieceEntryHeadingDeg(int p) {
        if (app.lastSamples == null || app.sectionSpans == null
                || p < 0 || p >= app.coaster.pieces.size()) {
            return Double.NaN;
        }
        int spanIdx = 0;
        for (int q = 0; q < p; q++) {
            spanIdx += app.coaster.pieces.get(q).sections.size();
        }
        int nSec = app.coaster.pieces.get(p).sections.size();
        if (nSec == 0 || spanIdx >= app.sectionSpans.size()) {
            return Double.NaN;
        }
        int i0 = app.sectionSpans.get(spanIdx).i0();
        int i1 = Math.min(app.sectionSpans.get(Math.min(
                spanIdx + nSec - 1, app.sectionSpans.size() - 1)).i1(),
            app.lastSamples.size() - 1);
        for (int i = Math.max(0, i0); i <= i1; i++) {
            double h = FVDIntegrator.headingOfT(
                app.lastSamples.get(i).T());
            if (!Double.isNaN(h)) return Math.toDegrees(h);
        }
        return Double.NaN;
    }

    /** A compiled sample's heading in the ACTIVE piece's frame (deg,
     *  principal): the absolute heading lanes' displayed quantity —
     *  heading MINUS the piece reference, wrapped into (−180, 180]
     *  (v19.9, D238; crossing shows the wrap wall, the abs-bank
     *  precedent). Vertical = 0. */
    private double pieceRelHeadingDeg(FVDIntegrator.Sample sm) {
        double hd = FVDIntegrator.headingOfT(sm.T());
        if (Double.isNaN(hd)) return 0;
        double ph = pieceEntryHeadingDeg(activePiece);
        double d = Math.toDegrees(hd) - (Double.isNaN(ph) ? 0 : ph);
        return Math.IEEEremainder(d, 360);
    }

    /** Roll {value, slope} across a seam whose sides author roll
     *  about DIFFERENT axes (v16.6, D114): the neighbor's authored
     *  roll converted into OUR semantics. At any sample the frames
     *  and felt are exact, so the heading rate ψ' comes from the κ
     *  identity without differencing, and worldRates' bank line
     *  inverts to p = φ' + ψ'·sinθ (body roll ← bank rate; minus for
     *  the other direction). Value at the BOUNDARY sample — exact,
     *  so re-presses never creep; slope from the same conversion 5
     *  samples inside the neighbor. NaN (vertical track) refuses
     *  with the stats-bar reason. */
    private double[] rollAcrossSeam(LaneSection nb, boolean atStart,
                                    boolean ourGlobal) {
        TrackCompiler.Span act = spanOf(activeSection);
        TrackCompiler.Span nsp = spanOf(atStart ? activeSection + 1
                                                : activeSection - 1);
        if (act == null || nsp == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return null;
        int ib = atStart ? act.i1() : act.i0();
        int ii = Math.max(0, Math.min(app.lastSamples.size() - 1,
            ib + (atStart ? 5 : -5)));
        if (ib >= app.lastSamples.size()) return null;
        double c0 = rollConverted(nb, nsp, ib, ourGlobal);
        double c1 = rollConverted(nb, nsp, ii, ourGlobal);
        if (Double.isNaN(c0) || Double.isNaN(c1)) {
            matchHint = "No defined heading/bank on vertical track — "
                + "roll can't convert across this seam.";
            return null;
        }
        double dtSpan = app.lastSamples.get(ii).t()
            - app.lastSamples.get(ib).t();
        double slope = Math.abs(dtSpan) < 1e-9 ? 0 : (c1 - c0) / dtSpan;
        slope = domSlope(slope, false, activeDistNative(),
            seamDesignV(atStart));
        return new double[]{ c0, slope };
    }

    /** The neighbor's roll rate at compiled sample i, converted into
     *  the requested semantics (°/s; NaN near vertical). */
    private double rollConverted(LaneSection nb, TrackCompiler.Span nsp,
                                 int i, boolean wantGlobal) {
        FVDIntegrator.Sample sm = app.lastSamples.get(i);
        // the authored roll at this sample, in the neighbor's axis
        double x = nb.domain() == Section.Domain.DISTANCE
            ? sm.s() - nsp.s0() : sm.t() - nsp.t0();
        double auth = authoredValAt(nb.lanes.get(2), x);
        // a per-meter neighbor authors °/m (v16.6): × that sample's v
        if (nb.ratePerMeter) auth *= Math.max(0.5, sm.v());
        if (effRollGlobal(nb) == wantGlobal) return auth;   // same semantics
        double vSafe = Math.max(0.5, sm.v());
        double pit = Math.toDegrees(FVDIntegrator.G0
            * (sm.Gv() - sm.U().y()) / vSafe);
        double yaw = Math.toDegrees(FVDIntegrator.G0
            * (sm.Gl() + sm.R().y()) / vSafe);   // REPORTED (D184+D185)
        double psi = Coaster.worldRates(sm, pit, yaw, 0)[1];
        if (Double.isNaN(psi)) return Double.NaN;
        // internal heading rate = the negated reported one (D185)
        double transport = -psi * sm.T().y();
        // neighbor authors bank rate φ' → body roll p = φ' + ψ'·sinθ;
        // neighbor authors body roll p → bank rate φ' = p − ψ'·sinθ
        // (EFFECTIVE semantics — a ypG geo neighbor's roll is a bank
        // rate too, v18.8)
        return effRollGlobal(nb) ? auth + transport : auth - transport;
    }

    /** Authored-lane value at local x (drawn-curve fidelity: the
     *  baked polyline, held flat past both ends). */
    private static double authoredValAt(List<BezierBaker.Node> lane,
                                        double x) {
        List<double[]> poly = BezierBaker.polyline(lane);
        if (x <= poly.get(0)[0]) return poly.get(0)[1];
        for (int i = 1; i < poly.size(); i++) {
            if (poly.get(i)[0] >= x) {
                double[] a = poly.get(i - 1), b = poly.get(i);
                double u = b[0] - a[0] < 1e-12 ? 0
                    : (x - a[0]) / (b[0] - a[0]);
                return a[1] + u * (b[1] - a[1]);
            }
        }
        return poly.get(poly.size() - 1)[1];
    }

    /** {value, slope} of neighbor section k's active-lane curve at the
     *  boundary it shares with the active section, in the ACTIVE
     *  mode's units. atStart = the neighbor is the NEXT section (look
     *  at its beginning) vs the previous one (look at its end). The
     *  roll lane is authored °/s in both modes, so it always matches
     *  from the authored lane (converted across roll semantics since
     *  v16.6); other cross-mode lanes read the
     *  compiled RESULTING curves, slope by finite difference over
     *  ~0.05 s on the neighbor's side. Null = no data yet. */
    private double[] boundaryState(int k, boolean atStart, int laneIdx) {
        double[] vs = boundaryStatePerSec(k, atStart, laneIdx);
        if (vs == null) return null;
        // OUR lane in PER-METER units (v16.6, D115): the workhorse
        // below always speaks °/s for rate lanes — convert its
        // {value, slope} at the seam speed, with the product-rule
        // term from v' = −g·T.y (value_s = v · value_m). On the
        // INFLUENCE surface (v18.37, D209) the DELTA lanes' own
        // rate-ness decides: a force section's Δpitch/Δyaw ARE rates
        // (°/m when the section authors per-meter, D117); a geo
        // section's ΔGv/ΔGl are felt targets, never per-meter.
        boolean pm = activeInfl() != null
            ? activeGeo()
                && sections().get(activeSection) instanceof LaneSection ai
                && ai.ratePerMeter
            : sections().get(activeSection) instanceof LaneSection act
                && laneRatePM(act, laneIdx);
        if (pm) {
            double v = Math.max(0.5, seamDesignV(atStart));
            double aT = -FVDIntegrator.G0 * seamTy(atStart);
            double vm = vs[0] / v;
            double sl = activeDistNative()
                ? (vs[1] - vm * (aT / v)) / v
                : (vs[1] - vm * aT) / v;
            return new double[]{ vm, sl };
        }
        return vs;
    }

    /** OUR OWN compiled design-space {value, slope} at the ACTIVE
     *  section's start/end, in boundaryState's exact units (the
     *  per-meter wrapper included) — the influenced-design B
     *  correction (v19.6, D235) subtracts this from the neighbor
     *  target so the RESULTING curves line up while the write lands
     *  on the authored lane. Same recipe as the neighbor tail:
     *  ~3 samples inside, sloped over 5, extrapolated to the seam. */
    private double[] ownEdgeState(boolean startEdge, int laneIdx) {
        TrackCompiler.Span act = spanOf(activeSection);
        if (act == null || app.resultRates == null
                || app.resultRates.length == 0) return null;
        double dt = FvdDesigner.INTEGRATE_DT;
        double tB = startEdge ? act.t0() : act.t1();
        int n = app.resultRates.length;
        int i0, i1;
        if (startEdge) {   // just inside our own start
            i0 = rateIdxAt(tB + 3 * dt);
            i1 = Math.min(i0 + 5, n - 1);
        } else {           // just inside our own end
            i1 = rateIdxAt(tB - 3 * dt);
            i0 = Math.max(i1 - 5, 0);
        }
        int at = startEdge ? i0 : i1;
        double span = app.resultRates[i1][0] - app.resultRates[i0][0];
        double a0 = sampledPerSec(i0, laneIdx, false);
        double a1 = sampledPerSec(i1, laneIdx, false);
        double slope = span < 1e-9 ? 0 : (a1 - a0) / span;
        double val = sampledPerSec(at, laneIdx, false)
            - slope * (app.resultRates[at][0] - tB);
        // boundaryState's flag convention: atStart == "the neighbor
        // is AFTER us", so our START edge is its atStart=false seam
        boolean bAtStart = !startEdge;
        slope = domSlope(slope, false, activeDistNative(),
            seamDesignV(bAtStart));
        boolean pm = sections().get(activeSection)
                instanceof LaneSection sl2 && laneRatePM(sl2, laneIdx);
        if (pm) {
            double v = Math.max(0.5, seamDesignV(bAtStart));
            double aT = -FVDIntegrator.G0 * seamTy(bAtStart);
            double vm = val / v;
            double sl = activeDistNative()
                ? (slope - vm * (aT / v)) / v
                : (slope - vm * aT) / v;
            return new double[]{ vm, sl };
        }
        return new double[]{ val, slope };
    }

    /** T.y at the shared boundary sample (0 before a compile) — the
     *  seam's slope of v: v' = −g·T.y. */
    private double seamTy(boolean atStart) {
        TrackCompiler.Span act = spanOf(activeSection);
        if (act == null || app.lastSamples == null
                || app.lastSamples.isEmpty()) return 0;
        int ib = Math.min(atStart ? act.i1() : act.i0(),
            app.lastSamples.size() - 1);
        return app.lastSamples.get(ib).T().y();
    }

    /** The workhorse: {value, slope} in PER-SECOND semantics for rate
     *  lanes (the wrapper converts for a per-meter active lane; the
     *  authored paths below convert a per-meter NEIGHBOR up front). */
    private double[] boundaryStatePerSec(int k, boolean atStart, int laneIdx) {
        List<Section> secs = sections();
        // k may fall OUTSIDE the active piece (v13.4: matching across a
        // bridged piece boundary) — the neighbor is then the bridge,
        // which has no lanes: only the sampled path below applies, and
        // it needs no Section object (spanOf's flattened index already
        // reaches the neighboring piece's spans)
        Section nbSec = k >= 0 && k < secs.size() ? secs.get(k) : null;
        // REAL-CURVE matching, GENERALIZED (v16.2 — user rule: B
        // ALWAYS lines the REAL graphs up; v15.2's real-anchored case
        // was the special case): whenever the seam still carries a
        // design/true split after the re-freeze attempt (pinned
        // deliberate conflicts, piece-firsts, custom-speed adds),
        // targets come from the TRUE arrays and convert into OUR
        // design values through the CURVATURE identity — true v is
        // continuous across any seam (energy), so real-graph
        // continuity IS curvature continuity, and the design rate
        // that reproduces a true rate ω_t at our design speed v_d is
        // ω_d = ω_t·v_d/v_t (felt via G = ω_d·v_d/g + axis.y; slopes
        // carry one more v_d/v_t for the clock).
        boolean real = false;
        double realScale = 1;   // sample-axis slope → per-true-time
        double rReal = 1;       // true rate → OUR design rate
        if (trueArraysReady()) {
            // STRICT SEAMS join the real-split test (v17.13, D154 —
            // user report: B/Shift+B across a conflicted strict seam
            // lined up the DESIGN graphs): the seam span and the
            // neighbor's design speed resolve CROSS-PIECE through
            // the follow link — spanOf's flattened index reaches the
            // adjacent piece, and the neighbor Section comes off the
            // follower/followed piece directly. Bridge boundaries
            // stay out (a bridge blends its own speed, D78).
            boolean followIn = !atStart && activeSection == 0
                && app.coaster.followerActive(activePiece);
            boolean followOut = atStart
                && activeSection == secs.size() - 1
                && app.coaster.followerActive(activePiece + 1);
            TrackCompiler.Span seamSp0 = atStart ? spanOf(activeSection)
                : activeSection > 0 || followIn
                    ? spanOf(activeSection - 1) : null;
            if (seamSp0 != null) {
                int ip = Math.min(seamSp0.i1(), app.lastSamples.size() - 1);
                if (ip < app.trueV.length) {
                    double vDesSeam = app.lastSamples.get(ip).v();
                    double vTru = Math.max(0.1, app.trueV[ip]);
                    // the seam is REAL-matched when EITHER side is
                    // split there (v16.3 — the v16.2 test read only
                    // the sampled side, so B from the UNCONFLICTED
                    // side of a seam matched the conflicted
                    // neighbor's DESIGN lanes, the user's report):
                    // our side = the section whose node moves;
                    // their side = the neighbor's design speed.
                    double vOurs, vTheirs;
                    if (atStart) {
                        vOurs = vDesSeam;   // our design END
                        Section nx = activeSection + 1 < secs.size()
                            ? secs.get(activeSection + 1) : null;
                        if (nx == null && followOut) {
                            List<Section> ns = app.coaster.pieces
                                .get(activePiece + 1).sections;
                            nx = ns.isEmpty() ? null : ns.get(0);
                        }
                        vTheirs = nx != null
                            ? Math.max(0.5, nx.vDesign()) : vDesSeam;
                    } else {
                        vOurs = Math.max(0.5,
                            secs.get(activeSection).vDesign());
                        vTheirs = vDesSeam;   // prev design end
                    }
                    real = Math.abs(vTru - vOurs) > DRIFT_EPS
                        || Math.abs(vTru - vTheirs) > DRIFT_EPS;
                    if (!real) {
                        // WHOLE-SPAN split test (v19.8, D237;
                        // sectionDriftedSpan since v19.18, D247 — the
                        // badge's ≠v tests the ENTRY only now, B keeps
                        // the span form): a sub-eps seam gap can still
                        // split visibly INSIDE either section (energy
                        // amplification at slow spots, a tilted
                        // piece's heights); B aims at the REAL curves
                        // there too
                        int tp, tk;
                        if (atStart) {
                            tp = followOut ? activePiece + 1 : activePiece;
                            tk = followOut ? 0 : activeSection + 1;
                        } else {
                            tp = followIn ? activePiece - 1 : activePiece;
                            tk = followIn
                                ? app.coaster.pieces.get(activePiece - 1)
                                    .sections.size() - 1
                                : activeSection - 1;
                        }
                        real = sectionDriftedSpan(activePiece, activeSection)
                            || (tp >= 0 && tp < app.coaster.pieces.size()
                                && tk >= 0
                                && tk < app.coaster.pieces.get(tp)
                                    .sections.size()
                                && sectionDriftedSpan(tp, tk));
                    }
                    realScale = vTru / Math.max(0.1, vTheirs);
                    rReal = vOurs / vTru;
                }
            }
        }
        // GLOBAL-axis lane on OUR side (v16.3 — B works there now):
        // targets come from the WORLD-rate matcher below, never the
        // authored/analytic body-rate paths. On the INFLUENCE surface
        // (v18.37, D209) the INFLUENCE integration's axes decide what
        // the deltas MEAN: heading'/elevation' deltas take the world
        // matcher, felt deltas (geo-section influence) never do.
        boolean inflM = activeInfl() != null;
        LaneSection actLs = secs.get(activeSection) instanceof LaneSection al
            ? al : null;
        boolean wg = inflM
            ? activeGeo() && actLs != null && actLs.inflYawPitchGlobal
            : actLs != null && laneWorld(actLs, laneIdx);
        // ABSOLUTE ROLL lane (v16.7 D118, reworked v16.8 D120): the
        // target is the seam's measured BANK (°) read off the
        // NEIGHBOR's side — with the neighbor's bank RATE as the
        // slope (best-effort by user acceptance: an absolute lane is
        // one derivative below the rate lanes B usually smooths).
        // Fires on real-split seams too — bank is a frame quantity,
        // invariant under the design/true speed split.
        if (!inflM && laneIdx == 2 && actLs != null
                && actLs.rollAbsolute && actLs.rollGlobal) {
            double[] ab = absRollTarget(atStart);
            if (ab != null || matchHint != null) return ab;
        }
        // ABSOLUTE YAW/PITCH lanes (v19.8, D237 — the same recipe
        // for lanes 0/1; positioned BEFORE the per-second wrapper's
        // paths like roll's, the D122 double-divide lesson)
        if (!inflM && laneIdx < 2 && actLs instanceof GeoSection
                && actLs.ypAbsolute && actLs.yawPitchGlobal) {
            double[] ab = absYpTarget(atStart, laneIdx);
            if (ab != null || matchHint != null) return ab;
        }
        // ROLL ACROSS DIFFERING ROLL ORIENTATIONS (v16.6, D114 — user
        // request "make B work for transitions between global and
        // local roll"): the neighbor's roll EXPRESSED IN OUR
        // semantics, EXACT at the boundary sample — its frames and
        // felt give Ω⊥ through the κ identity (no differencing), its
        // authored roll lane gives the rate in its own semantics, and
        // the transport relation p = φ' + ψ'·sinθ converts between
        // bank rate and body roll. Slope from the same conversion a
        // few samples inside — the value at the seam stays exact, so
        // re-presses don't creep. Vertical seams refuse (no defined
        // heading/bank), reason on the stats bar.
        if (!inflM && !real && laneIdx == 2 && actLs != null
                && nbSec instanceof LaneSection nbr
                && !nbr.reversed() && !nbr.influenced()
                && effRollGlobal(nbr) != effRollGlobal(actLs)
                // an ABSOLUTE neighbor's roll lane is a BANK, not a
                // rate — its compiled curves say what it does (v16.8)
                && !(nbr.rollAbsolute && nbr.rollGlobal)) {
            double[] rr = rollAcrossSeam(nbr, atStart, effRollGlobal(actLs));
            if (rr != null || matchHint != null) return rr;
        }
        // a REVERSED neighbor's authored lanes run against the chain
        // (values at the wrong end, roll/Gl signs flipped) — its
        // boundary state comes from the compiled curves instead (D67);
        // a straight/curve neighbor has no lanes at all — likewise.
        // The ROLL lane matches from the authored lane whenever both
        // sides SHARE roll semantics (v16.6 — global|global seams
        // used to take the sampled world-rate path for no reason:
        // both authored lanes are bank rates, direct is exact).
        // the authored/analytic shortcuts below speak the SECTION's
        // own lane semantics — on the influence surface the deltas
        // live in the OTHER mode, so targets come from the sampled
        // resulting curves instead (v18.37, D209: uniform across
        // every neighbor type, which is the point of the sampled
        // path; the ~3-samples-inside extrapolation converges in a
        // press or two, the D154 precedent)
        if (!inflM && !real && nbSec instanceof LaneSection nb
                && (sameMode(nb, secs.get(activeSection)) || laneIdx == 2)
                && !nb.reversed && !nb.influenced()
                && (laneIdx == 2
                    ? actLs != null
                        && effRollGlobal(nb) == effRollGlobal(actLs)
                        // rate vs ABSOLUTE mismatch: the two lanes
                        // hold different quantities (°/s-or-°/m vs
                        // bank °) — sampled path below (v16.8)
                        && (nb.rollAbsolute && nb.rollGlobal)
                           == (actLs.rollAbsolute && actLs.rollGlobal)
                    // lanes 0/1: exact whenever both sides mean the
                    // same thing — only the YAW/PITCH semantics
                    // matter here (v16.6; a neighbor's roll flag
                    // needlessly forced the sampled path before)
                    : actLs != null
                        && actLs.yawPitchGlobal == nb.yawPitchGlobal
                        // rate vs ABSOLUTE-angle mismatch: different
                        // quantities — sampled path (v19.8, D237,
                        // the D120 guard's lanes-0/1 twin)
                        && (nb.ypAbsolute && nb.yawPitchGlobal)
                           == (actLs.ypAbsolute
                               && actLs.yawPitchGlobal))) {
            // slopes read in the NEIGHBOR's native x units and convert
            // to OURS at the seam design speed (v16.0, D103) — values
            // are unit-identical across domains
            boolean nbD = nb.domain() == Section.Domain.DISTANCE;
            boolean actD = activeDistNative();
            double vSeam = seamDesignV(atStart);
            boolean nbPM = laneRatePM(nb, laneIdx);
            List<BezierBaker.Node> ln = nb.lanes.get(laneIdx);
            double val, rawSlope;
            if (atStart) {
                BezierBaker.Node n0 = ln.get(0);
                double outDt = ln.size() >= 2
                    ? BezierBaker.effOutDt(n0, ln.get(1)) : 0;
                val = n0.val;
                rawSlope = Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt;
            } else {
                BezierBaker.Node last = ln.get(ln.size() - 1);
                if (ln.size() < 2
                        || last.t < BezierBaker.rideEnd(nb.lanes) - 1e-9) {
                    // holding flat at the boundary
                    return new double[]{ nbPM
                        ? last.val * Math.max(0.5, vSeam) : last.val, 0 };
                }
                double inDt = BezierBaker.effInDt(ln.get(ln.size() - 2), last);
                val = last.val;
                rawSlope = Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt;
            }
            if (nbPM) {
                // a PER-METER neighbor speaks °/m (v16.6): value × v,
                // slope through per-second space with the product term
                double vv = Math.max(0.5, vSeam);
                double slopeSec = domSlope(rawSlope, nbD, false, vSeam);
                double aT = -FVDIntegrator.G0 * seamTy(atStart);
                double slopeConv = vv * slopeSec + val * aT;
                return new double[]{ val * vv,
                    domSlope(slopeConv, false, actD, vSeam) };
            }
            return new double[]{ val,
                domSlope(rawSlope, nbD, actD, vSeam) };
        }
        if (app.resultRates == null || app.lastSamples == null
                || app.resultRates.length < 12) return null;
        // a CROSS-MODE lane-section neighbor is matched ANALYTICALLY
        // (v13.4): its boundary rates are its authored lane values (geo)
        // or the exact force inversion ω = g(G − axis.y)/v at the seam
        // frame (force) — no bodyRates sampling at all. The sampled path
        // below extrapolates LINEARLY, and at a banked rolling seam the
        // rate curves are strongly curved, so every press re-injected a
        // small bias: the values (and the seam frame with them) crept
        // ~0.1 °/s per Shift+B round-trip without ever settling.
        // Analytic targets are exact AT the fixed point, so a re-press
        // changes nothing and the seam stays put.
        if (!inflM && !real && !wg && laneIdx < 2
                && nbSec instanceof LaneSection nbl
                && !nbl.reversed
                // an influenced neighbor's authored lanes don't
                // describe its actual curves — sampled path (v15.0);
                // global-axis lanes aren't body rates either (v16.1)
                && !nbl.influenced() && !nbl.globalAxes()
                // a PER-METER neighbor's lanes aren't °/s (v16.6) —
                // sampled path (the compiled curves already are)
                && !nbl.ratePerMeter
                // an orientation-CONFLICTED piece's authored force
                // targets live in the DESIGN frame; the analytic
                // inversion would mix them with the world seam frame
                // — sampled path there (v15.2; every conflict KIND
                // since v19.19 D248 — a design-tier / held-seam
                // conflict took the analytic path through the stale
                // true-tier test, and a lockstep-tilted UNconflicted
                // piece, whose design frame IS the world frame, was
                // needlessly forced to the sampled path)
                && !pieceConflicted(activePiece)) {
            return crossModeFromLanes(nbl, k, atStart, laneIdx);
        }
        // sample CLEAR of the seam (v10.2): bodyRates' central
        // differences SMEAR a boundary discontinuity across ±2 samples
        // (the short angled connector once visible at seams), and the
        // seam's sample index shifts with the integrator's cadence — so
        // read the neighbor ~3 samples inside and slope over the next 5,
        // then EXTRAPOLATE the value back to the seam itself (v13.3:
        // returning the inside sample verbatim left a 3·dt·slope bias —
        // every B press moved the node again and the seam kept a small
        // step wherever the neighbor's curve had slope). Reversed, s/c
        // and bridge neighbors still come through here.
        double dt = FvdDesigner.INTEGRATE_DT;
        TrackCompiler.Span act = spanOf(activeSection);
        if (act == null) return null;
        // the COMPILED boundary time (authored section ends truncate to
        // the sample grid; tOff accumulates authored durations)
        double tB = atStart ? act.t1() : act.t0();
        int n = app.resultRates.length;
        int i0, i1;
        if (atStart) {   // just inside the next section
            i0 = rateIdxAt(tB + 3 * dt);
            i1 = Math.min(i0 + 5, n - 1);
        } else {         // just inside the previous section
            i1 = rateIdxAt(tB - 3 * dt);
            i0 = Math.max(i1 - 5, 0);
        }
        int at = atStart ? i0 : i1;
        if (wg) {
            // WORLD-RATE matching (v16.3 — closes the global-axis /
            // global-global seam gap, user request): the neighbor's
            // world-angle rates, ANALYTIC per sample off its TRUE
            // body rates (Coaster.worldRates — no differencing bias),
            // fitted and extrapolated to the seam like the body-rate
            // path, then scaled into OUR design lane by the same
            // rReal/realScale factors. Vertical seams refuse (no
            // defined heading/bank), with the reason on the stats bar.
            double w0 = worldLaneAt(i0, laneIdx, actLs);
            double w1 = worldLaneAt(i1, laneIdx, actLs);
            double wAt = worldLaneAt(at, laneIdx, actLs);
            if (Double.isNaN(w0) || Double.isNaN(w1) || Double.isNaN(wAt)) {
                matchHint = "No defined heading/bank on vertical track — "
                    + "global-axis matching can't read this seam.";
                return null;
            }
            double spanW = app.resultRates[i1][0] - app.resultRates[i0][0];
            double slopeW = spanW < 1e-9 ? 0 : (w1 - w0) / spanW;
            double valW = wAt - slopeW * (app.resultRates[at][0] - tB);
            valW *= rReal;
            slopeW *= realScale * rReal * rReal;
            slopeW = domSlope(slopeW, false, activeDistNative(),
                seamDesignV(atStart));
            return new double[]{ valW, slopeW };
        }
        if (!activeGeo() && laneIdx < 2) {
            return forceTargetFromRates(laneIdx, atStart, tB, i0, i1, at,
                real, realScale, rReal);
        }
        double span = app.resultRates[i1][0] - app.resultRates[i0][0];
        // per-second reads, NOT derivedAt/trueAt (v16.10): those
        // pre-scale by 1/v for a °/m active lane and the wrapper
        // above divides again — targets collapsed to ~0
        double a0 = sampledPerSec(i0, laneIdx, real);
        double a1 = sampledPerSec(i1, laneIdx, real);
        double slope = span < 1e-9 ? 0 : (a1 - a0) / span;
        double val = sampledPerSec(at, laneIdx, real)
            - slope * (app.resultRates[at][0] - tB);
        if (real) {
            // true rate → OUR design rate (the κ identity, v16.2);
            // slope: sample axis → true time, then the same rate
            // factor plus the true→design clock factor
            val *= rReal;
            slope *= realScale * rReal * rReal;
        }
        // sampled slopes are per SECOND — onto our native x (v16.0)
        slope = domSlope(slope, false, activeDistNative(),
            seamDesignV(atStart));
        return new double[]{ val, slope };
    }

    /** {value, slope} of one lane at its start or end, with the
     *  hold-flat rule (a lane ending before the section end holds its
     *  final value — slope 0 at the boundary). */
    private static double[] laneEdge(List<BezierBaker.Node> ln,
                                     double laneSetEnd, boolean atStart) {
        if (atStart) {
            BezierBaker.Node n0 = ln.get(0);
            double outDt = ln.size() >= 2
                ? BezierBaker.effOutDt(n0, ln.get(1)) : 0;
            return new double[]{ n0.val,
                Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt };
        }
        BezierBaker.Node last = ln.get(ln.size() - 1);
        if (ln.size() < 2 || last.t < laneSetEnd - 1e-9) {
            return new double[]{ last.val, 0 };
        }
        double inDt = BezierBaker.effInDt(ln.get(ln.size() - 2), last);
        return new double[]{ last.val,
            Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt };
    }

    /** ANALYTIC cross-mode boundary state (v13.4): the neighbor's edge
     *  body rates + slopes, exactly, from its authored lanes — a geo
     *  neighbor's lanes ARE the rates; a force neighbor's rates invert
     *  its authored targets at the seam frame and ITS OWN seam design
     *  speed (ω = g(G − axis.y)/v, ω' = g(G' − (Ω×axis).y)/v + ω·g·T.y/v)
     *  — then converted to the ACTIVE mode's units (identity for a geo
     *  view; G = ω·v_act/g + axis.y for a force view, v13.3's formula).
     *  Exact both directions, so alternating B/Shift+B is idempotent
     *  once matched. */
    private double[] crossModeFromLanes(LaneSection nb, int k,
                                        boolean atStart, int laneIdx) {
        TrackCompiler.Span act = spanOf(activeSection);
        TrackCompiler.Span seamSp = atStart ? act
            : activeSection > 0 ? spanOf(activeSection - 1) : null;
        if (act == null || seamSp == null) return null;
        TrackPose seam = seamSp.exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        boolean nbStarts = atStart;   // neighbor is NEXT ⇒ its start is the seam
        double nbEnd = BezierBaker.rideEnd(nb.lanes);
        double[][] edge = new double[3][];
        for (int L = 0; L < 3; L++) {
            edge[L] = laneEdge(nb.lanes.get(L), nbEnd, nbStarts);
        }
        // a DISTANCE-domain neighbor's edge slopes are per METER — the
        // analytic rate math below runs per second, so convert at the
        // neighbor's own seam design speed first (v16.0, D103); its
        // edge VALUES are unit-identical either way
        if (nb.domain() == Section.Domain.DISTANCE) {
            TrackCompiler.Span nbSp = spanOf(k);
            double vNbSeam = nbStarts || nbSp == null
                ? nb.vDesign
                : app.lastSamples.get(Math.min(nbSp.i1(),
                    app.lastSamples.size() - 1)).v();
            for (int L = 0; L < 3; L++) {
                edge[L][1] = domSlope(edge[L][1], true, false, vNbSeam);
            }
        }
        double rate, rateSlope;   // the neighbor's laneIdx rate, °/s + °/s²
        double pitchDeg, yawDeg;
        if (nb instanceof GeoSection) {
            pitchDeg = edge[0][0];
            yawDeg = edge[1][0];
            rate = edge[laneIdx][0];
            rateSlope = edge[laneIdx][1];
        } else {
            // force neighbor: design speed at ITS side of the seam —
            // exactly vDesign at its start, the compiled end sample at
            // its end
            TrackCompiler.Span nbSp = spanOf(k);
            if (nbSp == null) return null;
            double vNb = nbStarts ? nb.vDesign
                : app.lastSamples.get(Math.min(nbSp.i1(),
                    app.lastSamples.size() - 1)).v();
            vNb = Math.max(0.5, vNb);
            pitchDeg = Math.toDegrees(
                FVDIntegrator.G0 * (edge[0][0] - U.y()) / vNb);
            // REPORTED yaw (+ = left; D184's and D185's negations
            // cancel in the Gl-sourced reconstruction)
            yawDeg = Math.toDegrees(
                FVDIntegrator.G0 * (edge[1][0] + R.y()) / vNb);
            double rateRad = Math.toRadians(laneIdx == 0 ? pitchDeg : yawDeg);
            Vec3 omega = omegaAt(T, U, R, pitchDeg, yawDeg, edge[2][0]);
            Vec3 axis = laneIdx == 0 ? U : R;
            // the reported-yaw derivative carries a FLIPPED gravity-
            // transport term: y' = g(Gl' + (Ω×R)·ŷ)/v + y·g·T.y/v
            double slopeRad = laneIdx == 1
                ? FVDIntegrator.G0 / vNb
                    * (edge[1][1] + omega.cross(axis).y())
                    + rateRad * FVDIntegrator.G0 * T.y() / vNb
                : FVDIntegrator.G0 / vNb
                    * (edge[0][1] - omega.cross(axis).y())
                    + rateRad * FVDIntegrator.G0 * T.y() / vNb;
            rate = Math.toDegrees(rateRad);
            rateSlope = Math.toDegrees(slopeRad);
        }
        if (activeGeo()) {
            // per-second slope → OUR native x units (v16.0)
            return new double[]{ rate, domSlope(rateSlope, false,
                activeDistNative(), seamDesignV(atStart)) };
        }
        // force view: v13.3's rate→G conversion at the ACTIVE side's
        // seam design speed
        double vAct = atStart
            ? app.lastSamples.get(Math.min(act.i1(),
                app.lastSamples.size() - 1)).v()
            : sections().get(activeSection).vDesign();
        double w = Math.toRadians(rate);
        Vec3 axis = laneIdx == 0 ? U : R;
        Vec3 omega = omegaAt(T, U, R, pitchDeg, yawDeg, edge[2][0]);
        double val, slope;
        if (laneIdx == 1) {
            // D184+D185: Gl = y_rep·v/g − R.y with y_rep the REPORTED
            // (left-positive) yaw — slope differentiates exactly that
            val = w * vAct / FVDIntegrator.G0 - R.y();
            slope = Math.toRadians(rateSlope) * vAct / FVDIntegrator.G0
                - w * T.y() - omega.cross(R).y();
        } else {
            val = w * vAct / FVDIntegrator.G0 + axis.y();
            slope = Math.toRadians(rateSlope) * vAct / FVDIntegrator.G0
                - w * T.y() + omega.cross(axis).y();
        }
        return new double[]{ val,
            domSlope(slope, false, activeDistNative(), vAct) };
    }

    /** Ω from a body-rate triple (°/s) in the given frame — the shared
     *  integrator convention Ω = yaw·U − pitch·R + roll·T. */
    private static Vec3 omegaAt(Vec3 T, Vec3 U, Vec3 R,
                                double pitchDeg, double yawDeg, double rollDeg) {
        // yawDeg is REPORTED (+ = left, D185): internal negates
        return U.scale(-Math.toRadians(yawDeg))
            .add(R.scale(-Math.toRadians(pitchDeg)))
            .add(T.scale(Math.toRadians(rollDeg)));
    }

    /** FORCE-view cross-mode boundary target for Gv/Gl (v13.3): the
     *  neighbor's resulting RATE at the seam, converted to a G target
     *  at the ACTIVE side's own seam design speed —
     *      G = ω·v_seam/g + axis.y
     *  Both edges of the seam then enforce the SAME invariant (body-
     *  rate continuity in ride time, what the unified graph shows), so
     *  alternating Shift+B has a fixed point. The old behavior copied
     *  the neighbor's FELT G — recorded at the NEIGHBOR'S design
     *  speed — which multiplied the seam rate by the design-speed
     *  ratio on every alternate press: once the two sections' design
     *  speeds drifted apart (any height edit upstream of a frozen
     *  vDesign), the B/Shift+B ping-pong never converged. Slope
     *  carries the same conversion (dG/dt = ω'·v/g + ω·v'/g + axis'ʸ,
     *  with v' = −g·T.y from the design energy profile and
     *  axis' = Ω×axis). */
    private double[] forceTargetFromRates(int laneIdx, boolean atStart,
                                          double tB, int i0, int i1, int at,
                                          boolean real, double realScale,
                                          double rReal) {
        int comp = laneIdx + 1;   // resultRates rows: {t, pitch, yaw, roll}
        // real (v15.2, D101; generalized v16.2): the TRUE rate rows,
        // converted into OUR design rates via the κ identity (rReal =
        // v_design_ours / v_true at the seam — 1 for the anchored
        // case, so D101's behavior is unchanged there)
        double[][] rr = real ? app.trueRates : app.resultRates;
        double span = rr[i1][0] - rr[i0][0];
        double slopeR = span < 1e-9 ? 0
            : (rr[i1][comp] - rr[i0][comp]) / span;
        double rateDeg = rr[at][comp] - slopeR * (rr[at][0] - tB);
        if (real) {
            rateDeg *= rReal;
            slopeR *= realScale * rReal * rReal;
        }
        // the ACTIVE side's design state at the seam: the entry is
        // exactly vDesign on the chained entry pose; the end is the
        // compiled end sample (both move as the section reshapes — the
        // documented mild iteration, now convergent)
        TrackCompiler.Span sp = spanOf(atStart ? activeSection
                                               : activeSection - 1);
        if (sp == null) return null;
        double vSeam;
        if (atStart) {
            int ie = Math.min(sp.i1(), app.lastSamples.size() - 1);
            vSeam = app.lastSamples.get(ie).v();
        } else {
            vSeam = sections().get(activeSection).vDesign();
        }
        TrackPose seam = sp.exit();   // exact seam frame either way
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double w = Math.toRadians(rateDeg);
        // internal Ω from the REPORTED rows: the yaw slot negates
        // (D185)
        Vec3 omega = U.scale(-Math.toRadians(rr[at][2]))
            .add(R.scale(-Math.toRadians(rr[at][1])))
            .add(T.scale(Math.toRadians(rr[at][3])));
        Vec3 axis = laneIdx == 0 ? U : R;
        double val, slope;
        if (laneIdx == 1) {
            // D184+D185: Gl = y_rep·v/g − R.y (y_rep = reported yaw)
            val = w * vSeam / FVDIntegrator.G0 - R.y();
            slope = Math.toRadians(slopeR) * vSeam / FVDIntegrator.G0
                - w * T.y() - omega.cross(R).y();
        } else {
            val = w * vSeam / FVDIntegrator.G0 + U.y();
            slope = Math.toRadians(slopeR) * vSeam / FVDIntegrator.G0
                - w * T.y() + omega.cross(axis).y();
        }
        // per-second slope → OUR native x units (v16.0, D103)
        return new double[]{ val,
            domSlope(slope, false, activeDistNative(), vSeam) };
    }

    /** Index of the compiled sample nearest ride time t (rows are
     *  time-sorted; binary search — authored section durations and
     *  sample times drift apart by the integrator cadence, so never
     *  compute indices as t/dt). */
    private int rateIdxAt(double t) {
        double[][] r = app.resultRates;
        int lo = 0, hi = r.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (r[mid][0] <= t) lo = mid; else hi = mid;
        }
        return t - r[lo][0] <= r[hi][0] - t ? lo : hi;
    }

    /** Same lane semantics (both force or both geo)? A straight/curve
     *  section is never same-mode with anything (no lanes). */
    private static boolean sameMode(LaneSection a, Section b) {
        return b instanceof LaneSection
            && (a instanceof GeoSection) == (b instanceof GeoSection);
    }

    /** Does lane laneIdx of a section author WORLD rates (v16.3)?
     *  Lane 2 follows the EFFECTIVE roll semantics; lanes 0/1 follow
     *  yawPitchGlobal on geo sections (a force section's Gv/Gl are
     *  always felt). */
    private static boolean laneWorld(LaneSection ls, int laneIdx) {
        return laneIdx == 2 ? effRollGlobal(ls)
            : ls instanceof GeoSection && ls.yawPitchGlobal;
    }

    /** EFFECTIVE roll semantics of a section's roll lane (v18.8, the
     *  user's force→geo B report): on a yaw-pitch-GLOBAL geo section
     *  the world-Y heading rotation itself twists the body
     *  (Ω·T = ψ'·T.y + p), so its "local" roll lane IS a bank rate —
     *  φ' = p − ψ'·sinθ + ψ'·T.y = p exactly. Roll matching across a
     *  seam must compare THIS, not the raw flag: copying a local
     *  neighbor's body roll verbatim left the resulting roll graph
     *  stepping by the transport term (35 °/s on his file). */
    private static boolean effRollGlobal(LaneSection ls) {
        return ls.rollGlobal
            || (ls instanceof GeoSection && ls.yawPitchGlobal);
    }

    /** Lane value at compiled sample i in the ACTIVE section's axis
     *  semantics, off the TRUE arrays: analytic world-angle rates
     *  where the lane is global (NaN on vertical track), trueAt
     *  otherwise. */
    private double worldLaneAt(int i, int laneIdx, LaneSection act) {
        // the INFLUENCE surface's world-ness rides the influence
        // axis flags, not the section's own (v18.37, D209)
        boolean world = activeInfl() != null
            ? activeGeo() && act != null && act.inflYawPitchGlobal
            : act != null && laneWorld(act, laneIdx);
        if (!world) return trueAt(i, laneIdx);
        double[] wr = Coaster.worldRates(app.lastSamples.get(i),
            app.trueRates[i][1], app.trueRates[i][2], app.trueRates[i][3]);
        return wr[laneIdx];
    }

    /** Why the last boundaryState probe refused, for the stats bar
     *  (v16.3 — B never fails silently); null = no specific reason. */
    private String matchHint;

    /** Convert a boundary SLOPE between authoring domains at the seam
     *  design speed (v16.0, D103 — the chain rule): per-meter × v =
     *  per-second, per-second ÷ v = per-meter; identity when both
     *  sides share a domain. Values need no conversion — the lanes
     *  hold the same quantities either way. */
    private static double domSlope(double slope, boolean fromDist,
                                   boolean toDist, double v) {
        if (fromDist == toDist) return slope;
        double vs = Math.max(0.5, v);
        return fromDist ? slope * vs : slope / vs;
    }

    /** The ACTIVE side's design speed at the boundary it is matching
     *  across (post re-freeze both sides agree there): our compiled
     *  design END when the neighbor is NEXT, our vDesign otherwise. */
    private double seamDesignV(boolean atStart) {
        if (atStart) {
            TrackCompiler.Span act = spanOf(activeSection);
            if (act != null && app.lastSamples != null
                    && !app.lastSamples.isEmpty()) {
                return Math.max(0.5, app.lastSamples.get(
                    Math.min(act.i1(), app.lastSamples.size() - 1)).v());
            }
        }
        return Math.max(0.5, sections().get(activeSection).vDesign());
    }

    /** The horizontal WALL for a node drag (v10.2): the section end as
     *  established by the OTHER lanes' last nodes ("where another graph
     *  already is"). Only the lane's LAST node can meet it (interior
     *  nodes are clamped by their neighbors), and a node that BEGINS
     *  the gesture at/past the wall is exempt — that is how a section
     *  is deliberately extended: drag to the end, release, grab it
     *  again to pull further. MAX_VALUE = no wall. */
    private double endLimitFor(int nodeIdx) {
        // strip surface (v18.43, D215 — user spec): the complex
        // graph's LAST node drags the CONTROL LENGTH itself — its
        // wall is the section end / next control, and moveNode
        // writes surfCtl.len from it WITHOUT rescaling the earlier
        // nodes (the end diamond keeps the classic stretch).
        // Interior nodes stay boxed at the control end.
        if (surfStrip) {
            return nodeIdx == active().size() - 1
                ? surfCtlMaxLen() : surfCtl.len;
        }
        List<BezierBaker.Node> lane = active();
        if (nodeIdx != lane.size() - 1) return Double.MAX_VALUE;
        // the influence surface's wall is ABSOLUTE (no started-at-end
        // exemption): deltas modify the section, they never extend it
        if (activeInfl() != null) return sectionEnd();
        double end = 0;
        for (List<BezierBaker.Node> ln : app.lanes) {
            if (ln == lane) continue;
            end = Math.max(end, ln.get(ln.size() - 1).t);
        }
        if (end <= 1e-9) return Double.MAX_VALUE;
        return lane.get(nodeIdx).t >= end - 1e-6 ? Double.MAX_VALUE : end;
    }

    // ---------- coordinate mapping ----------

    private double maxT() { return Math.max(0.5, runDur()); }
    private double domain() { return maxT() * 1.08; }
    private double plotHeight() {
        return canvas.getHeight() - MARGIN_TOP - MARGIN_BOTTOM
            - speedStripH();
    }
    private double plotWidth() { return canvas.getWidth() - MARGIN_LEFT - marginRight(); }

    private double viewStart() {
        return !Double.isNaN(dragWinT0) ? dragWinT0
             : (viewT1 > 0 ? viewT0 : runT0());
    }
    private double viewEnd() {
        return !Double.isNaN(dragWinT0) ? dragWinT1
             : (viewT1 > 0 ? viewT1 : runT0() + domain());
    }

    /** A MANUAL zoom window survives structural edits, so the piece's
     *  stretch can move out from under it (add/delete section, attach,
     *  split, undo…): every click then maps through a window the strips
     *  have left — inserts land in the wrong place and the plot shows
     *  nothing but ghost strips ("the graph disappeared"). Run before
     *  any mapping: slide the window back into the piece's stretch,
     *  keeping its width; a window at least as wide as the stretch
     *  resumes auto-fit. */
    private void sanitizeView() {
        if (viewT1 <= 0) return;   // auto-fit follows the run already
        double width = viewT1 - viewT0;
        if (width >= domain() - 1e-9) {
            viewT0 = 0;
            viewT1 = -1;
            return;
        }
        double n0 = FvdDesigner.clamp(viewT0,
            runT0(), runT0() + domain() - width);
        viewT0 = n0;
        viewT1 = n0 + width;
    }

    private double tToX(double t) {
        return MARGIN_LEFT + (t - viewStart()) / (viewEnd() - viewStart()) * plotWidth();
    }
    private double xToT(double x) {
        return viewStart()
            + FvdDesigner.clamp((x - MARGIN_LEFT) / plotWidth(), 0, 1) * (viewEnd() - viewStart());
    }
    private double valToY(int lane, double val) {
        if (surfStrip) return spValToY(surfCtl, val);
        // NO clamping: out-of-view geometry keeps its true position
        // (drawing is clipped to the plot; clamping here bent the
        // apparent angle of off-screen handles)
        double t = (val - curMin[lane]) / (curMax[lane] - curMin[lane]);
        return MARGIN_TOP + plotHeight() * (1 - t);
    }
    private double yToVal(int lane, double y) {
        if (surfStrip) return spYToVal(surfCtl, y);
        double t = 1 - (y - MARGIN_TOP) / plotHeight();
        return curMin[lane] + FvdDesigner.clamp(t, 0, 1) * (curMax[lane] - curMin[lane]);
    }

    // ---------- hit testing (active lane only) ----------

    private int hitNode(double x, double y) {
        List<BezierBaker.Node> lane = active();
        for (int i = 0; i < lane.size(); i++) {
            double px = ltToX(lane.get(i).t);
            double py = valToY(activeLane, dispVal(activeLane,
                lane.get(i).t, lane.get(i).val));
            if (Math.hypot(x - px, y - py) <= nodeHit()) return i;
        }
        return -1;
    }

    private int hitKnob(double x, double y) {
        double[] out = knobPos(selIdx, true);
        if (out != null && Math.hypot(x - out[0], y - out[1]) <= knobHit()) return 1;
        double[] in = knobPos(selIdx, false);
        if (in != null && Math.hypot(x - in[0], y - in[1]) <= knobHit()) return -1;
        return 0;
    }

    /** The hollow marker where an off-screen handle exits the plot. */
    private boolean hitEdgeMarker(double x, double y) {
        return (outEdgeMarker != null
                    && Math.hypot(x - outEdgeMarker[0], y - outEdgeMarker[1]) <= knobHit())
            || (inEdgeMarker != null
                    && Math.hypot(x - inEdgeMarker[0], y - inEdgeMarker[1]) <= knobHit());
    }

    /** Hit test the INACTIVE lanes: nodes first, then curve bodies.
     *  Returns {lane, nodeIdx} (nodeIdx -1 for a curve hit) or null. */
    private int[] hitOtherLane(double x, double y) {
        int bestLane = -1, bestNode = -1;
        double best = NODE_HIT + 3;   // slightly forgiving on dimmed app.lanes
        boolean noRoll = activeInfl() != null;   // lane 2 inert (v15.4)
        for (int L = 0; L < LANES; L++) {
            if (L == activeLane || (noRoll && L == 2)) continue;
            List<BezierBaker.Node> lane = app.lanes.get(L);
            for (int i = 0; i < lane.size(); i++) {
                double d = Math.hypot(x - ltToX(lane.get(i).t),
                                      y - valToY(L, dispVal(L,
                                          lane.get(i).t, lane.get(i).val)));
                if (d < best) { best = d; bestLane = L; bestNode = i; }
            }
        }
        if (bestLane >= 0) return new int[]{ bestLane, bestNode };

        // curve body: vertical distance to the lane's value at this time
        // (evalAt holds the end values outside the node range)
        double t = xToLt(x);
        double bestCurve = 7;
        for (int L = 0; L < LANES; L++) {
            if (L == activeLane || (noRoll && L == 2)) continue;
            double d = Math.abs(y - valToY(L,
                dispVal(L, t, BezierBaker.evalAt(app.lanes.get(L), t))));
            if (d < bestCurve) { bestCurve = d; bestLane = L; }
        }
        return bestLane >= 0 ? new int[]{ bestLane, -1 } : null;
    }

    /** Screen position of a node's handle knob, or null when the knob
     *  doesn't exist — or faces a LOCKED segment (v10.4/5): a locked
     *  segment's shape is its line no matter the handles, so its
     *  facing knobs are hidden and unclickable. */
    private double[] knobPos(int idx, boolean out) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        // Resulting view offsets knobs by the base at the NODE's time
        // (v15.4, D102 — user spec): the whole node+handle assembly
        // translates rigidly, so handle ANGLES read identically in
        // both influence views (offsetting at the knob's own time bent
        // them along the base curve).
        if (out) {
            if (idx >= lane.size() - 1 || n.lockedOut()) return null;
            double dt = BezierBaker.effOutDt(n, lane.get(idx + 1));
            return new double[]{ ltToX(n.t + dt), valToY(activeLane,
                dispVal(activeLane, n.t, n.val + n.outDv)) };
        } else {
            if (idx <= 0 || lane.get(idx - 1).lockedOut()) return null;
            double dt = BezierBaker.effInDt(lane.get(idx - 1), n);
            return new double[]{ ltToX(n.t + dt), valToY(activeLane,
                dispVal(activeLane, n.t, n.val + n.inDv)) };
        }
    }

    // ---------- editing ----------

    private void dragNodeTo(double x, double y, boolean snap, boolean shift) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(dragNodeIdx);

        // beyond a plot edge the node rides the edge while dragGrowTimer
        // smoothly extends the axis at a rate set by the overshoot
        double newT = xToLt(x);
        double newVal = yToVal(activeLane, y);

        if (snap) {   // Ctrl enables snapping; free otherwise
            // x: quantize the TRANSITION from the previous node
            // (0.3 s / 0.8 s — or meters on a distance axis) by
            // default, or the ABSOLUTE grid the axis ticks show
            // (Options toggle, v10.2). The grid lives in the DISPLAY
            // domain (v16.0): a cross-domain view snaps on the axis
            // you SEE, mapped back to native through the warp
            // (identity when unwarped — the historic math exactly).
            if (dragNodeIdx > 0) {
                double step = snapXStep();
                double aNew = warpFwd(newT);
                double aPrev = warpFwd(lane.get(dragNodeIdx - 1).t);
                double g = app.settings.snapTAbsolute()
                    ? Math.round((tOff + aNew) / step) * step - tOff
                    : aPrev + Math.round((aNew - aPrev) / step) * step;
                // the section end is a snap point of its own, and grid
                // points hugging it are ABSORBED — a snap near the end
                // lands EXACTLY on the end, never almost (v10.2)
                double aEnd = dragEndLimit < Double.MAX_VALUE
                    ? warpFwd(dragEndLimit) : Double.MAX_VALUE;
                if (aEnd < Double.MAX_VALUE
                        && (Math.abs(g - aEnd) < step * 0.5
                            || Math.abs(aNew - aEnd) < Math.abs(aNew - g))) {
                    newT = dragEndLimit;
                } else {
                    newT = Math.max(lane.get(dragNodeIdx - 1).t + minGap(),
                        warpInv(g));
                }
            }
            // value: global absolute grid (0.0 G, 3.6 G, 124 deg/s ...)
            // by default, or Δ from the previous node (Options toggle).
            // The influence RESULTING VIEW snaps in DISPLAY space: the
            // RESULTING value lands on the grid — that is the curve
            // under the cursor (newVal is display-space throughout;
            // the write-back below converts to the delta).
            double vStep = snapVal(activeLane);
            if (app.settings.snapVRelative() && dragNodeIdx > 0) {
                double pv = dispVal(activeLane,
                    lane.get(dragNodeIdx - 1).t,
                    lane.get(dragNodeIdx - 1).val);
                newVal = pv + Math.round((newVal - pv) / vStep) * vStep;
            } else {
                newVal = Math.round(newVal / vStep) * vStep;
            }
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

        // the section end is a WALL: only a node that began the gesture
        // already at the end may drag past it to extend the section
        if (newT > dragEndLimit) newT = dragEndLimit;

        // Resulting/cross-rate view: the cursor tracked the
        // DISPLAYED curve — write back the native value (v15.0 D98;
        // v16.7 D119)
        newVal = nativeVal(activeLane, newT, newVal);

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
                lo = Math.max(lo, lane.get(i - 1).t + minGap() - lane.get(i).t);
            }
            if (i < lane.size() - 1 && !selSet.contains(i + 1)) {
                hi = Math.min(hi, lane.get(i + 1).t - minGap() - lane.get(i).t);
            }
        }
        dT = FvdDesigner.clamp(dT, Math.min(lo, 0), Math.max(hi, 0));
        double[] gaps = BezierBaker.segGaps(lane);
        for (int i : selSet) {
            BezierBaker.Node m = lane.get(i);
            if (i > 0) m.t += dT;
            m.val += dV;
        }
        // handles facing a squished/stretched segment keep their
        // SLOPES — dv rides the run (v16.1, D105)
        BezierBaker.rescaleDvToGaps(lane, gaps);
        touchLane(lane);
        syncFollowingEnds();   // D248
        redraw();
        app.rebuildTrack();
    }

    /** Arrow keys: step the selection by the snap intervals — the
     *  GLOBAL spinner conventions on nodes since v19.19 (D248, user
     *  spec): SHIFT steps at ONE TENTH of the step, CTRL SNAPS to the
     *  NEXT round multiple of the (possibly Shift-scaled) step from
     *  the CURRENT position (Gestures.nudge — the modStep recipe;
     *  the old Ctrl branch stepped THEN rounded, which could jump two
     *  cells from an off-grid node, and re-rounded the idle axis),
     *  both combine. The snap REFERENCES stay Ctrl-drag's per-axis
     *  ones (time: the absolute grid or the previous node; value:
     *  absolute or the previous node — Edit > Snapping). Only the
     *  pressed axis moves. No end wall: arrows are the deliberate
     *  extend path. Auto-repeat (holding the key) gives continuous
     *  movement for free. */
    boolean nudgeSelected(int dtDir, int dvDir, boolean slow, boolean snap) {
        if (!surfStrip && stripHasNodeSel()) {
            final boolean[] r = { false };
            withSurf(() -> r[0] = nudgeSelected(dtDir, dvDir, slow, snap));
            return r[0];
        }
        if (selIdx < 0) return false;
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(selIdx);
        BezierBaker.Node prev = selIdx > 0 ? lane.get(selIdx - 1) : null;
        double newT = n.t, newVal = n.val;
        if (dtDir != 0 && prev != null) {   // node 0's t is frozen
            // steps live in the DISPLAY domain (v16.0): a warped view
            // nudges by axis steps, mapped back to native
            double a = warpMap == null ? n.t : warpFwd(n.t);
            double ref = app.settings.snapTAbsolute() ? -tOff
                : (warpMap == null ? prev.t : warpFwd(prev.t));
            double aNew = Gestures.nudge(a, ref, snapXStep(), dtDir,
                slow, snap);
            newT = warpMap == null ? aNew : warpInv(aNew);
            // no explicit floor here: moveNode clamps a single move to
            // prev.t + minGap itself, and a GROUP move must keep the
            // full delta (moveGroupBy clamps against NON-selected
            // neighbors only — a floor against a selected prev would
            // shorten the whole group's step)
        }
        if (dvDir != 0) {
            double ref = app.settings.snapVRelative() && prev != null
                ? prev.val : 0;
            newVal = Gestures.nudge(n.val, ref, snapVal(activeLane), dvDir,
                slow, snap);
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
        touchLane(lane);   // an edited lane stops following (D248)
        if (idx == 0) {
            newT = 0;
        } else {
            double lo = lane.get(idx - 1).t + minGap();
            // time-rippling pushes the later nodes along, so no upper clamp
            double hi = (app.rippleT || idx == lane.size() - 1)
                    ? Double.MAX_VALUE
                    : lane.get(idx + 1).t - minGap();
            if (surfStrip) {
                // the control is a HARD BOX (v18.5, D174): interior
                // nodes never leave it and a time ripple never moves
                // the pinned end. The LAST node itself drags the
                // CONTROL LENGTH since v18.43 (D215, user spec — no
                // stretch of the earlier nodes; the end diamond
                // keeps the classic rescale): its wall is the
                // section end / next control instead of the box.
                if (idx == lane.size() - 1) {
                    hi = Math.min(hi, surfCtlMaxLen());
                } else if (app.rippleT) {
                    hi = Math.min(hi, lane.get(lane.size() - 1).t
                        - minGap() * (lane.size() - 1 - idx));
                }
            }
            newT = FvdDesigner.clamp(newT, lo, hi);
        }
        double[] gaps = BezierBaker.segGaps(lane);
        if (app.rippleT || app.rippleV) {
            // shift everything after the node by the same deltas on the
            // enabled axes: time keeps later transitions, value
            // translates the rest of the graph — on the STRIP surface
            // the LAST node holds its x (v18.5, D174)
            double dT = newT - n.t, dV = newVal - n.val;
            for (int j = idx + 1; j < lane.size(); j++) {
                if (app.rippleT && !(surfStrip && j == lane.size() - 1)) {
                    lane.get(j).t += dT;
                }
                if (app.rippleV) lane.get(j).val += dV;
            }
            if (surfStrip && app.rippleT && lane.size() >= 2) {
                // belt: rippled interiors never cross the pinned end
                double bound = lane.get(lane.size() - 1).t;
                for (int j = lane.size() - 2; j > idx; j--) {
                    if (lane.get(j).t > bound - minGap()) {
                        lane.get(j).t = bound - minGap();
                    }
                    bound = lane.get(j).t;
                }
            }
        }
        n.t = newT;
        n.val = newVal;
        // the strip's LAST node carries the control length with it
        // (v18.43, D215): the box follows the node — floored so
        // spClampControls never has to re-stretch the graph (a graph
        // ending short of the floor just holds dotted to the end)
        if (surfStrip && idx == lane.size() - 1 && idx > 0) {
            surfCtl.len = Math.max(newT, Math.min(
                minGapOf(surfRs.sec()) * 2, surfCtlMaxLen()));
        }
        // handles facing the squished/stretched segments keep their
        // SLOPES — dv rescales with the run (v16.1, D105; the
        // removeBridging rule on node moves — smooth joints no longer
        // kink when a node slides horizontally)
        BezierBaker.rescaleDvToGaps(lane, gaps);
        // a level run's height is steerable from EITHER END (v10.6):
        // enforcement propagates left-to-right, so a move of the RIGHT
        // end writes its value back through the run first; interior
        // nodes stay pinned to the line (enforcement snaps them back)
        if (idx > 0 && lane.get(idx - 1).levelOut
                && (idx >= lane.size() - 1 || !lane.get(idx).levelOut)) {
            for (int j = idx - 1; j >= 0 && lane.get(j).levelOut; j--) {
                lane.get(j).val = newVal;
            }
        }
        syncFollowingEnds();   // a moved last node drags the untouched
                               // lanes' ends along (D248)
        redraw();
        app.rebuildTrack();
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

            double nx = ltToX(n.t), ny = valToY(activeLane,
                dispVal(activeLane, n.t, n.val));
            double kx = ltToX(n.t + curDt), ky = valToY(activeLane,
                dispVal(activeLane, n.t, n.val + oldDv));
            double dirX = kx - nx, dirY = ky - ny;
            double dirLen = Math.hypot(dirX, dirY);
            if (dirLen < 1e-6) return;
            dirX /= dirLen; dirY /= dirLen;

            double proj = Math.max(4, (x - nx) * dirX + (y - ny) * dirY);
            double newDt = xToLt(nx + dirX * proj) - n.t;
            newFrac = FvdDesigner.clamp(Math.abs(newDt) / segLen, MIN_FRAC, BezierBaker.MAX_FRAC);
            if (snap) newFrac = snapFrac(newFrac);
            newDv = slope * ((dragHandleIsOut ? 1 : -1) * newFrac * segLen);
        } else {
            // free: fraction from mouse time, dv from mouse value
            double dt = xToLt(x) - n.t;
            newFrac = FvdDesigner.clamp((dragHandleIsOut ? dt : -dt) / segLen,
                            MIN_FRAC, BezierBaker.MAX_FRAC);
            if (snap) newFrac = snapFrac(newFrac);
            newDv = nativeVal(activeLane, n.t,
                yToVal(activeLane, y)) - n.val;
        }

        touchLane(lane);   // D248
        if (dragHandleIsOut) { n.outFrac = newFrac; n.outDv = newDv; }
        else                 { n.inFrac = newFrac;  n.inDv = newDv; }

        // smooth node, free drag: the partner handle mirrors the change
        // proportionally on BOTH axes (its fraction and dv scale by the
        // same ratios as the dragged handle's), so joined handles grow,
        // shrink and re-angle together. Inline drags leave it alone.
        if (!inline && n.smooth) {
            if (dragHandleIsOut && prev != null) {
                n.inFrac = FvdDesigner.clamp(n.inFrac * (newFrac / oldFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                n.inDv = Math.abs(oldDv) > 1e-9
                    ? n.inDv * (newDv / oldDv)
                    // dragged handle was flat: ratio undefined, mirror by slope
                    : (newDv / (newFrac * segLen)) * (-n.inFrac * (n.t - prev.t));
            } else if (!dragHandleIsOut && next != null) {
                n.outFrac = FvdDesigner.clamp(n.outFrac * (newFrac / oldFrac), MIN_FRAC, BezierBaker.MAX_FRAC);
                n.outDv = Math.abs(oldDv) > 1e-9
                    ? n.outDv * (newDv / oldDv)
                    : (newDv / (-newFrac * segLen)) * (n.outFrac * (next.t - n.t));
            }
        }
        redraw();
        app.rebuildTrack();
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
        double px0 = ltToX(poly.get(0)[0]), py0 = valToY(activeLane,
            dispVal(activeLane, poly.get(0)[0], poly.get(0)[1]));
        // the dotted hold-final-value extension is insertable too
        double laneEnd = lane.get(lane.size() - 1).t;
        boolean extended = laneEnd < sectionEnd() - 1e-9;
        int segs = poly.size() + (extended ? 1 : 0);
        for (int i = 1; i < segs; i++) {
            double t1 = i < poly.size() ? poly.get(i)[0] : sectionEnd();
            double v1 = i < poly.size() ? poly.get(i)[1] : lane.get(lane.size() - 1).val;
            double t0 = i < poly.size() ? poly.get(i - 1)[0] : laneEnd;
            double px1 = ltToX(t1), py1 = valToY(activeLane,
                dispVal(activeLane, t1, v1));
            double segX = px1 - px0, segY = py1 - py0;
            double len2 = segX * segX + segY * segY;
            double u = len2 < 1e-9 ? 0
                     : FvdDesigner.clamp(((x - px0) * segX + (y - py0) * segY) / len2, 0, 1);
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
        return insertRoomAt(bestT) ? bestT : Double.NaN;
    }

    /** Enough room to insert a node at curve time t (curveHitT's
     *  spacing rules, reusable for re-checking a SNAPPED position). */
    private boolean insertRoomAt(double t) {
        List<BezierBaker.Node> lane = active();
        if (t > sectionEnd() + 1e-9) return false;
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return false;
        if (t - lane.get(i - 1).t < insertGap()) return false;
        if (i < lane.size() && lane.get(i).t - t < insertGap()) return false;
        return true;
    }

    /** The insert position's Ctrl x-snap (v10.7): the horizontal snap
     *  reference applies — Δ from the previous node (local, default)
     *  or the absolute grid, in the DISPLAY domain's units (v16.0),
     *  mapped back to native through the warp. */
    private double snapInsertT(double t) {
        double step = snapXStep();
        double a = warpFwd(t);
        if (app.settings.snapTAbsolute()) {
            return warpInv(Math.round((tOff + a) / step) * step - tOff);
        }
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return t;
        double aPrev = warpFwd(lane.get(i - 1).t);
        return warpInv(aPrev + Math.round((a - aPrev) / step) * step);
    }

    /** The hover dot after the optional Ctrl snap: time lands on the
     *  grid, the VALUE keeps riding the curve (deliberately unsnapped);
     *  NaN when the snapped spot has no room to insert. */
    private double snappedHover(double raw) {
        if (Double.isNaN(raw) || !ctrlHover) return raw;
        double s = snapInsertT(raw);
        return insertRoomAt(s) ? s : Double.NaN;
    }

    /** Ctrl pressed/released while hovering: re-snap the insert dot
     *  without waiting for the next mouse move (key filters, like the
     *  Shift slope toggle). */
    void ctrlHover(boolean on) {
        if (ctrlHover == on) return;
        ctrlHover = on;
        if (Double.isNaN(hoverRawT) && Double.isNaN(hoverT)) return;
        hoverT = snappedHover(hoverRawT);
        redraw();
    }

    /** Insert a node ON the curve. Flat segments get sine handles at the
     *  same level; anything else is a de Casteljau split — tangent
     *  handles, lengths rewritten so the curve does not change at all.
     *  Returns the new node's index, or -1 if there was no room (the
     *  caller may chain straight into a drag gesture — v10.1). */
    private int insertNodeOnCurve(double t) {
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        if (i == 0) return -1;
        BezierBaker.Node n;
        if (i == lane.size()) {
            // on the dotted hold-final-value extension: append a real
            // node at the same level (flat sine transition)
            BezierBaker.Node last = lane.get(i - 1);
            if (t - last.t < insertGap() || t > sectionEnd() + 1e-9) return -1;
            pushUndoSnapshot();
            n = new BezierBaker.Node(t, last.val);
            // the Append recipe (D248): a smooth sloped last node's
            // out continues its in slope, the new node holds flat
            Gestures.continueOut(i >= 2 ? lane.get(i - 2) : null, last, n,
                SINE_FRAC);
            n.inFrac = SINE_FRAC;
            n.outFrac = SINE_FRAC;
            lane.add(n);
            touchLane(lane);
            syncFollowingEnds();   // the new last node is the end (D248)
            selIdx = i;
            selSet.clear();
            selSet.add(i);
            axisLock = 0;
            redraw();
            app.rebuildTrack();
            return i;
        }
        BezierBaker.Node a = lane.get(i - 1), b = lane.get(i);
        if (t - a.t < insertGap() || b.t - t < insertGap()) return -1;
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
        n.straightOut = a.straightOut;   // a locked segment stays locked
        n.levelOut = a.levelOut;         // through a split (both halves)
        lane.add(i, n);
        touchLane(lane);   // D248
        selIdx = i;
        selSet.clear();
        selSet.add(i);
        axisLock = 0;
        redraw();
        app.rebuildTrack();
        return i;
    }

    /** Removing an interior node bridges the gap naturally: the two
     *  neighbors keep their positions and handle settings (fractions
     *  simply apply to the merged segment). Deleting the LAST node
     *  shortens the ride; the first node (t=0 anchor) is undeletable. */
    private void deleteNode(int index) {
        List<BezierBaker.Node> lane = active();
        if (index == 0 || lane.size() <= 2) return;
        pushUndoSnapshot();
        BezierBaker.removeBridging(lane, index);
        touchLane(lane);
        syncFollowingEnds();   // a deleted last node shortens the end (D248)
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        redraw();
        app.rebuildTrack();
    }

    void deleteSelected() {
        if (!surfStrip) {
            // the strip owns Delete while focused (v18.0/v18.3):
            // selected graph nodes take the SHARED deletion below;
            // otherwise the selected control/mark removes itself
            if (stripHasNodeSel()) {
                withSurf(this::deleteSelected);
                return;
            }
            if (spFocus && spDeleteSelected()) return;
        }
        if (activeSc() != null && !surfStrip) {
            deleteElement(elSel);
            return;
        }
        if (selIdx < 0) return;
        if (selSet.isEmpty()) selSet.add(selIdx);
        List<BezierBaker.Node> lane = active();
        List<Integer> idxs = new ArrayList<>(selSet);
        idxs.sort(java.util.Comparator.reverseOrder());
        boolean pushed = false;
        for (int i : idxs) {
            if (i == 0 || lane.size() <= 2) continue;
            if (!pushed) { pushUndoSnapshot(); pushed = true; }
            BezierBaker.removeBridging(lane, i);
        }
        if (!pushed) return;
        touchLane(lane);
        syncFollowingEnds();   // D248
        selIdx = -1;
        selSet.clear();
        axisLock = 0;
        redraw();
        app.rebuildTrack();
    }

    /** H: make the selected node's handles horizontal (dv = 0) — EVERY
     *  selected node when multi-selected. While a handle is being
     *  dragged on a BROKEN node, only that handle. */
    void flattenHandles() {
        if (selIdx < 0) return;
        List<BezierBaker.Node> lane = active();
        pushUndoSnapshot();
        touchLane(lane);   // D248
        if (selSet.size() > 1) {
            for (int i : selSet) {
                BezierBaker.Node n = lane.get(i);
                n.outDv = 0;
                n.inDv = 0;
            }
        } else {
            BezierBaker.Node n = lane.get(selIdx);
            if (dragHandleIdx >= 0 && !n.smooth) {
                if (dragHandleIsOut) n.outDv = 0; else n.inDv = 0;
            } else {
                n.outDv = 0;
                n.inDv = 0;
            }
        }
        redraw();
        app.rebuildTrack();
    }

    /** H (v10.5 — was Shift+H): level the whole selection ONTO the main
     *  highlighted node — every selected node takes selIdx's value with
     *  flat handles, so the selected run becomes one horizontal line
     *  (segments between selected nodes go exactly flat; transitions
     *  to unselected neighbors keep their handles). The main node is
     *  the last one clicked, or the earliest in a box selection.
     *  With fewer than two selected this falls back to flattening the
     *  selected node's handles (H's classic single-node meaning) —
     *  and a SECOND press on an already-flat node LEVELS it WITH the
     *  previous node (value copy; v19.12, D241, user spec). Section-
     *  first nodes only flatten; a third press is an exact no-op. */
    void levelSelected() {
        if (stripRoute(this::levelSelected)) return;
        if (selIdx < 0) return;
        if (selSet.size() < 2) {
            List<BezierBaker.Node> lane0 = active();
            if (selIdx < lane0.size()) {
                BezierBaker.Node n0 = lane0.get(selIdx);
                boolean flat = Math.abs(n0.inDv) < 1e-9
                    && Math.abs(n0.outDv) < 1e-9;
                // second press: already flat → take the PREVIOUS
                // node's value (a mid-drag broken-node H keeps its
                // single-handle meaning — the dragHandleIdx branch)
                if (flat && selIdx > 0 && dragHandleIdx < 0) {
                    if (lane0.get(selIdx - 1).val == n0.val) return;
                    pushUndoSnapshot();
                    touchLane(lane0);   // D248
                    n0.val = lane0.get(selIdx - 1).val;
                    redraw();
                    app.rebuildTrack();
                    return;
                }
            }
            flattenHandles();
            return;
        }
        List<BezierBaker.Node> lane = active();
        double ref = lane.get(selIdx).val;
        pushUndoSnapshot();
        touchLane(lane);   // D248
        for (int i : selSet) {
            BezierBaker.Node n = lane.get(i);
            n.val = ref;
            n.outDv = 0;
            n.inDv = 0;
        }
        redraw();
        app.rebuildTrack();
    }

    /** Z/X: constrain node drags to one axis; same key again unlocks. */
    void toggleAxisLock(boolean vertical) {
        if (stripRoute(() -> toggleAxisLock(vertical))) return;
        if (selIdx < 0) return;
        int target = vertical ? 1 : 2;
        axisLock = (axisLock == target) ? 0 : target;
        redraw();
    }

    /** Put segment (i, i+1)'s facing handles on its chord. reAngle* =
     *  keep C1 at that end by re-angling its FAR handle (the classic
     *  two-node F behavior; off when another chorded segment owns it). */
    private void chordSegment(List<BezierBaker.Node> lane, int i,
                              boolean reAngleLeft, boolean reAngleRight) {
        BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
        double slope = (b.val - a.val) / (b.t - a.t);
        a.outDv = slope * BezierBaker.effOutDt(a, b);
        b.inDv  = slope * BezierBaker.effInDt(a, b);
        if (reAngleLeft && a.smooth && i > 0) {
            a.inDv = slope * BezierBaker.effInDt(lane.get(i - 1), a);
        }
        if (reAngleRight && b.smooth && i + 1 < lane.size() - 1) {
            b.outDv = slope * BezierBaker.effOutDt(b, lane.get(i + 2));
        }
    }

    /** F: straighten every lane segment whose BOTH endpoints are
     *  selected onto its chord (the v10.6 selectedPairs target set —
     *  ANY number of nodes since v19.12, D241; two adjacent = the
     *  classic exact behavior). Interior joints between two chorded
     *  segments whose chords DISAGREE go honestly BROKEN first —
     *  leaving them "smooth" would let the C1 far-handle re-angle
     *  overwrite the neighbor chord (the user's snap-back). A SINGLE
     *  selected node straightens the segment to its PREVIOUS node
     *  (D241, user spec; section-first nodes no-op). Locked segments
     *  are skipped — their lock owns the shape. */
    void straightenSelected() {
        if (stripRoute(this::straightenSelected)) return;
        List<BezierBaker.Node> lane = active();
        Set<Integer> sel = lockSelection();
        List<Integer> segs = selectedPairs(sel);
        if (segs.isEmpty() && sel.size() == 1) {
            int only = sel.iterator().next();
            if (only > 0 && only < lane.size()) {
                segs = java.util.List.of(only - 1);
            }
        }
        segs = new ArrayList<>(segs);
        segs.removeIf(i -> lane.get(i).lockedOut());
        if (segs.isEmpty()) return;
        pushUndoSnapshot();
        touchLane(lane);   // D248
        Set<Integer> segSet = new HashSet<>(segs);
        for (int i : segs) {
            int k = i + 1;
            if (!segSet.contains(k)) continue;   // node k joins two chords
            BezierBaker.Node a = lane.get(i), b = lane.get(k),
                             c = lane.get(k + 1);
            double s1 = (b.val - a.val) / (b.t - a.t);
            double s2 = (c.val - b.val) / (c.t - b.t);
            if (b.smooth && Math.abs(s1 - s2) > 1e-6
                    * Math.max(1, Math.max(Math.abs(s1), Math.abs(s2)))) {
                b.smooth = false;   // honest broken joint (the D62 rule)
            }
        }
        for (int i : segs) {
            chordSegment(lane, i,
                !segSet.contains(i - 1),   // far handle owned by another
                !segSet.contains(i + 1));  // chord? leave it to that one
        }
        redraw();
        app.rebuildTrack();
    }

    /** Which authored segment (index of its LEFT node) contains curve
     *  time t; -1 for NaN, the hold extension, or before node 0. */
    private int segmentAt(double t) {
        if (Double.isNaN(t)) return -1;
        List<BezierBaker.Node> lane = active();
        int i = 0;
        while (i < lane.size() && lane.get(i).t < t) i++;
        return i == 0 || i >= lane.size() ? -1 : i - 1;
    }

    /** Right-click on a segment (v10.7): its lock setting as a
     *  three-way pick — same semantics as Shift+F / Shift+H, one
     *  segment at a time. */
    private void showSegmentLockMenu(int seg, double screenX, double screenY) {
        List<BezierBaker.Node> lane = active();
        if (seg < 0 || seg >= lane.size() - 1) return;
        BezierBaker.Node a = lane.get(seg);
        ContextMenu cm = new ContextMenu();
        ToggleGroup grp = new ToggleGroup();
        RadioMenuItem none = new RadioMenuItem("Unlocked");
        RadioMenuItem straight = new RadioMenuItem("Straight");
        RadioMenuItem level = new RadioMenuItem("Horizontal");
        none.setToggleGroup(grp);
        straight.setToggleGroup(grp);
        level.setToggleGroup(grp);
        (a.levelOut ? level : a.straightOut ? straight : none).setSelected(true);
        none.setOnAction(ev -> setSegmentLock(seg, 0));
        straight.setOnAction(ev -> setSegmentLock(seg, 1));
        level.setOnAction(ev -> setSegmentLock(seg, 2));
        cm.getItems().addAll(none, straight, level);
        showMenu(cm, screenX, screenY);
    }

    /** Apply one segment's lock pick: 0 unlocked, 1 straight, 2 level. */
    private void setSegmentLock(int seg, int mode) {
        List<BezierBaker.Node> lane = active();
        if (seg < 0 || seg >= lane.size() - 1) return;
        BezierBaker.Node a = lane.get(seg);
        boolean straight = mode == 1, level = mode == 2;
        if (a.straightOut == straight && a.levelOut == level) return;
        pushUndoSnapshot();
        touchLane(lane);   // D248
        boolean unlocking = mode == 0;
        a.straightOut = straight;
        a.levelOut = level;
        if (unlocking) {
            breakKinkedJoints(lane, java.util.Set.of(seg, seg + 1));
        }
        redraw();          // enforcement applies the new lock right here
        app.rebuildTrack();
    }

    /** The selection to operate on for lock toggles. */
    private Set<Integer> lockSelection() {
        return selSet.isEmpty() && selIdx >= 0 ? java.util.Set.of(selIdx) : selSet;
    }

    /** Segment indices BETWEEN adjacent selected nodes (i where i and
     *  i+1 are both selected) — the lock toggles' target set (v10.6):
     *  what happens between the selected nodes never disturbs a
     *  neighboring lock that merely shares an endpoint. */
    private List<Integer> selectedPairs(Set<Integer> sel) {
        List<Integer> out = new ArrayList<>();
        List<BezierBaker.Node> lane = active();
        for (int i : sel) {
            if (i < lane.size() - 1 && sel.contains(i + 1)) out.add(i);
        }
        return out;
    }

    /** Clear one lock flag on the given segments; kinked joints come
     *  out honestly broken (D62). Caller pushes the undo snapshot. */
    private void unlockSegments(List<BezierBaker.Node> lane,
                                Iterable<Integer> segs, boolean level) {
        Set<Integer> joints = new HashSet<>();
        for (int i : segs) {
            if (level) lane.get(i).levelOut = false;
            else lane.get(i).straightOut = false;
            joints.add(i);
            joints.add(i + 1);
        }
        breakKinkedJoints(lane, joints);
    }

    /** Shift+F: PERSISTENT straightness. With adjacent nodes selected
     *  (2+ — every selected pair counts, v10.6), the segments BETWEEN
     *  them lock straight — or unlock, if they are all already
     *  straight-locked; locks merely NEIGHBORING the selection are
     *  never touched. With no pair selected, a single selected
     *  endpoint unlocks the locked segment(s) it sits on (the classic
     *  gesture). enforceLocks() re-chords locked segments after every
     *  edit, so dragging an endpoint keeps the line straight. */
    void toggleStraightLock() {
        if (stripRoute(this::toggleStraightLock)) return;
        List<BezierBaker.Node> lane = active();
        Set<Integer> sel = lockSelection();
        List<Integer> pairs = selectedPairs(sel);
        if (!pairs.isEmpty()) {
            boolean allLocked = true;
            for (int i : pairs) allLocked &= lane.get(i).straightOut;
            pushUndoSnapshot();
            touchLane(lane);   // D248
            if (allLocked) {
                unlockSegments(lane, pairs, false);
            } else {
                for (int i : pairs) {
                    lane.get(i).straightOut = true;
                    lane.get(i).levelOut = false;   // swap from Shift+H
                }
            }
            redraw();          // enforcement straightens it right here
            app.rebuildTrack();
            return;
        }
        Set<Integer> locked = new HashSet<>();
        for (int i : sel) {
            if (i < lane.size() - 1 && lane.get(i).straightOut) locked.add(i);
            if (i > 0 && lane.get(i - 1).straightOut) locked.add(i - 1);
        }
        if (locked.isEmpty()) {
            // single node, nothing adjacent locked (v19.12, D241, user
            // spec): LOCK the segment to the PREVIOUS node — the F
            // pairing; section-first nodes no-op
            if (sel.size() != 1) return;
            int only = sel.iterator().next();
            if (only <= 0 || only >= lane.size()) return;
            pushUndoSnapshot();
            touchLane(lane);   // D248
            lane.get(only - 1).straightOut = true;
            lane.get(only - 1).levelOut = false;
            redraw();          // enforcement chords it right here
            app.rebuildTrack();
            return;
        }
        pushUndoSnapshot();
        touchLane(lane);   // D248
        unlockSegments(lane, locked, false);
        redraw();
        app.rebuildTrack();
    }

    /** Re-assert every LOCKED segment (runs at the top of every redraw,
     *  which follows every lane mutation; idempotent and cheap).
     *  STRAIGHT locks (Shift+F) re-chord their facing handles onto the
     *  endpoint line; LEVEL locks (Shift+H, v10.5) first pin the RIGHT
     *  node's value to the left's — so a run's height follows its left
     *  end, moveNode writes a RIGHT-end move back through the run
     *  before this pass (either end steers, v10.6), and interior
     *  nodes snap back onto the line — then the same
     *  slope math (slope 0) flattens the facing handles. Smooth
     *  endpoints keep C1 by re-angling their far handle — unless that
     *  side is itself locked, which owns its own handles. */
    private void enforceLocks() {
        for (List<BezierBaker.Node> ln : app.lanes) enforceLockLane(ln);
        // complex speed-control graphs share the whole lock machinery
        // (v18.3): every control lane, every section
        for (TrackPiece p : app.coaster.pieces) {
            for (Section s : p.sections) {
                for (SpeedControl c : s.speedControls()) {
                    if (c.complex) {
                        enforceLockLane(c.lane);
                        enforceLockLane(c.decelLane);   // D217
                    }
                }
            }
        }
    }

    private void enforceLockLane(List<BezierBaker.Node> ln) {
        {
            for (int i = 0; i < ln.size() - 1; i++) {
                BezierBaker.Node a = ln.get(i);
                if (!a.lockedOut()) continue;
                BezierBaker.Node b = ln.get(i + 1);
                if (b.t - a.t < 1e-9) continue;   // malformed file guard
                if (a.levelOut) b.val = a.val;
                double slope = (b.val - a.val) / (b.t - a.t);
                a.outDv = slope * BezierBaker.effOutDt(a, b);
                b.inDv  = slope * BezierBaker.effInDt(a, b);
                if (a.smooth && i > 0 && !ln.get(i - 1).lockedOut()) {
                    a.inDv = slope * BezierBaker.effInDt(ln.get(i - 1), a);
                }
                if (b.smooth && i + 1 < ln.size() - 1 && !b.lockedOut()) {
                    b.outDv = slope * BezierBaker.effOutDt(b, ln.get(i + 2));
                }
            }
        }
    }

    /** After an unlock, a joint whose two sides sit at different slopes
     *  would be a kinked "smooth" node — mark those broken; sides that
     *  still agree stay smooth (D62). */
    private void breakKinkedJoints(List<BezierBaker.Node> lane, Set<Integer> joints) {
        for (int i : joints) {
            if (i <= 0 || i >= lane.size() - 1) continue;
            BezierBaker.Node n = lane.get(i);
            if (!n.smooth) continue;
            double inDt = BezierBaker.effInDt(lane.get(i - 1), n);
            double outDt = BezierBaker.effOutDt(n, lane.get(i + 1));
            double sIn = Math.abs(inDt) < 1e-12 ? 0 : n.inDv / inDt;
            double sOut = Math.abs(outDt) < 1e-12 ? 0 : n.outDv / outDt;
            if (Math.abs(sIn - sOut)
                    > 1e-6 * Math.max(1, Math.max(Math.abs(sIn), Math.abs(sOut)))) {
                n.smooth = false;
            }
        }
    }

    /** Shift+H: PERSISTENT level — the horizontal sibling of Shift+F
     *  (v10.5). With adjacent nodes selected, the segments BETWEEN
     *  them are the target (v10.6, like Shift+F): all already
     *  level-locked = unlock them; otherwise the selection levels onto
     *  the MAIN node and those segments lock horizontal — locks merely
     *  neighboring the selection stay untouched. With no pair
     *  selected, a single selected endpoint unlocks its segment(s).
     *  enforceLocks keeps a run level through any edit (its height is
     *  steerable from EITHER end; interior nodes are pinned), and
     *  nodes inserted on it stay on the line. Swaps directly with
     *  Shift+F: locking one way clears the other. */
    void toggleLevelLock() {
        if (stripRoute(this::toggleLevelLock)) return;
        List<BezierBaker.Node> lane = active();
        Set<Integer> sel = lockSelection();
        List<Integer> pairs = selectedPairs(sel);
        if (!pairs.isEmpty()) {
            boolean allLevel = true;
            for (int i : pairs) allLevel &= lane.get(i).levelOut;
            pushUndoSnapshot();
            touchLane(lane);   // D248
            if (allLevel) {
                unlockSegments(lane, pairs, true);
            } else {
                double ref = lane.get(selIdx >= 0 ? selIdx : pairs.get(0)).val;
                for (int i : sel) {
                    BezierBaker.Node n = lane.get(i);
                    n.val = ref;
                    n.outDv = 0;
                    n.inDv = 0;
                }
                for (int i : pairs) {
                    lane.get(i).levelOut = true;
                    lane.get(i).straightOut = false;   // swap from Shift+F
                }
            }
            redraw();          // enforcement pins it right here
            app.rebuildTrack();
            return;
        }
        Set<Integer> locked = new HashSet<>();
        for (int i : sel) {
            if (i < lane.size() - 1 && lane.get(i).levelOut) locked.add(i);
            if (i > 0 && lane.get(i - 1).levelOut) locked.add(i - 1);
        }
        if (locked.isEmpty()) {
            // single node, nothing adjacent locked (v19.12, D241, user
            // spec): level the PREVIOUS node onto this one and lock
            // the segment horizontal — as if both were selected with
            // this node primary; section-first nodes no-op
            if (sel.size() != 1) return;
            int only = sel.iterator().next();
            if (only <= 0 || only >= lane.size()) return;
            pushUndoSnapshot();
            touchLane(lane);   // D248
            BezierBaker.Node p = lane.get(only - 1), n = lane.get(only);
            p.val = n.val;      // enforcement pins RIGHT to LEFT — the
            p.outDv = p.inDv = 0;   // copy keeps the selected height
            n.outDv = n.inDv = 0;
            p.levelOut = true;
            p.straightOut = false;
            redraw();          // enforcement pins it right here
            app.rebuildTrack();
            return;
        }
        pushUndoSnapshot();
        touchLane(lane);   // D248
        unlockSegments(lane, locked, true);
        redraw();
        app.rebuildTrack();
    }

    /** Are sections i and j of the active piece adjacent LIKE lane
     *  sections the CONNECT op accepts (v16.19 — mirror of
     *  Coaster.mergeSections' test, for menu enablement)? */
    private boolean likeSections(int i, int j) {
        List<Section> secs = sections();
        if (i < 0 || j >= secs.size()) return false;
        if (!(secs.get(i) instanceof LaneSection a)
                || !(secs.get(j) instanceof LaneSection b)) return false;
        return !a.reversed() && !b.reversed()
            && !a.influenced() && !b.influenced()
            && (a instanceof GeoSection) == (b instanceof GeoSection)
            && a.domain() == b.domain()
            && a.rollGlobal == b.rollGlobal
            && a.yawPitchGlobal == b.yawPitchGlobal
            && a.ratePerMeter == b.ratePerMeter
            && a.rollAbsolute == b.rollAbsolute
            && a.ypAbsolute == b.ypAbsolute;
    }

    /** CONNECT the active piece's sections leftIdx and leftIdx+1
     *  into one (v16.19, D131 — the node split's inverse): matched
     *  seams merge losslessly; an unmatched seam KEEPS BOTH VALUES
     *  since v19.18 (D247 — a steep step over the domain's minimum
     *  transition, Coaster.mergeStepLen; equal values with differing
     *  slopes = one broken node; see Coaster.mergeSections). One
     *  undo step; refusals post their reason. */
    void connectSections(int leftIdx) {
        if (!likeSections(leftIdx, leftIdx + 1)) {
            app.statsLabel.setText("Connect needs two adjacent "
                + "force/geo sections of the SAME type, domain and "
                + "axis/rate/roll settings (not reversed or "
                + "influenced) — convert one to match first.");
            return;
        }
        pushUndoSnapshot();
        if (!app.coaster.mergeSections(activePiece, leftIdx)) {
            app.statsLabel.setText("Couldn't connect these sections.");
            return;
        }
        activeSection = leftIdx;
        app.lanes = lanesFor(sections().get(activeSection));
        selIdx = -1;
        selSet.clear();
        elSel = -1;
        syncLaneButtons();
        app.rebuildTrack();
        app.updatePieceButton();
        refreshOffset();
        redraw();
    }

    /** Right-click menu for a node / the current selection (v16.18;
     *  reworked v16.20 D132, user spec: inapplicable ops do not
     *  APPEAR at all instead of graying — the menu is what you can
     *  do HERE): every node op in one place, LIVE shortcut labels in
     *  parentheses (rebinds show up). The split entries are the
     *  mid-section node-anchored split — "Split track piece" on a
     *  section's FIRST node routes to the classic boundary piece
     *  split; CONNECT is the split's inverse on edge nodes facing a
     *  LIKE neighbor (the badge menu carries a GRAYED copy for
     *  discoverability — here absent means unavailable). DELETE
     *  drops its redundant "(Delete)" tag and never shows on edge
     *  nodes (user rule; the Delete KEY still shortens the ride
     *  from the last node). */
    private void showNodeMenu(int idx, double sx, double sy) {
        List<BezierBaker.Node> lane = active();
        boolean edge = idx == 0 || idx == lane.size() - 1;
        boolean multi = selSet.size() > 1;
        boolean infl = activeInfl() != null;
        Section sec = sections().get(activeSection);
        boolean canCut = !multi && !infl && idx > 0
            && sec instanceof LaneSection && !sec.reversed()
            && idx < lane.size()
            && lane.get(idx).t < sectionEnd() - 1e-9;
        List<List<MenuItem>> groups = new ArrayList<>();
        List<MenuItem> g = new ArrayList<>();
        if (!multi) {
            MenuItem edit = new MenuItem("Edit node…");
            edit.setOnAction(ev -> showNodeEditor(idx));
            g.add(edit);
        }
        groups.add(g);

        g = new ArrayList<>();
        if (!multi) {
            MenuItem smooth = new MenuItem((edge
                ? "Match boundary (" : "Toggle smooth/broken (")
                + app.settings.shortcut("smooth") + ")");
            smooth.setOnAction(ev -> {
                selIdx = idx;
                toggleSmooth();
            });
            g.add(smooth);
            if (edge) {
                MenuItem matchAll = new MenuItem(
                    "Match boundary, all lanes ("
                    + app.settings.shortcut("matchAll") + ")");
                matchAll.setOnAction(ev -> {
                    selIdx = idx;
                    matchBoundaryAll();
                });
                g.add(matchAll);
            }
        }
        groups.add(g);

        g = new ArrayList<>();
        // single + already-flat + not first: H's SECOND press levels
        // with the previous node (D241) — the item says so honestly
        boolean flatHere = !multi && idx < lane.size()
            && Math.abs(lane.get(idx).inDv) < 1e-9
            && Math.abs(lane.get(idx).outDv) < 1e-9;
        MenuItem level = new MenuItem((flatHere && idx > 0
            ? "Level with previous (" : "Level selection / flatten (")
            + app.settings.shortcut("flath") + ")");
        level.setOnAction(ev -> {
            if (!multi) { selIdx = idx; }
            levelSelected();
        });
        g.add(level);
        boolean adjLevel = (idx < lane.size() - 1 && lane.get(idx).levelOut)
            || (idx > 0 && lane.get(idx - 1).levelOut);
        boolean adjStraight = (idx < lane.size() - 1
                && lane.get(idx).straightOut)
            || (idx > 0 && lane.get(idx - 1).straightOut);
        if (multi || adjLevel || idx > 0) {
            MenuItem levelLock = new MenuItem((adjLevel && !multi
                ? "Level-unlock ("
                : multi ? "Level-lock selection ("
                        : "Level-lock with previous (")
                + app.settings.shortcut("levelSel") + ")");
            levelLock.setOnAction(ev -> {
                if (!multi) { selIdx = idx; }
                toggleLevelLock();
            });
            g.add(levelLock);
        }
        if (multi || idx > 0) {
            MenuItem straighten = new MenuItem((multi
                ? "Straighten between pairs ("
                : "Straighten with previous (")
                + app.settings.shortcut("align") + ")");
            straighten.setOnAction(ev -> {
                if (!multi) { selIdx = idx; }
                straightenSelected();
            });
            g.add(straighten);
        }
        if (multi || adjStraight || idx > 0) {
            MenuItem strLock = new MenuItem((adjStraight && !multi
                ? "Straight-unlock ("
                : multi ? "Straight-lock ("
                        : "Straight-lock with previous (")
                + app.settings.shortcut("alignLock") + ")");
            strLock.setOnAction(ev -> {
                if (!multi) { selIdx = idx; }
                toggleStraightLock();
            });
            g.add(strLock);
        }
        groups.add(g);

        g = new ArrayList<>();
        if (canCut) {
            MenuItem splitSec = new MenuItem("Split section here");
            splitSec.setOnAction(ev -> splitAtNode(idx, false));
            g.add(splitSec);
        }
        // a section's FIRST node = the classic boundary piece split
        boolean firstNodeSplit = !multi && idx == 0 && activeSection > 0;
        if (canCut || firstNodeSplit) {
            MenuItem splitPc = new MenuItem("Split track piece here");
            splitPc.setOnAction(ev -> {
                if (firstNodeSplit) splitAt(activeSection);
                else splitAtNode(idx, true);
            });
            g.add(splitPc);
        }
        // CONNECT (v16.19): the split's inverse — shown only where a
        // LIKE neighbor faces this edge node
        boolean canConnect = !multi && !infl
            && ((idx == 0 && activeSection > 0
                    && likeSections(activeSection - 1, activeSection))
                || (idx == lane.size() - 1 && idx > 0
                    && likeSections(activeSection, activeSection + 1)));
        if (canConnect) {
            MenuItem connect = new MenuItem(idx == 0
                ? "Connect with previous section"
                : "Connect with next section");
            connect.setOnAction(ev -> connectSections(
                idx == 0 ? activeSection - 1 : activeSection));
            g.add(connect);
        }
        // the split/connect group sits DIRECTLY below "Edit node…"
        // since v19.13 (D242, user spec — was after the lock group)
        groups.add(1, g);

        g = new ArrayList<>();
        if (multi || (idx > 0 && idx < lane.size() - 1
                && lane.size() > 2)) {
            MenuItem del = new MenuItem("Delete");
            del.setOnAction(ev -> deleteSelected());
            g.add(del);
        }
        groups.add(g);

        ContextMenu cm = new ContextMenu();
        boolean first = true;
        for (List<MenuItem> gr : groups) {
            if (gr.isEmpty()) continue;
            if (!first) cm.getItems().add(new SeparatorMenuItem());
            cm.getItems().addAll(gr);
            first = false;
        }
        openMenu = cm;   // dismisses on the next graph click (D57)
        cm.show(canvas, sx, sy);
    }

    /** Double-click on a node: type its settings directly. Transition
     *  time/magnitude are relative to the PREVIOUS node (node 0 edits
     *  its global value); handles are segment fractions. 3 decimals. */
    private void showNodeEditor(int idx) {
        List<BezierBaker.Node> lane = active();
        // the strip surface's dialog (v18.3): capture the surface —
        // Apply and the live twins run AFTER withSurf returned, so
        // they re-enter it themselves (the typed dialog is part of
        // the recycled-editor contract)
        final boolean dSurf = surfStrip;
        final SpeedControl dCtl = surfCtl;
        final RunSec dRs = surfRs;
        final String dUnit = laneUnit(activeLane);
        BezierBaker.Node n = lane.get(idx);
        BezierBaker.Node prev = idx > 0 ? lane.get(idx - 1) : null;
        boolean hasOut = idx < lane.size() - 1;

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Node " + idx + " — " + laneName(activeLane));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));

        // LIVE EDITING (v19.1, D225 — user spec: "instead of an Apply
        // button, make the track update while editing each value"):
        // every box is a LiveNum — a text field with ▲/▼ steppers and
        // wheel stepping — whose every PARSEABLE text change applies
        // to the track at once (a blank/unparseable box holds, exactly
        // like the old Apply's parseOr fallback); each edit is a
        // GLOBAL undo step, bursts within 0.8 s on the same box
        // coalescing into one. The node is re-resolved BY INDEX per
        // apply on the captured surface, so Ctrl+Z (which swaps the
        // lane objects) leaves the box live; a section/lane switch
        // makes it inert instead of writing elsewhere.
        final int dPiece = activePiece, dSec = activeSection,
            dLane = activeLane;
        // re-enter the captured surface around a lane read/write
        java.util.function.Consumer<Runnable> onSurf = r -> {
            if (dSurf) {
                boolean os = surfStrip;
                SpeedControl oc = surfCtl;
                RunSec orr = surfRs;
                surfCtl = dCtl;
                surfRs = dRs;
                surfStrip = true;
                try {
                    r.run();
                } finally {
                    surfStrip = os;
                    surfCtl = oc;
                    surfRs = orr;
                }
            } else {
                if (activePiece != dPiece || activeSection != dSec
                        || activeLane != dLane) {
                    return;   // the dialog's node is no longer on screen
                }
                r.run();
            }
        };
        // the live node (null = gone: structure changed under us)
        java.util.function.Supplier<BezierBaker.Node> liveNode = () -> {
            List<BezierBaker.Node> ln = active();
            return idx < ln.size() ? ln.get(idx) : null;
        };
        final String[] undoKey = { null };
        final long[] undoNanos = { 0 };
        java.util.function.Consumer<String> liveUndo = key -> {
            long now = System.nanoTime();
            if (!key.equals(undoKey[0]) || now - undoNanos[0] > 800_000_000L) {
                pushUndoSnapshot();
            }
            undoKey[0] = key;
            undoNanos[0] = now;
            // every live sink is an edit (D248) — AFTER the snapshot,
            // so Ctrl+Z restores the flag with the node
            touchActive();
        };
        double vStep = snapVal(activeLane);
        double tStep = activeDistNative() ? 0.5 : 0.1;

        // CLICKABLE TWINS (v19.12, D241 — user spec): clicking a grey
        // computed twin SWAPS which representation the box edits —
        // transition ↔ absolute axis position, value ↔ change from
        // the previous node. Per-dialog-instance flags; reopening
        // resets. Snapping follows the box (its content IS the edited
        // representation).
        final boolean[] dtAbsMode = { false };
        final boolean[] valDeltaMode = { false };
        final Runnable[] dtSyncH = { null }, vSyncH = { null };
        // the hover-outline pattern (the speed popup's "Enter:" D237
        // recipe): a constant transparent border so hover never
        // shifts layout — grey stays the twins' color
        final String twinBase = "-fx-text-fill: rgb(140,145,158);"
            + " -fx-border-width: 1; -fx-border-radius: 3;"
            + " -fx-padding: 0 3 0 3; -fx-border-color: transparent;";
        final String twinHot = "-fx-text-fill: rgb(140,145,158);"
            + " -fx-border-width: 1; -fx-border-radius: 3;"
            + " -fx-padding: 0 3 0 3;"
            + " -fx-border-color: rgb(140,145,158);";

        LiveNum dtBox = null, v0Box = null, inBox = null, outBox = null,
            initBox = null;
        int row = 0;
        Label dtTitle = null;
        if (prev != null) {
            // min 0, not minGap(): in abs mode the steppers hold an
            // axis position — the transition floor lives in applyPos/
            // moveNode (D241; typed values were never box-clamped)
            dtBox = new LiveNum(n.t - prev.t, tStep, 0, 1e6);
            // computed twin (v16.15, user spec): the OTHER
            // representation — absolute display-axis position, or the
            // native transition in abs mode — live as you type
            Label dtAbs = new Label();
            dtAbs.setStyle(twinBase);
            final LiveNum fDtL = dtBox;
            dtTitle = new Label(activeDistNative()
                ? "Transition length (m)" : "Transition time (s)");
            final Label fDtTitle = dtTitle;
            Runnable dtSync = () -> {
                Double d = fDtL.parsed();
                BezierBaker.Node[] pv = { null };
                if (d != null) {
                    onSurf.accept(() -> {
                        List<BezierBaker.Node> ln = active();
                        if (idx - 1 < ln.size() && idx >= 1) {
                            pv[0] = ln.get(idx - 1);
                            if (dtAbsMode[0]) {
                                // box = display-axis absolute; twin =
                                // the native transition it implies
                                double x = warpInv(d - activeSectionStart());
                                dtAbs.setText(String.format(Locale.US,
                                    "(Δ %.3f %s)", FvdDesigner.z3(x - pv[0].t),
                                    activeDistNative() ? "m" : "s"));
                            } else {
                                dtAbs.setText(String.format(Locale.US,
                                    "(= %.3f %s abs)",
                                    FvdDesigner.z3(tOff + warpFwd(pv[0].t + d)),
                                    axisDist() ? "m" : "s"));
                            }
                        }
                    });
                }
                if (d == null || pv[0] == null) dtAbs.setText("");
            };
            dtSyncH[0] = dtSync;
            fDtL.field.textProperty().addListener((o, a, b) -> dtSync.run());
            dtSync.run();
            dtAbs.setOnMouseEntered(ev -> dtAbs.setStyle(twinHot));
            dtAbs.setOnMouseExited(ev -> dtAbs.setStyle(twinBase));
            Tooltip tw = new Tooltip(
                "Click to swap which value the box edits.");
            tw.setShowDelay(javafx.util.Duration.millis(300));
            Tooltip.install(dtAbs, tw);
            dtAbs.setOnMouseClicked(ev -> onSurf.accept(() -> {
                List<BezierBaker.Node> ln = active();
                if (idx < 1 || idx >= ln.size()) return;
                BezierBaker.Node nn = ln.get(idx);
                dtAbsMode[0] = !dtAbsMode[0];
                fDtTitle.setText(dtAbsMode[0]
                    ? (axisDist() ? "Absolute position (m)"
                                  : "Absolute time (s)")
                    : (activeDistNative() ? "Transition length (m)"
                                          : "Transition time (s)"));
                // re-seed from the LIVE node, muted — no re-apply
                fDtL.set(dtAbsMode[0]
                    ? activeSectionStart() + warpFwd(nn.t)
                    : nn.t - ln.get(idx - 1).t);
                dtSync.run();   // same-text corner: refill the twin
            }));
            // NATIVE units — a distance-domain section's transitions
            // are meters of track (v16.0)
            grid.addRow(row++, dtTitle, dtBox.node, dtAbs);
        } else if (!dSurf && activePiece == 0 && activeSection == 0
                && activeInfl() == null) {
            // node 0 of the FIRST section: the ride's start speed lives
            // here too (same value as the Coaster-menu spinner). Not on
            // the influence surface — its node 0 is just a delta.
            v0Box = new LiveNum(app.coaster.v0, 1, 1, 100);
            grid.addRow(row++, new Label("Start speed (m/s)"), v0Box.node);
        } else if (!dSurf && activeInfl() == null) {
            // node 0 of any LATER section (v18.2, user spec — his
            // completed sentence): the INITIAL DESIGN SPEED is typed
            // here; a change PINS a deliberate conflict (the D162
            // badge-dialog semantics). The ride entry (piece 0,
            // section 0) keeps its Start-speed row above.
            initBox = new LiveNum(sections().get(activeSection).vDesign(),
                1, 1, 100);
            grid.addRow(row++, new Label("Initial design speed (m/s)"),
                initBox.node);
        }
        // vertical axis is the GLOBAL value, not a delta — except on
        // the influence surface, where the value IS the delta
        LiveNum valBox = new LiveNum(n.val, vStep, -1e9, 1e9);
        // computed twin (v16.15): the CHANGE from the previous node
        // (or, after a D241 click-swap, the absolute value)
        Label valDelta = new Label();
        valDelta.setStyle(twinBase);
        // the value row wears the LANE'S OWN quantity name (v19.13,
        // D242 — user spec; was "Value"), and the click-swap delta
        // mode just prefixes Δ (was "Change in value"). No "(g)"
        // tail on Vertical/Lateral G (D243 — the name carries it)
        String vUnit = laneUnit(activeLane).trim();
        String valTitleBase = (activeInfl() != null ? "Δ " : "")
            + laneQtyName(activeLane)
            + (vUnit.equals("g") ? "" : " (" + vUnit + ")");
        String valTitleDelta = "Δ " + valTitleBase;
        Label valTitle = new Label(valTitleBase);
        if (prev != null) {
            final LiveNum fValL = valBox;
            Runnable vSync = () -> {
                Double v = fValL.parsed();
                BezierBaker.Node[] pv = { null };
                if (v != null) {
                    onSurf.accept(() -> {
                        List<BezierBaker.Node> ln = active();
                        if (idx >= 1 && idx - 1 < ln.size()) {
                            pv[0] = ln.get(idx - 1);
                            valDelta.setText(valDeltaMode[0]
                                ? String.format(Locale.US,
                                    "(= %.3f%s abs)", FvdDesigner.z3(pv[0].val + v), dUnit)
                                : String.format(Locale.US,
                                    "(Δ %+.3f%s)", FvdDesigner.z3(v - pv[0].val), dUnit));
                        }
                    });
                }
                if (v == null || pv[0] == null) valDelta.setText("");
            };
            vSyncH[0] = vSync;
            fValL.field.textProperty().addListener((o, a, b) -> vSync.run());
            vSync.run();
            valDelta.setOnMouseEntered(ev -> valDelta.setStyle(twinHot));
            valDelta.setOnMouseExited(ev -> valDelta.setStyle(twinBase));
            Tooltip tw2 = new Tooltip(
                "Click to swap which value the box edits.");
            tw2.setShowDelay(javafx.util.Duration.millis(300));
            Tooltip.install(valDelta, tw2);
            valDelta.setOnMouseClicked(ev -> onSurf.accept(() -> {
                List<BezierBaker.Node> ln = active();
                if (idx < 1 || idx >= ln.size()) return;
                BezierBaker.Node nn = ln.get(idx);
                valDeltaMode[0] = !valDeltaMode[0];
                valTitle.setText(valDeltaMode[0] ? valTitleDelta
                                                 : valTitleBase);
                valBox.set(valDeltaMode[0]
                    ? nn.val - ln.get(idx - 1).val : nn.val);
                vSync.run();
            }));
        }
        grid.addRow(row++, valTitle, valBox.node, valDelta);
        // HANDLE SLOPES (v19.2, D228 — user spec): the handles' dv/dx
        // in lane units per axis unit, typed directly; a SMOOTH node
        // (both handles collinear) shows ONE slope box driving both,
        // a broken node one per handle. Slope edits keep the handle
        // LENGTH (fraction) — dv := slope × the handle's x run.
        String slopeUnit = laneUnit(activeLane).trim() + "/"
            + (activeDistNative() ? "m" : "s");
        boolean oneSlope = prev != null && hasOut && n.smooth;
        double inSl = 0, outSl = 0;
        if (prev != null) {
            double dtIn = BezierBaker.effInDt(prev, n);
            inSl = Math.abs(dtIn) < 1e-12 ? 0 : n.inDv / dtIn;
        }
        if (hasOut) {
            double dtOut = BezierBaker.effOutDt(n, lane.get(idx + 1));
            outSl = Math.abs(dtOut) < 1e-12 ? 0 : n.outDv / dtOut;
        }
        LiveNum inSlopeBox = null, outSlopeBox = null, slopeBox = null;
        // ONE COLUMN throughout (v19.11, D240 — user spec): a broken
        // node's slope boxes sit directly BELOW their handle-length
        // rows ("In slope"/"Out slope"), never beside them; a
        // section-EDGE node's single slope keeps the D239 aligned
        // "Handle slope" row.
        if (prev != null) {
            inBox = new LiveNum(n.inFrac, 0.05, MIN_FRAC, BezierBaker.MAX_FRAC)
                .easeStops(EASE_STOP_VALS, EASE_STOP_NAMES);   // D240
            grid.addRow(row++, new Label("In handle length"), inBox.node);
            if (!oneSlope) {
                inSlopeBox = new LiveNum(inSl, vStep, -1e9, 1e9);
                grid.addRow(row++, new Label(hasOut
                        ? "In slope (" + slopeUnit + ")"
                        : "Handle slope (" + slopeUnit + ")"),
                    inSlopeBox.node);
            }
        }
        if (hasOut) {
            outBox = new LiveNum(n.outFrac, 0.05, MIN_FRAC, BezierBaker.MAX_FRAC)
                .easeStops(EASE_STOP_VALS, EASE_STOP_NAMES);   // D240
            grid.addRow(row++, new Label("Out handle length"), outBox.node);
            if (!oneSlope) {
                outSlopeBox = new LiveNum(outSl, vStep, -1e9, 1e9);
                grid.addRow(row++, new Label(prev != null
                        ? "Out slope (" + slopeUnit + ")"
                        : "Handle slope (" + slopeUnit + ")"),
                    outSlopeBox.node);
            }
        }
        if (oneSlope) {
            slopeBox = new LiveNum(outSl, vStep, -1e9, 1e9);
            grid.addRow(row++, new Label("Handle slope (" + slopeUnit + ")"),
                slopeBox.node);
        }
        final LiveNum fInSl = inSlopeBox, fOutSl = outSlopeBox, fSl = slopeBox;
        // slope sinks: dv := slope × run (fraction untouched)
        java.util.function.BiConsumer<Double, Boolean> writeInSlope = (s, both) ->
            onSurf.accept(() -> {
                List<BezierBaker.Node> ln2 = active();
                if (idx < 1 || idx >= ln2.size()) return;
                BezierBaker.Node nn = ln2.get(idx), pv = ln2.get(idx - 1);
                double dtIn = BezierBaker.effInDt(pv, nn);
                double ndv = s * dtIn;
                boolean chIn = ndv != nn.inDv;
                boolean chOut = false;
                double ndvOut = 0;
                if (both && idx + 1 < ln2.size()) {
                    ndvOut = s * BezierBaker.effOutDt(nn, ln2.get(idx + 1));
                    chOut = ndvOut != nn.outDv;
                }
                if (!chIn && !chOut) return;
                liveUndo.accept("slope");
                if (chIn) nn.inDv = ndv;
                if (chOut) nn.outDv = ndvOut;
                redraw();
                app.rebuildTrack();
            });
        if (fInSl != null) fInSl.onValue = v -> writeInSlope.accept(v, false);
        if (fSl != null) fSl.onValue = v -> writeInSlope.accept(v, true);
        if (fOutSl != null) {
            fOutSl.onValue = v -> onSurf.accept(() -> {
                List<BezierBaker.Node> ln2 = active();
                if (idx + 1 >= ln2.size()) return;
                BezierBaker.Node nn = ln2.get(idx);
                double ndv = v * BezierBaker.effOutDt(nn, ln2.get(idx + 1));
                if (ndv == nn.outDv) return;
                liveUndo.accept("slope");
                nn.outDv = ndv;
                redraw();
                app.rebuildTrack();
            });
        }

        // ---- the live sinks ----
        final LiveNum fDt = dtBox, fVal = valBox, fV0 = v0Box;
        final LiveNum fIn = inBox, fOut = outBox, fInit = initBox;
        // position/value: through moveNode (ripple toggles, level runs,
        // slope-keeping dv rescale — the drag path)
        Runnable applyPos = () -> onSurf.accept(() -> {
            BezierBaker.Node ln = liveNode.get();
            if (ln == null) return;
            List<BezierBaker.Node> lane2 = active();
            Double v = fVal.parsed();
            double val;   // the D241 swap: a delta-mode box holds the
            if (v == null) val = ln.val;   // CHANGE from the previous
            else if (valDeltaMode[0] && idx > 0) {
                val = lane2.get(idx - 1).val + v;
            } else val = v;
            if (idx > 0 && fDt != null) {
                Double d = fDt.parsed();
                double dt;   // abs mode: box = display-axis absolute
                if (d == null) dt = ln.t - lane2.get(idx - 1).t;
                else if (dtAbsMode[0]) {
                    dt = Math.max(minGap(),
                        warpInv(d - activeSectionStart())
                            - lane2.get(idx - 1).t);
                } else dt = Math.max(minGap(), d);
                if (Math.abs(lane2.get(idx - 1).t + dt - ln.t) < 1e-12
                        && val == ln.val) return;
                liveUndo.accept("pos");
                moveNode(idx, lane2.get(idx - 1).t + dt, val);
            } else {
                if (val == ln.val) return;
                liveUndo.accept("pos");
                moveNode(idx, ln.t, val);
            }
        });
        if (fDt != null) fDt.onValue = v -> applyPos.run();
        fVal.onValue = v -> applyPos.run();
        // typed fractions SLIDE the knob along its current line (S
        // semantics): dv scales with the length, angle preserved
        if (fIn != null) {
            fIn.onValue = v -> onSurf.accept(() -> {
                BezierBaker.Node ln = liveNode.get();
                if (ln == null) return;
                double nf = FvdDesigner.clamp(v, MIN_FRAC, BezierBaker.MAX_FRAC);
                if (nf == ln.inFrac) return;
                liveUndo.accept("in");
                if (ln.inFrac > 1e-9) ln.inDv *= nf / ln.inFrac;
                ln.inFrac = nf;
                redraw();
                app.rebuildTrack();
            });
        }
        if (fOut != null) {
            fOut.onValue = v -> onSurf.accept(() -> {
                BezierBaker.Node ln = liveNode.get();
                if (ln == null) return;
                double nf = FvdDesigner.clamp(v, MIN_FRAC, BezierBaker.MAX_FRAC);
                if (nf == ln.outFrac) return;
                liveUndo.accept("out");
                if (ln.outFrac > 1e-9) ln.outDv *= nf / ln.outFrac;
                ln.outFrac = nf;
                redraw();
                app.rebuildTrack();
            });
        }
        if (fV0 != null) {
            // through the spinner so the menu shows it and the track
            // rebuilds (start speed is per-coaster; the spinner's own
            // wiring pushes the document undo step)
            fV0.onValue = v -> app.startSpeedSpinner.getValueFactory()
                .setValue(FvdDesigner.clamp(v, 1, 100));
        }
        if (fInit != null) {
            final int dlgSec = activeSection;
            fInit.onValue = v -> {
                if (activePiece != dPiece || dlgSec >= sections().size()) {
                    return;
                }
                // a changed initial design speed PINS (v18.2 — the
                // D162 deliberate-conflict semantics; the reconcile
                // leaves it alone, B lines the real graphs up)
                Section s0 = sections().get(dlgSec);
                double nv = FvdDesigner.clamp(v, 1, 100);
                if (Math.abs(nv - s0.vDesign()) <= 1e-9) return;
                liveUndo.accept("init");
                s0.vDesign(nv);
                s0.vPinned(true);
                app.markDirty();
                app.rebuildTrack();
                redraw();
            };
        }

        // section-edge nodes: the boundary return lives here too (same
        // one-time value + slope move as pressing B on the node)
        boolean edgeStart = !dSurf && idx == 0 && activeSection > 0;
        boolean edgeEnd = !dSurf && idx == lane.size() - 1 && idx > 0
            && activeSection < sections().size() - 1;
        if (edgeStart || edgeEnd) {
            Button match = new Button("Match boundary (value + slope)");
            match.setOnAction(ev -> {
                matchBoundary(idx);
                dlg.close();
            });
            grid.add(match, 0, row++, 2, 1);
        }

        // (the Close button is GONE — v19.10, D239, user spec: the
        // dialog is live, closing is the X / Esc / Enter; the grid
        // ends at the last row, its symmetric 14 px padding is the
        // whole bottom margin)

        // UNDO RESYNC (D227) + LIVE SYNC (v19.14, D243 — user spec:
        // the boxes and twins follow ANY outside change in real
        // time): the same re-read runs after every undo restore
        // (force = true, typed text is stale) AND after every canvas
        // redraw (force = false — a FOCUSED box is being typed in
        // and is never overwritten; everything else re-reads muted,
        // and equal text fires no events, so the steady state is
        // free). The hooks die with the dialog.
        final LiveNum fDtR = dtBox, fValR = valBox, fInR = inBox,
            fOutR = outBox, fV0R = v0Box, fInitR = initBox;
        final int rSec = activeSection;
        java.util.function.Consumer<Boolean> resync = force ->
            onSurf.accept(() -> {
            List<BezierBaker.Node> ln2 = active();
            if (idx >= ln2.size()) return;
            BezierBaker.Node nn = ln2.get(idx);
            BezierBaker.Node pv = idx > 0 ? ln2.get(idx - 1) : null;
            BezierBaker.Node nx = idx + 1 < ln2.size() ? ln2.get(idx + 1) : null;
            java.util.function.BiConsumer<LiveNum, Double> put =
                (b, v2) -> {
                    if (b != null && (force || !b.field.isFocused())) {
                        b.set(v2);
                    }
                };
            if (pv != null) {
                put.accept(fDtR, dtAbsMode[0]
                    ? activeSectionStart() + warpFwd(nn.t)
                    : nn.t - pv.t);
            }
            put.accept(fValR, valDeltaMode[0] && pv != null
                ? nn.val - pv.val : nn.val);
            // same-text corner: a change to only the PREVIOUS node
            // fires no box event — refill the twins directly
            if (dtSyncH[0] != null) dtSyncH[0].run();
            if (vSyncH[0] != null) vSyncH[0].run();
            put.accept(fInR, nn.inFrac);
            put.accept(fOutR, nn.outFrac);
            if (pv != null) {
                double dtIn = BezierBaker.effInDt(pv, nn);
                double s = Math.abs(dtIn) < 1e-12 ? 0 : nn.inDv / dtIn;
                put.accept(fInSl, s);
                put.accept(fSl, s);
            }
            if (nx != null && fOutSl != null) {
                double dtOut = BezierBaker.effOutDt(nn, nx);
                put.accept(fOutSl,
                    Math.abs(dtOut) < 1e-12 ? 0 : nn.outDv / dtOut);
            }
            put.accept(fV0R, app.coaster.v0);
            if (fInitR != null && rSec < sections().size()) {
                put.accept(fInitR, sections().get(rSec).vDesign());
            }
        });
        Runnable refresh = () -> {
            undoKey[0] = null;   // a restore ends any coalescing burst
            resync.accept(true);
        };
        Runnable live = () -> resync.accept(false);
        docRestoreHooks.add(refresh);
        redrawHooks.add(live);
        // also a MODEL hook (D247): piece-gizmo drags and Track Piece
        // / friction spinner edits rebuild WITHOUT a redraw — the
        // boxes follow those live too (onSurf restores the surface,
        // so running outside redraw is safe)
        app.modelHooks.add(live);
        dlg.setOnHidden(ev -> {
            docRestoreHooks.remove(refresh);
            redrawHooks.remove(live);
            app.modelHooks.remove(live);
        });

        dlg.setScene(new Scene(grid));
        // Esc / Enter still close (the removed Close button carried
        // default+cancel — D239); a HANDLER, not a filter, so the
        // undo/ride key wiring below sees its keys first
        dlg.getScene().addEventHandler(
            javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE
                        || e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    dlg.close();
                }
            });
        // PLACED like the three placement windows since v19.19 (D248
        // — "like the node windows" holds both ways): bottom edge just
        // above the graph canvas, left at the node's on-screen x (a
        // pending gizmo anchor wins), clamped into the screen
        double ax = nodeScreenX(idx);
        if (Double.isNaN(ax)) {
            double[] cb = canvasScreenBox();
            ax = cb != null ? cb[0] + MARGIN_LEFT : 0;
        }
        LiveWindow.place(app, dlg, grid, ax);
        dlg.show();
        app.installDocUndoKeys(dlg.getScene());   // D227: global undo + ride keys
    }

    /** A LIVE number box (D225): TextField + ▲/▼ steppers (HOLD =
     *  steady repeat since v19.11, D240) + mouse wheel, all on the
     *  global spinner conventions (Shift = tenth steps, Ctrl = snap
     *  to round multiples — swapped in D241). TEXT-DRIVEN: every parseable text
     *  change calls onValue (clamped to [min, max] for the steppers;
     *  typed values pass through as typed and the sink clamps); a
     *  blank/unparseable box calls nothing — the value holds.
     *  easeStops(…) (D240, the handle-fraction boxes): stepping
     *  always STOPS at any named ease fraction lying between the
     *  current value and the step target; landing on one remembers
     *  the pre-stop grid, so the NEXT step resumes it (0.31 → 0.333
     *  → 0.36); a box opened/typed ONTO an ease value steps to round
     *  multiples instead; while ON one the field wears a soft blue
     *  outline and a tag names it. */
    private static final class LiveNum {
        final TextField field = new TextField();
        final HBox node;
        final double step, min, max;
        java.util.function.DoubleConsumer onValue;
        private boolean muted;
        // ease-stop state (D240) — null = feature off
        private double[] stops;
        private String[] stopNames;
        private Label stopTag;
        private double easeAnchor = Double.NaN;
        private boolean stepping;
        private final FvdDesigner.HoldRepeat repUp =
            new FvdDesigner.HoldRepeat();
        private final FvdDesigner.HoldRepeat repDn =
            new FvdDesigner.HoldRepeat();

        LiveNum(double init, double step, double min, double max) {
            this.step = step;
            this.min = min;
            this.max = max;
            field.setPrefColumnCount(7);
            // numeric-only entry (v18.1, user spec — every number box
            // accepts digits, sign and period alone)
            field.setTextFormatter(FvdDesigner.signedNumFormatter());
            field.setText(fmt(init));
            Button up = stepBtn("▲"), dn = stepBtn("▼");
            // HOLD-TO-REPEAT (v19.11, D240 — the global rule): press
            // arms the shared repeat at the native cadence with the
            // press-time modifiers; release stops it. (Mouse
            // handlers, not ActionEvent — it carries no modifiers.)
            wireHold(up, repUp, 1);
            wireHold(dn, repDn, -1);
            VBox arrows = new VBox(up, dn);
            arrows.setSpacing(0);
            node = new HBox(2, field, arrows);
            node.setAlignment(Pos.CENTER_LEFT);
            field.setOnScroll(e -> {
                double d = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
                if (d == 0) return;
                // Shift = tenth, Ctrl = snap since D241 (swapped)
                modClick(d > 0 ? 1 : -1, e.isShiftDown(),
                    e.isControlDown());
                e.consume();
            });
            field.textProperty().addListener((o, a, b) -> {
                if (!muted && !stepping) easeAnchor = Double.NaN;
                syncEaseTag();
                if (muted || onValue == null) return;
                Double v = parsed();
                if (v != null) onValue.accept(v);
            });
        }

        private void wireHold(Button b, FvdDesigner.HoldRepeat rep,
                              int dir) {
            b.setOnMousePressed(e -> {
                // Shift = tenth, Ctrl = snap since D241 (swapped)
                final boolean slow = e.isShiftDown();
                final boolean snap = e.isControlDown();
                rep.start(() -> modClick(dir, slow, snap));
            });
            b.setOnMouseReleased(e -> rep.stop());
        }

        /** Opt into the ease-fraction stops (the handle boxes). */
        LiveNum easeStops(double[] vals, String[] names) {
            stops = vals;
            stopNames = names;
            stopTag = new Label();
            stopTag.setStyle("-fx-text-fill: rgb(90,150,230);");
            // UNMANAGED overlay (v19.13, D242 — the D241 constant
            // width still held a column open and shoved the grid's
            // readout twins right, his screenshot): the tag takes NO
            // layout space at all — it floats just past the arrows
            // over the empty gutter (handle rows have no column-2
            // twin), so nothing ever moves when it appears
            stopTag.setManaged(false);
            stopTag.setMouseTransparent(true);
            node.getChildren().add(stopTag);
            Runnable place = () -> {
                // an UNMANAGED node is never resized by its parent —
                // without this autosize it stays 0×0 and renders
                // nothing (the D242 vanished-tag bug)
                stopTag.autosize();
                double x = 0;
                for (javafx.scene.Node c : node.getChildren()) {
                    if (c != stopTag && c.isManaged()) {
                        x = Math.max(x, c.getBoundsInParent().getMaxX());
                    }
                }
                stopTag.relocate(x + 5,
                    (node.getLayoutBounds().getHeight()
                        - stopTag.getHeight()) / 2);
            };
            node.layoutBoundsProperty().addListener((o, a, b) -> place.run());
            stopTag.textProperty().addListener((o, a, b) -> place.run());
            // the layoutBounds listener fires BEFORE the children are
            // positioned on the first pass — the tag sat INSIDE the
            // field over the digits until something re-placed it
            // (D244, his report): re-place when the arrows actually
            // land, and once more a pulse after the window shows
            for (javafx.scene.Node c : node.getChildren()) {
                if (c != stopTag) {
                    c.boundsInParentProperty()
                        .addListener((o, a, b) -> place.run());
                }
            }
            javafx.application.Platform.runLater(place);
            // focus must stay visible ON a stop — the ring style
            // replaces the skin's focus ring (D243)
            field.focusedProperty().addListener((o, a, b) -> syncEaseTag());
            place.run();
            syncEaseTag();
            return this;
        }

        private static Button stepBtn(String glyph) {
            Button b = new Button(glyph);
            b.setFocusTraversable(false);
            b.setStyle("-fx-font-size: 6px; -fx-padding: 0 4 0 4;");
            b.setMinHeight(11);
            b.setPrefHeight(11);
            b.setMaxHeight(11);
            return b;
        }

        static String fmt(double v) {
            // z3 (D244/D245): −0.0 and sub-1e-6 dust display as
            // plain zero; larger dust keeps its sign ("-0.000" =
            // honestly a little negative, user spec)
            return String.format(Locale.US, "%.3f", FvdDesigner.z3(v));
        }

        /** The box's number, or null while blank/unparseable. */
        Double parsed() {
            try {
                String t = field.getText().trim();
                if (t.isEmpty() || t.equals("-") || t.equals(".")
                        || t.equals("-.")) {
                    return null;
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        /** One global-convention step (Shift = tenth, Ctrl = snap to
         *  the next round multiple of the possibly Shift-scaled step
         *  — swapped in D241) — with the D240 ease-stop rules where
         *  enabled. */
        void modClick(int dir, boolean slow, boolean snap) {
            double q = step * (slow ? 0.1 : 1);
            Double cur = parsed();
            double base = cur != null ? cur : 0;
            int atStop = stopAt(base);
            double target;
            if (atStop >= 0 && !snap && !Double.isNaN(easeAnchor)) {
                // ON a stop we stepped onto: resume the pre-stop
                // grid {anchor + k·q} — the adjacent point in the
                // travel direction (down returns exactly to the
                // pre-stop value)
                double rel = (base - easeAnchor) / q;
                double kk = dir > 0 ? Math.floor(rel + 1e-9) + 1
                                    : Math.ceil(rel - 1e-9) - 1;
                target = easeAnchor + kk * q;
            } else if (atStop >= 0 || snap) {
                // fresh on a stop (opened/typed there), or the snap
                // modifier: round multiples of the quantum
                target = dir > 0
                    ? (Math.floor(base / q + 1e-9) + 1) * q
                    : (Math.ceil(base / q - 1e-9) - 1) * q;
            } else {
                target = base + dir * q;
            }
            // a stop strictly between always intercepts (D240)
            int hit = interceptStop(base, target);
            double v;
            if (hit >= 0) {
                v = stops[hit];
                if (atStop < 0) easeAnchor = base;   // remember the grid
                // (stop-to-stop keeps the original anchor)
            } else {
                if (atStop >= 0) easeAnchor = Double.NaN;  // back on grid
                v = Math.round(target / (q * 0.001)) * (q * 0.001);
            }
            v = FvdDesigner.clamp(v, min, max);
            stepping = true;
            try {
                // standard 3 decimals everywhere (D241 — stops
                // re-detect through the ±5e-4 tolerance)
                field.setText(fmt(v));
            } finally {
                stepping = false;
            }
            field.positionCaret(field.getText().length());
        }

        /** Index of the ease stop at v (±5e-4 — half the 3-decimal
         *  display quantum), or −1. */
        private int stopAt(double v) {
            if (stops == null) return -1;
            for (int i = 0; i < stops.length; i++) {
                if (Math.abs(v - stops[i]) <= 5e-4) return i;
            }
            return -1;
        }

        /** The stop nearest `from` lying STRICTLY between from and
         *  to, or −1. Stops within the display tolerance of `from`
         *  never intercept (v19.12, D241 — the freeze bug: the
         *  3-decimal rendering of a landed stop sits a hair off the
         *  exact fraction, and the exact value then "lay between"
         *  the box and the target forever — a stop is an inserted
         *  snapping point, never a wall). */
        private int interceptStop(double from, double to) {
            if (stops == null) return -1;
            double lo = Math.min(from, to), hi = Math.max(from, to);
            int best = -1;
            for (int i = 0; i < stops.length; i++) {
                if (Math.abs(stops[i] - from) <= 5e-4) continue;
                if (stops[i] > lo + 1e-9 && stops[i] < hi - 1e-9
                        && (best < 0 || Math.abs(stops[i] - from)
                            < Math.abs(stops[best] - from))) {
                    best = i;
                }
            }
            return best;
        }

        /** Blue outline + name tag while the value sits ON an ease
         *  stop (D240) — however it got there, typing included. The
         *  ring is LAYERED BACKGROUNDS, not a border (v19.13, D242 —
         *  a CSS border joins the control's layout insets, so the
         *  D240 border grew the field 4 px on every stop and shoved
         *  its arrows, his screenshot; backgrounds never touch
         *  layout — the D233 highlight-by-background rule). */
        private void syncEaseTag() {
            if (stopTag == null) return;
            Double v = parsed();
            int i = v == null ? -1 : stopAt(v);
            stopTag.setText(i >= 0 ? stopNames[i] : "");
            if (i >= 0) {
                // 1 px ring (half the D240 thickness, user spec) —
                // and a FOCUSED box keeps a visible focus ring: an
                // extra -fx-focus-color layer OUTSIDE the bounds
                // (negative insets never touch layout), since this
                // style replaces the skin's own focus visuals
                boolean foc = field.isFocused();
                field.setStyle("-fx-background-color: "
                    + (foc ? "-fx-focus-color, " : "")
                    + "rgba(90,150,230,0.85),"
                    + " -fx-control-inner-background;"
                    + " -fx-background-insets: "
                    + (foc ? "-1.4, " : "") + "0, 1;"
                    + " -fx-background-radius: "
                    + (foc ? "5, " : "") + "3, 2;");
            } else {
                field.setStyle("");
            }
        }

        /** Programmatic set that does NOT apply. Equal text is a
         *  full no-op (D243 — the live resync runs per redraw, and
         *  an unconditional write wiped the ease-resume anchor
         *  after every step). */
        void set(double v) {
            String s = fmt(v);
            if (s.equals(field.getText())) return;
            muted = true;
            try {
                field.setText(s);
                easeAnchor = Double.NaN;   // a real change = fresh box
            } finally {
                muted = false;
            }
        }
    }

    /** Auto-fit each lane's value axis to the curve within the current
     *  time window: at least the default range, expanded and rounded
     *  outward to a nice step when the data pokes beyond it. Frozen
     *  during drags so the scale doesn't shift under the cursor. */
    private void updateScale() {
        if (dragNodeIdx >= 0 || dragHandleIdx >= 0
                || elDragBoundary >= 0 || elDragSmooth >= 0
                || bridgeDragGap) return;
        double t0 = viewStart(), t1 = viewEnd();
        if (activeSc() != null || activeBridge() != null) {
            // no authored lanes (s/c widget or frozen bridge): fit the
            // (force-view) scales to the DERIVED curves on show
            double dur = sectionDur(activeSection);
            for (int L = 0; L < LANES; L++) {
                double lo = defMin(L), hi = defMax(L);
                if (app.resultRates != null && app.lastSamples != null) {
                    for (int i = 0; i < app.resultRates.length; i++) {
                        double a = gAxis(i);
                        double trim = axisTrim(i);
                        if (a < Math.max(tOff + trim, t0)
                                || a > Math.min(tOff + dur - trim, t1)) continue;
                        double v = derivedAt(i, L);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                }
                if (lo < defMin(L) || hi > defMax(L)) {
                    double pad = (hi - lo) * 0.05;
                    double step = niceStep((hi - lo) / 6);
                    curMin[L] = Math.floor((lo - pad) / step) * step;
                    curMax[L] = Math.ceil((hi + pad) / step) * step;
                } else {
                    curMin[L] = defMin(L);
                    curMax[L] = defMax(L);
                }
            }
            updateResScale();
            return;
        }
        LaneSection infl = activeInfl();
        for (int L = 0; L < LANES; L++) {
            double lo = defMin(L), hi = defMax(L);
            // the fit reads DISPLAYED values (v16.9, D121): a warped
            // lane's scale must cover the other-units curve so the
            // °/m defaults hold and the data fits (scale only — the
            // influence offset is fitted via influenceSum below)
            for (double[] p : BezierBaker.polyline(app.lanes.get(L))) {
                double a = tOff + warpFwd(p[0]);   // native → axis (v16.0)
                if (a < t0 || a > t1) continue;
                double dv = p[1] * rateViewScale(L, p[0]);
                lo = Math.min(lo, dv);
                hi = Math.max(hi, dv);
            }
            double xA = warpInv(t0 - tOff), xB = warpInv(t1 - tOff);
            double eA = BezierBaker.evalAt(app.lanes.get(L), xA)
                * rateViewScale(L, xA);
            double eB = BezierBaker.evalAt(app.lanes.get(L), xB)
                * rateViewScale(L, xB);
            lo = Math.min(lo, Math.min(eA, eB));
            hi = Math.max(hi, Math.max(eA, eB));
            if (infl != null) {
                // the influence surface also shows the RESULTING
                // curves (dotted behind, or as the ridden curve in
                // Resulting view) — fit them like a lane (v15.0)
                for (double[] p : influenceSum(infl, L)) {
                    if (tOff + p[0] < t0 || tOff + p[0] > t1) continue;
                    lo = Math.min(lo, p[1]);
                    hi = Math.max(hi, p[1]);
                }
            }
            // expanded-handle view: include the selected node's knobs
            if (handleViewExpand && !spFocus && L == activeLane
                    && selIdx >= 0 && selIdx < app.lanes.get(L).size()) {
                BezierBaker.Node sn = app.lanes.get(L).get(selIdx);
                double sf = rateViewScale(L, sn.t);
                lo = Math.min(lo, Math.min(sn.val + sn.outDv,
                    sn.val + sn.inDv) * sf);
                hi = Math.max(hi, Math.max(sn.val + sn.outDv,
                    sn.val + sn.inDv) * sf);
            }
            if (lo < defMin(L) || hi > defMax(L)) {
                double pad = (hi - lo) * 0.05;   // "slightly beyond"
                double step = niceStep((hi - lo) / 6);
                curMin[L] = Math.floor((lo - pad) / step) * step;
                curMax[L] = Math.ceil((hi + pad) / step) * step;
            } else {
                curMin[L] = defMin(L);
                curMax[L] = defMax(L);
            }
        }
        updateResScale();
    }

    /** Fit the shared RESULTING scale (right axis) to the enabled
     *  overlay curves — over the WHOLE ride within the visible time
     *  window (v10.8: overlays span every section, fitted like a lane
     *  of the edited graph): rates default ±60 °/s, felt forces
     *  default −1..4 g, expanded 1-2-5-rounded like the lane scales.
     *  Frozen during drags with the rest of updateScale. */
    private void updateResScale() {
        resAxisOn = app.resultRates != null && app.lastSamples != null
            && app.resultRates.length > 0
            && (app.settings.resPitch() || app.settings.resYaw()
                || resCompOn(3));
        if (!resAxisOn) return;
        boolean geo = activeGeo();
        // defaults MATCH the normal lane scales (v17.7, D144): felt
        // −1..4 like Gv, rates ±60 like geo lanes — ±5 in °/m.
        // PER-QUANTITY normalization (v18.42, D214): the roll/bank
        // overlay joins the shared fit ONLY when its units match
        // comps 1/2 (force view + rate roll); over geo sections (g
        // vs °/s) and for absolute bank (°) it fits its OWN pair —
        // a −112 °/s roll rate must never stretch the g axis.
        double lo = geo ? -1
            : resYpAbsOn() ? -90 : resRatePM() ? -5 : -60;
        double hi = geo ? 4
            : resYpAbsOn() ? 90 : resRatePM() ? 5 : 60;
        boolean ownRoll = resRollOwnScale();
        boolean resAbs = app.settings.resRollAbs() && app.settings.resRollG();
        double lo3 = resAbs ? -90 : resRatePM() ? -5 : -60;
        double hi3 = resAbs ? 90 : resRatePM() ? 5 : 60;
        double t0 = viewStart(), t1 = viewEnd();
        List<Double> seams = sectionBoundaries();
        double rT0 = runT0(), rT1 = rT0 + runDur();   // run clamp (D85)
        for (int i = 0; i < app.resultRates.length; i++) {
            double a = gAxis(i);
            double trim = axisTrim(i);
            if (a < t0 || a > t1) continue;
            if (a < rT0 + trim || a > rT1 - trim
                    || nearSeam(a, seams, trim)) continue;
            if (app.settings.resPitch()) {
                lo = Math.min(lo, resValueAt(i, 1));
                hi = Math.max(hi, resValueAt(i, 1));
            }
            if (app.settings.resYaw()) {
                lo = Math.min(lo, resValueAt(i, 2));
                hi = Math.max(hi, resValueAt(i, 2));
            }
            if (resCompOn(3)) {
                double v = resValueAt(i, 3);
                if (ownRoll) {
                    lo3 = Math.min(lo3, v);
                    hi3 = Math.max(hi3, v);
                } else {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
            }
        }
        double pad = (hi - lo) * 0.05;
        double step = niceStep((hi - lo) / 6);
        resLo = Math.floor((lo - pad) / step) * step;
        resHi = Math.ceil((hi + pad) / step) * step;
        double pad3 = (hi3 - lo3) * 0.05;
        double step3 = niceStep((hi3 - lo3) / 6);
        resLo3 = Math.floor((lo3 - pad3) / step3) * step3;
        resHi3 = Math.ceil((hi3 + pad3) / step3) * step3;
    }

    /** The roll/bank overlay normalizes on its OWN scale whenever its
     *  units differ from comps 1/2 (v18.42, D214): geo view (g vs
     *  rates), absolute bank against rate comps — and, since v19.9
     *  (D238), a RATE roll against ABSOLUTE elevation/heading comps
     *  (the previously unreachable inverse). Matching units share —
     *  all rates, or all degrees (both behaviors absolute). */
    private boolean resRollOwnScale() {
        boolean rollAbs = app.settings.resRollAbs()
            && app.settings.resRollG();
        return resCompOn(3)
            && (activeGeo() || rollAbs != resYpAbsOn());
    }

    /** Interior section boundaries in AXIS coordinates across the
     *  whole RUN — the overlays skip a trim band around each
     *  (bodyRates smear discontinuities). */
    private List<Double> sectionBoundaries() {
        List<Double> out = new ArrayList<>();
        List<RunSec> rl = runLayout();
        for (int i = 1; i < rl.size(); i++) out.add(rl.get(i).t0());
        return out;
    }

    /** Within `trim` axis units of a seam? (SEAM_TRIM on the time
     *  axis; speed-scaled by the caller on the meters axis, v16.0.) */
    private static boolean nearSeam(double a, List<Double> seams, double trim) {
        for (double s : seams) {
            if (Math.abs(a - s) < trim) return true;
        }
        return false;
    }

    /** The overlay quantity at compiled sample i: resulting pitch/yaw
     *  rates over a force section, felt Gv/Gl over a geo one. While
     *  the INFLUENCE surface is edited the overlays show the TRUE
     *  values of the other mode (v15.0, D98 — user spec: "the
     *  resulting graphs drop down should display the true values"). */
    private double resValueAt(int i, int comp) {
        boolean tru = activeInfl() != null && trueArraysReady();
        if (activeGeo() && comp <= 2) {
            if (tru) return app.trueFelt[i][comp - 1];
            return comp == 1 ? app.lastSamples.get(i).Gv() : app.lastSamples.get(i).Gl();
        }
        // rate channels (force view comps 1-3), presented per the
        // RESULTING GRAPH SETTINGS (v17.6, D143): global axes read
        // the analytic world rates, °/m divides by the speed, roll
        // behavior = absolute shows the bank angle itself
        double[] br = tru ? app.trueRates[i] : app.resultRates[i];
        if (comp == 3 && app.settings.resRollAbs()
                && app.settings.resRollG()) {
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
            return Double.isNaN(b) ? 0 : Math.toDegrees(b);
        }
        // ABSOLUTE yaw/pitch behavior (v19.9, D238): comps 1/2 show
        // the elevation / piece-relative heading ANGLES — the
        // abs-bank branch's twin (force view only; the geo view's
        // comps are felt G and returned above)
        if (comp <= 2 && resYpAbsOn()) {
            FVDIntegrator.Sample sm = app.lastSamples.get(i);
            if (comp == 1) {
                return Math.toDegrees(FVDIntegrator.elevationOfT(sm.T()));
            }
            return pieceRelHeadingDeg(sm);
        }
        double r;
        boolean world = comp == 3 ? app.settings.resRollG()
                                  : app.settings.resYpG();
        if (world) {
            double[] wr = Coaster.worldRates(app.lastSamples.get(i),
                br[1], br[2], br[3]);
            r = wr[comp - 1];
            if (Double.isNaN(r)) r = 0;   // vertical hold-off
        } else {
            r = br[comp];
        }
        if (resRatePM()) {
            r /= Math.max(0.5, tru ? app.trueV[i]
                : app.lastSamples.get(i).v());
        }
        return r;
    }

    /** Do the resulting rate overlays show °/m? The RESULTING GRAPH
     *  SETTINGS govern since v17.6 (D143 — the pre-v17.6 automatic
     *  follow of a °/m section's units is superseded). */
    private boolean resRatePM() {
        return app.settings.resRateM();
    }

    /** Do overlay comps 1/2 show ABSOLUTE elevation/heading angles
     *  (v19.9, D238)? Force view + global yaw/pitch axis only — the
     *  settings interlock enforces the pairing, this guards stale
     *  properties. (No redundancy gray exists for comps 1/2: over a
     *  geo section the overlays show felt G, so they can never
     *  duplicate an abs section's own angle lanes.) */
    private boolean resYpAbsOn() {
        return !activeGeo() && app.settings.resYpAbs()
            && app.settings.resYpG();
    }

    /** Is resulting overlay comp enabled (v17.6): 1/2 = the classic
     *  pitch/yaw-or-felt checks; 3 = the roll/bank overlay — BOTH
     *  views since v17.7 (D144), grayed only while it would
     *  duplicate the active section's own roll graph exactly. */
    private boolean resCompOn(int comp) {
        if (comp == 1) return app.settings.resPitch();
        if (comp == 2) return app.settings.resYaw();
        return app.settings.resRoll() && !resRollRedundant();
    }

    /** Would the resulting roll overlay duplicate the active
     *  section's own REAL roll graph exactly (v17.7, D144)? True
     *  when the section's roll representation — rate units, roll
     *  axis, roll behavior — matches the resulting settings; the
     *  check grays then (its state persists across sections). */
    boolean resRollRedundant() {
        if (!(sections().get(activeSection) instanceof LaneSection ls)) {
            return false;
        }
        boolean secAbs = ls.rollAbsolute && ls.rollGlobal;
        boolean resAbs = app.settings.resRollAbs()
            && app.settings.resRollG();
        return ls.ratePerMeter == app.settings.resRateM()
            && ls.rollGlobal == app.settings.resRollG()
            && secAbs == resAbs;
    }

    /** The RESULTING GRAPH SETTINGS dialog (v17.6, D143 — user
     *  spec): how the overlays present — rate units, roll axis,
     *  yaw/pitch axis (geo-resulting only), roll behavior — in the
     *  canonical row order. Display-only, applies live. */
    void showResultingSettings() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Resulting graph settings");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int row = 0;
        Runnable applyLive = () -> {
            app.syncResChecks();
            updateResScale();
            redraw();
        };

        ToggleGroup ug = new ToggleGroup();
        RadioButton uSec = new RadioButton("Per second (°/s)");
        RadioButton uMet = new RadioButton("Per meter (°/m)");
        uSec.setToggleGroup(ug);
        uMet.setToggleGroup(ug);
        (app.settings.resRateM() ? uMet : uSec).setSelected(true);
        ug.selectedToggleProperty().addListener((o, w, n2) -> {
            app.settings.setResRateM(uMet.isSelected());
            applyLive.run();
        });
        grid.addRow(row++, new Label("Rate units"),
            new HBox(10, uSec, uMet));

        ToggleGroup yg = new ToggleGroup();
        RadioButton yLocal = new RadioButton("Local");
        RadioButton yGlobal = new RadioButton("Global (heading/elevation)");
        yLocal.setToggleGroup(yg);
        yGlobal.setToggleGroup(yg);
        (app.settings.resYpG() ? yGlobal : yLocal).setSelected(true);
        String ypHow = "Yaw/pitch axes apply when the RESULTING graphs "
            + "are geometric rates — i.e. while a FORCE section is "
            + "active (a geo section's resulting graphs are felt G).";
        boolean resGeo = !activeGeo();
        yLocal.setDisable(!resGeo);
        yGlobal.setDisable(!resGeo);
        grid.addRow(row++, new Label("Yaw/pitch axis"),
            new HBox(10, grayHint(yLocal, ypHow),
                grayHint(yGlobal, ypHow)));

        // Yaw/pitch BEHAVIOR (v19.9, D238 — the roll-behavior row's
        // twin, directly below the axis row like the section dialogs)
        ToggleGroup yag = new ToggleGroup();
        RadioButton yaRate = new RadioButton("Rate");
        RadioButton yaAbs = new RadioButton("Absolute angles (°)");
        yaRate.setToggleGroup(yag);
        yaAbs.setToggleGroup(yag);
        (app.settings.resYpAbs() && app.settings.resYpG()
            ? yaAbs : yaRate).setSelected(true);
        String yaHow = "Absolute elevation/heading need the GLOBAL "
            + "yaw/pitch axis — and apply while a FORCE section is "
            + "active (a geo section's resulting graphs are felt G).";
        Runnable syncYpAbs = () -> {
            boolean ok = resGeo && yGlobal.isSelected();
            yaRate.setDisable(!resGeo);
            yaAbs.setDisable(!ok);
            if (!ok && yaAbs.isSelected()) yaRate.setSelected(true);
        };
        yg.selectedToggleProperty().addListener((o, w, n2) -> {
            app.settings.setResYpG(yGlobal.isSelected());
            syncYpAbs.run();
            applyLive.run();
        });
        yag.selectedToggleProperty().addListener((o, w, n2) -> {
            app.settings.setResYpAbs(yaAbs.isSelected());
            applyLive.run();
        });
        syncYpAbs.run();
        grid.addRow(row++, new Label("Yaw/pitch behavior"),
            new HBox(10, grayHint(yaRate, yaHow),
                grayHint(yaAbs, yaHow)));
        ToggleGroup rg = new ToggleGroup();
        RadioButton rLocal = new RadioButton("Local");
        RadioButton rGlobal = new RadioButton("Global (bank vs horizon)");
        rLocal.setToggleGroup(rg);
        rGlobal.setToggleGroup(rg);
        (app.settings.resRollG() ? rGlobal : rLocal).setSelected(true);
        grid.addRow(row++, new Label("Roll axis"),
            new HBox(10, rLocal, rGlobal));


        ToggleGroup ag = new ToggleGroup();
        RadioButton aRate = new RadioButton("Roll rate");
        RadioButton aAbs = new RadioButton("Absolute bank (°)");
        aRate.setToggleGroup(ag);
        aAbs.setToggleGroup(ag);
        (app.settings.resRollAbs() && app.settings.resRollG()
            ? aAbs : aRate).setSelected(true);
        grid.addRow(row++, new Label("Roll behavior"),
            new HBox(10, aRate, grayHint(aAbs, ABS_HOW)));
        Runnable syncAbs = () -> {
            boolean glob = rGlobal.isSelected();
            aAbs.setDisable(!glob);
            if (!glob && aAbs.isSelected()) aRate.setSelected(true);
        };
        rg.selectedToggleProperty().addListener((o, w, n2) -> {
            app.settings.setResRollG(rGlobal.isSelected());
            syncAbs.run();
            applyLive.run();
        });
        ag.selectedToggleProperty().addListener((o, w, n2) -> {
            app.settings.setResRollAbs(aAbs.isSelected());
            applyLive.run();
        });
        syncAbs.run();

        Button close = new Button("Close");
        close.setDefaultButton(true);
        close.setCancelButton(true);
        close.setOnAction(ev -> dlg.close());
        HBox foot = new HBox(close);
        foot.setAlignment(Pos.CENTER_RIGHT);
        grid.add(foot, 0, row, 2, 1);
        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installViewKeys(dlg.getScene());   // ride keys live (v18.1)
    }

    private double resToY(int comp, double v) {
        // comp 3 may normalize on its own pair (v18.42, D214)
        boolean own = comp == 3 && resRollOwnScale();
        double lo = own ? resLo3 : resLo;
        double hi = own ? resHi3 : resHi;
        return MARGIN_TOP + plotHeight() * (1 - (v - lo) / (hi - lo));
    }

    /** Overlay accent: rates wear the geo palette, forces the force one
     *  (a curve's color names its QUANTITY, not the active mode). */
    private Color overlayColor(int comp) {
        if (comp == 3 && app.settings.resRollAbs()
                && app.settings.resRollG()) {
            return FvdDesigner.BANK_COLOR;
        }
        // absolute elevation/heading overlays wear the abs colors
        // like the real lanes (v19.9, D238 — the BANK_COLOR rule)
        if (comp <= 2 && resYpAbsOn()) {
            return comp == 1 ? FvdDesigner.ELEV_ABS_COLOR
                             : FvdDesigner.HEAD_ABS_COLOR;
        }
        return (activeGeo() ? FvdDesigner.LANE_COLORS
                            : FvdDesigner.GEO_LANE_COLORS)[comp - 1];
    }

    /** Clamp a hover readout's anchor so the text stays inside the
     *  given rect (v18.8, user spec — the node-drag readout's
     *  stay-in-view rule applied to every hover readout; left-aligned
     *  VPos.BOTTOM text, ~6 px/char at the 10 px font). */
    private static double[] clampHover(String txt, double x, double y,
            double l, double r, double t, double b) {
        double tw = txt.length() * 6.0;
        return new double[]{ FvdDesigner.clamp(x, l + 4, r - tw - 4),
                             FvdDesigner.clamp(y, t + 14, b - 2) };
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
        // LIVE node-editor resync (v19.14, D243) — before every
        // guard: the dialog follows outside edits even while the
        // panel is collapsed. Muted equal-value writes = free.
        if (!redrawHooks.isEmpty() && !inRedrawHooks) {
            inRedrawHooks = true;
            try {
                for (Runnable r : new ArrayList<>(redrawHooks)) r.run();
            } finally {
                inRedrawHooks = false;
            }
        }
        // LIVE TOOL WINDOWS (v19.18, D247): graph-only edits reach the
        // shell's windows too (re-entry-guarded; equal-value no-ops
        // at steady state)
        app.fireModelHooks();
        // UNLOADED while the graph panel is collapsed (v18.36, D208):
        // a busy zoomed-out plot must cost nothing off screen. The
        // canvas is usually 0-high then anyway (the size check below);
        // this makes it a contract. 3D markers/gizmos stay synced —
        // rebuildMeshes calls refreshMarkers directly — and expanding
        // repaints via the divider listener + layoutChildren.
        if (app.graphCollapsed()) return;
        // ALWAYS drawn from the MAIN surface (v18.4, D173 — the
        // strip-flashing root cause): a strip-surface mutation
        // (dragNodeTo under withSurf) triggers rebuildTrack →
        // refresh() → redraw() with surfStrip still set, and that
        // frame rendered the whole plot through the STRIP's chokes
        // (wrong lane, wrong scales) — the main graphs "disappeared"
        // until the gesture's own redraw repainted. The strip
        // re-enters its own surface via spDraw's withSurf.
        if (surfStrip) {
            surfStrip = false;
            try {
                redraw();
            } finally {
                surfStrip = true;
            }
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (app.coaster.pieces.isEmpty()) {   // NO COASTER (D230): blank —
            // BEFORE any plot geometry (marginRight reads the lanes)
            if (w > 0 && h > 0) {
                gc.setFill(Color.rgb(20, 22, 28));
                gc.fillRect(0, 0, w, h);
            }
            return;
        }
        if (w <= 0 || h <= 0 || plotWidth() <= 0 || plotHeight() <= 0) return;
        enforceLocks();           // before updateScale sees the handles
        clearStaleFollows();      // the END-FOLLOW belt (D248): clears a
                                  // stale flag, never moves a node — a
                                  // redraw can't rebuild (review fix)
        // an AXIS DOMAIN change re-scales the whole x-axis: a manual
        // zoom window's ENDS line up with their counterparts in the
        // new units (v16.1, user rule — flips used to reset to
        // auto-fit and "snap to the whole piece"); auto-fit stays
        // auto-fit
        if (axisDist() != lastAxisDist) {
            if (viewT1 > 0) {
                double n0 = mapGlobalAxis(viewT0, lastAxisDist);
                double n1 = mapGlobalAxis(viewT1, lastAxisDist);
                if (n1 - n0 > 1e-9) {
                    viewT0 = n0;
                    viewT1 = n1;
                } else {
                    viewT0 = 0;
                    viewT1 = -1;   // degenerate map: fall back
                }
            }
            lastAxisDist = axisDist();
        }
        refreshOffset();          // section offsets before any x mapping
        // the cross-domain warp map follows the compile — except while
        // a node/handle gesture is live (frozen so the mapping under
        // the cursor stays stable through the per-event rebuilds) — or
        // a STRIP gesture (v19.20, D249, item S1): a diamond / 3D disk
        // drag rebuilds the track per event and a speed control changes
        // the very t↔s profile the map is made of, so under a
        // cross-domain view the map used to slide under the cursor
        // mid-drag; the release redraw (spDragKind back to 0)
        // re-derives it — the diamond may visibly re-land there
        // (accepted, documented)
        if (dragNodeIdx < 0 && dragHandleIdx < 0 && spDragKind == 0) {
            refreshWarp();
        }
        sanitizeView();           // stale manual windows follow the piece
        updateScale();
        refreshMarkers();         // 3D markers follow lane + selection
        gc.setGlobalAlpha(1.0);
        gc.clearRect(0, 0, w, h);
        gc.setFill(Color.rgb(20, 22, 28));
        gc.fillRect(0, 0, w, h);

        gc.setFill(Color.rgb(28, 31, 40));
        gc.fillRect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - marginRight(), plotHeight());

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
            gc.strokeLine(MARGIN_LEFT, gy, w - marginRight(), gy);
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
        gc.rect(MARGIN_LEFT, MARGIN_TOP, w - MARGIN_LEFT - marginRight(), plotHeight());
        gc.clip();

        double zy = valToY(activeLane, 0);
        gc.setStroke(Color.rgb(70, 75, 90));
        gc.setLineWidth(1);
        gc.strokeLine(MARGIN_LEFT, zy, w - marginRight(), zy);
        // 1 g reference: force mode only — and not while editing
        // DELTAS around zero (influence), where 1 g means nothing
        if (activeLane == 0 && !activeGeo() && activeInfl() == null) {
            double oy = valToY(0, 1);
            gc.setStroke(Color.rgb(60, 90, 65));
            gc.strokeLine(MARGIN_LEFT, oy, w - marginRight(), oy);
        }

        drawInactiveSections(gc);
        drawPvRef(gc);   // density preview's original-curve reference

        if (activeSc() != null) {
            // a straight/curve section: the element widget IS the
            // editing surface — no lanes to stroke (v11.0)
            drawScStrip(gc);
        } else if (activeBridge() != null) {
            drawBridgeStrip(gc);   // frozen: derived curves + status
        } else {
            for (int lane = 0; lane < LANES; lane++) {
                if (lane == activeLane) continue;
                gc.setGlobalAlpha(INACTIVE_ALPHA);
                strokeLaneCurve(gc, lane);
            }
            gc.setGlobalAlpha(1.0);
            strokeLaneCurve(gc, activeLane);
        }

        // TRUE companion strokes (round A, §5.2): thin solid lines in
        // the lane colors wherever the Pass-D/A true curves visibly
        // leave the design curves — drift made visible; nothing draws
        // while design == true
        drawTrueStrokes(gc);

        // read-only RESULTING curves (toolbar "Resulting" dropdown),
        // drawn across the WHOLE ride (v10.8 — was active-section only),
        // with the path broken over a SEAM_TRIM band at each section
        // boundary (bodyRates smear discontinuities there); no
        // zero-line fill. MODE-AWARE (v10.2): the active mode decides
        // the quantity — resulting pitch/yaw RATES over a force
        // section, felt VERTICAL/LATERAL G over a geo one. Both
        // enabled curves share THE RESULTING SCALE (v10.3) — fitted in
        // updateResScale and labeled on the right-edge axis. Colors are
        // the QUANTITY's palette (overlayColor). Roll is not offered:
        // resulting roll = the authored roll lane.
        if (resAxisOn) {
            List<Double> seams = sectionBoundaries();
            // clamped to the RUN (v13.11, D85): the global timeline
            // continues into detached pieces, whose overlay bled in
            // just right of the run's curves
            double rT0 = runT0(), rT1 = rT0 + runDur();
            gc.setLineWidth(1);
            gc.setLineDashes(4, 4);
            gc.setGlobalAlpha(0.7);
            if (app.resultRates.length > 0) {
                // view-culled index range + seam-crossing breaks
                // (v18.35/v18.36): the curves run right up to each
                // seam sample — span-differenced rates are clean
                // there — and the path breaks exactly ON the seam,
                // never drawing a connector across the jump
                int[] ir = axisIdxRange(Math.max(viewStart(), rT0),
                    Math.min(viewEnd(), rT1));
                for (int comp = 1; comp <= 3; comp++) {
                    if (!resCompOn(comp)) continue;
                    gc.setStroke(overlayColor(comp));
                    ColPath path = new ColPath(gc);
                    SeamWalk sw = new SeamWalk(seams, gAxis(ir[0]));
                    for (int i = ir[0]; i <= ir[1]; i++) {
                        double a = gAxis(i);
                        if (sw.crossed(a)) path.endRun();
                        if (a < rT0 - 1e-6 || a > rT1 + 1e-6) {
                            path.endRun();
                            continue;
                        }
                        path.add(tToX(a), resToY(comp, resValueAt(i, comp)));
                    }
                    path.stroke();
                }
            }
            gc.setLineDashes((double[]) null);
            gc.setGlobalAlpha(1.0);
            gc.setLineWidth(2);
        }

        // hover readout on a resulting curve: dot + the value there, in
        // the curve's color (the lane hover's read-only sibling, v10.3);
        // Shift swaps in the numeric slope, like the lane hover (v10.4)
        if (hoverResComp != 0) {
            double hx = tToX(hoverResT);
            double hy = resToY(hoverResComp, hoverResVal);
            gc.setGlobalAlpha(0.55);
            gc.setFill(overlayColor(hoverResComp));
            gc.fillOval(hx - 4, hy - 4, 8, 8);
            gc.setGlobalAlpha(0.9);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            boolean resAbs = app.settings.resRollAbs()
                && app.settings.resRollG();
            // the ROLL/BANK overlay (comp 3) is a rate/bank in BOTH
            // views — v17.17 (D158) bug fix: the geo-view branch
            // labeled it by component index as "Gl … g" (the user's
            // "resulting roll reads in gs of laterals")
            String name = hoverResComp == 3 ? (resAbs ? "bank" : "roll")
                : activeGeo()
                ? (hoverResComp == 1 ? "Gv" : "Gl")
                : resYpAbsOn()
                ? (hoverResComp == 1 ? "elevation" : "heading")
                : (hoverResComp == 1 ? "pitch" : "yaw");
            String unit = hoverResComp == 3
                ? (resAbs ? "°" : resRatePM() ? "°/m" : "°/s")
                : activeGeo() ? "g"
                : resYpAbsOn() ? "°"
                : resRatePM() ? "°/m" : "°/s";
            String txt;
            if (hoverSlope) {
                int ic = sampleIdxAtAxis(hoverResT);
                int[] w2 = slopeWin(ic, 2);   // span-clamped (D207)
                int i0 = w2[0], i1 = w2[1];
                double span = gAxis(i1) - gAxis(i0);
                double slope = span < 1e-9 ? 0
                    : (resValueAt(i1, hoverResComp) - resValueAt(i0, hoverResComp)) / span;
                txt = String.format("%s %.2f %s/%s", name,
                    FvdDesigner.z3(slope), unit, xUnit());
            } else {
                txt = String.format("%s %.2f %s", name, hoverResVal, unit);
            }
            double[] hp = clampHover(txt, hx + 8, hy - 6, MARGIN_LEFT,
                w - marginRight(), MARGIN_TOP, MARGIN_TOP + plotHeight());
            gc.fillText(txt, hp[0], hp[1]);
            gc.setGlobalAlpha(1.0);
        }

        // hover readout on the active DERIVED strip (bridge/s-c,
        // v13.5): value in the curve's lane color, slope with Shift —
        // the lane hover's read-only sibling for frozen curves
        if (hoverDerLane >= 0 && app.resultRates != null
                && app.resultRates.length > 0) {
            double hx = tToX(hoverDerT);
            double hy = valToY(hoverDerLane, hoverDerVal);
            gc.setGlobalAlpha(0.55);
            gc.setFill(laneColor(hoverDerLane));
            gc.fillOval(hx - 4, hy - 4, 8, 8);
            gc.setGlobalAlpha(0.9);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            String txt;
            int ic = sampleIdxAtAxis(hoverDerT);
            if (hoverSlope) {
                int[] w2 = slopeWin(ic, 2);   // span-clamped (D207)
                int i0 = w2[0], i1 = w2[1];
                double span = gAxis(i1) - gAxis(i0);
                double slope = span < 1e-9 ? 0
                    : (derivedAt(i1, hoverDerLane)
                        - derivedAt(i0, hoverDerLane)) / span;
                txt = String.format("%.2f %s/%s", FvdDesigner.z3(slope),
                    laneUnit(hoverDerLane).trim(), xUnit())
                    + trueSuffix(ic, hoverDerLane, true);
            } else {
                txt = String.format("%.2f%s", hoverDerVal,
                    laneUnit(hoverDerLane))
                    + trueSuffix(ic, hoverDerLane, false);
            }
            double[] hp = clampHover(txt, hx + 8, hy - 6, MARGIN_LEFT,
                w - marginRight(), MARGIN_TOP, MARGIN_TOP + plotHeight());
            gc.fillText(txt, hp[0], hp[1]);
            gc.setGlobalAlpha(1.0);
        }

        // POV position on the track — drawn ALWAYS since v17.11
        // (user spec: the POV position persists outside POV view and
        // the bottom stats line tracks it; the line is its marker)
        {
            double px2 = tToX(axisOfTime(app.povTime));
            if (px2 >= MARGIN_LEFT - 1 && px2 <= w - marginRight() + 1) {
                gc.setGlobalAlpha(0.4);
                gc.setStroke(FvdDesigner.TIME_COLOR);   // purple = time
                gc.setLineWidth(2);
                gc.strokeLine(px2, MARGIN_TOP, px2, MARGIN_TOP + plotHeight());
                gc.setGlobalAlpha(1.0);
            }
        }

        // the shared editing overlays: nodes, rings, readouts, insert
        // dot, guides, handles, box band (v18.3 — extracted so the
        // speed strip draws the SAME overlays on its own surface)
        drawEditOverlays(gc, w);

        // the split-line overlay on LANE and S/C strips (v14.0 — the
        // bridge strip draws its own inside drawBridgeStrip); on top
        // of nodes/curves: the mode owns the strip
        if (pcSec == activeSection && pcSec >= 0
                && activeBridge() == null) {
            drawPartialConvert(gc, sectionDur(activeSection));
        }

        drawAddButton(gc, w);
        drawSectionBadges(gc, w);

        gc.restore();   // end plot clip

        // SPEED CONTROL STRIP (Phase 3, v18.0 D160) — below the plot,
        // outside its clip; the Ripple label follows the axis units
        spSanitize();
        spDraw(gc, w, h);
        app.syncRippleLabel(axisDist());

        gc.setGlobalAlpha(1.0);
        gc.setFont(Font.font(11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(laneColor(activeLane));
        // the rotated y-axis title runs along the same left edge — on
        // a SHORT plot its vertical footprint reaches the corners, so
        // a max/min scale label it would cross goes INVISIBLE (v18.8,
        // user spec)
        String yTitle = laneName(activeLane) + " ("
            + laneUnit(activeLane).trim() + ")";
        javafx.scene.text.Text meas = new javafx.scene.text.Text(yTitle);
        meas.setFont(Font.font(11));
        double tHalf = meas.getLayoutBounds().getWidth() / 2 + 4;
        double tCy = MARGIN_TOP + plotHeight() / 2;
        if (tCy - tHalf > MARGIN_TOP + 12) {
            gc.setTextBaseline(VPos.TOP);
            gc.fillText(fmtVal(curMax[activeLane]), 6, MARGIN_TOP);
        }
        if (tCy + tHalf < MARGIN_TOP + plotHeight() - 12) {
            gc.setTextBaseline(VPos.BOTTOM);
            gc.fillText(fmtVal(curMin[activeLane]), 6,
                MARGIN_TOP + plotHeight());
        }

        // y-axis label, rotated along the left edge (tracks the edit mode)
        gc.save();
        gc.translate(14, MARGIN_TOP + plotHeight() / 2);
        gc.rotate(-90);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        // the unit rides the title (v16.7 — a °/m lane's 15 lined up
        // with a °/s 15 with nothing on the axis saying which)
        gc.fillText(yTitle, 0, 0);
        gc.restore();

        // CROSS-RATE right axis (v16.8, D120/D121): the main scale
        // shows the WARPED (other) units — this axis labels the same
        // rows in the lane's NATIVE units at the section's design
        // speed. Takes the right edge over the resulting axis while
        // the view is on.
        if (rateViewAxisOn()) {
            double f = rateViewFactor();
            double rx = w - marginRight() + 4;
            double lo = Math.min(curMin[activeLane], curMax[activeLane]) / f;
            double hi = Math.max(curMin[activeLane], curMax[activeLane]) / f;
            double rStep = niceStep((hi - lo) / 5);
            gc.setFill(Color.rgb(150, 150, 168));
            gc.setFont(Font.font(9));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            for (double v = Math.ceil(lo / rStep) * rStep;
                    v <= hi + 1e-9; v += rStep) {
                gc.fillText(fmtVal(v), rx, valToY(activeLane, v * f));
            }
            gc.save();
            gc.translate(w - 6, MARGIN_TOP + plotHeight() / 2);
            gc.rotate(90);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(laneName(activeLane)
                + (activeRatePM(activeLane) ? " (°/m)" : " (°/s)")
                + " native, at design speed", 0, 0);
            gc.restore();
        } else
        // RIGHT axis for the resulting overlays (v10.3): their shared
        // scale's ticks down the right edge (its own nice steps — they
        // deliberately don't chase the active lane's gridlines) + a
        // rotated title naming the quantity/unit
        if (resAxisOn) {
            double rx = w - marginRight() + 4;
            double rStep = niceStep((resHi - resLo) / 5);
            gc.setFill(Color.rgb(150, 150, 168));
            gc.setFont(Font.font(9));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.CENTER);
            for (double v = Math.ceil(resLo / rStep) * rStep; v <= resHi + 1e-9; v += rStep) {
                gc.fillText(fmtVal(v), rx, resToY(1, v));
            }
            gc.save();
            gc.translate(w - 6, MARGIN_TOP + plotHeight() / 2);
            gc.rotate(90);
            gc.setFont(Font.font(11));
            gc.setTextAlign(TextAlignment.CENTER);
            boolean resAbs = app.settings.resRollAbs()
                && app.settings.resRollG();
            // the axis numbers name the SHARED scale; an own-scaled
            // roll/bank overlay says so (v18.42 — the axis used to
            // claim g for a °/s roll curve; hover for its values)
            gc.fillText((activeInfl() != null ? "True resulting " : "Resulting ")
                + (activeGeo() ? "G (g)"
                    : resYpAbsOn() ? "angle (°)"   // D238 abs comps
                    : resRatePM() ? "rate (°/m)" : "rate (°/s)")
                + (resRollOwnScale()
                    ? (resAbs ? " · bank ° own scale"
                              : resRatePM() ? " · roll °/m own scale"
                                            : " · roll °/s own scale")
                    : ""), 0, 0);
            gc.restore();
        }

        gc.setFill(Color.rgb(130, 135, 150));
        gc.setFont(Font.font(10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.BOTTOM);
        String tickFmt = gridTStep < 0.1 ? "%.2f" : gridTStep < 1 ? "%.1f" : "%.0f";
        for (double t = Math.ceil(vt0 / gridTStep) * gridTStep; t <= vt1 + 1e-9; t += gridTStep) {
            gc.fillText(String.format(tickFmt, t), tToX(t), h - 16);
        }
        gc.setFont(Font.font(11));
        gc.setFill(FvdDesigner.TIME_COLOR);   // purple = the x-axis color
        gc.fillText(axisDist() ? "Distance (m)" : "Time (s)",
            MARGIN_LEFT + plotWidth() / 2, h - 2);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
    }

    private void strokeLaneCurve(GraphicsContext gc, int laneIdx) {
        // INFLUENCE surface (v15.0, D98): Resulting view replaces the
        // lane rendering wholesale (nodes ride base+delta); Influence
        // view draws the RESULTING graph dotted behind the normal
        // delta-lane rendering, then falls through to it.
        LaneSection infl = activeInfl();
        if (infl != null && laneIdx == 2) {
            // ROLL takes no deltas (v15.4, D102): its strip shows only
            // the RESULTING roll, dotted, in either influence view
            List<double[]> sum = influenceSum(infl, 2);
            gc.setStroke(laneColor(2));
            gc.setLineWidth(1.4);
            gc.setLineDashes(4, 5);
            strokeSeries(gc, toPixels(sum, 2, false));
            gc.setLineDashes((double[]) null);
            gc.setLineWidth(2);
            return;
        }
        if (infl != null) {
            List<double[]> sum = influenceSum(infl, laneIdx);
            gc.setStroke(laneColor(laneIdx));
            gc.setLineWidth(1.4);
            gc.setLineDashes(4, 5);
            strokeSeries(gc, toPixels(sum, laneIdx, false));
            gc.setLineDashes((double[]) null);
            gc.setLineWidth(2);
        }
        List<BezierBaker.Node> lane = app.lanes.get(laneIdx);
        // CROSS-RATE VIEW (v16.9, D121 — THE v16.7 gap): a warped
        // lane draws from its baked polyline through dispVal. The
        // bezier path below strokes RAW control points, which is why
        // the curve sat at native scale while the nodes warped (the
        // user's "nodes and graph don't line up"). The hold
        // continuation samples too — a constant native value is a
        // CURVE in the other units (× v(x)). Locked segments lose
        // their twin-line styling here (the polyline already carries
        // the chord shape).
        if (laneWarped(laneIdx)) {
            List<double[]> wpoly = BezierBaker.polyline(lane);
            List<double[]> wpts = new ArrayList<>(wpoly.size());
            for (double[] p : wpoly) {
                wpts.add(new double[]{ ltToX(p[0]),
                    valToY(laneIdx, dispVal(laneIdx, p[0], p[1])) });
            }
            double wEnd = sectionEnd();
            BezierBaker.Node wLast = lane.get(lane.size() - 1);
            List<double[]> hold = new ArrayList<>();
            if (wLast.t < wEnd - 1e-9) {
                int nH = (int) FvdDesigner.clamp(
                    (ltToX(wEnd) - ltToX(wLast.t)) / 3, 2, 400);
                for (int i = 0; i <= nH; i++) {
                    double x = wLast.t + (wEnd - wLast.t) * i / nH;
                    hold.add(new double[]{ ltToX(x),
                        valToY(laneIdx, dispVal(laneIdx, x, wLast.val)) });
                }
            }
            List<double[]> fillPts = new ArrayList<>(wpts);
            fillPts.addAll(hold);
            fillUnderSeries(gc, fillPts, laneIdx);
            gc.setStroke(laneColor(laneIdx));
            gc.setLineWidth(2);
            strokeSeries(gc, wpts);
            if (!hold.isEmpty()) {
                gc.setLineDashes(3, 7);
                strokeSeries(gc, hold);
                gc.setLineDashes((double[]) null);
            }
            return;
        }
        // zero-line fill first (from the baked polyline + hold extension;
        // the true bezier stroke sits on top of its edge)
        List<double[]> poly = BezierBaker.polyline(lane);
        List<double[]> pts = new ArrayList<>(poly.size() + 1);
        for (double[] p : poly) {
            pts.add(new double[]{ ltToX(p[0]), valToY(laneIdx, p[1]) });
        }
        double[] lastP = poly.get(poly.size() - 1);
        if (lastP[0] < sectionEnd() - 1e-9) {
            pts.add(new double[]{ ltToX(sectionEnd()), valToY(laneIdx, lastP[1]) });
        }
        fillUnderSeries(gc, pts, laneIdx);

        gc.setStroke(laneColor(laneIdx));
        gc.setLineWidth(2);
        gc.beginPath();
        gc.moveTo(ltToX(lane.get(0).t), valToY(laneIdx, lane.get(0).val));
        for (int i = 0; i < lane.size() - 1; i++) {
            BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
            if (a.lockedOut()) {   // drawn separately as twin lines
                gc.moveTo(ltToX(b.t), valToY(laneIdx, b.val));
                continue;
            }
            double c1x = ltToX(a.t + BezierBaker.effOutDt(a, b));
            double c1y = valToY(laneIdx, a.val + a.outDv);
            double c2x = ltToX(b.t + BezierBaker.effInDt(a, b));
            double c2y = valToY(laneIdx, b.val + b.inDv);
            gc.bezierCurveTo(c1x, c1y, c2x, c2y, ltToX(b.t), valToY(laneIdx, b.val));
        }
        gc.stroke();

        // locked segments: TWO skinny parallel lines offset along the
        // chord normal — the "this is pinned" look. LEVEL locks (v10.5)
        // additionally tint the band between the lines, telling the two
        // locks apart at a glance.
        gc.setLineWidth(1);
        for (int i = 0; i < lane.size() - 1; i++) {
            if (!lane.get(i).lockedOut()) continue;
            BezierBaker.Node a = lane.get(i), b = lane.get(i + 1);
            double ax = ltToX(a.t), ay = valToY(laneIdx, a.val);
            double bx = ltToX(b.t), by = valToY(laneIdx, b.val);
            double dx = bx - ax, dy = by - ay;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) continue;
            double ox = -dy / len * 1.6, oy = dx / len * 1.6;
            if (lane.get(i).levelOut) {   // level segments are horizontal
                gc.setFill(laneColor(laneIdx).deriveColor(0, 1, 1, 0.35));
                gc.fillRect(Math.min(ax, bx), ay - Math.abs(oy),
                            Math.abs(bx - ax), Math.abs(oy) * 2);
            }
            gc.strokeLine(ax + ox, ay + oy, bx + ox, by + oy);
            gc.strokeLine(ax - ox, ay - oy, bx - ox, by - oy);
        }
        gc.setLineWidth(2);

        // a lane that ends early holds its final value to the ride end:
        // drawn DOTTED to show it's implied, not authored (clickable to
        // insert a real node on it)
        double end = sectionEnd();
        BezierBaker.Node last = lane.get(lane.size() - 1);
        if (last.t < end - 1e-9) {
            double ly = valToY(laneIdx, last.val);
            gc.setLineDashes(3, 7);
            gc.strokeLine(ltToX(last.t), ly, ltToX(end), ly);
            gc.setLineDashes((double[]) null);
        }
    }

    /** The RESULTING series base(t)+delta(t) over the influence
     *  surface's stretch, (t, value) rows on the base grid (falls back
     *  to the bare delta before the first compile). */
    private List<double[]> influenceSum(LaneSection infl, int laneIdx) {
        List<double[]> dpoly = BezierBaker.polyline(app.lanes.get(laneIdx));
        double end = sectionEnd();
        List<double[]> out = new ArrayList<>();
        double[][] rows = infl.baseCurves;
        if (rows == null) {
            for (double[] p : dpoly) out.add(new double[]{ p[0], p[1] });
            return out;
        }
        for (double[] row : rows) {
            if (row[0] > end + 1e-9) break;
            out.add(new double[]{ row[0],
                row[laneIdx + 1] + TrackCompiler.polyAt(dpoly, row[0]) });
        }
        if (out.isEmpty() || out.get(out.size() - 1)[0] < end - 1e-9) {
            out.add(new double[]{ end,
                infl.baseValAt(laneIdx, end) + TrackCompiler.polyAt(dpoly, end) });
        }
        return out;
    }

    /** (t, value) rows → pixel series; ofBase = the BASE guide (rows
     *  read straight from baseCurves). */
    private List<double[]> toPixels(List<double[]> series, int laneIdx,
                                    boolean unused) {
        List<double[]> pts = new ArrayList<>(series.size());
        for (double[] p : series) {
            pts.add(new double[]{ ltToX(p[0]), valToY(laneIdx, p[1]) });
        }
        return pts;
    }

    private void strokeSeries(GraphicsContext gc, List<double[]> pts) {
        if (pts.size() < 2) return;
        gc.beginPath();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            if (i == 0) gc.moveTo(p[0], p[1]); else gc.lineTo(p[0], p[1]);
        }
        gc.stroke();
    }

    /** The editing surface's overlays — nodes, selection rings,
     *  hover/drag readouts, the insert dot, axis-lock guides, handles
     *  and the box band. ONE implementation for the plot AND the
     *  speed strip (v18.3, D172): the surface chokes (active/ltToX/
     *  valToY/nodeR/edit bounds/…) retarget everything, and the
     *  shared selection/hover/gesture state applies only on the
     *  surface that owns it (spFocus / spHoverSurf / surfGesture). */
    private void drawEditOverlays(GraphicsContext gc, double w) {
        boolean owns = spFocus == surfStrip;
        int sel = owns ? selIdx : -1;
        java.util.Set<Integer> selMembers = owns ? selSet
            : java.util.Collections.emptySet();
        int dragged = owns ? dragNodeIdx : -1;
        int draggedH = owns ? dragHandleIdx : -1;
        boolean hovOwn = spHoverSurf == surfStrip;
        double hovT = hovOwn ? hoverT : Double.NaN;
        int hovN = hovOwn ? hoverNodeIdx : -1;

        List<BezierBaker.Node> lane = active();
        for (int i = 0; i < lane.size(); i++) {
            double px = ltToX(lane.get(i).t);
            double py = valToY(activeLane, dispVal(activeLane,
                lane.get(i).t, lane.get(i).val));
            boolean isSel = (i == sel);
            boolean isDragged = (i == dragged);

            if (isSel || selMembers.contains(i)) {
                // primary = solid white ring; other members = fainter
                gc.setStroke(isSel ? Color.WHITE : Color.rgb(255, 255, 255, 0.5));
                gc.setLineWidth(isSel ? 1.5 : 1.2);
                gc.strokeOval(px - nodeR() - 3, py - nodeR() - 3,
                              (nodeR() + 3) * 2, (nodeR() + 3) * 2);
            } else if (i == hovN && dragged < 0
                    && draggedH < 0) {
                // GREY hover ring (v16.15, user spec): the node under
                // the cursor pre-lights before the white click ring
                gc.setStroke(Color.rgb(170, 175, 185, 0.85));
                gc.setLineWidth(1.3);
                gc.strokeOval(px - nodeR() - 3, py - nodeR() - 3,
                              (nodeR() + 3) * 2, (nodeR() + 3) * 2);
            }
            gc.setFill(isDragged ? Color.WHITE : laneColor(activeLane));
            gc.fillOval(px - nodeR(), py - nodeR(), nodeR() * 2, nodeR() * 2);
            if (!isDragged && i == hovN && dragged < 0
                    && draggedH < 0) {
                // GREY node-hover readout (v16.15, user spec): the
                // drag readout's numbers without the click — and
                // SHIFT swaps in the computed twins: the ABSOLUTE
                // axis position + the CHANGE from the previous node
                gc.setFill(Color.rgb(170, 175, 185));
                gc.setFont(Font.font(11));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.TOP);
                BezierBaker.Node nd = lane.get(i);
                double shown = dispVal(activeLane, nd.t, nd.val);
                String xu = xUnit();
                String txt;
                if (hoverSlope) {
                    String dv = i > 0
                        ? String.format("  Δ%+.2f%s",
                            nd.val - lane.get(i - 1).val,
                            laneUnit(activeLane))
                        : "";
                    txt = String.format("%s=%.2f%s%s",
                        axisDist() ? "s" : "t",
                        tOff + warpFwd(nd.t), xu, dv);
                } else {
                    String vTxt = String.format("%.2f%s", shown,
                        laneUnit(activeLane));
                    txt = i == 0 ? vTxt
                        : String.format("Δ%s=%.2f%s  %s",
                            axisDist() ? "s" : "t",
                            warpFwd(nd.t) - warpFwd(lane.get(i - 1).t),
                            xu, vTxt);
                }
                double tw = txt.length() * 6.5;
                double tx = FvdDesigner.clamp(px + 8, editL() + 4,
                    editR() - tw - 4);
                double ty = FvdDesigner.clamp(py - 20, editT() + 4,
                    editB() - 16);
                gc.fillText(txt, tx, ty);
            }
            if (isDragged) {
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(11));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.TOP);
                // transition time from the previous node; global value +
                // unit. Position clamped INTO the plot so the readout
                // stays readable while dragging past the bounds.
                BezierBaker.Node nd = lane.get(i);
                // Resulting view: the readout leads with the RESULTING
                // value under the node and carries the delta along
                double shown = dispVal(activeLane, nd.t, nd.val);
                double off = dispOff(activeLane, nd.t);
                String vTxt = off != 0
                    ? String.format("%.2f%s (Δ %+.2f)", shown,
                        laneUnit(activeLane), nd.val)
                    // `shown` == nd.val without a warp; the cross-rate
                    // view shows the displayed value (v16.9, D121)
                    : String.format("%.2f%s", shown, laneUnit(activeLane));
                // x readout in the DISPLAY domain: native Δ unwarped
                // (Δt or Δs per the section), the warped axis Δ in a
                // cross-domain view — always what the plot shows.
                // SHIFT mid-drag (v16.16, user spec) swaps in the
                // computed twins, exactly like shift+hover: the
                // ABSOLUTE axis position + the Δ from the last node
                String xu = xUnit();
                String txt;
                if (hoverSlope) {
                    String dv = i > 0
                        ? String.format("  Δ%+.2f%s",
                            nd.val - lane.get(i - 1).val,
                            laneUnit(activeLane))
                        : "";
                    txt = String.format("%s=%.2f%s%s",
                        axisDist() ? "s" : "t",
                        tOff + warpFwd(nd.t), xu, dv);
                } else {
                    txt = i == 0
                        ? String.format("%s=%.2f%s  %s",
                            axisDist() ? "s" : "t", warpFwd(nd.t), xu, vTxt)
                        : String.format("Δ%s=%.2f%s  %s",
                            axisDist() ? "s" : "t",
                            warpFwd(nd.t) - warpFwd(lane.get(i - 1).t),
                            xu, vTxt);
                }
                double tw = txt.length() * 6.5;   // approx width at 11 px
                double tx = FvdDesigner.clamp(px + 8, editL() + 4, editR() - tw - 4);
                double ty = FvdDesigner.clamp(py - 20, editT() + 4, editB() - 16);
                gc.fillText(txt, tx, ty);
            }
        }

        // insert preview: translucent white dot riding the curve, with
        // the lane's value at that point alongside (the DISPLAYED
        // curve: base+delta in the influence Resulting view)
        if (!Double.isNaN(hovT) && !lane.isEmpty()) {   // (a lane-less
                                                       // section swap under a
                                                       // live hover, D231)
            double hv = dispVal(activeLane, hovT,
                BezierBaker.evalAt(lane, hovT));
            double hx = ltToX(hovT);
            double hy = valToY(activeLane, hv);
            gc.setGlobalAlpha(0.5);
            gc.setFill(Color.WHITE);
            gc.fillOval(hx - 4, hy - 4, 8, 8);
            gc.setGlobalAlpha(0.75);
            gc.setFont(Font.font(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.BOTTOM);
            String hovTxt;
            if (hoverSlope) {
                // Shift: the ANALYTIC slope here replaces the value
                // (differencing the baked polyline stair-stepped).
                // Native units — divided onto the DISPLAY axis when
                // the strip is warped (the slope you SEE, v16.0).
                double slope;
                if (laneWarped(activeLane)) {
                    // cross-rate view (v16.9, D121): the DISPLAYED
                    // slope — value scale AND x-axis both warp
                    double hStep = activeDistNative() ? 0.5 : 0.02;
                    double y1 = dispVal(activeLane, hovT + hStep,
                        BezierBaker.evalAt(lane, hovT + hStep));
                    double y0 = dispVal(activeLane, hovT - hStep,
                        BezierBaker.evalAt(lane, hovT - hStep));
                    double dx = warpMap != null
                        ? warpFwd(hovT + hStep) - warpFwd(hovT - hStep)
                        : 2 * hStep;
                    slope = Math.abs(dx) < 1e-12 ? 0 : (y1 - y0) / dx;
                } else {
                    slope = BezierBaker.slopeAt(lane, hovT);
                    if (warpMap != null && !surfStrip) {
                        double hStep = activeDistNative() ? 0.5 : 0.02;
                        double da = warpFwd(hovT + hStep)
                            - warpFwd(hovT - hStep);
                        slope = Math.abs(da) < 1e-12 ? 0
                            : slope * 2 * hStep / da;
                    }
                }
                hovTxt = String.format("%.2f %s/%s", FvdDesigner.z3(slope),
                    laneUnit(activeLane).trim(), xUnit());
            } else {
                hovTxt = String.format("%.2f%s", hv, laneUnit(activeLane));
                // CROSS-RATE view (v16.9, D121): hv is already the
                // warped display value — the NATIVE value rides
                // along in parentheses
                if (laneWarped(activeLane)) {
                    hovTxt += String.format(" (= %.2f%s)",
                        BezierBaker.evalAt(lane, hovT),
                        activeRatePM(activeLane) ? " °/m" : " °/s");
                }
            }
            // §5.2: the true value rides along once it visibly differs
            // (not on the influence surface — its deltas aren't the
            // quantity the true arrays measure; the overlays carry true)
            if (!surfStrip && activeInfl() == null) {
                hovTxt += trueSuffix(
                    sampleIdxAtAxis(tOff + warpFwd(hovT)),
                    activeLane, hoverSlope);
            }
            // CTRL: the snapped spot's Δ from the PREVIOUS NODE leads
            // the readout since v19.3 (D232, user spec — was the
            // absolute axis position, v16.16): it reads as the
            // transition length an insert there would create
            if (ctrlHover) {
                List<BezierBaker.Node> hl = active();
                double prevT = 0;
                for (BezierBaker.Node nd : hl) {
                    if (nd.t <= hovT) prevT = nd.t;
                    else break;
                }
                hovTxt = String.format("Δ%s=%.2f%s  ",
                    axisDist() ? "s" : "t",
                    warpFwd(hovT) - warpFwd(prevT), xUnit()) + hovTxt;
            }
            double[] hp = clampHover(hovTxt, hx + 8, hy - 6,
                editL(), editR(), editT(), editB());
            gc.fillText(hovTxt, hp[0], hp[1]);
            gc.setGlobalAlpha(1.0);
        }

        // axis-lock guide: the line the node rides along. Explicit Z/X
        // overrides the transient slide axis. Color = what the drag
        // CHANGES: vertical movement changes the VALUE (lane color);
        // horizontal movement changes TIME (light purple).
        int lock = !owns ? 0 : axisLock != 0 ? axisLock
                 : (dragged >= 0 ? shiftAxis : 0);
        if (sel >= 0 && lock != 0) {
            double lx = ltToX(lane.get(sel).t);
            double ly = valToY(activeLane, dispVal(activeLane,
                lane.get(sel).t, lane.get(sel).val));
            gc.setGlobalAlpha(0.5);
            gc.setLineWidth(1.5);
            if (lock == 1) {
                gc.setStroke(laneColor(activeLane));
                gc.strokeLine(lx, editT(), lx, editB());
            } else {
                gc.setStroke(FvdDesigner.TIME_COLOR);
                gc.strokeLine(editL(), ly, editR(), ly);
            }
            gc.setGlobalAlpha(1.0);
        }

        if (sel >= 0) drawHandles(gc, sel);

        // box-select rubber band
        if (!Double.isNaN(boxX0) && surfGesture == surfStrip) {
            double bx = Math.min(boxX0, boxX1), by = Math.min(boxY0, boxY1);
            double bw = Math.abs(boxX1 - boxX0), bh = Math.abs(boxY1 - boxY0);
            gc.setFill(Color.rgb(255, 255, 255, 0.07));
            gc.fillRect(bx, by, bw, bh);
            gc.setStroke(Color.rgb(255, 255, 255, 0.55));
            gc.setLineWidth(1);
            gc.strokeRect(bx, by, bw, bh);
        }

    }

    private void drawHandles(GraphicsContext gc, int idx) {
        List<BezierBaker.Node> lane = active();
        BezierBaker.Node n = lane.get(idx);
        double nx = ltToX(n.t);
        double ny = valToY(activeLane, dispVal(activeLane, n.t, n.val));
        outEdgeMarker = null;
        inEdgeMarker = null;

        for (boolean out : new boolean[]{ true, false }) {
            double[] k = knobPos(idx, out);
            if (k == null) continue;
            boolean dragging = (idx == dragHandleIdx && out == dragHandleIsOut);

            // S-slide rail: the line the knob is locked to (dashed,
            // extended across the plot — the clip trims it)
            if (dragging && app.slideKey) {
                double ddx = k[0] - nx, ddy = k[1] - ny;
                double len = Math.hypot(ddx, ddy);
                if (len > 1e-6) {
                    ddx /= len; ddy /= len;
                    gc.setGlobalAlpha(0.5);
                    gc.setStroke(Color.WHITE);
                    gc.setLineWidth(1);
                    gc.setLineDashes(4, 6);
                    gc.strokeLine(nx - ddx * 4000, ny - ddy * 4000,
                                  nx + ddx * 4000, ny + ddy * 4000);
                    gc.setLineDashes((double[]) null);
                }
            }
            boolean offscreen = k[0] < editL() || k[0] > editR()
                             || k[1] < editT() || k[1] > editB();

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
                gc.strokeOval(edge[0] - knobR(), edge[1] - knobR(),
                              knobR() * 2, knobR() * 2);
                if (out) outEdgeMarker = edge; else inEdgeMarker = edge;
            } else {
                gc.strokeLine(nx, ny, k[0], k[1]);
                gc.setFill(Color.WHITE);
                if (n.smooth) {
                    gc.fillOval(k[0] - knobR(), k[1] - knobR(), knobR() * 2, knobR() * 2);
                } else {
                    gc.fillRect(k[0] - knobR(), k[1] - knobR(), knobR() * 2, knobR() * 2);
                }
            }
            if (dragging) {
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font(10));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.setTextBaseline(VPos.TOP);
                // lengths read as FRACTIONS of the segment, matching the
                // node dialog (was percentages; what they mean lives in
                // the Help window, not here)
                gc.fillText(String.format("%s %.2f%s  dv %.2f",
                    out ? "out" : "in",
                    out ? n.outFrac : n.inFrac,
                    dragPresetName != null ? " " + dragPresetName : "",
                    out ? n.outDv : n.inDv),
                    FvdDesigner.clamp(k[0] + 8, editL(), canvas.getWidth() - 140),
                    FvdDesigner.clamp(k[1] - 16, editT(), editB() - 14));
            }
        }
        gc.setGlobalAlpha(1.0);
    }

    /** Where the segment (x0,y0)->(x1,y1) exits the plot rectangle. */
    private double[] clipToPlot(double x0, double y0, double x1, double y1) {
        double t = 1;
        double L = editL(), R = editR();
        double T = editT(), B = editB();
        if (x1 < L) t = Math.min(t, (L - x0) / (x1 - x0));
        if (x1 > R) t = Math.min(t, (R - x0) / (x1 - x0));
        if (y1 < T) t = Math.min(t, (T - y0) / (y1 - y0));
        if (y1 > B) t = Math.min(t, (B - y0) / (y1 - y0));
        return new double[]{ x0 + (x1 - x0) * t, y0 + (y1 - y0) * t };
    }
}
