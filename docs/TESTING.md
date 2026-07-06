# TESTING.md — What's verified, how, and what to keep testing

## The trust boundary (most important thing in this file)

- **Verified headlessly:** everything in `Vec3`, `FVDIntegrator`,
  `BezierBaker`, the save-format read/write logic, and the default layout's
  end-state invariants.
- **Never executed by the AI assistant:** all JavaFX code in `FvdDesigner`
  (the original dev environment had no display, no JavaFX, no javac). The
  human user has run and confirmed earlier UI versions on Windows; the v5
  additions (POV camera, rails, spinners, save/load dialogs) compile-checked
  only by careful reading. Treat UI behavior claims accordingly.

## Existing validations (convert these to JUnit in this repo)

1. **Circle accuracy (integrator ground truth).** Flat unbanked turn,
   v0=20 m/s, Gl=1.5 ⇒ analytic radius v²/(g·Gl)=27.192 m, center =
   pos0 + R0·r. Assert: worst |distance-from-center − r| over the whole
   path < 1e-6 m and the path stays in the y=0 plane. RK4 (v8.0)
   measures < 1e-9 m; the old Euler stepper measured 10.0 cm. Do NOT use
   "closure error at the last sample" as the metric — the last sample
   lands up to dt before exact closure time, which alone contributes
   ~6 cm and made Euler look 4× better than it was (see D44).

2. **Default layout invariants (bake + integrate pipeline).** Build the
   shipped three-lane default (node tables live in `FvdDesigner`; consider
   extracting to a shared `DefaultLayout` class so tests and app share one
   source of truth). Assert: baked keyframes strictly increasing in t, all
   finite; end |pitch| < 3°; end U.y > 0.99. RK4 baseline (v8.0,
   start (0,21,0), v0 26): 271 keyframes, 1347 samples, 13.46 s,
   406.878 m, end pitch 0.2284°, U.y 0.999971, top speed 32.10 m/s,
   rail y 2.95–24.78. (Euler-era historical: 406.809 m, pitch 0.2300°.)

3. **Baker fuzz.** 200 seeded-random layouts (4–13 nodes, random values in
   ~2× the UI ranges), then scramble ALL handles with out-of-range values
   (dt exceeding segments, wrong signs, huge dv). Assert: baked output
   strictly monotonic in t, all values finite, zero exceptions. (Historical:
   0 violations.)

4. **Save round-trip.** DONE PROPERLY as of v9.0: save/load lives in
   `DesignIO` (D50), so tests exercise the real production code — the
   old duplicated-logic weakness is gone. The round-trip is asserted
   CANONICALLY in #11: save → load → save must be byte-identical, which
   subsumes field-by-field comparison at format precision.

5. **Banked-turn physics identity (recommended addition).** Bisect Gv for
   "turn ends level" at a 40° bank through the full pipeline and assert the
   result is within 2% of 1/cos(40°) ≈ 1.305. This single test cross-checks
   the ω equations, roll accumulation, and frame conventions.

6. **Handle squish invariance (added with fractional handles, v6).** Two-node
   segment with non-default handles (fracs 0.42/0.27, dv 0.35/−0.15), end
   node at t=2 vs t=1: assert `eval_wide(2u) ≈ eval_narrow(u)` for u in
   (0,1). Historical: max error 0.002 (bake-polyline sampling noise); assert
   < 0.005. Also re-assert test 2's invariants — after the v6 refactor they
   reproduced exactly (271 keyframes, 13.46 s, 406.8 m, pitch 0.23°,
   U.y 1.000).

7. **Split identity (v6.5).** `BezierBaker.splitSegment` on assorted
   segments (flat, angled, near-folded) at assorted split times: evaluating
   the 2-node original vs the 3-node split lane must agree everywhere.
   Historical: max diff 0.005 (bake-polyline sampling noise); assert < 0.01.

