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
        helpText(box, "You design FORCES, not shapes: the lanes — vertical G, "
            + "lateral G and roll rate (or pitch/yaw/roll rates on a geometric "
            + "section) — integrate into the 3D track, so the graphs say what "
            + "the rider feels and the geometry follows. Signs: +Gl is the "
            + "body pushed RIGHT and +yaw turns LEFT — the same left turn, "
            + "two viewpoints; yaw ANGLES (piece, next-piece, seam) read "
            + "left-positive the same way. Pick a lane with the "
            + "colored buttons, keys 1/2/3, or by clicking a dimmed curve; "
            + "every edit rebuilds the track live.");
        helpText(box, "Ctrl+Z / Ctrl+Shift+Z undo and redo document edits — the "
            + "curves plus everything that shapes the path (heart, gauge, "
            + "start point, start speed). File > New/Save/Save As manage the "
            + ".fvd (the title wears * while unsaved; the fly/orbit camera "
            + "pose saves with the file and opening one brings the view "
            + "back). A file holds any number of COASTERS (Coaster menu). "
            + "File > New starts an EMPTY file and opens the START SCREEN in "
            + "the graph area for its first coaster: ONE form — Placement, "
            + "the coaster's name / starting speed / friction, and the "
            + "SECTION TYPE tiles (Force/Geometric — pre-selected — or "
            + "Straight/Curve; the picked type's options appear beneath "
            + "them). The track is visible from the start; every setting applies "
            + "LIVE and each is its own Ctrl+Z step — Ctrl+Z right after "
            + "opening removes the new piece/coaster; selecting another "
            + "piece hides the panel (come back and it's as you left it). "
            + "File > Export "
            + "(Ctrl+E) hands a coaster — or ONE PIECE of it, from its "
            + "dropdowns — to NoLimits 2. A damaged file "
            + "loads what it can and reports how many lines were "
            + "skipped — saving writes it clean. Keys shown here are "
            + "defaults — rebind anything in Edit > Shortcuts.");

        helpHead(box, "Graph editor");
        helpKey(box, "View", "scroll = zoom the axis around the cursor (fully out resumes auto-fit) · right-drag = pan · the divider resizes the graph, and the corner button on the 3D view collapses it (a collapsed editor is fully unloaded — a busy plot costs nothing while hidden).");
        helpKey(box, "Hover", "on a curve: the value there (Shift = slope; with Ctrl the insert dot snaps in time and the readout leads with the Δ from the previous node — the transition an insert there would create). On a node: a grey ring + its Δt and value (Shift = absolute position + Δ). Wherever a thin TRUE stroke exists, readouts append the true value in parentheses.");
        helpKey(box, "Resulting ▾", "read-only derived curves across the whole connected run: over a force section, the resulting pitch/yaw rates (plus an optional roll/bank pick); over a geo section, the felt Gs. They fit their own right-edge axis, and hovering one reads its value (Shift = slope) — the roll/bank pick normalizes on its OWN scale whenever its units differ from the axis (geo view, absolute bank), so a big roll rate never stretches the G axis. \"Resulting graph settings…\" picks how they present: °/s or °/m, roll axis local/global, rate vs absolute bank (bank draws in red-orange). A checked box fills with its curve's color. Every file opens with all picks OFF.");
        helpKey(box, "True strokes", "a THIN solid line splitting off a lane is the REAL ride where it differs from the design curve — see Speed & conflicts below.");
        helpText(box, "Value axes auto-fit to the visible curves (frozen during "
            + "drags). The dotted tail after a lane's last node holds its "
            + "final value — click it to add a node there. Dragging past any "
            + "plot edge grows the view, faster the farther past.");

        helpHead(box, "Nodes");
        helpKey(box, "Click curve", "insert a node exactly ON the curve — the shape doesn't change; keep the button down to drag it away in the same gesture (one undo step). Ctrl while hovering pre-snaps the insert spot in time.");
        helpKey(box, "Drag", "free move; the readout shows Δt + value (Shift mid-drag swaps in absolute + Δ). Hold S to slide along one axis (picked from your motion, switches if you pull the other way); Z / X lock vertical/horizontal until pressed again; Ctrl snaps by the Edit > Snapping steps — the section end is a snap magnet of its own. Arrows nudge by snap steps (Ctrl+arrow lands on the grid; hold to glide). A lane's last node stops at the section end; release and re-grab it to extend past.");
        helpKey(box, "Select", "Shift+click adds; box-drag on empty plot selects a group (drags move it together; the full-ring node is the main one). Delete removes — interior deletes bridge the gap keeping the neighbors' handle slopes; the last node shortens the ride; node 0 stays.");
        helpKey(box, "Right-click", "a NODE: its options menu — Edit node…, smooth/broken (boundary match on edge nodes), match all lanes, level / level-lock, straighten / straight-lock, split section here, split track piece here, connect with previous/next section, delete — only ops that apply appear, with your current shortcuts in parentheses. A SEGMENT: its lock menu (Unlocked / Straight / Horizontal). Right-drag still pans; menus open on a clean click.");
        helpKey(box, "Double-click", "the node editor: transition time, value, and each handle's LENGTH (fraction) and SLOPE (lane units per axis unit — one slope box on a smooth node, one per handle when broken; typing a slope re-aims the handle keeping its length), with grey twins previewing the absolute results. It edits LIVE — every parseable change in a box updates the track at once (a blanked box just holds), each box has ▲/▼ steppers and the mouse WHEEL steps it (Shift ×10, Ctrl ×0.1), and every edit is a normal Ctrl+Z step (a typing burst in one box counts once) — Ctrl+Z works with the dialog focused and the boxes follow. Close just closes. Node 0's dialog also edits the ride's START SPEED (its only home since the Coaster-menu row left) — later sections' node 0 takes the initial design speed instead.");
        helpKey(box, "B / Shift+B", "smooth/broken toggle — but on a SECTION-EDGE node it matches the boundary instead: value AND slope from the neighbor (Shift+B = all three lanes at once, one undo step). The match is exact across every mode/axis/unit combination, first re-freezes the downstream design speed to the seam, and where a design/true split exists it lines up the REAL graphs. Re-pressing changes nothing; whenever B refuses, the stats bar says exactly why.");
        helpKey(box, "H / Shift+H", "level the selection onto the main node (one node: flatten its handles) · LEVEL-LOCK the segments between selected nodes — twin lines + tinted band, either end drags the whole line, inserted nodes stay on it; re-apply to unlock.");
        helpKey(box, "F / Shift+F", "straighten the segment between two adjacent selected nodes onto the chord · STRAIGHT-LOCK it — it stays a line through any edit (facing knobs hide; they can't change a line); re-apply to unlock.");
        helpKey(box, "A", "append 1 s of held value to the active lane (with a selection: insert a 1 s hold after it, all lanes shifting). On a straight/curve section: append a 10 m straight.");
        helpKey(box, "Ripple", "toolbar checks — a drag also shifts every later node of the lane in time and/or value.");

        helpHead(box, "Handles");
        helpKey(box, "Drag", "free (angle + length) · hold S = slide along the current line, length only · Ctrl snaps the length to ease presets — cubic 0.33, sine 0.36, quintic 0.47, circle 0.55 — plus 0.05 steps up to 1.5.");
        helpText(box, "Round knob = smooth (the partner mirrors proportionally), "
            + "square = broken; B toggles. Handle lengths are FRACTIONS of the "
            + "segment (0.33 = a third), so re-timing nodes keeps curve "
            + "shapes; dv is the handle's value offset. An off-scale handle "
            + "draws as a dashed stub — click its hollow marker to expand the "
            + "scale, click elsewhere to restore.");

        helpHead(box, "Sections");
        helpText(box, "The ride is a chain of SECTIONS on one shared axis — "
            + "seconds, or METERS over a distance-domain or straight/curve "
            + "section; every other strip warps to match, so the ride always "
            + "reads in one unit (the axis label says which). The active "
            + "section edits normally; click a faint strip to switch. Each "
            + "section integrates from the previous one's end at its own "
            + "design speed. The plot always shows ONE mode's quantities — "
            + "the active section's — so curves join across boundaries.");
        helpKey(box, "Kinds", "FORCE = the classic FVD lanes (vertical G, lateral G — positive = the body pushed RIGHT, so a right turn reads negative — and roll rate). GEOMETRIC = pitch (teal), yaw (yellow) and roll rates integrated directly — gravity never bends the shape; felt forces are recorded at design speed. STRAIGHT/CURVE = an element list instead of lanes (its own section below). BRIDGES connect pieces (see Bridges).");
        helpKey(box, "Type badge", "the force/geo/s/c tag atop each strip — the section's SETTINGS BUTTON (it lights up under the mouse). Click it for the section's menu: info line (design speed, domain, axes, ≠v), the view toggles, Orientation settings…, Resolve conflict, Influence, Convert…, Delete section (deleting a piece's FIRST section re-anchors the piece so the remaining track stays exactly put; the ride entry's start speed follows). An inactive badge's first click just activates the section. A ◀ marks a legacy reversed-flag section from old files — modern reversal rebuilds forward sections instead.");
        helpKey(box, "Domain", "each force/geo section authors over TIME or DISTANCE (the badge wears ·m for meters; lanes keep their units — G, °/s — over the other axis). \"View in time/distance domain\" is a pure VIEW and the exact-shape tool: the axis flips, the strip warps, and you edit straight through it — snaps and readouts in the units you see. The Convert… dialog's Domain row instead REFITS the lanes into the other unit through the density preview.");
        helpKey(box, "Axes & units", "\"Orientation settings…\" sets what the lanes rotate about. Roll: LOCAL (classic — bank rides along with pitched turns) or GLOBAL (the lane drives bank-vs-horizon; with ABSOLUTE behavior the lane IS the bank angle in degrees, unwrapped past ±180°). Geo yaw/pitch: LOCAL body rates or GLOBAL heading/elevation rates. Then \"Switch to This Orientation\" (same graphs, the shape changes) or \"Bake This Orientation\" (graphs convert, the shape stays). RATE UNITS make rate lanes °/s or °/m — a °/m profile turns the same angles into the same meters at any speed (force G lanes are targets and stay G). \"View rates in °/m|°/s\" edits in the other units through the warp, native axis on the right. B/Shift+B match across every combination of all of these; only vertical track refuses (no defined heading/bank — the stats bar says so).");
        helpKey(box, "Convert…", "ONE dialog for every re-authoring: Whole or Part, then Type, Domain, Rate units, Roll axis, Yaw/pitch axis, Roll behavior — each row starts at the current setting, ↺ resets it, and grayed choices explain themselves on hover. PART opens a draggable split line on the strip (readout in %, snaps onto the selected lane's nodes; the kept side stays bit-exact; click a side, then the [Convert] chip). Everything commits through the NODE DENSITY preview — it opens on \"Optimal placement\" (accuracy) every time; uncheck it for even spacing — refitting LIVE against the dashed originals; Apply is one undo step, Cancel restores exactly. Fits are area-matched: crank the density and the shape converges onto what you had. Conflicts, pins and speeds always carry; reversed sections refuse.");
        helpKey(box, "Influence", "badge menu — sculpt a section's RESULTING other-mode graphs while keeping its design lanes: a force section gains Δpitch/Δyaw lanes, a geo section ΔGv/ΔGl, and resulting = base + delta EXACTLY (speeds untouched; roll takes no deltas). The deltas edit around zero with the resulting curves dotted behind (hover them for values). B/Shift+B on a delta EDGE node sets value + slope so the resulting graphs line up with the neighbor's real curves (re-press to converge; no neighbor on that side = back to zero influence). Exits, one undo step each: \"Bake influence into design graphs…\" (folds in through the density preview) or \"Discard influence\" (exact restore). Influenced sections SPLIT like any lane section (from a design-lane node) — both halves stay influenced and the track stays put exactly, whatever the deltas (the right half carries a split anchor; its speed pair reads divergent by design). Speed controls work on influenced sections too, and B on a design-lane edge node lines the TRUE curves up with the neighbor.");
        helpKey(box, "⊕ (ride end)", "the circled + past the last section adds one. \"Add force/geometric section…\" opens a dialog — type, domain, axes, rate units, with the new section HOLDING the ride's end state (1 s / 10 m), plus the starting design speed behind a padlock: locked follows the seam; unlocked is a DELIBERATE speed conflict, arriving with its real graphs already lined up. \"Add floating section…\" opens the START SCREEN for a NEW detached PIECE (defaults 5 m past this piece's end at its heading and true speed; the piece exists — invisible — until you pick its section type; every setting is a Ctrl+Z step, and Ctrl+Z right after opening removes it; \"Add piece\" keeps it, Cancel removes it). After a conflicted end the menu forks: Continue speed conflict / Transition to real. \"Add straight/curve\" appends a 10 m straight — the seamless continuation. Also here: Attach floating piece and Bridge to piece N (pieces are listed by their LENGTH).");

        helpHead(box, "Straight/curve sections");
        helpText(box, "Elements instead of lanes: straights (length) and arcs "
            + "(radius, angle, direction — 0 = flat turn with positive angle "
            + "to the LEFT, 90 = pull-up; negative mirrors), authored per "
            + "METER — the shape ignores speed entirely. The strip shows a "
            + "band per element with a measurement chip; the felt-G curves "
            + "draw faintly behind (lane buttons disable).");
        helpKey(box, "Edit", "click a band = select · double-click or right-click > Edit element… = its dialog (kind, size, entry smoothing, roll) · right-click also inserts straights/curves before/after and deletes · drag a dashed separator (or the section end) = resize the element left of it, Ctrl snapping by the element-length step; drag past the end to extend · A appends a 10 m straight · Delete removes the selected element.");
        helpKey(box, "Roll", "per element: GLOBAL BANK ° holds the bank against the horizon — 0 keeps the rails level through slopes and turns; eased in over max(entry smoothing, 2 m). RATE °/s is body roll like the roll lane — 0 lets the bank drift through pitched turns; its total twist depends on design speed. Every roll transition eases with zero roll-rate slope at both ends (no jolt — the same smoothness as seam smoothing). Non-zero roll shows on the chip (∠ bank, ↻ rate).");
        helpKey(box, "Smoothing ◇", "the diamond on each internal boundary sets that transition's blend length — drag it (snaps shut at the boundary; filled = active; the shaded band is the blend zone) or type it as \"entry smoothing\". Curvature eases over that many meters, half into each neighbor; an arc's total turn angle is always preserved. The diamond ON the section's entry is the ENTRY ROLL EASE — the bank ease into the first element (drag the diamond or its band's edge = the length from the start; 0 = an instant snap; new sections start at 2 m); the curvature seam itself stays sharp.");

        helpHead(box, "Speed & conflicts");
        helpText(box, "Every section owns a DESIGN speed, frozen when it was "
            + "made; the TRUE ride speed comes from energy and friction. "
            + "Sections chained inside one piece re-freeze themselves to the "
            + "real arriving speed automatically, so lasting drift lives at "
            + "piece boundaries, deliberate custom speeds, tilted pieces and "
            + "bridge stitches.");
        helpKey(box, "≠v & true strokes", "a drifted section's badge wears ≠v (its info line shows the arriving speed) and thin TRUE strokes split off its curves — the same quantities at the speed the train really arrives with. POV, the stats line and the top-speed stat already ride true values. Inside a smoothed strict seam's window the strokes also show the real blended transition on both sides.");
        helpKey(box, "Resolving", "badge > Resolve conflict, left to right, one section at a time: \"Convert Real to Design (keep track shape)…\" refits the lanes at the true entry speed — the track stays put and the strokes vanish; \"Set Design to Real (keep graphs, reshape)\" keeps your curves and re-derives the geometry at the real speed (a tilted piece folds its tilt in first). Then Shift+B on the first node lines the seam up. Only a live bridge DIRECTLY before a section blocks resolving — convert the bridge first. Deliberate (pinned) speeds are matched around, never silently closed. Badge > \"Set initial design speed…\" types a starting speed directly any time (a custom value pins as a deliberate conflict; on the ride entry it writes the Start-speed spinner).");
        helpKey(box, "Friction", "Coaster menu: FRICTION (m/m) decelerates by μ·g per meter; AIR RESISTANCE (m⁻¹) by k·v². New coasters start at 0.03 and 1.0e-4 — about NoLimits 2; zero both for the loss-free classic model. On, they shape every design profile and the true ride, and losses are paid BOTH ways (see Reverse).");
        helpKey(box, "+V speed strip", "the +V box (bottom-left) opens the SPEED CONTROL LINE above the time axis (always collapsed at first — the ▲ arrow, top-edge divider or Edit button expand it). Click the line = a diamond; double- or right-click a diamond (or the line after it) = the stretch to the next diamond becomes a SPEED CONTROL and its options open (the same clicks reopen them). Drag a control's BODY to move it, its end diamond to resize. A quick double-click on the line replaces the fresh diamond with a control spanning its neighbors (the right-click create). Constant Speed holds/clips to a target — Max = ceiling (snaps down), Min = floor (snaps up), both = hold exactly. Constant Acceleration approaches the target at a FELT longitudinal G instead (launches/brakes) — Acceleration and Deceleration sit on one line with a 🔗 LINK button between them (neon yellow while linked): linked (default) = one magnitude both ways — the Deceleration side grays out and just mirrors every Acceleration edit live; unlink and the brake side gets its own editable value. Complex swaps the constant for an editable graph (green = speed, blue = accel); an unlinked complex acceleration control has TWO graphs — the ▸ Edit Acceleration / Edit Deceleration buttons pick which one the strip shows and edits (the other draws dimmed behind; accel first). Controls act on design AND real speed alike, so a control inside a conflicted stretch closes the ≠v split at its clamp.");
        helpKey(box, "Strip editing", "click-DRAG on a free stretch of the line to create a control from press to release (Ctrl snaps; a plain click drops a diamond instead); drag the START diamond to move a control, the END diamond to resize (its graph stretches along) — or drag a complex graph's LAST NODE to resize the control WITHOUT stretching: only that node moves, the box follows it; ripple time/distance shifts later diamonds; Ctrl snaps; click selects, Delete removes. The strip's TOP EDGE is a grabbable divider — drag it to any height (small enough collapses to the line; drag the thin edge up to expand; the ▲ arrow still toggles). The tall view shows the resulting TRUE speed (green dotted, hover reads it — hidden inside COMPLEX speed controls, whose graph IS the speed) with ZERO on the control line itself; a control graph ending early holds its value to the control's end (dotted, clickable to append); with time ripple on, a control's LAST node never moves horizontally — controls are a hard box; a complex constant-speed control shows its resulting LONGITUDINAL G inside its span (blue dotted, hover for the value / Shift for slope; the instant boundary snaps are trimmed), and complex graphs run the REAL editor — every gesture, snap, lock, readout, the typed node dialog (double-click a node) and the Ripple checks work exactly like the main plot, at smaller nodes: click the curve to insert (Ctrl pre-snaps the white dot), box-drag or Shift+click selects several nodes and the group drags/deletes together, arrows nudge, S slides / Z/X lock an axis (with the guide lines), H levels, F straightens ahead (Shift+F/Shift+H lock), handles slide on S and snap to ease presets on Ctrl, off-screen handles show a dashed stub whose edge marker expands the scale. B on a constant-speed graph's END node snaps its value AND slope onto the real speed outside the control. Controls can never leave their section; bridges take none. In 3D every control wears a colored plate down the tie centers.");
        helpKey(box, "Stalls", "where the true ride bottoms out at the crawl floor, everything after turns TRANSPARENT — the train never really gets there — and the POV treats that point like the END of the track: riding on jumps to the next piece (or back to the start when it is the last one); riding backward lands on the last solid sample. A Min-flag speed control (a lift) over the climb is the fix.");

        helpHead(box, "Pieces & strict seams");
        helpText(box, "A ride can be several PIECES, each placed independently. "
            + "Pieces joined by bridges or strict seams read and edit as ONE "
            + "run — one timeline in the graph, boundary matching across the "
            + "joins, full color in 3D, the ⊕ at the run's end. Detached "
            + "pieces stay desaturated. Switch with the \"Piece k/n\" toolbar "
            + "button or by plain-clicking any track in 3D; placement, split "
            + "and reverse act on the ACTIVE piece. Deleting a piece's last "
            + "section deletes the piece.");
        helpKey(box, "Move / tilt", "toolbar \"Track Piece ▾\" (the panel drags by its body): x/y/z, and the piece's REAL starting orientation — yaw (heading, + = left), padlocked PITCH (elevation, ±90) and ROLL (bank) — absolute numbers, so a split piece's boxes read exactly where its track starts. Past straight up/down the numbers flip the way NoLimits does (heading and bank +180, elevation mirrored) while a ring drag keeps turning smoothly. How a tilt acts is \"Lock Track Shape\" below; rows a connecting bridge owns gray out — edit through the bridge. The 3D gizmos drag all of this directly.");
        helpKey(box, "Lock Track Shape", "Track Piece panel, checked by default. CHECKED: pitch/roll edits move the REAL orientation only — the shape is preserved rigidly, the design orientation stays put (a design/real split opens: ≠v, thin strokes, and a grey design-orientation readout under the spinners), and moving/rotating the piece carries EVERY connected piece after it along as one rigid body (bridges and strict seams included). UNCHECKED: edits move design AND real together — the shape re-solves under gravity, and downstream updates naturally. Toggling never moves anything by itself; any design/real offset present at the toggle simply rides along.");
        helpKey(box, "Design ↔ Real", "one button line, shown only while the design and real orientations differ: \"Design → Real Orientation\" moves the design onto the real one (shape re-solves at the real frame, conflict closed); \"Real → Design Orientation\" rotates the piece (and its carried chain) back onto the authored orientation (shape preserved, conflict closed).");
        helpKey(box, "Split / attach", "a seam line's \"Split track at seam\" (or a node's split entries — any node, mid-section too) cuts at the EXACT compiled pose: splitting never moves or tilts the track, wherever you cut — a tilted piece's second half simply carries the same design/real conflict onward, and its Track Piece boxes show its real starting orientation. ⊕ > Attach floating piece connects a piece on through a STRICT seam with its shape frozen; the seam's locked axes seed from that piece's placement padlocks, and the settings pop up. \"Connect with previous/next section\" on a seam's edge nodes merges two LIKE sections into one (a B-matched seam merges losslessly).");
        helpKey(box, "Strict seams", "a strict seam makes the track after it behave as if everything before were a bridge: upstream edits can move and re-aim the follower but never RESHAPE it. Seam lines highlight on hover — click (the smoothing ◇ too) for the menu: \"Strict seam orientation…\" (dark panel, one row per axis: a 🔒 LOCK — red while locked — holds the spinner's angle, 🔓 follows the seam; locking never snaps (it holds the current angle); ↺ returns the value from when the window was opened — or, for an axis locked during this session, the stored placement's angle (an attached piece's pre-attach orientation); Apply keeps, Cancel reverts; the window drags and hides with the app), \"Enable/Disable smoothing\" (not on the edge of an s/c section), \"Make smoothing real…\" (grayed with the reason when it can't run), \"Un-strict seam\", \"Split track at seam\". New strict seams arrive with ALL THREE axes locked at their current angles — except directly attaching a piece, which keeps yaw following. SMOOTHING spreads the junction geometrically over the window — size it by dragging the ◇ on the seam line or either band edge (Ctrl snaps meters; near-zero snaps off); nothing outside the window moves, and a hard kink in a short window is honestly violent — widen it. If the window itself opens a conflict, the band tints RED: shrink it. MAKE SMOOTHING REAL converts the whole blend window into an editable section of the UPSTREAM section's type (through the density preview, at the real speed), guarded by a fresh strict seam at the window's end so nothing downstream reshapes — the old seam is gone and the window is yours to edit; the guard seam stays (a fit can't land exactly), un-strict it when you're happy. Speed controls elsewhere on the seam's sections are fine — only one touching the window itself refuses; influence follows the same rule (deltas zero across the window convert, and the kept halves stay influenced). The double line wears colored CARETS pointing the way each lane's rate graph spikes at the seam (small caret = small kink; smoothing never moves them). In 3D, the ball ON the junction wears rings for the discontinuous axes (drag = re-aim, Ctrl snaps degrees; double-click = settings). Speeds stay independent across the seam, so upstream edits open an honest ≠v; B/Shift+B match the real graphs across it, and the resolve ops work on the follower.");
        helpKey(box, "Reverse", "\"Track Piece ▾\" > Reverse piece (rebuild backward)…: the piece is REBUILT as forward sections running the other way, in place, through the density preview. The entry-speed row starts padlocked at the recorded end speed — unlock to type; a stats-bar hint warns when friction can't climb the reversed run home (raise the speed). Same boundaries, same types (straight/curve comes back as force lanes; influence folds in), NO speed conflict. Apply is one undo step and Ctrl+Z the exact restore. A piece ending in a bridge refuses.");

        helpHead(box, "Bridges");
        helpText(box, "⊕ > Bridge to piece N spans from this piece's end to "
            + "another piece's entry (picking a non-adjacent piece moves it "
            + "next on the timeline). Position, direction, curvature, roll "
            + "rates, the graphs' slopes AND the speed all flow across both "
            + "seams, and the bridge re-solves LIVE as you edit or move "
            + "either side. Its shape is the natural continuation of the two "
            + "seams — the graphs change as little as possible, turning "
            + "spreads evenly, a split turn re-bridges as the same turn, and "
            + "hill-top joins stay level and honest at every length. A "
            + "cramped bridge still swings fast INSIDE — smooth but quick; "
            + "lengthen it to calm it. The bridge's exit seam draws the "
            + "same DOUBLE line as a strict seam: the piece after it is "
            + "frozen downstream the same way.");
        helpKey(box, "Panel", "with a bridge active, the toolbar button becomes \"Bridge properties ▾\" — and the bridge badge's \"Bridge properties…\" opens the same panel. Modes: NO OPTIMIZATION (fresh bridges start here — you place the next piece: x/y/z plus any unlocked orientation row), OPTIMIZE POSITION (the bridge places it; you own the whole orientation), OPTIMIZE POSITION AND ORIENTATION (unlocked axes align to the natural path; locked ones keep your values). The yaw/pitch/roll PADLOCKS tint red locked / green unlocked and are shared with the Track Piece panel. \"Optimize length\" picks the shortest length as smooth as the seams allow — when a split piece would re-join seamlessly it collapses to zero and offers to CONNECT DIRECTLY (the strict seam inherits the bridge's locks). \"Zero lateral G\" re-banks every sample so lateral force reads exactly zero (airtime stretches interpolate; seams keep their banks).");
        helpKey(box, "Length", "set it by the spinner, by dragging the vertical line at the END of the bridge's strip (↔ cursor; Ctrl snaps whole meters), or by the orange tangent arrow of the 3D bridge-end gizmo — each re-runs the placement live, one undo step per gesture. Editing UPSTREAM of an optimized bridge re-places the next piece automatically. An optimized bridge is deterministic: same seams + same length = the same bridge, always.");
        helpKey(box, "Convert", "badge > Convert…: the WHOLE bridge becomes editable lanes and the pieces connect — by default through a strict seam carrying the bridge's locks, so nothing downstream reshapes; un-strict it later for the classic continuous stitch. PART opens the split line; the unconverted part stays a live bridge and the track stays connected end to end. Downstream design speeds and any design/true split are preserved exactly — resolve the stitched seam from its badge when you're ready. If the target piece is deleted, the bridge freezes at its last shape (badge \"bridge !\").");

        helpHead(box, "3D viewport");
        helpKey(box, "Orbit", "middle-drag = orbit · scroll = zoom · Shift+middle-click (or C+middle-click) the track = re-center the pivot there · plain LEFT-click the track = edit that piece + section · double-click the track = insert a node there (the graph scrolls to it). The active section's ties glow white; View > Visualize Selection turns the white ties and piece desaturation off.");
        helpKey(box, "1-6 views", "digit keys jump to TRUE-orthographic views (parallel projection, world-aligned GRID overlay): 1 Front, 2 Back, 3 Right, 4 Left (all with the ground line), 5 Top (grid-square), 6 Tangent to Selection — centered on the selected node, tangent to the track, the grid's horizontal origin anchored there, with two buttons: ↻ re-tangents to the current selection, ↔ flips 180° in place. 1-5 work while hovering (or captured in) a view — over the graph, 1/2/3 switch lanes as ever; 6 works from the graph too, aimed at the last-hovered view. Right-drag pans in-plane, scroll zooms toward the cursor, orbiting away returns to perspective.");
        helpKey(box, "View label", "top-left of each view pane names its camera and clicking it opens ONE flat list — Fly/Orbit, POV, Top, Front, Back, Right, Left, Tangent to Selection — to switch views directly. \"Add View\" at the bottom opens another FULL view pane to the right (draggable divider), centered on the whole coaster: every pane rides, flies, edits, picks and drags gizmos exactly like any other. \"Remove View\" closes that pane (the last one stays). Space and the view digits 1-6 act on the pane under the mouse — or the captured (right-clicked) one.");
        helpKey(box, "Fly", "right-click the view: WASD + mouse look, Shift fast, Ctrl slow, scroll bursts; right-click exits keeping the view.");
        helpKey(box, "POV", "Space rides the track. Hands-off at first — the mouse and W/S stay with the editor; right-click CAPTURES: W/S ride at TRUE speed (Shift ×4, Ctrl slow), A reverses the view — per PANE (\"Backwards POV\" tag shows; A again or D = forward), Space flips fly/POV, scroll jumps along the track, right-click releases. Riding across a piece boundary follows in the editor. The light-purple graph line and the BOTTOM STATS LINE track the POV position at all times — X/Y/Z, speed, vertical and lateral G, plus the absolute global Pitch, Yaw and Roll angles with their °/s rates (yaw left-positive; vertical track reads —), all true values.");
        helpKey(box, "Gizmos", "click a WHITE BALL (piece entry, bridge end, or strict seam) to reveal its gizmo; click anywhere off it to hide it. A revealed gizmo draws THROUGH the track and shows the top-right \"Gizmos: Local/Global\" button (Local aims the arrows AND rings at the piece's own frame, and dragging a ring rotates the piece EXACTLY about the drawn axis — the whole rig rides along rigidly; every launch starts Global): translation arrows X green / Y blue / Z red (double-ended — grab either side), rotation rings yaw yellow / pitch teal / roll orange — locked or bridge-owned axes build no part; fixed ortho views with Global axes hide the useless out-of-plane arrow. The idle ball keeps one size at any distance; dragging it moves its owner in the view plane (any view) and reveals the gizmo, whose own scaling center ball takes over — click it to hide, drag to move, and double-click into its settings — Track Piece panel, bridge properties, or seam settings (double-clicking ANY part of a gizmo does the same, always for THAT gizmo); a plain click on the entry ball still selects node 0. The white ball IS the first node's marker at a piece entry. In fixed ortho views only the in-plane ring builds. Rings turn by straight-line mouse travel (~57° per radius); Ctrl snaps translation onto the world grid with Global axes (step increments with Local) and rings to 1°; one undo step per drag.");
        helpKey(box, "Node markers", "small spheres at the active lane's nodes (View > Node Markers; selection in white) — click one to select that node in the graph, scrolling it into view. They sit ON the heartline in orbit/fly and drop to track level in POV. The bottom-left compass shows the world X/Y/Z directions under the current view, always live (it hops above any readout sharing that corner).");
        helpKey(box, "Combs", "FVD++-style teeth along the track — five exclusive View-menu modes: G-FORCE (vertical felt G outward on the rail gradient + lateral felt G rider-left on the absolute stops), SPEED (true speed, upward, green), GEOMETRIC (curvature per meter, teeth pointing OUTWARD from the curve — pitch/elevation teeth orange, yaw/heading teeth red, the combined tooth red-orange; its inline dots pick Local = body pitch + yaw teeth, Global = elevation + heading rates, Combined = one tooth along the curve normal — the default), and TIME (whole seconds DEEP BLUE at 1.5× height, every 5th second RED, with light-blue fillers spaced evenly between the seconds at roughly the other combs' density). Teeth grow from the heartline at exact spacing, scale with \"Comb size (%)\", never block clicks, and hide during POV. Hover any tooth for its exact value bottom-left — every curvature tooth reads °/m plus its radius (a zero component reads r=∞); time reads the ride time.");
        helpKey(box, "Heartline", "View > Show Heartline: a thin yellow strand on the integrated path, one per piece (hides during POV — the camera sits on it).");

        helpHead(box, "Menus & settings");
        helpKey(box, "File", "New (an empty file + the start screen for its first coaster) / Save / Save As with an unsaved-changes guard — closing the app runs the same guard (Cancel keeps it open) · Export (Ctrl+E): pick the COASTER (when the file holds several) and the whole track or ONE PIECE, as Spline (CSV) for NL2's external-track import or NL2 Element (.nl2elem) for the editor's element insert; heartline or rail-center, point spacing — all remembered per-user.");
        helpKey(box, "Undo everywhere", "Ctrl+Z / Ctrl+Shift+Z are the DOCUMENT's in every window that can change the track — the Track Piece / Bridge panel, the strict-seam pop-out, the node editor, the speed-control popup, the section dialogs and the Coaster menu — with the panel open or closed; their boxes follow each step. Only cosmetic/settings menus (View, Snapping, colors, Export) keep a menu-local undo.");
        helpKey(box, "Edit", "SHORTCUTS — click a binding, press the new combo, ↺ resets; grouped by category; per-user. SNAPPING — the Ctrl-snap steps (time, value, roll °/s, rate °/m, element length) and each axis's snap reference: LOCAL (Δ from the previous node) or GLOBAL (absolute grid).");
        helpKey(box, "View", "Show Heartline · Show Node Markers · Visualize Selection · the five comb modes with the Geometric dots + Comb size · G-Force Colors… (the SIX vertical stops shared by the rail gradient and vertical comb, plus the lateral comb's three absolute stops; per-coaster) · ONE FOV slider (all cameras) · mouse sensitivity. Rows keep the menu open; sliders wear ↺; Ctrl+Z inside a menu undoes its edits. Every tool window is MODELESS: with any dialog or panel open you can still right-click the viewport, fly and ride — WASD always reaches the camera, and every numeric box only ever accepts digits and a period. Typing in ANY text box never fires shortcuts (Ctrl+combos still work), tool windows hide while you fly/ride captured and return on release, and anything grayed out explains itself — a short reason in the label or on hover.");
        helpKey(box, "Coaster", "the coaster SELECTOR at the top (pick which of the file's coasters you edit — the others stay in view in their solid rail color) · \"Coaster Options…\": rename, the two FRICTION coefficients, and the statistics (ride time, track length, height range, top speed, vertical-G range) · \"New Coaster…\" (the start screen) · \"Delete Coaster\" (asks first; even the last one — the file may be empty, and Ctrl+Z restores) · \"Rail Settings…\": Solid (pick via its swatch) or Normal Force — the rails colored by the TRUE felt vertical G on the same gradient as the comb, with G-Force Colors… — plus heartline offset, gauge and tie spacing. Per-coaster, saved in the .fvd; Ctrl+Z in both dialogs is the global one.");
        helpKey(box, "Colors", "every color control is a swatch button opening the app's picker: quick palette, your last ten, a hue/saturation plane with a brightness slider, typed RGB and hex, live preview — OK keeps, Cancel restores.");

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 660);

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);   // non-modal: usable as a reference while editing
        dlg.setTitle("Help");
        dlg.setScene(new Scene(scroll));
        dlg.show();
        app.installViewKeys(dlg.getScene());
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
        // modeless since v18.1 (D166): the viewport stays reachable
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
            Button reset = new Button();   // drawn round icon (v18.11)
            reset.setGraphic(FvdDesigner.resetIcon());
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
        app.installViewKeys(dlg.getScene());
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

    // (showRailColorDialog is GONE, v16.14 — the solid color lives
    // in the View menu's rail row and the gradient stops in the
    // "G-Force Colors" dialog. And since v16.16 there is NO
    // ColorPicker anywhere: its "Custom Color" child is owned by
    // the picker's transient POPUP, which both crashed the pre-v7.5
    // menu embed AND caused the unbeatable focus-flash — colors are
    // picked through colorSwatch + pickColor, an ordinary owned
    // window.)

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
        Button b = new Button();
        // drawn icon, not the flat-bottomed font glyph (v18.11)
        b.setGraphic(FvdDesigner.resetIcon());
        b.setTooltip(new Tooltip("Reset to default"));
        b.setOnAction(e -> restore.run());
        return b;
    }

    /** Same, GRAYED while the row already sits at its default (v16.7,
     *  user spec — applies across the program). obs = anything that
     *  fires when the row's value changes. */
    Button optionReset(Runnable restore,
                       javafx.beans.value.ObservableValue<?> obs,
                       java.util.function.BooleanSupplier isDefault) {
        Button b = optionReset(restore);
        Runnable sync = () -> b.setDisable(isDefault.getAsBoolean());
        obs.addListener((o, a, n) -> sync.run());
        sync.run();
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
        FvdDesigner.numericEditor(sp, false);   // v18.1: digits only
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

    /** The VIEW menu (v16.13, user spec): every viewport setting in
     *  one place — gizmo axes, heartline, node markers, the three
     *  track combs, both FOVs, mouse sensitivity and the G-force /
     *  comb color stops (these rows all MOVED here from Edit >
     *  Options, which keeps the graph-editor snaps + POV marker).
     *  Rows are CustomMenuItems that keep the menu open; sliders
     *  live-apply exactly as before; menu-local Ctrl+Z works while
     *  the menu shows. */
    Menu buildViewMenu() {
        // (the "Show Gizmos" row + inline Local/Global dots are GONE,
        // v18.16 D187 — clicking a gizmo's white ball reveals its rig,
        // and the top-right viewport button owns the axes choice)
        CheckBox heartCheck = new CheckBox("Show Heartline");
        heartCheck.setStyle("-fx-text-fill: black;");
        heartCheck.setSelected(app.settings.showHeartline());
        heartCheck.setOnAction(ev -> {
            boolean on = heartCheck.isSelected();
            app.pushMenuEdit(
                () -> { heartCheck.setSelected(!on); app.settings.setShowHeartline(!on); app.rebuildTrack(); },
                () -> { heartCheck.setSelected(on); app.settings.setShowHeartline(on); app.rebuildTrack(); });
            app.settings.setShowHeartline(on);
            app.rebuildTrack();
        });

        CheckBox markCheck = new CheckBox("Show Node Markers");
        markCheck.setStyle("-fx-text-fill: black;");
        markCheck.setSelected(app.settings.trackMarkers());
        markCheck.setOnAction(ev -> {
            boolean on = markCheck.isSelected();
            app.pushMenuEdit(
                () -> { markCheck.setSelected(!on); app.settings.setTrackMarkers(!on); app.editor.refreshMarkers(); },
                () -> { markCheck.setSelected(on); app.settings.setTrackMarkers(on); app.editor.refreshMarkers(); });
            app.settings.setTrackMarkers(on);
            app.editor.refreshMarkers();
        });

        // "Visualize Selection" (v17.10, renamed v17.11, user spec):
        // OFF = no desaturation of unselected pieces and no white
        // active-section ties — the whole track renders as if
        // selected (tubes) with plain dark ties
        CheckBox vizSel = new CheckBox("Visualize Selection");
        vizSel.setStyle("-fx-text-fill: black;");
        vizSel.setSelected(app.settings.visualizeSelection());
        vizSel.setOnAction(ev -> {
            boolean on = vizSel.isSelected();
            app.pushMenuEdit(
                () -> { vizSel.setSelected(!on); app.settings.setVisualizeSelection(!on); app.rebuildTrack(); },
                () -> { vizSel.setSelected(on); app.settings.setVisualizeSelection(on); app.rebuildTrack(); });
            app.settings.setVisualizeSelection(on);
            app.rebuildTrack();
        });

        // (the "Reverse POV view direction" row was REMOVED in v17.11,
        // user spec — A/D in captured POV are the whole surface, with
        // the bottom-left "Backwards POV (A)" tag as the indicator)

        // COMB mode — three-way since v16.14, FIVE-way since v17.16
        // (D157: + the GEOMETRIC comb with its inline Local/Global/
        // Combined dots — the gizmo-dots pattern — and the TIME
        // comb); modes stay exclusive (the v16.14 user spec)
        ToggleGroup combGrp = new ToggleGroup();
        RadioButton combNone = new RadioButton("No comb");
        RadioButton combG = new RadioButton("G-force comb");
        RadioButton combSp = new RadioButton("Speed comb");
        RadioButton combGeo = new RadioButton("Geometric comb");
        RadioButton combTime = new RadioButton("Time comb");
        RadioButton[] combBtns =
            { combNone, combG, combSp, combGeo, combTime };
        for (RadioButton rb : combBtns) {
            rb.setToggleGroup(combGrp);
            rb.setStyle("-fx-text-fill: black;");
        }
        combBtns[app.settings.combMode()].setSelected(true);
        // the geometric sub-mode dots gray out unless the geometric
        // comb is the selected mode (the D149 gizmo-dots rule)
        ToggleGroup geoGrp = new ToggleGroup();
        RadioButton geoLocal = new RadioButton("Local");
        RadioButton geoGlobal = new RadioButton("Global");
        RadioButton geoComb = new RadioButton("Combined");
        RadioButton[] geoBtns = { geoLocal, geoGlobal, geoComb };
        for (RadioButton rb : geoBtns) {
            rb.setToggleGroup(geoGrp);
            rb.setStyle("-fx-text-fill: black;");
            rb.setDisable(
                app.settings.combMode() != AppSettings.COMB_GEO);
        }
        geoBtns[app.settings.combGeoMode()].setSelected(true);
        Runnable geoSync = () -> {
            boolean on = app.settings.combMode() == AppSettings.COMB_GEO;
            for (RadioButton rb : geoBtns) rb.setDisable(!on);
        };
        java.util.function.IntConsumer geoApply = gm -> {
            int old = app.settings.combGeoMode();
            if (old == gm) return;
            app.pushMenuEdit(
                () -> { geoBtns[old].setSelected(true); app.settings.setCombGeoMode(old); app.rebuildTrack(); },
                () -> { geoBtns[gm].setSelected(true); app.settings.setCombGeoMode(gm); app.rebuildTrack(); });
            app.settings.setCombGeoMode(gm);
            app.rebuildTrack();
        };
        geoLocal.setOnAction(e ->
            geoApply.accept(AppSettings.COMB_GEO_LOCAL));
        geoGlobal.setOnAction(e ->
            geoApply.accept(AppSettings.COMB_GEO_GLOBAL));
        geoComb.setOnAction(e ->
            geoApply.accept(AppSettings.COMB_GEO_COMBINED));
        java.util.function.IntConsumer combApply = cmNew -> {
            int old = app.settings.combMode();
            if (old == cmNew) return;
            app.pushMenuEdit(
                () -> { combBtns[old].setSelected(true); app.settings.setCombMode(old); geoSync.run(); app.rebuildTrack(); },
                () -> { combBtns[cmNew].setSelected(true); app.settings.setCombMode(cmNew); geoSync.run(); app.rebuildTrack(); });
            app.settings.setCombMode(cmNew);
            geoSync.run();
            app.rebuildTrack();
        };
        combNone.setOnAction(e -> combApply.accept(AppSettings.COMB_NONE));
        combG.setOnAction(e -> combApply.accept(AppSettings.COMB_GFORCE));
        combSp.setOnAction(e -> combApply.accept(AppSettings.COMB_SPEED));
        combGeo.setOnAction(e -> combApply.accept(AppSettings.COMB_GEO));
        combTime.setOnAction(e ->
            combApply.accept(AppSettings.COMB_TIME));
        // grayed dots = explained on hover (D235)
        String geoHow = "Pick the Geometric comb first.";
        HBox combGeoRow = new HBox(10, combGeo,
            GraphEditor.grayHint(geoLocal, geoHow),
            GraphEditor.grayHint(geoGlobal, geoHow),
            GraphEditor.grayHint(geoComb, geoHow));
        combGeoRow.setAlignment(Pos.CENTER_LEFT);

        // comb tooth size, percent of default (v16.14) — applied on
        // release/click so slider drags don't recompile per pixel
        Slider combSc = optionSlider(25, 300, app.settings.combScale(), 50);
        Label combScVal = viewMenuLabel(
            String.format("%.0f%%", app.settings.combScale()));
        wireOptionSlider(combSc, combScVal, "%.0f%%",
            v -> { },
            v -> { app.settings.setCombScale(v); app.rebuildTrack(); });
        HBox combScRow = new HBox(8, viewMenuLabel("Comb size (%)"),
            combSc, combScVal,
            optionReset(() -> combSc.setValue(AppSettings.COMB_SCALE_DEFAULT),
                combSc.valueProperty(),
                () -> Math.abs(combSc.getValue()
                    - AppSettings.COMB_SCALE_DEFAULT) < 1e-9));
        combScRow.setAlignment(Pos.CENTER_LEFT);

        MenuItem combColors = new MenuItem();
        combColors.setGraphic(gforceColorsLabel());
        combColors.setOnAction(e -> showCombColorsDialog());

        // (the RAIL COLOR radios + swatch moved to the COASTER menu's
        // "Rail Color" section in v17.10, user spec — railColorItems)

        // ONE FOV since v18.9 (user spec — was a POV + fly/orbit
        // pair): every camera reads it; the legacy povFov key is
        // written along so old builds stay consistent
        Slider fovFly = optionSlider(30, 110, app.settings.flyFov(), 20);
        Label fovFlyVal = viewMenuLabel(String.format("%.0f°", app.settings.flyFov()));
        wireOptionSlider(fovFly, fovFlyVal, "%.0f°",
            v -> {
                for (Viewport3D vp : app.viewports) {
                    vp.povCam.setFieldOfView(v);
                    vp.flyCam.setFieldOfView(v);
                    // never stomp the 1° ortho lens; it re-reads the
                    // setting on exit
                    if (!vp.orthoView) vp.orbitCam.setFieldOfView(v);
                }
            },
            v -> {
                app.settings.setFlyFov(v);
                app.settings.setFov(v);
            });

        Slider sens = optionSlider(0.03, 0.40, app.settings.flySens(), 0.1);
        Label sensVal = viewMenuLabel(String.format("%.2f", app.settings.flySens()));
        wireOptionSlider(sens, sensVal, "%.2f",
            v -> { }, app.settings::setFlySens);   // fly + orbit read it live

        HBox fovFlyRow = new HBox(8, viewMenuLabel("FOV"),
            fovFly, fovFlyVal,
            optionReset(() -> fovFly.setValue(AppSettings.FOV_DEFAULT),
                fovFly.valueProperty(),
                () -> Math.abs(fovFly.getValue()
                    - AppSettings.FOV_DEFAULT) < 1e-9));
        fovFlyRow.setAlignment(Pos.CENTER_LEFT);
        HBox sensRow = new HBox(8, viewMenuLabel("Mouse sensitivity (°/px)"),
            sens, sensVal,
            optionReset(() -> sens.setValue(AppSettings.SENS_DEFAULT),
                sens.valueProperty(),
                () -> Math.abs(sens.getValue()
                    - AppSettings.SENS_DEFAULT) < 1e-9));
        sensRow.setAlignment(Pos.CENTER_LEFT);

        // (a "Mesh quality (%)" slider lived here for one unshipped
        // round — v18.2 removed it on user verdict: the tuned 100%
        // adaptive tessellation IS the behavior, D167)

        Menu m = new Menu("View", null,
            new CustomMenuItem(heartCheck, false),
            new CustomMenuItem(markCheck, false),
            new CustomMenuItem(vizSel, false),
            new SeparatorMenuItem(),
            new CustomMenuItem(combNone, false),
            new CustomMenuItem(combG, false),
            new CustomMenuItem(combSp, false),
            new CustomMenuItem(combGeoRow, false),
            new CustomMenuItem(combTime, false),
            new CustomMenuItem(combScRow, false),
            combColors,
            new SeparatorMenuItem(),
            new CustomMenuItem(fovFlyRow, false),
            new CustomMenuItem(sensRow, false));
        m.setOnShown(e -> {
            app.beginMenuSession();
            app.installMenuUndoKeys(heartCheck.getScene());
            // color edits re-tint the item's letters (v16.15)
            combColors.setGraphic(gforceColorsLabel());
        });
        m.setOnHidden(e -> app.endMenuSession());
        return m;
    }

    /** The COASTER menu's "Rail Color" section (v17.10, user spec —
     *  moved from the View menu): a header plus "Solid" (its color
     *  swatch shows while selected; no reset arrow) and "Normal
     *  Force" (the G-force gradient). railMenuSync re-syncs the
     *  swatch after Load/New WITHOUT dirtying (v16.16 guard). */
    Runnable railMenuSync = () -> { };

    /** The rail-color controls (v19.3, D232 — they LEFT the Coaster
     *  menu for the Rail Settings dialog): Solid + swatch / Normal
     *  Force + "G-Force Colors…" — built ONCE (the radios are the
     *  app's rail-mode sync targets), re-parented per dialog open. */
    private VBox railContent;

    VBox railSettingsContent() {
        if (railContent != null) return railContent;
        Label header = new Label("Rail Color");
        header.setStyle("-fx-font-weight: bold;");
        ToggleGroup railGrp = new ToggleGroup();
        RadioButton railSol = new RadioButton("Solid");
        RadioButton railGrad = new RadioButton("Normal Force");
        railSol.setToggleGroup(railGrp);
        railGrad.setToggleGroup(railGrp);
        (app.railSolid ? railSol : railGrad).setSelected(true);
        app.gradientModeItem = railGrad;
        app.solidModeItem = railSol;
        railGrad.setOnAction(e -> app.changeRailMode(false));
        railSol.setOnAction(e -> app.changeRailMode(true));
        // swatch + our own dialog (v16.16) — this also retires the
        // historic "Custom Color from a menu row" crash risk: the
        // dialog is a plain Stage owned by the main window
        javafx.beans.property.ObjectProperty<Color> railProp =
            new javafx.beans.property.SimpleObjectProperty<>(app.railColor);
        boolean[] railSyncing = { false };   // open-time re-sync guard
        railProp.addListener((o, a, c) -> {
            if (railSyncing[0]) return;
            app.railColor = c;
            app.markDirty();
            app.rebuildTrack();
        });
        Button railPick = colorSwatch(railProp);
        railPick.visibleProperty().bind(railSol.selectedProperty());
        Button gfc = new Button();
        gfc.setGraphic(gforceColorsLabel());
        gfc.setFocusTraversable(false);
        gfc.setOnAction(e -> showCombColorsDialog());
        gfc.visibleProperty().bind(railGrad.selectedProperty());
        gfc.managedProperty().bind(railGrad.selectedProperty());
        railMenuSync = () -> {
            if (!railProp.get().equals(app.railColor)) {
                railSyncing[0] = true;
                railProp.set(app.railColor);
                railSyncing[0] = false;
            }
            gfc.setGraphic(gforceColorsLabel());
        };
        HBox solRow = new HBox(8, railSol, railPick);
        solRow.setAlignment(Pos.CENTER_LEFT);
        railContent = new VBox(8, header, solRow, railGrad, gfc);
        return railContent;
    }

    /** RAIL SETTINGS dialog (v19.3, D232): rail color + heartline +
     *  gauge + tie spacing (the rows re-parent from the shell; their
     *  wiring — doc undo for heart/gauge, plain apply for the
     *  cosmetics — is untouched). Modeless; global Ctrl+Z (D227:
     *  heart/gauge shape the track). Track TYPE lands here later. */
    private Stage railSettingsDlg;

    void showRailSettingsDialog() {
        if (railSettingsDlg != null) railSettingsDlg.close();
        Stage dlg = new Stage();
        railSettingsDlg = dlg;
        dlg.initOwner(app.stage);
        dlg.setTitle("Rail Settings");
        railMenuSync.run();   // swatch + tinted letters follow the doc
        VBox box = new VBox(8, railSettingsContent(),
            new javafx.scene.control.Separator(),
            app.heartRow, app.gaugeRow, app.tieRow);
        box.setPadding(new Insets(14));
        Scene sc = new Scene(box);
        dlg.setScene(sc);
        dlg.setOnHidden(e -> {
            if (railSettingsDlg == dlg) railSettingsDlg = null;
        });
        dlg.show();
        app.installDocUndoKeys(sc);
    }

    /** "G-Force Colors…" with the letters of "G-Force" tinted by the
     *  CURRENT vertical stops, hottest first (v16.15, user spec: G
     *  red, F yellow, o green… by default) — rebuilt on every menu
     *  show so stop edits re-tint it. */
    private javafx.scene.Node gforceColorsLabel() {
        HBox box = new HBox(0);
        box.setAlignment(Pos.CENTER_LEFT);
        String word = "G-Force";
        int n = app.gradColors.length;
        int ci = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            Label l = new Label(String.valueOf(c));
            if (c == '-') {
                l.setTextFill(Color.BLACK);
            } else {
                l.setTextFill(app.gradColors[Math.max(0,
                    n - 1 - Math.min(ci, n - 1))]);
                ci++;
            }
            l.setStyle("-fx-font-weight: bold;");
            box.getChildren().add(l);
        }
        Label rest = new Label(" Colors…");
        rest.setTextFill(Color.BLACK);
        box.getChildren().add(rest);
        return box;
    }

    /** Menu popups are light — default label fill washes out. */
    private static Label viewMenuLabel(String text) {
        Label l = new Label(text);
        l.setTextFill(javafx.scene.paint.Color.BLACK);
        return l;
    }

    // (the v16.14 refocusAfterPicker band-aid is GONE, v16.15: it
    // yanked the window back a frame AFTER the OS had activated
    // another app — the user's "flash". FvdDesigner now watches for
    // the custom-color dialog itself and hands focus straight back
    // as it hides, before activation ever leaves the app.)

    /** G-force gradient + lateral comb stops in ONE dialog (v16.13,
     *  View menu): the VERTICAL stops are the same four the rail
     *  gradient uses — one set drives the rails AND the vertical
     *  comb; the LATERAL stops color the lateral comb at its own
     *  scale (lateral G lives near ±1.5). */
    void showCombColorsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        // modeless since v18.1 (D166): the viewport stays reachable
        dlg.setTitle("G-force & comb colors");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        DesignIO.Cosmetics def = DesignIO.Cosmetics.defaults();
        Label vHead = new Label("Vertical G stops (rail gradient + vertical comb)");
        vHead.setStyle("-fx-font-weight: bold;");
        grid.add(vHead, 0, 0, 4, 1);
        int r = addStopRows(grid, 1, app.gradColors, app.gradStops,
            FvdDesigner.G_MIN, FvdDesigner.G_MAX, def.gradColors());
        Label lHead = new Label("Lateral G stops (lateral comb, ± mirrored)");
        lHead.setStyle("-fx-font-weight: bold;");
        grid.add(lHead, 0, r, 4, 1);
        addStopRows(grid, r + 1, app.latColors, app.latStops, 0, 5,
            def.latColors());
        Scene sc = new Scene(grid);
        app.installMenuUndoKeys(sc);
        dlg.setScene(sc);
        app.beginMenuSession();
        dlg.setOnHidden(e -> app.endMenuSession());
        dlg.show();
        app.installViewKeys(dlg.getScene());
    }

    /** Stop rows (G value + color, one per stop) editing the given
     *  arrays in place — shared by both stop sets; length-generic
     *  since v16.14 (five vertical / three lateral). Every color
     *  wears a ↺ back to its default, grayed while already there
     *  (user spec), and the window pulls itself back in front after
     *  the custom-color child closes. */
    private int addStopRows(GridPane grid, int row0, Color[] cols,
                            double[] stops, double lo, double hi,
                            double[] defRgb) {
        for (int i = 0; i < cols.length; i++) {
            final int gi = i;
            Spinner<Double> vs = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(lo, hi, stops[gi], 0.1));
            vs.setPrefWidth(75);
            vs.setEditable(true);
            FvdDesigner.numericEditor(vs, true);   // v18.1: G stops go negative
            vs.valueProperty().addListener((obs, o, v) -> {
                app.pushMenuEdit(() -> vs.getValueFactory().setValue(o),
                             () -> vs.getValueFactory().setValue(v));
                stops[gi] = v;
                app.markDirty();
                app.rebuildTrack();
            });
            // color SWATCH + our own dialog (v16.16 — see pickColor:
            // the built-in picker's Custom Color window caused the
            // focus flash by construction)
            javafx.beans.property.ObjectProperty<Color> prop =
                new javafx.beans.property.SimpleObjectProperty<>(cols[gi]);
            prop.addListener((obs, o, c) -> {
                cols[gi] = c;
                app.markDirty();
                app.rebuildTrack();
            });
            Button swatch = colorSwatch(prop);
            Color defCol = Color.color(defRgb[gi * 3], defRgb[gi * 3 + 1],
                defRgb[gi * 3 + 2]);
            Button rst = optionReset(() -> {
                if (!prop.get().equals(defCol)) {
                    Color oc = prop.get();
                    app.pushMenuEdit(() -> prop.set(oc),
                                 () -> prop.set(defCol));
                    prop.set(defCol);
                }
            }, prop, () -> prop.get().equals(defCol));
            grid.addRow(row0 + i, new Label("Stop " + (i + 1)), vs, swatch, rst);
        }
        return row0 + cols.length;
    }

    /** A color SWATCH button (v16.16): its face IS the current color;
     *  clicking opens pickColor. One menu-local undo pair per dialog
     *  session (live changes coalesce into it). */
    private Button colorSwatch(javafx.beans.property.ObjectProperty<Color> prop) {
        Button b = new Button();
        // SQUARE and small (v17.10, user spec — was 52x22)
        b.setPrefSize(22, 22);
        b.setMinSize(22, 22);
        b.setFocusTraversable(false);
        // -fx-background-insets: 0 kills modena's default layer that
        // extends 1 px BELOW the button (v18.11, user report: the
        // fill "overflowed" past the outline at the bottom)
        Runnable paint = () -> b.setStyle("-fx-background-color: "
            + toHex(prop.get())
            + "; -fx-background-insets: 0;"
            + " -fx-border-color: rgba(120,125,135,0.85);"
            + " -fx-border-radius: 3; -fx-background-radius: 3;");
        prop.addListener((o, a, c) -> paint.run());
        paint.run();
        b.setOnAction(e -> {
            javafx.stage.Window owner = b.getScene() != null
                && b.getScene().getWindow() instanceof Stage st
                ? st : app.stage;
            Color before = prop.get();
            pickColor(owner, before, prop::set, () -> {
                Color after = prop.get();
                if (!after.equals(before)) {
                    app.pushMenuEdit(() -> prop.set(before),
                                 () -> prop.set(after));
                }
            });
        });
        return b;
    }

    static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
            (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255),
            (int) Math.round(c.getBlue() * 255));
    }

    /** One clickable palette/recent cell for pickColor (v16.16). */
    private static javafx.scene.layout.Region swatchCell(Color c,
            java.util.function.Consumer<Color> pick) {
        javafx.scene.layout.Region cell = new javafx.scene.layout.Region();
        cell.setMinSize(20, 20);
        cell.setPrefSize(20, 20);
        String base = "-fx-background-color: " + toHex(c)
            + "; -fx-background-radius: 3; -fx-border-radius: 3;";
        cell.setStyle(base + " -fx-border-color: rgba(120,125,135,0.85);");
        cell.setOnMouseEntered(e -> cell.setStyle(base
            + " -fx-border-color: white;"));
        cell.setOnMouseExited(e -> cell.setStyle(base
            + " -fx-border-color: rgba(120,125,135,0.85);"));
        cell.setOnMouseClicked(e -> pick.accept(c));
        return cell;
    }

    /** OUR color dialog (v16.16 — the focus-flash ROOT fix): the
     *  built-in ColorPicker's "Custom Color" window is owned by the
     *  picker's transient POPUP, so when Save/Use closes it Windows
     *  hands activation to whatever application is next in Z-order —
     *  no after-the-fact refocus can win that race (v16.14's
     *  toFront and v16.15's on-hiding watcher both still flashed).
     *  This dialog is an ordinary WINDOW_MODAL child of OUR window,
     *  so closing it returns focus exactly like every other window
     *  in the app. RGB sliders + hex, LIVE apply (the gradient and
     *  track update as you slide); OK keeps, Cancel/X restores. */
    void pickColor(javafx.stage.Window owner, Color initial,
                   java.util.function.Consumer<Color> apply,
                   Runnable onOk) {
        Stage dlg = new Stage();
        dlg.initOwner(owner != null ? owner : app.stage);
        // modeless since v18.1 (D166): the viewport stays reachable
        dlg.setTitle("Pick color");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        javafx.scene.layout.Region preview = new javafx.scene.layout.Region();
        preview.setPrefSize(240, 30);
        grid.add(preview, 0, 0, 3, 1);
        // the working color lives as HSB (v16.17): a HUE × SATURATION
        // plane picks the tint, a vertical slider sets brightness —
        // and grays keep their hue while you slide, instead of the
        // marker snapping to hue 0
        double[] hsb = { initial.getHue(), initial.getSaturation(),
            initial.getBrightness() };
        TextField hex = new TextField(toHex(initial));
        hex.setPrefColumnCount(7);
        TextField[] rgbF = new TextField[3];
        for (int i = 0; i < 3; i++) {
            rgbF[i] = new TextField();
            rgbF[i].setPrefColumnCount(3);
            // v18.1 numeric sweep (hex keeps its letters, RGB doesn't)
            rgbF[i].setTextFormatter(FvdDesigner.numFormatter());
        }
        boolean[] updating = { false };
        Runnable paintPreview = () -> preview.setStyle(
            "-fx-background-color: " + hex.getText()
            + "; -fx-border-color: rgba(120,125,135,0.85);");
        java.util.function.Supplier<Color> current = () ->
            Color.hsb(hsb[0], hsb[1], hsb[2]);
        // the PLANE (hue across, FULL saturation at the top — user
        // spec) paints at the CURRENT brightness since v16.19 (user
        // re-spec of v16.17's fixed-brightness draw); the slider's
        // own track + thumb carry the readability instead
        Canvas plane = new Canvas(252, 132);
        Slider bright = new Slider(0, 1, hsb[2]);
        bright.setOrientation(Orientation.VERTICAL);
        bright.setPrefHeight(132);
        Tooltip.install(bright, new Tooltip("Brightness"));
        Runnable paintPlane = () -> {
            GraphicsContext gc = plane.getGraphicsContext2D();
            var pw = gc.getPixelWriter();
            int w = (int) plane.getWidth(), h = (int) plane.getHeight();
            for (int y = 0; y < h; y++) {
                double s = 1 - y / (double) (h - 1);
                for (int x = 0; x < w; x++) {
                    pw.setColor(x, y, Color.hsb(x * 360.0 / w, s, hsb[2]));
                }
            }
            // double-ring marker reads on any background
            double mx = hsb[0] / 360 * w;
            double my = (1 - hsb[1]) * (h - 1);
            gc.setLineWidth(1.5);
            gc.setStroke(Color.WHITE);
            gc.strokeOval(mx - 5, my - 5, 10, 10);
            gc.setStroke(Color.BLACK);
            gc.strokeOval(mx - 6, my - 6, 12, 12);
        };
        // the slider TRACK shows the brightness gradient of the
        // selected hue/sat, and the THUMB's centre wears the selected
        // color (v16.19, user spec) — the skin substructure only
        // exists after CSS applies, so look it up lazily each pass
        Runnable paintSlider = () -> {
            Color top = Color.hsb(hsb[0], hsb[1], 1.0);
            javafx.scene.Node track = bright.lookup(".track");
            if (track != null) {
                track.setStyle("-fx-background-color: linear-gradient("
                    + "to top, black, " + toHex(top) + ");"
                    + " -fx-background-radius: 4;");
            }
            javafx.scene.Node thumb = bright.lookup(".thumb");
            if (thumb != null) {
                thumb.setStyle("-fx-background-color: "
                    + "rgba(235,235,240,0.95), " + hex.getText() + ";"
                    + " -fx-background-insets: 0, 2.5;");
            }
        };
        // ONE guarded refresh: fields, slider, marker, preview, live
        // apply — every input path funnels through here
        Runnable syncAll = () -> {
            Color c = current.get();
            updating[0] = true;
            rgbF[0].setText(String.valueOf((int) Math.round(c.getRed() * 255)));
            rgbF[1].setText(String.valueOf((int) Math.round(c.getGreen() * 255)));
            rgbF[2].setText(String.valueOf((int) Math.round(c.getBlue() * 255)));
            hex.setText(toHex(c));
            bright.setValue(hsb[2]);
            updating[0] = false;
            paintPlane.run();
            paintPreview.run();
            paintSlider.run();
            apply.accept(c);
        };
        // entry point for palette/recent/typed picks — a GRAY color
        // keeps the current hue (its own hue is degenerate 0)
        java.util.function.Consumer<Color> setColor = c -> {
            if (c.getSaturation() > 1e-9) hsb[0] = c.getHue();
            if (c.getBrightness() > 1e-9) hsb[1] = c.getSaturation();
            hsb[2] = c.getBrightness();
            syncAll.run();
        };
        javafx.event.EventHandler<MouseEvent> planePick = e -> {
            double w = plane.getWidth(), h = plane.getHeight();
            hsb[0] = FvdDesigner.clamp(e.getX() / w, 0, 0.9999) * 360;
            hsb[1] = FvdDesigner.clamp(1 - e.getY() / (h - 1), 0, 1);
            syncAll.run();
        };
        plane.setOnMousePressed(planePick);
        plane.setOnMouseDragged(planePick);
        bright.valueProperty().addListener((o, a, v) -> {
            if (updating[0]) return;
            hsb[2] = v.doubleValue();
            syncAll.run();
        });
        // QUICK SWATCHES (v16.16, user spec): three shades of ten
        // hues + a grayscale row, and the last TEN colors picked in
        // this dialog (per-user, newest first)
        javafx.scene.layout.VBox swatches = new javafx.scene.layout.VBox(3);
        double[][] shades = { { 0.55, 1.0 }, { 0.95, 0.95 }, { 1.0, 0.55 } };
        for (double[] sh : shades) {
            HBox rowBox = new HBox(3);
            for (int h = 0; h < 10; h++) {
                rowBox.getChildren().add(swatchCell(
                    Color.hsb(h * 36, sh[0], sh[1]), setColor));
            }
            swatches.getChildren().add(rowBox);
        }
        HBox grays = new HBox(3);
        for (int g = 0; g < 10; g++) {
            grays.getChildren().add(swatchCell(
                Color.gray(g / 9.0), setColor));
        }
        swatches.getChildren().add(grays);
        grid.add(swatches, 0, 1, 3, 1);
        int row = 2;
        java.util.List<String> recent = app.settings.recentColors();
        if (!recent.isEmpty()) {
            HBox recRow = new HBox(3);
            for (String rc : recent) {
                try {
                    recRow.getChildren().add(swatchCell(Color.web(rc), setColor));
                } catch (IllegalArgumentException ignored) {
                    // a mangled settings entry never blocks the dialog
                }
            }
            grid.addRow(row++, new Label("Recent"), recRow);
        }
        HBox planeRow = new HBox(8, plane, bright);
        planeRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(planeRow, 0, row++, 3, 1);
        // TYPED RGB (v16.17, user spec — the sliders are gone)
        Runnable rgbTyped = () -> {
            if (updating[0]) return;
            try {
                int r = Integer.parseInt(rgbF[0].getText().trim());
                int g = Integer.parseInt(rgbF[1].getText().trim());
                int b = Integer.parseInt(rgbF[2].getText().trim());
                if (r < 0 || r > 255 || g < 0 || g > 255
                        || b < 0 || b > 255) return;
                setColor.accept(Color.rgb(r, g, b));
            } catch (NumberFormatException ignored) {
                // keep typing; incomplete = no-op
            }
        };
        for (TextField f : rgbF) {
            f.textProperty().addListener((o, a, v) -> rgbTyped.run());
        }
        HBox rgbRow = new HBox(6, rgbF[0], rgbF[1], rgbF[2]);
        grid.addRow(row++, new Label("RGB"), rgbRow);
        hex.textProperty().addListener((o, a, v) -> {
            if (updating[0]) return;
            try {
                setColor.accept(Color.web(v.trim()));
            } catch (IllegalArgumentException ignored) {
                // keep typing; invalid = no-op
            }
        });
        grid.addRow(row++, new Label("Hex"), hex);
        syncAll.run();   // fields, marker, preview — and the first
                         // apply is the color it already had
        Button ok = new Button("OK");
        ok.setDefaultButton(true);
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        HBox buttons = new HBox(8, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        grid.add(buttons, 0, row, 3, 1);
        ok.setOnAction(e -> {
            Color fin = current.get();
            if (!fin.equals(initial)) {
                app.settings.pushRecentColor(toHex(fin));
            }
            onOk.run();
            dlg.close();
        });
        cancel.setOnAction(e -> {
            apply.accept(initial);
            dlg.close();
        });
        // the window's X = cancel (Stage.close() from code does not
        // fire this handler, so OK stays clean)
        dlg.setOnCloseRequest(e -> apply.accept(initial));
        dlg.setScene(new Scene(grid));
        dlg.show();
        app.installViewKeys(dlg.getScene());
        // the slider's .track/.thumb nodes exist only after the first
        // CSS pass — re-run the tint once the skin is up (v16.19)
        javafx.application.Platform.runLater(paintSlider);
    }

    /** Visual/interaction options (app-wide, persisted). */
    void showOptionsDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        // modeless since v18.1 (D166): the viewport stays reachable
        dlg.setTitle("Snapping");

        Spinner<Double> snapTSp = snap125Spinner(0.01, 2.0, app.settings.snapT());
        wireOptionSpinner(snapTSp, app.settings::setSnapT);
        Spinner<Double> snapGSp = snap125Spinner(0.01, 2.0, app.settings.snapG());
        wireOptionSpinner(snapGSp, app.settings::setSnapG);
        Spinner<Double> snapRollSp = snap125Spinner(0.5, 50, app.settings.snapRoll());
        wireOptionSpinner(snapRollSp, app.settings::setSnapRoll);
        Spinner<Double> snapPmSp = snap125Spinner(0.05, 20, app.settings.snapRatePM());
        wireOptionSpinner(snapPmSp, app.settings::setSnapRatePM);
        Spinner<Double> snapLenSp = snap125Spinner(0.1, 20, app.settings.snapLen());
        wireOptionSpinner(snapLenSp, app.settings::setSnapLen);

        // (the v13.9 bridge-optimizer rows moved into the per-bridge
        // "Bridge properties" dropdown in v13.10, D84 — user rule:
        // the settings are local to each bridge section)

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

        // (the POV-marker toggle is GONE, v16.14 — the marker is
        // always on; the Snapping dialog is snaps + references only)

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(14));
        int r = 0;
        // (FOV, sensitivity, heartline, markers and combs MOVED to
        // the View menu in v16.13 — Options keeps the graph-editor
        // snaps and the POV marker)
        grid.add(new Label("Snap: transition time (s)"), 0, r);
        grid.add(snapTSp, 1, r);
        grid.add(optionReset(() -> snapTSp.getValueFactory().setValue(AppSettings.SNAP_T_DEFAULT),
            snapTSp.getValueFactory().valueProperty(),
            () -> Math.abs(snapTSp.getValue() - AppSettings.SNAP_T_DEFAULT) < 1e-12), 3, r++);
        grid.add(new Label("Snap: G value (g)"), 0, r);
        grid.add(snapGSp, 1, r);
        grid.add(optionReset(() -> snapGSp.getValueFactory().setValue(AppSettings.SNAP_G_DEFAULT),
            snapGSp.getValueFactory().valueProperty(),
            () -> Math.abs(snapGSp.getValue() - AppSettings.SNAP_G_DEFAULT) < 1e-12), 3, r++);
        grid.add(new Label("Snap: roll rate (°/s)"), 0, r);
        grid.add(snapRollSp, 1, r);
        grid.add(optionReset(() -> snapRollSp.getValueFactory().setValue(AppSettings.SNAP_ROLL_DEFAULT),
            snapRollSp.getValueFactory().valueProperty(),
            () -> Math.abs(snapRollSp.getValue() - AppSettings.SNAP_ROLL_DEFAULT) < 1e-12), 3, r++);
        grid.add(new Label("Snap: per-meter rate (°/m)"), 0, r);
        grid.add(snapPmSp, 1, r);
        grid.add(optionReset(() -> snapPmSp.getValueFactory().setValue(AppSettings.SNAP_RATE_PM_DEFAULT),
            snapPmSp.getValueFactory().valueProperty(),
            () -> Math.abs(snapPmSp.getValue() - AppSettings.SNAP_RATE_PM_DEFAULT) < 1e-12), 3, r++);
        grid.add(new Label("Snap: element length (m)"), 0, r);
        grid.add(snapLenSp, 1, r);
        grid.add(optionReset(() -> snapLenSp.getValueFactory().setValue(AppSettings.SNAP_LEN_DEFAULT),
            snapLenSp.getValueFactory().valueProperty(),
            () -> Math.abs(snapLenSp.getValue() - AppSettings.SNAP_LEN_DEFAULT) < 1e-12), 3, r++);
        grid.add(new Label("Horizontal snap (time)"), 0, r);
        grid.add(new HBox(12, hLocal, hGlobal), 1, r, 2, 1);
        grid.add(optionReset(() -> {
            if (!hLocal.isSelected()) { hLocal.setSelected(true); hApply.run(); }
        }, hLocal.selectedProperty(), hLocal::isSelected), 3, r++);
        grid.add(new Label("Vertical snap (value)"), 0, r);
        grid.add(new HBox(12, vLocal, vGlobal), 1, r, 2, 1);
        grid.add(optionReset(() -> {
            if (!vGlobal.isSelected()) { vGlobal.setSelected(true); vApply.run(); }
        }, vGlobal.selectedProperty(), vGlobal::isSelected), 3, r++);

        Scene sc = new Scene(grid);
        app.installMenuUndoKeys(sc);
        dlg.setScene(sc);
        app.beginMenuSession();
        dlg.setOnHidden(e -> app.endMenuSession());
        dlg.show();
        app.installViewKeys(dlg.getScene());
    }

}
