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