8. **NL2 CSV export (v7.8).** `Nl2CsvExporter` validated headlessly under
   `Locale.GERMANY` (comma-decimal trap — output must stay `Locale.US`):
   (a) straight 5 s line ⇒ 131 points over 130 m, spacing uniform to 1e-6,
   front=+X, up=+Y, left=−Z (the Z-mirror); (b) every row orthonormal and
   `up = front × left` (NL2's right-handed identity) to 5e-6 — the
   tolerance is %.6f text round-trip noise, do NOT tighten it; (c) +Gl
   integrates as a rider-right turn AND exports clockwise-from-above
   (`f_k × f_k+1` has y<0), +roll tips exported `up` toward `left` — the
   chirality pair that catches a wrong/missing mirror; (d) a violent
   hills+roll layout (67 m/s, 198 m drop): heartline export length matches
   the integrator's `s`, spine export is resampled by the OFFSET curve's
   own arc length (337.4 vs 343.2 m — they must differ), and every spine
   point sits on the offset POLYLINE within 1e-5 (measure distance to
   SEGMENTS, not to sample points — dense samples are ~0.7 m apart at that
   speed and nearest-point distance ~0.33 m is expected, not a bug);
   (e) count = round(total/segLen)+1 at 0.1 m and 10 m requests. All
   passed 2026-07-05 (scratch main; convert to JUnit with the rest);
   re-passed unchanged on the RK4 integrator (path lengths shifted
   ~1 m on the violent test layout, as expected).

9. **Resulting body rates (v8.0).** `FVDIntegrator.bodyRates`: straight
   line ⇒ all rates 0 (measured 0.0); flat circle (v0=20, Gl=1.5) ⇒ yaw
   = deg(g·Gl/v) = 42.141 °/s within 4e-4, pitch 0; constant 50 °/s roll
   ⇒ extracted roll within 1e-3; default layout ⇒ extracted roll tracks
   the baked roll lane within 0.09 °/s (skip ±5 edge samples — central
   differences degrade to one-sided there).

10. ~~Smoothing tool~~ removed in v8.1 (D47) — validation retired with it.

11. **Phase-1 gates (v9.0, D50 — scratch Phase1Gate; these BLOCK any
    further Phase-1/2 work if they break).**
    (a) BIT-FOR-BIT: TrackCompiler.compile on the default document (one
    piece at (0,21,0) yaw 0, one force section, vDesign = v0 = 26) must
    equal the old direct `integrateTrack(bake(lanes), opts)` on RAW
    DOUBLE BITS for every field of every sample (t, s, pos, T, U, R, v,
    Gv, Gl — all 1347). Any new arithmetic on that path (an extra
    normalize, a quaternion round-trip, reordered adds) fails this.
    (b) Pass D pass-through: `trueV[i]` bitwise equals `Sample.v` while
    single-section (same formula, same evaluation order — deliberate).
    (c) FVDD3 canonical round-trip: save → load → save byte-identical,
    on a document with yaw, broken node, straight-lock, speed region and
    non-default cosmetics.
    (d) FVDD2 fixture loads as 1 piece/1 section (start → placement,
    v0 → v0 + vDesign, 7- and 8-field n lines, cosmetics); missing
    start/v0 default to (0,21,0)/26. FVDD1 fixture: absolute-second
    handles convert to fractions (0.5 s over a 2 s segment → 0.25).
    (e) Piece independence (§0 connect invariance): in a two-piece
    document, editing piece A's lanes leaves piece B's WORLD geometry
    bitwise unchanged (t/s rebase aside), and the edit demonstrably
    changed A. (f) Changing global v0 leaves ALL geometry bitwise
    unchanged — shape depends on vDesign only. (g) Quat exactness:
    IDENTITY.rotate returns its argument bitwise; aboutY(0) == IDENTITY;
    fromFrame→T()/U() round-trips < 1e-12. All passed 2026-07-05.

## The historical headless harness (context, not a prescription)

The dev sandbox had only a JRE, so multi-file compilation was impossible.
Tests ran via Java's single-file source launcher against a concatenation:

```
{ cat TestMain.java.part
  sed 's/^public record/record/; /^package/d; /^import/d' Vec3.java
  sed 's/^public class/class/;  /^package/d; /^import/d' FVDIntegrator.java
  sed 's/^public class/class/;  /^package/d; /^import/d' BezierBaker.java
} > Combined.java   # prepend shared imports, test class must be first & public
java Combined.java
```

Quirks that shaped it: exactly one public top-level class per file; the
class with `main` must appear first; shared `java.util` imports hoisted and
stripped from sources. In this repo none of that is needed — use JUnit 5 via
Maven Surefire. The pure-core/JavaFX split is what makes that possible;
preserve it.

## Manual test script for UI changes (until UI tests exist)

After any `FvdDesigner` change, run `mvn javafx:run` and walk:
1. Window fits screen incl. title bar (test target: 1920×1080). Floor is a
   transparent grid (1 m lines, heavier every 10 m) at ABSOLUTE y=0 — the
   default layout floats ~2 m above it (start y 21) and a track edited to
   dive below 0 goes under the grid; that's fine. The grid fades
   out with distance (minors gone past 100 m, everything ends at 150 m):
   zoomed way out there must be NO shimmering/moiré. Heart/Gauge labels in
   the Coaster menu are black (readable on the light popup).
2. Keys 1/2/3 switch lanes; inactive curves faint. Clicking a dimmed curve
   or node switches to that lane (and selects the node); clicks near the
   ACTIVE lane's nodes/handles never switch away.
3. Click node → ring + translucent handles. Drag knob: moves FREELY (angle
   + length). On a smooth node the partner mirrors proportionally on both
   axes: equal handles stay exact mirror images; unequal ones scale in
   proportion (drag one 30%→15% and a 20% partner lands at 10%; dv 1.0→1.5
   takes a −0.8 partner to −1.2) — always collinear. Shift-drag: knob rides
   its current line, length only, partner does NOT move. Ctrl-drag: length
   snaps between ease presets (readout shows e.g. "out 0.33 cubic" —
   fractions of the segment, no % anywhere).
   Drag node: free; Ctrl snaps the TRANSITION to 0.1 s steps (readout Δt
   lands on 0.30s/0.80s even when the previous node sits off-grid) and the
   value to the absolute grid (0.1 G; whole °/s in the roll lane); readout
   shows the transition ("Δt=0.80s  Δ+1.50 (2.50)") — Δs from the previous
   node, true value in parens. Moving a node closer to its neighbor
   squishes the segment shape, never distorts it.
4. B on an interior node: knobs circle↔square; re-smooth swings the RIGHT
   handle to match the LEFT.
5. Hover the active curve away from nodes: translucent white dot rides the
   line; click inserts a node there with NO visible change to the curve
   (tangent handles on slopes, level sine handles on flat runs); the new
   node comes up selected. Must also work on NEAR-VERTICAL sections (the
   hit test is nearest-point in 2D, not vertical distance). Holding Shift
   while hovering REPLACES the value readout with the slope (no prefix) —
   it swaps even if the mouse is stationary when Shift goes down. Dragging
   a node past the plot edges keeps its Δt/value readout visible, clamped
   inside the plot.
5b. Edit > Options: "Show heartline (neon)" draws a hairline glowing
   yellow strand along the heart path (between/above the rails per the
   Heart setting), unaffected by lighting; toggling off removes it; state
   survives restart. The two FOV sliders live in Options too (no View
   menu). Clicking "Help" in the menu bar opens the Help window
   immediately (no dropdown). Shift+click a track piece in orbit mode:
   the camera re-centers its orbit on that spot (within ~25 px of the
   rails); orientation/zoom unchanged. Shift+click on EMPTY SKY moves
   nothing — the drag that follows orbits exactly like a plain drag (no
   pivot teleports to off-screen/behind-camera track). Shift-hover slope
   readout changes CONTINUOUSLY as the mouse slides along a curved
   segment (no stair-stepping). Double-click ON a node opens the editor dialog:
   typed transition time/Δ and handle fractions apply on Apply/Enter,
   Cancel/Esc discards, garbage input keeps old values, ripple toggles are
   honored. Typing a HANDLE fraction slides the knob along its current
   angle (curve steepens/relaxes but the tangent direction at the node
   is unchanged — like an S-drag); on node 0 the dialog shows "Start
   speed (m/s)" instead of a transition time and applying it updates the
   Coaster-menu spinner and the stats top speed. Right-click or Delete
   key removes the selected node: neighbors
   stay put (positions AND handle settings) with the curve bridging the
   gap; deleting the last node shortens the ride; node 0 refuses.
6. A appends 1 s; ride time in stats bar grows by 1.0; the appended
   transition's handles sit at the SINE fraction (readout ~36%, not 33%).
   ONLY the active lane gets the new node — switch lanes: the other two
   still end at their old last node with a dotted hold to the new ride
   end (clicking the dotted line still inserts).
7. Space: POV cam starts at t=0 HANDS-OFF — W/S do nothing to the camera
   and the S slide key still works on the graph (drag a node with S held
   while the POV view shows). Right-click the view: mouse vanishes, W
   rides forward at believable speed, S reverses, Shift/Ctrl speed/slow,
   wraps at both ends; moving the mouse does NOT look around. Right-click
   releases (cursor back, W/S return to the graph). While captured, Space
   flips to fly (WASD, mouse look) and Space again back to POV, mouse
   staying hidden throughout. Uncaptured Space exits to orbit. Scroll
   bursts along the track captured or not.
8. Coaster menu > Heart spinner to −1.0: rails jump above the heartline
   (inverted); POV now hangs below track. Gauge visibly widens/narrows rail
   spacing. Menu stays open while spinning (CustomMenuItem hideOnClick off).
9. Ctrl+S (or File > Save) → .fvd file (FVDD3 as of v9.0 — old app
   versions can NOT read new saves; new versions read all three
   formats). As of v9.1 Save writes STRAIGHT to the open file once one
   exists (no chooser; title loses its *) — use Save As… to re-target;
   a never-saved "Untitled" still opens the chooser. Edit something;
   Ctrl+O the file back:
   design, heart, gauge, rail color, start point/speed all restored;
   Ctrl+Z after load returns the pre-load design. Loading a pre-v6
   FVDD1 file must reproduce its curve shapes (handles auto-convert
   from absolute seconds to fractions); loading a v7/v8 FVDD2 file must
   reproduce it exactly (start line → piece placement).
10. Undo spam: Ctrl+Z ×20 walks history without exceptions; one drag = one
    undo step; Ctrl+Shift+Z ×20 walks it all forward again (see step 36).
11. Coaster menu > "Rail colors…" opens the color dialog; picking a solid
    color switches to solid mode and repaints the rails. "Custom Color…"
    inside the picker must NOT crash (regression: it did when the picker was
    embedded in the menu). Switch back to gradient: G-force coloring
    returns. Change the four gradient stops — both a COLOR (e.g. 1 g to
    white) and a G VALUE (e.g. move stop 3 from 3 g to 2 g): rails recolor
    live; colors AND stop values survive a save/load round-trip; Ctrl+Z
    inside the dialog steps both kinds of edits back. With "Show
    heartline" on, entering POV hides the neon strand (no flicker across
    the lens) and exiting POV — or flipping to fly with Space — brings it
    back.
