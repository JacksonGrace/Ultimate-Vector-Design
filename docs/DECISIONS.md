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
