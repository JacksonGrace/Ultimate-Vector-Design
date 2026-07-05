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

1. **Circle closure (integrator ground truth).** Flat unbanked turn,
   v0=20 m/s, Gl=1.5 ⇒ analytic radius v²/(g·Gl)=27.192 m. Assert: after one
   circumference the position error < 5 cm and end·start heading dot > 0.999.
   (Historical result: 1.5 cm over 170.9 m.) Note: originally written for the
   distance-domain integrator; re-express in time domain (duration =
   circumference / v).

2. **Default layout invariants (bake + integrate pipeline).** Build the
   shipped three-lane default (node tables live in `FvdDesigner`; consider
   extracting to a shared `DefaultLayout` class so tests and app share one
   source of truth). Assert: baked keyframes strictly increasing in t, all
   finite; end |pitch| < 3°; end U.y > 0.99. (Historical: pitch 0.2°,
   U.y 1.000, 13.46 s, ~407 m.)

3. **Baker fuzz.** 200 seeded-random layouts (4–13 nodes, random values in
   ~2× the UI ranges), then scramble ALL handles with out-of-range values
   (dt exceeding segments, wrong signs, huge dv). Assert: baked output
   strictly monotonic in t, all values finite, zero exceptions. (Historical:
   0 violations.)

4. **Save round-trip.** Serialize lanes (include ≥1 broken-smooth node and
   non-default handles) + heart/gauge through the same writer/parser logic as
   `FvdDesigner`, re-parse, compare field-by-field at 1e-3 tolerance.
   (Historical: 0 mismatches.) Recommend extracting save/load into a
   `DesignIO` class so the test exercises the REAL production code instead of
   a copy — the historical test used duplicated logic, which is a known
   weakness.

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
   transparent grid (1 m lines, heavier every 10 m) that does NOT move while
   dragging nodes — the track may dip below it; that's fine. The grid fades
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
   snaps between ease presets (readout shows e.g. "out 33% cubic").
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
   hit test is nearest-point in 2D, not vertical distance). Double-click ON a node opens the editor dialog:
   typed transition time/Δ and handle fractions apply on Apply/Enter,
   Cancel/Esc discards, garbage input keeps old values, ripple toggles are
   honored. Right-click or Delete key removes the selected node: neighbors
   stay put (positions AND handle settings) with the curve bridging the
   gap; deleting the last node shortens the ride; node 0 refuses.
6. A appends 1 s; ride time in stats bar grows by 1.0; the appended
   transition's handles sit at the SINE fraction (readout ~36%, not 33%).
7. Space: POV cam starts at t=0 holding position; hold W — rides forward at
   believable speed, banking through the turn; S reverses; Shift/Ctrl
   speed/slow it; wraps at both ends; Space exits; orbit drag disabled
   while riding.
8. Coaster menu > Heart spinner to −1.0: rails jump above the heartline
   (inverted); POV now hangs below track. Gauge visibly widens/narrows rail
   spacing. Menu stays open while spinning (CustomMenuItem hideOnClick off).
9. Ctrl+S (or File > Save) → .fvd file; edit something; Ctrl+O the file back:
   design, heart, gauge, rail color all restored; Ctrl+Z after load returns
   the pre-load design. Loading a pre-v6 FVDD1 file must reproduce its curve
   shapes (handles auto-convert from absolute seconds to fractions).
10. Undo spam: Ctrl+Z ×20 walks history without exceptions; one drag = one
    undo step.
11. Coaster menu > "Choose solid color…" opens the color dialog; picking a
    color switches to solid mode and repaints the rails. "Custom Color…"
    inside the picker must NOT crash (regression: it did when the picker was
    embedded in the menu). Switch back to gradient: G-force coloring returns.
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
    VIEW STAYS WHERE YOU FLEW (no snap-back); orbit drags now circle the
    point you were looking at. Space during fly exits fly and starts POV.
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
    (energy is relative to start height); grid stays where it was; values
    survive save/load.
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
    node's handles (both dv 0); all three keys rebindable in Shortcuts.
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
    Ctrl+Shift+Z forward — all while the menu stays open. Close the menu,
    make sure a single Ctrl+Z now undoes the last CURVE edit, not the menu
    values. Same inside Edit > Options (FOV drag = one undo step) and the
    rail-color dialog.
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
27. Shift+click two more nodes (faint rings): dragging any moves all three
    (clamped against unselected neighbors); HOLDING S mid-drag constrains
    IMMEDIATELY to the dominant axis and SWITCHES axes if you pull further
    the other way (total dx vs dy); the node returns to its original
    row/column; Shift while dragging does nothing axis-wise. Guide colors:
    vertical guide = lane color (value changing), horizontal = yellow
    (time changing); "Time (s)" label is yellow. Z/X override S's choice
    (press again to return it). With exactly two ADJACENT nodes selected,
    F straightens the segment between them into a line (smooth neighbors
    stay C1). Ctrl-drag a handle far out: snaps at 10% steps up
    to 150% of the segment (curve may fold; must stay finite).
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