12. Edit > Shortcuts: rebind POV to another key (click button, press key) —
    new key rides, old key does nothing, ↺ appears; restart the app and the
    binding survives (~/.fvd-designer.properties); ↺ restores Space and
    disappears. Escape during capture cancels. Stats-bar hint tracks changes.
13. Edit > Options: FOV slider changes POV lens live (ride while dragging);
    value survives restart.
14. Fly mode: right-click the 3D view — cursor vanishes, view starts at the
    exact orbit pose; mouse looks around smoothly (no cursor escaping the
    window), WASD flies (A strafes, does NOT append track), Shift is fast,
    Ctrl slow, scroll lunges forward. Right-click exits: cursor back and the
    VIEW STAYS WHERE YOU FLEW (no snap-back) — even when the mouse is
    MOVING during the right-click (regression: stale orbit anchor jumped
    the view); orbit drags now circle the
    point you were looking at. Space during fly switches to captured POV.
    Roll lane Ctrl-snap defaults to 10 °/s steps (adjustable in Options).
16. Coaster menu > "Tie every (m)": ties appear between the rails, evenly
    spaced along the track (not bunched on slow sections), ends flush —
    nothing pokes past the rails; lowering the value visibly densifies
    them; value survives save/load round-trip.
17. Entering/exiting fly mode: NO field-of-view jump in either direction
    (fly & orbit share one FOV setting). The "Fly & orbit FOV" slider
    widens both live; "POV camera FOV" affects only the ride cam. The
    snap-step spinners' arrows walk the 1-2-5 series both ways
    (0.05 → 0.1 → 0.2 → 0.5 …); typed values are kept as-is. Every Options
    row has a ↺ that restores its default. "Mouse sensitivity" affects
    orbit dragging AND fly look. Scrolling in fly/POV/orbit/graph works
    IDENTICALLY with Shift held (Windows turns Shift+wheel into horizontal
    scroll — must not go dead). Hovering the curve shows the insert dot
    plus the value at that point.
