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
     * added here, in the matching section, when it ships. REWRITTEN v19.11
     * (D240, user spec): terse, purely user-facing — say what things DO,
     * never how they work inside; no all-caps emphasis; diagrams where
     * they teach faster than words. Keep additions to a line or two.
     * Layout: helpHead = section, helpKey = aligned key/gesture row,
     * helpText = prose, helpCanvas = a drawn diagram.
     */
    void showHelpDialog() {
        VBox box = new VBox(5);
        box.setPadding(new Insets(18));

        // diagram: a transition with node, handles and the insert dot
        GraphicsContext g = helpCanvas(box, 560, 110);
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

        helpHead(box, "Getting started");
        helpText(box, "A coaster is a chain of sections, each designed in "
            + "the graph editor. Force sections shape the track from the "
            + "forces the rider should feel (vertical G, lateral G, roll "
            + "rate); geometric sections from pitch/yaw/roll rates or "
            + "absolute angles; straight/curve sections from element "
            + "lengths and radii. Edit the curves and the 3D track follows "
            + "live. Positive lateral G pushes the body right; positive "
            + "yaw turns left. Pick a lane with the colored buttons, keys "
            + "1/2/3, or by clicking a dimmed curve. A new section opens on "
            + "its first lane.");
        helpText(box, "Ctrl+Z / Ctrl+Shift+Z undo and redo everywhere. "
            + "File > New opens the start screen for a first coaster; "
            + "Save / Save As write the .fvd (the title shows * while "
            + "unsaved, and the camera pose saves along); Export (Ctrl+E) "
            + "hands a coaster — or one piece — to NoLimits 2. A file can "
            + "hold several coasters (Coaster menu). Keys shown here are "
            + "defaults — rebind anything in Edit > Shortcuts.");

        // diagram: the graph editor's landmarks
        GraphicsContext m = helpCanvas(box, 560, 150);
        m.setStroke(Color.rgb(70, 76, 92));
        m.setLineWidth(1);
        m.strokeRect(28, 30, 448, 84);            // plot frame
        m.setStroke(Color.rgb(200, 205, 215));
        m.strokeLine(250, 30, 250, 114);          // seam (double line)
        m.strokeLine(253, 30, 253, 114);
        m.setFill(Color.rgb(200, 205, 215));
        m.fillPolygon(new double[]{ 251.5, 257.5, 251.5, 245.5 },
            new double[]{ 60, 66, 72, 66 }, 4);   // smoothing diamond
        m.setStroke(FvdDesigner.LANE_COLORS[0]);
        m.setLineWidth(2);
        m.beginPath();
        m.moveTo(32, 95);
        m.bezierCurveTo(120, 95, 160, 50, 248, 50);
        m.stroke();
        m.setStroke(FvdDesigner.GEO_LANE_COLORS[0]);
        m.beginPath();
        m.moveTo(255, 50);
        m.bezierCurveTo(330, 50, 380, 80, 472, 80);
        m.stroke();
        m.setLineWidth(1);
        // badges
        m.setFill(Color.rgb(45, 50, 62));
        m.fillRoundRect(33, 34, 38, 13, 4, 4);
        m.fillRoundRect(258, 34, 30, 13, 4, 4);
        m.setFill(Color.rgb(200, 205, 215));
        m.setFont(Font.font(9));
        m.fillText("force", 41, 44);
        m.fillText("geo", 265, 44);
        // the ⊕ add button past the end
        m.setStroke(Color.rgb(200, 205, 215));
        m.strokeOval(486, 64, 16, 16);
        m.strokeLine(490, 72, 498, 72);
        m.strokeLine(494, 68, 494, 76);
        // the +V box and the resulting axis
        m.setFill(Color.rgb(45, 50, 62));
        m.fillRoundRect(28, 120, 24, 14, 4, 4);
        m.setFill(Color.rgb(120, 220, 130));
        m.setFont(Font.font(9));
        m.fillText("+V", 33, 130);
        m.setStroke(Color.rgb(120, 125, 140));
        m.strokeLine(476, 30, 476, 114);
        // labels
        m.setFill(Color.rgb(200, 205, 215));
        m.setFont(Font.font(11));
        m.fillText("type badge = the section's menu", 60, 22);
        m.strokeLine(52, 27, 52, 34);
        m.fillText("seam line — click for its menu, drag ◇ = smoothing",
            140, 128);
        m.strokeLine(251, 118, 251, 124);
        m.fillText("add", 504, 60);
        m.fillText("speed controls", 58, 131);
        m.fillText("resulting axis", 482, 24);
        m.strokeLine(478, 27, 478, 30);

        helpHead(box, "Graph editor");
        helpKey(box, "View", "scroll = zoom around the cursor (fully out resumes auto-fit) · right-drag = pan · the divider resizes the graph; the corner button on the 3D view collapses it.");
        helpKey(box, "Hover", "on a curve: its value (Shift = slope; Ctrl pre-snaps the insert spot). On a node: its timing and value. Where a thin true stroke exists, readouts add the true value in parentheses.");
        helpKey(box, "Resulting ▾", "read-only derived curves across the whole run — resulting rates over a force section, felt G over a geometric one, plus an optional roll/bank pick — on their own right-edge axis. \"Resulting graph settings…\" picks units, axes and rate vs absolute presentation. Hover a curve to read it.");
        helpKey(box, "True strokes", "a thin solid line splitting off a lane is the real ride where it differs from the design curve — see Speed & conflicts.");
        helpText(box, "Value axes auto-fit to the visible curves. The dotted "
            + "tail after a lane's last node holds its final value — click "
            + "it to add a node there. Dragging past a plot edge grows the "
            + "view.");

        helpHead(box, "Nodes");
        helpKey(box, "Click curve", "insert a node exactly on the curve; keep the button down to drag it away in the same gesture. Ctrl pre-snaps the spot.");
        helpKey(box, "Drag", "free move. Hold S to slide along one axis, Z / X to lock vertical/horizontal, Ctrl to snap by the Edit > Snapping steps (the section end is its own magnet). Arrows nudge by the snap step — Shift a tenth, Ctrl to the next round step. A lane's last node stops at the section end — release and re-grab it to extend past. Lanes you have never edited follow the section end: move one lane's last node and the untouched lanes' ends move with it, growing or shrinking the section (edit a lane and it stops following).");
        helpKey(box, "Select", "Shift+click adds; box-drag on empty plot selects a group (drags move it together). Delete removes; deleting the last node shortens the ride.");
        helpKey(box, "Right-click", "a node: its options menu — edit, smooth/broken, level and straighten with their locks, splits, connect, delete (only what applies appears). A segment: its lock menu. Right-drag still pans.");
        helpKey(box, "Double-click", "the node editor: transition time, the lane's value, handle lengths and slopes. Every change applies immediately and is a normal undo step; the boxes also follow edits made anywhere else live. Esc or Enter closes; it opens just above the graph, at its node. Clicking a grey readout twin swaps what the box edits — absolute time instead of transition, change-from-previous instead of value. Node 0's dialog also edits the start speed (later sections: their initial design speed). The handle boxes stop on the named ease fractions while stepping, and show a blue tag while sitting on one.");
        helpKey(box, "B / Shift+B", "smooth/broken toggle — on a section-edge node it matches the boundary instead: value and slope from the neighbor (Shift+B = all three lanes at once). Works across every mode, axis and unit; when it refuses, the stats bar says why.");
        helpKey(box, "H / Shift+H", "level the selection onto the main node (one node: flatten its handles — press again to level it with the previous node) · level-lock the segments between selected nodes, or one node's segment to its previous (either end drags the line; re-apply to unlock).");
        helpKey(box, "F / Shift+F", "straighten every segment between selected nodes onto its chord (one node: to its previous node) · straight-lock the same (re-apply to unlock).");
        helpKey(box, "A", "append 1 s of held value (straight/curve sections: a 10 m straight). A smooth, sloped last node carries its slope into the hold; with a node selected, the hold goes in after it smoothly.");
        helpKey(box, "Ripple", "toolbar checks — a drag also shifts everything later in time and/or value.");

        helpHead(box, "Handles");
        // diagram: smooth vs broken
        GraphicsContext hd = helpCanvas(box, 560, 78);
        hd.setStroke(FvdDesigner.LANE_COLORS[0]);
        hd.setLineWidth(2);
        hd.beginPath();
        hd.moveTo(30, 60);
        hd.bezierCurveTo(80, 20, 140, 20, 190, 60);
        hd.stroke();
        hd.beginPath();
        hd.moveTo(320, 60);
        hd.bezierCurveTo(360, 20, 400, 55, 470, 20);
        hd.stroke();
        hd.setStroke(Color.WHITE);
        hd.setLineWidth(1);
        hd.strokeLine(70, 32, 150, 32);            // smooth: collinear
        hd.strokeLine(345, 35, 375, 21);           // broken: kinked
        hd.strokeLine(375, 21, 410, 45);
        hd.setFill(Color.WHITE);
        hd.fillOval(66, 28, 8, 8);
        hd.fillOval(146, 28, 8, 8);
        hd.fillRect(341, 31, 8, 8);
        hd.fillRect(406, 41, 8, 8);
        hd.setFill(FvdDesigner.LANE_COLORS[0]);
        hd.fillOval(104, 26, 12, 12);
        hd.fillOval(369, 15, 12, 12);
        hd.setFill(Color.rgb(200, 205, 215));
        hd.setFont(Font.font(11));
        hd.fillText("smooth — round knobs, handles mirror", 30, 74);
        hd.fillText("broken — square knobs, independent", 320, 74);
        helpKey(box, "Drag", "free (angle + length) · S slides along the current line · Ctrl snaps the length to the ease presets — cubic 0.33, sine 0.36, quintic 0.47, circle 0.55.");
        helpText(box, "B toggles smooth/broken. Handle lengths are fractions "
            + "of the segment, so re-timing nodes keeps curve shapes. An "
            + "off-scale handle draws as a dashed stub — click its marker "
            + "to expand the scale.");

        helpHead(box, "Sections");
        helpText(box, "The ride is a chain of sections on one shared axis "
            + "— seconds, or meters where a section authors by distance "
            + "(the axis label says which). The active section edits "
            + "normally; click a faint strip to switch. Each section "
            + "starts from the previous one's end at its own design "
            + "speed.");
        helpKey(box, "Kinds", "Force = felt-G lanes. Geometric = rotation-rate lanes, or absolute elevation/heading/bank angles; gravity never bends a geometric shape. Straight/curve = an element list. Bridges connect pieces.");
        helpKey(box, "Type badge", "the gray tag atop each strip names the section's type — Force, Geo, S/C, Bridge — with its tags (·m distance domain, ◀ legacy reversed, fx influenced, ! an orphaned bridge) and is its settings button: view toggles, orientation settings, initial design speed, resolve conflict, influence, convert, delete; the menu's bold first row is the section's run-wide title (Force Section 1, Geometric Section 2, …). It wears ≠v where the section enters at a speed other than its design speed and ≠o where the piece's real orientation differs from its design orientation (both can show). A first click on an inactive badge just activates it.");
        helpKey(box, "Domain", "sections author over time or distance. \"View in the other domain\" flips the axis as a pure view — you edit through it; the Convert dialog's Domain row refits instead.");
        helpKey(box, "Axes & units", "\"Orientation settings…\" picks what the lanes mean. Roll: local, or global (bank vs the horizon; absolute = the lane is the bank angle). Geo yaw/pitch: local or global — and under global, absolute: the lanes are the elevation angle and the heading relative to the piece's entry (Elevation blue, Heading yellow-green, Bank orange; elevation within ±90° — a loop needs rate lanes). Rate units make rate lanes per-second or per-meter. \"Switch\" keeps the graphs and changes the shape; \"Bake\" converts the graphs and keeps the shape. B matches boundaries across all of it. The button at the bottom of the dialog is labeled like the toolbar button that owns the section's placement — Track Piece ▴, or the strict seam / bridge before it — and opens that window.");
        helpKey(box, "Convert…", "one dialog for every re-authoring: whole or part, to any type, domain, axes or units. Lanes whose meaning doesn't change keep their nodes exactly. Part opens a split line — the side chip (or clicking a side) toggles which half converts, Confirm applies. Everything commits through the node-density preview: tune density live against the dashed originals (you can still switch lanes to inspect each fit); Apply is one undo step, Cancel restores exactly.");
        helpKey(box, "Influence", "badge menu — delta lanes that nudge the section's resulting other-mode graphs point by point; where the deltas are zero the section behaves exactly per its own graphs. \"Bake\" folds them in; \"Discard\" restores exactly. B on a delta edge node lines the resulting graphs up with the neighbor.");
        helpKey(box, "⊕ (ride end)", "adds a section holding the ride's end state; its design speed sits behind a padlock (unlock to type = a deliberate speed conflict). \"Add floating piece…\" opens the start screen for a new detached piece (a coaster with no track offers the same as a centered button). After a conflicted end the menu forks: continue the conflict or transition to real. Also here: attach a floating piece and bridge to another track piece — a whole run moves with its first piece; a piece ending in an orphaned bridge can't be picked.");
        helpKey(box, "Insert section…", "seam menu (click any boundary line): the same choices as ⊕ plus an initial length, inserted at that seam — the track after it re-solves from the new exit. \"Lock Subsequent Track Shape\" puts a strict seam after it instead, so the track after keeps its shape and just moves. On a strict seam the section goes in before the seam.");

        helpHead(box, "Straight/curve sections");
        helpText(box, "Elements instead of lanes: straights (length) and "
            + "arcs (radius, angle, direction — 0 = flat left turn, 90 = "
            + "pull-up; negative mirrors), authored per meter — the shape "
            + "ignores speed.");
        helpKey(box, "Edit", "click a band = select · double-click = its dialog (kind, size, smoothing, roll) · right-click inserts and deletes · drag a separator or the section end to resize (Ctrl snaps) · A appends a straight.");
        helpKey(box, "Roll", "per element: a global bank angle held against the horizon (0 = rails level), or a body-roll rate (0 = the bank drifts through pitched turns). Transitions ease without a jolt; non-zero roll shows on the chip.");
        helpKey(box, "Smoothing ◇", "the diamond on each boundary sets that transition's blend length (drag; snaps shut). The diamond on the section's entry is the bank ease into the first element — 0 is an instant snap.");

        helpHead(box, "Speed & conflicts");
        helpText(box, "Every section owns a design speed; the true ride "
            + "speed comes from energy and friction. Sections chained "
            + "inside one piece follow the real arriving speed "
            + "automatically, so lasting differences live at piece "
            + "boundaries, deliberate custom speeds, tilted pieces and "
            + "bridge stitches.");
        helpKey(box, "≠v & true strokes", "≠v marks a section that enters at a true speed other than its design speed; ≠o marks a piece whose real orientation differs from its design orientation (hover the badge menu's title row for the numbers). Thin true strokes split off the curves wherever the real ride differs — the same quantities at the speed the train really has. The POV and the stats line always ride true values.");
        helpKey(box, "Resolving", "badge > Resolve conflict — the submenu's title says what will be resolved: speed, orientation or both. For a speed conflict the two ops act on that section: \"Convert Real to Design (keep track shape)…\" refits the graphs at the true speed — the track stays put; \"Set Design to Real (keep graphs, reshape)\" re-solves at the real speed. For an orientation conflict (≠o) they act on a range of sections — from this one through an end you pick — behind strict seams that keep the rest of the piece exactly as it is: Set Design to Real is one step; Convert Real to Design walks the range one preview at a time (Ctrl+Z steps back a section, Cancel keeps what was applied). Both ops also work through a smoothed seam — the smoothing comes back afterwards; a speed conflict the smoothing itself opened shows only the hint to shrink the ◇ window. \"Initial design speed…\" (just above it in the menu) sets a starting speed any time, live as you type (a number box — wheel, Shift, Ctrl and hold-repeat like every other); its \"Set Design to Real\" button sets just the speed to the real one at once and lets it follow (an orientation conflict stays — \"Design → Real Orientation\" is for that); Confirm keeps, Cancel puts the speed back.");
        helpKey(box, "Friction", "Coaster Options: rolling friction (m/m) and air resistance (m⁻¹). New coasters start near NoLimits 2's values; zero both for the loss-free model. Losses are paid both ways — see Reverse.");
        helpKey(box, "+V speed strip", "the +V box (bottom-left) opens the speed-control line (▲ expands the tall view). Click the line = a mark; double-click a mark (or click-drag a free stretch) = a speed control with its options popup. Speed holds or clips to a target (Max = ceiling, Min = floor, both = hold exactly); Acceleration launches/brakes at a felt G — the 🔗 button links or unlinks the brake side; Complex swaps the constant for a graph edited with the full editor (every gesture works, at smaller nodes). A fresh control starts at its enter speed; the popup's \"Enter:\" readout is clickable to reset the target. Controls act on design and real speed alike, and wear colored plates in 3D.");
        helpKey(box, "Strip editing", "drag a control's body to move it, its start diamond to resize it from the left, its end diamond from the right (the popup's Start box moves it); Delete removes the selection; Ctrl snaps; the strip's top edge drags to any height. The tall view shows the resulting true speed (bright green dotted) and, wherever the design speed differs from it, the design speed as a finer, dimmer dotted line — hover the true line to read both; a complex speed control's longitudinal G draws blue dotted. Every control's start and end also sits on the 3D track as a disk in the control's color: drag it along the track to resize the control exactly as its diamond would (the strip opens to show it), double-click it for the options popup. Hovering or dragging a diamond or a disk reads its position (\"t = 3.20 s\" / \"s = 12.5 m\"); with the section viewed in the other domain the drags and Ctrl snaps use the units you see and the popup's Start / Length boxes gain grey twins in them. Selecting a node deselects the control and the other way round (a right-drag pan or a right-click menu leaves the selection alone); the options popup stays open and follows the selected control, and its Edit Speed / Acceleration / Deceleration buttons select the control they open the strip for.");
        helpKey(box, "Stalls", "where the ride can't make a climb, everything after turns transparent and the POV treats that point as the end of the track. A Min speed control (a lift) over the climb is the fix.");

        helpHead(box, "Pieces & strict seams");
        // diagram: pieces joined into one run
        GraphicsContext pr = helpCanvas(box, 560, 84);
        pr.setStroke(Color.rgb(70, 76, 92));
        pr.setLineWidth(1);
        pr.strokeRect(24, 26, 150, 32);
        pr.strokeRect(180, 26, 150, 32);
        pr.strokeRect(392, 26, 144, 32);
        pr.setStroke(Color.rgb(200, 205, 215));
        pr.strokeLine(176, 22, 176, 62);       // strict seam double line
        pr.strokeLine(179, 22, 179, 62);
        pr.setFill(Color.rgb(200, 205, 215));
        pr.fillPolygon(new double[]{ 177.5, 183, 177.5, 172 },
            new double[]{ 36, 42, 48, 42 }, 4);
        pr.setStroke(Color.rgb(255, 170, 90)); // bridge squiggle
        pr.setLineWidth(2);
        pr.beginPath();
        pr.moveTo(332, 42);
        pr.bezierCurveTo(352, 30, 370, 54, 390, 42);
        pr.stroke();
        pr.setLineWidth(1);
        pr.setFill(Color.rgb(200, 205, 215));
        pr.setFont(Font.font(11));
        pr.fillText("piece 1", 78, 45);
        pr.fillText("piece 2", 234, 45);
        pr.fillText("piece 3", 442, 45);
        pr.fillText("strict seam — frozen after it", 96, 76);
        pr.fillText("bridge — a live connector", 320, 76);
        pr.fillText("joined pieces edit as one run", 24, 18);
        helpText(box, "A ride can be several pieces, each placed "
            + "independently. Pieces joined by bridges or strict seams "
            + "edit as one run; detached pieces render desaturated. "
            + "Switch with the toolbar Piece selector — it lists whole "
            + "track pieces, one entry per run, and hides when the coaster "
            + "is a single run — or by clicking track in 3D. Deleting a "
            + "piece's last section deletes the piece.");
        helpKey(box, "Move / tilt", "toolbar \"Track Piece ▴\": a window just above the graph with Anchor x/y/z and the real starting orientation — yaw (+ = left), then pitch and roll, each with a padlock (on a free piece it guards the box against accidental edits; on a piece a bridge leads to, it is the bridge's own lock — see Window), a ↺ back to the value the window opened with and a grey readout of the other orientation (design or real). The anchor is the first piece of the run: x/y/z always move the whole run from its head; with a piece behind a strict seam selected, the orientation rows point you at that seam's button; on a piece a bridge leads to, only a free axis under the bridge's optimize position and orientation grays. \"Mirror\" flips the piece horizontally exactly, in place. \"Copy\" duplicates the piece — with everything joined to it by bridges or strict seams — 5 m to the side and selects the copy. \"Switch Coaster…\" moves it under another same-friction coaster. \"Delete…\" removes the piece with everything joined to it, after a confirmation. Confirm (Enter or the X) keeps; Cancel (Esc) puts every piece and bridge of the coaster back to where the window opened (graph edits made meanwhile stay; a Mirror stays too — Ctrl+Z undoes it); the toolbar button closes an open window without reverting. The 3D gizmos drag all of this directly.");
        helpKey(box, "Real vs design orientation", "\"Edit Real and Design Orientation Independently\" is unchecked by default: pitch/roll edits move the real and design orientation together and the shape re-solves under gravity. Checked, two radios choose what the boxes and gizmo rings edit — \"Edit real orientation (locks track shape)\": the shape is kept rigidly and a design/real split opens; \"Edit design orientation\": the real entry stays put and the shape re-solves. The grey readout beside each row shows the other orientation — click it to swap the radios. \"Affect Subsequent Strict Seams\" (shown while the check is on) carries a real-orientation edit rigidly through the piece's next strict seam or bridge to every connected piece; off, the track after that junction keeps its own placement settings. The strict seam and bridge windows have the same row for the piece after the junction.");
        helpKey(box, "Design ↔ Real", "shown while the two orientations differ (in the Track Piece window, in the strict seam window for a piece after a seam, and in the bridge window for the piece after a bridge — there Real → Design is unavailable while the bridge optimizes orientation). \"Design → Real\" re-solves the shape at the real frame — if the piece has absolute-angle graphs, a dialog asks per graph whether to preserve it (the track snaps to the design shape) or convert it to the current shape. \"Real → Design\" rotates the piece (and its carried chain) back onto the authored orientation, shape preserved.");
        helpKey(box, "Split / attach", "splitting (at a seam or any node) cuts at the exact pose — the track never moves. ⊕ > Attach floating piece connects a piece on through a strict seam. \"Connect with previous/next section\" merges two like sections into one; where the two sides disagree, both values are kept with a very steep transition at the seam. The seam menu's \"Insert section…\" adds a section at any boundary (see Sections).");
        helpKey(box, "Strict seams", "the track after a strict seam moves and re-aims under upstream edits but never reshapes. Click the seam line for its menu. \"Strict Seam n…\" (n = the junction's number in the run — seams and bridge ends count together): yaw, pitch and roll rows — 🔒 holds the typed angle, 🔓 follows the seam (locking holds the current angle; nothing snaps), each with a ↺. Below them, while the held angles differ from the piece's design orientation (≠o on its badges), the readouts, the independent-orientation check and the Design ↔ Real buttons appear; with the check off (the default), angle edits re-solve the piece and keep that difference constant; with \"Edit design orientation\" picked, the Pitch and Roll boxes edit the design elevation and bank whatever the padlocks say, and the yaw row always edits the held heading. The ◇ on the line sets seam smoothing — a geometric blend over a window (hollow = off; drag it to enable; dragging the band's edge sizes it from that side, and pushing the edge across the seam switches smoothing off; the band tints red if the window itself opens a conflict — shrink it). \"Make smoothing real…\" converts the blend window into an editable section. Colored carets on the line point the way each lane's rate graph kinks; speeds stay independent across the seam, and B matches the real graphs across it.");
        helpKey(box, "Junction buttons", "\"Strict Seam n ▴\" appears in the toolbar beside Track Piece ▴ while a piece behind a strict seam is selected and opens that seam's window; \"Strict Seam n (bridge) ▴\" appears while a bridge, or the piece it leads to, is selected and opens the bridge's window (grayed when the bridge has no following piece). The button closes an open window without reverting.");
        helpKey(box, "Reverse", "\"Track Piece ▴\" > Reverse…: rebuilds the piece backward, in place, through the density preview — the entry speed starts padlocked at the recorded end speed, and a hint warns when friction can't climb the reversed run home. A piece ending in a bridge refuses.");

        helpHead(box, "Bridges");
        helpText(box, "⊕ > Bridge to piece N spans from this piece's end "
            + "to another piece's entry and re-solves live as either side "
            + "moves — the natural continuation of both seams. A cramped "
            + "bridge swings fast inside; lengthen it to calm it. Its "
            + "exit seam behaves like a strict seam.");
        helpKey(box, "Window", "\"Strict Seam n (bridge) ▴\" (or the badge menu's \"Bridge settings…\") opens the bridge window, titled \"Bridge settings (Strict Seam n)\". Modes: no optimization (you place the next piece), optimize position, or optimize position and orientation. The padlocks on the next piece's yaw, pitch and roll rows: 🔒 holds the axis at the typed value — its box, ↺ and 3D ring stay editable in every mode and the bridge bends to it; 🔓 leaves it free — under optimize position and orientation the bridge aligns it and the box grays, in the other modes it stays typeable. The 3D rig shows rings for the locked axes in those two modes. \"Optimize length\" picks the shortest smooth length — at zero it offers to connect the pieces directly. Below, the independent-orientation check, its radios, \"Affect Subsequent Strict Seams\" and Design ↔ Real act on the next piece (\"Edit design orientation\" and optimize position and orientation exclude each other). The window keeps editing its bridge while you click around; Cancel restores every piece and bridge to where the window opened, mode and length included.");
        helpKey(box, "Length", "the spinner, the vertical line at the end of the bridge's strip, or the orange tangent arrow of the 3D gizmo — each re-places live. Editing upstream of an optimized bridge re-places the next piece automatically.");
        helpKey(box, "Convert", "badge > Convert…: the whole bridge becomes editable lanes and the pieces connect (through a strict seam by default). Part-converting keeps the rest a live bridge and the track connected. If the target piece is deleted the bridge freezes (badge \"bridge !\").");

        helpHead(box, "3D viewport");
        helpKey(box, "Orbit", "middle-drag = orbit · scroll = zoom · Shift+middle-click the track = re-center on it · left-click the track = edit that piece and section (another coaster's track offers switching) · double-click the track = insert a node there (on a node marker: opens its editor instead).");
        helpKey(box, "1-6 views", "digit keys jump to orthographic views: 1 Top (always world Z up, X right), 2 Front, 3 Back, 4 Right, 5 Left, 6 Tangent to the selected node. Right-drag pans, scroll zooms toward the cursor. Middle-dragging out of the Top view starts orbiting around the ground point under the cursor; leaving for fly/orbit otherwise restores the camera pose from before the ortho view. Over the graph, 1/2/3 still switch lanes.");
        helpKey(box, "View label", "the top-left label names the pane's camera; click it to switch views. \"Add View\" opens another full pane beside it; \"Remove View\" closes it.");
        helpKey(box, "Fly", "right-click the view: WASD + mouse look (Shift fast, Ctrl slow, scroll bursts); right-click again exits keeping the view.");
        helpKey(box, "POV", "Space rides the track, hands-off at first. Right-click captures the ride: W/S ride at true speed (Shift fast, Ctrl slow), A reverses the view, Space flips to fly, scroll jumps along the track, right-click releases — the mouse stays confined to the view while captured. The stats line under the plot follows the ride with true values.");
        // diagram: the placement gizmo
        GraphicsContext gz = helpCanvas(box, 560, 108);
        gz.setStroke(Color.rgb(120, 220, 130));
        gz.setLineWidth(2);
        gz.strokeLine(120, 60, 190, 60);           // X arrow
        gz.strokeLine(190, 60, 182, 55);
        gz.strokeLine(190, 60, 182, 65);
        gz.setStroke(Color.rgb(120, 170, 255));
        gz.strokeLine(120, 60, 120, 16);           // Y arrow
        gz.strokeLine(120, 16, 115, 24);
        gz.strokeLine(120, 16, 125, 24);
        gz.setStroke(Color.rgb(255, 120, 110));
        gz.strokeLine(120, 60, 78, 92);            // Z arrow
        gz.strokeLine(78, 92, 88, 90);
        gz.strokeLine(78, 92, 82, 83);
        gz.setStroke(FvdDesigner.GEO_LANE_COLORS[1]);
        gz.strokeOval(85, 42, 70, 36);             // yaw ring
        gz.setFill(Color.WHITE);
        gz.fillOval(113, 53, 14, 14);              // the ball
        gz.setFill(Color.rgb(200, 205, 215));
        gz.setFont(Font.font(11));
        gz.fillText("white ball — click to reveal, drag to move", 215, 34);
        gz.fillText("arrows translate (Ctrl snaps the grid)", 215, 54);
        gz.fillText("rings rotate one axis (Ctrl snaps 1°)", 215, 74);
        gz.fillText("double-click any part = its settings", 215, 94);
        helpKey(box, "Gizmos", "every junction of the selected run wears a white ball — the run's entry, each strict seam, each bridge end. Click one to select that piece and reveal its gizmo; click off it to hide. Arrows and rings grab from a few pixels away, and the center ball wins where they cross it. Arrows move, rings rotate; locked or bridge-owned axes build no part; one undo step per drag. The top-right \"Gizmos: Local/Global\" button aims the axes at the piece's own frame or the world. Dragging the idle ball moves its owner in the view plane; double-click opens its window (a bridge end opens the bridge's). Pressing any ball, arrow or ring deselects the graph's nodes.");
        helpKey(box, "Node markers", "small spheres at the active lane's nodes (View > Node Markers) — click one to select that node in the graph, double-click to open its editor; the selected one is larger and paler; a node 0 under a junction ball has no marker (the ball is its anchor). Small light-gray disks mark the plain section boundaries inside a piece (not clickable; none at strict seams or bridge ends); speed controls wear draggable disks in their own color — see Strip editing. The bottom-left compass shows the world axes under the current view.");
        helpKey(box, "Combs", "teeth along the track — View-menu modes: G-force, speed, curvature (with local/global/combined dots), and time. Hover any tooth for its value; curvature teeth also read their radius. Size with \"Comb size (%)\".");
        helpKey(box, "Heartline", "View > Show Heartline draws a thin strand on the ride path (hidden during POV — the camera sits on it).");
        helpKey(box, "Ground", "the grid is see-through: track below ground shows behind the floor — under the sheet, lines and axes — while track above ground covers them; seen from below ground it is the other way round; a stalled (ghost) track shows through both. The Top view keeps its own flat grid over everything.");

        helpHead(box, "Menus & settings");
        helpKey(box, "File", "New / Save / Save As with an unsaved-changes guard (closing the app runs it too) · Export (Ctrl+E): a coaster or one track piece (a whole run), as a CSV spline or an .nl2elem element for NoLimits 2; heartline or rail-center; point spacing. The dialog follows your edits — what you export is the track as it is when you press Export.");
        helpKey(box, "Undo everywhere", "Ctrl+Z / Ctrl+Shift+Z are the document's in every window that can change the track, open or closed — the boxes follow each step. Only cosmetic menus (View, Snapping, colors, Export) keep a local undo. Every open tool window also follows edits made anywhere else live; a box you are typing in is never overwritten (an undo still refreshes it). While a convert preview is open, other edits wait — the stats bar says so.");
        helpKey(box, "Edit", "Shortcuts — click a binding, press the new combo (↺ resets). Snapping — the Ctrl-snap steps and whether each axis snaps relative to the previous node or an absolute grid. Resolution Scale — a whole-UI size factor; \"Apply and Restart\" reopens the app at the new scale.");
        helpKey(box, "View", "heartline, node markers, selection visuals, the comb modes and size, G-force colors, one FOV slider for all cameras, mouse sensitivity.");
        helpText(box, "Every tool window is modeless and stays visible and "
            + "live while you fly or ride captured; anything grayed out "
            + "explains itself on hover. The placement windows (Track "
            + "Piece, strict seam, bridge) and the node editor open just "
            + "above the graph: Confirm, Enter or the X keep what you "
            + "changed, Cancel or Esc revert it. Number boxes everywhere: the "
            + "wheel steps by the increment, Shift steps at a tenth, Ctrl "
            + "snaps to the next round value, and holding an arrow "
            + "repeats.");
        helpKey(box, "Coaster", "the coaster selector (other coasters stay in view in their solid color) · Coaster Options: rename, friction, statistics · Rail Settings: solid color or the felt-G gradient, heartline offset, gauge, tie spacing · New / Delete Coaster.");
        helpKey(box, "Colors", "every color control is a swatch opening the picker — palette, recents, a hue/saturation plane, typed RGB/hex, live preview.");

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 660);

        Stage dlg = new Stage();
        dlg.initOwner(app.stage);   // non-modal: usable as a reference while editing
        // the display name in the title (v19.20, D249 — the main
        // window's " — APP_NAME" form; the menu-bar label stays "Help")
        dlg.setTitle("Help — " + FvdDesigner.APP_NAME);
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

    /** A drawn diagram row (v19.11, D240): dark rounded canvas, the
     *  caller paints on the returned context. */
    static GraphicsContext helpCanvas(VBox box, double w, double h) {
        Canvas c = new Canvas(w, h);
        GraphicsContext g = c.getGraphicsContext2D();
        g.setFill(Color.rgb(28, 31, 40));
        g.fillRoundRect(0, 0, w, h, 8, 8);
        box.getChildren().add(c);
        VBox.setMargin(c, new Insets(2, 0, 8, 0));
        return g;
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

    /** RESOLUTION SCALE (v19.12, D241 — Preferences > Resolution
     *  Scale…): a Blender-style whole-UI scale factor, RELATIVE to
     *  the OS display scale (1.0 = the OS default). Applied by
     *  coaster.Main via glass.win.uiScale BEFORE the toolkit
     *  initializes — restart-only by hard platform fact (the value
     *  is baked into glass in WinApplication's clinit), so the
     *  dialog says so. Snapped to 0.05 steps; ↺ grays at 1.0. */
    void showResolutionScaleDialog() {
        Stage dlg = new Stage();
        dlg.initOwner(app.stage);
        dlg.setTitle("Resolution Scale");

        double cur = FvdDesigner.clamp(app.settings.uiScale(), 0.75, 1.75);
        Slider s = optionSlider(0.75, 1.75, cur, 0.25);
        s.setBlockIncrement(0.05);
        Label val = new Label(String.format(Locale.US, "%.2f×", cur));
        val.setMinWidth(46);
        // snap the knob to 0.05 steps (crisp fractional scales probed
        // safe; finer values just add restart churn)
        s.valueProperty().addListener((obs, o, v) -> {
            double q = Math.round(v.doubleValue() / 0.05) * 0.05;
            if (Math.abs(q - v.doubleValue()) > 1e-9) s.setValue(q);
        });
        wireOptionSlider(s, val, "%.2f×",
            v -> { }, app.settings::setUiScale);

        // a LIVE apply is impossible (the scale is baked in at toolkit
        // boot — D241 platform fact), so the closest Blender-like
        // gesture is an in-place restart (v19.13, D242, user ask)
        Label note = new Label(
            "Applies at the next launch — the platform reads the scale "
            + "once at startup.");
        note.setStyle("-fx-text-fill: rgb(120,126,140); -fx-font-size: 11px;");
        Button restart = new Button("Apply and Restart");
        restart.setFocusTraversable(false);
        restart.setOnAction(ev -> app.restartApp());
        Tooltip.install(restart, new Tooltip(
            "Reopens the app at the new scale (offers to save first)."));

        HBox row = new HBox(8, new Label("Scale"), s, val,
            optionReset(() -> s.setValue(1.0), s.valueProperty(),
                () -> Math.abs(s.getValue() - 1.0) < 1e-9));
        row.setAlignment(Pos.CENTER_LEFT);
        HBox noteRow = new HBox(10, note, restart);
        noteRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, row, noteRow);
        box.setPadding(new Insets(14));

        Scene sc = new Scene(box);
        app.installMenuUndoKeys(sc);
        dlg.setScene(sc);
        app.beginMenuSession();
        dlg.setOnHidden(e -> app.endMenuSession());
        dlg.show();
        app.installViewKeys(dlg.getScene());
    }

}
