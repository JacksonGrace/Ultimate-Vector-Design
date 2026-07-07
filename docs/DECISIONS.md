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