18. Coaster menu > Start x/y/z: track translates; speeds/forces unchanged
    (energy is relative to start height); grid stays at y=0; values
    survive save/load. Start speed spinner: raising it visibly speeds the
    POV ride and raises the stats top speed; the same value appears in
    node 0's double-click dialog and edits made there show in the menu;
    survives save/load (v0 line); Ctrl+Z inside the open menu steps
    spinner changes back, AND after closing the menu plain Ctrl+Z steps
    them back too (v9.1: heart/gauge/start/speed are document undo).
19. Graph: scroll zooms the time axis around the cursor; ticks and the
    "Time (s)" / rotated lane-name axis labels stay correct; right-drag
    pans when zoomed; node editing works identically while zoomed; zooming
    fully out resumes auto-fit (appending track re-fits again). Grid lines
    sit on nice values (…, 0.5 s, 1 s, 2 s; 0.5 G; 10 °/s…) and re-density
    as you zoom; tick labels ride the same lines.
20. Zoom into a short steep transition: the hover dot appears close to the
    surrounding nodes (down to 0.02 s away) and clicking inserts — the dot
    never shows where a click would refuse.
21. Select a node, press Z: translucent vertical guide appears, drags only
    change value; Z again unlocks. X: horizontal guide, drags only change
    time. Selecting another node clears the lock. H levels the selected
    node's handles (both dv 0) — with a multi-selection EVERY selected
    node's handles go flat (values untouched). Shift+H with 2+ selected:
    every selected node jumps to the PRIMARY node's value with flat
    handles — the run becomes one horizontal line; segments to unselected
    neighbors still transition. Primary = last clicked, or the EARLIEST
    node of a box selection (check its ring). All keys rebindable in
    Shortcuts.
