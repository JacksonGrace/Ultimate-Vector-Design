# DECISIONS.md — Chronological decision log

Each entry: what was decided, why, and what it superseded. Newest model wins;
earlier entries explain the fossils you may find in old commits/files.

## D1. FVD approach itself
Specify rider forces (Gv, Gl, roll rate) and integrate them into geometry,
rather than drawing splines and computing forces after. This is the product's
identity; a public reference for the technique is Ian Kopack's
"Force-Vector Design Roller Coaster Centerline Modelling" series
(iankopack.com).

## D2. Language: prototyped in JS, product in Java
First working integrator was Node.js (fast iteration, testable in the dev
sandbox). Ported to Java at the user's request; results matched exactly
(same 1.5 cm circle-closure error), proving the math ports cleanly. The JS
files (`fvd-integrator.js`, `test-circle.js`) are historical artifacts only.

## D3. Pure-Java core / JavaFX shell split
`Vec3`, `FVDIntegrator`, `BezierBaker` have zero JavaFX imports. Reason:
the dev environment had no JavaFX, no display, and no `javac` (JRE only),
so the only way to validate anything was headless single-file execution.
This constraint produced the project's best architectural property — keep it.

## D4. Build: Maven + javafx-maven-plugin
Chosen over manual JavaFX SDK + `--module-path` flags after the user hit the
usual setup friction (winget couldn't find Maven → manual zip+PATH install
worked). `mvn javafx:run` is the only supported launch path. Java 21,
JavaFX 21.0.2, mainClass `coaster.FvdDesigner`.

## D5. Rendering: JavaFX 3D over jMonkeyEngine/LWJGL
Lowest-friction path to a live 3D view inside the same window as the editor.
Sufficient for rails + POV. Revisit only if scenery/lighting ambitions grow.

## D6. Graph editor: Canvas immediate-mode
One `Canvas` with custom hit-testing rather than scene-graph nodes per
control point. Simple, fast, full control over alpha/overlays.

## D7. Editor transitions: cubic Beziers with user handles (v2)
Replaced linear interpolation between keyframes. User explicitly wanted
adjustable Bezier handles, not preset cubic easing. Handles stored as
(dt, dv) offsets from the node. Defaults = flat tangents at 1/3 segment,
which yields the "sinusoidal" force transitions the FVD community
recommends, with zero user effort.

## D8. Bake-then-integrate
Editor curves are baked to dense linear keyframes; integrator unchanged.
See PHYSICS.md §7 for rejected alternatives.

## D9. Handle interaction model (evolved across v2→v4, all user-driven)
- v2: handles revealed on HOVER → superseded; handles blocked node access.
- v3: handles appear on CLICK-select only; smooth (collinear) handles by
  default; **B** toggles smooth/broken per node; re-smoothing keeps the IN
  (left) handle and re-angles the OUT (right) — exact user spec. Knob shape
  encodes mode: circle = smooth, square = broken.
- v4: default drag changes handle LENGTH only (pixel-space projection onto
  the current direction); **Shift** unlocks angle. "Smooth" is slope-matching
  (dv/dt), the unit-correct C1 notion — NOT screen-angle matching, which
  would depend on axis scales.

## D10. Undo (v3)
User asked for it "in the future"; implemented immediately because the B
toggle made destructive edits cheap. Deep snapshots of the whole model, one
per completed gesture (snapshot staged on mouse-press, committed on first
actual movement so click-only selections don't pollute history). Cap 100.
No redo yet. Heart/gauge deliberately excluded.

## D11. Time domain replaces arc length (v4) — BREAKING
User request; also matches FVD++. Rewrote `FVDIntegrator` around angular
velocities (ω = g·ΔG/v, rad/s) and dt stepping; roll rate became deg/SECOND
(lane scale ±120°/s). Old distance-based files (`FvdViewer`, `TestCircle`)
became incompatible and were deleted from the tree. The default layout was
re-tuned from scratch in time domain (see D14).

## D12. Single overlaid plot (v4)
Replaced three stacked lanes. All curves share full plot height, each on its
own value scale; active lane opaque + interactive, others 25% alpha and
mouse-transparent; keys 1/2/3 or toolbar toggles switch. Left axis relabels
in the active lane's color.

## D13. Fully independent per-lane nodes (v5) — BREAKING model change
Previously one node carried all three values (moving it in time moved all
lanes). Now each lane has its own `List<Node>`. Ride end = max last-node t
across lanes; shorter lanes hold their final value (drawn as a flat
continuation). Append (+1 s, key A) extends all three lanes holding final
values. Node insert now takes its value from the actual curve
(`BezierBaker.evalAt`) instead of linear neighbor interpolation.

## D14. Layout tuning by per-phase bisection
Hand-tuned layouts repeatedly blew through vertical (curvature ∝ 1/v²
runaway as trains slow on climbs; non-monotonic parameter response).
Working recipe: bisect each phase duration against a pitch target
(pull-up +28°, float −32°, pull-out → end pitch 0, then bisect turn Gv for
level), always through the FULL bake+integrate pipeline. Also derived: the
banked-turn identity Gv = 1/cos(φ) (validated <1%), and pull-outs must be
longer than pull-ups. Current default: 13.46 s / ~407 m — crest, diving
drop, level pull-out, 40°-banked turn, ends level.

## D15. Rails + heartline (v5)
Integrated path treated explicitly as heartline; two 140 mm rails at
`heart − U·heartOffset ± R·gauge/2`. Heart (default 1.0 m, negative =
inverted) and Gauge (default 1.0 m) are toolbar spinners; changes rebuild
live. Single-tube rendering retired. Ground plane sits under the lowest
RAIL, not the heartline. Known accepted gap: no ties/spine yet (user spec
was exactly two rails).

## D16. POV camera (v5)
Toggle with Space (originally R) / toolbar button. `AnimationTimer` advances ride time
(looping), camera oriented by an `Affine` built from the sample frame
(derivation + sign-flip debugging table in PHYSICS.md §6). POV camera node
lives inside the `world` group so scene centering applies to it. Orbit
input disabled while riding. Least-verified code in the project.

## D17. Save/load format `FVDD1` (v5)
Plain text: header, `heart`, `gauge`, then `lane i` sections of
`n t val outDt outDv inDt inDv smooth` lines. `Locale.US` on ALL numeric
formatting (comma-decimal locales would corrupt files). Unknown lines
ignored (forward compatibility). Lanes must load with ≥2 nodes or the load
is rejected. Loading pushes an undo snapshot first. Round-trip verified.

## D18. Window sizing from screen bounds (v3 fix)
Hardcoded 1200×900 overflowed the user's 1920×1080 (bottom lanes + title bar
off-screen). Now sized from `Screen.getPrimary().getVisualBounds()`
(excludes taskbar), capped 1400×880, centered. Never hardcode window sizes.

## D19. Testing method: single-file concatenation harness
No `javac` in the dev sandbox — only the `java` single-file launcher. Tests
were run by concatenating (public-stripped, package-stripped) sources plus a
test main into one file. See TESTING.md. In this repo, convert the four
existing validations into JUnit and keep the harness description for
historical context.

## D20. Menu bar + settings split (v6)
Toolbar Save/Load moved to a File menu; Heart/Gauge spinners moved to a
Coaster menu (embedded as CustomMenuItems, live-updating). Two kinds of
settings with different lifetimes:
- **App-wide, persist across runs** (Edit menu): rebindable shortcuts and a
  POV FOV slider (30–110°), stored by `AppSettings` (pure Java, no JavaFX)
  in `~/.fvd-designer.properties`. Shortcut combos are strings ("Ctrl+Z",
  "Space"); `FvdDesigner.matches()` parses and matches them, normalizing
  numpad digits to top-row. Shortcuts dialog: click the key button, press
  the new combo (Escape cancels); a ↺ button appears on non-default rows
  and restores the default.
- **Per-coaster, saved in the .fvd file** (Coaster menu): heart, gauge, and
  rail color — solid (ColorPicker) or the original vertical-G gradient —
  written as `railmode` / `railcolor` lines (old versions ignore them via
  the unknown-line rule; roadmap: more color options). The ColorPicker must
  live in its own dialog, not a CustomMenuItem: its "Custom Color" popup is
  owned by the menu popup, which auto-hides on click, killing the owner and
  crashing the app (found by the user at runtime). Picking a color
  auto-selects solid mode.
Shortcut reminders removed from button labels; the stats-bar hint is now
generated from the live bindings so it survives rebinding.

## D21. Fractional handles, snapping, lane click-switch, static grid (v6) — BREAKING format
- **Handles are fractions (FVDD2).** `outDt/inDt` (absolute seconds) became
  `outFrac/inFrac` (fraction 0..0.95 of the neighboring segment); dv offsets
  stay absolute. User-requested semantics: a handle is a pure DISTANCE from
  its node, relative to the neighbor — move two nodes twice as close and the
  segment renders the same graph at half width ("squish", verified headlessly
  to 0.002, which is bake-polyline sampling noise). Save format bumped to
  `FVDD2`; loader auto-converts `FVDD1` files (dt/gap per segment). The
  default-layout invariants were re-checked after the refactor: identical
  (271 keyframes, 13.46 s, 406.8 m, end pitch 0.23°, U.y 1.000).
- **Snapping (Ctrl = free).** Node drags snap to 0.1 s × 0.1 G (10 °/s for
  roll). Handle-length drags snap to ease-preset fractions — cubic 1/3,
  sine 1−2/π, quintic 7/15, circle 0.5523 — each derived by matching the
  center slope of a flat-handle Bezier to the target ease; the preset name
  shows in the drag readout. Shift+drag (angle) stays free.
- **Click a dimmed lane to edit it.** Nodes checked first, then curve bodies
  (vertical distance at the clicked time); the ACTIVE lane always wins
  because its node/knob hit tests run first.
- **Static transparent floor grid** (FVD++-style) replaced the solid ground
  box that re-positioned under the lowest rail on every rebuild (it bobbed
  around while editing). Built ONCE below the initial track: 1 m line quads
  in two meshes (minor lines skip every 10th; major every 10 m, thicker),
  alpha in the PhongMaterial diffuse, CullFace.NONE so it's visible from
  below. Lives inside `world` so the one-time centering applies.
- Heart/Gauge menu labels set to black — default fill washed out on the
  light menu popup.

## D22. Modifier inversion, mirrored smooth handles, banded grid (v6.1) — user feedback on D21
- **CTRL now ENABLES snapping** (nodes to the 0.1 s/0.1 G/10 °/s grid,
  handle fractions to the ease presets); default movement is free. Inverted
  from D21 at user request after hands-on testing.
- **Handle drags are free by default** (angle + length); **SHIFT locks the
  handle on its current line** (the old default length-only behavior).
  Shift never touches the partner handle.
- **Smooth nodes mirror the partner on free drags, proportionally on BOTH
  axes:** partner.frac *= newFrac/oldFrac and partner.dv *= newDv/oldDv of
  the dragged handle (user's spec: equal handles behave as mirror images;
  unequal handles scale in proportion — 30%→15% takes a 20% partner to 10%,
  dv 1.0→1.5 takes −0.8 to −1.2 — staying collinear while growing/shrinking
  together). When the dragged handle was flat (dv≈0, ratio undefined) the
  partner falls back to slope-mirroring. Ratios compound per mouse event;
  the telescoping product keeps the whole gesture proportional.
- **Grid fades in concentric square bands** (full detail to 50 m, faint to
  100 m, majors-only to 150 m, nothing beyond). Reason: JavaFX 3D has no
  texture mipmapping, so the original uniform 700 m grid shimmered/moiréd
  at distance (sub-pixel lines) — eye strain reported in testing. Band
  meshes clip lines to square rings so nothing double-draws.

## D23. Transition-centric editing (v6.2)
The user thinks in TRANSITIONS (FVD style: "2 s pull-up to +2.5 G"), not in
absolute coordinates, so:
- **Node-drag readout** shows Δt and Δvalue from the PREVIOUS node, with the
  true value in parentheses ("Δt=0.80s  Δ+1.50 (2.50)"). Node 0 still shows
  absolutes (it has no previous node and its t is pinned to 0).
- **Append (A) uses sinusoidal handles** (frac 1−2/π) for the new transition
  instead of cubic 1/3 — matches the FVD community default for force ramps.
  Double-click node INSERT still uses 1/3 flat (it lands on an existing
  curve; see the de-Casteljau roadmap item).
- **"Ripple edit" toolbar toggle:** dragging a node also shifts every LATER
  node of the same lane by the same Δt/Δvalue. Horizontal drags therefore
  preserve all later transition times (only the edited segment stretches —
  fractional handles keep every shape); vertical drags translate the rest of
  the graph. The upper time clamp is lifted while rippling (later nodes move
  out of the way); values may ripple past the lane's display range — the
  plot clamps drawing but the data is honored by the physics.

## D24. Ripple per-axis, sine inserts, transition snapping (v6.3) — user feedback on D23
- **Ripple split into two toolbar checks** ("Ripple: time / value") so each
  axis toggles independently. Rendered as minimal filled-square checkboxes
  (custom Region styling) — the user explicitly did not want the stock
  CheckBox's checkmark glyph.
- **Double-click insert now also defaults to sinusoidal handles** (matches
  append). Only the shipped default layout and `applyDefaultHandles` keep
  cubic 1/3 — changing those would alter the validated default geometry.
- **Snap semantics (with CTRL): time snaps the TRANSITION length** (Δt from
  the previous node in 0.1 s steps — the readout shows clean 0.30s/0.80s),
  **value snaps to the GLOBAL absolute grid** (0.1 G steps; roll changed
  from 10 °/s to whole 1 °/s steps per the user's "exactly 124 deg/s"
  example). Deliberately NOT Δ-value snapping — user spec.

## D25. Node editor dialog, fly camera, Delete key, unit readouts (v6.4)
- **Node readout shows the global value with units** ("Δt=0.80s  2.50 g" /
  "°/s"); the Δ-value display from D23 lasted one iteration — the user
  wanted transition time but ABSOLUTE value.
- **Double-click a node → small modal editor**: type transition time (s),
  transition Δ (value units, relative to the previous node), and in/out
  handle fractions, all %.3f `Locale.US`. Applies through the same
  `moveNode` path as dragging, so the ripple toggles are honored. Node 0
  edits its global value instead (no previous node). Bad input in a field
  silently keeps the old value. Double-click on empty curve still inserts.
- **Fly camera**: right-click the 3D view to enter/exit. WASD moves (strafe
  stays level), mouse looks, SHIFT ×4, CTRL ×0.2, scroll is a fast burst
  along the view direction. JavaFX cannot capture the pointer, so mouse
  look uses the standard workaround: hide the cursor and Robot-warp it back
  to the viewport centre every event (delta < 0.5 px = our own warp, skip).
  Entering fly adopts the orbit camera's exact pose (seamless); exiting
  returns to the orbit rig's unchanged pose (snap-back, accepted). Fly and
  POV are mutually exclusive; while flying, WASD/Shift/Ctrl are consumed
  before the shortcut matcher (A = strafe, not append). Fly cam shares the
  POV FOV setting.
- **Delete key** (rebindable action "delNode", shown in the Shortcuts
  dialog) deletes the selected node. Interior deletion bridges the gap with
  both neighbors keeping their global positions and handle settings — this
  falls out of fractional handles for free (the neighbors' fractions just
  span the merged segment). Deleting the LAST node now works (shortens the
  ride) — right-click shares these rules; only the first node (t=0 anchor)
  refuses, and lanes keep ≥ 2 nodes.

## D26. Cross ties, orbit pivot, exact node insert (v6.5)
- **Cross ties**: one thin box between the rails (extended past them by the
  rail radius) every `tieSpacing` metres of ARC LENGTH (uniform spacing
  regardless of speed; positions quantize to the nearest 0.01 s sample).
  Per-coaster spinner in the Coaster menu (0.2–10 m, default 1.0), saved as
  `tiespace` in the .fvd. Fixed dark-gray material regardless of rail color.
  Spine box still missing — roadmap.
- **Orbit rig gained a movable pivot** (`camPivot`, first transform in the
  rig) so EXITING FLY MODE KEEPS THE VIEW: on exit the pivot is placed
  `-camZoom.z` metres in front of the fly camera and yaw/pitch copied over —
  the pose is preserved exactly, and subsequent orbit drags rotate around
  the point you were looking at. Entering fly reads the pivot back so the
  handoff stays seamless in both directions.
- **Insert-on-curve replaced double-click insert**: hovering the active
  curve (away from nodes/knobs) shows a translucent white dot riding the
  line; LEFT CLICK inserts there. Flat segments insert a level node with
  sine handles. Curved segments use `BezierBaker.splitSegment` — an EXACT
  de Casteljau subdivision (no optimization needed; this was roadmap item
  4): the u-parameter is found by bisection on x(u)=t (robust to folded
  handles), neighbors' facing handles are rewritten, the new node's handles
  are tangent and collinear (smooth). Headless-validated: max eval
  difference 0.005 across shapes including near-folds — pure bake-sampling
  noise. The same `curveHitT` predicate drives both the dot and the click,
  so preview and action can't disagree.

## D27. POV ride control, graph zoom/pan, start point, polish (v6.6)
- **POV is now W/S-controlled**: W rides forward at the simulated speed,
  S backward, SHIFT ×4, CTRL ×0.25, no key = hold position (still follows
  live edits while holding). Replaces the auto-looping playback; wraps at
  the ride ends. W/S/Shift/Ctrl are consumed while riding (so Ctrl+S can't
  save mid-POV — exit first; same tradeoff as fly mode).
- **Graph zoom/pan**: scroll zooms the TIME axis around the cursor (min
  window 0.5 s); right-drag on empty graph pans (right-click on a node
  still deletes). Zooming fully out snaps back to auto-fit, which is the
  default state and follows ride-length changes. Implemented as a view
  window [viewT0, viewT1] under tToX/xToT so every feature (hit tests,
  hover insert, ticks) inherited it for free.
- **Axis labels**: rotated lane name along the left edge (follows the edit
  mode, lane-colored) and "Time (s)" centered under the ticks
  (MARGIN_BOTTOM 20→32). The horizontal top-left lane name was removed.
- **Track start point** (Coaster menu, per-coaster, `start x y z` line in
  .fvd): three spinners ±500 m. Physics is unchanged by translation (speed
  is relative to start height); the static grid/world-centering do NOT
  follow — placed once at startup, by design.
- **Fly FOV now matches the orbit camera** (the D25 choice of sharing the
  POV FOV made entering/exiting fly visibly zoom); the Options slider
  applies to the POV cam only.
- **Ties end at the rail centerlines** (were gauge/2 + rail radius, poking
  out); the tie cross-section hides inside the rail tubes.
- **Steep-section insert fixed**: `curveHitT` measured only VERTICAL pixel
  distance at the mouse's time — a near-vertical curve was unhittable. Now
  it takes the nearest point over the baked polyline's segments in 2D pixel
  space (`BezierBaker.polyline` exposed for this).

## D28. Editing QoL round (v6.7) — committed
- **Insert near nodes**: the real cause of the remaining "steep sections
  won't insert" report was the MIN_GAP (0.1 s) guard, not steepness —
  short/steep transitions put every click within 0.1 s of a node. Inserts
  now use INSERT_GAP = 0.02 s, and the gap check moved INTO `curveHitT` so
  the hover dot only appears where a click will actually succeed. (Node
  DRAGS keep the 0.1 s spacing; two nodes placed < 0.1 s apart by insert
  push apart if later dragged — accepted.)
- **H** flattens the selected node's handles (dv = 0; while dragging a
  handle on a BROKEN node, only that one). **Z/X** toggle per-selection
  axis locks for node drags (vertical/horizontal), drawn as a translucent
  guide line through the node; the lock clears when the selection changes.
  All three are rebindable actions in the Shortcuts dialog.
- **Adaptive graph grid**: 1-2-5 "nice" steps (0.1/0.2/0.5/1/2/5…) chosen
  so lines sit ~60 px apart in time and ~30 px in value; density re-derives
  on every zoom/resize. Time tick labels moved onto the same grid lines
  (the fixed 8-tick row is gone).
- **POV**: scroll = fast burst along the track (0.02 s per scroll px ≈
  fly-mode's 20 m/notch); a translucent vertical line on the graph marks
  the camera's track position, toggled in Edit > Options ("povMarker" in
  AppSettings, default on), redrawn only when the position changes.
- **Graph panel is a vertical SplitPane**: drag the divider to resize;
  a translucent ▼/▲ button overlaid bottom-right on the 3D VIEW (so it
  survives full collapse) minimizes/restores the whole bottom panel
  (stats + toolbar + editor) for a full-screen viewport.

## D29. Selection/scale/undo round (v6.8)
- **Graph-restore bug**: SubScene reports its current size as its minimum,
  so `subHolder` refused to shrink — on non-maximized windows the divider
  and collapse button were pushed OFF-SCREEN (looked like "can't restore").
  Fix: `subHolder.setMinSize(0, 0)`. Keep this line.
- **Menu-local undo/redo**: Ctrl+Z / Ctrl+Shift+Z inside the Coaster menu,
  Options and rail-color dialogs act on a LOCAL history of {undo, redo}
  setter pairs, alive only while that menu/dialog is open (sessions begin
  on show, are discarded on hide — the changes lock in). Menu popups and
  dialogs have their own Scenes, so the key filter is installed per scene
  (`installMenuUndoKeys`, idempotent). Spinner listeners record via
  `wireMenuSpinner`; FOV drags record ONE entry per gesture; re-applying a
  recorded value is guarded by `menuApplying` so undo doesn't re-record.
  Menu edits never touch the coaster's undo stack — after closing a menu,
  one Ctrl+Z undoes the last COASTER edit, per user spec.
- **Auto-scaling value axis** per lane: defaults Gv −1..4 g, Gl −1..1 g,
  roll −150..150 °/s; when the curve within the visible time window pokes
  out, the axis expands (5% pad, rounded outward to a 1-2-5 step). Frozen
  during drags so the mapping can't shift under the cursor.
- **End-node drag tamed**: the old feedback loop (node.t grows → auto-fit
  domain grows → same mouse x maps further right → runaway) is gone — the
  time window FREEZES for the duration of any drag gesture (dragWinT0/T1);
  pulling past the right plot edge extends time at 3× the overshoot
  (farther = faster), and the view re-fits on release.
- **Dotted hold-value extension is real UI**: a lane ending before the ride
  end draws its implied flat continuation DASHED, it is hover/insertable
  (appends a real node at the same level, flat sine handles), and
  `curveHitT` treats it as one more segment.
- **Multi-select**: Shift+click adds nodes (primary keeps the solid ring,
  members get faint rings). Dragging any member moves the group by common
  deltas, clamped against non-selected neighbors; node 0 pins the group in
  time; ripple toggles are IGNORED for group moves (the group is the
  ripple). Delete removes all selected (first node and the ≥2-node floor
  still hold). Undo prunes stale indices.
- **SHIFT during a node drag** = transient axis constraint chosen from the
  dominant direction of the first ~6 px of movement, with the same guide
  line as Z/X. Z/X remain sticky per-selection; Shift wins while held.
- **Arrow keys** nudge the selection by the snap steps (0.1 s / 0.1 G /
  1 °/s); Ctrl+arrow lands ON the grid; OS key auto-repeat provides
  hold-to-glide; undo entries coalesce per keypress burst (0.8 s window).
- Lane toggle buttons tinted with their curve colors (`LANE_COLORS`
  hoisted out of GraphEditor).

## D30. Rate-based view growth, Help menu, unified FOV, ground v3 (v6.9)
- **Edge-pull view growth is now RATE-based on both axes** (supersedes
  D29's 3× positional overshoot): while a node drag sits past a plot edge,
  `dragGrowTimer` (AnimationTimer) extends that axis every frame at a
  speed proportional to the overshoot — smooth, controllable, and it grows
  even with the mouse held still. The scale DOES shift under the cursor
  mid-gesture now, by design (user reversed D29's freeze rationale after
  feel-testing); the gesture window/value scale are still the mutable
  copies, and everything re-fits on release.
- **Ground v3**: six fade bands (minors to 120 m, majors to 240 m) over a
  solid translucent 600 m sheet 3 cm below the lines (z-fighting offset).
  Reads as a surface in the far field instead of lines dissolving into
  void; still a handful of static meshes, no per-frame cost.
- **Fresh-insert grab priority**: for ONE press after click-inserting a
  node, the node body outranks its own knobs (freshInsertPriority) so
  insert-then-move doesn't accidentally grab a handle.
- **Help > Controls & Features** dialog (non-modal, sectioned, scrollable):
  Getting started / Graph view / Nodes / Handles / Cameras / Menus &
  settings. USER REQUIREMENT: must be updated with every user-facing
  change — rule recorded in CLAUDE.md and above `showHelpDialog`.
- **One FOV for every camera** (Options slider now drives orbit + fly +
  POV; label renamed). Supersedes D27's fly=orbit-only pairing: the user
  wants the slider to govern fly too, and syncing all three keeps the
  no-zoom-jump invariant. Note the orbit camera now starts at the saved
  FOV (65° default) instead of JavaFX's 30°.
- **Shift axis-constraint anchors at the node's gesture-start position**
  (drifted pixels snap back onto the original row/column) and decides its
  axis after ~3 px instead of 6.

## D31. Off-screen handles, box select, split FOVs, options round (v7.0)
- **Off-screen handles draw honestly**: `valToY` no longer clamps (the
  clamp bent the apparent angle of out-of-view handles — the reported
  "sharp spot"); all plot geometry is clipped to the plot rect instead.
  An off-screen knob renders as a dashed stub ending in a hollow marker at
  the plot edge; CLICKING the marker sets `handleViewExpand`, which makes
  updateScale include the selected node's knobs (view grows to fit);
  clicking elsewhere clears it and the view returns. Handle drags also
  drive the edge-growth timer now, so knobs can be pulled past the edge
  smoothly like nodes.
- **Node body ALWAYS outranks its own knobs** on press (generalizes the
  v6.9 fresh-insert rule, which is removed): overlapping handles can never
  steal the node grab. Handles of the selected node are still grabbable
  anywhere they don't overlap the node itself.
- **Append with a node selected inserts a 1 s flat hold after it**: all
  lanes' later nodes shift +1 s (regardless of ripple toggles); the
  selected node's outgoing transition shape transfers to the new hold node
  so the ride after the pause is untouched. No selection = old ride-end
  append.
- **MIN_GAP 0.1 → 0.01 s** (minimum transition length). INSERT_GAP stays
  0.02 s.
- **Node dialog edits the GLOBAL value** (the Δ-from-previous field from
  D25 lasted two iterations; transition time stays relative).
- **Handle presets padded**: 5% steps (0.05–0.30) below cubic, 10% steps
  (0.65–0.95) beyond circle; unnamed presets show only the % readout.
- **Options dialog**: split FOVs — "Track view" (orbit+POV) and "Fly" (the
  all-cameras experiment from D30 lasted one iteration); fly mouse
  sensitivity slider (default 0.15 °/px; per-event delta also capped ±35 px
  because OS pointer acceleration delivered huge single deltas = the
  slow-too-slow/fast-too-fast complaint); adjustable snap steps (time/G/
  roll) consumed live by the editor via settings.
- **Grid LOD**: the floor grid group follows the active camera (orbit
  pivot, fly position, POV sample) in 10 m quantized steps so full line
  density is always around the viewer; band pattern stays world-aligned.
- **Graph axes**: faded small value labels on every horizontal grid line;
  time zoom now reaches 0.1 s windows with 0.01 s minimum grid step and
  two-decimal tick labels (the 1-2-5 ladder no longer stalls at 0.1).
- **Box select** on empty plot (rubber band; Shift adds); plain-click
  deselection moved from press to release so the two don't fight.
- **Help dialog v2**: bullet lists per section + a canvas diagram of a
  transition (node/handles/insert dot/dotted hold); content updated for
  everything above.
- WARNING (process): PowerShell text rewriting corrupted the file's UTF-8
  (mojibake + BOM) once — repaired. Do not pipe these sources through
  PS 5.1 Get-Content/Set-Content.

## D32. FOV pairing flip, live shift axis, colored guides, 150% handles (v7.1)
- **Orbit now shares the FLY FOV** (third FOV arrangement, user-driven:
  D30 unified all → D31 paired orbit with POV → now orbit pairs with fly).
  Rationale: fly and orbit hand off to each other on right-click, so THEY
  are the pair that must not zoom-jump; POV keeps its own lens. Options
  labels: "POV camera FOV" / "Fly & orbit FOV".
- **Shift axis constraint is instant and reversible**: re-evaluated every
  drag event from the TOTAL |dx| vs |dy| since the press (1 px deadzone),
  so it engages immediately and flips if you pull further the other way.
  Explicit Z/X now takes PRECEDENCE over the shift-auto axis (pressing
  again returns to auto) — inverted from D29 where shift won.
- **Yellow = time, project-wide UI rule** (`TIME_COLOR`): the "Time (s)"
  axis label, the vertical time-pinning guide (Z), and the POV marker are
  all yellow; the horizontal value-pinning guide (X) is the lane color
  faded. Future time-related chrome should follow it.
- **Snap spinners walk the 1-2-5 series** on their arrows (both ways:
  … 0.05 0.1 0.2 0.5 1 2 5 …, `nice125Next/Prev`); typing still accepts
  arbitrary values.
- **Handle cap raised to 150% of the segment** (MAX_FRAC 0.95 → 1.5) for
  aggressive overshoot shapes; the baker's monotonic-t guard sanitizes the
  folded time axis (headless ExtremeFracCheck: 0 violations at the cap).
  Presets gained 0.40 and 0.60, and 10% steps run 0.60 → 1.50.

## D33. S-slide, F-straighten, hover value, options polish (v7.2)
- **The slide constraint moved from SHIFT to a held S key** (nodes: axis
  lock; handles: in-line length-only). Shift was overloaded — it also
  multi-selects — and the user wanted them separate. S is not a modifier,
  so `slideKey` is tracked by scene key filters (set on press when no
  camera mode owns WASD, cleared on release); Ctrl+S still saves.
- **F with two ADJACENT selected nodes straightens the segment**: both
  facing handles land on the chord (slope · effDt); smooth endpoints
  re-angle their far handles to keep C1. Rebindable action "align".
- **Guide colors swapped** (D32 lasted one iteration): colors now mean
  what the drag CHANGES — vertical movement changes value = lane color,
  horizontal changes time = yellow. TIME_COLOR still owns the "Time (s)"
  label and POV marker.
- **Hover readout**: the insert dot shows the lane's value at the cursor.
- **Options**: ↺ reset-to-default on every row (routes through the
  control, so persists + menu-undoable); "Mouse sensitivity" now drives
  orbit too (orbit = 2× the setting, preserving both old defaults).
- **Shift+wheel fix**: Windows delivers Shift+wheel as HORIZONTAL scroll
  (deltaY = 0) — every scroll handler (fly, POV, orbit zoom, graph zoom)
  falls back to deltaX, so scrolling keeps working with Shift held.

## D34. POV capture, gradient colors, slide-rail guides (v7.3)
- **POV ride controls are now CAPTURED with right-click** (mirrors fly's
  hidden-mouse model): a fresh Space enters POV hands-off — W/S and the S
  slide key stay with the graph editor, so you can edit while watching the
  ride cam. Right-click hides the mouse and takes W/S for riding (no mouse
  look; orientation is track-driven); right-click again releases. While
  captured, SPACE FLIPS between fly (WASD) and POV (W/S) without releasing
  — `povCaptured` field; movement-key consumption and the slideKey guard
  test `flyActive || (povActive && povCaptured)`. Scroll-burst works in
  POV captured or not (it's mouse-based).
- **G-force gradient is customizable**: four per-coaster anchor colors
  (below 0 g / 1 g / 3 g / 5 g+) in the renamed "Rail colors" dialog,
  saved as a `gradcolors` line (12 floats) in the .fvd; segment structure
  of the gradient (thresholds 0 / 1.5 / 3 / 5) is unchanged.
- **S-slide rail for handles**: dragging a knob with S held draws the
  dashed line the knob is locked to, plot-clipped; both this and the node
  axis guides brightened (alpha 0.35 → 0.5).

## D35. Roll snap default, hover slope, fly-exit jump fix (v7.4)
- **Roll snap default back to 10 °/s** (D24 had set 1 °/s from the user's
  "124 deg/s" example; now that the step is user-adjustable the coarse
  default wins — typed values still allow any number).
- **Shift while hovering the curve shows the SLOPE** at the cursor
  (central difference over ±0.01 s, drawn under the value readout);
  Shift press/release retriggers the redraw via key filters so it appears
  without wiggling the mouse.
- **Fly-exit view jump fixed**: exiting fly by right-click while MOVING
  the mouse jumped the view. Cause: the right-drag events that follow the
  exit fell into the ORBIT drag handler, which rotated using a stale
  lastMouse anchor (huge phantom delta). Orbit drags now respond only to
  the primary button, and the secondary-press handler refreshes the
  anchor.

## D36. View menu, heartline, pivot picking, readout polish (v7.5)
- **New View menu** (File · Edit · View · Coaster · Help): "Viewport
  settings…" dialog now owns the two FOV sliders (moved out of Options,
  which keeps sensitivity/snaps/POV marker) + a "Show heartline"
  CheckMenuItem — thin pale tube on the integrated path (the curve the
  physics lives on), app-wide setting, default off. In POV the tube sits
  at the camera and is mostly hidden by the near clip.
- **Help opens directly**: the menu's text is a Label graphic whose click
  shows the dialog (an empty Menu never pops), title renamed "Help".
- **Shift+click the track in orbit = re-center the pivot there**:
  nearest-sample search in SCREEN space (world.localToScreen over every
  5th sample, 25 px radius) — a forgiving "tube hitbox" without JavaFX
  mesh picking. View translates (orientation and zoom kept).
- **Node drag readout clamps into the plot** (it vanished when dragging
  past the bounds; handle readout already clamped since v7.0).
- **Shift-hover now REPLACES the value with the slope** (no label prefix).
- Roll snap default 10 → **5 °/s** (third pick: 10 → 1 → 10 → 5; it's
  user-adjustable, this is just the out-of-box step).

## D37. View menu retired, neon heartline, analytic slope, pivot fix (v7.6)
- **View menu removed after one iteration**: FOV sliders and the heartline
  toggle merged into Edit > Options (user preference: one settings
  window). Menu bar is File · Edit · Coaster · Help again.
- **Heartline is a hairline neon strand**: radius 45 mm → 15 mm, 4 sides,
  and a 1×1 self-illumination map (diffuse black) so it renders as pure
  unlit yellow at any distance/lighting — reads as a line, not a tube.
- **Hover slope is ANALYTIC** (`BezierBaker.slopeAt`: bisect x(u)=t on the
  segment's cubic, then dy/du / dx/du): differencing the baked polyline
  gave a stair-stepped readout because a polyline's slope is piecewise
  constant between bake samples.
- **Shift+click pivot snapping rejects behind-camera samples**:
  localToScreen projects points behind the camera MIRRORED onto the
  screen, so clicking empty sky could snap the pivot to invisible track.
  Samples with (p − camPos)·forward ≤ 0 are skipped; a genuine miss moves
  nothing and the drag proceeds as a normal orbit.

## D38. POV-hidden heartline, gradient stop values (v7.7)
- **Heartline hides during POV** (`heartMesh.setVisible(false)` on ride
  start, back on exit/flip-to-fly; rebuilds mid-ride respect it): the
  camera sits exactly ON the strand, so it flickered across the lens.
- **Gradient stops carry adjustable G VALUES** alongside their colors:
  the rail-colors dialog shows a spinner + picker per stop (defaults
  0/1/3/5 g; range G_MIN..G_MAX; flat outside the ends, lerped between).
  Saved as a `gradstops` line next to `gradcolors`. NOTE: this replaced
  the old hard-coded shape (flat green 0–1.5 g, step at 0) with a
  continuous 4-stop ramp — the default look shifts slightly (blue→green
  now blends over 0..1 g instead of stepping at 0).
- **Help v3** (audit pass): control entries are aligned bold key →
  description rows (helpKey), prose only for non-key behavior (helpText).
  Audit added the missing pieces: node deletion semantics (Delete/
  right-click, gap-bridging), coaster Ctrl+Z in Getting started, the drag
  readout, heartline-hides-in-POV, and de-duplicated the Options list
  that appeared in two sections.

## D39. Multi-section architecture adopted (planning only)
The user's large feature list (sections, bridges, speed control, NL2
interop, viewport editing) was analyzed and phased. Core insight: a force
section's SHAPE depends on the speed it is integrated at, so "connecting
track must never change shape" forces splitting the single integrator pass
into geometry (per section, at a per-section DESIGN speed) / dynamics
(global true speed) / analysis (true forces) passes. Full contracts,
data model, per-mode geometry functions, bridge freeze rule, influence
curves, "become real", speed regions, FVDD3, and testing gates are in
**docs/SECTIONS-DESIGN.md** (the Phase 1+ spec). CLAUDE.md's roadmap now
carries the phase order; Phase 0 items are deliberately independent of
the overhaul. The old "geometric sections need a solver" note is
superseded — geo sections specify body rates directly; solvers return
later as tools on top.

## D40. NoLimits 2 CSV export (v7.8, Phase 0 item 1)
- **New pure-Java `Nl2CsvExporter`** (no JavaFX, headless-tested like the
  rest of the core): resamples the integrated track onto NL2's external
  CSV format — tab-separated header `"No." "PosX" … "UpZ"`, rows numbered
  from 1, one position + orthonormal front/left/up frame per vertex,
  `Locale.US` decimals at %.6f.
- **Z is negated on export.** NL2's world is right-handed Y-up; ours as
  rendered (X right, Y up, Z into the screen — the JavaFX Y-flip) is
  LEFT-handed. Without the mirror every turn and roll imports reversed.
  `left` is then computed as `up × front` IN EXPORTED COORDINATES rather
  than by hand-mirroring −R — algebraically identical, immune to sign
  slips, and it makes NL2's `up = front × left` identity hold exactly.
  Chirality was proven headlessly (+Gl turn exports clockwise-from-above;
  +roll tips exported up toward left) but the one thing the tests CANNOT
  see is JavaFX's own camera handedness — the first real NL2 import
  should eyeball a turn (fix would be dropping the negation, one line).
- **Rail center is the default exported line** (heartline is the option):
  NL2 treats imported vertices as the track spline between the rails, so
  our spine (`heart − U·heartOffset`) is the honest mapping; heartline
  export exists for feeding NL2's own heartlining tools.
- **The exported line is resampled by ITS OWN arc length** (an offset
  curve is shorter/longer than the heartline through hills — 337 vs 343 m
  on the test layout), lerping position and frame between the dense
  dt=0.01 s samples and re-orthonormalizing.
- **Segment length is a target, not a promise:** the requested spacing is
  rounded to the nearest whole number of equal segments (step =
  total/round(total/req)), so both endpoints land exactly and there is no
  runt final segment. The actual step is reported in the stats bar.
- **Settings live in AppSettings** (`nl2Heartline`, `nl2SegLen`, default
  rail-center / 1.0 m): export preferences are per-user workflow, not
  per-coaster design data, so they do NOT touch the .fvd format.
- **UI:** File > Export NL2 CSV… (new rebindable action "export",
  default Ctrl+E) opens a small modal — radios, spinner with ↺, note —
  wired into the menu-local Ctrl+Z session like Options; Export… runs the
  FileChooser and reports "points, actual spacing, length".

## D41. Feel batch: v0 setting, ground at 0, lane-local append, selection leveling (v7.9)
User-driven refinement list after hand-testing v7.8:
- **Export default flipped to HEARTLINE** (D40 shipped rail-center default;
  user preference after one day). Radios reordered heartline-first.
- **Start speed (v0) is a per-coaster setting** — was hardcoded 26 m/s
  since v1. Coaster menu spinner (1–100 m/s) + `v0` line in the .fvd
  (unknown-line rule keeps old readers safe), AND a "Start speed (m/s)"
  field in the double-click dialog of node 0 of ANY lane (user spec:
  reachable from both places). The dialog writes THROUGH the spinner so
  the menu stays in sync and the track rebuilds once. Like heart/gauge
  it is not in coaster undo (menu-local Ctrl+Z only).
- **Ground plane is ABSOLUTE y=0** — the grid used to sit at
  floor(lowestRail−1), i.e. wherever the track dipped. Now Start y IS
  height above ground. Default startY 0 → 21 keeps the shipped layout's
  old ~1.95 m clearance exactly (its lowest rail dips 19.045 m below the
  start; old ground sat at −21 — GroundCalc scratch, 2026-07-05). Old
  .fvd files with small start y may now sit at/below the grid: accepted,
  that's what the start-y spinner is for.
- **Default orbit distance 260 → 60 m** (`ORBIT_DIST0`): "much closer";
  260 framed the whole 400 m default ride, 60 starts near the station.
- **A/Append (no selection) extends only the ACTIVE lane** — the other
  two keep their dotted hold-final-value line with no nodes added
  (they used to each get a node at the new end). Ride end still grows
  because ride duration = latest node across lanes. The WITH-selection
  hold-insert still shifts all lanes (it splices time into the middle).
- **Typed handle fractions slide the knob** (node dialog): dv scales
  with the fraction so the handle keeps its angle — S-drag semantics;
  previously typing a length changed the handle's slope. Partner handles
  are untouched (also matching S).
- **Box-select primary = EARLIEST node in the box** (was: last, an
  iteration-order accident). Shift+click keeps last-clicked-is-primary.
  This feeds the next item:
- **H flattens EVERY selected node's handles; NEW Shift+H ("levelSel",
  rebindable) levels the selection ONTO the primary node** — all selected
  nodes take its value with flat handles, so the run becomes one
  horizontal line. <2 selected falls back to plain H. Ripple toggles are
  deliberately ignored (it's an explicit align, not a drag).

## D42. Straight-lock segments, fraction readouts (v7.9 cont.)
- **Shift+F ("alignLock", rebindable) = PERSISTENT straightness** between
  two adjacent selected nodes (F remains the one-shot). Model: a
  `straightOut` boolean on the segment's LEFT node (BezierBaker.Node) —
  a node property survives index shifts from inserts/deletes, rides
  along in undo snapshots via Node.copy(), and costs nothing in the
  core (bake doesn't know about it).
- **Enforcement is a single choke point**: `enforceStraightLocks()` at
  the top of GraphEditor.redraw() re-chords the facing handles of every
  locked segment (same math as F). Every lane mutation is followed by a
  redraw, so the lock holds under node drags, group drags, nudges,
  ripple shifts, dialog edits and loads; it's idempotent and O(nodes).
  Consequences that fall out for free: dragging an endpoint keeps the
  line straight (handles re-chord), and dragging a FACING knob only
  changes its fraction — enforcement pins dv to the chord, so the knob
  slides along the line like an S-drag.
- **Smooth endpoints keep C1** (far handle re-angled to the chord slope)
  UNLESS that far segment is itself locked — each lock owns exactly its
  own facing handles, so chained locks with a kink don't fight
  (last-writer problem avoided by construction).
- **Unlock = Shift+F with EITHER endpoint selected** (user spec:
  "selecting at least one of them removes"); if the selection touches
  any locked segment, Shift+F unlocks those instead of locking new ones.
- **Visual: twin 1 px lines** along the chord normal replace the 2 px
  curve stroke on locked segments (user suggestion).
- **Insert on a locked segment locks BOTH halves** (the run was straight;
  splitting shouldn't quietly free half of it). Deleting the interior
  node of two locked halves leaves the merged segment locked.
- **Handle lengths now read as FRACTIONS everywhere** — the drag readout
  says "out 0.33 cubic" (was "out 33%"), matching the node dialog, whose
  labels dropped "(fraction)". Deliberately NO explanation in menus or
  readouts; the Help window's Handles section carries the semantics
  (user: keep the UI clean, Help is the reference).

## D43. Drag modifiers no longer complete shortcut combos (v7.9 cont.)
User report: holding Ctrl mid-drag (snap) and then pressing S (slide)
fired Ctrl+S SAVE — a file dialog popping mid-gesture. Same class:
Ctrl+Z fired UNDO (restoring lanes under a live drag!) when Z was
pressed for the axis lock with Ctrl held. Fix, two halves:
- **S now becomes the slide key mid-drag even with Ctrl down** (the
  `!isShortcutDown()` guard is waived while `editor.isDragging()`), so
  snap+slide compose. At rest the guard stays — pressing S while Ctrl is
  held is still a deliberate Ctrl+S save.
- **While a drag gesture is live, OR S is held as the slide key, key
  events carrying ANY modifier fire no bound action** (handleKey returns
  before the dispatch chain, unconsumed, so the raw key still reaches
  the slideKey/shift-hover filters). The `slideKey` half also covers
  keys still held right after the mouse releases — S auto-repeat with
  Ctrl down must not save a moment after the drag ends. Bare keys keep
  their mid-drag roles (B, H, Z/X). Known acceptable losses while S is
  held at rest: Ctrl+arrow grid-nudge and modifier combos generally are
  ignored until S comes back up.

## D44. RK4 integrator (v8.0, Phase 0 item 2)
- **FVDIntegrator v3: classic RK4** on state (pos, T, U) with
  `T' = Ω×T`, `U' = Ω×U`, `pos' = T·v`; frame re-orthonormalized after
  each step (normalize T; U ⊥ T; R = U×T). Each STAGE re-normalizes its
  local frame copy so the physics never sees the slightly non-unit
  vectors RK4 stages produce. Speed stays ALGEBRAIC (energy vs start
  height, evaluated per stage) — it is not ODE state, so there is
  nothing to integrate wrong. Same public API, same dt=0.01, same
  sample cadence; the old one-Rodrigues-rotation-per-step loop is gone.
- **Why**: measured on the analytic flat circle (v0=20, Gl=1.5,
  r=27.192 m), worst radial deviation fell from **10.0 cm (Euler) to
  < 1e-9 m** at identical dt. The historical "1.5 cm closure" figure
  was dominated by end-time quantization (last sample lands up to dt
  before exact closure ≈ 6 cm of arc), NOT integrator accuracy — radial
  deviation is the honest metric and TESTING.md #1 now says so.
- **Shipped-layout invariants re-baselined** (the layout was tuned under
  Euler): length 406.809 → 406.878 m, end pitch 0.2300° → 0.2284°,
  ride time/keyframes/sample count unchanged (13.46 s / 271 / 1347),
  U.y 0.999971 unchanged, top speed 32.1 m/s unchanged. The banked-turn
  drift tightened 2.75 → 2.15 m. No re-tuning needed. SECTIONS-DESIGN
  §9's Phase-1 bit-for-bit gate now references the RK4 numbers.
- **interpAt is binary search now** (RK4 quadrupled the lookups; the old
  "O(n) scan, fix if rides get long" debt is paid).
- **Friction placeholder demoted**: the old per-step bleed never
  accumulated (v was recomputed fresh from energy each step) — an inert
  placeholder. RK4's dynamics use pure energy speed; the bleed survives
  only on the RECORDED v (identical behavior at friction=0, the only
  shipped value). Real friction is Phase 3's job.
- **Stall behavior changed (noticed post-hoc)**: the old loop advanced
  position with the RECORDED v, which carried a `max(0.1, …)` floor — so
  a layout that climbed above its energy ceiling CREPT through the crest
  at 0.1 m/s, quietly creating energy. RK4 advances with the raw energy
  speed, so a genuine stall now wedges asymptotically just below the
  crest (frame still rotating at vSafe-floored rates → local knot)
  instead of cheating through. Both are garbage-by-design on stalled
  layouts (v floor 0.5 in the ω denominators is the "finite garbage"
  guard); the new failure mode is at least visible. If push-through
  feels better in practice, floor the advance speed in deriv() at
  0.1 m/s — one line. Honest stall handling is Phase 3.

## D45. Resulting-rates overlay (v8.0, Phase 0 item 3)
- **FVDIntegrator.bodyRates(samples)** (pure, testable): central
  differences on the frame vectors give body rates via `pitch = T'·U`,
  `yaw = T'·R`, `roll = −U'·R` (from T' = Ω×T, U' = Ω×U). DISPLAY sign
  conventions match the editor: pull-up = +pitch, right turn = +yaw,
  +roll = the roll lane's sign. Validated headlessly: straight ⇒ 0;
  flat circle yaw = g·Gl/v to 4e-4 °/s; constant-roll extraction to
  1e-3 °/s; default layout's extracted roll tracks the baked lane
  within 0.09 °/s.
- **UI: toolbar "Rates" toggle** (persisted per-user in AppSettings) —
  thin DASHED read-only curves over the plot, on the ROLL lane's value
  scale (all three rates are °/s), each colored like the lane it
  answers to (pitch~Gv green, yaw~Gl blue, roll~roll orange). The
  auto-scale includes them when visible so nothing clips (rates spike
  legitimately where vSafe floors near stalls). No hit-testing, no
  legend on the plot — semantics live in Help (D42's cleanliness rule).

## D46. Smoothing tool (v8.0, Phase 0 item 5)
- **M ("smoothSel", rebindable) = one smoothing pass** over the selected
  nodes: each selected INTERIOR node's value relaxes toward the
  TIME-WEIGHTED line between its two lane neighbors by the strength
  setting (Options, 0.05–1, default 0.5, ↺). Strength 1 lands exactly
  on the chord; repeated passes converge the selection onto the line
  through its fixed boundary — same "toward the chord" philosophy as F.
- **Values only**: node times, handles and lane endpoints never move
  (time-smoothing would silently re-time the ride; handles keep their
  authored shapes and straight-locks re-enforce anyway). New values are
  computed from the PRE-pass state so results don't depend on node
  order. Pure math in BezierBaker.smoothValues — headless-tested
  (exact chord at strength 1, order independence, endpoint immunity,
  convergence, t/handle immutability).
- One undo snapshot per press (each pass separately undoable).

## D47. Resulting dropdown, coaster redo, smoothing tool removed (v8.1)
User feedback round on the v8.0 batch:
- **Roll dropped from the resulting overlay**: force-mode roll geometry
  follows the authored roll lane's shape, so the derived curve added
  nothing (validated to 0.09 °/s in v8.0 — it literally retraced the
  lane). It returns naturally when geo/analysis passes make resulting
  roll differ from authored roll. `bodyRates` still computes all three
  (cheap; the tests use roll as a correctness probe).
- **"Rates" button → "Resulting" MenuButton dropdown** with per-curve
  CheckMenuItems (pitch rate, yaw rate), each persisted per-user
  (`resPitch`/`resYaw`; the old `showRates` key is abandoned in place —
  stale properties are harmless). Room for the Phase-1 analysis curves
  (true Gv/Gl, longitudinal G) to join the list.
- **Coaster editor has REDO: Ctrl+Shift+Z** ("redo", rebindable),
  mirroring the menu-local sessions ("redo everywhere Ctrl+Z undoes").
  Implementation: second snapshot stack; undo pushes the CURRENT state
  onto it, redo pushes current back onto undo (directly — not via
  pushUndo); every fresh edit clears the redo branch inside pushUndo(),
  the single choke point all edits already funnel through. The old
  "redo (Ctrl+Y)" roadmap note is superseded (Ctrl+Shift+Z matches the
  menu-local convention; rebind if Ctrl+Y is preferred).
- **Smoothing tool REMOVED entirely** (was D46, shipped v8.0): user
  verdict — the design method shouldn't need it, and relying on it
  would be bad practice (forces should be authored deliberately, not
  noise-reduced). BezierBaker.smoothValues, the M binding, the Options
  strength row and the settings keys are all gone; TESTING #10 and
  manual step 36 retired. D46 stays in this log as history.

## D48. Ortho views, orbit gestures, track markers + insert — Phase 0 complete (v8.2)
- **"Resulting ▾" is a plain Button + ContextMenu**, not a MenuButton:
  MenuButton renders a separate .arrow-button region that broke the flat
  toolbar-button styling — a Button with the ▾ glyph in its own label
  keeps the dropdown affordance "in the same space" as the other
  buttons (user request). Same CheckMenuItems inside.
- **Ortho top/side views are PSEUDO-orthographic** (Phase 0 item 4):
  JavaFX's ParallelCamera is unusable for 3D content (pixel-unit view
  volume, no depth control), so ortho = the same PerspectiveCamera at a
  1° FOV moved ~72x farther out, preserving apparent size
  (2·d·tan(fov/2) held constant). Far clip 5000 → 200000 while ortho.
  UX: `T` = top view (KEEPS current yaw — a rotated plan is
  legitimate), `Shift+T` = side elevation (yaw 0, pitch 0: the world
  X–Y plane; rides start along +X), both rebindable. Same key again
  restores the saved perspective pose; ORBITING away instead keeps the
  current angles and just restores the lens (distance scaled back, so
  no size jump). Scroll in ortho zooms MULTIPLICATIVELY (the additive
  step + its −1500 m clamp would wreck a camera sitting 4 km out).
  Guards: entering fly/POV exits ortho first (sane pose handoff); the
  Options FOV slider skips orbitCam while the 1° lens is active.
- **C+click = orbit recenter** (item 4), aliasing Shift+click (which
  stays): C tracked like the S slide key via scene filters. FVD++
  muscle memory.
- **Middle-drag orbits** (item 4) alongside primary-drag — CAD muscle
  memory now, and it frees primary-drag for viewport editing part 2
  later. Secondary stays excluded (the fly-exit stale-anchor jump).
- **On-track node markers** (item 6, READ-ONLY): one 0.22 m sphere per
  ACTIVE-lane node, on the spine like the ties, lane-colored with the
  selection in white; Options toggle, default on. Rebuilt from a
  signature check on every graph redraw (lane/selection/samples/times)
  so the scene graph only churns when something relevant changed —
  redraw fires per mouse-move and naive rebuilding would thrash.
- **Ctrl+click the track = insert a node at that ride time** (item 6):
  the screen-space nearest-sample search was extracted from the pivot
  snapping (pickTrackSample, same 25 px tube + behind-camera filter)
  and feeds insertNodeOnCurve — the 3D twin of clicking the graph
  curve, identical split/gap/selection semantics, one undo step.
- **Phase 0 is complete** (item 5 shipped then removed by D47).

## D49. Ortho view polish before Phase 1 (v8.2 cont.)
User feel-pass on the fresh ortho views:
- **Top view is always grid-aligned**: yaw snaps to the nearest 90° on
  entry (D48 kept the current yaw; a skewed plan view read as broken
  against the square grid). Nearest-90 rather than always-0 so the
  rough orientation you approached with survives.
- **Side view centers on the selected node** (its spine point) when one
  is selected; otherwise the pivot stays. To make the centered node
  findable, SELECTED track markers are now larger as well as white
  (0.32 vs 0.22 m radius).
- **Right-drag PANS inside ortho views** without exiting them: grab
  style (world follows the mouse), scaled from the ortho lens's
  world-units-per-pixel, moving the orbit pivot in the view plane; the
  grid's LOD dense region follows. While a snapped view is active,
  right-click therefore does NOT hand off to fly (the press is eaten as
  the pan anchor) — exit ortho first (T / orbit away), then right-click
  flies as usual. Primary/middle drags still rotate-and-exit by design.

## D50. Phase 1 milestone A: document model, three-pass pipeline, FVDD3 (v9.0)
The overhaul's foundation, delivered feature-neutral (the app looks and
behaves identically; the architecture underneath is the SECTIONS-DESIGN
one). New pure-core files: Quat, TrackPose, Section (sealed) +
ForceSection, TrackPiece, Coaster, TrackCompiler, DesignIO.
- **The document (Coaster)** owns what physics consumes: global v0 +
  friction, pieces (placement origin + heading yaw) holding sections,
  and the Phase-3 SpeedRegion model slot. The single ForceSection's lane
  lists are THE SAME OBJECTS the graph editor mutates (shared by
  reference — zero editor churn, loads copy INTO them via
  DesignIO.rebindLanes). Cosmetics (heart/gauge/ties/colors) stay
  UI-side deliberately, traveling through DesignIO.Cosmetics at I/O —
  they move into the document with the milestone-B split.
- **TrackCompiler = the three passes** (§1): passG per section (force
  sections run the UNCHANGED bake+RK4 at v0 = vDesign, energy referenced
  to the section's own entry height, seeded from the entry pose), passD
  global true speed from energy vs the global start, passA resulting
  rates. For today's document shape the passes are arranged to add ZERO
  arithmetic — compile() output is bit-identical to the old direct call.
  Section chaining re-bases t/s and drops the duplicated boundary
  sample; a new PIECE keeps its first sample (that skip-first bug was
  caught by the piece-independence gate while writing it).
- **Quaternions live at the MODEL level** (TrackPose.orient, piece yaw,
  section exit poses via Quat.fromFrame), NOT inside the force-section
  integrator, which keeps stepping T/U as vectors. The spec's
  "Pass G integrates orientation as a quaternion" (§2) conflicts with
  its own §1 "Phase 1 must reproduce current output bit-for-bit" — the
  gate wins; re-discretizing orientation would shift every float. Geo
  sections (Phase 2) integrate quaternions natively. Identity paths are
  float-exact by construction (IDENTITY.rotate adds literal zeros;
  aboutY(0) == IDENTITY) — the bit-for-bit gate rides on this.
- **FVDD3 written, FVDD3/2/1 read, all in DesignIO** — save/load is
  finally out of FvdDesigner and headlessly testable (TESTING #4's
  old wish). Format: global lines (v0 + cosmetics, same shapes as
  FVDD2), then `piece x y z yaw` blocks holding
  `section force vDesign domain reversed` blocks holding the same
  lane/n lines (8th field straight-lock). Unknown-line and
  extra-field rules unchanged. Legacy flat files load as one piece at
  their `start` (default (0,21,0) — what a fresh session's spinners
  did to them anyway) holding one force section with vDesign = their
  v0 (default 26); FVDD1 handle conversion moved along.
- **Gates all passed** (scratch Phase1Gate, see TESTING #11): default
  document compiles BIT-IDENTICAL to the old pipeline (raw double
  bits, every field of all 1347 samples); trueV == Sample.v bitwise;
  FVDD3 save→load→save byte-identical; FVDD2/1 fixtures load right;
  editing piece A leaves piece B's world geometry bitwise unchanged;
  changing global v0 leaves ALL geometry bitwise unchanged (shape
  depends on vDesign only — §0 holds).
- **Still open in Phase 1 (milestone B):** the mechanical FvdDesigner
  split (GraphEditor / Viewport3D / Dialogs / shell), section-aware
  graph strips, document-level undo (settings included or not — needs
  a user call: today heart/gauge/v0 stay menu-local), and the
  section-insertion UI that makes multi-section real. The MODEL and
  pipeline already handle multi-section/multi-piece (gated above);
  only the UI is single-section.

## D51. Document undo widened, file lifecycle: New / Save / Save As / title (v9.1)
User call on the milestone-A question, plus a workflow feature round:
- **Document undo now includes heart, gauge, start x/y/z and start
  speed** — the rule is the user's own: "everything that changes the
  track path, not just aesthetics." Tie spacing and rail colors remain
  cosmetic (menu-local undo only, but they DO mark the file dirty).
  Implementation: undo snapshots are a DocState record (deep-copied
  lanes + the four path settings); the Coaster-menu spinners for path
  values are wired through wireDocSpinner, which pushes a document
  snapshot BEFORE applying (the field still holds the old value at
  listener entry). Menu-local Ctrl+Z replays skip the document push
  (menuApplying guard) so the two histories never double-record; undo/
  redo application suppresses the listeners entirely (docApplying) and
  writes fields + spinner displays directly. Granularity: one document
  step per spinner CLICK (matches the old menu-local feel); a node-0
  dialog Apply that changes BOTH start speed and node values lands as
  two steps (speed, then nodes) — accepted, each step is coherent.
  Undoing a LOAD now restores the pre-load settings too (the pre-load
  snapshot carries them), which the old lanes-only undo silently lost.
- **File > New… (Ctrl+N, rebindable "newfile")**: if dirty, offers
  Save / Don't Save / Cancel (Save on a never-saved file runs the
  chooser; cancelling the chooser aborts the New). A fresh document is
  one 1 s force/time section — Gv 1 g, Gl 0, roll 0, default handles —
  at (0,21,0), v0 26, default cosmetics, EMPTY undo history, auto-fit
  view, "Untitled" title. File > Load now runs the same unsaved-changes
  gate (same data-loss risk; flagged as a judgment call).
- **The window title is the document**: "name.fvd — FVD Coaster
  Designer", with * after the name while unsaved ("Untitled" before the
  first save). Dirty is set at every document mutation choke point
  (pushUndo, the menu-spinner wirings, rail mode/color/gradient
  handlers, undo/redo application — content differs from disk either
  way) and cleared on save/load/new. Deliberately NOT a compare-to-
  saved-state check — cheap and predictable beats clever here.
- **Save vs Save As**: Ctrl+S / File > Save writes straight to the open
  file (no chooser) once one exists; Save As… always asks. The old
  always-ask Save behavior is gone — that's what "the file that is
  open" in the title implies.

## D52. FvdDesigner split part 1: GraphEditor.java (v9.2)
First slice of the milestone-B mechanical split (§8): the ~1,760-line
graph editor moved from an inner class to its own top-level
package-private class. FvdDesigner drops 4,167 → 2,403 lines.
- **Method: scripted verbatim move, not a rewrite.** A Python pass
  extracted the class by brace-walking, de-indented it, and made every
  outer reference explicit through a `final FvdDesigner app` backref —
  instance members/methods as `app.x`, outer statics as
  `FvdDesigner.X` — using a curated symbol list with a
  no-prefix-after-dot guard (`.sub(` and comment prose were the traps;
  `editor`/`sub` matches turned out to be false positives and were
  excluded). Pure-comment lines were left untouched.
- **Verification for a change the compiler half-checks:** compile-clean
  proves no UNDER-prefixing (a missed outer ref cannot resolve), but
  not over-prefixing onto a shadowing local — a post-pass grep asserted
  zero bare occurrences of the curated names and zero shadowing
  declarations inside the new file. All headless suites re-passed
  (they don't execute JavaFX, but they catch collateral core damage).
  The REAL test is the full manual pass: any graph-editing behavior
  drift from v9.1 is a split bug, not a feature.
- Outer members the editor reaches went private → package-private
  (lanes, settings, coaster, stage, ripple/slide/pov state,
  heartOffset, spinners it writes, lastSamples/resultRates,
  rebuildTrack, markDirty, snapshot/apply, refreshTrackMarkers,
  INTEGRATE_DT, clamp). DocState was already package-visible.
- **Remaining split (next batch): Viewport3D.java and Dialogs.java.**
  Deliberately NOT bundled here — the 3D input/camera code is the
  least-verified area in the app (POV affine, capture handoffs); it
  gets its own isolated diff so a regression bisects to one commit.

## D53. Side view is tangent to the track at the selected node (v9.3)
User report read as a regression but was not one: Shift+T's side view
was ALWAYS the fixed world X–Y elevation (yaw 0, D48) — it merely
LOOKED track-aligned at the start node because rides launch along +X,
and the top view's D49 grid snap was unrelated. What the user expects
(and FVD++-style elevation editing wants) is a side view TANGENT to
the track where you are working:
- With a node selected, Shift+T now yaws the lens PERPENDICULAR to the
  track's horizontal heading at that node (atan2 on the sample's T),
  so the track runs across the screen at the centered point — a true
  local elevation. Of the two perpendicular sides, the one nearer the
  current camera yaw wins (no surprise 180° flips).
- No selection, or a locally vertical tangent (|T.xz| ~ 0): the old
  world X–Y elevation stands.
- Top view stays grid-snapped (D49) — that lock was the user's own
  request and was never the culprit.
- **A second Shift+T RE-TANGENTS to the current selection** instead of
  exiting (follow-up user spec): select another node — or pan away —
  and Shift+T re-aims without leaving ortho. Consequence: the side view
  has no same-key exit; leave it by orbiting away (keeps angles,
  restores the lens) or via T, T. T-in-top still restores the saved
  perspective pose.

## D54. FvdDesigner split complete: Viewport3D.java + Dialogs.java (v9.4)
Second and final slice of the §8 mechanical split, same scripted recipe
as D52 (verbatim move, `app` backref, curated prefixing, no-prefix-
after-dot, comments untouched). FvdDesigner is now a 1,118-line SHELL:
document + file lifecycle, key dispatch, menu bar + toolbar, layout,
menu-local undo machinery, save/load/new/export.
- **Viewport3D.java (~900 lines)**: cameras (orbit/fly/POV + the
  pseudo-ortho views), all mouse/scroll handlers and both animation
  timers (moved inside its constructor, which builds the SubScene the
  shell lays out), rail/tie/heartline meshes, gradient texture, ground
  grid + LOD, track picking, node markers, and rebuildTrack — the
  pipeline entry stays name-stable via shell delegates (rebuildTrack /
  refreshTrackMarkers / togglePov), so no call sites moved in
  GraphEditor or the menus. lastSamples/resultRates stay on the shell
  (editor, exporter and viewport all share them).
- **Dialogs.java (~460 lines)**: Help, Shortcuts, Rail colors, Options
  + the option-row helpers (sliders/spinners/reset/1-2-5 walkers) and
  comboString. Menu-local undo machinery STAYS on the shell — the
  Coaster menu and the doc/menu spinner wirings use it too; dialogs
  reach it through app.
- **The audit caught real bugs the compiler alone would not have**:
  (a) a stale target/classes made the first post-split compile pass
  VACUOUSLY green — after `rm -rf target/classes` the honest error list
  appeared (always rebuild from scratch when verifying moves);
  (b) the brace-walker returned early on wireOptionSlider's THREE-line
  signature, splitting the method across both files — compile caught
  it; the lesson is baked into this log because the recipe will be
  reused (multi-line signatures need the walker to wait for the first
  '{'). Post-fix: zero bare curated names, zero shadowing declarations,
  multi-line-signature methods verified whole, from-scratch compile +
  all headless suites green (bit-for-bit gate included).
- File sizes after the full split: FvdDesigner 1,118 · GraphEditor
  1,782 · Viewport3D ~900 · Dialogs ~460 · pure core untouched.

## D55. Section-aware graph + '+' add-section — PHASE 1 COMPLETE (v9.5)
The last two Phase-1 items, to the user's UX spec: sections render as
strips on ONE shared time axis, inactive ones greyed (alpha 0.13, all
three lanes) with boundary lines between; clicking a greyed strip
activates it; a dashed vertical line with a circled '+' past the ride
end opens the add-section menu (today: "force section, 1 s hold" —
disabled placeholders advertise Phase 2's geo/bridge/curve and
ATTACH-FLOATING-PIECE entries; the user also wants a PIECE SELECTOR
eventually, one piece in the editor at a time — recorded in
SECTIONS-DESIGN §8).
- **All editor math went SECTION-LOCAL** behind two helpers: ltToX/xToLt
  add/remove the active section's global start (tOff). With one section
  tOff = 0 and every code path is IDENTITY with the pre-section editor —
  that is the compatibility argument, plus the bit-for-bit gate which
  still passes untouched. Global stays global: grid ticks, zoom/pan,
  POV marker, resulting rates, strips, boundaries, '+'.
- **The old maxT() split in two**: maxT() = whole-piece duration
  (domain, auto-fit); sectionEnd() = the ACTIVE section's own ride end
  (dotted holds, insert guard, hover walk) — exactly the old semantics,
  now per section.
- **app.lanes became a mutable pointer** at the active section's lane
  lists; section switches re-point it, so GraphEditor's hundreds of
  lane references needed no change. Loads/new re-point wholesale
  (editor.resetSections()); DesignIO.rebindLanes is gone.
- **Document undo covers sections**: DocState snapshots EVERY section
  (lanes + vDesign) plus which one was active — adding a section, then
  Ctrl+Z, removes it; redo re-adds. The node-0 start-speed dialog field
  only appears in section 0 (the ride entry); the Start-speed spinner
  writes v0 + section 0's vDesign, and LATER sections keep their
  creation-time vDesign (§0: changing entry speed must never reshape
  downstream geometry — their design-vs-true gap is what Phase 2's
  influence/become-real tools are for).
- **New section defaults**: 1 s, holds the previous section's final
  lane values (Coaster.continuation, pure + gated), vDesign = design
  speed at the piece's current end (from the compiled samples).
- **On-track markers and 3D Ctrl+click-insert are section-aware**:
  markers offset by the active section's start; a track click routes to
  the section containing that ride time, activates it, inserts locally.
- **Gates extended** (Phase1Gate #9): continuation holds end values;
  chained compile is monotonic in t with no boundary jump and
  continuous frames; editing section B leaves section A BITWISE
  unchanged; the canonical FVDD3 round-trip now uses a two-section
  document. Everything else still green, bit-for-bit included.

## D56. Section polish round (v9.6)
User hand-test feedback on v9.5, all three items:
- **The '+' dodges nodes**: the ride's last node sits exactly on the
  add-section line and the button covered it. If a node (any lane of
  the active section) is within 24 px of the button's home spot (top of
  the plot), the button drops to mid-plot height — unless a node is
  even closer there (nearestNodePx picks the clearer of the two).
- **Inactive strips brightened**: alpha 0.13 → 0.20, named
  INACTIVE_SECTION_ALPHA in GraphEditor for future feel passes (the
  active section's inactive LANES stay at 0.25 — strips remain a step
  fainter, deliberately).
- **B on a section-EDGE node = one-time cross-boundary slope match**
  (also a "Match slope across boundary" button in the node's
  double-click dialog): the start node's out-handle takes the previous
  section's END slope; the end node's in-handle takes the next
  section's START slope. A cubic's endpoint slope IS its handle-line
  slope, so the match is exact — computed from the adjacent section's
  handle directly, no polyline differencing. NOT a lock, per the user:
  edit freely afterwards (and a straight-locked segment's enforcement
  wins over it). Interior nodes keep the classic smooth/broken B;
  edge nodes with no adjacent section (ride start/end) no-op.

## D57. '+' dodge troubleshoot: the mid-plot spot is never empty (v9.7)
The D56 dodge compared the top spot against mid-plot and moved only if
mid was CLEARER — but mid-plot is essentially always occupied at the
ride-end x: the other lanes' value-0 end nodes map exactly there
(Gl 0 on its ±1 scale and roll 0 on ±150 both hit plot centre). So the
comparison failed and the button never moved. Two fixes:
- **Ladder dodge**: candidate heights top → 30% → 50% → 70% → bottom;
  the first spot with ≥26 px clearance from every active-section node
  wins, else the clearest of the five.
- **Nodes outrank the '+' in the press order** (the real editability
  guarantee): the button's hit test moved from before hitNode to the
  nothing-else-hit branch, so a node overlapping the button is always
  grabbable; the button's exposed edge still opens the menu.
Lesson recorded because it generalizes: "move it to the centre" style
dodges must account for value-0 curves resting at scale centres.

## D58. Phase 2 begins: GEO sections — direct body rates (v10.0)
The first Phase-2 section type (SECTIONS-DESIGN §3): lanes 0/1/2 =
pitch / yaw / roll rate, all °/s, integrated DIRECTLY — no solver, no
force inversion. Gravity does not bend a geo section; it only shows in
the felt forces reported downstream.
- **LaneSection abstract base** slid under the sealed Section:
  ForceSection and GeoSection both extend it (lanes + vDesign +
  reversed live there), so the graph editor, undo snapshots (SecState
  grew a `geo` flag), DesignIO and the compiler handle both through one
  type. Non-lane Phase-2 modes (bridge, straight/curve, external) will
  implement Section directly.
- **GeoIntegrator is quaternion-native** (the SECTIONS-DESIGN §2
  amendment): RK4 on (pos, q), q' = ½·(0,Ω)⊗q, Ω = yaw·U − pitch·R +
  roll·T, normalized per step and stage; the exit pose carries the
  integrated q itself. The force integrator's gated T/U vector path is
  untouched (bit-for-bit gate re-passed). Quat grew add/scale; the
  integrator reuses FVDIntegrator's interpAt/speedAt (made
  package-visible, zero arithmetic change). KEYFRAME SLOT REUSE: geo
  lanes ride BezierBaker.bake's FVDIntegrator.Keyframe output with Gv
  slot = pitch, Gl = yaw — mapping documented in GeoIntegrator only.
- **Samples record FELT G at design speed** (Gv = ω_pitch·v/g + U.y,
  Gl likewise) so rail gradient coloring, stats and force-continuation
  holds stay honest over geo track. vDesign still shapes TIME-domain
  geo geometry (pos' = T·v at the design profile) — the §0 freeze
  applies identically; a future DISTANCE domain (deg/m) removes speed
  from geo shape entirely.
- **'+' menu**: "Add geo section (1 s straight)" — zero rates, NOT a
  hold of the previous section's end rates (holding rates keeps its
  curvature going, which surprises more than it helps; cheap to flip in
  GeoSection.straight). Force-after-geo holds the FELT end state: the
  compiled end sample's Gv/Gl + the geo roll lane's final value
  (Coaster.holdSection; continuation(force) now delegates to it).
- **Editor is mode-aware, not duplicated**: laneName/laneUnit/
  defMin/defMax/snapVal switch on the active section's mode (geo
  defaults ±60 °/s pitch/yaw, ±150 roll; all three lanes snap by the
  roll step); lane buttons relabel Pitch/Yaw/Roll (colors kept — pitch
  answers the Gv lane's green like the Resulting overlay always has);
  the 1 g reference line draws in force mode only. A neighboring strip
  of the OTHER mode draws on its own fitted scale (g vs °/s don't
  share axes; same-mode strips keep the shared scale so curves join
  visually). B's boundary slope match and the dialog button no-op /
  hide at cross-mode boundaries (a g/s slope means nothing in °/s²).
- **FVDD3 carries `section geo`** — same format version (unknown
  section MODES are structural, not skippable lines; an old app
  refusing the load with "unknown section mode" is the right failure).
- **Gate: scratch Phase2Gate, 28 checks, all green** — bit-for-bit
  force baseline; constant-rate = exact angle (asserted against the
  LAST SAMPLE's t: the loop records at accumulated t ≤ duration, so a
  2 s section's final sample can land at 1.99 s — the force
  integrator's cadence, now written down); pure pitch stays planar /
  pure roll stays straight; +yaw chirality equals force +Gl; rate
  integration speed-independent; authoring the default layout's
  RESULTING rates as geo reproduces the force geometry to 2 mm / 407 m
  (sign-convention canary); mixed force→geo→force chain continuous
  with the force prefix bitwise identical; geo edits and v0 changes
  move nothing upstream (§0); FVDD3 round-trip byte-identical with a
  geo section aboard; felt-G spot checks.

## D59. Geo/section UX round: unified view, badges, boundary return (v10.1)
User feedback batch on v10.0, seven items:
- **The '+' geo default flipped to a 1 s HOLD of the end rates** (D58
  shipped "straight"/zero rates; user wants seamless transitions like
  force-after-force). Coaster.continuation went same-mode generic
  (LaneSection in, matching type out); geo-after-FORCE holds the
  resulting end pitch/yaw (bodyRates' last row) + the authored roll;
  force-after-geo keeps holding the felt end forces.
  GeoSection.straight stays as a utility.
- **UNIFIED GRAPH VIEW**: the plot always shows ONE mode's quantities —
  the active section's. Same-mode strips draw their authored lanes
  (shared scales, unchanged); an other-mode strip draws what it DOES in
  the active units, derived from the compiled track (geo view of force
  strips = resulting pitch/yaw/roll rates; force view of geo strips =
  felt Gv/Gl + resulting roll — one `derivedAt(i, lane)` chooser feeds
  strips AND boundary matching). This REPLACED D58's "own fitted scale"
  cross-mode strips — with everything in one unit system the shared
  axes just work, and curves join up visually across every boundary.
  The "Resulting" overlays draw only over the ACTIVE section now (the
  strips are the derived picture everywhere else) and keep no fill.
- **B on a section-edge node = RETURN TO THE BOUNDARY, value AND
  slope** (was slope only, same-mode only): the node's value snaps to
  the neighbor's boundary value and its handle takes the boundary
  slope. Same-mode = exact from the authored lane (a lane holding past
  its last node is FLAT there — new, previously used the stale handle
  slope); cross-mode = from the resulting curves via derivedAt (slope
  by finite difference over ~0.05 s on the neighbor's side); the roll
  lane is °/s in both modes and always matches exactly. Dialog button
  renamed "Match boundary (value + slope)", shown at every edge.
- **GEO LANE PALETTE** (user spec): pitch teal rgb(80,210,200), yaw
  yellow rgb(245,215,80), roll keeps orange. GEO_LANE_COLORS in
  FvdDesigner; GraphEditor.laneColor(lane) is THE accessor — curves,
  fills, nodes, guides, axis labels, resulting overlays, lane buttons
  (setLaneButtonMode retints) and the 3D node markers (color joined
  the marker signature) all route through it. Yaw's yellow knowingly
  sits near TIME_COLOR — the user picked it.
- **ZERO-LINE FILL** under every lane curve (LANE_FILL_ALPHA 0.13,
  inherits the strip/lane globalAlpha so dimmed curves get fainter
  fills): active lanes (bake-polyline outline under the bezier
  stroke), same-mode strips incl. dotted holds, derived strips.
  Resulting overlays excluded by design.
- **SECTION BADGES = type indicator + per-section menu** (user's
  "share the UI" suggestion): a small force/geo tag at the top-left of
  every strip (skipped when the strip is too narrow on screen), active
  section's brighter. Click → menu: disabled info line ("Geo section 2
  — design 24.0 m/s") + "Delete section" (disabled at one section;
  downstream sections re-chain from the new end, their local shapes
  §0-untouched; deleting the active one activates its predecessor; one
  undo step). Press rank: nodes/knobs > '+' > badges > curve insert
  (D57's lesson).
- **Context menus dismiss on graph clicks**: JavaFX auto-hide covers
  clicks everywhere EXCEPT this canvas, so the editor remembers the
  open menu ('+' or badge) and the next canvas press just hides it.
- **Click-insert now chains into the drag** (user: Ctrl+press-drag on
  the curve felt dead): pressing on the curve inserts the node AND
  starts a node drag in the same gesture — Ctrl held from the press
  snaps it as usual. The insert's undo snapshot covers the whole
  gesture (pendingSnapshot stays null), so Ctrl+Z removes node + move
  as one step, matching the 3D Ctrl+click contract.

## D60. Snap/boundary UX round 2 (v10.2)
User feedback batch on v10.1, eight items:
- **The section end is a Ctrl-snap point + a drag WALL.** Snap: while
  dragging with Ctrl, the end of the section (as established by the
  OTHER lanes — "where another graph already is") competes with the
  grid point nearest the cursor, and grid points within HALF A STEP of
  the end are ABSORBED by it — landing "at the end" always means
  exactly at the end, never a grid point shy of it (user request).
  Wall: a lane's LAST node cannot drag past that end at all — unless
  it BEGAN the gesture already there. Extending a section is therefore
  always two deliberate gestures: drag to the end, release, grab again
  and pull (endLimitFor; interior nodes are already neighbor-clamped;
  the exemption is evaluated at gesture start, so the wall never
  "melts" mid-drag). Arrow nudges and the node dialog bypass the wall
  deliberately (typed/stepped input is already deliberate).
- **Snap axis references are per-axis Options toggles** (AppSettings
  snapTAbsolute / snapVRelative, both default OFF = the historical
  behavior): time snaps the transition Δt from the previous node OR
  the absolute ride-time grid the axis ticks show; value snaps the
  absolute grid OR Δ from the previous node. Ctrl-drags and
  Ctrl+arrow nudges honor them; node 0 falls back to absolute (no
  previous node).
- **B at a boundary is now ONE press** (was several): boundaryState
  sampled the compiled curves AT the boundary, but (a) bodyRates'
  central differences SMEAR a boundary discontinuity across ±2
  samples — the "small angled straight line" the user spotted at
  seams — and (b) the seam's sample index shifts against authored
  durations by the integrator cadence, so the read often landed one
  sample into the ACTIVE section, returning the node's own value:
  each press only converged partway. Cross-mode targets now sample ~3
  samples INSIDE the neighbor (rateIdxAt binary-searches by time —
  never compute sample indices as t/dt) with the slope over the next
  5. Residual: matching the END node against the NEXT section is
  still iterative when modes differ — editing this section moves the
  next section's entry pose and therefore its felt values; it
  converges fast but is inherently a moving target (same-mode stays
  exact, and the common prev-side match is exact one-press).
- **The seam smear is also TRIMMED out of drawing** (SEAM_TRIM =
  2.5·dt): derived strips and the Resulting overlays drop that band
  at their ends, so boundaries read as direct connections, not tiny
  angled ramps.
- **Clicking another strip ALWAYS activates it**: the strip check now
  runs BEFORE the dimmed-lane hit test. Root cause: the active
  section's lanes hold their end values across the whole time axis,
  so hitOtherLane's curve test (evalAt holds ends) matched clicks in
  OTHER strips whenever they lined up with a held value and stole the
  section switch as a lane switch.
- **TIME_COLOR is light purple** rgb(200,165,255) (user spec; was
  yellow since v7): "Time (s)" label, horizontal guide, POV marker.
  Yaw now owns yellow outright.
- **The Resulting overlays are mode-aware** (user items 7+8): over a
  FORCE section they stay resulting pitch/yaw rates on the roll
  lane's scale, but now wear the GEO palette (teal/yellow — they ARE
  rates); over a GEO section they become the felt VERTICAL/LATERAL G
  in the FORCE palette (green/blue), drawn on their own hidden scale
  (force defaults grown to the visible data — the geo lanes are °/s
  and can't host g; auto-fit skips them there). The dropdown entries
  retitle with the mode (setLaneButtonMode); the picks are shared.
  Rule of thumb shipped: a curve's color always names its QUANTITY's
  palette, whichever mode is active.

## D61. Shortcuts columns, Local/Global snap labels, hoverable Resulting (v10.3)
User batch on v10.2, four items:
- **Shortcuts dialog reorganized into TWO titled columns** (the flat
  list outgrew 1080p): ACTIONS grew a 4th CATEGORY field — "Graph
  editor" (12 actions, fills the left column) and View / File /
  History stacked right. Rebinding/reset/capture logic unchanged, one
  shared capture filter across the columns.
- **Snap axis toggles renamed to the user's mental model**: per-axis
  LOCAL/GLOBAL radio pairs in Options — "Horizontal snap (time)":
  local = Δt from the previous node (default), global = absolute
  ride-time grid; "Vertical snap (value)": local = Δ from the previous
  node, global = absolute values (default). Same two AppSettings
  booleans underneath (snapTAbsolute/snapVRelative), just resurfaced;
  ↺ restores each axis's default.
- **The "Resulting" dropdown became a hover-dismissed dark Popup of
  CheckBoxes**: it stays open while picks are clicked (toggle both
  without reopening) and closes 250 ms after the mouse leaves both
  the button and the popup (grace timer cancels on re-entry; autoHide
  kept as an outside-click backstop — Popup's default
  consumeAutoHidingEvents also makes a click on the button while open
  close it cleanly instead of re-toggling).
- **Resulting curves got their OWN right-edge axis + hover readout.**
  Enabled overlays share one RESULTING scale (they share units per
  mode: °/s over force sections, g over geo), fitted like the lane
  scales (defaults ±60 °/s / −1..4 g, 1-2-5 expanded, frozen during
  drags) and labeled down the RIGHT edge with a rotated title
  ("Resulting rate (°/s)" / "Resulting G (g)"); the right margin
  widens 14 → 48 px while shown (marginRight() replaced the constant
  everywhere). This REPLACES the old arrangement where force-mode
  overlays rode the roll lane's scale and expanded its axis — the
  roll lane no longer auto-fits them (TESTING #35 updated), and both
  modes now draw overlays identically. Hovering within a few px of an
  overlay shows a dot + its value in the curve's color ("pitch 23.4
  °/s", "Gv 1.82 g"); the active curve's insert dot keeps priority.

## D62. C1 discipline + boundary batch + popup race (v10.4)
User batch on v10.3, five items:
- **Shift+B = boundary return on ALL THREE lanes at once** (new
  rebindable action "matchAll"): with a section-edge node selected,
  every lane's edge node takes its own neighbor's boundary value and
  slope — identical per-lane math to B, one undo step.
  matchBoundary/boundaryState refactored to take the lane explicitly
  (applyBoundaryMatch is the shared no-undo core).
- **The Resulting popup could hide itself mid-hover** (user: "quickly
  press the dropdown then click an option"). Root cause: JavaFX only
  generates MOUSE_ENTERED from motion — when the popup materializes
  under an already-moving cursor, the popup never learns the mouse is
  inside, so the 250 ms exit-timer from LEAVING THE BUTTON fires and
  hides it while hovered (and the matching missed-exit means no
  re-arm either). Fix: trust the REAL pointer, not the event
  bookkeeping — the timer's finish asks Robot.getMousePosition() and
  RE-ARMS instead of hiding while the cursor sits inside the popup or
  button bounds; the popup also self-arms on show and any motion
  inside it stops the timer. Net effect: it closes only when the
  mouse is genuinely elsewhere for 250 ms.
- **Shift on a hovered resulting curve shows its numeric slope**
  (±2-sample central difference; "pitch −14.20 °/s/s" style), same
  gesture as the lane hover; the key filter redraw covers it.
- **Straight-lock polish**: the facing knobs of a locked segment are
  HIDDEN and unclickable (knobPos returns null — collinear control
  points make the cubic exactly the chord, so they truly do nothing).
  UNLOCKING now marks a joint broken when its two sides sit at
  different slopes (the state was a lie: a kinked "smooth" node);
  sides that still agree — the insert-on-the-line-then-unlock case —
  stay smooth. Tolerance 1e-6 relative.
- **Re-timing a segment no longer kinks smooth neighbors** (user:
  extending a section angled the previous node's handles apart).
  Cause: fraction-stored handles rescale dt with the segment while dv
  stays, so the slope on the re-timed side drifts from its partner.
  Fix: moveNode/moveGroupBy capture the affected SMOOTH INTERIOR
  handles' absolute dt+dv and restore them after the move by
  re-deriving the fraction (HandleKeep; dv rescales only if the
  fraction clamps, preserving at least the slope). Scope is
  deliberate: node 0, lane ends and BROKEN nodes keep the classic v6
  squish (the validated 2-node squish case is untouched); only
  smooth joints — where collinearity is the contract — go rigid.
  Boundary segments of group drags get the same treatment.

## D63. C1 revert, popup seam, LEVEL LOCK (v10.5)
User batch on v10.4, three items:
- **D62's C1-preserving re-timing is REVERTED in full** (user: "I
  still want the squish for all nodes"): HandleKeep and the
  capture/restore calls are gone; moveNode/moveGroupBy are back to
  their pre-v10.4 bodies, so re-timing rescales smooth handles with
  their segments again (and may kink them — accepted, preferred).
  The rest of D62 (Shift+B, popup cursor-check, hover slope,
  straight-lock knob hiding, honest unlock) stands.
- **The button→popup seam killed the Resulting menu**: the popup sat
  2 px below the button and the Robot cursor-check treated that strip
  as "outside". The popup now shows 1 px OVERLAPPED with the button
  and screenContains pads bounds by 6 px — the seam can never read as
  outside.
- **LEVEL LOCK (Shift+H) — the horizontal sibling of Shift+F** (user
  spec: "h shortcuts mirror the f shortcuts"). Keys re-dealt: plain H
  now LEVELS the selection onto the main node (Shift+H's old one-shot;
  single node = flatten handles, H's classic meaning); Shift+H
  level-LOCKS — the selection levels onto the main node, then every
  adjacent selected pair gets Node.levelOut. enforceStraightLocks
  became enforceLocks: a level segment pins its RIGHT node's value to
  the left's (so a run's height is its LEFTMOST node — dragging that
  one vertically moves the whole line, later nodes snap back onto it)
  and the slope-0 chord math flattens the facing handles; C1 rules and
  the malformed-file guard shared with straight locks (lockedOut()).
  Facing knobs hidden like straight locks (a level cubic IS the line);
  insert-on-locked carries levelOut across the split, so new nodes
  stay ON the line and stay draggable horizontally; delete merges keep
  the flag (left node owns it). Unlock via Shift+H on any endpoint,
  kinked joints break honestly (shared breakKinkedJoints). DIRECT
  SWAP: locking one way clears the other flag, so Shift+F on a level
  segment straight-locks it and Shift+H on a straight segment levels +
  level-locks it. Visual: the same twin 1 px lines as straight locks
  PLUS a translucent lane-color band between them (user's suggestion;
  straight stays fill-free so the two read differently). Saved as a
  9th field on `n` lines (same ignore-extra-fields ride as the 8th;
  missing = unlocked; round-trip gate re-passed).

## D64. Lock toggles refined: pair-priority, multi-F, two-ended level (v10.6)
User batch on v10.5, three items:
- **A level run's height steers from EITHER END** (was leftmost-only):
  enforcement still propagates left-to-right, but moveNode detects a
  move of a run's RIGHT end (segment in is level, segment out is not)
  and writes the new value back through the run before the pass.
  Interior nodes stay pinned to the line, exactly as before. Group
  drags of a partial run still resolve to the left end's value.
- **Lock toggles target the segments BETWEEN selected nodes** (the
  selectedPairs set): Shift+F/Shift+H with adjacent nodes selected
  add/convert/remove locking ONLY there — a lock that merely shares an
  endpoint with the selection is never disturbed (previously the
  "touches" unlock fired first and killed the neighbor instead of
  locking the new segment). All target segments already carrying that
  lock = unlock them; any other mix = lock (converting the other lock
  type). The classic single-endpoint unlock gesture survives when NO
  pair is selected. Shared plumbing: lockSelection/selectedPairs/
  unlockSegments (which also breaks kinked joints, D62).
- **Shift+F now locks 2+ nodes in series** like Shift+H: each selected
  adjacent pair chords its own segment (three nodes = two independent
  straight segments hinging at the middle node, not one long chord —
  matching how insert-on-a-lock already behaved).

## D65. Right-click re-deal + snapped inserts (v10.7)
User batch on v10.6, three items:
- **Right-click a NODE opens its editor dialog** (same as
  double-click; the node also becomes selected). Deletion moved to the
  Delete key exclusively — right-click-delete was v5 muscle memory the
  user chose to retire.
- **Right-click a SEGMENT opens its lock menu**: Unlocked / Straight /
  Horizontal radio picks ("Horizontal" is the user's name for the
  level lock in this menu), applied to that one segment with the same
  flags/enforcement as Shift+F / Shift+H (setSegmentLock; unlocking
  breaks kinked joints, D62). IMPLEMENTATION NOTE: right-DRAG pan can
  start anywhere, including on the curve — so the menu opens on a
  clean RELEASE (≤3 px travel, rClickSeg); any real drag cancels it
  and pans as ever. The hold-extension and the region before node 0
  have no segment and fall through to pan.
- **Ctrl snaps the insert dot in TIME** (and the click inserts there):
  hovering the curve with Ctrl held moves the dot to the snapped time
  — horizontal snap reference honored (Δt from the previous node, or
  the absolute grid) — while its VALUE keeps riding the curve
  (deliberately unsnapped, per user spec; the node lands exactly ON
  the line). The dot hides when the snapped spot violates the
  INSERT_GAP spacing (shared insertRoomAt, also used by curveHitT), so
  it still never shows where a click would refuse. Ctrl toggles the
  snap live without mouse motion (ctrlHover via the key filters, like
  the Shift slope toggle); the press path re-snaps from its own
  Ctrl state so hover and click can never disagree.

## D66. Full-ride Resulting overlays + Gv/Gl color swap (v10.8)
User batch on v10.7, two items:
- **The Resulting overlays stretch across the WHOLE ride** (they were
  active-section-only since v10.1 — the unified strips were meant to
  cover the rest, but the user wants the continuous derived picture).
  Same right-edge axis and vertical placement; updateResScale now fits
  the enabled curves over the VISIBLE TIME WINDOW across every
  section, "as if they were a lane of the edited graph" (user spec).
  The dashed path BREAKS over a SEAM_TRIM band at each interior
  section boundary (and the ride's first/last 25 ms) — bodyRates smear
  discontinuities there (D60), so the gap is deliberate and honest.
  Hover readout/slope works anywhere along the full curve outside
  those bands. The active mode still decides the QUANTITY (rates over
  force sections, felt G over geo) for the entire ride.
- **Vert G and Lat G swapped colors** (user pick): Gv is now BLUE
  rgb(120,170,255), Gl GREEN rgb(120,220,130) — LANE_COLORS entries
  swapped, everything downstream (curves, fills, buttons, markers,
  cross-mode strips, geo-mode Resulting overlays, help diagram)
  follows automatically via laneColor/overlayColor. Green had been
  Gv's color since v5; TESTING steps 35/41/42 updated.

## D67. Phase 2 continues: REVERSAL (v10.9)
The roadmap item after geo sections (SECTIONS-DESIGN §3): a section's
`reversed` flag (in the model + FVDD3 since Phase 1) now WORKS — the
train traverses the authored shape back-to-front.
- **Canonical generation + yaw placement.** A reversed section's shape
  is integrated in the AUTHORED direction from a canonical pose
  (identity heading at x=z=0, ENTRY height — energy is height-relative
  but float rounding is not, and the entry-height choice makes the
  reversed shape BITWISE equal to a forward run at that height,
  gated). The samples are then reversed (T → −T, R → −R, U unchanged,
  t/s re-based to traversal order) and the whole line is yawed about
  world-Y + translated so its first sample sits exactly on the entry
  position with the entry's HORIZONTAL heading. Yaw-only is
  deliberate: a force shape is only gravity-honest in the orientation
  it was authored for, so a pitched/banked entry cannot be fully
  matched — the seam shows the mismatch honestly (end the upstream
  section level for a clean join). vDesign stays the
  AUTHORED-direction entry speed (the train's exit side).
- **Sign corrections vs the §3 spec** (derived + gated): felt Gl
  NEGATES (the physical force vector is traversal-invariant, R flips —
  one flip). The roll RATE does NOT flip — T flips AND the
  bank-change-per-second flips, and the two cancel; §3's "roll sign
  flips in readouts" was wrong and SECTIONS-DESIGN is annotated. Gv,
  U.y and the design-speed profile are traversal-invariant (bitwise).
- **The editor treats reversed sections as derived data**: their
  authored lanes read against the chain (wrong end, Gl sign), so
  inactive strips, boundaryState (B matching) and the '+' hold all
  route through the compiled samples/rates — the same paths cross-mode
  sections already use. The ACTIVE reversed section still edits its
  authored lanes normally ("original lanes stay editable", §3).
- **UI**: the badge menu gains a "Reversed" check (one undo step;
  SecState now carries the flag so undo/redo restores it); the badge
  label wears a ◀. FVDD3 needed nothing — reversed has been the
  section line's 5th token since D50.
- **Gates** (TESTING #13): reversed vs forward point sets
  rigid-motion identical (pairwise distances); U.y/v/Gv bitwise, Gl
  bitwise-negated; entry lands exactly on the entry pose with exact
  horizontal heading; t/s ascend; chained force+reversed compile
  continuous; +50 °/s reversed geo roll reads +50 (invariance).

## D68. STRAIGHT/CURVE sections + the element strip widget (v11.0)
The next Phase-2 mode (SECTIONS-DESIGN §2/§3): the first NON-LANE
section. The designer authors an ELEMENT LIST — straights (length) and
circular arcs (radius + signed angle + direction) — the classic NL2
way to build stations, brake runs and lift approaches. User calls made
up front: the editing surface is a STRIP WIDGET REPLACING THE LANES
(not a dialog-first or viewport-first flow), and transition SMOOTHING
ships from day one.
- **Model.** `StraightCurveSection implements Section` directly —
  `LaneSection` stays the base of the two lane modes only. Elements
  are mutable (editor edits in place; undo deep-copies via
  `Element.copy`). Always DISTANCE domain: curvature is authored per
  meter, so the SHAPE is fully speed-independent; vDesign only maps it
  onto ride time. `Section` grew `vDesign(double)`/`reversed(boolean)`
  setters so the editor and the Start-speed spinner write through the
  interface (`Coaster.soleSection` returns `Section` now).
- **Geometry = a curvature-vector profile over arc length.** Inside an
  element, Ω/m = κ·(cos(dir)·U − sin(dir)·R) with κ = sign(angle)/R —
  that axis is invariant under its own rotation, so each element is an
  EXACT circle/line (gated to 1e-7). dir 0 = flat turn (+angle =
  rider-right, matching +Gl chirality), 90 = pull-up, negative angle
  mirrors. ScIntegrator RK4s (pos, quat, s) in TIME (downstream
  indexes uniform ride time) with v algebraic from energy — the
  s-profile makes shape independent of the speed profile anyway.
- **Smoothing = sine-eased curvature blend, centered on the boundary,
  stored per element as `smoothIn` (entry transition; element 0's
  entry is the section seam and stays sharp).** The symmetric ease
  integrates to the mean curvature, so an arc's TOTAL TURN ANGLE is
  preserved EXACTLY through straight↔arc blends (single fixed rotation
  axis; gated to 1e-6°); arc↔arc blends with different dir mix axes
  and preserve it only approximately. Blend halves that would overlap
  inside a short element clamp proportionally (turn still preserved —
  each blend stays boundary-centered). smoothIn 0 is BITWISE sharp.
- **Integration accuracy lesson: never RK4 OVER a curvature step.**
  The k4 stage samples the derivative at the step's endpoint — across
  a sharp element boundary that reads the NEXT element's κ and costs
  ~0.02–0.13° of heading per crossing at dt 0.01. The integrator now
  SUB-STEPS: each RK4 leg lands exactly on the next profile breakpoint
  (element boundary / blend edge / section end) via 3 Newton
  refinements of the leg length, and every stage lookup is capped to
  the leg's own side. Samples stay on the uniform dt grid (legs are
  interior); sharp chains hit analytic headings to 1e-6°.
- **Compiler.** passG gains the s/c branch (canonical + reverseAndPlace
  when reversed, same as geo). `TrackCompiler.Result` gains
  per-section `Span(i0, i1, t0, t1, s0, sLen)` — a DISTANCE-domain
  section's DURATION exists only after compilation, so the editor lays
  out strips from the spans (authored-lane sections keep exact
  authored durations; an s/c strip uses its compiled span, estimate
  `totalLen/vDesign` before the first compile). Model edits that
  resize an s/c section rebuild BEFORE redraw so the layout never sees
  stale spans.
- **Editor: the element STRIP WIDGET.** With an s/c section active,
  `app.lanes` points at a shared empty NO_LANES (every node path —
  hit tests, locks, markers, nudges — is naturally a no-op) and the
  strip draws: felt-G derived curves behind at SC_CURVE_ALPHA 0.45,
  a translucent band per element (straight slate 150,160,185 / curve
  rose 235,130,180 — a hue no lane owns), a labeled chip ("— 12 m",
  "R20 90°→", arrow = turn plane by nearest 45°), dashed separators,
  a white smoothing DIAMOND on each internal boundary (filled =
  blending, hollow = sharp) + translucent blend-zone band. The plot
  stays in FORCE view (activeGeo() false): other strips and the
  Resulting overlays read as felt G / rates, lane buttons grey out
  (setLaneButtonMode mode 2), scales fit the derived curves.
  Interactions — press rank diamonds > '+' > badges > boundary grabs >
  band select > strip activation: drag a separator (or the section
  end) to resize the element left of it (straight = length, curve =
  re-angle at fixed radius; Ctrl snaps by the new Options "element
  length" step, default 1 m; H_RESIZE cursor; live readout; edge
  auto-grow rides the same dragGrowTimer); drag a diamond to size the
  blend (snaps shut under 0.2 m); click a band to select; double-click
  or right-click > "Edit element…" for the typed dialog (kind radios,
  length / radius+angle+direction, entry smoothing — disabled on
  element 0); right-click also inserts straight/curve before/after
  and deletes (menu deferred to a clean ≤3 px release so right-drag
  still pans, same as segment locks); Delete = delete selected
  element; A = append a straight after the selection (the '+'-append
  analog). Inserted/appended elements default smoothIn =
  SC_SMOOTH_DEFAULT (2 m) — smoothing-on is the shipped default;
  the '+'-menu's new SECTION ("10 m straight", the seamless
  continuation of any mode) starts sharp at the seam. Inactive s/c
  strips draw derived curves + faint separators; clicking one
  activates it (generic path). A REVERSED s/c strip maps authored
  arc length back-to-front (element 0 draws at the strip's right
  edge — what the train does); drags stay live through the same
  authored↔traversal conversion.
- **Cross-mode plumbing**: boundaryState routes s/c neighbors through
  the compiled curves (an s/c section is never "same mode"; its roll
  lane doesn't exist, so laneIdx 2 derives too); the '+' hold after an
  s/c section takes felt end G + resulting roll (rollEnd falls back to
  compiled rates exactly like after a reversed section); 3D
  Ctrl+click-insert on s/c track just activates the section; the badge
  menu gains total length + element count + "Append element"; undo
  SecState carries mode + deep-copied elements.
- **FVDD3**: `section sc <vDesign> distance <reversed>` block with
  `elem straight <len> <smooth>` / `elem curve <radius> <angle> <dir>
  <smooth>` lines — unknown-line forgiveness unchanged both ways
  (old readers skip `elem`; new reader rejects `lane`/`n` inside sc
  blocks). Round-trip byte-identical (gated).
- **Gates** (TESTING #14): analytic line/circle/direction + felt G;
  sharp-chain analytic heading; smoothing turn preservation + clamp +
  bitwise-sharp-at-zero; reversal point set; force→s/c chain §0
  invariance; span alignment; round-trip. All legacy gates re-run
  green (bit-for-bit baseline untouched).
- **Deliberate scope cuts** (for the feel pass): no banking control on
  elements (bank = whatever the entry pose carries; a "bank to" field
  can join later), no drag-to-reorder of bands, no viewport gizmos
  (that's the split/reconnect + placement roadmap item), DISTANCE
  domain for force/geo lanes still pending (the domain toggle).
  (The banking cut lasted one session — D69.)

## D69. s/c roll modes + drag-extend fix + "Append" (v11.1)
First feel-pass on v11.0, three user calls.
- **Boundary drags could only SHORTEN.** scSAtT clamped the cursor's
  arc length to the CURRENT total, so the section-end drag could never
  express "past the end". It now EXTRAPOLATES beyond both span ends at
  the boundary sample's speed (the live rebuild re-maps every event,
  so the extension converges under the cursor). The before-entry side
  extrapolates negative, which is what lets a REVERSED section extend
  (its authored end draws at the strip's LEFT edge).
- **Toolbar button is just "Append"** — it appends whatever the active
  section authors (seconds on lane sections, elements on s/c); the
  tooltip carries the per-mode meaning. The keyboard action was
  already generic (A).
- **Per-element ROLL, two modes** (Element.rollMode/rollVal, dialog
  radios, chip suffix ∠°/↻°/s when non-zero):
  - RATE (°/s): body roll about T at a constant rate, the roll lane's
    semantics — joins Ω as ω_T = rate·T (a TIME rate, deliberately
    NOT v-scaled: cU/cR are per-meter, cT is per-second), blended
    across boundaries with the same sine ease as curvature. Rate 0 =
    exact v11.0 behavior: bank vs the horizon DRIFTS through pitched
    turns (rotating about a tilted U cones — gated: a 20° dip into a
    90° flat turn picks up R.y 0.342 of parasitic bank).
  - GLOBAL (°): hold the value as the BANK ANGLE VS THE HORIZON —
    NL2-classic banking, the user's ask ("enter at roll 0, slope down,
    turn — the track stays level, just visually twisting"). Enforced
    as an ALGEBRAIC PROJECTION after every integration leg: measure
    the bank (angle of U vs the level up at T, about T; positive =
    the roll lane's positive), roll the quaternion about T by the
    difference. A pure T-roll never moves pos or T, so the projection
    CANNOT bend the spine (gated bitwise on pos). The target eases
    from the element's ENTRY bank (measured at runtime, so it is
    always continuous whatever preceded) over max(smoothIn,
    ROLL_EASE_MIN = 2 m), capped by the element — bank never jumps
    even with smoothing off. Near-vertical track (1 − T.y² < 1e-6)
    has no defined bank: the projection holds off (re-eases when the
    track leaves the pole). Corrections under 1e-12 rad are skipped —
    planar zero-bank runs stay bit-identical, which is what let the
    factory default change without moving any v11.0 gate.
- **Defaults**: NEW elements default GLOBAL 0 (level rails — the
  station/brake norm, and the reason the mode exists); the LOADER
  defaults short v11.0 elem lines to RATE 0 so existing saves keep
  their exact geometry. FVDD3 elem lines gain two trailing fields
  (mode 0|1, value) riding the ignore-extra-fields rule.
- **Consequence, documented**: a RATE element's total twist depends on
  the design speed (deg/s over a distance-authored element), so s/c
  shape is fully speed-independent only with GLOBAL/zero-rate rolls.
- **Gates** (TESTING #14g): exact rate twist, spine unbent both modes,
  25° global bank held to 1e-9, the slope+turn level-vs-drift pair,
  roll fields round-trip, v11.0-format lines load as RATE 0.

## D70. Phase 2 continues: SPLIT / ATTACH / PIECE SELECTOR (v12.0)
The multi-piece milestone (SECTIONS-DESIGN §8): the model carried
TrackPiece placements since Phase 1 and the compiler already chained
pieces — v12.0 gives them EDITING. 3D drag gizmos are deliberately a
LATER round (spinner placement shipped first; gizmos are pure viewport
interaction and deserve their own feel pass).
- **Split** (badge menu, "Split piece before this section"): sections
  k.. move BY REFERENCE to a new piece inserted right after, placed at
  the compiled boundary — position exact, orientation YAW-ONLY (the
  piece spec: pieces never tilt, one gizmo-friendly DOF). A LEVEL
  boundary therefore re-lays the ride bitwise (gated — the identity
  quaternion path again); a pitched/banked boundary re-lays level from
  the exact point and the seam shows honestly, same doctrine as
  reversal (D67) — split where the track is level for a clean cut.
  The editor follows the section being edited onto the new piece.
  Splitting keeps the duplicated boundary sample (a new piece's first
  sample is a genuinely new point — appendRebased has always worked
  this way), so sample count grows by one per split; downstream code
  never assumed grid alignment across sections since v11.0.
- **Attach** (the '+' menu, real at last): a floating piece's sections
  append to the ACTIVE piece's chain and the emptied piece dissolves;
  its placement is deliberately discarded — attaching MEANS "continue
  from this end" (§0 keeps every section's local shape; attach after
  split at a level boundary reproduces the original ride bitwise,
  gated). "Attach floating piece" lists every other piece.
- **Piece selector**: the graph shows ONE piece at a time (§8). A
  toolbar "Piece k/n ▾" button (hidden while single-piece — the common
  case stays uncluttered) lists pieces with section counts +
  placements; 3D Ctrl+click activates the piece AND section under the
  cursor (activateGlobal maps global ride time through the spans).
- **Global timeline**: pieces concatenate on the compiled timeline, so
  the editor keeps ALL lookups in global ride time and the active
  piece lives in the window [pieceT0, pieceT0+pieceDur] — the axis
  shows the piece's true global stretch (piece 2 starts at piece 1's
  end time; POV rides pieces in order, teleporting across gaps until
  bridges land). Auto-fit, zoom/pan clamps, '+' line, badges, strips
  and the span indexing (spanBase offsets) all follow the window.
- **Placement UI**: the Coaster menu's "Start x/y/z" is now "PIECE
  x/y/z" + a NEW "Piece yaw (°)" spinner — they edit the ACTIVE
  piece (piece 0 of a single-piece ride = the old Start row exactly);
  values sync on piece switches (listener-suppressed). Piece
  placements + the active piece + active section ride the undo
  DocState (now a list of PieceStates).
- **Deletion**: deleting a piece's LAST section deletes the piece
  (when others remain); the document keeps ≥ 1 section overall.
- **'+' / vEnd fix**: adding a section reads the ACTIVE PIECE's end
  sample (via its span) for the frozen vDesign / felt-G hold — the
  ride's last sample belongs to another piece now.
- **Gates** (TESTING #15): level split bitwise (+1 boundary sample),
  attach round-back bitwise, pitched split honest + upstream bitwise,
  placement rigidity + §0 isolation, two-piece FVDD3 round-trip.
  (The pitched-split "honest re-lay" behavior lasted one review — the
  user's actual spec was orientation preservation; D71 fixes it.)

## D71. Splits never tilt; reversal is a PIECE operation (v12.1)
Two user corrections to v12.0/D67 semantics.
- **"Never tilt" meant the EDIT CONTROLS, not the split.** Splitting
  must not change the track's orientation AT ALL. TrackPiece therefore
  gains a BASE ORIENTATION quaternion (`baseOrient`, identity for
  from-scratch pieces): entryPose = pos + aboutY(yawDeg)·baseOrient,
  with yaw 0 exactly baseOrient (float-exact path). A split copies the
  compiled boundary's EXACT exit pose into the new piece — position
  AND full orientation — so splitting is bitwise at ANY boundary,
  pitched or banked (gated; the yaw-only re-lay is gone).
  `TrackCompiler.Span` now carries each section's exit TrackPose (the
  chain's own quaternion, NOT a frame round-trip from samples — that
  distinction is what makes the gate bitwise for geo/s/c sections,
  whose exit orientation is the natively integrated q). The EDIT
  control stays yaw-only on top (pitch editing is user-approved for
  later, with the same rule: the original orientation must be
  revertible — zeroing the deltas does that, since baseOrient itself
  is never edited). FVDD3 piece lines gain the quat as fields 5-8
  (%.10f keeps the quantized quat unit within the loader's
  re-normalize threshold, so round-trips stay byte-identical; missing
  fields = identity, old files unchanged).
- **Reversal applies to a WHOLE PIECE** ("the train runs the piece
  backward"), and it is a MODEL TRANSFORMATION, not a compile mode:
  `Coaster.reversePiece` re-anchors the placement at the piece's old
  EXIT (position + reversed orientation into baseOrient: fromFrame(−T,
  U) — right-handed with R = U×(−T) = −R, matching reverseAndPlace's
  frame flip), REVERSES the section list to train order, and TOGGLES
  each section's reversed flag. Consequences: the TRACK STAYS PUT and
  the train runs it the other way (level-seamed pieces exactly —
  gated; pitched/banked seams re-join with D67's usual yaw-only
  honesty per section); the editor needs NO reversal mode (the list IS
  train order; strips, '+', markers, POV all just work); applying it
  twice restores the model EXACTLY and the geometry to integration
  noise (gated at 4e-14). The section-level `reversed` flag remains in
  the model and FVDD3 as reversal's MECHANISM (and D67's gates keep
  running on it), but its badge-menu checkbox is GONE — the user-facing
  control is Coaster menu > "Reverse piece (flip travel direction)",
  acting on the ACTIVE piece; the ◀ badge still marks flagged
  sections. The editor follows the edited section to its new list
  position (index n−1−k).
- **Confirmed on the roadmap** (user Q&A): mid-section splitting — a
  piece split at an arbitrary point INSIDE a section, likely node-
  anchored (select a node, split there), which will split the lanes
  via the exact de Casteljau machinery insertNodeOnCurve already uses;
  and DIRECT ATTACH stays a first-class join even after bridge
  sections land (bridges are additive, not a replacement).

## D72. BRIDGE sections (v13.0)
SECTIONS-DESIGN §4, user-picked next. A bridge is the LAST section of
a piece and connects its chain end to the NEXT piece's entry with a
G¹ spline. BridgeSection.java holds the model AND the geometry (the
solve is closed-form — no integrator class).
- **FROZEN world-anchored geometry (the §4 feedback guard).** A
  bridge stores the two POSES it was solved against (solvedEntry /
  solvedTarget, world coordinates) + tangent lengths; its geometry is
  a pure function of those. passG deliberately IGNORES the incoming
  chain pose. Editing upstream track or moving the target piece
  changes NO bridge bit (gated bitwise) — the badge goes "bridge !"
  in the warning color instead, the active strip says "endpoints
  moved — badge ▸ Re-solve bridge", and the badge menu's explicit
  "Re-solve bridge" re-captures both poses (one undo step; tangent
  lengths kept). poseMatches compares pos to 1e-9 and |q·q'| to
  1−1e-12 (q ≡ −q). A bridge whose piece is LAST (target gone) is
  permanently dirty.
- **Geometry**: quintic Hermite on position, p'' = 0 at both ends,
  end tangents = T·lenA / T·lenB (user-adjustable via the badge
  menu's "Bridge tangents…" dialog; default = half the gap, floored
  at 1 m). Collinear matched endpoints degenerate to an EXACT
  straight line (gated to 1e-12). Orientation: rotation-minimizing
  frame transport along the spline, then the residual roll error vs
  the target's U distributes as a sine-eased TWIST about T — roll
  matched at both ends (gated ~1e-6°), zero roll rate at the seams.
  G¹ deliberately: tangent DIRECTIONS match, curvature steps at the
  seams honestly like every other boundary.
- **Sampling**: geometric presample (5 cm) with analytic curvature
  from the polynomial, then a time-domain walk at dt with v from
  energy at vDesign (frozen at creation from the chain-end sample) —
  felt Gv/Gl = v²·κ projected + gravity components, so rail colors
  and stats are honest. Exit pose = solvedTarget VERBATIM: a clean
  bridge meets the next piece's entry bitwise (the duplicated seam
  sample matches the split precedent).
- **Editor**: '+' menu "Bridge to piece N (connect the gap)" (needs a
  following piece); a piece ENDING with a bridge refuses further
  add/attach ("extend the NEXT piece" hint) — its end is glued.
  Active bridge strip = derived felt-G curves (nothing to drag; badge
  menu carries re-solve/tangents/delete/split). Reversing a piece
  containing a bridge is refused with a stats-bar hint (its poses are
  world-anchored A→B; delete the bridge first). Lane buttons disable
  (the no-lane mode 2, shared with s/c).
- **FVDD3**: `section bridge vDesign distance 0` + `bpose` (14 fields:
  two pos+quat poses at %.10f — the same skip-renormalize round-trip
  trick as piece baseOrient) + `blen lenA lenB`. Loader defaults are
  placeholders; bpose fills them (missing bpose = degenerate but
  loadable, unknown-line rules unchanged).
- **Gates** (TESTING #16): endpoint/tangent/roll exactness at posed
  ends, collinear-line degeneracy, chain continuity + exact seam,
  frozen-bitwise under target moves, dirty/in-sync reporting,
  re-solve re-join, byte-identical round-trip.
- **Scope note**: §4's "Optimize placement" solver mode (translate +
  yaw the target piece to minimize bridge strain) is NOT in this
  round — autocomplete-only first; influence curves + "become real"
  (§5) also remain on the roadmap.
  (The freeze doctrine and the optimize gap both lasted one review —
  D73 is the user's redesign.)

## D73. Bridges v2: LIVE-FOLLOW, G² ends, any piece, optimize (v13.1)
Four user calls on v13.0, plus two viewport fixes in the same round.
- **LIVE-FOLLOW replaces the freeze.** The user wants to move the
  attached piece and watch the bridge move with it — and he is right
  that there is no feedback loop: a bridge's geometry never feeds its
  own endpoints (D72's freeze guarded against a problem that belongs
  to §5 influence mode). The compiler now RE-CAPTURES the solve inputs
  (both poses AND both seam curvatures) on every compile, so dragging
  the target piece's spinners re-joins live. solvedEntry/solvedTarget/
  accA/accB remain in the model + FVDD3 as the LAST CAPTURE: they
  govern alone only when the target piece is GONE (deleted/attached) —
  the orphaned bridge stays frozen at its last shape, badge "bridge !"
  ("no following piece"). "Re-solve bridge" is deleted (nothing to
  re-solve); poseMatches/dirty machinery gone with it. Gated: move
  target → next compile's bridge exit sits on the new entry to 1e-12;
  delete target → bridge samples bitwise frozen.
- **G² ends** (user upgrade request): the quintic's end second
  derivatives now match the NEIGHBORS' curvature vectors instead of
  zero — p''(0)/p''(1) = acc·len² (κ ⊥ T; the parallel part stays
  zero). accA is MEASURED off the compiled chain's tail samples
  (mode-agnostic); accB is computed ANALYTICALLY from the target's
  first section (force: κ = (ωR·R + ωU·U)/v from node-0 targets; geo:
  (yaw·R + pitch·U)/v; s/c: κ(cos·R + sin·U) from element 0; reversed
  sections read their authored END with the lateral sign flipped —
  approximate for lane sections). Gated with one-sided |ΔT|/Δs probes
  at each seam: curvature is CONTINUOUS across both (arc 0.0503 vs
  bridge 0.0491 / 0.0379 vs 0.0400) — the v13.0 G¹ bridge read ~0
  there. acc = 0 reproduces G¹ exactly, which is how v13.0 files load
  (`bacc` line missing) and why the collinear-line gate is untouched.
  NOTE the honest limitation: the neighbors' curvature is matched at
  design speeds; the felt-G at the seam is continuous only insofar as
  speeds match (same §0 story as everywhere).
- **Bridge to ANY piece** (user: "in the future" — cheap now): the '+'
  menu's bridge entry is a submenu of every other piece. Picking a
  non-adjacent one REORDERS it to come right after the bridging piece
  (Coaster.movePiece) — the piece list IS the ride timeline, and
  connecting means "this one follows". Picking an EARLIER piece moves
  it too (if that was piece 0 the ride start changes — well-defined,
  undoable; closing a loop back to the ride start is a separate
  future feature).
- **"Optimize placement" implemented as an explicit, repeatable
  ACTION** (badge menu: "Optimize placement of piece N (align dead
  ahead)"): closed-form, no numeric solver needed — with the gap
  distance held, minimum strain = target dead ahead along the chain
  end's tangent with horizontal headings aligned (translate + yaw
  only, per the §4 DOF spec; yaw wraps into the spinner range). The
  live-follow bridge straightens with it (gated: max chord deviation
  1.4e-14 m for level ends). Chosen over a per-bridge auto-optimize
  TOGGLE deliberately: a live optimizer would fight the user's own
  placement drags — if the toggle semantics are wanted anyway, this
  action is the piece it would call.
- **Viewport fix: no more stretched connectors between pieces** (user
  bug report): the rail/heartline tube meshes strung rings across
  piece gaps (consecutive samples of DIFFERENT pieces). Rings farther
  apart than MESH_GAP_BREAK = 3.5 m (max legit ring spacing is
  SAMPLE_STRIDE·v·dt = 3 m at the 100 m/s cap) no longer get tube
  faces. Gaps smaller than that still draw a connector — rare and
  reads as intentional track; revisit if it bites.
- **Viewport: plain CLICK activates a piece** (user request): a clean
  primary click (≤3 px, no modifiers, orbit mode) on any track
  activates its piece + section in the graph (activateAt → the spans
  map global ride time back). Drags still orbit; Shift/C+click still
  recenters; Ctrl+click still inserts.

## D74. Seam smoothness ("G4"), gap spinner, piece-UX round (v13.2)
Six user calls on v13.1.
- **The seam SNAP, diagnosed**: G² matched curvature VALUES, but three
  things still stepped at bridge seams — the rate graphs' SLOPES
  (dκ/ds unconstrained: the quintic dumped its end curvature within a
  meter, ~600 °/s² probed), the SPEED at the far seam (the bridge's
  energy profile vs the target's own design speed — rates = κ·v, so a
  v step is a rate step), and the ROLL RATE (the old twist eased to
  zero-slope ends against neighbors rolling at e.g. 15 °/s). All
  three are fixed; together the rate/felt graphs are continuous in
  VALUE and SLOPE at both seams (the user's "G4 — keep the slope of
  the graphs the same"):
  1. SEPTIC (degree-7) Hermite: p, p', p'', p''' at both ends; p'''
     ends = the neighbors' d²T/ds² · len³. Seam derivatives are
     MEASURED, not modeled: one-sided quadratic fits of T(s) over the
     chain's tail samples / a pre-integrated target-head (one extra
     passG per bridge per compile — mode-agnostic, reversed sections
     included). CRITICAL detail (probed the hard way): the raw T''
     carries a −|κ|²·T parametrization term; imposed at len³ scale it
     made the septic RING (a tame seam whipped into a reverse-
     curvature spike within 2 m). Both seam vectors are therefore
     projected ⊥ T — only the perpendicular parts are geometry.
  2. SPEED BLEND: the bridge's design profile eases from its energy
     curve onto the target's entry design speed (sine ease, zero end
     slopes — dv/dt = −g·T.y stays continuous at both seams because
     the ends keep the pure energy slope). vDesign itself is now
     LIVE-captured (the chain-end speed), so the near seam can't
     drift either. Physically the blend means the connector contains
     whatever invisible brake/boost the design-speed mismatch implies
     — Phase 3 makes speed real everywhere.
  3. ROLL-RATE-MATCHED TWIST: the twist profile is a quintic scalar
     Hermite whose end slopes are the neighbors' measured roll rates
     (zero rates reproduce the old ease exactly).
  Persisted as the `bdyn` line (jerks, roll rates, vB; missing =
  zeros = the v13.1 shape). HONEST LIMIT, gated as such: cramped or
  conflicting geometry (a 5 m dive over a 20 m gap, ends pinned
  level; entry curving away from the target's heading) still needs
  FAST transients — smooth, slope-matched, but violent; that is the
  geometry's demand, and the cure is the gap spinner / tangents /
  optimize, not more derivatives. The no-snap gates therefore run on
  geometry where a smooth continuation exists.
- **Bridge GAP spinner** (user): a toolbar "Gap (m)" spinner appears
  while a live bridge is active; every change (arrow clicks included)
  RE-RUNS the placement optimization at that distance — the target
  slides along the chain end's tangent, the live bridge follows.
  Arrow bursts coalesce into one undo step (nudge-style, 800 ms);
  the value re-syncs from the actual gap after every rebuild
  (docApplying-guarded, so syncing never re-optimizes).
- **Piece settings moved to the graph area** (user): the Coaster
  menu's Piece x/y/z + yaw rows and "Reverse piece" now live in a
  toolbar "Placement ▾" dropdown next to the piece selector (same
  spinner objects, same menu-local undo wiring; the Coaster menu
  keeps heart/gauge/ties/speed/colors — the truly per-coaster knobs).
- **Inactive pieces DESATURATE in the viewport** (user): meshes are
  now built PER PIECE (sample ranges from the spans); inactive pieces
  get a desaturated+darkened gradient texture (deriveColor sat 0.30,
  bright 0.80) or desaturated solid color, dimmer ties and specular.
  setActivePiece triggers a rebuild so the highlight follows the
  editor (and the 3D plain-click).
- **Piece-boundary mesh semantics** (user #5/#6, interpreted): meshes
  never span a piece boundary — so detached pieces stay visually
  detached — while a clean bridge/attach seam abuts EXACTLY (zero
  gap) and reads as one continuous track; deleting the bridge (badge
  ▸ Delete) leaves the pieces apart again. Coincident-but-detached
  ends (a fresh split) are distinguished by the desaturation change
  rather than a fake physical gap — the geometry genuinely coincides.
  MESH_GAP_BREAK stays as an in-piece safety net.

## D75. Seam-consistent B-matching, bridge tangent defaults + gap drag, view sanitizing (v13.3)

User bug list after hand-testing v13.2. Four independent fixes, one
interaction move.

- **Stale manual zoom windows caused BOTH "insert landed at the
  section start" and "the graph disappeared"** (user: adding a
  section while zoomed out, first click misplaced a node; plot
  sometimes went blank/ghost). A manual [viewT0, viewT1] window
  survives structural edits, so the piece's stretch moves out from
  under it: after '+' the new ACTIVE section sat beyond the window's
  right edge (clicks clamped into its first sliver — "the beginning
  of the section" — and the active curve drew entirely off-view, so
  the plot showed nothing but 0.20-alpha ghost strips); after
  delete/undo the window could sit wholly outside the piece (blank
  plot). Fixes: (1) sanitizeView() runs at the top of every redraw —
  a manual window slides back inside [pieceT0, pieceT0+domain]
  keeping its width, and resumes auto-fit when at least piece-wide;
  (2) addSection/addBridgeTo reset to auto-fit outright (they
  ACTIVATE the piece-end section, so the view must show it — same
  rule setActivePiece already followed). Attach/split/delete rely on
  the sanitizer alone (no gratuitous view jumps while zoomed into an
  area that still exists).
- **Shift+B ping-pong at cross-mode seams: rate-consistent targets**
  (user: "you can continuously shift+B on one side and then the
  other and the graphs will always change", plus a residual instant
  step at geo→force seams). Two defects in boundaryState's compiled
  branch. (a) BIAS: it returned the derived value ~3 samples INSIDE
  the neighbor verbatim — matching landed 3·dt·slope off the seam,
  so a sloped neighbor left a small step and every press nudged
  again. Now the value linearly EXTRAPOLATES back to the compiled
  boundary time (span t0/t1, not the authored tOff — authored ends
  truncate to the sample grid). (b) DIVERGENCE: force-side targets
  copied the neighbor's FELT G, which is recorded at the NEIGHBOR'S
  design speed, while geo-side targets read resulting rates — two
  different invariants. Once the seam design speeds differ (any
  height edit upstream of a frozen vDesign), alternating Shift+B
  multiplied the seam rate by vA/vB per round trip: probed 12 →
  42 °/s in 8 rounds (ratio 23.4/19.9). Force-view cross-mode
  targets are now built FROM the resulting rate, converted at the
  ACTIVE side's own seam design speed (G = ω·v_seam/g + axis.y,
  slope with the ω·v' and Ω×axis terms; v_seam = vDesign exactly at
  a start edge, the compiled end sample at an end edge) — both
  directions then enforce the same invariant (body-rate continuity,
  what the unified graph shows) and the probe settles at 12.00 with
  <0.01 °/s drift per press. Same-mode + roll-lane matching stays
  the exact authored copy. KNOWN HONEST RESIDUAL: with mismatched
  seam design speeds the felt-G DISPLAY still steps at the seam in
  force view (each section's felt G is recorded at its own design
  speed — the step is the broken speed chain showing, not a match
  failure); rates join exactly.
- **Tie density after a split** (user: "tie density dramatically
  increases"): buildTieMesh anchored its arc-length grid at s = 0,
  but per-piece meshes (v13.2) hand it subLists starting mid-ride —
  every sample tripped the guard until nextS caught up, so later
  pieces opened with a tie on EVERY integration sample. The grid now
  anchors at the sublist's own first s (and catch-up is a loop, so a
  fast sample can't outrun the increment permanently).
- **Bridge tangent default = ONE FULL GAP, was gap/2** (user: "very
  spiky ends and flat middles... would this require G6?"). Not a
  continuity-order problem — the ends already match through jerk.
  |p'| at a septic end EQUALS the tangent length, so short tangents
  pack the turning into the seam regions: probed end rates 12-55×
  the interior at gap/2, ~1× at a full gap, max |dRate/dt| down
  3-7×, curvature-change-per-meter down ~3.5×. Above ~1.15·gap the
  interior bulge takes over (and short gaps between curved
  neighbors suffer from the acc·len²/jerk·len³ amplification), so
  the default is 1.0·gap; the tangents dialog still overrides.
  Existing saves keep their stored lengths (blen is data, not a
  default). Gap changes (spinner or drag) now RESCALE lenA/lenB
  proportionally — stored meters otherwise degraded back toward the
  spiky regime as the gap grew.
- **Bridge end line drags the gap** (user: "move the ability to
  change the size of the bridge section to dragging the vertical
  line at the end"). The active bridge's end boundary (the piece
  end) wears the H_RESIZE cursor; dragging it re-runs the same
  placement optimization as the Gap spinner at the cursor-mapped
  distance (cursor fraction of the current strip × current gap —
  the live recompile re-maps each event, s/c-boundary style), with
  Ctrl snapping by the Options element-length step, a live "gap
  … m" readout, one undo step per gesture, and the spinner
  re-syncing live. The toolbar spinner STAYS for typed/precise
  input.

## D76. Bridged RUNS, analytic B-matching, bridge polish round (v13.4)

User re-test of v13.3 (seven items). The headline model change: pieces
chained by bridges now read and edit as ONE track.

- **Bridged pieces edit as one RUN** (user: "when connecting two
  tracks, they should merge into one track with ability to see and
  edit everything before and after the bridge"). A RUN = consecutive
  pieces linked by trailing BridgeSections. The graph lays out EVERY
  section of the run as strips (runLayout: piece+section+global t0),
  the view window/auto-fit/zoom clamps span the run (runT0/runDur
  replaced pieceT0/pieceDur in all view code), clicking any strip or
  badge activates that piece+section WITHOUT resetting the zoom
  (activateRunSec — the run is one editing surface; the toolbar
  selector still does a full reset), the '+' sits at the RUN's end
  and appends to its last piece (activating it first), boundary
  matching works across the bridged piece boundary (the neighbor is
  the bridge: spanOf's flattened index already reaches the adjacent
  piece's spans, so the sampled path just works; k outside the piece
  resolves to no Section = never the authored/analytic branches),
  and the 3D viewport renders the WHOLE run full-color (desaturation
  now marks detached pieces only — "bridged = one track" was the
  user's mental model). Deleting the bridge splits the run again,
  which also makes the delete semantics visually obvious (their
  item 5: the pieces were always detached correctly — probed
  placements bit-identical, gap real — but nothing SHOWED it).
  A single unbridged piece is a run of one: behavior identical to
  v13.3 by construction. Piece-scoped ops (placement spinners, split,
  reverse) still target the ACTIVE piece.
- **Cross-mode B-matching went ANALYTIC** (user: the v13.3 fix didn't
  hold — "still seems to be shift+B ping ponging ... when the handles
  in/out of the sections are banked", lateral G + yaw drifting).
  Probed: the v13.3 sampled targets (resulting rates ~3 samples
  inside, LINEARLY extrapolated to the seam) creep ~0.1 °/s per
  Shift+B round-trip at a banked rolling seam — the rate curves are
  strongly CURVED there, so the linear extrapolation re-injects a
  bias every press and the seam frame walks with it (R.y 0.6157 →
  0.6028 over 10 rounds), equally with matched design speeds. Fix:
  for a non-reversed LANE-section neighbor the boundary state needs
  no sampling at all — a geo neighbor's edge rates ARE its authored
  lane values (+ handle slopes); a force neighbor's rates invert its
  authored targets at the exact seam frame and ITS OWN seam design
  speed (ω = g(G − axis·y)/v, ω' = g(G' − (Ω×axis)·y)/v + ω·g·T.y/v);
  conversion to the active mode's units is v13.3's formula. Exact
  both directions ⇒ alternating Shift+B is IDEMPOTENT once matched
  (probe: values and seam frame bit-stable from round 1). The
  sampled+extrapolated path remains for s/c, bridge and reversed
  neighbors.
- **Bridge aims at the target's ACTUAL head** (user: reversing +
  bridge = "extremely sharp angle at the entrance to the reversed
  section"). A reversed first section yaw-places itself (D67: entry
  pitch/bank not honored), and editing after a reversal drifts the
  stored baseOrient further from the regenerated shape — the bridge
  aimed at the nominal entry pose and kinked. The compiler's live
  capture now takes solvedTarget's ORIENTATION from the
  pre-integrated head's first sample (position is identical by the
  reverseAndPlace anchor; for normal sections the head frame IS the
  entry pose). Probed: nominal pitch −44° vs actual −11° and the
  seam still lands T/U-exact. "Optimize placement" aligns the ACTUAL
  head tangent for the same reason.
- **Optimize placement: bisector chord** (user: "aligning dead ahead
  won't quite cut it ... some math without any iteration ... purely
  adjust the position and yaw"). Dead-ahead along the chain tangent
  forces the whole pitch mismatch to be absorbed downstream (the
  dip-and-recover "bouncing"). Now the target's head is placed along
  the 3D ANGLE BISECTOR of the two end tangents (after yaw-align):
  both ends bend equally toward the chord — the circular-arc chord
  for matched ends. Closed-form, position+yaw only. Level matched
  ends reduce to dead-ahead exactly (gated). Probed on a −22° dive
  into a level head: max |dRate/dt| 1106 → 529 °/s².
- **Bridge roll-rate SLOPE continuity** (user: "roll rate could use
  one more derivative ... staying flat on the edges"). The twist
  quintic's end second derivatives were hardcoded zero; they are now
  the neighbors' MEASURED roll accelerations (seamRollAcc: one-sided
  differenced roll-rate reads; captured live per compile; persisted
  as two new bdyn fields, missing = 0). Mapping to x-space includes
  the q'·dv/dt term (dv/dt = −g·T.y at the ends). A min-∫(q'')²
  septic was probed and REJECTED — strain-optimal rushes q'' away
  from the pinned ends (near-seam slope got WORSE, −312 vs −179);
  the quintic through six conditions is the minimum-JERK interpolant,
  so where the residual twist demands a fast swing it still swings
  (D74 honesty), but it now leaves each seam ON the neighbor's slope
  (probed: entry slope −90 → −68 toward the chain's +29 on a nasty
  fixture; tame fixtures track closely).
- **Gap spinner input hardening** (user: letters typed while
  navigating). Scene-level key FILTERS run the ride/fly bindings,
  but consuming KEY_PRESSED does not suppress KEY_TYPED — a focused
  editor kept receiving the characters. The editor now has a
  numeric-only TextFormatter, the spinner is not focus-traversable,
  and both the 3D subscene and the graph canvas pull focus on press.
- **POV hands-off click selects track** (user request): uncaptured
  POV keeps the mouse, so a clean primary click now activates the
  piece+section under it, exactly like orbit (capture/fly still own
  the mouse; drag-to-orbit unaffected since POV has no orbit drag).

## D77. Bridge deep-dive: placement = the CONTINUATION MARCH (v13.4)

User dig-in on bridges with a crisp acceptance principle: optimal
placement should position the other piece so THE GRAPHS CHANGE AS
LITTLE AS POSSIBLE — canonically, splitting a banked constant turn
and re-bridging it must reproduce THE SAME TURN, and lengthening the
bridge must simply carry it further around the circle. The v13.4
bisector produced "a straight line with transitions at either end"
there. (Iteration was explicitly allowed but feared — crashes, slow
generation, wacky results.)

- **Placement is now a CONTINUATION MARCH** (Coaster.
  alignPieceForBridge, signature now takes the BridgeSection — it
  needs the live-captured seam states): hold the seams' BODY-frame
  curvature components and roll-per-meter, LINEARLY BLENDED entry →
  target over the requested length, and march that path from the
  chain end (fixed-step RK4 at 5 cm, hard 40k-step cap —
  deterministic, no solver, no iteration, no failure mode; an absurd
  length on a tight turn just loops the circle, honestly and
  undoably). The target's head lands at the march's end, yawed to
  its end heading. Matched seam states blend to a CONSTANT = the
  seam's own circle/helix (the split-turn case, exactly); straight
  seams reduce to dead-ahead EXACTLY (gated at 1e-12). Because the
  target's head PITCH is a constraint yaw cannot fix, a second pass
  adds the smallest smooth vertical-rate hump (parabolic, zero at
  both seams, integrating to the leftover pitch gap) — the
  dive-into-level stress case improves 1106 → 533 °/s² max rate
  slope vs dead-ahead (BridgeProbe2).
- **D74 amendment — the septic's end jerk gets its ANALYTIC
  tangential part back**: bpose jerk stays stored/measured ⊥ T, but
  integrate() now adds −|κ|²·T (rebuilt from the stored curvature)
  before building the septic. A turning curvature vector HAS a
  tangential derivative; zeroing it made the septic sag off a
  continued circle (probed: 4.9 °/s yaw ripple + 0.28 m radial sag
  over a 103° sweep). D74's ringing came from MEASURING the
  tangential part (finite-difference noise); the analytic rebuild is
  exact, and all 125 gates (incl. every D74 seam-smoothness gate)
  pass unchanged. With it, SplitTurnProbe lands the acceptance test
  outright: flat s/c circle AND the banked LEVEL force turn (the
  example layout's kind) re-join with rate deviation ≤ 0.11 °/s and
  ≤ 7 mm radial sag at 34°/69°/103° sweeps — the graphs are flat and
  the bridge IS the turn.
- **The gap became LENGTH**: the toolbar spinner (relabeled
  "Length (m)"), the badge action ("Optimize placement … (continue
  the turn)") and the end-line drag all speak bridge length along
  the track; activeBridgeGap() reads back the compiled span's arc
  extent (chord only pre-compile). A curved bridge's number now
  means what it says, and requesting more of it extends the turn.
  Readback can drift a few percent from the request on mismatched
  seams (the septic's length ≠ the march's) — accepted, the spinner
  tracks reality.
- **applyBridgeGap degenerate-start fix**: a bridge created across a
  ~zero gap (right after a split) has no meaningful sweep to scale —
  the proportional lenA/lenB rescale blew 1 m tangents up by
  dist/ε. Old gap ≤ 0.5 m now takes the default full-length sweep
  instead.
- NOT changed, per user: the bridge stays a septic with matched end
  states (they like the transition behavior); Bezier-editable
  bridges and the "two nodes + Shift+B + iterate yaw/position"
  workflow stay future options; "Bridge tangents…" and manual
  placement remain as overrides.

## D78. The seam jolt's root cause + bridge placement modes + unfreeze (v13.5)

Second bridge/seam round of the day (user: the ping-pong is gone but a
jolt remains "exactly on the transition line"; picking works half the
time; wants hover stats on bridges, placement modes, editable bridges).

- **The seam jolt was the DESIGN-SPEED DRIFT, and B-matching now
  fixes it**: the graphs' felt Gv/Gl are recorded at each section's
  own design speed, so rate-matched seams still step by ω·Δv/g the
  moment the two sections' design speeds drift apart (any upstream
  edit does it — '+' freezes vDesign only at creation). At a banked
  seam that value step reads as "the bank jumped" (the user's
  instantaneous jump in absolute roll). B/Shift+B now RE-FREEZE the
  downstream section's vDesign to the upstream design-end speed (the
  same rule '+' applies) before matching; at a common seam speed,
  rate continuity IS curvature continuity, so the felt lanes, the
  rate graphs and the true ride all join. Probed (SeamJolt): felt
  step 0.06–0.10 g before, 0.002–0.004 g after (bake-grid noise),
  rates still continuous, both mode orders, idempotent. Bridge
  neighbors are skipped (their speed profile already blends, D74).
  JUDGMENT: a boundary match now deliberately overwrites the
  downstream section's design speed — that's what "make this seam
  seamless" means; force sections reshape accordingly.
- **Track picking fixed twice** (user: "works half the time"): the
  behind-camera filter used the ORBIT rig's eye/forward even while
  the POV camera rendered (half the scene misclassified), and the
  25 px disc was tested against every 5th SAMPLE — near track
  projects those hundreds of px apart, so clicks between the dots
  missed. Now the ACTIVE camera's own localToScene supplies
  eye/forward and distance is measured to the SEGMENTS between
  consecutive strided projections (piece gaps > 6 m skipped so empty
  space between detached pieces stays unclickable).
- **Derived-curve hover** (user request): a bridge/s-c strip's felt
  Gv/Gl + roll curves give the lane hover's readout — value on
  hover, slope while Shift is held — in the lane's color, off the
  compiled samples (rateIdxAt + derivedAt), trimmed a SEAM_TRIM from
  the strip edges; drag handles (end line, separators, diamonds)
  keep priority via the H_RESIZE cursor check.
- **Bridge placement MODES** (user spec, three-way): BridgeSection.
  optMode — "Optimize position and yaw" (default, the full
  continuation march), "Optimize position" (the march places, the
  user owns the target's yaw), "No optimization" (fully manual).
  Lives in the toolbar Placement dropdown, which is now CONTEXTUAL:
  with a live bridge active it shows the mode radios + Length
  (optimize modes), next-piece Yaw (position-only + manual) and
  next-piece x/y/z (manual), all editing the NEXT piece through the
  bridge; otherwise the usual active-piece rows. The toolbar Length
  spinner moved inside. Length/yaw edits re-run the march per mode;
  switching INTO an optimize mode applies it immediately; the badge
  action follows the mode and the end-line drag disables in manual
  mode. Persisted as blen's 3rd field (missing = 0 = the default).
  JUDGMENT: while a bridge is active the dropdown shows ONLY the
  bridge panel (the active piece's own placement is one strip-click
  away).
- **UNFREEZE** (user request, "Make Editable"): badge > "Unfreeze
  (edit as geo section)" replaces the bridge with a GeoSection that
  re-authors its shape — per-METER rates measured straight off the
  bridge's samples (frame change per arc), re-timed onto the geo
  energy profile (the bridge's speed blend is not energy), fitted as
  slope-matched nodes every 0.1 s. One undo step returns the live
  bridge; the run splits (no more bridge); the next piece stays put.
  HONEST LIMITS (probed, UnfreezeProbe): the dt-grid cadence
  quantizes lane-section durations, so the unfrozen end sits within
  ~a cadence quantum of the old seam (1.5 mm position on a tame
  continuation bridge; 21 cm on a deliberately violent whipsaw
  fixture) and the exit heading carries ~one integration step of
  rate (~0.5°); felt G tracks within 0.07 g. Realistic (optimized)
  bridges unfreeze essentially exactly.

## D79. §5 round A: TRUE speed exists and shows (v13.6)

The influence-curves/"become real" roadmap item got a full DESIGN
PASS first (user request; SECTIONS-DESIGN §5 grew from a three-line
sketch into the spec — display → reconcile → sculpt, rounds A/B/C,
user-approved). Round A ships the foundation: drift between each
section's DESIGN speed and the TRUE speed becomes computed, visible,
and honest. No model or save-format change.

- **Pass D was write-only since Phase 1** — Result.trueV existed,
  nothing consumed it, and it referenced the whole document's first
  sample (meaningless for floating pieces). It is now PER-RUN (a
  run = pieces chained by trailing bridges, the same structure the
  editor's runLayout walks, re-derived model-side): each run's
  energy references its own first sample height at its first
  section's vDesign (run 0's == v0 through the Start-speed
  coupling). Bridges are pure GEOMETRY for Pass D — the D74 speed
  blend is a design-side device, so trueV shows the ride WITHOUT
  the blend's invisible brake/boost (gated: a bridged run is one
  energy body, no trueV step at either seam, honest divergence
  inside the bridge).
- **Pass A finished with NO differencing**: the recorded design
  felt already encodes curvature exactly — κ_U = g(Gv_d − U.y)/v_d²
  — so Gv_true = (v_t²/v_d²)(Gv_d − U.y) + U.y per sample, Gl
  against R.y alike, rates scaled by v_t/v_d (trueFelt + trueRates
  arrays on the compile Result, uniform across all four modes).
- **The §5.0 claim CORRECTED while gating it** (the design pass
  said "true curves are seamless by construction" — half right):
  true curves tell the GEOMETRIC truth, continuous exactly where
  curvature is, stepping where the track physically kinks. Gated in
  BOTH directions (Phase2Gate.trueSpeed): a felt-matched force seam
  with a drifted downstream vDesign reads SMOOTH in design felt
  while the true felt steps 4.7 g (D78's hidden jolt, now visible);
  a stale-vDesign s/c seam steps 1.3 g in design felt while the
  true felt stays smooth to 0.02 g (false alarm suppressed).
- **Display — the strokes ARE the indicator** (no toggle): wherever
  |true − design| exceeds TRUE_SPLIT_PX (1.5 px) on a lane's scale,
  a THIN solid companion stroke draws in the lane color across the
  whole run (design keeps the full-weight stroke); they rejoin when
  reconciled. Felt components draw straight through seams;
  rate-sourced ones (roll, and all geo-view lanes) trim the
  SEAM_TRIM band like every bodyRates consumer. Drifted sections'
  badges wear "≠v" (DRIFT_EPS 0.2 m/s) and their badge-menu info
  line gains "· entered X m/s" (canvas badges have no tooltips; the
  menu is one click and will be the reconcile-op home in round B).
  Lane + derived hover readouts append " (true …)" — value and
  Shift-slope both — once the curves visibly split.
- **POV rides and stats report TRUE speed**: the POV advance scales
  its design-time step by v_true/v_design at the current sample
  (W/S now cover true meters per wall second); the stats bar's top
  speed reads Pass D. Identical to before until something drifts.
  The stats ride TIME deliberately stays the design/axis duration.
- Tunables named for feel-testing: TRUE_SPLIT_PX 1.5, DRIFT_EPS
  0.2 m/s, TRUE_STROKE_ALPHA 0.85 (GraphEditor).

## D80. The geo-seam "instant roll jump": a PHANTOM exit step (v13.6)

User report on v13.6: geo↔force transitions STILL show a small
INSTANT change — roll ANGLE for sure (not rate), maybe pitch/yaw,
"often but not always", same feel as the old jolt. The deep-dive
found a real orientation discontinuity, orthogonal to all the
D74/D76/D78 rate-and-felt smoothness work:

- **GeoIntegrator returned the wrong exit quaternion.** Its loop
  records each sample at the TOP of the iteration and then steps —
  so when the loop exits, q sits one full RK4 step PAST the last
  recorded sample. passG built the geo exit pose from (the last
  sample's POSITION, that post-loop ORIENTATION): every section
  downstream of a geo section started rotated by a PHANTOM STEP of
  end-rate × dt — roll·dt of instant extra bank (0.40° at 40 °/s),
  pitch·dt, yaw·dt — with position continuous. Probed (SeamOrient):
  phantom 0.4387° = |Ω_end|·dt EXACTLY on a 15/10/40 °/s fixture;
  the seam sample-gap twisted 0.80° of roll where the interior
  twists 0.40°/sample. ZERO end rates ⇒ no phantom — the user's
  "sometimes there isn't one". Force sections (fromFrame of the
  last sample) and ScIntegrator (Newton-lands, samples the landed
  state) already kept exit == last sample; only geo mismatched.
- **Why nothing caught it**: bodyRates smears the extra rotation
  across its ±2-sample window — exactly the SEAM_TRIM band every
  display, overlay and probe deliberately trims; and the split
  gates compile both sides from the SAME (phantom) pose, so splits
  stayed bitwise while the seam itself jumped.
- **Fix**: qEnd = the quaternion AT the last recorded sample (the
  exit contract all other modes keep). Downstream-of-geo geometry
  shifts by up to the phantom angle — that shift IS the fix. All
  gates and probes pass; the contract is now pinned canonically
  (Phase2Gate.geoExitPose, TESTING #22). Bridges captured off geo
  chain ends and the analytic B-matching seam frames aim at the
  true frame now too.

## D81. Fresh-bridge placement default + CONVERT unification (v13.7)

Two user notes after D80 ("that fixed it completely"):

- **Fresh bridges start in "No optimization"** (user: the Placement
  dropdown claimed "Optimize position and yaw" while the just-added
  bridge had deliberately moved nothing — and the unmoved track is
  the behavior he wants). addBridgeTo sets optMode = OPT_NONE at
  creation, so the dropdown, the Length row and the end-line drag
  all agree with reality (fully manual until the user picks an
  optimize mode, which applies immediately per D78). The FVDD3
  missing-field default deliberately stays 0 = OPT_POS_YAW — old
  files keep their documented semantics; only NEW bridges changed.
- **CONVERT: one unified mode re-authoring feature** (user spec:
  "two modes to edit geo on a force section and vice versa:
  influence … and Convert", s/c and bridges convertible to either
  lane mode, the bridge's "make editable" renamed — "it all should
  be unified"). Coaster.unfreezeBridge generalized into
  Coaster.convertToLanes(samples, vDesign, toGeo, dt): per-meter
  rates measured off the samples, re-timed onto the target's ENERGY
  profile (identity for force/geo/s-c, real work for bridge
  blends), fitted as the 0.1 s slope-matched carpet; geo lanes =
  the rates, FORCE lanes = the felt targets ω·v/g + axis.y at that
  profile, and both keep the SAME roll-rate third lane (roll is
  shared by the two modes — it passes through conversions
  unchanged). Every badge menu now ENDS with "Convert to geo
  section" / "Convert to force section" (only the OTHER modes
  offered; a separator groups them): force↔geo, s/c→either,
  bridge→either — the bridge entries replace "Unfreeze (edit as
  geo section)" with the same run-split semantics. REVERSED
  sections refuse (§5.3's round-1 rule: their lanes author the
  opposite direction). vDesign carries over: conversion is a
  REPRESENTATION change — §5.1 drift survives it, and round B's
  become-real remains the separate speed reconciliation (the ops
  compose). Gated TESTING #23: geo→force 7 mm, s/c→either 2 cm
  interior, bridge→force 10 cm interior; force→geo across the
  whole 407 m default layout 0.94/1.30 m = 0.3% (the carpet
  rounding ±50 °/s roll spikes — better RELATIVE error than
  TESTING #20's accepted unfreeze bound). JUDGMENT: the node
  carpet stays a uniform 0.1 s for every conversion; §5.3's
  authored-node-times fit upgrades force↔geo when round B lands
  (shared machinery). Influence mode (the user's other cross-mode
  editing mode) stays round C, designed in §5.4.

## D82. Bridge double-bump root causes, deterministic lens, "Optimize length" (v13.8)

User revisit: bridges still bump at their ends in all graphs — "at
least for optimizing placement and yaw this should not occur" — and
the optimized shape differs after a length round trip ("in theory
there should just be one best smoothest track"); plus an "Optimize
length" check spec. Deep-dive (scratch BridgeBump) found THREE
stacked mechanisms and fixed all of them in Coaster:

- **Lens compounding (the non-determinism)**: applyBridgeGap rescaled
  lens ×= requested/REALIZED arc — a ratio that never inverts across
  steps. One 30→90→30 round trip drifted the tangents 3.8 m and the
  shape 4.3 cm, compounding forever. Worse, a fresh bridge's lens =
  the pre-optimization CHORD (44 m on a 30 m bridge in the probe) —
  deep in D75's interior-bulge regime: x2.20 rate spikes. Now every
  optimize apply picks the tangent PAIR from a FIXED grid
  (LENS_GRID × dist, Coaster.optimizeBridge) — a pure function of
  (geometry, dist). Gated: L→3L→L returns lens AND shape to 0.000.
- **March-vs-septic misalignment (the true double bump)**: the
  continuation march blended curvature/roll LINEARLY, ignoring the
  measured seam jerks — while the septic is FORCED to leave the
  seams at exactly those jerks. Placement and end constraints
  disagreed; the polynomial overshot near both seams to reconcile
  them. The march blend is now a cubic HERMITE per body component
  with the measured jerks as end slopes (zero-jerk seams reduce to
  the old blend — the D77 gates keep). Turn→straight fixture:
  overshoot x2.20 → x1.00 (peak inside == the seam's own rate, the
  monotone ideal).
- **Lens cost function (three tries, two rejected — kept for
  honesty)**: peak-rotation-rate REWARDED corner-cutting a continued
  circle (lower peak curvature than the circle: 10-30 cm radial
  sag); arc-fraction-matched deviation drowned in TANGENTIAL
  parameterization offset (~0.1% arc mismatch = cm of along-track
  shift vs mm of real signal). The kept cost: LATERAL deviation
  only — max point-to-polyline distance from the march path ("the
  bridge should BE the continuation the march promised"). Also
  fixed on the way: candidate solves must ROTATE the B-side seam
  vectors (accB/jerkB, world-frame) by the virtual yaw before
  evaluating — evaluating a ~70°-yawed placement against stale
  vectors chose lens for a bridge that never exists. And the
  march's d-scaled Hermite slopes DEADBAND below 2% of the seam
  curvature scale: on an exact circle the measured ⊥-jerk is
  ~1e-4/m² of quadratic-fit noise, which d-scaling turned into
  10 cm of placement error. NET RESULT on the D77 split-turn gates:
  BETTER than ever — circle re-join ripple 0.06-0.12 °/s with
  radial 0-8 mm (flat s/c), 0.00-0.01 °/s and 0-1 mm (banked force
  turn), vs the old ≤0.11 °/s / ≤7 mm.
- **"Optimize length"** (user spec; REDESIGNED in a second round on
  his report "not deterministic at all — mode switches change the
  distance dramatically"): BridgeSection.optLen (blen's 4th field,
  missing = off) + a check beside the Length spinner. Checked:
  length := Coaster.optimalBridgeLength — the SHORTEST length whose
  rate cost (peak frame rate + KNEE_SLOPE_WEIGHT 0.1 × peak slope)
  sits within 10% + 0.5 of the best achievable over an ABSOLUTE
  candidate ladder {1, 2, 4 … 128 m}, bisection-refined between
  neighbors. The FIRST attempt anchored the candidates to the
  CURRENT chord — every application moved the piece, which moved
  the chord, which re-anchored the next search: the fixed point
  wandered (his mode ping-pong), and a far-away piece never even
  EXAMINED short lengths. With absolute candidates the whole cost
  is a pure function of the SEAM STATES (body components + target
  pitch/bank — all invariant under moving/yawing the piece), so the
  optimum is ONE answer wherever the piece sits: gated ping-pong
  P+Y → pos-only → P+Y returns 56.000 → 56.000 m exactly. The
  user's invariant holds NATURALLY: split a smooth piece (constant
  seams OR ramps — matched values AND slopes; an ideal connector
  between identical states with equal nonzero slopes must WIGGLE,
  and the wiggle grows with length, so the cost curve itself
  prefers short), MOVE the piece anywhere + yaw it, optimize —
  the ladder bottoms out at its 1 m floor (gated: 51 m away,
  yawed 137° → 1.00 m; sloped geo seams → 1.00 m). Bottoming out
  IS the zero-distance signal (no proximity check): the prompt
  "zero-distance bridge — connect directly?" fires (YES = bridge
  deletes + direct attach, which discards the stray placement and
  re-joins seamlessly; NO = check off, nothing moves). Switching
  INTO an optimize mode checks it and applies immediately (user
  spec); the spinner and end-line drag disable while checked; yaw
  edits in position-only mode re-run it. The roll fixture's honest
  residual (x1.12 at a fixed 35 m — the twist demand) relaxes to
  x1.00 at the knee's 56 m.
- JUDGMENT calls: manual "Bridge tangents…" values survive until
  the next optimize apply (the optimizer owns lens in optimize
  modes — that's what optimizing means); the zero-distance NO
  answer unchecks rather than re-prompting forever; the knee's
  10% + 0.5 tolerance and KNEE_SLOPE_WEIGHT 0.1 are named
  feel-tunables; optimize actions cost ~25 coarse solves (the
  pairs grid) — per click/tick, not per compile.

## D83. Quintic march blend + bridge-optimizer user settings (v13.9)

User, after feel-testing v13.8 ("shaping up really well"): what ARE
the optimizer's numeric settings, could they be user-adjustable, and
could the rate graphs' ONSET at the seams be softer by default —
"slopes at the beginning and end can have a bit high of an onset,
as in a high curvature on the graph".

- **The onset had a precise cause: the march blend was a CUBIC
  Hermite**, whose second derivative at the ends is nonzero — the
  rate graphs started bending the instant they left a seam. The
  blend is now the QUINTIC Hermite pinning value + measured-jerk
  slope at both ends AND zero end second-derivative: the graphs
  leave each seam along their own slope and do their bending
  mid-blend — exactly the user's earlier "make edges less curved,
  middle more curved". Since the lens search pulls the septic
  toward the march path, the softening propagates to the real
  bridge. Gated (BridgeBump onset ratio = peak |rate slope| in the
  outer 8% over the overall peak): 0.11 vs the cubic's ~0.4 on the
  turn→straight fixture, overshoot still x1.00, and the constant/
  zero cases are IDENTICAL (equal ends + zero slopes reduce the
  quintic to a constant — every D77/D82 gate passes unchanged:
  floor collapse, ping-pong purity, zero hysteresis).
- **Three per-user settings, Options ▸ "Bridge:" rows** (AppSettings
  keys bridgeSweep/bridgeKneeTol/bridgeSlopeW; defaults live with
  the optimizer as Coaster.SWEEP_DEFAULT 1.0 / KNEE_TOL_DEFAULT
  0.10 / SLOPE_W_DEFAULT 0.1; spinners with ↺ resets + tooltips):
  TANGENT SWEEP × (0.6–2) scales the LENS_GRID candidates — bigger
  = straighter departure/arrival, turning mid-span; LENGTH
  TOLERANCE % (2–30) = how much smoothness "Optimize length" trades
  for a shorter bridge; SLOPE WEIGHT (0–0.5) = how heavily violent
  rate ramps count against a candidate length. JUDGMENT — per-USER
  on purpose: the settings shape optimize ACTIONS only, and the
  results (tangents, placement, length) are stored in the .fvd, so
  a saved file compiles identically on any machine/settings; the
  core takes them as parameters (the old signatures remain as
  default-overloads, keeping every probe and gate byte-stable).
- NOT exposed: the LENS_GRID/LEN_LADDER candidate sets, the 2%
  jerk deadband, the 1 m floor — structural, not taste.
- RETRO (v13.10, D84): BOTH halves of this entry were REVERSED on
  feel-testing — the quintic blend back to cubic (the softened
  onset wasn't what he wanted and its mid-blend bulge read as
  shape weirdness) and the three settings withdrawn from the UI
  entirely (confusing, effects unpredictable). The tuned values
  live on as internal constants.

## D84. Bridge round: properties menu, OPT_POS hump, no user
##      optimizer knobs, the seam pause, auto re-optimize,
##      run-clamped rates (v13.10)

Six of the user's eight-item list (partial bridge convert and the
POV/selection sync deferred to the next batch by agreement):

- **"Bridge properties ▾"** (user: "really a completely different
  menu"): the SAME toolbar button relabels contextually — piece
  placement normally, the bridge's own panel over a live bridge
  (mode radios, Length + Optimize-length, the new per-bridge
  optimizer prefs, next-piece yaw/xyz). updateBridgeControls owns
  the label; syncLaneButtons fires it on every activation.
- **"Optimize position" was yaw-blind** (user: "very weirdly shaped
  track… obvious that a different position would generate a
  smoother result", high length sensitivity): the march placed the
  target on the FREE continuation and left the septic to absorb
  the entire heading error to the user's fixed yaw. The march now
  closes that heading itself — a parabolic rotation-about-WORLD-Y
  hump, the pitch hump's twin; dT/ds = c·(Ŷ×T) changes heading at
  exactly c rad/m regardless of pitch, so the closure is exact.
  Gated (BridgeBump): a 55°-yawed straight target at 30 m peaks at
  68 °/s vs the 48 °/s ideal constant turn (1.43×, was the septic's
  problem alone before) and 30→33 m changes the peak 9% (smooth in
  length).
- **The optimizer knobs are NOT user-exposed after all** (a
  two-step feel reversal inside one version: v13.9 put sweep/knee
  tolerance/slope weight in Options; this batch first moved them
  PER-BRIDGE into Bridge properties per user spec — then, on
  testing, he withdrew them entirely: "a bit confusing and aren't
  quite what I was imagining — often changing them did nothing,
  the tangent sweep changed it in unpredictable ways"). The tuned
  values are internal constants again (LENS_GRID as-is, knee
  10% + 0.5, KNEE_SLOPE_WEIGHT 0.1); stale AppSettings keys and
  the short-lived dev `bopt` lines are ignored on load (the
  unknown-line rule).
- **The v13.9 QUINTIC march blend is REVERTED to cubic** (user:
  the softened onset "isn't quite exactly what I want — I'm
  completely okay with the rate graphs starting to bend the
  instant they leave the seam", and the quintic's mid-blend bulge
  "caused some weirdness in how the bridge shapes now"). The D82
  jerk-slope matching stays; BridgeBump's onset assertion retired
  to an informational print (cubic ≈ 0.33 end/peak slope — the
  intended shape); every other gate value held (knee 56 m,
  OPT_POS peak 66 vs 68 °/s, floor collapses, ping-pong purity).
- **The seam "brief stop" was TWO bugs** (user report + his flat-
  kink graph observation, probed in SeamPause): (1) the landing
  step read sAt AFTER clamping — `(total − (sAt − v·dt))/v` always
  equaled a FULL dt, stamping the last bridge sample ~dt late: the
  train crawled the final centimeters over a whole tick (his
  flattened graph tail); (2) the bridged NEXT PIECE's first sample
  was kept as "a genuinely new point" (right for detached pieces)
  but its entry coincides EXACTLY with the bridge exit — a
  zero-advance, zero-dt duplicate at every bridge seam (the full
  stop; bodyRates differenced across dt = 0). Fixes: the landing
  now happens in ONE stride of at most 1.25·dt (no full-step +
  runt pair either — runt dt in the optimizer costs divided noise
  into spikes and briefly broke the sloped-seam floor collapse,
  caught by the gate), and compile drops a bridged piece's first
  sample like any continuation. Phase2Gate's seam assertion moved
  to the POSES (exit pose == next entry to 1e-12) + a no-duplicate
  step check.
- **AUTO RE-OPTIMIZE** (user rule: upstream edits update optimized
  bridges): after every compile, each live optimize-mode bridge
  whose PLACEMENT-INVARIANT signature changed re-runs its
  optimization at its last requested length (the knee when
  Optimize-length is on; the zero-distance prompt never fires on
  the automatic path). Signature = entry pose + body-frame target
  dynamics (+ target heading only in position-only mode, where yaw
  is the user's knob): optimizing moves/yaws the piece without
  changing its own signature, so ONE extra compile settles — no
  feedback loop, and unrelated compiles are a no-op array compare.
- **Detached-piece rate jumps** (user: "the resulting graphs jump
  around" at run ends): bodyRates central-differenced ACROSS the
  gap to the next piece's first sample. Pass A now differences
  WITHIN each run (the same run table Pass D uses; bridged seams
  are one continuous body and still difference straight through).

## D85. Partial bridge convert, POV piece-follow, delete keeps
##      handles, run-clamped overlays (v13.11)

Three user fixes plus the two features deferred from the D84 list:

- **Bridge Length spinner arrows step 1 m** (was 5).
- **Deleting a node no longer flattens its neighbors' handles**
  (user: converted-carpet handles "snap to horizontal" when a node
  beside them is deleted). Diagnosis: handles are FRACTIONS of the
  neighboring segment, so removing a node stretched the facing
  handles' time runs across the merged gap — dv unchanged, slope
  HALVED per deletion. Invisible on flat default handles (dv = 0),
  glaring on Convert's slope-matched carpets. Deletion now goes
  through BezierBaker.removeBridging: the facing fractions
  re-normalize onto the merged segment so each knob keeps its
  ABSOLUTE time + value offset (gated: survivor slope 3.500 →
  3.500 exactly, time run 0.0333 s preserved where plain removal
  doubled it). The D63 rule is untouched — RE-TIMING still
  squishes; only DELETION anchors.
- **Resulting overlays / true strokes / their hover are clamped to
  the RUN** (user: the next detached piece's resulting graph
  appeared just right of the run's curves): the global timeline
  continues into detached pieces, and their first samples mapped
  inside the window's right edge. All three consumers now skip
  rows outside [runT0, runT0+runDur] (the scale fit too).
- **PARTIAL BRIDGE CONVERT** (user spec): a bridge's badge convert
  items now open a SPLIT-LINE MODE on its strip — the strip
  highlights, a draggable vertical line (H_RESIZE cursor, live
  "% of bridge" readout) sets the cut, clicking a side selects
  which portion converts (tinted + brief flash; LEFT default), and
  canvas chips [Convert left/right part] / [Cancel] apply or leave
  (chips over key plumbing: the '+'-button precedent). Engine:
  Coaster.partialConvertBridge — LEFT: the piece becomes
  [..., converted lanes, bridge(cut→target)]; the bridge stays
  last-of-piece, live-follows the converted section's exit and
  re-solves to the same measured end conditions, so the remainder
  stays ~identical (gated: whole track holds to 0.14 m interior /
  0 end, bridge still lands on the target to 1e-9). RIGHT: the
  piece SPLITS — [..., bridge(entry→cut)] + a new piece at the
  exact cut pose holding the lanes; the bridge's next piece is now
  the converted piece (followed live), and the lanes end at the
  old target's entry where the run splits, the full-convert rule
  (gated 0.15 m / 0; structure checks). Both leave optSig null —
  first-sight, so auto re-optimize records without moving
  anything — and PIN each surviving bridge's LENGTH (v13.12 fix,
  user report: "after a partial conversion, adjusting the track
  before it changes the length, with optimize length suddenly
  becoming checked"): optLen is NOT inherited and lastOptDist :=
  that side's share of the cut arc, so upstream edits re-place at
  exactly that length (gated: LEFT remainder pinned 17.7 ≈ its
  17.9 m share, RIGHT kept bridge 16.4 m instead of a stale 999).
  Cut clamps 5–95% with rate-fit margins; the mode
  cancels on any activation change.
- **POV ↔ selection sync** (user spec): riding across a piece
  boundary ACTIVATES that piece in the editor (activateAt — run
  zoom preserved within a run; piece transitions only, so
  hands-off graph edits are never fought mid-piece). Entering POV
  starts at the ACTIVE piece's first sample when a DIFFERENT piece
  was selected since POV last rode, and RESUMES the previous ride
  position otherwise (it used to always restart at 0; povTime
  clamps into the current ride length on resume).

## D86. Bridges BECOME the march; mode-switch parity; delete keeps
##      fractions; POV start fix (v13.12)

Four user calls on v13.11, one of them the deepest bridge change
since D72 (invited: "put a lot of effort into seeing if you can
improve this" — with an explicit revert warning, so the septic path
remains fully intact underneath):

- **Optimized bridges ARE the continuation march now** (the fix for
  "noticeably sharper turns near the start and end" + "high slope
  onset" on big-turn bridges): a degree-7 interpolant cannot bend
  uniformly for long — it concentrates curvature near its pinned
  ends — while the march (jerk-matched Hermite blend + mid-span
  correction humps) carries turning evenly by construction. When a
  bridge's placement LANDS on its target (optimized, nothing moved
  since; miss test 2 cm / 0.3°), BridgeSection.integrate uses the
  MARCH SPINE itself ({s,pos,T,U,κ} rows straight from
  Coaster.marchSpine at lastOptDist, now persisted as blen's 5th
  field — missing = 0 keeps old files' septic shapes exactly); the
  march's U carries the blended roll (value+slope seam-matched), a
  residual-only smoothstep-quintic twist closes the remaining bank
  without touching either seam's roll-rate graph, and the speed
  blend + resample tail is shared. The SEPTIC remains the absorber
  for everything else: manual mode, orphans, never-optimized
  bridges, and any bridge whose target was yanked by hand. The
  LENS_GRID pair search died with this — there is nothing to
  imitate when the geometry IS the ideal (lens := dist as the
  fallback sweep); optimalBridgeLength's ladder now scores the
  actual march geometry (probe fixtures: OPT_POS 55° yawed target
  rides ONE symmetric hump 17→69→1 °/s instead of the septic's
  end-heavy shape; turn→straight overshoot x1.00; determinism,
  floor collapse, hysteresis 0.000 all hold).
- **P+Y → POS switch no longer changes the shape** (user: "even
  though the Yaw hasn't changed, there seems to be a difference"):
  switching BETWEEN optimize modes re-ran the optimal-length
  ladder, whose costs differ per mode (the yaw hump enters at other
  candidate lengths) — the knee moved even with yaw aligned. Mode
  switches now keep the CURRENT length (an aligned yaw then makes
  both modes' marches identical — zero residual hump); only
  NONE→optimize runs the ladder (the v13.8 "lands at the optimal
  distance" spec was for entering optimization). The auto-reopt
  signature moved onto BridgeSection.optSignature and is pure SEAM
  GEOMETRY — no mode flags, no heading — and optimizeBridge STAMPS
  it on apply, so explicit actions never double-fire the auto pass.
- **Delete keeps handle FRACTIONS, preserves SLOPES** (user
  rejected v13.11's absolute-position variant: "handles should
  stay their fractional amount. The slope should just not
  change"): removeBridging now leaves outFrac/inFrac untouched and
  rescales dv onto the merged segment's new time run — the knob
  slides out fractionally like every other edit, the curve leaves
  the survivor at the same dv/dt (gated: slope 3.500 → 3.500,
  fraction bit-equal).
- **POV started at the END of the previous piece** when entering
  track view on a freshly selected piece: pieceStartTime returned
  Span.t0, which is BY DEFINITION the previous section's last
  sample time. It reads the piece's own FIRST sample now.

## D87. Right-convert stays connected; orientation LOCKS + "Optimize
##      position and orientation" (v13.13)

Three user calls (the Convert-expansion list deferred to a fresh
session by agreement):

- **Right partial-convert keeps the track CONNECTED** (user): the
  converted lanes used to ride a new piece with everything after
  them detached. They now PREPEND to the original target piece,
  which re-anchors at the exact cut pose — its own sections chain
  on after the lanes (shapes untouched, §0; downstream shifts only
  by the fit residual). Orphaned bridges keep the old new-piece
  fallback. Gated: 2 pieces, joined samples, whole track 0.15 m.
- **Piece orientation DELTAS + LOCKS**: TrackPiece gains pitchDeg/
  rollDeg beside yawDeg (`piece` fields 9-10, missing = 0) —
  applied yaw → pitch-about-horizontal-right → roll-about-heading,
  so each delta controls exactly ONE Euler quantity of the entry
  frame (gated: pitch 10° raises pitch only, roll 25° banks only,
  zeros revert to baseOrient exactly — the roadmap's "pitch editing
  if revertible", landed through the bridge panel). Bridge
  properties gains Pitch and Roll rows beside Yaw, each with a
  PADLOCK toggle (🔒/🔓; BridgeSection.lockYaw/Pitch/Roll, blen
  fields 6-8, missing = yaw unlocked + pitch/roll locked): locked =
  frozen everywhere, the optimization MEETS the value (yaw/pitch
  humps, roll twist); unlocked = editable (POS/NONE modes), and
  the orientation mode ALIGNS it.
- **"Optimize position and yaw" → "Optimize position and
  ORIENTATION"** (same optMode 0 — old files + default locks = the
  historic behavior exactly): the march aligns every UNLOCKED axis
  to its natural end — yaw via the existing heading delta, pitch/
  roll as direct Euler additions (the deltas are decoupled, so
  each alignment is an independent offset; pitch clamps ±85°).
  Locked axes keep their correction humps/twist. The radio grays
  out when all three are locked (tooltip explains the padlocks;
  in-mode lock toggles re-apply immediately at the pinned length).
  Gated: a target pitched −20° AND banked 25° with pitch+roll
  unlocked meets a level continuation at 0.00°/−0.00°; with locks
  (defaults) the same fixture keeps −20° and the bridge lands on
  it to 1e-9. GATE-FIXTURE LESSON kept for honesty: an s/c
  straight is NOT an orientation-holding target — its GLOBAL-0
  roll actively unbanks itself (an honest ~93 °/s head roll rate
  the march faithfully blends in); zero-rate GEO sections hold any
  orientation.
- **Grandfathered badge items REMOVED** (user): "Bridge tangents…"
  (an optimized bridge's geometry is the march itself — there are
  no user tangents left to sweep; the septic fallback derives its
  own) and the explicit "Optimize placement of piece N" action
  (the mode radios apply on switch, the Length/orientation
  controls re-run per edit, and auto re-optimize covers upstream
  changes). The orphan "!" status line stays.

## D88. Partial convert honors the march claim (stale signature);
##      LEFT remainder keeps the locks (v13.14)

User bug: "partially converting a bridge often significantly alters
the bridge's graph shape, even though it should be near-identical —
and switching placement modes fixes it, despite the settings being
deterministic."

- **Root cause**: partial convert PINNED lastOptDist (v13.12) but
  deliberately moved nothing and left optSig null (record-only), so
  the surviving bridge CLAIMED march-spine geometry its placement
  no longer satisfied. A fresh march over the sub-length cannot
  reproduce a hump-carrying march's sub-arc — the pitch/yaw humps
  are FULL-length parabolas: their mid-span curvature is baked into
  the seam state measured at the cut, and the sub-march re-plans
  its own hump over the shorter run (in orientation mode the
  render-time march also never closes heading — resYaw belongs to
  the piece alignment). So integrate()'s 2 cm/0.3° landing gate
  failed (measured 0.09–0.17 m / 0.33–0.50° on pitched+turning
  fixtures) and the graph silently fell to the SEPTIC — the
  end-concentrated shape D86 replaced (2–4.3 °/s off the sub-arc).
  The mode ping-pong "fix" worked because every optimize-mode
  switch runs optimizeBridge, which MOVES the piece onto the fresh
  march — the hidden state behind "deterministic settings,
  different graphs". Hump-free bridges reproduced within the gate,
  which is why the bug struck "often", not always.
- **Fix**: partialConvertBridge plants optSig = SIG_STALE (empty
  array, matches nothing) on a surviving bridge that was RENDERING
  the march — the first rebuild's auto pass then re-places it at
  the pinned length off the seams that compile just captured (the
  real converted-lane measurements, which is why the convert
  doesn't call optimizeBridge itself: the LEFT remainder's entry
  states don't exist until a compile runs). The cut NUDGES by the
  former miss — 0.09/0.17 m on the hump fixtures, 1 cm on a level
  turn, 0.000 on straight track — the same order as the conversion
  fit residual the feature already accepts, and the march keeps
  rendering: graph delta vs the pre-cut sub-arc drops to the
  hump-replan residual (~3 °/s there, 0 hump-free), mode
  ping-pong now moves ≤ 6 mm and returns the same graph. The
  pre-cut sub-arc itself is NOT recoverable under live-follow (the
  full-length hump profile is information the seams don't carry) —
  accepted; the fresh sub-march IS the smooth-family shape for the
  new configuration, and it's exactly what the ping-pong produced
  by hand. Septic-rendering bridges (never optimized, manual mode,
  hand-moved target — decided by the same marchSpine test
  integrate() uses, on the pre-cut state) keep the record-only
  null: nothing moves, v13.13 behavior byte-for-byte. optSig null
  keeps meaning "first sight, record only" — file loads still
  never move pieces.
- **autoReoptimizeBridges moved FvdDesigner → Coaster** (public):
  it was pure model logic stranded on the JavaFX shell, which
  forced gates to replicate it; Phase2Gate.partialConvertMarch now
  drives the real pass (Viewport3D calls
  app.coaster.autoReoptimizeBridges — same one compile settles).
- **LEFT remainder keeps lockYaw/lockPitch/lockRoll**: the v13.11
  remainder constructor never copied the v13.13 flags, silently
  resetting a customized bridge to yaw-unlocked/pitch+roll-locked.
- Gated (TESTING #30): stale sig fires exactly once, one recompile
  settles, placement lands ON the march (same-mode re-optimize
  moves 0.00000 m — pre-fix it moved by the miss), nudge bounded,
  whole track holds, septic convert byte-still, locks preserved;
  full Phase2Gate + BridgeBump/SplitTurnProbe/SeamPause/SeamJolt/
  UnfreezeProbe/SeamOrient/BridgeProbe2 all pass unchanged.

## D89. Convert expansion 1: partial convert for EVERY mode, exact
##      cuts, whole-bridge convert that connects (v14.0)

The user's Convert-expansion list (deferred from v13.13 by
agreement), first half — structure:

- **Partial convert generalizes off bridges**
  (Coaster.partialConvertSection): every convertible section's badge
  menu now offers "Convert part to … section…" beside the whole
  convert. The UNCONVERTED side keeps its original representation
  EXACTLY: lane sections split by the de Casteljau identity
  (BezierBaker.splitSegment — zero disturbance; a cut ON a node just
  partitions the list, locks riding along), s/c sections by element
  arithmetic (straights by length, arcs by angle share; the right
  half enters sharp — the cut sits in constant-state interior by
  construction). The cut CLAMPS out of s/c transition-blend and
  global-roll ease windows (scCutClamp, shared with the UI line) —
  the only regions where truncation would reshape. The downstream
  side's vDesign re-freezes to the design speed AT the cut: the same
  energy expression, so the kept shape continues exactly (gated
  0.4 mm on s/c, 3 mm on lanes — bake-grid noise).
- **Exact-fraction cuts** (the user: "the length is getting altered
  slightly?" — confirmed): the cut pose now INTERPOLATES between
  samples at exactly frac·total (Coaster.sampleAtArc/sampleAtTime);
  the old nearest-sample snap shifted every cut by up to half a
  sample (~0.13 m at speed). The split line, its % readout, the
  converted share and the surviving bridge's pinned length now all
  agree (gated: pinned length == 0.37 × total to 1e-6). The line
  itself also maps arc→time through the span's samples — it used to
  sit at the TIME fraction while bridges cut the ARC fraction.
- **Whole-bridge convert is back, and CONNECTS**
  (Coaster.convertBridgeWhole; the v13.7 whole convert split the run
  and v13.11 removed it): the lanes replace the bridge in place and
  the next piece ATTACHES — its sections chain on after the lanes,
  one track (gated: 2 pieces → 1, joined, shape holds 0.20 m).
  Orphans just convert. Coaster.convertWholeSection is the non-bridge
  twin (the editor's local sampling moved into the model so the
  density preview can re-run it).
- **Node snap on the split line** (user spec): on lane strips the
  line SNAPS to a clicked node (and magnetizes while dragging, same
  7 px ring); anywhere else in the strip still picks the convert
  side. The model cut then lands bitwise on the node.
- **Undo lost piece pitch/roll** (found while wiring the preview's
  restore path): FvdDesigner.PieceState never carried the v13.13
  pitchDeg/rollDeg — ANY undo/redo zeroed a piece's pitch/roll
  placement. Snapshot + restore now carry them.

## D90. Convert expansion 2: target density + live preview + optimal
##      placement (v14.0)

- **Target density** (user spec): convertToLanes takes a node
  SPACING — endpoints pinned exactly on the section ends, interior
  evenly spaced, COUNT chosen nearest the target (round(dur/spacing)
  + 1, min 2, capped at the 0.01 s minimum transition). A 99 s
  target collapses to the bare endpoints (gated). 0.1 s stays the
  default (NODE_SPACING_DEFAULT).
- **Density preview window** (user spec — GraphEditor's pv* block):
  EVERY convert (whole or partial, any mode) now commits through a
  small non-modal popup: typed value + log slider (spacing 0.02–2 s),
  the conversion re-applied LIVE per change, the ORIGINAL compiled
  curves dashed behind in the lane colors as the accuracy reference
  (captured pre-convert in the target mode's units), a node-count
  readout, Apply/Cancel. Internals: restore-pristine → re-run per
  change; applyDocState ADOPTS a snapshot's objects, so the pristine
  DocState is re-captured after every restore; Apply pushes THE
  pre-convert state as one undo step; Cancel restores it with no
  history entry; canvas presses are consumed while the popup is open
  (view pan/zoom stay live) and any activation change cancels.
- **Optimal placement** (user's "would there be a possibility" —
  yes): the popup's check switches convertToLanes to an ADAPTIVE fit
  (fitLaneAdaptive): nodes inserted greedily at the worst-fitting
  sample until each lane tracks its data within a tolerance (the
  slider becomes accuracy, 0.05–10 % of that lane's value range;
  0.02 s minimum node gap; 400-node cap). Node values from the data,
  slopes node-scale local differences (window bounded by neighbor
  gaps — the D87 lesson), C1 handles at 1/3 fracs (the segment time
  cubic is then exactly linear: closed-form evaluation). Each lane
  gets only the nodes IT needs — a flat lane keeps its two endpoints
  (gated); the mixed fixture holds shape to 0.21 m at 1 % with
  8/6/10 nodes where uniform 0.1 s uses 31 per lane. Handle-position
  optimization beyond slope matching was considered and skipped:
  per-segment least squares breaks C1 at the joints (kinked "smooth"
  nodes — the D62 lie).
- Judgment calls: density is expressed in SECONDS (the lane axis
  unit) rather than meters; the preview blocks graph edits while
  open (tune, then Apply); slider ranges/tolerance floor are
  constants in GraphEditor.syncPvControls/setPvValue.

## D91. Lock padlocks tinted (v14.0)

The bridge panel's yaw/pitch/roll padlocks read too alike as bare
glyphs (user): LOCKED now sits on a faded RED base, UNLOCKED on a
faded GREEN one (LOCK_SHUT_STYLE / LOCK_OPEN_STYLE in FvdDesigner
— plain constants, retune freely).

## D92. [REVERTED by D95] Banked-seam bridges: roll hump + neighbor
##      rate budget (v14.1)

User bug hunt ("frequently getting very erroneous results"): a
banked S-hill — entry banked one way, exit the other — bridged
across its deleted middle. Manual placement looked fine (the septic
+ twist handle bank changes by design); "optimize position and
orientation" "looped all over the place with a section length well
over 100 m, shooting the second piece way up high", and hand-set
lengths stayed "weird and rough with an obviously better placement
possible". Two independent defects, both fixed; the shape
machinery (march + septic) untouched per the user's constraint.

- **The march was bank-BLIND** (the placement/shape defect): it
  blends the seams' BODY-frame curvature and roll-per-meter, but a
  net bank change with quiet seam roll rates (banks HELD at both
  seams) lived only in the RENDER twist layer — which never touches
  the march. The far half applied the target's body components in a
  frame still banked the ENTRY's way: up to the whole bank gap of
  curvature misdirection (repro: 76° of world curvature-direction
  error at the far seam, felt Gl broken 0.50 g, a phantom 1.0 g of
  interior lateral). FIX: a ROLL HUMP — the pitch/yaw humps' twin —
  a smoothstep-quintic roll rate (30u²(1−u)²/d: zero value AND
  slope at both seams, exactly the render twist's profile, so the
  seams' roll-rate graphs are untouched) integrating to the
  measured bank gap, active when roll is LOCKED (unlocked roll
  keeps rolling the PIECE, v13.13). Two hard-won details: (1) the
  gap must be measured as the BANK DIFFERENCE about each frame's
  own tangent (ScIntegrator.bankOf) — a raw U-projection mixes the
  heading difference in and is NOT yaw-invariant: re-optimizing
  after the yaw alignment measured a different hump and the
  placement crept 0.17 m per run; (2) roll about T does not COMMUTE
  with the world-axis pitch/yaw humps, so the residuals iterate to
  convergence (≤ 4 marches; the one-shot hump left ~16° of bank at
  the far seam). A 0.2° deadband keeps every level/planar march —
  i.e. every pre-v14.1 bridge — on the old two-march code path
  bit-identically (all D77/D82/D84 probes pass unchanged). Gated:
  far-seam frame mismatch 0.01°, felt steps ≤ 0.001 g, placement
  deterministic to 0.00000 m. NOTE: previously-optimized BANKED
  bridges from old files won't land on the new march until
  re-optimized (any upstream edit re-places them) — they render
  septic meanwhile, which is what they effectively deserved.
- **The knee had no answer for net rotation** (the 100+ m defect):
  a gap that simply MUST roll ~60° has no intrinsic knee — its
  peak rate dilutes as 1/length, the cost "improves" forever, and
  the absolute ladder ran to its top (repro: 120 m for a ~40 m
  gap; the v13.8 auto-check applied it immediately on mode
  switch). FIX: optimalBridgeLength gains a rateBudget acceptance
  FLOOR — the knee also accepts the shortest length whose cost is
  no worse than the two joined pieces' own peak rotation cost
  (Coaster.neighborRateBudget: same |body rate| + slope-weight
  metric as rateCost, non-bridge spans only, seam smear trimmed).
  THE BRIDGE MAY MOVE AS FAST AS THE RIDE AROUND IT DOES.
  Placement-invariant (body rates don't move with placement — the
  D82 wander stays dead); budget 0 = the old pure knee (the 2-arg
  overload, which BridgeBump still gates at 60.000 m); the budget
  can only SHORTEN answers, and the 1 m zero-distance cue still
  requires a genuinely tiny cost. Repro: knee 120 m → 36 m against
  the entrance's own 60 °/s roll. JUDGMENT CALL (flagged): the
  budget is a comfort POLICY, not math — a gentle ride still gets
  long gentle bridges; if it reads wrong in practice, the one-line
  revert is passing 0 at the two call sites (autoReoptimizeBridges,
  GraphEditor.applyOptimalLength).

## D93. [REVERTED by D95, except the length spinner] March stability
##      deep-dive (v14.2)

User: "still often getting extraneous results with wild placement
and orientations", plus the Length spinner "does weird things and
gets stuck". A 14-family × 6-length headless sweep (scratch
BridgeSweep: matched seams, value/slope mismatches per axis, roll
ramps, banked+curving, POS heading gaps, dives, speed steps, a
combo) with wildness metrics (peak rate / seam rate, straightness,
height/arc, |Gl|, re-optimize movement) found and fixed four
mechanisms; every fix is placement-side (the march/septic shape
machinery untouched):

- **Slope-term saturation**: the blend's Hermite end tangents are
  measured seam jerks × d — at long lengths a REAL graph slope
  (ordinary ramping rates) dwarfed the endpoint values and the
  blend corkscrewed mid-span (28× seam rates at 128 m). Fritsch-
  Carlson-style cap in deadbandSlopes: each component's u-space
  tangent ≤ 3× max(its value gap, 0.35·ref) — honest seam-slope
  continuity at working lengths, saturating instead of exploding.
- **FOLD-signature rejection in bankGap**: a pure-blend march
  ending PAST vertical reads its bank ~180° off (h² cannot tell
  79° from 101° — the level-up reference flips), and the v14.1
  twist iteration accumulated the wrap-ambiguous gap into a full
  360° BARREL ROLL on a planar fixture (1.9 g phantom lateral,
  heading swung 40°) with fold-adjacent chaos moving re-optimizes
  by meters. No real seam pair demands > ~120° of bank: bigger
  readings are refused (hump 0), the accumulated twist clamps at
  150°, and near-vertical frames (h² ≤ 0.03) measure as 0. The
  fold window now renders planar or hands to the septic: |Gl|
  0.000, re-optimize 0.0000000 m (gated).
- **Landable knees**: lengthCost probes the candidate's own render
  landing (marchSpine on the virtual solve) and scores un-landable
  lengths at +1e6 — the knee used to pick fold lengths whose APPLY
  fell to a different, wilder septic than the march it scored.
  Ramped-seam knees: 56 m-septic → 14 m march; every swept family's
  knee now lands (arc == request).
- **Request-true length control**: the spinner and end-line drag
  re-synced to the COMPILED ARC, which equals the requested march
  length only while the march lands — when it missed, the septic's
  own arc snapped the value back and arrow-stepping fought it (the
  user's "stuck at certain points"). GraphEditor.requestedBridgeGap:
  optimize-mode bridges read/step lastOptDist (the request); manual
  bridges keep the honest compiled arc.

Accepted/known (brainstormed, deliberately NOT changed): POS-mode
large heading gaps at short lengths are hairpins (20+ g laterals at
8 m) — the user asked for that heading at that length; a felt-G
warning badge could flag it someday. A forced length inside a fold
window still places at the (deterministic) march end with a septic
render — a smarter degenerate-case placement (e.g. chord-based)
would need its own design. Sweep numbers in TESTING #33.

## D94. [REVERTED by D95] Knee budget de-inflation + ZERO LATERAL G
##      bridges (v14.3)

- **"Optimize distance severely underestimated"** (user): the D92
  neighbor budget included the cost metric's rate-SLOPE term —
  differenced rate slopes spike at every node corner of ordinary
  authored track, so the acceptance floor ballooned and 2-4 m
  bridges passed for gaps needing tens of meters.
  neighborRateBudget now returns PEAK RATE only (robust); the
  bridge's own cost still pays its slope term, making the floor
  comparison conservative. Banked fixture: budget 101 → 61, knee
  36 → 56 m (gated; real tracks were far worse).
- **ZERO LATERAL G** (user spec — "banking the created track won't
  work, roll will be wobbly"): BridgeSection.zeroGl (blen field 9,
  missing = off; "Zero lateral G" check in the bridge panel, any
  mode). RENDER-side re-bank: each sample's U rotates about T onto
  the felt-force resultant f⊥ = (v²κ + g·ŷ)⊥T at the design
  profile — real lateral is ZERO by construction (the heartline
  force-vector law), not a post-hoc bank guess. Airtime rows
  (|f⊥| < 0.15 g) have no defined bank: the bank angle
  interpolates linearly across them (the classic zero-G roll —
  exactly the user's cut-out S-hill middle), a ±0.75 m box smooth
  kills threshold kinks, and the correction EASES in/out over
  min(total/3, 10 m) end windows (smoothstep, zero end slope) so
  the seams keep their banks and roll rates. The SPINE, placement
  and length machinery are untouched (gated: mid-span |Gl|
  0.93 → 0.000 g, path delta 0.00e+00, seam bank step 0.09°).
  Works on march AND septic renders; the felt-G graphs re-derive
  from the new banking automatically.

## D95. The v14.1-v14.3 bridge-optimization round REVERTED; same-piece
##      speed reconcile (v14.1 final)

USER VERDICT after hand-testing the whole round: "making them work
worse and worse every single time" — the slanted banked S-hill got
twisted by Zero-lateral-G with a lateral spike at its end, optimize
length stayed far too short, a previously-good fixture turned rough,
and the v13.8 DOUBLE BUMP returned at longer lengths. All bridge
placement/length/render changes from D92-D94 are reverted to the
v13.14 state (commit 43baeeb); the v14.0 convert expansion, the
D89 undo pitch/roll fix and the lock tints stay (untested, separate
feature). ONE bridge change kept: requestedBridgeGap — the Length
spinner/end-line reads the REQUESTED march length instead of the
compiled arc (pure readback; fixes the user's stuck arrow keys; no
geometry).

RECORD FOR THE NEXT ATTEMPT — what was tried and how it failed:
- ROLL HUMP in the march (D92): mathematically closed the far-seam
  frame error (76°→0.01° on the gate fixture) but on the user's real
  slanted S-hill still produced bad shapes; bank-vs-horizon is the
  wrong measure on pitched track (a slanted roll's equilibrium bank
  is not horizon-referenced), the measure folds past vertical (h²
  cannot tell 79° from 101°), and iterating the non-commuting humps
  was chaos-prone (360° barrel roll; meters of placement creep). Any
  future fix must carry the bank change INSIDE the spine model
  without a horizon-referenced measurement.
- SLOPE CAPS on the Hermite jerk terms (D93): directly re-broke the
  v13.8 double-bump fix — capping the end tangents is exactly
  "partially ignoring the seam jerks" that D82 cured. Don't cap;
  if long-length overshoot needs taming, it must preserve the seam
  tangents exactly (e.g. a different interior basis).
- NEIGHBOR RATE BUDGET knee floor (D92/D94): no stable definition —
  with the slope term the floor ballooned (2-4 m knees); with peak
  rate only it was still "way way too short" on real tracks. The
  premise (compare bridge rates to neighbor rates) does not match
  what lengths feel right to the user; the knee question for
  net-rotation gaps remains open.
- LANDING-PROBED lengthCost + fold gates (D93): coupled to the
  above, reverted with them (pure D82 knee semantics restored).
- ZERO LATERAL G render re-bank (D94): rotating U onto the felt
  resultant AFTER the spine is marched leaves the spine's curvature
  directions built in the OLD frame — on a slanted S-hill the
  result read as "extremely twisted" with a lateral spike where the
  end ease window handed back to the seam bank. A real zero-Gl
  bridge must bank DURING spine generation (Gl-driven roll in the
  march itself), which is a shape-model design, not a render pass.
- Gates lesson: every one of these passed its headless fixtures and
  the full probe suite — the fixtures were too tame (level seams,
  zero-curvature targets, no up-slant). Future bridge work needs
  the user's actual saved layouts as fixtures BEFORE claiming wins.

KEPT (this entry's second half): SAME-PIECE SPEED RECONCILE (user
rule from the same message): editing upstream of directly-chained
force/geo/s-c sections used to open the design/true split — the
user wants those chains to stay REAL: after every compile,
continuation sections re-freeze vDesign to the previous section's
design-end speed (Coaster.reconcileChainedSpeeds, looped ≤3 with
the auto re-optimize pass in rebuildTrack; piece FIRST sections
keep the §5.1 drift feature — bridged/attached pieces preserve
shape and show the split — and REVERSED sections are excluded:
their vDesign is the authored-direction entry). Gated TESTING #35:
4.09 m/s of chained drift closes to 0.0000 and trueV == design v
across the piece. NOTE: loading an old save whose same-piece
sections had drifted design speeds will re-derive that geometry on
first compile (the graphs win over the stale shape — the user's
explicit rule).

## D96. Convert-expansion feel round (v14.4)

User verdict on v14.0: "fantastic", plus adjustments:

- **Density popup**: DRAGGABLE by its body (it sat over the strip's
  nodes); default position ABOVE the plot, clear of the converted
  graph; and — a JavaFX Popup is its own OS window that floats over
  OTHER APPLICATIONS — it now hides when the app loses focus and
  re-shows in place when focus returns (partial occlusion is left
  alone; that's normal window stacking). The Resulting popup already
  hover-dismisses and context menus auto-hide on deactivate, so the
  density popup was the only offender.
- **Split-line node snap tightened**: ACTIVE lane's nodes only (it
  snapped to all three lanes); a CLICK snaps only on a direct
  node-body hit (x AND y — clicking anywhere on the node's vertical
  column used to grab it); dragging magnetizes on x alone at 5 px
  (was 7).
- **Graph read-only during the convert UI**: bound key actions
  (edit/file/undo) are dead while the split line or density preview
  is up — an open convert is an open gesture, same rule as
  mid-drag keys (D75-era); POV and ortho view keys stay live. Mouse
  edits were already consumed by the modes' press handling.
- **Padlock tints saturated** (D91 follow-up): the 0.30-alpha washes
  read as "grayed out"/disabled (user calls the original pick his
  own bad decision); now 0.75-alpha green/red.

## D97. Bridges convert TRUE graphs; split-resolution ops (§5 round
##      B); bridge mode-switch round-trip + seam-kink fix (v14.5)

Three user items after his convert-expansion testing:

- **Bridge converts PRESERVE everything they can** (corrected
  in-session — a first draft made bridges convert the TRUE profile
  and the user reversed it: "I actually DO want the piece after the
  bridge to keep its split... all of the graphs and this split
  should stay the same"). vCut stays the DESIGN blend's speed, the
  downstream piece's design speeds are untouched, and the
  design/true split survives the conversion exactly. The mechanism
  that was silently destroying it: the D95 same-piece reconcile
  re-froze the downstream chain once the convert stitched the
  pieces together. NEW Section.vPinned flag (section-line 5th
  field, missing = off; in DocState + save/load): set on the first
  downstream section by both bridge convert flavors — the reconcile
  skips pinned sections, so the split survives upstream edits too;
  the resolve ops clear the pin (resolution re-joins the speed
  flow). Gated: 16.8 m/s of split survives partial AND whole
  converts through the full rebuild loop, vDesign 15.0 bitwise
  untouched, pin survives save/load.
- **"Resolve speed conflict" submenu** (§5 round B's become-real,
  user spec): a DRIFTED section's badge menu grows a submenu with
  both ways out — "Make graphs real (keep shape)…" re-fits the
  lanes at the true entry speed through the density preview
  (Coaster.makeGraphsReal; s/c just re-freezes, its shape is
  speed-independent; reference = the TRUE curves), and "Re-freeze
  design speed (keep graphs, reshape)" keeps the lanes and
  re-derives the geometry (Coaster.refreezeSpeed — the seam then
  kinks HONESTLY; Shift+B smooths it, noted in Help). ANY unconverted
  bridge earlier in the run blocks both with a disabled explainer
  ("convert the bridge section(s) earlier on this track first") —
  the user first narrowed the block to "directly after a bridge",
  then reversed it after testing: the bridge re-blends against the
  changed speeds and shows the same odd behavior anywhere
  downstream. Undrifted sections show no submenu at all. Also from
  this round: the adaptive-fit ACCURACY floor drops 0.05 % → 0.01 %
  of the lane range (slider + typing; the 400-node cap and 0.02 s
  insert gap still bound the fit). Gated: a 16.8 m/s drift closes to 0.000
  both ways — make-real holds the shape to 0.19 m interior,
  re-freeze keeps the very same lane objects; both clear the pin.
- **Manual-mode round-trip** (user spec): switching INTO "No
  optimization" stashes {optLen, next-piece placement}
  (BridgeSection.noneStash, transient); switching back with the
  placement UNTOUCHED restores exactly what was left (the v13.8
  knee-on-entry now fires only for stash-less fresh bridges); a
  HAND-MOVED placement comes back at the manual bridge's own arc
  with Optimize length unchecked. The grayed Length box tracks the
  manual bridge live (updateBridgeControls after placement edits).
- **The far-seam kink** ("instant jump in values at the end",
  POS_YAW wrong / POS right, deterministic ping-pong): each hump's
  integral is exact for the rotation it ADDS, but adding it
  re-mixes the blend's own pitch along the new path — a
  second-order ~0.06° pitch residual at the seam (6 °/s rate spike
  across one sample); position mode's yaw hump happened to cancel
  it. Fix: ONE bounded refinement pass in marchEnd (re-measure the
  pitch/yaw gaps on the humped march, re-march once; 1e-4 rad
  deadband keeps every already-clean march on the historic
  two-march sequence bit-identically). Deliberately NOT the
  reverted D95 iteration: no bank measure, no wrap ambiguity, hard
  one-pass bound. Gated via the mode-switch repro (scratch
  BridgeModeSwitch): seam step 0.063 -> 0.000 deg, ping-pong
  deterministic, POS unchanged.

## D98. INFLUENCE mode — cross-mode delta lanes over a self-computed
##      base (§5 round C, v15.0)

The user's round-C spec (2026-07-11) sharpened §5.4/5.5 into the
shipped design; where his message and the older written spec
differed, the message won:

- **CROSS-MODE, not same-mode**: influence lets a FORCE section
  sculpt its RESULTING GEO graphs and a geo section its FELT FORCE
  graphs — "editing that graph directly adds or subtracts the
  amount from the influence graph to the resulting graph". Delta
  lanes live in the OTHER mode's units (lane 2 = the shared roll
  rate either way). The additive contract is exact BY CONSTRUCTION:
  passG integrates base+delta AS the other mode (force section →
  GeoIntegrator on rate keyframes, geo section → FVDIntegrator on
  felt targets), so the resulting graphs ARE base + delta and the
  recorded felt stays honest at the operating profile. Gated:
  resulting yaw == base + delta to 0.006 °/s, geo felt to 1e-4 g;
  the user's example (no-lateral section + yaw influence → the
  laterals appear in felt/true, authored lane untouched) matches
  ω·v/g analytically.
- **The §5.4 base construction survives, with the field mapping
  INVERTED**: ls.vDesign stays THE operating speed every consumer
  already reads/writes (run rows, '+', B re-freeze, bridge vB, the
  resolve ops — no interface trickery); the NEW field vAuthored
  freezes the authored speed for ref generation. The FILE keeps the
  spec'd §5.6 slots exactly (section line = authored, `infl` line =
  operating; `dlane`/`dn` in the `n` grammar; missing = uninfluenced
  — old readers see the plain design). base = analytic per-sample
  other-mode values along ref at v* (no differencing — exact at the
  edges), re-timed by the convert trapezoid; rows {t*, L0..L2}
  exported transiently on LaneSection.baseCurves for the editor.
- **DO-NO-HARM fast path**: all-zero deltas at vDesign == vAuthored
  (bitwise compare) take the untouched single-pass route — entering
  influence moves NOTHING (gated bitwise), discarding restores the
  un-influenced document bitwise even after sculpting. The two-pass
  engages only when a delta or a speed drift exists; its delta-0
  closure at a DRIFTED speed is convert-class (0.27 m over the
  407 m layout — the re-time across a speed change IS the
  become-real mechanism, same residual family).
- **NON-MODAL editing** (softens §5.4's "authored lanes dormant" —
  user: "make able to go back and edit original design mode"): a
  badge-menu check toggles the strip between the DESIGN lanes and
  the DELTA lanes; both stay live. lanesFor() binds app.lanes to
  the deltas while influence-editing, so every node gesture, snap,
  lock, dialog and undo path works on them verbatim; activeGeo()
  FLIPS there, so palette, units, snap steps, y-axis, the Resulting
  popup and the B-matching conversions all follow the edited
  surface (lane buttons wear Δ, badge wears fx).
- **TWO VIEWS, both user-spec'd** (§5.5's primary AND its reserve,
  as a radio pair): "Influence view" (default) edits deltas around
  zero with the RESULTING graphs dotted behind (hoverable);
  "Resulting view" is purely visual — nodes RIDE base+delta (drawn
  as a sampled polyline with a faint dashed base guide and the fill
  spanning guide→curve), drags/snap/box-select/handles all offset
  by base(t) through one dispOff() choke point and write the delta
  back out. Ctrl value-snap lands the RESULTING value on the grid.
- **Design view shows the truth beside the design** (the user's
  laterals requirement): inside an influenced strip being
  design-edited, drawTrueStrokes references the AUTHORED lane curve
  instead of derivedAt — the compiled curves equal the truth there
  and would never split. Inactive influenced strips draw DERIVED
  (reversed-strip rule); B-matching against an influenced neighbor
  takes the sampled path (its authored lanes don't tell the truth);
  on a delta edge node it writes (neighbor target − base), so the
  RESULTING curve meets the neighbor.
- **"Influencing creates a split in the resulting and design
  speeds"** (user note, honored literally): influenced sections are
  EXEMPT from the D95 same-piece reconcile — entering influence
  reconciles the operating speed once (to the true entry at that
  moment) and it moves only by explicit action afterwards (resolve
  ops, B seam re-freeze, spinner). Shape is operating-speed
  invariant by construction (rates at v* retrace the same spatial
  curve; only the clock moves), so a later re-freeze re-times
  without reshaping.
- **Exits**: "Bake influence into design graphs…" = convert to the
  OWN mode through the density preview (the influence effect is in
  the compiled shape; deltas gone); "Discard influence" = deltas +
  vAuthored drop, bitwise restore. Whole cross-mode converts bake
  the influence naturally; PARTIAL converts refuse on influenced
  sections (model-side guard too): the kept side's ref cannot
  reproduce the original's sub-arc once deltas bent the mid-section
  frame. REVERSED sections refuse influence ENTRY (§5.3's rule) but
  a reversed piece carrying influence keeps applying it (passG runs
  both passes canonically + reverseAndPlace; double reversal gated
  to 5.6e-14 m) with editing locked.
- **Duration**: an influenced section's clock is the re-timed
  t*_end (durOf reads the compiled span, like s/c; the delta wall
  is absolute — influence never extends the section). Corner
  accepted: entering influence on an ALREADY-drifted section pins
  vApply ≠ vAuthored, so the design lanes' own clock differs from
  the strip width by the speed ratio — the design curves are
  reference material there until resolved (flagged in Help).

Gates: Phase2Gate.influence (TESTING #37) — 24 checks: bitwise
entry/discard, drifted-speed closure + re-clock, both additivity
directions, the laterals example, translation invariance (0.0),
reconcile exemption, IO round-trip byte-identical + uninfluenced
saves clean, reversal round-trip. All 241 suite checks green.

## D99. Convert honesty round (v15.1): analytic rate extraction;
##      converts preserve speed conflicts

Two confirmed-by-probe fixes from the user's conversion testing
(2026-07-11, ConvProbe in the session scratchpad):

- **"Converged to a slightly different spot" — root cause: the
  MEASUREMENT, not the fit.** convertToLanes differenced frames over
  a ±2-sample window, low-passing the CORNERS of the rate curves —
  a floor (~1.1 °/s on the default layout) no node density could
  recover, and NOT a time shift (best time-alignment was exactly 0).
  Fix: pitch/yaw come ANALYTICALLY from the recorded felt per sample
  (κ = g(G − axis.y)/v² — the same identity Pass A's trueFelt
  already rides; every mode records felt compatibly, bridges and
  reversed sections included). Roll has no felt to invert and stays
  measured, but over ±1 sample (second-order error ÷4). Max-accuracy
  adaptive converts now converge: rate residual 1.094 → 0.077 °/s
  (gated). Shape residual stays ~0.3 m / 407 m (roll measurement +
  duration quantum — the documented convert class).
- **Converting a speed-conflicted section silently RESOLVED it**
  (user: "the geo section should still have design/real speed
  conflict and shouldn't change shape"): the replacement section
  dropped vPinned, the D95 reconcile re-froze it (15 → 16.93 m/s in
  the probe), and the shape re-derived 0.75 m. Whole converts now
  carry vPinned onto the converted section; partial converts pin
  BOTH halves of a pinned original (each continues the same design
  profile, so each stays conflicted). Gated: vDesign 15 kept, pin
  kept, 1.94 m/s drift identical, shape movement down to the fit
  residual.

## D100. RIGID pitch/roll placement (v15.2): the design/real
##       ORIENTATION split

USER RULE (2026-07-11): "if the pitch and/or roll of the end of a
bridge section [= the start of the next piece] gets changed, make
sure that the real geometric shape is being preserved… pitching/
rolling starting point of track" — plus a design/real PITCH AND ROLL
split to go with the speed one. Probed first: pitching a piece 10°
re-solved a force section 190 m off the rigid rotation (gravity), and
even geo moved 21 m (the energy profile rides the heights). Design:

- Sections integrate at the piece's GENERATION pose (baseOrient ⊕
  yaw — TrackPiece.genPose(); yaw always commuted with gravity), and
  the pitch/roll deltas become a RIGID rotation of the finished
  geometry about the entry point (TrackPiece.tilt(), applied
  per-section in the compiler; identity object short-circuit keeps
  every untilted piece BITWISE — the whole suite rides it).
- The recorded felt RE-PROJECTS through the rotation
  (G' = G − axis.y + axis'.y), preserving the κ identity exactly
  (gated to 2e-16), so Pass A's true curves, rail colors and every
  felt consumer stay honest; recorded v stays the DESIGN profile —
  the tilt's real speed change is exactly Pass D's design/real speed
  split, shown by the existing machinery. Body rates are
  rotation-invariant: authored GEO lanes stay true under tilt; only
  FORCE felt splits (true strokes reference the AUTHORED lanes
  inside a tilted piece's active force strip, the influence rule
  reused; inactive force strips draw derived; the analytic B-match
  branch falls back to sampled there).
- Bridges never tilt (world-anchored); the bridge target-head
  capture reads the tilted head (pieceHead). Gated: force AND geo
  pieces rigid to 0.0 m at pitch 10° roll −7°, zeroing restores
  bitwise.
- ACCEPTED CORNER: splitting or reversing a TILTED piece bakes the
  tilt into the new baseOrient (deltas reset) — generation resumes
  FROM the tilted pose there, i.e. those two ops re-solve tilted
  force sections instead of staying rigid. Documented; revisit if
  feel-testing trips on it.

## D101. Transition-to-real; left-to-right conflict resolution;
##       placement pitch/roll rows (v15.2-v15.3)

The user's conversion-round UX spec (his 4 unanswered design forks
defaulted to the recommended options — all reversible):

- **'+' FORK after a conflicted piece end**: "Continue speed
  conflict ▸" (the historic design-profile continuation) vs
  "Transition to real ▸" (the new section starts at the TRUE end
  speed holding the TRUE end state — force = true felt, geo = true
  rates — as if the real curves were the design graphs). Identical
  menus when nothing drifted.
- **"Transition to real" as a RESOLVE op** (Coaster.transitionToReal):
  vDesign := true entry, pin cleared, every lane's start node takes
  the LEFT neighbor's TRUE curve value + slope at the seam — slope
  in REAL time (dG/dt_real = dG/dt_axis · v_true/v_design). Gated:
  a section inheriting a pinned neighbor's 3.55 m/s split closes to
  0.000, the neighbor keeps its own conflict, the start value lands
  on the true end felt.
- **Resolve-menu rework** (user relaxed the v14.5 whole-run bridge
  block): the submenu is blocked ONLY directly right of a live
  bridge (new explainer: it re-blends to follow any resolution —
  convert it first). Make-real/re-freeze additionally gray while the
  DIRECT left neighbor is itself conflicted ("resolve the previous
  section first") — conflicts resolve LEFT TO RIGHT, one section at
  a time; a resolved section's downstream stays put because
  make-real/transition preserve what arrives there (the pinned ones
  keep their conflicts — D99 — and unpinned continuations were
  already tracking).
- **REAL-ANCHORED sections** (the transition-to-real state) are
  recognized by which seam speed their vDesign sits nearer (true vs
  the neighbor's design end): B/Shift+B then matches the neighbor's
  TRUE curves (sampled from the true arrays, slope onto real time),
  refreezeSeamSpeed and the D95 reconcile freeze them to the TRUE
  seam speed (the reconcile would otherwise silently undo the
  resolution — gated), and B on the CONFLICTED side's end node
  toward a real-anchored neighbor is REFUSED with a stats hint
  (user: only the right side of the transition matches; the other
  direction feedback-chases).
- **Placement ▾ pitch/roll rows** (v15.3): the ACTIVE piece's start
  pitch/roll spinners with padlocks (default LOCKED, the bridge
  panel's convention). A bridged-in piece shares the BRIDGE's lock
  fields (one state, whichever panel flips it); free pieces keep a
  UI-only guard (TrackPiece.uiLockPitch/Roll, deliberately not
  persisted — pure edit-guards). In optimize-orientation mode the
  march owns the values and the spinners freeze either way.

## D102. Influence behaves like a normal section; resolve flow
##       simplified; the POV convert-cancel bug (v15.4)

The user's first influence/conversion test round (2026-07-11,
evening):

- **THE POV CONVERT-CANCEL BUG** ("Convert whole bridge to … did
  nothing", intermittent, self-healing): a whole-bridge convert
  ATTACHES the next piece, shifting piece indices; with POV parked
  BEYOND the bridge, the POV piece-follow saw a "new" piece under
  the camera on the very next animation frame, fired activateAt,
  and the activation change CANCELLED the density preview —
  restoring the pre-convert snapshot within a frame. Matches every
  observed detail (positions before the bridge keep their index =
  worked; partial converts remove no piece = worked; leaving POV
  stops the timer = worked again). Fix: the follow yields while
  editor.convertUiActive(); and beginDensityPreview now posts a
  stats-bar message whenever a convert op refuses — nothing fails
  silently again.
- **INFLUENCE NEVER TOUCHES SPEEDS** (user: influenced sections
  should "behave exactly like a normal force or geo section, just
  with little nudges" — supersedes both the §5.4 "instant
  reconciliation" and D98's reconcile exemption): entering
  influence on a speed-conflicted section used to move its
  OPERATING speed to the true entry, changing its design end and
  silently closing the NEXT section's conflict (his bug, now gated:
  drift 3.55 → 3.55, vDesign bitwise). enterInfluence leaves
  vDesign alone; LaneSection.vDesign(v) MIRRORS vAuthored while
  influenced (the pair is locked through every consumer — spinner,
  B, reconcile, resolve ops); the D95 reconcile treats influenced
  sections exactly like normal ones (pinned still skips). The
  divergent-pair machinery in influencedLane stays (hand-edited
  files), gated via direct field writes.
- **NO ROLL DELTAS** (user): influence offers Gv/Gl (geo sections)
  or pitch/yaw (force sections) only. Lane 2 stays in the model and
  file (machinery + old files intact) but is INERT in the UI: the
  roll button disables with the Δ set, lane-switch paths refuse it,
  and its strip draws only the dotted RESULTING roll. Fresh delta
  lanes now hold a node at 0 AND at the section end (user spec);
  lane 2 keeps its lone (0,0).
- **RESULTING-VIEW HANDLE ANGLES** (user): knobs offset by the base
  at the NODE's time, not the knob's own — the node+handle assembly
  translates rigidly, so handle angles read identically in both
  influence views (they used to bend along the base curve).
- **RESOLVE SIMPLIFIED** (user, reversing part of D101): the
  explicit "Transition to real" RESOLVE op is REMOVED — its job is
  the two-step "Re-freeze design speed" (now available even
  directly right of another conflicted section) then B/Shift+B
  (which, against a drifted neighbor with the speed re-anchored,
  matches the TRUE curves — value + slope in real time, the D101
  machinery unchanged). Make-real keeps waiting for the previous
  section's conflict. The '+' menu's "Transition to real ▸" fork
  for ADDING sections STAYS — user: "I really like how it works
  for adding a section." Gated: refreeze right of a pinned
  conflict closes the downstream split 3.55 → 0.000 m/s and
  survives the reconcile.

Addendum (v15.5, same session): B/Shift+B on a delta lane's FIRST
or LAST node now returns it to NO INFLUENCE — value 0, flat facing
handle, no neighbor probe and no seam speed re-freeze (user: "the
default locations for influence should be no influence"; the v15.0
neighbor-minus-base match nudged the node by the base sampling
residual on every press). Works with or without a neighbor.

## D103. TIME/DISTANCE domain for force/geo lanes: exact node-map
##       conversion, axis-follows-active, cross-domain edit view (v16.0)

The Phase-2 domain toggle (user-confirmed 2026-07-06; detailed
re-spec 2026-07-12): force/geo lanes can author over METERS of track
instead of seconds of ride. Three deliberately separate things —

- **The DOMAIN is a per-section property** (LaneSection.domain; the
  Section slot existed since Phase 1, the FVDD3 field was already
  written and is now honored on load — old files all say "time" and
  load unchanged). Lane VALUES keep their units in both domains
  (G, °/s): a domain is a re-parameterization of the AUTHORING AXIS,
  not a unit change. That is what makes the exact node map possible —
  and it means a distance-domain GEO section's shape still depends on
  design speed through the °/s rates (unlike s/c curvature-per-meter;
  documented in GeoIntegrator).
- **Physics**: FVDIntegrator.integrateTrackDist / GeoIntegrator
  .integrateDist — the ScIntegrator pattern (s joins the RK4 state,
  s' = v; keyframe lookup by arc; the FINAL step Newton-lands exactly
  on the lane end; advance speed floored at 0.5 so stalls creep and
  terminate). Lanes bake onto a 0.05 m grid (the same 0.05 constant,
  per-axis units). Gated: a distance-authored flat circle closes to
  2.6e-12 m radial deviation and lands its length to float exactness
  (TESTING #40b).
- **Domain SWITCHING is the exact node map** (Coaster.switchLaneDomain
  — the 2026-07-09 user rule: NO carpet refit, NO density preview):
  each node's x maps through the section's own compiled design-speed
  profile (Coaster.sectionMap — {t, s, v} rows off the span, reversed
  sections flipped to the authored axis, exact (0,0)/(end,end)
  anchors), fracs stay, values transfer exactly, handle dv scales by
  v̄_segment/v(node) (the chain rule; node slopes exact). vDesign,
  vPinned, reversed and locks are untouched — SPEED CONFLICTS PERSIST
  (user rule). The interior residual is the documented theory floor
  (a cubic-in-t composed with s(t) is not a cubic in s): constant-rate
  sections switch NUMERICALLY EXACTLY (2.4e-5 m, gated #40n); the
  407 m default layout — big speed swings through every transition,
  lever-arm amplification — measures 0.83 m = 0.20%, BELOW the
  convert engine's own 0.3% fit residual on the same layout (the
  "sub-fit-residual" the roadmap promised; gated #40d/e/g/h at that
  scale). Want it tighter locally: add nodes before switching.
  NOTE the user's 2026-07-12 message said "fully convert them with
  the existing convert feature" — read as the menu/UX placement, not
  the refit engine, which would CONTRADICT the recorded 2026-07-09
  node-map rule; flagged in the session summary.
- **The plot's x-axis follows the ACTIVE section's DISPLAY domain**
  (axisDist): seconds, or meters over a distance/s-c section. All
  layout (runLayout/durOf/tOff/zoom windows) runs in axis units;
  strips of the other parameterization WARP on by plotting compiled
  samples at their other coordinate (every sample carries t AND s —
  the warp falls out for free; same-mode inactive strips draw their
  authored lanes only when their native domain IS the axis, else the
  derived path). Overlays, true strokes, POV marker, hovers: all
  sample-driven, all axis-generic; seam trims scale by the local
  speed on the meters axis (2.5·dt of ride either way). An axis flip
  resets to auto-fit (the window's units changed meaning). s/c
  sections now lay out LINEARLY in meters when active (elements at
  true lengths); their "seconds view" is the pre-v16.0 rendering.
- **The cross-domain VIEW** (Section.viewOtherDomain — session state
  like editInfluence: in DocState, never saved): the active strip
  keeps its NATIVE lanes but displays on the other domain's axis,
  warped through the section's own map (Coaster.sectionMap again —
  one map, one truth), FROZEN per node/handle gesture so live
  rebuilds never slide the curve under the cursor (the auto-fit
  freeze rule). Editing goes straight THROUGH the warp: ltToX/xToLt
  are the two choke points (the dispOff precedent, x-flavored), so
  every gesture path works verbatim; Ctrl-snap, arrow nudges and
  insert snapping grid in the DISPLAY domain's units and map back
  (unwarped, the math reduces to the historic expressions exactly);
  readouts show what you see (Δs=…m, slopes per display unit).
- **Seam matching converts slope units** (domSlope): a boundary slope
  is per-second or per-meter by the neighbor's native domain; B/
  Shift+B convert through the seam design speed (dG/dt = v·dG/ds) at
  every path — authored same-mode, analytic cross-mode (neighbor
  edge slopes normalized to per-second before the ω math, results
  converted to the active side's native units), sampled, and
  forceTargetFromRates. The D78 seam-speed re-freeze runs first as
  ever, so one v serves both sides.
- **Distance ergonomics**: the Options element-length step (default
  1 m) is THE distance snap step everywhere (drag snap, nudges,
  inserts — no new Options row); min transition 0.1 m / insert gap
  0.2 m (the 0.01 s/0.02 s siblings); A and the hold-insert append
  10 m; the '+' same-mode continuation after a distance section is a
  10 m hold CARRYING the domain (cross-mode adds stay 1 s TIME);
  node dialog says "Transition length (m)"; badges wear "·m"; the
  badge-menu SECTION SETTINGS block (user spec: with the type
  options, where the future global/local axis toggles will also
  live) holds the Domain radios (= the exact convert) and the view
  check.
- **Refusals**: influence ⇄ distance is mutually exclusive (the
  delta machinery is time-parameterized end to end — enter is
  disabled on distance sections, domain options disabled while
  influenced, compiler falls back to the plain path for hand-edited
  files); the partial-convert split line drops a live cross-domain
  view on entry (fraction and cut must share units); bridges have no
  domain surface (their strip reads as time). Partial converts on
  distance lanes cut by ARC fraction with the same de Casteljau
  machinery (the kept half stays distance; gated #40l); whole/mode
  converts and make-graphs-real produce TIME lanes as ever — switch
  back after if wanted.
- **3D bridges**: markers/POV/selected-sample map node x → global
  ride TIME through the span (nodeGlobalTime; refreshTrackMarkers now
  takes per-node times — the old tOff + n.t stopped meaning time).

Gated as TESTING #40 (14 checks incl. the all-TIME do-no-harm
baseline and a byte-identical FVDD3 round trip with distance
sections); walkthrough #71. The global/local yaw/pitch/roll axis
toggles from the same user message are deferred to their own round —
the roll half needs a spec question answered first (see the session
summary): the code's force/geo roll rotates about the LOCAL T axis
(s/c's "RATE" semantics; bank drifts through pitched turns), which
the message called "currently global".

## D104. Domain conversion becomes a carpet REFIT; the VIEW owns
##       exact-shape (v16.1 — user re-spec 2026-07-12)

The user's answer to the D103 conflict flag REVERSED the recorded
2026-07-09 node-map rule, with a clean division of labor: the
CROSS-DOMAIN VIEW is the exact-shape mechanism ("keeps the shape
EXACTLY the same, the bezier graphs themselves warping a little"),
and domain CONVERSION is now a same-mode carpet refit through the
standard convert engine — density preview, uniform/adaptive
placement, dashed reference, one undo step. Coaster.switchLaneDomain
and its node map were REMOVED (implemented in v16.0, replaced same
day — the v13.9-quintic pattern); Coaster.sectionMap/mapCol SURVIVE
as the view warp's map. convertToLanes gained a target-Domain
overload: DISTANCE fits the carpet over the section-local ARC
(spacing in METERS — the preview relabels, range 0.2-20 m, sticky
default 1 m), the duration is the EXACT arc (distance lanes
Newton-land; no dt rounding), and the fit constants scale x10.
Convergence gated (#40d): the 407 m default layout round-trips
time to distance and back within 3.47 m at coarse density and 0.24 m
at fine — "with a high node density the shape converges", the user's
acceptance test. vDesign/vPinned carry (conflicts persist); an
INFLUENCED section's resulting curves are CAPTURED and the output is
plain (the whole-convert rule — influence folded in, not preserved);
reversed sections refuse like every refit. Zoom survives the switch:
the old window's ends map through the t/s samples (D106's zoom rule
applied here too).

## D105. Node moves keep handle SLOPES (dv rides the squish) —
##       supersedes D63's "kinks accepted" (v16.1, user re-spec)

The user asked for the removeBridging rule (D86) on ordinary node
moves: fractions already rescale a handle's x-run when its segment
squishes, and dv now rescales WITH it (BezierBaker.segGaps +
rescaleDvToGaps — snapshot the runs, mutate, rescale facing dv by
newRun/oldRun), so a smooth joint's handles stay collinear through
any horizontal move. He identified the history himself: this
behavior existed "very far back", was lost around the D62 rigid-
absolute-handles attempt and its D63 revert ("kinks accepted" was
recorded as the user rule then; 2026-07-12 supersedes it — the D62
version pinned ABSOLUTE knobs, which is still rejected; slopes are
the invariant, not knob positions). Applied in moveNode (drags,
nudges, dialog, ripple), moveGroupBy, and the hold-insert's foreign-
lane shifts (the active lane's spanning segment is rebuilt by the
hold and skips it). Gated #41a: slopes exact through a re-time.
NOTE: v6's "squish preserves shape" invariant (TESTING #6) is now
"squish preserves slopes" for MOVES — bake-time squish semantics
(fractional handles) are untouched.

## D106. Refreeze bakes the tilt; conflicts detected ANYWHERE in a
##       span; zoom survives axis flips (v16.1)

THE USER'S BUG ("refreeze design speed isn't fully resolving — I had
adjusted the pitch of the section after a bridge"): his diagnosis was
exactly right. A TILTED piece's design solve stays in the UNTILTED
frame (D100's rigid tilt), so its design/real split is only PARTLY
speed: re-anchoring the entry speed fixed one point and the interior
profile (tilted heights vs design heights) plus the felt orientation
re-diverged immediately after it. Fix per his spec ("silently also
resolve the orientation conflict... just changing speed and
orientation"): refreezeSpeed BAKES the piece tilt first
(TrackPiece.bakeTilt — the D100 split/reverse corner as an explicit
op: baseOrient := the full entry orientation, deltas zeroed; the
entry pose is IDENTICAL, generation resumes from the real frame and
gravity re-solves there), then re-freezes at the fresh true entry.
Gated #41b: a bridged piece pitched 8 deg goes from 4.64 m/s max
interior drift to 0.017 with the entry pose preserved to 1e-9.
makeGraphsReal bakes too (its fit reads the REAL samples — without
the bake the compile re-tilted the fitted section a second time, the
same family of bug, latent). Piece-scoped by nature: sibling
sections re-solve (the tilt is a piece property).
- CONFLICT DETECTION moved from entry-only to ANYWHERE IN THE SPAN
  (GraphEditor.sectionDrift): a tilted run-START piece has a matching
  entry but splits inside — no drift badge, no resolve submenu (the
  user's "if the start section is changed, a speed conflict should be
  generated", read as: orientation changes must surface). Bridges
  excluded (their design-side speed blend legitimately leaves the
  pure-energy profile mid-span). The info line says "true splits
  inside" when the entry matches but the interior does not.
- ZOOM SURVIVES AXIS FLIPS (user rule: "adjusting domain settings or
  selecting a section with another domain should not change the
  zoom"): a manual window's ENDS map through the global t/s samples
  (GraphEditor.mapGlobalAxis, linear extrapolation past the ride
  ends) instead of resetting to auto-fit — on flips from activation
  changes, the view toggle, and domain conversions alike; auto-fit
  stays auto-fit.

## D107. GLOBAL/LOCAL axes: geo yaw/pitch (heading/elevation) and
##       roll (bank vs horizon) for both lane modes (v16.1)

The second half of the user's 2026-07-12 spec, built once he
confirmed the roll question (he had misspoken: current roll IS local
— about T, the s/c RATE semantics). Two per-section flags in the
badge-menu settings block (radios; defaults LOCAL = the historic
behavior, bitwise — gated by #40a riding the same build):
- rollGlobal (force + geo): the roll lane's rate integrates to a
  BANK-VS-HORIZON target (entry bank + exact per-step trapezoid of
  the lane — steps land on the keyframe grid), enforced by the s/c
  GLOBAL projection after every step; the roll term leaves Omega.
  Rate 0 = bank HELD through pitched turns: the user's example — an
  upward turn makes an "upwards spiral", not an "inclined loop".
  Vertical track has no bank: the projection holds off (the s/c
  rule), the reference binding lazily on a vertical entry.
- yawPitchGlobal (geo only, toggled together): lanes 0/1 rotate
  about the WORLD vertical / the heading's horizontal axis — heading
  and elevation rates (Omega = yaw*Yhat + pitch*hhat with hhat =
  (-T.z, 0, T.x) normalized, the TrackPiece delta axis; +pitch pulls
  up in both conventions; vertical T falls back to body axes for the
  step).
Felt Gv/Gl record the BODY components of Omega (identical arithmetic
to the lane values when local — the local path stays bitwise). Gated
#41c: pitched-entry global yaw 15 deg/s spirals with elevation AND
bank held to < 0.3 deg while the same lanes under local axes drift
elevation by 22 deg; #41d: global roll's bank equals the lane
integral exactly on level track. FVDD3: section-line fields 6-7
(missing = local — old files unchanged); DocState carries both.
Global-axis lanes are NOT body rates, so: B/Shift+B refuse with a
stats hint (the analytic matcher computes body-rate targets; sampled
matching from the OTHER side still works), inactive strips draw
DERIVED (the reversed rule), influence refuses entry (the base
extraction reads body rates), and '+' same-mode continuations CARRY
the flags (a zero-rate hold only continues seamlessly under the same
axis semantics). Converts produce local sections as ever.

Also v16.1: INFLUENCE + DISTANCE mutual exclusion LIFTED (the user's
ask; the reason for it was exactly what he guessed — the two-pass
was time-parameterized): influencedLane runs the two-pass over ARC
for distance sections — base rows and delta lanes key by section-
local METERS and there is NO re-time at all (arc is speed-invariant);
the delta wall is the authored length; the cross-domain view warps
influenced strips too (the influenced guard came off refreshWarp).
Gated #41e: zero deltas bitwise, resulting == base + delta at
mid-arc. Domain conversion of an influenced section captures the
influence (D104).

## D108. v16.2 organizational round: axis BAKES, the Add dialog with
##       lockable design speed, B = real-graph matching EVERYWHERE,
##       make-real right of conflicts, visual/menu cleanups
##       (user list 2026-07-12, evening)

- **BAKE AXES (keep shape)**: Coaster.bakeAxes — the convert engine
  re-fits a section's compiled curves as lanes meaning the OTHER
  axis semantics (same mode, same domain, density preview; the
  radios above them still just REINTERPRET). Global-axis targets
  take measured world-angle rates — heading/elevation per arc for
  yaw/pitch, the bank-vs-horizon rate for roll — unwrapped
  cumulatively, vertical samples holding the previous angle, rates
  capped at 3600 deg/s (the user's guard-don't-refuse rule; a
  vertical fixture bakes finite, gated #42e). Shape preservation
  gated #42a-c: geo local→global 6 mm over 106 m, round trip 6 mm,
  force roll bake 0.4 m. Influenced sections capture; reversed
  refuse.
- **ADD FORCE/GEOMETRIC SECTION dialog** replaces the separate '+'
  force/geo items (and the "(1 s hold)" labels everywhere): type,
  domain (1 s / 10 m hold), roll + yaw/pitch axes (defaults carried
  from the previous lane section), the conflict fork (shown only
  after a drifted end — s/c keeps its two menu forks), and the
  STARTING DESIGN SPEED behind a padlock: locked (default) it
  TRACKS what the fork implies (design end vs true end, live on the
  radio flip); unlocked = a custom speed = a DELIBERATE conflict —
  the section pins (vPinned) so the reconcile leaves it alone.
  Add-only for now (existing sections keep the badge settings).
  Hold values: same-mode same-axis clean continuations copy the
  authored end values exactly (the old rule); global-axis lanes
  hold ZERO rates (the straight/held-bank continuation — the s/c
  straight precedent); everything else holds the compiled (or
  true) end state.
- **B/Shift+B = REAL-GRAPH MATCHING, generalized** (user rule: "B
  should ALWAYS line up real graphs"): true v is continuous across
  every seam (energy), so real-graph continuity IS curvature
  continuity — whenever a seam still carries a design/true split
  after the re-freeze attempt, targets come from the TRUE arrays
  and convert into OUR design values by the kappa identity
  (rate_design = rate_true * v_design/v_true; slopes carry one more
  such factor for the clock; rReal = 1 reduces to D101's anchored
  case bitwise). refreezeSeamSpeed now SKIPS vPinned sections — a
  deliberate conflict is matched AROUND, never closed.
  SMOOTHABILITY REPORT (the user asked): every force/geo seam is
  B-smoothable across any mode/domain/speed combination — analytic
  when the seam speeds agree, true-sampled when split; s/c, bridge,
  reversed and influenced neighbors are sampled (smooth to sampling
  accuracy). NOT currently B-able: pressing B ON a global-axis
  section (its lanes aren't body rates — refused with a hint; match
  from the LOCAL side instead, which works sampled), therefore a
  GLOBAL|GLOBAL seam has no B side today (workaround: bake one side
  to local, match, bake back; analytic world-rate targets are a
  possible future round), B toward a real-anchored next section
  from the conflicted side (the D101 feedback refusal), and seams
  sitting exactly on vertical track under global axes (no defined
  heading/bank). The rReal slope factor is analytic-only (editor
  code is not headlessly gateable) — feel-verify with walkthrough
  #73 and re-press idempotence.
- **Make-real right of a conflict** (user rule): the leftDrifted
  wait is GONE — make-real fits THIS section's true curves as they
  are now; the left conflict stays its own, pinned/piece-first
  downstream conflicts survive the reconcile untouched, and
  downstream track re-chains with only the fit-residual
  reorientation (gated #42d: A 11.0->11.0 m/s, B->0.000, C pinned
  and split). Only a DIRECT bridge neighbor still blocks.
- **Cleanups**: domain conversion is a single ACTION item ("Convert
  to time/distance domain...") instead of radios, the view check is
  "View in time/distance domain", and LEAVING a section always
  drops its cross-domain view (returning shows the true domain);
  ride/fly movement keys are FORWARDED from the density popup's
  scene (the popup held key focus and parked W/S — user report);
  the true companion strokes of UNSELECTED lanes fade like their
  design curves (full strength cluttered the plot — user).

Gated #42 (5 checks) on top of #40/#41; the full Phase2Gate suite
re-run green. Walkthrough #73.

## D109. v16.3: analytic world rates (the D99 lesson, again), B/Shift+B
##       completed — two-sided real detection, world-rate matcher for
##       global lanes, never-silent refusals; kappa-matched custom-speed
##       adds (user feedback round 2026-07-12, late)

- **THE BAKE BIAS was the D99 bug's twin**, as the user suspected
  ("I remember this happened before"): v16.2's global-rate extraction
  finite-differenced heading/elevation/bank angles over +-1 sample —
  the same low-pass that once made converts "converge to a slightly
  different spot". Now ANALYTIC (Coaster.worldRates, shared by bakes
  AND the matcher): Omega is known per sample (kappa-identity body
  pitch/yaw + measured roll), and the Euler-rate relations give
  theta' = (Omega x T).y/cos(theta), psi' from the heading quotient,
  and phi' = Omega.T MINUS psi'*sin(theta) — the transport sign is
  FLIPPED from the aircraft convention by our up-Y world; the wrong
  (+) sign sent the 42c force-roll bake 121 m off and was pinned down
  by probing phi' against d(bank)/dt directly (matches to 2 decimals
  everywhere; the probe is the ground truth, not the textbook).
  NEAR-VERTICAL HOLD-OFF: past ~81 deg elevation the exact rates
  amplify by 1/cos^2 and a capped spike in a BANK-RATE lane
  INTEGRATES into a garbage bank target downstream — world rates
  read NaN there; bakes hold the angle (rate 0, matching the
  integrator's own vertical hold-off), matchers refuse with the
  reason. Results: geo 3-lane bake 4 mm over 106 m (was 6);
  force roll-only bake 0.408 m — IDENTICAL to the differencing
  version, so that residual is NOT extraction: it is the
  step-boundary projection lag (the bank projection applies between
  dt steps while a local-roll original twists within them), an
  integrator-fidelity artifact of the same class as accepted
  march/septic differences. Documented; shrink-by-sub-step is a
  possible future refinement if it matters in feel.
- **B/Shift+B detection is TWO-SIDED now** (the user's report: B
  from the UNCONFLICTED side of a seam matched the conflicted
  neighbor's DESIGN lanes): the real test used to read only the
  sampled side's split; it now asks whether EITHER side is split at
  the seam — our side = the section whose node moves (its design
  edge speed vs true), their side = the neighbor's design speed vs
  true. Either split routes to the TRUE arrays with rReal =
  v_design_ours/v_true (1 for a clean us). The v15.2 real-anchored
  REFUSAL is gone — both directions match; the left side may shift
  the seam slightly and re-press to converge (the documented mild
  iteration; the user predicted "it'll change but should converge").
- **WORLD-RATE MATCHER** (user request — the global-global gap): B
  works ON global-axis lanes now. Per-lane (laneWorld: lane 2
  follows rollGlobal, lanes 0/1 follow yawPitchGlobal on geo): the
  neighbor's world-angle rates come analytic per sample off its
  TRUE body rates, fitted/extrapolated to the seam like the
  body-rate path, scaled by the same rReal/realScale factors.
  Every seam combination now has a working B side EXCEPT vertical
  seams under global axes (no defined heading/bank — refused with
  the reason).
- **B NEVER FAILS SILENTLY** (user rule): every refusal posts a
  stats-bar explanation — non-edge node selected, no neighbor on
  that side, no compiled curves yet, vertical global seam
  (boundaryState plants matchHint; callers fall back to a generic
  line).
- **Kappa-matched custom-speed adds**: a section added with an
  edited (unlocked) design speed after any section now scales its
  held end state onto that speed — rates x v_d/v_t, felt targets x
  (v_d/v_t)^2 about the gravity components — so its REAL graphs
  line up with the previous section's from the moment it appears;
  B/Shift+B afterwards only re-aims handles (the user's
  clarification), never moves positions. The dialog's domain radios
  dropped the "(1 s)/(10 m)" suffixes.

Gates re-run green (DomainGate 19 checks incl. the tightened 42a +
the 42c probe story; Phase2Gate 253). Walkthrough #74. The user's
truncated geo-to-force custom-speed report ("even after shift+B it
seems that there…") is expected to be the two-sided detection bug —
re-test with #74(b).

## D110. v16.4: sub-step roll feed-forward (the bake residual closed),
##       Orientation Settings dialog, badge two-click, dialog re-lock
##       (user feedback round 2026-07-12, night)

- **SUB-STEP FEED-FORWARD for global roll** (the user still saw a
  converging offset on local<->global roll bakes; D109 had identified
  the projection's step-cadence lag as the residual): Omega now
  carries the BODY roll rate implied by the lane's bank rate,
  p = phi' + psi'*sin(theta) (FVDIntegrator.rollTerm, shared with
  GeoIntegrator.omegaFor; psi' reads off the U/R part of Omega — a
  T-roll never moves T, so no circularity). The frame twists WITHIN
  steps like a local-roll original and the post-step projection only
  corrects O(dt^2). Result: the force roll-only bake residual fell
  0.408 m -> 0.008 m (51x, gate 42c tightened to 0.05); the geo
  3-lane bake stays 4 mm; local paths are op-for-op identical
  (bitwise — 40a + Phase2Gate's 253 green). Felt is untouched by the
  feed-forward (a T-term contributes to neither Omega.U nor Omega.R).
- **ORIENTATION SETTINGS dialog** (user spec): the roll/yaw-pitch
  radios and both bake actions condensed from six badge-menu items
  into one "Orientation settings..." window — target radios plus
  "Switch to <target>" (reinterpret; shape changes) and "Bake shape
  to <target>..." (the convert engine keeps the track), both labels
  tracking the radios LIVE. Influenced sections can BAKE (captures)
  but not switch; reversed can switch but not bake; no-change
  targets disable both.
- **Badge two-click** (user rule "only able to press section
  settings if section is highlighted"): clicking an INACTIVE
  section's badge now only activates it; the menu opens on a click
  while active.
- **Add dialog: changing the seam fork RE-LOCKS the design speed**
  (the padlock snaps back to tracking the new fork's implied value;
  an unlocked custom value is deliberately dropped — picking a
  different seam basis invalidates it).
- (The user also clarified the earlier "remove 1s/10m" item meant
  the Add dialog's domain radio labels — already done in v16.3.)

Gates re-run green (DomainGate 19 with the tightened 42c;
Phase2Gate 253). Walkthrough #75.

## D111. v16.5: AREA-MATCHED convert fits — the endpoint lands ON the
##        original (user request round 2026-07-13/14)

User: "It still seems like ALL converts converge to a point that isn't
quite where the original track was... look very deeply into the
possible solutions... just improvements to the placements of the nodes
[and maybe handles?], nothing else extra."

- **Root cause measured, theory confirmed**: the fit put nodes exactly
  ON the extracted data with the data's slopes, but the cubic BETWEEN
  nodes doesn't integrate to the data's area — and a rate lane's
  integral IS the turned angle, so signed per-segment area residuals
  accumulate into an end-pose miss no pointwise density fully kills.
  The pitch-lane integral error predicted the default layout's
  convert end miss almost linearly (0.058 deg <-> 0.62 m at 0.1 s
  uniform).
- **The fix (Coaster.matchSegmentAreas)**: after each lane fits,
  nudge the NODE VALUES so every segment's weighted area matches the
  data's — handles keep their slopes and 1/3 fracs (the closed-form
  evaluation property survives). E_k integrates (data − BAKED
  polyline)·w — the exact curve RK4 consumes, so the 0.05-grid bias
  cancels — with w = 1/v^p for the force inversion (per g(G−axis.y)/v)
  and arc-axis lookups (per ds/v): the unweighted first cut REGRESSED
  adaptive force fits. The bidiagonal constraint system solves
  LEAST-NORM (A^T(AA^T)^{-1}E, tridiagonal Thomas, O(N)), iterated
  with 0.7-damped re-baked passes (response moments drift past 100%
  error at one bake cell per segment — undamped it blew a 0.05 s
  carpet up to 177 m), every pass competing on peak |cumulative
  error| with the BEST state shipping (the raw fit is pass 0's
  entry). Sub-bake-grid carpets (median gap < 0.6 cells) skip the
  pass — attribution is noise there and chasing it measurably hurt.
- **Probed and REJECTED**: handle-FRACTION knob (zero area authority
  wherever end slopes are parallel — reflection symmetry, most of a
  smooth carpet); forward substitution with a pinned start (its
  alternating homogeneous mode random-walks over long carpets — −89 g
  at the tail of a 269-segment fit); smoothing/centering/capping that
  walk (each biased some fixture worse than no matching).
- **Adaptive fits grew an AREA criterion + length cap**: segments
  whose mean signed deviation beats eps/2, and non-flat segments
  longer than 0.6*xScale, take midpoint nodes (flat stretches keep
  bare endpoints).
- **Measured (endpoint miss, 407 m default layout / 140 m geo
  fixture)**: uniform 0.1 s force->geo 0.617 -> 0.019 m (33x),
  force->force 0.824 -> 0.017 (48x); uniform 0.05 s 0.0095 -> 0.0025
  / 0.017 -> 0.0045; DISTANCE targets 1.6-7.6 mm; geo-source fits
  0.2-1.0 mm (up to 30x); the domain round trip (gate 40d) coarse
  3.47 -> 0.019 m (180x). KNOWN FLOOR: adaptive carpets of violent
  FORCE layouts keep ~0.1-0.2 m of through-the-solution residual
  (fit shape <-> speed profile feedback, invisible to any lane-local
  metric — proved by best-state selection not recovering it); fine
  UNIFORM spacing is the max-fidelity setting there, said so in Help.
  40d re-baselined: round trips land at cm scale but are no longer
  density-MONOTONE (the matcher, not spacing, sets the floor).
- Also learned: geo fixtures with sustained yaw+bank CLIMB until they
  stall (the D107 upward-spiral effect) — the old 42a fixture creeps
  at the 0.1 m/s floor and its convert comparisons were stall
  artifacts (15 deg end-tangent reads at HEAD too); test fixtures
  must dive first. Converting stalled/creeping track remains
  undefined-ish (extraction reads garbage in creep) — pre-existing,
  out of scope.

## D112. v16.5: influence orientation settings + global-axis influence,
##        one-item s/c conflict fork (same round)

- **Influence gains its own AXIS SEMANTICS** (user spec: "make
  'Orientation Settings...' bring up the settings for the influencing
  graph... geo influencing... for both roll orientations of force
  sections, and force influencing... for all orientations of geo
  sections"): LaneSection.inflRollGlobal/inflYawPitchGlobal say how
  the two-pass integrates base + delta AS the other mode — a force
  section's geo influence picks roll + yaw/pitch axes, a geo
  section's force influence picks roll (the force integrator's only
  choice). Independent of the section's OWN flags: buildBase derives
  body pitch/yaw from the kappa identity (felt is honest under any
  flags, D107), converts the roll lane between bank rate and body
  roll through the transport relation p = phi' + psi'*sin(theta),
  and emits world heading/elevation rates via Coaster.worldRates
  when the influence is global-axis (NaN near vertical = hold, the
  bake rule). Pass 2 runs under the influence flags; the ref runs
  under the section's. Entry defaults MIRROR the section's roll
  choice (zero deltas then reproduce the ref without conversion);
  yaw/pitch starts local.
- **Global-axis sections take influence now** (the D107 refusal is
  gone — it existed only because the base read body rates), and the
  orientation dialog's SWITCH works on influenced sections (same
  reason). While a strip edits influence, the badge item relabels
  "Influence orientation settings..." and the dialog configures the
  influence axes — switch only, no bake (the delta lanes are the
  user's; "Bake influence into design graphs..." stays the exit).
- **File**: `infl` line fields 2-3 = the two flags; missing = local =
  historic. Undo snapshots carry them (SecState +2 fields).
- **'+' menu s/c fork collapsed** (user: "don't need an entire pop
  out window if just two options"): over a conflicted end the
  straight/curve entry is ONE submenu revealing "Continue speed
  conflict" / "Transition to real" — was two top-level items.
- Gated #45 (a: global-roll influence entry + discard bitwise, b:
  zero-delta two-pass shape v-invariant at a hand-split pair
  (0.078 m/83 m — the D110 projection-cadence class), c: +10 deg/s
  heading delta on a 48-deg climb turns EXACTLY 60.00 deg under
  global axes vs 39.75 local — the cos(elevation) contrast, d: FVDD3
  byte-identical round trip with flags). DomainGate 24 checks +
  Phase2Gate 253 green. Walkthrough #76.

## D113. v16.6: MID-CHAIN converts lost their entry step — the user's
##        real "converges off" bug (feedback round 2026-07-14)

User: "an overbanked turn in force of about length 150m... STILL
getting a significant converging difference... roughly 0.3m."

- Reproduced at 0.20 m ONLY when the section sits MID-CHAIN: a
  continuation section's span starts one dt AFTER its entry (the
  compiler drops the sample duplicating the previous exit, D84), so
  every span-based convert extracted a profile 0.01 s short and
  SHIFTED — the refit section lost v·dt ≈ 0.2-0.3 m of arc. Sole-
  section gate fixtures never showed it (their spans start at t=0);
  the user converts mid-layout, so this was likely THE standing
  "converges to a point that isn't quite the original" all along —
  D99's extraction fix and D111's area matching each removed real
  error layers but not this one.
- Fix: Coaster.withEntry — the convert extractions include the
  boundary sample (the section's exact entry state; its felt belongs
  to the previous section, so a deliberate seam jump reads the old
  target for one dt-local row — replacing a whole-profile shift). A
  position-continuity guard keeps DETACHED run-starts (whose spans
  are complete) from pulling in the previous run's tail. Applied to
  convertWholeSection, convertDomain, bakeAxes, makeGraphsReal and
  the LEFT partial-convert half; the partial FRACTION axis stays
  span-only (D89's exact node cuts must not shift).
- Overbank repro: 0.20 m -> 2.6-7.2 mm (gate 45e).

## D114. v16.6: B/Shift+B across local|global ROLL seams (same round)

- The neighbor's roll expressed in OUR semantics, EXACT at the
  boundary sample: frames and felt are exact there, so the heading
  rate psi' comes from the kappa identity with no differencing, and
  worldRates' bank line inverts to p = phi' + psi'*sin(theta) (body
  roll <- bank rate; minus for the reverse). Slope from the same
  conversion 5 samples inside — the seam value stays exact, so
  re-presses don't creep. Vertical seams refuse with the stats-bar
  reason (no defined bank).
- Bonus exactness: global|global roll seams now match DIRECT from
  the authored lanes (both are bank rates — the sampled world-rate
  path was pointless there), and lanes 0/1's exact path keys on
  YAW/PITCH semantics equality only (a neighbor's roll flag
  needlessly forced sampling before).

## D115. v16.6: PER-METER rate units (user spec, same round)

User: "could you make RATES able to be between time or distance as
well? For vertical/lateral force, this would be adding g/m..."

- LaneSection.ratePerMeter: the RATE lanes author °/m — geo
  pitch/yaw/roll and a force section's roll. Consumed rate =
  value × v at every integrator stage (both integrators, both
  domains, global axes included — the global-roll bank target
  integrates the lane over ARC). NOTE on the "g/m" ask: Gv/Gl are
  G TARGETS, not rates — they carry no time denominator to swap
  (a 2 g target is 2 g at any speed), so the flag deliberately
  leaves them; the Rate-units setting on a force section governs
  its roll lane. Explained to the user.
- Distance domain × per-meter GEO = fully speed-independent shape
  (the s/c property for lanes — gated 0.08 mm between 18 and
  30 m/s). Per-meter constant pitch: elevation == rate × arc
  exactly (gate 46a); global per-meter roll: bank == rate × arc
  (46c).
- Everywhere the units matter: converts (a toRateM target divides
  each sample's rate by the profile speed; area weights: ∫val·v dt
  on time axes → w = v, exactly ∫val ds on arc axes → w = 1), the
  Add dialog (Rate-units row; per-meter holds divide the °/s end
  state, κ-matching skipped — per-meter is speed-invariant already),
  '+' continuations carry the flag, B/Shift+B convert value+slope
  across mixed-rate seams (× / ÷ v_seam with the product-rule v'
  term; a per-meter neighbor's authored reads convert up front),
  section line field 8 in FVDD3 (missing = °/s), undo, badge
  "· rates °/m", y-axis/readout units, derived strips rescale onto
  a per-meter axis (÷ that sample's v). DEFERRED (told the user,
  spec'd "OR"): the cross-rate VIEW (edit °/s seeing °/m nodes);
  influence on per-meter sections refuses with the reason.

## D116. v16.6: ONE "Convert…" dialog — every combination to every
##        other (user spec, same round) + UI round items

- The badge menu's four convert items + the separate domain-convert
  action condensed to ONE "Convert…" opening an Add-style dialog:
  Whole/Part scope, Type, Domain, Roll axis, Yaw/pitch axis (grayed
  for force), Rate units — each row pre-set to the section's current
  value with a ↺ reset button. Whole commits through the density
  preview; Part closes into the split-line mode CARRYING the chosen
  output settings. Influenced sections gray Part (the D98 kept-half
  rule); reversed still refuse the dialog.
- Engine: convertToLanes grew the full flag set (type × domain ×
  rollG × ypG × rateM in ONE call — convertWholeSection,
  convertBridgeWhole, partialConvertSection/Bridge all take it).
  Gate 46e: force/time/local/°s -> geo/DISTANCE/global/°m in one
  call, dev 0.0002 m. LESSON re-learned while gating: global-axis
  targets remain undefined over NEAR-VERTICAL track (T.y ±0.95 broke
  a first fixture by 35-58 m through the documented hold-off — not
  a regression, the v16.2 cap; fixtures must stay off vertical).
- UI round items: the Orientation-settings dialog dropped its gray
  explainer (redundant with the button parentheticals, user);
  s/c + bridge strips' TRUE strokes take a MIDDLE weight
  (TRUE_STROKE_MID 0.55 — they had missed the v16.2 dimming and drew
  all-bold; user spec "between selected and unselected").

Gates: DomainGate 30 checks (45e, 46a-e new) + Phase2Gate 253, all
green 2026-07-14. Walkthrough #77.

## D117. v16.7 part 1: per-meter polish + per-meter influence
##        (user feedback round 2026-07-14, afternoon)

- **Axis units + °/m scales** (his "15 °/m lined up with 15 °/s"):
  the LEFT y-axis title now carries the unit (laneName + laneUnit);
  the RIGHT resulting axis relabels °/m and rescales (resValueAt ÷
  that sample's v) when a °/m FORCE section's overlays show rates.
  °/m lanes get their own default (minimum) scales: roll ±15,
  geo pitch/yaw ±10 (user numbers; defMin/defMax).
- **Program-wide ↺ graying + orange changed-radios** (user spec):
  wireOriginal ties every radio row in the Convert/Orientation/Add
  dialogs to its ORIGINAL selection — the ↺ grays at the original,
  a differing selection tints orange rgb(235,150,60); Options-row
  ↺ buttons gray at their defaults (optionReset overload);
  Shortcuts already HID theirs at default (kept).
- **PER-METER INFLUENCE** (user: "the influence graph should also
  be in per m — maybe this was the hangup?" — it was, and it's the
  fix): the two-pass emits base rows in °/m for °/m sections (the
  conversion math runs in °/s space and divides back down; a °/m
  rate is angle-per-meter of the FIXED ref shape, so the operating-
  speed re-expression is the IDENTITY — scale 1 — which makes °/m
  influence cleaner than °/s), delta lanes author °/m
  (activeRatePM treats every influence-surface lane as a rate),
  ref + pass-2 integrators run under the section's perMeter flag.
  Entry bitwise + a 0.5 °/m heading delta lands exactly rate × arc
  (gate 47a, 60.00°).
- **Density preview lane switching** (user spec): the 1/2/3 keys
  pass through the convert-UI key block (the toolbar buttons
  already worked); still read-only until Apply.
- **Orientation dialog**: the button parentheticals moved to gray
  captions UNDER each button (user spec; VBox per button).

## D118. v16.7 part 2: ABSOLUTE ROLL — the lane authors the bank
##        angle itself (user spec, same round)

- LaneSection.rollAbsolute (section-line field 9; requires GLOBAL
  roll — the angle IS the projection's target, local roll has no
  absolute meaning): lane 2 holds the BANK vs the horizon in
  DEGREES. 50 on the graph = 50 deg of bank there; 0 = level, no
  matter what came before. Both integrators, both domains: the
  post-step projection targets the lane VALUE at the reached
  time/arc (no integral, no entry-bank reference) and the
  within-step feed-forward uses the lane's SLOPE (rollTermPhi +
  rollSlopeAt — the D110 quality holds). Gated: a ramp-to-50-hold
  reads bank 50.000 at mid and end (47b).
- Everywhere: Add dialog "Roll behavior" row + the Convert dialog's
  — both interlocked (Absolute grays without Global roll; picking
  Local flips it back to Rate, incl. the Orientation dialog's
  Switch clearing the flag); converts to/from any combination
  (toRollAbs measures bankOfTU per sample, vertical holds the last
  defined bank, area matching skips the lane — an absolute lane IS
  the integrated quantity, pointwise fidelity is the story; gate
  47c: rate section -> absolute at 1.6 mm + byte-identical IO);
  '+' holds copy the end bank; unified view shows other strips'
  BANK on an absolute axis; unit " °", default scale ±90.
- B/Shift+B: value = the seam's measured bank (exact frame read),
  slope = the bank rate through the transport term off the sampled
  body roll — BEST-EFFORT by design: the user pre-accepted that an
  absolute lane sits one derivative below the rate lanes ("a
  perfectly equal slope of roll rate can't be achieved in many
  situations here... this is perfectly okay"). Vertical seams
  refuse with the reason.
- Influence refuses absolute-roll sections (the two-pass roll
  channel is rate-shaped); rate units don't apply to an absolute
  lane (degrees are degrees).

## D119. v16.7 part 2: the CROSS-RATE VIEW (user request, same round)

- "View rates in °/m|°/s" (badge menu, active section only): the
  strip DISPLAYS its rate lanes in the other units — values warp
  through the compiled speed profile per x — while the native lanes
  stay put; every edit writes back through the warp (the influence
  Resulting-view pattern, generalized: dispOff's additive offset
  became dispVal/nativeVal, (val + off) × rateViewScale(x), one
  choke point, 20 call sites). Session state like the domain view:
  auto-drops on leaving the section, never saved. Displayed UNITS
  govern the axis label and the default scales (laneShownPM).
- The warp is NOT frozen per gesture (unlike the domain view's
  map): v(x) barely moves under value edits — revisit only if feel
  reports sliding.
- ALSO this round: the incremental compiler had been silently
  reporting stale success (GraphEditor sat broken for several
  "BUILD_CHECKED" cycles — a missing Toggle import surfaced only on
  a forced full rebuild). Gates never load JavaFX classes, so they
  can't catch editor-side compile breaks: force-delete
  target/classes/coaster when in doubt.

Gates: DomainGate 33 checks (47a-c new) + Phase2Gate 253, all green
2026-07-14. Walkthrough #78.

## D120. v16.8: absolute-roll B seam fix (parrot + wrap), cross-rate
##        view re-presented, radio-dot highlight (user feedback on
##        v16.7)

- B ON AN ABSOLUTE LANE WAS READING ITS OWN REFLECTION: the D118
  target sampled the boundary at OUR side's edge sample — which
  sits PAST the absolute projection, already snapped to our lane's
  current value. B returned the lane to itself (never the seam),
  and the "slope" it wrote was the one-step snap TRANSIENT smeared
  by bodyRates (probe: -2270 deg/s where the honest answer was 0).
  Sole-fixture gates were blind by self-consistency — the
  lane->projection->measure loop round-trips ANY value; only a
  neighbor comparison exposes it (the D113 lesson shape again).
  Fix: absRollTarget reads the sample just ACROSS the seam
  (act.i0()-1 / act.i1()+1 — the neighbor's side, untouched by our
  projection), takes the slope ~3 samples INSIDE the neighbor
  (clear of the +-2-sample smear), and extrapolates the bank onto
  the exact boundary time (rate x dt, 0.25 deg at a 25 deg/s seam).
- THE USER'S MIRROR was the atan2 WRAP: bankOfTU returns the
  principal bank in (-180, 180], so a seam whose unwrapped bank
  story passes 180 (his CUTBACK — banking beyond a half turn)
  handed back the mirrored representative (+250 read as -110; all
  representatives are the same physical orientation, the projection
  is mod-360 — but the GRAPH is not). Fix: unwrap the returned bank
  to the representative nearest the edge node's CURRENT value, so a
  lane authored past 180 keeps its own story. Probe: lane at +250
  against a +250-unwrapped seam now matches +252.5 (the true seam
  bank), lane at 0 gets the principal -107.5 (same orientation).
- The abs branch now serves REAL-SPLIT seams too (bank is a frame
  quantity — invariant under the design/true speed split; the
  slope stays design-side, best-effort per D118's acceptance), and
  two guards keep RATE lanes from ever reading an absolute
  neighbor's lane as a rate: the same-semantics authored path
  requires matching rollAbsolute, rollAcrossSeam skips absolute
  neighbors — both fall to the sampled/world paths, which read the
  compiled truth in the right units.
- CROSS-RATE VIEW RE-PRESENTED (user picked the design after
  feeling v16.7's): the value-warp on node geometry made nodes
  disagree with everything drawn at native scale ("nodes way
  further from zero than the graph"). The warp is GONE — all
  geometry stays native — and the other units live on a RIGHT-EDGE
  AXIS (the active lane's scale re-labeled through the constant
  design-speed factor; it takes the right edge over the Resulting
  axis while on) plus the hover readout, which appends the
  EXACTLY-converted value at the cursor (v(x), not the nominal
  factor). dispVal/nativeVal reverted to the influence-offset
  contract; laneShownPM is native again (left axis, defaults,
  readouts); rateViewScale survives as the readout converter.
- Changed-radio highlight moved OFF the label text onto the radio
  DOT (-fx-mark-color / -fx-mark-highlight-color) per user spec.

Gates: DomainGate 33 + Phase2Gate 253 green on a forced full
rebuild, 2026-07-14. AbsSeamProbe (scratchpad) documents the
before/after target values. Walkthrough #79.

## D121. v16.9: absolute-roll rate STAIRCASE (two root causes),
##        cross-rate view COMPLETED as a true warp, radio-dot
##        geometry (user feedback on v16.8)

- THE JITTER/STAIR-STEPPING had two stacked causes, both in what the
  absolute lane CONSUMES (rate lanes see neither — their
  piecewise-linear VALUE is already continuous; the absolute lane's
  value gets DIFFERENTIATED by the ride, exposing the bake's
  structure):
  (1) The integrators read the baked 0.05 polyline directly as the
  bank target — a piecewise-LINEAR target has a per-cell STAIRCASE
  derivative by construction (constant rate, jump at every knot).
  Fix: FVDIntegrator.bankHermite — Catmull-Rom through the keyframe
  knots, one C1 read serving the projection target (bankAt) and the
  feed-forward slope (rollSlopeAt, same name so all call sites
  stood), both integrators, both domains.
  (2) Under the C1 read a cell-periodic wobble REMAINED (bank err
  0.107 deg, jumps 2.6 deg/s): BezierBaker.bakeLane's parameter walk
  targets ~0.1-unit steps (clamp(dt/0.1, 8, 48) per segment) and the
  0.05 grid LINEAR-FILLS between walked points — every other knot
  held a midpoint, not the Bezier value. Fix: BezierBaker.exactAt
  (the slopeAt bisection, value flavor) + TrackCompiler.exactRoll —
  absolute sections re-sample their roll channel knot-exactly after
  bake. Force/geo/time/dist all take it; nothing else changes
  (historic bitwise untouched — the resample only fires with
  rollAbsolute).
  Result: bank tracks the analytic smoothstep to 0.007-0.008 deg,
  max consecutive resulting-rate jump 3.56 -> 0.88 deg/s on the
  200-deg ramp = the true rate curvature x dt, the same smoothness a
  rate lane shows (the user's stated bar). Convert-to-absolute
  round-trips improved too (47c dev 1.6 mm -> 0.0 mm). Gate 47d.
- CROSS-RATE VIEW, the user's actual spec (three rounds converged):
  EDIT in the other units — nodes AND curve warped together, scales
  fitted to the viewed units. v16.7 warped only the node/handle/hit
  machinery (dispVal sites); strokeLaneCurve strokes RAW bezier
  control points, so the curve sat at native scale under the warped
  nodes — the v16.8 "which one is right?" answer unwarped the nodes,
  the wrong way around. Now: dispVal/nativeVal carry the warp again;
  strokeLaneCurve gets a warp branch (baked polyline through dispVal,
  sampled hold continuation — a constant native value is a CURVE in
  the other units; locked segments lose only their twin-line styling
  there); updateScale fits DISPLAYED values (so the deg/m defaults
  roll +-15 / pitch-yaw +-10 govern and the data always fits);
  derivedDisp/trueDisp wrap derivedAt/trueAt for inactive strips,
  true strokes and hovers (B-matching keeps reading the NATIVE
  helpers — a session view must never change what B writes);
  the right-edge axis flipped to show the NATIVE units beside the
  warped main scale; hover/drag readouts lead with the displayed
  value, hover appends the exact native one.
- Radio-dot highlight: only -fx-mark-color tints now — overriding
  -fx-mark-highlight-color too repainted the dot's second background
  layer and subtly changed its shape vs the default black dots.

Gates: DomainGate 34 (47d new) + Phase2Gate 253 green on forced full
rebuilds, 2026-07-14. StairProbe/StairProbe2/KfProbe (scratchpad)
document the staircase numbers. Walkthrough #80.

## D122. v16.10: B/Shift+B per-meter double-divide (the sampled path),
##        1 deg/m snap step, gray-out hover explainers (user feedback
##        on v16.9)

- B/SHIFT+B COLLAPSED PER-METER TARGETS TO ~ZERO on every seam that
  takes the SAMPLED path: boundaryStatePerSec's tail read the
  neighbor through derivedAt/trueAt, which since v16.6 PRE-SCALE
  rates by 1/v for an active deg/m lane — and boundaryState's
  wrapper, whose contract is "the workhorse speaks deg/s", then
  divided by the seam speed AGAIN. A ~10 deg/s pitch target became
  10/26/26 ~ 0.015 deg/m: the user's geo pitch node "went to zero
  with horizontal handles" instead of continuing the hill. His seam
  (abs-roll force -> abs-roll geo x deg/m) routed lanes 0/1 there
  because a global-roll neighbor's globalAxes() disqualifies the
  analytic crossModeFromLanes path; the same collapse hit ALL
  sampled-path matches on deg/m lanes — global-axis, reversed,
  influenced, s/c and bridge neighbors, and real-split seams. The
  analytic/authored paths were correct all along (they return deg/s
  per the contract), which is why deg/m gates and simple same-mode
  seams never showed it. Fix: sampledPerSec — derivedAt/trueAt's
  sibling reading the raw resultRates/trueRates/felt arrays (bank
  kept for the abs lane) — feeds the tail; display helpers stay
  display-only. The roll lane was immune in the user's repro only
  because ABSOLUTE roll takes absRollTarget and skips the wrapper.
- SNAP: a rate lane DISPLAYED in deg/m steps by a new Options value
  "Snap: per-meter rate (deg/m)", default 1 (user spec — deg/m
  values live near +-3, where the 5 deg/s grid was a whole turn of
  range). laneShownPM picks the step, so the cross-rate view counts
  (Ctrl-snap grids live in display units, the D121 rule); deg/s
  lanes and the absolute BANK lane (degrees, not a rate) keep the
  5 deg/s step. AppSettings.snapRatePM, snap125 spinner + reset row
  in Options.
- GRAY-OUT HOVER EXPLAINERS (user spec) in the Convert and
  Add-section dialogs: hovering a disabled choice says why it is
  gray and how to enable it — Absolute bank (needs Roll axis
  Global), the Yaw/pitch radios (need Type Geometric), Part…
  (influenced sections: bake or discard first), and the padlocked
  Design-speed field (click the padlock; custom = deliberate
  conflict). Disabled JavaFX controls swallow mouse events, so
  GraphEditor.grayHint wraps each in an enabled StackPane and
  installs the tooltip only WHILE disabled — enabled controls stay
  tooltip-free. Reset (undo-arrow) buttons deliberately carry no
  hint: their gray means "already at this setting".
  (A shift+drag-a-node = value-only draft shipped briefly in this
  round and was REVERTED on user instruction — the ask had been
  meant for the 3D viewport's coming gizmo work, not the graph.)
- FLY-LOOK VERTICAL DRIFT (the user's "view gradually moves upward
  when I move my mouse side to side"): flyLook measured every
  delta against the IDEAL subscene centre — usually FRACTIONAL in
  screen coords — while Robot.mouseMove parks the cursor on a
  whole device pixel. The cursor therefore rested a constant
  sub-pixel off the reference, and because the own-warp guard only
  skips events quiet on BOTH axes, sweeping the mouse sideways
  applied the dy bias at full event rate (~0.5 px x sens per
  event = steady pitch). Fix: warpFlyCursor records where the
  cursor ACTUALLY landed (Robot.getMousePosition) and the next
  delta measures from that — bias exactly zero at any DPI scale.
- B/SHIFT+B TOWARD AN ABSOLUTE-BANK NEIGHBOR REFUSES (user rule):
  from the section BEFORE an abs-roll section, end-edge matching
  posts "press B/Shift+B on the OTHER side of the boundary (its
  first node)" instead of acting — the abs section's entry state
  is enforced by its OWN bank lane (D118), so our-side targets
  would read its projection snap; the good seam flow is matching
  FROM the abs section's first node (which reads the neighbor
  side, D120). Applies to all three lanes, both B and Shift+B,
  nextAbsRoll()/ABS_NEXT_HINT in matchBoundary/matchBoundaryAll.

Gates: DomainGate 34 + Phase2Gate 253 green, 2026-07-14 (the fixes
are editor/viewport-side; the gates pin no regression in the core).
Hand-test: walkthrough #81.

## D123. v16.11: the VIEWPORT ROUND — placement gizmos, middle-drag
##        pan, white active-section ties, clickable node markers
##        (user spec, start of the interactive-viewport arc)

- MIDDLE-DRAG = PAN (user spec): grab-style in the view plane,
  shared math with the v8.2 ortho right-drag pan (panView — world
  meters per pixel off the CURRENT lens, so ortho and perspective
  feel identical); the v8.2 middle-drag ORBIT alias is gone, left
  drag still orbits, shift+left-click still snaps the pivot to the
  track. An ortho view SURVIVES a middle-drag pan like it does the
  right-drag one.
- WHITE TIES mark the ACTIVE SECTION on the 3D track (user spec:
  "cross ties of a selected section turn white"): buildTieMeshes
  splits each piece's ties into a normal and a white mesh by the
  active span's GLOBAL sample range; a per-piece TiePiece context
  (sublist + base index) lets refreshTieHighlight re-tint on pure
  activation changes without recompiling (signature-guarded, rides
  refreshTrackMarkers like the markers themselves).
- NODE MARKERS ARE CLICKABLE (user spec): a clean left-click within
  14 px of a marker sphere selects that node in the graph
  (selIdx + selSet) and, when a manual zoom window has it
  off-screen, re-centers the window on it (auto-fit already shows
  everything). Marker pick outranks the track-activate click;
  behind-camera projections filtered like pickTrackSample.
- PLACEMENT GIZMOS (the Phase-2 roadmap item, user re-spec of
  2026-07-15): the ACTIVE piece's entry pose wears translation
  arrows (X red / Y green / Z blue) + rotation rings; while a live
  BRIDGE section is active the gizmo sits on the NEXT piece's entry
  instead. THE LOCK RULE (user spec): an axis that is locked or
  machine-owned builds NO part — a default piece shows 3 arrows +
  the yaw ring only (pitch/roll rings appear when their Placement
  padlocks unlock; bridged-in pieces read the bridge's shared
  locks); a piece bridged-in under an OPTIMIZE mode shows no piece
  gizmo at all (the auto-reoptimize pass would fight every hand
  move — the bridge's own gizmo is the surface). Bridge gizmo per
  mode: manual = arrows + unlocked rings (setBridgeTargetOrient /
  a new one-rebuild nudgeBridgeTarget composite); optimize modes =
  ONE orange TANGENT arrow dragging the LENGTH through setBridgeGap
  (user spec — replaces position arrows; hidden under Optimize
  length; optimize-orientation owns the unlocked rings too, so
  none show). Undo: piece gestures push once per drag; bridge parts
  ride the spinners' coalesced clock. Drags rebuild the track live
  per event (the spinner/tick precedent).
- ROTATION RINGS turn the MODEL's own Euler quantities — yaw about
  world Y, pitch about the heading's horizontal right, roll about
  the heading (exactly what the Placement spinners edit, one ring =
  one number) — so every ring stays representable under any lock
  combination. JUDGMENT CALL: the user asked for a local/global
  toggle on "both roll and translation"; a fully world-axis pitch/
  roll rotation isn't expressible in the yaw/pitch/roll delta model
  without cross-coupling locked quantities, so the AXES TOGGLE
  (overlay button, top-right of the 3D view, persisted as
  AppSettings.gizmoLocal) flips the TRANSLATION arrows between
  world X/Y/Z and the entry frame's right/up/forward, while rings
  keep the model axes. Toggling moves nothing (pure view) — the
  user's stated invariant. Revisit on feel-test if he wants ring
  axes to flip too.
- Gizmo mechanics, for the next reader: parts live in gizmoGroup
  inside `world` (Y-flip discipline: positions (x,-y,z), directions
  flip y). Scale pivots at the gizmo ORIGIN via an explicit Scale
  transform — the scaleX/Y/Z PROPERTIES pivot at the layout-bounds
  centre and slid the asymmetric arrow set off its anchor — and
  tracks ~10% of the visible height at the anchor's distance
  (lens-generic; re-scaled on zoom/orbit/pan). Arrow drags project
  the axis into SCREEN space and convert pixel deltas to world
  units, capped at one gizmo-scale per event (an edge-on axis
  projects tiny and λ exploded). Ring drags intersect the mouse ray
  with the ring plane and track the hit angle; the scene is a
  Y-MIRROR of the world, so the world delta is the NEGATED
  scene-measured delta (conjugation by a reflection flips
  handedness) — if a ring ever feels reversed, that one sign in
  wireRingDrag is the tunable. Parts consume their mouse events
  (the orbit/click handlers never see them); a mid-drag refresh
  only repositions (rebuilding the pressed part would kill JavaFX's
  mouse capture); signature-guarded rebuilds ride
  refreshTrackMarkers.

Gates: DomainGate 34 + Phase2Gate 253 green (all viewport-side —
the gates pin no core regression). Hand-test: walkthrough #82.

## D124. v16.12: viewport feedback round — middle-drag ORBIT,
##        gizmo reveal + absolute drags, live re-aim, ctrl snap
##        (user feedback on v16.11)

- MIDDLE-DRAG = ORBIT after all (user correction of his own v16.11
  pan spec): it REPLACES left-drag entirely — the primary button is
  selection/gizmos only now — and shift+middle-click (or C+middle)
  snaps the pivot to the track. The pan is gone (panView survives
  for the ortho right-drag). Node insert moved from Ctrl+click to
  DOUBLE-click, and it scrolls the graph to the new node like a
  marker click (ensureSelectedVisible, shared).
- GIZMO REVEAL (user spec: "only reveal when you click first node
  of track piece"): gizmos hide behind REVEAL MARKERS — a bigger
  sphere (0.34 vs 0.22) at the active piece's entry and, with a
  trailing bridge, at the bridge end. Clicking toggles; the reveal
  shows BOTH the entry gizmo and the trailing bridge's end gizmo
  at once, whatever section is active (multi-Rig rework — each rig
  owns its anchor/scale/re-aim hooks); switching pieces re-hides.
  The bridge gizmo therefore stopped requiring the bridge SECTION
  to be active: new PIECE-scoped write paths (trailingBridge —
  gizmoSetBridgeGap/gizmoSetBridgeOrient/gizmoBridgeMoved, no undo
  push of their own; the viewport pushes once per gesture; the
  OPT_POS+optLen orient path re-knees SILENTLY — applyOptimal-
  Length's zero-distance PROMPT must never pop mid-drag). Clicking
  the entry marker open with the first section active also selects
  lane node 0 (it covers that node's marker).
- ABSOLUTE-FROM-PRESS DRAGS (user bug: "optimize position makes
  the yaw gizmo go crazy — rotating moves the gizmo, which adjusts
  the rotation, which moves the gizmo…"): every drag now captures
  its screen mapping AND base value once at the press. Arrows: λ =
  mouse travel projected on the press-time screen axis, pose =
  base + dir₀·λ. Rings: LINEAR drag (user spec) — the press grabs
  the ring's screen tangent at the hit point and the rotation is
  proportional to distance moved along that fixed line (1 grabbed
  ring-radius ≈ 57°, like dragging the rim), value = base − dist·k
  (the one Y-mirror sign). A part that re-places ITSELF per event
  (optimize-position bridges) can no longer feed back into its own
  input. Ctrl SNAP (user spec): translation + bridge length by the
  element-length step (absolute along-axis grid / whole-step
  lengths), rings to 1°; the bridge YAW spinner's arrows step 1°
  now too (pitch/roll already did).
- LIVE RE-AIM (user: "appearance only updates after releasing"):
  parts keep a Rotate whose axis/angle re-derive from live
  direction suppliers — positionGizmo runs the aim hooks per
  event, so rings/arrows track the pose in real time; rebuild
  still waits for release (replacing the pressed node would kill
  JavaFX's mouse capture). THICKER parts (shaft 0.05, ring minor
  0.06, cone 0.13 — grabbing needed too much aim). DEPTH-based
  scale (anchor·view-axis, not euclidean distance): the old
  distance scaling ballooned the gizmo toward the screen edge
  (off-axis wide-FOV magnification). Gizmos STAY VISIBLE in fly
  mode (POV still hides them); fly timer/look reposition them.
- COLORS (user spec, matching the graph): arrows X red / Y blue /
  Z green (blue↔green swapped); rings wear GEO_LANE_COLORS — yaw
  yellow, pitch teal, roll the roll-lane orange-red.
- WHITER TIES: the active section's ties self-illuminate now — a
  diffuse-only white still rendered grey under the dim ambient.
- UNDO vs PADLOCKS (user bug): applyDocState rebuilt TrackPieces
  whose uiLockPitch/Roll DEFAULT to locked, so any undo slammed
  open padlocks shut. The locks ride PieceState now — Ctrl+Z after
  a gizmo move keeps them open, and they relock only when undoing
  past the point they were actually locked (their toggles aren't
  undo steps, so the state simply rides neighboring snapshots —
  exactly the user's stated expectation). Still not persisted.

Gates: DomainGate 34 + Phase2Gate 253 green (viewport-side round).
Hand-test: walkthrough #83.

## D125. v16.13: View menu, force/speed COMBS, viewport tweaks
##        (user feedback on v16.12 + new features)

- TWEAKS: gizmo arrows are X GREEN / Y blue / Z RED now (the v16.12
  red/green swapped, user spec); a clean click on EMPTY space hides
  the gizmos (selection stays — the reveal already reset on piece
  switches); the HEARTLINE builds one strand PER PIECE like every
  other mesh (the single ride-wide strand only broke on the 3.5 m
  gap heuristic, so pieces parked close together read as connected);
  the Placement rows GRAY whatever a connecting bridge owns —
  position under any optimize mode, yaw when locked/march-aligned —
  with grayHint explainers pointing at the bridge (they silently
  fought the auto re-optimize before).
- VIEW MENU (user spec: "basically everything to do with the
  viewport"), between Edit and Coaster: gizmo axes local/global
  (synced with the overlay button through one applier), show
  heartline, node markers, the three comb toggles, "G-force & comb
  colors…", both FOV sliders and mouse sensitivity — the last four
  MOVED out of Edit > Options (which keeps the graph-editor snaps
  and the POV marker). Rows are CustomMenuItems (menu stays open);
  the sliders reuse the Options wiring verbatim (menu-local undo
  included).
- COMBS (user spec, FVD++-style): one tooth per meter of track,
  per piece. VERTICAL felt G — outward along −U (loops comb their
  outside), length 0.5 m/g, colored by the SAME four stops as the
  rail gradient (one set drives both). LATERAL felt G — along R,
  signed, 0.8 m/g, its OWN four color stops (lateral lives near
  ±1.5 g; per-coaster `latcolors`/`latstops` lines, missing =
  defaults, lateral clamp ±5 — the vertical −1..5 clamp would eat
  the −1.5 default; the round-trip gate carries them). TRUE SPEED —
  upward along U, 0.05 m per m/s, solid light blue. Values read the
  TRUE arrays (identical to design until a split). Hovering a
  tooth's TIP (12 px) shows the exact value in a cursor-following
  overlay label (combLabel, unmanaged in the subHolder StackPane).
  Length cap 3.5 m; teeth under 2 cm skip. All constants
  (COMB_SPACING/COMB_*_SCALE/COMB_MAX) deliberately internal.

Gates: DomainGate 34 + Phase2Gate 253 green — Phase2Gate's
round-trip fixture now includes the lateral stop lines (the gate
scratch file grew the two Cosmetics args). Hand-test: walkthrough
#84.

## D126. v16.14: comb/gradient feedback round — exact tie/comb
##        spacing, three-way combs, 5-stop gradient, View-menu rail
##        color, Snapping (user feedback on v16.13)

- THE SPACING BUG (user: "spacings significantly more than the
  others… around almost the entire time crossties have existed"):
  ties and combs placed themselves on the first SAMPLE at/past each
  arc grid point — up to v·dt ≈ 0.26 m late per item, so adjacent
  gaps differed by up to half a meter. It was the documented
  quantize-to-sample quirk, not a section/piece interaction. Fix:
  arcHit — frames (pos/T/U lerped + renormalized, R = U×T) at the
  EXACT grid arc between bracketing samples; ties and comb teeth
  both build through it, comb VALUES lerp the TRUE arrays the same
  way. The white-tie highlight keys off the NEAREST sample index.
- COMBS: spacing halved to 0.5 m (user spec); teeth grow from the
  HEARTLINE (was the spine); lateral teeth point rider-LEFT for
  +Gl (reversed, user spec); a THREE-WAY mode replaces the three
  checks (user spec: No comb | G-Force comb = vertical + lateral
  as one unit | Speed comb — never both; AppSettings.combMode,
  old keys ignored); "Comb size (%)" scales teeth (applied on
  slider release — per-pixel recompiles are pointless); teeth are
  in a combGroup HIDDEN while riding POV and are MOUSE-TRANSPARENT
  (they block nothing — double-click-to-insert works through
  them). HOVER FIX: the v16.13 readout label was UNMANAGED in the
  subHolder StackPane, so it was never laid out to a size — 0×0,
  literally invisible (the user's "hover isn't working") — and it
  measured to the tooth's TIP point only. Now: a MANAGED label
  pinned BOTTOM-LEFT (user spec) and hover distance measured to
  the tooth's projected root→tip SEGMENT (10 px).
- GRADIENT: FIVE stops now — PURPLE (−1.5) joined below the BLUE
  (moved 0 → −0.7), all editable, one set driving rails AND the
  vertical comb; the render range widened −1 → −2 (G_MIN) so the
  purple shows. Legacy 4-stop gradcolors/gradstops lines load with
  the default purple PREPENDED (old files' flat-blue negatives now
  shade toward purple below −0.7 — deliberate, the user made
  purple the default). LATERAL stops are ABSOLUTE now (user spec:
  colors mirror about zero): three |Gl| stops, defaults green 0.5 /
  yellow 1.0 / red 1.5; latcolors/latstops = 9 + 3 values (the
  v16.13 12 + 4 shape never shipped). stopColor() is the shared
  length-generic lerp.
- VIEW MENU: RAIL COLOR moved in (user spec) — gradient|solid
  radios + the solid ColorPicker inline (visible only while solid)
  with a ↺ to the default red; showRailColorDialog is GONE (its
  gradient stops already lived in "G-force & comb colors…", which
  now shows 5 + 3 rows, EVERY color with its own ↺ grayed at
  default — user spec "swirly arrows to revert"). WATCH ITEM: a
  ColorPicker inside a menu popup once crashed on "Custom Color"
  (owner = auto-hiding popup, the pre-v7.5 lesson) — the row rides
  CustomMenuItem(hideOnClick=false) now; if his test still crashes
  it, fall back to a mini owned dialog. FOCUS FIX (user bug): after
  Save/Use in the custom-color child window, focus fell to another
  OS window — refocusAfterPicker pulls our stage back to front on
  the next pulse (all pickers).
- Edit > "Options…" renamed "SNAPPING…" (user spec) — after the
  viewport rows moved out, snapping is all that's left; the POV
  graph marker option is GONE, always on (user spec).

Gates: DomainGate 34 + Phase2Gate 253 green (the round-trip fixture
re-shaped to 5 + 3 stops). Hand-test: walkthrough #85.

## D127. v16.15: node hover/dialog twins, POV reverse, comb + color
##        polish, custom-color focus ROOT fix (user feedback on
##        v16.14, closing the pre-Phase-2-finish punch list)

- NODE DIALOG COMPUTED TWINS (user spec): grey labels beside the
  Transition and Value fields update live as you type — the
  ABSOLUTE display-axis position the transition lands on
  (tOff + warpFwd(prev.t + dt)) and the CHANGE from the previous
  node. NODE HOVER (user bug: the hover stat vanished over a node
  body): a node under the cursor now wears a GREY ring (white
  stays the selection ring) and a grey drag-style readout —
  Δt-from-previous + value; SHIFT swaps in the twins (absolute
  position + Δvalue). hoverNodeIdx rides the same
  signature/redraw plumbing as the other hovers.
- POV REVERSE (user spec): AppSettings.povReversed — the camera
  looks along −T (up stays U, right re-derives, handedness kept);
  A toggles it while the ride is CAPTURED, A-again-or-D returns
  forward, and the View-menu check shares ONE applier
  (FvdDesigner.setPovReversed; forwarded from popup scenes too).
  W/S deliberately keep their TRACK direction — reversed W reads
  as riding backward, per the user's exact spec.
- COMBS: teeth 2× wider ALONG the track (HW_T 0.06 vs the 0.03
  cross-thickness). VERTICAL GRADIENT gains CYAN at −0.5 with blue
  moving to −1 — SIX stops (gradcolors/gradstops 18+6;
  DesignIO.gradSlot maps legacy: 15+5 keeps the default cyan
  inserted at slot 2, 12+4 fills slots 2-5). The View item is
  "G-FORCE COLORS…" with the letters of "G-Force" tinted by the
  LIVE gradient hottest-first (rebuilt every menu show, so edits
  re-tint it; 6 letters = 6 stops).
- CUSTOM-COLOR FOCUS, ROOT CAUSE (user: "flashes to another window
  before coming back"): the JavaFX Custom Color window is owned by
  the ColorPicker's POPUP, which has long auto-hidden by the time
  Save/Use closes it — Windows then activates the next application
  in Z-order, and v16.14's Platform.runLater(toFront) yanked us
  back a frame LATER (the visible flash). Now a Window-list
  watcher in FvdDesigner.start spots every custom-color dialog by
  its root style class, remembers which of OUR windows was focused
  when it appeared, and hands focus straight back in the dialog's
  ON-HIDING handler — before activation ever leaves the app. The
  refocusAfterPicker band-aid is deleted.

Gates: DomainGate 34 + Phase2Gate 253 green on a forced full
rebuild (the gate's Cosmetics fixture moved to 6 gradient stops);
launch smoke test clean. Hand-test: walkthrough #86.

## D128. v16.16: OWN color dialog (the flash, actually killed), View
##        slider resets, backwards-POV tag, readout twins everywhere
##        (user feedback on v16.15)

- THE FOCUS FLASH, FOR REAL THIS TIME (user: "why couldn't this
  window work the same way as other windows?" — exactly the right
  question): the built-in ColorPicker's Custom Color window is
  owned by the picker's transient POPUP, so by the time Save/Use
  closes it the owner is long hidden and WINDOWS decides who gets
  activation — another app. Both post-hoc fixes lost that race
  (v16.14 runLater-toFront = the flash; v16.15's on-hiding watcher
  = still a flash). v16.16 removes the cause: there is NO
  ColorPicker in the app anymore. Colors are SWATCH buttons
  (Dialogs.colorSwatch, face = the current color) opening
  Dialogs.pickColor — an ordinary WINDOW_MODAL Stage owned by OUR
  window — a QUICK PALETTE (three shades of ten hues + a
  grayscale row) and the last TEN picked colors (per-user,
  AppSettings.recentColors, newest first, deduped) above RGB
  sliders + a hex field, LIVE apply (gradient and track update as
  you slide), OK keeps / Cancel + X restore, one menu-undo pair
  per session. Owned window ⇒ focus returns like
  every other dialog — nothing to intercept. Bonus: the pre-v7.5
  "Custom Color from a menu row" crash class is extinct, and the
  rail swatch re-syncs on menu open (Load/New change railColor
  outside it; railSyncing guard keeps the re-sync from dirtying).
- View menu SLIDERS wear ↺ resets (both FOVs, sensitivity, comb
  size — AppSettings.COMB_SCALE_DEFAULT joins the constants).
- "Backwards POV (A)" tag (user spec): povLabel, the comb
  readout's styling, bottom-left, visible exactly while POV rides
  reversed (combLabel can't collide — comb hover bails in POV);
  synced from togglePov + setPovReversed.
- READOUT TWINS everywhere: SHIFT while DRAGGING a node swaps the
  readout to the absolute axis position + Δ-from-previous (the
  shift+hover format, live via shiftHover's redraw now covering
  drags); CTRL while hovering the curve makes the insert-dot
  readout LEAD with the snapped spot's GLOBAL axis position — you
  see where the grid point sits before clicking.

Gates: DomainGate 34 + Phase2Gate 253 green on a forced rebuild;
launch smoke test clean. Hand-test: walkthrough #87.

## D129. v16.17: hue/saturation PLANE picker (user feedback on
##        v16.16's color dialog)

- The RGB sliders in pickColor are REPLACED (user spec) by a HUE ×
  SATURATION plane — hue across the horizontal, FULL saturation at
  the top — beside a VERTICAL brightness slider, with the RGB
  values now TYPED (three int fields, 0-255, live like the hex
  field). The plane paints at full brightness so hues stay
  readable wherever the slider sits (the preview shows the true
  color); its marker is a white+black double ring, visible on any
  background. The working color lives as HSB so GRAYS keep their
  hue while sliding brightness/saturation (a pure-RGB round trip
  snapped the marker to hue 0). Every input path — plane, slider,
  typed RGB, hex, palette, recents — funnels through one guarded
  syncAll; live apply, OK/Cancel/recents semantics unchanged
  (v16.16). Palette + recent rows stayed exactly as shipped.

Gates: DomainGate 34 + Phase2Gate 253 green on a forced rebuild;
launch smoke test clean. Hand-test: walkthrough #88.

## D130. v16.18: mid-section NODE-ANCHORED SPLIT + the node
##        right-click menu — PHASE 2 COMPLETE

- "SPLIT SECTION" / "SPLIT TRACK PIECE" at a node (user spec; the
  roadmap's last Phase-2 item): Coaster.splitSectionAt cuts a lane
  section in two at a node's local x — all three lanes through the
  splitLanes de Casteljau identity / node partition (the D89
  machinery, now with BOTH halves), right half's vDesign = the
  design speed AT the cut, axis/rate/roll flags + vPinned onto both
  halves; reversed/influenced/s-c/bridge refuse. The piece variant
  chains the classic splitPiece at the fresh boundary (D71 rigid
  pose copy). GATED: split == authored-as-two EXACTLY (0.0 m) and
  piece variant == section split (0.0 m), Phase2Gate 258.
- THE HONEST CONTRACT, learned by gating: the first gate draft
  compared against the UNSPLIT original and FAILED at 2 cm — the
  probe chain showed the left half's last sample landing one dt
  short. Root cause is PRE-EXISTING and universal: integrateTrack's
  `while (t <= totalTime)` walks an accumulated t whose float sum
  overshoots the authored end, so EVERY time-domain section
  truncates to the sample grid and every seam absorbs up to one dt
  of authored profile (the CLAUDE.md "authored section ends
  truncate to the sample grid" note, now understood mechanically).
  A split therefore cannot equal the unsplit section — it equals
  AUTHORING TWO SECTIONS, which is the operation's actual meaning;
  vs the unsplit original it sits inside the standard seam bound
  (2.2 cm on the gate's violent ±40°/s fixture, invisible against
  14 cm rails). Fixing the loop epsilon would change historic
  bitwise output everywhere — deliberately NOT done.
- NODE RIGHT-CLICK MENU (user spec): right-clicking a node (or a
  selection) opens its OPTIONS menu — Edit node…, smooth/broken or
  boundary match on edges, match-all-lanes, level, level-lock,
  straighten, straight-lock, the two SPLITS, delete — with LIVE
  shortcut labels in parentheses (rebinds show up) and items grayed
  where they can't apply. "Split track piece" on a section's FIRST
  node routes to the classic boundary piece split, so the menu is
  the one surface for every split. v10.7's right-click dialog lives
  on as "Edit node…" (double-click still opens it directly); the
  segment lock menu is untouched. Editor bookkeeping: the editor
  stays on the LEFT half, boundary node selected, one undo step.
- Also flagged while here (background task): partialConvert's KEPT
  half drops the axis/rate/abs flags — the D89 "keeps its
  representation exactly" rule needs the same four copies
  splitSectionAt does.

Gates: DomainGate 34 + Phase2Gate 258 (nodeSplit new, 5 checks)
green on forced rebuilds; launch smoke test clean. Walkthrough #89.
PHASE 2 IS COMPLETE — next: Phase 3 (speed sections, friction).

## D131. v16.19: CONNECT sections (the split's inverse), brightness-
##        aware picker plane (user feedback on v16.17/18)

- COLOR PICKER (user spec): the hue/saturation plane paints at the
  CURRENT brightness now (v16.17 drew it at full brightness — the
  user wants the plane to darken with the slider); readability
  moves to the SLIDER itself: its track wears a black→hue/sat
  gradient and its thumb centre wears the selected color (both via
  lazy .track/.thumb lookups — the skin substructure only exists
  after the first CSS pass, so a runLater re-tints after show and
  every syncAll pass re-applies).
- CONNECT TWO LIKE SECTIONS (user spec; the node split's inverse):
  Coaster.mergeSections joins adjacent sections sharing type,
  domain and every axis/rate/roll flag (reversed/influenced
  refuse); offered as "Connect with previous/next section" on the
  edge nodes' right-click menu, grayed otherwise. The second's
  lanes append shifted by the first's duration; where BOTH sides
  own a seam node they become ONE: a MATCHED seam (equal value +
  slope — what B leaves) merges losslessly, an unmatched one takes
  the AVERAGE value and the AVERAGE handle slope on both handles,
  smooth (the user's rule); held lanes just append (the hold
  becomes a real segment). Merged section keeps the FIRST's
  vDesign + pin. GATED: split → connect ROUND-TRIPS the original
  EXACTLY (0.0 m, sample-count equal — the seam's grid-truncation
  dt comes back when the seam goes away, closing the D130 loop);
  the averaging fixture lands 0.6/1.4 → 1.0 smooth; force+geo
  refuse. Phase2Gate 261.

Gates: DomainGate 34 + Phase2Gate 261 green on forced rebuilds;
launch smoke test clean. Hand-test: walkthrough #90.

## D132. v16.20: connect hold-lane fix, node menu hides instead of
##        graying, badge-menu connect copy (user feedback on v16.19)

- THE "CONNECT AFTER SHIFT+B CHANGES THE SLOPE" BUG — TWO stacked
  causes. (1) SIGN: BezierBaker.effInDt is NEGATIVE (−inFrac·gap),
  so the merge's `inDt > 1e-12` guard never passed and every
  in-slope read as a phantom 0 — sloped B-matched seams failed the
  matched test and "averaged" against the phantom flat side,
  HALVING the slope (the user's "the average slope was not
  accurate"). Guards are on |dt| now; the gate fixtures were
  flat-handled and blind — the split node carries a real slope
  since (sloped round-trip still 0.0 m). (2) DEAD HANDLES: a lane
  ending before the seam keeps its last node's OUT fields (and the
  next section's node-0 IN fields) as dead data, and the held
  branch appended them verbatim, reviving stale slopes into the
  join. Held joins now run the SAME averaging rule as everything
  else (user spec): the held side contributes its held value and a
  FLAT slope — seam value = mean(held, node), slope = sOut/2 on
  both handles, smooth — so a B-matched hold still merges
  losslessly, and the gate's dirtied-dead-handle + held-averaging
  fixtures (40/10 → 25, slope-halved handles) pin both routes.
- NODE MENU: inapplicable ops DON'T APPEAR instead of graying (user
  spec — the menu is what you can do here). Lock entries retitle
  as unlock when the adjacent segment is already locked and show
  for lone endpoints of locked runs too; DELETE lost its redundant
  "(Delete)" tag and never shows on a section's END nodes (user
  rule; node 0/two-node lanes refuse in deleteSelected anyway, and
  the Delete KEY still shortens the ride from the last node).
- BADGE MENU gains a "Connect with previous section" copy that
  GRAYS when unavailable (user spec: the node menu hides, the
  badge menu is where you discover the op exists) — safe to key
  off the active section because badges are two-click (v16.4).

Gates: DomainGate 34 + Phase2Gate 261 green (the strengthened
connect fixture); launch smoke test clean. Hand-test: walkthrough
#91.

## D133. v17.0: PHASE 3 OPENS — friction (two coefficients) + the
##        reversed-section design-direction scheme (user spec
##        2026-07-17)

- THE MODEL (SECTIONS-DESIGN §7; NL2 calls the pair "friction" and
  "air resistance"): deceleration = fricRoll·g (rolling,
  dimensionless, constant per meter — the §7 first cut, no
  normal-force scaling yet) + fricAir·v² (air, 1/m; the Coaster
  menu edits it per KILOMETER for readable spinner steps).
  Per-COASTER: Coaster menu rows, `friction` line in FVDD3,
  document undo (they reshape the track). Defaults 0 0 = the
  historic loss-free model.
- PLUMBING: speed keeps its algebraic energy form MINUS a LOSS
  deficit (v² units) that joined the RK4 state of all three
  integrators in both domains — v = sqrt(v0² + 2g(y0−y) − loss).
  With zero coefficients the deficit is exactly 0.0 and x − 0.0 is
  bitwise x: every pre-friction fixture compiles identically
  (Phase2Gate 262 + DomainGate green ARE the bitwise-off gate).
  Profile re-computations march the same deficit: passD (Heun over
  the sample arc, always TRAVEL order), convertToLanes' re-time —
  via the new INSTANCE Coaster.fitLanes wrappers; the static
  convertToLanes tower stays loss-free for probes, and new
  in-document converts MUST route through fitLanes or they go
  friction-blind — influencedLane's operating profile, and the
  bridge's blendedV (a lossTable over its geo rows; the vB ease now
  absorbs only genuine seam mismatch, not friction too).
  Deliberately loss-free: the bridge length optimizer's rateCost
  ladder (a RELATIVE smoothness ranking — friction shifts every
  candidate alike; the APPLIED geometry is friction-aware).
- REVERSED SECTIONS (the user's kickoff spec, implemented as
  written): with friction the speed at a point genuinely differs
  by travel direction, so a reversed section can never line up —
  ACCEPTED, surfaced, and resolvable:
  · Piece reversal NEVER touches the design profile — the track
    stays put (gated 5e-14 m) — so the design keeps running the
    AUTHORED direction and its speed DISPLAYS NEGATIVE wherever
    design direction opposes travel (sign rule: negative iff
    designRev != reversed). Read along the ride it "loses energy
    negatively" — a flat reversed section's design speed grows
    toward the travel exit, exactly his phrasing.
  · Reversed sections stay OUT of the auto-reconcile AND B's seam
    re-freeze (new skip): their anchor moves only by explicit
    action; under friction they are permanently ≠v — by design
    ("will of course always be speed conflicted").
  · RE-FREEZE DESIGN SPEED now works on reversed sections: design
    direction := travel (Section.designRev — force/geo line field
    10, s/c line field 6, missing = authored), vDesign re-anchors
    at the TRAVEL entry reading the SEAM sample itself (the
    continuation span drops its entry sample, D84 — reading one dt
    inside left a permanent one-step friction offset), lanes
    untouched. Generation still marches the authored axis: the
    profile integrates with friction REVERSED (deficit negative —
    it gains along the march exactly what the real train sheds)
    and TrackCompiler.solveEndAnchor BISECTS the start energy so
    the authored end lands on vDesign (~60 full-section probes per
    compile; only re-frozen reversed sections pay it). Afterward
    design == true pointwise (gated 1.8e-7 m/s) and a re-press
    moves nothing (gated 0.0).
  · MAKE GRAPHS REAL now works on reversed LANE sections:
    re-authors in the TRAVEL direction at the true entry speed —
    the span samples are already train-ordered, so the standard
    fit produces forward lanes and reversed/designRev clear.
    Geometry kept to the fit residual AT A MATCHED SEAM (gated
    2.7 cm at matched arc); a KINKED reversed seam (pitched
    lead-in exit — reverseAndPlace matches heading only) is
    legitimately consumed by the smooth re-author. Reversed s/c
    refuses make-real (elements have no mirrored re-author; the
    resolve submenu explains) — re-freeze covers it.
  · designRev survives piece reversal untouched (double reversal
    round-trips, gated 1e-13 m), so an UNREVERSED section can
    carry designRev (design opposing travel again → negative
    display); it is excluded exactly like reversed from reconcile,
    converts, domain switch, bakes, split and merge (the resolve
    ops are the exits), and influence falls back to the plain path
    on it (the rollAbs precedent).
- s/c under friction: the SPINE is friction-invariant — RATE-0
  roll gated 3e-7 m; GLOBAL-bank elements re-project per
  integration leg, whose cadence is speed-dependent: ~8 mm of
  O(dt²) noise on the 61 m fixture, gated under 5 cm and accepted
  (the projection cadence, not a model error). The clock, felt
  records and RATE-roll twist move with friction (per-second by
  design).
- FIXTURE LESSONS (three gate rounds): a reversed fixture must be
  TRAVERSABLE BACKWARD — a diving shape run in reverse CLIMBS and
  stalls, and no resolution can close a stalled split (the
  "fixtures must dive first" lesson, mirrored). And compare refit
  geometry at matched ARC, not sample index — make-real re-times
  the samples onto the true profile, so index-to-index measures
  the clock.

Gates: Phase3Gate 27 (TESTING #48) + Phase2Gate 262 + DomainGate
green on forced rebuilds; launch smoke test clean. Hand-test:
walkthrough #92.

## D134. v17.0 (same round): REVERSAL REWORKED AS A CONVERT — the
##        D133 design-direction scheme walked back same-day (user
##        re-spec 2026-07-17)

- THE RE-SPEC (verbatim intent): "walk back all of the changes to
  reversing sections and completely rework how reversing a section
  works from the ground up in order to dramatically simplify it:
  make it essentially just a 'convert' feature." Reversal no longer
  transforms the model — it REBUILDS the piece: record the speed at
  the END of the piece (or a user-typed one behind an "unlock"),
  mirror the compiled geometry, and re-fit it as ordinary FORWARD
  sections through the standard Convert density popup. The result
  has NO speed conflict ("in theory reversed track shouldn't have a
  speed conflict") and almost identically resembles the original —
  with friction included.
- WALKED BACK (all of D133's reversed-section half): Section.
  designRev + both concrete fields, the FVDD3 fields (force/geo 10,
  s/c 6), Friction.dir + reversed(), TrackCompiler.solveEndAnchor +
  the designRev branches, the resolve-ops-on-reversed changes
  (makeGraphsReal/refreezeSpeed refuse reversed again), the
  negative design-speed display, the reconcile/B-refreeze designRev
  skips, the SecState field. The FRICTION CORE (D133's other half)
  is untouched. Old files carrying a field-10 saved by the few
  hours of D133: the reader ignores extra fields — they load as
  plain reversed sections.
- THE NEW OP — Coaster.reversePiece(pieceIdx, dt, spacing, tol,
  vEntry):
  · REFUSES pieces containing a BRIDGE (world-anchored A→B).
  · Entry speed vEntry NaN = the recorded Pass-D TRUE end speed —
    the padlocked "Entry speed" row the density popup grows for
    this op (unlock to type; every change re-runs live).
  · reversedSamples mirrors the compiled piece in place (T/R flip,
    U kept, felt Gl negated — the D67 identities; per-sample v kept
    so the fit's κ decode stays exact), reversedProfile marches ONE
    energy flow from vEntry (Heun on the friction deficit — the
    passD pattern), and each ORIGINAL section's span re-fits via
    fitLanes at the flow speed at ITS entry: same boundaries, same
    types (force→force, geo→geo; s/c re-authors as FORCE lanes —
    an element list has no mirrored form; influence FOLDS IN like
    any whole convert; legacy reversed-flag sections just come out
    plain forward). The piece re-anchors at the old exit running
    backward (the D71 placement math); tilts are baked into the
    mirrored samples, deltas reset.
  · Slice rule: each fit includes its entry-boundary sample (the
    withEntry/D113 rule) ONLY across a frame-SMOOTH seam — a
    legacy reversed section's seam can be KINKED (D67 matched
    heading only) and a cross-kink row poisons the fit's edge.
  · CONFLICT-FREE BY CONSTRUCTION: the first section anchors the
    flow, the D95 reconcile keeps the rest real (gated split
    ≤ 1e-6 m/s).
- WHAT THE GATES SAY (Phase3Gate 48f-i, Phase2Gate's reworked
  pieceReversal + influence-i): shape at matched arc 0.87 m over
  204 m on a roll-carrying two-section piece at the 0.02 s carpet
  (the per-seam fit-residual reorientation chained converts always
  had — the measured-roll edge contributes ~0.4°/seam; single
  sections land 0.04-0.2 m), s/c spine 4 cm, level-seam legacy
  0.000 m, double reversal 0.6-0.9 m nearest-point, custom entry
  speed shape-invariant, stalling reversals finite + conflict-free.
- HONEST CORNERS (documented, not bugs):
  · FRICTION IS PAID BOTH WAYS: a piece reversed at its natural
    end speed often cannot climb home — the flow STALLS at the
    creep floor and the tail balloons. The preview's stats-bar
    telltale says so ("unlock the entry speed and give it more");
    the gate pins the stall staying finite. This is real physics,
    not a defect — the user chooses the entry energy.
  · A LEGACY KINKED SEAM (pitched reversed entry from old files)
    cannot survive ANY chained re-author: frame-continuous
    sections smooth it, rotating the far side by the kink angle.
    Reversing such pieces changes shape by that lever arm.
  · The old exact involution (reverse twice = bitwise restore) is
    gone by design — a fit of a fit; Ctrl+Z is the exact restore.
  · The reversed FLAG and reverseAndPlace remain ONLY as the
    legacy-file compile path; nothing in the app sets the flag
    anymore.

Gates: Phase3Gate 24 + Phase2Gate 265 + DomainGate green on forced
rebuilds; launch smoke test clean. Hand-test: walkthrough #92
(rewritten).

## D135. v17.1: STRICT SEAMS (user spec 2026-07-18) + the reversal
##        conflict clarification

- REVERSAL CLARIFICATION (no code change — the implementation
  already behaved this way): D134's "no speed conflict" is about
  the CONVERT itself. The reversed piece is born conflict-free at
  one energy flow; from then on it is an ordinary piece — it holds
  its shape and design graphs, and connecting/editing upstream
  opens honest ≠v conflicts like anywhere else.
- STRICT SEAMS — the user's unifier ("the track after the seam
  should work as if the track before it were a bridge", spawned
  from wanting to convert a bridge and edit it freely while the
  following track holds its shape):
  · MODEL: TrackPiece.followPrev + sLockYaw/sLockPitch/sLockRoll
    (piece line fields 11-14, missing = detached; in DocState). A
    follower's entry POSITION always sits on the previous piece's
    exit; its entry ORIENTATION composes per the locks — UNLOCKED
    axes align to the incoming frame, LOCKED axes keep the values
    of the piece's own STORED placement, which following never
    mutates: relocking an axis RETURNS it to the original (the
    user's relock rule, gated 0.0). Coaster.strictEntry +
    eulerOf/eulerFrame (the TrackPiece delta axes: heading about
    world Y, elevation, bank; all-locked and all-unlocked take the
    exact frames — no Euler round-trip; vertical headings hold the
    whole frame, the global-axis rule).
  · COMPILER: the follower still GENERATES at its stored
    generation orientation and the whole difference applies as the
    D100 rigid tilt about the seam — upstream edits can NEVER
    reshape it (gated 4e-14 m), only translate/re-aim. Its design
    speeds stay independent (piece-first reconcile exemption →
    honest ≠v, gated 4.4 m/s on the fixture) while the REAL ride
    flows through: followers join the previous piece's RUN
    (continuation first-sample drop; runStart/runEnd walk follow
    links like trailing bridges). B/Shift+B across the seam take
    the cross-piece sampled path and never auto-refreeze (the
    seam speed is the follower's own — the vPinned pattern).
  · OPS: makeSeamStrict = the D71 rigid split (boundary pose →
    stored placement = the frozen original) + follow flags (yaw
    unlocked, pitch/roll locked — the bridge convention);
    unstrictSeam = attachPiece + PIN the first section (user spec:
    reorient continuous, graphs kept bit-exact — gated — SHAPE
    re-solves, conflict KEPT); detachStrictSeam = bake the current
    effective placement + drop the follow (the piece stays put);
    connectStrict = reorder + follow with ALL axes unlocked (fully
    continuous — coincident pieces move nothing, gated 5e-15).
  · FLOWS: '+' "Attach floating piece" and the zero-distance
    bridge prompt now CONNECT STRICTLY (the settings pop open);
    whole-bridge converts grow "Make following seam strict (freeze
    following track shape)" — CHECKED by default, locks carried
    from the bridge; unchecked = the classic attach+pin. The two
    paths differ at creation only by how they absorb the lanes'
    fit residual (classic bends the target continuous through it,
    strict holds locked axes — ~0.4 m on the coarse fixture), and
    the user's stated identity holds EXACTLY: convert-with-strict
    then UN-STRICT == the classic convert, gated bit-identical
    (0.00 m).
  · UI: strict seams draw a DOUBLE boundary line; clicking any
    non-bridge seam line opens the seam menu (make-strict /
    settings / un-strict / split-at-seam; press rank: after nodes,
    knobs and badges — edge nodes stay grabbable); the settings
    are three stay-open padlock checkboxes anchored at the seam;
    a follower's Placement rows gray entirely (the seam owns
    position, unlocked axes align, locked ones live in the seam
    settings — the grayHint says so) and its entry gizmo doesn't
    build (the seam is the surface); reversing a follower clears
    the follow (its entry moves to the other end).
- Corners: a bridge's TARGET can't also follow (the bridge IS the
  connection — followPrev is ignored there, and bridge-adjacent
  boundaries take no seam menu); an orphaned follower (previous
  piece deleted) falls back to its stored placement silently;
  clicking the active s/c strip's OWN end line keeps meaning
  resize (its seam is reachable from the neighbor side).

Gates: Phase3Gate 43 (48a-k + 49a-e, TESTING #48/#49) + Phase2Gate
+ DomainGate green; launch smoke clean. Hand-test: walkthrough #93.

## D136. v17.1 feedback round (user 2026-07-18): the strict-seam
##        RESOLVE bug + Discontinuous settings + seam UX

- THE RESOLVE BUG (user: "both options led to a very different
  shape that seemed to launch off into the distance"): the resolve
  ops didn't account for the strict FOLLOW rotation — the D106
  double-tilt reincarnated. Make-real fit the REAL (follow-rotated)
  samples and the next compile applied the follow rotation A SECOND
  time (~95-190 m displacement on the probe); refreeze reshaped
  against gravity in the STORED frame then rotated. FIX = the D106
  pattern extended: Coaster.bakeFollow (stored placement := the
  current effective entry; follow flags/values untouched, the
  rotation becomes identity) runs inside both ops. ORDER MATTERS
  for make-real: unlike a rigid tilt-bake, baking a follow CHANGES
  the generation frame (gravity re-solves), so the bake uses — and
  the fit reads — the SAME pre-bake compile: "keep shape" fits what
  the user sees. Gated 49g: make-real end dev 1.4 m on the violent
  40°-dive+60°/s-roll fixture (was ~190 m), arc preserved; strict
  refreeze == the UN-STRICTED twin's refreeze to 0.22 m over a
  ~300 m reshape (the user's own removing-the-seam comparison, now
  equal by construction). detachStrictSeam reuses bakeFollow.
- DISCONTINUOUS SETTINGS (reworked, user spec): the seam settings
  are a POP-OUT (Popup, auto-hide + an Apply button) — one row per
  axis: a "Discontinuous" toggle wearing a RED OUTLINE while on, a
  ±360° SPINNER (typed + arrows), and a ↺ back to the value the
  seam was created with. Discontinuous OFF = the axis follows (the
  spinner grays and shows the live value); ON = the axis holds the
  spinner's angle. Toggling ON captures the CURRENT value — nothing
  jumps. Model: TrackPiece.sYawDeg/sPitchDeg/sRollDeg (piece fields
  15-17; missing = re-seed from the stored placement; in DocState),
  strictEntry reads them for locked axes (Coaster.eulerOf now
  public), seeded at every creation point. Direct model relocks
  still restore the seeded originals (49b unchanged, 0.0).
- SEAM UX: boundary lines HIGHLIGHT on hover (brighter + wider +
  hand cursor) and stay lit while their menu is open (click
  feedback). The seam menu grew "Connect sections (merge into
  one)" (= the badge's old connect, like sections only); "Split
  piece before this section" and "Connect with previous section"
  LEFT the badge menu (user spec — they live on the seam + the
  first node's right-click menu now).
- GHOST NODE (user report): a follower's entry REVEAL MARKER was
  placed at its STORED x/y/z — the frozen record, not where the
  entry sits — floating off-track as an unselectable "node".
  Followers get no entry mark (no gizmo builds there; the seam
  menu is that entry's surface).
- Density preview ranges (user spec): accuracy down to 0.001%,
  time spacing down to 0.01 s, DISTANCE spacing floor raised to
  0.5 m.
- withEntry (the shared convert-extraction helper) now includes
  the entry-boundary sample only across a frame-SMOOTH seam (< 2°)
  — a kinked row (legacy reversed seams, discontinuous strict
  seams) poisoned fit edges.

Gates: Phase3Gate 52 (49a-g) + Phase2Gate + DomainGate green;
launch smoke clean. Hand-test: walkthrough #94.

## D137. v17.2: the SYNTHESIZED entry row — converts at a jump seam
##        anchor on the section's own values (user 2026-07-18)

USER REPORT: "whenever there is an instant change in a graph between
two sections, converting the second section oscillates no matter how
high the density — the first node always lands BETWEEN the previous
section's end value and this section's. Shouldn't it be at exactly
the current section's value? What comes before shouldn't matter."
He was exactly right, and his second report — make-real after a
strict seam "converts okay but the ≠v stays" — was the SAME bug
seen through the resolve op.

- MECHANISM: withEntry (D113) prepends the boundary sample to every
  span-based convert extraction so the fit doesn't run one dt short.
  That sample's felt/design-speed belong to the PREVIOUS section, so
  at any DISCONTINUOUS seam (a deliberate target jump, a conflicted
  seam's design-speed step, a strict seam's frozen-curvature step)
  the fit's t=0 row holds the OLD value. fitLaneUniform pins node 0
  on that row and matchSegmentAreas spreads the miss — node 0
  between the two values at coarse density, exactly ON the wrong
  value at fine density, C1 handles ringing the miss down the lane
  (probe: 20 sign flips at 0.1 s spacing). D136's guard only caught
  T-KINKS > 2° — value steps ride a continuous frame.
- FIX: withEntry's discontinuity test grew U-kinks and VALUE steps
  (felt Gv/Gl, design v — each read against the section's own first
  interval, |a−b| > floor + 6·|b−c|, so smooth seams stay BITWISE —
  the whole old suite passes untouched), and a discontinuous entry
  is now SYNTHESIZED instead of dropped: exact seam clock/arc/pos
  (no D113 one-step-short regression) with the section's OWN
  dynamics — frame back-rotated one step at the b→c world rate
  (qi = qb·qc⁻¹), felt and design speed linearly extrapolated. The
  entry row becomes the section's limit from the right: what comes
  before no longer matters.
- FALLOUT: 49g's make-real shape residual fell 1.38 → 0.25 m for
  free. Gated 50a-d: jump-seam converts land node 0 exactly (0.1 s
  AND 0.02 s), make-real after locked AND unlocked strict seams
  clears ≠v (11.9/20.0 → 0.008/0.040 m/s) with node 0 on the true
  felt (the unlocked fixture's pre-fix node 0 read −0.409 g against
  a true −0.706 — the user's visible "conflict still present").

## D138. v17.2: strict-seam JUMP CARETS (user request)

"Show which direction the resulting 'infinite' value is with little
caret hats in the respective colors on the stricted seam." A strict
seam joins frozen shapes, so the real ride's curves generally STEP
there — drawn as an unreadable near-vertical stroke. Per lane, a
small chevron on the double line points the way the TRUE value jumps
(GraphEditor.drawSeamCarets: trueDisp read 3 samples clear of each
side — the bodyRates ±2-sample smear, the SEAM_TRIM rationale —
lane color, staggered x per lane, centered in the jump's pixel gap,
hidden under CARET_MIN_PX 8 px on the lane's scale). TRUE arrays,
not design: the jump the rider takes is the thing being annotated,
and it is defined whatever mix of authored/derived the strips draw.
JavaFX-side — walkthrough #95c.

## D139. v17.2: strict-seam geometric SMOOTHING (user request —
##        "like the s/c curves; nothing outside may change")

The s/c entry-smoothing idea applied to the frozen junction: the
seam settings pop-out grew a "Smoothing (m)" row —
TrackPiece.sSmoothLen, piece field 18, missing = 0 = bitwise-sharp.

- GEOMETRY (TrackCompiler.smoothStrictSeam, right after the
  follower's first section lands): across a ±len/2 arc window
  (halves clamped to HALF of each adjacent section's arc — windows
  can never reach a second seam) the orientations SLERP from the
  upstream curve onto the follower's, each side EXTRAPOLATED across
  the seam at its own measured per-sample rate (quatPow of the
  world step) under a quintic-smoothstep weight over arc. The
  window ends sit EXACTLY on the original frames, so NOTHING
  outside reorients, ever — the user's hard constraint. Positions
  re-march along the blended tangents with the ORIGINAL per-step
  arc (lengths kept, s honest); the follower's remainder rigidly
  TRANSLATES by the window-end delta ("moving the position slightly
  is okay") — postShift carries it through the piece's later
  sections and a trailing bridge's captured entry. Gated 50e:
  upstream bitwise, outside frames bit-exact, translation constant
  to 6e-14 m.
- FELT: the window's interior felt re-derives from the blended
  rotation measured PER ARC — κ, the speed-free invariant; a
  time-based Ω would mix the two sides' design speeds across a
  conflicted seam and jitter the decoded curvature (probed: 0.875 g
  phantom steps). Felt = κ·v²/g + axis.y at each sample's own
  design speed keeps trueFelt = κ·v_t²/g continuous while the
  design felt keeps the honest ≠v step. Gated 50f: a 30 m window
  turns a ~9° kink's 589 °/s spike into 23 °/s with 0.062 g/sample
  felt steps. PHYSICS IS HONEST: a hard kink in a short window IS
  a strong finite g-swing (9° in 8 m at 38 m/s ≈ 5.4 g) — the
  window trades "infinite for an instant" for "real for a while";
  widen it to calm it. The true strokes show the whole excursion.
- CONTRACTS: the upstream span's EXIT deliberately does NOT
  re-stamp to the blended seam sample — that pose is the FOLLOW
  CONTRACT (strictEntry, bakeFollow read it; baking the half-kink
  blend into the placement was the D136 bug's ghost). The D80
  "exit == last drawn sample" rule bends knowingly inside a
  smoothed window. followerEuler reads the contract pose
  analytically now (the entry SAMPLE sits inside the blend).
- FITS READ THROUGH THE SMOOTHING (Coaster.compileForFit): every
  lane-refit op (make-real, refreeze, convert, domain, bakeAxes,
  partial) compiles with smoothing suspended — a fit of the
  BLENDED samples re-anchored at the contract frame swung the tail
  122 m (gate 50h caught it; 0.16 m after). The still-active
  smoothing re-applies on top of the refit shape. Reversal
  deliberately reads the smoothed truth (it captures reality).
- Save: field 18 (%.4f), legacy 17-field lines = 0 (gated 50g);
  DocState carries it; the settings row is spinner + ↺, one undo
  step per change.

Gates: Phase3Gate 60 (48a-k, 49a-g, 50a-h) + Phase2Gate +
DomainGate green; launch smoke clean. Hand-test: walkthrough #95.

## D140. v17.3: the v17.2 feedback round (user 2026-07-18) — spike
##        carets, the smoothing diamond, position closure, the seam
##        gizmo

Nine-item batch on the (uncommitted) v17.2 work; all landed:

- CARETS RE-SPEC'D (his: "point the direction the rate graphs would
  go at the exact point on the seam"): the caret is the SIGN OF THE
  DELTA-FUNCTION SPIKE an orientation step is — not the value jump
  across the seam. The seam's instant angular jump Δφ =
  axisAngle(qR·qL⁻¹) projects the way Ω decomposes (§2):
  {−Δφ·R, Δφ·U, Δφ·T} = the spike signs of lanes 0/1/2 in BOTH
  views (an instant pitch-up in a valley = +∞ felt = up caret; a
  negative-direction roll step = −∞ roll rate = down). His two
  examples verified against the algebra. Carets sit DIRECTLY on the
  seam line (stagger removed, per spec) at each curve's height;
  enabled RESULTING overlays wear theirs too (components 0/1, the
  overlay palette + scale); threshold CARET_KINK_MIN 2° (continuous
  track turns ≤ ~1°/sample, so smooth axes stay quiet); a SMOOTHED
  seam draws none — no instant change left (his rule). D138's
  value-jump semantics + CARET_MIN_PX are gone.
- SMOOTHING = A DIAMOND ON THE SEAM (his: "like for the s/c
  curves"): the settings-row spinner is gone; a white diamond at
  the s/c diamonds' height on the strict seam line (filled =
  active) drags the window length — twice the ARC between cursor
  and seam, read off the compiled samples so time AND distance
  axes work — with a live "smoothing X m" readout, Ctrl snapping
  by the element-length step, snap-shut under 0.5 m, cap 80 m, a
  shaded blend-zone band (the s/c look), one undo per gesture
  (the beginGesture/commitGestureSnapshot contract), H_RESIZE
  cursor, press rank above the seam-line menu.
- POSITION CLOSURE (his: "still a little drift ... noticeable for
  longer track sections"; measured: the ⊥ residual was 1e-4 and
  the drift was 100% ALONG-CHORD foreshortening — the blend path
  curves differently at the same arc length): the window now bends
  AND stretches until its re-march lands back on the original end
  position. Per iteration: sin²(πu) rotation humps (zero value +
  slope at the ends — outside frames stay exact) close the
  ⊥-to-chord components by a 2-axis least-squares; a hump-weighted
  per-step ARC STRETCH closes the ∥ rest. Caps 0.2 rad / ±5%:
  pathological kinks keep a residual translation instead of
  corkscrewing. Gated 50e: moderate fixture 0.011 → 3e-6 m; the
  violent 147°-kink probe 0.689 → 0.000. COST, documented: the
  drawn arc inside the window exceeds the s fields by ≤ the
  stretch (ties/combs sit a hair wide there), and the closing
  bend adds its (honest, felt-recorded) curvature.
- SEAM GIZMO (his spec): the follower's reveal ball sits ON the
  junction now (the D136 removal was about the STALE stored
  position — the true seam is a real surface; seamWorldPos reads
  the follower's first sample). Single click reveals rings for
  the DISCONTINUOUS axes only (locked = an editable held angle;
  following axes have no number to turn), writing
  sYaw/sPitch/sRollDeg through GraphEditor.gizmoSetSeamOrient —
  the standard absolute-from-press ring drags, one undo per
  gesture; DOUBLE-CLICK opens the seam settings pop-out (the
  first click's reveal toggle is undone, net = settings only).
  Ring axes read the FOLLOW CONTRACT frame (seamEntryOrient —
  strictEntry off the prev span exit, smoothing-proof).
- SETTINGS POP-OUT: white like the context menus (the dark
  pv-popup styling read as foreign), "Strict seam" title only
  (explanation removed), MOVABLE by its body (the pv grab
  pattern), and toggling an axis DISCONTINUOUS seeds the ORIGINAL
  creation angle (as if ↺ were pressed — was: capture-current).
- Density-preview ranges reverted to pre-D136 (spacing 0.02–2 s /
  0.2–20 m, accuracy 0.01–10%) on his word.
- LESSON re-learned: the incremental compile accepted a static
  field initializer with an illegal forward reference (stale
  classes); the clean rebuild caught it. rm -rf
  target/classes/coaster before believing MVN_EXIT=0.

Gates: Phase3Gate 60 (50e re-pinned on the closure) + Phase2Gate +
DomainGate green; launch smoke clean. Hand-test: walkthrough #96
(+ #95 revised in place — v17.2 never shipped alone).

## D141. v17.4: the v17.3 feedback round (user 2026-07-18) —
##        enable/disable smoothing, the red conflict band, edge
##        carets, grabbable band edges

- ENABLE/DISABLE SMOOTHING (user spec): the strict seam menu grew a
  toggle — "Enable smoothing (1 m)" / "Disable smoothing" — and the
  DIAMOND + band show only while enabled (a drag that snaps shut
  keeps its readout until release, then the widget hides; the menu
  re-enables). DEFAULTS: hand-made strict seams start OFF;
  CONVERTED-BRIDGE seams and piece CONNECTS start ON at 1 m (the
  joint replaces something smooth — Coaster.convertBridgeWhole's
  strict path + connectStrict seed sSmoothLen 1 when unset). The
  blend's minimum-sample guard softened 3/4 → 2/3 samples per side
  so a 1 m window ENGAGES at speed instead of being silently
  sub-sample (mU/mD already adapt).
- SMOOTHING-CAUSED ≠v (user spec): when the follower's conflict
  exists ONLY because of the window — drifted as compiled, clean
  in a smoothing-suspended compile (GraphEditor.
  smoothingCausedDrift, ONE extra compile cached per rebuild keyed
  on the sample-list identity + window length) — the band tints
  RED and the badge menu's "Resolve speed conflict" submenu is
  REPLACED by a grayed "Resolve speed conflict by decreasing seam
  smoothing size": the resolve ops would chase a moving target the
  window itself created. Physics note: the closure's stretch/bend
  changes heights inside the window while the design profile keeps
  its speeds — big windows on violent kinks CAN open a real split.
- CARETS AT THE LINE'S EDGES (user re-spec of D140's curve-height
  placement): fixed positions signaling DIRECTION only — up-spikes
  stack from the TOP edge of the seam line, down-spikes from the
  BOTTOM, so pan/zoom never moves them; overlay carets join the
  stacks in their palette. No magnitude channel, per spec.
- GRABBABLE BAND EDGES (user spec, seam AND s/c): either edge of a
  smoothing band drags its size — the seam edges route into the
  diamond's own drag (the window is symmetric, len = 2·|cursor↔
  seam| arc), the s/c edges into the existing elDragSmooth
  machinery. Press rank DELIBERATELY LOWEST: nodes/knobs, '+',
  badges, the seam line and the curve insert all win first (the
  user's rule); in the s/c handler the edges sit just above the
  element select, which would otherwise eat every click. H_RESIZE
  hover on all edges.

Gates: Phase3Gate 60 + Phase2Gate + DomainGate green (the 1 m
defaults ride under the strict identity checks' tolerances);
launch smoke clean. Hand-test: walkthrough #97.

## D142. v17.5: the v17.4 feedback round (user 2026-07-18) —
##        always-on carets, 10 m defaults, relative diamond drag,
##        diamond double-click + node dodge

- CARETS ALWAYS DRAW (user: "they disappear if the value doesn't
  change much"): every lane's caret (and every enabled overlay's)
  now draws at every sharp strict seam — components under the old
  CARET_KINK_MIN 2° threshold go TRANSLUCENT (alpha 0.30 vs 0.95)
  instead of vanishing. Direction only, as before; smoothed seams
  still draw none.
- 10 m DEFAULTS (user spec): "Enable smoothing" seeds 10 m (was
  1), and the bridge-convert + connect flows arrive at 10 m too.
  Gate 49e re-pinned: it now asserts the 10 m default explicitly,
  then zeroes the window before the placement-contract check — a
  continuous seam blends to ~1e-5 m of numeric residual, which is
  the deliberate cosmetic, not a placement bug.
- RELATIVE DIAMOND DRAG (user: "rather than restarting from
  zero"): grabbing the diamond now ADJUSTS the current length —
  len = base + 2·(arc(cursor) − arc(press)), rightward grows,
  leftward shrinks (seamDragRel/seamDragBase/seamDragPressS).
  Band-EDGE grabs keep the absolute window-to-cursor semantics
  (natural there: the edge goes where the cursor is).
- DIAMOND DOUBLE-CLICK = the seam settings pop-out (the 3D ball's
  rule; click 1's drag is cancelled), and the diamond DODGES NODES
  exactly like the '+' button: seamDiaYAt walks the same y-ladder
  (48 px, 30/50/70%, bottom) to the first spot ≥ 22 px clear of
  any node, clearest otherwise — draw and hit-test share it.

Gates: Phase3Gate 60 + Phase2Gate + DomainGate green; launch smoke
clean. Hand-test: walkthrough #98.

## D143. v17.6: the both-global continuation bug + resulting graph
##        settings + canonical dialog order (user 2026-07-19)

- THE BOTH-GLOBAL BUG (user: adding a geo section with global roll
  AND global yaw/pitch "stayed flat at zero ... nothing changed
  with B"): the root cause was D108's STRAIGHT-CONTINUATION holds —
  the add zeroed lanes 0/1 for global yaw/pitch and the roll lane
  for global roll, so with BOTH flags every lane held flat zero and
  nothing lined up (either flag alone left some lane carrying real
  values, which read as "works okay"). FIX (user re-spec, the D137
  "what comes before" principle): GLOBAL-axis holds now take the
  incoming WORLD rates — elevation'/heading' for yaw/pitch, bank'
  for roll — via Coaster.worldRates at the end sample (NaN-vertical
  falls back to zero; the κ-scale rH and °/m division ride along;
  the same-flags authored copy now outranks the world hold, it was
  shadowed before). Gated 51a-c: seam steps 0.37-0.44 °/s across
  all three flag combos (19-25 °/s flat-zero before). The B matcher
  itself checked out clean in an end-to-end headless repro (probe:
  world targets + integrator respond correctly in every combo) —
  if B still misbehaves in-app, the stats bar hint will say which
  branch refused. Known corner: a ypG-only section's LOCAL roll
  lane keeps its transport shift (p = φ' + ψ'·sinθ) once the
  section turns — inherent to mixed axes (D109).
- CARETS SMOOTHING-INDEPENDENT (user rule: "enabling and disabling
  smoothing should naturally not make carets move or disappear"):
  seamSpikes reads the CONTRACT frames now — the upstream span's
  exit pose against strictEntry's effective entry — which are the
  junction's sharp states whatever the blended samples look like.
  D141's smoothed-seam-draws-none rule is gone.
- CONNECT DEFAULT OFF (user walk-back of D141): connectStrict no
  longer seeds smoothing; only bridge converts arrive at 10 m.
  Gate 49e re-pinned (sSmoothLen == 0 after connect).
- RESULTING GRAPH SETTINGS (user spec): the Resulting dropdown
  grew "Resulting graph settings…" — a live-apply dialog picking
  how the overlays present: Rate units (°/s|°/m), Roll axis
  (local|global), Yaw/pitch axis (local|global; enabled only while
  the resulting graphs are GEO — i.e. over a force section), Roll
  behavior (rate|absolute bank; needs global roll). Persisted
  per-user (AppSettings resRateM/resRollG/resYpG/resRollAbs) and
  wired through resValueAt: global = analytic worldRates, °/m =
  ÷v, absolute = bankOfTU. SUPERSEDES v16.7's automatic °/m-follow
  of the active section's units. A third overlay check joined the
  dropdown — resulting ROLL/BANK (resRoll; FORCE view only — over
  geo the roll lane already is the shared quantity; wears the roll
  palette, hover/carets/scale included; check labels re-title live
  via FvdDesigner.syncResChecks).
- CANONICAL DIALOG ORDER (user spec): Type, Domain, Rate units,
  Roll axis, Yaw/pitch axis, Roll behavior — applied to the
  Convert dialog, the Add-section dialog and the Resulting
  settings (which has no Type/Domain).

Gates: Phase3Gate 63 (51a-c new) + Phase2Gate + DomainGate green;
launch smoke clean. Hand-test: walkthrough #99.

## D144. v17.7: the v17.6 feedback round (user 2026-07-19) —
##        always-on gizmos, caret sizing/dodge, resulting roll
##        everywhere, scale defaults

- GIZMOS ALWAYS ON (user spec — ends the v16.12 reveal-click era):
  the active piece's gizmos (entry + trailing bridge end + seam
  rings) show whenever the View menu's new "Show placement/seam
  gizmos" check is on (AppSettings.showGizmos, default true; the
  menu-local undo pattern). The reveal spheres stay as ANCHORS:
  the entry ball still selects node 0, the seam ball's
  double-click still opens the seam settings; the empty-click
  hide and per-piece re-hide are gone.
- CARETS: sub-threshold components draw SMALLER and thinner (w 3 /
  h 2.5 / 1.2 px vs 4.5 / 4 / 2 px) at full alpha — the v17.5
  translucency read as "disappearing". And carets DODGE nodes with
  the '+'-ladder idea, edge-anchored: each caret takes the rung
  nearest its OWN edge (top for up-spikes, bottom for down) that
  has ≥ 14 px of node clearance, walking inward only as needed
  (GraphEditor.caretY).
- RESULTING BANK wears its own RED-ORANGE (BANK_COLOR
  rgb(235,70,45)) — distinct from the roll-rate orange.
- RESULTING ROLL IN BOTH VIEWS (user question answered: the
  force-only restriction was just redundancy-avoidance — over a
  geo section a LOCAL-rate resulting roll IS the authored lane):
  the check now grays ONLY while the overlay would duplicate the
  active section's own roll graph exactly — same rate units, roll
  axis and roll behavior (resRollRedundant; bridges/s-c never
  gray). The checked state persists across sections, so it
  re-appears wherever it means something new. Geo view accepts
  the felt-g + roll-rate unit mix on the shared resulting scale.
- SCALE DEFAULTS: every °/m lane's default (minimum) scale is
  ±5 °/m now (was roll ±15 / pitch-yaw ±10, v16.7); the resulting
  scale's defaults MATCH the normal lane defaults — felt −1..4,
  rates ±60, ±5 in °/m, and an absolute-bank overlay opens the
  scale to at least ±90 (the abs lane's default).

Gates: Phase3Gate 63 + Phase2Gate + DomainGate green; launch smoke
clean. Hand-test: walkthrough #100.

## D145. v17.8: the v17.7 feedback round (user 2026-07-19) — seam
##        settings polish, tinted checks, Track Piece panel

- FIRST-OPEN vs LATER (user spec): only the FIRST time a seam's
  settings open does a Discontinuous toggle seed the CREATION
  angle (the D140 rule, usually right after make-strict); every
  later open captures the CURRENT angle instead — "it stays where
  it is" — with ↺ still returning to the creation angle.
  TrackPiece.sSeenSettings tracks it: interaction history,
  deliberately NOT persisted and NOT in undo (a reload's first
  open behaves first-time again — natural).
- APPLY OR CANCEL ONLY (user spec): the seam settings popup no
  longer auto-hides on click-off; a CANCEL button reverts the
  locks + angles to what this open found (one undo step for the
  revert — the live edits already pushed theirs).
- BANK COLOR reddened: rgb(240,45,20) — almost pure red, a
  whisper of orange (BANK_COLOR moved to FvdDesigner: the check
  tints below need it too).
- TINTED RESULTING CHECKS (user spec): a CHECKED Resulting box
  fills with its curve's color (white mark), via the lazy .box
  lookup (skins exist only after the popup shows — the
  color-picker precedent); re-tinted on toggle, popup show and
  every syncResChecks.
- "PLACEMENT ▾" → "TRACK PIECE ▾", and the dropdown became a
  MOVABLE dark Popup (a ContextMenu cannot be dragged): same
  rows, grab any non-control spot, click-off still closes, the
  menu-local undo session wiring carried over; the reverse action
  is a button inside it now. The bridge-mode relabel ("Bridge
  properties ▾") is unchanged.

Gates: Phase3Gate 63 + Phase2Gate + DomainGate green; launch smoke
clean. Hand-test: walkthrough #101.

## D146. v17.9: the v17.8 feedback round (user 2026-07-19) — bank
##        color unification, caret trim, axis-row swap, movable
##        Resulting

- BANK COLOR UNIFIED + red-orange again (D145's near-red walked
  back): BANK_COLOR = rgb(235,70,45), and a REAL absolute-bank
  lane now wears it too (laneColor's abs branch) — the authored
  bank curve and the resulting bank overlay read as one quantity.
- NO SEAM CARETS for the resulting roll/bank overlays (user: the
  roll-rate overlay's spike is the roll lane's own caret anyway).
  Overlay carets = components 0/1 only again.
- AXIS-ROW SWAP (user spec): the canonical dialog order is now
  Type, Domain, Rate units, YAW/PITCH AXIS, ROLL AXIS, Roll
  behavior — swapped in the Convert dialog, the Add-section
  dialog and the Resulting settings.
- RESULTING POPUP MOVABLE (the Track Piece pattern): grab any
  non-control spot; once dragged, the hover-dismiss stands down
  for that showing (click-off still closes; a fresh open re-arms
  it). And the Track Piece panel's labels are WHITE now — they
  were black holdovers from the old white ContextMenu.

Gates: Phase3Gate 63 + Phase2Gate + DomainGate green; launch smoke
clean. Hand-test: walkthrough #102. COMMITTED with the whole
v17.2-v17.9 stack on user request.

## D147. v17.10: integrator END LANDING -- the closing step lands on
##        the authored end (user bug: split-at-node moved the track end)

- THE BUG (user 2026-07-21): insert a node on an angled stretch of a
  lane, split the section there -- the END of the track moved, "when
  in theory there should be absolutely no difference at all". Root
  cause = the D130 truncation: integrateTrack's `while (t <=
  totalTime)` refused the final step whenever the accumulated float
  t overshot the end (300 x 0.01 = 3.0000000000000027), so EVERY
  time-domain section end absorbed up to one dt of authored profile
  -- and a split ADDS a seam, i.e. one fresh truncation the unsplit
  compile didn't have (0.245 m end shift on the probe ramp).
- THE FIX: both time-domain loops (FVDIntegrator.integrateTrack,
  GeoIntegrator.integrate) size the CLOSING step to land the last
  sample EXACTLY on the authored end -- a single stretched/shrunk
  step in (0, 1.5*dt] (the D84 no-runt rule: never a full step plus
  a sliver; near-duplicate samples poison rate differencing).
  Interior steps stay h == dt bit-identically. Distance mode always
  Newton-landed; s/c and bridge geometry were never affected.
- D130's "never fix the loop epsilon" is SUPERSEDED knowingly: the
  fix re-times every time-section end by up to one dt of profile,
  so old saves compile marginally more complete (sub-cm shifts per
  seam). All three suites re-ran green -- the bitwise gates compare
  two in-build paths and survive a uniform re-time; no absolute
  baseline broke.
- The residual split-vs-unsplit difference is BAKE-GRID PHASE only
  (each half re-anchors its 0.05 s keyframe grid and per-segment
  polyline knots): ~9 mm over the 120 m curved hill fixture, and
  1.6e-5 m on an angled LINEAR stretch -- the user's exact case --
  where the bake is exact. Gates 52a-c pin all three.

## D148. v17.10: bridge seams smooth at EVERY length (user bug:
##        rough transitions in length ranges, "magic" smooth lengths)

- SYMPTOM (user): sweeping an optimize-mode bridge's length,
  certain ranges showed an instant jump and/or instant slope change
  in G/rate at the bridge's ends; at some custom distances the
  graphs "snapped" back to matching value + slope.
- ROOT CAUSE 1 -- the correction humps: the march closed its pitch/
  heading residuals with PARABOLIC curvature humps 6u(1-u)/d --
  zero end VALUE but end SLOPE +/-6*res/d^2: an instant kink in the
  rate-graph slope at BOTH seams, proportional to the residual gap,
  which varies with length and crosses ZERO at isolated lengths --
  the user's magic smooth distances. Probe: seam slope gaps up to
  ~1800 deg/s^2 at d = 8 m; OPT_POS ~5x worse than OPT_POS_YAW
  (both humps fire there).
- FIX 1: hump weight = 30u^2(1-u)^2 -- same unit integral (the
  residual still closes exactly), zero end value AND slope; mid-
  span peak 1.875x vs 1.5x (25% higher, accepted). This is NOT the
  reverted D83 quintic -- that changed the BLEND basis the user
  feel-rejected; the humps' end slopes were never load-bearing.
- ROOT CAUSE 2 -- the landing gate: marchSpine accepted misses up
  to 2 cm / 0.3 deg and rendered the spine as-is -- an instant
  position/orientation STEP at the far seam (0.3 deg at 30 m/s is
  a ~30 deg/s rate spike). Genuine optimize flows land at ~1e-12 m
  / <=1e-4 rad, so the slack only ever admitted STALE placements
  (hand-nudged targets inside 2 cm kept rendering a stepped spine).
- FIX 2: gate tightened to 5 mm / 0.02 deg. Anything visibly off
  falls to the SEPTIC, which is value+slope continuous into both
  seams BY CONSTRUCTION -- so every render path is now seam-smooth,
  exactly like the situations the user called "magically smooth".
- Post-fix probe (rotation-per-meter, local two-sample measure):
  curvature value kinks 0.000 at every length, slope breaks at or
  below the septic's own floor (0.08 vs 0.50 deg/m^2 reference).
  Gates 53a-c.
- COMPAT: pre-v17.10 saves with OPTIMIZED bridges were placed under
  the parabola humps -- on load the new march ends elsewhere, the
  tight gate rejects it, and those bridges render SEPTIC (seam-
  smooth, same seam poses, slightly different mid-span) until the
  first upstream edit or explicit re-optimize re-places them. Loads
  still never move pieces (the optSig == null record-only rule).

## D149. v17.10: View/Coaster menu + visual round (user list,
##        2026-07-21)

- "SHOW GIZMOS" (renamed from "Show placement/seam gizmos") is the
  TOP View row, with the axis choice inline as two radio dots
  "Local" | "Global" -- grayed while the gizmos are hidden; they
  share app.setGizmoAxesLocal with the viewport's overlay button
  (the separate "Gizmo axes: local" check is gone;
  FvdDesigner.viewGizmoLocal/Global are the new twins).
- "Show heartline" dropped its "(neon)".
- RAIL COLOR moved to the COASTER menu as a "Rail Color" section
  (Dialogs.railColorItems): "Solid" -- its swatch shows inline
  while selected, and the reset arrow is GONE (user spec) -- and
  "NORMAL FORCE" (the former "G-force gradient" wording). The
  v16.16 Load/New re-sync guard moved along (railMenuSync, run by
  the Coaster menu's onShown).
- "VISUALIZE TRACK SELECTION" (View check, default ON =
  AppSettings.visualizeSelection): OFF disables selection tinting
  wholesale -- no desaturated pieces (every tube renders in its
  full active coloring) and no white active-section ties (all ties
  the plain dark color). Pure per-user view setting.
- COLOR SWATCHES are SQUARE and small (22x22, were 52x22) -- every
  picker button: rail solid, gradient stops, lateral stops.
- BRIDGE PROPERTIES panel text is WHITE (mode radios, length row,
  Optimize length, yaw/pitch/roll/xyz labels) -- black holdovers
  from the pre-v17.8 light ContextMenu eras on the dark popup.
- TRUE STROKES AT SMOOTHED STRICT SEAMS (user spec): inside a
  smoothing window the ACTIVE strip draws its AUTHORED lanes while
  the ride is the compiler's blend, so drawTrueStrokes now takes
  the authored value as its split reference there (the influence/
  tilt rule) -- the thin real curve shows on BOTH sides of the
  seam, including the upstream side, which is never speed-
  conflicted and used to show nothing while its geometry bent.

Gates: Phase3Gate 69 (52a-c end landing + 53a-c bridge seams new) +
Phase2Gate 265 + DomainGate green; launch smoke clean. Hand-test:
walkthrough #103.

## D150. v17.11: bridge far-seam jumps ROOT-CAUSED — the residual
##        twist joins the march (v17.10's two bridge drafts REVERTED)

- THE REVERT (user 2026-07-21): D148's zero-end-slope humps and the
  5 mm / 0.02 deg landing gate "didn't quite capture the specific
  bug" -- both restored (6u(1-u) parabola, 2 cm / 0.3 deg). His
  attached split-and-rebridge file showed rough far-seam Gv/Gl at
  lengths <= 24.1 m and smooth above; the 24.1 threshold was MY
  tight gate flipping the render to the septic mid-range (the
  march's landing residual crossed 0.02 deg there) -- the
  underlying roughness existed at EVERY march length.
- THE REAL BUG (probe on his file: ~0.6-1.2 g instant Gv/Gl jumps
  at the far seam, growing with length): his seams carry a -52 deg/s
  roll rate with roll LOCKED, so the march's blended roll runs the
  bank ~2 deg/m away from the target's and the render's residual
  quintic twisted U back afterwards (~48 deg at 24 m) -- while the
  spine's CURVATURE was built in the UNTWISTED frame. The track
  genuinely bent in directions rotated by the whole residual at the
  far seam: bank closed, curvature vector didn't. (The v17.10-era
  probes never saw it -- their fixtures had zero roll.)
- THE FIX, three parts (Coaster.marchEnd/march/contDeriv):
  1. RESIDUAL TWIST IN THE MARCH: the bank gap to the target joins
     the march as a roll RATE (parabolic weight like the humps, zero
     at the ends so seam roll rates stay the neighbors'); kappa --
     rebuilt in the CURRENT frame each step -- then arrives at the
     far seam in the TARGET's directions.
  2. YAW-INVARIANT MEASURE (bankGap): the residual is the
     difference of the two frames' BANK-VS-HORIZON angles, each
     about its own heading -- invariant under the yaw alignment the
     placement applies after the march. A direct U-to-target-U
     angle re-measured differently after placement, re-aimed the
     blend, and the render missed its own placement by ~1 deg
     (permanent septic).
  3. BOUNDED FIXED-POINT refinement (<= 6 passes, exit at 1e-4
     rad): with ~48 deg of twist re-aiming the blend, the pitch
     hump's response gain is far from 1 and D97's single pass left
     DEGREES of far-seam tangent error. All three measures are
     wrap-safe (asin / wrapped atan2 -- NOT the reverted D95 bank
     iteration's ambiguous measure); clean marches exit on the
     first measure, keeping the historic two-march sequence
     bit-identically.
- RESULT on his file: march renders at every length, landing
  0.000 m / <= 0.005 deg, far-seam felt jump 1.2 g -> 0.008 g (the
  per-sample noise floor). Roll-free fixtures unchanged. Gates
  53a-c rewritten: 53a/b re-pin the shipped parabola-hump levels,
  53c pins the rolling-seam fix (worst 0.0015 g).

## D151. v17.11: EXACT bake + 0.01 s time grid — the split residual
##        drops to the integration floor (user round 2 on D147)

- HIS REPORT: split accuracy "much better" but a curvature-heavy
  cut still moved the end ~0.02 m per 100 m -- the D147 bake-grid
  residual, "likely would exponentially increase with longer
  coasters".
- CAUSE, two discretization layers: bakeLane's ~0.1-unit PARAMETER-
  space polyline (whose knots move under node inserts/splits even
  when the curve is identical) and the 0.05 keyframe grid re-
  anchoring at each split half's own zero.
- FIX 1 -- EXACT KEYFRAMES (BezierBaker.exactAt): bake() reads each
  grid point straight off the owning segment's cubic (bisection on
  x, the splitSegment pattern; folds behave at the documented-quirk
  level). The physics path no longer sees the polyline at all; the
  editor keeps it for drawing/hit tests. Hill-fixture split
  residual 9.3 mm -> 0.13 mm.
- FIX 2 -- TIME GRID 0.01 s (= dt; knots on every RK4 step
  boundary): split halves' grids land on the parent's own grid
  points, so a cut at any 0.01-multiple is exact to MACHINE
  precision (1.2e-12 m on the hill fixture); off-grid cuts sit at
  the few-um RK4 step-phase floor. DISTANCE lanes keep 0.05 m
  (already ~26x denser per meter of track; 0.01 m would balloon
  long sections' tables). matchSegmentAreas bakes on the same
  per-domain grid (threaded parameter).
- FIX 3 -- the convert fit's round-duration workaround RETIRED: it
  existed only because the old loop dropped fractional tails
  (D147 landed that); fits keep the exact data duration now.
- RE-BASELINED (the exact values shift every compile slightly, a
  pure accuracy gain): default layout 406.878 -> 406.931 m, end
  pitch 0.2284 -> 0.2131 deg (sample count unchanged, 1347); the
  bit-for-bit gate's direct call bakes 0.01 like the compiler;
  conv (a) re-pinned 0.6 deg/s (Pass A's +/-2-sample differencing
  smears the now-sharper rate corners -- comparison artifact, both
  sides were equally low-passed before); 48f re-pinned 0.02 m/s
  (near-stall v amplifies mm/s of profile change; badges fire at
  0.2). Compile cost: ~5x more keyframes on time sections --
  sub-ms; interpAt is a binary search.

## D152. v17.11: POV stats line + menu polish (user list, 2026-07-21)

- The FIVE coaster stats (ride time, track length, height range,
  top speed, Gv range) moved to a COASTER STATISTICS submenu of the
  Coaster menu (refreshed per open); the shortcut hint is GONE
  entirely (Edit > Shortcuts and Help own that).
- The bottom line is the LIVE POV POSITION now: X/Y/Z, speed,
  vertical + lateral G -- TRUE values, three decimals
  (Viewport3D.updatePovStats; refreshed per rebuild, per ride
  frame and per POV scroll). povTime persists across POV exits, so
  the line stays live while editing -- and the purple POV marker
  line on the graph draws ALWAYS now, not just in POV view.
- POV STAYS ON THE BRIDGE while bridge settings are edited
  (GraphEditor.clampPovIntoActiveBridge, inside applyBridgeGap +
  gizmoBridgeMoved): shrinking the length used to drop the parked
  POV into the next piece, whose ride-follow activated it and
  closed the Bridge-properties panel mid-edit. The clamp runs
  synchronously in the edit event (the POV timer never sees the
  position off the bridge) and only when the POV was ON the bridge
  before the edit.
- Coaster menu: "G-Force Colors..." (the tinted-letters item)
  appears under "Normal Force" while it is selected.
- View menu: "Reverse POV view direction" REMOVED (A/D in captured
  POV + the bottom-left tag are the surface); "Visualize track
  selection" -> "VISUALIZE SELECTION"; "Node markers on the 3D
  track" -> "NODE MARKERS".

Gates: Phase3Gate 69 (53a-c rewritten) + Phase2Gate 265 (re-
baselined) + DomainGate green; launch smoke clean. Hand-test:
walkthrough #104 (supersedes #103's bridge item).

## D153. v17.12: bridge seam SLOPES + attach lock defaults + true-force
##        rails (user round 3, 2026-07-21)

- SEAM SLOPES (user: "the slopes of the graphs aren't lining up at
  the seams at either side of the bridge [just the values]"): with
  the D150 value fix in, what remained at march-bridge seams was
  the correction channels' 6u(1-u) parabola END SLOPES (+/-6*res/d²
  each) -- the pitch hump, the heading hump AND the D150 residual-
  twist rate. All three now share the 30u²(1-u)² weight: unit
  integral (residuals still close exactly), zero end value AND
  slope, so the blend leaves each seam exactly on the measured
  jerks / roll accelerations. This is the SAME weight D148 shipped
  briefly and D150 reverted -- at that point the value jumps still
  dominated and it read as not-the-bug; now it lands as the asked-
  for fix, applied to the twist channel too. Probe on his file:
  seam slope breaks ~0.50 -> 0.02-0.08 deg/m² (the septic's own
  floor), value kinks stay 0. Mid-span hump peak 1.875x vs 1.5x.
- ATTACH LOCK DEFAULTS (user spec): connectStrict grew a lock-
  carrying overload -- a DIRECT ATTACH seeds the seam's
  Discontinuous axes from the floating piece's placement PADLOCKS
  (locked pitch/roll => discontinuous holding the piece's own
  angles; yaw has no padlock => continuous); the ZERO-DISTANCE-
  BRIDGE "Yes" seeds them from the BRIDGE's yaw/pitch/roll locks.
  Held angles seed from the piece's current orientation (the
  existing seedStrictValues), so nothing moves at creation either
  way. The 2-arg connectStrict stays all-unlocked (model-level +
  gates unchanged).
- TRUE-FORCE RAILS (user spec: "just like the g force comb"): the
  Normal Force gradient's per-ring color index reads trueFelt[i][0]
  now instead of the design Gv (buildRailMesh carries the sublist's
  global base index; falls back to design felt if the true arrays
  are absent). Identical until a design speed drifts -- then the
  rails show what the ride actually feels, matching the comb.

Gates: Phase3Gate 69 + Phase2Gate 265 + DomainGate green (53a/b
message text now reflects the bump levels); launch smoke clean.
Hand-test: walkthrough #105.

## D154. v17.13: B/Shift+B match REAL graphs across STRICT seams
##        (user round 4, 2026-07-21)

- THE BUG: boundaryStatePerSec's real-split test resolved only
  SAME-PIECE indices — at a strict seam the neighbor lives in the
  adjacent piece, so `real` stayed false on both sides: from the
  follower's first node the `activeSection > 0` guard nulled the
  seam span outright, and from the upstream side's end node
  vTheirs fell back to OUR OWN design speed (missing the
  follower's). B/Shift+B therefore lined up the DESIGN graphs
  across conflicted strict seams — including the unconflicted-vs-
  conflicted case the user hit.
- THE FIX: the detection resolves CROSS-PIECE through the follow
  link only (Coaster.followerActive gates it; bridge boundaries
  stay out — a bridge blends its own speed, D78): the seam span
  via spanOf's flattened index, the neighbor Section straight off
  the follower/followed piece. With `real` correctly true, the
  EXISTING v16.2/16.3 machinery does the rest — targets from the
  TRUE arrays, converted by the kappa identity (rReal/realScale),
  through the sampled cross-piece path that already handles
  flattened spans. refreezeSeamSpeed still skips cross-piece
  seams, so B never auto-closes a strict seam's speed
  independence (the D135 contract).
- Unconflicted strict seams keep the historic sampled design path
  bitwise (real stays false there).
- KNOWN LIMIT (user-accepted: "won't be perfect, maybe need
  iteration"): with seam SMOOTHING enabled, the sampled targets
  near the seam read the BLENDED samples, so a press converges
  iteratively; matching with the seam sharp (or smoothing
  temporarily off) reads the exact contract state. And his
  "smoothing didn't smooth a conflicted seam" observation: the
  TRUE ride/strokes smooth through the window — what steps AT the
  seam are the DESIGN felt/rates, deliberately (D139: the honest
  not-equal-v display; no window width removes it). With this fix
  + a B press the real transition has ~nothing left to absorb.

JavaFX-side (not headlessly gateable); suites re-run green
untouched. Hand-test: walkthrough #106.

## D155. v17.14: CREST BRIDGES — branch-correct march measures
##        (user report "Odd bridge behavior 1-3", 2026-07-24)

- THE REPORT: bridging the TOP of a hill (slight curve, yaw-only
  unlocked, OPT_POS_YAW) rolled the track "unless the distance was
  super small", made S-bends / inverted loops / far-seam graph
  spikes at many lengths, and the initial optimized length was way
  too big; VALLEYS behaved as expected. Three .fvd repros.
- ROOT CAUSE (probed on his files): at a crest BOTH seam curvature
  vectors point UP (the entry still pulling up, and the target's
  own entry curvature is a pull-up too) while the NET pitch change
  points DOWN — the march's pure Hermite blend therefore turns the
  WRONG way and, at useful lengths, overshoots past VERTICAL.
  Every residual measure was branch-blind there: resPitch's asin
  difference reflects past ±90°, the heading atan2 and the D150
  bank-vs-horizon measure both flip ~180° across a pole. The hump
  fixed point then converged onto REVERSED-HEADING or FULL-LOOP
  branches: the twist walked 180+180 into a 360° BARREL ROLL with
  clean ends (his roll — probe: minUy −0.99 across L=30..64, dYaw
  jumping per length), L=64 on file 2 landed a genuine inverted
  loop AS the rendered march, other lengths burned the 6-pass cap
  and fell to the septic with tangents = the requested length
  (120 m tangents over a 45 m gap = the balloon loops). And the
  LENGTH optimizer scored wrong-branch marches as SMOOTH (a
  near-loop is near-constant curvature), so the knee ran to 88 m —
  his "way too big". Valleys never overshoot (blend integral and
  net angle share a sign) — exactly his crest/valley asymmetry.
- THE FIX, three march-side parts (the SEPTIC is untouched — old
  files' shapes are a contract):
  (1) march() tracks the UNWRAPPED pitch — pole crossings counted
  when the horizontal heading reverses while T is near vertical,
  with parity-aware direction memory (past an odd number of
  crossings the measured asin runs mirrored). marchEnd measures
  resPitch against it, so a wrong branch reads a ±360k defect and
  the fixed point can never settle there; yaw/bank are measured
  only on POLE-FREE passes (their pass-1 flip was the barrel-roll
  seed), and accumulated resYaw/resTw wrap to ±180° (a pose gap's
  short way around is always the correction). Crossing-free
  marches take the OLD expressions verbatim — bit-identical, the
  whole old suite is the off-gate.
  (2) deadbandSlopes now also CAPS |jerk|·d at MARCH_SLOPE_CAP=4 ×
  the seam curvature scale: file 2's honestly-measured plunge jerk
  (−0.025/m² — the force lane drops 4.2 g in 0.13 s right at the
  cut) extrapolated across a third of a 46 m bridge (κ → −0.2/m ≈
  −10 g mid-span). The seam still LEAVES on the measured slope;
  its d-scaled reach is bounded. Every existing fixture sits far
  below the cap (bitwise).
  (3) optimalBridgeLength's knee tolerance gains a SEAM-RATE FLOOR
  (KNEE_SEAM_FLOOR = 1.2 × the seams' own |Ω| in °/s): a crest's
  cost decays ~1/L indefinitely — the end curvatures keep pumping
  pitch the mid-span must undo — so the relative-only knee ran
  away; a candidate no rougher than its own boundary conditions is
  long enough. Matched seams keep the 1 m zero-distance collapse
  (their costs sit at the floor from candidate 1); valley knees
  stay governed by the slope term, which towers over the floor at
  short lengths.
- POST-FIX on his files: file 1 marches at EVERY length 5..120 —
  zero crossings, dYaw 8.1° CONSTANT across the sweep, roll 0,
  far-seam felt step ≤ 0.002 g, knee 88 → 60 m. File 2 marches to
  100 m (the capped jerk turns the −10 g spiral into a
  proportionate dive); only 120 m over its 34 m gap still falls to
  the honest septic — the solution there wants a near-vertical
  dive, the world-pitch hump's singular zone. KNOWN RESIDUAL: a
  crest bridge still S-curves BY CONSTRUCTION — C1 seam continuity
  with two upward end curvatures forces pitch to overshoot
  ~±10-20° around the net drop; shorter bridges are flatter.
- MANUAL-MODE STALE TANGENTS (his file 3: lenA/lenB = 11 m from an
  earlier optimize, target then hand-dragged to a 27 m gap → the
  D75 spiky-ends disease, peak rates 331/12/179 °/s by thirds):
  the compiler re-capture now SCALES lenA/lenB with the gap ratio
  in OPT_NONE whenever either endpoint moves (relative change >
  1e-6 — unmoved compiles and text round-trip noise stay bitwise;
  the canonical-save gate rides on that). Proportions preserved
  (partial-convert shares survive); a file SAVED with stale
  proportions keeps its shape until the target is nudged or
  re-optimized (loads never move pieces — the D148 precedent).
- UI (user request): the bridge badge menu's top gains "Bridge
  properties…" — the SAME movable panel as the toolbar button
  (FvdDesigner.showPlacementPopup extracted from the button
  handler; grayed "(no following piece)" on orphans per the D132
  badge-menu discoverability rule).
- Gates 54a-d (Phase3Gate 73): the crest fixture marches at every
  length pole-free, stays upright (min U.y > 0.5, roll delta 0),
  places deterministically (yaw spread 0.00°), far seam continuous,
  knee in [4, 64]. Phase2Gate 265 + DomainGate green untouched;
  launch smoke clean. Hand-test: walkthrough #107.

## D156. v17.15: BANKED CRESTS — damped/staged march corrections
##        (user report #4, "Odd bridge behavior 4", 2026-07-24)

- THE REPORT: after D155 he banked the entry piece (+19 deg roll
  delta; then the target too, -22 — an s-hill) and re-optimized:
  the intended shape held BELOW ~51 m (one bank) / ~45 m (both),
  and lengths immediately beyond "suddenly shoot the track around"
  into odd, extreme shapes. He asked for the root cause and for
  the clean behavior to extend to larger lengths.
- ROOT CAUSE (probe CrestProbe2 + RampTrace on his file): the
  PITCH-HUMP <-> TWIST COUPLING. The twist rate rolls the frame
  mid-march, re-aiming the blend's body-frame kappa_u; the pitch
  hump re-mixes the bank along the changed path. The coupled
  response gain grows with resPitch x twist (~kappa*d*twist), and
  right around his lengths it crosses 1: the D150 fixed point —
  SIX passes, FULL steps — diverged, its first full steps flinging
  the iterate out of the convergent basin (pole-crossed marches,
  branch-flipped bank reads) before any damping could act. The
  unconverged march missed the 0.3 deg render gate, and the septic
  fallback rendered with lenA/lenB = the REQUESTED length (120 m
  tangents over a 42 m chord = the balloon loops). A cliff, not a
  slope: converged-clean at 44, garbage at 46.
- FIX 1 — DAMPED FIXED POINT: cap 6 -> 14 passes; a pass whose
  worst defect GROWS halves the step (floor 1/8). Contracting
  sequences take full steps throughout, so every previously-
  converging case (and every gate baseline) is reproduced verbatim.
- FIX 2 — SEEDED STAGED RESCUE when the direct loop still fails
  (never entered otherwise): restart with the TWIST SEEDED AT THE
  SEAM-TO-SEAM BANK GAP (a pose property; 0 for unbanked seams)
  and the PITCH at the analytic blend defect (target minus entry
  minus the planar Hermite kappa_u integral), converge the pitch/
  heading humps alone (their fixed point is robust at any length),
  then RAMP the twist toward the measured bank gap in coupling-
  bounded increments (<= 0.8/(kappa*d) rad), re-converging pitch
  after each; a pole-crossed increment REVERTS and halves the
  step, and a walk straying > 90 deg from the seed stops (wrong
  family). LESSON THAT COST THE ROUND: a twist-0 stage 1 put the
  march in the WRONG PATH FAMILY on banked crests — the zero-twist
  blend sweeps an inclined arc whose end bank reads ~180 deg off,
  and the wrapped bank homotopy then walked the short-way-per-the-
  measure into a corkscrew that CONVERGED self-consistently (end
  bank exact, mid-span inverted, minUy -0.99) or wrap-flipped the
  accumulator mid-walk (the +81 deg stuck bank). Seeds are
  load-bearing; measures near the +-180 wrap are not directions.
- FIX 3 — BANK RENDER GATE: marchSpine now also refuses a march
  whose end bank misses the target by > 1 deg (converged marches
  close to 1e-4 rad = 0.006 deg — 170x margin; near-vertical NaN
  banks skip, matching bankGap's hold-off). Without it the
  corkscrew family PASSED the position+tangent gates (their ends
  are exact — the garbage is mid-span). Compat-safe: marchSpine
  always re-marches fresh, so old saves that converge keep
  rendering; only genuine garbage falls to the septic.
- FIX 4 — FALLBACK TANGENTS CAPPED at 1.5x the placed chord, in
  optimizeBridge AND the knee ladder's probes (lengthCost): a
  landing march never reads them (and any sane march's arc is
  under 1.5x its chord), so only the failure shapes change — from
  the ballooned "inverted vertical loop" septic to the honest D75
  full-gap-family sweep. The knee ladder now also RANKS failed
  candidates as that sane septic.
- POST-FIX on his file 4: scenario B (both banks) marches clean
  20 -> 90 m (2.1x the gap; was <= 44), scenario A (one bank)
  through 80; knees 120/112 -> 60, inside the clean zone; the
  deep extremes (~2.2x+ the gap) fall to the sane septic with the
  bank gate guaranteeing no rendered corkscrew ever. Files 1-3
  re-probed unchanged-or-better (file 1 marches 5..120 with knee
  72; file 2 to 100).
- KNOWN RESIDUAL: at ~2x+ the gap on banked crests an isolated
  length can still fail to converge (honest septic; neighbors
  march) — the family genuinely thins out near the world-pitch
  hump's singular zone. Practical lengths are covered.
- Gates 54e-g (Phase3Gate 76): banked-crest fixture (+19/-22)
  marches with the bank CLOSED and the track upright through
  64 m, far seam continuous; at 80/100 anything that renders has
  its bank closed. Phase2Gate + DomainGate green untouched; launch
  smoke clean. Hand-test: walkthrough #108.

## D157. v17.16: GEOMETRIC + TIME combs; concise Help rewrite
##        (user request "for fun", 2026-07-24)

- COMB MODES grew to five, still exclusive (his original v16.14
  rule): No | G-Force | Speed | GEOMETRIC | Time
  (AppSettings.combMode 0-4; combGeoMode 0-2 for the Geometric
  sub-mode, persisted per-user).
- GEOMETRIC COMB: curvature per METER in solid orange (the geo
  palette's roll color), teeth pointing OUTWARD from the curve —
  away from its center, the same convention as the G comb's -U for
  positive G. Computed straight off the bracketing sample frames
  (dT/ds perp T; runt landing steps widened past), 0.5 m per deg/m
  (COMB_K_SCALE). Sub-modes on inline dots next to the radio (the
  D149 gizmo-dots pattern, grayed unless Geometric is selected):
  LOCAL = body pitch + yaw tooth pairs (about -U / -R), GLOBAL =
  elevation + heading rates (skipping near-vertical track, the
  usual hold-off), COMBINED (default — "shows the curvature of the
  track") = one tooth along the negated curve normal, hover adds
  the radius. NOTE: his spec sentence "Combined shows the ." was
  cut off mid-thought; Combined-as-resultant-curvature was the
  interpretation, flagged in the summary.
- TIME COMB: a constant-height tick every 0.1 s of ride time —
  a ruler, not a magnitude — lerped by t (new timeHit, arcHit's
  twin) so the s/c sub-sample phase never quantizes it; integer
  tick indices so whole seconds test exactly: k%10==0 draws WHITE,
  the rest wear the old speed-comb light blue (his spec); hover
  reads the ride time. The SPEED comb turned GREEN (rgb 100,220,
  110) to free the blue.
- Tooth-box emission extracted to addToothBox (the time loop
  shares it); hover labels grew the deg/m / radius / time cases.
- HELP REWRITE (his ask: "extremely long" -> clean, organized,
  concise, current): the body shrank 67.6k -> 25.8k chars.
  Structure now: Getting started / Graph editor / Nodes / Handles
  / Sections / Straight-curve / Speed & conflicts / Pieces &
  strict seams / Bridges / 3D viewport / Menus & settings — terse
  helpKey rows, no version archaeology (that is DECISIONS.md's
  job), one idea per row. STALE text found and killed in the
  pass: the View-menu row still listed "Reverse POV view
  direction" (removed v17.11) and rail color (moved to Coaster
  v17.10), a "bridges will close those gaps later in Phase 2"
  line, and the literal heading "Menus & app.settings" (an old
  find-replace scar). The new combs documented in their section.
  MAINTENANCE RULE updated in CLAUDE.md: keep entries at this
  density — new features join their section in 3-4 lines.
- JavaFX-side, not headlessly gateable: hand-test walkthrough
  #109. All suites re-ran green (no core-side changes); clean
  rebuild + BOM scan + launch smoke clean.

## D158. v17.17: influence single-view; diamond click; hover units;
##        bridge exit double line (user quick list, 2026-07-24)

- RESULTING VIEW REMOVED (his call: "likely some bad edge cases
  that would either be buggy or unintuitive — upside down with
  opposite orientations, etc."): the influence surface has ONE
  view again — deltas edit around zero with the resulting curves
  dotted behind. Both badge radios are gone (Influence view was
  only a name once its sibling died). dispOff() stays as a
  0-returning choke point (a future display warp has its seam);
  strokeResultingLane + the ride-the-sum drawing (v15.0 D98,
  refined D102) are deleted; the "Δ " lane-name prefix now applies
  whenever influencing.
- SEAM DIAMOND: a clean SINGLE-CLICK (<= 3 px press-to-release)
  opens the strict-seam settings — was double-click; dragging
  still resizes the window and never opens it, guarded by a
  matching 3 px drag deadband so a click cannot commit a
  micro-resize on the way (band-edge drags keep responding
  immediately; the 3D seam ball keeps its double-click).
- HOVER UNITS BUG (his report: "hovering the resulting roll per
  second is giving the values in gs of laterals"): the resulting-
  overlay hover labeled comp 3 (the roll/bank overlay) through the
  geo-view branch's component-indexed names — "Gl … g". The
  roll/bank overlay is a rate/bank in BOTH views now: name
  roll|bank, unit °/s|°/m|° per the Resulting settings. Values
  were always right — labeling only. Audit of the siblings:
  laneUnit() already handles °/m display and absolute bank; the
  derived-strip hover reads laneUnit; the shared right-edge axis
  title still says "G (g)" in geo view with the roll overlay
  enabled — a shared-axis compromise (one title, two unit
  systems), left as is.
- BRIDGE EXIT SEAM draws the strict seam's DOUBLE line (his spec:
  "it essentially functions as one" — the target piece rides the
  bridge exactly like a follower rides a strict seam). Visual
  only: no carets (a live bridge is value- and slope-continuous by
  construction), no smoothing diamond (nothing to size), and the
  seam menu stays off bridge-adjacent boundaries as before.
- Help updated in place (Influence row one-view, Strict seams row
  click-the-diamond, Bridges intro double-line). JavaFX-side —
  hand-test walkthrough #110; all suites green, launch smoke
  clean.

## D159. v17.18: geometric-comb component colors + radius hovers
##        (user request, 2026-07-24 — the pre-commit tweak)

- The geometric comb's YAW-family teeth (local yaw + global
  heading) turned RED (235,60,45) and the COMBINED tooth the
  red-orange midpoint (245,115,65) of its two components;
  pitch/elevation keep the geo-roll orange. (The color half
  landed at the tail of the v17.17 round and is documented here
  with its formal request.)
- EVERY curvature tooth's hover now appends the radius:
  radiusTxt — "(r≈X m)" from 57.3/|val|, and a ZERO component
  (straight track in that plane) reads "(r=∞)". Note drawn teeth
  are never exactly zero (sub-2 cm teeth skip), so ∞ appears via
  near-zero components on large-scale settings — the honest cap.
- Help + CLAUDE.md comb wording updated. JavaFX-side; suites
  green untouched; committed with the v17.14-v17.18 stack.
