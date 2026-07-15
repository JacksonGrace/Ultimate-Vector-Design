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
        helpKey(box, "Hover curve", "white dot + the value there; hold Shift for the slope instead. Where a thin TRUE stroke has split off (see True speed), the readout adds the true value in parentheses.");
        helpKey(box, "Resulting (toolbar)", "read-only derived curves across the whole CONNECTED run (thin dashed; a small gap at each section boundary is deliberate; detached pieces draw their own when you edit them): with a force section active, pitch/yaw rates (°/s, geo colors); with a geo section active, felt vertical/lateral G (force colors). The picks live in a menu that stays open while you toggle them and closes when the mouse leaves it (or the button). Enabled curves get their OWN axis on the RIGHT edge of the plot — fitted to the visible view like the lanes — and hovering a curve shows its value there (Shift for its slope). No roll entry: the resulting roll rate IS the roll lane. These overlays are the one thing without a zero-line tint.");
        helpKey(box, "True speed", "a THIN solid line splitting off a curve is the TRUE ride — the same quantity at the speed the train ACTUALLY arrives with, instead of the section's design speed. The curves separate only where those speeds have drifted apart (an upstream edit changed what reaches the section, a bridge is quietly adjusting speed to meet the next piece, converting a bridge stitched pieces with different speeds, influencing a section, or a piece was pitched/rolled); where they split, the thin line is what you'd really feel — smooth where the track is truly smooth, stepping where it truly kinks, even when the design curves say otherwise. Sections chained directly in one piece re-freeze themselves automatically, so splits live at PIECE boundaries and deliberately-kept seams. Converting a conflicted section (whole or part) KEEPS its conflict and its shape exactly — nothing resolves silently. The drifted section's badge wears ≠v — detected ANYWHERE in the section now, not just its entry (v16.1): a pitched/rolled piece splits mid-section with a matching entry, and the info line then says \"true splits inside\" — and carries a RESOLVE SPEED CONFLICT submenu — conflicts resolve LEFT TO RIGHT, one section at a time: \"Make graphs real (keep shape)\" re-authors the curves at the true speed — the track stays put and the thin strokes vanish; it works even directly AFTER another conflicted section (v16.2) and resolves ONLY itself — the neighbors keep their own conflicts; \"Re-freeze design speed (keep graphs, reshape)\" keeps your curves and lets the geometry re-derive at the real speed — on a PITCHED/ROLLED piece it also folds the tilt into the piece's natural orientation first (the deltas read 0 after; v16.1 — the whole design/real split closes instead of just the entry point, and sibling sections of the piece re-solve with it) — available even right after another conflicted section, and THAT is how you transition a chain onto the real ride: re-freeze, then press Shift+B on the section's first node. In fact B/Shift+B ALWAYS line up the REAL graphs (v16.2; completed v16.3): wherever EITHER side of a seam carries a design/true split — a deliberate custom-speed section, a pinned stitch, a piece boundary — the match reads the neighbor's TRUE curves and writes the design values that reproduce them at YOUR design speed (real continuity is curvature continuity, since the true speed itself never jumps). It works from BOTH sides: the unconflicted side matches the conflicted neighbor's real curves too (that side may need a re-press or two to settle — the seam moves under it slightly). Deliberately-kept (pinned) speeds are matched AROUND, never silently closed. A custom-speed section added from the ⊕ dialog already arrives lined up (its held values are speed-converted), so B there only re-aims handles. And whenever B can't act — wrong node, no neighbor, nothing compiled, a vertical global-axis seam — the stats bar says exactly why. Re-pressing B changes nothing; matching from the conflicted section's LAST node toward a re-anchored neighbor refuses (the stats bar explains — that direction would chase itself). Only a live BRIDGE directly before a section blocks its submenu — it re-blends to follow anything you resolve, so convert the bridge first. ADDING a section after a conflicted one asks first: CONTINUE SPEED CONFLICT lines the new section up with the design graphs as before; TRANSITION TO REAL starts it from the true speed and true end values — a clean seam off the real ride, no resolving needed. The stats bar's top speed and the POV camera already ride the true speed.");
        helpText(box, "Value axes auto-scale to the curves; every lane curve is "
            + "tinted toward its zero line. The dotted line after a "
            + "lane's last node is its held final value — click it to add a real node. "
            + "Dragging past a plot edge grows that axis, faster the farther past. The "
            + "divider resizes the graph; the corner button on the 3D view collapses it.");

        helpHead(box, "Sections");
        helpText(box, "The ride is a chain of SECTIONS sharing one x-axis. The "
            + "axis follows the section you are editing — seconds of ride "
            + "time, or METERS of track over a distance-domain or "
            + "straight/curve section — and every other strip WARPS onto "
            + "it, so the whole ride always reads in one consistent unit "
            + "(the x-axis label says which). The "
            + "section you are editing draws normally; the others are faint "
            + "strips — click one to edit it. Boundaries show as vertical "
            + "lines. Each section integrates from the previous one's end, at "
            + "its own design speed frozen when it was created. If later "
            + "edits change the speed that actually ARRIVES there, the "
            + "section has DRIFTED: its badge wears ≠v, thin TRUE strokes "
            + "split off its curves (see Graph view), and boundary-matching "
            + "(B/Shift+B) re-freezes the seam and clears it.");
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
        helpKey(box, "Domain (time/distance)", "every force/geo section authors over TIME (seconds — the classic FVD model) or DISTANCE (meters of track); the badge wears ·m when it's meters, and the CONVERT… dialog's Domain row switches it through the NODE DENSITY window, exactly like every other convert: a fresh node carpet re-fits the same curves over the other unit (meters spacing for →distance) against the dashed originals — crank the density and the shape converges onto what you had; Apply commits (one Ctrl+Z back), Cancel restores exactly. Speeds, pins and any speed conflicts carry through; an influenced section folds its influence in (the whole-convert rule); reversed sections refuse. A distance section's badge wears ·m; its lanes hold the same quantities (G, °/s) over meters, and editing feels identical — Ctrl-snap and arrow nudges step by the element-length step (Options, default 1 m), A appends 10 m instead of 1 s, and the section's length is simply its last node. VIEW IN METERS / SECONDS is the EXACT-shape tool: a pure VIEW — the section keeps its native domain and nodes, but the plot's axis flips to the other unit and the strip warps to match (slow track stretches, fast track compresses; nothing is refit). You edit straight THROUGH the warp: drags, Ctrl-snaps and readouts work in the units you SEE and write back through the speed profile (the warp freezes per gesture so live rebuilds never slide the curve under the cursor); it works while influencing too. YOUR ZOOM SURVIVES all of it: flipping the domain, the view, or clicking a strip of the other domain lines the window's ends up in the new units instead of resetting. Straight/curve sections are inherently distance (radios read-only, but the seconds view shows their classic time layout); bridges have neither.");
        helpKey(box, "Axes (local/global)", "the same badge-menu block sets what the lanes rotate ABOUT. Roll: LOCAL (default, the classic behavior) twists about the track's own axis, so with zero roll rate the bank rides along with pitched turns; GLOBAL makes the roll lane drive the BANK VS THE HORIZON — its rate integrates to a bank target held by the same projection straight/curve sections use, so zero roll through an upward turn gives a level UPWARD SPIRAL instead of an inclined loop, and a constant rate winds the horizon-bank steadily whatever the track does. Geo sections also choose Yaw/pitch: LOCAL (track axes — the classic body rates) or GLOBAL (yaw = heading rate about the world's vertical, pitch = elevation rate; the two switch together). Vertical track has no heading or bank — global control holds off there, like s/c global banking. Global-axis lanes aren't body rates, but B/Shift+B still work on them (v16.3): the matcher reads the neighbor's WORLD-angle rates — heading, elevation and bank-vs-horizon rates, computed exactly — so every seam combination matches, including global|global, except seams on vertical track (no defined heading/bank there; the stats bar says so). Seams whose two sides pick DIFFERENT roll orientations match exactly too (v16.6): the neighbor's roll converts into your semantics through the transport relation at the boundary — value exact, so re-presses never creep. Other strips show their compiled curves, converts produce ordinary local sections, and a ⊕ same-kind hold carries the axis choice. All of it lives in ONE badge-menu window since v16.4 — ORIENTATION SETTINGS: pick the target semantics with the radios, then \"Switch to This Orientation\" (same graphs, the shape changes) or \"Bake This Orientation\" (converts the graphs through the node-density window, the track keeps its shape — millimeter-accurate, vertical stretches remaining the accepted rough edge); both enable once the radios differ from the section's current settings. While a strip EDITS INFLUENCE the same entry reads INFLUENCE ORIENTATION SETTINGS… and sets the axes the influence deltas integrate about instead (a force section's geo deltas pick roll and yaw/pitch; a geo section's force deltas pick roll) — switch only, since the delta lanes are yours to keep. Clicking an inactive section's badge just activates it; click again for its menu.");
        helpKey(box, "Rate units (°/s vs °/m)", "every force/geo section also picks what its RATE lanes mean (v16.6): PER SECOND (the classic FVD reading — degrees of rotation per second of ride) or PER METER (degrees per meter of track — the consumed rate is value × speed, so a °/m profile twists the same angles into the same meters whatever the train's speed; pair it with the DISTANCE domain and a geometric section's shape stops depending on design speed entirely, like straight/curve track). Set it when adding a section or convert an existing one through the Convert… dialog's Rate-units row. VERTICAL/LATERAL FORCE lanes are targets, not rates — a 2 g target is 2 g at any speed, there is no per-meter version of it — so on a force section the setting governs the roll lane. B/Shift+B convert across °/s|°/m seams (value and slope, at the seam speed); influence works on °/m sections too, with the delta lanes in °/m (v16.7). VIEW RATES IN °/m|°/s (badge menu, v16.7; completed v16.9) lets you EDIT the strip in the OTHER units: curve, nodes and handles all warp together through the speed profile, the left axis and default scales speak the viewed units (roll ±15 °/m, pitch/yaw ±10 °/m), a RIGHT-EDGE axis shows the lane's NATIVE units at the section's design speed, and hovering a curve appends the exact native value at that spot; drags and Ctrl-snaps work in the units you SEE and write back through the warp, and the view drops when you leave the section. ABSOLUTE ROLL (v16.7): with GLOBAL roll, the Roll behavior setting (Add or Convert… dialog) can make the roll lane the BANK ANGLE itself — the graph's height in degrees IS the bank vs the horizon (50 = banked 50° there, 0 = level, whatever came before). B/Shift+B read the seam's bank off the NEIGHBOR's side of the boundary and keep your lane's UNWRAPPED story — banking authored past ±180° (a cutback) matches at its own values instead of snapping to the mirrored equivalent; the SLOPE match is best-effort (an absolute lane sits one derivative below the rate lanes). Switching roll to Local reverts the lane to a rate; influence needs rate lanes. The badge wears · rates °/m.");
        helpKey(box, "⊕ at the ride end", "click the circled + on the dashed line past the last section to add one (its own undo step). ADD FORCE/GEOMETRIC SECTION… opens one dialog with every option: force vs geometric, TIME vs DISTANCE domain (the new section holds the ride's end state for 1 s / 10 m), local/global roll and yaw/pitch axes (defaults follow the previous section; global-axis holds start at zero rates — the straight, held-bank continuation), the conflict fork when the piece ends speed-conflicted, and the STARTING DESIGN SPEED behind a padlock — locked (default) it tracks what your choices imply (design end, or true end on \"Transition to real\"); unlock it to type a custom speed, which is a DELIBERATE speed conflict: the section arrives wearing ≠v, keeps it through edits, and B/Shift+B on its first node lines the REAL graphs up across the seam. Or add a STRAIGHT/CURVE section starting as a 10 m straight — all three continue seamlessly from whatever came before (after a speed-conflicted end the straight/curve entry stays ONE item and just reveals the same Continue/Transition choice when clicked, v16.5). Adding zooms the graph back out to the whole piece so the new (now active) section is under your cursor, wherever you were zoomed. ATTACH FLOATING PIECE appends another piece's whole section chain here (the floating placement is discarded — attaching means \"continue from this end\"). BRIDGE TO PIECE N spans the gap to the next piece with a smooth connector (see Pieces below). Any open menu dismisses on the next click, including on the graph.");
        helpKey(box, "Convert", "every section's badge menu ends with ONE CONVERT… entry (v16.6) opening a dialog with every output setting: WHOLE or PART, TYPE (force/geometric), DOMAIN (time/distance), ROLL AXIS, YAW/PITCH AXIS (grayed for force), and RATE UNITS (°/s vs °/m) — each row starts at the section's current setting and its ↺ button puts that row back; ANY combination converts to ANY other in one pass (global-axis outputs stay undefined over near-vertical track, the usual cap). Choosing PART opens a SPLIT LINE on the strip carrying your chosen output settings: drag it (the readout shows the exact %), click either side to choose which portion converts, and on lane strips the line snaps onto the SELECTED lane's nodes — click directly ON a node to land the line there, or drag near one and it magnetizes — a cut on a node just partitions the lanes. The UNCONVERTED side keeps its original form exactly (lanes split with zero disturbance; straight/curve elements split by length and angle, the line steering clear of transition blends where a cut would reshape; the downstream side's design speed re-freezes to the speed at the cut, so the shape continues seamlessly). Every convert then opens the NODE DENSITY window (above the graph; DRAG it by its body if it's in the way — it hides while you're in another app and comes back when you return): type a spacing or ride the log slider and watch the nodes re-fit LIVE against the original curves (dashed, behind — your accuracy reference); the graph itself is read-only until you Apply or Cancel; check OPTIMAL PLACEMENT to let the fit choose node positions and counts instead — nodes land only where the shape needs them (a flat lane keeps just its two endpoints) and the slider becomes accuracy (0.01–10 % of each lane's range). Apply commits — one Ctrl+Z returns the original — and Cancel puts everything back untouched. First and last nodes always sit exactly on the section's ends, with the rest spaced evenly between them. Roll is the same lane in both kinds, so it passes through unchanged. The fit reads the track's pitch/yaw EXACTLY now (v15.1) — crank the density or accuracy and the fitted graphs converge onto the originals instead of stopping slightly off (only roll is still measured off the samples, so sharp roll spikes round a hair). The fitted nodes also AREA-MATCH the originals (v16.5): tiny value nudges pin each curve's integral — the turned angle — at every node, so the converted track lands ON the original's endpoint instead of drifting near it (millimeters at normal densities; even spacing tends to land closer than Optimal placement on violent layouts). Converting a SPEED-CONFLICTED section keeps the conflict AND the shape exactly — design speed, ≠v badge and true strokes all stay; nothing resolves behind your back (partial converts leave both halves conflicted, since both continue the same design profile). Converting a bridge changes NOTHING it doesn't have to: the next track keeps its design speed, its graphs, and its designed/true split exactly as they were — the seam where the pieces were stitched together stays deliberately independent (upstream edits won't re-sync it) until you resolve it from its badge menu. Converting a WHOLE bridge connects the pieces directly — the next piece's sections carry on after the new lanes as one track. Reversed sections can't convert — their lanes read in the authored direction, opposite the train. An INFLUENCED section (below) refuses the PART convert until its influence is baked or discarded; the whole convert folds the influence in. (Convert changes REPRESENTATION at the same design speed; INFLUENCE, below, is the way to sculpt the other kind's graphs while KEEPING the design lanes.)");
        helpKey(box, "Influence", "a force or geo section's badge menu offers INFLUENCE — sculpt the section's RESULTING other-kind graphs without giving up its design lanes. A force section gains DELTA lanes for Δ Pitch and Δ Yaw (°/s); a geo section gains Δ Vert G and Δ Lat G. (Roll takes no deltas — it is the shared third lane you already author directly; its strip shows the resulting roll dotted, read-only, and its lane button disables while influencing.) Whatever you draw ADDS to the resulting graphs: resulting = base + delta, exactly — the base being what the section already does. Entering influence flips the strip to the other kind's colors and units, the lane buttons wear Δ, the badge wears fx, and NOTHING ELSE changes: the section's design speed, the next section's speed, any speed conflicts — all exactly as they were (an influenced section behaves like a normal section with nudges on top). A fresh influence starts as a flat zero line with a node at the START and at the END of the section, and the track doesn't move until you sculpt. Two ways to look at it (badge-menu radios): INFLUENCE VIEW edits the deltas around the zero line with the RESULTING graphs dotted behind (hover them for values); RESULTING VIEW is purely visual — the same nodes RIDE the resulting curve, so you place a node where you want the result to BE (the drag readout shows the resulting value plus the delta it took; Ctrl-snap lands the RESULTING value on the grid; the faint dashed line underneath is the base, the tint between them is what your influence adds, and handles keep their exact Influence-view angles — the whole node+handle assembly just rides the base). Deltas hold their last value to the section's end and can never extend it (the end is a hard wall). Uncheck \"Edit influence\" to return to the ordinary DESIGN lanes — the influence stays live, and a THIN true stroke shows what it does to this section's own quantities: author zero laterals, add yaw influence, and the laterals appear as the thin line beside your untouched flat lane. While influencing, the Resulting ▾ picks read the TRUE values of the other kind (the right axis says so), and B / Shift+B on a delta lane's FIRST or LAST node returns it to NO INFLUENCE — value zero, flat handle (the delta's natural boundary is \"change nothing\"). Ways out, one undo step each: BAKE INFLUENCE INTO DESIGN GRAPHS re-authors the section through the node-density window with the influence folded in (deltas gone); DISCARD INFLUENCE drops the deltas and the section snaps back to its authored shape EXACTLY. DISTANCE-domain sections influence too (v16.1): the deltas author over METERS, the wall is the section's length, and the meters/seconds view swap keeps working while influencing; switching the DOMAIN of an influenced section folds the influence in (like the whole convert). Reversed sections refuse new influence (reverse the piece back first); a reversed piece that already carries influence keeps applying it, with editing locked until it's forward again. GLOBAL-AXIS sections influence like any other (v16.5), and the badge menu's INFLUENCE ORIENTATION SETTINGS… (shown while editing influence) picks the axes the deltas themselves integrate about — local body axes or the global heading/elevation/bank — independent of the section's own orientation; fresh influence mirrors the section's roll choice.");

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
        helpKey(box, "Move", "toolbar \"Placement ▾\" (next to the piece selector) > Piece x/y/z + Piece yaw edit the ACTIVE piece's placement — the whole piece slides/turns rigidly; other pieces never move. Yaw is a DELTA on top of the piece's natural orientation: set it back to 0 and the original heading returns exactly. PIECE PITCH/ROLL rows sit below (each behind a PADLOCK, locked by default — click it to unlock and edit): these tilt the piece's START and the whole piece rotates RIGIDLY with it — the geometric shape is preserved exactly, and because gravity now cuts through the track differently, the force graphs split design-vs-real (thin true strokes show what the tilted track really feels; the design lanes stay yours). Zeroing pitch/roll reverts exactly. When a BRIDGE connects into this piece, these padlocks are the SAME locks as the bridge panel's — flip them in either place — and in optimize-orientation mode the bridge's march owns the values (spinners freeze). One corner to know: SPLITTING or REVERSING a tilted piece bakes the tilt in (the new piece re-solves from the tilted start instead of staying rigid). The spinners re-sync when you switch pieces. The piece being edited renders FULL-COLOR in 3D; the others are desaturated. (3D drag gizmos are on the roadmap.)");
        helpKey(box, "Reverse", "toolbar \"Placement ▾\" > \"Reverse piece (flip travel direction)\": the TRACK STAYS PUT and the train runs the piece the other way — the old exit becomes the entry. The strips reorder to train order and every section badge wears ◀; sections keep editing in their authored direction. Where a seam isn't level it re-joins with an honest kink; reversing again restores everything exactly. One undo step. (A piece containing a bridge refuses to reverse — delete the bridge first.)");
        helpKey(box, "Bridge", "⊕ menu > \"Bridge to piece\" > pick ANY other piece: a smooth connector from this piece's end to that piece's entry — position, direction, roll (its rate AND how fast that rate is changing), curvature, the RATE GRAPHS' slopes and even the SPEED all flow across both seams (the bridge's design speed eases onto the next piece's), so the joined pieces ride, read and EDIT as ONE track (see Pieces above). A reversed section on the far side is aimed at exactly as it really starts. Picking a piece that isn't next on the timeline moves it there (connecting means \"this one follows\"). The bridge FOLLOWS LIVE: move either piece or edit the track and it re-joins as you drag. RESIZE it by DRAGGING the vertical line at the END of its strip (the cursor becomes ↔ there): the next piece slides along and the bridge re-solves live — Ctrl snaps whole meters (the element-length step), a readout shows the length, and one Ctrl+Z undoes the whole drag (disabled while Optimize length is checked). OPTIMIZE LENGTH (checked automatically when you switch into an optimize mode) picks the length itself: the shortest bridge whose transition is as smooth as any longer one would be — one exact answer for a given pair of seams, no matter where the next piece currently sits or how it's yawed; Uncheck it to set the length by hand. When the seams continue each other perfectly — a split piece bridged back together, even after its second half was dragged and spun anywhere — the optimum collapses to zero and the app offers to CONNECT THE PIECES DIRECTLY instead (the next piece snaps back into the seamless join). With a bridge active, the toolbar button becomes BRIDGE PROPERTIES ▾ — the bridge's own panel. A FRESH bridge starts in \"No optimization\" — it hasn't moved anything and the panel says so (place the next piece fully by hand: x/y/z plus any UNLOCKED orientation). The next piece's YAW, PITCH and ROLL rows each wear a PADLOCK, tinted red when LOCKED and green when UNLOCKED: locked (the default for pitch and roll) = the value is frozen and the bridge bends to meet it; click the padlock to unlock and edit. Pick \"Optimize position and ORIENTATION\" to have the Length box place the next piece where the track would NATURALLY go — rotating every unlocked axis to meet it, while locked axes keep your values (all three locked grays the mode out — unlock something first) — and an optimized bridge's own shape IS that natural path (the seams' turn and roll carried forward, blended across the bridge, so the graphs change as little as possible and the turning spreads evenly instead of bunching near the seams — split a constant turn and re-bridge it and the bridge IS that same turn, longer lengths simply carry the piece further around the circle, straight track continues dead ahead), or \"Optimize position\" (same march for position, but YOU own the whole orientation and the bridge bends to it); switching into an optimize mode applies it immediately, and switching BETWEEN the two optimize modes keeps the current length. A round trip through \"No optimization\" is polite: leave everything untouched there and coming back restores Optimize length exactly as you left it; move the piece by hand and coming back keeps THAT bridge's length (Optimize length unchecked) — and the grayed Length box keeps reading the manual bridge live. Hovering the bridge's curves reads their values like any lane (Shift = slope). An optimized bridge is deterministic: the same seams at the same length give THE same bridge, every time (grow the length and bring it back: identical). And when you want to sculpt the bridge directly, badge > \"Convert whole bridge to geo/force section\" turns ALL of it into editable lanes and CONNECTS the pieces (the next piece's track carries on after the lanes), while \"Convert part to geo/force section…\" opens a SPLIT LINE on its strip: drag the line (a readout shows the exact % of the bridge — the cut lands precisely there), click either side to choose which portion becomes editable lanes (left is the default; the chosen side tints), then press the [Convert … part] chip — the other side REMAINS a live bridge and the track stays CONNECTED end to end (left part: the lanes slot in before the bridge; right part: the lanes become the start of the next piece, whose own track carries on after them). Either way the NODE DENSITY window opens to tune the fit live (see Convert under Sections). Cutting an OPTIMIZED bridge keeps the remainder on its natural path: the next piece nudges a few centimeters so the join stays exact and the graphs keep their shape — cutting a hand-placed (no-optimization) bridge moves nothing. [Cancel] or clicking another strip leaves the mode; Ctrl+Z brings the whole bridge back. A cramped bridge (big drop or clashing headings over a short gap) still swings hard INSIDE — smooth but fast; lengthen it to calm it. While an optimize mode is on, editing the track BEFORE the bridge re-places the next piece automatically — the whole connection follows your upstream edits live. If the target piece is ever deleted, the bridge keeps its last shape and its badge shows \"bridge !\". A piece ending with a bridge can't be extended — its end is glued; extend the next piece instead. Deleting the bridge just disconnects: both pieces stay exactly where they are.");
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
        helpKey(box, "Delete", "remove the selected node(s): interior deletes bridge the gap — the neighbors' handles keep their fractional length AND their slope (the curve still leaves them at the same angle), so the surviving shape keeps its character; deleting the last node shortens the ride; node 0 stays. (Right-click no longer deletes — it opens the node's dialog.)");
        helpKey(box, "Double-click", "type the transition time, value and handle sizes exactly — typed handle sizes slide the knob along its current angle (like S). On the first node the ride's start speed is edited here too.");
        helpKey(box, "B · H · F", "smooth/broken · level the selection onto the MAIN node (one node: flatten its handles) · straighten between two adjacent selected nodes. On a node at a SECTION EDGE, B instead returns it to the boundary — the neighboring section's value AND slope (also a button in its double-click dialog); Shift+B does that for ALL THREE lanes' edge nodes at once (one undo step). Across MODES the match is computed EXACTLY from the neighbor's authored curves (converted between rates and forces), so pressing it on either side of any geo/force boundary — banked, rolling, sloped, any order — agrees: re-pressing changes nothing. It also works against a BRIDGE next door (matched from its compiled curves). Matching FIRST re-freezes the downstream section's design speed to what actually arrives at the seam (the same rule new sections get at creation) — editing upstream drifts the two apart, and drifted seam speeds made the felt-G curves step exactly on the boundary line even with the rates matched (the \"jolt\"). After a match, the rate graphs, the felt-G curves AND the ride itself all flow through the seam.");
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
        helpKey(box, "POV", "Space. Starts hands-off: the camera sits on the track (following edits) while W/S and the S key stay with the graph — and a plain click on any track selects that piece + section for editing, just like orbit view. Right-click CAPTURES: mouse hides, W/S ride at the TRUE simulated speed (Shift/Ctrl faster/slower), no mouse look; right-click releases. Scroll jumps along the track either way. Riding across a piece boundary SELECTS that piece — the graph follows the ride. Entering POV starts at the selected piece's beginning if you picked a different piece since the last ride, and otherwise resumes right where it left off.");
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
            optionReset(() -> fovTrack.setValue(AppSettings.FOV_DEFAULT),
                fovTrack.valueProperty(),
                () -> Math.abs(fovTrack.getValue()
                    - AppSettings.FOV_DEFAULT) < 1e-9));
        grid.addRow(r++, new Label("Fly & orbit FOV"), fovFly, fovFlyVal,
            optionReset(() -> fovFly.setValue(AppSettings.FOV_DEFAULT),
                fovFly.valueProperty(),
                () -> Math.abs(fovFly.getValue()
                    - AppSettings.FOV_DEFAULT) < 1e-9));
        grid.addRow(r++, new Label("Mouse sensitivity (°/px)"), sens, sensVal,
            optionReset(() -> sens.setValue(AppSettings.SENS_DEFAULT),
                sens.valueProperty(),
                () -> Math.abs(sens.getValue()
                    - AppSettings.SENS_DEFAULT) < 1e-9));
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
