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
 * The DIALOGS (Phase 1 milestone B split part 2, D54): Help,
 * Shortcuts, Rail colors and Options windows plus their small
 * control-wiring helpers — extracted verbatim from FvdDesigner.
 * Menu-local undo machinery stays on the shell (the Coaster menu
 * uses it too); dialogs reach it through `app`.
 */
class Dialogs {

    /** The shell: settings, menu-undo machinery, viewport cameras. */
    private final FvdDesigner app;

    Dialogs(FvdDesigner app) { this.app = app; }

    /**
     * The Help window (click "Help" in the menu bar). MAINTENANCE RULE
     * (user requirement): every new user-facing feature or control MUST be
     * added here, in the matching section, when it ships. Prioritize the
     * non-obvious. Layout: helpHead = section, helpKey = aligned
     * key/gesture row, helpText = prose for non-key behavior.
     */
    void showHelpDialog() {
        VBox box = new VBox(5);
        box.setPadding(new Insets(18));

        // small diagram: a transition with node, handles and the insert dot
        Canvas diagram = new Canvas(560, 110);
        GraphicsContext g = diagram.getGraphicsContext2D();
        g.setFill(Color.rgb(28, 31, 40));
        g.fillRoundRect(0, 0, 560, 110, 8, 8);
        g.setStroke(FvdDesigner.LANE_COLORS[0]);
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
        g.setFill(FvdDesigner.LANE_COLORS[0]);
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
        helpKey(box, "Resulting (toolbar)", "read-only derived curves across the WHOLE ride (thin dashed; a small gap at each section boundary is deliberate): with a force section active, pitch/yaw rates (°/s, geo colors); with a geo section active, felt vertical/lateral G (force colors). The picks live in a menu that stays open while you toggle them and closes when the mouse leaves it (or the button). Enabled curves get their OWN axis on the RIGHT edge of the plot — fitted to the visible view like the lanes — and hovering a curve shows its value there (Shift for its slope). No roll entry: the resulting roll rate IS the roll lane. These overlays are the one thing without a zero-line tint.");
        helpText(box, "Value axes auto-scale to the curves; every lane curve is "
            + "tinted toward its zero line. The dotted line after a "
            + "lane's last node is its held final value — click it to add a real node. "
            + "Dragging past a plot edge grows that axis, faster the farther past. The "
            + "divider resizes the graph; the corner button on the 3D view collapses it.");

        helpHead(box, "Sections");
        helpText(box, "The ride is a chain of SECTIONS sharing one time axis. The "
            + "section you are editing draws normally; the others are faint "
            + "strips — click one to edit it. Boundaries show as vertical "
            + "lines. Each section integrates from the previous one's end, at "
            + "its own design speed frozen when it was created.");
        helpText(box, "Three section kinds so far. FORCE sections are the classic "
            + "FVD model: vertical G, lateral G and roll rate lanes. GEO "
            + "sections specify the motion DIRECTLY: pitch (teal), yaw "
            + "(yellow) and roll rates (°/s) — gravity does not bend them, "
            + "the track goes exactly where the rates point it (zero rates "
            + "= dead straight, however the track is oriented). Selecting a "
            + "geo section recolors and relabels the lane buttons and axes "
            + "(Pitch/Yaw/Roll, all °/s with the roll snap step); rail "
            + "colors and stats still show the forces the geometry produces "
            + "at its design speed. STRAIGHT/CURVE sections have no lanes "
            + "at all — see the next section of this help.");
        helpText(box, "The whole graph always shows ONE kind of quantity — "
            + "the active section's: editing a geo section, force strips "
            + "display their RESULTING pitch/yaw/roll rates; editing a "
            + "force section, geo strips display the forces they produce. "
            + "So curves join up across every boundary, and B on a "
            + "boundary node RETURNS it there — the node takes the "
            + "neighboring section's value and slope at the boundary "
            + "(exact for same-kind neighbors, from the resulting curves "
            + "across kinds; also a button in the node's double-click "
            + "dialog).");
        helpKey(box, "Type badge", "the small force/geo/s/c tag at the top of each strip names its kind; click it for per-section actions: Split piece before this section (see Pieces below) and Delete section (the document keeps at least one; downstream sections re-chain from the new end; a piece's last section deletes the piece). A ◀ on the badge marks a section that runs back-to-front — that happens when its PIECE is reversed (Coaster menu; see Pieces): the section's lanes still read in the AUTHORED direction while the faint strip shows the train-direction picture, and pitched/banked seams show honestly.");
        helpKey(box, "⊕ at the ride end", "click the circled + on the dashed line past the last section to add one (its own undo step): a 1 s FORCE or GEO section HOLDING the piece's end state (end forces / end rates), or a STRAIGHT/CURVE section starting as a 10 m straight — all three continue seamlessly from whatever came before. Adding zooms the graph back out to the whole piece so the new (now active) section is under your cursor, wherever you were zoomed. ATTACH FLOATING PIECE appends another piece's whole section chain here (the floating placement is discarded — attaching means \"continue from this end\"). BRIDGE TO PIECE N spans the gap to the next piece with a smooth connector (see Pieces below). Any open menu dismisses on the next click, including on the graph.");

        helpHead(box, "Pieces");
        helpText(box, "A ride can be several PIECES — disconnected groups of "
            + "sections, each with its own position and heading. Pieces "
            + "CONNECTED BY BRIDGES read and edit as ONE track: the graph "
            + "shows every section on both sides of each bridge as one "
            + "timeline, clicking any strip or badge jumps to it without "
            + "losing your zoom, the ⊕ sits past the LAST piece's end, "
            + "boundary-matching (B/Shift+B) works across the bridge, and "
            + "the whole connected run renders full-color in 3D. Detached "
            + "pieces stay separate (and desaturated) — nothing is drawn "
            + "between them until you attach or bridge; deleting a bridge "
            + "splits the view back apart (the pieces themselves never "
            + "move). A \"Piece k/n\" toolbar button appears when there is "
            + "more than one piece (it lists them all — click to switch); "
            + "placement, split and reverse still act on the ACTIVE piece. "
            + "Plain-click (or Ctrl+click) any piece's track in 3D to jump "
            + "straight to it — this works in orbit and in hands-off POV.");
        helpKey(box, "Split", "a section badge > \"Split piece before this section\" cuts the piece there: the later sections move to a new piece placed at the EXACT boundary pose — position and full orientation — so splitting NEVER moves or tilts the track, wherever you cut (mid-hill, banked, anywhere). Splitting at an arbitrary point INSIDE a section — likely by selecting a node — is on the roadmap.");
        helpKey(box, "Attach", "⊕ menu > Attach floating piece: the chosen piece's sections continue this piece's chain (re-laid from its end; their shapes are untouched) and the empty piece disappears. Direct attach stays available alongside the future bridge sections.");
        helpKey(box, "Move", "toolbar \"Placement ▾\" (next to the piece selector) > Piece x/y/z + Piece yaw edit the ACTIVE piece's placement — the whole piece slides/turns rigidly; other pieces never move. Yaw is a DELTA on top of the piece's natural orientation: set it back to 0 and the original heading returns exactly. The spinners re-sync when you switch pieces. The piece being edited renders FULL-COLOR in 3D; the others are desaturated. (3D drag gizmos are on the roadmap.)");
        helpKey(box, "Reverse", "toolbar \"Placement ▾\" > \"Reverse piece (flip travel direction)\": the TRACK STAYS PUT and the train runs the piece the other way — the old exit becomes the entry. The strips reorder to train order and every section badge wears ◀; sections keep editing in their authored direction. Where a seam isn't level it re-joins with an honest kink; reversing again restores everything exactly. One undo step. (A piece containing a bridge refuses to reverse — delete the bridge first.)");
        helpKey(box, "Bridge", "⊕ menu > \"Bridge to piece\" > pick ANY other piece: a smooth connector from this piece's end to that piece's entry — position, direction, roll (its rate AND how fast that rate is changing), curvature, the RATE GRAPHS' slopes and even the SPEED all flow across both seams (the bridge's design speed eases onto the next piece's), so the joined pieces ride, read and EDIT as ONE track (see Pieces above). A reversed section on the far side is aimed at exactly as it really starts. Picking a piece that isn't next on the timeline moves it there (connecting means \"this one follows\"). The bridge FOLLOWS LIVE: move either piece or edit the track and it re-joins as you drag. RESIZE it by DRAGGING the vertical line at the END of its strip (the cursor becomes ↔ there): the next piece slides along and the bridge re-solves live — Ctrl snaps whole meters (the element-length step), a readout shows the length, and one Ctrl+Z undoes the whole drag. The toolbar \"Length (m)\" box (numbers only) does the same for typed/precise lengths (also on the badge menu as \"Optimize placement — continue the turn\"). All three place the next piece where the track would NATURALLY go — the seams' turn and roll carried forward, blended across the bridge, so the graphs change as little as possible: split a constant turn and re-bridge it and the bridge IS that same turn, with longer lengths simply carrying the next piece further around the circle; straight track continues dead ahead; ends at different pitches meet on a clean symmetric arc instead of dipping. The tangent sweep scales with the length so the bridge keeps its character. Badge > \"Bridge tangents…\" sets how straight it leaves/arrives (fresh bridges default to a full-gap sweep, which spreads the turning through the middle instead of spiking at the seams). A cramped bridge (big drop or clashing headings over a short gap) still swings hard INSIDE — smooth but fast; lengthen the gap or tangents to calm it. If the target piece is ever deleted, the bridge keeps its last shape and its badge shows \"bridge !\". A piece ending with a bridge can't be extended — its end is glued; extend the next piece instead. Deleting the bridge just disconnects: both pieces stay exactly where they are.");
        helpText(box, "Deleting a piece's only section deletes the piece. The POV "
            + "camera rides pieces in timeline order, jumping the gap between "
            + "them — bridge sections will close those gaps later in Phase 2.");

        helpHead(box, "Straight/curve sections");
        helpText(box, "A straight/curve section is built from ELEMENTS instead of "
            + "lanes — straight pieces (length in meters) and circular arcs "
            + "(radius + angle + direction) — the way you'd lay out a "
            + "station, brake run or lift approach. Its shape ignores speed "
            + "entirely (geometry is authored per METER of track); the "
            + "design speed only decides how much ride TIME it covers. With "
            + "one active, the strip shows a BAND per element (grey-blue = "
            + "straight, rose = curve) with its measurements on a chip, and "
            + "the felt G curves draw faintly behind (the lane buttons "
            + "disable — there is nothing to hand-draw here).");
        helpKey(box, "Click / double-click", "click a band to select its element; double-click (or right-click > Edit element…) opens its dialog: kind, length or radius/angle/direction, entry smoothing, roll. Direction is the curve plane: 0 = flat turn (positive angle = right), 90 = pull up, 180 = left, 270 = drop; a negative angle mirrors. The chip's arrow shows where the curve points.");
        helpKey(box, "Drag a boundary", "the dashed separator between elements (or the section end) resizes the element LEFT of it — a straight changes length, a curve re-angles at its radius; drag past the current end to EXTEND. Ctrl snaps to the Options length step (default 1 m); the readout shows the live size; dragging past the plot edge grows the view.");
        helpKey(box, "Roll", "each element rolls one of two ways (its dialog). GLOBAL BANK (°, the default at 0): the bank angle measured against the HORIZON is held — 0 keeps the rails level through slopes and turns (the track visibly twists to stay flat); a value banks a helix at a constant angle. Eased in over max(entry smoothing, 2 m), so bank never jumps. RATE (°/s): body roll like the roll lane — 0 here means the bank DRIFTS through pitched turns instead (and old v11.0-era saves load this way, keeping their shape). Positive matches the roll lane's direction; non-zero roll shows on the chip (∠ = bank, ↻ = rate). Note a rate element's total twist depends on the design speed.");
        helpKey(box, "Smoothing ◇", "the diamond on each internal boundary controls TRANSITION SMOOTHING: drag it sideways to set the blend length (drag to the boundary to snap it shut; filled = blending, hollow = sharp), or type it in the element dialog as \"entry smoothing\". The blend eases curvature over that many meters, half into each neighbor — an arc's TOTAL turn angle is preserved exactly, only the corner softens (the shaded zone shows the blend). New elements arrive with 2 m of smoothing; the first element's entry is the section seam and stays sharp.");
        helpKey(box, "Right-click a band", "Edit element… · insert straight/curve before/after · Delete element (a section keeps at least one). Right-drag still pans — the menu only opens on a clean click.");
        helpKey(box, "A / Delete", "append a 10 m straight after the selection · delete the selected element.");
        helpText(box, "Reversed straight/curve sections draw their elements in "
            + "TRAIN order (element 1 at the right edge). Rail colors, "
            + "stats, the Resulting overlays and the '+' hold all read the "
            + "felt forces at design speed, like every other kind.");

        helpHead(box, "Nodes");
        helpKey(box, "Click curve", "insert a node exactly ON it — the shape does not change. Keep the button down to drag the new node away in the same motion (Ctrl snaps as usual; insert + drag = one undo step). Holding Ctrl while HOVERING snaps the insert dot in time (per the horizontal snap reference; its value keeps riding the curve) — the click inserts at that snapped spot.");
        helpKey(box, "Right-click", "on a NODE: its editor dialog (same as double-click). On a SEGMENT: its lock menu — Unlocked / Straight / Horizontal (one segment at a time; same locks as Shift+F / Shift+H). Right-DRAG still pans: the menu only opens on a clean click.");
        helpKey(box, "Drag", "move freely; the readout shows Δt from the previous node + value. A lane's LAST node stops at the section end (where the other lanes already are) — release and grab it again to pull past and extend the section.");
        helpKey(box, "Ctrl", "snap time and value (steps set in Options; transitions can be as short as 0.01 s). Each axis can snap relative to the previous node or to the absolute grid — two Options checkboxes; defaults: time = Δt, value = absolute. The section end is a snap point of its own, and grid points right next to it are ignored — snapping near the end lands exactly ON it.");
        helpKey(box, "S (hold)", "slide along one axis from the start point — picked instantly from the dominant direction, switches if you pull the other way. Combines with Ctrl snapping: pressing S mid-drag with Ctrl held slides — it never triggers Ctrl+S save (no modifier combo fires during a drag).");
        helpKey(box, "Z / X", "sticky vertical/horizontal lock; overrides S, same key releases. Guides: lane color = value changing, light purple = time changing.");
        helpKey(box, "Arrows", "nudge by snap steps; Ctrl+arrow lands ON the grid; hold to glide.");
        helpKey(box, "Shift+click / box", "multi-select (drag a box on empty plot); dragging any member moves the group. The MAIN node (full ring) is the last one clicked — or the earliest in a box.");
        helpKey(box, "Delete", "remove the selected node(s): interior deletes bridge the gap, deleting the last node shortens the ride; node 0 stays. (Right-click no longer deletes — it opens the node's dialog.)");
        helpKey(box, "Double-click", "type the transition time, value and handle sizes exactly — typed handle sizes slide the knob along its current angle (like S). On the first node the ride's start speed is edited here too.");
        helpKey(box, "B · H · F", "smooth/broken · level the selection onto the MAIN node (one node: flatten its handles) · straighten between two adjacent selected nodes. On a node at a SECTION EDGE, B instead returns it to the boundary — the neighboring section's value AND slope (also a button in its double-click dialog); Shift+B does that for ALL THREE lanes' edge nodes at once (one undo step). Across MODES the match is computed EXACTLY from the neighbor's authored curves (converted between rates and forces at each side's own design speed), so pressing it on either side of any geo/force boundary — banked, rolling, sloped, any order — agrees: the rate graphs join and re-pressing changes nothing. It also works against a BRIDGE next door (matched from its compiled curves). If the FELT-G curves still show a small step at a seam, the two sections' design speeds differ there (the display is honest about it); that's the speed chain, not the match.");
        helpKey(box, "Shift+H", "LEVEL-LOCK the selection (2+ nodes): they jump onto the main node's value and the segments BETWEEN the selected nodes lock HORIZONTAL — drawn as twin lines with a tinted band between them. The run stays level through any edit: drag EITHER END vertically to move the whole line, interior nodes are pinned to it, and nodes inserted on it stay on the line. Selecting all of a locked stretch's nodes and pressing Shift+H again unlocks it; a lone selected endpoint unlocks too. Same key swaps a straight-locked segment to level and vice versa.");
        helpKey(box, "Shift+F", "straight-LOCK the segments BETWEEN the selected nodes (2+ — three selected nodes lock two segments): drawn as twin thin lines (no fill — that's the level lock) and each STAYS a line — move an endpoint and its line follows. Facing handle knobs are hidden (they can't change a line). Locks merely NEIGHBORING the selection are never disturbed — the toggle only adds, converts or removes locking between the selected nodes (all already locked = unlock). A lone selected endpoint unlocks its segment(s); a node whose two sides ended up at DIFFERENT slopes comes out broken (matching slopes — e.g. a node inserted on the line — stay smooth). Inserting on a locked segment keeps both halves locked.");
        helpKey(box, "A / Append", "+1 s at the ride end (active lane only; the others keep holding) — or a 1 s flat hold after the selected node (all lanes shift). On a straight/curve section the same button/key appends a 10 m straight element instead.");
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
        helpKey(box, "Orbit", "drag or MIDDLE-drag = rotate · scroll = zoom · plain CLICK the track = edit that piece + section in the graph · Shift+click (or hold C and click) the track = orbit around that spot · Ctrl+click the track = insert a node there.");
        helpKey(box, "T / Shift+T", "snap to a TOP or SIDE view with a near-orthographic lens (no perspective — for judging alignments). Top locks square to the grid; side centers on the selected node and aligns TANGENT to the track there (the track runs across the screen; nearest side kept — no selection = world-axis elevation). Shift+T AGAIN re-tangents to the current selection; T again in the top view returns to the previous perspective. Right-drag PANS without leaving the view; scroll zooms; orbiting away keeps the angle and brings perspective back.");
        helpKey(box, "Track markers", "small spheres on the track mark the active lane's nodes (selection shows white; Options toggle). Read-only for now — dragging them comes later.");
        helpKey(box, "Fly", "right-click the 3D view: WASD + mouse look, Shift fast, Ctrl slow, scroll = burst; right-click exits keeping the view.");
        helpKey(box, "POV", "Space. Starts hands-off: the camera sits on the track (following edits) while W/S and the S key stay with the graph — and a plain click on any track selects that piece + section for editing, just like orbit view. Right-click CAPTURES: mouse hides, W/S ride at simulated speed (Shift/Ctrl faster/slower), no mouse look; right-click releases. Scroll jumps along the track either way.");
        helpKey(box, "Space (captured)", "flips between fly (WASD) and POV (W/S) with the mouse still hidden.");
        helpText(box, "The light-purple line on the graph marks the POV camera "
            + "(Options toggle). The neon heartline hides itself while riding POV.");

        helpHead(box, "Menus & app.settings");
        helpKey(box, "File > Export NL2 CSV", "(Ctrl+E) writes the track for NoLimits 2's CSV import. Choose heartline (default) or rail center (the spline NL2 builds its track around), plus the point spacing; both remembered per-user.");
        helpKey(box, "Edit > Shortcuts", "grouped by category (Graph editor · View · File · History): click a key button, press the new combo (Esc cancels); ↺ resets a binding. Per-user.");
        helpKey(box, "Edit > Options", "POV FOV · fly+orbit FOV (shared so mode switches never zoom-jump) · mouse sensitivity (fly + orbit) · snap steps incl. the straight/curve element length (arrows walk the 1-2-5 series) · horizontal/vertical snap LOCAL vs GLOBAL (horizontal: local = Δt from the previous node, global = absolute ride time — default local; vertical: local = Δ value from the previous node, global = absolute values — default global) · neon heartline · 3D node markers · POV marker. Per-user.");
        helpKey(box, "Coaster menu", "heart offset, gauge, tie spacing, start speed (also on the first node's double-click dialog), rail colors — incl. the four G-force gradient stops, each a COLOR + G VALUE (defaults 0/1/3/5 g). Per-coaster, saved in the .fvd. (Piece placement + Reverse piece live in the toolbar's \"Placement ▾\" dropdown since v13.2 — y = height above the ground plane.)");
        helpKey(box, "Ctrl+Z in menus", "while a menu/dialog is open, Ctrl+Z / Ctrl+Shift+Z undo/redo the edits made IN it. Path-shaping values (heart, gauge, start, speed) ALSO land in the document history, so after closing, plain Ctrl+Z steps them back too.");

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 660);

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);   // non-modal: usable as a reference while editing
        dlg.setTitle("Help");
        dlg.setScene(new Scene(scroll));
        dlg.show();
    }

    static void helpHead(VBox box, String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
        VBox.setMargin(l, new Insets(10, 0, 2, 0));
        box.getChildren().add(l);
    }

    static void helpText(VBox box, String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(566);
        l.setPadding(new Insets(1, 0, 1, 10));
        box.getChildren().add(l);
    }

    /** One aligned "key/gesture -> what it does" row. */
    static void helpKey(VBox box, String term, String desc) {
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
     *  Edited rows get a ↺ button that restores the default. Persisted.
     *  Grouped by category into TWO titled columns (v10.3 — the flat
     *  list outgrew the screen): "Graph editor" fills the left column,
     *  the smaller categories (View / File / History) stack right. */
    void showShortcutsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Shortcuts");

        String[] capturing = { null };       // action id awaiting a key press
        List<Runnable> rowRefreshers = new ArrayList<>();
        Runnable refreshAll = () -> rowRefreshers.forEach(Runnable::run);

        java.util.LinkedHashMap<String, GridPane> cats = new java.util.LinkedHashMap<>();
        for (String[] a : AppSettings.ACTIONS) {
            String id = a[0];
            GridPane grid = cats.computeIfAbsent(a[3], k -> {
                GridPane g = new GridPane();
                g.setHgap(12);
                g.setVgap(8);
                return g;
            });
            Button combo = new Button();
            combo.setPrefWidth(110);
            Button reset = new Button("↺");   // circle-and-arrow: back to default
            reset.setTooltip(new Tooltip("Reset to default (" + a[2] + ")"));

            rowRefreshers.add(() -> {
                combo.setText(app.settings.shortcut(id));
                reset.setVisible(!app.settings.isDefaultShortcut(id));
            });
            combo.setOnAction(ev -> {
                capturing[0] = id;
                refreshAll.run();              // clear any stale "press a key…"
                combo.setText("press a key…");
            });
            reset.setOnAction(ev -> {
                app.settings.resetShortcut(id);
                refreshAll.run();
                app.rebuildTrack();                // stats-bar hint shows bindings
            });
            grid.addRow(grid.getRowCount(), new Label(a[1]), combo, reset);
        }
        refreshAll.run();

        VBox left = new VBox(6);
        VBox right = new VBox(6);
        boolean first = true;
        for (var entry : cats.entrySet()) {
            Label title = new Label(entry.getKey());
            title.setStyle("-fx-font-weight: bold; -fx-font-size: 13;");
            VBox block = new VBox(6, title, entry.getValue());
            (first ? left : right).getChildren().add(block);
            if (!first) VBox.setMargin(block, new Insets(6, 0, 0, 0));
            first = false;
        }
        HBox cols = new HBox(30, left, right);
        cols.setPadding(new Insets(14));

        Scene sc = new Scene(cols);
        sc.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (capturing[0] == null) return;
            ev.consume();
            if (ev.getCode().isModifierKey()) return;   // wait for the real key
            if (ev.getCode() != KeyCode.ESCAPE) {       // Escape cancels capture
                app.settings.setShortcut(capturing[0], comboString(ev));
            }
            capturing[0] = null;
            refreshAll.run();
            app.rebuildTrack();
        });
        dlg.setScene(sc);
        dlg.show();
    }

    /** Serialize a pressed key (with modifiers) into the combo format above. */
    static String comboString(KeyEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) sb.append("Ctrl+");
        if (e.isAltDown())     sb.append("Alt+");
        if (e.isShiftDown())   sb.append("Shift+");
        if (e.isMetaDown())    sb.append("Meta+");
        return sb.append(FvdDesigner.normalizeNumpad(e.getCode()).getName()).toString();
    }

    /** The ColorPicker gets its own dialog: embedded in the Coaster menu its
     *  "Custom Color" popup was owned by the menu popup, which auto-hides on
     *  click — the owner died and the app crashed. A real window owner fixes it. */
    void showRailColorDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Rail color");

        ColorPicker picker = new ColorPicker(app.railColor);
        picker.setOnAction(e -> {
            Color nc = picker.getValue();
            Color oc = app.railColor;
            if (!nc.equals(oc)) {
                app.pushMenuEdit(() -> picker.setValue(oc), () -> picker.setValue(nc));
            }
            app.railColor = nc;
            if (!app.railSolid) {           // picking a color implies solid mode
                app.railSolid = true;
                app.solidModeItem.setSelected(true);
            }
            app.markDirty();
            app.rebuildTrack();
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
                new SpinnerValueFactory.DoubleSpinnerValueFactory(FvdDesigner.G_MIN, FvdDesigner.G_MAX, app.gradStops[gi], 0.1));
            vs.setPrefWidth(75);
            vs.setEditable(true);
            vs.valueProperty().addListener((obs, o, v) -> {
                app.pushMenuEdit(() -> vs.getValueFactory().setValue(o),
                             () -> vs.getValueFactory().setValue(v));
                app.gradStops[gi] = v;
                app.markDirty();
                app.rebuildTrack();
            });
            ColorPicker gp = new ColorPicker(app.gradColors[gi]);
            gp.setOnAction(ev -> {
                Color nc = gp.getValue();
                Color oc = app.gradColors[gi];
                if (!nc.equals(oc)) {
                    app.pushMenuEdit(() -> gp.setValue(oc), () -> gp.setValue(nc));
                }
                app.gradColors[gi] = nc;
                app.markDirty();
                app.rebuildTrack();
            });
            grid.addRow(2 + i, new Label("Stop " + (i + 1)), vs, gp);
        }

        Scene sc = new Scene(grid);
        app.installMenuUndoKeys(sc);
        dlg.setScene(sc);
        app.beginMenuSession();
        dlg.setOnHidden(e -> app.endMenuSession());
        dlg.show();
    }

    /** Slider with live apply + persisted commit + menu-local undo (drags
     *  record one entry per gesture; clicks/keys one per change). */
    void wireOptionSlider(Slider s, Label valLbl, String fmt,
                                  java.util.function.DoubleConsumer apply,
                                  java.util.function.DoubleConsumer persist) {
        double[] dragStart = { s.getValue() };
        s.valueProperty().addListener((obs, o, v) -> {
            apply.accept(v.doubleValue());
            valLbl.setText(String.format(fmt, v.doubleValue()));
            if (!s.isValueChanging()) {
                persist.accept(v.doubleValue());
                app.pushMenuEdit(() -> s.setValue(o.doubleValue()),
                             () -> s.setValue(v.doubleValue()));
            }
        });
        s.valueChangingProperty().addListener((obs, was, is) -> {
            if (is) {
                dragStart[0] = s.getValue();
            } else {
                double from = dragStart[0], to = s.getValue();
                persist.accept(to);
                if (from != to) app.pushMenuEdit(() -> s.setValue(from), () -> s.setValue(to));
            }
        });
    }

    /** Settings spinner with persisted apply + menu-local undo. */
    void wireOptionSpinner(Spinner<Double> sp, java.util.function.DoubleConsumer persist) {
        sp.valueProperty().addListener((obs, o, v) -> {
            app.pushMenuEdit(() -> sp.getValueFactory().setValue(o),
                         () -> sp.getValueFactory().setValue(v));
            persist.accept(v);
        });
    }

    static Slider optionSlider(double min, double max, double value, double tick) {
        Slider s = new Slider(min, max, value);
        s.setPrefWidth(240);
        s.setShowTickMarks(true);
        s.setShowTickLabels(true);
        s.setMajorTickUnit(tick);
        return s;
    }

    /** Little ↺ that restores one Options row to its default (the change
     *  goes through the control, so it persists and is menu-undoable). */
    Button optionReset(Runnable restore) {
        Button b = new Button("↺");
        b.setTooltip(new Tooltip("Reset to default"));
        b.setOnAction(e -> restore.run());
        return b;
    }

    /** Spinner whose arrow buttons walk the 1-2-5 series in both directions
     *  (… 0.05, 0.1, 0.2, 0.5, 1, 2, 5 …); typing still allows any value. */
    static Spinner<Double> snap125Spinner(double min, double max, double initial) {
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

    static double nice125Next(double v) {
        double pow = Math.pow(10, Math.floor(Math.log10(v * 1.0001)));
        double m = v / pow;
        return (m < 1.5 ? 2 : m < 3.5 ? 5 : 10) * pow;
    }

    static double nice125Prev(double v) {
        double pow = Math.pow(10, Math.floor(Math.log10(v * 0.9999)));
        double m = v / pow;
        return (m > 7 ? 5 : m > 3 ? 2 : m > 1.5 ? 1 : 0.5) * pow;
    }

    /** Visual/interaction options (app-wide, persisted). */
    void showOptionsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("Options");

        Slider fovTrack = optionSlider(30, 110, app.settings.fov(), 20);
        Label fovTrackVal = new Label(String.format("%.0f°", app.settings.fov()));
        wireOptionSlider(fovTrack, fovTrackVal, "%.0f°",
            v -> app.viewport.povCam.setFieldOfView(v),
            app.settings::setFov);

        Slider fovFly = optionSlider(30, 110, app.settings.flyFov(), 20);
        Label fovFlyVal = new Label(String.format("%.0f°", app.settings.flyFov()));
        wireOptionSlider(fovFly, fovFlyVal, "%.0f°",
            v -> {
                app.viewport.flyCam.setFieldOfView(v);
                // never stomp the 1° ortho lens; it re-reads the setting on exit
                if (!app.viewport.orthoView) app.viewport.orbitCam.setFieldOfView(v);
            },
            app.settings::setFlyFov);

        Slider sens = optionSlider(0.03, 0.40, app.settings.flySens(), 0.1);
        Label sensVal = new Label(String.format("%.2f", app.settings.flySens()));
        wireOptionSlider(sens, sensVal, "%.2f",
            v -> { }, app.settings::setFlySens);   // fly + orbit read it live

        CheckBox heartCheck = new CheckBox("Show heartline (neon)");
        heartCheck.setSelected(app.settings.showHeartline());
        heartCheck.setOnAction(ev -> {
            boolean on = heartCheck.isSelected();
            app.pushMenuEdit(
                () -> { heartCheck.setSelected(!on); app.settings.setShowHeartline(!on); app.rebuildTrack(); },
                () -> { heartCheck.setSelected(on); app.settings.setShowHeartline(on); app.rebuildTrack(); });
            app.settings.setShowHeartline(on);
            app.rebuildTrack();
        });

        CheckBox markCheck = new CheckBox("Node markers on the 3D track");
        markCheck.setSelected(app.settings.trackMarkers());
        markCheck.setOnAction(ev -> {
            boolean on = markCheck.isSelected();
            app.pushMenuEdit(
                () -> { markCheck.setSelected(!on); app.settings.setTrackMarkers(!on); app.editor.refreshMarkers(); },
                () -> { markCheck.setSelected(on); app.settings.setTrackMarkers(on); app.editor.refreshMarkers(); });
            app.settings.setTrackMarkers(on);
            app.editor.refreshMarkers();
        });

        Spinner<Double> snapTSp = snap125Spinner(0.01, 2.0, app.settings.snapT());
        wireOptionSpinner(snapTSp, app.settings::setSnapT);
        Spinner<Double> snapGSp = snap125Spinner(0.01, 2.0, app.settings.snapG());
        wireOptionSpinner(snapGSp, app.settings::setSnapG);
        Spinner<Double> snapRollSp = snap125Spinner(0.5, 50, app.settings.snapRoll());
        wireOptionSpinner(snapRollSp, app.settings::setSnapRoll);
        Spinner<Double> snapLenSp = snap125Spinner(0.1, 20, app.settings.snapLen());
        wireOptionSpinner(snapLenSp, app.settings::setSnapLen);

        // snap axis references (v10.2; Local/Global wording v10.3):
        // HORIZONTAL (time) — Local = Δt from the previous node,
        // Global = the absolute ride-time grid; VERTICAL (value) —
        // Local = Δ from the previous node's value, Global = absolute
        // values. Defaults: horizontal LOCAL, vertical GLOBAL (the
        // historical behavior). Mutually exclusive per axis.
        ToggleGroup hSnapGrp = new ToggleGroup();
        RadioButton hLocal = new RadioButton("Local");
        RadioButton hGlobal = new RadioButton("Global");
        hLocal.setToggleGroup(hSnapGrp);
        hGlobal.setToggleGroup(hSnapGrp);
        hLocal.setTooltip(new Tooltip("Snap the transition length — Δt from the previous node"));
        hGlobal.setTooltip(new Tooltip("Snap to the absolute ride-time grid (the axis ticks)"));
        (app.settings.snapTAbsolute() ? hGlobal : hLocal).setSelected(true);
        Runnable hApply = () -> {
            boolean glob = hGlobal.isSelected();
            app.pushMenuEdit(
                () -> { (glob ? hLocal : hGlobal).setSelected(true); app.settings.setSnapTAbsolute(!glob); },
                () -> { (glob ? hGlobal : hLocal).setSelected(true); app.settings.setSnapTAbsolute(glob); });
            app.settings.setSnapTAbsolute(glob);
        };
        hLocal.setOnAction(ev -> hApply.run());
        hGlobal.setOnAction(ev -> hApply.run());

        ToggleGroup vSnapGrp = new ToggleGroup();
        RadioButton vLocal = new RadioButton("Local");
        RadioButton vGlobal = new RadioButton("Global");
        vLocal.setToggleGroup(vSnapGrp);
        vGlobal.setToggleGroup(vSnapGrp);
        vLocal.setTooltip(new Tooltip("Snap the CHANGE in value — Δ from the previous node"));
        vGlobal.setTooltip(new Tooltip("Snap to the absolute value grid (0.0, 0.1, 0.2 …)"));
        (app.settings.snapVRelative() ? vLocal : vGlobal).setSelected(true);
        Runnable vApply = () -> {
            boolean loc = vLocal.isSelected();
            app.pushMenuEdit(
                () -> { (loc ? vGlobal : vLocal).setSelected(true); app.settings.setSnapVRelative(!loc); },
                () -> { (loc ? vLocal : vGlobal).setSelected(true); app.settings.setSnapVRelative(loc); });
            app.settings.setSnapVRelative(loc);
        };
        vLocal.setOnAction(ev -> vApply.run());
        vGlobal.setOnAction(ev -> vApply.run());

        CheckBox povMark = new CheckBox("POV position marker on the graph");
        povMark.setSelected(app.settings.povMarker());
        povMark.setOnAction(ev -> {
            boolean on = povMark.isSelected();
            app.pushMenuEdit(() -> { povMark.setSelected(!on); app.settings.setPovMarker(!on); app.editor.refresh(); },
                         () -> { povMark.setSelected(on); app.settings.setPovMarker(on); app.editor.refresh(); });
            app.settings.setPovMarker(on);
            app.editor.refresh();
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
        grid.add(new Label("Snap: element length (m)"), 0, r);
        grid.add(snapLenSp, 1, r);
        grid.add(optionReset(() -> snapLenSp.getValueFactory().setValue(AppSettings.SNAP_LEN_DEFAULT)), 3, r++);
        grid.add(new Label("Horizontal snap (time)"), 0, r);
        grid.add(new HBox(12, hLocal, hGlobal), 1, r, 2, 1);
        grid.add(optionReset(() -> {
            if (!hLocal.isSelected()) { hLocal.setSelected(true); hApply.run(); }
        }), 3, r++);
        grid.add(new Label("Vertical snap (value)"), 0, r);
        grid.add(new HBox(12, vLocal, vGlobal), 1, r, 2, 1);
        grid.add(optionReset(() -> {
            if (!vGlobal.isSelected()) { vGlobal.setSelected(true); vApply.run(); }
        }), 3, r++);
        grid.add(heartCheck, 0, r++, 3, 1);
        grid.add(markCheck, 0, r++, 3, 1);
        grid.add(povMark, 0, r, 3, 1);

        Scene sc = new Scene(grid);
        app.installMenuUndoKeys(sc);
        dlg.setScene(sc);
        app.beginMenuSession();
        dlg.setOnHidden(e -> app.endMenuSession());
        dlg.show();
    }

}