22. POV: scrolling in the 3D view lunges along the track (both directions,
    wraps); a translucent vertical line on the graph tracks the camera and
    moves with W/S/scroll; Edit > Options unchecks it away live.
23. Drag the divider between 3D view and graph: both resize live; the ▼
    button (bottom-right of the 3D view) collapses the whole bottom panel
    (stats + toolbar + graph) to full-screen the viewport, turns to ▲, and
    restores to the previous size when clicked again. With the window
    NON-maximized and resized smaller, the button and divider must remain
    visible and functional (regression: SubScene min-size pushed them
    off-screen).
24. Coaster menu open: change Heart a few times, Ctrl+Z steps them back,
    Ctrl+Shift+Z forward — all while the menu stays open. Close the menu:
    plain Ctrl+Z now steps the heart changes back TOO (v9.1 — path values
    are document undo; each spinner click = one step), then continues
    into curve edits. Tie spacing does NOT document-undo (cosmetic).
    Same menu-local behavior inside Edit > Options (FOV drag = one undo
    step; app settings never document-undo) and the rail-color dialog
    (colors dirty the file but don't document-undo).
25. Value axis: default ranges (Gv −1..4, Gl −1..1, roll ±150); drag a Gv
    node to 5 g — the axis expands to a rounded bound; delete it — the
    axis returns to default. Axis stays still while dragging.
26. Hold a node PAST any plot edge without moving the mouse: that axis
    grows continuously, faster the further past the edge (right edge =
    ride extends; top/bottom = value scale expands); pull back inside and
    growth stops instantly; view re-fits on release. No runaway.
    Move a lane's last node left of the ride end: dotted flat line appears
    to the ride end; hovering it shows the dot and clicking appends a node
    on it. After any click-insert, immediately click-drag the new node:
    it must MOVE (not grab one of its handles).
26b. Mid-drag combo guard: start dragging a node, hold Ctrl (snap engages),
    then press and hold S — the drag starts SLIDING (axis constraint) and
    NO save dialog appears; release the mouse with Ctrl+S still held —
    still no save dialog (key auto-repeat must not fire it); release
    everything, plain Ctrl+S saves normally. Same drill with Z instead of
    S: mid-drag Ctrl+Z does NOT undo (the axis lock simply doesn't engage
    while Ctrl is down; nothing else fires either).
27. Shift+click two more nodes (faint rings): dragging any moves all three
    (clamped against unselected neighbors); HOLDING S mid-drag constrains
    IMMEDIATELY to the dominant axis and SWITCHES axes if you pull further
    the other way (total dx vs dy); the node returns to its original
    row/column; Shift while dragging does nothing axis-wise. Guide colors:
    vertical guide = lane color (value changing), horizontal = yellow
    (time changing); "Time (s)" label is yellow. Z/X override S's choice
    (press again to return it). With exactly two ADJACENT nodes selected,
    F straightens the segment between them into a line (smooth neighbors
    stay C1). Ctrl-drag a handle far out: snaps at 0.1 steps up
    to 1.5× the segment (curve may fold; must stay finite).
    Arrows nudge the group; Delete removes them all. Lane buttons show
    their curve colors.
28. Ground: dense 1 m lines near the track fade over bands; past ~120 m
    only 10 m lines, past ~240 m a smooth solid translucent sheet; no
    shimmer zoomed out, no z-fighting stripes between lines and sheet.
29. Help > Controls & Features opens a non-modal scrollable reference
    (usable while editing) with bulleted sections and the node/handle
    diagram; spot-check that it mentions the newest features — it is
    REQUIRED to stay current with every change.
30. Drag one handle of a smooth node until the partner leaves the view:
    NO fake kink at the node — the partner draws as a dashed stub with a
    hollow marker at the plot edge; clicking the marker expands the value
    scale to show the whole handle (editable); clicking empty plot
    restores the scale. Pulling a knob past the plot edge grows the view
    like nodes do.
31. Select a node whose handles overlap it: pressing on the node body
    always drags the NODE, never a knob. Append with it selected inserts
    a 1 s flat hold (later track identical, 1 s later, in all lanes);
    without a selection Append still extends the ride end.
32. Options: two FOV sliders (track view vs fly — changing one leaves the
    other camera alone), fly sensitivity slider takes effect immediately,
    snap-step spinners change Ctrl-snap/arrow-nudge granularity live.
    Box select: drag a rectangle on empty plot — nodes inside get rings
    (Shift adds to the existing selection); a plain click still deselects.
    Zoom to a 0.2 s window: grid/ticks show 0.02/0.05 s steps with two
    decimals, no repeated labels. Fly/orbit/POV: the floor grid's dense
    region follows the camera (fly far from the track and look down).
33. File > Export NL2 CSV (or Ctrl+E): dialog offers rail center /
    heartline radios + segment length spinner (↺ resets 1.0 m); Export…
    prompts for a .csv and the stats bar reports points/spacing/length.
    Heartline is the default (fresh settings file). Both settings survive
    an app restart (they're per-user, not in the
    .fvd). Ctrl+Z inside the dialog undoes setting changes. In NoLimits 2:
    import the file as an external/CSV track — the layout must match the
    designer INCLUDING turn direction and banking side (if turns come out
    mirrored, the Z-negation in `Nl2CsvExporter` is wrong — see D40) and
    segment spacing should look even. Rail-center export should put NL2's
    rails where the designer draws them; heartline export sits one Heart
    setting above.
34. Straight lock: select two adjacent nodes, Shift+F — the segment snaps
    straight and draws as TWIN thin lines (single 2 px stroke gone). Drag
    either endpoint anywhere: the segment stays a perfect line. Drag one
    of the segment's facing knobs: it slides ALONG the line (length
    changes, line doesn't bend); an S-drag feels identical. Double-click
    an endpoint and type a handle value — still straight after Apply.
    Click-insert ON the locked segment: both halves stay locked (four
    thin lines); delete the inserted node: merged segment still locked.
    Select just ONE endpoint, Shift+F: unlocks (normal single stroke
    returns, handles stay where the lock left them). Lock survives a
    save/load round-trip (n-line 8th field) and Ctrl+Z restores the
    pre-lock handle shape. In the node dialog the handle rows read just
    "In handle"/"Out handle" (no "(fraction)").
35. Toolbar "Resulting" dropdown: check "Pitch rate" — a thin dashed
    green curve appears (°/s on the roll lane's scale); check "Yaw rate"
    — dashed blue joins it. On the default layout the pitch-rate curve
    swings through the hills while yaw stays ~0 (it's a no-lateral
    layout). There is NO roll entry (the resulting roll rate is the
    roll lane itself). The roll lane's value axis expands if an enabled
    curve pokes past it; unchecking both restores the default axis.
    Picks survive an app restart. Editing nodes updates the curves live.
36. Redo: drag a node, Ctrl+Z — it snaps back; Ctrl+Shift+Z — the drag
    returns. Undo ×5 then redo ×5 walks forward through the same states.
    After an undo, make ANY new edit: Ctrl+Shift+Z now does nothing (the
    redo branch cleared). Works for deletes, inserts, appends, dialog
    edits and loads. Inside menus/dialogs Ctrl+Shift+Z still redoes the
    MENU-local edits only (unchanged).
37. Ortho views & orbit gestures: press T — the view snaps straight down
    with NO perspective (rails don't converge) and the grid runs SQUARE
    to the screen (yaw snapped to the nearest 90°); the picture is the
    same SIZE as before the snap. Select a node mid-turn (somewhere the
    track no longer heads east), Shift+T — the view centers on that
    node AND the track runs HORIZONTALLY across the screen there (lens
    tangent to the local heading, v9.3); approach from the other side
    of the track first and the view opens from THAT side (no 180°
    flip). The node's marker is white and larger than the rest. With
    nothing selected the side view is the world X–Y elevation and keeps
    the current center. While IN the side view, select a different node
    and press Shift+T again: the view re-centers and re-tangents WITHOUT
    exiting; the side view has no same-key exit — orbit away or T, T. RIGHT-drag in either view pans (world follows the
    mouse, view stays snapped, grid dense region follows); right-click
    does NOT enter fly while a snapped view is active. Scroll zooms in
    both (smooth, no sudden clamp). The same key returns to the exact
    perspective view you left; orbit-DRAGGING (primary or middle)
    instead keeps the direction you're facing and restores perspective
    without a size jump. MIDDLE-drag rotates the orbit exactly like
    primary-drag. Hold C and click the track: the pivot re-centers
    there (same as Shift+click); C+click on sky does nothing.
38. Track markers & insert: small spheres sit between the rails at each
    ACTIVE lane node (lane color; selected nodes white — click nodes in
    the graph and watch the spheres follow; switch lanes and they move
    to that lane's nodes). Options unchecks them away live. Ctrl+click
    ON the track: a node appears in the active lane at that point of
    the ride (graph shows it selected; one Ctrl+Z removes it);
    Ctrl+click on sky does nothing. Markers must NOT flicker or lag
    while dragging graph nodes (signature-guarded rebuild).
39. File lifecycle (v9.1): fresh launch shows "Untitled — FVD Coaster
    Designer"; drag any node → "Untitled*". File > New with changes:
    Save/Don't Save/Cancel dialog — Cancel keeps everything; Don't Save
    gives a flat 1 s track (Gv 1 g, straight line, all defaults, title
    "Untitled", empty undo — Ctrl+Z does nothing); Save on a never-saved
    doc opens the chooser first (cancelling THAT aborts the New). Save
    to a file: title shows "name.fvd"; edit → "name.fvd*"; Ctrl+S
    silently rewrites the same file and the * clears; Save As… asks for
    a new name. Load with unsaved changes shows the same gate. Undo of
    heart/gauge/start/speed changes also works after New (fresh
    history). Ctrl+N is rebindable ("New design").
15. Ripple checks (filled square = on, no checkmark glyph): with only
    "time" on, dragging a middle Gv node right 1 s moves every later node
    of that lane right 1 s (later transition times unchanged, shapes
    preserved) but vertical drags move only the node; with only "value" on,
    the reverse. Other lanes unaffected. Both off: old clamped single-node
    behavior. Ctrl+Z undoes a whole ripple drag as one step.

## Debugging heuristics learned the hard way

- Wild 3D output after math edits → check the frame-rotation sign (`−ωU·R`)
  and the Y-flip discipline before anything else.
- "Track dives underground / loops unexpectedly" is usually a LAYOUT problem
  (curvature ∝ 1/v² runaway), not an integrator bug — reproduce headlessly
  and print pitch over time before touching physics code.
- Parameter scans that respond non-monotonically mean the track is passing
  through vertical; shrink the phase durations and re-bisect.
- If numbers parse/format weirdly for a European user: a missing `Locale.US`.
