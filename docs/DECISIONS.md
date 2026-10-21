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

## D160. v18.0: SPEED CONTROLS — lifts, brakes and launches on a
##        strip above the x-axis (user spec, 2026-07-25; Phase 3's
##        "speed-controlled sections")

- MODEL: `SpeedControl` — an interval [x0, x0+len] on a section's
  OWN authoring axis (seconds / meters), never past its end.
  Two types: CONSTANT SPEED (lifts/transports — one target speed)
  and CONSTANT ACCELERATION (launches/brakes — a FELT longitudinal
  G magnitude toward a target speed; not "real" acceleration, per
  spec). Either can go COMPLEX: an editable Bezier graph replaces
  the constant (speed graph green, accel graph blue; accel keeps
  its constant target). Min/Max flags say how the target acts —
  max = ceiling (snap/decelerate above), min = floor (snap/
  accelerate below), both = hold; at least one always stays
  checked (unchecking the last re-checks the other). Controls
  live per section (`LaneSection`/`StraightCurveSection.speedCtls`
  + plain `speedMarks` diamonds); bridges take none, influenced
  and legacy-reversed sections refuse (speedControllable()). The
  Phase-1 `SpeedRegion` placeholder is retired (no file ever
  carried one).
- PHYSICS — a control edits the LOSS DEFICIT that already rides
  every integrator's RK4 state (D133), so v = sqrt(v0² + 2g·Δy −
  loss) everywhere and ZERO controls stay bitwise (whole old suite
  green + gate 55e):
  · const speed: post-step SNAP — loss += v² − v_t² whenever a
    flag is violated ("instantly snap", the user's spec). The shed
    or added energy persists past the control, exactly like a real
    lift/brake.
  · const accel: while off-target on a flagged side the step's
    loss rate becomes ∓2·g·A (felt long G = (dv/dt + g·T.y)/g = A
    ⇒ d(v²)/ds = 2g(A − T.y) ⇒ loss' = −2gA; friction is absorbed
    by the drive — the rider feels exactly A). A step CROSSING the
    target lands ON it; at the target the hold is exact (the
    ramp's limit). Gate 55b: flat-track ramp matches dv/dt = gA
    to 3e-11 m/s.
  · complex: values read EXACTLY off the cubic (exactAt); accel
    graphs may dip negative = coast.
  Drives/snaps key on the STEP-START axis position, decided once
  per step (per leg in ScIntegrator) — all three integrators, both
  domains. Pass D applies the SAME rules over per-sample resolved
  rows (compile maps each control onto its samples), so design and
  real speed respond EQUALLY — which is why a control CLOSES a
  design/real conflict: both sides exit at the clamp (gate 55c,
  split exactly 0.0). The reconcile then heals everything
  downstream on its own, the user's predicted "naturally resolve a
  speed conflict".
- UI — the SPEED CONTROL STRIP: the "+V" box at the graph's
  bottom-left toggles a lane above the x-axis (shares the plot's x
  mapping, domain views/warps included; AppSettings.showSpeedStrip
  + speedStripTall persist). The thin strip is the speed-control
  LINE — clicking adds a diamond; double-click OR right-click a
  diamond (or the line after one) turns that stretch into a
  CONTROL and opens the SPEED CONTROL options (movable dark popup:
  type radios, Complex check, speed box + Max/Min checks, accel
  box, Start/Length in native units, the read-only "End speed"
  reference showing true when split, Edit Speed/Accel button that
  expands the strip and nudges the popup clear). Left-click
  selects (white glow), Delete removes; the same spots re-open the
  options after the window closes. Diamond drags: start = MOVE,
  end = RESIZE (complex graphs stretch with the control —
  fraction semantics); the horizontal ripple check shifts later
  controls/marks; Ctrl snaps by the axis step; everything clamps
  inside the section and against neighboring controls ("controls
  never resize the section"). The ARROW at the strip's left
  expands the TALL view: resulting TRUE speed as a green dotted
  hoverable curve ("v = … m/s") + complex graphs in detail with
  the usual gestures — insert on the curve (exact de Casteljau),
  node/handle drags with smooth-partner mirroring, Ctrl snap
  (x by the axis step, values 1 m/s / 0.1 g), Delete. Segments/
  diamonds wear the type color (speed green rgb(100,220,110) =
  the speed comb's, accel blue rgb(90,160,255)); the baseline
  draws DASHED over sections that take no controls.
- 3D: a flat PLATE runs down the tie centers over every controlled
  stretch, type-colored (the friction-wheel/booster read) —
  Viewport3D.buildPlateMeshes off compile-resolved arc ranges.
- STRUCTURE OPS carry controls: node split/merge partition them
  EXACTLY (straddlers clip in two, complex graphs de Casteljau —
  gate 55f); whole/partial converts and bakeAxes carry them, domain
  conversions MAP positions through the section's compiled {t,s}
  map (gate 55g — the hardware keeps its track position); make-real
  re-clocks TIME controls arc-invariantly onto the true profile;
  refreeze touches nothing (controls ride the section). REVERSAL
  REFUSES a piece with controls (a lift acting on the mirrored
  flow is ill-defined — remove them first; model + editor hint),
  and influence entry grays while controls exist (the two-pass
  would drop them = silent reshape).
- FILE: `sctl type complex x0 len speed accel min max` + `cn`
  node lines (shared grammar) + `smark x`, inside the section
  block; old readers ignore all three, missing = none. Round-trip
  byte-identical (Phase2Gate). Deep copies ride DocState undo.
- GATES 55a-i (Phase3Gate 87): const-speed exact clamp/hold/exit,
  analytic accel ramp + exact landing, conflict closure 0.0,
  brake-under-friction (ceiling only — friction keeps shedding
  below target), inert-control bitwise, split/merge partition,
  domain-convert arc carry, geo + s/c clamps, and a min-lift
  RESCUING a stalling geo climb in both passes (55i).

## D161. v18.0: friction in NL2 units with NL2-like defaults
##        (user spec, 2026-07-25)

- The Coaster menu rows read "Friction (m/m)" (step 0.001) and
  "Air resistance (m⁻¹)" (step 0.00001, 5-decimal converter) —
  the old per-km ×1000 display is gone; the model was always m/m
  and 1/m.
- NEW documents (startup + File > New) default 0.03 m/m and
  1.0e-4 m⁻¹ ≈ NoLimits 2. `Coaster`'s own field defaults stay 0
  so headless gates and every loaded file keep the loss-free model
  unless their `friction` line says otherwise (old files load
  exactly as before).
- BUG FIX found in passing: `loadDesign` never copied the loaded
  file's friction coefficients into the live document — the
  spinners re-showed the PREVIOUS session's values and the loaded
  ride ran with them. The headless round-trip gates read
  DesignIO.load directly, which is why they never saw it.

## D162. v18.0: initial-design-speed surface + renames
##        (user spec, 2026-07-25)

- Badge menu grows "Set initial design speed…" (any non-bridge,
  non-reversed section): type the section's starting design speed
  directly. The ride entry (piece 0, section 0) writes through the
  Start-speed spinner (v0 coupling intact); any other section PINS
  on a manual value — a deliberate conflict, exactly the Add
  dialog's unlocked-padlock semantics. The dialog's "Set Design
  Speed to Real" button IS the refreeze op.
- RENAMES (same message): the resolve submenu's "Re-freeze design
  speed (keep graphs, reshape)" → "Set Design Speed to Real (keep
  graphs, reshape)" (the two surfaces do the same thing, so they
  say the same thing); the Add-section dialog's "Seam" row →
  "Initial design speed"; "Transition to real" → "Set to Real"
  (dialog radio + the s/c '+' fork).
- The horizontal Ripple check relabels "time" ↔ "distance" with
  the plot's display domain (it ripples the x axis, whatever the
  axis is; it also ripples the speed strip's diamonds).
- (His message cut off at "Also make 'Set Initial Design Speed'
  an option when s" — flagged; interpreted as the badge-menu
  button above, revisit when he finishes the sentence.)

## D163. v18.0: stalled track renders TRANSPARENT + the POV skips
##        it (user spec, 2026-07-25)

- Pass D's true march bottoms out at the 0.1 m/s crawl floor —
  from that sample to the end of its RUN the train never really
  arrives. Those samples now render as GHOST track (translucent
  grey rails + ties, whatever the rail-color mode; the piece's
  meshes split at the stall sample) and the POV never parks on
  them: riding forward jumps to the next run's start, backward to
  the last solid sample, scroll/entry clamp the same way
  (Viewport3D.computeStallRanges/povClampStall).
- A speed control is the natural fix — gate 55i's lift turns the
  ghost stretch solid again (real stall/rollback physics remains
  future work; this is the honest display of the creep-through
  model).

## D164. v18.0: time-comb density follows the other combs
##        (user request, 2026-07-25)

- The TIME comb's whole-second ticks stay EXACTLY where they were
  (white, timeHit-lerped). The 0.1 s minor grid is replaced by
  FILLER ticks: between consecutive whole seconds, N evenly
  TIME-spaced ticks with N = round(arc of that second / 0.5 m) − 1
  — roughly the other combs' density everywhere (the fixed grid
  was sparse at speed and piled up in crawls). Fillers keep the
  speed-blue; kind 8/9 split unchanged.

## D165. v18.1: speed-control feedback round — popup polish +
##        complex-graph parity + hard section bounds (user feel
##        round, 2026-07-26)

- POPUP: the "End speed" reference moved directly UNDER the
  target-speed row; every spinner editor is numeric-only; the
  panel HIDES when the app loses focus and returns — same spot,
  still open — when focus comes back (the pv popup's v14.4
  pattern; a Popup is its own OS window and floated above other
  applications).
- COMPLEX GRAPHS get the main lanes' node features (his "etc.
  etc. etc." list, kept at the smaller line/node scale he likes):
  S slides along the dominant axis, Z/X lock one (routed to the
  strip while it holds the last-pressed selection — spFocus);
  H flattens handles; F straightens the segment LEAVING the
  selected node, Shift+F straight-locks it, Shift+H level-locks it
  (the single-selection reading of the pair rule — flags enforced
  every redraw like the main locks); Shift-hover reads the
  analytic slope in the control's own axis units; the zero-line
  fill shadow + the grey hover ring joined the drawing; broken
  nodes tint slightly darker. B (and Shift+B, same thing per
  spec) on a CONSTANT-SPEED graph's end nodes snaps the node's
  value AND slope onto the REAL speed just outside the control —
  the graph then leaves/meets the ride smoothly; interior B stays
  the smooth/broken toggle (re-smooth keeps the IN handle).
- HARD BOUNDS: controls can never sit past their section — a
  SHORTENED section pulls them back in (spClampControls, every
  redraw; complex graphs rescale by fraction; the deferred rebuild
  keeps physics in step). Independently clamped controls may stack
  at the end; the control lists stay x-sorted so hits select the
  FIRST overlapping diamond (his rule). Minimum control length =
  2× the domain's minimum node gap (0.02 s / 0.2 m) everywhere —
  drags, the popup's Length spinner, the clamp.

## D166. v18.1: every window modeless + ride keys everywhere +
##        the numeric-entry sweep (user spec, 2026-07-26)

- "With ANY window open, right-click the viewport and all view
  controls still work": every tool dialog dropped WINDOW_MODAL
  (Shortcuts, Snapping, G-force colors, Pick color, Orientation,
  Convert, Add section, Element, Node, Initial design speed,
  Resulting settings, NL2 export — all show() already, no nested
  event loops), and EVERY secondary Scene — those dialogs plus
  the Speed Control panel, Track Piece/Bridge panel, seam
  settings, Resulting popup, density preview, and all menus via
  the installMenuUndoKeys choke point — forwards the ride/fly
  keys to the viewport (installViewKeys → the pv popup's v16.2
  forwardViewKey). Only the unsaved-changes confirm and the OS
  file choosers stay modal (they must).
- ACCEPTED CORNERS (modeless dialogs hold live references): a
  node/element dialog left open across edits applies to the
  object it captured — a deleted node's apply is a harmless no-op
  on an orphan; the same dialog can be opened twice. Flagged.
- NUMERIC SWEEP: every number entry in the app — spinner editors
  and text fields — takes a digits+period formatter (signed where
  negatives are legal: values, angles, G stops; the color
  dialog's hex field keeps its letters). This is also what makes
  WASD-while-focused harmless: movement letters can never leak
  into a numeric box.

## D167. v18.1: smooth POV + adaptive tube tessellation with a
##        Mesh Quality setting (user report, 2026-07-26)

- POV JITTER (worst at Ctrl ×0.25): the camera snapped to the
  0.01 s sample grid — at slow advance successive frames landed
  on the same sample and the view stepped. The camera now
  INTERPOLATES the heartline frame at the exact povTime
  (povPoseAt — the combs' timeHit lerp), so motion is as smooth
  as the display's frame rate at any speed.
- TUBE SMOOTHNESS: rails used a fixed every-3rd-sample stride
  (~0.8 m at cruise) — visibly polygonal in tight curves. Rings
  are now placed by TURN ANGLE (railRings): a ring lands whenever
  the tangent has swung ~2° since the last one, with a 1.2 m cap
  on straights and an arc floor so ultra-tight twists can't
  explode the mesh — exactly his "density should increase for
  tighter turns, to a point". The View menu's "Mesh quality (%)"
  (25-400, ↺, AppSettings.meshQuality) scales the angle budget,
  the FVD++ knob; applied on release (full remesh).

## D168. v18.1: time-comb colors + the POV stops at a stall
##        (user spec, 2026-07-26)

- TIME comb: whole-second ticks are DEEP BLUE at 1.5× the filler
  height, and every 10th second (10, 20, …) is RED (its own mesh,
  kind 10); the fillers keep the light speed-blue. (Supersedes
  D157's white seconds.)
- POV at a stall now STOPS — the stall point behaves like the end
  of the track (riding W parks exactly on the last solid sample;
  S backs away; entering/scrolling clamps the same way).
  Supersedes D163's jump-to-the-next-run, which he read as the
  ride teleporting.

## D169. v18.2: roll-only converts keep the non-roll lanes
##        BIT-EXACT (user spec, 2026-07-26)

- A Convert whose ONLY change is the roll axis and/or roll
  behavior (same type, domain, rate units, yaw/pitch axis) now
  keeps lanes 0/1 as DEEP NODE COPIES of the original — Gv/Gl (or
  pitch/yaw) targets mean exactly what they meant under any roll
  representation, so refitting them was pure added residual. Only
  the roll lane re-fits to the measured bank/rate in the new
  semantics; the whole conversion error collapses into that one
  lane (gate 55j: lanes 0/1 bit-exact, ride end moves 3 mm on the
  ±40°/s wave fixture — a full-lane refit moved decimeters). The
  Orientation dialog's roll-only bake takes the same path.
- The density preview auto-selects the ROLL lane when such a
  convert opens (its slider only moves that lane now); switching
  lanes during the preview stays allowed as ever.

## D170. v18.2: speed-strip parity round 2 — the full main-editor
##        feature set + the resizable strip (user feel round,
##        2026-07-26: "many do not appear to have been implemented")

- What landed on the complex graphs this round: SHIFT+CLICK
  multi-select + BOX select on empty tall-strip space (primary =
  earliest caught, the main rule) with GROUP drags clamped against
  non-selected neighbors; Delete removes the whole selection
  (interior nodes; endpoints never silently kill the control); H
  levels a multi-selection onto the primary; ARROW nudges by the
  snap steps (Ctrl lands on the grid); CTRL-HOVER pre-snaps the
  insert dot in x (value rides the curve, the white main-plot dot
  + white readout — the type-colored hover was v18.1's, wrong per
  spec); S/Z/X drag GUIDES draw (type color = value changing,
  purple = x changing, anchored at the gesture start); handle S =
  slide on the current line, length only, partner untouched;
  handle CTRL snaps the fraction to the ease presets; OFF-SCREEN
  handles draw the dashed stub + hollow edge marker, and clicking
  it expands the scale (spViewExpand — cleared with the
  selection). The smaller node/line scale stays (he likes it).
- The hover dot could linger mid-drag (spMove never runs during
  drags) — cleared at every strip press now.
- ZERO SITS ON THE CONTROL LINE (user spec): the tall view's value
  scales are linear about the line (≈72% down the strip) — speed
  grows up from it, negative accel dips below; the "0" label sits
  on the line itself.
- RESIZABLE STRIP: the strip's top edge is a grabbable DIVIDER
  (V-resize cursor, center grip marks) — drag to any height
  (AppSettings.speedStripSize, 44-420 px, persisted), below ~40 px
  it collapses to the thin line, dragging the thin strip's edge up
  expands it. The ▲/▼ arrow stays as the quick toggle.
- Still absent, deliberately: value-ripple inside control graphs
  and the typed node dialog (double-click is the promote/options
  gesture there).

## D171. v18.2: the misc round (user feedback, 2026-07-26)

- SPACE (and T/Shift+T) now work from EVERY window: forwardViewKey
  handles the pov/viewTop/viewSide actions before the movement
  keys, so "right-click the viewport, press Space" toggles POV
  whatever panel holds focus (extends D166; the numeric-only boxes
  keep the letters harmless).
- The Resulting dropdown's ROLL check DESELECTS when it grays
  (redundant) — the v17.7 sticky-check rule is gone (a
  grayed-but-checked box read as broken).
- An ABSOLUTE-BANK section's lane-2 toolbar button wears the bank
  RED like its curve (setLaneButtonMode reads activeAbsRoll).
- TIME comb: the red emphasis ticks come every 5 s (was 10, D168
  amended).
- POV at a stall RE-SPECCED once more (his clarification — D168's
  stop was the misread): the stall point behaves like the END OF
  THE TRACK, which WRAPS — forward jumps to the next piece's
  start, or to the ride's beginning when the stall is in the last
  run; backward still lands on the last solid sample.
- The "Mesh quality (%)" View row was REMOVED unshipped (D167
  amended): the tuned 100% adaptive tessellation IS the behavior.
- His completed sentence: node-0 double-click of ANY section now
  carries an "Initial design speed (m/s)" row (the ride entry
  keeps its Start-speed row; elsewhere a changed value pins as a
  deliberate conflict — the D162 badge-dialog semantics).

## D172. v18.3: the strip RECYCLES the real editor (user verdict, 2026-07-26)

His round-3 verdict on the strip's complex graphs: "fully take the
exact code for the graph editor and recycle it… all of the same bugs
and edge cases are not getting covered", with the only sanctioned
differences the smaller nodes/graph lines — "and of course… the typed
node dialog and the value ripple".

- The v18.1/v18.2 PARALLEL mini-editor (the sp* node/knob/box/hover
  clones) is DELETED. In its place an EDITING-SURFACE indirection:
  the main pipeline already flowed through a handful of choke points
  (active(), ltToX/xToLt via warpFwd/warpInv, valToY/yToVal, dispVal/
  nativeVal, laneColor/laneUnit/laneName, snapXStep/snapVal, minGap/
  insertGap, sectionEnd/endLimitFor, axisDist, activeDistNative,
  rateViewScale, activeInfl) — surfStrip retargets exactly those at
  the selected complex control's lane (surfCtl/surfRs; withSurf()
  brackets a call). EVERYTHING above the chokes — hit tests, the
  press ladder, node/handle drags, S-slide/Z/X, Ctrl snapping + the
  ease presets, H/F and BOTH locks (enforceLocks walks every control
  lane now), box + Shift select, arrow nudges, B semantics, hover/
  insert/readout drawing with the Shift twins, off-screen handle
  stubs + the expand click, the typed node dialog and the ripple
  checks — is the SAME code running on a second surface.
- Extractions that made it possible: drawEditOverlays (redraw's
  node/hover/guide/handles/box block; bounds via editL/R/T/B, node
  sizes via nodeR()/knobR() — the strip's smaller look is those two
  functions and nothing else), pressNodesKnobs/pressCurveInsert/
  pressBoxStart out of the press handler, finishBoxSelect out of
  release. Shared-state OWNERSHIP: selIdx/selSet/axisLock belong to
  the surface spFocus names, hoverT to spHoverSurf, the live gesture
  to surfGesture — each surface's overlays draw only what they own,
  and focus transitions drop the other side's node selection.
- The node DIALOG captures its surface at open: Apply and the live
  computed twins re-enter it (they run after withSurf returned).
  Under the strip it drops the Start-speed/Initial-design-speed and
  boundary-match rows — those are section affairs.
- B on a const-SPEED control's END nodes keeps the v18.1 real-match
  (value + slope onto the true speed just outside); interior nodes
  take the house smooth/broken toggle verbatim; Delete follows the
  house rules (node 0 refuses, the last two nodes stay).
- Guardrails: dragGrowTimer is inert during strip gestures (the
  strip's wall is ABSOLUTE — endLimitFor returns the control length,
  no started-at-end exemption), spFitScales honors the shared
  handleViewExpand, and the tall strip draws a main-plot-style value
  grid + labels on the selected control's own zero-on-the-line scale.
- (Process note, expensive lesson: the refactor's first attempt
  spliced at the WRONG anchor twice — hitNode's body opens byte-
  identically to redraw's node loop — and the damage hid behind
  maven's no-op incremental compile. GraphEditor.java was reset to
  HEAD and the whole v18 layer rebuilt in final form; since then
  every scripted anchor asserts uniqueness and every check compile
  wipes target/classes/coaster first.)

## D173. v18.4: strip refinements round (user feedback, 2026-07-26)

Six refinements on the v18.3 shared-editor strip:

- DRAG-CREATE: click-and-drag on a free stretch of the control line
  creates a SPEED control from press to release (kind-4 gesture:
  live translucent preview + end diamonds + length readout, Ctrl
  snaps the moving end, clamped into the free interval around the
  press, min length 2× the domain gap — a too-short drag grows into
  the room; marks swallowed by the range are consumed; one undo; the
  new control arrives selected, options via the usual double/right
  click). A clean ≤3 px click still adds a plain diamond.
- The resulting TRUE-speed curve (and its hover) HIDES inside
  SPEED-type controls — it only overlapped the control's own
  line/graph there (ACCEL stretches keep it: there the true curve IS
  the information).
- The strip starts CLOSED on every program open:
  AppSettings.showSpeedStrip became SESSION-ONLY (the tall/height
  prefs still persist; stale properties keys are ignored).
- FLASHING-GRAPHS ROOT CAUSE: a strip-surface mutation (dragNodeTo
  under withSurf) triggers rebuildTrack → refresh() → redraw() with
  surfStrip STILL SET, so that frame painted the whole plot through
  the strip's chokes (wrong lane, wrong scales — "the main graphs
  disappear"). redraw() now ALWAYS renders from the main surface
  (drops the flag, re-enters, restores; spDraw re-enters the strip
  surface itself). One guard kills the whole flash class — every
  snapping shortcut and drag path included.
- Complex const-SPEED controls draw their resulting LONGITUDINAL G
  inside their span (v18.4, user spec): (d(v²/2)/ds + g·T.y)/g off
  the TRUE arrays — the accel-control BLUE, dotted like every
  derived curve, on the accel scale (zero on the line), EDGES
  EXCLUDED (SP_LONGG_EDGE 4 samples — the boundary snap is an
  instant Δv, its difference quotient is garbage). Hover reads
  "long G = X g", Shift the slope per axis unit (composed at draw
  time so a Shift press flips without a mouse move).
- MINI LOCK VISUALS: locked strip segments drop the curve stroke and
  wear the main plot's twin parallel lines at strip size (0.8 px,
  ±1.1 px along the chord normal); LEVEL locks tint the band between
  — Shift+F and Shift+H read at a glance on control graphs too.

## D174. v18.5: last strip nits before the commit (user, 2026-07-26)

- The TRUE-speed curve hides only inside COMPLEX speed controls
  (D173 amended): a PLAIN constant control's segment sits ON the
  control line, not at the speed's height — the true curve is real
  information there and stays (hover included).
- The longitudinal-G curve joins spFitScales' accel fit: it always
  stays in view vertically (steep track pushed it past the default
  window).
- The control is a HARD BOX under ripples too: with the time ripple
  on, the LAST node of a control's graph never moves horizontally
  (value ripple still moves it vertically), the dragged node clamps
  against the pinned end with min gaps preserved right-to-left, and
  nothing ever leaves the control.
- A control graph ending before the control's end draws its
  hold-final-value continuation DOTTED (the lane strips' 3/7
  convention); the shared pipeline already makes the held stretch
  clickable to append.

## D175. v18.6: menu slim-down + HONEST low-speed records + strip scale growth (user, 2026-07-28)

Also his verdict: NO Advanced Friction Model — the two-coefficient
model stays the whole friction story (the D160-round design for a
normal-force-scaled μ toggle is dropped, not deferred).

- The Coaster menu's "Start speed" row is GONE: the ride entry speed
  is edited in node 0's double-click dialog (its historic second
  home). The spinner OBJECT survives off-scene as the v0
  write-through choke (undo + rebuild wiring; loads/News still set it
  programmatically).
- LOW-SPEED "PIECEWISE" SPEEDS root-caused: every integrator RECORDED
  max(0.1, v) per sample and passD recorded max(0.1, vh) — on the
  resulting graph every slow dip PARKED at exactly 0.1 m/s, and the
  design/true pair grazed the clamp differently (the jitter he saw
  near zero-speed stretches; FVD++ has no such park). A probe showed
  the underlying data perfectly smooth — the stair was pure record
  clamping. ALL FIVE record sites now store the HONEST value (force
  time+dist, geo time+dist, s/c, and passD); every INTERNAL floor is
  untouched (vSafe 0.5 for ω, the 0.5 creep advance, the loop v that
  feeds drive/trapezoid decisions) — physics bitwise. Consumers that
  divide or seed design speeds floor at their own sites (reconcile's
  vIn/vTrue, entrySpeeds, the add-dialog vEnd/vAuto — Math.max(0.1,·)
  = exactly the values they historically read); the viewport's stall
  predicate (≤ 0.1 + ε) selects identical ranges either way. Gate
  48f's stall check now compares ABOVE the 0.1 creep floor explicitly
  — it always meant "no conflict above the creep floor", and honest
  records expose the sqrt-amplified creep-zone residual its own
  comment already called measurement scale.
- The strip's value scale GROWS during node/handle drags exactly like
  the plot's axes (dragGrowTimer's surface branch): overshoot past
  the strip's top expands the edited control's scale at a
  proportional rate (accel also grows downward; the speed scale's
  zero stays ON the line), re-dispatching the drag per tick — no
  more snap-to-scale after release.

## D176. v18.7: POV un-freeze + popup Start/End + viewport trio (user, 2026-07-28)

- POV STUCK AT ZERO-SPEED SECTIONS (his regression report on D175):
  the ride advance scales povTime by trueV[at]/max(0.1, vDesign) —
  with HONEST records the numerator now goes to 0 approaching a
  stall, so the advance Zeno-froze just short of the stall range and
  povClampStall's end-of-track wrap never fired. The RIDE ratio now
  floors its numerator at 0.1 (max(0.1, trueV)/max(0.1, vD)) — the
  exact pre-v18.6 crawl feel, guaranteed to reach the range; the
  graphs stay honest (this is ride pacing, not display).
- The Speed Control popup shows BOTH boundary speeds — "Start:
  12.3 m/s (true 11.8)" and "End: …" (his exact labels; the true
  twin appears only when split ≥ 0.05 m/s) — via spCtlSpeeds(end),
  spEndSpeeds generalized.
- Translation gizmo arrows are DOUBLE-ENDED (a mirrored shaft+tip
  half rotated 180° about local X): grab either side, same
  absolute-from-press drag math. The bridge LENGTH tangent arrow
  stays one-sided — its direction means "longer".
- Node markers ride the HEARTLINE in orbit/fly (user spec — they sat
  at spine/track level like ties); POV keeps the spine height (the
  camera occupies the heartline). The marker signature carries
  povActive so Space transitions rebuild.
- AXES REFERENCE: a 96 px canvas at the viewport's bottom right
  draws the world X/Y/Z directions under the CURRENT camera —
  gizmo-arrow colors, back-pointing axes dimmed and drawn first,
  labels at the tips, a lightweight always-on AnimationTimer that
  redraws only when the camera rotation matrix actually changes
  (screen-right = axis·camX column, screen-down = axis·camY, world
  +Y entering as scene −Y per the Y-flip discipline).


## D177. v18.8: B/Shift+B at yaw-pitch-global seams — EFFECTIVE roll semantics (user bug report, 2026-07-28)

His file ("B Shortcut not working 7.28.26.fvd"): a TIME force section
carrying a complex launch control into a DISTANCE geo section with
yawPitchGlobal + °/m, then back to force. B/Shift+B on the geo's
first node "doesn't line the real graphs up" at the force→geo seam;
the geo→force seam after it "seems okay".

- ROOT CAUSE (headless BProbe replicating boundaryStatePerSec on the
  file): lanes 0/1 were FINE — the world-rate path's targets land and
  the compiled elevation'/heading' flow smoothly through the seam
  (finite-difference off the frames; the apparent ±2-6 °/s "jumps"
  in a 3-8-samples-inside linear extrapolation are the sampled
  path's documented curvature bias, not a discontinuity). The REAL
  step was BODY ROLL: 0 → −35.4 °/s across the seam. On a
  yaw-pitch-GLOBAL geo section the world-Y heading rotation itself
  twists the body — Ω·T = ψ'·T.y + p — so its "local" roll lane is
  effectively a BANK RATE (φ' = p − ψ'·sinθ + ψ'·T.y = p exactly,
  T.y = sinθ). The roll matcher compared RAW rollGlobal flags (both
  "local"), took the direct authored path, and copied the force
  side's body roll 0 verbatim — missing the ψ'·T.y transport term
  (35 °/s here). The geo→force direction read fine only because his
  geo section's heading rate is ~0 at that seam (transport ≈ 0) —
  exactly matching his "could just not be looking close enough".
- FIX: GraphEditor.effRollGlobal(ls) = rollGlobal || (GeoSection &&
  yawPitchGlobal) — the quantity a roll lane actually holds — used
  by the rollAcrossSeam gate (fires on effective mismatch, passes
  the effective want), rollConverted (the neighbor's effective
  semantics pick the ± transport direction), the direct
  authored-path equality, and laneWorld's lane-2 test (real-split
  seams match bank rate through worldRates). D114's local|global
  machinery already had the exact conversion — it was gated on the
  wrong flag. Raw-flag comparisons stay where they belong:
  mergeSections/likeSections (merging needs identical AUTHORING
  semantics), the ABS-roll gates (absolute is defined rollGlobal-
  only).
- PROBE RESULT: after one Shift+B the roll target reads +0.669 °/m
  (= +34.0 °/s bank rate) instead of 0 and the true body-roll step
  collapses 35.4 → 1.2 °/s (the shipped one-sample-in read of the
  D114 path; re-presses are stable to 1e-5). Elevation/heading and
  the felt lanes were and remain continuous.

## D178. v18.8: TRUE orthographic views + viewport/graph polish round (user, 2026-07-28)

- TRUE ORTHO Top/Side (reverses D48's "ParallelCamera is unusable"
  — that verdict is OBSOLETE on JavaFX 26, established by offscreen
  snapshot probes): a ParallelCamera MIRRORS the orbit rig —
  transforms [pivot][yaw][pitch][Scale(s,s,ORTHO_DEPTH)]
  [Translate(−w/2,−h/2,0)] with s = the world-units-per-pixel the
  camZoom/1°-lens bookkeeping implies (2·d·tan(FOV/2)/h) — and is
  swapped in as the SubScene camera while orthoView. ALL existing
  state machinery is untouched (camZoom still IS the zoom, pan/
  restore/handoffs unchanged); syncOrthoCam (signature-guarded,
  riding the axes-compass AnimationTimer) keeps it glued through
  zoom/pan/resize. PROBED FACTS the design leans on: ParallelCamera
  honors translate+rotate+scale in JavaFX 26; near/farClip setters
  are IGNORED and the depth window is a fixed ±(viewport w/2) in
  CAMERA units around the projection plane; a larger Z-scale widens
  it proportionally (ORTHO_DEPTH 10 ⇒ ±(w/2)·10 ≈ ±7 km at app
  sizes) and depth ordering survives the non-uniform scale.
  Adaptations: effEye backs the pick/projection filters' eye off
  the projection plane by the window depth (parallel projection
  renders BEHIND the plane; the old dot-test would cull visible
  geometry); ringHit's ortho branch shoots the pixel ray from the
  plane point along fwd (allowing t<0) instead of the perspective
  fan; positionGizmo's ortho scale is depth-INDEPENDENT (10% of
  visible height); the axes compass normalizes the camera matrix
  columns (the Scale would shrink them).
- VIEW-MODE LABEL (top-left overlay): names the live camera —
  Fly/Orbit | POV | Top | Side — and clicking opens a switcher
  list (● marks current). Switches route through the SAME toggles
  the keys use; leaving captured POV via the list releases capture
  to plain orbit. syncViewMode called from togglePov /
  toggleOrthoView / exitOrthoLens (fly shares the orbit label).
- POV NODE MARKERS FIX (his report: "don't go back to the track in
  POV, always at the heartline"): D176's height flip was correct
  but its sync rode editor.refresh() → redraw(), which
  early-returns while the GRAPH PANEL IS COLLAPSED (plot height 0)
  — riding POV with the panel down kept the orbit-time heartline
  markers forever. togglePov now calls editor.refreshMarkers()
  DIRECTLY (canvas-independent, sig-guarded = free when redraw also
  ran). Verified live: povActive=true rebuilds at spine Y.
- XYZ COMPASS moved to the BOTTOM-LEFT (user spec — D176 had it
  bottom-right, where it also collided with the collapse button);
  when a readout occupies that corner (comb hover label, "Backwards
  POV (A)") the compass rides just ABOVE it — visibility listeners
  bump the StackPane margin by the label height, so the two never
  overlap in any camera mode.
- Toolbar DROPDOWNS lost their hover tooltips (Resulting, piece
  selector, Track Piece/Bridge properties — user: no hover
  descriptions on the dropdown row).
- The graph's MAX/MIN scale labels go INVISIBLE when the rotated
  y-axis title's vertical footprint would cross them (short plots;
  measured via a throwaway Text at the title font ± 4 px pad).
- EVERY hover readout clamps INTO the plot like the node-drag
  readout (clampHover: resulting-overlay, derived-strip, lane/insert
  — the shared surface path also covers the speed strip's complex
  graphs — and the strip's true-speed/long-G readout width fix).
- Speed Control popup: the Start/End speed readouts share ONE row,
  Start first (user spec — D176 stacked them).

## D179. v18.9: derived strips speak the ACTIVE section's axis semantics + strip/readout round (user, 2026-07-28)

- INACTIVE-STRIP DERIVED CURVES (his screenshot: a verified-smooth
  force→ypG-geo seam still read as a big visual step): derivedAt/
  trueAt drew BODY rates whatever the active section authored, so a
  yaw-pitch-global (or effective-global-roll) view never lined up
  with its neighbors' grayed curves. Both helpers now route through
  Coaster.worldRates when the active lane authors world quantities
  (new activeLaneWorldAxis: geo view lanes 0/1 under ypG, lane 2
  under EFFECTIVE roll semantics in both views; off on the strip/
  influence surfaces; NaN near vertical draws 0 — the abs-bank
  precedent), then apply the °/m per-sample speed division as
  before. The authored-direct fast path for inactive same-mode
  strips now requires the strip's axis flags to EQUAL the active
  section's (was a blanket !globalAxes(): two same-flag global
  strips fell to derived body rates — same false step). The
  RESULTING-dropdown overlays keep their own persisted settings
  (deliberately user-configured presentation). Probe on his edited
  file: the force strip's derived world rates land at (1.348,
  −3.692, −2.506) °/m at the seam vs the geo's authored (1.353,
  −3.689, −2.474) — joined; body rates read 75 °/s there.
- SPEED-CONTROL BODY DRAG: click+dragging a control's segment MOVES
  it exactly like its start diamond (spGrabOff anchors the grabbed
  spot under the cursor, applied before the Ctrl snap; 3 px deadband
  keeps a plain click a selection; end diamond still resizes).
- +V always opens the strip COLLAPSED (tall re-arms via the arrow/
  divider/Edit button; the persisted tall flag is overwritten on
  open).
- The strip's true-speed/long-G hover clamps INTO the strip's clip
  rect (his screenshot: the readout drew half outside and was cut);
  the remaining cursor readouts (element resize, bridge length, seam
  smoothing, split-line %) were audited — all already clamped or at
  fixed safe heights.

## D180. v18.9: viewport round — ground line, side sub-modes, ONE FOV, extra view panes (user, 2026-07-28)

- SIDE-VIEW GROUND LINE: true ortho sees the y=0 plane exactly
  edge-on (zero pixels — the pseudo-ortho's faint slice is gone), so
  side views draw a subtle full-width horizontal overlay line where
  the ground is (world y=0 == scene y=0, the centering translate is
  XZ-only; positioned per frame off the compass timer via
  root3D.localToScreen — pitch is always 0 in side views so the
  plane IS a horizontal line).
- SIDE SUB-MODES: while Side is live, a second dropdown under the
  view label picks Front (looking along world +Z), Back, Right
  (−X), Left (+X) or Tangent to Selection (the D53 behavior, still
  the Shift+T default). orthoSideMode rides toggleOrthoView's split
  into orthoEnter/applySideMode; the view label's own "Side" pick
  keeps the last sub-mode.
- ONE FOV (was "POV camera FOV" + "Fly & orbit FOV"): a single
  "FOV" slider drives povCam/flyCam/orbitCam (and the extra panes);
  povCam reads flyFov at startup and the legacy povFov key is
  written alongside but no longer read (the D84 stale-key
  precedent).
- EXTRA VIEW PANES ("Add View"/"Remove View" at the bottom of every
  view label's list): the 3D area is a horizontal SplitPane —
  dividers drag like the graph divider; Remove reflows the rest.
  Each added pane (Viewport3D.MiniView) is a PASSIVE VIEWER: its
  own orbit rig + true-ortho mirror + view/side labels + ground
  line, middle-drag orbit / scroll zoom / ortho right-drag pan,
  over a shared-mesh CLONE of the world (new MeshView/Sphere/
  Cylinder/Box/Group nodes wrapping the SAME Mesh + Material
  objects, transforms flattened to Affine copies; gizmos/markers/
  POV holder skipped). Content re-clones on every rebuild and tie
  re-tint. DELIBERATE SCOPE (flagged): no POV/fly/picking/editing
  in extra panes — the primary keeps those (a JavaFX node tree
  lives in ONE scene, so true sharing is impossible; the clone is
  the honest cheap mirror); the primary pane cannot remove itself —
  its Remove View drops the LAST added pane; panes are session-only
  (not persisted); pure visibility toggles (heartline check) reach
  clones on the next rebuild.

## D181. v18.10: FULL-PARITY view panes + ortho grids + polish round (user, 2026-07-28)

- FULL-PARITY MULTI-VIEW (his re-spec REJECTING D180's passive-clone
  scope: "every view should have the same functionality... equal
  importance"): every pane IS a complete Viewport3D instance — own
  meshes/ties/combs/markers/gizmos/compass/labels/ground line, own
  orbit/fly/POV/ortho cameras. The class was already ~95%
  instance-encapsulated; the restructure: (1) the DOCUMENT phase of
  rebuildTrack (compile + reconcile + auto-reoptimize + shared
  arrays + Coaster-menu stats) moved to FvdDesigner.rebuildTrack,
  which loops every pane through the remaining per-pane
  rebuildMeshes(); (2) povTime/povFollowPiece are SHARED on
  FvdDesigner (one ride position, one graph marker, one stats line
  — two POV panes deliberately show the same spot; updatePovStats
  lives shell-side now); (3) KEY ROUTING (his sorting-out note):
  Space/T/Shift+T and the ride keys go to keyViewport() — a
  CAPTURED pane first (fly or right-clicked POV; captures are
  exclusive via releaseOtherCaptures, two Robots would fight), else
  the hovered/last-hovered pane (MOUSE_ENTERED/MOVED track it);
  (4) each pane assembles its own overlays in buildPane(); panes
  live in the horizontal viewSplit, Add View appends a full pane,
  Remove View removes THAT pane (any but the last), shutdown()
  stops its timers. The D180 MiniView clone machinery is DELETED.
  Cost per edit: ONE compile, N mesh builds (mesh building is the
  cheap half; drag-responsiveness holds at 2-3 panes).
- The top-right "Axes: global/local" overlay toggle is REMOVED
  (user spec) — the View menu's inline Local/Global dots are the
  whole surface. The graph COLLAPSE arrow re-parents into the
  RIGHTMOST pane on every add/remove (placeCollapseBtn).
- NL2-STYLE ORTHO GRID OVERLAYS (Top + Side): a per-pane 2D canvas
  draws world-aligned adaptive 1-2-5 lines (minors ≥ ~28 px apart,
  majors every 5th) — top = world X/Z, side = height + the view
  plane's horizontal axis. The TANGENT side's horizontal origin
  anchors at the selection point CAPTURED AT VIEW ENTRY
  (tangAnchor; moving the node later doesn't slide the grid — his
  spec) while heights stay absolute; sig-guarded redraw on the
  compass timer.
- Side picked from the view list defaults to FRONT (user spec);
  Shift+T remains the tangent gesture. TANGENT TOOLS under the
  sub-mode dropdown: ↻ "Refresh" re-tangents to the CURRENT
  selection (re-centers + re-anchors the grid); ↔ "Reverse View"
  spins 180° in place (same pivot, same grid, other side) — both
  with tooltips per spec.
- Every file OPEN (New / Load / the startup Untitled) begins with
  the RESULTING overlays OFF (clearResultingPicks — the picks still
  stick within the session once re-checked).
- worldRates' near-vertical hold-off TIGHTENED 0.15 → 0.05 cosθ
  (~81° → ~87°, user: "a lot closer to vertical") — display zeros,
  bake hold-offs and matcher refusals all start later; the 42-series
  gates stay green (the 3600 °/s cap still bounds spikes).
- The speed-control LINE is PINNED under strip scaling (user
  report: expanding moved it up): outside a selected COMPLEX
  control nothing lives below the line, so the tall view keeps it
  at the collapsed height and the value scale grows UP from it
  (zero stays ON the line); complex editing keeps the historic 72%
  split for below-line room (negative accel, long-G).

## D182. v18.11: multi-view feedback + round icons + strip-arrow priority (user, 2026-07-28)

- NEW PANES arrive in FLY/ORBIT at the SPAWNING pane's perspective
  pose (Add View passes `this`; an ortho spawner hands its saved
  perspective pose). (His "EXCEPT when the original view…" clause
  arrived truncated — revisit when he finishes the sentence.)
- POV DIRECTION IS PER-PANE (his report: A flipped EVERY POV pane
  through the shared AppSettings.povReversed): Viewport3D.povReversed
  is an instance field now, A/D flip only keyViewport(), each pane's
  backwards tag tracks its own state. The settings key is written no
  more and ignored on read (the D84 stale-key precedent).
- View menu renames: "Show Node Markers", "Show Heartline".
- ORTHO ZOOM: twice the v18.8 rate (pow base 1.0015 → 1.003), and
  zooming IN anchors the point under the CURSOR — the pivot slides
  by (screen offset)·(upp_before − upp_after) along the view plane's
  right/down axes (post-clamp honest); zooming OUT stays
  center-anchored (his spec).
- GRID OVERLAY spacing = POWERS OF TEN with 1 m squares as the
  floor (was 1-2-5): minors step ×10 whenever they'd crowd under
  ~7 px, majors every 10th line.
- The XYZ COMPASS STAYS PUT (the v18.8 dodge is gone): bottom-left
  readouts simply draw over it (z-order — labels added after the
  canvas).
- ROUND CIRCLE-ARROW ICONS (his screenshots: every font ↺/↻ glyph
  renders with a FLAT-BOTTOMED circle): FvdDesigner.circleArrowIcon
  draws a fully-round arc with only the arrowhead gap (top), CW
  mirrored from the CCW drawing, centered. Used by Dialogs.
  optionReset, GraphEditor's resetBtn + the seam-settings ↺, the
  tangent ↻ (light variant for the dark overlay), and the s/c
  chip's rate marker (strokeMiniCircleArrow paints it into the
  glyph's slot in the centered canvas text).
- VIEW-DIRECTION DIGITS (his follow-up in the same round): 1-6 =
  Front / Back / Right / Left / Top / Tangent to Selection, FIXED
  keys REPLACING T/Shift+T (the rebindable viewTop/viewSide actions
  are gone; stale bindings ignored, D84). Context rules per his
  spec: 1-5 fire only while a pane is CAPTURED or the mouse is over
  a VIEW (mouseOverView, tracked by pane enter/move/exit) — over
  the GRAPH, 1/2/3 stay lane switches (mouseOverGraph); 6 fires
  from the graph too, aimed at the last-hovered pane. All exit
  POV/fly first (exitRideModes; captures release).
- STRIP EXPAND/COLLAPSE ARROW OUTRANKS the control line (his
  report: rapid clicks created a speed control — the arrow MOVES on
  toggle, so the second click fell past it): spArrowHit is now the
  whole left-gutter column of the strip (stable across states), and
  gutter presses inside the strip are consumed before the line/
  segment/diamond paths (xToTPlain happily mapped x left of the
  plot into a diamond-add).

## D183. v18.12: gizmo center-ball round + icon/strip polish (user, 2026-07-28)

- (His truncated bullets resolved: the "I've noticed that all of
  the…" fragment was the icon item's false start — disregarded.)
- GIZMO SCALE STALE after fly→side (his report): mode transitions
  only repositioned; orthoEnter/exitOrthoLens/setOrthoSideMode now
  run refreshGizmo(false) — the sig also carries orthoPlaneAxis so
  arrow sets rebuild per view.
- "Backwards POV (A)": the key hint shows only while CAPTURED
  (syncPovLabel reads povCaptured; A does nothing hands-off).
- CENTER-BALL round (user spec): in a FIXED ortho view with GLOBAL
  axes the OUT-OF-PLANE arrow doesn't build (useless there; fully
  reveals the ball) — Front/Back hide Z, Right/Left hide X, Top
  hides Y, tangent keeps all three. The WHITE BALL: dragging moves
  its owner in the VIEW PLANE (any view; POV has no gizmos), the
  screen right/down axes mapped to world at the anchor's depth,
  absolute from the press with a 3 px deadband so clicks stay
  clicks; the ENTRY ball moves the piece (unless a bridge owns the
  placement), the BRIDGE-END ball moves the next piece in manual
  mode only, the seam ball stays position-locked. DOUBLE-CLICK on
  any ball opens its owner's settings (Track Piece panel / bridge
  properties / the seam pop-out — which panel the placement popup
  shows still follows the active section, the shared-popup
  contract). The ball SCALES with the rig (apparent size; BALL_KEEP
  8.5 keeps the historic size at the default view; gizmos-off =
  historic fixed size). CTRL SNAP is now semantic everywhere:
  GLOBAL axes = the absolute world grid (arrows snap the coordinate
  along their axis, the ball snaps all three coords), LOCAL = step
  increments from the press (the historic feel).
- ICONS: stroke thinned (0.10→0.075 of size, smaller heads — "a
  little more like the old style"); the SHORTCUTS dialog's binding
  reset was the missed glyph site (a `// circle-and-arrow` comment
  made the sweep's comment-filter skip the line — the lesson:
  filters that exclude comments also exclude CODE lines carrying
  one); the tangent Reverse View button wears a matching drawn
  double-arrow (doubleArrowIcon) instead of the ↔ glyph.
- COLOR SWATCH fill overflow (his report): modena's default button
  background carries a layer with -1 px BOTTOM inset (the subtle
  drop edge) — overriding -fx-background-color alone kept that
  geometry, so the fill poked below the border. -fx-background-
  insets: 0 aligns fill and outline exactly.
- STRIP ARROW, second pass (his re-report — the v18.11 guard sat in
  spPress but the DOUBLE-click promote routes through spDoubleClick
  → spPromoteOrOptions): the same arrow/gutter guard now leads
  spDoubleClick, so rapid arrow clicks can never promote a control.

## D184. v18.13: LATERAL SIGN FLIP (+Gl = body-RIGHT) + strip/gizmo round (user, 2026-07-28)

- LATERAL G SIGN, EVERYWHERE (user spec: "rightward laterals are
  positive"): the convention is the BODY-FORCE sense — a flat RIGHT
  turn pushes the body LEFT, so it reads NEGATIVE now; +Gl authors/
  reads a LEFT-visual turn (body pushed right). Implemented as a
  paired flip of every felt↔rate site — the historic internal
  relations gain a minus and mirrored gravity term:
  consume ω = g(Gl − R.y)/v → −g(Gl + R.y)/v (FVDIntegrator time +
  dist); record Gl = ω·v/g + R.y → −(ω·v/g + R.y) (Geo ×4, Sc,
  Bridge analytic felt, s/c compiler felt, convert extraction);
  κ-rescale Gl_t = r²(Gl_d − R.y) + R.y → r²(Gl_d + R.y) − R.y
  (passA trueFelt, influence base, editor guides, custom-speed
  hold); tilt re-projection mirrors its R.y pair; the matcher
  entries flip their yaw reconstruction and the force-view exits
  negate val+slope for lane 1. Roll/yaw/pitch rates, geometry, and
  reversal ("Gl negates") are untouched. The lateral COMB draws
  along +R now — with the negated values this keeps every tooth on
  the SAME visual side as before (his note: he'd flipped the comb
  earlier to compensate; the source fix makes that compensation
  cancel out — the comb code changed, the picture didn't).
  OLD FILES MIRROR their force-section laterals (and ΔGl influence)
  — accepted by the user explicitly; geo and s/c geometry are
  unaffected (they author rates/curvature, not felt). FOUR gate
  fixtures re-baselined (they encoded the old direction pairing):
  Phase2 chirality (+yaw now turns OPPOSITE to +Gl), s/c flat R20
  right turn = −v²/Rg, influence (e) sign, DomainGate 40b circle
  center side. All suites green — every invariance-style check
  (converts, influence exactness, friction, seams) held through
  the flip untouched, which is the consistency proof.
- STRIP DOUBLE-CLICK CREATES (user spec: "same as right-clicking"):
  a fast second click on the just-added mark (< 600 ms,
  spMarkAddedAt) REMOVES it and creates a control spanning its
  former neighbors via spCreateControlAt (no second undo push — the
  add's snapshot covers the pair); an old diamond keeps its historic
  promote. spCreateControlAt gained the push flag.
- GIZMO: double-click ANYWHERE on a rig (arrows/rings too, via the
  drag-release hooks; a pushed drag never counts) opens ITS OWN
  settings — Rig.onDouble: entry rig/ball = the Track Piece rows,
  bridge rig/ball = the bridge rows, seam rig = the seam pop-out —
  showPlacementPopup(Boolean forceBridge) overrides the
  active-section branch (his nuance report). FIXED ortho views keep
  only the IN-PLANE ring (axis ∥ view direction, |dot| > 0.9;
  built-time test, the sig rebuilds per view/pose). The center ball
  idles WHITE (hover warms it) and REPLACES the node-0 marker
  wherever it covers it (entry of the active piece, section 0 —
  they z-fought; the marker sig carries the state). Ctrl+arrow
  drags in GLOBAL land the whole position ON the world grid (all
  three coordinates; LOCAL keeps increments).

## D185 (v18.14) — YAW SIGN FLIP: +yaw = a LEFT turn everywhere

The same-day companion to D184 ("reverse the sign of Yaw everywhere,
just like for laterals... give this a major deep dive, and feel free
to double check your work on flipping the lateral g sign"): a
positive yaw RATE now authors/reads a LEFT turn, restoring the
chirality pairing with +Gl = body-pushed-right (both "positive"
senses describe the same left-visual turn again — D184 alone had
made them opposites).

Implemented as a REPORTED-vs-INTERNAL boundary contract, not a math
rewrite: the internal Ω algebra (Ω = yaw·U − pitch·R + roll·T, the
quaternion step, RMF, every integrator's core) is UNTOUCHED; the
negation lives at every point where an AUTHORED lane enters Ω or a
measured rate is REPORTED back out:
- GeoIntegrator.omegaFor: all FOUR local-base builds negate the yaw
  slot (fast path, rateScale path, the ypGlobal heading base, and
  the else-of-ypGlobal base used by global-roll + local-yaw — the
  one gate 51a caught missing); the two lane-based felt-record
  shortcuts re-derive as lane·v/g − R.y (they had equated the lane
  with internal ω).
- FVDIntegrator.bodyRates reports out[i][2] = −dT·R (pitch and roll
  unchanged).
- Coaster.worldRates speaks REPORTED on both faces: yaw input
  negates into Ω, heading' ψ' negates on return — while every
  TRANSPORT term (φ' = p − ψ'·sinθ) keeps the INTERNAL ψ'
  (= −reported). Callers all pass reported-sense arrays already.
- Convert extraction: the yaw curvature κR = +G0·(Gl + R.y)/v²
  (the D184 and D185 negations CANCEL — the expression returns to
  its pre-D184 form, now meaning "reported left-positive") and the
  force-lane decode val[1] = ω·v/g − R.y; influence base yawB and
  the editor's absRollTarget/rollConverted/crossModeFromLanes/
  forceTargetFromRates/omegaAt all rewritten to the reported
  contract (transport sites use −ψ'_reported).
- Seam spike carets: component 1 negates — an audit find OWED BY
  D184 (the felt-Gl caret pointed against the flipped lane), now
  correct for both views.

WHAT DID NOT CHANGE (deliberate): yaw ANGLES — piece yaw placement,
strict-seam held angles, gizmo rings, the compass — keep their
world-Y sense (an angle is a pose, not a rate; flipping those would
re-place every saved piece). The GEOMETRIC comb is curvature-based
(frames, not lanes) and untouched. Roll and pitch rates untouched.

OLD FILES with geo-yaw content CHANGE SHAPE — and unlike D184's
lateral mirror, yaw+roll coupling means it is NOT a pure mirror
when roll is present (a mirrored yaw under unmirrored roll is a
different curve; the 48g stall analysis below is the concrete
case). User pre-accepted file breakage in D184's round.

Gate re-baselines (direction-encoding fixtures only — every
invariance gate passed unchanged, the consistency proof):
Phase2 chirality back to SAME-sign (+yaw and +Gl both LEFT);
influence (e) expGl positive; Phase3 twoSecPiece authors yaw −12
(the SAME physical shape as the historic +12 — with +12 kept, the
flipped shape genuinely STALLED at entry 15: worstSplit 0.026 in
the creep zone, span inflated to 20282 samples — evidence of the
non-mirror point above, found via Rev48gProbe); DomainGate 41c
spiral authors −15 (headTurn stays +120). All three suites green.

## D186 (v18.15) — yaw ANGLES read left-positive; ball proportion; world-aligned overlay grid

Round 8, three items.

- YAW ANGLE SIGN ("change the sign of the yaw angles everywhere,
  just like the yaw rates... consistent with each other without
  changing any behavior"): every user-facing yaw-angle NUMBER now
  reads/types LEFT-positive, via the same reported-vs-internal
  boundary as D185 — the UI negates, storage keeps the historic
  internal sense. Exactly three surfaces exist: the Track Piece
  panel's "Piece yaw (°)" spinner (init/apply/sync), the bridge
  panel's "Next piece yaw (°)" spinner (setIfOff read + listener
  write), and the strict-seam pop-out's yaw row (the sync display —
  held angle AND the live continuous followerEuler readout — plus
  the value-listener write; the Discontinuous-toggle seed, ↺ reset
  and Cancel restore all traffic internal-to-internal and re-display
  through the sync). NOTHING ELSE changes: TrackPiece.yawDeg/
  sYawDeg, the file format, gizmo ring drags (never displayed,
  behavior identical), undo snapshots, eulerOf/followerEuler
  internals, the optimizer march, ortho yaw snapping. Zero pose
  change anywhere — old files load bit-identically; typing +30 now
  aims the piece LEFT, and a value shown before the flip shows
  negated after (the display convention, not a move). DELIBERATE
  EXCLUSION: the s/c arc element's signed angle keeps its own
  documented "+angle = rider-right with direction arrow" spec —
  it is a turn description, not a placement yaw; flag if he wants
  it too.
- CENTER BALL SCALING (his two screenshots): BALL_KEEP 8.5 made
  the ball's world size ≈ constant (historic marker size), so it
  vanished against the depth-scaled gizmo far away; only CLOSE UP,
  where the bsc 0.4 and rig 0.5 floors both bit, did the ratio
  look right — which is precisely the screenshot he called
  correct. BALL_PROP 0.8 (= that floored ratio 0.4/0.5) now
  multiplies the rig scale at every depth and in ortho, so the
  ball keeps the endorsed proportion everywhere; gizmos-off keeps
  the historic fixed size.
- OVERLAY GRID WORLD ALIGNMENT ("aligned vertically, but not
  horizontally... it does align with the grid of the ground"): the
  world group carries an XZ centering translate (scene +
  gridCamX0/Z0 = world) and drawOrthoGrid measured its families
  from SCENE zero — X/Z lines sat off the ground grid by the
  translate while height lines were exact (Y never translates),
  and Ctrl-snap (which rounds WORLD coords, agreeing with the
  ground grid) missed the overlay. Each family's origin scalar now
  sits on the WORLD origin (top X/Z families + the fixed side
  views' horizontal family; the TANGENT side keeps its anchored-
  node origin — a scene point, deliberately not the world grid);
  gridCamX0/Z0 join the redraw signature. Overlay lines, ground
  grid and global snapping now agree in every fixed ortho view.

## D187 (v18.16) — ball-click gizmo reveal; flat view list; s/c +angle = left

Round 9, three items.

- GIZMO REVEAL RETURNS (user spec — reversing v17.7 D144's
  always-on): rigs are HIDDEN until the WHITE BALL is clicked
  (entry/seam ball = the piece/seam rig, bridge-end ball = the
  bridge rig; clicking the same ball again, clicking the other
  ball, any primary press off the gizmo, or switching pieces hides/
  switches — Viewport3D.gizmoReveal, per pane, in the rebuild sig).
  The View menu's "Show Gizmos" row + inline axis dots are GONE
  (AppSettings.showGizmos deleted; stale properties keys ignored,
  the D84 precedent); the TOP-RIGHT Local/Global button is BACK
  (per pane, v18.10 D181 reversed) but only while a rig is revealed
  — and gizmoLocal is SESSION-ONLY now: every launch starts GLOBAL
  (the field never touches the properties file). Gizmos draw
  THROUGH everything: gizmoGroup gets DepthTest.DISABLE + a
  toFront() per rebuild (painter's order wins; picking is
  unaffected — a buried ball renders on top but the nearer surface
  still takes the click, an accepted JavaFX quirk). Balls are
  always bright + proportionally scaled now (the gizmos-off muted/
  fixed-size branch died with the setting). Ball planar drag,
  node-0 select and double-click settings are unchanged.
- ONE FLAT VIEW LIST (user spec): the "Side" entry + sub-dropdown
  are GONE — the view label's list is Fly/Orbit, POV, Top, Front,
  Back, Right, Left, Tangent to Selection (switchViewMode 0-7 →
  setOrthoSideMode for 3-7; the label wears the short "Tangent";
  the ↻/↔ tangent tools move up under the label). The 1-6 digits
  are unchanged. Also fixed in passing: the Help "View label" row
  still said T/Shift+T (stale since v18.11's digits).
- S/C ARC ANGLE +LEFT (his answer to D186's flag): the profile
  negates the U component — cU = −κ·cos(dir) — so dir 0 + positive
  angle turns rider-LEFT (the D185 yaw boundary applied to s/c);
  dir 90 pull-up is untouched (the R component keeps its sign) and
  negative angles mirror as before. The chip's turn arrow table
  mirrors horizontally (vertical octants unchanged); dialog/Help/
  doc texts updated. OLD FILES with flat/tilted arc content MIRROR
  those arcs (the D184 acceptance); vertical loops are unaffected.
  Phase2's five s/c direction fixtures re-baselined by authoring
  the mirrored angle (same physical shapes — right turns author
  −90 now); the convert/reversal/smoothing invariance gates and
  the other two suites passed untouched.

LESSON RE-LEARNED this round: "BUILD-OK" via `ls` of a class file
is worthless — a failed mvn compile leaves STALE classes behind
(the smoke run threw "Unresolved compilation problem"). Wipe
target/classes/coaster and check the compiler's own output.

## D188 (v18.17) — gizmo OVERLAY SUBSCENE; ball split; lock-live rings; marker index fix

Round 10 — his test of the D187 reveal rework, four items.

- OVERLAY SUBSCENE (his screenshot: "normals inverted or
  something?", rings drawn fully over arrows): D187's
  DepthTest.DISABLE renders in SUBMISSION order — a torus's far
  side paints over its near side (the inverted-normal look) and
  later-added parts paint over earlier ones regardless of depth.
  The rigs now render in their OWN transparent SubScene
  (gizmoSub) stacked right above the main one: real depth buffer =
  proper self-occlusion ("exactly like before"), still drawn over
  the whole world. gizmoWorld mirrors the world group's XZ
  centering translate; gizmoPCam/gizmoOCam mirror the active
  camera's full localToScene transform + lens every frame
  (syncGizmoCam on the axes timer); pickOnBounds(false) + a
  mouseTransparent-when-empty guard pass events through wherever
  no gizmo geometry is hit. All drag math is screen-space +
  camera-basis (no PickResult dependence), so every handler works
  unchanged; ringHit builds its own ray. The 2D overlays (grid
  canvas, labels) still draw above the gizmos, as they always did.
- BALL SPLIT (his clarification of "originally not scaling"): the
  un-revealed white ball is FIXED SIZE again (0.34, part of the
  track layer — trackBallGroup in `world`, occludable like
  geometry, positionGizmo translates only); clicking it reveals,
  and a DRAG both moves the owner AND reveals mid-gesture
  (revealDuringDrag: a rig-only build — rebuilding the pressed
  ball would kill the mouse capture, the D124 lesson). The
  SCALING white ball is now a rig PART (addRigCenterBall, radius
  0.272 = 0.34 × the D186 0.8 ratio, riding rig.scaleT): proper
  depth among arrows/rings (it used to hide behind them), click =
  hide, planar drag = move owner, double-click = settings. One
  wireBall helper wires both.
- LOCK-LIVE RINGS: the bridge-panel lock buttons and the
  Placement pitch/roll padlocks now call refreshGizmos(false) —
  the sig carries the locks, so rings appear/disappear the moment
  an axis is un/locked (before: only on the next unrelated
  rebuild).
- MARKER INDEX FIX (his exact guess — "the first node was removed
  but the indices weren't updated"): D187's entry ball replaces
  node 0's marker, so markerGroup's child list starts at node 1 —
  but pickMarkerIndex returned the CHILD index as the NODE index,
  selecting the node BEFORE the one clicked. Each marker sphere
  now carries its node index (setUserData), and the pick reads it.

## D189 (v18.18) — fly-over-gizmo input fix; ball hides when revealed; NL2 local rings; button label

Round 11 — his D188 test, four items.

- FLY-OVER-GIZMO BUG (root cause found): in fly mode the cursor is
  hidden and Robot-warped to the subscene center — but the D188
  overlay SubScene sits ON TOP of `sub`, so whenever the warped
  cursor crossed gizmo geometry, hover went to the OVERLAY: its
  default cursor showed (the "mouse reappears") and `sub`'s
  mouse-moved handler stopped receiving those events, starving the
  fly look of deltas (the "bit shaky" view — deltas lost each time
  the center crossed a rig part). syncGizmoSubMouse() now makes
  the overlay fully mouse-transparent during FLY and POV (and when
  empty — the D188 guard, now centralized); refreshGizmo calls it
  at the top, and both mode toggles already route through
  refreshGizmo. Gizmos stay VISIBLE in fly (the v16.12 rule) —
  they are simply not hover/click targets there, matching the
  pre-D188 practical reality (the mouse is warped and hidden).
- BALL HIDES WHEN REVEALED: the fixed track ball sets
  visible(false) at build time while ITS rig is up (the rig's
  scaling center ball is the visible white); the OTHER handle ball
  stays. Set at build time only — reveal-by-drag keeps the pressed
  ball alive until the release rebuild (invisible nodes get no
  events, which would have killed the drag).
- NL2-STYLE LOCAL RINGS ("the entire gizmo rotates with the
  object"): with Gizmos: Local, ring AXES ride the entry frame —
  yaw about U, pitch about R (= U×T, new rightOf helper; Quat has
  no R() accessor), roll already about T — for the piece rig, the
  bridge rig (next piece's frame) and the seam rig (follow-contract
  frame). The aim suppliers re-run per drag event, so the whole
  rig rotates RIGIDLY with the object mid-drag, the NL2 look.
  Global keeps the historic model-Euler axes. Each ring still
  WRITES its one Euler quantity by linear mouse distance (the
  delta model, D87) — under a strong compound tilt the object
  follows its Euler axis rather than the drawn plane; visually
  rigid, semantics unchanged, locks unchanged.
- The top-right button reads "Gizmos: Local" / "Gizmos: Global".

## D190 (v18.19) — TRUE local ring rotation; A-key viewport gate; viewport deselect; popups at the ball

Round 12 — four items.

- TRUE ROTATE-ABOUT-THE-DRAWN-AXIS (his request, upgrading D189's
  aim-only local rings): a LOCAL ring drag now rotates the OBJECT
  about the drawn axis exactly. Machinery:
  TrackPiece.decomposeEntry(base, target) inverts entryPose()'s
  orientation composition in closed form — the composition
  preserves heading through its pitch/roll steps and elevation
  through roll, so yaw extracts from the heading gap, pitch from
  the elevation gap, roll as the U-vs-U twist about the shared T;
  null where a heading is undefined (near-vertical T). The ring
  plumbing gains a RingLocal sink (begin captures the press-time
  frame + drawn axis; rotate(deltaDeg) composes
  aboutAxis(axis, delta)·q0 and writes the decomposed deltas —
  the SAME mouse mapping as the classic path, so the aligned case
  is identical). Piece rig writes unlocked deltas directly;
  bridge rig through the new combined
  GraphEditor.gizmoSetBridgeOrientAll (locks + the OPT_POS
  re-place tail in one apply); seam rig through
  gizmoSetSeamOrientAll (eulerOf decomposition — the held angles
  ARE euler quantities; discontinuous axes take values, following
  axes follow). LOCKED axes hold their value — the object then
  follows the ring as closely as the model allows (exact when all
  drawn-axis components are writable). Absolute-from-press: the
  pitch clamp (±85) can bite mid-drag without accumulating error.
  VERIFIED headlessly (DecompProbe, 20k random trials): in-range
  delta inversion exact to 1.3e-12°, frame reproduction ≤ 1.1e-5°
  worst-case at near-vertical poles (the ±85 clamp keeps real
  drags away), compose→decompose→recompose ring loop the same.
  Global mode keeps the classic one-Euler-per-ring writes bitwise.
- A KEY over any 3D view no longer appends (the graph keeps its
  shortcut; gated on mouseOverView at the dispatch).
- VIEWPORT DESELECT (graph parity): a clean primary click that
  does not hit a node marker clears the selection
  (GraphEditor.deselectFromViewport) — then the track-activate
  pick proceeds as before.
- POPUPS AT THE BALL: a double-click on any ball or rig part
  passes its screen point (FvdDesigner.nextPopupAnchor, one-shot)
  and the Track Piece / bridge / seam panels open there (+14,+10
  off the cursor) instead of under the toolbar button; the
  toolbar button and the seam line keep their historic anchors.

## D191 (v18.20) — gesture-end graph redraw; typable spinners everywhere; widths; label

Round 13 — four items.

- GRAPHS FOLLOW GIZMO GESTURES ("update as soon as the click is
  lifted"): rebuildTrack deliberately never redraws the graph
  canvas (the hot path), and the gizmo drags relied on the next
  graph hover to repaint — piece moves left stale resulting/true
  curves (≠v badges included). endGizmoDrag now calls the new
  GraphEditor.redrawFromViewport (refreshOffset + redraw), so
  every ball/arrow/ring gesture repaints the strips on release.
- TYPE-ANYWHERE SPINNERS: ten spinners were arrow-only (heart,
  gauge, tie, piece x/y/z, piece yaw, start speed, both friction
  rows) — all editable now via the new typable() helper. Bigger:
  numericEditor (the v18.1 one-choke formatter) now COMMITS TYPED
  TEXT ON FOCUS LOSS — JavaFX only commits on ENTER, so typing a
  number and clicking away silently reverted on EVERY editable
  spinner in the app (unparsable text falls back to the current
  value). The remaining direct-formatter sites (piece pitch/roll,
  bridge yaw/pitch/roll, bridge x/y/z, bridge length, seam
  spinner) all route through typable/numericEditor now, so the
  commit covers the whole program.
- CLIPPED DIGITS (his "3" screenshot): the eight 70 px spinners
  (Track Piece x/y/z + yaw, heart/gauge/tie, start speed) widen
  to 82 px; friction rolls to 82/90. No other spinner sits under
  75 px.
- The Track Piece popup's reverse button reads "Reverse piece…"
  (the "(rebuild backward)" parenthetical is gone).

## D192 (v18.21) — blank-spinner zero commit; the stairstep investigation

Round 14, two items.

- BLANK SPINNER = ZERO (user spec: "delete everything and click
  off, it goes to zero"): commitTyped replaces the bare
  commitValue in numericEditor's focus-loss hook — a CLEARED
  editor commits 0 clamped into the factory's range (gauge lands
  on its 0.3 min, bridge length on 1) and explicitly re-renders
  the text (a no-change setValue fires no listener, which is why
  the box stayed empty before). ENTER on a blank editor was the
  same hole via Spinner's INTERNAL commit — DoubleStringConverter
  returns NULL for empty text, so the value property literally
  went null; an event filter routes it through commitTyped.
  Custom factories (the snap-step walker) restore the current
  value instead of forcing 0.
- LOW-SPEED STAIRSTEP report ("occurring again"): NOT REPRODUCED
  in the model or the display pipeline. SpeedStairProbe
  (scratchpad) drives a stalling geo climb — with and without
  friction + a low brake control — and every array is smooth to
  machine precision into the stall (0.53 → 0.46 → ... → 0.04 →
  honest 0.0 through the creep): all five D175 record sites still
  store raw speeds, Pass D's trueV marches raw, the strip's green
  curve strokes trueV per sample, gAxis/spVToY are continuous,
  and the only remaining max(0.1) sites are the D175-sanctioned
  consumer floors (reconcile seeding, ratio divisions). Candidate
  explanations pending his file: an honest FLAT-ZERO stall zone
  reading as a "stair" (possibly new stalls, since the D184/D185/
  D187 sign flips change old files' shapes non-mirror-wise), or a
  surface not yet named (POV ride feel vs the strip curve).
  Awaiting the file / a pointer to which surface shows it.

## D193 (v18.22) — the POV stall-approach stair: interpolate the ride values

Round 15 — his file (Speed Stairstepping 7.29.26.fvd) closed the
D192 investigation. The arrays were never wrong: the file's piece
carries an 11-degree PITCH TILT, so the design profile (rigid-tilt
rule, D100: sections solve at genPose, the tilt is placement) rides
~28.6 m/s while the TRUE march climbs the tilted track and stalls —
an honest, documented ≠v split. The consequence nobody had hit yet:
the design profile spaces the samples (~0.286 m apart here), and
near the true stall the TRUE speed loses 0.3-0.6 m/s PER SAMPLE
(his exact readouts 1.992 → 1.666 → 1.261 → 0.637 → 0.000 are
consecutive samples, machine-verified by StairFileProbe). Every
nearest-sample consumer of the ride position therefore STEPPED
visibly — the bottom stats line AND the ride-advance ratio (the
"feel"). His diagnosis was exactly right: the speed was locked to
the design sample spacing — distance increments.

Fix = the D167 treatment (the camera pose already interpolates at
the exact povTime) applied to the VALUES: FvdDesigner.povBracket
finds the bracketing sample pair by the samples' OWN t fields (the
s/c sub-sample phase rule) clamped into every parallel array;
trueVAt/designVAt interpolate linearly, and updatePovStats renders
interpolated position, speed and felt Gs. The povTimer's advance
ratio reads trueVAt/designVAt at the exact povTime (the D176 0.1
crawl floors unchanged). Physics, records and graphs untouched —
this is purely the POV readout/feel layer, per his "the most
important thing is for it to not change other things".

## D194 (v18.23) — the stall wrap fires the instant the speed reads zero

Round 16, one item ("go back to the start / to the next piece a
little earlier — preferably the instant it hits 0.000 m/s"):
povClampStall's forward gate was idx > r[0] — the ride had to
crawl a FULL design-sample past the first stalled sample before
wrapping, and at the floored advance ratio on a drifted section
(0.1/28.6 in his file) that one 0.01 s design-gap costs ~3 wall
seconds of frozen POV. Forward travel now wraps ON the first
stalled sample AND at the interpolated zero point in the gap just
before it (trueVAt ≤ 5e-4 — the readout shows 0.000 at that
instant); POV entry's forward clamp inherits the same rule, so
parking exactly on a stall start no longer shows a frozen camera.
Backward travel keeps the historic gate (park at the range start,
ride out freely). The honest decel INTO the stall is untouched —
only the dead crawl past zero is gone.

## D195 — PHASE 3 COMPLETE (stall/rollback REJECTED); the Phase 4 roadmap

User decisions, 2026-07-29, closing the phase:

- REAL STALL/ROLLBACK PHYSICS: REJECTED — "I actually don't want
  real stall/rollback physics." The 0.1 m/s creep floor, the
  transparent ghost track (D163) and the instant POV wrap (D194)
  are the FINAL stall model. With friction (D133) and speed
  controls (D160) long done, PHASE 3 IS COMPLETE.
- PHASE 4 adopted, his items and order: (1) the STRAIGHT/CURVE
  OVERHAUL — advanced turns (planar vs 3D, editable end slopes/
  radii), full roll customization, end-to-start closure, strong
  viewport interactivity with neighbor re-solve; (2) TRAIN
  APPROXIMATION GRAPHS — back/front + left/right-seat felt off
  the POV-as-train-middle, no simulation, Resulting-dropdown
  surface; (3) IMPORTS & CONTENT — incl. NL2 CSV import (kept
  from the old roadmap on his confirmation), ground images, .glb
  models, NL2 terrain, markers, NL2-image skybox, track
  customization; (4) 3D NODAL TRACK INFLUENCE, post-first-release
  by his own ranking. Viewport-editing-part-2 from the old
  roadmap is subsumed by (1)+(4); the Euler display toggle drops
  to the small-items list. A running laundry list of minor
  updates interleaves throughout, session-feedback style.

The next arc: (1), straight/curve sections in the viewport.


## D196 (v18.24) — 1-node lanes must LOAD (the split-hold save refusal)

His 2026-07-31 report: the full test coaster built the previous
day refused to reopen — "Load failed: each lane needs at least
2 nodes".

Root cause — a save/load ASYMMETRY, latent since v14.0. The
loader has refused lanes under 2 nodes since its birth (v9.1,
when nothing could produce fewer), but Coaster.splitLanes' hold
branch (partial convert v14.0; the v16.18 node-anchored section
split reuses it) deliberately emits a SINGLE held-value node for
the right half whenever the cut lands past a lane's last node —
its own javadoc says so. The runtime embraces 1-node lanes
(bakeLane special-cases size 1 as the constant, exactAt holds
ends, both Delete paths guard at 2 so hand-editing can't go
below), so the editor ran his file all day and saved it
happily; only reopening failed. His file: a geo section's yaw
lane held 0 past two successive splits — sections 3 and 4 each
carried `lane 1` with one `n 0 0` line.

Fix — LOADER-side only: refuse only an EMPTY lane block ("each
lane needs at least 1 node"); a single node loads as the held
constant it already is in memory. splitLanes is UNTOUCHED — the
1-node hold half is the model's honest representation, not a
bug (delta lanes already live as 1-node by design, D98). No
heal/rewrite either: the lane round-trips as 1 node, and his
file re-saves byte-identical to its on-disk bytes.

Gate (LoadGate196, session scratchpad): his real file loads /
compiles (7 sections, 201.94 m) / round-trips save→load→save
byte-identically; a scratch split past a lane end emits the
1-node half, saves, and LOADS (refused pre-fix); an empty lane
block still throws.


## D197 (v18.25) — DETERMINISM: the reconcile cascade + save precision

His 2026-07-31 report, the shakedown's second find, flagged
"critical": the reopened full coaster didn't match the saved
shape; clicking a section (editing NOTHING) changed it again;
moving the piece XYZ/yaw "dramatically" reshaped the track. His
requirement, now explicit: unchanged graphs = identical shape,
at any length.

Diagnosis (ReconProbe/ReconProbe2 on his 1150 m file): ONE
compile is bit-deterministic, and placement is honest — X/Z
translation rigid to 6e-13 m; Y-translation and yaw invariant to
float-roundoff growth (µm over a km) plus isolated mm-level
blips where a speed-control landing's sub-step branch flips; NO
frame bug anywhere. The churn was the DOCUMENT WRITE-BACK:
reconcileChainedSpeeds re-freezes every continuation vDesign
from the spans of a compile taken BEFORE that same pass's
earlier writes, so a speed change CASCADES one section per
compile — his 17-section chain needed 9 compile+reconcile
rounds — while rebuildTrack capped the loop at 3. Every rebuild
(open, click, spinner tick) advanced the unfinished cascade and
moved the track 60-140 m; the file had been SAVED mid-cascade,
so his remembered shape was a transient. On top, the save wrote
graph numbers at %.4f: 5e-5 quantization amplified through
1150 m of chained integration into a 0.5 m reload shift even at
the fixed point.

Fixes:
- CASCADE CARRY in reconcileChainedSpeeds: when this pass just
  rewrote section k−1 (vOld→vNew), section k's entry speed reads
  the frozen-energy prediction sqrt(vNew² + vEndStale² − vOld²)
  instead of the stale end sample — one pass walks the whole
  chain; saturating speed controls and speed-dependent shapes
  mispredict second-order and the verify compile corrects them.
  An untouched predecessor keeps the stale read BITWISE (a
  converged doc's pass writes nothing — gated).
- rebuildTrack iterates to QUIESCENCE, cap 12 (was a hard 3):
  the displayed track is always the fixed point, so a click can
  never "edit" the document again.
- DesignIO writes GEOMETRY-FEEDING numbers at %.10f (v0,
  friction, piece placement incl. held seam angles, section
  design speeds, lane/control/delta nodes, sctl/smark, elem,
  blen; cosmetics stay %.4f). Old files load unchanged; the
  byte-identical round-trip gate still holds.

Gates: DetermGate197 (6 checks — quiesces in 6 passes; a SECOND
rebuild is a bitwise no-op; save→load→rebuild reproduces the
shape to 6.7e-7 m with ZERO reload passes, was 0.5 m; X/Z
translate rigid 7e-13 m; converged-doc reconcile silent;
independent runs bitwise-equal) + Phase2Gate/Phase3Gate/
DomainGate/LoadGate196 all green.

Accepted corners: HIS file settles ONCE on first open under the
fix — the honest converged shape (end ~44 m off the remembered
mid-cascade transient; the graphs are untouched) — and never
moves again. Placement invariance is float-exact only: µm-level
roundoff divergence under Y/yaw moves (with occasional mm-level
control-crossing blips) is arithmetic, not state — bitwise
placement invariance does not exist in floating point.


## D198 (v18.26) — BIT-EXACT saves + the load-clamp audit (the knot round)

His 2026-07-31 follow-up with before/after screenshots: a reload
matched the session EXACTLY up to the ghost-track stall knot,
then diverged; Y-translation and piece-yaw moves still reshaped
dramatically while X/Z and pitch/roll tilts stayed clean; and a
STANDING DIRECTIVE: every future update must preserve absolute
determinism — save/reopen may change NOTHING.

Diagnosis (DiagProbe198 on his file):
- PRIMARY: the loader CLAMPED section design speeds to [1, 100]
  while the reconcile legitimately writes its 0.1 stall floor —
  his knot section saved 0.1 and LOADED as 1.0, a silent 10×
  curvature change at the one section generating at the floor
  (curvature ~ 1/v²). Same bug class as D196: the saver writes
  states the loader refuses/rewrites.
- SECONDARY: %.10f's 5e-11 dust flips KNIFE EDGES — the section
  before the knot grazes a design-profile stall, and which side
  it lands on decides the whole downstream shape. No finite
  fixed precision reproduces a chaotic regime.
- PLACEMENT: verified DETERMINISTIC and X/Z rigid to 1.5e-12 m,
  pitch/roll tilts rigid by construction (post-rotation). Y/yaw
  moves perturb the integration ARITHMETIC (energy bits / frame
  bits), the knot re-rolls (its exit pose is chaotic), piece 2
  hanging off that seam relocates wholesale (~560 m), and the
  changed speeds trigger ~9 legitimate reconcile rewrites — the
  "dramatic differences" he reported. His observations were all
  exactly right.

Fixes:
- BIT-EXACT serialization (DesignIO.d = Double.toString, the
  shortest decimal that reparses to the same bits) for every
  geometry-feeding number — nodes, speeds, placements incl.
  quats/held angles, controls, friction, elems, bridge solve
  inputs. The loaded document IS the session document: reload
  compiles BITWISE-identically, chaotic knots included. Colors
  keep %.4f; loader-side nothing changed (it always parsed full
  doubles); save→load→save stays byte-identical.
- LOAD-CLAMP RULE — a clamp must ADMIT every value the app
  itself writes: section vDesign [1,100] → [0.1, 1000] (the
  reconcile floor / controls past 100), infl operating speed
  [0.5,100] → [0.1, 1000], piece x/y/z ±500 → ±1e6 (the bridge
  optimizer marches placements past ±500 on long layouts —
  reloads teleported them). v0 [1,100] stays: symmetric with its
  spinner, the only writer.

THE DETERMINISM CONTRACT (his directive, now standing for every
future change): (a) compile(doc) is bit-deterministic; (b) a
rebuild at the fixed point is a bitwise no-op; (c) save→load→
rebuild reproduces the session bitwise. DetermGate197 +
DetermGate198 (settled doc AND a pinned live 0.1-knot both
round-trip BITWISE; byte-identical re-save) are the permanent
gates; run them for ANY change touching compile, reconcile, or
IO.

OPEN (his "still an issue", needs his verdict — the cost is a
bitwise re-baseline of every compile): CANONICAL-FRAME
GENERATION — strip heading+position from each section's entry,
integrate canonically, rigid-transform back — would make Y/yaw
placement moves bitwise-rigid even across stall knots, closing
the last placement sensitivity. Until then: X/Z and tilts are
exact; Y/yaw moves on a layout with a live stall knot re-roll
the knot (deterministically). A layout that doesn't stall shows
only µm-level placement sensitivity.

Accepted: files saved by PRE-v18.26 builds around a stall still
settle ONCE on first open (their 0.1 now survives load, but
their lanes carry the old %.10f quantization, so the knife edge
may land differently than the session that wrote them); files
saved from v18.26 on reproduce bitwise forever.


## D199 (v18.27) — CANONICAL-FRAME GENERATION: placement moves are BITWISE-rigid

His go-ahead on D198's proposal ("please implement the complete
fix"), plus the explicit re-statement that this fix must sustain
the determinism contract.

THE MODEL: rotation about world Y and translation commute with
gravity EXACTLY, so every non-bridge section now integrates in a
CANONICAL frame — position ZERO, entry HEADING stripped, only the
gravity-relevant pitch/roll kept — and the result is placed by one
rigid heading-rotation + translation (TrackCompiler.placeLine;
rotY leaves the y column untouched, so heights carry generation
bits). The chain's heading accumulates ADDITIVELY (CanonPose:
psi + the LOCAL exit's own azimuth, headingDeg = the
decomposeEntry convention) and the canonical orientation is NEVER
re-extracted from a composed world orientation — re-extraction
costs ~1e-16 rad, which a stall knot amplifies to O(100 m).
Piece entry: psi = heading(baseOrient) + yawDeg, canon from
baseOrient alone — the yaw spinner and x/y/z never touch the
generation arithmetic at all.

PASS D joined the same discipline (the second half of the fix):
compile chains a per-sample ENERGY-HEIGHT array from the LOCAL
generation bits (hy; run starts reset to 0 — energy references
the run entry as before) and the real march reads it instead of
world heights. Without this, a min-lift SNAPPED exactly onto its
target made "v < target?" a knife-edge at EVERY sample, and
world-height dust under a Y move flipped the engage pattern —
0.4 m/s of trueV at his seam 12, flipping the reconcile's
real-anchor and rewriting 9 design speeds (the "dramatic
differences with every additional change"). World-delta fallback
where the world genuinely is the reference: bridges, tilted /
follow-tilted pieces, smoothed-seam windows, post-shift stretches.

SPLITS stay bitwise (the D71 user rule) via a stored CANONICAL
PAIR: Span carries the chain's exact (psi, canon) at each
boundary; splitPiece(…, Span) copies it onto the new piece
(TrackPiece.canonPsiDeg/canonOrient — piece fields 19-23, undo
snapshots included; missing/other baseOrient writers null it and
fall back to deterministic decomposition; bakeTilt keeps it
across pure-yaw bakes, psi += yaw exact).

GATED (DetermGate199, 10 checks, on his live knot file with the
floor-speed section pinned): Y+50 → speeds BITWISE, heights
exactly +50 (1.3e-12), ZERO rewrites (was 9 rewrites + 561 m);
yaw+37 → piece-1 speeds/heights BITWISE outside the ±5 m seam
blend window, heading rotates exactly +37, zero rewrites; X/Z
1.3e-13; piece split BITWISE (+1 duplicated boundary sample —
the D71 "0.0 m" was always geometric); the pair survives
save→load→rebuild BITWISE. DetermGate196-198 + Phase3/Domain
suites green unchanged; Phase2Gate's two PASS-THROUGH contracts
re-baselined (compile == direct-at-ORIGIN + placement, still
bitwise; trueV pass-through likewise) and its splitPiece calls
take the Span.

Deliberate limits (documented, not bugs): placement rigidity ends
at a BRIDGE (it re-solves to world endpoints by design); a piece
across a strict seam with HELD absolute angles legitimately
re-aims under upstream yaw (his "okay and expected"), and the
±len/2 smoothing window re-blends toward it; TILTED pieces' trueV
keeps world-delta dust (their real speeds ARE world physics,
D100). Every compile's arithmetic changed once — old saved files
re-settle ONCE on first open under v18.27, then reproduce bitwise
forever.

REVERT SEMANTICS (his question): Ctrl+Z is bitwise always
(snapshot restore + pure compile). Hand-editing values back to
identical doubles restores the geometry bitwise; placement
move-and-revert is now trivially bitwise (the shape never varied
and the reconcile never fires). The one residue: reconciled
design speeds re-settle within the reconcile's ±1e-3 write tube
rather than exactly on their previous bits after a graph-edit
round-trip — OPEN as a polish item (snap-to-derived at
quiescence) if he wants it closed.


## D200 (v18.28) — the reconcile tube CLOSED (exact settle) + NL2 export verified

His request, closing the determinism arc's last open item: a
hand-reverted graph edit (retype the value — Ctrl+Z was always
exact) must return the track to its ORIGINAL BITS, not within
±1e-3 of them.

THE FIX — a second reconcile mode. reconcileChainedSpeeds(r,
exact): the FAST pass (live gestures) keeps the 1e-3 write tube +
the D197 cascade carry; the EXACT pass writes vDesign on BIT
difference with the carry DISABLED. Without predictions each
exact pass finalizes the next section of the TRIANGULAR chain (a
section's derived entry — design sample OR the D101 trueV
redirect — depends only on sections before it, and Pass D marches
forward), so the settle terminates in at most chain-depth passes
and the settled speeds are a PURE FUNCTION of the graphs — no
path memory except the D101 design/true anchor choice, which is
deliberate resolution state. rebuildTrack runs fast passes (cap
12) then, when no gesture is live, exact passes (cap 40).

THE HOT/SETTLED SPLIT (the exact settle costs one compile per
chain-depth section — measured 1.2 s worst-case after an
early-chain edit on the 2.3 km test doc, 27 ms at rest — too much
per pointer event, invisible once per gesture): gestureHot is set
on graph-canvas press, gizmo drag start and arrow-key nudges;
cleared + settleIfPending() on canvas release, endGizmoDrag and
the arrow's KEY_RELEASED. saveFile settles BEFORE DesignIO.save —
the document can never rest or be saved inside the tube, keeping
the D198 reload-bitwise contract. Placement moves stay cheap in
the exact tail by construction: D199 makes them write nothing.

Gates: DetermGate200 — the hand-revert returns EVERY vDesign and
EVERY sample bitwise on the plain full-test file AND with the
chaotic 0.1-knot pinned alive (any tube residue would re-roll the
knot); settled idempotence stays a bitwise no-op at 0 passes.
DetermGate197's quiescence bound widened for the exact tail
(first-open of a pre-D200 file walks fast + chain-depth exact
passes once, 19 on the test doc).

NL2 EXPORT PRE-VERIFIED headlessly the same round (the shakedown
item): Nl2Probe on the 1217 m FULL TEST file with the friction
defaults — spine + heartline modes at 0.5/1/2 m: table shape,
sequential indices, all-finite, uniform chords, frames unit +
orthogonal + NL2's up = front × left, and the D40 Z-negation
anchored at both ends (20 checks green). Max frame bend ~10.8°
per 1 m vertex is the file's own tight-radius geometry, halving
at 0.5 m — real curvature, not an export artifact.

ADVERSARIAL VERIFY ROUND (three independent skeptic passes over
the change — hot-path coverage, settle leaks, exact-mode
correctness) found and fixed six real holes before ship:
- settleIfPending FORCES now (clears gestureHot first): it used
  to defer to the flag, making the save-path settle ADVISORY —
  Ctrl+S during an arrow-hold, or any stuck flag, wrote the tube
  state, the exact violation D200 exists to close.
- gestureHot could STICK forever when an edit-key's release
  landed in another window (Alt+Tab, a modeless panel — only the
  main scene's KEY_RELEASED cleared it): every later rebuild ran
  fast-only. Backstop: the stage's focus-loss edge clears + settles.
- The key-burst end is UNIVERSAL now (any key release while an
  edit-key gesture is hot), guarded by pointerDown so a stray
  arrow release can neither settle mid-mouse-drag nor un-hot the
  rest of the drag; A-append and B/Shift+B joined the arrows as
  hot key-burst gestures (held keys repeat at OS rate).
- The density-preview slider re-applies its convert per DRAG
  EVENT — now a gesture (pvRerun rebuilds hot; Apply settles via
  commitDensityPreview, Cancel via its applySnapshot rebuild;
  bound keys are dead while the preview is up, so no save can
  observe the interim).
- saveFile settles BEFORE the PrintWriter truncates the target
  (the settle's compile surface must never be able to destroy the
  user's file on an exception).
- docSettled is HONEST at the cap (a cap-exhausted exact loop
  reports unsettled and keeps retrying via settleIfPending) and
  the exact write NaN-guards vIn (the fast tube's |diff| test was
  structurally NaN-immune; the bit test wasn't).
Analyzed and deliberately NOT changed: held Ctrl+Z/redo repeats
are cheap (undo restores SETTLED snapshots — the exact loop finds
nothing to write); Delete/H/F/locks are idempotent after the
first repeat.

Accepted corners: spinner arrow-HOLD bursts (friction / speed-
control / bridge length + next-piece rows / strict-seam angles on
a long doc) run the exact settle per tick — correct but
potentially laggy; wiring them as gestures needs a burst-end
event spinners don't emit (revisit if felt). An excursion that
FLIPS a section's D101 anchor keeps the flip after a hand-revert
— that memory is the feature's spec. A focus-blip mid-mouse-drag
degrades the REST of that drag to settled-per-event (harmless,
exotic).


## D201 (v18.29) — NL2 ELEMENT export (.nl2elem) + the generic Export dialog

His request: the FVD++ "NoLimits 2 Exporter" equivalent — an
.nl2elem ELEMENT file — plus File > "Export…" (generic name)
opening the existing window with a Format choice (Spline (CSV) |
NL2 Element) and the heartline explanation paragraph removed.

THE FORMAT — taken from FVD++'s actual source (openFVD
ui/exportui.cpp + core/track.cpp::exportNL2Track), not from
memory: root > element > description, per-vertex x/y/z/strict
blocks, then per-point roll blocks (ux..uz up, rx..rz right,
coord, strict false), every value C "%e" scientific notation.
Coordinates are ELEMENT-LOCAL: subtract the anchor (first point)
and rotate about Y so the entry's horizontal heading lands on
NL2's forward = −Z, heights untouched (FVD++'s anchorBase, a
transpose whose rows we apply verbatim); our left-handed render
world first maps to NL2's right-handed Y-up by negating Z — the
SAME D40 convention the CSV gates. FVD++'s roll "right" is its
vLat = (−dir.z, 0, dir.x) = rider-RIGHT, and right = front × up
reproduces it identically (verified algebraically: up = −vNorm,
vNorm = dir × vLat). Endpoint vertices strict, interior not
(FVD++'s extra section-boundary stricts are its bezier-segment
bookkeeping; our uniform resample has none); roll coords are
k/nSeg (its normalized-arc form under equal spacing).
Nl2ElemExporter.java, pure Java, sharing the CSV exporter's
line-choice/resample/orthonormalize construction.

THE DIALOG: File > "Export…" (menu + the rebindable "export"
action's label; Ctrl+E unchanged), Format radios at the top
(persisted, AppSettings.nl2Elem, default CSV, menu-undo wired
like the line radios), the explanation Label REMOVED (user ask),
FileChooser filter/name per format (*.csv / *.nl2elem), same
heartline|rail-center radios + spacing spinner feeding both.

VERIFIED headlessly (Nl2ElemProbe, 21 checks): JDK-DOM-parsed
structure; vertices equal the ALREADY-VERIFIED CSV export's
points under an independently recomputed rigid anchor transform
(≤1.5e-5, the %e rounding floor); heights preserved, chords
rigid, origin start; strict flags; roll coords exactly k/nSeg;
up/right unit + orthogonal, up matches the CSV up, right =
front × up; and the flat station start reads up=+Y, right=+X —
the handedness witness (facing −Z, rider-right is +X). Spine +
heartline at 0.5/1 m. The in-NL2 element insert is the one step
only he can run.


## D202 (v18.30) — the export JITTER fix (heart-Hermite + frame-slerp resampling)

His report: the classic FVD++ .nl2elem artifact — small
high-frequency jitter in NL2 at steeply banked track, on his file
at 0.2 m spacing around 935 m.

ROOT CAUSE (measured, not guessed): both exporters LERPED
positions and frames between the integration samples, so every
export vertex sat on a CHORD of the true curve — an alternating
radial sag of κ·d²/8 (≈0.42 mm at 935 m: κ 0.124, sample spacing
0.164 m at 16.4 m/s) that BEATS against the 0.2 m export grid
(alias wavelength ≈0.9 m) and reads as ~0.14 g of 18 Hz curvature
ripple once NL2 splines through the vertices. FVD++ exports its
own node polyline the same way — the shared heritage of the bug.
Measured red herrings: the %e 7-digit format contributes ~1e-4 of
the noise (kept, FVD++-faithful), and a dt/4 "ground truth"
compile diverges MACROSCOPICALLY from the displayed track (31 cm
at 935 m, meters past the strict seam — dt-convergence of a long
layout), so denser integration is neither a valid reference nor a
valid export source.

THE FIX — Nl2CsvExporter.resample, shared by BOTH writers: the
HEARTLINE interpolates as a cubic Hermite whose tangents are the
samples' exact unit T (error O(d⁴)); the FRAME interpolates as a
SLERP of each segment's relative rotation (within one 0.01 s step
the motion is near-constant-Ω, so the slerp IS the step's true
rotation); a spine vertex derives as heart − offset·U from that
frame. Position and bank leave the sag regime together, frames
arrive orthonormal by construction (the old per-point
re-orthonormalization is gone). A naive Hermite on the SPINE with
T tangents was probed and REJECTED — the offset line's tangent is
T − heart·dU/ds, and the wrong tangents made the noise WORSE
(rms 0.0053 → 0.019).

Measured on his file at 0.2 m: hi-freq curvature noise rms
0.0053 → 0.0024, and at his 935 m spot 0.0051 → 0.0008 (0.14 g →
0.02 g of ripple — below visibility). The residual IS the track:
the global max at 367.2 m is a real two-sample curvature/twist
transient at his abs-bank section's 0.3676 s lane node (the bake
knot lands off the 0.01 grid there — a runt integration step with
a genuine bank-slope kink), which the export must carry
faithfully. The twist channel's rms (0.026 °/m) is identical
before/after — sample-borne content, not export noise.
Nl2ElemProbe grows the jitter regression bound (rms < 0.0035,
@935 < 0.002); all export probes re-verified on the shared
resampler (42 checks).

SAME ROUND — the strict-seam roughness DIAGNOSIS (his question:
old-file artifact, speed conflict, or other?): it is the SPEED
CONFLICT. The seam arrives at 29.64 m/s TRUE against piece 2's
18.17 m/s design — an 11.5 m/s split scaling the felt about
gravity by (v_t/v_d)² ≈ 2.7×: the seam's designed 37°-over-10 m
turn pulls ~2.1 g at design speed but ~5.6 g at the true speed,
and the surrounding end-of-layout true felt peaks ~10 g. The
smoothing blend itself only adds a modest design-rate bump
(107 vs 90 °/s neighborhood) — the D139/D140 machinery is
healthy, and the pre-D199 save re-baseline contributes only dust.
Levers (his choice): resolve the conflict (badge menu > Set
Design Speed to Real / make-real on piece 2's first section),
brake before the seam, or widen the smoothing window.


## D203 (v18.31) — the smoothed conflicted seam's INSTANT TRUE-FELT JUMP (arc-true extrapolation)

His sharpened report on the strict-seam roughness: an
INSTANTANEOUS jump in value at the seam that no smoothing length
touched — "the smoothing not quite dealing with the speed
conflict correctly". Exactly right, and against D139's intent
(the DESIGN felt legitimately steps at a ≠v seam; the TRUE
curves are supposed to smooth).

ROOT CAUSE: smoothStrictSeam extrapolates each side's frame
field across the seam "at its own measured per-sample rate" —
with the exponent in SAMPLE INDEX. At a conflicted seam the two
sides' samples are design-spaced differently (v_d·dt each:
0.297 m vs 0.181 m on the test coaster), so the upstream field
extrapolated past the seam turned at its per-sample rotation
applied every FOLLOWER-spaced step — 1.64× its true per-arc
rate — and the follower's field mirrored at 0.61×. The blended
field's per-arc rotation therefore STEPPED at the seam by
exactly the design-speed ratio (measured: true rates stepped
1.55×, TRUE felt jumped 1.9-3.5 g), at ANY window length — the
cadence mismatch is scale-free, which is why his length lever
did nothing. Unconflicted seams have equal cadence, so every
D139-era gate passed over it.

FIX: the extrapolation exponents advance by ARC over each
side's own measured per-sample arc (quatPow already takes real
exponents; dsUp/dsDn measured over the same mU/mD windows as
the step rotations). At an unconflicted seam this is the old
integer exponent to float dust. Measured on the test coaster's
seam (29.6 true vs 18.2 design): TRUE Gv seam-step 1.86 → 0.31 g
at 10 m (the residual is the honest blend slope over one sample
pair) and 2.09 → 0.075 g at 30 m — the window length lever
WORKS again (wider = smoother, as designed). Smoothing OFF
keeps the honest hard kink; the design felt still steps (the ≠v
speaking — unchanged, documented). SeamJumpProbe is the gate
(4 checks incl. both honest-step guards); all suites green
(unconflicted strict-seam fixtures bit-level unaffected).

Remaining known display corner: the seam SAMPLE itself carries
a one-sample true-rate dip (the bodyRates differencing straddles
the landing runt interval) — rate-sourced graph draws already
trim SEAM_TRIM there; felt strokes run monotone through.


NEXT SESSION (his 2026-08-02 announcement): FIRST the list of
improvements and optimizations he collected while building the
full test coaster (a session-feedback round), THEN the four
Phase 4 items in the adopted order (1: the STRAIGHT/CURVE
OVERHAUL, 2: train approximation graphs, 3: imports & content,
4: 3D nodal track influence).
(advanced turns, roll customization, closure, viewport
interactivity with neighbor re-solve).


## D204 (v18.32) — shakedown round 1a: new-file defaults, Optimal-first previews, the seam padlock returns

Three small user specs from building the full test coaster:

1. FILE > NEW starts LOW AND SLOW — piece at y = 5 m, v0 =
   5 m/s, section 0's design speed 5 (constants NEW_START_Y /
   NEW_START_SPEED). The STARTUP document deliberately keeps
   21 m / 26 m/s: it ships the demo layout, whose 2.5 g hills
   need that energy budget (flagged to him — if he wants the
   startup blank-slate too, that is a one-line follow-up).
   Legacy-load defaults (0,21,0)/26 untouched.

2. The DENSITY PREVIEW opens on "Optimal placement" EVERY time
   (pvAdaptive := true in beginDensityPreview) — his "default
   selection optimize for every time it is opened". Unchecking
   is per-open; the sticky spacing/accuracy values persist as
   before. Applies to every preview flavor (converts, domain,
   make-real, bake-influence, reversal) — one window, one rule.

3. The strict-seam settings' per-axis "Discontinuous" toggle
   (D136/D140 wording) reverts to a plain PADLOCK button —
   🔒 = the axis holds the spinner's angle, 🔓 = it follows the
   seam; red outline stays as the held-axis telltale, tooltip
   carries the words. All machinery (first-open seeding D145,
   ↺ creation angle, Apply/Cancel) unchanged; Help re-worded.

## D205 (v18.33) — the STALE-PIECE gizmo: a document GENERATION joins the viewport signatures

His bug: new project → Convert the section to geo (distance,
°/m, both axes global) → drag the entry gizmo — "only the gizmo
moved, not the track."

ROOT CAUSE (not the compiler): a 32-combination headless sweep
(GizmoProbe) shows every convert-flag combo compiles and follows
piece moves exactly. The break is OBJECT IDENTITY in the
viewport: applyDocState — which the density preview runs on
every rerun and Apply, and undo/redo run on every Ctrl+Z —
rebuilds coaster.pieces with NEW TrackPiece objects carrying
IDENTICAL values. Viewport3D.gizmoSigNow() is a pure VALUE
signature (pose numbers, locks, flags), so refreshGizmo saw
"nothing changed" and kept the white ball's/rig's closures wired
to the ORPHANED pre-convert piece. A ball drag then wrote x/y/z
into a dead object — the ball follows its own writes (its anchor
reads the same dead piece), the document never changes, the
track never moves. Same hole after any lanes-only undo.

FIX: FvdDesigner.docGen — bumped wherever piece objects are
replaced wholesale (applyDocState, File > New, Load) — leads the
gizmo signature, so the next refreshGizmo (rebuildMeshes ends in
refreshMarkers → refreshGizmo on every compile) rebuilds
balls/rigs against the live objects. Covers every applyDocState
consumer at once: convert previews, undo/redo, load, new. The
mid-drag guard (no rebuild while gizmoDragging) is unaffected —
a doc-state swap cannot happen inside a gizmo gesture.

## D206 (v18.34) — a dragged "second click" no longer opens the node dialog

His intermittent bug: switch lanes (click a dimmed lane's node),
then click-AND-DRAG that node — the node settings dialog pops on
release. ROOT CAUSE: JavaFX counts clicks by press TIME/DISTANCE
only — the lane-switch click is click 1, the drag's press is
click 2, and MOUSE_CLICKED fires after the drag with
clickCount == 2 (dragging never resets it). The canvas clicked
handler now keeps the press point (clkPressX/Y) and treats any
gesture that traveled > 3 px as NOT a click — the seam-diamond
deadband convention (D158) — covering the node dialog AND the
s/c element dialog. A genuine stationary double-click (even one
whose first click switched lanes: it IS a double-click on that
node) still opens.

## D207 (v18.35) — SEAM-CLEAN RATES: Pass A differences per SPAN, the graph trim bands collapse

His ask: "make the buffer space on the seam between sections a
bit smaller for all situations… without artifacts."

The buffer existed because bodyRates' ±1-sample central windows
DIFFERENCED ACROSS section seams: a rate discontinuity smeared
over the straddling samples, so every consumer (Resulting
overlays, derived strips, true strokes, scale fits) trimmed
SEAM_TRIM = 2.5·dt around each boundary — a ~6·dt visual hole.

FIX at the source: passA segments per section SPAN (was per RUN,
D84 — a new int[] overload; the runs-based one delegates for
probes). One-sided windows at span edges make every seam-adjacent
sample honest: the boundary sample's rate is its OWN section's,
and the instant jump lives BETWEEN samples — where the seam
CARETS (D138/D140) already point. Consequences:
- The drawn curves run TO the seam and break exactly ON it
  (SeamWalk crossing-breaks replace the proximity trims; no
  extrapolation, no connector across the jump — zero artifacts).
- SEAM_TRIM shrinks 2.5·dt → 0.6·dt and only guards hover
  readouts/scale fits; hover SLOPE differencing clamps to the
  sample's own span (slopeWin) instead.
- D203's known corner — the one-sample true-rate dip AT the seam
  (bodyRates straddling the landing runt) — is CURED, not
  covered: the runt interval sits inside one span.
- Gate re-baseline: Phase3Gate 50f asserted the unsmoothed 9°
  kink shows a > 300 °/s smeared spike — that spike WAS the
  artifact this fix removes. Re-pointed at the new contract
  (sharp seam = clean rates ≤ 60 °/s + the U-kink witness;
  smoothed = the real ~23 °/s ramp). Single-section documents
  are bitwise (one span == the old whole-array call); smooth
  seams move by one-sided-vs-central dust. All suites +
  DetermGates 197-200 green.

## D208 (v18.36) — the collapsed graph editor UNLOADS; zoomed-out draws decimate per pixel column

1. redraw() is a hard no-op while the panel is collapsed
   (FvdDesigner.graphCollapsed, divider > 0.97) — the busy-plot
   cost goes to zero off screen. Expanding repaints via the
   divider listener + layoutChildren; 3D markers/gizmos stay
   synced through rebuildMeshes' direct refreshMarkers call
   (never redraw-borne — the D178 lesson holds).

2. The zoomed-out lag itself (his "if there is a way…"): the
   three sample-walking draw families each scanned the WHOLE
   compiled array per curve — derived strips even did it per
   inactive strip × 3 lanes. Now: view-culled index ranges
   (axisIdxRange binary search), span-scoped derived strips
   (each strip walks only its own samples; off-screen strips
   skip outright), and a COLUMN-COMPACTED path writer (ColPath /
   compactCols): consecutive points in one pixel column collapse
   to enter/min/max/exit, so the stroked coverage is IDENTICAL —
   spikes preserved exactly — while the path stays O(pixels)
   however many samples are in view. At normal zoom every point
   passes through verbatim (no change at all); dense dashed
   overlays additionally stop chewing their dash pattern inside
   zigzag columns.

## D209 (v18.37) — influence edge B/Shift+B lines up the RESULTING graphs (reverses D102's zero rule)

His re-spec (explicitly reversing the earlier instruction): on
the influence surface, B/Shift+B on a delta lane's EDGE node
should position it — value AND handle slope — so the RESULTING
graphs line up with the neighbor, not return it to zero. And
"like usual, B aims to line up the resulting graphs, not the
design graph."

MECHANISM: resulting == base + delta EXACTLY (the D98 two-pass
contract), so the matched delta is neighborTarget − base.
- neighborTarget rides the standard boundaryState machinery with
  an influence-mode routing (inflM): the authored/analytic
  shortcuts are SKIPPED — their lane semantics are the
  SECTION's, not the surface's (the v15.0 sameMode branch would
  hand a force neighbor's felt G to a Δpitch lane) — so targets
  come from the SAMPLED resulting curves, uniform across every
  neighbor type (force/geo/s-c/bridge/reversed/influenced/
  tilted), with the real-split κ conversion built in: at a ≠v
  seam B lines up the REAL curves, like everywhere else. The
  °/m wrapper and the world-rate matcher key off the DELTA
  lanes' own semantics (rates only for force-section influence;
  inflYawPitchGlobal drives world targets) — worldLaneAt reads
  the influence flags too.
- base value/slope read the compiler's baseCurves rows at the
  exact edge (t = 0 / the last row); a distance-native lane's
  handle slope converts per-second → per-meter at the seam
  speed.
- NO seam-speed re-freeze here: an influenced section's
  operating speed moves only by explicit action (the D95/D98
  reconcile contract stands).
- Fallbacks keep the OLD zero rule wherever matching is
  meaningless: no neighbor on that side (ride start/end), no
  compiled data yet, and the inert roll lane.
The sampled extrapolation carries the usual ~3-samples-inside
bias — a press or two converges (the D154 precedent, documented
in Help). InflMatchProbe: the seam's resulting-rate step closes
135.4 → 3.4 °/s on the first press, slopes join, and
resulting == base + delta holds through the span (0.09 °/s).


## D210 (v18.38) — new-file round 2: 5 s starter + a STANDARD camera, saved per file

1. File > New's starting section runs 5 s (NEW_START_LEN, joining
   D204's 5 m / 5 m/s — a 25 m level starter).
2. The fly/orbit CAMERA rides the .fvd (user spec: "opening a file
   will also bring the camera to the position the file was saved
   in"): a `camera px py pz yaw pitch dist` cosmetic line (world
   coordinates — Viewport3D.cameraWorld/applyCameraWorld convert
   through the pane's scene offsets gridCamX0/Z0 and the Y-mirror;
   an ortho pane reports/receives its saved perspective pose, a
   flying pane the orbit pose it returns to). Cosmetics grows a
   nullable camera slot; missing line = the view stays put (old
   files unchanged). Save writes the PRIMARY pane's pose; load
   applies to it after the rebuild. File > New frames the fresh
   starter deterministically (pivot mid-track at (12.5, 5, 0),
   yaw −30, pitch −20, dist 35 — the default orbit angles).
   Save-while-flying stores the pre-fly orbit pose (accepted).

## D211 (v18.39) — POV stats line: absolute global Pitch/Yaw/Roll + rates

The bottom stats line (which lives on FvdDesigner.updatePovStats —
the CLAUDE.md Viewport3D attribution was stale) gains three
entries: Pitch (elevation°), Yaw (heading°, REPORTED left-positive
per the D186 display boundary — eulerOf's internal heading
negates), Roll (bank°), each with its GLOBAL rate (°/s) from
Coaster.worldRates on the TRUE arrays (already reported-sign).
Angles lerp wrap-aware between the POV bracket samples (D193);
vertical track shows "—" where heading/bank are undefined
(worldRates' NaN hold-off).

## D212 (v18.40) — blank spinner: the zero rule at EVERY commit entry + press backstops

His report stood: ENTER-on-blank committed 0 (D192) but clicking
off didn't. TWO root causes (bytecode-verified by the
investigation): (a) most numeric spinners live in NON-AUTOHIDE
Popup scenes (seam settings, speed-control options) whose other
controls are focus-untraversable — JavaFX focus is per-Scene and a
popup's focused flag is slaved to the owner stage, so clicking
anywhere else fires NO focus event at all: neither JavaFX's own
commit nor the D191 listener ever ran; (b) where a transition DOES
fire, Spinner's INTERNAL focus-loss commit (installed in its
constructor — the D191 "JavaFX only commits on ENTER" premise was
wrong) runs FIRST and drives the value through the converter's
empty→NULL transient — swallowed NPEs in the appliers, and
commitTyped's custom-factory restore branch rendered "null" (which
the numeric TextFormatter rejected: value stuck).

FIX, three layers: (1) FvdDesigner.wrapBlankConverter — a
BlankSafeConverter around every numeric spinner's factory
converter, so blank reads as clamp-to-range(0) for double
factories (current value for the snap-step walkers) at EVERY
commit entry point, Spinner's internal ones included; the null
transient is gone at the source (re-wrapped after the friction
spinners install their fixed-decimal converters). (2) Scene-level
MOUSE_PRESSED backstops (installNumericPressCommit /
commitStrayNumericEditors): any press outside the focused spinner
commits it — in its own scene AND across windows (a popup's editor
vs a main-window click); the stage-deactivation handler runs the
same sweep BEFORE settleIfPending. (3) A null guard in
wireDocSpinner (hardening; the transient no longer occurs).

## D213 (v18.41) — the °/m + absolute-roll convert de-banked (one-line guard)

His "converting from roll rate to absolute roll always generated
erroneous results": convertToLanes' PER-METER block divided lane 2
by the profile speed UNCONDITIONALLY — including when the
toRollAbs block had just filled it with the measured BANK ANGLE
(degrees). Every °/m+absolute target authored bank/v (60.5° →
2.39° at ~25 m/s — measured); his sections are °/m throughout, so
it hit "always". rateM=0 targets were always fine, which is why
DomainGate 47c never saw it. FIX: `if (!toRollAbs)` on the lane-2
division — an absolute bank lane is degrees, not a rate; the
integrators consume it verbatim under every flag (laneRatePM's
exemption, and the '+'-hold already passed bank through undivided).
Covers whole/partial/bridge converts (one engine). The
investigator's 28-combination sweep now lands every combo at the
rateM=0 fit residuals (≤ 0.06 m / 0.09°; was 58° bankDev, 168 m).
Known separate corner (unmeasured, pre-existing): the bank
extraction doesn't unwrap ±180° across inversions.

## D214 (v18.42) — resulting overlays: PER-QUANTITY normalization (the −125 g axis)

His two-geo rate→abs fixture showed a force axis of −125..+25 g
though nothing exceeds 3.7 g (measured on his file — the model
arrays are clean). The ~±100 lives in resultRates[i][3]: a legit
−112 °/s resulting ROLL RATE (−9.86 °/m × v), fitted by
updateResScale into the ONE shared overlay scale — which over a
geo section is seeded, labeled and read as G. FIX: the roll/bank
overlay (comp 3) normalizes on its OWN pair (resLo3/resHi3,
comp-aware resToY) whenever its units differ from comps 1/2 — geo
view (g vs rates) and absolute bank (degrees) — with its own
defaults (±60 °/s / ±5 °/m / ±90°); force view with a RATE roll
keeps the shared scale (all three comps same units, unchanged).
The right-edge axis title appends "· roll °/s own scale" so the
numbers never claim g for a rate curve; hover reads its values.

## D215 (v18.43) — complex-control LAST-NODE length drag + the collapsed strip readout

1. Dragging a complex speed-control graph's LAST node now drags
   the CONTROL LENGTH itself (user spec: like a normal lane's end
   node — no stretch): endLimitFor's strip wall becomes the
   section end / next control (surfCtlMaxLen) for the last node,
   and moveNode writes surfCtl.len = the node's new x (floored at
   2× the domain gap so spClampControls never re-stretches; a
   graph ending short holds dotted, the D174 look). Interior
   nodes keep the hard box; the END DIAMOND (and the popup Length
   spinner) keep the classic stretch semantics — both rescale
   BOTH graphs now via spRescaleCtlLanes. Non-ripple neighbor
   clamp (v1).
2. The drag-to-create "length … m/s" readout drew at lineY − 8 —
   in the COLLAPSED strip that is above the strip's own clip rect
   and the glyphs were cut to slivers (his "hover message gets
   covered up"). Collapsed mode drops it under the line
   (bot − 2); tall mode unchanged.

## D216 (v18.44) — the FAIL-SOFT loader ("should be able to open the file anyway")

Continuing D196/D198, from a full audit of DesignIO.load vs
save() (9 findings, 3 confirmed by measurement):
- PER-LINE fail-soft: a truncated/garbled known line (a partial
  write, a sync conflict — the docs live in OneDrive) used to
  throw AIOOBE/NumberFormatException out of load() and refuse the
  ENTIRE file. The line-switch now catches per line, skips, and
  counts; LoadResult carries `warnings`, and loadDesign reports
  "N damaged line(s) skipped/healed", marking DIRTY so a save
  rewrites a clean file. Damage can still mis-attach children
  when a structural line (piece/section/lane) is the broken one —
  the warning is the honest cue (documented tradeoff).
- Post-load HEALING replaces refusals: an empty lane block heals
  to a held constant (1 g on a force Gv lane, 0 elsewhere — a
  1-node lane is legal, D196), an element-less s/c section and a
  sectionless piece DROP with a warning; a document with nothing
  left throws "no track data" — CLEANLY, before loadDesign
  mutates the live document (a zero-piece file used to load,
  replace the session doc, then crash the editor on
  pieces.get(0): the one path where a bad file corrupted the
  RUNNING session).
- Clamp symmetry (the D198 rule, upper face): the reconcile
  writes true entry speeds UNCAPPED while load clamped vDesign
  (and infl) to 1000 — a >1000 m/s save (legal inside the
  admitted ±1e6 placement envelope) silently rewrote on reload.
  Upper bound now 1e4 (≥ the envelope's ~6.3 km/s ceiling).
- Smaller: UTF-8 BOM before FVDD3 no longer refuses; railcolor/
  start loops bounded by parts.length (the gradcolors pattern);
  FVDD1 zero-length-segment handles no longer produce NaN
  fractions; the bridge `section` line persists vPinned (field 6
  — the lane/sc lines always did; a pin on a bridge-first piece
  silently dropped).
- NaN policy: deliberately UNCHANGED (admitted bit-faithfully;
  Infinity clamps) — rejecting would break bit-fidelity for the
  one case where the session really held NaN; the codebase's
  no-NaN stance lives in the writers (Coaster.java:1837).

## D217 (v18.45) — ACCEL/DECEL split for constant-acceleration controls

User spec: separate Acceleration and Deceleration, tied by
default. Model: SpeedControl gains decel + tied + decelLane (+ the
session-only showDecel display flag; all ride copy() = undo), and
driveCore takes both magnitudes — the below-target (launch) side
drives at aAcc, the above-target (brake) side at aDec, each with
its own a ≤ 0 = natural guard; tied passes accel for both =
BIT-IDENTICAL to the one-magnitude arithmetic (gated). rowFor
widens to {type, vt, aAcc, aDec, min, max} (passD pre-resolves
complex decel graphs per sample like accel always was);
Ctl.drive/driveRow/snapRow re-index; portion() clips and
carrySpeedCtls remaps decelLane exactly like lane.

UI: the options popup's accel row gains a "Tied" check (default
on); untied reveals a Deceleration row — spinner when constant,
"Edit Deceleration" button when complex. The two Edit buttons
pick WHICH graph the strip displays/edits (▸ marks it; accel
first on untie — user spec); the un-displayed twin draws dimmed
behind. Untying seeds decel := accel (deep-copied graph), so
nothing changes at the split; re-tying keeps the decel data
stored but ignored (session fidelity). The strip pipeline routes
through SpeedControl.editLane() at the active() choke; scale
fits, hit tests, lock enforcement, and all three length-rescale
sites cover both lanes.

IO: sctl fields 9-10 = decel + tied (missing = tied with decel :=
accel — every old file bitwise), decel graph as `cdn` node lines
(written even while tied; old readers drop unknown lines), heal
to a flat graph like cn. Accepted corner (the D166 class): the
typed node dialog applies to the graph that was displayed when it
opened.

Gates: R2Probe (14 checks — tied-ignores-decel BITWISE, untied
brake distances land on ¼-theory, flat-complex == constants to
3.6e-15, byte-identical round-trip, reload-bitwise, decel-graph
clipping; plus the D210 camera line and five D216 loader checks).
GATE-INFRA note (same round): the on-disk determinism fixture
came back KNOTLESS (he re-saved it 2026-08-04 — the known
re-saves-test-files trap), so DetermGate198/199/200 now fall back
to a CONSTRUCTED knot (KnotFixture: a geo climb that eats the
kinetic energy chained into a force section the reconcile floors
at 0.1); DetermGate199's window/split legs made single-piece-
safe; LoadGate196's "empty lane throws" leg re-baselined to the
D216 heal; Phase3Gate 50f re-baselined for D207 (the smeared-
spike expectation WAS the artifact). Full battery green.


## D218 (v18.46) — Add View centers on the coaster; camera jumps re-follow the floor grid

His round-3 report: a freshly added pane's floor grid "does not
update until the view is moved — can result in no grid being
shown". ROOT CAUSE: the LOD grid follows the camera only through
the MOVE handlers (orbit/pan/fly/pivot each call updateGridLOD);
a new pane's grid is parked at the TRACK MIDPOINT by the
worldCentered block, while its camera was a copy of the spawner's
pose — past the grid's 240 m fade that reads as no floor at all.
Same-cause audit found two more holes: (a) applyCameraWorld (the
D210 load restore + New framing) moved the pivot without the grid
following — the SAME bug one report later; (b) the D182
spawner-pose copy transplanted SCENE coordinates between panes
whose worlds can be centered on different documents' midpoints
(pane 0 centers on the STARTUP doc, later panes on the current
one) — a silently offset camera.

FIX, one route: updateGridLOD moved INTO applyCameraWorld (every
programmatic camera jump re-follows the grid), and Add View now
CENTERS ON THE COASTER (his spec) via FvdDesigner.frameCoasterPose
— compiled-track bbox center, the default orbit angles, distance
fitting the bounding radius in the current FOV — applied through
applyCameraWorld AFTER the pane's first rebuildMeshes (the
scene↔world offsets exist only then). The scene-coord copy is
gone with the feature it served.

## D219 (v18.47) — the accel/decel CHAIN LINK (his UI re-spec of D217)

The Tied check + conditional Deceleration row become ONE always-
visible line: Acceleration · 🔗 · Deceleration (spinners when
constant, the ▸ Edit buttons when complex). The 🔗 ToggleButton
(tooltip "Link Acceleration and Deceleration"; lit border while
linked, dimmed when not) replaces "Tied": LINKED (default) keeps
both spinners editable and MIRRORS every edit into the other —
the classic one-magnitude behavior, still bitwise (the model's
tied flag and evaluator are unchanged; the mirror just keeps
decel == accel in the file too). Unlinking seeds decel := accel
(+ a deep copy of the graph when complex) so nothing changes at
the split; re-linking snaps decel back onto accel. While linked
BOTH Edit buttons open the one shared graph and both wear the ▸;
unlinked, each button selects its own graph (accel first).


## D220 (v18.48) — round-4 polish: closer default framing, denser/bigger combs, the neon chain, the layered-under speed curve

1. DEFAULT VIEW: frameCoasterPose tightens to 0.8× the
   fit-the-sphere distance (coasters are flat-ish — the bounding
   sphere overestimates) and now ALSO applies at STARTUP (the
   default view was the never-centered pivot-at-origin rig; file
   loads still restore their saved camera on top) and to File >
   New (replacing the hand-tuned constant — one framing rule
   everywhere).
2. COMBS: 1.5× denser (COMB_SPACING 0.5 → ⅓ m; the TIME comb's
   fillers follow automatically — their count derives from the
   spacing — while whole-second ticks stay on seconds) and the
   old 140% size IS the new 100% (COMB_SIZE_BASE 1.4 multiplies
   the slider; a stored 140% now renders 1.96× — the slider's ↺
   returns to the new default look). EVERY comb hover readout and
   the POV stats angles/rates read THREE decimals (his round-5
   follow-up), and the radius wears a plain "r=" — it is the
   exact reciprocal of the displayed °/m value, so its accuracy
   IS the curvature reading's.
3. The speed-control CHAIN: smaller button (padding only — the 🔗
   glyph size unchanged), BRIGHT PURE YELLOW background while
   linked (rgb(255,235,0) — the first pick's green cast came from
   G > R), plain gray otherwise, glyph always black, NO
   outline (the blue border is gone). And his re-spec of the
   linked behavior: the DECELERATION side grays out while linked
   — spinner disabled but live (it visibly mirrors every
   Acceleration edit), the Edit Deceleration button disabled, the
   ▸ stays on Acceleration alone. Unlink to edit the brake side.
4. The "dim resulting speed graph at 5 m/s" ROOT CAUSE: the
   strip drew the green dotted TRUE-speed curve BEFORE its value-
   axis gridlines — the main plot's inverse layering — so the
   dark gridlines painted over the curve; a new file's near-flat
   5 m/s curve hugs one gridline for a long stretch and read as
   washed out. The curve now draws AFTER the grid (and at full
   alpha like every other curve); no color was ever wrong — the
   layering was.


NEXT: the rest of his shakedown feedback (he is holding a couple
of items for their own prompts), then Phase 4 in the adopted
order (1: the STRAIGHT/CURVE OVERHAUL — advanced turns, roll
customization, closure, viewport interactivity with neighbor
re-solve; 2: train approximation graphs; 3: imports & content;
4: 3D nodal track influence). Also pending his verdict: renaming
the .fvd extension is a two-line FileChooser change whenever he
picks a program name (answered in-session).


## D221 (v19.0) — the ORIENTATION LINK: design/true pitch-roll shown, chosen, and carried

His first "larger addition" prompt: make the hidden difference
between DESIGN and TRUE orientation a first-class control. (He
wrote "pitch/yaw"; yaw physically CANNOT split design from true —
rotation about gravity's axis commutes with the solve, D199 — so
the substance is pitch/ROLL, with yaw riding along in every
surface. Stated in the summary; roll included by symmetry.)

1. ONE FLAG PER PIECE, THREE SURFACES. TrackPiece.orientMode
   {AUTO, LINKED, RIGID} + nodesDirty, surfaced as an
   "Orientation edits reshape track" check on the Track Piece
   panel, the Bridge properties panel (targeting the NEXT piece)
   and the Strict seam settings (targeting the FOLLOWER). LINKED
   = orientation edits fold into the GENERATION frame — gravity
   re-solves the shape, design == true, no ≠v from placement.
   RIGID = the historic D100 tilt: shape preserved, honest ≠v.
   Toggling flips POLICY only (nothing is baked): with live
   deltas the shape re-solves or reverts, orientation never
   jumps, and the flip is symmetric + undoable.

2. AUTO DEFAULT = "pristine pieces link". linkedNow() resolves
   AUTO by nodesDirty: a piece whose AUTHORED content (lane
   nodes, s/c elements, speed controls) has never been hand-
   edited since the piece's creation stays LINKED; the first
   content edit flips it RIGID. Tracked by a per-rebuild content
   FINGERPRINT (Coaster.contentFingerprint) baselined on fresh
   piece objects — undo restores, convert previews and loads
   replace pieces wholesale (applyDocState), so CONVERSIONS and
   INITIAL-SPEED edits never count, exactly his exemption list,
   with no per-call-site bookkeeping. Old files load AUTO+dirty
   (a `porient mode dirty` line per piece, missing = dirty) =
   bitwise the historic behavior — all four DetermGates green
   untouched.

3. LINKED GENERATION IS CANON-SPACE. The pitch/roll deltas
   compose onto the canonical (heading-stripped) frame
   (TrackCompiler.linkedCanon) — the same axis math as
   entryPose() rotated by −psi, heading-equivariant, so psi
   stays additive: x/y/z AND yaw moves of a linked piece remain
   BITWISE-rigid (OrientGate d1/d2) while pitch/roll edits
   re-solve, which is the point. Linked tilt == bakeTilt's
   re-solve to 1.3e-14 m (gate c1) WITHOUT mutating stored
   fields — the deltas stay in the spinners, and unchecking
   returns the rigid reading. Resolve ops skip bakeTilt on
   linked pieces (nothing to resolve; baking would only swap
   arithmetic bits). LINKED FOLLOWERS generate AT the follow
   pose (canonOf(eff)) — design tracks the seam and upstream
   edits reshape the follower, the deliberate opposite of the
   D135 freeze; upstream translation is bitwise, upstream yaw
   re-solves with dust (the held-angle re-aim's accepted class).

4. RIGID CARRY: the whole downstream chain is one rigid body.
   A RIGID piece's placement edit (spinners, all gizmo surfaces,
   seam settings — every write funnels through
   FvdDesigner.placementEdit) transforms the CONNECTED chain
   (trailing bridges + strict followers, recursively) by the
   same rigid delta (Coaster.carryDownstream): manual-bridge
   targets get position + orientation-as-deltas (decomposeEntry
   — yaw carries the heading conflict-free, pitch/roll ARE the
   adopted conflict), optimize-mode targets get orientation only
   (the march re-lands position on the rigid image itself),
   followers get their LOCKED Euler angles rewritten (position +
   unlocked axes follow the moved seam automatically). Gated:
   translation exact to 9e-14 through a manual bridge; a 6°
   pitch keeps both pieces exact rigid images (6e-14) with the
   bridge SPINE rigid to the check's own chord floor (7e-4) —
   only the bridge's TIME sampling re-derives over the new
   heights, the honest energy law. A carried LINKED piece that
   receives a tilt flips to explicit RIGID (folding it in would
   reshape — the opposite of a rigid carry). LINKED pieces keep
   today's behavior: bridges re-solve, followers re-aim, nothing
   is carried.

5. RETURN TO DESIGN. Each surface shows a "Return to design
   orientation" button only while a RIGID conflict exists:
   piece/bridge-target = zero the pitch/roll deltas (the rigid
   un-rotation — shape preserved, conflict closed, downstream
   carried back); seam = each LOCKED axis back to the follower's
   stored generation Euler. Yaw is deliberately untouched
   (design yaw == true yaw always).

6. THE OPTIMIZER LOOP GUARD. alignPieceForBridge writing
   pitch/roll onto a LINKED target would re-solve its shape,
   re-capture its head and re-align — an unsettleable loop. The
   march claiming those axes flips the target to explicit RIGID
   (gate i3); the default P+Y locks (yaw-only alignment) leave
   the flag alone (i2), so bridging onto a pristine piece with
   default locks keeps it linked. The bridge panel's link check
   grays under optimize-orientation with a hint.

Accepted corners (stated to him): a follower's UNLOCKED axes
follow the seam by definition, so under a pitch/roll carry their
re-aim differs from the exact rigid image at second order
(pure translation/yaw carries are exact — gate g at 5e-16); a
vertical-heading bridge target cannot take the delta
decomposition and carries position-only; spinner-click carries
compose per commit (hand-reverting leaves float dust — Ctrl+Z
is the exact path, as everywhere); the carry writes ride the
caller's one undo snapshot. Gates: OrientGate (23 checks) +
DetermGate197/198/199/200, Phase2Gate (258), Phase3Gate,
DomainGate, LoadGate196 all green — the three fixture files pin
nodesDirty=true where they hand-author pieces in code and then
assert RIGID-tilt behavior (model-built pieces are pristine by
construction; a user's authored piece is dirty by the fingerprint).


## D222 (v19.0) — "Lock Track Shape": the two-tier re-spec of D221 + the seam-popup fly-keys fix

His feedback round on D221 (he confirmed "pitch/yaw" was a typo
for pitch/ROLL), plus a mid-round clarification: seam and bridge
ORIENTATIONS work exactly as before — the feature lives at the
START of a track piece only — but the rigid carry stays keyed to
the edited piece's checkbox.

1. TWO TIERS, ONE ROUTING FLAG. TrackPiece grows
   designPitchDeg/designRollDeg beside the true pitchDeg/rollDeg
   (spinners + file fields 9-10 unchanged). Generation composes
   the DESIGN tier in canonical space (TrackCompiler.designCanon);
   the design→true gap applies as the rigid conflict tilt
   (conflictTilt: design (0,0) = tilt()'s historic arithmetic
   bitwise; design == true = the IDENTITY object, the pure link).
   shapeLocked (default TRUE) is a pure EDIT-ROUTING flag — the
   compile never reads it. TrackPiece.editPitch/editRoll are the
   routing chokes: locked = true tier only (conflict grows, shape
   kept, rigid carry); unlocked = both tiers move by the same
   Euler amount. That lockstep IS his invariance spec: unchecking
   never moves anything (the offset freezes), and the shaping any
   subsequent edit causes is IDENTICAL with or without a conflict
   riding on top — same design triple, same generation bits
   (OrientGate c3: dev 0.0, the conflicted twin is the exact
   rigid image). D221's policy-flip-reshapes behavior is gone;
   AUTO/nodesDirty/contentFingerprint died with it (the default
   is simply LOCKED = classic).

2. ONE SURFACE. The checkbox ("Lock Track Shape", checked by
   default, tooltip verbatim "Design orientation does not
   change") lives on the Track Piece panel ONLY — the D221 bridge
   and seam copies were removed on his spec ("weird edge cases").
   Strict-seam and bridge orientation surfaces are classical
   single-tier again (the linked-follower compiler branch is
   deleted; the march writes the true tier by convention, so the
   optimizer can never reshape-loop — even on an unlocked target,
   gate i). The carry keeps its D221 shape and keys off the
   EDITED piece's flag: locked = the downstream chain (bridges +
   strict seams, recursively) rides along rigidly adopting the
   conflict on its TRUE tier; unlocked = natural (historic)
   updates. Followers hide the surface (seam-owned; their
   default-locked flag keeps seam re-aims carrying).

3. THE BUTTON PAIR + READOUT. One line, shown only while design ≠
   real: "Design → Real Orientation" (design := true — the shape
   re-solves at the real frame; bitwise the pure-link shape, gate
   j2) and "Real → Design Orientation" (true := design — the
   piece and its carried chain un-rotate onto the authored
   orientation, shape preserved, gate j1; replaces D221's
   "Return to design orientation"). Below the pitch/roll
   spinners, a read-only "Design orientation: pitch X° roll Y°"
   line appears while split (synced per rebuild +
   syncPlacementSpinners; the popup's row visibility refreshes
   through the same hook).

4. TIER HYGIENE. bakeTilt zeroes BOTH tiers (and the pure-yaw
   canon fast path now requires all four deltas zero); the
   resolve ops bake on orientConflict() only; reversePiece,
   bakeFollow and partialConvertBridge's right-side re-anchor
   reset the design tier with the true one — a leftover design
   delta would silently ACTIVATE generation when the piece stops
   being machine-owned (the workflow's change-surface sweep
   caught all three). editRoll wraps the design tier into ±360
   (ring drags wrap the true value; the ±720 lockstep jump is
   the same rotation, kept in spinner range).

5. IO. `porient locked designPitch designRoll` (Double.toString —
   the design tier SHAPES the track, so it is geometry-feeding);
   missing = locked + (0,0) = every pre-v19 file bitwise; the
   uncommitted D221 interim format (`porient mode dirty`)
   migrates: mode 1 (LINKED) → unlocked + design := true (gate
   h3). PieceState carries the three fields.

6. THE FLY-KEYS BUG (his report: "right clicking into the
   viewport in fly view doesn't allow camera movement when in at
   least the strict seam menu"). Root cause, pinned by a
   full-window audit: the strict-seam settings popup was the ONE
   editor-hosting secondary window without the D166
   installViewKeys ride-key forwarder — its non-auto-hide window
   holds keyboard focus after any spinner click, so W/A/S/D died
   in the popup's scene while right-click fly-entry and mouse
   look (mouse events, focus-free) kept working. Fix: a
   setOnShown installViewKeys registration (covers BOTH show
   branches — the gizmo-anchored one returns early). The audit
   cleared every other window: all editor-hosting
   dialogs/popups/menus already forward (or are momentary plain
   menus / deliberately-blocking modal alerts).

Bitwise contract note: a piece with a LIVE CONFLICT has always
carried last-ulp dust under yaw moves (the tilt quat is
yaw-conjugated — the D100 tilted-piece limit, unchanged);
conflict-free pieces (either tier alone) keep D199's bitwise
yaw/translation rigidity (OrientGate d1/d2). Full battery green:
OrientGate 23, DetermGate197/198/199/200, Phase2 (258, fixture
pins reverted — default-locked restores rigid fixtures natively),
Phase3, Domain, LoadGate196.


## D223 (v19.1) — Small-items round: badge hover, export piece picker; the LOST GATES + the durable `gates/` folder

Batch 15 opened with two quick asks: (1) the section-settings
BADGE lights up under the mouse (brighter fill/edge, full alpha,
hand cursor — the seam-line hover pattern, `hoverBadge`; the
mouse-exit now also clears the seam hover it never cleared), so
it reads as the button it is; (2) File > Export grows a "Track
piece" dropdown (Whole track | Piece k/n — hidden for one-piece
docs): a piece exports its own compiled sample range incl. a
trailing bridge, with the dropped continuation seam sample put
back so the spline starts ON the piece's entry; filename
`coaster-pieceN`.

PROCESS NOTE (important): Windows Temp cleanup purged every
older session scratchpad between the D222 round and this one —
Phase2Gate/Phase3Gate/DomainGate/DetermGate197-200/LoadGate196/
KnotFixture existed ONLY there. They were RECOVERED by replaying
the Write/Edit tool calls out of the session transcripts, and
ALL gates now live in the repo under `gates/` (compile with
`javac -cp target/classes -d gates/out gates/*.java`, run with
`java -cp "target/classes;gates/out" X`; the recovery script is
gates/tools/). Never keep a gate only in a scratchpad again.
Recovery was complete for every gate EXCEPT Phase2Gate, which is
PARTIAL: 163 of its historic 258 checks (the convert / partial-
convert / node-split / connect-sections / speed-reconcile / split-
resolution families were authored in a session whose transcript
no longer exists — surviving fragments in Phase2Gate.lost-edits.txt);
three recovered checks were stale and re-baselined (six-stop
cosmetics round-trip, the D84 dropped-seam-sample bridge check, a
55i stall fixture that looped instead of stalling — now a straight
geo climb). Rewriting the lost families is an open item.


## D224 (v19.1) — ABSOLUTE orientation boxes; a TILTED split stays put; through-the-pole rings (decomposeNear)

His report: after splitting a piece at a pitched/banked point,
the Track Piece boxes read zero orientation while the position
boxes read the real position; asked that the boxes always show
the REAL starting orientation, that splitting NEVER changes the
track however complicated, and (a separate item) that global
gizmo rings and the placement pitch pass through vertical
seamlessly like NL2 — plus a "wobble" at the top of local ring
drags.

1. DISPLAY, NOT MODEL. baseOrient + deltas stay the model (the
   D71/D199 bitwise split rides on baseOrient copies). The panel
   shows CANONICAL heading/elevation/bank of the entry —
   TrackPiece.dispYaw/dispPitch/dispRoll: base Euler
   (decomposeEntry off the identity) + delta, exact by the
   composition's construction (yaw keeps elevation+bank, pitch
   about the horizontal right keeps heading+bank, roll keeps
   both) — EXCEPT past the pole, where a raw elevation beyond
   ±90 reads as heading+180, mirrored elevation, bank+180 (the
   level-up reference flips). Fresh pieces (identity base) show
   the deltas bitwise as before. setDispYaw/Pitch/Roll invert on
   the CURRENT pole branch (setDispPitch keeps a past-the-pole
   piece on its mirrored scale — heading never jumps); route =
   the D222 lock (true only vs lockstep), false = the bridge
   rows' plain true write. The bridge next-piece rows and the
   design readout use the same functions.
2. RINGS DRIVE THE RAW COORDINATE. Global rings read/write
   rawAbsYaw/Pitch/Roll (base + delta, unreduced) — absolute-
   from-press stays continuous THROUGH the pole (a raw pitch of
   100 composes fine; the boxes then read 80 with heading+180 =
   NL2's flip); the ±85 clamps are gone everywhere (spinners
   ±90). pitchAxis draws the ring about the YAWED BASE heading's
   horizontal right (identical to the true T's below the pole,
   continuous through it).
3. decomposeNear (the wobble fix). Local rings/carries used
   decomposeEntry — canonical, so crossing the pole flipped yaw
   AND roll by 180 (with a SHARED yaw, that would have flipped
   the design tier too) while the ±85 clamp fought it: the
   wobble. decomposeNear(base, target, hints) evaluates BOTH
   Euler branches (primary: heading gap / elevation gap; flipped:
   yaw±180, pitch carried OVER the pole = 180−e₁−eₜ, roll±180),
   wraps each component into the hint's 360° window and returns
   the branch with the smaller total change — a pole crossing
   keeps yaw/roll and runs pitch past 90; a 170° yaw carry stays
   pure yaw (the flipped branch costs ~360° there). Used by the
   local rings (piece + bridge), carryDownstream, and the split.
4. TILTED SPLIT CONTINUITY. Span carries exitTilt (the piece's
   conflict tilt or a follower's re-aim; canon pair now ALWAYS
   stored). splitPiece hands the new piece the DESIGN chain
   (baseOrient := tilt⁻¹·E, the pair copied — bitwise
   generation) plus the tilt as its OWN true-tier deltas
   (decomposeNear(design, E)): a rotation about the new entry
   composed with the boundary translation is the parent's
   rotation about ITS entry, so the maps coincide — the track
   stays to dust (gate c1 6.8e-14, follower e 7.1e-14) and the
   panel shows the honest inherited conflict. Untilted splits
   keep the exact-zero path (bitwise, gate b1). The D100
   "splits re-solve" corner is CLOSED (reversal still bakes).
   Gate: OrientGate2 (18 checks).

Accepted: a raw pitch accumulates unbounded across ring drags
(the display is canonical; a spinner write re-derives on the
current branch); yaw is shared, so a rotation with a heading
component splits into design heading + pitch/roll conflict at a
split (rotationally exact, "Real → Design" leaves that heading).


## D225 (v19.1) — LIVE node editor

"Instead of an Apply button, make the track update while
editing each value." The node dialog's boxes are LiveNum
widgets (TextField + ▲/▼ steppers + mouse wheel — Shift ×10,
Ctrl ×0.1): every PARSEABLE text change applies at once through
the same paths Apply used (moveNode for time/value — ripple,
level runs, slope-keeping dv rescale; direct fraction writes
for handles; the start-speed spinner; vDesign+pin for the
initial design speed); a blank/unparseable box holds (his
"exactly as it does currently" clause = the old parseOr
fallback). Each edit is a GLOBAL undo step (typing bursts within
0.8 s on the same box coalesce). The node is re-resolved BY
INDEX per apply on the captured surface, so Ctrl+Z (which swaps
lane objects) leaves the box live; switching section/lane makes
it inert rather than writing elsewhere. Buttons: Close only.


## D226 (v19.1) — The START SCREEN + "Add floating section"

File > New shows a start screen IN the graph area (StartPanel,
hosted over the GraphEditor canvas; graph keys dead while up,
text boxes get their keys): step 1 = two tiles (Straight/Curve |
Geometric/Force) with the track HIDDEN (Viewport3D reads
app.trackHidden); step 2 = the track appears and a live form:
the section's rows (the Add-section dialog's Type/Domain/Rate
units/Yaw-pitch axis/Roll axis/Roll behavior for lane types; a
10 m straight note for s/c), Placement (x/y/z + yaw/pitch/roll —
BOTH tiers written = "as if Lock Track Shape were unchecked", no
conflict), Speed, and (new file only) Friction, all applying
live; "Start designing" closes it (history clean, doc clean),
"Skip" keeps the plain 5 s starter, "‹ Back" re-picks. Defaults
= the historic ones. "Add floating section…" in the ⊕ menu opens
the same screen minus friction for a NEW detached piece —
default 5 m past the active piece's end at its heading and true
speed; ONE undo step (snapshot at the pick; Cancel/Back
un-create through it). Model writes funnel through
FvdDesigner.wizard* so undo/dirty/spinner sync stay in one
place. Dev hook: `-Dfvd.devshot=<dir>` renders the three states
to PNG (this display is too small to show the graph panel).


## D227 (v19.2) — The UNDO deep-dive: one history, global keys in every shape-affecting window, live panels

His report: intermittent Ctrl+Z weirdness around the Track Piece
settings (dead while the panel was open; edits made in the panel
not undoable after closing it), Ctrl+Z dead in the node editor,
and the rule going forward — ANY menu that can change the track
gets the GLOBAL Ctrl+Z; menu-local undo is for cosmetic/settings
menus only. Root causes found:

1. TWO HISTORIES FOR ONE EDIT. wireDocSpinner pushed a document
   snapshot AND a menu-local edit; the popup's Ctrl+Z (its scene
   filter → menuUndo, consumed) replayed the spinner with
   menuApplying=true (no doc push) while the document stack kept
   its snapshot — the next main-window Ctrl+Z re-applied a state
   already current ("undo did nothing"), and when the local stack
   was empty (gizmo edits, the lock check) the popup's Ctrl+Z did
   nothing at all. FIX: installDocUndoKeys — the bound undo/redo
   combos route to editor.undo()/redo() (consumed before any
   TextField's own text-undo) — on every shape-affecting window:
   the Track Piece / Bridge properties panel, the strict-seam
   pop-out, the node editor, the speed-control popup, the
   orientation, add-section, element and initial-speed dialogs,
   and the COASTER MENU (heart/gauge/friction shape the track;
   its cosmetic rows — tie spacing, rail color — simply carry no
   Ctrl+Z there any more; the color picker keeps OK/Cancel).
   wireDocSpinner keeps ONE history. installMenuUndoKeys stays
   for the View menu, Snapping, colors and Export.
2. ORPHANED PIECE OBJECTS. Undo/redo REPLACE the TrackPiece
   objects; the panel's lock-shape rows had captured the piece
   at build time, so after any Ctrl+Z the check and the two
   resolve buttons wrote to a dead object. FIX: live resolution
   by index at click time; the row's sync re-reads the flag.
3. STALE LIVE DIALOGS. The node editor's boxes showed pre-undo
   values (and its 0.8 s coalescing could swallow the snapshot
   of the edit right after an undo). FIX: GraphEditor keeps
   docRestoreHooks — every undo/redo restore re-reads the boxes
   (muted) and resets the burst; the strict-seam pop-out (whose
   closures also captured the replaced piece) is REBUILT in place
   at its screen position after a restore.


## D228 (v19.2) — Handle SLOPES in the node editor

The node editor's handle rows carry the handle SLOPE beside the
length: dv/dx in lane units per axis unit (g/s, °/s per s, °/m per
m …). A SMOOTH node (both handles collinear) shows ONE slope box
driving both handles; a broken node one per handle (an edge node
its single handle's). Slope edits keep the length — dv := slope ×
the handle's x-run (BezierBaker.effOutDt/effInDt), which is exactly
the quantity removeBridging/rescaleDvToGaps preserve, so the
existing slope-keeping machinery reads them back unchanged. Live,
undoable, resynced after undo like every other box.


## D229 (v19.2) — Piece labels wear LENGTH; the main ground axes; menu order

Every piece label — the piece selector, Bridge to piece, Attach
floating piece, the export dropdown — says the piece's compiled
LENGTH ("Piece 3 — 187.4 m") instead of its section count
(FvdDesigner.pieceLengthM: its spans' sLen sum). Two EXTRA-THICK
world-fixed lines mark the main X (green) and Z (red) ground axes
through the origin, 5 mm above the grid, outside the camera-
following LOD group. In the ⊕ menu "Add floating section…" now
sits BELOW "Attach floating piece". The wizard's "Orientation
sets the DESIGN frame" hint is gone.


## D230 (v19.2) — MULTI-COASTER FILES

One .fvd holds any number of coasters — add, name, rename, switch,
delete (down to ZERO — an empty document is legal and loads
without exception). Design:

- MODEL: FvdDesigner.coasters (the file) + `coaster` = the ACTIVE
  one; every editor/viewport path already read app.coaster live,
  so switching re-points the field. Coaster.name + Coaster.look
  (the stashed render look of an INACTIVE coaster; the active
  one's look lives in the shell fields and swaps on switch —
  stashActiveLook/applyLook). An empty document keeps `coaster`
  on a zero-piece placeholder (no NPEs) and hasCoaster() gates:
  rebuildTrack clears the meshes, the GraphEditor covers itself
  with a "No coaster in this file — New coaster…" panel, sections()
  returns an empty list, redraw/refreshMarkers/sync* return, keys
  allow only undo/redo/file ops/view digits.
- RENDER: inactive coasters render in their own SOLID rail color
  (whatever their rail mode — user spec), own heart/gauge/tie
  spacing, never desaturated, never picked (Viewport3D.
  buildOtherCoasters swaps the shell's look fields around the
  existing builders; compiles are cached per coaster until it is
  next active — an inactive coaster cannot change).
- UNDO: DocState = every coaster (CoasterState: name, pieces,
  heart/gauge/v0/friction, look) + the active index + the wizard
  record. Restore reuses coaster objects by index (their looks
  stay — cosmetics stay outside undo per D51) and re-creates the
  ones an undone delete brings back with the snapshot's look.
- IO: ONE coaster still writes FVDD3 (plus a harmless `name`
  line — every older reader ignores it); any other count writes
  FVDD4: `ncoasters N`, the camera, then `coaster <name>` blocks
  each holding the exact FVDD3 body. The loader takes both; a v4
  file may hold zero coasters; a v3 file with no track still
  refuses; sectionless coasters drop fail-soft. LoadResult now
  carries lists; coaster()/cosmetics() keep the legacy shape.
- UI: the COASTER MENU's top block lists the coasters (✓ =
  active; click switches), then New coaster… (the start screen),
  Rename coaster…, Delete coaster, separator. Export grows a
  Coaster dropdown when there is more than one (an inactive one
  exports from its cached compile; the piece list follows). The
  window title wears the active coaster's name in multi-coaster
  documents. Gate: MultiCoasterGate (15).


## D231 (v19.2) — The start screen reworked: one form, wizard state IN the document

His feedback on D226: (1) the type pick + settings on ONE form
(the two SECTION TYPE tiles under a "Section Type" header at the
FAR RIGHT — Placement, then Speed & friction (+ the coaster's
NAME), then Section — the type's options appearing beneath the
tiles once picked; confirming without a type shows "Select the
Section Type First" and FLASHES the tiles); (2) the panel is
LOCAL to what it builds — select another piece/coaster and the
normal graphs show; return and the panel is as you left it; (3)
the graph editor never shrinks below the panel's comfortable
size while it shows (grows to it at once); (4) FULL undo — every
setting change one step, and Ctrl+Z right after opening removes
the piece/coaster.

The model that gives all four: the wizard's target EXISTS from
the moment it opens — a coaster (with its first piece) or a
floating piece, holding a placeholder 5 s / 1 g section and
flagged PENDING (renders nothing — rails, ties, ball, markers)
until the type is picked; the WizardState {mode, coaster, piece,
picked, sc} RIDES THE DOCUMENT (DocState.wizard), and every panel
edit is a document undo step through FvdDesigner.wizard*
(place, speed, friction, name, type pick, option change, Cancel).
The panel is a VIEW: StartPanel.syncFromModel re-reads the target
after every restore; showing is decided by syncWizardPanel from
the document + the SESSION flag wizardOpen (false once Done or
Cancel was pressed — later undos restore values with the panel
closed, EXCEPT when a restore lands on a still-pending state: the
panel returns, an invisible piece with no way to pick would be a
trap) + the selection (its coaster active; for a floating piece
its piece active). One wizard at a time: opening another brings
the open one back instead. File > New = an EMPTY document + the
first coaster's wizard (Cancel leaves the file empty). The
editor pins its min height to StartPanel.MIN_HEIGHT while the
panel shows (FvdDesigner.growEditorTo moves the divider).
Placement yaw/pitch/roll write BOTH tiers (design frame, no lock
conflict). Dev hook -Dfvd.devshot renders pick / lane / s-c.


## D232 (v19.3) — Batch 17: wizard polish, Coaster menu restructure, seam UX, MAKE SMOOTHING REAL, s/c entry ease, axis-shimmer fix

1. START SCREEN polish (user spec): tile descriptions and the s/c
   note parenthetical are gone; "Force/Geometric" (renamed, now
   FIRST) is SELECTED BY DEFAULT — the placeholder 5 s force
   section IS the pick, so the track is visible from open and the
   type-error flash machinery died a round after it was born; the
   lane options sit in a TWO-COLUMN grid (Type+Yaw/pitch axis,
   Domain+Roll axis, Rate units+Roll behavior — natural-width
   labels, never-ellipsizing radios); MIN_HEIGHT 300 → 240.
2. COASTER MENU restructured (user spec): a coaster SELECTOR
   dropdown at the top, then "Coaster Options…" (a modeless dialog:
   rename + the two friction rows + the statistics block),
   "New Coaster…", "Delete Coaster" (a CONFIRM dialog naming the
   coaster), separator, "Rail Settings…" (a modeless dialog: the
   rail-color radios + swatch + G-Force Colors… plus heartline,
   gauge and tie spacing — the future home of track TYPE). The
   settings ROWS survive as shell fields and re-parent per open;
   Dialogs.railSettingsContent() replaced railColorItems (built
   once — the radios stay the app's rail-mode sync targets).
   Everything left-aligned; both dialogs take installDocUndoKeys
   (D227 — friction/heart/gauge/rename are document history; the
   cosmetics simply carry no undo, as decided in D227).
3. STRICT SEAM UX: the settings pop-out went DARK (the Track Piece
   panel look; v17.3's white verdict reversed on user spec — locks
   keep their black glyph on the light button face); the smoothing
   DIAMOND's clean click opens the full SEAM MENU (was the
   settings pop-out — his original intent); "Un-strict seam" lost
   its parenthetical.
4. CTRL-HOVER on a curve: the leading time readout is now the Δ
   from the PREVIOUS NODE (the transition an insert would create)
   instead of the absolute axis position (v16.16 reversed).
5. GROUND AXES LOD: the fixed 600 m X/Z quads SHIMMERED far away
   (sub-pixel, the same disease the grid's fade bands solve). They
   are now camera-following 30 m segments alpha-faded by 2D
   distance (±240 m, gone beyond), rebuilt with updateGridLOD's
   10 m quantization — world-anchored at x=0/z=0 as before.
6. S/C ENTRY ROLL EASE (user: "the small smooth transition at the
   start"): element 0's entry-smoothing box is EDITABLE and means
   the SECTION-ENTRY bank ease — a positive value replaces the
   historic max(smoothIn, 2 m) floor (sub-2 m now possible), 0
   keeps the 2 m default so every old file is bitwise (elem 0 was
   uneditable and stored 0). The κ seam stays sharp. scCutClamp
   mirrors the formula. ACCEPTED corner: an old file whose
   element-0 got there by DELETING the original first element and
   carried 0 < smoothIn < 2 sees its entry ease shorten to the
   stored value (was floored to 2) — the stored number now means
   what it says. Gated c1-c3.
7. MAKE SMOOTHING REAL (the batch's core — see the
   Coaster.makeSmoothingReal javadoc for the full mechanics):
   seam menu > "Make smoothing real…" through the density preview.
   The blend window [seam−L/2, seam+L/2] (the compiler's own
   clamps) becomes ONE authored section of the UPSTREAM section's
   type/domain/axes/units, fitted from the SMOOTHED compiled
   samples AT THE TRUE entry speed ("to be real" — also keeps the
   reconcile from ever re-timing it); the neighbor halves are
   exact de Casteljau cuts; the follower re-anchors at the window
   END as a fresh hand-made strict seam (smoothing off), and the
   original seam is gone. THE BUG THE GATE CAUGHT: anchoring the
   follower's remainder at the blended WORLD frame re-solved its
   force lanes against gravity from the re-aimed frame (a 6° held
   pitch walked the tail 37 m) — the remainder must generate at
   the DESIGN-chain frame; Span.exitTilt (D224) recovers it
   (baseOrient := tilt⁻¹·world) and the re-seeded held angles
   re-aim the rigid image back onto the world. Whole-track hold:
   5 mm at fine density (gate a4/a5). HIS "IN THEORY" QUESTION —
   can the trailing seam vanish with no change? Not bitwise: W is
   a FIT, its exit misses the original window end by the fit
   residual, and un-stricting re-bends by that miss. The op
   auto-un-stricts only when the joint lands essentially exact
   (< 1e-4 rad / 1e-4 m / 1e-3 m/s) — real fits sit at
   mm/centidegree, so the seam stays in practice; the honest
   answer is the guard seam. Refusals (stats-bar reasons): s/c or
   bridge neighbors, influence, legacy-reversed, speed controls on
   either section, a stall inside the window (< 0.5 m/s — the
   re-time is ill-defined), a window too small to have engaged.
   RELATED (user observation): strict-seam smoothing can no longer
   be ENABLED on the edge of an s/c section (conflicting eases; no
   lane form for the window) — old files with it keep compiling
   and can still disable. Gate: SmoothRealGate (11).


## D233 (v19.4) — Batch 18: seam-UX polish round, quintic s/c roll eases, entry ease to ZERO + its diamond, hover interpolation, window-scoped make-real refusals

1. MAKE SMOOTHING REAL refusals are WINDOW-SCOPED (user spec):
   speed controls/marks on the seam sections are fine as long as
   none touches the blend window — outside-the-window controls
   ride onto the kept halves (carrySpeedCtls; a control INSIDE
   the window still refuses with a reason). Influence on a DIRECT
   seam section still refuses — the window always cuts into both
   seam sections and an influenced section cannot be cut (D98);
   influence anywhere else never blocked. Gate b2/b3.
2. THE ALIGNED-OUTLINE BUTTON PATTERN (user screenshot: the seam
   padlocks'' red outline sat crooked). A border over JavaFX''s
   DEFAULT button background never lines up — the default is
   multi-layer with unequal insets and its own radii. THE RULE
   from now on, captured as FvdDesigner.outlineStyle(extra,
   borderColor): ONE background layer, -fx-background-insets: 0,
   and the SAME radius on background and border. The seam
   padlocks use it; the audit found no other bordered button (the
   bridge/placement padlocks tint by background, the 🔗 link is
   background-only per D219, the toolbar checks are custom
   regions).
3. THE COASTER SELECTOR is a SUBMENU (fix: a ChoiceBox inside a
   menu popup never opened — the menu''s focus/auto-hide grab eats
   the press). "Coaster:  <name>" ▸ one item per coaster.
4. Wizard: title "New Track Piece" / "New Coaster"; the options
   grid puts labels and radio pairs in SEPARATE GridPane cells,
   so every row''s first radio dot lines up per column (optLbl/
   optPair; the D232 HBox cells ragged them).
5. HOVER INTERPOLATION (user report: the resulting/derived hover
   dot stuck to piecewise nearest-sample heights): interpAtAxis
   lerps any per-sample quantity between the bracketing samples
   at the cursor''s axis coordinate — the Resulting-overlay hover
   and the s/c-bridge-influence derived hover ride it; the dot
   follows the drawn curve.
6. QUINTIC S/C ROLL EASES (user report: "a jolt on either end of
   the smoothing" — the sine ease''s nonzero end curvature kinks
   the roll-RATE graph): the GLOBAL bank ease and the RATE-roll
   component of boundary blends now use the quintic smoothstep
   (6u⁵−15u⁴+10u³ — rate leaves and lands tangent to zero, the
   strict-seam smoothing''s own weight; ScIntegrator.quintic).
   CURVATURE blends (pitch/yaw) keep the historic sine — "the
   pitch/yaw is okay" — so roll-less sections stay bitwise; roll
   transients on old files change shape (accepted, his no-
   attachment note). Gated: the compiled bank IS the quintic law
   per sample to 0.000°.
7. ENTRY EASE TO ZERO (user spec, walking back D232''s 0=default):
   element 0''s smoothIn is VERBATIM — 0 = an instant bank snap at
   the section entry. Old files (stored 0) now snap instead of
   easing 2 m — accepted per his "not attached to old files"; NEW
   sections create element 0 with an explicit 2 m (the historic
   comfortable look). scCutClamp mirrors.
8. THE ENTRY-EASE DIAMOND: the s/c strip draws the section-entry
   boundary''s own diamond + shaded band [0, ease] — drag sets the
   ease ABSOLUTELY (cursor arc into the section; Ctrl snaps by
   the element step; < 0.2 m snaps to 0; capped at element 0''s
   length; live "entry ease X m" readout; hitScDiamond index 0,
   one undo per gesture — the exact internal-diamond surface).

## D234 (v19.5) — Batch 19: the Coaster menu root cause (+ ChoiceBox restored), influence splits, window-scoped influence in make-real, entry-ease band edge, resolve renames

1. THE COASTER MENU BUG'S REAL ROOT CAUSE (user: "the menu popup
   itself does not appear when pressing Coaster" — D233's submenu
   fix rested on a MISDIAGNOSIS): a JavaFX Menu with ZERO items
   never opens its popup at all — the exact trick the Help menu
   exploits deliberately — and the D232 restructure populated the
   menu only in onShown, which never fires on a menu that never
   opens. Chicken-and-egg: empty → never shows → never populates.
   Fix: populate at construction + rebuild in onShowing (fires
   BEFORE display, list always fresh). The D233 "a ChoiceBox
   inside a menu popup never opens" lesson is FALSE and expunged.
2. THE CHOICEBOX SELECTOR IS BACK (user spec): "Coaster: [pick]"
   as a CustomMenuItem row, hideOnClick(false); picking hides the
   menu and switches. VERIFIED END TO END with a JavaFX Robot
   (-Dfvd.devmenu=1, the new dev hook): real click on the menu-bar
   button opens the popup, real click on the ChoiceBox opens ITS
   dropdown (a child popup of the menu's window — no focus-grab
   problem exists), DOWN/ENTER picks the second coaster and
   switches, menu intact throughout. Robot caveat found on the
   way: Menu.getStyleableNode() is null for MenuBar menus — find
   the MenuBarButton via a ".menu-button" scene lookup instead.
3. INFLUENCED SECTIONS SPLIT (user re-spec lifting the D98
   refusal, which dated from when deltas had to start at zero):
   splitSectionAt cuts the delta lanes at the same coordinate as
   the authored lanes (legit under the D102 mirrored pair — the
   delta axis t* matches the authored clock to the trapezoid's
   µs; DISTANCE axes are identical outright; Coaster.
   carryInfluence), both halves come out influenced with mirrored
   pairs and carried axis flags; "Split section here" and "Split
   track piece here" both work on the design surface (the delta
   surface keeps its redirect message). LEGACY divergent pairs
   (vAuthored != vDesign, hand-edited files only) refuse — their
   delta axis is a genuinely different clock. ACCEPTED CORNER
   (InflSplitGate c2/c4): with non-zero deltas BEFORE the cut the
   right half's base re-derives from the cut's RESULTING frame
   (the influence contract — ref runs from the section's own
   entry), so the tail re-solves by roughly tail-length × the
   prefix deltas' integrated rotation: ~1.8 m for a 1 °/s nudge,
   ~11.8 m for a violent 6 °/s bump over ~170 m of tail. Deltas
   zero up to the cut split in the normal seam class (gated
   0.0003 m); the D71 piece split on top adds exactly nothing.
4. MAKE SMOOTHING REAL takes INFLUENCE window-scoped (user spec —
   the D233 speed-control precedent): influenced seam sections are
   fine as long as the DELTAS are zero across the blend window
   (deltasZeroOn: polyline rows in range + held tails + a uniform
   exact sweep) — the kept halves STAY influenced (carryInfluence
   at the cuts); non-zero deltas inside the window refuse with a
   reason, and legacy divergent pairs refuse outright. Gates
   b4-b6.
5. THE ENTRY-EASE BAND EDGE DRAGS (user spec — the strict-seam
   band pattern): scEdgeHit returns 0 for the entry band's far
   edge (at s = ease), routing into the same absolute drag as the
   entry diamond; H_RESIZE cursor included.
6. RESOLVE-OP RENAMES (user spec): "Make graphs real (keep
   shape)…" → "Convert Real to Design (keep track shape)…";
   "Set Design Speed to Real (keep graphs, reshape)" → "Set
   Design to Real (keep graphs, reshape)"; the initial-speed
   dialog's button renames to match ("Set Design to Real" — the
   D162 verbatim-match rule), the Add dialog's short "Set to
   Real" stays.

## D235 (v19.6) — Batch 20: the EXACT influence split (anchor), influence + speed controls, area-match ringing fix, delete-first re-anchor, seam-lock rework, typed-text/key isolation, capture-hide windows, the gray-out rule

1. THE INFLUENCE SPLIT IS EXACT (user file "Split Influence not
   lining up": the D234 split left a resulting-value step at the
   seam and moved the tail — the documented proportional corner,
   now CLOSED). Root cause: the right half's ref/base re-derived
   from the cut's RESULTING frame at the mirrored speed. Fix — the
   SPLIT ANCHOR on LaneSection: inflRefTilt (the parent authored
   chain's frame at the cut, relative to the resulting frame,
   composed onto the entry for the REF integration), vAuthored :=
   the parent ref profile's speed at the cut (the D102 mirror skips
   anchored sections), inflVStarK := the parent operating profile's
   speed as a ratio of vDesign. splitSectionAt maps the cut through
   TrackCompiler.refProfile — the compiler's own ref+profile
   arithmetic factored out — locating the boundary at operating
   time t*(xc) (pass 2 consumes rows by ITS clock; the first
   attempt anchored at ref arc and left 2.3 m), and cuts the delta
   lanes at exactly t*(xc). Violent-prefix fixture: 11.8 m to
   0.0002 m; resulting felt continuous (0.011 g); divergent LEGACY
   pairs split correctly now too (the refusal dropped — the map
   handles their re-timed axis; ~7 cm dur-rounding class at
   +5 m/s). IO: `infl` fields 4-8 = the anchor quat + K
   (Double.toString, loaded WITHOUT re-normalize — a divide by
   0.999… would flip compile bits); undo SecState carries both.
   discardInfluence keeps an anchored half's OPERATING speed (its
   vAuthored is no return target). makeSmoothingReal still refuses
   divergent pairs (anchored halves included) — its cuts don't run
   the map.
2. B ON AN INFLUENCED SECTION'S DESIGN SURFACE LINES UP THE TRUE
   CURVES (user report: it aligned the AUTHORED curve with the
   neighbor's true curve — resulting ended up offset by the edge
   delta): applyBoundaryMatch gains a CORRECTION branch — authored
   edge += (neighbor target − what our resulting curve currently
   does at the seam, read by ownEdgeState: the neighbor-tail recipe
   pointed inward, per-meter wrapper included). Re-press converges
   (the standing pattern). The delta surface keeps D209.
3. INFLUENCED SECTIONS TAKE SPEED CONTROLS (user ask; the D160
   exclusion existed because the two-pass IGNORED controls = a
   silent lift-drop on entry): speedControllable() drops
   !influenced(); the two-pass now drives its REF, its operating
   profile (drive+snap per step — the passD twins, x on the
   operating clock) and PASS 2 with the same Ctl. Gated: a 12 m/s
   clamp inside a live-delta section drives the profile to 12.00.
4. CONVERTS ALREADY CARRIED SPEED CONTROLS (the user's "can't
   convert with a control" was a MISATTRIBUTION): every convert
   path has carried controls since v18.0 — type flips, domain
   flips (x0/len + complex-graph nodes remapped through the
   section map), partial cuts, make-real (CtlConvProbe verifies).
   What he actually hit: reversePiece's control refusal surfacing
   as the density preview's "too short to re-fit" — the UI now
   PRE-CHECKS with the honest reason and the panel's Reverse
   button grays with a hover explanation. The influence-bake also
   kept resetting domain/axis flags to TIME/local (the 2-arg
   convertSection) — it passes the section's own flags now.
5. AREA-MATCH RINGING (user file "Oscillations in baking
   influence" + screenshot; reproduced: ±2 g alternating nodes on
   ANY sharply-stepped data at coarse uniform spacing, influence
   or not): matchSegmentAreas' best-state metric (peak cumulative
   area error) PREFERS an alternating solution wherever segments
   cannot hold a step's area — the ringing state genuinely wins
   that metric. Fix: the POINTWISE CURVE GUARD — a pass's state
   may only ship while its baked curve stays within 1.3× the raw
   fit's own pointwise miss (+2% of range). His bake: 24 flips to
   0. The trade: coarse 2.0-spacing domain round-trips re-baseline
   0.02 to ~3.2 m (the same mechanism was buying that; nobody
   converts at 2.0 — D104's "converges with density" story), FINE
   fits improved 0.24 to 0.046 m (gate 40d re-derived).
6. DELETE-FIRST-SECTION RE-ANCHOR (user spec): deleting a piece's
   first section re-anchors the piece at the old section-0/1
   boundary via Coaster.reanchorAtBoundary — the splitPiece pose
   recipe (canonical pair, D224 tilt handoff) written onto the
   existing piece — so the remaining track NEVER moves: BITWISE
   plain, 1e-13 tilted (ReanchorGate). The ride entry's v0 follows
   the new first section's design speed (spinner re-synced without
   a history push). Strict-seam FOLLOWERS skip (their entry is
   glued to the seam — the remaining sections re-chain there, the
   only geometry-consistent reading).
7. STRICT-SEAM LOCK REWORK (user spec): (a) EVERY seam creation
   locks ALL THREE axes (makeSeamStrict, bridge-convert strict,
   make-real reseed — seeds are the current angles, nothing moves;
   Phase3Gate 49d re-baselined: strict-vs-classic divergence now
   includes the heading lever, the un-strict identity unchanged);
   direct attach stays yaw-unlocked (the one exception), the
   zero-distance-bridge YES keeps carrying the bridge's locks.
   (b) LOCKING an axis ALWAYS holds the CURRENT angle — the D145
   first-open creation-angle snap is REMOVED (sSeenSettings
   deleted). (c) ↺ returns the value from WHEN THE WINDOW WAS
   OPENED; an axis unlocked at open (locked during the session)
   falls back to the STORED placement's angle — for a direct
   attach that is the pre-attach orientation, the user's exact
   ask. (d) The pop-out is "Strict seam orientation…" now, and it
   HIDES on app focus loss + returns on refocus (the pv pattern's
   third copy — it used to float over other applications).
8. TYPED TEXT NEVER FIRES ACTIONS (user report: a space typed into
   the rename box toggled POV): handleKey and forwardViewKey both
   return early while a TextInputControl holds focus and no
   shortcut modifier is down — bare keys type, Ctrl+combos still
   act. The wizard's key gate also allows DELETE now (its
   placeholder piece is live track — the user's "couldn't delete
   speed controls" was the wizard eating the key), and
   spDeleteSelected deletes a selected control even when a stray
   node index is live (stripHasNodeSel routed real node deletions
   earlier anyway).
9. CAPTURE-HIDE (user report: a modeless dialog under the warped
   hidden fly cursor stole the mouse stream — the view froze and
   the cursor popped onto the window): while any pane owns the
   mouse (fly / captured POV), every open tool window hides
   (FvdDesigner.syncCaptureWindows — owned Stages + non-autohide
   Popups, positions kept); release brings them back. External
   applications stay untouched (his explicit carve-out).
10. EXIT GATE: closing the app runs the same Unsaved-changes
   dialog as New/Load (Save / Don't Save close, Cancel keeps it
   open).
11. THE GRAY-OUT RULE (user spec, STANDING): anything prevented
   must be GRAYED with a short reason — 6 words ideally, 10 max —
   menu items carry a "(reason)" parenthetical (tooltips can't
   ride them), controls take GraphEditor.grayHint (new Node-row +
   live-Supplier overloads). Swept: Make smoothing real is
   PRE-CHECKED at menu build (makeSmoothingRealWhy dry-run + the
   shortWhy compressor), silent-false ops post stats reasons
   (un-strict, detach, make-strict, refreeze, s/c make-real,
   piece split), plus-menu items/delete-section/delete-element/
   view-rates/influence items wear parentheticals, bridge-panel
   rows + mode radio + decel-linked + lane buttons + Resulting
   roll + seam spinners + comb dots wear grayHint tooltips.
   Never ship a click that silently does nothing.
12. Smaller (user list): resulting smoothing graphs draw across
   the WHOLE window when EITHER seam neighbor is active
   (drawTrueStrokes collects both sides' spans; matching units
   required on the far side); "Resolve speed conflict" renamed
   "Resolve conflict"; wizard button "Create Coaster"/"Create
   Track Piece"; a separator between Coaster Options… and New
   Coaster….

## D236 (v19.7) — Batch 21: the ONE-PASS HYBRID influence model, ctl-aware convert re-time, the ridge area solve, menu tooltips, wizard polish, coaster-switch click

Date: 2026-08-25. His batch-21 list (14 items) with two attached
files ("influence bake and conversion not working well with speed
section 8.25.26.fvd", "Odd influence behavior 8.25.26.fvd"). No
commit requested — awaiting his hand-test.

1. THE ONE-PASS HYBRID INFLUENCE MODEL (his re-spec: "at any
   point, if the original graphs result in a certain resulting
   value, the influence adjusts that value that much, and then
   naturally when the influence graphs return to zero everything
   behaves as it should be according to the original graphs.
   There should not be any speed/orientation conflict once
   influence is back to zero" — plus "the corner didn't quite
   make sense to me conceptually: why there was a 'frame'
   specific to the influence section"). His file 2 showed the
   symptom: after a Δyaw bump returned to zero, RESIDUAL LATERALS
   persisted for the rest of the section. ROOT CAUSE = the v15.0
   TWO-PASS ARCHITECTURE itself: the ref pass integrated the
   authored lanes in their own frame, the base re-expressed those
   rates along the DELTA-BENT track — so after the delta window
   the base replayed the ref's rates in whatever frame the deltas
   had left the track in, and a force section's re-solve against
   gravity no longer matched its own authored targets (phantom
   laterals, leftover conflicts, and the D235 split ANCHOR
   existed only to pin down which ref frame a split half should
   continue). REPLACED WHOLESALE: influence is now ONE
   integration of the section's OWN mode with the delta lanes
   injected PER RK4 STAGE at the current state —
   - a FORCE section's Δpitch/Δyaw (geo rates) join Ω inside
     FVDIntegrator.deriv (GeoIntegrator.inflOmega — local body
     axes or global heading/elevation per inflYawPitchGlobal;
     injected BEFORE the roll term so global-roll feed-forward
     sees the whole turn; °/m sections scale by v);
   - a GEO section's ΔGv/ΔGl (felt) become Δω = ±g·ΔG/v inside
     GeoIntegrator.deriv;
   - the felt/rate RECORDS add the deltas' contribution
     analytically (force: gv += dk.Gv() etc.; geo: the −Ω·axis
     identity), so resulting == base + delta EXACTLY by
     linearity, and baseCurves := resulting − delta on the
     section's OWN axis (no re-timed t* clock exists — the delta
     axis IS the authoring axis).
   Where the deltas are zero the solve IS the plain one: felt ==
   the authored targets bitwise (his file 2 probes 0.0 over all
   554 zero-delta samples — OddInflProbe), no residual laterals,
   no leftover ≠v (the reconcile sees the natural continuation).
   CONSEQUENCES, all deliberate: vAuthored is INERT (a mirrored
   file-compat field — a hand-edited divergent pair now compiles
   at vDesign like a plain section, gated bitwise in Phase2Gate
   influence(b)/DomainGate 45b); the operating-speed-invariance
   property of the two-pass is GONE (an influenced force section's
   shape depends on vDesign exactly like a normal force section —
   "behave exactly like a normal section, just with nudges",
   D102); SPLITS ARE EXACT BY THE MARKOV PROPERTY ALONE — the
   D235 split anchor (LaneSection.inflRefTilt/inflVStarK,
   TrackCompiler.refProfile, infl-line fields 4-8) is DELETED,
   splitSectionAt cuts influenced sections like plain ones
   (native-axis cut + carryInfluence), and the split residual
   improved from D235's 0.0002 m to the MACHINE class (1.4e-12 m,
   InflSplitGate a-e); anchored files from the one v19.6 build
   load with fields 4-8 ignored and self-heal via the reconcile;
   makeSmoothingReal's divergent-pair refusal is gone (the window
   check deltasZeroOn remains). The all-zero-deltas fast path
   compiles BITWISE == uninfluenced (gate z1); speed controls
   ride the single solve (gate g). InflSplitGate rewritten (22
   checks incl. the new h/z acceptance series).

2. CONVERTS/BAKES WITH A SPEED CONTROL ON THE SOURCE (his file 1:
   bake-influence moved the track 10.9 m): convertToLanes' re-time
   march was ENERGY-ONLY — blind to the source's speed controls
   (his 29 m/s launch drive vs ~17 energy → (v/vG)² ≈ 2.9 felt
   distortion + the clock stretch). The fit's march now takes the
   SOURCE section's Ctl (drive + snap per sample, the passD
   twins) keyed by the source-native coordinate — new widest
   convertToLanes overload (srcCtl, srcOff, srcDist) + the
   fitLanes(…, Section src, Span sp) instance wrapper; call
   sites: convertDomain, bakeAxes, partialConvertSection,
   convertWholeSection, makeGraphsReal. File 1 case A pathDev
   10.864 → 0.011 m (CtlBakeProbe).

3. THE RIDGE AREA SOLVE (his remaining bake oscillations at 0.11
   spacing/non-optimal placement): matchSegmentAreas' EXACT
   per-segment area constraints DEMAND node-to-node alternation
   on data with features finer than a segment (measured −0.93
   lag-1 autocorrelation of node deviations — and the
   positionally-good coarse solutions rang identically, so no
   discriminator exists; the D235 pointwise guard capped the
   amplitude at ±0.19 g but kept the mode). The exact least-norm
   Thomas solve is replaced by RIDGE least-squares:
   min ‖A·δv − E‖² + μ‖δv‖² + λ‖D₁·δv‖² (λ = 1.0, μ = 1e-4 of
   the mean c² scale) — the first-difference penalty suppresses
   exactly the alternating mode; AᵀA + λD₁ᵀD₁ + μI stays
   tridiagonal SPD (same O(N) Thomas). Damping/best-state/curve
   guard unchanged. File 1 case B: 20 flips ±0.19 g → 0 flips
   0.041 g; 40d/49d and the whole battery green unchanged.

4. WIZARD LOCKOUTS: the Track Piece toolbar dropdown GRAYS while
   the start panel shows (grayHint "Placement is edited in the
   start screen…"; showPlacementPopup guards ALL routes — ball
   double-clicks included — with a stats reason), and Coaster
   Options… grays during ITS coaster's NEW_COASTER wizard
   ("Finish the start screen first.").

5. GIZMO → WIZARD LIVE SYNC: FvdDesigner.placementEdit (the choke
   every viewport placement write runs through) now ends with
   wizardPanel.syncFromModel() — dragging the entry gizmo/ball
   updates the wizard's Placement spinners live (muted, no
   feedback).

6. CTRL+Z REOPENS THE WIZARD (user spec): applyDocState sets
   wizardOpen = (restored wizard != null) — undo right after
   Create Coaster/Track Piece brings the panel back with its
   picks (wizardFinish snapshots BEFORE clearing); redo past the
   finish closes it again. (Replaces the D231 "session flag stays
   closed unless un-picked" rule.)

7. COASTER-SWITCH CLICK (user spec): a clean 3D click that misses
   the active coaster now runs the SAME screen-space pick over
   every inactive coaster's cached compile (Viewport3D
   pickSampleIn — pickTrackSample's math list-generic — over
   otherCoasterRenders). A hit opens a small dark popup at the
   click: `Switch to editing "<name>"?` [Switch] [Cancel] —
   Switch = switchCoaster + activateAt(hit.t()) (the clicked
   piece + section selected); Cancel/click-off leaves everything
   put. Inactive meshes stay mouse-transparent — no mesh picking.

8. CAPTURE-HIDE KEEPS STAGE BOUNDS: syncCaptureWindows stores
   x/y/width/height for owned Stages and re-applies them before
   show() (a re-shown Stage otherwise re-centers/re-sizes to its
   scene) — hidden tool windows return at the same place AND
   size. Popups already re-anchored exactly (content-sized).

9. WIZARD "INITIAL SIZE" SPINNER (user spec): a new row under the
   Section Type options — the first section's length in the
   surface's own units ("Initial size (s)"/"(m)"), applied live
   as one undo step. PER-DOMAIN MEMORY rides the WizardState
   record (lenTime/lenDist, s/c shares the distance slot): a 6 s
   edit survives a Time→Distance→Time round-trip
   (wizardWithLen folds every built section's length back into
   the record; buildSection reads the TARGET domain's memory on a
   flip and the live spinner on a size edit).

10. MENU-ITEM GRAY REASONS RIDE HOVER TOOLTIPS (user spec,
   program-wide — supersedes D235's "(reason)" parentheticals
   and retires the shortWhy ≤6-word compressor): a disabled
   MenuItem swallows hover, so GraphEditor.grayItem builds the
   grayed row as an ENABLED CustomMenuItem holding a gray Label
   with an installed Tooltip (300 ms delay, hideOnClick false —
   clicks do nothing and never close the menu). Converted: Make
   smoothing real (the FULL makeSmoothingRealWhy sentence is the
   tooltip now), Enable smoothing on s/c edges, Connect sections,
   the '+' menu's bridge-blocked adds + Bridge to piece + Attach
   floating piece (the "Piece ends with a bridge" hint row is
   folded into the tooltips), Delete section/element, Bridge
   properties, View rates, the influence enter/edit/bake items,
   Convert…, and the Coaster menu's Options/Delete. Always-
   disabled info rows whose LABEL is the reason stay as-is.

11. Rail Settings… sits DIRECTLY under Coaster Options… in the
   Coaster menu (was below the New/Delete block).

12. SMOOTHED-WINDOW HOVER SHOWS THE BLENDED VALUE (user spec):
   trueSuffix's split test compared derived-vs-true, and inside a
   smoothing window BOTH read the compiled blend — the " (true …)"
   suffix never showed without a ≠v. The reference is now the
   AUTHORED lane inside a smoothed strict seam's window (the
   drawTrueStrokes recipe, factored into smoothedWindowParts and
   shared), so hovering the window shows the real/blended value
   whenever it visibly differs (TRUE_SPLIT_PX = the "negligible"
   threshold).

## D237 (v19.8) — Batch 22: the unresolvable-conflict root cause, ABSOLUTE YAW/PITCH, Mirror piece, Switch Coaster, global spinner steps, visible capture windows, seam-diamond UX

Date: 2026-08-25, right on top of D236 (both uncommitted until now —
committed together at his "please commit"). His 15-item batch with
the "Conflict not getting resolved 8.25.26.fvd" file.

1. THE PHANTOM UNRESOLVABLE ≠v (his file; "conceptually there
   should not be a speed conflict… the fix should be about whatever
   is causing it"): a strict follower's FIRST section carried a
   stale design speed 0.155 m/s off the arriving energy — UNDER
   DRIFT_EPS 0.2, so no badge anywhere at the seam — but a seam gap
   is a constant ENERGY offset (~2·v·dv), and at the next section's
   10 m/s crest it reads 0.357 m/s: the badge lit on section B "out
   of the blue". Both resolve ops DID anchor B to true — and the
   reconcile's REAL-ANCHOR recognition gate (Coaster
   reconcileChainedSpeeds) tested the SEAM gap against the same 0.2
   constant, refused the anchor, and snapped B back onto the design
   chain every rebuild. Two tests measuring different quantities
   with one constant; every entry gap in (0.2/amplification, 0.2)
   was a badge no resolve could clear. FIX: the anchor gate is
   AMPLIFICATION-AWARE — the seam gap scales by vTrue/vMin over the
   section's own span (the same quantity the D106 whole-span badge
   measures); flat sections reduce to the historic test bitwise.
   SWEEP (the same mechanism elsewhere): GraphEditor
   refreezeSeamSpeed's twin gate got the same fix; its [1,100]
   clamp violated the D198 admit rule (a near-stall refreeze wrote
   1.0) → [0.1, 1e4]; the B-matcher's real-split test gained the
   whole-span fallback (sectionDrifted on either side); and
   makeGraphsReal/refreezeSpeed now anchor at the SEAM sample the
   reconcile itself reads (they read one dt inside — resolves
   moved by mm/s dust each rebuild; now a BITWISE fixed point).
   ConflictGate (8, the file embedded verbatim): resolves stick
   (0.357 → 0.00002, bit-stable), source-refreeze clears the chain.

2. ABSOLUTE YAW/PITCH (the "major addition" — the rollAbsolute
   twin for geo lanes 0/1 under GLOBAL yaw/pitch; ypAbsolute,
   section-line field 10, missing = rate): lane 0 IS the ELEVATION
   angle (°), lane 1 the HEADING (°, reported-left, unwrapped past
   ±180; SECTION-ENTRY-RELATIVE — every section generates with its
   entry heading stripped per D199, so 0 = straight on; elevation
   is a world angle and authors within ±90 — a loop needs rate
   lanes, the dialogs say so). Machinery mirrored off the whole
   D118–D122 playbook up front (every trap pre-fixed, none waited
   for a report):
   - FVDIntegrator.angleHermite = bankHermite slot-generalized
     (bitwise for roll) + headingOfT/elevationOfT (bankOfTU's
     twins, NaN heading on vertical);
   - GeoIntegrator.projectYp — elevation about the horizontal-
     right axis FIRST, heading about world-Y second (a world-Y
     rotation preserves the just-set elevation EXACTLY), wrapped-
     difference correction, vertical hold-off, run BEFORE the roll
     blocks so the bank contract still lands exactly; per-stage
     slope feed-forward through omegaFor's new absTheta/absPsi
     (°/m-style ×v in the distance domain); the felt records read
     the SLOPES, never the angles (the "50° read as 50°/s" trap);
   - TrackCompiler.exactYp (the D121 knot-exact resample — the
     staircase pre-fixed: heading-rate steps gate at 0.33 °/s);
   - Coaster convert tower: toYpAbs through every overload —
     extraction = elevation + entry-relative UNWRAPPED heading
     (vertical holds the last defined, the lastBank pattern), the
     °/m division EXEMPTS the angle lanes (the D213 trap — gated
     under °/m day one), area-match skips them, the roll-only
     preserve requires matching ypAbs;
   - SPLITS REBASE the heading lane at the cut (−value_at_cut; the
     first gate run caught 44 m of re-aim before this) with
     mergeSections carrying the exact inverse lift — round-trips
     lossless; partialConvert's kept-right half and
     makeSmoothingReal's window-right half rebase the same way;
     residual = the absolute lanes' one-sided Hermite class at a
     cut (~1e-4 m, == authored-as-two, the D118 acceptance);
   - editor: laneIsRate/laneRatePM/laneUnit/defaults (elev ±90,
     heading ±180)/badge " · abs yaw/pitch"/snap = the roll step
     read as degrees (D122's rule); B on a ypAbs edge = the D120
     recipe (absYpTarget: NEIGHBOR-side sample + worldRates slope
     + entry-heading subtraction + unwrap-to-current; vertical
     refuses with the reason); B TOWARD a ypAbs neighbor refuses
     on every lane (nextAbsYp, the D122 choice); the authored
     fast path requires matching ypAbs (the copy-an-angle-as-a-
     rate guard); influence refuses absolute-angle sections (the
     deltas are rates; compiler + menu gray both);
   - the "Yaw/pitch behavior: Rate | Absolute angles (°)" row sits
     DIRECTLY BELOW "Yaw/pitch axis" (user spec) in the Convert
     dialog, the Add-section dialog and the START WIZARD, double-
     interlocked (Geometric + Global) with the grayHint reason;
     switching Yaw/pitch to Local clears the flag (the D118 rule);
     the '+' hold: elevation holds the current angle, heading
     holds 0 (= continue straight, entry-relative);
   - undo snapshots (SecState.ypAbs — the D89 lesson), FVDD field
     10, byte-identical round-trip. YpAbsGate (15). DELIBERATE
     SCOPE LINE: the Resulting-overlay settings did NOT grow a
     resYpAbs row (comps 1/2 are the shared overlay scale — the
     D214 own-scale problem squared; add on request).

3. MIRROR PIECE ("Track Piece ▾" beside Reverse): flip the piece
   horizontally about the vertical plane through its entry heading
   — same start position/heading/elevation, the bank mirrors. A
   pure ONE-TO-ONE GRAPH NEGATION (Coaster.mirrorPiece, no refit):
   force Gl + the roll lane (rate or absolute bank), geo yaw,
   influence delta lane 1, s/c angle+dirDeg+rollVal, piece roll
   deltas, held seam bank; the orientation conjugates by the
   z-reflection (Quat.mirrorZ, the canonical pair kept exact —
   heading 0 is mirror-invariant). Every integrator arithmetic
   path is sign-symmetric, so the compile is BITWISE the z-negated
   track in the canonical entry frame — MirrorGate (20): force/
   geo/s-c/influenced/tilted-entry all bitwise, mirror² == the
   original bitwise. Pieces ending with a bridge refuse (the
   bridge re-solves against BOTH pieces).

4. SWITCH COASTER… (beside Mirror): move the active piece under
   another coaster with the SAME friction — the compile reads only
   pieces + friction, so the piece stays BITWISE identical (rail
   color/heartline/gauge are the parent's cosmetics). The picker
   grays different-friction coasters with the reason (grayItem);
   bridge-connected pieces gray (trailing bridge / bridged-in);
   strict follows BAKE in place first (detachStrictSeam — nothing
   moves); v0 mirrors both ride entries (the D235 re-anchor rule);
   both compile caches invalidate; the view follows the piece.

5. GLOBAL SPINNER STEPS (every spinner, present and future —
   installed inside numericEditor, the one route all 26
   construction sites take): the WHEEL steps any spinner by its
   increment (always consumed — spinners sit over the plot and in
   menu popups), CTRL steps at ONE TENTH, SHIFT SNAPS to the next
   round multiple of the (possibly Ctrl-scaled) increment — 0.435
   → 0.5 → 0.6. Arrow clicks with modifiers intercept in the
   capture phase (the skin never arms auto-repeat; plain clicks
   keep it); Shift+wheel's Windows deltaX fallback is load-bearing
   (Shift IS the snap modifier). The 1-2-5 snap-step walkers keep
   their series (the wheel drives them directly). The node
   editor's LiveNum boxes adopt the same conventions (its old
   Shift = ×10 wheel gave way to Shift = snap — the D225 mapping
   superseded by the global rule).

6. CAPTURE KEEPS TOOL WINDOWS VISIBLE (superseding D235's hide +
   D236's bounds restore — his "actually make all menus stay
   visible… if possible"): while a pane owns the mouse, every
   non-autohide Popup's and owned Stage's scene hides its cursor
   and FORWARDS mouse moves into the capturing pane's look handler
   (flyLook reads screen coordinates only — a window under the
   warped hidden cursor no longer starves the view, the D235
   freeze), a RIGHT-click over a window releases the capture, and
   presses/scrolls are swallowed. Windows live-update throughout —
   placementEdit syncs the Track Piece rows per gizmo event now
   (docApplying-guarded, the wizard-panel pattern).

7. SEAM DIAMOND ALWAYS VISIBLE (strict seams): hollow while
   smoothing is off — DRAGGING the hollow diamond ENABLES
   smoothing at the dragged length (the enable path is the drag
   itself; s/c edges still refuse, the D232 ban, via the factored
   seamScEdge); hover grows a gray center dot in the hollow
   diamond + small ◀ ▶ carets beside it in BOTH states (drawCaretH,
   the drag affordance); a clean click still opens the seam menu.
   And the CURSOR now respects press priority: a node sitting on a
   smoothing-window edge shows the normal cursor, not H_RESIZE
   (the node outranks the band edge at press time — the hover test
   moved above the cursor decision).

8. SPEED POPUP: a fresh control's target = the ENTER speed to TWO
   decimals (was whole m/s); "Start:" renamed "Enter:" and made
   CLICKABLE — target := enter speed, with a layout-stable hover
   outline (constant transparent border) + the tooltip "Make
   control speed equal to enter speed"; complex SPEED controls
   keep it a plain readout (their graph IS the speed).

9. WIZARD FIXES: (a) the Track Piece button stayed STUCK DISABLED
   after Create (his "intermittent… redo makes it adjustable") —
   wizardFinish/wizardCancel bypassed syncWizardPanel, the one
   place the D236 lockout recomputes; both call it now (the
   adjacent-cause sweep found no other bypass — open/switch/
   applyDocState all called it already). (b) The wizard piece
   creates with shapeLocked=false + OPEN pitch/roll padlocks, so
   the entry gizmo's pitch/roll RINGS build and write BOTH tiers
   (as if Lock Track Shape were off — the panel's own rows'
   parity; local and global rings both route through the same
   unlocked tier writes, no conflict can open); Done restores the
   default locks. PieceState already carried all three flags.

10. ATTACH FLOATING PIECE locks pitch AND roll at the PRE-ATTACH
   angles always (was: seeded from the placement padlocks, D153) —
   yaw stays following. seedStrictValues already reads the
   pre-attach orientation, so a conflict-free layout stays
   conflict-free; only the honest speed split remains (his spec
   verbatim).

11. Smaller: "Influence orientation settings…" sits directly below
   "Edit influence" while editing influence (was up in the
   section-settings block); the toolbar POV button REMOVED
   (redundant with Space + the view dropdown); grayItem rows
   align + size exactly like native MenuItems now (the D236
   padding doubled the container's — measured by menu-snapshot
   probe) and their tooltip fires over the WHOLE highlighted row
   (installed on the .menu-item container via the parent hook).

## D238 (v19.9) — Batch 23: PIECE-RELATIVE absolute heading, per-lane convert preservation, resulting yaw/pitch behavior, abs lane colors/naming, dialog cleanup

Date: 2026-08-25. User feedback batch on D237's absolute yaw/pitch
(colors, resulting overlays, the heading zero, dialog format) plus
two structural convert/UI rules. No commit requested this round.

1. **PIECE-RELATIVE absolute heading** (his headline: "It seems
   that the pitch is a global value but the yaw is not [always sets
   the entrance angle to be zero]. Please make yaw also a global
   value like the pitch and roll."). D237 made the heading lane
   SECTION-entry-relative because every section generates
   heading-stripped (D199). Re-specced: the heading lane's zero is
   now the PIECE's entry heading — continuous across the piece's
   whole section chain, exactly like elevation and bank, and equal
   to the world heading for unrotated pieces. Mechanically the
   canonical generation is untouched: the compile captures
   psiPieceStart at each piece's entry and geoLane shifts the baked
   lane-1 keyframes by the section entry's piece-relative heading
   (psiPieceStart − cp.psiDeg(), threaded through passGRaw and
   influencedLane's abs early-out). The shift is a chain-psi
   DIFFERENCE — pure generation bits — so piece x/y/z + yaw moves
   stay BITWISE-rigid (gated h2) and a piece's first section keeps
   offset exactly 0.0 (single-section pieces bitwise). Slopes are
   untouched by a constant shift, so the feed-forward and felt
   records ride through; ±360k representatives are the same
   orientation (projectYp wraps).
   Consequences swept:
   - The D237 rebases DIE where both halves share a piece:
     splitSectionAt's cut rebase, mergeSections' lift,
     partialConvertSection's kept-right rebase — values carry
     verbatim now (split→connect still lossless).
   - Rebases are BORN where sections change piece frame:
     splitPiece and reanchorAtBoundary shift moved abs heading
     lanes by the boundary's chain-psi delta (rebaseAbsHeading;
     the yaw-delta term of a tilted split's decomposition cancels
     between psi0 and the chain — the shift is the plain
     exitPsi − entryPsi in both branches, gated h3 at 2.8e-14 m
     with the lane re-zeroing at the new piece, h3b).
     makeSmoothingReal's window-end rebase STAYS (B re-anchors
     there) and now applies to EVERY abs section of the follower,
     not just its first (sample-based fallback when the first
     section isn't abs — a multi-turn window may land a 360°
     representative off, same orientation).
   - The convert extraction takes a ypHeadDeg REFERENCE (the
     piece's first compiled sample's heading; NaN = self-relative,
     right for pieces' first sections): convertWholeSection /
     partialConvertSection pass pieceHeadDeg(r, pieceIdx),
     bridge converts the stored-placement flavor
     (pieceHeadDeg(pieceIdx) — no compile in hand; differs from
     measured only by a follower's re-aim tilt, the same
     world-vs-chain corner every global-axis fit has), the
     partial-bridge RIGHT side NaN (it becomes the target piece's
     first section). The entry value seeds at the representative
     nearest the reference — it lands in (−180, 180] of the piece
     frame (gated h4: a mid-piece convert extracts ~40°, not 0).
   - Display (derivedAt/trueAt/sampledPerSec) shows heading MINUS
     the ACTIVE piece's reference, wrapped principal
     (pieceRelHeadingDeg; wrap walls = the abs-bank precedent);
     B's absYpTarget subtracts the same reference; the '+' add
     hold takes the ride end's piece-relative heading (unwrapped
     against a same-language neighbor's end value, else
     principal) instead of D237's flat 0.
   - FVDD note: lane values just MEAN piece-relative now — no
     format change; mid-piece abs sections authored on the
     one-day-old D237 build re-aim (accepted, the feature was
     hours old). Followers re-aimed by held angles keep the
     documented world-vs-chain dust class. (An early draft
     carried attach lanes verbatim; the parity sweep — item 9 —
     replaced that with the world-preserving attach rebase.)

2. **±180 wrapping is CONCRETE** (his question: "would there be
   any issues making it concrete? would it be possible to avoid
   those issues?"). Answer shipped: no issues exist — every ±360k
   representative is the identical orientation and slopes are
   translation-invariant, so a B/creation value wrapped into
   (−180, 180] matches the neighbor exactly even when the two
   sides show 360-apart numbers ("as if the value on the other
   side were translated in the same way", his acceptance).
   absRep() is the one implementation: principal range UNLESS the
   lane's adjacent interior node sits > 180° away (a deliberately
   out-of-range authored lane — a helix — keeps its branch:
   continuity wins). Supersedes D120's unwrap-to-the-edge-node
   for B targets; absRollTarget and absYpTarget share it;
   creation holds wrap the same way.

3. **Per-lane convert preservation** (his theory, verified
   correct): if only the yaw/pitch axis/behavior changes the roll
   lane is not re-fitted; if only rate units and/or the OTHER
   orientation change, absolute lanes are not converted — "a
   conversion would only converge to the original". laneKeepMap
   generalizes D169's roll-only rule: lanes 0/1 keep when
   yaw/pitch axis + behavior are unchanged and a rate-unit flip
   doesn't reach them (force G targets aren't rates; absolute
   angles aren't either — D213/D237); lane 2 keeps symmetrically.
   The ONE correction to the theory: a FULLY IDENTICAL flag tuple
   preserves NOTHING — the identity convert is the deliberate
   re-author-at-density tool and must keep re-fitting everything.
   preserveLanes deep-copies kept lanes; the preview auto-selects
   the first lane that actually re-fits (generalizing the D169
   roll-select). bakeAxes now computes the keep map against the
   fit's ACTUAL targets — which FIXES a pre-existing D237 bug: a
   roll-only bake on an abs-yaw/pitch section used to copy its
   ANGLE lanes verbatim into the rate-flagged output (degrees
   consumed as °/s).

4. **Abs lane colors + naming** (user spec): absolute ELEVATION =
   deep cyan-blue rgb(40,150,200) (darker/bluer than pitch teal),
   absolute HEADING = amber rgb(255,180,25) (more orange than yaw
   yellow, still off roll's orange) — ELEV_ABS_COLOR /
   HEAD_ABS_COLOR, the BANK_COLOR pattern: worn by curves, lane
   buttons, markers AND the resulting overlays (laneColor +
   overlayColor + tintResChecks branches). NAMING rule (standing):
   where the two lanes appear separately they are "Elevation" and
   "Heading" (lane buttons, y-axis title, node-dialog title,
   hover readouts, resulting check labels); "absolute angles"
   remains the collective. laneName also gained the "Bank" branch
   — the abs-bank y-axis used to read "Roll rate (deg/s) (°)".

5. **Resulting yaw/pitch behavior** (resYpAbs, AppSettings): the
   Resulting settings dialog grows a "Yaw/pitch behavior" row
   (Rate | Absolute angles (°)) directly below the axis row,
   double-interlocked on force view + global axis — overlays
   comps 1/2 then show absolute elevation / piece-relative
   heading (degrees, ±90 default scale, own abs colors, hover
   "elevation/heading", axis title "angle (°)"). Their seam
   carets suppress (angles are continuous — the comp-3 abs-bank
   precedent). resRollOwnScale generalizes to "units differ":
   a RATE roll now takes its own scale under absolute comps 1/2
   (the previously unreachable D214 inverse), and two absolute
   behaviors SHARE (all degrees). No redundancy gray exists for
   comps 1/2 (geo view shows felt G — the overlay can never
   duplicate an abs section's own lanes). CLAUDE.md's old "NO
   resYpAbs row" note is reversed by this decision.

6. **Convert dialog cleanup** (user spec: remove the "swirly
   arrows", unify with the Add dialog): all seven per-row ↺
   resets are GONE from the Convert dialog (the only
   section-settings dialog that had them), its third grid column
   with them (colspans trimmed, the scope row's stale
   parenthetical dropped — the grayHint tooltip carries the
   reason); wireOriginal's orange changed-row dot remains the
   "differs from current" indicator. resetBtn deleted. The ↺
   KEEPS its other homes deliberately — they reset to a SETTING,
   not to "current": Snapping/Shortcuts/View sliders, color
   dialogs, Export, and the strict-seam pop-out's
   window-open-revert.

7. **The "Rate" misalignment** (his intermittent report, found):
   the START WIZARD's option grid parked D237's "Yaw/pitch
   behavior" row in the LEFT label+radio columns — the long label
   stretched that column and its "Rate" radio sat out of line.
   The right column now runs the canonical order (Yaw/pitch axis,
   Yaw/pitch behavior, Roll axis, Roll behavior), every label in
   a label column, every pair aligned.

8. **The Resulting-popup alignment bug + the STRUCTURAL fix**
   (his "recurring mistake class" complaint): grayHint returns a
   StackPane, whose default alignment is CENTER — a fill-width
   VBox stretches the wrapper and CENTERS its one child against
   left-aligned siblings (the popup's roll/bank check, the only
   VBox call site; HBox rows size wrappers to content and never
   showed it). ALL THREE grayHint overloads now set
   Pos.CENTER_LEFT on the wrapper — the whole mistake class is
   closed at the factory, not the call site.

9. **The abs-roll vs abs-yp PARITY SWEEP** (his item 7 — an agent
   audit of every touchpoint after the above landed). Fixed:
   - partialConvertSection's KEPT half silently dropped ALL FIVE
     axis/rate/behavior flags (pre-existing since v16.x! — only
     domain copied, unlike splitSectionAt): a partial convert of
     any global/°/m/absolute section read its kept half as local
     °/s rates, violating "the unconverted side keeps its
     original representation EXACTLY". The flags copy now.
   - attachPiece REBASES moved absolute heading lanes into the
     target's frame (the psi delta — WORLD headings preserved,
     exactly how elevation/bank carry through an attach, and the
     exact inverse of splitPiece's rebase so split→attach
     round-trips). Covers "Attach floating piece", un-strict and
     the whole-bridge-convert stitch — a verbatim carry would
     have re-aimed the attached stretch at the target's entry
     heading. (Un-strict's "graphs bit-kept" bends by the
     constant translation for abs heading lanes — shape kept,
     values re-framed honestly.)
   - partialConvertBridge's RIGHT branch re-anchors the target
     piece at the cut — its original sections' abs heading lanes
     rebase by the psi delta now (the reanchor rule; "their own
     shapes untouched" held for everything but abs headings).
   - The cross-piece AUTHORED fast paths (drawInactiveSections'
     same-flag strip drawing + smoothedWindowParts' far-side
     reference) treated piece-relative heading as frame-free —
     a strict-seam neighbor's lane values sit a constant off the
     active frame. Both now require same-piece under an abs-yp
     active surface and fall back to DERIVED (drawn in the
     active frame — curves still join at the seam).
   - The abs-BANK extraction never unwrapped sample to sample
     (pre-existing D118 gap vs the heading extraction): a bank
     crossing ±180 (barrel roll) fitted a lane jumping 360
     between knots that swept back the wrong way. Unwraps now;
     no-crossing tracks bitwise.
   - The '+' hold's abs BANK gained D238's abs-yp parity: a
     same-language neighbor's authored end value copies bitwise,
     and a measured bank unwraps toward the neighbor's branch
     (an over-±180 authored lane keeps it) instead of always
     the principal.
   Deliberate non-findings: no resYp redundancy gray can exist
   (geo view shows felt G); a strict FOLLOWER's delete-first
   keeps its stored frame (entry glued to the seam — values keep
   their meaning, no rebase); snap steps already shared (both
   abs kinds take the roll step); mirrorPiece verified correct
   under piece-relative semantics (pure lane-1 negation IS the
   mirror about the entry heading).

Gates: YpAbsGate grew the h-series (piece-relative continuity h1,
bitwise yaw rigidity h2, splitPiece rebase h3/h3b at 2.8e-14 m,
piece-relative extraction h4/h5) — 22 checks green; full battery
(32) re-run green on wiped classes after the sweep fixes.

## D239 (v19.10) — Batch 24: fixed-slot POV readouts, the grayItem self-measuring pad, part-convert Confirm flow, empty-coaster recovery, tooltip accuracy audit

Date: 2026-08-26. Ten-item polish batch on the v19.8/v19.9 work.
No commit requested.

1. **Heading color re-specced YELLOW-GREEN** (user: "actually make
   the Heading color be a yellow-green") — HEAD_ABS_COLOR
   rgb(180,225,45), between the yaw yellow and the Lat-G green and
   distinct from both; one constant, every surface follows (curves,
   buttons, resulting overlays, check tints).

2. **Node editor (edge nodes + Close)**: a section-edge node has
   ONE handle, which made `oneSlope` false and dropped its slope
   box into the BROKEN node's 4-cell row — visually out of the
   column line ("as if the node were broken", his report). Single-
   handled nodes now get the smooth node's own aligned "Handle
   slope" row. The Close button is GONE (the dialog is live; X /
   Esc / Enter close — a scene KeyEvent handler replaces the lost
   default/cancel-button wiring) and with the buttons row gone the
   grid's symmetric 14 px padding is the whole bottom margin — no
   leftover whitespace.

3. **FIXED-SLOT POV READOUTS** (his "values jump around
   horizontally"): updatePovStats no longer formats one string
   into statsLabel — each field (X/Y/Z, Speed, Gv, Gl, Pitch/Yaw/
   Roll angle+rate) renders into its own value Label inside a slot
   pre-sized from a worst-case template measured in the actual
   font (buildPovRow/povSet/statsTextW). Values left-anchor in
   their slots and captions are separate fixed labels, so sign
   flips and digit-count changes stop at the slot boundary; an
   EXTREME value RATCHETS its slot wider once for the session
   (never narrower — his "more space automatically given, but
   normal changes don't move things"), so overlap is impossible
   and the layout shifts at most once per extreme, never per
   frame. The row stacks over the plain statsLabel in a StackPane;
   any setText on statsLabel (refusal reasons, save/load lines —
   the bar is dual-duty) makes the message visible and hides the
   row until the next POV update, reproducing the old clobber
   order exactly (a textProperty listener, zero changes at the
   ~40 message call sites).

4. **Bank more orange + the button says "Bank"**: BANK_COLOR
   rgb(235,70,45) → rgb(240,110,35) (hotter than the roll lane's
   pale orange, decidedly orange now); the lane-2 button caption
   reads "Bank" under absolute roll (it kept "Roll" with only the
   tint changing — the Elevation/Heading precedent).

5. **Force grays the whole Yaw/pitch-behavior row**: the "Rate"
   radio now disables (with a hover reason) whenever Type is Force
   — in the Convert dialog, the Add-section dialog, the start
   wizard and (already there) the Resulting settings — matching
   the axis row's rule; Absolute still additionally needs Global.

6. **"Add floating piece…"** — the ⊕ menu item renamed (it creates
   a PIECE; the old "…section…" undersold it), Help along.

7. **EMPTY-COASTER RECOVERY** (user: a coaster whose last piece
   left had no way to add one; "I'm okay with allowing the coaster
   to be empty"): zero pieces is reachable via Switch Coaster
   moving a coaster's only piece away. The graph editor now shows
   a centered "Add Floating Piece" button over the otherwise blank
   plot (GraphEditor.setEmptyPiece — the no-coaster panel's clone,
   synced by syncNoCoasterPanel from every transition incl. both
   rebuildTrack branches), opening the New Track Piece (FLOATING)
   wizard — whose no-piece fallback placement (origin, y 5 m,
   5 m/s) already existed. The Delete-section guard is unchanged
   (a coaster's last section still can't be deleted — emptying by
   deletion wasn't asked for); the wizard's placeholder piece
   clears the button while the screen is open.

8. **Part-convert Confirm flow** (user re-spec): the [Convert
   left/right part] chip now TOGGLES which side converts (same
   flash as clicking a side, which still works); a [Confirm] chip
   on a second row applies — [Cancel] to its right. pcSideRect
   joins the chip hit-test (code 3); Confirm keeps the old apply
   wiring untouched; the chips outrank the line's resize cursor.

9. **grayItem alignment — the real root cause at last** (his THIRD
   report): the ContextMenu skin positions a CustomMenuItem's
   content at the bare left inset, while native item TEXT sits
   after the menu's check/graphic COLUMN — which exists only in
   menus containing Check/Radio rows. The badge menu always holds
   the "View in … domain" check, so its gray rows sat ~20 px left
   of native text; every OTHER menu has no column, which is why
   D237's fixed padding was wrong there and zero padding wrong
   here — no constant works. grayItem labels now SELF-MEASURE: a
   post-layout runLater hop reads a native sibling row's label x
   and pads the gray label to match — pixel-exact in both menu
   kinds (MenuAlignProbe, a scratch headed probe: diff 0.00 px on
   JavaFX 21 and 26), zero where no column exists, any font.

10. **Tooltip accuracy AUDIT** (his "double check all tool tips"):
    ~124 hover/gray/stats strings verified against current code;
    8 were stale and are fixed —
    (a) Delete section: "The document's only section" → "This
        coaster's only section" (the guard tests the ACTIVE
        coaster's one-piece-one-section state; his guess "the
        track piece's only section" was also off — a piece's only
        section IS deletable when other pieces exist, the piece
        goes with it);
    (b) the whole-bridge Convert strict-seam checkbox still
        claimed "the bridge's padlocks carry over" — D235 locks
        all three axes at current angles;
    (c) YP_ABS_HOW still said absolute angles target "the
        heading/elevation vs the world" — heading is
        piece-relative since D238;
    (d) the s/c Direction tooltip said "180 = left" — 180 is a
        flat RIGHT turn since D187;
    (e) the element-0 entry-ease tooltip said "0 = the default
        2 m" — verbatim-0 snaps since D233 (new sections start at
        2 m);
    (f) the Global-bank radio (and the Help Roll row) said "eased
        over max(entry smoothing, 2 m)" — element 0 is verbatim;
    (g) the strict-seam ↺ tooltips promised "the opening value"
        unconditionally — an axis locked during the session
        restores the STORED placement angle (D235);
    (h) "Un-strict refused — the previous piece must end with
        lane sections" — the actual rule is "must not end with a
        bridge" (s/c ends pass fine).
    Also fixed nearby: the node-editor Help row's stale "wheel
    Shift ×10 / Ctrl ×0.1" (D237's global rules superseded) and
    "Close just closes"; CLAUDE.md's Java/JavaFX "21" header
    (pom.xml has been 26 since the initial commit).

Verification: MenuAlignProbe pixel-exact both menu kinds on
JavaFX 21 + 26; devshot smoke clean (startup exercises the new
stats bar + wizard grid); full 32-gate battery green on wiped
classes (the batch is UI-side — no model arithmetic touched).

## D240 (v19.11) — Batch 25: the Design→Real absolute-lane dialog, the Help rewrite, spinner hold+ease stops, POV confinement, capture-cursor hardening, the Add-Floating-Piece fix

Date: 2026-08-30. Eleven-item batch, with his "Design to real not
working for case 8.30.26.fvd" attached. No commit requested.

1. **Design → Real on absolute lanes** (his deep-dive ask + his own
   correct diagnosis). Probed on his file (one all-absolute geo
   section, true pitch/roll −48.9/−38.6, design (0,0)): the plain
   design := true write left the entry frame right for ONE
   integration step, then projectYp/the abs-bank projection
   re-asserted the authored absolute angles — the track snapped
   back to the DESIGN shape, 28.9 m / 41.7° off the on-screen
   track ("the rest is just the Real→Design track", exactly as he
   described). Rate lanes — local or global — are entry-relative
   and re-solve correctly (probed ≤ 0.03 m); only absolute lanes
   snap. THE FIX (his spec): when the piece carries absolute
   lanes, the button opens a dialog — per absolute quantity
   (Elevation & heading / Bank) pick PRESERVE (the snap, now an
   informed choice) or CONVERT (default): Coaster.
   designToRealConvert refits the chosen lanes from the CURRENT
   compileForFit samples (the conflict-tilted truth, captured
   BEFORE the design write) with the section's own flags + the
   D238 piece-heading reference, installs ONLY those lanes in
   place (other lanes, flags, controls, pins untouched), then
   writes design := true — committed through the density preview.
   Probe: his track holds to 0.40 m over 37 m at default density
   (the residual = fit + the one-step entry transient) with the
   conflict closed. All-Preserve routes to the classic direct
   write (FvdDesigner.designToRealDirect, factored out). Sections
   too short to fit keep their lanes; followers can't reach the
   buttons; Real→Design needs no dialog (re-solving to the design
   graphs is exactly what it promises).

2. **HELP REWRITTEN** (user spec): half the length, purely
   user-facing — what things do, never how they work inside; no
   all-caps emphasis; four NEW drawn diagrams beside the node
   intro (helpCanvas rows): the graph editor's landmarks (strips,
   badges, seam line + ◇, ⊕, +V, resulting axis), smooth vs
   broken knobs, the pieces/run sketch (strict seam ‖◇ + bridge),
   and the placement gizmo. Verified by full-height snapshot
   (HelpShotProbe, scratch). Canvas fillText can't render ⊕/emoji
   — draw shapes, label with words (noted in CLAUDE.md).

3. **Density preview: lane-switch clicks live** — the v16.7
   1/2/3-key rule extended to the mouse: clicking a dimmed lane's
   node/curve inside the preview's own strip switches the VIEWED
   lane (read-only — no selection, no gestures; the lane survives
   every preview re-run since restores never touch activeLane).
   Double-clicks are now consumed too (the node editor could open
   under a live preview and be clobbered by its re-runs).

4. **Full-density reference curves** — the preview's dashed
   originals were capped at 400 strided points (visible chords on
   any section over ~4 s, his "bunch of lines"); they now carry
   every compiled sample, drawn through ColPath with view culling
   (pixel-bounded like the Resulting overlays; captured once per
   open). The reversal guides share the fix.

5. **Captured POV confines the mouse like fly** (user spec):
   flyLook gains a confine-only branch — same warp machinery,
   camera untouched; both capture entries (right-click, the
   fly→POV Space flip) init the Robot + center. The tool-window
   forwarding now genuinely warps off windows in POV too;
   releases unchanged.

6. **"Add Floating Piece" fixed** (his bug report — probe-verified
   chain): on a zero-piece coaster the editor idles at activePiece
   0 + NO_LANES; the FLOATING wizard's first piece lands at index
   0 and setActivePiece(0)'s bare same-index return skipped the
   lane re-point — a ForceSection paired with empty immutable
   lanes, and redraw threw (BezierBaker get(-1)) BEFORE
   syncWizardPanel could show the start screen: black canvas, live
   invisible wizard. Fix: the guard now also requires app.lanes ==
   lanesFor(active section) — the setActiveSection rule — plus
   empty-lane no-op hardening in rideEnd/bakeLane/evalPolyline
   (legal documents never hold one; bitwise elsewhere).

7. **Hold-to-repeat on every spinner** (user spec, STANDING):
   plain arrows keep the native skin repeat; the D237-consumed
   MODIFIER presses and LiveNum's ▲/▼ (which only fired on
   release) arm a shared FvdDesigner.HoldRepeat at the native
   cadence — immediate step, 300 ms delay, then 60 ms (verified
   against javafx-controls 26) — modifiers captured at press;
   MOUSE_RELEASED / disable / scene-loss stop it. Installed at
   the numericEditor choke, so future spinners inherit.

8. **Ease stops for the handle boxes** (user spec): the node
   editor's handle-length boxes stop on any NAMED ease fraction
   (cubic 1/3, sine 1−2/π, quintic 7/15, circle 0.5523 — derived
   from the existing HANDLE_PRESETS) lying strictly between the
   current value and the step target — wheel, arrows, Ctrl and
   Shift alike. Landing remembers the pre-stop grid and the next
   step resumes it (0.3 → 0.333 → 0.35; from 0.31 → 0.333 → 0.36;
   Ctrl: 0.330 → 0.333 → 0.335; stepping back returns exactly to
   the pre-stop value); a box OPENED or typed onto an ease value
   steps to round multiples instead (his reopen example). On a
   stop the field wears a soft blue outline (outlineStyleBg — the
   D233 one-layer rule generalized over the control's own
   background) and a tag names it ("Cubic", "Sine", …); stops
   render at 4 decimals so they re-detect (±5e-4 tolerance).

9. **Broken-node editor rows in one column** (user spec): "In
   slope"/"Out slope" rows sit directly below their handle-length
   rows — the 4-cell side-by-side form is gone; edge nodes keep
   D239's "Handle slope" row.

10. **Capture cursor flashes tied up** (his report: I-beam and
    resize cursors flickering over centered tool windows): the
    TextField I-beam is CSS and beats the scene-level NONE (the
    deepest non-null cursor wins, and the cursor update runs
    outside the event dispatch — consuming can't stop it), and
    every dialog Stage was decorated + resizable (native OS
    resize-border cursors). syncCaptureWindows now stashes+nulls
    every node-level cursor per wired window (restored on
    release), sets Stages non-resizable for the capture, wires
    Stages owned transitively by the main stage (the color picker
    owned by Rail Settings was skipped), wires windows OPENED
    MID-CAPTURE via a Window-list watch (Ctrl+Z rebuilds the seam
    pop-out; Ctrl+E opens Export — both previously arrived
    unwired), and re-sweeps cursors every 25th forwarded move.
    Residual: a decorated Stage's title-bar arrow is OS-drawn and
    cannot be suppressed.

11. **"Set Design to Real" in the initial-speed dialog fills the
    box** (user spec): no op, no close — it writes the true seam
    speed (the reconcile's D237 anchor read) into the box; Apply
    commits through the existing routes. Apply's clamp widened
    [1,100] → [0.1, 1e4] (the D198 admit-what-the-app-produces
    rule — a near-stall true speed round-trips now); grayed with
    a reason before the first compile. The badge-menu resolve op
    keeps the direct refreeze (note: the dialog's Apply pins
    where the resolve op unpins — deliberate, the dialog's
    documented semantics).

Verification: D2RFixProbe (0.40 m) + the D2R probe family;
HelpShotProbe full-page render; full 32-gate battery green on
wiped classes (model changes: designToRealConvert added,
BezierBaker empty-lane guards — legal paths bitwise).


## D241 (v19.12) — Batch 26: the Blender-style adaptive floor grid, Preferences + Resolution Scale, badge colors, the Ctrl/Shift swap, single/multi-node H·F·locks, clickable node-editor twins

Date: 2026-09-01. Eighteen-item batch. No commit requested.

1. **The ADAPTIVE FLOOR GRID** (his big item — the Blender spec:
   density from on-screen square size only, powers of 10, no
   view-angle dependence, top ortho uniform with no special case,
   levels cross-fading, camera-position anchored, minimal lag).
   The fixed 1 m/10 m band tables (minorA/majorA, six rings to
   240 m) are GONE. New model in Viewport3D: FOUR DECADE MESHES
   (spacing 10^n … 10^(n+3), n floored at 0 = 1 m; every mesh
   skips its 10th lines — the next decade owns them), each split
   into TEN concentric alpha rings (edges spacing × {0, 7.5, 15,
   22.5, 30, 45, 60, 90, 120, 180, 240}; extent 240 × spacing,
   self-similar). EVERY ring's alpha is ONE pure function of its
   projected cell size in EFFECTIVE pixels — pxEff = cell px ×
   the GRAZING factor dRef/dist (far ground is seen edge-on; its
   squares compress on screen, which is what actually breeds the
   no-mipmap shimmer the old tables were hand-tuned against):
   gridAlpha(px) = 0.30·smoothstep((px−4)/12) + 0.20·
   smoothstep(px/60) — the fast ramp ≈ the historic minor look,
   the slow one brightens majors toward 0.50, and the 4 px dead
   zone kills sub-pixel dot forests outright (probed: even at 3%
   alpha they read as a textured patch). Purity in pxEff is the
   whole design: the level CROSS-FADE and the radial fade emerge
   from the same formula, a decade keeps its exact alpha when the
   base level re-labels (no pops — its brightest ring sits at the
   visibility threshold at every base transition, so rebuilds are
   invisible by construction), line width 0.03·q^0.426 (pure in
   the line's own spacing q; reproduces the historic 0.03/0.08 m
   at q = 1/10), and the ORTHO branch (constant m/px) comes out
   UNIFORM with zero special-casing. Driven per frame off the
   axes timer (updateFloorGrid): the anchor is the CAMERA EYE via
   the active camera's localToScene — the orbit grid used to
   follow the PIVOT and never updated on rotate/zoom at all (his
   report); the 9 scattered updateGridLOD call sites are RETIRED
   (with them the whole D218 "programmatic jump left the grid
   parked" class), the D232 ground axes ride the same eye anchor,
   and each decade group translates in multiples of 10× its own
   spacing (world-aligned; per-decade because one shared quantum
   would be the coarsest decade's — km jumps). Per frame: one
   transform read + 40 bucketed material writes (1/64 buckets,
   epsilon-free); meshes rebuild only on base-decade changes.
   TWO dev-round root causes worth recording: (a) the first build
   showed a dotted speckle field everywhere — ALPHA-0 line quads
   still WRITE DEPTH, and the sheet (drawn after) lost the depth
   test under every invisible line: zero-alpha rings now
   setVisible(false) and the sheet renders FIRST (gridGroup child
   0); (b) coplanar decade meshes under different translates
   z-fight at crossings (the old two-mesh grid shared one
   transform — identical arithmetic hid it): decades stack 4 mm
   apart, coarser on top. The sheet's extent rides the third
   decade (±24 000 m at base 1 m — ground to the horizon).
   The 2D ORTHO OVERLAY gets the same treatment (his side-view
   1 m ↔ 10 m seam): gridFamily strokes THREE decades, each at
   orthoGridAlpha(px) = 0.13·ss((px−7)/14) + 0.15·ss((px−7)/200)
   — pure in the decade's own px, so promotions/demotions are
   continuous and a fresh minor decade fades in from 0 instead of
   popping (the old hard `while (minor/upp < 7) ×10` step with
   fixed 0.13/0.28 alphas is gone; today's densities preserved at
   the same 7 px threshold).

2. **Preferences menu** (renamed from "Edit", user spec) with the
   new **"Resolution Scale…"** row (his "if feasible" — probed
   FEASIBLE, restart-only): a Blender-style whole-UI factor.
   Empirics from the feasibility probe: glass.win.uiScale scales
   the entire toolkit crisply (Canvas included — full-res
   rasterization), coordinates stay logical, fractional and sub-1
   values work — but the property is consumed in
   WinApplication's static initializer, which the standard java
   launcher runs BEFORE an Application main class's own main()
   (probed: a setProperty there is ignored), and a malformed
   value CRASHES toolkit startup. So: NEW coaster.Main — a plain
   launcher class (does NOT extend Application) that reads
   AppSettings.uiScale (default 1.0 = never set the property =
   today bitwise), multiplies by the OS scale read pre-FX via AWT
   (the property REPLACES the OS scale — an absolute 1.0 on his
   150% desktop would shrink the app by a third), sanitizes hard,
   sets the property, then Application.launch(FvdDesigner).
   pom.xml mainClass → coaster.Main; FvdDesigner.main delegates.
   The dialog: slider 0.75–1.75 snapped to 0.05, ↺ at 1.0,
   "Applies at the next launch." — restart-only is a platform
   fact (probed: a mid-session Stage keeps the boot scale).

3. **Ctrl/Shift SWAP on all value entry boxes** (user spec,
   reversing D237): Ctrl = snap to the next round multiple,
   Shift = tenth steps — installSpinnerSteps (press + wheel),
   modStep, LiveNum.wireHold/modClick/setOnScroll, every doc/
   Help string. Node DRAGS keep Ctrl-snap (unchanged — the swap
   is value boxes only). The ease-stop machinery rides along
   (Ctrl = round multiples incl. from a stop, Shift-tenths still
   stop on ease fractions).

4. **Ease-stop fixes** (his two reports): (a) values FROZE on
   stops — a landed stop renders 3 decimals (0.333) while the
   exact stop is 0.3333…, which then lay strictly between the box
   value and every later target and interceptStop re-caught it
   forever; stops within 5e-4 of the CURRENT value are now
   skipped (a stop is an inserted snap point, never a wall) and
   stops render at the standard 3 decimals (his explicit ask).
   (b) The ease tag pushed the grid's readout twins right when it
   appeared — the tag has a constant width (measured from the
   widest stop name) from birth.

5. **Single-node H / F / lock semantics** (user spec): with ONE
   node selected, F straightens the segment to its PREVIOUS node,
   Shift+F/Shift+H LOCK that segment (straight / level-with-this-
   node), and H's SECOND press on an already-flat node levels it
   WITH the previous node (value copy; third press exact no-op).
   Section-first nodes: the prev-pair features no-op (H still
   flattens). He wrote "ctrl+h and ctrl+f" for the lock gestures —
   implemented on the EXISTING Shift+F/Shift+H bindings (Ctrl+F/H
   are unbound; rebindable anyway), flagged in the reply.

6. **Multi-node F** (his report + his correct hypothesis): F with
   >2 or non-adjacent selections did nothing — a hard
   `selSet.size() != 2` gate (nothing ever "tried"); and pairwise
   application WOULD have snapped back exactly as he suspected
   (the smooth-joint C1 far-handle re-angle overwrites the
   neighbor chord). Rewrite: every segment between consecutive
   selected nodes chords (the v10.6 selectedPairs target set);
   interior joints whose chords DISAGREE go honestly BROKEN
   first (the D62 rule; agreeing collinear runs stay smooth);
   far-handle re-angles only at chain ends; locked segments skip
   (their lock owns the shape). Two adjacent nodes = the classic
   behavior bitwise. Right-click menu entries follow (level/
   straighten/lock "with previous" on single nodes, D132
   hide-inapplicable rule).

7. **Clickable node-editor twins** (user spec): the grey computed
   twins next to Transition time and Value wear the speed-popup
   "Enter:" hover outline (constant transparent border — D237
   layout rule) and CLICKING one SWAPS which representation the
   box edits — titles flip to "Absolute time (s)"/"Absolute
   position (m)" and "Change in value (unit)", the twin shows the
   other representation, snapping follows the box (its content IS
   the edited quantity), applyPos converts back (abs mode maps
   through warpInv − section start; delta mode adds the previous
   node's value), the undo-refresh hook re-seeds mode-aware and
   re-runs both twin fills (closing the pre-existing stale-twin
   corner). Per-dialog-instance; reopening resets. Node 0 has no
   twins (nothing previous).

8. **Init-speed equivalence** (his question — answered YES, they
   differed): the dialog's "Set Design to Real" + Apply PINNED a
   2-decimal value while the badge-menu resolve op unpins, bakes
   tilt/follow and writes the exact seam value. Now an UNMODIFIED
   fill commits through Coaster.refreezeSpeed — identical to the
   resolve op; a hand-typed value (even re-typed equal) still
   pins as a deliberate conflict; ride-entry and bridge-before
   cases keep the plain write (no conflict exists / the resolve
   op refuses there).

9. **UI polish sweep**: section badges COLOR-CODED (blue Force /
   orange Geo / purple S/C; bridges keep neutral gray + the dirty
   warning) with capitalized labels and brighter alphas (0.6
   inactive / 0.9 active / 1.0 hover); the yp-axis row's GRAY
   under Force now also DESELECTS Global → Local in the Add and
   Convert dialogs (the behavior row's Abs→Rate precedent — the
   dot sat on a choice that no longer applied; the wizard was
   already correct via its model round-trip); the wizard panel
   pins the editor to its REAL pref height (MIN_HEIGHT 240 dated
   from the D232 3-row grid — the D237/D238 rows grew content to
   ~279 px and the centered title rendered half above the dark
   background; + TOP_CENTER so any future overflow eats the
   bottom, never the title); "(10 m straight)" dropped from the
   ⊕ items; the view-label dropdown wears shortcut tags ("(1)"…
   "(6)" fixed digits, the live POV binding on Fly/Orbit and
   POV) and the Append button "(A)"; the Design→Real dialog is
   retitled "Design → Real Absolute-Angle Conversion" minus its
   explainer paragraph; TOP view enters at ONE fixed orientation
   (world Z up, X right — as if tilted down from Front; the
   nearest-90° yaw snap is gone); ortho→fly/orbit ALWAYS
   restores the full pre-ortho perspective pose — angles, zoom
   AND pivot (savedPiv* joins the stash; exitOrthoRestorePose
   feeds toggleFly, togglePov, the dropdown restore and the
   same-key exit; orbiting away keeps the adopt-view behavior).

10. **Tooltip length sweep** (his Append-button report + "do
    another sweep"): 14 overlong tooltips + 4 grayHint reasons
    trimmed to the D240 what-not-how length (worst: the Convert
    dialog's 8-line strict-seam checkbox; the bridge length/mode/
    optimize trio; both friction rows; the element dialog's
    global-bank + entry-ease pair; YP_ABS_HOW).

Verification: full 32-gate battery green on wiped classes (model
deltas: AppSettings.uiScale + coaster.Main only — every gate path
untouched); devshot wizard renders (title inside the panel, both
modes); live-app PrintWindow captures of the adaptive floor
(default framing: clean 10 m field, smooth grazing fade, faint
100 m super-grid, no speckle — plus the two root-caused rendering
bugs above found via a -Dfvd.griddbg telemetry round).


## D242 (v19.13) — Batch 27: the floor-grid REBUILD (premultiplied fades, depth-disable, q-quanta), Edit-menu revert, speed-only Set-Design-to-Real, Apply-and-Restart, view digits in dropdown order

Date: 2026-09-01, same day as D241 — his feedback round on batch
26. Ten items; the grid "EXTREMELY buggy … put far more resources
into making this a very clean feature" headline drove a 3-agent
probe/sweep/review pass plus nine visual iteration rounds through
a new `-Dfvd.gridshot` pose-ladder hook.

1. **THE FLOOR GRID, ROOT-CAUSED AND REBUILT.** His live symptoms
   (flashing, missing lines, whole-grid vanishing, no fading /
   hard decade boundaries) decomposed into FIVE independent
   defects, each probe- or math-confirmed:
   (a) **The 3D blend law never dims the source.** Empirical
   probe (the decisive one): prism's 3D translucency composites
   src_lit·1 + dst·(1−α) — the material's alpha attenuates only
   what is BEHIND the quad. Every "faded" ring therefore rendered
   at FULL line brightness until its bucket hit zero and it
   vanished — the literal "no fading at all, obvious boundaries".
   An α=0 quad still adds its whole source color. FIX: fades are
   PREMULTIPLIED (diffuse = o·LINE at alpha o == exact OVER
   compositing) and zero-strength rings go setVisible(false).
   The D232 ground-axis segment fades had the same disease —
   premultiplied now too.
   (b) **Depth noise between the grid's own layers.** Translucent
   3D writes depth; perspective depth resolution ≈ z²/(near·2²⁴)
   (near 0.1) is ~5 cm at 300 m — the D241 4 mm decade stacking
   and 3 cm sheet offset sat INSIDE the noise: probed 21-79% of
   overlap pixels mis-ordered per static frame at 300-1500 m =
   the per-pixel patchwork that flashes under motion. FIX:
   gridGroup + axisGroup render DepthTest.DISABLE at the FRONT of
   `world` (indices 0/1) — probe-proven pure submission order, NO
   depth read or write (D3D sets both from one flag; bytecode-
   corroborated NGNode → D3DContext), sheet first, everything
   flat y=0. The track drawn later overdraws the grid cleanly;
   a below-ground camera never sees grid lines over track
   (accepted corner). Also probed: transparent 3D is NEVER
   sorted — submission order is the only order.
   (c) **The 10q translate quantum de-centered the alpha field.**
   Ring alphas are eye-centered but the decade meshes translated
   in 10-spacing steps — the bright field parked up to 7q beside
   the camera and HOPPED 10q per step (100 m hops for the 10 m
   decade; km hops above): the live "grid vanishes / pulses".
   FIX: per-decade quantum = its OWN spacing q, coupled with
   skipTenth = FALSE everywhere (the skip slots live in mesh
   coordinates — under q-quanta they'd land beside the coarse
   decade's lines; and all four decades skipping i==0 left the
   x=0/z=0 lines beyond the ±240 m colored axes drawn by NOBODY —
   his "certain lines never appearing"). A major now carries the
   finer line under it as a brighter core — reads as emphasis.
   (d) **Sub-pixel dash shimmer.** Quads much thinner than a
   pixel rasterize as dash chains (4-sample MSAA, no mipmaps)
   that crawl under motion — his original top-down "uneven patch"
   reproduced exactly in the pose ladder. FIX: WIDTH-COVERAGE
   attenuation — each ring's strength × smoothstep of its own
   projected line width (grazing^0.75), dead below ~⅓ px, full by
   ~1 px — so every decade lives only where it renders as SOLID
   lines; widths are hw = 0.03·q^0.7 (each decade ~1 px in its
   own viewing band; pure in q so base re-labels can't pop), and
   a HIGH-side rolloff (cells past ~150 px) retires a decade
   whose sparse lines would only render as fat stripes.
   (e) **Structure/lifecycle.** Grid + axes are CONSTRUCTOR-built
   (the old first-nonempty-rebuild latch left empty coasters and
   fresh panes with NO floor at all); 15 GEOMETRIC rings per
   decade (inner edges q·{0,1,1.35,…} — the review probe showed
   the radial falloff lives at eye-height scale, INSIDE the old
   coarse first rings: identical worst-banding with the old
   outer-only subdivision), extent 64q (beyond ~C/8·q no ring
   ever clears the dead zone — the 240q tail was dead weight),
   base-flip HYSTERESIS (up at upp>10^b, down at 0.5·10^(b−1);
   both thresholds sit in the dead zone so flips are invisible),
   strength buckets 1/256 (1/64 popped whole annuli during slow
   zooms). The ORTHO lens hides the line decades outright — the
   2D drawOrthoGrid canvas IS the ortho grid (it double-drew
   under a uniform 3D field that ended in a hard square edge:
   his top-down "obvious boundary"); its per-decade alphas rose
   to 0.18/0.38 caps (the 3D contribution left). The sheet is
   ONE ±3500 m quad (inside the 5000 far clip — the old 24 km
   sheet showed the far-plane's cut sweeping the ground; a
   graded-annulus taper was tried and TERRACED at grazing),
   follows the eye continuously un-quantized, hides in SIDE
   ortho (edge-on = a shimmering 1 px line). Line color near-
   white 0.93/0.95/1.0 (premultiplied mixing needs the headroom
   against the lit sheet), law 0.55·ss((px−2)/10) + 0.30·
   ss(px/60) on grazing-compressed cell px. Verified across a
   9-pose gridshot ladder (low graze, mid orbit, high, very
   high, base straddle ×2, POV height, ortho ×2) over nine
   candidate rounds — the failures of each round are what
   produced (c), (d), the terrace find and the ramp widths.

2. **"Set Design to Real" in the initial-speed dialog is SPEED-
   ONLY** (his correction of D241's full-resolve): the unmodified
   fill + Apply now runs Coaster.refreezeSpeedOnly — the exact
   seam speed, un-pinned so it follows the real flow — and an
   ORIENTATION conflict stays exactly as it stands; with none,
   it equals the badge-menu resolve op, which alone still bakes
   tilt/follow (refreezeSpeed = bakes + refreezeSpeedOnly,
   factored). Typed values still pin; ride entry keeps the
   spinner write; the bridge-before guard is gone (a speed-only
   write is as safe as any typed one).

3. **Resolution Scale: "Apply and Restart"** (his "instantly
   update like Blender?" — the honest answer is no: the scale is
   baked into glass at toolkit boot, probed in D241; Blender owns
   its renderer, JavaFX doesn't let us). The closest gesture
   ships instead: FvdDesigner.restartApp spawns a fresh JVM
   (java.home binary + RuntimeMXBean input args + classpath, on
   coaster.Main) through the unsaved-changes gate and exits; a
   spawn failure posts a stats reason and keeps the session.

4. **"Preferences" reverted to "Edit"** (his call — the D241
   rename undone; Resolution Scale stays in the menu; every
   Help/comment reference swept back).

5. **View digits renumbered in DROPDOWN order** (1 Top, 2 Front,
   3 Back, 4 Right, 5 Left, 6 Tangent — was sides-then-top);
   dropdown tags, handleViewDigit, Help updated. 6 = Tangent
   still works from the graph.

6. **Node editor**: the value row wears the LANE'S OWN quantity
   name (laneQtyName — Vertical G / Lateral G / Roll / Pitch /
   Yaw / Bank / Elevation / Heading + unit; his spec), and the
   click-swap delta title is "Δ " + the same (replacing "Change
   in value"). Ease-stop LAYOUT NEUTRALITY (his screenshot): the
   blue ring is layered BACKGROUNDS, not a border (a CSS border
   joins layout insets — the D240 border grew the field 4 px on
   every stop and shoved its arrows), and the name tag is an
   UNMANAGED overlay floating past the arrows (any reserved
   width held the grid column open and pushed the readout twins
   right — the twins sit exactly where they did pre-D240 now).

7. **Node right-click menu**: the split/connect group moved
   directly below "Edit node…" (separator kept).

8. **Badges desaturated** ~45% toward the old neutral gray (his
   "less saturated"); labels/alphas/hover kept.

9. **Wizard**: the Straight/Curve card's "A 10 m straight to
   begin with…" note removed — tiles + size spinner only.

Verification: 3-agent workflow (depth-semantics probe with
pixel-level evidence + bytecode corroboration; camera-parenting/
world-order sweep — all three cameras' eye reads confirmed
correct, POV's world-parenting cancels; adversarial design review
with computed alpha tables), nine gridshot ladders, a live
PrintWindow capture, devshot smoke, full 32-gate battery green on
wiped classes (model deltas: Coaster.refreezeSpeedOnly factored
out of refreezeSpeed — resolve-op path bitwise).


## D243 (v19.14) — Batch 28: grid tuning round (3-decade cap, density gate, scaled axes), top-view white overlay, live node editor, ease-tag fixes, badge revert

Date: 2026-09-01, his feedback on D242 — "all of the glitchiness
of the floor grid looks to be gone and the fading is clean."
Eight items.

1. **Grid tuning** (all user spec): the coarsest decade is CAPPED
   at 100 m (GRID_DECADES 3, base ≤ 2 — no 1 km+ grids ever);
   line widths hw = 0.03·q^0.6 (q^0.7's close-up 100 m lines read
   "massive"); a DENSITY GATE ss(3·dRef/q − 0.3) keeps the 100 m
   grid away until the camera is well out (~15 m eye: gone; ~45 m:
   full); the width-coverage attenuation relaxed (sqrt-grazing,
   dead 0.2 px / full 0.7 px — was grazing^0.75 with 0.35/1.0) so
   each grid reaches MUCH further before fading ("a little
   artifact on the horizon is OK" — his explicit tolerance).
2. **The MAIN AXES are the only lines on the world zero lines**
   and scale like grid lines (user spec): buildAxisMesh reworked —
   segments ±6.4 km from the camera anchor in a geometric ladder
   (AXIS_SEG), per-segment half-width max(0.09, 0.0016·d) = ~1.5
   screen px at its own distance AND wider than the 100 m
   decade's line wherever that decade is visible, so the white
   zero lines underneath (drawn by every decade since the D242
   skip removal) never peek out — the axes render AFTER the grid
   in the depth-disabled submission order. Strength fades
   smoothly by 6.4 km; anchor quantized 30 m.
3. **Middle-clicking out of the TOP view orbits around the ground
   point under the cursor** (user spec): before exitOrthoKeepView
   the pivot moves to the click's XZ at y=0 (the ortho lens's
   screen→world math; top is always yaw-0 since D241 so the
   mapping is exact). Top→orbit also keeps grid density/width
   roughly continuous by construction now: the ortho overlay and
   the 3D law agree on which decades are visible at a given m/px.
4. **The TOP view's 2D overlay strokes WHITE** (like the 3D grid;
   user spec) — side views keep the grey-blue; behavior (zoom
   sizing, per-zoom decades — which he called out as liked)
   unchanged; gridFamily gained a color parameter.
5. **Badges: the type colors are GONE** (his explicit reversal of
   D241/D242) — neutral gray again but LIGHTER than historic
   (fill 56,62,76 / hot 84,92,112) with a THICKER edge (1.6 px,
   2.2 hot); capitalized labels and the raised alphas stay.
6. **Node editor lives** (user spec): a resync hook runs at the
   top of EVERY redraw (GraphEditor.redrawHooks + a reentry
   guard) — outside changes to the node, its handles, or the
   previous node update every box and twin in real time; a
   FOCUSED box is never overwritten (typing safe), undo restores
   force-overwrite; LiveNum.set() no-ops on equal text, which
   both makes the steady state free AND stops the resync from
   wiping the ease-resume anchor every frame. Also "(g)" dropped
   from the Vertical G / Lateral G row titles (redundant).
7. **The vanished ease tag** (his report — a D242 regression):
   an UNMANAGED node is never resized by its parent, so the
   overlay tag stayed 0×0 and rendered nothing; place() now
   autosize()s it. Worth remembering alongside the D242 blend
   lesson: unmanaged JavaFX nodes need explicit sizing.
8. **Focus visible on an ease stop** (his report): the ring style
   replaces the skin's focus visuals, so a focused box showed
   nothing — the ring is 1 px now (half of D240, his suggestion)
   and a focused box adds an -fx-focus-color layer at negative
   insets (outside the bounds — layout untouched, the D242
   layout-neutrality preserved).

Verification: gridshot ladder (clean 10 m field reaching ~2-3×
further at orbit heights, no massive close-up 100 m lines, thin
even top-down 100 m grid, axes running to the horizon at every
height, white top-ortho overlay); devshot; DetermGate197/199 +
CoreCheck + ConflictGate smoke (NO model-layer changes this
round — the full battery ran identically in D242).


## D244 (v19.15) — Batch 29: grid reach ×5 at half thickness, ease-tag first-open placement, the "-0.000" fix, influence titles

Date: 2026-09-01, his quick follow-up on D243. Four items.

1. **Grid ~5× reach, half thickness** (user spec): gridHw 0.015·
   q^0.6 (halved), the width-attenuation thresholds dropped ~5×
   (dead 0.04 px / full 0.16 — was 0.2/0.7), the cell fade-in
   starts at 0.8 px (was 2), and the ring ladder extends to 128q
   (the reach outran the 64q mesh edge — a clipped square would
   have shown). The far tail renders as a soft dashed haze —
   explicitly accepted ("a little artifacts on the horizon", his
   standing tolerance). Axes untouched (approved last round;
   they still cover the thinner zero lines everywhere).

2. **Ease tag inside the box at first open** (his report): the
   HBox's layoutBounds listener fires BEFORE the first layout
   pass positions the children, so place() read zero bounds and
   parked the tag at x≈5 over the digits until any later
   re-place. Now the managed children's boundsInParent changes
   re-place it (they fire after positioning) plus one
   Platform.runLater pulse after construction.

3. **"-0.000" never displays** (his report + the why): the flat
   handle's slope is inDv/effInDt where effInDt is NEGATIVE by
   convention — a ±0.0 numerator yields −0.0, and typing 0 in
   the slope box stores −0.0 right back via 0·(−dt), so the sign
   "came back"; it appeared only after a mouse move because the
   D243 live resync was the writer. Fix at the DISPLAY layer:
   FvdDesigner.z3 snaps any value that renders as zero at 3
   decimals (|v| < 5e-4 — −0.0 and dust alike) to +0.0, applied
   in LiveNum.fmt, the node-dialog twins, the POV bar's numeric
   fields + angTxt (his old screenshot showed "(-0.000°/s)"
   there), and the graph hover/drag slope readouts.

4. **Badge-menu influence items retitled** "Influence geo
   graphs" / "Influence force graphs" (user spec — the
   resulting/felt qualifiers dropped).

Verification: gridshot ladder (10 m field to near-horizon at
orbit heights, full-frame even 100 m field straight down, thinner
lines everywhere); no model-layer changes (DetermGate197 +
CoreCheck smoke).


## D245 (v19.16) — Batch 30: sign-preserving dust display, grid reach ×3 (grazing-aware)

Date: 2026-09-01. Two items + two answered questions.

1. **"-0.000" re-specced sign-preserving** (user): z3 now snaps
   only |v| < 1e-6 to +0.0 — true zero, −0.0 and deep dust show
   "0.000"; anything from 1e-6 up keeps its real sign, so
   "-0.000" honestly reads "a little negative" (his ask: the user
   should see the dust's sign "up to a certain point"). Edge
   cases examined: display-only (nothing reads formatted text
   back into the model), fixed-slot POV bar and fixed-width boxes
   mean a sign flicker on oscillating ±1e-6 dust moves no layout
   — the flicker IS the requested information. His curiosity
   answered: typed sub-display values apply at FULL double
   precision (the sink takes the parsed value; only the display
   rounds — though a subsequent STEPPER step starts from the
   displayed text, i.e. the rounded value).

2. **Grid reach ×3 — grazing-aware** (user: "3× as far"). A flat
   ~5× threshold drop was tried first and probed visually: it let
   the FINEST decade survive as a mid-range speckle carpet over
   the working area (at eye ~80 m the 1 m lines at 0.12 px width
   painted dot noise everywhere — the same px regime as the
   wanted far tail, differing only in VIEWING ANGLE). So both
   fade gates now slide with grazing proximity f =
   ss((dRef/dist)/0.45): the width floor 0.04 px (steep, = the
   clean D244 tuning) → 0.008 edge-on, the cell fade-in start
   0.8 px → 0.09 — the horizon-ward reach roughly triples while
   steep views keep their speckle-free look. Ring ladder extends
   to 384q, with rings whose inner edge passes the 5.5 km far
   clip never built (null slots, guarded). Gridshot-verified:
   the low-graze and orbit poses run the 10 m field far toward
   the horizon with a soft dashed tail; near/mid fields clean.

No model-layer changes; compile + gridshot ladder.


## D246 (v19.17) — Batch 31: the low-height grid flicker (height-gated reach)

Date: 2026-09-01. One item — his report: from low down the grid
flickers, worst when traveling straight along an axis; stopping
shows several ENTIRE lines missing.

Root cause: the D245 long reach permits DEEP-sub-pixel line
widths at low grazing, and near the ground most of the visible
field IS low-grazing — a quad 0.01-0.1 px wide either lands on an
MSAA sample column (drawn, full length) or misses it entirely
(invisible), so parallel lines alternate visible/missing by their
sub-pixel phase against the pixel lattice, and camera motion
slides that phase = whole lines popping (gradual alpha can't fix
it — it's rasterization coverage, not opacity, which is why "more
gradual fading" alone wouldn't have helped).

Fix — his own suggested trade, implemented smoothly: the reach is
HEIGHT-GATED. c = 1 − hf·(1 − f) with hf = ss(dRef/70) joins the
D245 grazing factor f: at eye heights below ~20 m the gates sit
at the clean D244 tuning (short, solid, flicker-free field); by
~70 m the full D245 horizon reach is unlocked — both dimensions
smoothstep-continuous, so the field expands as you rise and
contracts as you descend with no seam ("would need to be
seamless", his spec). Distance views (which are taken from
height) keep their full density — gridshot-verified: the
mid-orbit pose is pixel-identical to D245, the low-graze pose
contracts to a clean solid field.

No model-layer changes; compile + gridshot ladder.


## D247 (v19.18) — Batch 32: Copy + Anchor rows + run-head placement, the follower DESIGN tier (seam window with Lock Track Shape / Design ↔ Real, the Strict Seam ▾ button), the seam undo bug + live tool windows + the preview transaction, reveal balls on the whole run, entry-only ≠v + the ≠° glyph, range-scoped Resolve conflict, Connect sections keeps both values, seam-menu Insert section…

Date: 2026-09-06, his batch-32 list (the follower / seam /
tool-window round after D246; "undo works once, then Ctrl+Z is
dead" was the headline bug). Thirteen items + a review round.

1. **Renames + tooltips** (user spec): the Track Piece panel's
   ops row reads "Reverse…" / "Mirror" (was "Reverse piece…" /
   "Mirror piece"); its three placement labels read "Anchor
   x/y/z (m)" / "Anchor yaw (°)" / "Anchor pitch/roll (°)" (the
   bridge branch's "Next piece …" labels untouched) — a RUN is
   what he calls "the track piece" and its head entry is "the
   anchor". Lock Track Shape tooltip verbatim "Design
   orientation does not change for adjustments to pitch/roll"
   (panel + seam window). The dead Coaster-menu reverseItem
   (never added to a menu since D232) is deleted. The
   initial-speed dialog's enabled "Set Design to Real" wears a
   tooltip: it fills the box and does not resolve an orientation
   conflict — "Design → Real Orientation" (Track Piece ▾ /
   Strict Seam ▾) does.

2. **Copy** (user spec; Coaster.copyRun / copySection,
   TrackPiece.copy(dz), COPY_DZ = 5): the panel's fourth ops
   button (Reverse…, Mirror, Copy, Switch Coaster… — no
   ellipsis, the op is immediate) duplicates the RUN
   (head..runEnd: strict followers + bridged targets included —
   a lone follower's stored placement is its frozen pre-attach
   pose and a lone bridge-ending piece would orphan onto the
   ORIGINAL target, so only the connected whole copies honestly)
   5 m along world +Z, appends it at the list END, activates the
   copy, reveals its entry rig and selects node 0. Deep copy
   with the undo snapshot's field coverage; session flags
   (viewOtherDomain/viewOtherRate/editInfluence) reset,
   baseCurves null; the canonical pair untouched (a translation
   is bitwise-rigid, D199); copied bridges get optSig = null
   (first sight = record-only: the sig carries the world entry,
   a kept array would re-place the copied target on the first
   rebuild) and keep lastOptDist so the march renders; the
   head's followPrev normalized false. Refusal (grayed + stats):
   the LIST's last piece ends in an orphaned bridge
   (Coaster.listEndsInOrphanBridge — the review moved the test
   from the run end to the list end: appending would hand that
   bridge the copy's head as its target and move the ORIGINAL
   track). Tolerances = the D199 corners: bitwise except tilted
   / held-angle / smoothed dust at 1e-9 and bridge spans at 1e-6
   (CopyGate). Tooltip "Duplicate this piece with everything
   joined to it 5 m to the side (+Z) and edit the copy."; stats
   "Copied the piece / run (N pieces) 5 m along +Z — editing the
   copy."

3. **Strict-seam followers get a LIVE design tier** (user
   re-spec; supersedes D222's "both delta tiers are dead on
   followers"): TrackCompiler's follower branch generates from
   designPose()/designCanon when designPitchDeg/designRollDeg ≠
   0 (zero deltas ⇒ the historic path bitwise — SeamShapeGate
   carries pre-D247 sample-bit checksums recorded from f84061a's
   core to prove it), so a follower's DESIGN orientation =
   designPose() and its REAL orientation = the follow contract;
   the seam conflict (Coaster.seamConflictTilt /
   isSeamOrientConflict) is elevation/bank only — a heading-only
   re-aim commutes with gravity (D199) and is no conflict.
   Attach preserves a design-tilted piece's conflict for free
   (connectStrict keeps the deltas + canonical pair; NO fold —
   the map's fold-at-attach plan superseded; generation bitwise,
   SeamShapeGate 1b/2a). ONE seam write choke,
   GraphEditor.seamEdit(fi, write): with Lock Track Shape OFF
   the design tier moves by the real Euler delta
   (Coaster.seamDesignLockstep — the elevation/bank gaps stay
   constant; the quaternion tilt drifts at second order exactly
   as a free piece's does under editPitch/editRoll, probed
   9.5e-3, accepted with the Euler-additive model), ON (default)
   = today's write bitwise; yaw never touches the design tier.
   POLE GUARD (review): a write crossing straight up/down
   (either side |elevation| > 89.9°, or heading AND bank both
   flipping by > 90°) leaves the design tier alone and posts
   "Seam edit crossed straight up/down — the design tier was
   left alone; adjust it in the seam window." Seam Design → Real
   = Coaster.bakeFollow (both tiers zero, follow + locks + held
   angles kept, NOT detached; absolute lanes on the follower
   route through the D240 Preserve/Convert dialog —
   designToRealConvert bakes the follow with the SAME
   compileForFit result the fit read). Seam Real → Design =
   Coaster.seamRealToDesign (held pitch/roll := the design
   elevation/bank, both locked, yaw untouched; refuses a
   vertical design frame). detachStrictSeam is
   CONFLICT-PRESERVING (review): the position bakes, the TRUE
   tier decomposes onto the follow contract (decomposeNear),
   baseOrient / canonical pair / design deltas stay —
   attach→detach is an involution again (7e-14 m, SeamShapeGate
   8), the seam's ≠° becomes the free piece's ≠°; a vertical
   frame falls back to the bakeFollow bake. Shared
   Coaster.followContract(idx, r). A run-head translate keeps
   followers rigid to the D199 bound; a REAL-anchored
   continuation honestly re-solves at its new true speed after
   seamRealToDesign (the reconcile's job, not the op's).

4. **The seam window rework** (user spec;
   GraphEditor.showStrictSeamSettings): the Yaw row stays; pitch
   + roll MERGE into one row mirroring the panel's
   tiltPlacementRow ([Pitch/roll (°)] 🔒 pitch ↺ 🔒 roll ↺, 80 px
   boxes; ±360 factories so a loaded held pitch past ±90 never
   clips; the seam padlock SEMANTICS kept — 🔒 red outline =
   holds the typed angle, the panel's padlocks are edit guards
   with the opposite sense, the tooltip says so). Below the
   rows: the "Design orientation:  pitch …°   roll …°" readout
   (eulerOf(designPose()), NaN → "—"), a "Lock Track Shape"
   check on the follower's own shapeLocked (reusing the flag
   means unchecking also stops placementEdit's rigid carry of
   ITS downstream — D222's unlocked = natural updates) and the
   "Design → Real Orientation" / "Real → Design Orientation"
   buttons — readout + buttons only while orientConflictAt(fi).
   Cancel restores held angles, locks, shapeLocked and the
   design tier (the tier only while baseOrient is still the
   open-time object: after a Design → Real bake the bake stays,
   Ctrl+Z is the path — the panel has no Cancel either). Toolbar
   "Strict Seam ▾" (FvdDesigner, right after Track Piece ▾;
   visible only while the active piece follows a strict seam —
   the grayHint WRAPPER toggles so the HBox spacing collapses;
   tooltip "Orientation of the strict seam this section
   follows."; wizard-disabled like the Track Piece button) opens
   it. SELF-VALIDATING since the review (seamLive: identity +
   followPrev check on every handler — "The strict seam window
   closed — its seam no longer exists."), every write gated by
   convertBusy(), pre-hidden before every structural op.

5. **Track Piece panel over a follower = the run head** (user
   spec): the x/y/z rows show and edit the RUN HEAD
   (FvdDesigner.placementTargetIdx/placementTargetRef —
   followers follow by compile, the head's carry moves bridged
   targets; posOwned = the target's own bridge rule); the anchor
   label's hover reads "Moves the run's first piece (the anchor)
   — every connected piece follows rigidly." — or, with the
   head's Lock Track Shape OFF (review), "…; with Lock Track
   Shape off, bridged pieces re-solve instead of following."
   Yaw/pitch/roll rows AND their padlocks stay disabled with the
   LIVE reason "This section follows a strict seam — its
   orientation is edited in Strict Seam ▾. Anchor orientation is
   editable while a section before the first strict seam is
   selected."; the old shared bridge-or-seam gray text became a
   bridge-only sentence; pitch/roll under an
   optimize-orientation bridge show the march sentence (they
   showed the padlock text before). The design readout +
   lockShapeRows stay hidden for followers (seam-owned).
   grayHint's Supplier overload re-resolves on every tooltip
   show (rows can stay disabled across a bridged-in → follower
   switch).

6. **The seam undo bug** (his report): ROOT CAUSE —
   applySnapshot's re-open hid the old pop-out while seamPopup
   still pointed at it; Popup.hide() un-focused the auto-focused
   spinner editor, whose focus-loss commit re-parsed the "#.##"
   text into a value ≠ the stored full-precision angle, and the
   value listener ran that as a user edit on the ORPHANED piece:
   pushUndo of the just-restored state + redoStack.clear().
   Fixes: (a) GraphEditor.hideSeamPopup() = NULL-BEFORE-HIDE
   with seamSyncing held — every seam teardown routes through it
   — plus `pop != seamPopup` identity guards on every handler
   (STANDING rule for every popup: null the field before hiding;
   the speed popup, the density preview and the Track Piece
   teardowns were reordered too); (b) FvdDesigner.commitTyped:
   an UNTOUCHED editor (text == the converter's rendering of the
   current value, isUntouchedEditor) is a no-op — the same
   phantom step / sub-0.005° nudge existed on every
   numericEditor spinner (panel yaw click-away, Alt+Tab with a
   window open); the D212 blank→0 branch stays; (c) Apply
   commits a typed-but-unentered box explicitly
   (commitFocusedNumericEditor) before the muted teardown, and
   Cancel DISCARDS typed text — the old hide-first Cancel
   silently APPLIED it after the revert, a latent bug closed for
   free; Ctrl+Z with typed text discards it too. Known hazard,
   deliberately not deferred: the undo re-open still runs in
   place (D227 timing) — a hide-without-reshow inside the
   popup's own key dispatch could log an FX EventRedirector
   ConcurrentModificationException, never history corruption.

7. **Live tool windows** (user spec — ONE mechanism):
   FvdDesigner.modelHooks + fireModelHooks(), fired LAST in
   rebuildTrack (and inside its early returns) and from
   GraphEditor.redraw() after the redrawHooks loop (re-entry
   guard, docApplying held, copy-iterated, per-hook try/catch
   logged) — hooks are READ-ONLY resyncs, never
   pushUndo/markDirty. Registrations (add on show, remove on
   hide): the node editor, the seam pop-out (seamHook →
   seamSync, off in hideSeamPopup), the speed popup (spHook;
   every capture of the control converted to
   spSel()/spSelRunSec() index resolution — a real edit in a
   still-open panel wrote a stale object before), the
   initial-speed dialog (index-resolved, a `typed` flag the
   Set-Design-to-Real fill also sets), Coaster Options
   (statistics + name), the Track Piece panel (opsState +
   syncPlacementSpinners), StartPanel (wizardHook), Export
   (re-reads the live compile at export time; piece labels
   live). FOCUS PRECEDENCE: typing wins — setUnlessTyping skips
   a focused editor — but an undo restore outranks it
   (docRestoring spans applyDocState + rebuildTrackRestoring).
   Apply-only dialogs (Element, Add-section, Design → Real,
   Insert) re-resolve their targets by index at Apply and post a
   stats reason when the target changed. THE DENSITY PREVIEW IS
   A TRANSACTION: FvdDesigner.convertBusy() posts
   CONVERT_BUSY_MSG "Finish or cancel the open convert preview
   first." and refuses gizmo ball drags / arrow + ring presses,
   every document spinner (placement, bridge,
   heart/gauge/friction/v0 — snapped back muted via
   revertSpinner), the Track Piece ops buttons, the seam + speed
   popup writes, reveal-ball single AND double clicks (review —
   the implementer's "clicks still activate" reading reversed)
   and plain Ctrl+Z from tool windows or the preview popup (a
   range chain's Ctrl+Z steps back, item 10). The bridge-end
   track ball's drag routes through placementEdit now (rigid
   carry + wizard sync apply there too); Mirror acts on the
   piece active at click time.

8. **Reveal balls on the whole run** (user spec; Viewport3D
   record Reveal(piece, kind) replaces the int): one white ball
   per JUNCTION of the active run — the head's entry (kind 1),
   each follower's seam ball at seamWorldPos (kind 1), each
   trailing bridge's end ball at the next piece's x/y/z (kind 2;
   a piece bridged in from within the run gets NO separate entry
   ball — two coincident balls would z-fight — and its
   double-click opens the BRIDGE rows). Single click = reveal +
   ACTIVATE (kind 1 → (piece, 0) and node 0; kind 2 → (piece+1,
   0)); double-click activates first, then the owner's settings
   (bridge-end → the bridge SECTION so Bridge properties syncs);
   drags never activate (D188). The reveal clears only when its
   piece leaves the run (was: any piece switch); gizmoSigNow
   signs every run piece + `seamWorldPos != null` per follower
   (the seam ball appears exactly when a compile places the
   junction); the trailing-bridge gizmo paths are piece-indexed
   overloads (no-arg forms delegate to the active piece bitwise;
   the POV clamp only for the active piece).
   revealEntryGizmo(idx) is Copy's handoff. Behavior change
   flagged: clicking the head ball while another section was
   active now activates section 0 and selects node 0.

9. **Badge conflict indicators** (user spec): ≠v is ENTRY-ONLY —
   sectionDrifted = |true − design| > DRIFT_EPS 0.2 at the
   span's FIRST sample (the whole-span D106 predicate lives on
   as sectionDriftedSpan for the true strokes and B's real-split
   fallback) — and " ≠°" = an ORIENTATION conflict
   (sectionOrientConflict → orientConflictAt: free piece
   TrackPiece.orientConflict(), follower = the seam tilt with a
   horizontal axis); both can show ("Force ≠v ≠°"). The info
   line drops " · true splits inside" and gains a second
   disabled line "orientation: design pitch …° roll …° · real
   pitch …° roll …°" (followers: design elevation/bank · seam
   elevation/bank; NaN "—"). The resolve submenu opens for a
   pure orientation conflict too, with a disabled first line
   stating the scope ("Resolves: speed only" / "…orientation for
   this section through an end you choose (the rest of the piece
   keeps its shape behind strict seams)" / "…speed + orientation
   for …"); under an OPT_POS_YAW bridge both items gray with the
   march sentence; the D141 smoothing hint only for a pure speed
   conflict; smoothingCausedDrift measures the entry sample so
   the red band agrees. DIVERGENCE flagged: the badge tests the
   RAW entry gap while the reconcile keeps its D237 amplified
   gate — a sub-0.2 m/s seam gap over a slow interior can split
   the true strokes with NO ≠v (a one-line change inside
   sectionDrifted if he objects). pieceTilted still reads the
   true tier only (no visual difference while design == true).

10. **Range-scoped Resolve conflict** (user spec — "resolve
   conflict over multiple sections"; supersedes D102's
   one-section-at-a-time on ORIENTATION-conflicted pieces; pure
   speed conflicts keep the single-section ops bitwise): the
   badge's two items resolve sections k..m behind INSERTED
   strict seams — Coaster.isolateRange(p, k, m, dt) =
   makeSeamStrict after m (unless m is the piece's last
   non-bridge section; a trailing bridge is never in a range —
   it re-solves as bridges do) THEN before k: the upstream keeps
   its conflict as its own piece, the remainder becomes an
   all-locked follower keeping the conflict (local shape
   bitwise, world placement to Euler dust). k == last
   (one-section pieces included) resolves alone; otherwise the
   WINDOW_MODAL "Resolve orientation conflict" dialog picks the
   end ("Section j — <badge label>", default last, note "A
   strict seam after the chosen section keeps the rest of the
   piece exactly as it is."). "Set Design to Real" =
   Coaster.refreezeRange(rp, dt) (the refreezeSpeed prologue —
   bakeTilt / bakeFollow — then refreezeSpeedOnly(rp, 0) and
   unpin the rest; ONE undo step): equal to today's whole-piece
   op restricted to the range only to the dust the whole-piece
   bake introduces at the seam (1e-7 measured, RangeResolveGate
   g2c bound 1e-6 — never claim bitwise; with a force section
   before the range today's op reshaped it, which IS the
   feature). "Convert Real to Design" = a CHAINED density
   preview (RangeChain), one section at a time, every
   intermediate document honest: step i isolates, makes [i+1..m]
   a locked follower, makeGraphsReal, and absorbFollower
   (followPrev off + attachPiece, no pin) re-joins the converted
   sections into ONE piece; s/c members apply directly; one
   Apply = one undo step; Cancel keeps earlier Applies; Ctrl+Z
   inside a step (chainStepBack) re-opens the previous section's
   preview, past step 0 = the pre-isolation document. Seams are
   LEFT strict afterwards (un-stricting the UPSTREAM seam
   re-chains the range onto the conflicted design chain and
   visually undoes the resolution); the remainder is a
   piece-first, never reconciled, so an honest ≠v can open on it
   after Set Design to Real. Gray reasons (grayItem, full
   sentences): a smoothed strict seam on the piece or the
   follower right after it (conservative — even when m < last),
   a reversed section in k..last, an uncompiled span, a lane
   section under 4 samples. LEAK FIXES: makeGraphsReal tests `<
   4` samples BEFORE baking; beginDensityPreview's refused first
   run restores pvBefore; refused ops snapshot before and push
   only on success (rollbackTo preserves the dirty flag —
   review); the dialogs re-check orientConflictAt + run
   membership at Resolve (review).

11. **Connect sections keeps both values** (user spec;
   supersedes D131's averaging and D132's held-side averaging —
   Coaster.mergeSections, MERGE_EPS 1e-9, mergeStepLen(domain) =
   0.01 s TIME / 0.1 m DISTANCE mirroring GraphEditor.MIN_GAP /
   minGapOf, the three must stay in step): an UNMATCHED seam
   becomes a STEP over δ — left node = A's end at seam−δ (in
   handle kept with its dv rescaled by (gA−δ)/gA, the D105 slope
   rule; out handle flat at DEFAULT_FRAC, straight/level locks
   cleared — a levelOut there would level the right value onto
   the left every redraw), right node = the seam node with B's
   value (in flat, out handle + locks = B's verbatim), each
   broken unless its outer slope is flat (a kinked "smooth" node
   is a lie — the next drag would mirror the partner); equal
   values with differing slopes → ONE broken node; A's last gap
   < 2δ mirrors the step onto B's side, both short → δ' =
   max(gap)/2, two one-node lanes → the old average as the last
   resort. Held lanes: b0 keeps its value (in flat, out VERBATIM
   — it was halved through the average before) plus a flat hold
   node at seam−δ when the values differ. Matched seams, the
   seam-node construction and the D160 controls/marks append are
   bitwise (MergeGate part 1 recreated the LOST D131 fixture and
   was shown green on the pre-change code FIRST). The physics
   sees the step as a one-to-two-cell keyframe ramp on the 0.01
   s / 0.05 m bake grid — a 0.8 g step over 0.01 s is an ~80 g/s
   jerk BY DESIGN, that is what "very steep" means; the two step
   nodes sit exactly one minimum transition apart, so they
   cannot be dragged toward each other (drag clamps); a saved
   file writes 0.01 s / 0.1 m node spacing there (reload
   bitwise). No label change: "Connect sections (merge into
   one)" / "Connect with previous/next section".

12. **Seam menu "Insert section…"** (user spec;
   Coaster.insertSectionAtSeam(piece, sec, added,
   lockDownstream, dt)): on a plain section boundary right after
   Connect and on a strict seam right after "Strict seam
   orientation…"; grayed "This boundary isn't compiled yet." The
   "Insert section" dialog = the Add dialog's rows in the D143
   order plus a THIRD Type radio "Straight / Curve" (grays
   Domain / Rate units / Roll axis / Roll behavior with one
   reason; the s/c pick keeps the fork and the padlocked speed —
   a custom speed on a straight pins it, mirroring the lane
   path), the fork row when the seam is speed-conflicted
   (seamDrifted(kLeft), the generalized pieceEndDrifted), the
   padlocked design speed (custom clamp [0.1, 1e4] per D198), an
   "Initial length (s)/(m)" spinner (0.1..10000, typable; label
   follows Distance / s/c; DEFAULTS the wizard's NEW_START_LEN 5
   s / CONT_HOLD_DIST 10 m per the brief — NOT the '+' hold's 1
   s, trivial to flip; per-domain SESSION memory
   GraphEditor.insLenTime/insLenDist like D236's wizard slots,
   s/c shares meters, a type/domain flip folds the current value
   into the outgoing slot) and, on NON-strict seams only, "Lock
   Subsequent Track Shape" (default off; tooltip "Adds a strict
   seam after the inserted section so the track after it keeps
   its shape (it moves to the new exit)."): ON = makeSeamStrict
   FIRST on the pre-insert compile — the downstream becomes an
   all-locked D235 follower frozen at its present shape AND
   orientation, then moves to the new exit; its design speed
   stays independent, so an honest ≠v can open at the new seam.
   OFF = a plain list insert: the downstream re-solves from the
   new exit against gravity and the reconcile re-freezes its
   speeds — the '+' continuation semantics. On a strict seam the
   section appends to the UPSTREAM piece before the seam; the
   follower's held angles stay, axes the user unlocked re-aim to
   the new exit (D135 verbatim, no forced relock). The Type
   default follows the upstream section. The '+' hold recipe was
   split into buildLaneHold / buildScStraight (the '+' path
   bitwise). Add commits a typed length first, re-validates by
   index (piece, section count, strict-ness, drifted) with a
   stats reason, refuses an uncompiled boundary BEFORE pushing
   undo; one undo step; hideSeamPopup before a lock insert only
   (piece indices shift).

13. **Review round** (16 findings applied; the model ones folded
   into items 2, 3, 5, 7, 10 above): designToRealDirect bakes
   the follow for an active follower (after a followContract
   pre-check); the export dialog's hook de-registers on
   WINDOW_HIDDEN; the follower padlocks gray with the seam
   reason; the Design → Real absolute-angle dialog takes
   installDocUndoKeys; the strict-seam pop-out is pre-hidden
   before every structural op (Un-strict, Split at seam, delete
   piece, the attach flows, Switch Coaster, the range ops, lock
   inserts) and self-validates otherwise — its teardown reason
   also fires honestly when some OTHER structural op shifts its
   index without pre-hiding; refused ops (runRefreezeRange, the
   chain's s/c branch, Insert, Copy, seamDesignToReal) snapshot
   before and push only on success. OPEN design question (his
   call): whether placementEdit should carry a pure TRANSLATION
   downstream even when the run head has Lock Track Shape off —
   only the tooltip was made accurate. JavaFX-layer work was
   verified by compile and reading only (no UI run this
   session): TESTING #164 is the walkthrough.

Verification: five NEW gates — coaster.SeamShapeGate 31 (attach
keeps a design-tilted piece's shape; recorded pre-D247 checksums
pin the designed=false follower paths, re-record them on any
deliberate physics change; seamRealToDesign / bakeFollow; the
lockstep invariant + pole guard; run-head translate;
attach→detach in place), RangeResolveGate 23, CopyGate 73,
MergeGate 23, InsertGate 40 — plus the full battery green on
wiped classes: DetermGate197/198/199/200 (6/4/10/7), LoadGate196
7, OrientGate 23, OrientGate2 18, Phase2Gate, Phase3Gate,
DomainGate, MultiCoasterGate 15, SmoothRealGate 15,
InflSplitGate 22, ReanchorGate 3, coaster.ConflictGate 8,
MirrorGate 20, YpAbsGate 22, coaster.DecompProbe,
InflMatchProbe, Nl2ElemProbe 22 (25 members).

## D248 (v19.19) — Batch 33: section + junction NAMES (≠o, run-wide titles, the bold title row), RUN AS PIECE (selector, attach/bridge, export, wizard placement), the THREE LIVE WINDOWS (Track Piece ▴ / Strict Seam n ▴ / Strict Seam n (bridge) ▴ — Lock Track Shape unlocked by default + Only Affect Orientation Until Next Strict Seam), gesture round (Shift/Ctrl arrows, C1 append, start-diamond resize, one-sided band edges, the init-speed spinner, lane 0 on create), pristine-lane END FOLLOW, the viewport round (marker double-click, the screen-space gizmo picker), the SEE-THROUGH FLOOR; 33b: VIEWPORT DISKS (draggable speed-control starts/ends + light-gray boundary disks on the track, gizmo select deselects nodes), the strip's DESIGN-SPEED line + the Speed/Acceleration, "Initial design speed…" and "Resolve section conflict: …" renames, the INVISIBLE LATERAL-G STROKE under an orientation conflict (root-caused)

Date: 2026-09-23, his batch-33 list (the naming / windows / runs
round after D247, with a gesture list and the "underground track
should read as behind the floor" report). Ten items — the seven of his list plus three late "33b"
additions the same day (viewport disks, the strip's design-speed
line, the lateral-G stroke bug — items 9-11) — each with its own
review-and-fix round; the docs round folds the review fixes into
the items below. The program rename is answer-only — item 12.

1. **Names: ≠o, run-wide section titles, the bold title row,
   the resolve submenu title** (user spec; coaster.SectionNames —
   a NEW pure class so LabelGate pins every string headlessly;
   GraphEditor.sectionTitle / junctionOrdinal / junctionTitle /
   ownerButtonLabel are thin wrappers on the active coaster). The
   orientation-conflict glyph is "≠o" (lowercase o — was "≠°";
   swept through code, comments, Help, CLAUDE.md, TESTING and the
   SeamShapeGate labels; this log keeps its history verbatim).
   Section TITLES are numbered PER TYPE across the WHOLE RUN in
   run order — "Force Section 1", "Geometric Section 2", "S/C
   Section 1" (judgment: he wrote "S/C Curve 2", but an s/c
   section can be all straights — "Section" like the other
   three), "Bridge Section 1"; flags, domain, influence and
   controls never change the type word, they stay badge TAGS in
   the fixed order " !" (dirty bridge) " ·m" " ◀" " fx" with the
   glyphs " ≠v" " ≠o" after them, space-separated ("Force
   Section 2 ·m ◀ fx" — the old glued "Geo·m" no longer reads
   with a number between); numbering restarts per run (a
   detached piece is its own run) and structural ops renumber on
   the next redraw (an open Apply-only dialog keeps its open-time
   title — accepted). The drawn BADGE takes a WIDTH LADDER
   measured with a real Text (Font 10; the old length×6+10
   estimate under-measured): the full title + tags + glyphs when
   the strip is wide enough, else the SHORT form "Geo 2 ≠v" (the
   number never drops before the type word), else no badge
   (today's rule). The badge menu's FIRST row is the PURE title
   (no tags — the badge above wears them), BOLD, never
   highlighted or focused-looking and never closing the menu
   (labelItem — the CustomMenuItem + Label pattern factored out
   of grayItem, inert = an inline transparent background on the
   skin's container + an inline text fill, which outrank modena's
   :focused rules; hideOnClick false; the D239 self-measuring pad
   kept); the D247 speed and orientation INFO LINES are GONE and
   their numbers moved into the title row's TOOLTIP
   (sectionInfoText: "Design speed %.1f m/s" + " (authored %.1f)"
   for influenced sections + " · entered %.1f m/s" when drifted;
   a second line joining "distance domain (m)" / "global axes" |
   "global roll" | "global yaw/pitch" / "rates °/m" / "absolute
   roll" / "absolute yaw/pitch"; an s/c section's "%.0f m, %d
   elements"; the D247 orientation line while conflicted).
   Flagged: the tooltip is now the only textual home of an s/c
   section's length + element count and of the design/authored
   speed. The resolve submenu is TITLED "Resolve conflict: speed"
   / "…: orientation" / "…: speed + orientation" and the D247
   disabled "Resolves: …" first line is REMOVED (the range scope
   is stated by the end-section dialog and Help; when k == last
   no dialog appears, so no in-menu scope statement exists there
   — flagged; item 10 re-titled it "Resolve SECTION conflict: …"
   the same day and moved it below "Initial design speed…"); the
   leftIsBridge branch's lone disabled explainer
   became the two ops grayed with "The bridge before this section
   re-blends to follow any resolution — convert the bridge
   first." The range dialog reads "Resolve from <title> through:"
   with "<title> + tags" items, rangeResolveWhy names the section
   ("<title> is reversed — …" / "<title> is too short to measure
   — lengthen it first."), the chain stats line reads "Range
   resolve — <title> (%d of %d): …", the Orientation dialog
   "Orientation settings — <title>" / "Influence orientation
   settings — <title>", Convert "Convert — <title>", Insert
   "Insert section after <title>". JUNCTIONS = every piece
   boundary INSIDE a run — plain strict seams AND bridge exits
   counted together in run order: "Strict Seam n" / "Strict Seam
   n (bridge)" (the bridge test runs FIRST, so a bridged-in
   piece's stale followPrev is inert; a chain head → bridge → P2
   → strict → P3 reads "Strict Seam 1 (bridge)" / "Strict Seam
   2"); the seam menu item is "Strict Seam n…" (was "Strict seam
   orientation…") and the seam window's head follows it live. The
   init-speed dialog's enabled "Set Design to Real" tooltip is his
   VERBATIM text incl. the missing trailing period: "Fills box
   with the real entry speed, resolving a speed conflict. Does not
   resolve an orientation conflict". OWNER BUTTON: the Orientation
   settings dialog ends with a button labeled EXACTLY the toolbar
   text that owns the section's placement — ownerButtonLabel:
   "Track Piece ▴" while no junction lies left of the section,
   else "Strict Seam n ▴" / "Strict Seam n (bridge) ▴" (a bridge
   section is owned by its own exit junction — for an orphan the
   would-be ordinal, which item 3 grays); tooltip "Opens the
   window that owns this section's placement."; it CLOSES the
   dialog first (its k is active-piece-relative and the bridge
   route activates the previous piece's bridge) and opens the
   window FRESH (review: it routed through the toolbar TOGGLE and
   CLOSED an already-open panel — a click doing the opposite of
   its label; and the run-head route opened the ACTIVE piece's
   panel, not the dialog's — openOwnerWindow(p, k) activates p
   first when the modeless dialog outlived an activation change,
   behind the same convertBusy guard); grayed "Placement is
   edited in the start screen while it is open." under the
   wizard, "The piece is gone." if the piece vanished. RUN
   TOPOLOGY moved onto Coaster as the ONE definition
   (endsWithBridge / followsPrev / bridgedInto / runStartOf /
   runEndOf — GraphEditor's runStart/runEnd/followsPrev/
   endsWithBridge delegate; LabelGate section 8 pins the
   equivalence on every fixture). Rejected: naming text on
   GraphEditor (not gate-able headlessly); title + tags on the
   menu row (the badge already shows them).

2. **Run as piece** (user vocabulary — a connected RUN is "the
   track piece"; Coaster.runHeads / runCount / runOrdinal /
   canMoveRun / moveRun / connectStrictRun + the static helpers
   runLengthM / runSampleRange / runTailEnd): the toolbar "Piece
   k/n ▾" selector lists RUNS — one entry per run head, "✓ Piece
   %d — %s at (%.0f, %.0f, %.0f)" with the run ordinal, the WHOLE
   run's length and the HEAD's anchor, ✓ and k/n keyed off
   runStartOf(activePiece) (a member-active state after a 3D
   click on a follower still shows the right run); hidden with
   one run (a strictly-split or bridged single run hides it —
   accepted); picking the active run is a no-op (no zoom jump),
   another run activates its head's section 0 with auto-fit; the
   OPEN wizard's piece grays "Finish or cancel the open start
   screen first." (review: the gray keyed off isPendingPiece,
   which requires !picked(), but the wizard has opened picked
   since D232 — it NEVER fired; FvdDesigner.isWizardPiece is the
   test now, and addBridgeTo/attachFloating refuse that piece at
   apply time too); every entry grays with CONVERT_BUSY_MSG while
   a convert preview owns the graph (setActivePiece would cancel
   it). '+' "Bridge to piece ▸" / "Attach floating piece ▸" list
   only OTHER runs' heads ("Piece %d — %s (next)" / "Piece %d —
   %s"): a non-adjacent pick MOVES THE WHOLE BLOCK (moveRun —
   head..runEndOf contiguous, order kept, no field touched except
   the head's inert followPrev := false; returns the new head
   index, activePiece := that − 1 and app.lanes re-pointed via
   lanesFor); canMoveRun is the DRY RUN checked BEFORE
   pushUndoSnapshot (refusals never clear redo): a non-head, a
   destination inside the block, a bridge-ending destination
   ("this piece ends with a bridge; extend the next piece
   instead."), and a block ending in an ORPHAN bridge unless it
   is already the list's last run ("This track piece ends in an
   orphaned bridge — delete or convert it first." — a bridge-
   ending block moved anywhere else would seize the next run as
   its target and, under an optimize mode, MOVE it on the next
   rebuild; an orphan-bridge run is necessarily last, so "lands
   at the list end" collapsed to "already last"); "Bridge refused
   — <reason>" / "Attach refused — <reason>" on the stats bar at
   apply time; both flows pre-hide the seam AND speed popups
   (spAfterRebuild resolves by INDEX only — a reorder would resync
   it onto another section's control) and DROP the pane's
   gizmo reveal before the reorder (review: Reveal(piece, kind)
   holds a piece INDEX which the D247 run-membership reset kept
   — the revealed rig silently re-rigged another piece). Inside a
   moved block nothing reads the list index, so per-piece
   compiles are bitwise across the move and save→load bitwise
   (RunGate). The EXPORT dialog's "Track piece" picker lists runs
   too ("Piece %d/%d  (%s)", hidden for one run; a run exports
   its contiguous sample range head-first-span..tail-last-span
   incl. bridges — the D84 seam-sample prepend is DEAD for run
   heads (a head is never a continuation) and was dropped, which
   also closes the old per-piece export's quirk of prepending one
   FOREIGN sample from the previous piece; a live refresh whose
   head list changed resets the pick to "Whole track"; the pick
   is read AFTER settleIfPending — review). The floating wizard
   places 5 m past the RUN TAIL's end (Coaster.runTailEnd — was
   the active MEMBER's end) and "Add floating piece…" grays /
   openFloatingWizard refuses when the list ends in an orphan
   bridge ("The coaster's last track piece ends in an orphaned
   bridge — delete or convert it first." — the copyRun hazard: a
   piece appended there is seized as its target). deleteSection
   normalizes an inert successor followPrev. The dead per-piece
   length helpers (pieceLengthM / pieceLengthLabel) are removed
   (runLengthLabel). Switch Coaster / Reverse / Mirror stay
   PIECE-scoped (accepted-and-documented; a follower can still be
   reversed / mirrored / switched alone — Switch Coaster as a
   block move is the natural follow-up, and switchPieceCoaster's
   destination-list append has no orphan-bridge guard yet —
   deferred, flagged). Cosmetic: the apply-time fallback yields
   "Bridge refused — This track piece ends…" (capital T
   mid-sentence, the standalone tooltip sentence reused); the
   gray WIZARD_OPEN_WHY ends with a period while the pre-existing
   focusOpenWizard stats text has none — his wording call.

3. **The three windows** (user spec — the Track Piece panel, the
   Bridge properties panel and the strict seam pop-out become ONE
   kind of window "like the node editor": coaster.LiveWindow, a
   NEW JavaFX class; FvdDesigner.openTrackPieceWindow /
   openBridgeWindow / GraphEditor.showStrictSeamSettings rebuilt
   on it). OWNED Stages in the light default Modena (black text —
   the dark-popup -fx-text-fill: white styles dropped; D232's
   dark seam verdict reversed by this request), PLACED explicitly
   just above the graph canvas (bottom edge at the canvas top,
   left at the toolbar button / the seam strip / a node / the
   pending gizmo double-click anchor; clamped into
   Screen.getVisualBounds, never a hardcoded size — an opacity-0
   show + setOnShown placement, because a pre-show prefWidth()
   measurement made JavaFX cache a clipped "No optimizati…" radio
   it never re-clipped, found by the winshot render); the node
   editor takes the SAME placement (anchored at its node's screen
   x). Values apply LIVE through the existing chokes
   (wireDocSpinner / seamEdit / bridgeTargetWrite), handlers are
   INDEX-resolved (Supplier), modelHooks + docRestoreHooks
   resync, installDocUndoKeys, null-before-hide teardown,
   convertBusy() gating, seamLive-style self-validation ("The
   strict seam window closed — its seam no longer exists." / "The
   bridge window closed — its bridge no longer exists."). Footer
   [Confirm][Cancel]: Confirm = Enter = the X = keep (the focused
   box commits first; Esc / Enter arrive through a scene KEY_PRESSED
   FILTER limited to those two codes — a Spinner editor consumes a
   bubbling Enter and Esc, so a handler and the default / cancel
   buttons never saw them while a box had focus, review round 2);
   Cancel = Esc = a FIELD-LEVEL revert to the
   open-time record (the seam pattern — a DocState snapshot would
   also undo unrelated graph edits made meanwhile; the bridge
   record includes lastOptDist / optLen / noneStash so D97's
   OPT_NONE round trip survives, and Cancel re-marches at
   lastOptDist in optimize modes stamping the signature so the
   next rebuild settles in one pass). The toolbar button TOGGLES
   (open = close WITHOUT revert — Confirm semantics, matching the
   old second-click hide); gizmo double-clicks and the owner
   button open FRESH (openPlacementPopup(boolean)). ONE LAYOUT
   unified on the bridge look: title; [bridge only: mode radios +
   Length / Optimize length]; the orientation rows VERTICAL, one
   label each — Yaw = [label][spinner][↺] (+ the bridge's yaw
   lock); a thin separator; Pitch = [label][spinner][🔒][↺]
   [design readout]; Roll the same — the padlocks styled like the
   bridge locks (right side, LOCK_OPEN/LOCK_SHUT red/green), the
   ↺ smaller and matched (revert to the open-time value — NEW on
   the Track Piece and bridge rows), the grey "design 12.34°"
   readout right of the ↺ shown only while orientation-conflicted
   (followers: design elevation/bank via the D247 seam
   predicate; replaces the single "Design orientation: …" line);
   then the x/y/z row ("Anchor x/y/z (m)"; bridge: "Next piece
   x/y/z (m)" in manual mode); then the FLAG ROW "Lock Track
   Shape" + gray "(creates orientation conflict)" with "Only
   Affect Orientation Until Next Strict Seam" to its right; then
   "Design → Real Orientation" / "Real → Design Orientation"
   while conflicted; [Track Piece only] the ops row Reverse… /
   Mirror / Copy / Switch Coaster…; Confirm / Cancel. Track
   Piece rows read "Anchor yaw (°)" / "Anchor pitch (°)" / "Anchor
   roll (°)" (the one pitch/roll row split), the seam window "Yaw
   (heading °)" / "Pitch (°)" / "Roll (°)" (the seam padlock
   SEMANTICS kept: 🔒 holds the typed angle, the tooltip says the
   Track Piece window's padlocks are edit guards — the opposite
   sense). No lock on the Track Piece yaw row (a free piece has no
   yaw guard flag, D153 — adding one for symmetry rejected).
   TOOLBAR: "Track Piece ▴" ALWAYS (its x/y/z rows = the RUN
   HEAD unconditionally — placementTargetIdx generalized from
   D247's follower-only rule; a bridged-in active piece's own
   position lives in the bridge window's "Next piece x/y/z" row)
   plus exactly ONE junction button through ownerButtonLabel:
   "Strict Seam n ▴" while the nearest junction left of the
   active section is a plain strict seam, "Strict Seam n (bridge)
   ▴" while the active section IS a bridge (its own exit) or the
   piece is bridged-in (opening the PREVIOUS piece's bridge
   window); none before the first junction; an ORPHAN bridge
   shows the (bridge) button GRAYED "This bridge has no following
   piece." (never a silent nothing); window titles = the same
   text without the ▴; the bridge badge's top item reads "Strict
   Seam n (bridge)…" (was "Bridge properties…"). The bridge
   window addresses its bridge BY PIECE (GraphEditor.withBridge /
   bridgePieceCtx scope the active-section bridge API — the old
   auto-hide popup could never outlive an activation change, an
   owned Stage can; the strip end-line drag and gizmo paths keep
   the active-section scoping bitwise); the Track Piece window
   FOLLOWS the active piece (today's panel rule) and RE-CAPTURES
   its ↺/Cancel record when the active piece or run head changes
   under it (Cancel reverts the current piece to the state it had
   when it became the window's — a per-piece record map is the
   follow-up if he expects the original piece reverted); the seam
   window is index-resolved (the D247 undo in-place re-open and
   applySnapshot's pre-hide are gone); structural ops that shift
   piece indices pre-hide BOTH junction windows
   (hideJunctionWindows). SEMANTICS: Lock Track Shape DEFAULT
   UNCHECKED everywhere (TrackPiece.shapeLocked = false; a
   MISSING porient line loads unlocked — supersedes D222's
   missing = locked; the compile reads neither flag, so every old
   file's TRACK is bitwise and only its edit routing changed:
   pre-v19 files load unlocked, v19.0-v19.18 files keep their
   saved state; wizardFinish no longer re-locks). Behavior change
   flagged: hand-made strict seams (Make seam strict / attach)
   now RE-SOLVE the follower's shape under seam edits unless Lock
   Track Shape is checked, and seamDesignLockstep's pole guard
   message shows on ordinary seams. NEW per-piece flag
   TrackPiece.orientOnlyToSeam "Only Affect Orientation Until
   Next Strict Seam" (default true; the porient line's 4th field,
   missing = true; PieceState / restorePieces / TrackPiece.copy;
   tooltip "The pieces after this piece's next strict seam or
   bridge keep their own placement settings."; grayed "Applies
   while Lock Track Shape is checked." while unlocked):
   Coaster.carriesDownstream(p) = p.shapeLocked &&
   !p.orientOnlyToSeam is the ONE predicate and carryAfterEdit
   the choke placementEdit calls — checked = NO carryDownstream
   at all, the edit stops at the piece's own exit junction of
   EITHER kind (judgment: the flag's NAME says "strict seam" but a
   bridge junction IS "Strict Seam n (bridge)" now; an OPT_NONE
   target keeps its stored placement, optimize bridges re-march
   from the moved head, a follower's LOCKED axes keep their
   absolute held angles while its position and unlocked axes
   follow by compile — a re-aim with a horizontal component can
   open a seam ≠o, accepted; "followers stay put in the world" is
   impossible for POSITION, the compiler glues their entry to the
   upstream exit); unchecked = the D221 rigid carry bitwise. The
   BRIDGE window's Lock Track Shape / Only-Affect are the TARGET
   piece's; routed target writes (setBridgeTargetOrient /
   gizmoSetBridgeOrient → setDispPitch/Roll(…, true), the rings'
   gizmoSetBridgeOrientAll → editPitch/editRoll) go through the
   target's lock: unlocked = design + real lockstep (re-solve,
   one re-march — verified no feedback loop: the march writes
   the TRUE tier only and the routed write happens once per user
   event), locked = real only (conflict); "Design → Real" on the
   target = designToRealDirect (the D240 absolute-lane dialog
   kept, with a new `after` hook so SIG_STALE is stamped only at
   the dialog's Apply — a cancelled dialog leaves the bridge
   signature alone) then the re-march re-lands position; "Real →
   Design" = true := design + re-march; ONLY Real → Design grays
   under OPT_POS_YAW ("The optimize-orientation bridge owns this
   piece's pitch/roll — switch the mode or lock the axes.") in
   ALL three windows — the Lock check and Design → Real stay
   enabled (supersedes lockShapeRows' wholesale gray); a
   shape-LOCKED target under OPT_POS_YAW re-opens its conflict on
   every re-march (accepted; its D→R tooltip appends "While the
   bridge optimizes orientation the next re-optimization may
   reopen it."). Copy/Mirror/Reverse/Switch keep their D247 gray
   reasons; the Track Piece window's follower gray names the
   junction live ("… its orientation is edited in Strict Seam n
   ▴. …"); anchor hover gains a third sentence for Only-Affect
   ("…; the pieces after its next strict seam or bridge keep their
   own placement settings."). Verified by the new -Dfvd.winshot
   dev hook (renders all three windows to PNG; it caught two real
   bugs — the bridge resolve row showing while unconflicted, and
   the Labeled clip). Not exercised interactively: Confirm /
   Cancel / ↺ / undo flows and the bridge Cancel re-march were
   verified by reading, the model gates and the render — TESTING
   #165 walks them.

4. **Gestures** (user list; coaster.Gestures — a NEW pure class
   holding the arithmetic so GestureGate pins it): (1) ARROW
   NUDGES take the D241 spinner conventions — Shift = one tenth
   of the step, Ctrl = the NEXT round multiple of the (possibly
   Shift-scaled) step FROM THE CURRENT VALUE (Gestures.nudge, the
   modStep recipe: floor/ceil with a 1e-9 guard so an on-grid
   node lands one cell over and an off-grid one never skips a
   cell; the old branch stepped THEN rounded, which could jump two
   cells and re-rounded the IDLE axis — an Up press could move
   t), both combine; the per-axis snap REFERENCES stay Ctrl-drag's
   (time: absolute grid or the previous node; value: absolute or
   the previous node — D241: drags keep their references, the
   spinner's absolute-only multiples rejected); modifiers forward
   through withSurf (strip parity — forgetting one makes the
   strip ignore the modifier); no end wall on arrows (the deliberate extend
   path). Behavior change flagged: on an off-grid node the first
   Ctrl press moves LESS than before. (2) APPEND (A /
   appendSecond / the dotted-hold click) after a SMOOTH SLOPED
   last node continues its slope: the previous node's OUT handle
   takes its IN handle's slope (Gestures.continueOut — toggleSmooth's
   C1 rule) and the new node keeps a flat sine in handle at the
   hold value (the segment overshoots the hold slightly —
   accepted; sloping the new node's in handle too would turn the
   hold into a ramp, rejected); broken / straight- or
   level-locked / 1-node / flat-in last nodes keep the historic
   flat out bitwise. insertHoldAfterSelected (A with a selection)
   continues the selected node's slope the same way AND re-angles
   the hold node's in handle to the inherited slope
   (Gestures.matchInToOut) so it is truly smooth — the sel→hold
   run slopes instead of a flat hold, a visible change; the hold
   node inherits a lock for the hold→next half (the
   insert-on-curve split rule; the far half silently unlocked
   before). Cases listed: the complex control graph (A never
   routes to the strip), s/c / bridge / legacy reversed
   (unchanged), the end wall (unchanged). (4) The speed-control
   START DIAMOND drag = a RESIZE FROM THE LEFT (SP_DRAG_START:
   x0 moves between the previous control's end and end − 2×the
   domain gap, the END fixed, complex graphs — both lanes —
   rescale by len/oldLen through the shared
   SpeedControl.rescaleLanes, no ripple since nothing after the
   fixed end moves); the body drag still MOVES, the popup's Start
   spinner keeps MOVE semantics (a position; Length is the
   resize surface), an abutting pair's shared spot still picks
   the END diamond first (existing, documented). (5) A smoothing
   band EDGE drag is ONE-SIDED (Gestures.edgeWindowLen): the
   window is twice the arc from the seam to the cursor on the
   grabbed side and 0 once the cursor crosses the seam, staying
   off while it continues past (the historic |·| re-grew it on
   the far side); the diamond's relative drag is unchanged; the
   same rule on the s/c INTERIOR blend edges (parity; the entry
   ease edge was already one-sided). (6) The init-speed dialog's
   box is a TYPABLE SPINNER (step 1 m/s, Shift 0.1, Ctrl whole
   multiples, hold-repeat, %.2f display, typed values at full
   precision; the factory range IS the D198 [0.1, 1e4] clamp,
   Gestures.clampInitSpeed re-applied at Apply as a belt;
   commitTyped before Apply; the D247 typed/fill flag semantics
   kept — the EDITOR holds the focus, so the typing test reads
   the editor, review). LiveNum rejected: it is for live-apply
   boxes. (7) DEFAULT ACTIVE LANE 0 (Vertical G / Pitch —
   Elevation under absolute yaw/pitch) when a coaster or track
   piece is created (wizard open AND Done), after every '+' add
   and seam Insert of a force/geo section (GraphEditor.defaultLane
   — activeLane was session state nobody reset: a user editing
   Roll got every new section with Roll active); load / Copy /
   piece switches deliberately untouched (an "every activation
   shows lane 0" option if he wants it).

5. **Pristine-lane END FOLLOW** (user spec — "lanes not edited
   since creation follow the section end"; LaneSection.endFollows
   [3] + pristineHold / touchLane / syncFollowingEnds, pure model
   so EndFollowGate drives the real thing): a hold lane is created
   with the flag SET (continuation / holdSection / the '+' and
   Insert holds / the start wizard incl. its Initial-size rebuild;
   split halves — section, piece, re-anchor, partial-convert kept
   half, make-smoothing-real — INHERIT; merges, converts, fits
   and delta lanes never; TrackPiece.copy deep-copies). The
   EDITOR owns the write (the enforceLocks precedent — never
   TrackCompiler/bake, which would break rebuild idempotence):
   touchLane clears the flag of the ONE lane being written,
   resolved by IDENTITY against the section's own lanes (a delta
   lane, a speed-control graph or a foreign list is a no-op), at
   every lane-writing sink — node/handle drags, nudges, the node
   editor, group moves, inserts, append, deletes, B (per written
   lane), smooth/broken, H/F/level/straighten, the lock toggles
   and menus; syncFollowingEnds then moves every still-following
   lane's last node to E = the latest last-node x over the
   DEFINING lanes (flag off, ≥ 2 nodes), floored at node 0 +
   the domain's minimum gap (seconds or meters) — values,
   handles and locks untouched (a pristine lane's dv are zero),
   growing AND shrinking the section (the old end wall stayed at
   the untouched lanes; a shrink can pull speed controls back in
   through the existing spClampControls); a wholly untouched
   section moves nothing (no defining lane); 1-node lanes neither
   define nor follow. The REDRAW-TIME BELT: a flagged lane that is
   no longer hold-shaped (pristineHold: exactly two nodes at the
   same value, DEFAULT_FRAC facing handles within 1e-9 — a de
   Casteljau cut leaves 1/3 ± ulps, a typed 0.333 is an edit —
   zero dv, no lock) is CLEARED and never moved, so a missed sink
   can never snap an edited lane back. Explicit syncs at the
   sinks run BEFORE their redraw + rebuild (the compile never
   sees a stale end; redraw can be a no-op while collapsed);
   guarded by docApplying / docRestoring; only the ACTIVE
   section syncs. Influence DELTA lanes never follow (their wall
   is the compiled duration); a hand-reverted edit stays
   "edited" (Ctrl+Z restores the flag). IO: the `lane k f` line's
   3rd field (1/0; old readers ignore it); a file WITHOUT it
   derives the flag in finishCoaster from the loaded shape —
   pristine AND at the section end AND only while the DEFINING
   end (the lanes that will not follow) is the section end too,
   so the derived state is a FIXED POINT of syncFollowingEnds (a
   hold short of the end stays off; so does a pristine hold that
   is the SOLE longest lane — it defines the end, and a derived
   true would have let the first follow write shrink it onto the
   shorter lanes: review round 2's '+' hold whose Gl node had
   been time-dragged short derived 101 and shrank the section on
   open; a draft before that derived it for any pristine hold —
   either way an undirtied, non-bitwise load); an explicit 0 on a
   hold-shaped lane is a deliberate non-follow that survives;
   SecState carries it; save→load→save byte-identical. Flags feed
   no geometry (compiles bitwise across flipped flags); the
   follow WRITE is an editor action, so compile/rebuild stay
   idempotent (DetermGate197/198 re-run — IO + SecState changed).
   Rejected: a derived predicate only (a lane edited and
   hand-returned to a flat hold would follow again — the flag is
   faithful to "not edited since creation"; the derivation
   survives only for old files, where that corner is accepted).

6. **Viewport** (user list; UI-only — the -Dfvd.pickprobe=1 dev
   hook drives the picker on the default document and exits 0
   when green): (1) NODE-MARKER DOUBLE-CLICK opens that node's
   LIVE editor (openNodeEditorFromViewport — the marker pick now
   runs BEFORE the double-click branch, so within 14 px of a
   marker the track insert no longer fires: no more 0.02 s-off
   inserts beside a node, flagged; refused with CONVERT_BUSY_MSG
   under the preview and "Finish or cancel the open start screen
   first." under the wizard; the entry ball's double-click keeps
   opening the Track Piece window — node 0's editor from the
   graph, accepted); the SELECTED marker is radius 0.26 vs 0.22
   (MARKER_R_SEL) tinted laneColor.interpolate(WHITE, 0.4)
   (MARKER_SEL_MIX) instead of the big white sphere (pale lanes
   may read near-white — single constants to tune). (2) A
   central SCREEN-SPACE GIZMO PICKER (option B — the house's
   pickMarkerIndex / pickTrackSample / ringHit precedent; option
   A's invisible proxies rested on an unprobed opacity-0 render
   skip): every rig part registers a GPart (handlers + the
   analytic shape it was built from) and a capturing pane filter
   picks in PIXELS — BALLS FIRST (zone = projected radius ×
   PICK_BALL_MUL 1.5 + PICK_BALL_PAD 6 px, so an edge-on ring
   crossing the center can never steal it), then the NEAREST
   arrow (segment distance ≤ PICK_ARROW_PX 9) or ring (48-point
   projected polyline ≤ PICK_RING_PX 6 — adjacent rings' bands
   overlap and nearest-by-px resolves it); every rig enumerated
   (piece, seam, bridge OPT_NONE, bridge optimize incl. the
   LENGTH arrow); the gesture rides gPress in the filter, never
   the pressed node; hover glow central too; gizmoSub is
   PERMANENTLY mouse-transparent (D189's fly/POV rule subsumed —
   the warped cursor can never hover it); the filter consumes
   ONLY primary presses whose target is the 3D content (targetIs3D
   — click-off hide, marker clicks, track activation, the
   collapse button and tangBtnRow keep working); fly / POV take
   nothing, but a gesture LIVE when fly/POV began still ENDS on
   its release (review: the early return left gPress set for
   good); a refused press under convertBusy posts the sentence
   ONCE (it used to refuse silently); a rig double-click still
   opens its window; visuals unchanged. (3) A DRAG-REVEAL hides
   the pressed track ball the moment the rig builds mid-gesture
   (revealDuringDrag — under the picker the capture no longer
   depends on the node, so hiding cannot lose the drag) and the
   release rebuild re-derives it; MID-DRAG MODIFIER COMBOS
   (Ctrl+Z, Ctrl+S…) are suppressed while a gizmo gesture is live
   — isGizmoDragging = past the deadband OR a part still pressed
   (review: a ball's first 3 px never set gizmoDragging, and a
   Ctrl+Z there replaced the piece under the pressed closures);
   bare keys unaffected. Also (review) dropGizmoReveal before a
   run move (item 2). A tool window's own Ctrl+Z mid-drag stays a
   pre-existing corner (documented).

7. **The see-through floor** (his "underground track should read
   as behind the floor"; coaster.FloorDepth — a NEW pure class
   holding the arithmetic so FloorGate pins it): the floor (sheet
   + line decades + main axes) draws AFTER the track,
   DEPTH-ENABLED — world order [trackGroup, povHolder,
   trackBallGroup] → [gridGroup, axisGroup] → [ghostGroup] — so
   above-ground track occludes it and underground track shows
   through the translucent sheet (alpha 0.10 unchanged — the
   lines carry the cue; a per-user opacity slider only if he
   asks) with the LINES AND AXES DRAWN OVER IT (the map's (b′);
   (a) sheet-only was the fallback and was NOT needed — one
   tuning round on the gate bounds, zero on the renders). The
   floor's own coplanar layers would z-fight (D242 fact (b):
   translucent 3D writes depth); the map's per-ring flat LIFTS
   were proved unworkable by the first two FloorGate drafts (the
   decade ladders don't align at ×10 and each decade is
   decentered ≤ 0.5q — far-corner-sized lifts either need ~3×
   growth per layer or land tens of px near the eye), so the fix
   is RANKED TILTED PLANES: lifting a ground point by h toward
   the eye moves its hit nearer by exactly h·z/|eyeY| in view
   depth, so layer rank r lives on the plane y = sgn · r ·
   FLOOR_LSB_MARGIN (2) · k · |eyeY| · z_view, k =
   1/(near · 2^DEPTH_BITS) — ONE Affine SHEAR per line group per
   frame (FloorDepth.shear, liftFloorLayers), exact at every
   pixel, ranks handed out DYNAMICALLY to the layers that draw
   anything this frame (sheet 0, each alive decade's x-lines then
   z-lines — the rings split into TWO MeshViews per decade so
   crossings are pixel-exact — then the axes), the sign flipping
   toward a below-ground eye (the sheet is CullFace.NONE — its
   underside draws), k off the ACTIVE camera's near clip (near
   clips unchanged — raising them would change fly/POV
   close-range clipping). The decades DRAW THEIR ZERO LINES again
   (D1 stands — a static mesh translated by round(eye/q)·q per
   frame cannot skip the WORLD zero; the implementer's skipZero
   punched a hole at the line nearest the eye in every alive
   decade — review fix); the axes sit on the highest plane, so
   the coincidence is fight-free and covered by the axes' width
   wherever the decade shows (D243's status quo, incl. its
   accepted 100 m-decade peek near the eye); the axis anchor
   quantum stays 30 m (the brief's 100 m was for the abandoned
   lift scheme). The on-screen float of rank r is r · m · k ·
   |eyeY| · subH / (2 tan(fov/2)) px — ∝ eye height, ≈ 0.0073 ·
   |eyeY| px per LSB-rank at 700 px / 60° / near 0.1 (twice for
   the 0.05 fly/POV clips): ≤ 1 px at a 100 m eye, coincident
   decades 0.58 px orbit / 1.16 px fly at an 800 m eye where the
   10 m decade is at 22 % and dead by 1.3 km, axes 5.2 px at the
   2.4 km very-high pose against a featureless sheet — accepted;
   a lifted far line could cross a 5 m-high track at 3 km
   (sub-decimeter width there). The lines lose ≤ 10 % brightness
   under the sheet blend — not visible on the nine original poses
   (A/B identical). The stalled GHOST track goes LAST with
   DepthTest.DISABLE — composited over floor and track alike (an
   above-ground ghost in front of the floor would otherwise cut
   a dark silhouette through the lines; an underground ghost stays
   visible; a ghost is faintly visible through nearer opaque
   track — accepted; back faces blend, denser ghost; still
   PICKABLE). Rails straddling y = 0: the sheet slices the tube —
   real depth separation. ORTHO: Top keeps the 2D orthoGridCanvas
   stroking lines over ALL track at any height (D242 made the
   canvas THE ortho grid; re-enabling the 3D rings there means
   D242's rejected hard-square-edge problem) with the sheet
   depth-tested and the axes at a fixed +1 cm (ORTHO_AXIS_LIFT,
   ≈ 20 linear LSB); Side hides the sheet as before — an accepted,
   documented difference from the 3D behavior. The strength law
   moved verbatim onto FloorDepth.ringStrength (bitwise) so the
   gate's alive/peak figures track it; 7 Affine writes per frame,
   skipped when unchanged; empty coasters get a floor. Risk: if
   prism's depth format were not 24-bit or the compare not
   LESS(EQUAL), FLOOR_LSB_MARGIN is the one lever. Verified by
   the -Dfvd.gridshot ladder EXTENDED to 15 poses with an
   UNDERGROUND fixture (the demo lowered so statMinY = −15,
   fricRoll 0.15 forcing a ghost; under-above / under-graze /
   under-below / under-far / under-ortho-top / under-ghost):
   dimmed underground track under crisp lines, crisp above-ground
   track, no patchwork or flicker. SUPERSEDES D242's "the track
   drawn later overdraws it; a below-ground camera never sees
   grid over track — accepted" and D243's "the axes draw AFTER
   the grid, submission order" (they draw after it on a higher
   rank plane now).

8. **Review rounds** (per item, folded above): L — the glyph
   sweep had corrupted its own comment, the owner button's
   toggle/active-piece routes; R — the wizard gray that never
   fired + its apply-time twin, the stale reveal index across a
   run move, the export pick read before the settle; W — the
   bridge resolve row showing unconflicted, the Labeled clip;
   V — the fly/POV early return that stranded a live gesture, the
   pre-deadband Ctrl+Z; F — the skipped world zero line; E — the derivation's
   at-the-end test; G — the s/c blend-edge side read in AUTHORED
   arc (a legacy reversed strip mirrored the pixel side); S1 — the
   strided drag polyline that skipped the section's END sample (an
   end disk could never reach the wall on long sections) and the
   auto-fit time window a strip diamond / mark drag froze and the
   strip's own release never unfroze; S3 — the force local roll
   lane's authored reference restored per the brief (the
   implementer had switched it to derived). Cross-item: the toolbar wording
   item 1 left to item 3 landed (ownerButtonLabel drives both);
   the D247 "Strict Seam ▾" / "Bridge properties ▾" / "Track
   Piece ▾" labels are gone from every surface.

9. **Viewport disks + node deselect** (his "33b" list;
   coaster.ViewDisks — a NEW pure class holding the arithmetic so
   GestureGate's section 7 pins it): every speed control's START and
   END sit on the 3D track as stylized DISKS centered on the
   HEARTLINE (core radius CTL_DISK_R 0.26 m — bigger than a node
   marker so a coincident marker never hides the handle —
   half-thickness 0.035 m plus a lighter outer ring at 0.30 m; a
   darker shade of the control's type color, CTL_DISK_SHADE 0.72 of
   the strip's speed green / accel blue, faint self-illumination
   CTL_DISK_GLOW 0.45; the SELECTED control's two disks wear the
   full color with a white rim CTL_SEL_RING; feel-tunable
   constants). The disk IS the diamond: a press
   (Viewport3D.pickCtlDisk → GraphEditor.spViewportPress — within
   PICK_PX 12 px it wins over the node markers' 14 px RELEASE pick)
   selects the control, gives the strip focus and begins the strip's
   own diamond gesture — kind 1 = the START disk = item 4's resize
   from the left, kind 2 = the END resize (the END diamond's clamp
   factored to Gestures.resizeEndLen, bitwise the inline arithmetic
   over 500 random clamps); the drag maps the cursor onto the
   NEAREST projected point of the section's sample polyline
   (ViewDisks.nearestOnPolyline — no radius cap, the cursor may
   leave the track; NaN = nothing projected, the control stays put;
   strideIndices always ends on the section's last sample — review:
   a strided walk skipped it and an end disk could never reach the
   wall on long sections), reads it back as the section's NATIVE x
   (ViewDisks.nativeAtArc — the inverse of the plates' historic
   arcAtTime lerp, moved here as arcAtNative and extended by spanLo
   so a continuation's x = 0 lands ON the seam sample; interior x
   bitwise) and hands THAT to spDragDiamondNative — the strip's
   write path with the strip's own clamps (min length 2× the domain
   gap, neighbor controls, section walls, Ctrl = the strip's native
   snap, complex graphs rescale BOTH lanes) — so the disk can never
   move further than the diamond can and the two coincide by
   construction (GestureGate 7d: disk write == strip write under
   every clamp, clamped writes bitwise on the wall). Every event
   rebuilds the track and redraws the strip live (gestureHot), one
   undo step per gesture (beginGesture / commitGestureSnapshot on
   the first real move — a clean click pushes nothing),
   settleIfPending at release, an unmoved DOUBLE-click opens the
   control's options popup at the click; a press on a piece OUTSIDE
   the active run activates that piece + section FIRST (track-click
   semantics; inside the run nothing activates — strip parity); a
   hidden strip opens COLLAPSED (the +V rule) so the mirrored
   diamond is visible; refused with CONVERT_BUSY_MSG under the
   preview and WIZARD_OPEN_WHY under the start screen (the strip is
   unreachable there — a disk must not become a back door); hover =
   hand cursor + the bottom-left combLabel "speed control start — x
   = 3.20 s" (end / m per kind and domain, read live by index);
   hidden in POV like the combs; Ctrl+Z mid-drag suppressed
   (isGizmoDragging includes diskDrag); disks build for EVERY piece
   of the active coaster (not the wizard's pending piece, not
   inactive coasters, not bridge / legacy-reversed sections — not
   speedControllable); the rebuild signature carries the selected
   control. Accepted: abutting controls' coincident disks pick the
   EARLIER control's END (spFindDiamond's order — B's start is
   reachable via its end disk or the strip); a disk under a white
   ball is the ball's (the screen-space picker consumes the press
   first); a track crossing itself in screen space may jump branches
   (the strip is the exact surface); plain speed MARKS get no disk
   this batch; Delete after a disk click removes the SELECTED
   CONTROL (strip focus rules); the node-marker toggle never hides
   disks. SECTION-BOUNDARY DISKS: small light-gray disks (BOUNDARY_R
   0.16 m, rgb(190,194,202), glow 0.40) on the heartline at every
   section boundary of every piece — ViewDisks.boundarySamples: each
   section's EXIT sample plus the piece's ENTRY sample, deduped by
   index and by DEDUPE_M 1e-6 m (a follower's entry coincides with
   the upstream exit; the next section's dropped continuation entry
   is NEVER placed — two disks one dt apart read as a bug; a
   smoothed seam's disk sits at the blended sample),
   mouse-transparent, never hovered, hidden in POV, rebuilt with the
   markers. NODE DESELECT ON GIZMO SELECT: any primary press on a
   registered rig part (track ball, center ball, arrow, ring) runs
   deselectFromViewport BEFORE the convertBusy gate (view state, not
   a document write) — selIdx/selSet clear, a strip node too, the
   control selection stays; the D247 entry-ball node-0 select is
   REMOVED (a ball click selects nothing; node 0 stays reachable in
   the graph; Copy's explicit node-0 handoff is untouched); safe
   mid-press only because refreshGizmo(false) never signs the node
   selection (comment at the call).

10. **The strip's design-speed line + renames** (his 33b list;
   coaster.SpeedStrip — a NEW pure class so StripGate pins it): the
   tall strip draws the DESIGN speed (Sample.v) as a SECOND dotted
   line wherever it differs from the TRUE speed by more than the
   lanes' threshold — SpeedStrip.split: |spVToY(design) −
   spVToY(true)| > TRUE_SPLIT_PX, purely PIXEL-based, no ≠v / ≠o
   flag read (so a tilted piece's line appears mid-section exactly
   like the lanes' true strokes, a pinned section splits everywhere,
   an unconflicted section never; a gap exactly at the threshold
   stays hidden). Design = DIMMER and THINNER — SP_SPEED_COLOR
   rgb(100,220,110) at DESIGN_W 0.8 with DESIGN_DASH (2, 2.67) = the
   old true style shrunk 1.5× (dots 1.5× smaller and 1.5× denser);
   true = the NEW SP_TRUE_COLOR rgb(160,250,170) at TRUE_W 1.6 with
   the historic (3, 4) dashes — the current true brightness IS the
   new design brightness and the true line brighter still (never
   alpha: "dimmer" is the thinner line, finer dots and the darker
   green; feel-tunable, all named). Hidden inside COMPLEX speed
   controls like the true line (the D173/D174 intervals; inside a
   constant-SPEED control design == true after the snap, so the line
   ends at the snap by itself), draws THROUGH stalled stretches (the
   design profile keeps its energy value), joins the strip's scale
   fit (SpeedStrip.fitHi — a derived curve must never leave the
   strip, D174; the same 1.12 factor; both lines clamp to the run
   window); hovering the TRUE line appends " (design 12.34)"
   (SpeedStrip.designSuffix — two decimals, no unit) only where the
   design line is drawn (always-on = a one-line flag); the hover dot
   wears the brighter color; the hover index is captured at hit time
   for the true line too (it was for the accel line only — a rebuild
   could shift it). The control popup's radios read "Speed (lift,
   transport)" / "Acceleration (launch, brake)" (the "Constant"
   prefix dropped, the hints kept). The badge menu SWAPS the resolve
   submenu and the initial-speed item: "Initial design speed…" (was
   "Set initial design speed…") sits directly under "Orientation
   settings…", the submenu below it titled "Resolve section
   conflict: speed" / "…: orientation" / "…: speed + orientation"
   (supersedes item 1's "Resolve conflict: …"), the D141
   smoothing-only line reads "Resolve section conflict by decreasing
   seam smoothing size", then the separator and Delete section;
   Influence / Convert keep their places; bridges and legacy
   reversed sections keep their existing guards (no resolve block,
   the swap is invisible there). Nothing model-side changed.

11. **The invisible lateral-G stroke under an orientation conflict —
   BUG** (his report: the thin true stroke of the lateral G vanished
   under a roll conflict. ROOT CAUSE per the lateral map:
   GraphEditor's split-reference selector keyed off pieceTilted(p) =
   pitchDeg != 0 || rollDeg != 0 — the pre-v19.0 TRUE-tier test —
   while the compile tilts rigidly by conflictTilt (a free piece's
   design→true gap, D222) or the seam tilt (a follower's held
   angles, D247): a DESIGN-tier conflict (design roll 30 / true 0)
   or a held-seam follower read "not tilted", the reference stayed
   the DERIVED (tilt-re-projected) felt == the true felt wherever
   the speeds matched, and the stroke was culled except in the
   speed-split band — 117/301 and 154/300 samples drawn on the map's
   fixtures). FIX: pieceConflicted(p) = orientConflictAt(p), the
   D247 conflict predicate (TrackPiece.orientConflict for a free
   piece, Coaster.isSeamOrientConflict for a follower), applied PER
   LANE through LaneSection.tiltDeviates(ls, L): every FORCE lane
   (Gv/Gl re-project under the tilt; the local roll RATE lane is
   rotation-invariant, but the whole force section keeps D100's
   authored reference — the brief's decision, restored by the review
   after the implementer had switched it to derived; harmless,
   authored == derived to differencing dust, ConflictGate e6) and
   the geo WORLD-axis and ABSOLUTE lanes (bank-vs-horizon rate incl.
   a yaw-pitch-global geo section's "local" roll lane, D177; heading
   / elevation rates; absolute bank / elevation / heading angles)
   take the AUTHORED reference under ANY conflict kind; geo LOCAL
   body rates keep the derived reference (an authored one would only
   risk one-sample differencing dots at rate kinks). ONE shared
   helper splitRef feeds the stroke (drawTrueStrokes) AND the "
   (true …)" hover (trueSuffix) — priority: a smoothed-seam window
   row → the reference section's authored lane inside its span (time
   membership ±1e-9, so the seam sample reads x = 0 — the historic
   influence-view rule kept) → derived — so the hover now shows its
   suffix at the entry of a true-tier-tilted piece too; it also
   closes the cross-rate VIEW's latent native-vs-warped mismatch
   (authDisp scales every authored reference like derivedDisp, the
   smoothed-window rows included). Consequences: a LOCKSTEP-tilted
   piece (design == true ≠ 0, Lock Track Shape off) is NOT
   conflicted — the derived reference (visually identical, authored
   == compiled to < 1e-6 g), its inactive strips draw AUTHORED and B
   takes the exact ANALYTIC path (its design frame IS the world
   frame); conversely the B-match analytic guard reads
   !pieceConflicted, so a design-tier / seam-conflicted piece takes
   the SAMPLED path (the analytic cross-mode inversion mixed the
   design frame's authored targets with the world seam frame — wrong
   targets before); inactive force strips and world / absolute geo
   strips of a conflicted piece draw DERIVED (the truth — they drew
   authored curves the ride doesn't follow; local-rate geo strips
   keep authored, bitwise); a smoothed window's neighbor-half row
   still wins inside the window; a legacy reversed-flag section
   keeps the band (its authored lanes read back-to-front —
   accepted). The Resulting ▾ overlays are UNCONDITIONAL and
   unaffected (they draw the tilted truth at design speed), the
   strip's speed line was never gated (item 10 owns its design
   twin), no control changed — no Help edit. Pinned in ConflictGate
   (9 new checks, 8 → 17): true roll 30 (e1), design roll 30 (e2)
   and a held seam roll −30 (e3) draw ≥ 98 % under the new rule
   while the old rule drew < 60 % on the last two; a heading-only
   re-aim (e4) and a lockstep tilt (e5) draw nothing; the force roll
   lane (e6); a pitch conflict via Gv (e7); the tiltDeviates truth
   table (f1); a geo absolute-bank section ~30° off its tilted
   samples on lane 2 only (g1).

12. **Deferred** (reported, not shipped): renaming the program
   "Ultimate Vector Design" — answer-only, his call; plus the
   follow-ups items 2 and 3 flag (Switch Coaster as a block move, a
   per-piece Cancel record for the Track Piece window).

Verification: seven NEW gates — LabelGate 89 (titles, tags, the
short form, junctions, owner labels, Coaster's one run-topology
definition == the historic loops), RunGate 51 (runHeads /
runOrdinal, moveRun bitwise per piece + save→load, refusals,
connectStrictRun, runTailEnd / runSampleRange / runLengthM),
PlacementGate 28 (the unlocked default + orientOnlyToSeam carry
scoping, the 4-field porient round trip + missing-line defaults,
TrackPiece.copy, bridge-target routing through editPitch, the
target's Design → Real re-march via SIG_STALE), GestureGate 90
(nudge arithmetic incl. 2000 random snaps, the C1 append and
insert-hold recipes, the start-diamond resize, the one-sided edge
length, the init-speed clamp; + item 9's section 7: resizeEndLen ==
the historic inline arithmetic over 500 random clamps, the native↔arc
round trip on real TIME + DISTANCE compiles incl. the seam rule,
disk write == strip write under every clamp, nearestOnPolyline, the
boundary-disk sample set), StripGate 30 (item 10: no split on an
unconflicted section at any scale, a pinned section splits at every
sample and its neighbor at none, a tilted piece's line appears
mid-section, the strict > TRUE_SPLIT_PX / NaN rules, design == true
inside a constant-speed control, fitHi, the " (design 12.34)"
suffix, the line styles), EndFollowGate 73 (creation flags,
the follow write up and down, the belt, touchLane identity, the
`lane` field IO + derivation, flags feed no geometry), FloorGate 15
(the ray-cast rank separation over 20 000 random poses above and
below ground with both near clips, shear ≡ liftAt, the sign flip,
dynamic ranks, the pixel-float closed form under the real strength
law, lsbK guards, ORTHO_AXIS_LIFT) and coaster.ConflictGate EXTENDED
by item 11 (8 → 17: the stroke selector on real compiles under the
three fixtures, the no-conflict cases, the truth table, the abs-bank
case) — plus the fixture updates the
default flip forced (OrientGate / OrientGate2 / RangeResolveGate /
SeamShapeGate set shapeLocked = true explicitly in their "locked:
pure conflict" fixtures, OrientGate a / h2 expectations flipped,
CopyGate / RunGate compare the new flags) and the full battery
green on wiped classes at the batch's end (the run recorded in
TESTING #165): DetermGate197/198/199/200, LoadGate196, OrientGate,
OrientGate2, Phase2Gate, Phase3Gate, DomainGate, MultiCoasterGate,
SmoothRealGate, coaster.SeamShapeGate, RangeResolveGate, CopyGate,
MergeGate, InsertGate, InflSplitGate, ReanchorGate,
coaster.ConflictGate, MirrorGate, YpAbsGate, coaster.DecompProbe,
InflMatchProbe, Nl2ElemProbe, and the seven above (32 members).
JavaFX-side verification: the winshot render (three windows,
junction button per activation, the Labeled clip check), the
pickprobe hook (part registration, bands, the ball-first rule, a
drag-reveal with the drag alive, a ring drag) and the 15-pose
gridshot ladder; the disks' drawing / picking / hover, the strip's
line drawing, the badge-menu order and the stroke wiring are
JavaFX-side — the interactive flows are TESTING #165.

REVIEW ROUND 2 (the batch's second review-and-fix pass; every fix
cites "D248 review fix" in code):
1. END-FOLLOW derivation FIXED POINT (DesignIO.finishCoaster; the
   derivation paragraph above updated): a candidate (pristine hold
   AT the end) follows only while the defining end — the latest
   last node over the lanes that will NOT follow — is the section
   end; the sole longest pristine hold therefore defines and never
   follows. EndFollowGate c10 flipped to 000 (+ c10b-c13b: the
   time-dragged Gl twin, the triage 5/6/5 s probe, the
   holds-follow-an-authored-definer case, c4 — each asserts one
   syncFollowingEnds pass moves nothing, rideEnd unchanged and the
   save text equal).
2. The redraw BELT is CLEAR-ONLY (LaneSection.clearStaleFollows —
   also syncFollowingEnds' first pass; GraphEditor.redraw calls it
   instead of the moving sync): a redraw cannot rebuild, so a move
   there left the graph ahead of the compiled track until the next
   rebuild, which was then not a no-op (contract b); the moving sync
   stays at the gesture sinks (insertHoldAfterSelected /
   appendSecond / moveGroupBy / moveNode / the delete paths), before
   their own rebuild. EndFollowGate b13.
3. Esc / Enter reach the live windows through a scene KEY_PRESSED
   filter (LiveWindow.show — see the footer paragraph): with the
   caret in any spinner editor a bubbling Enter committed the
   spinner and stopped (Spinner's onAction consumes it; Esc via its
   cancelEdit), so "type 45 into Anchor yaw, Enter" left the window
   open. Filter on two codes only; the undo / ride filters act on
   their own keys.
4. Cancel DISCARDS a typed-but-unentered box FIRST
   (FvdDesigner.discardFocusedNumericEditor in LiveWindow.doCancel —
   the editor text reset to the converter's rendering of the current
   value): the caller's revert reads the MODEL, and the hide-time
   focus-loss commit then applied the typed text AFTER the revert
   (the piece moved on Cancel). After the close the Track Piece /
   bridge windows resync their shell spinners from the model
   (closeTrackPieceWindow → syncPlacementSpinners, closeBridgeWindow
   → updateBridgeControls) so a box the Cancel BUTTON's press-commit
   had already applied (edit + revert = two undo steps, accepted)
   shows the reverted value; a toolbar toggle-close keeps Confirm
   semantics (a typed box commits).
5. The bridge window's Design → Real stamps SIG_STALE AFTER the undo
   push (designToRealDirect(idx, afterPush) — the hook runs right
   after pushUndoSnapshot, before the tier write; the dialog's
   all-Preserve route passes its `after` the same way): the pre-push
   stamp rode into the snapshot's bridge copy (BridgeSection.copy
   carries optSig) and Ctrl+Z restored a stale-signed bridge that
   the next rebuild's auto pass re-marched — an undo that moved the
   target. A refused follower branch stamps nothing. PlacementGate
   g4/g4b/g4c pin why (copy carries optSig; a restored null-signed
   bridge is recorded, a restored SIG_STALE one re-marches).
6. Live windows RE-FIT after managed rows appear (LiveWindow.refit at
   the end of trackPieceRowsSync / bridgeRowsSync / the seam
   tailSync): a Stage never grows when its root's pref size changes
   after show, so a resolve row appearing (a Lock-Track-Shape pitch
   edit, the window following the active piece onto a conflicted
   one) pushed the ops row and [Confirm][Cancel] off the bottom of
   the non-resizable frame; sizeToScene + the show-time placement
   rule (bottom edge above the canvas top, x kept, screen-clamped)
   when the pref size differs by > 0.5 px — a steady state is free.
7. Coaster.canMoveRun refuses a destination that is not the END of
   its own run (runEndOf(afterIdx) != afterIdx): moveRun touches no
   follower flag, so landing after A0 with A1 following it spliced
   the block into the middle of that run (A1 re-chained onto the
   block's tail). The UI's callers always pass the active run's
   tail — a model-side guard; GraphEditor.moveRefuseWhy mirrors it
   ("that piece is not the end of its track piece."). RunGate
   e3c/e3d/e4b-d.
8. GraphEditor.toggleSeamWindow compares the open window's FOLLOWER
   (seamWinPiece, the bridgeWinPiece pattern): a bare null test
   closed piece 2's window when piece 4's "Strict Seam n ▴" was
   clicked and opened nothing.
9. Every REFUSING early return of a window open drops the pending
   gizmo double-click anchor (LiveWindow.dropAnchor —
   showStrictSeamSettings' two returns, openTrackPieceWindow's and
   openBridgeWindow's no-coaster returns): the stale anchor placed
   the next toolbar-opened window at the old 3D-click x.
10. The range chain's s/c refusal names the section by its run-wide
   title (brief L.6): "Range resolve stopped — <title> isn't compiled
   yet." (was the raw piece-local index).
11. The axis anchor quantum stays 30 m — the deviation from the
   brief's 100 m is now WRITTEN (FloorDepth.AXIS_ANCHOR_Q's javadoc,
   the F-floor edge table): the 100 m belonged to the abandoned
   per-ring flat-lift scheme (axis pieces had to align with the q =
   100 decade's rings); the ranked tilted-plane design does not
   depend on it, and raising it without re-basing AXIS_SEG (first
   boundaries 0/30/60/90 m) would park the eye up to 50 m off the
   anchor and mis-size the near-field width max(0.09, 0.0016·d).
Gates after the round: EndFollowGate 61 → 73, RunGate 46 → 51,
PlacementGate 25 → 28; the full battery re-run (TESTING #165).

## D249 (v19.20) — Batch 34: the 3-STATE ORIENTATION MODE (Edit Real and Design Orientation Independently → real / design radios, Affect Subsequent Strict Seams, the ONE TIER RULE for bridge targets) + the bridge padlocks on the SEAM RULE (🔒 = held at the typed value, editable in every mode), Cancel = a FULL placement snapshot; resolve ops UNDER seam smoothing, the LIVE "Initial design speed" window, smoothing cap 100 m, the Track Piece window's Delete…; the UI-bug round (wizard radios root-caused, ONE selection choke, windows placed BEFORE show, ball-edge gizmo picking, node-0 markers under junction balls, boundary disks on single-line seams only, the ◇ dodges carets); speed-control CROSS-DOMAIN parity + "t = 3.20 s" readouts + thicker whole-disk-pickable disks; the badge reverted to its type word, "Resolve conflict: …", "Bridge settings…", the program name ULTIMATE VECTOR DESIGN; the FLOOR FLICKER root-caused — the D248 depth-tested floor (b′) RETIRED, the floor back to D246 bitwise with a GROUND-SPLIT track layer

Date: 2026-09-24, his batch-34 list on top of the uncommitted
batches 32 + 33 (the D247/D248 tree; the "flipped padlock colors",
"the grid is flashing like crazy", "wizard radios do nothing" and
the orientation-model re-spec — real vs design editing as a first-
class choice). Eight items (M B O C U S L F), each with its own
edge table (scratchpad b34_edges_<item>.md) and review-and-fix
round; the docs round folds the review fixes into the items below.
Four ROOT CAUSES with evidence — bridge locks RC1/RC2 (item 2), the
wizard re-parenting (item 5.1), the selection choke (item 5.2) and
the floor's (b′) postmortem (item 8).

1. **The orientation MODEL: three states, one tier rule** (item M —
   user re-spec of D248's boolean; TrackPiece.OrientMode { LOCKSTEP,
   REAL_ONLY, DESIGN_ONLY }, field orientMode, default LOCKSTEP =
   D248's unlocked default; shapeLocked() survives as the DERIVED
   accessor == REAL_ONLY for readers that only ask "does an edit
   keep the shape?"). editPitch/editRoll route per mode: LOCKSTEP =
   both tiers by the same delta (the historic unlocked path
   bitwise), REAL_ONLY = the true tier only (the historic locked
   path bitwise — shape kept, the conflict grows), DESIGN_ONLY =
   the DESIGN tier only — the real entry never moves, the shape
   re-solves, the conflict changes by the design delta (judgment:
   design := v ABSOLUTELY, the wrappers deriving v from the design
   raw, rather than the map's "design += v − pitch"). The
   setDispPitch/Roll + setRawAbsPitch/Roll wrappers derive the new
   delta from the tier the mode edits — under DESIGN_ONLY from the
   DESIGN raw incl. its own pole branch (the map's spec gap 1), so a
   design box/ring value round-trips on the design tier
   (rawAbsDesignPitch/Roll = the rig baseVals there). THE CARRY
   FLAG is RENAMED AND INVERTED: orientOnlyToSeam →
   affectSubsequentSeams (true = the D221 rigid carry; default false
   = D248's default behavior bitwise); Coaster.carriesDownstream =
   orientMode == REAL_ONLY && affectSubsequentSeams (DESIGN_ONLY
   inert — the real entry never moves). IO: `porient locked pd rd
   affect mode` — field 1 keeps its D222 meaning (1 = REAL_ONLY) for
   old readers, field 4 stores the NEW carry meaning, field 5 = the
   mode ordinal and the AUTHORITY when it parses to 0..2 (fail-soft
   to field 1 otherwise, D216); the loader decides field 4's meaning
   BY FIELD COUNT: exactly 4 fields = a D248 'only' line → INVERT, 3
   fields = no carry, a missing line = LOCKSTEP + (0,0) + no carry
   (the compile never reads the mode — every old file's TRACK is
   bitwise, only its edit routing changed), the D221 interim 2-field
   form unchanged. PieceState / DocState / restorePieces, TpRecord /
   BrRecord, the seam window's open record and TrackPiece.copy carry
   mode + carry. FOLLOWERS: seamDesignLockstep is mode-keyed
   (LOCKSTEP only; its math factored into the public
   Coaster.applyDesignEulerDelta with the D247 pole guard);
   GraphEditor.seamEdit gained the DESIGN_ONLY divert (a value-only
   write of a held pitch/roll has its real Euler delta applied to
   the design tier and the held values restored — the real entry
   never moves; padlock toggles and yaw stay real-side, since the
   locks are the real contract and heading is shared) plus the
   explicit design write path Coaster.seamDesignSet /
   GraphEditor.seamEditDesign (design elevation / bank := deg
   through eulerOf(designPose()); refuses axis 0 "Heading is shared
   by both tiers — edit it as the real yaw." and a vertical design
   frame "The design frame points straight up/down — its elevation/
   bank cannot be set; adjust the real orientation first.") for the
   UI's design boxes and rings (item 3). THE ONE TIER RULE for EVERY
   writer of a bridge TARGET's orientation — the user's boxes / ↺ /
   rings AND Coaster.alignPieceForBridge (optimizeBridge /
   autoReoptimizeBridges): pitch/roll route through editPitch/
   editRoll, so LOCKSTEP = both tiers (the march can NEVER open a
   conflict), REAL_ONLY = bitwise the historic true-only write,
   DESIGN_ONLY = optimize-orientation unavailable (item 3's mutual
   exclusion; a loaded DESIGN_ONLY + PY file marches defensively
   true-only, record-only on load). SUPERSEDES D222's "the march
   writes the true tier by convention" and fixes bridge-locks RC1
   (item 2). Quiescence under LOCKSTEP was PROBED, not assumed: the
   aligned target re-solves, its head dynamics shift the bridge's
   signature, and the outer rebuild loop needed 13 (explicit
   optimize) to 23 (upstream 3° tilt) whole-document passes to rest
   — inside the 12 + 40 cap, but a live gesture's 12-pass tube
   would have shown a mid-cascade target. Rather than the map's
   fallback (REAL_ONLY writes + a stats reason) the fix is
   Coaster.settleLockstepAlign inside optimizeBridge: re-capture the
   target head exactly as the next compile will
   (TrackCompiler.captureTargetHead — the bridge's target-side
   solve-input capture factored VERBATIM out of compile(), at the
   new model-side Coaster.INTEGRATE_DT that FvdDesigner's constant
   now reads) and re-align until the deltas fall below 1e-9° (≤ 24
   rounds, ~10 observed) — the SAME fixed point (pitch −30.631847°
   both ways) in 0-1 outer passes. REVIEW ROUND 2 — the REST
   GUARANTEE: the settle's exit used to stamp the PENULTIMATE
   capture, and the claim that an un-settled exit "finishes, docSettled
   honest" was FALSE — a slow pitched target (vDesign 2-8 m/s at
   ±25-30°, pitch/roll unlocked; probed on the SettleProbe layout)
   has a REPELLING fixed point (the pitch↔head-curvature gain
   exceeds 1: no step size converges, damping is no fix), its stamp
   never matched the next capture, and every rebuild re-marched
   through the whole 12 + 40 cap (52 passes, restless — the D197
   class). Now settleLockstepAlign re-captures the head ONCE MORE
   after its last alignment whatever the convergence (stamp == the
   next compile's capture; converged loops repeat their last
   capture to < 1e-9), an unconverged exit STOPS HONESTLY (each
   round snapshots the placement before its alignment, the
   smallest-delta round is restored, a round whose pitch lands on
   the ±85° clamp ends the loop) and the placement then fails
   marchSpine's landing test → the honest septic, never a cascade;
   OrientModeGate g7 pins five slow targets (rest in ≤ 2 passes, a
   second rebuild bitwise). And the RE-KNEE: under "Optimize length"
   the applied length was the knee of the seams as the COMPILE
   captured them, never re-kneed once the settle moved the head
   dynamics (optSignature stores 0 in the length slot; probed 9.00 →
   8.00 m on the gate fixture, 9.00 → 6.50 m at vDesign 24 optLen)
   — optimizeBridge now re-computes the knee off the settled seams
   and aligns + settles ONE more pass when it differs (contracting
   knees converge in one; a still-moving knee keeps the second
   answer, bounded by design; g7b). Tunables by name: the 1e-9°
   settle tolerance and the 24-round cap. Nine gates migrated to the mode by
   script (assignments → orientMode / affectSubsequentSeams, reads →
   shapeLocked() / !affect; PlacementGate expects the 5-field line,
   CopyGate / RunGate compare mode + carry, OrientGate (i)
   re-specified as the REAL_ONLY contract — its "even unlocked: the
   machine bypasses" claim is superseded and gated the other way as
   OrientModeGate g1). OrientModeGate g2b pins the recorded pre-D249
   REAL_ONLY alignment (−27.507015°) to 1e-6 as a probe-recorded
   number — re-record on any deliberate physics change. REVIEW
   FIXES (M): the seam rig's pitch/roll rings under DESIGN_ONLY
   compounded (the ring's baseVal was the HELD real angle, which
   never moves in that mode, and writes are absolute-from-press —
   each event re-applied the whole drag: +1, +2, … +10 → +55°) —
   superseded by item 3's absolute design-angle rings; the Track
   Piece and bridge ↺ passed the open-time REAL delta to
   editPitch, which under DESIGN_ONLY set design := real (now
   r.dPitch / r.dRoll in that mode); the piece and bridge-end rigs'
   global ring baseVals read the real raw while the write derived
   the design delta (now rawAbsDesignPitch/Roll under DESIGN_ONLY);
   the local-ring sinks decomposed against the REAL entry pose
   (pieceLocal / bridgeLocal now decompose on the tier the mode
   edits). Rejected: an extra editDesign* API (the wrappers suffice);
   REAL_ONLY-with-a-reason as the LOCKSTEP march fallback (the
   user's model is "no conflict from the machine").

2. **Bridge padlocks on the SEAM RULE** (item B; his "flipped
   padlock colors"). ROOT CAUSES with evidence: RC1 (the "odd
   behavior", real, batch 33) — the next piece's orientation had TWO
   writers with OPPOSITE tier rules: D248 routed the user's
   setBridgeTargetOrient / gizmoSetBridgeOrient(All) / bridgeRevert
   through editPitch/editRoll (unlocked = both tiers) while
   Coaster.alignPieceForBridge still wrote the TRUE tier only for
   the axes it aligns — the probe's D2 case: HEAD TRUE 9 / DESIGN 0
   (a conflict, rigid deviation 9.87 m) vs the tree TRUE 9 / DESIGN
   9 (no conflict, 21.27 m) against bitwise-identical marches in A/
   B/C; visible as a ≠o opening when an axis was unlocked under
   Optimize position and orientation with Lock Track Shape off,
   "Design → Real" re-opening on every re-march (converging ~10×
   per pass, never one-shot). Closed by item 1's one tier rule
   (bitwise the historic march under REAL_ONLY, gated). RC2 (the
   "flipped" reading, pre-existing, exposed by D248's side-by-side
   unification) — the ICONS agree on both windows (🔒 red = flag
   true); what differed was the ENABLE RULE: the bridge grayed a
   LOCKED axis's spinner in every mode ("Click the padlock to
   unlock." — under OPT_POS_YAW simply false: the click handed the
   axis to the march and the spinner stayed gray with the other
   hint) and grayed an UNLOCKED axis under OPT_POS_YAW, while the
   seam window enables exactly the LOCKED axis; so the typeable
   axis wore 🔓 in the bridge and 🔒 in the seam. D87's "locked =
   frozen, no edits anywhere" was the definition that had to
   change, not the icon; the user's model "🔒 = held at what I
   typed" had NO path under OPT_POS_YAW in either build (RC4 of the
   map). THE RULE NOW (BridgeSection.axisLocked / axisEditable /
   axisAligned — ONE model predicate every surface reads:
   axisEditable = locked || optMode != OPT_POS_YAW): 🔒 = the axis is
   HELD at the typed value and its box / ↺ / ring are editable in
   EVERY mode (the bridge bends to it); 🔓 = free — under Optimize
   position and orientation the march aligns it and the box grays
   "The bridge aligns this axis — lock it to hold a value.", under
   Optimize position / No optimization nothing aligns it, so it
   stays typeable (de facto held). A typed HELD value under
   OPT_POS_YAW RE-MARCHES at once (bridgeTargetWrite's tail now
   covers both optimize modes, alignYaw = the mode) so the hump /
   twist meets the typed value (gated: lands, LOCKSTEP keeps design
   == true, the next rebuild a no-op; Optimize length ON re-knees
   via applyOptimalLength, whose zero-distance prompt can pop from a
   spinner commit — today's OPT_POS rule, accepted). LOCKING
   CAPTURES the current angle with NO write (judgment vs the brief's
   "captures explicitly": the held value IS the target piece's own
   field — an explicit write would be a no-op at best and a disp →
   raw → delta round trip at worst, breaking the bitwise-HEAD flip;
   gated h1c / h7); the OPT_POS_YAW flip's re-march is HEAD's tail
   verbatim; one pushUndoSnapshot in setBridgeLock from either
   window. The Track Piece window over a bridged-in piece: its
   padlocks RE-ENABLED in every mode (the D247 review gray
   reverted) and routed through GraphEditor.withBridge(a−1,
   setBridgeLock) — one behavior from either window (undo, capture,
   re-march; setBridgeLock resyncs the Track Piece padlocks); its
   yaw / pitch / roll boxes + ↺ take the bridge's one write tail
   (FvdDesigner.pieceOrientWrite → editor.bridgeTargetWrite) under
   the same enable rule — the YAW row too (the brief named pitch/
   roll; yaw was grayed by the OLD sense and wrote with no re-place;
   its own hint "The bridge aligns this axis — lock it in the bridge
   window to hold a value.", since this window has no yaw padlock).
   Accepted: the spinner compiles twice per commit (the re-place +
   wireDocSpinner's rebuild — the fixed point's no-op). BRIDGE-END
   RIG: rings build for the HELD axes in OPT_NONE AND OPT_POS (the
   seam rig's rule; was: unlocked axes, manual only); OPT_POS_YAW
   keeps the tangent arrow only; the local ring's combined write
   writes the held axes' deltas, per-axis ring writes refuse free
   axes defensively; the piece rig's bridged-in branch mirrors it.
   USER FLAG: a DEFAULT manual / position-mode bridge (yaw free)
   therefore shows NO yaw ring any more — lock yaw to get one (a
   one-line change in Viewport3D.buildBridgeGizmo — build on
   axisEditable(0) — if he wants rings to follow the enable rule
   instead). Free-piece padlocks (uiLockPitch/Roll, 🔒 = the box is
   frozen) KEEP their edit-guard sense — tooltip "Guards the box
   against accidental edits." (a live Tooltip object swapping text
   per piece kind; the bridge padlocks + a bridged-in piece's Track
   Piece padlocks wear "Locked = hold this axis at the typed angle
   (the bridge bends to it).\nUnlocked = free: under Optimize
   position and orientation the bridge\naligns it; in the other
   modes it stays editable.") — USER FLAG: the residual opposite
   sense. Strings gone: "Click the padlock to unlock.", "The
   optimize mode owns this axis.", the Track Piece optimize-
   orientation pitch/roll gray, the yaw-row "owns this value" gray
   (the x/y/z rows keep theirs), both old padlock tooltips; the
   free-piece gray reads "Locked — open the padlock to edit.", the
   seam padlock tooltip "Locked = hold the typed angle; unlocked =
   follow the seam"; the bridge mode radio's gray "Unlock at least
   one axis to optimize orientation." and LiveWindow.MARCH_OWNS_R2D
   are now literally true. SUPERSEDES D87's "locked = frozen — no
   edits anywhere" and D248 item 3's D247-review Track Piece padlock
   gray. Not applied: the map's STEP 1 (revert bridge-target writers
   to true-only) — item 1's tier rule replaced it.

3. **The orientation UI** (item O; all three windows — Track Piece
   ▴, Strict Seam n, the bridge window; LiveWindow.flagRow's ONLY
   signature now). The D248 "[Lock Track Shape (creates orientation
   conflict)] [Only Affect…]" row is REPLACED by a CheckBox "Edit
   Real and Design Orientation Independently" (off = LOCKSTEP;
   tooltip "Unchecked: pitch/roll edits move the real and design
   orientation together (the shape re-solves).\nChecked: choose
   which one the boxes and gizmo rings edit."; checking pre-selects
   REAL_ONLY) with two condensed RadioButtons directly below it —
   "Edit real orientation (locks track shape)" (tooltip = the D247
   Lock Track Shape sentence verbatim "Design orientation does not
   change for adjustments to pitch/roll") / "Edit design
   orientation" (tooltip "Edits move the design orientation only —
   the real entry stays put and the shape re-solves.") — then "Affect
   Subsequent Strict Seams" (tooltip "Also move the pieces after
   this piece's next strict seam or bridge by the same rigid change
   (real-orientation edits only)."; HIDDEN while the check is off,
   not grayed; grayed "Applies while editing the real orientation."
   under DESIGN_ONLY). ONE mode writer; a mode flip (check, radio or
   readout click) first COMMITS a typed-but-unentered box to the
   tier it was editing and drops focus (LiveWindow.settleTyping —
   otherwise setUnlessTyping kept the old tier's number under the
   new label and Enter would have committed it onto the wrong
   tier). The grey readout became LiveWindow.Readout: "design
   12.34°" under LOCKSTEP / REAL_ONLY, "real 12.34°" under
   DESIGN_ONLY ("design —" / "real —" when undefined), shown while
   the check is on OR the piece is conflicted (judgment: visible
   whenever the check is on, even unconflicted, so the swap target
   always exists; LOCKSTEP keeps hidden-unless-conflicted), and
   CLICK-SWAPPABLE (the node editor's twin recipe — constant
   transparent border, hover outline, hand cursor, 300 ms tooltip
   "Click to edit this orientation instead") only while the check
   is on and the target choice is not grayed (a REAL_ONLY bridge
   target under Optimize position and orientation shows a plain
   label — the design radio's gray sentence is the explanation;
   swapping onto DESIGN_ONLY is always allowed). PITCH/ROLL ROWS:
   under DESIGN_ONLY the BOX shows/edits the design elevation/bank
   (dispDesignPitch/Roll — judgment beyond the spec, which fixed
   only the readout: box + readout are the two tiers in every
   mode); Track Piece window: readouts + the design radio's
   designWhy (bridgeInto(active) under OPT_POS_YAW), the anchor
   tooltip FOUR-way (lockstep head: "Moves the run's first piece
   (the anchor); with the orientation edited in lockstep, bridged
   pieces re-solve instead of following."; REAL_ONLY carry off:
   "…; with Affect Subsequent Strict Seams off, the pieces after its
   next strict seam or bridge keep their own placement settings.";
   DESIGN_ONLY: "…; while editing the design orientation the pieces
   after its next strict seam or bridge keep their own placement
   settings."; carry on = the run-head hint alone). SEAM WINDOW:
   under DESIGN_ONLY the pitch/roll boxes show eulerOf(designPose)
   and are ENABLED regardless of the padlocks (Coaster.seamDesignSet;
   a vertical design frame grays / refuses "The design orientation
   points straight up/down — set the real orientation first."), ↺
   returns to the OPEN-TIME design euler as absolute world angles
   (valid whatever happened to baseOrient; refuses before the undo
   push when either frame is vertical), the readouts read "real …"
   (followerEuler), the yaw row ALWAYS edits the held/real heading
   (tooltip "Heading is shared by the real and design orientation —
   this row always edits the held/real heading."). BRIDGE WINDOW:
   bridgeTargetOrient reads the mode's tier; "Edit design
   orientation" and "Optimize position and orientation" are MUTUALLY
   EXCLUSIVE — the target's design radio grays "Optimize position
   and orientation must not be selected." under PY, the PY radio
   grays with pyWhy(): all-locked ("Unlock at least one axis to
   optimize orientation."), target DESIGN_ONLY ("Edit design
   orientation must not be selected.") or both joined with " · " —
   NEVER while selected (the historic m != PY guard: the user can
   always leave the mode; a loaded DESIGN_ONLY + PY file shows the
   design radio selected + grayed with the real radio / the check as
   the escape); setBridgeOptMode refuses PY on a DESIGN_ONLY target
   with a stats belt "Optimize position and orientation refused —
   the next piece edits its design orientation; choose its real
   orientation first."; Real → Design under PY keeps MARCH_OWNS_R2D.
   GIZMOS: gizmoSigNow / appendPose sign orientMode + the design
   tier per run piece and bridge target (a mode flip / design edit
   rebuilds the rig); the piece + bridge-end rigs aim at rigFrame
   (designPose under DESIGN_ONLY, else entryPose); the seam rig
   under DESIGN_ONLY builds pitch/roll rings regardless of the
   padlocks with axes off the DESIGN frame (local: design right / T
   / U; global: the design heading's horizontal right), baseVal =
   the design elevation/bank, ABSOLUTE writes (gizmoSetSeamOrient →
   seamEditDesign — the M review's held-delta trick retired);
   seamLocal rotates the design pose and routes through
   gizmoSetSeamDesignAll (heading delta → the held yaw when locked,
   absolute design elevation then bank); deselecting the mode / the
   check snaps the rig back. CANCEL / ESC (all three windows) = a
   FULL-SCOPE placement snapshot (coaster.PlacementSnapshot — a NEW
   pure class: every piece's x/y/z/yaw/both tiers/baseOrient/the
   canonical pair/mode/carry/uiLocks/followPrev/seam locks + held
   angles/sSmoothLen and every trailing bridge's optMode/optLen/
   lastOptDist/lenA/lenB/locks/noneStash/optSig with SIG_STALE /
   null identities kept) captured at open and RE-CAPTURED after
   undo restores (TP/BW records, seamForceSync — the seam ↺'s
   open-time held values keep D248's not-re-captured behavior),
   restored BY OBJECT IDENTITY with DIRECT writes through
   FvdDesigner.cancelRestore (never placementEdit — the D221 carry
   is not an exact inverse and the routing flags may have changed
   mid-window; refuses "Cancel skipped — the document changed
   underneath this window; use Ctrl+Z to go back." on a coaster /
   docGen mismatch; no undo step when nothing differs); pieces that
   vanished are skipped, pieces that appeared untouched, a bridge
   whose object was replaced (a convert) skips its bridge half;
   graph edits made meanwhile survive; a mid-window Design → Real
   bake REVERTS too (baseOrient + the canonical pair recorded —
   supersedes D247's sameBase guard); then the bridge window
   re-applies its gap and stamps its signature
   (reapplyBridgeAfterRestore), the Track Piece and seam windows
   run a plain rebuildTrack (the restored optSigs match the
   open-time seams — gated passes 0 — so only meanwhile graph edits
   re-march, accepted). REVIEW ROUND 2 — record RE-BASING: the Track
   Piece record re-captures on ANY document generation the restore
   hooks did not see (trackPieceRowsSync — the density preview's
   pvRerun replaces the piece objects through applyDocState without
   the docRestoreHooks, and its Apply pushes undo without a
   rebuild, so Cancel found gen != docGen and skipped with
   CANCEL_STALE_MSG for the rest of the window's life; while the
   preview is LIVE the record re-bases per hook, ending on the
   committed post-conversion placement — the pre-preview values
   are unrecoverable by design, the preview's own Cancel restores
   them; the bridge window's BrRecord takes the defensive twin) and
   the ops row's MIRROR runs the restore hooks itself after
   mirroring (mirrorPiece rebuilds baseOrient / the canonical pair
   and negates the roll tiers + a follower's held bank beside the
   lane negation; a record taken before it reverted the placement
   alone — a half-mirrored piece; Cancel never un-mirrors, the
   mirror is its own undo step, Ctrl+Z is the path). And the seam
   rig under DESIGN_ONLY writes the held yaw only when it actually
   changed (> 1e-9°): the roll ring — and the pitch ring on an
   unbanked design frame or under Global axes — hands back the
   press-time heading plus eulerOf's ~1e-14° dust, and the write
   ran through seamEdit → placementEdit per drag event, re-aiming
   the REAL entry the design mode promises never moves (with a
   carry under Affect Subsequent Strict Seams); the Local-mode
   banked pitch ring's heading delta still lands (heading is
   placement); OrientModeGate h6. Track Piece padlocks under DESIGN_ONLY keep
   their meaning (no new padlock rule invented). REMOVED strings:
   "Lock Track Shape", "(creates orientation conflict)", "Only
   Affect Orientation Until Next Strict Seam", "The pieces after
   this piece's next strict seam or bridge keep their own placement
   settings.", "Applies while Lock Track Shape is checked.", the
   static "Unlock at least one axis to optimize orientation."
   grayHint (now pyWhy's first half). OrientModeGate emulates a
   Design → Real bake (bakeFollow is package-private) — the identity
   restore is what h2 pins.

4. **Resolve under seam smoothing, the LIVE init-speed window, cap
   100 m, Delete…** (item C). (a) RESOLVE OPS UNDER SMOOTHING:
   Coaster.withSeamsSuspended(pieceIdx, op) zeroes the smoothing of
   the piece's OWN seam and the seam right AFTER it, runs op,
   restores the exact doubles in a finally BY PIECE OBJECT (not
   index — isolateRange's splits move the successor down the list;
   pieces born inside the op own fresh sharp 0 seams and never
   inherit a stash; a piece removed by absorbFollower keeps its
   restored value harmlessly; nested calls stash 0 / restore 0 = a
   no-op by construction; a throw restores). makeGraphsReal /
   refreezeSpeed / refreezeRange / isolateRange (its makeSeamStrict
   cut and the follow bakes read the suspended compile — the ops
   used to MIX compiles: bakeFollow WITH smoothing, the anchor
   without) run inside it; SCOPED, not compileForFit's all-off: an
   unrelated smoothed seam keeps its blend during the op (gated:
   the H → P0 seam never zeroed). rangeResolveWhy's two smoothed
   reasons are REMOVED — the badge submenu no longer grays smoothed
   cases; the D141 line shows ONLY when smoothingCausedDrift and
   reads "Speed conflict caused by seam smoothing — shrink the ◇
   window instead" (the ops hidden for a pure smoothing-caused ≠v —
   the window is the fix; with a ≠o they stay reachable). Accepted:
   the restored smoothing blends around the NEW seam frames (Set
   Design to Real reshapes the range by design; Convert Real to
   Design stays within the fit residual, gated 6 mm) and the ≠v may
   reopen after the restore (the red band says so). (b) "Initial
   design speed…" applies LIVE — the node editor's model: every
   spinner change writes at once (the ride entry: v0 + section 0's
   vDesign through the muted Start-speed spinner, its [1,100]
   factory clamp = the D198 entry rule; any other section: vDesign
   + PIN — a value re-typed EQUAL still pins, D242; equal AND
   already pinned = no-op), undo coalesced per 0.8 s typing burst
   (the liveUndo recipe; an undo ends the burst); "Set Design to
   Real" runs Coaster.refreezeSpeedOnly AT ONCE (one undo step:
   exact seam value, un-pinned; an orientation conflict left
   standing — the badge op alone bakes tilt/follow; a later typed
   edit pins again), grayed on the ride entry "The ride entry's
   design speed is the real one — nothing to set." and with nothing
   compiled "Nothing compiled for this section yet."; a LiveWindow
   (Confirm / Enter / X keep after the focused box commits; Cancel /
   Esc discard typed text and restore the open-time vDesign +
   vPinned (+ v0 + the spinner for the ride entry) as one more undo
   step when anything differs — skipped with CANCEL_STALE_MSG when
   the coaster switched or a generation the hooks did not
   re-capture: the record RE-BASES on every undo restore through a
   docRestoreHooks twin and, in the model hook, on any document
   generation it has not seen, review round 2 — it was captured
   once, so every Cancel after an undo skipped for the window's
   life); the hook re-seeds the box (undo
   outranks typing), tears the window down "The initial design
   speed window closed — its section no longer exists." when its
   section is gone or the coaster switched; convertBusy refuses
   (the change reverts muted). SUPERSEDES D241's fill-the-box and
   D242's unmodified-Apply rule — with live editing there is no
   Apply moment. (c) SMOOTHING CAP 80 → 100 m (GraphEditor.
   SEAM_SMOOTH_MAX — the drag cap; the loader already admits 0..200;
   no spinner exists, the brief's mention notwithstanding; a loaded
   150 m stays until the next drag). (d) "Delete…" on the Track
   Piece window's ops row after Switch Coaster…: a WINDOW_MODAL dark
   confirmation "Delete the whole track piece (N pieces)?" ("(1
   piece)") [Delete][Cancel] with global undo keys →
   pushUndoSnapshot → Coaster.deleteRun(head) removes the whole RUN
   (head..runEndOf — trailing bridge + bridged-in targets; a run
   ending in an orphan bridge removes it too) → activates the run
   after, else the previous run's head; deleting the FIRST run sets
   v0 := the new first piece's section-0 vDesign (the Switch-Coaster
   precedent); the coaster's ONLY run may go (an empty coaster is
   legal, D239 — the placeholder shows; v0 untouched); the
   successor's followPrev normalized as a belt; every window
   addressing the block pre-hidden null-before-hide, every pane's
   reveal dropped; stats "Deleted the track piece (N pieces)."
   Refusals: the wizard's pending piece "Finish or cancel the open
   start screen first.", convertBusy, "No piece to delete.", a
   document changed under the confirm dialog "Delete refused — the
   track piece changed while the dialog was open.", a non-head
   index (−2, list untouched). The brief's "The bridge before it
   will connect to the next piece." sentence is NOT shown — it is
   UNREACHABLE: a run head is never preceded by a bridge-ending
   piece (endsWithBridge would have absorbed it into that run) and
   an orphan bridge is necessarily the list's last piece; pinned as
   a topology invariant (RunGate j6) so no dead text ships.

5. **UI bugs + viewport rules** (item U). (1) WIZARD RADIOS DEAD —
   ROOT CAUSE: StartPanel.syncFromModel was not idempotent — every
   call cleared typeOptions and re-created the GridPane / HBox
   parents of the fourteen RadioButtons, and since D247 that sync
   runs from wizardHook on every fireModelHooks, which
   GraphEditor.redraw fires from every layoutChildren. A radio
   press → the panel's MOUSE_PRESSED filter requestFocus() → the
   previously focused wizard Spinner's skin requests layout on
   focus loss → editor layout → redraw → hooks → the grid rebuilt
   BETWEEN press and release; JavaFX's enter/exit bookkeeping hands
   the re-parented RadioButton a synthetic MOUSE_EXITED,
   ButtonBehavior disarms it, the release fires nothing, the next
   sync re-selects the model's radio (evidence: the probe's
   MOUSE_EXITED-while-armed with a changed parent hash, armed=false
   before the release, the rebuild stack ending in layoutChildren;
   `muted`, docApplying, targets() and the listener chain all
   verified NOT the cause). Fix: the lane grid is built ONCE
   (laneGrid, buildLaneOptions); syncFromModel only sets
   selections / disables in place and re-attaches the same grid
   (the s/c pick clears typeOptions, a later lane pick re-adds the
   SAME grid); syncGray's corrective setSelected saves/restores
   `muted` (it wrote false unconditionally — latent). STANDING RULE
   (the class comment): a live sync must never re-parent a node.
   (2) THE SELECTION CHOKE — ROOT CAUSE: two independent selection
   models (spSelPiece/Sec/Ctl/Mark for the strip, selIdx/selSet for
   nodes) with spFocus arbitrating only KEY routing; the plot press
   cleared spFocus but no path cleared spSel* when a node / marker /
   gizmo / element was selected (the strip draws its highlight from
   spSel* alone), and the viewport's single-click select/deselect
   bypassed the plot's focus-return step — with spFocus left true
   the shared selIdx was read as the STRIP surface's node index
   (refreshMarkers passed −1, spSanitize clamped it against the
   control's node count, H/F/B/Delete routed to the strip: "markers
   unselectable while a control is selected"). Fix: ONE choke
   GraphEditor.takePlotSelection() — a plot press, a marker click
   (single and double), a gizmo / ball press or an empty-track click
   (deselectFromViewport runs it BEFORE its early return), an s/c
   element — clears spSel* / spMark, hands spFocus back and drops a
   strip-owned node selection; the reverse spTakeStripSelection()
   at the four strip select sites + the disk press drops the plot
   node selection (mutual exclusivity both ways; spClearSel from
   spSanitize stays GATED on spFocus so a vanished control never
   drops an unrelated plot selection). REVIEW ROUND 2: the plot
   press runs the choke on the PRIMARY button only — a right-drag
   pan, a middle press or a right-click menu open selects nothing,
   and the plot's own node selection survives a pan (deselect on
   RELEASE ≤ 3 px), so a lit control survives it too; the secondary
   presses that DO select — the node menu, the element menu — call
   the choke themselves. And the popup's Edit Speed / Acceleration /
   Deceleration SELECT the control the panel shows
   (spSelectPopupControl: since the popup outlives the selection
   and editing resolves the SELECTED control only, the button
   opened the tall strip over an inert dimmed graph — a click that
   did nothing useful; a plot node selection drops with it, mutual
   exclusivity). The options POPUP
   STAYS OPEN (user spec — an open panel is not a selection; it is
   bound to its own indices spPopPiece/Sec/Ctl and hides only via
   ✕ / +V-off / a control delete / the structural clears /
   spAfterRebuild when ITS control vanished; another control
   selected re-shows it bound to that control with the unit label
   rebuilt — a cross-domain follow used to keep a stale "s"/"m";
   never during a strip gesture); an open '+' / badge / seam MENU
   is not a selection. (3) WINDOW FLASH ("moved from another
   location"): LiveWindow.place places BEFORE show — the D248 build
   showed the Stage at JavaFX's default owner-centered spot and
   moved it in setOnShown, but the native frame (and Windows' open
   animation) is created at the creation rect and opacity 0 does
   not cover it. Now the content's CSS is applied and it is
   measured + LAID OUT at its pref size pre-show — THE LAYOUT IS
   LOAD-BEARING (winshot A/B): applyCss + prefWidth/prefHeight
   ALONE reproduce the D248 "No optimizati…" clip, resize(pw, ph) +
   layout() right after gives every label its width; frame = pref +
   the cached decoration insets (TITLE_BAR_EST / 0 for the session's
   first window), setX/setY before show; setOnShown stays the
   RESIDUAL corrector (learns the real insets, moves only when
   > 0.5 px off — the first window's small hop, later windows
   exact); refit() remains the belt. (4) GIZMO PICK: the two-pass
   "balls first within radius × 1.5 + 6 px" (D248) is ONE
   nearest-by-px pass with the ball measured from its silhouette
   EDGE — Gestures.ballEdgeDist = max(0, d − rPx) (a press inside
   the visible ball reads 0 px and wins; a ring just outside beats
   the ball's edge distance; the edge band rPx·0.5 + 6 keeps the
   D248 zone's outer reach); review fix: an exact TIE goes to a
   BALL (every arrow shaft and an edge-on ring pass through the rig
   center at 0 px and the center ball registers LAST, so strict `<`
   alone handed a press on the visible ball to an arrow — the
   pickprobe center case); hover glow shares pickPart. (5) NODE-0
   MARKER hidden under a JUNCTION BALL (Viewport3D.
   junctionBallCoversNode0): run heads (the historic v18.13 rule),
   bridged-in pieces (never strict followers — the bridge-end ball
   at their x/y/z covers it) and strict-seam FOLLOWERS whose seam
   ball exists (seamWorldPos != null; before that compile the
   sphere shows); node 0 stays editable from the graph, POV shows
   it (balls hide there). (6) BOUNDARY DISKS only where the graph
   draws a SINGLE seam line (ViewDisks.boundarySamples: every
   section's exit except the last — plain boundaries inside a piece
   incl. the boundary INTO a bridge section; never strict seams,
   bridge exits, run heads / ends / a detached piece's entry+exit);
   constants named for retune: BOUNDARY_R 0.34 (was 0.16 — > the
   control ring's 0.30, so a control starting on a boundary reads
   as a ring around the control disk, accepted), BOUNDARY_HT 0.02,
   Viewport3D.BOUNDARY_COLOR rgb(105,110,122), glow 0.30. (7) The
   smoothing ◇ DODGES THE CARETS too (seamDiaYAt three-pass: the
   first rung ≥ 22 px from every node AND ≥ SEAM_DIA_CARET_CLEAR 12
   px from every caret → the node-clear rung with the largest caret
   clearance → the historic best-node-distance fallback; no carets
   = the D142 walk bitwise; draw and hit share it; the diamond may
   hop once when the compile's carets appear, accepted). Item 8 of
   the map (the all-piece Cancel) landed in item 3.

6. **Speed-control parity under the cross-domain view, position
   readouts, disks** (item S). (1) 'View in time/distance domain'
   PARITY: strip drags (move / resize / mark) and the create drag
   Ctrl-snap on the DISPLAY axis — SpeedStrip.snapDisplay: warpFwd
   → quantize on the section-local ABSOLUTE display grid (judgment:
   controls have no "previous node", the strip always snapped
   absolute) → map back (the forward map of the result lands on the
   display grid to lerp dust, gated ≤ 1e-9); the OLD native snap
   runs LITERALLY when the view is off and the strip's native domain
   is the axis domain (explicit guard, bitwise historic — not a
   float round trip); an inactive strip of another domain snaps on
   the AXIS grid through Coaster.sectionMap; a degenerate map falls
   back native; the warp map FREEZES per strip gesture
   (refreshWarp's gate extended to spDragKind != 0 — a control
   altering the profile mid-drag no longer slides the diamond; the
   release redraw re-derives it and the diamond may visibly re-land,
   accepted); readouts in display units (the create drag's "length
   …" in axis units); the popup's Start / Length STAY native
   spinners (judgment, flagged: a display-unit box would need a live
   inverse warp per keystroke) with grey display-unit TWINS "= 12.5
   m" / "= 3.20 s" shown only while the section is viewed in the
   other domain (synced per redraw via spHook; a reversed section's
   twin routes through the same map the strip draws with); the
   viewport disks' drag and readout go through the same mapping.
   (2) POSITION READOUTS: strip hover on a diamond (start / end /
   mark) and during its drag shows "t = 3.20 s" / "s = 12.5 m"
   (SpeedStrip.posReadout — seconds %.2f, meters %.1f, Locale.US,
   z3 dust → 0, larger dust keeps its sign; drawn below the line on
   a collapsed strip (the D215 clip lesson), above it when tall; the
   diamond check runs first and wins over the curve hovers;
   coincident diamonds read the earlier control's END, the viewport
   pick's order); the hover stores (piece, sec, native x) and
   RESOLVES AT DRAW TIME — units follow a view flip without a mouse
   move, and (review fix) a diamond must STILL sit at the stored
   native x (SpeedStrip.diamondAt) or nothing draws (an undo of the
   control had left the readout floating beside nothing); the
   create drag keeps its "length …" only. Viewport: the combLabel
   drops the "Speed control start — " prefix, reads "t = 3.20 s" /
   "s = 12.5 m" (a section IN the run reads the display domain, an
   unconnected piece its native units) and updates LIVE during the
   disk drag (the drag handler calls the hover text — MOUSE_MOVED
   never fires while a button is down; setText only, no per-event
   visibility toggle; the release re-reads; NaN from a
   behind-camera track leaves the control unmoved and the text
   last-valid); accepted: with the graph panel collapsed the
   readout reads the last warp map. (3) DISKS: thickness ×2 along
   the track (CTL_DISK_HT 0.035 → 0.07, CTL_RING_HT 0.015 → 0.03;
   a control end ON a section boundary now hides the thinner
   boundary disk inside it, cosmetic); the pick region = the
   PROJECTED RIM's bounding radius with a 12 px floor
   (ViewDisks.rimPoints — 8 points at 45° at CTL_RING_R in the
   disk's U/R plane, stored at build; pickRadiusPx skips
   behind-camera NaN rim points, all null → the floor = today's
   point pick; nearest by the NORMALIZED score d²/pickR²
   (ViewDisks.pickScore) so a big near disk cannot steal a small far
   one, strict < keeps build order on ties — END before START);
   accepted: a big near disk shadows node markers within its radius
   (the D248 disk-wins-at-press precedence extended to the whole
   disk); the selected ring LIGHTENS by CTL_SEL_RING_LIGHTEN 1.18
   (deriveColor brightness only, saturation kept; green / blue clamp
   at brightness 1) — not white. (4) deselect rules = item 5.2.

7. **Label reverts + the display name** (item L). The DRAWN badge
   is back to the pre-batch-33 form: the type word (Force / Geo /
   S/C / Bridge) + tags + glyphs, NO number / title
   (SectionNames.badgeLabel = shortWord + tags — the one source, so
   the tags are SPACED "Geo ·m", "Force ·m ◀ fx", "Bridge !"; HEAD
   glued "Geo·m" — USER FLAG); " ≠v" then " ≠o" after them; the
   measured width kept (the D248 real-Text measurement, a
   correctness fix); the D248 short form "Geo 2" and its ladder are
   GONE — a strip narrower than the measured width + 8 px shows no
   badge (the HEAD rule). The badge MENU's bold title row STAYS
   ("Force Section 2"); its TOOLTIP is REMOVED (sectionInfoText /
   orientInfoLine / deg2 deleted — sole callers; entrySpeeds kept).
   Accepted: the design / authored / entered speeds, the flag line,
   an s/c section's length + element count and the orientation
   angles have NO textual home in the badge menu any more — the
   glyphs, the init-speed window and the orientation windows carry
   them. "Resolve section conflict: …" → "Resolve conflict: speed"
   / "…: orientation" / "…: speed + orientation" (the op labels
   unchanged). The bridge section's badge menu owner item →
   "Bridge settings…" (grayed "This bridge has no following piece."
   on orphans, unchanged reason); the bridge WINDOW title → "Bridge
   settings (Strict Seam n)" — n = the junction ordinal, the same
   number as the toolbar button; judgment, FLAGGED: no "(bridge)"
   inside the parentheses (the brief's text had it) — the toolbar
   button keeps "Strict Seam n (bridge) ▴" and the Orientation
   dialog's owner button keeps the toolbar text; the title re-titles
   live from one source (SectionNames.bridgeWindowTitle). DISPLAY
   NAME: FvdDesigner.APP_NAME = "Ultimate Vector Design" — the window
   title "Untitled — Ultimate Vector Design" / "x.fvd* — Ultimate
   Vector Design" / "x.fvd* [name] — Ultimate Vector Design" (order
   kept), the Help window "Help — Ultimate Vector Design" (judgment:
   the main title's " — APP_NAME" form; the menu-bar "Help" label
   stays), the .nl2elem <description> "Ultimate Vector Design
   Export" — a LITERAL in the pure-Java exporter (every future
   .nl2elem byte stream changes; Nl2ElemProbe compares geometry
   only), the ~/.fvd-designer.properties header comment "Ultimate
   Vector Design settings" — a literal in the pure-Java AppSettings;
   the settings FILE NAME is UNCHANGED (USER FLAG — a rename needs a
   read-old-write-new migration); pom.xml (artifactId coaster /
   mainClass coaster.Main) and the dev hooks untouched (they name
   the class, not the display name). The PACKAGE rename coaster →
   uvd is NOT in this batch: docs/PACKAGE-RENAME-PLAN.md holds the
   step-by-step for its own commit next session.

8. **The floor flicker — (b′) RETIRED, the floor back to D246
   bitwise, the GROUND-SPLIT track layer** (item F; his "the floor
   grid is flashing like crazy when moving the camera; there are
   even points where I can stop moving and almost the entire grid
   is invisible" on the D248 build). POSTMORTEM with evidence
   (FloorDepth's header carries the six-line version): RC1
   (PRIMARY — blanks the whole grid at rest) — under (b′) the
   visibility of EVERY floor line pixel was a depth-test outcome
   against the SHEET (and the lower-ranked line layers) with a
   nominal margin of FLOOR_LSB_MARGIN = 2 depth LSB; the platform's
   real disagreement between two independently rasterized
   near-coplanar kilometre-scale translucent triangles was never
   measured above one LSB — TESTING #159's D242 probe recorded
   21-79% mis-ordered overlap pixels at 300-1500 m for 4 mm / 3 cm
   offsets, which are 0.02-0.56 nominal LSB (LSB(300 m, near 0.1) =
   5.4 cm, LSB(1500 m) = 1.34 m): fights BELOW an LSB with an
   ASYMMETRIC, per-triangle-biased outcome (79% wrong is not ±1 LSB
   noise), never the clean threshold; D248 itself listed "depth
   format not 24-bit / compare not LESS(EQUAL) → FLOOR_LSB_MARGIN is
   the one lever" as the open risk, and it materialized: when the
   GPU's coplanar bias exceeds 2 LSB the losing layer is DROPPED
   wholesale (not re-blended), and because the bias is per triangle
   (plane-equation setup from that triangle's clipped vertices — the
   sheet spans 7 km and its z/w extrapolates far outside [0,1] at
   grazing looks) whole line layers vanish coherently. RC2 (the
   temporal amplifier — why it FLASHES while moving) — the sheet is
   the depth reference for everything and the ONLY floor layer whose
   vertices move continuously with the eye (unquantized since D242),
   so its ±3500 m quad was near-plane-clipped and its depth plane
   re-derived on every frame of camera motion while the line groups
   hop by q and the axes by 30 m: per frame the sheet's per-triangle
   rounding re-rolled against fixed line planes and the pass/fail
   pattern of the whole grid flipped; second amplifier: the DYNAMIC
   ranks re-numbered every later layer whenever the faintest ring
   crossed its 1/256 bucket. RC3 (near-field, derivable) — the
   required separation 2·z²/(near·2^24) ≈ 1.2e-6·z² m sits below
   float32 vertex rounding under ~2 m, and every z/w depth value
   lies in [0.9, 1) where float32's own ulp IS one 24-bit LSB — a
   2-LSB margin is at the precision floor of the GPU's interpolation
   arithmetic, no lift constant fixes that regime. Ruled OUT: the
   D246 strength law (bitwise unchanged), sheet-after-lines (the
   sheet was first), the lift/translate accounting (FloorGate f1/f2
   were green — the algebra was exact, the failure was not in the
   math), the axis anchor quantum, a stale-shear frame. Why the
   ladder passed: 15 STATIC poses with no motion sequence and no
   per-frame metric cannot see a per-frame re-roll or a
   pose-specific rest loss. DECISION TREE: (i) keep (b′) and correct
   the margin — REJECTED: a bias-robust margin on this GPU is
   unknown (expect ≥ 8-32) and the screen float of rank r is
   r·m·0.0073·|eyeY| px — at m = 16 a 20 m eye floats the axes ~8 px
   and coincident decades ~1.2 px apart (visible double lines);
   q-quantizing the sheet would stop only the per-frame re-roll;
   RC3 has no lever at all. (iii) lines re-drawn after the track
   only over underground regions — REJECTED as impossible in
   JavaFX 3D: no stencil / mask / depthWrite / colorWrite control;
   "only over underground track" is a per-pixel condition only a
   depth test against the sheet can express — (b′)'s failing test
   again. (ii)/(a) sheet-only after the track — flicker-free by
   construction but NOT a "dimming" veil: the sheet's material is
   not premultiplied and prism blends src·1 + dst·(1−α), so it ADDS
   its full lit colour S ≈ (0.62, 0.69, 0.84) over everything under
   it — lines at o ≳ 0.3 clip to white (the graded fade flattens;
   pre-compensable to ±5%·o) and the gizmo-coloured axes turn pale
   white / pink / cyan (unrecoverable) — kept as a written, never
   shipped stopgap. CHOSEN (iv) THE GROUND-SPLIT TRACK LAYER: the
   ENTIRE floor (sheet + line decades + axes) returns to the
   D242-D246 build BITWISE — DepthTest.DISABLE, drawn FIRST, pure
   submission order, no shears, ONE mesh per ring again (the D248
   x/z two-mesh split reverted — pixel-equivalent under DISABLE,
   60 MeshViews), axis quads back at y = 0.005 (HEAD), the sheet
   translate unquantized (harmless without depth tests), Ortho as
   D246 (Top: sheet visible, the 2D canvas IS the grid; Side: sheet
   hidden) — and the see-through look comes from ORDER alone: every
   track-layer mesh builder routes its faces by the y = 0 plane
   (FloorDepth.underMask: a face goes UNDER iff its centroid's world
   y < −GROUND_DEADBAND 0.07 m = the rail radius; straddlers by
   centroid, ties toward ABOVE — a rail resting ON the ground
   renders as one clean tube, an 8-sided r = 0.07 tube's lowest
   centroid sits 0.0632 below its centre so a tube centred down to
   ≈ −7 mm is wholly above, at −5 cm it sheds its bottom faces, at
   −8 cm half/half; a mesh with no under face is left UNTOUCHED —
   bitwise the D246 object graph, the point-scan fast path); world
   order [trackUnder (ENABLE), ghostUnder (DISABLE)] → [gridGroup =
   sheet + decades, axisGroup (DISABLE)] → [trackGroup, povHolder,
   trackBallGroup] → [ghostGroup (DISABLE)] — underground halves
   drawn BEFORE the floor so the sheet + lines + axes composite
   over them (the D248 under-* look), above-ground halves after it
   as always, ZERO coplanar depth tests anywhere (the two halves are
   separated by real metres along any ray); while the eye is BELOW
   ground the two track blocks SWAP around the floor
   (setFloorOrder, a scene-graph reorder only on the sign flip,
   ±0 = above; ortho forced above); the stalled GHOST splits the
   same way (its under half in ghostUnder drawn after trackUnder,
   its above half LAST with DISABLE = D248's composite rule kept; a
   ghost straddling y = 0 is cut by centroid); every builder routes
   through the one recursive split — rails, ties (TiePiece carries
   TWO groups, the re-tint re-splits), plates, heartline strand,
   comb teeth (the under half's visibility BOUND to its source
   group), inactive coasters (buildOtherCoasters), extra panes; node
   markers / boundary + control disks / reveal balls STAY in the
   after-floor group (USER FLAG — an underground marker / disk /
   ball draws over the floor: they are pick anchors whose groups
   are index-walked, re-parenting halves would break the picks);
   trackUnder is NOT mouse-transparent (underground rails stay
   clickable like any rail; track picks are screen-space anyway);
   accepted: a straddling tube segment is cut per FACE (≤ one
   segment ≈ 0.14 m of jaggedness vs D248's real-depth slice); a
   ground-level eye sees the D242 1 px sheet sliver. FloorDepth's
   lift API (FLOOR_LSB_MARGIN / DEPTH_BITS / ORTHO_AXIS_LIFT / lsbK
   / liftAt / shear / ranks) and FloorGate f1-f7 are RETIRED (no
   live-sounding dead code); the strength law + ladders stay;
   FloorGate re-baselined 18 (the strength law's sanity, the
   ladders + GROUND_DEADBAND, eyeBelowGround's signs, faceWorldY,
   the split routing on a 500-ring tube dipping +5 → −15 m incl.
   the deadband family, purity + a −30 m translation). STANDING
   RULE (the FloorDepth postmortem): no depth-tested coplanar floor
   layer ever again without a MEASURED platform envelope (a
   coplanar-quad probe at k = 1..64 LSB on the user's GPU) and a
   screen-float budget. VERIFICATION: the -Dfvd.gridshot ladder
   (15 poses; its first 9 pixel-diffed against a HEAD-f84061a
   worktree build: identical outside re-run PNG dust — the
   bitwise-D246 claim holds for every no-track pixel and above-
   ground track pixel; underground-track pixels change by design;
   the six under-* poses show the sheet + lines + axes over the
   buried valley, the swapped order from below, the split ghost)
   and the NEW `-Dfvd.gridshot.sweep=<dir>` dev hook (the ladder's
   TEMPORAL twin, kept: s1 a low ≈ 2.6 m eye panning 0.25 m per
   frame along x, s2 a grazing ≈ 30 m eye over the 10 m decade
   yawing 1° per frame + nine REST stills pitch −5/−8/−12 × yaw
   10/20/35 — the "stopped and the grid is gone" family, s3 the
   pov-height and low-graze poses as 0.1 m micro-pans; per frame a
   LINE-PIXEL COUNT — lower-half pixels whose luminance exceeds
   that half's median by > 0.08 — and each sequence's max
   consecutive-frame ratio: a dropped layer reads as a large swing,
   the D246 sub-pixel phase only drifts). SUPERSEDES D248 item 7
   (the ordering paragraph and its "no patchwork or flicker" claim)
   and restores D242 fact (b)'s answer (DepthTest.DISABLE at the
   FRONT of `world`) with the split as the new sentence; D242 1(b)'s
   accepted corner "a below-ground camera never sees grid over
   track" is now closed by ORDER, not depth; D243's "the axes draw
   AFTER the grid, submission order" stands again. If he reports
   the D246-style sparkle at low height after this, that is the
   height-gated D246 mechanism, pre-existing and accepted — the
   sweep metric distinguishes the two.

9. **Review rounds** (per item, folded above): M — the four
   DESIGN_ONLY sink fixes (seam rings compounding, ↺ tiers, global
   ring baseVals, local-ring decompose tier); B — the edge table's
   phantom gate ids (h3/h5/h6 relabeled to the checks that exist;
   no source change); O — the reviewed edge rows stand; C — the
   wrapper's own contract gated (nested no-op, throwing op
   restored, out-of-range inert; a run-after-run delete down to the
   empty coaster); U — the gizmo tie-to-ball rule; S — the
   draw-time diamondAt re-resolution; L / F by reading. Deferred
   from the M review (item 3 closed them): the rigs' axes riding the
   real frame, gizmoSigNow blind to the mode, the DESIGN_ONLY box
   showing the real value.

10. **Flagged for the user** (decisions folded above, listed once):
   the default manual / position bridge shows no yaw ring (item 2);
   free-piece padlocks keep the opposite (edit-guard) sense (2);
   "Geo ·m" spaced tags (7); "Bridge settings (Strict Seam n)"
   without "(bridge)" (7); the settings file name stays
   ~/.fvd-designer.properties (7); markers / disks / balls draw over
   the floor underground (8); the popup's Start / Length stay native
   spinners with twins (6); the pitch/roll BOX edits the design tier
   under Edit design orientation (3).

Verification: OrientModeGate 51 (NEW — item 1's model + item 3's
seam design-box contract and the full-scope PlacementSnapshot; 48
+ review round 2's g7 / g7b / h6),
PlacementGate 28 → 41 (item 2's section h), RangeResolveGate 23 →
43 (item 4's section s), RunGate 51 → 66 (item 4's section j:
deleteRun), GestureGate 90 → 108 (items 5.4, 5.6, 6.3), StripGate
30 → 49 (item 6's display-domain snap + readout formats), LabelGate
89 → 103 (item 7), FloorGate 15 → 18 (RE-BASELINED, item 8) —
plus the migrated fixtures (OrientGate 23, OrientGate2 18,
coaster.SeamShapeGate 31, coaster.ConflictGate 17, CopyGate 73,
RangeResolveGate, RunGate, StripGate, PlacementGate) and the
determinism core re-run per item (DetermGate197/198/199/200 6/4/10/7,
LoadGate196 7); the docs round re-ran, on the finished tree, the
eight item gates above + DetermGate197/198/199/200, LoadGate196,
OrientGate, OrientGate2, SeamShapeGate, ConflictGate, CopyGate 73,
MergeGate 23, InsertGate 40, EndFollowGate 73 — all green (the run
recorded in TESTING #166); the full 33-member battery is the
batch's closing run. JavaFX-side verification: the winshot A/B
(item 5.3's pre-show layout), the pickprobe hook (item 5.4's ball
tie), the gridshot ladder + the HEAD pixel-diff + the sweep hook
(item 8); the flag row, readouts, seam design boxes, rigs, the
Cancel flows, the live init-speed window, Delete…, the selection
choke, the strip readouts / twins and the disks are interactive —
TESTING #166 walks them.

## D250 (v19.21) — Package rename `coaster` → `uvd` (mechanical; no behavior change)

Date: 2026-09-25, the user's go-ahead on docs/PACKAGE-RENAME-PLAN.md
("The package name uvd sounds great … please start"; app and editor
closed, OneDrive paused). ITS OWN COMMIT, nothing else rides on it.

What changed: `git mv src/main/java/coaster src/main/java/uvd`
(34 files, git sees renames, ≤ 5 changed lines per source file);
`package coaster;` → `package uvd;` in all 34 sources + the 16
package-scoped gates and `import coaster.` → `import uvd.` in 88 gate
files (98 lines) by a count-asserting script (whole-file atomic
writes, UTF-8, line endings kept, no BOM); pom.xml `<artifactId>` +
`<mainClass>` → `uvd` / `uvd.Main`; FvdDesigner.restartApp's relaunch
literal `cmd.add("uvd.Main")` (the D242 Apply-and-Restart path) + the
three comments naming the launcher; the run lines in gates/README.md,
CLAUDE.md, docs/TESTING.md and the memory notes now say `uvd.X`.
NOT renamed, by decision: the settings file
`~/.fvd-designer.properties` (its name is a user-visible contract —
renaming it would silently reset every preference), the save format
magic (FVDD3/FVDD4), gates/attic-noncompiling, the historic prose in
this file and TESTING (a D-entry describes the tree it was written
on), the memory folder key (the project folder rename is the user's
own step and re-keys it), and the Save-As default name "coaster.…"
(user choice — kept). The file EXTENSION change is the NEXT commit
(D251), deliberately separate so the rename stays mechanical.

Verification (the plan's steps 7-10): `rm -rf target/classes && mvn
compile -q` EXIT 0 (119 class files under target/classes/uvd);
`rm -rf gates/out` + javac of every gate EXIT 0; the full 33-member
core battery green with counts identical to the D249 closing run
(DetermGate197/198/199/200 6/4/10/7, LoadGate196 7, OrientGate 23,
OrientGate2 18, MultiCoasterGate 15, SmoothRealGate 15, InflSplitGate
22, ReanchorGate 3, MirrorGate 20, YpAbsGate 22, uvd.SeamShapeGate 31,
RangeResolveGate 43, CopyGate 73, MergeGate 23, InsertGate 40,
LabelGate 103, RunGate 66, PlacementGate 41, OrientModeGate 51,
GestureGate 108, EndFollowGate 73, FloorGate 18, StripGate 49,
uvd.ConflictGate 17, Nl2ElemProbe 22, DecompProbe + InflMatchProbe +
Phase2/Phase3/DomainGate pass); smoke launch via `mvn javafx:run`
came up titled "Untitled — Ultimate Vector Design"; a DIRECT
`java --module-path <openjfx 26 win jars> --add-modules
javafx.controls -cp target/classes uvd.Main` — the exact command
restartApp rebuilds — came up with the same title (so Apply-and-
Restart relaunches); BOM scan clean, line endings unchanged, `git
diff --stat -M` = 34 renames + 143 files / +212 −180 (no whole-file
churn). Still the user's by-hand check: Preferences > Resolution
Scale… > Apply and Restart on his machine (TESTING #167).

## D251 (v19.22) — The design file extension `.uvd` (Save enforces it; Load opens NOTHING else)

Date: 2026-09-25, the user's answers to the rename questions: "Would
this also be a good time to change the file extensions to .uvd? [I
don't care about any of my past saved files]" and "Make it stop
opening .fvd files entirely"; the Save-As default NAME stays
("keep the save as default name" → "coaster.uvd").

What changed (FvdDesigner only — DesignIO and the FVDD3/FVDD4 format
are untouched, so every headless gate still writes/reads its `.fvd`
fixtures through DesignIO directly):
- DESIGN_EXT ".uvd" + DESIGN_FILTER_LABEL "Ultimate Vector Design
  (*.uvd)"; hasDesignExt(File) = a case-insensitive test on the file
  NAME (a FileChooser's filter can be bypassed by typing, so both
  paths re-check the name themselves).
- Save / Save As: the one filter is *.uvd, the initial name is the
  open file's or "coaster.uvd", and a chosen name without the suffix
  gets ".uvd" APPENDED (design.txt → design.txt.uvd, mirroring the
  Windows chooser's own append for bare names) — because the app
  opens only .uvd, a design saved under any other name would be a
  file it could never reopen: the suffix is enforced rather than
  left as a silent trap.
- Load: the one filter is *.uvd, and a picked file whose name is not
  .uvd is REFUSED with the stats-bar line "Not opened: only .uvd
  files open (<name>)." — before anything touches the live document
  (the D216 rule: refuse cleanly BEFORE the doc mutates). No content
  sniffing, no rename-on-open, no .fvd fallback — "entirely" as
  asked. His old .fvd files: rename them to .uvd by hand if ever
  wanted (the bytes are the same format).
- Help (File paragraph): "Save / Save As write a .uvd file — the
  only kind that opens". CLAUDE.md's settings-split + AppSettings
  lines say .uvd. The settings file keeps its
  `~/.fvd-designer.properties` name (D250's reasoning).

Verification: wiped target/classes/uvd + mvn compile EXIT 0;
LoadGate196 7 / DetermGate198 4 / MultiCoasterGate 15 green (IO
untouched, run as a belt); smoke launch titled "Untitled — Ultimate
Vector Design". The chooser behavior itself is his hand test
(TESTING #168): Save As shows only the .uvd filter and defaults to
coaster.uvd; typing a bare name saves name.uvd; File > Open lists
only .uvd files, and a typed .fvd path is refused with the stats
line while the document stays put.

## D252 (v19.23) — File > Rename…, the Optimize-length readback (a DISABLED box is never "typing"), and the slow bridge spinner (the stale target-head capture)

Date: 2026-09-25, the user's session-opening list: a Rename… item
under File; a file "relatively similar" to the attached "UVD long
bridge update time 9.25.26.uvd" (3 pieces, 2 optimize-orientation
bridges, REAL_ONLY targets, pitch/roll held) where every bridge-window
spinner click took ~5 s to update BOTH bridges; and the Length box not
updating when Optimize length was clicked right after an arrow click.
He then asked not to over-spend on the stall if it stayed
unreproducible — the mechanism below was already confirmed.

1. RENAME… (FvdDesigner.renameDesign, File menu after Save As…): a
   small owned WINDOW_MODAL dialog (the Delete… confirm's dark look)
   pre-filled with the current name; Rename/Enter = java.nio
   Files.move in the SAME folder with the .uvd suffix enforced like
   Save, then currentFile := the new file + updateTitle — the
   in-memory document and its dirty flag are untouched (an unsaved
   edit still saves, to the new name). No reload, no restart: the
   open file is only a path the next Save writes to. Refusals on the
   dialog itself: blank, a path (/ \ : . ..), an existing file (a
   case-only rename passes — same file on Windows); an OS failure
   (open elsewhere, no permission) reads "Rename failed: …". A
   never-saved document grays the item with "Save the design first —
   there is no file to rename yet." (fileMenu.setOnShowing swaps the
   slot, the D235 rule). Help's File paragraph names it. Robot-driven
   end to end by the bridgeprobe hook's -Dfvd.renameprobe=1 step on a
   scratch copy: "rename test.uvd" → "rename test-renamed.uvd", title
   + stats line followed, old name gone.

2. OPTIMIZE-LENGTH READBACK — ROOT-CAUSED in the running app (the
   new -Dfvd.bridgeprobe hook Robot-clicks the Length arrow, then the
   check): after the arrow click the Length EDITOR holds focus; the
   check's apply (setBridgeOptLen → applyOptimalLength → optimizeBridge
   + rebuildTrack → updateBridgeControls → syncBridgeRows) runs
   setIfOff on the box while typing() reads it focused → SKIPPED;
   refreshBridgeRows then disables the box, but JavaFX moves focus off
   a disabled node only at the NEXT pulse, so every later sync inside
   the same click skipped too — the box showed 23 while lastOptDist
   was 20 until the next unrelated rebuild (his "updated when
   unchecked"). FIX: FvdDesigner.typing() returns false for a DISABLED
   spinner (the skin cannot take typing into it, so the D247 guard has
   nothing to protect), and every row sync sets the enable state
   BEFORE its value writes — syncBridgeRows (refreshBridgeRows moved
   to the top, the duplicate tail call dropped), syncPlacementSpinners
   (the x/y/z + yaw + pitch/roll writes moved after their disables)
   and the seam window's per-axis sync (setDisable first, `||
   sp.isDisabled()` in the write condition; its padlock path already
   forced). Same class, same fix, in all three windows: a box the
   model just took over (the Length box under Optimize length, a free
   axis handed to the march, an unlocked seam axis) now shows the
   model at once. Probe after the fix: after check — box 20, editor
   "20", lastOptDist 20.

3. THE SLOW BRIDGE SPINNER. Not reproduced on the attached file:
   headless (gates/BridgeSlowProbe.java — the shell's rebuild loop
   verbatim + the click route) 15-350 ms per click across REAL_ONLY /
   LOCKSTEP × locks × Optimize length; in the REAL app (the
   bridgeprobe hook stepping the spinners like the arrows, -Dfvd.timing
   breakdown) 50-130 ms per click, frames 10-80 ms. A six-family
   headless hunt (modes × locks × optLen × conflicts; target-head
   types; chain topology; energy/reconcile; the D249 inner settle;
   his likely edit history) with independent re-verification of every
   report found NO runaway to the 12 + 40 cap on the file, but ONE
   real inefficiency on every click and three documented heavy
   regimes:
   (a) THE STALE STAMP (the fix shipped): the UI's explicit re-place
   (bridgeTargetWrite / setBridgeLock / the Length paths →
   GraphEditor.applyBridgeGap / applyOptimalLength → optimizeBridge)
   ran BEFORE any compile had seen the write it follows, so the march
   and the stamped signature were built on the PREVIOUS compile's
   target-head capture (solvedTarget / accB / jerkB / rollB / vB — a
   typed pitch changes tB.y at once). The stamp never matched the next
   capture → the auto pass re-marched the bridge again in pass 1, then
   the moved target changed the NEXT bridge's A side → pass 2 → …:
   a bridge-0 click = 3 compiles + 4 marches, a bridge-1 click = 2 + 2,
   and under Optimize length every one of those marches runs the
   ~100-140 ms knee ladder (optimalBridgeLength — 3 knees + 4 compiles
   per click on this file, ~1.5-3 s on a loaded machine per the
   verifiers, 4 s measured under sibling load). His third bullet says
   Optimize length was on while he saw the stall — the likeliest
   story. Coaster.captureBridgeTarget(bs, targetIdx) =
   TrackCompiler.captureTargetHead on the target AS IT IS NOW; the two
   GraphEditor chokes call it before marching/kneeing. Effect (gates/
   BridgeSlowProbe `fixed`: the OLD route vs the NEW route on the same
   click): bridge-1 clicks BITWISE the old fixed point with 1 + 1
   passes instead of 2 + 1; bridge-0 clicks 2 + 1 instead of 3 + 1 and
   the auto pass's knee work 334 → 55 ms, the fixed point to 6e-14 …
   2e-12 m (the old route wrote the aligned yaw twice — ulp dust); in
   the app bridge-1 clicks 1 march + 1 compile (was 2 + 2), bridge-0
   2 + 2 (was 4 + 3). Model callers (gates, fresh bridges) call
   optimizeBridge exactly as before — DetermGate197/198/199/200 6/4/
   10/7, PlacementGate 41, OrientModeGate 51 green. (A free-yaw write
   slipped past the UI guard used to be KEPT by the stale march — the
   +1 survived because the delta cancelled the old heading — and is
   now aligned away; the UI never offers that write.)
   (b) DOCUMENTED LIMITS, not changed (his budget call): N chained
   optimize bridges settle in chain-depth passes (each re-march moves
   the next entry — inherent; N=5 ≈ 6 compiles ≈ 0.2 s, knees extra);
   a LOCKSTEP target with pitch/roll FREE at long lengths (120 m)
   spends 1-2.5 s INSIDE optimizeBridge in the D249 inner settle
   (24 rounds × captureTargetHead), and the documented slow-pitched
   class (2 m/s, +30°, 80 m) 5 s; a deep REAL-anchored chain (46
   resolved sections behind a bridge) costs one exact pass per
   section — 37 passes / 4 s (the D200 triangular termination is the
   cost). If the stall returns, his actual file + the -Dfvd.timing
   breakdown pin it in one run.

4. TOOLING: uvd.Prof (the static-final-guarded profiler),
   FvdDesigner.loadDesignFile(File) (the chooser-free half of Load),
   the -Dfvd.bridgeprobe / -Dfvd.renameprobe hooks, gates/
   BridgeSlowProbe.java, and the hunt's 25 probes parked under
   gates/probes/cascade-2026-09-25/ (research, not battery). Also
   .gitignore: the `hs_err_pid*.log` pattern had been glued onto the
   settings.local.json line (no newline) — three August crash logs
   sat untracked in the root; split, they ignore again.

## D253 (v19.24) — Live gestures HOLD the "Optimize length" knee (the second slow-bridge file)

Date: 2026-09-25, the user's follow-up with "UVD long bridge update
time 2 9.25.26.uvd": dragging the graph nodes of the force section
between the two bridges was visibly sluggish, editing the bridge
itself had a smaller delay, and converting the second bridge away
made the section before the first bridge instant again. He allowed
that it might be a valid edge case; it was, and it had a cheap answer.

The file differs from the first one in one setting: the SECOND bridge
has Optimize length ON (blen field 4 = 1). Headless (gates/
BridgeSlowProbe `drag` — six live events on piece 1's lane-0 node 1
with the fast loop only, then the release settle): 520-700 ms per
event, ~90 % inside autoReoptimizeBridges. Per event the fast loop
runs three compiles: pass 0 re-marches bridge 1 (its target head
changed — cheap), which moves piece 1, so pass 1 re-marches bridge 2
— and under Optimize length that means optimalBridgeLength's ladder
(8 candidates + 3 bisections, each a virtual march + a probe
integration; 300-550 ms on this banked crest, where the D156 damped/
staged march runs long per candidate). Dragging piece 0's nodes did
the same through both bridges (300-400 ms). Removing bridge 2
removes the knee, exactly his observation.

The knee is a REST-TIME answer (the D200 split: live gestures keep the
fast tube, the release settles): Coaster.autoReoptimizeBridges(r,
live) — rebuildTrack's fast loop passes gestureHot; a live event
re-marches an optLen bridge at its LAST length and sets the transient
BridgeSection.kneePending, and the first AT-REST pass treats a
pending knee as a signature mismatch, re-knees, stamps and clears it.
The live march keeps a PROPER stamp — a first draft stamped SIG_STALE
and would have run every live rebuild to its 12-pass cap (SIG_STALE
never matches), caught before compiling. GraphEditor.applyOptimalLength
takes the same branch under gestureHot (the bridge's own rig drags).
At rest (live = false, kneePending false) the pass is bitwise the
historic one. Measured: per drag event 700/380 → 31-34 ms (three
compiles + two cheap marches), the release settle re-knees once
(280-340 ms), the settled placements old vs new to 3.1e-13 (yaw
alignment dust); DetermGate197/198/199/200 6/4/10/7, PlacementGate 41,
OrientModeGate 51 green.

What stays: a SPINNER CLICK on an optLen bridge (or upstream of one)
is a discrete edit, so it knees at once — 230-540 ms per click on
this crest in the real app (the explicit knee, or bridge 2's at-rest
re-knee after a bridge-1 click). Deferring the knee across a
hold-repeat arrow burst would need a burst-end timer (no release
event exists for a spinner) — offered, not built. Help's bridge
paragraph says the length holds during a drag and re-optimizes on
release.

## D254 (v19.25) — Batch 36, round 1: the POV pulse clock, the insert-drag jump, FOCUSED ≠ TYPING, the orientation windows restructured (Edit with gizmo, top-left-anchored growth), gizmo Shift/Ctrl, the POV design readout, the handle readout, the badge-menu order

Date: 2026-09-28, the user's first feedback list on batches 32-35 plus
new items (verbatim list in the session; each point below quotes its
ask). Method: five parallel investigators (two in worktrees with
instrumented runs of the app, three read-only audits) with an
adversarial challenge on every root-cause claim, then ONE writer.
Items 4 (gizmo modifiers) and 1 (the POV skip) were implemented from
the audits by a worktree agent — see their own paragraphs below.

1. THE INSERT-AND-DRAG JUMP ("if I adjust the view of the graph
   editor then try to click+drag on the graph to add and move the
   added node in the same motion, the graph editor jumps … if I
   click+drag an existing node first it doesn't"). ROOT CAUSE
   (measured with a Robot hook in a worktree, then independently
   re-run by the challenger): GraphEditor.pressCurveInsert froze the
   drag window with two bare field writes, dragWinT0 = viewStart()
   then dragWinT1 = viewEnd() — and viewEnd() returns the FROZEN
   field the moment dragWinT0 is set, so the second read returned
   dragWinT1 itself: the PREVIOUS gesture's end (0.0 at startup).
   After any zoom/pan (which write viewT0/viewT1 only) the
   insert-drag mapped through [live start, stale end] for the whole
   gesture — the plot jumped and the node landed ~2 s back on its
   left neighbor (frozen [0.9115, 0.0000] vs [0.9115, 13.5522], node
   8.76 → 7.17 s); a plain node drag (beginGesture, which read both
   ends into locals first) left the CURRENT end in the field, so an
   insert-drag right after it happened to work — exactly the user's
   cure. FIX: one helper freezeView() (both ends into locals, then
   the writes), beginGesture calls it (bitwise its old math) and
   pressCurveInsert freezes THROUGH it BEFORE the insert (behaviorally
   identical for an on-curve insert — t ≤ the section end, the run
   domain cannot change) and unfreezes on a refused insert. Verified
   in the worktree: both Robot phases PASS (err −0.007 s = the Robot's
   event cadence; the mapping identical before, during and after the
   drag). No Help change (a bug, not a control).

2. FOCUSED IS NOT TYPING — the init-speed window's "Set Design to
   Real" stale box ("the value in the box doesn't update even though
   the value is correctly updated … do a sweep of the program"). The
   audit measured JavaFX 26 itself (standalone probes against the
   same jar): a Spinner is AUTO-FOCUSED on show when it is the
   window's only traversable node, SpinnerSkin fake-focuses its
   editor whenever the Spinner has focus, an arrow click keeps it
   there, and a non-traversable button click never moves it. So the
   init-speed window's own button ran refreezeSpeedOnly → the hook's
   setUnlessTyping → typing() read the editor focused → SKIPPED, until
   a click into another window blurred it. The D252 disabled clause
   cannot help a box that stays enabled. The sweep found the SAME
   class on the Track Piece and bridge windows' own ↺ / Real → Design
   / Design → Real (under DESIGN_ONLY) beside a just-clicked row, on
   the init-speed ride-entry clamp (typed 0.5 → model 1.0, box
   "0.50"), and on the speed-control POPUP (a Popup's editor never
   blurs on main-window presses, so every strip/disk drag resync of a
   once-clicked box was skipped for the popup's life); cross-window
   writers were already safe (owned Stages blur on any main-window
   press, the 3D/graph presses pull focus explicitly). THE ONE RULE
   (replacing "a window's own actions force their writes", which
   would have been the third patch of this class in three days and
   still missed the popup): FvdDesigner.typing() = focused AND the
   editor text differs from the converter's rendering of the current
   value (isUntouchedEditor — commitTyped's own D247 definition of
   "nothing was typed"). A merely-focused box follows the model; a
   half-typed one ("12" over "12.00") keeps its text; a same-value
   resync is a no-op either way, so the caret moves only when the
   number was actually stale. The seam window's inline guard routes
   through app.typing (one predicate for the three windows); the
   node editor's LiveNum guard is already an equal-text no-op.
   D247's motivating case (re-syncs per gizmo drag event fighting
   the caret of the D232-D247 DARK POPUP seam pop-out) moved out from
   under the guard when those windows became owned Stages in D248.

3. THE INIT-SPEED WINDOW LAYOUT ("Set Design to Real" had moved right
   of the box). git log -S: the only change was 4ae50e2's D249
   LiveWindow conversion — show() builds its own Confirm/Cancel
   footer with no slot for a caller's button, so the conversion
   parked the button in the only row the caller still owned. FIX:
   LiveWindow.show takes footLead nodes placed LEFT of Confirm/Cancel;
   the window is [label][box] / [Set Design to Real][Confirm][Cancel]
   again (the pre-D249 shape with Confirm/Cancel in Apply/Cancel's
   seats).

4. THE ORIENTATION WINDOWS (items 1-7 of the "Track Piece / Strict
   Seam orientation features" list):
   - ROW ORDER: Track Piece = orientation block (yaw | pitch, roll |
     the mode row + what it reveals | Design ↔ Real) → separator →
     Anchor x/y/z → a 10 px gap + separator → the ops row (Reverse…
     Mirror Copy Switch Coaster… Delete…) → Confirm/Cancel; bridge =
     mode + Length | yaw | pitch, roll | the target's mode row +
     Design ↔ Real → separator → Next piece x/y/z → footer. Only the
     x/y/z rows moved.
   - "EDIT WITH GIZMO" (Track Piece only, right of the ↺, unchecked
     by default): the check = RING VISIBILITY; the box is ALWAYS
     editable on a free piece — the user's words define the check
     that way, so the padlock's accidental-edit guard (D101) is gone
     (flagged). Backed by uiLockPitch/Roll INVERTED (no new field:
     snapshots, Cancel, TpRecord, copy, the gizmo sig and the rig
     builder untouched). On a follower / a bridged-in piece the
     check is disabled with the junction-named reason and reports
     that rig's truth; a bridged-in piece's Track Piece window no
     longer carries the bridge's padlocks (D249's sharing ends —
     the bridge window is the one surface). The wizard's
     open-rings-while-editing (uiLock false) and Done's reset (true
     = unchecked) keep their meaning.
   - "Affect Subsequent Strict Seams" shows only while a junction
     FOLLOWS the piece in its run: Coaster.hasSubsequentJunction(p)
     = p < runEndOf(p) (a live trailing bridge or a follower — the
     flag's own contract carries bridge targets; an orphan bridge /
     run tail = none); LiveWindow.flagRow(…, hasNext) hides the row
     (the stored flag is inert then and reappears with its value).
   - Renames: "Edit Real and Design Independently" (INDEP_LABEL);
     the bridge window title "Bridge Settings (Strict Seam n)"
     (SectionNames.BRIDGE_TITLE — title case like "Track Piece" /
     "Strict Seam n"; the badge-menu item keeps "Bridge settings…"
     in sentence case; LabelGate's three title expectations updated).
   - THE NO-JUMP MODEL (structural, per the user's spec): LiveWindow.
     refit no longer re-pins the window's BOTTOM edge above the
     canvas — that rule shifted the whole window UP by every
     revealed row (the resolve buttons, the mode radios) and back
     down when they hid, and re-snapped a dragged window on every
     size change: the "jumping around". refit now only RESIZES
     (top-left anchored: a revealed row extends the bottom edge; the
     root's min width RATCHETS so a window never narrows; the
     screen-bounds clamp is the one residual move), the readouts
     RESERVE their width (Readout is managed at READOUT_W from the
     start, show = visibility only — the seam window used to grow
     ~100 px per conflict), and every revealable row sits BELOW the
     always-visible rows, so the item under the mouse never moves
     and the rows below shift by exactly the revealed height. The
     pre-show placement (D249) stays; a reveal now overlaps the
     canvas top by the revealed height until it hides — accepted
     (the window can be dragged and stays). Residual, flagged: the
     Anchor x/y/z row of a bridged-in target under REAL_ONLY +
     optimize-orientation can still shift when a march toggles the
     target's conflict (the resolve row sits above it by the
     requested order).
   Measured with the winshot hook: Track Piece 466×259 (was 430×234
   — the check row is the widest), seam 389×173 (was 302 — the
   reserved slots), bridge 442×338, titles as specified; LabelGate
   103 / OrientModeGate 51 / PlacementGate 41 green.

5. SMALL ITEMS: the badge menu swaps "Delete section" (now LAST,
   after the influence block's separator) and "Convert…" (now right
   after the resolve block's separator, where Delete was) and draws a
   line UNDER the view items ("View in … domain" / "View rates in …")
   before "Orientation settings…" (non-bridge sections). The POV bar's
   pitch/roll parentheses show the DESIGN elevation/bank as
   "(d = 12.345°)" while the POV sits on an orientation-conflicted
   piece (FvdDesigner.povConflictTilt: entry ⊕ design⁻¹ for a free
   piece, Coaster.seamConflictTilt off the previous exit span for a
   follower; design orient = tilt⁻¹ · true per sample; the slot
   templates widened so the swap never ratchets). Hovering or
   dragging a HANDLE shows "out 0.33 [ease]  slope 1.234 g/s" — the
   fraction and the slope in lane units per axis unit, the node
   window's own numbers (hoverKnob via hitKnob on the selected node's
   knobs; grey on hover, white while dragging).

6. GIZMO MODIFIERS ("Make holding shift while making adjustments to
   gizmos adjust the movement ten times slower, and for any gizmos
   that don't already … snap incrementally when holding ctrl make
   them do this too"). THE AUDIT of every drag sink (entry-rig
   arrows, manual-bridge arrows, the tangent length arrow, the three
   ring families — piece / bridge end / seam —, the center + track
   balls, the speed-control disks): EVERY sink already snapped under
   Ctrl (Local arrows/ball in increments from the press, Global ones
   onto the absolute world grid by D183, the length arrow and Global
   rings on absolute values, Local rings in increments, disks on the
   strip's grid) — no new snap path was needed. SHIFT = a tenth of
   the mouse travel, RE-BASED per event: Gestures.slowDebt keeps a
   per-gesture DEBT (raw − effective travel) that grows by 0.9·Δ
   while Shift is down and freezes otherwise, so pressing Shift
   mid-drag continues from where the part IS at a tenth of the speed
   and releasing resumes full speed from there — the whole-travel
   ×0.1 variant would have snapped the part 90 % back toward the
   press. With Shift never touched the debt is exactly 0.0 and
   `x − px − 0.0` is bitwise the historic travel (GestureGate 10a;
   the pickprobe's drag and ring lines identical before and after).
   Viewport3D: beginSlowTravel / slowTravel feed wireAxisDrag,
   wireRingDrag and wireBall (the ball's 3 px deadband stays on the
   RAW travel); the disks carry the debt in NATIVE units seeded from
   the disk's own x. CTRL+SHIFT = the snap at a TENTH of the step —
   the app's own keyboard convention (nudges, spinners: Shift =
   tenths, Ctrl = snap, both combine), not "the full step on the
   slowed value": arrows/ball by snapLen/10 (Global: the world grid
   at that step), the length arrow by step/10, rings to 0.1°, disks
   on their native step/10 (AxisSink.apply gained a `fine` flag;
   gizmoStep). Flag: under a cross-domain view a Ctrl+Shift disk
   snaps on the NATIVE tenth-grid, not the display grid (the strip's
   own snap takes no step; plain Ctrl keeps the display grid). The
   pickprobe itself had a latent NPE (partDistPx routed a BALL
   through ringDistPx, whose dir() is null — the probe aborted
   before its drag sections); fixed with pickPart's own
   silhouette-edge metric. GestureGate 115 (was 108).

7. THE POV PULSE CLOCK ("Periodically [every roughly half second] …
   the POV skips forward just a frame or two … noticeable when
   pitching or rolling"). ROOT CAUSE, measured and then reproduced
   by the challenger: on Windows the JavaFX glass pulse timer ticks
   at an INTEGER 16 ms (QuantumToolkit.PULSE_INTERVAL = 1000 / 60 —
   62.5 Hz; WinTimer has no vsync timer) while vsync'd frames present
   every 16.67 ms; each pulse's scene sync waits behind the previous
   present for the accumulated 0.67 ms/tick drift until a whole tick
   is SWALLOWED, every ~25 pulses. The pulse-start deltas read 16,
   16, …, 32 while the frames still display one per vblank — so the
   ride, advanced by the raw delta, ran 4 % slow for 24 frames and
   then jumped two frames at once: a barcode painted per pulse and
   read off the SCREEN at 60 Hz showed 27 consecutive-frame 32 ms
   ride jumps per 6 s at 0.4 s spacing. Not FX-thread work (timer
   handlers ≤ 8 ms), not GC, not the sample interpolation, not the
   stall ratio, not the floor. FIX: uvd.PulseClock drives the POV
   and fly timers. The first cut (a 32-slot MEAN) was REJECTED by the
   challenger on measurements — 3-8 ms vsync-hint pulses and
   busy-startup 32 ms runs inflate/deflate a mean (the ride ran
   20-45 % fast for a second, sank to 13.5 ms). Shipped: the advance
   = the MEDIAN of the last 32 raw deltas (EVEN window, the mean of
   the two middle order statistics — an odd median flips 8/24 on an
   exact hint alternation, and 32 slots keep a 12-pulse busy run a
   minority) + a bounded DRIFT term (deficit = Σraw − Σadvance bled
   at deficit/32 per pulse, capped at 12 % of the median, the
   accumulator bounded at ±0.10 s, off while seed slots remain) so
   the long-term rate is wall time; the window starts full of the
   nominal 1/60 s; a HITCH (> 2.5 × max(median, 1/60 s), or the
   historic 0.1 s cap) passes through whole and stays out of the
   window, the owed drift kept. Two closing-round corrections from
   my own -Dfvd.povprobe runs: (a) the hitch threshold is FLOORED at
   the nominal period — on a hint-heavy machine (65 of 430 pulses
   under 8 ms) the median dipped to ~12.7 ms and 2.5 × 12.7 let the
   32 ms swallowed tick itself pass WHOLE, the skip back; (b) the
   first bounds (accumulator ±0.05 s, bleed 10 %, drift zeroed per
   hitch) CLIPPED under load — 80 double-length pulses in 694 and
   two hitches left the ride 2.3 % slow over 12 s; the wider bounds
   read 0.995-0.997 (a fixed ~50 ms lag, not a rate error). Measured
   in the app, default layout, W held: RAW 18 double advances per
   6 s (one every ~0.33 s), max step 17.5 ms; FIXED 0 outliers,
   every advance 16.00-17.00 ms, max step 0.54 ms. Speed factors
   (true/design, Shift/Ctrl) multiply OUTSIDE the clock, so the
   ride's dynamics keep their instant response. RESIDUAL PLATFORM
   LIMIT, documented rather than claimed fixed: JavaFX frame
   production is not paced to the display (timer pulses + hint
   pulses into a 60 Hz desktop, no present callback), so now and
   then a rendered frame is superseded before composition and the
   screen steps two pulses' worth at once (~1-3 per second in the
   challenger's barcode runs, any clock). Deliberate limit: a machine
   dropping more than ~1 frame in 8 runs the ride slow rather than
   jumping. PulseClockGate 29 (30 after §8); the display-side count after the
   median clock was NOT re-measured (the barcode sampler lived in a
   scratch worktree) — his eye on the first hill is the acceptance.
   Tooling: -Dfvd.povprobe=<s> (+ .raw=1 for the historic clock).

8. THE REVIEW ROUND (three lenses over the finished batch — code
   paths, the D252/D253 bridge work, docs/UX; every finding fixed):
   (a) HIGH — THE PHANTOM EDIT, a consequence of §3's own rule. Since
   FOCUSED ≠ TYPING a merely-focused box is resynced to the model —
   its editor then shows the ROUNDED rendering of a value it no
   longer equals in the last bits. JavaFX's Spinner commits on focus
   loss BY ITSELF (its own focused listener calls commitValue, which
   re-parses the editor text): leaving such a box wrote 12.35 over
   12.3456 as a user edit — undo step, dirty flag, a re-solve. FIX at
   the converter, the one place every commit entry passes:
   BlankSafeConverter.fromString returns the factory's CURRENT value
   when the text equals the rendering of that value (the D247
   isUntouchedEditor rule applied where JavaFX itself commits).
   Consequence, same as D247's: typing exactly the displayed text
   into a box whose value carries more digits is a no-op.
   (b) the handle-hover readout (§5) called hitKnob on every mouse
   move with no surface guard: with a strip control's node selected
   (selIdx belongs to the STRIP lane) or over an s/c / bridge
   section, knobPos indexed the wrong lane — IndexOutOfBounds per
   move. Guarded like the cursor's own overKnob test (which had the
   same latent hole and got the same guard): node-hit first, no
   strip focus, a lane section active, selIdx inside the lane.
   hoverKnob also resets on press and when the pointer enters the
   strip.
   (c) the POV "(d = …°)" readout ignored BRIDGE spans: bridges never
   tilt (D100 — they re-solve to world endpoints), so over a
   conflicted piece's trailing bridge the readout showed a design
   angle that exists nowhere. povConflictTilt returns null there; the
   plain rates stay.
   (d) RENAME: an illegal character (* ? " < > |, a control
   character) reached Path.of and threw InvalidPathException — a
   RuntimeException, out of the button handler, nothing on the
   dialog. DesignIO.fileNameWhy (pure, LabelGate section 12) refuses
   those and the Windows device names with a sentence; the move
   catches RuntimeException too. A CASE-ONLY rename was a silent
   half-success: Files.move is a no-op when source and target are the
   same file, so the title changed and the disk did not — it goes
   through a temporary sibling name now and moves back if the second
   hop fails.
   (e) the bridge-end RING drags under "Optimize position" + Optimize
   length ran optimalBridgeLength PER DRAG EVENT, off the previous
   compile's target head — D252's and D253's bugs on the one path
   neither had covered. GraphEditor.gizmoReplace: live events hold
   the last knee and leave kneePending, a rest call captures the head
   first. The D249 settle's INNER re-knee ran per live event too (a
   LOCKSTEP target): Coaster.optimizeBridge gained a holdKnee
   overload, passed by autoReoptimizeBridges and applyBridgeGap; the
   4-arg form is the historic body. BridgeSection.copy carries
   kneePending (a snapshot taken between a live march and its settle
   kept the held knee for good). BridgeSlowProbe drag on his second
   file after the change: 23-27 ms per event, 13 ms release settle,
   settled placements old vs new 0.0.
   (f) disks under Ctrl+Shift rounded NATIVELY in the viewport, off
   the displayed grid under a cross-domain view (§6's flag): the
   strip's own snap takes a step scale now (spSnapNative's overload,
   spViewportDragTo(nx, snap, fine)) — the display grid at a tenth,
   scale 1 literally the historic method. The flag is closed.
   (g) a bridged-in piece's grayed Track Piece pitch/roll box said
   "lock it to hold a value" — this window has no padlock since §4;
   it says "…lock it in the bridge window…" like the yaw row.
   (h) DOCS: twelve CLAUDE.md passages still described the Track
   Piece padlocks, the shared bridge padlocks or the old row order;
   Help's "Strict seams" said the independent check appears only
   under a conflict (it is always there) and "Window" promised a 3D
   ring in every bridge mode (the rig builds rings in the two
   non-orientation modes); source javadocs for the removed padlock
   code, autoReoptimizeBridges ("stamps SIG_STALE" — the opposite of
   what it does) and LiveWindow.refit; gates/README counts.
   NOT CHANGED, a documented limit: PulseClock's seed phase runs the
   nominal 1/60 s for the first 32 pulses, so on a display cadence far
   from 60 Hz the first half second of a ride advances at the wrong
   rate before the median takes over (no drift correction while seeds
   remain — by design: a busy startup must not bank catch-up).
   OBSERVED, not resolved: the dev bridge probe logs four JavaFX CSS
   warnings (a ClassCastException inside CssStyleHelper, converting
   -fx-background-color for .spinner / .text-input) when the bridge
   window is re-opened on a DIFFERENT bridge — thrown inside `new
   Scene(root)` while JavaFX resets the re-parented rows' styles; no
   visible effect. A plain close → re-open reproduces it neither on
   this tree nor on 3f6c67e, so whether it predates the batch is
   unknown; `-Dfvd.bridgeprobe.csstrace=1` prints a stack per warning
   for the next look.
   Gates after the round, wiped classes: PulseClockGate 30 (the drift
   surviving a hitch is pinned now), GestureGate 115, LabelGate 111,
   OrientModeGate 51, PlacementGate 41, RunGate 66, CopyGate 73,
   FloorGate 18, StripGate 49, DetermGate197/198/199/200 6/4/10/7,
   -Dfvd.pickprobe=1 fails=0, -Dfvd.winshot renders the three windows
   in the new layout.

## D255 (v19.26) — Batch 37: the Track Piece window is the RUN's window (anchor rows, run-scoped Mirror / Switch Coaster), the graph view follows appends and keeps a drag-grown window, the init-speed real readout + the bitwise answer + the run-head refreeze bug, resolve behind a "No optimization" bridge, the floor-order sweep (glyph routing + exact ground cut), the one-motion disk drag (a JavaFX press-target fact), the depth-aware gizmo pick, window layout polish

User feedback round on batches 35-36 (eighteen items, three of them
questions). Method (the standing early-edge-case rule): eight READ-ONLY
investigators, one per item cluster, each returning sites with verbatim
anchors, the state combinations, a patch plan and gate checks — BEFORE
any code; then two implementation phases with DISJOINT FILE OWNERSHIP
(phase 1: Gestures / FloorDepth / SectionNames + Coaster, each with its
gates; phase 2: Viewport3D, GraphEditor, FvdDesigner + Dialogs — one
owner per file, every agent compiling only its own files into a scratch
class directory that shadows target/classes, so parallel work never saw
another agent's half-edited file); then a review. The working tree was
uncommitted throughout, so no worktrees (a worktree starts from HEAD).

1. THE TRACK PIECE WINDOW ("it always is referring to the very start of
   the run"). Since D247/D248 only the Anchor x/y/z row targeted the run
   head; yaw / pitch / roll, the "Edit with gizmo" checks, the ↺
   reverts, the open-time record, the mode row, Design ↔ Real and the
   readouts all resolved the ACTIVE piece — with a follower selected the
   rows showed the follower's stored angles, grayed; with a bridged-in
   piece selected they edited THAT piece (D249's "the two windows are
   one route"). The window's own gray text said it: "Anchor orientation
   is editable while a section before the first strict seam is
   selected." The user's model is the other one: the window belongs to
   everything the graph shows, and its rows are the anchor's. NOW
   FvdDesigner.placementTargetIdx / placementTargetRef is the ONE
   accessor of every row. A run head is never a follower and never
   bridged-in, so every follower / bridged-in branch in this window is
   DEAD and deleted (seamOwned, bridgeOwned, tiltWhyFor, gizmoWhy,
   BRIDGE_ALIGNS_ELSEWHERE, pieceOrientWrite's bridge route): on a head
   no row has a gray cause left; the wizard and an open convert preview
   are the only blocks, as before. The record re-captures when the
   active RUN's head changes (index + object identity), not when the
   selection moves inside the run. The orientation after a strict seam
   or a bridge is edited in that junction's own window only. Superseded:
   D247 item 5, D248's "the orientation rows keep editing the active
   piece", D249's shared Track Piece route for a bridge target, D254's
   disabled "Edit with gizmo" readback on followers / targets.
   THE OPS ("the relevant functions that apply to the entire run"):
   Copy and Delete already were run-scoped. MIRROR is now
   (Coaster.mirrorRun / mirrorRunWhy): every piece takes the per-piece
   mirror (mirrorPieceCore — mirrorPiece's body, extracted verbatim),
   and every NON-head piece also reflects its stored heading, yaw delta,
   x / z and its HELD SEAM HEADING about the vertical plane through the
   anchor's heading (sYawDeg is an absolute world heading — the old
   per-piece op negated the held bank and never touched it, which is why
   it could not mirror a run); bridges get optSig = null and noneStash =
   null (first sight = record only, the Copy rule), so the rebuild does
   not move a target at all. A single-piece run is field-for-field and
   compile-bitwise the old mirrorPiece. Measured against the geometric
   reflection: strict runs ≤ 2.1e-13 m, runs with bridges (three modes,
   Optimize length on / off) ≤ 1.3e-11 m, target moved 0.0 m by the
   rebuild; mirror twice returns within 2.3e-13 m with every lane node
   bitwise (dust, not bits, on multi-piece runs; a held yaw outside
   ±180 comes back as its wrapped twin). The risk the investigator
   named — a CREST march that is not reflection-equivariant — is gated:
   banked crests at 45 m and 58 m (past the old D156 cliff), two head
   yaws, mirrored-then-edited vs the reflection of an equally edited
   twin: ≤ 1.1e-12 m, the march spine rendering in all eight. An
   orphan-bridge tail refuses (its frozen target-side inputs would need
   reflecting). SWITCH COASTER… moves the whole block
   (Coaster.moveRunTo): links are index-relative inside the block, so
   nothing is baked or detached any more (the old op cut the run in up
   to three); a destination whose list ends in an orphaned bridge is
   grayed in the picker (it would seize the moved run as its target — a
   latent bug of the old op). NOT bitwise without qualification: lane
   sections and a bridged-in target's geometry are, but a run with a
   smoothed seam or a bridge that lands BEHIND other pieces differs by
   up to 1.24e-11 m — the smoothing window and the bridge's time
   resampling read the global clock / arc offset; moved to an empty
   coaster the same run is bitwise whole (RunGate k4 / k4b / k4c).
   REVERSE… stays a single-piece op and is GRAYED on a multi-piece
   track piece ("Reverse works on a track piece without strict seams or
   bridges — un-strict its seams and convert its bridges first."): on
   such a run the old op dropped the reversed piece out of the run and
   let the follower after it jump to the new exit. A whole-run reversal
   needs decisions the user has not made (the output structure across
   kinked seams, what a bridge becomes, a synthesized EXIT row) — asked,
   not built. Also fixed on the way: a refused Mirror pushed an undo
   step before its refusal check; Delete / Switch now gray when removing
   the block would shift a hidden wizard piece's index.

2. THE GRAPH VIEW. (a) APPEND never touched the view: in auto-fit the
   redraw re-derives the whole (longer) run — the behavior he liked —
   while a manual window simply stayed put and the new node landed
   off-screen. Now a PAN-ONLY follow (Gestures.panToInclude — width
   kept, the smallest shift that brings the node inside with an 8 %
   margin, clamped into the run's domain), computed AFTER the rebuild
   (a distance section's axis x needs the fresh spans), from
   appendSecond, from the hold-insert ONLY when the selected node was
   the lane's last node, and from the s/c element insert only at the
   END of the list. (b) THE GROWN WINDOW: every drag freezes a copy of
   the window (dragWinT0/T1) and the edge overshoot grew only that copy;
   the release's `dragWinT0 = NaN` dropped it, so the view fell back to
   the untouched manual window — the v7-era "view re-fits on release".
   Now a window that grew at a LEFT / RIGHT edge is ADOPTED at release
   (Gestures.adoptWindow: intersected with the run's domain; at least
   domain-wide = auto-fit, the D75 rule, so the release redraw's
   sanitize never moves it again). A gesture that began in AUTO-FIT
   stays auto-fit — the mode means "follows edits", and adopting would
   silently hide the ⊕ in the 8 % headroom. Right growth is capped at
   the run's domain end (growRightCapped — there is nothing to see past
   it, and a node pinned against its neighbor no longer grows the view
   forever). The value axis is untouched: the editor has no manual
   value window, it re-fits to data that includes the dragged node.
   Found on the way: a clean click on a seam's smoothing diamond
   returned without unfreezing (scroll-zoom looked dead until the next
   canvas click); and A with a STRIP graph node selected ran the
   hold-insert on the MAIN lane at the strip's index.

3. THE INITIAL DESIGN SPEED WINDOW. A grey "real 12.34" sits right of
   the spinner in a RESERVED slot (the window never resizes), built on
   the node-editor twin recipe (same style strings, a constant
   transparent border, the hover outline, no cursor change); a click is
   the SAME Runnable as "Set Design to Real". It shows once a conflict
   is seen and stays until the window is closed (a latch local to the
   open window); afterwards it is a plain label. THE PREDICATE is not
   the badge's 0.2 m/s — a value typed 0.05 off real shows no ≠v, and
   he could not snap back — and not a bit difference either (with
   friction every continuation differs from real by ~1e-6 for good, the
   button would never gray and would do nothing): "the click would
   change the design speed by more than 0.005 m/s" (half the box's
   display quantum), computed by a pure dry run
   (Coaster.realEntrySpeed) that twins the reconcile's anchor gate.
   Gray reasons, each its own sentence: the ride entry, a piece that
   starts its own run, a reversed section, nothing compiled, a stall,
   "follows the section before it, which carries the speed difference",
   an optimizing bridge before it (§4's sentence — the readout then
   shows as a plain, non-clickable label: the LATCH keys on the speed
   conflict itself, the CLICKABILITY on the button being enabled), and
   plain "No speed conflict". A click that still changes nothing (the
   knife edge of the dry run) rolls back instead of leaving an empty
   undo step, and puts the window's Cancel record back (the rollback
   fires the restore hooks, which would re-base it).
   THE BITWISE QUESTION ("the design speed is set to be the same as the
   real initial speed bitwise?") — the first answer was NOT ALWAYS:
   bitwise for followers, real-anchored continuations and everything
   with friction off; with friction a plain continuation settled on the
   previous section's DESIGN end, 2e-6 m/s off the real speed
   (11.228125804341 vs 11.228127915835), and a bridged-in first section
   2.5e-3 off after one click. He asked for the friction case to be
   made exact — §11 is that change, and the answer is now YES wherever
   the button is enabled, with two documented exceptions (a smoothed
   seam, a stalled entry). "Entry" is the SEAM sample (the previous
   span's last); a continuation's own first sample is one dt later.
   THE BUG FOUND: refreezeSpeedOnly picked its anchor by
   FLATTENED span index, so on a detached run head at piece index > 0
   it read the PREVIOUS piece's last sample — another run — and wrote
   that speed into the head (17.0 became 12.482); the badge's range
   "Set Design to Real" on a tilted detached head inherited it, and
   makeGraphsReal had the same read. A run head's real entry speed IS
   its design speed (Pass D's run entry): Coaster.hasUpstream gates the
   write now. (A ride entry whose vDesign sat in [0.1, 0.5) is no
   longer raised to 0.5 by the op.)

4. RESOLVE BEHIND A BRIDGE. The badge's two resolve ops were grayed on
   the first section of ANY bridged-in piece, whatever the bridge's
   mode (GraphEditor.leftIsBridge never read optMode). The block's own
   rationale — "it re-blends to follow any resolution" — describes the
   OPTIMIZE modes: a resolve changes the target's head dynamics, those
   are in the bridge's signature, the auto pass re-marches and re-places
   the target, and the resolution is chased (probed: the target moved
   0.2-3.5 m and the split re-opened at 0.21-0.27 m/s). Under "No
   optimization" none of that machinery runs. Probed on a hill → bridge
   → tilted three-section piece: every op (single convert, single
   refreeze, isolateRange + refreezeRange, the chained convert) leaves
   the target's ENTRY POSE BITWISE, the bridge re-lands to 1e-13 m
   through the ordinary per-compile re-capture, the second rebuild is a
   bitwise no-op, save → load → rebuild bitwise. So BOTH ops run there —
   the user named the convert; "Set Design to Real" rests on the same
   invariance (the only placement write is bakeTilt: pose kept, deltas
   zeroed) and withholding it would be a gray with no true reason.
   Nothing has to happen afterwards beyond the rebuild. With friction
   the bridge's arc changes by mm and the true seam speed by ≤ 1.3e-3
   m/s — below every threshold, the rebuild still a no-op. "Set Design
   to Real" visibly reshapes the BRIDGE (0.3 m on the fixture: the
   target's head curvature changed) — the op's documented reshape, seen
   on a bridge for the first time. The optimize modes stay blocked, with
   the user's hint: "The bridge before this section re-places this
   piece after any resolution — convert the bridge first or set it to
   "No optimization."" ("…re-places and re-aims…" under optimize
   orientation). Two older sentences advised "lock the axes" although
   both predicates are mode-only; they name the modes that un-gray now.
   No model guard ever refused a preceding bridge — the block was
   UI-only; the range dialog re-checks at Resolve (the bridge window is
   a sibling Stage).

5. THE FLOOR SWEEP ("many viewport items do not draw with respect to
   the floor correctly"). D249's see-through floor is pure scene-graph
   ORDER, and only MESHES were ground-split. The four glyph kinds — node
   markers, boundary disks, speed-control disks and the un-revealed
   reveal balls — were added WHOLE to the after-floor block ("flagged").
   Because that whole block moves BEFORE the floor while the eye is
   below ground, an underground glyph was wrong in BOTH eye states: eye
   above = drawn after the floor, untinted (pixels: marker 114,155,214
   beside washed rails 176,196,244); eye below = drawn before the floor
   and washed by the sheet (212,251,255) with nothing in between — the
   "highlighted" look. THE TRUTH TABLE (FloorDepth.beforeFloor): a
   drawable is submitted before the floor exactly when it and the eye
   sit on OPPOSITE sides of the ground. FIX: each glyph routes PER ITEM
   by its center's y (FloorDepth.itemUnder, strict < −0.07, ties and NaN
   toward above — never a two-tone sphere) into an "under" twin group
   inside the before-floor block (markerUnder / boundaryUnder /
   ctlDiskUnder inside trackUnder, trackBallUnder beside it), so the
   existing eye-crossing swap handles everything with no per-item work.
   Routing re-runs for free (every glyph set rebuilds per rebuild / sig
   change) except the balls, which re-parent where they are positioned.
   The stated reason for not routing them ("pick anchors whose groups
   are index-walked") was mostly untrue: only pickMarkerIndex walks a
   group, keyed by userData; it walks both now. SECOND FINDING: the
   per-face CENTROID split mis-routes long faces — every comb tooth
   crossing the ground was cut along its side quads' DIAGONAL (a
   two-tone tooth), and straight rails have faces up to 1.2 m, not the
   documented 0.14. FloorDepth.clipAtGround cuts straddling triangles
   EXACTLY at the plane (shared cut vertices, winding kept, area
   conserved to 1e-9, smoothing groups mapped through their source
   faces) — one rule for every mesh; a rail lying ON the ground (the
   plane stays at −0.07) is still one untouched tube. underMask /
   splitCounts stay for the gate's history but no longer describe the
   render. THIRD: the side view's 2D ground line was the LAST pane child
   and drew over the gizmo overlay; it sits under it now. Left as
   documented-accepted: the ghost track showing through nearer solid
   track (JavaFX has no depth-read-without-write) and the Top view's 2D
   grid over everything. The gizmo stays on top of all of it.

6. THE ONE-MOTION DISK DRAG. The disk gesture always was a single
   press-drag-release; it died on a JAVAFX EVENT-ROUTING FACT. The press
   on a disk of an unselected control changes the strip selection; the
   disk signature signs the selection; the disks rebuild DURING the
   MOUSE_PRESSED dispatch — removing the very MeshView JavaFX had picked
   as the press target. Scene.MouseHandler sets pdrInProgress only AFTER
   the press was delivered and re-targets a gesture on node removal only
   while it is set, so every DRAGGED and the RELEASED went to the
   detached node and never reached the pane (isolated: removed during
   PRESSED 0 / 5 drags, removed during DRAGGED or hidden 5 / 5). The
   second press found the control already selected, nothing rebuilt,
   and worked. The lost release also left the gesture state stuck: the
   mid-drag modifier guard stayed armed (Ctrl+Z / Ctrl+S dropped), the
   next plot drag was swallowed, the popup did not follow, the view
   stayed frozen. FIX, one line: world.setMouseTransparent(true) —
   every pick in the pane is screen-space, so JavaFX's node pick only
   ever chose the gesture's TARGET, and it chose a removable one; the
   SubScene (it has a fill) is the target now and is never removed.
   Making only the disks transparent fails for a control on a piece
   outside the run (its activation replaces every mesh at press). The
   same hazard sat under gizmo presses (their press deselects, which
   rebuilds markers). STANDING LESSON: nothing that a press handler can
   rebuild may be a JavaFX mouse target. Why no probe saw it: pickprobe
   fires events AT the pane, bypassing the Scene's press-drag-release
   targeting.

7. THE GIZMO PICK ("if the hitbox for the center dot is closest it gets
   selected"). D249's picker was purely 2D: nearest by pixels with the
   ball measured from its silhouette edge — a filled disc reading 0 px
   everywhere inside, against arrows and rings that are lines. Inside
   the silhouette the ball could never lose, whether the ring / arrow
   was drawn in FRONT of it or hidden behind it; that was his
   complaint. Now TWO TIERS (Gestures.pickFront): (1) among parts
   DIRECTLY under the cursor — the cursor ray pierces the drawn sphere /
   shaft / cone / tube, inflated by 1 px — the FRONT-MOST along the ray
   wins; (2) only when nothing is directly under the cursor, D249's
   nearest-by-px-within-band rule runs, verbatim. An arrow's root runs
   inside the ball and loses there; its emerged near half wins (45°
   arrow: the switch at 0.15-0.17 rig units from the center); the far
   half behind the ball loses; a ring's near arc wins, its far arc
   loses. The drawn geometry and the picked geometry read the same
   constants. Two more findings: an un-revealed track ball (world
   layer, drawn UNDER the overlay rig) stole presses from rig parts
   drawn over it — a layer rule; and an arrow aimed at the camera
   refuses its press silently, so ungrabbable arrows are outranked
   (they are picked only when nothing else is in reach, as before).
   The ball's click-to-hide target shrinks by exactly the pixels where
   something is drawn over it — the requested trade. FOUND WHILE
   PROBING, older than the batch: the arrow press test measured a 1 m
   world vector ("spans under 2 px = edge-on"), so once the camera was
   far enough that 1 m projected under 2 px — about 134 m of view
   depth at the default pane, i.e. the STARTUP framing — every arrow
   swallowed its press silently. The test is scale-free now
   (Viewport3D.arrowDraggable: the arrow's own projected length
   against the length it would show lying in the view plane, under 6 %
   = aimed at the viewer), the drag law keeps the press-time image of
   1 m, and a refused press says "That arrow points at the camera —
   orbit a little to drag it." 

8. SMALL ITEMS. POV BAR: over a speed conflict the speed reads
   "12.345 m/s (d = 12.000)" — the section under the POV wears ≠v, or
   design and true differ right there by more than the badge's 0.2 m/s
   (a tilted piece's interior split carries no ≠v); never over a
   bridge; the slot template carries the wide form, so nothing shifts
   (the bar measures 1261 px of a ≤ 1400 px window). HANDLE READOUT:
   "L=0.33 [ease]  sl 1.23 g/s". WIZARD: confirming a floating piece or
   a new coaster leaves the piece's entry gizmo revealed (Copy's
   handoff). WINDOWS: the yaw | pitch-roll separator ends at the right
   edge of the ↺ (LiveWindow.rowSeparator — bound to the ↺'s layout
   position in its row, so it follows each window's columns) and is
   lighter (222,225,230 vs 200,204,212); the Track Piece pitch / roll
   spinners are 82 px like yaw's so the three ↺ line up; no line above
   the ops buttons, the same 23 px gap (an invisible separator keeps it
   identical at every render scale); Reverse… / Mirror / Copy on one
   line, Switch Coaster… and Delete… on the bottom line, left, beside
   Confirm and Cancel (LiveWindow.footSpring). THE CLIPPED RESOLVE
   BUTTONS ("Orientati…") were NOT reproduced — three windows, three
   bridge modes, revealed late or present at open, 14 render scales, 240
   random steps: never clipped; the bridge window is 442 px wide against
   a 293 px row, so "too narrow" is not it. What is true: the buttons
   were laid out at EXACTLY their snapped pref width with 0.01-0.65 px
   to spare and could shrink — losing exactly "on" is a sub-pixel
   shortage. HARDENING, not a root-cause repair: fixed measured widths
   with 4 px slack that cannot shrink, and the window reserves the
   row's width while it is hidden. If it clips again the cause is
   outside button sizing — a screenshot with the display setup settles
   it. THE STARTUP WARNINGS ("A restricted method in java.lang.System
   has been called … System::load … NativeLibLoader in module
   javafx.graphics") are JDK 24+'s native-access notice (JEP 472):
   JavaFX loads its rendering DLLs, which is what it must do; harmless
   today, but a future JDK will block the load unless access is
   granted. pom.xml passes --enable-native-access=javafx.graphics
   (verified: four WARNING lines without it, none with it; "Apply and
   Restart" re-launches with the same JVM arguments).

9. AN INCIDENT, AND THE RULE. One investigator drove the bridge window
   with javafx Robot clicks while the app was not in the foreground;
   six left clicks landed in the user's mail client (no keyboard input;
   nothing visibly deleted or sent). Reported to the user at once; he
   allows OS-level input for probes. RULE for probes from here on:
   prefer in-JVM event injection; if Robot is used, the Stage is
   always-on-top and showing, every click point comes from a node's
   own screen bounds and is re-checked right before the click, mouse
   only. A SECOND ENVIRONMENT FACT the parallel agents ran into:
   target/classes is rewritten within ~2 s of every source SAVE by the
   IDE's Java build (VS Code), with "Unresolved compilation problems"
   stubs while a file is mid-edit — so "the current build" moved under
   every agent that ran a hook against it, and two of them froze
   private snapshots. It is why the standing rule "wipe
   target/classes/uvd, then mvn compile, then run" exists; nothing may
   be trusted that ran between a save and that rebuild.

10. ASKED, NOT CHANGED: "Typing exactly the number a box already
   displays does nothing." The chain: his stale-box reports (D252,
   D254 §3) → a merely-focused box is refreshed from the model → its
   text is then the ROUNDED rendering of a value with more digits →
   JavaFX's Spinner commits on focus loss BY ITSELF and re-parses that
   text → leaving the box would write 12.35 over 12.3456 as an edit →
   the guard (D254 §8): text equal to the rendering of the current
   value commits as a no-op. The guard cannot tell "retyped the same
   digits" from "never touched". His wish — typing the shown number
   makes the value EXACTLY that — is right and reachable: mark a box
   as typed-in on real input (a key, a paste, a cut) and clear the mark
   on every programmatic write; commit parses whenever the mark is set.
   Proposed for the next batch; nothing reverted meanwhile.

11. THE REAL SPEED IS THE DESIGN SPEED, BIT FOR BIT, ON UNCONFLICTED
   TRACK (his follow-up: "please make the friction case bit-exact").
   WHY IT WAS NOT: the same physics was integrated twice by different
   rules — the design solves carry the friction loss in their RK4
   state, Pass D sums it by Heun over the sample arcs — so an
   unconflicted chain's real speed sat ~1e-6 m/s beside its design
   speed for good, and the reconcile chains sections on the DESIGN end
   speed: "Set Design to Real" could not land on the real speed's bits.
   THE RULE (TrackCompiler.passD, the clean variant — three builds to
   get there, see below): a stretch whose samples are the design
   solve's own geometry (not a bridge, no rigid conflict tilt, not a
   legacy reversed section, outside a smoothing blend) and which the
   train ENTERS at exactly the section's design speed records the
   design speed as its true speed — one initial-value problem on one
   track has one answer, and the design solve already computed it.
   ONE FLOW: the march is RE-SEEDED on the last sample of every
   recorded stretch, so the real flow continues FROM the design speed
   there and the record never steps where a clean stretch hands over
   to one that is not. The re-seed is CAUSAL and per run: only where
   the two can have drifted — friction is on, or a control governed a
   step of THIS run upstream; elsewhere they agree to rounding and the
   march keeps every historic bit (a coaster-wide switch, the design
   critic showed, let the first control on a DETACHED piece re-round
   the other runs). And never from a STALLED design sample: the design
   record is clamped at 0 while the design creeps above its energy
   height, and seeding from it handed the descent 6 m/s more than the
   energy allows.
   SMOOTHED SEAMS: a window too short to blend rewrites nothing and
   both neighbors keep copying; a blend leaves the follower PIECE from
   the window on to the march — the blended first span and, riding
   its closure residual (postShift), every later section of the
   piece, then each downstream piece until an exact entry: the march
   is the honest speed there, dust-level and continuous (3e-9 m/s
   friction-free, 1e-5 with friction on the gate fixture; review
   round 4 wording — the first write said "the follower's first
   span") — and the upstream span copying UP TO its window (the first
   sample object the blend replaced).
   BEHIND A BRIDGE the entry test is a tolerance, 1e-11 m/s
   (TrackCompiler.BRIDGE_ENTRY_TOL — 1e-9 until review round 4), and
   the seam then records the design speed: the bridge's own time
   sampling reacts to the last bits of the target's design speed, so
   "design speed → real speed at the bridge's end" has NO bit-level
   fixed point on some bridges.
   THE BUILDS. (1) A draft that skipped the march across clean
   spans and re-based it moved a real-anchored section's speed by an
   ulp in a friction-free fixture: SeamShapeGate 7a (the pinned
   pre-D247 checksum) failed — geometry had changed where nothing
   should. (2) The first merged build recorded the design speed but
   let the march run on beside it, untouched. The adversarial
   verification took it apart: the record STEPPED by the march/design
   drift wherever a clean span handed over to a non-clean one; the
   badge reads the first own sample and the resolve op the seam
   sample — opposite sides of the step; behind a held speed control
   the step was 0.2-0.9 m/s, a speed conflict "Set Design to Real"
   could not close (it read "no conflict" and wrote nothing); both
   neighbors of a smoothed seam lost the copy for their WHOLE span,
   even under a window too short to blend; and the op's fixed-point
   loop behind a bridge ran all twelve passes on two of the user's own
   files (2- and 3-cycles of adjacent doubles) and still ended off by
   ulps. (3) The rework, written by the main session while a DESIGN
   CRITIC built its own overlay of the same design and attacked it:
   the stall guard, the causal switch, the op's tolerance and the
   stalled-bridge refusal are its corrections. (4) The build described
   above, verified once more as merged.
   WHAT IT COSTS, measured old build vs new on 52 documents, 33 of
   them his own files (gates/probes/cleanspan-2026-09-30, read-only):
   ONE of his files re-settles on first open — "9.24.26 test.fvd", one
   design speed by 1.4e-6 m/s, the track by 4.6 micrometres — and
   never again (a second rebuild and save → load → rebuild are bitwise
   on all of them). Every other file of his keeps every design speed
   and every sample bit. The general statement is weaker and must be
   said: a document with friction or speed controls in which a section
   had been ANCHORED on the old march's seam speed re-settles once,
   by the old march/design drift — friction dust in ordinary chains,
   more behind held speed controls (9 of 66 adversarial fixtures
   settled by the old build moved, the largest by 7.4 m). It is the
   pre-v18.25 / pre-v18.27 precedent: a one-time arithmetic
   re-baseline.
   WHAT THE COMPARISON UNCOVERED. On his main test coaster the old
   real speed sat up to 0.15 m/s beside the design speed in sections
   that wore no conflict. Two causes, both in how speed controls are
   stepped: (a) THE SEAM STEP — Pass D's control rows are per sample,
   rows[i] governs the step i → i+1, and a continuation's first own
   sample sits one step inside its section; the step that starts ON
   the seam was resolved against the PREVIOUS section at its end
   coordinate, where no control reaches (the interval is half-open).
   A control starting exactly at a section start — every promoted
   x = 0 diamond — lost its first step in the real march: on his 62°
   lift the real speed fell 0.09 m/s behind in that one step. FIXED
   (ctlSampleRows' 4-arg form: the seam sample takes the NEW section's
   row at x = 0, or none). (b) THE HOLD KNIFE EDGE — NOT CHANGED, his
   call: SpeedControl.snapCore decides "crossed the target" by the
   sign of (vB − vt)(vA − vt); after a snap vB is the target ± one
   rounding, so whether the next step holds is a coin flip on the
   rounding direction. With a control as strong as his (0.76-0.9 g)
   the hold leaks one- to three-sample dips of 0.03 m/s, in the design
   solve AND in the real march, at different samples. With a control
   WEAKER than the slope it holds against (a 0.3 g trim brake on a
   steep drop, a complex graph that coasts) a lost hold is never
   regained: design and march part by up to 1 m/s for the rest of the
   ride. The fix is one comparison (treat |vB − vt| ≤ EPS as at the
   target) — but it changes the DESIGN solve, and his main test
   coaster passes a slow crest that amplifies everything upstream:
   with the fix its far end moves 1.4 KILOMETRES (design speeds by up
   to 4 m/s, 25 samples fewer); "B Shortcut not working" moves 1.5 mm.
   Not a change to make without him. On unconflicted track the clean
   rule shows the design's side of the coin as the real speed; inside
   conflicted, tilted or blended stretches the two still flip their
   own coins.
   THE OP'S OWN PART: a bridged-in piece's first section is never
   reconciled, so refreezeSpeedOnly owns its bits — and the seam's real
   speed moves with the speed written (the bridge re-blends onto it):
   the op re-reads until the write reproduces itself. The loop reads
   the RECORDED seam speed, so it ends the moment the raw march is
   inside the seam's band (BRIDGE_ENTRY_TOL — the record then IS the
   written bits; review round 4 found the first write's "rests two
   orders inside the band" claim false: the resting gap sat anywhere
   inside the then-1e-9 band, path-dependent — so the band itself is
   1e-11 now, two orders above the widest cycle found, 6.75e-14, and
   above the 5e-14 a rigid move of a whole document changes the gap
   by; Coaster.REFREEZE_TOL, the same size, bounds only a TILTED
   target's loop, whose seam never snaps), two to four compiles; a
   read within the band of the CURRENT design speed writes nothing (a
   second press changes no bit); a value seen before ends the loop. A
   bridge the train CANNOT CROSS refuses outright, in the model and as
   a gray sentence in the window ("The train stalls on the bridge
   before this track piece — there is no real entry speed to take."):
   writing the 0.5 floor re-blended the bridge onto it — half a
   million samples, a runaway 76 m/s end speed — and every later press
   flipped between the two. A bridge the train CRAWLS OFF refuses too
   since review round 4 (§14). On his two cycling files: one click,
   21 / 58 ms, the real seam speed == the design speed bitwise, the
   piece copies, a second click writes nothing.
   STILL NOT BITWISE, documented: a seam speed that SMOOTHING changes
   (the op reads with smoothing suspended, D141: that difference is the
   ◇ window's to close — the window grays with that sentence, §12 E1);
   a stalled entry (real 0.0, design lands on the 0.1 / 0.5 floors); a
   continuation whose upstream is itself conflicted by less than the
   reconcile's gate (it chains on the design end — the button is
   grayed there, "resolve it there").
   CONSEQUENCES ELSEWHERE, all display-side: on unconflicted track the
   true felt G, the rail gradient, the combs and the POV advance read
   the design values exactly (ratio 1), and the thin true strokes his
   main coaster drew at the 1.5 px threshold are gone. Stall ranges,
   the POV stall wrap and the ghost track are unchanged on 52
   documents (15 of them stall).
   Gates: uvd.ConflictGate blocks i (i1-i7) and j (j1-j6) + h2c
   bitwise, 49 checks; the whole battery green; DetermGate197/198/199/
   200 and SeamShapeGate's pinned checksums untouched.

12. THE REVIEW ROUND (three lenses, every finding adversarially
   verified; 16 findings, 1 refuted). FIXED: SH-1 (HIGH) the POV bar
   OVERFLOWED the default window on his display — the speed slot's
   permanent "(d = …)" reserve took the row to 1260 px in a 1216 px
   scene, and since value slots never shrink the CAPTIONS were
   ellipsized ("Spe…", "Vertica…") from the first POV update on:
   templates for what a ride shows (speeds under 100 m/s, elevation
   within ±90°; the ratchet covers the rest) and POV_GAP 7 (was 10)
   bring it to 1194 px, and -Dfvd.winshot prints "WINSHOT povrow
   pref=… width=… (scene …, gap …) fits" — against the row's
   LAID-OUT width, 2.7 px under the scene's (§13) — so a template
   change cannot overflow silently again. E1 the init-speed window behind a SMOOTHED seam: its dry run
   read the displayed compile, the op the smoothing-suspended one —
   the button never grayed, the first click pushed an empty undo step,
   every later one rolled the document back with a false sentence;
   the dry run takes Coaster.firstSectionSettle (the op's own read,
   cached per displayed compile) for piece-first sections while any
   seam smooths, a difference the smoothing alone opens grays with
   "Seam smoothing opens this speed difference — shrink the ◇ window
   instead.", and the knife-edge sentence is keyed on k. E2 the
   optimizing-bridge block is tested AFTER the conflict itself (the
   hover never recommends a mode change that leaves the button gray);
   the block STAYS — the verifier measured 3-5 clicks and up to 19 m
   of target travel under both optimize modes. E3 the range resolve's
   Resolve-time re-check covers the optimize-orientation block for any
   k. E5 mirrorRun REFLECTS the manual-mode stash (nulling it forced
   Optimize length on at the round trip back). V1 the arrow press with
   the eye within a metre of the junction: the 1 m point projected
   behind the eye and the press returned without a word; a step the
   drawn arrow covers is measured instead and scaled back to px per
   metre, and no projection at all speaks the refusal. V2 stale
   comments. SH-3 / SH-5 / SH-4 Help and the anchor tooltips say what
   happens (the anchor moves the track up to the next bridge; Cancel's
   record re-bases on a track-piece switch; "(d = …)" shows wherever
   the speeds differ by more than 0.2 m/s). SH-6 holding A from the
   last node walked the end off screen (the selection stays on the
   original node, so every press after the first is an interior
   insert) — first answered with "pan whenever the ride end was
   visible", which §13 replaces. SH-7 "track piece" in Copy / Delete's
   sentences. SH-8 (from batch 36) the first Track Piece window of a
   session opened 55 px lower than later ones: the revealed rows take
   their shown state BEFORE the window is measured (tpOpening).
   KEPT AS DECISIONS FOR HIS EYE: SH-2 Reverse… is grayed on a track
   piece with strict seams or bridges (a whole-run reversal is not
   built); V3 an un-revealed white junction ball below ground shows
   behind the floor, its revealed gizmo on top; E4 (refuted) a pinned
   section whose value equals the real speed cannot be un-pinned from
   the init-speed window.

13. ROUNDS 2 AND 3 — THE FIXES VERIFIED, AND FIXED AGAIN. The §12
   fixes and the first bit-exact build went through a second
   adversarial pass (three lenses + skeptics, 15 findings, all
   confirmed or plausible). The compile lens is §11's story. The rest:
   THE APPEND PAN (R2-ES-3): §12's "pan whenever the ride end was
   visible" also panned on INTERIOR inserts — the selected node
   scrolled out of view — against his rule ("only … appending from the
   last node"). Now an APPEND CHAIN: a press with the lane's last node
   selected starts it; a following press pans only with the same lane
   object, the same selection, and every node right of the selection
   being a hold this chain created (by identity); anything else ends
   the chain. Holding A keeps the end in sight, an interior insert
   never moves the view.
   THE POV BAR (R2-ES-4): templates leave 19 px of headroom, and
   ratchets add up — two rare out-of-template values in one session
   would clip captions for the rest of it. povFit: once a slot has
   ratcheted and the row no longer fits its laid-out width, the gaps
   tighten a pixel at a time, 7 → 2, and are never restored. None of
   his 33 files holds a value that leaves its template.
   THE INIT-SPEED WINDOW: the grey readout latches on the DISPLAYED
   real speed too, so a gray sentence that speaks of a difference
   (smoothing, the section before, a stall) has its number on screen
   (R2-ES-5); the smoothing-suspended settle is not recomputed per
   mouse event of a live gesture — the release's rebuild does it
   (R2-ES-6: +19 ms per event on his main coaster), at the price of a
   gray sentence that can lag until the release; a bridge the train
   cannot cross grays the button (§11).
   THE ANCHOR TOOLTIPS AND HELP (R2-ES-2): "what lies after a bridge
   stays where it is" was true only under "No optimization" — an
   optimizing bridge re-places its target with the anchor (10.0000 m
   for a 10 m move, in every mode of the head). Three short lines now;
   11 of the 14 bridges in his files optimize.
   THE GIZMO ARROWS WITH THE EYE AT THE JUNCTION (RV2-1, RV2-2): ONE
   function, Viewport3D.arrowAxis, is the press test, the pick's
   grabbable test and the source of the drag law's screen axis. The
   measuring step is chosen by VIEW DEPTH — the historic 1 m step,
   bitwise, wherever that point sits at least half as deep as the
   anchor; a step the drawn arrow covers otherwise (the 1 m point
   3 mm in front of the eye plane projected to x = 49,019 px and 40 px
   of mouse moved the piece 0.8 mm) — and a TWO-SIDED arrow whose +
   half cannot steer is measured on its mirrored half. 30,653 presses
   over 28 camera poses: none silent, none where the lit part was not
   the picked one, none dragging the wrong way; 224 historic rows
   byte-identical. ACCEPTED: the gain steps by 1.67× at the step
   switch and 3× at the hand-over to the mirrored half — both only
   with the eye under 2 m from the junction, where it used to fall to
   0.004 of the mouse. A two-sided arrow pointing 3-4° AWAY from the
   camera is draggable now at any distance, like its mirror image
   always was. The refusal sentences: "points at the camera" for a
   foreshortened arrow, "runs behind the camera — back away a little"
   when the anchor or a one-sided tip has no screen image.
   HELP (R2-ES-7, inherited): "hover the badge menu's title row for
   the numbers" named a tooltip D249 removed.
   METHOD, worth keeping: the rework was written by the main session
   in a scratch copy WHILE a design critic built its own overlay of
   the same written design and attacked it, and two implementers fixed
   the UI findings on scratch copies of files they alone owned; the
   tree was written once, at the merge, with nobody probing it.

14. ROUND 4 — THE MERGED TREE VERIFIED ONCE MORE (two lenses +
   skeptics on a snapshot of the final build; seven findings, all
   confirmed, all fixed; the compile lens found only wording).
   THE CRAWLING BRIDGE (R4-UI-1, HIGH — the round-3 stall guard
   stopped exactly at 0.5 m/s): a "No optimization" bridge whose
   target sits so high that the train crests it at 0.55 m/s. The
   window said "real 0.55", clickable, the button enabled; the click
   wrote 0.5499999 and the compile re-captured the target's head at
   that design speed — a 3.2 g hill at 0.55 m/s is κ ≈ 71 rad/m —
   which the septic absorbs as accB·lb² and j·lb³ over its 32 m
   tangents: 940 KILOMETRES of bridge, 134,000 samples, 2.4 GB of
   heap, QuantumRenderer out of memory, the application dead. At
   0.8 m/s 120 km, at 2 m/s still 2 km from 38 m. Two guards now,
   both in the MODEL: (a) SectionNames.bridgeCrawlWhy — a bridged-in
   first section refuses BY THE REAL SPEED at the bridge's end,
   stalled at or under Pass D's 0.1 floor ("The train stalls on the
   bridge before this track piece — there is no real entry speed to
   take."; R4-UI-3: the old sentence said "stalls" for 0.1 < real ≤
   0.5 while the readout beside it read "real 0.45" — the train does
   cross, the POV rides it, the ghost starts at the target) or
   CRAWLING under BRIDGE_CRAWL_SPEED 1 m/s ("The train crawls off the
   bridge at 0.55 m/s — re-solving this track piece at that speed
   would blow the bridge up; raise the bridge or the entry speed
   first."); Coaster.bridgeCrawlWhy is the dry run off any arrays,
   refreezeSpeedOnly and makeGraphsReal refuse with it before any
   bake, and (b) the BLOW-UP BELT inside refreezeSpeedOnly's re-read
   loop: a compile whose bridge arc exceeds 3× the arc it had + 30 m
   (BRIDGE_BLOWUP_MUL/ADD — a slower crossing keeps a bridge's arc, a
   head it cannot absorb multiplies it: 53× at 2 m/s, 30,000× at
   0.55; a usable entry never trips it, ConflictGate j3's 24 bridges
   unchanged) restores the design speed and the pin, re-captures the
   bridge's stored solve inputs at the restored state (the blown-up
   compile had written them — orphan-only data, but a save right
   after would have carried it) and refuses with the numbers ("at
   2.00 m/s the bridge re-solves to 2028 m of track (it was 38 m) —
   raise the bridge or the entry speed first."), before anything
   renders it. The REASON travels with the model
   (R4-UI-2: window and op read DIFFERENT compiles — the displayed
   one and the smoothing-suspended one — and behind a smoothed seam
   with friction they straddled the 0.5 floor by 1e-3 m/s: the window
   enabled the button, the op refused, and the stats bar said
   "Nothing compiled for this section yet."): Coaster.resolveRefusedWhy
   is the last refusal's sentence, nulled at every op's start and by
   the UI before each call, read BEFORE any rollback; every refusal
   site — the badge ops, the range op, the init-speed window, the
   density preview's first run — posts it, and the window re-syncs
   its gray after a refused click. The badge's Resolve-conflict items
   are PRE-GRAYED with the sentence (R4-UI-4 — the D235 rule where a
   dry run exists; the refusal's rollback replaced every piece object
   and re-based the open windows' Cancel records for a click that
   changed nothing), the action-time belts test the crawl before the
   snapshot, and rangeResolveWhy carries it for the range flow. Help
   'Resolving' names the case. ConflictGate j7 (0.55 m/s: both ops
   refuse with the sentence, the dry run agrees, nothing written) and
   j8 (2 m/s: the belt takes the write back, arc 38.4 → 38.4, sample
   count unchanged), LabelGate 14 pins the sentences; the fixtures are
   the reviewer's recipe, the target height bisected for the wanted
   crossing speed.
   THE INIT-SPEED WINDOW'S SECTION (R4-UI-5): index-resolved like the
   node editor, it silently re-targeted itself to the NEXT section
   when its own was deleted (or a Connect / Insert / split put
   another object at its index) — the box then showed and WROTE that
   section, and the window carries no section name to tell. It now
   remembers the section OBJECT: a restore or an unseen document
   generation re-bases it, the same generation with another object at
   k tears the window down ("The initial design speed window closed —
   its section was replaced.").
   THE TOLERANCE STORY (R4C-1, wording + one constant): the loop
   never converged a copying target to REFREEZE_TOL — it reads the
   snapped record, so it stops as soon as the raw march is inside
   BRIDGE_ENTRY_TOL, wherever inside (fuzz 60: median 4e-12, max
   9.5e-10 under the 1e-9 band, the written bits depending on the
   starting value). The band is 1e-11 now (two orders above the widest
   cycle, 6.75e-14; a rigid move of a whole document changes the gap
   by ≤ 5e-14 — 0 flips in 120 documents × 4 moves at the old band,
   the margin holds at the new one by 200×), REFREEZE_TOL keeps its
   value as the TILTED target's stop, and the comment, the javadoc,
   §11 and CLAUDE.md say what happens. ConflictGate j3's 24 bridges
   still land bitwise and copy, i5 unchanged. THE SMOOTHING WORDING
   (R4C-2): after a blend the whole follower piece and every
   downstream piece run the march, not "the follower's first span" —
   §11 and the compile comment say so now.
   ACCEPTED, documented: opening the init-speed window on a smoothed
   document re-captures the bridge's stored bpose off the unsmoothed
   compile (compileForFit's capture writes the bridge's solve fields
   — 1e-7 m of dust in the saved text, no shape change; the op's own
   compile does the same). The junction windows still close before a
   range op runs (hideJunctionWindows precedes the model call — the
   pre-gray keeps the refused click from reaching it). VERIFIED on
   the final build with the reviewers' own in-JVM probes: the 0.55
   and 0.45 m/s fixtures open the window grayed with the crawl
   sentence (readout not clickable), the badge's two items pre-grayed
   with it, the button's action invoked directly refuses with the
   same sentence, document unchanged through undo and Cancel; the
   2 m/s fixture enables the button, the click posts the belt's
   sentence and the document is unchanged (before the re-capture it
   differed by the blown-up bpose until the next rebuild); the
   three-section window probe: deleting the window's section closes
   it with "its section was replaced", deleting the last one with
   "no longer exists". Gates 57 green, the 52-document dump
   byte-identical to round 3's, winshot / pickprobe unchanged, the
   two cycling user files still one press (16 / 56 ms).

## D256 (v19.27) — Batch 38: the "~v" slight speed-conflict glyph, the EXPLICIT real anchor (the unresolvable 0.078 m/s file) + "Set Design to Real" always enabled, ride keys live from every tool window (the pv-keys root cause) + save on release, the TYPED MARK (typing the number a box shows sets exactly it), the "Smoothing options…" window + TRANSLATIONAL seam smoothing, Reverse's gray sentences, the handle readout without "sl" — and his answers to the batch-37 open list

User feedback round on batch 37 (2026-10-12): five items from his list,
the nine open decisions of D255 answered, and two explanations he asked
for. Method (the standing early-edge-case rule): five READ-ONLY
investigation reports first (small-items, the conflict bug, pv-keys,
typed-mark, smooth-options — sites with verbatim anchors, state
combinations, patch plans, gate checks), then one implementer per
item, each enumerating its state combinations before coding (the
-enumeration notes; 41 rows for the smoothing window) and leaving a
docs note, one lean reviewer per item (the review findings are named
where they changed the design), and the docs integrated last from the
notes. Two pure classes are new (uvd.KeyRoute, uvd.TypedMark), two
gates (uvd.KeyRouteGate, TypedMarkGate) and four in-JVM dev hooks
(-Dfvd.keyprobe, -Dfvd.typedprobe, -Dfvd.initspeedprobe,
-Dfvd.smoothprobe); the battery is §11.

1. THE "~v" SLIGHT SPEED-CONFLICT GLYPH (user spec, verbatim: "When the
   design and real speed are just barely not the same up to two decimal
   places [what is displayed in initial design speed], add a '~v' to
   the section settings button to show that there is a slight speed
   conflict [if speed conflict is below two decimal places, show
   nothing. If the difference is above two decimal places but below
   the current speed conflict threshold, show '~v', and if it is above
   the current speed conflict threshold display the not equal sign + v
   symbol as it does currently]"). THE RULE is pure (SectionNames,
   LabelGate 15): speedLevel(gap) of the section's ENTRY gap (D247's
   entry-only measure — |max(0.1, trueV[i0]) − design v[i0]|) = 0 for
   NaN or gap ≤ Gestures.INIT_SPEED_SHOW_EPS 0.005 (half the initial
   design speed box's %.2f quantum — the box's own "two decimals"), 1
   for gap ≤ Gestures.DRIFT_EPS 0.2 (the historic badge threshold,
   MOVED HOME from GraphEditor to Gestures; GraphEditor.DRIFT_EPS
   stays the package alias so nothing else moved), else 2;
   speedGlyph(gap) = "" / SLIGHT_SPEED_GLYPH " ~v" / SPEED_GLYPH " ≠v".
   The exact 0.005 and 0.2 are the LOWER levels (≤), matching
   Gestures.speedDiffers's strict > and the historic gap > DRIFT_EPS
   bitwise. ORIENT_GLYPH " ≠o" joined the constants (GraphEditor's two
   literals are gone). THE EDITOR: GraphEditor.driftLevel / driftGlyph
   / driftGap(p, k) — a bridge hands the helper NaN (never a speed
   glyph, as sectionDrifted ruled), no compile → NaN;
   sectionDrifted(p, k) is driftLevel == 2 now, so speedConflictAt
   (the POV bar's "(d = …)" design-speed suffix — DECIDED to stay at
   0.2), seamDrifted / pieceEndDrifted (the '+' fork and the Insert
   dialog's fork row), sectionDriftedSpan and the B-match belts keep
   the 0.2 threshold untouched. THREE sites moved to the three-state:
   (1) the badge (driftGlyph + ORIENT_GLYPH; badgeWidth measures
   "Force ~v" — a strip too narrow shows no badge, the historic rule);
   (2) the badge menu's speedC = driftLevel ≥ 1 — DECIDED: a "~v" badge
   DOES open "Resolve conflict: speed" (title unchanged) with exactly
   the grays ≠v gets (bridgeResolveWhyAt by the bridge's mode,
   bridgeCrawlWhyAt, MARCH_OWNS_RESOLVE, rangeResolveWhy, the reversed
   gate): the two ops have no threshold of their own — makeGraphsReal /
   refreezeSpeed write the exact seam speed, so a slight conflict is as
   resolvable as a full one; (3) smoothingCausedDrift keys on the
   three-state (pre-check driftLevel(p, 0) == 0 → false; the
   zero-window test smcVal = speedLevel(split) == 0) so the D141
   "shrink the ◇ window" hint and the red band keep agreeing with the
   drawn glyph (the D247 invariant), now for a smoothing-caused sub-0.2
   gap too. WHAT HE SEES: on his fixture (conflict.uvd — piece 0 three
   force sections, piece 1 an all-locked strict follower entering 0.045
   / 0.077 m/s off its design speeds) piece 1's two badges wear "~v"
   where they wore nothing (ConflictGate k0-k6 pins the levels after
   the app's settle recipe: the run head + piece 0's continuations 0,
   the follower's sections 1, never 2). The init-speed window already
   lived at 0.005; its predicate measures a DIFFERENT sample than the
   badge (the settle value vs vDesign, against the span's first
   in-span sample vs true), so a "~v" section can still hover a
   no-conflict sentence — the same window-vs-badge disagreement that
   exists at 0.2 today, documented, seen more often at the fine level;
   not changed. A VISIBLE CHANGE on existing documents (stated on
   purpose): a smoothed strict follower already wearing ≠v whose
   smoothing-suspended residual lies in (0.005, 0.2] used to be classed
   "caused by smoothing" (the D141 hint + the red band — smcVal = split
   ≤ 0.2); it is NOT any more (level 1 ≠ 0): the resolve ops are
   offered and the band stays plain, and only a residual at or under
   0.005 takes the D141 hint — consistent with the three-state (the
   window-at-zero would wear "~v") and the badge/hint agreement
   invariant; after a resolve the leftover smoothing-only gap
   re-classes as caused, so the ops converge. DEVIATIONS from the
   investigator's proposal: none — the POV suffix at 0.2 and the
   submenu opening on "~v" were its two open questions, decided as
   above. LabelGate 15, ConflictGate k.

2. THE EXPLICIT REAL ANCHOR — Section.vRealAnchored (his "last force
   section can't resolve its speed conflict" file). THE BUG: the file's
   piece 1 is a strict follower whose first section enters 0.045 m/s
   off its design speed — under the badge's 0.2 (no ≠v; §1's "~v" now
   shows it), so he never resolved it there. Energy conservation
   carries that offset to the section's end, where it reads 0.078 m/s
   (13.991 design end vs 13.913 real). "Set Design to Real" on the
   last section wrote 13.913 — and the very next rebuild wrote 13.991
   back: Coaster.reconcileChainedSpeeds re-chains every continuation
   onto the previous section's DESIGN end unless its D237 amplified
   anchor-recognition gate fires, and that gate needs |vTrue − vIn| ·
   (vTrue/vMin) > 0.2. A 0.078 m/s resolve could never be recognized,
   the window saw the bits unchanged, rolled the document back and
   said "changed nothing — this section follows the section before
   it". Batch 37 had pinned exactly that as the design answer
   ("resolve it on the section before", ConflictGate h5) — he did not
   find the section before, which wears no ≠v. The smoothing was not
   the cause (the op's suspended read differs from the displayed seam
   speed by 2.6e-7 m/s; with smoothing 0 every number repeats); the
   piece's seam tilt is a pure HEADING re-aim (x ≈ 1e-16, z = 0 — NOT
   an orientation conflict, contrary to the investigation's "≠o"
   reading; it only means the follower's interior can never be a
   clean span). THE FIX — D101's anchor made EXPLICIT. Which of design
   / true a resolved section chases was READ off proximity (the one
   "deliberate piece of history" the D200 exact reconcile kept). It is
   a persisted per-section flag now: Section.vRealAnchored() /
   vRealAnchored(boolean), default false; LaneSection +
   StraightCurveSection store it beside vPinned, bridges never (pure
   geometry in Pass D). Coaster.reconcileChainedSpeeds (both passes):
   a flagged continuation takes vIn := max(0.1, trueV[seam])
   UNCONDITIONALLY — the D237 proximity gate stays as the FALLBACK for
   sections nobody resolved, so every existing document settles
   bitwise as before (ConflictGate h5b pins the unpressed sub-0.2
   picture). GraphEditor.refreezeSeamSpeed (B / Shift+B): a flagged
   downstream takes v = vTrue before the gate — B never snaps a
   resolved section back. SET by Coaster.refreezeSpeedOnly (after its
   vDesign / vPinned write; the bridged "already there" branch too;
   the blow-up refusal restores the flag with vWas / pinWas; a RUN
   HEAD only un-pins and sets nothing — it has no seam), by
   Coaster.makeGraphsRealBody (the s/c path and the fitted LaneSection
   it installs — RangeResolveGate's 115 bounds did not move, so the
   flag ships there too) and by the '+' / Insert "Transition to real"
   fork (GraphEditor.buildLaneHold + buildScStraight + the addSection
   chain, keyed on the TRUE arrays being used; a custom speed that
   PINS the section clears it). CLEARED by a typed design speed: the
   init-speed window's live write and the node-0 dialog's initial
   design speed (both pin; a typed speed supersedes the anchor; the
   pin always wins in the reconcile — its continue precedes the flag).
   CARRIES by the ENTRY-SEAM RULE — the object that keeps the original
   entry seam keeps the flag: copySection (Copy run), convertDomain,
   bakeAxes, convertWholeSection copy it; splitSectionAt → the LEFT
   half keeps, the right half (its vDesign is the design profile at
   the cut, the D130 bitwise split) does not; partialConvertSection →
   the half owning the original entry keeps; mergeSections takes A's;
   makeSmoothingReal's prevLeft copies, the window section and
   nextRight stay false; continuation() / reversePiece's fits are
   fresh (false); the three PIN sites (unstrict, partial bridge
   convert, whole bridge convert) are untouched. UNDO:
   FvdDesigner.SecState.realAnchored (last component) — snapshotPieces
   + applyDocState; redo stays bitwise (without it a redone resolve
   would snap back); PlacementSnapshot untouched (placement fields
   only). FILE: the section line's pinned slot (field 6, every kind)
   is a TRI-STATE — writer pinned ? 1 : anchored ? 2 : 0 on force /
   geo and sc lines (the bridge line unchanged); reader vPinned(=="1")
   / vRealAnchored(=="2"). Missing / 0 / 1 load bitwise as today; an
   OLD build reads 2 as unpinned = the pre-D256 picture (fail-soft —
   batch-37 answer 8: he does not care about old-build compatibility
   until the first release). A new trailing field was rejected — three
   different positions (force/geo 11, sc 6). DRY RUN:
   Coaster.realEntrySpeed for a continuation (secIdx ≥ 1) returns the
   settle max(0.1, vReal) — the flagged landing — instead of the
   copied gate; a piece-first keeps max(0.5, vReal) and
   firstSectionSettle. The op still writes max(0.5, …) off its
   smoothing-suspended read; the at-rest EXACT pass corrects the
   2.6e-7 to the displayed bits in ONE pass (ConflictGate l2b: 1 exact
   rewrite, then 0), so D255's "after the click + rebuild vDesign ==
   the real seam speed BITWISE" holds by the reconcile, not by the
   read. USER-FACING: a section that CONTINUES the one before it, once
   set to its real speed (Set Design to Real, Convert Real to Design,
   the Transition-to-real add), KEEPS following its real entry speed
   after upstream edits, whatever the size of the difference — and on
   his file the last section's window now takes the click: the box
   reads 13.91, the grey number matches, a second click says it
   changed nothing, Ctrl+Z puts 13.99 back. A TRACK PIECE'S FIRST
   SECTION (one after a strict seam or a bridge — his p1s0, the "~v"
   follower-first) is the exception: its design speed stays
   independent by D135 (the reconcile never re-chains a piece-first,
   so the anchor is INERT there — review finding); set it again after
   an upstream edit. The section before it keeps its own 0.045 m/s
   difference (it wears "~v" now) and its shape. Typing a speed into
   the box ends the following (the section is pinned at the typed
   value, as before). DEVIATIONS from the investigation: (a) the
   '+' / Insert fork and makeGraphsReal DO set the flag (its "if
   adopted" options — decided yes); (b) the fixture's piece 1 is NOT
   ≠o (the seam tilt is heading-only; the gate asserts a non-identity
   tilt and reports the verdict as detail); (c) undo / redo through
   the DocState snapshot is pinned by the in-JVM -Dfvd.initspeedprobe
   hook (DocState is JavaFX-side), ConflictGate l2f pins the pre-op
   document's rebuild as the headless twin; (d) DetermGate198 reads a
   FILE fixture (no in-code one) — the flagged save → load → rebuild
   is pinned in ConflictGate l4 / h5 instead. ConflictGate 87 (was 58
   after §1): h5 REWRITTEN to the new verdict + h5b, block l l1-l14e
   on his conflict.uvd (embedded as FVD_B38) + the review round's
   l14f-h (the four CONVERT carries: partialConvertSection both sides,
   convertDomain, bakeAxes, convertWholeSection); DetermGate 6/4/10/7,
   LoadGate196 7, RangeResolveGate 115, SmoothRealGate, CopyGate 73,
   MergeGate 23, InsertGate 40, InflSplitGate 22, MultiCoasterGate 15,
   Phase2Gate 162 green; the hook prints fails=0 (10 checks): the
   click lands 13.913246110656 bitwise on the displayed real seam
   speed with the anchor set, a second rebuild and a second click are
   no-ops, Ctrl+Z restores 13.991003664686 + unflagged and a rebuild
   keeps it, Ctrl+Shift+Z restores the landed bits + anchor.

3. "SET DESIGN TO REAL" ALWAYS ENABLED (small item e, user verbatim:
   "actually make 'Set Design to Real' always able to be used/pressed,
   even if there isn't any speed conflict [never grayed out]"). The
   init-speed window's button never grays; its reasons became ONE live
   tooltip of two kinds. HARD — the click REFUSES and posts "Set
   Design to Real refused — <sentence>" on the stats bar: nothing
   compiled for the section, a legacy reversed section, a bridged-in
   first section the train stalls on / crawls off
   (SectionNames.bridgeCrawlWhy — the model refuses too, and its
   blow-up belt posts bridgeBlowUpWhy through toRealRefusedWhy), and an
   open convert preview (CONVERT_BUSY_MSG, as before). SOFT — the click
   RUNS; the sentence says what it does or why a difference may
   remain: the ride entry ("… — a click sets nothing."), a run head
   ("This track piece starts here — …; a click sets nothing."), an
   OPTIMIZING bridge leading into the piece (SectionNames.
   bridgeResolveWhy — batch-37 answer 7: the click runs, the bridge
   re-places the piece after it and the click can need repeating, 3-5
   measured), a stall, "Seam smoothing opens this speed difference —
   shrink the ◇ window instead." (k == 0 only — a continuation's
   settle IS the real seam speed since §2's anchor, so batch 37's
   "This section follows the section before it, which carries the
   speed difference — resolve it there." arm is GONE; §7 makes the
   sentence mode-aware), and the NO-CONFLICT sentence KEYED ON THE
   STATE (review finding — the anchor is INERT at k == 0, and a
   flagged section already follows): k ≥ 1 pinned "No speed conflict —
   the design speed already is the real entry speed; a click un-pins
   it so it keeps following the real flow.", k ≥ 1 un-pinned + flagged
   "No speed conflict — this section already follows its real entry
   speed; a click changes nothing.", k ≥ 1 un-pinned + un-flagged "No
   speed conflict — the design speed already is the real entry speed;
   a click makes it keep following the real flow."; k == 0 (a strict
   follower's / bridged-in piece's first section) pinned "No speed
   conflict — the design speed already is the real entry speed; a
   click un-pins it.", un-pinned "No speed conflict — the design speed
   already is the real entry speed; a click changes nothing — a track
   piece's first section keeps its own design speed." The D248
   verbatim tip shows while a conflict the click resolves is the whole
   story. AFTER THE CLICK: bits + pin + anchor unchanged → the
   knife-edge rollback (no empty undo step) + "Set Design to Real
   changed nothing — the design speed already is the real entry
   speed." (one wording now; at k == 0 a click that moved ONLY the
   anchor is this knife edge too — the flag is inert on a piece-first
   and the hover said "changes nothing", review finding); bits equal
   but the pin cleared → one undo step + at k ≥ 1 "Set Design to Real
   un-pinned this section — its design speed already is the real entry
   speed and now follows the real flow." (batch-37 answer 6: a section
   pinned AT its real speed un-pins with its bits kept), at k == 0
   "Set Design to Real un-pinned this section — its design speed
   already is the real entry speed." (no "follows" clause); bits equal
   and only the anchor set (k ≥ 1) → one undo step + "Set Design to
   Real changed no speed — the design speed already is the real entry
   speed, and this section now keeps following it."; bits changed →
   one undo step and, when a SOFT sentence other than a "No speed
   conflict…" one still applies (an optimizing bridge, the smoothing),
   "Set Design to Real — " + that sentence as information — and when
   the hover had named NO conflict yet the bits moved (a gap under the
   box's two decimals — a sub-display landing, review note), "Set
   Design to Real un-pinned this section and landed its design speed
   exactly on the real entry speed." (the pin cleared) or "Set Design
   to Real landed the design speed exactly on the real entry speed —
   the difference sat under the shown decimals." instead of a silent
   undo step. The grey "real NN.NN" readout is clickable WHENEVER
   SHOWN (the same Runnable, the same live tooltip); Cancel restores
   vDesign + vPinned + vRealAnchored (+ v0 for the entry) as one more
   undo step. The BADGE menu's two resolve items keep their grays (not
   in the ask). Model unchanged for this item (refreezeSpeedOnly
   already un-pinned on an equal value and on run heads); the
   grayHint wrapper around the button is gone — LiveWindow.show takes
   the Button itself. ConflictGate l11-l13 pin the model states.

4. RIDE KEYS LIVE FROM EVERY TOOL WINDOW — the per-key typing rule
   (the pv-keys root cause; user verbatim: "It seems that when the
   convert node density menu is open [the one that appears after
   clicking the "Convert" button in the Convert window] the viewport
   isn't able to be moved [at least POV can't be moved, wasd doesn't
   work in fly, space doesn't switch views, etc.]. Please find the root
   cause of this and fix this bug for all menus like it."). ROOT CAUSE
   (code reading + javap of the JavaFX 26 jars; the in-JVM probe
   reproduces and verifies it): the density preview popup DOES install
   the ride-key forwarder (v16.2) — the defect was the v19.6 / D235
   TYPING GUARD in FvdDesigner.forwardViewKey, which returned for ANY
   focused TextInputControl. The popup is a JavaFX Popup (never
   OS-focusable; every key enters the MAIN stage and PopupWindow's
   EventRedirector fires a copy at the popup scene's focus owner
   before the main Scene sees it), and on the first pulse after show
   Scene.focusInitial hands its key focus to the first
   focus-traversable node — the NUMERIC spacing TextField. The guard
   then bailed for W/S/A/D, Space, the view digits, Shift and Ctrl,
   and TextInputControlBehavior's catch-all KEY_PRESSED mapping
   consumed every plain press (the redirector consumes the original
   when the copy was consumed), so handleKey's convert-UI branch —
   which keeps POV / digits live — never ran either. D235 was written
   for the Coaster Options NAME field (Space must type a space) and
   silently killed the exact window the forwarder was built for; the
   same guard killed ride keys in every window whose focus owner was a
   plain TextField (the node editor's LiveNum boxes on open, the
   Reverse… entry speed, the Add / Insert speed box, the element
   dialog, the color picker's RGB boxes). CORRECTION to the
   investigator's report: numeric SPINNERS were never affected, before
   or after a click — JavaFX's FakeFocusTextField forwards
   requestFocus() to its parent Spinner (javap-verified; the probe's
   (f) step shows the Spinner as the focus owner after a click into
   its editor), so a Spinner editor is never a text-box focus owner
   and the Track Piece / seam / bridge / init-speed / speed-popup
   spinners were live all along; the dead class was exactly the bare
   TextFields. A LATENT COMPANION on the same path: neither
   non-autohide popup (density preview, speed-control options) set
   hideOnEscape(false) — JavaFX's default auto-hides a Popup on an
   unconsumed Esc. In the preview, Esc was dead while the spacing box
   had focus (the TextField swallowed it) and, after a click on the
   slider or the "Optimal placement" check, hid the popup WITHOUT
   clearPv, leaving pvBefore set — the app locked behind
   CONVERT_BUSY_MSG until an activation change cancelled; the speed
   popup's auto-focused radio never consumed Esc, so Esc hid it past
   spHideCtlPopup's null-before-hide teardown (stale spPopup / spHook
   until the next open). THE FIX, one root-cause change at the one
   choke every secondary scene uses, plus companions on the same
   path: (A) THE PREDICATE — uvd.KeyRoute (pure Java):
   numericTextKey(awtCode, signed) = digits 0x30-0x39, numpad
   0x60-0x69, period, numpad decimal, Backspace, Delete, the four
   arrows, Home, End, Enter, Escape, Tab, plus minus / numpad subtract
   on a SIGNED box; typesInto(press, shortcutDown, textBox,
   disabledOrReadOnly, numericSigned, code) = a release never types, a
   shortcut combo never types, a non-text focus owner never types, a
   disabled or read-only box never types (the D252 rule), a FREE-TEXT
   box (null sign) takes everything (D235 kept), a numeric box takes
   exactly the table. FvdDesigner.keyTypes(Scene, KeyEvent) reads the
   scene's focus owner, its disabled / editable state and its numeric
   tag and asks KeyRoute. forwardViewKey now reads `if
   (e.isConsumed()) return; if (source is a Scene && keyTypes(scene,
   e)) return;` — the isConsumed bail is new too: an earlier
   same-scene filter that consumed the key (the Shortcuts dialog's
   binding capture, a window's Esc / Enter filter) keeps the forwarder
   out, so a Space captured as a binding no longer ALSO toggles POV
   (same-node filters all run regardless of consumption). handleKey
   (the MAIN scene) keeps its D235 guard untouched — the user can
   always click the viewport there. (B) THE NUMERIC TAG —
   FvdDesigner.NUM_BOX_TAG ("fvd-numeric-box", a Boolean = the sign
   flag) + ONE factory numericField(TextInputControl, signed) =
   formatter + tag. Every hand-installed numFormatter /
   signedNumFormatter routes through it: the density preview's
   spacing box and the Reverse… entry-speed box (GraphEditor), the
   node editor's LiveNum (signed), the Add-section / Insert-section
   speed box (two sites), the element dialog's loop (signed), the
   color picker's three RGB boxes (Dialogs — the hex box stays free
   text: A and D are hex digits). numericEditor tags every Spinner
   editor the same way and NUM_SPINNER_TAG now carries the sign (every
   reader used containsKey; unchanged). numericBoxSigned(owner) = the
   box's own tag, else its tagged Spinner's (a belt — see the
   correction above), else null = free text. A site that is ever
   missed stays a free-text box = fully guarded, the fail-safe
   direction. StartPanel has no bare formatter site (its spinners go
   through typable → numericEditor; its name box is free text by
   design). (C) NO WINDOW OPENS "TYPING" — FvdDesigner.parkFocus(Scene)
   = root.requestFocus() right after show (the LiveWindow.settleTyping
   / winshot precedent — requestFocus needs no focusTraversable, and
   the first pulse's focus cleanup keeps a non-null owner) in the two
   windows whose first traversable node is a plain TextField: the
   density preview and the node editor. From an untouched density
   preview the view digits switch views (under handleViewDigit's own
   rule — only while a pane is captured or the mouse is over a view;
   hovering the popup itself is not "over a view") and W/S/Space
   ride; Tab or a click enters a box, and a digit typed into a clicked
   numeric box NEVER switches views (typing wins — decided).
   Spinner-first windows need no parking (the Spinner node is the
   owner). (D) ESCAPE IN THE TWO POPUPS — pvPopup.setHideOnEscape(false)
   + a KEY_PRESSED scene filter: Esc → cancelDensityPreview() (= Cancel;
   identity-guarded); Enter stays as today (root focused = the Apply
   default button, box focused = the box's own commit); spPopup.
   setHideOnEscape(false) + Esc → spHideCtlPopup() (= the ✕, the
   null-before-hide teardown). The pv popup's two hand-rolled filters
   (the D247 Ctrl+Z chain step-back and the forwarder pair) are
   replaced by app.installDocUndoKeys(scene) — its undo branch under
   convertUiActive is the same logic (chain step-back, else
   CONVERT_BUSY_MSG), and the forwarder now comes from the one install
   site. (E) MODAL CONFIRMS — installDocUndoKeys gained a (Scene,
   boolean viewKeys) overload; the two WINDOW_MODAL confirms — Delete…
   (FvdDesigner.confirmDeleteRun) and the Resolve-range end picker
   (GraphEditor) — pass false: undo keys stay (D227), the ride-key
   forwarder is dropped, so Space presses their focused default button
   instead of toggling POV (the viewport is blocked while they are
   modal anyway). Untouched by design: the showAndWait Alerts (unsaved
   changes, zero-distance bridge), Rename (WINDOW_MODAL free text,
   auto-focused), the Shortcuts dialog WHILE capturing (its filter
   owns every key — the isConsumed bail), any focused FREE-TEXT box
   (Coaster Options name, color hex, wizard name: D235's full
   swallow), the Coaster menu's ChoiceBox row (Space toggles POV before
   the menu's own activation, since v18.1) and the switch-coaster
   prompt (autohide, non-traversable buttons, live by fall-through).
   USER-FACING: ride and view keys work with ANY tool window open,
   whatever box has focus — a number box you are typing in keeps
   exactly the keys it can type (digits, period, minus where allowed,
   Backspace / Delete, arrows, Enter, Esc, Tab) and lets W/S/A/D,
   Space, Shift and Ctrl through; a text box you are typing in
   (coaster name, file name, hex color) keeps everything; the density
   preview and the node editor open with no box focused (digits switch
   views until you click or Tab into a box); Esc in the density
   preview is Cancel; Esc in the speed-control popup closes it like
   its ✕; Space on the Delete… / Resolve confirm presses the button.
   DEVIATIONS from the investigator's proposal: (i) its claim that
   spinner windows go dead after a click into an editor was wrong (the
   FakeFocusTextField fact) — the tag on spinner editors is a belt,
   not a fix; (ii) the optional installViewKeys on
   Viewport3D.showSwitchPrompt was not added (live by fall-through,
   out of scope); (iii) the gate's optional JavaFX-on-classpath run is
   folded into KeyRouteGate as a conditional section (e): it SKIPs on
   the plain battery classpath and pins the 28 KeyCode ints when run
   with the module path (131 checks there, 103 without). GATES:
   uvd.KeyRouteGate 103 / 131; the -Dfvd.keyprobe=1 hook
   (FvdDesigner.keyProbe — synthetic KeyEvents fired at the main
   scene's focus owner through Event.fireEvent so the Window →
   EventRedirector → popup path is exercised; GraphEditor exposes
   probePvPopup / probePvField / probeSpPopup / probeCanvas /
   probeWholeConvert / probeCreateControl as read-only seams): 45
   PASS lines, fails=0 (41 before the review round's (e2) — Enter from
   the parked popup root FIRES APPLY: popup gone, convertUiActive
   false, undo depth +1 — the Help's "Apply or Enter" claim is pinned,
   not assumed) — (a) preview open, focus parked on the root, Space
   toggles POV on / off; (b) fly: W press writes / release clears
   moveKeys from the preview; (c) 2 while captured leaves fly and
   enters the Front view; (d) spacing box clicked: a typed 2 edits the
   text ("1.00" → "2"), fly stays, no ortho, W still rides; (e) Esc
   cancels — popup null, convertUiActive false; (f) speed popup: a
   control created with its popup, the Spinner is the focus owner after
   a click into its editor, Space toggles POV, W rides, Esc closes it
   through spHideCtlPopup; (g) the node editor opens parked on its
   root, Space toggles POV; (h) the Coaster Options name box: Space
   never toggles POV; (i) Shortcuts capture: Space captured, POV
   untouched, the button shows "Space" again. -Dfvd.winshot re-run:
   the five window PNGs render, "trackpiece rows identical=true pixels
   equal=true", povrow fits.

5. SAVE ON RELEASE (small item a, user verbatim: "Make ctrl+s to save
   happen on release to make it not happen if ctrl+s and then
   click+drag graph node, etc. [less likely to accidently ctrl+s]").
   MECHANISM (FvdDesigner only): two fields beside slideKey —
   savePending (the save binding was pressed and nothing disarmed it)
   and saveKeyHeld (its main key is still down: OS auto-repeat must
   neither re-arm nor fire). bindingKey(actionId) = the non-modifier
   KeyCode of a binding (extracted from matches(), which now calls
   it). armSave() = `if (saveKeyHeld) return; saveKeyHeld = savePending
   = true;` replaces the three saveDesign(false) dispatches in
   handleKey (its start-panel, no-coaster and main branches — their
   gating untouched: a press inside a drag / slide / gizmo gesture
   never reaches them, fly and captured POV swallow S as a movement
   key, the convert-UI branch stays save-dead, a focused text box in
   the wizard returns first). DISARM: a new main-scene MOUSE_PRESSED
   filter (savePending = false, beside the KEY filters — it precedes
   every handler: canvas, viewport, gizmo picker, toolbar, menu bar;
   saveKeyHeld stays so the repeat that follows the click cannot
   re-arm) and the stage focus-loss listener (clears BOTH flags — the
   D200 backstop: a release landing in a tool window or after Alt+Tab
   never reaches the main filter). FIRE: saveKeyReleased(e), called at
   the end of the main scene's KEY_RELEASED filter after the
   gestureHot settle block — the release of the binding's MAIN key
   (normalizeNumpad(code) == bindingKey("save")) clears both flags
   and, if it was still armed and no convert preview owns the
   document, runs saveDesign(false). Both flags clear BEFORE the save
   runs, so the Save-As chooser a never-saved document opens (a nested
   loop that swallows the real key release) can never leave the latch
   stuck. SEMANTICS DECIDED: the release of the binding's MAIN key
   fires, modifier state at release ignored — releasing Ctrl first
   then S saves on the S release; releasing S first saves at once; a
   held Ctrl+S saves ONCE (today: once per OS repeat); a binding
   without modifiers (F2) presses / releases the same key; the
   modifier's own release never fires or clears anything. The
   slide-key case: Ctrl+S at rest never sets slideKey; the node press
   disarms; mid-drag S repeats take the waived branch and are
   swallowed by the modifier guard, so after the mouse release nothing
   re-arms and the S release saves nothing — TESTING 26b holds and his
   "Ctrl+S then click-drag" saves nothing. Ctrl+S armed then Ctrl+Z
   before the S release: undo fires at its press, the release saves
   the undone document (both were asked for). Untouched: File > Save /
   Save As (menu items call saveDesign directly), "export" (Ctrl+E)
   stays a press action, Ctrl+S inside a LiveWindow / popup scene does
   nothing (as today — no save dispatch there). USER-FACING: Ctrl+S
   saves when you RELEASE the key; press it and start a drag, a click
   or any mouse gesture instead and nothing saves; holding it saves
   once; the title's * clears on the release. GATES: the keyprobe
   hook's five drills through the saveDesign test seam (keyProbeOn
   counts and returns before the chooser; inert without the property):
   S1 one save per Ctrl+S press + release, none at the press (0 → 0 →
   1); S2 30 synthetic auto-repeat presses then one release save once
   (1 → 1 → 2); S3 Ctrl+S then a canvas press / drag / release (+ a
   repeat after the click) then the key release saves nothing (2 →
   2); S4 Ctrl released first: nothing at the Ctrl release, one save
   at the S release (2 → 2 → 3); S5 a modifier-less binding (F2)
   saves once, on its release (3 → 3 → 4).

6. THE TYPED MARK — typing or pasting the number a box already shows
   sets EXACTLY that number (batch-37 answer 5, his verbatim
   acceptance of the D255 §10 proposal; closes D255 §10, supersedes
   the consequence sentence of D254 §8 and of the D254-review
   BlankSafe note — "typing exactly the displayed text … is a no-op"
   is no longer true). WHY IT WAS A NO-OP: the only evidence of
   "typed" every numeric box kept was its TEXT. isUntouchedEditor
   (D247), typing() (D254) and BlankSafeConverter.fromString's
   short-circuit (D254 review) all tested text == the converter's
   rendering of the current value — and a box showing the "#.##"
   rendering of a value with more digits (12.35 over a ring-dragged
   12.3456) cannot tell "retyped 12.35" from "never touched".
   Decompiling javafx-controls 26 pinned the facts the fix rides on
   (gates/probes/typed-mark/FxOrderProbe, re-run this batch against
   the current jars — every line identical): the Spinner's own value →
   editor render runs BEFORE any listener on spinner.valueProperty()
   (wrapper listeners first); an equal setValue / setText fires
   nothing; commitValue parses the editor text through the factory
   converter; increment commits the editor first; a converter swap
   re-renders first; an EQUAL setText resets the caret to 0;
   commitValue('-') THROWS out of the default converter; and — the key
   one — a paste of the IDENTICAL text over a selection produces NO
   text event at all (TextInputControl.paste = Clipboard.getString →
   replaceSelection; isUndoable() is equally blind, UndoableProbe [a]),
   while typing the same digits char by char fires two. THE MECHANISM
   — uvd.TypedMark (pure, no JavaFX): one flag per numeric editor,
   kept in the editor's properties (FvdDesigner.markOf). SET by real
   input: any editor-text ChangeListener event outside a program-write
   bracket, or the PASTE PRE-MARK — a KEY_PRESSED filter on the editor
   for EXACTLY the chords Shortcut+V / Shift+Insert / the bare Paste
   key (review fix: TextInputControlBehavior's bindings are
   KeyBinding(V).shortcut() / KeyBinding(INSERT).shift(), whose
   unmentioned modifiers default to FALSE and KeyBinding.
   getSpecificity returns 0 with one down — Ctrl+Shift+V, Ctrl+Alt+V,
   Ctrl+Shift+Insert or a modified Paste key paste NOTHING, so they
   never pre-mark; the first build matched them and the next commit of
   the shown "#.##" text would have been the D247 phantom re-opened by
   a chord) that reproduces the result (text[0, selStart] + the clip
   with control characters stripped like TextInputControl's
   filterInput + text[selEnd, ]) and marks iff the box's own regex
   (TypedMark.SIGNED_RX / UNSIGNED_RX — the formatters read the same
   constants now) accepts it, so a rejected clip never marks. CLEARED
   by every program write: the value ChangeListener registered in
   numericEditor (it runs AFTER the Spinner's render and BEFORE every
   site's own listener, so the render's transient mark is gone before
   wireDocSpinner / seamEdit / apply run), a listener on the factory's
   converterProperty (re-hooked on a valueFactory swap — fricRoll /
   fricAir and the wizard's sMu / sK install their fixed-decimal
   converter AFTER typable), the DISABLE belt (a box disabled
   mid-event re-renders the model and clears — the D252 class),
   commitTyped and the Cancel discard. The app's own four
   editor.setText sites (commitTyped's blank rule ×2, its catch path —
   which renders through the converter now, not String.valueOf —, the
   discard) are bracketed through progSet. untouched := !typed && text
   == rendering (TypedMark.untouched) is the ONE predicate
   isUntouchedEditor, typing() and BlankSafeConverter.fromString share
   — the three readers changed by one line each. BlankSafeConverter
   takes the Spinner and NEVER THROWS any more (try / catch around
   base.fromString, LOAD-BEARING: a lone '-' used to throw out of
   commitValue, and Spinner's own focus-loss listener then ran
   cancelEdit() — the one rendering write nothing can bracket, which
   would have re-opened the D247 phantom on a marked box). commitTyped
   ends with canonical text (bracketed, the caret kept at the end
   while the editor is focused — only the in-place Enter routes
   outside the LiveWindows can see it) and a clear mark, so the
   same-value commit ('5.00' typed over 5.0 — no value event) leaves
   nothing behind. An ACTION handler on the editor routes Enter
   OUTSIDE the LiveWindows (the Coaster menu, the speed popup,
   Snapping / G-stops / Export / Insert) through commitTyped — it runs
   before the Spinner's own setOnAction commit, and the order is
   irrelevant: the second commit sees untouched text. discard clears
   the mark ALWAYS (the shown digits retyped then Esc = equal text + a
   set mark; the clear is what makes the hide-time commit a no-op).
   The init-speed editor's focus-loss re-seed skips a box still marked
   (it can run before the Spinner's commit; the commit's value change
   clears the mark and the model hook re-seeds). ONE COLLATERAL: the
   bridge window rows were the only ROUNDED mirror — setIfOff stored
   Math.round(v·100)/100 — so a typed-equal commit there parsed to the
   very value already stored and no listener could fire. It stores
   the EXACT model value now (the eps band kept, so the display
   behaves as before; a held axis is a value the march never moves),
   and the yaw site adds + 0.0 against "-0". USER-FACING: typing or
   pasting the number a box already shows (char by char, select-all +
   type, delete-and-retype, Ctrl+V / Shift+Insert of the identical
   string) sets exactly that number — one undo step, dirty, a re-solve
   where the number feeds the track; a box you only clicked into still
   changes nothing (D247 / D254 kept — the in-JVM probe keeps 12.3456
   bitwise through click-in / click-out, Esc after typing, Esc after
   the typed-identical text, and a lone '-' then a click away). The
   init-speed window's re-typed-equal value on an unpinned section
   PINS again (D242's rule, which D254 had silently dropped for
   equal-rendering text; equal + already pinned stays a no-op).
   DECIDED, documented: (a) a right-click context-menu Paste of the
   IDENTICAL text stays a no-op — JavaFX offers no hook short of
   replacing the editor's context menu (accepted gap, answer 5); (b)
   "typed something then deleted back to the shown digits" commits
   the shown number exactly (typed = intent); (c) an undo that
   restores a value EQUAL to the spinner's current value keeps a
   pending mark, so the later commit lands the typed number; (d) the
   node editor's LiveNum boxes are untouched (text-driven, they apply
   at the last keystroke; an identical paste shares the gap).
   DEVIATIONS from the investigation: none in substance. The
   DRAG_DROPPED pre-mark is added as proposed but VERIFIED after the
   event — JavaFX 26 has NO text-drop handler on a TextField (zero
   DragEvent references in TextInputControl / TextField / both skins /
   both behaviors; without a DRAG_OVER acceptance no DRAG_DROPPED is
   ever dispatched to the editor), so the filter is a belt for a
   toolkit that gains one: a runLater after the event marks only when
   the drop changed nothing visibly AND the selection-model result is
   that same text (TypedMark.dropMarks) — inert on JavaFX 26 by
   construction. The paste model lives on TypedMark as pure statics
   (pasteResult / pasteMarks / stripControl) so the gate pins it
   without a clipboard (the in-JVM probe deliberately never touches
   the user's system clipboard). GATES: TypedMarkGate 42 (the
   prototype's 29 checks verbatim as sections A-G + section H, 13
   checks, for the paste / drop model); -Dfvd.typedprobe=1 36 PASS,
   fails=0; FxOrderProbe + UndoableProbe re-run against the JavaFX 26
   jars, every line as recorded (gates/probes/typed-mark, own README
   — re-run on any JavaFX upgrade); no compile / reconcile / IO path
   reads the mark, the only model-facing delta is setIfOff's exact
   value in a UI sync.

7. "SMOOTHING OPTIONS…" — the strict-seam smoothing window +
   TRANSLATIONAL seam smoothing. (The TRANSLATIONAL mode — the check,
   the field, the `psmooth` line, the compiler's closure switch and
   the mode-aware sentences — was REMOVED in D257 §3 on his request;
   the window, its length box and its two buttons stay.) USER SPEC
   (verbatim intent): in the
   strict seam's dropdown, while smoothing is on, a "Smoothing
   options…" window carrying the smoothing length as a graph-editor
   style number box; a check "Allow translational movement of later
   sections" toggling between today's strict-seam smoothing (no
   translation of the track after the seam) and the s/c-element kind
   of smoothing (the downstream translates — smoother, but a speed
   conflict can open; the later track keeps its shape and only
   translates); the "Make smoothing real…" and "Disable smoothing"
   items as buttons, removed from the menu; with no smoothing only
   "Enable smoothing" remains in the menu, with smoothing only
   "Smoothing options…"; converts work as before ("convert as if no
   smoothing, then add existing smoothing"). WHAT THE TWO MODES ARE:
   the strict-seam smoothing is one pass, TrackCompiler.
   smoothStrictSeam — a quintic SLERP of the two sides' frames over
   the ±len/2 arc window, positions re-marched along the blended
   tangents at the original per-step arc, and, since v17.3 D140, a
   POSITION CLOSURE (sin² corrective humps capped 0.2 rad + a
   hump-weighted arc stretch capped ±5 %) that bends the window back
   onto its original end so the remainder's translation all but
   vanishes. The closure is the ONLY position-restoring step; the
   window-end miss dp was already returned and applied to the
   follower's remainder (later sections shiftLine'd, a trailing
   bridge's solvedEntry shifted, the span exit re-stamped, later
   followers entering at the shifted exit) for the closure's residual.
   The s/c reference he names (ScIntegrator.Profile.blend) is exactly
   the blend WITHOUT closure. (removed in D257) TRANSLATIONAL mode is
   therefore one boolean in the compiler: smoothStrictSeam(all, spans,
   len, hy,
   boolean closure) — closure = true runs the D140 block (the 4-arg
   overload delegates with true: every historic caller bitwise);
   closure = false skips it, so the window is the pure blend marched
   at the ORIGINAL per-step arc (the s fields equal the drawn arc
   exactly — no stretch, so D140's "ties / combs sit a hair wide"
   corner does not exist in this mode; the interior felt is the
   blend's own curvature by the κ identity) and the honest miss dp
   translates the remainder through the SAME postShift machinery.
   Nothing else in the compiler changed; the call site passes
   !piece.sSmoothTranslate. THE NUMBERS HE MUST BE TOLD (the design
   probe on the frozen snapshot + SmoothRealGate d2): the visible
   difference between the two modes is SMALL on realistic seams. The
   pure blend's miss — what the translational mode moves the rest of
   the track by — is 0.2 mm on his conflict.uvd seam (3.0146 m
   window, held pitch −39.57°), 3.1 mm at 10 m on the same seam,
   6.9 mm on the gate's 6°-kink fixture at 8 m and 36.9 mm at 20 m;
   today's closure removes a bend of only 0.01°-0.21° and a stretch of
   ≤ 0.11 % from those windows. The speed difference a translation
   opens downstream is the constant energy offset −2g·dp.y ≈ −0.0001 /
   −0.0010 / +0.0009 / +0.0022 m/s on those four cases — two orders
   under the 0.2 m/s ≠v badge, a hair-width true-stroke split; it
   becomes a badge-level conflict only on violent kinks with large
   windows (D140's 147° probe moved 0.69 m). The entry split the
   follower already shows is the seam's own and equal in both modes
   (his file −0.045 m/s; the gate fixture 7.962 vs 7.963 m/s).
   (removed in D257) THE MODEL: TrackPiece.sSmoothTranslate (default
   false = every old file
   bitwise), beside sSmoothLen; INERT while sSmoothLen ≤ 0.01. Carried
   by TrackPiece.copy, PlacementSnapshot (PieceRec.sTrans — record /
   differs / restore, so the three placement windows' Cancel reverts a
   flip exactly as they revert a diamond drag), FvdDesigner.PieceState
   (sSmoothTrans after sSmooth — Ctrl+Z after a flip returns the
   mode). "Disable smoothing" KEEPS the flag (disable → enable
   remembers the mode); "Enable smoothing (10 m)" writes the length
   only; makeSmoothingReal resets it beside B.sSmoothLen = 0 on the
   fresh window-end seam. mirrorPieceCore / mirrorRun leave it
   (mirror-invariant like the length); moveRun / Switch Coaster move
   objects. compileForFit and withSeamsSuspended stash and restore the
   LENGTH only — the flag is never written by them (SmoothRealGate
   d9). (removed in D257) IO: a new fail-soft line `psmooth 0|1` is
   written for EVERY
   piece right after the porient line (the piece line's fields 19-23
   are conditional, so a 24th field is ambiguous — the porient
   precedent); the loader's case "psmooth" reads field 1 when present.
   Missing line, a truncated psmooth, or an unknown value = false —
   every pre-D256 file compiles bitwise (SmoothRealGate d6 reloads a
   file with the line stripped onto the recorded closure checksum);
   the canonical save → load → save round trip is byte-identical; old
   readers ignore the unknown line (D50). MAKE-REAL AND CONVERTS: NO
   mechanism change. makeSmoothingReal compiles WITH smoothing (the
   blend IS the target), reads the window-end sample at arc sB — which
   in translational mode is exactly the translated window-end sample
   (the blend's last point) — and re-anchors the follower there, so
   the translation is carried into the real geometry to the fit
   residual (d5: B.x/y/z == the sharp cut + dp to 0.0 m, whole-track
   hold 0.3 mm, the tail 0.1 mm). Every lane-refit / resolve op keeps
   reading compileForFit with EVERY seam suspended — his own spec:
   the refit fits the sharp shape and the still-active blend
   re-applies on top with ≈ the same dp. CONSEQUENCE (unchanged by
   decision, told to him): the speed-resolve ops (refreezeSpeedOnly /
   makeGraphsReal / firstSectionSettle) anchor on the UNSMOOTHED seam;
   under translation the displayed compile differs from that read by
   the ~1e-3 m/s energy offset — inside the class D255 already
   documents for smoothed seams. A conflict that exists only because
   of the window keeps the D141 gray, now with a MODE-AWARE sentence
   (shrinking the window shrinks but never zeroes a translation;
   making it real or switching the mode back does). THE UI: (a) the
   seam menu — with sSmoothLen ≤ 0.01 the only smoothing item is
   "Enable smoothing (10 m)" (grayed on an s/c edge with the D232
   sentence, as before); with smoothing on, the only one is
   "Smoothing options…" — the "Disable smoothing" and "Make smoothing
   real…" items LEFT the menu. Order: Strict Seam n… / Insert
   section… / Enable | Smoothing options… / Un-strict seam / Split
   track at seam. The diamond's clean click still opens the menu, the
   diamond / band drags are untouched and the window's box follows
   them live. (b) THE WINDOW, GraphEditor.showSmoothingOptions(
   followerIdx): an owned LiveWindow titled SectionNames.
   smoothWindowTitle = "Smoothing Options (Strict Seam n)" (n = the
   follower's own junction, live-renumbered), anchored like the strict
   seam window (the follower's first strip x, else the junction
   button). Rows: "Smoothing length (m)" with a GraphEditor.LiveNum
   box — the node editor's box: every parseable text change applies
   at once (f.sSmoothLen = v; markDirty; rebuildTrack; redraw, undo
   coalesced per 0.8 s burst via the liveUndo recipe), ▲/▼, wheel,
   Shift tenths, Ctrl snaps to the diamond's own step
   (app.settings.snapLen(), Snapping > element length, default 1 m),
   hold-repeat, z3 three-decimal display; range [0.5, SEAM_SMOOTH_MAX
   100] — a typed 0 CLAMPS to 0.5 and never disables (Disable is the
   off switch; the drag's snap-shut threshold is the same 0.5); the
   box re-seeds from the model on focus loss so a clamped entry shows
   what the model took; the live hook never overwrites a focused box
   (an undo restore does). (removed in D257) Then the CheckBox "Allow
   translational
   movement of later sections" (tooltip: "Unchecked: the window bends
   and stretches to land back on its original end — nothing after the
   seam moves (the strict-seam smoothing as before). Checked: a pure
   blend like a straight/curve transition — the track after the seam
   keeps its shape but translates by the window's miss; a speed
   difference can open."), one undo step per flip. Then the buttons
   "Make smoothing real…" — grayHint with the FULL
   makeSmoothingRealWhy sentence, computed per displayed at-rest
   compile and CACHED on app.lastSamples identity (+ the length and
   mode as belts), the last answer kept while app.gestureHot (the
   smcGen / fssGen pattern — the dry run is a whole compile; diamond
   drags with the window open stay fast); click = hide the window,
   then makeSmoothingRealPreview (the menu item's exact behaviour) —
   and "Disable smoothing" = hide the window FIRST (null-before-hide:
   the seam stops smoothing, so the window's own hook would otherwise
   tear it down with a reason), then the D141 toggle's exact write
   (pushUndo, length := 0, dirty, rebuild, redraw), the flag kept.
   Footer Confirm / Enter / X keep; Cancel / Esc = app.cancelRestore
   of the open-time PlacementSnapshot (length AND mode revert as one
   more undo step; the seam window's recipe incl. rebuild +
   syncPlacementSpinners + updateBridgeControls + refreshGizmos),
   CANCEL_STALE_MSG on a coaster / generation mismatch. No box takes
   focus on open (FvdDesigner.parkFocus — §4's node-editor rule; W/S
   keep riding). (c) LIFECYCLE = the seam window's: fields smoothWin /
   smoothWinCoaster / smoothWinPiece / smoothWinObj, the live supplier
   by index on the open-time coaster, every handler identity-guarded;
   smoothLive(win, idx) = win is the live window AND the coaster is
   active AND idx in range AND followsPrev AND the piece at idx IS the
   object the window opened on (re-based on every document generation
   the hook has not seen — the init-speed window's rule — and on every
   undo restore through the docRestoreHooks force twin, which also
   re-captures the Cancel record) AND sSmoothLen > 0.01 — the length
   test DEFERRED while app.gestureHot, so a diamond drag through zero
   and back keeps the window and the release's at-rest rebuild tears
   it down if the window is still off; any failure tears the window
   down with the stats reason "The smoothing options window closed —
   its seam no longer smooths." (SectionNames.SMOOTH_WINDOW_CLOSED).
   Registered as a model hook (smoothHook) and a docRestoreHooks twin
   (smoothRestoreHook); setOnHidden routes to the same teardown;
   hideJunctionWindows hides it (every index-shifting op's pre-hide —
   Delete…, Connect, lock-Insert, Make seam strict, attach, Switch
   Coaster, Un-strict), and the three bare hideSeamPopup sites
   (split-at-seam, attachThroughBridge, the winshot hook) call
   hideSmoothingWindow too; every write refuses under
   app.convertBusy() (the box reverts muted, the check reverts,
   Disable / Cancel refuse, Make real grays with CONVERT_BUSY_MSG —
   the F3 transaction guard); LiveWindow.show installs the global
   undo + ride keys. (d) (removed in D257) THE D141 SENTENCES are
   MODE-AWARE through
   GraphEditor.smoothingTranslatesInto(p) (the nearest smoothing
   strict seam at or upstream of p in its run — for the badge's k == 0
   line that is p's own seam; A BRIDGE ENDS THE TRANSLATION'S REACH
   since the review round: the walk returns false at a bridged-in
   piece, p itself included — the bridge re-solves from the shifted
   exit to its target's OWN placement, so the sharp and the
   translated compiles reach the target at the same height and the
   −2g·dp.y energy offset closes at its entry; a seam before the
   bridge cannot have opened a speed difference behind it, and the
   init-speed window's k > 0 sentence no longer blames a translating
   seam upstream of a bridge): the badge's grayed line =
   SectionNames.smoothingCausedWhy(translate) — closure: "Speed
   conflict caused by seam smoothing — shrink the ◇ window instead"
   (verbatim), translational: "Speed conflict caused by the
   translating seam smoothing — make the smoothing real (Smoothing
   options…) or switch it back to anchored."; the initial design
   speed window's k == 0 soft sentence = SectionNames.initSmoothingWhy
   — closure: "Seam smoothing opens this speed difference — shrink the
   ◇ window instead." (verbatim), translational: "The translating seam
   smoothing opens this speed difference — make the smoothing real
   (Smoothing options…) or switch it back to anchored." The red band
   and smoothingCausedDrift (incl. §1's three-state driftLevel read)
   are unchanged and tint in both modes — the conflict IS the
   window's. (e) DEV HOOK -Dfvd.smoothprobe=<file.uvd>
   (FvdDesigner.smoothProbe, in-JVM, no Robot): opens the file, the
   window on piece 1, checks title / box text / check / focus, types 5
   and 0 (clamp 0.5, window stays), flips the check (flag set,
   rebuilt, the tail translated by ONE constant vector: |dp| 0.5 mm on
   conflict.uvd at 5 m, variation 1.8e-15), Cancel (length bitwise +
   mode restored, one more undo step), undo / redo with the window
   alive (the check resyncs; Cancel after a restore reverts to the
   state the restore left — flag kept, no undo step), Disable (closed,
   length 0, flag KEPT), a model re-enable (the remembered check +
   "10.000"), a model zero + rebuild (teardown with the reason),
   hideJunctionWindows, "Make smoothing real…" (closes the window,
   opens the density preview; its Cancel restores the seam).
   DEVIATIONS from the investigator's proposal: (1) the initial-speed
   window's translational sentence keeps that site's own subject ("The
   translating seam smoothing opens this speed difference — …")
   instead of the badge's "Speed conflict caused by …" sentence — the
   two sites have always worded their closure sentences differently
   and each keeps its form (four constants + two choosers in
   SectionNames, all LabelGate-pinned): the proposal's "both in
   SectionNames" is honored, its single shared sentence is not; (2)
   the sentence chooser reads the nearest smoothing strict seam at or
   upstream of the piece (smoothingTranslatesInto) rather than the
   piece's own flag — identical for the badge line, and the only
   honest answer for the initial-speed window on a k > 0 section
   behind a translating seam; (3) the length box re-seeds its text
   from the model on focus loss (the init-speed window's rule) —
   beyond the node editor's put rule — so a typed 0 reads 0.5 once
   focus leaves; (4) the dev hook is an addition (the standing in-JVM
   probe rule); (5) gate d2 pins dp as EQUAL (0.0 m) to the gate's own
   re-marched pure blend plus a magnitude band (8 m 6.5-7.3 mm, 20 m
   35-39 mm) rather than "within 1e-9 of the probe's printed value"
   (printed to 4 decimals only). EDGE DECISIONS (the enumeration, 41
   rows): a window too tight to engage behaves identically in both
   modes (no blend, no shift; Make real grays "too small to
   convert"); the flag is inert while off; translations compound down
   follower-of-follower chains and shift a trailing bridge's captured
   entry (an optimize-mode bridge re-marches at rest — the same path a
   diamond drag takes); Disable / snap-shut / Un-strict / Split at
   seam move the remainder back by dp (mm-cm, visible unlike the
   closure's 1e-6) and detachStrictSeam bakes the follow-contract
   pose = the sharp junction (correct); converts of the seam sections
   fit the sharp shape and re-blend; the follower's k > 0 sections
   and every downstream piece carry the constant −2g·dp.y as a
   true-stroke split; an old file with smoothing on an s/c edge still
   gets "Smoothing options…" (box + check + Disable work, Make real
   grays with the s/c sentence). GATES: SmoothRealGate 77 (was 15:
   section d — d1 the recorded closure checksums 28808918c0612fbe /
   0fc8182da8f4a418 over 1601 samples, d2 locality + dp == the pure
   blend's miss (6.9 mm at 8 m, 36.9 mm at 20 m, the third strict
   piece and a trailing bridge's entry shifted by it), d3 arc-exact +
   no humps bitwise, d4 trueV² == sharp − 2g·dp.y to 2.9e-12, d5
   make-real under translation, d6 the psmooth line's round trip /
   missing / truncated / unknown, d7 PlacementSnapshot + copy, d8 the
   entry split's level equal in both modes, d9 withSeamsSuspended),
   LabelGate section 17 (21 checks: the window's strings, the
   mode-aware D141 sentences at both sites + choosers,
   smoothWindowTitle on a plain seam / past a bridge / run head / out
   of range); Phase3Gate, uvd.SeamShapeGate 31, DetermGate 6/4/10/7,
   LoadGate196 7, CopyGate 73, MirrorGate 100, InsertGate 40,
   ReanchorGate 3, ConflictGate, RangeResolveGate 115, MultiCoasterGate
   15 green; the clean-span dumps (gates/probes/cleanspan-2026-09-30,
   OLD = this tree before the change, NEW = after, both off frozen
   class copies) BYTE-IDENTICAL, compare3.py "documents 54 | geometry
   or vDesign moved: 0"; -Dfvd.smoothprobe fails=0 (34 checks).

8. SMALL ITEMS. (c) THE HANDLE READOUT loses the word "sl" (user:
   "Remove 'sl' from the handle readouts"): GraphEditor's one format
   string (the drag readout in white and the selected node's knob
   hover in grey share it) is "L=%.2f%s  %.2f %s" now, so a readout
   reads "L=0.33 cubic  1.23 g/s" (or "L=0.33  1.23 g/s" without a
   Ctrl ease preset): the length keeps its "L=" and the preset name
   keeps its slot right after it (D255 §8), the SLOPE NUMBER and its
   UNIT stay — "g/s", "g/m", "°/s/s", "°/m/s", "m/s/s" per surface,
   the unit already names the number as a slope — only the redundant
   "sl" token is gone. Read as "drop the word, keep the number": the
   slope readout itself was his own D254 ask and D255 §8 set its form
   a batch ago; dropping the whole slope would have reversed that.
   Nothing else in the call changed; no gate pins the string (none
   did). Supersedes D255 §8's "L=0.33 [ease]  sl 1.23 g/s" form.
   (f) REVERSE'S GRAY SENTENCES — one chooser, five full sentences
   (batch-37 answer 2: keep the gray; when the run ALSO has a speed
   control the sentence mentions both). FvdDesigner's ad-hoc chain
   (REVERSE_MULTI_WHY / "Delete or convert the bridge first." /
   "Remove the speed controls first.") became the pure,
   LabelGate-pinned SectionNames.reverseWhy(multi, orphanTail,
   controls) (null = allowed; the busy / no-piece reasons still come
   first in the shell): precedence multi → orphanTail → controls, with
   speed controls APPENDED to the first two sentences instead of
   hidden behind them — REVERSE_MULTI_WHY unchanged ("Reverse works on
   a track piece without strict seams or bridges — un-strict its seams
   and convert its bridges first."), REVERSE_MULTI_CONTROLS_WHY
   "Reverse works on a track piece without strict seams, bridges, or
   speed controls — un-strict its seams, convert its bridges and
   remove its speed controls first." (his wording completed),
   REVERSE_BRIDGE_WHY "Reverse works on a track piece without bridges
   — delete or convert its bridge first.", REVERSE_BRIDGE_CONTROLS_WHY
   "Reverse works on a track piece without bridges or speed controls —
   delete or convert its bridge and remove its speed controls first.",
   REVERSE_CONTROLS_WHY "Reverse works on a track piece without speed
   controls — remove its speed controls first."; a multi-piece run
   whose tail is an orphan bridge takes the multi sentence ("bridges"
   covers it). The stats-bar belt on a racing click keeps "Reverse
   refused — " + sentenceTail(revWhy) and reads "Reverse refused —
   reverse works on …" in every case; GraphEditor.reverseActivePiece's
   own two belts (a bridge section / a section with controls, reached
   from the keyboard path) post the SAME sentences through a local
   refusalTail (the model's "a lift / brake on the mirrored flow is
   ill-defined" explanation moved into the code comment). LabelGate
   16 pins the four new constants and all eight (multi, orphanTail,
   controls) combinations. DEVIATIONS: none (the orphan-tail pair was
   the report's proposal; the decision fixed the five sentences
   verbatim).

9. BATCH-37 OPEN DECISIONS, HIS ANSWERS (2026-10-12; the D255 §12
   "kept as decisions for his eye" list and the batch summary's nine
   questions). (1) SpeedControl.snapCore's hold knife edge — he asked
   for the explanation; NO change (RESOLVED in D257 §5: he then asked
   for the one-line fix). snapCore decides "the step crossed
   the target" by the sign of (vB − vt)(vA − vt); after a snap the
   recorded speed sits at the target ± one rounding, so on the NEXT
   step the sign test is a coin flip on that rounding — the hold
   either re-engages or lets the step run free. A control stronger
   than the slope it holds against only leaks one- to three-sample
   dips (0.03 m/s); a control WEAKER than the slope (a 0.3 g trim
   brake on a steep drop, a complex graph that coasts) never regains
   a lost hold — up to 1 m/s for the rest of the ride; the design
   solve and the real march flip their OWN coins, so inside conflicted
   / tilted / blended stretches they can disagree by that much. The
   one-line fix (treat |vB − vt| ≤ EPS as at the target) changes the
   DESIGN solve: on his main test coaster the far end would move
   1.4 km (a slow crest amplifies), "B Shortcut not working" 1.5 mm.
   Standing rule unchanged: never change it without him. (2)
   Whole-run Reverse: KEEP THE GRAY; when the run also has speed
   controls the sentence must mention both — §8 (f). (3) Anchor x/y/z
   carrying what lies after a bridge: behaves as it does today (no
   change). (4) Un-revealed white junction balls below ground drawn
   behind the floor: INTENDED, confirmed. (5) "Typing exactly the
   number a box already displays does nothing": IMPLEMENT the
   proposal — any typed / pasted text directly from the user results
   in that value exactly — §6; accepted gaps: the right-click
   context-menu Paste of the identical text stays a no-op,
   typed-then-deleted-back-to-the-shown-digits commits the shown
   number. (6) A pinned section equal to the real speed could not be
   un-pinned from the init-speed window (button grayed "no conflict")
   — he asked what a pinned section is: a section whose design speed
   the user set by hand (the init-speed box or a custom speed in the
   Add dialog), so the reconcile never overwrites it; "Set Design to
   Real" is the un-pin. He confirmed the related intent: the FIRST
   section of a track piece (a run head) cannot have a speed conflict
   created — correct, a run head's real entry IS its design speed.
   RESOLVED by §3's "always enabled" rule: a click on an equal value
   un-pins (bits kept) and posts "Set Design to Real un-pinned this
   section — …". (7) "Set Design to Real" grayed behind an OPTIMIZING
   bridge — he asked for more detail: the bridge re-places its target
   piece after every resolution, so each click moves the piece —
   batch 37 measured 3-5 clicks and up to 19 m of target travel before
   it settles; the badge's two resolve items keep that gray. RESOLVED
   for the init-speed window by §3: the click runs, the bridge
   re-places the piece, the stats bar says why the conflict can reopen
   and that another click converges. (8) Old files re-settling once on
   first open (the 4.6 µm case): he does not care about changes to
   past files until the first release — so §2 persists the new
   per-section anchor as a tri-state in the section line's pinned
   slot ("2") rather than a new field. (9) BRIDGE_CRAWL_SPEED 1 m/s and
   the blow-up belt 3× + 30 m: accepted as judgment calls; he will say
   if a legitimate case is refused.

10. THE EXPLANATIONS HE ASKED FOR. (a) "Set Design to Real on a
   non-first detached piece wrote the previous piece's end speed
   instead of its own" (batch 37, D255 §3). Every piece in the list
   has an index, and a "run" is a chain of pieces joined by strict
   seams or bridges. A DETACHED piece (one that starts a new run — a
   run head) has no upstream track at all: the train is placed on it
   at its own design speed, so its real entry speed IS its design
   speed and "Set Design to Real" has nothing to write. Before batch
   37 the op found "the previous track" by LIST INDEX (piece p−1's
   last sample) instead of by RUN TOPOLOGY: for a detached piece at
   index ≥ 1 it therefore read the end speed of whatever piece
   happened to precede it in the list — an unrelated run — and wrote
   that number into the detached piece's first section as if the
   train arrived from there. The fix made the op ask
   Coaster.hasUpstream(p, k) (k > 0, or the piece follows a strict
   seam, or is bridged into) and write nothing for a run head (it only
   un-pins the section); the init-speed window's hover says "This
   track piece starts here — its real entry speed is its design
   speed." §3's always-enabled button keeps that: a click on a run
   head runs, changes nothing and posts the k == 0 "changed nothing"
   sentence. (b) THE POV ROLL-RATE REQUEST ("make the roll rate
   readout global rather than local"). Finding (a probe on his file,
   3015 samples): the POV bar's roll parenthesis ALREADY prints the
   bank-vs-horizon rate — Coaster.worldRates' third component φ' =
   Ω·T − ψ'·sin θ — since D211 (v18.39), and the probe shows it equals
   the numeric slope of the displayed bank angle over the true clock
   to three decimals at every interior sample, while differing from
   the LOCAL body roll by up to 35.9 °/s on pitched turns (i = 1751:
   pitch 18°, heading rate −114 °/s → global −31.6 °/s, body 4.3). The
   pitch / yaw parentheses are the same world rates (elevation',
   heading' reported-left). On FLAT track global and local are the
   same number by identity, and over an orientation-conflicted piece
   the slot shows the DESIGN BANK ANGLE "(d = …°)" (D254), not a rate
   — those are the two places a local-looking number can be seen. No
   code change; the summary asks him where he saw a local value. Help
   gained one clarifying clause (the roll rate in parentheses is the
   bank's rate against the horizon, what a global roll lane authors).

11. THE BATTERY (wiped classes, every rc 0). 36 members since D256:
   uvd.KeyRouteGate 103 (131 with the JavaFX module path) and
   TypedMarkGate 42 are new; LabelGate 190 (was 129: section 15 the
   three-state glyph, 16 Reverse's sentences, 17 the Smoothing Options
   strings), uvd.ConflictGate 87 (was 51: block k the "~v" levels on
   his fixture, h5 rewritten + h5b, block l the explicit anchor incl.
   the convert carries), SmoothRealGate 77 (was 15: section d);
   GestureGate 180, DetermGate197/198/199/200 6/4/10/7, LoadGate196 7,
   RangeResolveGate 115, Phase2Gate 162, Phase3Gate (86 PASS lines),
   uvd.SeamShapeGate 31, CopyGate 73, MergeGate 23, InsertGate 40,
   InflSplitGate 22, MultiCoasterGate 15, MirrorGate 100,
   ReanchorGate 3 unchanged. In-JVM hooks on the same build:
   -Dfvd.keyprobe=1 45 PASS / fails=0, -Dfvd.typedprobe=1 36 PASS /
   fails=0, -Dfvd.initspeedprobe=<conflict.uvd> fails=0 (10 checks),
   -Dfvd.smoothprobe=<conflict.uvd> fails=0 (34 checks), -Dfvd.winshot
   the five PNGs with "trackpiece rows identical=true pixels
   equal=true" and the povrow fitting; gates/probes/typed-mark's
   FxOrderProbe + UndoableProbe re-run against the JavaFX 26 jars,
   every line as recorded; the clean-span dump byte-identical over 54
   documents. The docs integrator's belt on the merged tree: LabelGate
   and uvd.ConflictGate re-run on wiped classes (TESTING #173 carries
   the lines).

## D257 (v19.28) — Batch 39: the POV bar's design-speed suffix in its own slot, the initial design speed window's layout (button left, no readout space without a conflict), the TRANSLATIONAL smoothing mode REMOVED + the tooltip rule re-asserted, the bridge-end rig's rings follow the box (a ring wherever the box is typeable), and the held-control knife edge CLOSED (his answer to batch-37 open decision 1)

User feedback round on batch 38 (2026-10-13): five items, each quoted
verbatim in its §. Method (the standing early-edge-case rule): one
implementer per item, each enumerating its state combinations before
coding (the -enumeration notes in the batch-39 scratchpad:
smooth-remove, bridge-rings, pov-slot, init-speed-layout, snap-fix — 25
rows for the snap fix) and leaving a docs note; one lean reviewer per
item; a polisher applying the review's six should-fix findings (the ten
initial-speed tooltip sentences, two Help rows, the winshot assertion, a
passD comment, two note corrections — polish.md); the docs integrated
last from the notes. One new in-JVM dev hook (-Dfvd.bridgeringprobe); no
new gate; the battery is §6. The only model / compile change is §5's one
line in SpeedControl.snapCore; §3 removes a one-batch compiler branch
(TrackPiece.java and PlacementSnapshot.java are byte-identical to the
pre-batch-38 tree again, TrackCompiler.java differs from it by one
comment).

1. THE POV BAR'S DESIGN-SPEED SUFFIX IS ITS OWN SLOT, SHOWN ONLY ON A
   CONFLICT (user, verbatim: "For the speed POV readout, when there is
   not a speed conflict, remove the space where the design speed readout
   would be [an exception to the statistics not jumping around]").
   BEFORE: D255's review round (SH-1) sized the speed slot for the
   conflict form "88.888 m/s (d = 88.888)" permanently, so the " (d =
   …)" reserve sat empty in the row whenever the POV was off a speed
   conflict — 70 px of nothing between the speed and the "|" before
   Vertical G — in the name of D239's rule that the stats never move.
   NOW the suffix is a slot of its OWN: povVal[POV_DES] (= 9), a Label
   right after the speed slot (3) in the same HBox, template " (d =
   88.888)" measured like every slot, with its own ratchet
   (povSet(POV_DES, …)). updatePovStats writes the speed as "12.345 m/s"
   into slot 3 (template "88.888 m/s") and hands the suffix to
   povDesign(String): a string shows it (text + managed + visible), null
   takes it OUT of the layout (unmanaged + invisible), so the HBox lays
   the row out without it — the readouts right of the speed slide 70 px
   left off a conflict and back right on one. The managed / visible
   writes happen only when the state CHANGES (a guard on isManaged), so
   a ride inside or outside a conflict costs nothing per frame beyond
   the text, like every other slot. This is the ONE deliberate exception
   to D239's no-jump rule: the layout right of the speed moves at
   exactly two moments — a conflict appearing and one ending — never per
   frame or per digit (inside a conflict the suffix is a fixed slot: its
   digits move nothing). The pitch / roll "(d = …°)" orientation form is
   untouched (not asked): those slots keep their wide templates. povFit
   FITS THE WORST CASE: the gap-tightening fallback (D255 round 3: once
   a slot has ratcheted and the row no longer fits its laid-out width,
   POV_GAP 7 → POV_GAP_MIN 2 a pixel at a time, never restored) now
   measures povRowPrefWidthWorst = the row's pref width PLUS the
   suffix's slot while the suffix is hidden, so the tightening is a
   function of the ratchets alone — once and for the session, never of
   what the POV happens to be over — and a conflict appearing after a
   ratchet can never overflow the bar (the gaps already gave way); with
   no ratchet povFit still returns at once: the default look is the
   templates', bit-identical whatever the window's width. MEASURED (the
   -Dfvd.winshot fixture, his 1216 px scene, the row laid out at
   1213.3): as rendered off a conflict 1127.3 px (D255: 1194.0 with the
   permanent reserve); the worst case / with the suffix on screen 1197.3
   px — 3.3 px over D255's 1194 because two labels each carry
   statsTextW's +2 px ceiling where one did (16 px of headroom remain);
   the suffix slot " (d = 88.888)" measures 70 px. THE DEV HOOK:
   -Dfvd.winshot's last lines are THREE "WINSHOT povrow" measures
   instead of one — the row as rendered at the ride start (the suffix
   hidden), its worst case ("-worst", what povFit fits) and — the POV
   ridden onto the fixture's bridged-in target piece, whose design speed
   (24) differs from the real one it enters at by well over 0.2 m/s (the
   PNG reads "21.649 m/s (d = 23.895)"), so povSpeedConflict holds — the
   row with the suffix ON SCREEN ("-conflict", "design shown"); each
   line ends "fits" or "OVERFLOWS (captions clip)" and the new field
   "design hidden|shown" says which state was measured. Since the
   batch-39 review the "-conflict" measure ASSERTS its state: if
   povDesignShown() is false after the ride onto the target the hook
   prints "WINSHOT povrow-conflict MISSING: the design-speed suffix is
   not shown on span N" and exits 1, so a fixture drift (the target's
   design speed sliding under DRIFT_EPS, the span index landing on a
   bridge) can never leave the line reading "fits" vacuously (the
   negative branch is not exercised — it needs a fixture without the ≠v
   target; the positive run reads "design shown" and exits 0). Two PNGs
   of the laid-out row join the window shots (povrow-hidden.png /
   povrow-shown.png, 1214×26, one pulse after each update).
   -Dfvd.povprobe prints the pulse clock only (no row widths) and stays
   the per-frame smoke test (146 pulses clean at 3 s). FILES:
   FvdDesigner.java only (the POV bar block — updatePovStats, the
   fixed-slot fields + POV_DES, povRowPrefWidth / povRowPrefWidthWorst /
   povDesignShown, povFit, buildPovRow, povDesign — and the winshot
   tail); no model / compile / reconcile / IO code, no gate-pinned
   string, no gate grew or shrank, no tooltip. DEVIATIONS: none from the
   request; nothing else in the row changes (captions, separators, gaps
   and the other eight slots keep D239 / D255 bitwise). The hook's
   earlier "trackpiece rows identical=true pixels equal=…" line — the
   Track Piece window shot three times, before this item's code runs —
   printed "false" once in four runs and "true" otherwise, on the
   baseline classes too: a timing flake of that comparison, not this
   change.

2. THE INITIAL DESIGN SPEED WINDOW'S LAYOUT (user, verbatim: "For the
   initial design speed window, put the 'set design to real' button on
   the left side of the window. Then when there is not a speed conflict
   [creating a real speed readout button], remove the real speed readout
   button space and have the right edge of the cancel button be in line
   with the right edge of the spinners [no speed conflict makes it look
   like how it looked before]"). THREE CHANGES. (a) "Set Design to Real"
   sits at the window's LEFT edge: LiveWindow.show takes it as a
   footLead followed by a footSpring (the Track Piece window's Switch
   Coaster… / Delete… recipe, D255) — D254 had packed it against
   Confirm. (b) The grey "real NN.NN" readout takes NO SPACE while no
   speed conflict is displayed: it is UNMANAGED (not merely invisible),
   so the row is [label][box] and the window's content width is the
   row's — the right-aligned footer ends on the box's right edge:
   Cancel's right edge == the box's right edge EXACTLY (262.0 == 262.0
   measured, the pre-D255 look). D255's RESERVED slot ("the window never
   resizes") and its LATCH ("shows once a conflict is seen and stays
   until the window closes") are GONE: the readout shows WHILE the D256
   predicate holds (speedDiffers(vDesign, settle) ||
   speedDiffers(vDesign, displayed real), re != null — unchanged) and
   hides when it stops; a conflict appearing (a typed value, an upstream
   edit, an undo) WIDENS the window at its right edge by the readout's
   slot, its end (the click, an undo, an upstream edit) NARROWS it back
   — measured on conflict.uvd: 276.0 px ↔ 365.3 px scene width. The
   readout keeps its D254 fixed width WHILE SHOWN (the widest text,
   "real 10000.00"), so the digits changing per compile never resize the
   window; no distinct real speed (a run head, a reversed section,
   nothing compiled) = hidden (D255's latched "real —" state no longer
   exists); clickable whenever shown = the button's Runnable, the one
   live tooltip, as D256 left them. (c) THE BOX NEVER MOVES between the
   two states: the footer [Set Design to Real][spring][Confirm][Cancel]
   can be wider than the bare [label][box] row, so the row's LABEL
   COLUMN is sized PRE-SHOW to max(label pref, footer pref − 8 − box
   pref) — then the no-conflict row is exactly the footer's width (or
   wider by itself; on his machine the row wins by 0.7 px: label 154 + 8
   + box 86 = 248 vs the footer's 247.3) and the column stays fixed, so
   a conflict adds the readout to the RIGHT of a box that stays put (x =
   176..262 in both states). REJECTED: letting the label grow (HGrow) —
   the box would slide ~15 px left under the typist when a conflict
   opens live. MECHANISM — three LiveWindow additions, every other
   window bitwise on its old path (no property → no branch):
   allowNarrowing(content) sets a MAY_NARROW property that refit() reads
   to SKIP the D254 min-width ratchet (the window's min width stays
   computed, so sizeToScene shrinks it — the SECOND deliberate exception
   to the no-jump rule after the screen clamp, this window alone;
   top-left anchored still: the right edge moves, nothing above a row
   moves, the clamp is the one residual move when the widening would
   leave the screen); preMeasure(content, Runnable) runs the caller's
   step inside place() right AFTER content.applyCss() and BEFORE the
   pre-show measure (every Labeled has its skin, font and padding there,
   so the buttons' pref widths are real and the window opens at the
   right size with no post-show hop — the D249 flash lesson);
   footOf(content) returns the footer HBox show() stored on the
   content's properties (FOOT). syncReal ends with LiveWindow.refit(app,
   win) (a no-op before show; the refit's steady state is free). The
   readout carries the id "initSpeedReal" for the probe. TOOLTIPS: none
   added by this item; the batch-39 review then had the polisher trim
   every D256 HARD / SOFT sentence and the default to ONE short sentence
   — the list is in §3. MEASURED (the -Dfvd.initspeedprobe hook, 39
   checks, fails=0, conflict.uvd): no-conflict open on piece 0 section 1
   — scene 276.0 px wide, readout unmanaged + hidden, Cancel right edge
   262.0 = box right edge 262.0, the button at x 14.7 (content padding
   14 — a 0.7 px pixel-snap remainder of the footer being 0.7 px
   narrower than the row; 14.0 in the wide state; sub-pixel, accepted);
   stepping the box +3 m/s pins it and the window widens to 365.3 with
   the readout managed + visible and the box at the same x; Ctrl+Z
   narrows it back to 276.0 aligned; a re-opened conflict then Cancel
   closes the window with the section reverted and un-pinned; on piece 1
   section 1 (the conflict) the window opens at 365.3 with the readout
   present, the D256 click lands 13.913246110656 bitwise + anchored and
   one pulse later the readout is gone and the window is 276.0 and
   aligned; the second click, Ctrl+Z (readout back, 365.3), Ctrl+Shift+Z
   (gone, 276.0) as in D256. The probe measures LAYOUT bounds — the
   auto-focused box's boundsInLocal include modena's focus ring at −1.4
   px insets (a first run read 263.4 against 262.0). winshot: the three
   placement windows byte-identical to the batch-38 PNGs (468×344 /
   389×203 / 442×405, rows identical, pixels equal). Belts:
   uvd.ConflictGate 87/87, LabelGate 182/0 (no sentence changed by the
   layout). KNOWN: the window is re-measured per model hook (refit's
   size compare is the whole cost at a steady state); near the screen's
   right edge a widening is clamped LEFT by the overflow and the later
   narrowing leaves x there (refit's documented one move). FILES:
   LiveWindow.java, GraphEditor.java (showInitSpeedDialog),
   FvdDesigner.java (the hook); no model / IO / compile change; no gate
   grew or shrank (the hook 10 → 39 checks). DEVIATIONS: none.

3. THE TRANSLATIONAL SMOOTHING MODE IS GONE; THE TOOLTIP RULE
   RE-ASSERTED (user, verbatim: "Actually completely remove the
   translational movement checkbox from the smoothing window, it didn't
   do exactly what I had in mind and it's a small feature. Also I
   noticed that the hover tooltip was egregiously long and did not
   follow the rule for hover tooltip length I had set a while ago.
   Please make sure to follow this rule for all future tooltips."). WHY:
   the mode of D256 §7 — a pure blend that translates the track after
   the seam by the window's miss — was not what he had in mind, and it
   is small; it is removed on his request (D256 §7 carries inline
   "(removed in D257)" notes — the history stays). WHAT WENT (the whole
   D256 §7 feature, which existed only in the uncommitted batch 38): the
   "Allow translational movement of later sections" CheckBox and its
   tooltip in GraphEditor.showSmoothingOptions;
   TrackPiece.sSmoothTranslate and every carrier — TrackPiece.copy,
   PlacementSnapshot (PieceRec.sTrans record / differs / restore),
   FvdDesigner.PieceState (sSmoothTrans); DesignIO's `psmooth 0|1`
   writer and its reader case; TrackCompiler.smoothStrictSeam's 5-arg
   `boolean closure` form — the 4-argument signature is back with the
   D140 position-closure block UNCONDITIONAL, and the call site passes
   four arguments again (TrackPiece.java and PlacementSnapshot.java are
   byte-identical to the pre-batch-38 tree; TrackCompiler.java was too
   until the polisher's §5 comment edit); the `B.sSmoothTranslate =
   false` reset in Coaster.makeSmoothingReal;
   GraphEditor.smoothingTranslatesInto and the mode-aware sentence
   choosers SectionNames.smoothingCausedWhy / initSmoothingWhy with
   their translational constants SMOOTH_TRANSLATE_LABEL /
   SMOOTH_TRANSLATE_TIP / SMOOTH_TRANSLATE_WHY /
   INIT_SMOOTH_TRANSLATE_WHY; the -Dfvd.smoothprobe hook's check steps;
   SmoothRealGate's translational checks (d2-d4, d6-d9, d5's translation
   assertions) and LabelGate 17e / 17f / 17k / 17l / 17n / 17o. WHAT
   STAYS: the "Smoothing options…" window itself (D256: the live
   "Smoothing length (m)" box, the "Make smoothing real…" and "Disable
   smoothing" buttons, Confirm / Cancel, the seam-window lifecycle, the
   reduced seam menu with ONE smoothing item), every strict seam
   smoothing as the D140 CLOSURE always (the window bends and stretches
   back onto its original end; nothing after the seam moves). The two
   D141 sentences are the single D255 texts again, kept as
   LabelGate-pinned constants: the badge's grayed line
   SectionNames.SMOOTH_SHRINK_WHY "Speed conflict caused by seam
   smoothing — shrink the ◇ window instead" and the initial-speed
   window's SectionNames.INIT_SMOOTH_SHRINK_WHY "Seam smoothing opens
   this speed difference — shrink the ◇ window instead." (that site's k
   > 0 handling from the batch-38 real-anchor item is untouched: the
   sentence shows whenever the displayed difference exceeds the window's
   0.005 m/s gate). "Disable smoothing" → "Enable smoothing (10 m)"
   remembers nothing (there is no mode to remember). The window's
   Make-real gray-reason cache is keyed on the displayed compile's
   identity + the length (the mode belt is gone). FILES: no `psmooth`
   line is written; the loader has no `psmooth` case, so a line in a
   file saved by the batch-38 build is ignored by the unknown-line rule
   (D50) and that seam compiles the closure — a seam that was
   translating snaps back by its blend's miss (mm–cm, the D256 numbers)
   on open. Accepted: the feature never reached a commit, and a grep
   over his design folder finds no file carrying `psmooth 1`, so no file
   re-settles on open. The canonical save → load → save round trip is
   byte-identical (DetermGate197/198, LoadGate196 green); the clean-span
   dump of his 54 documents is byte-identical old vs new for this item
   and to the dump batch 38 recorded (54 DOC blocks / 8,649,952 bytes
   each, `cmp` identical; compare3.py "documents 54 | geometry or
   vDesign moved: 0" — §5 then moves two). THE COMPILE IS BITWISE THE
   CLOSURE MODE: SmoothRealGate d1 keeps the two 64-bit checksums
   recorded from the frozen pre-D256 snapshot — 28808918c0612fbe at 8 m
   / 0fc8182da8f4a418 at 20 m over 1601 samples — now simply the
   smoothing compile's regression pins (re-record on any deliberate
   blend change); d2 = the make-real round trip on the smoothed compile
   (applies, structure, the fresh window-end seam's smoothing off, B
   re-anchors at the smoothed compile's window-end sample to 0.00e+00 m,
   whole-track hold 0.0042 m, tail 0.0042 m). The gate shrank 77 → 24;
   LabelGate 190 → 182 (section 17 = 13 checks: title word, the two
   seam-menu items, the length label, the two buttons, the teardown
   reason, the two D141 sentences, smoothWindowTitle on a plain seam /
   past a bridge / run head / out of range). UI probe
   -Dfvd.smoothprobe=<conflict.uvd>: "PROBE smooth fails=0" (28 PASS
   lines; it gained one check — "no check box in the window" — and
   drives its undo / redo leg through a typed length (7) instead of the
   removed flip). THE TOOLTIP RULE, RE-ASSERTED (STANDING, the user's
   rule restated this batch): a hover tooltip is ONE short sentence
   saying what the control does — never how it works, never multi-line;
   a gray reason likewise one sentence. The offending tooltip was the
   check's SMOOTH_TRANSLATE_TIP — two labelled paragraphs over a line
   break explaining both modes' mechanisms — and it went with the check.
   The sweep of every tooltip batch 38 added (the b38 src-baseline vs
   the tree: `new Tooltip(` / `grayHint(` / `setTooltip(` /
   `Tooltip.install` in GraphEditor / FvdDesigner / Dialogs /
   LiveWindow): (a) the smoothing window's remaining hover text is the
   Make-real button's grayHint = Coaster.makeSmoothingRealWhy's
   one-clause fragments ("the smoothing window is too small to convert",
   …) — compliant, unchanged; (b) the initial-speed window's ONE live
   tooltip (the batch-38 real-anchor item: realTip / toRealTip fed by
   tipText) — CORRECTED BY THE BATCH-39 REVIEW: its default text was NOT
   the user's D248 verbatim (D248's read "Fills box with the real entry
   speed, resolving a speed conflict. Does not resolve an orientation
   conflict"; batch 38 wrote a new two-sentence default, absent from
   b38/src-baseline), and the live sentences, though one sentence each,
   were long and explained mechanism. The polisher trimmed EVERY one to
   ONE SHORT sentence saying what the click does
   (GraphEditor.showInitSpeedDialog; none is pinned by a gate —
   LabelGate pins only SectionNames.INIT_SMOOTH_SHRINK_WHY and the
   badge's bridge sentences, both unchanged; the post-click branch still
   keys on the "No speed conflict" lead; the initspeedprobe asserts only
   the stats line "Set Design to Real changed nothing…", unchanged). Old
   → new: the default (tipDefault) "Sets this section's design speed to
   the real entry speed at once, resolving a speed conflict. Does not
   resolve an orientation conflict" → "Sets the design speed to the real
   entry speed."; "A reversed section (from an older file) has no real
   entry speed to take." → "A reversed section has no real entry speed
   to take."; "The ride entry's design speed is the real one — a click
   sets nothing." → "The ride entry's speed is already real — a click
   sets nothing."; "This track piece starts here — its design speed is
   its real entry speed; a click sets nothing." → "This track piece
   starts here — a click sets nothing."; "The train stalls before this
   section — the design speed is already at its lowest." → "The train
   stalls before this section."; noConflict (k ≥ 1, pinned) "No speed
   conflict — the design speed already is the real entry speed; a click
   un-pins it so it keeps following the real flow." → "No speed conflict
   — a click un-pins the section to follow the real flow.";
   noConflictFollows (k ≥ 1, real-anchored), RENAMED noConflictNone, "No
   speed conflict — this section already follows its real entry speed; a
   click changes nothing." → "No speed conflict — a click changes
   nothing."; noConflictFree (k ≥ 1, neither) "No speed conflict — the
   design speed already is the real entry speed; a click makes it keep
   following the real flow." → "No speed conflict — a click makes the
   section follow the real flow."; noConflictFirstPin (k == 0, pinned)
   "No speed conflict — the design speed already is the real entry
   speed; a click un-pins it." → "No speed conflict — a click un-pins
   the section."; noConflictFirst (k == 0, un-pinned), FOLDED into
   noConflictNone, "No speed conflict — the design speed already is the
   real entry speed; a click changes nothing — a track piece's first
   section keeps its own design speed." → "No speed conflict — a click
   changes nothing."; unchanged: "Nothing compiled for this section
   yet.", INIT_SMOOTH_SHRINK_WHY, the bridge sentences
   (bridgeResolveWhyAt / bridgeCrawlWhyAt — pre-batch-38, pinned). The
   Help's "Resolving" sentence "its hover says what a click will do:
   with no speed conflict a click just un-pins the section, or makes it
   keep following the real flow, or changes nothing — the hover says
   which" still describes them; (c) Reverse's four gray sentences (D256
   §8) are one sentence each; (d) FvdDesigner, Dialogs and LiveWindow
   added no tooltip in batch 38; (e) §4's BRIDGE_LOCK_TIP, three lines
   since D249, is one sentence now. Changed tooltips in all: the removed
   check tooltip (old → gone), the ten initial-speed sentences above and
   BRIDGE_LOCK_TIP. FILES: TrackPiece, PlacementSnapshot, TrackCompiler,
   Coaster, DesignIO, FvdDesigner (PieceState + the -Dfvd.smoothprobe
   hook), GraphEditor, SectionNames, gates/SmoothRealGate,
   gates/LabelGate. DEVIATIONS: (1) the two D141 sentences stay as
   SectionNames constants (SMOOTH_SHRINK_WHY / INIT_SMOOTH_SHRINK_WHY,
   LabelGate-pinned) rather than the D255 inline literals — the texts
   are the D255 sentences verbatim; only the translational twins and the
   choosers went. (2) The reduced SmoothRealGate section d numbers its
   two surviving blocks d1 / d2 (d2 = the D256 d5 round trip in its
   closure form). (3) The smoothprobe hook gained one check and drives
   its undo / redo leg through a typed length instead of the removed
   check flip. (4) His own files were scanned for `psmooth 1` to make
   the "no file re-settles" claim honest — none found.

4. THE BRIDGE-END RIG BUILDS A RING WHEREVER THE BRIDGE WINDOW'S BOX IS
   TYPEABLE (user, verbatim: "For bridges that have no optimization or
   have position optimization checked, if an orientation is unlocked
   still allow the unlocked orientation to be edited with the gizmo.
   Only disallow this for when position and orientation is optimized. In
   other words, only if the value in the box is grayed out should you
   hide its respective gizmo."). WHAT CHANGED: the bridge-end rig (the
   rig the trailing bridge's white end ball reveals — the NEXT piece's
   entry) builds a ring for axis k exactly when
   BridgeSection.axisEditable(k) — D249's ONE enable predicate,
   unchanged: `locked || optMode != OPT_POS_YAW` — which is precisely
   the condition under which the bridge window's box for that axis is
   NOT grayed. So: under "No optimization" and "Optimize position" EVERY
   axis wears a ring, locked or not (nothing aligns it there, the box is
   typeable and the value is de facto held); under "Optimize position
   and orientation" the HELD (🔒) axes wear rings and the free ones — the
   march's, their boxes grayed "The bridge aligns this axis — lock it to
   hold a value." — do not. D249 had built the held axes' rings only, in
   the two non-aligning modes, and NO ring at all under
   optimize-orientation ("the tangent arrow alone"); a default free-yaw
   bridge therefore showed no yaw ring in manual mode even though its
   yaw box was typeable — the asymmetry the user called out. THE WRITE
   PATH IS THE BOX'S: a ring drag writes exactly the field the bridge
   window's typeable box writes — the target piece's own yaw / pitch /
   roll through GraphEditor.gizmoSetBridgeOrient (Global aiming:
   setRawAbsYaw / Pitch / Roll(…, true), routed through the target's
   orientation mode like the box — LOCKSTEP both tiers, REAL_ONLY the
   real tier, DESIGN_ONLY the design tier; placementEdit's D221 carry)
   and then re-places per mode like a typed value: a plain rebuild under
   "No optimization", and under BOTH optimize modes
   GraphEditor.gizmoReplace (D254's live path — re-place at the current
   / held length, NEVER applyOptimalLength's zero-distance prompt
   mid-drag; under optimize-orientation applyBridgeGap's alignYaw
   re-aligns the free axes around the held value just written, the D249
   typed-held-value semantics). The refusal belt is
   `!axisEditable(axis)` (was: optimize-orientation refused outright,
   then `!axisLocked`). gizmoReplace's at-rest knee passes `alignYaw =
   optMode == OPT_POS_YAW` to optimalBridgeLength (it was hard-wired
   `false` while "Optimize position" was its only caller). The
   LOCAL-aiming sink (bridgeLocal → gizmoSetBridgeOrientAll, a true
   rotation about the drawn axis) writes every EDITABLE axis's
   decomposed value — all three under the two non-aligning modes, so the
   object follows the ring exactly; the held ones only under
   optimize-orientation, where a free axis is the march's (the piece
   rig's locked-axis rule); it refuses only when no axis is editable (no
   ring could have fired it). One undo step per gesture (wireRingDrag's
   pushOnce), the gizmo contract. The D253 live knee hold rides along:
   under Optimize length a drag event re-places at lastOptDist and sets
   kneePending, the release's settle re-knees once. THE RING SET FOLLOWS
   THE MODE AND THE PADLOCKS LIVE: the gizmo signature already signed
   the trailing bridge's optMode, optLen and the three locks; every
   rebuild (rebuildMeshes → GraphEditor.refreshMarkers →
   refreshTrackMarkers → refreshGizmo) and every graph redraw re-reads
   it, the padlock buttons call refreshGizmos(false) explicitly, and
   setBridgeOptMode's two exits (applyBridgeGap → rebuildTrack; the
   manual entry's redraw()) both reach it — verified by reading, and the
   drill switches modes / flips locks through the real chokes
   (setBridgeOptMode / setBridgeLock under withBridge) and counts the
   rings after. A padlock flip under "No optimization" / "Optimize
   position" changes NOTHING now (no ring appears or vanishes — every
   axis wears one; setBridgeLock's doc said "it only changes which rings
   the bridge-end rig builds" — reworded); under optimize-orientation a
   flip re-marches (as before) and the ring set follows the held set.
   THE PIECE RIG'S BRIDGED-IN BRANCH (buildPieceGizmo with a bridge
   leading into the piece — unreachable through the balls since D247,
   which gives a bridged-in piece no entry ball, kept consistent): a
   ring per `into.axisEditable(k)` — under the manual mode the early
   return admits, every ring builds. TOOLTIP: BRIDGE_LOCK_TIP, the
   padlocks' hover text, was three lines explaining both states'
   mechanics (D249) → ONE sentence per the rule re-asserted in §3:
   "Locked holds this axis at the typed angle; unlocked lets Optimize
   position and orientation align it." BRIDGE_ALIGNS_AXIS (the grayed
   box's reason) is unchanged, one sentence; no other tooltip touched,
   none added. SURFACES THAT STATE THE RULE: only comments and the docs
   — BridgeSection's lock doc, Viewport3D.buildBridgeGizmo /
   buildPieceGizmo / bridgeLocal docs, GraphEditor.gizmoSetBridgeOrient
   / gizmoSetBridgeOrientAll / gizmoReplace / setBridgeLock docs,
   TrackPiece's uiLock* doc, CLAUDE.md and Help. The Track Piece
   window's "Edit with gizmo" checks show the RUN HEAD (D255) — never a
   bridged-in piece — so no junction-named reason exists there any more
   (D255 deleted gizmoWhy); nothing to change. MEASURED (the
   -Dfvd.bridgeringprobe=1 drill, on the default document + a 1 s hold
   bridged 14 m past the end under "No optimization"): ring sets NONE
   7/7/7/7 (defaults, yaw locked, pitch freed, defaults), POS 7/7,
   POS_YAW 6 (defaults) / 7 (yaw locked) / 5 (pitch freed) / 0 (all
   free) / 7 (all held) / 6, back to NONE 7 — bits yaw 1 / pitch 2 /
   roll 4. A Global yaw-ring drag under NONE through the pane filter
   turned the next piece −68.6219° → 7.7574°, one undo step (5 → 6),
   Ctrl+Z bitwise back; typing the same heading into the bridge window
   landed the same yawDeg BITWISE and the same compiled positions (fold
   b01a1d64bede6677 both ways). The same under POS with Optimize length
   off: yawDeg bitwise, placement delta 0.00e+00 m, positions fold
   3071def4ee884cd1 both ways. POS + Optimize length (the knee at the
   120 m ladder cap on this fixture): mid-drag kneePending set and
   lastOptDist 120.0 held, cleared by the release settle (D253).
   POS_YAW: no yaw ring to drag (code 0); the held pitch ring wrote
   0.0000° → 76.3800° with no conflict under LOCKSTEP, padlock + mode
   untouched; Local pitch ring 0.2175° → −76.1559°. Local yaw ring under
   NONE: turned 7.7574° → 84.1451° with pitch / roll written through the
   combined path staying put (0.00e+00 / 0.00e+00). With the bridge
   window and the Track Piece window open a drag re-synced the
   next-piece yaw box (−26.8606 == −dispYaw) and never moved the run
   head. -Dfvd.pickprobe=1 unchanged (fails=0). FILES:
   BridgeSection.java (comments), Viewport3D.java (buildBridgeGizmo's
   ring gating + doc, buildPieceGizmo's bridged-in branch + doc,
   bridgeLocal's doc, the probe helpers probeBridgeRings /
   probeBridgeRingDrag), GraphEditor.java (gizmoSetBridgeOrient,
   gizmoReplace, gizmoSetBridgeOrientAll, setBridgeLock's doc),
   FvdDesigner.java (BRIDGE_LOCK_TIP; the hook: bridgeRingProbe /
   bridgeRingFixture / bridgeRingChecksum / bridgeRingBs /
   bridgeRingSteps), TrackPiece.java (the uiLock* doc sentence). No
   model / compile / IO code changed; gates DetermGate197 6,
   DetermGate198 4, LabelGate 182, PlacementGate 41 (it pins
   axisEditable's truth table, h0 — unchanged). DEVIATIONS: (1) the spec
   names the two non-aligning modes; the implementation is the one
   predicate the boxes already gray on, so optimize-orientation's HELD
   axes gain rings too (their boxes are typeable — "only if the value in
   the box is grayed out should you hide its respective gizmo", the
   user's own restatement; D249 showed none there). (2) The drill's
   fixture is built in the model (Coaster.continuation +
   BridgeSection.solve, the '+' menu's recipe) rather than through a
   file. (3) PlacementGate (41) was run as an extra belt.

5. THE HELD-CONTROL KNIFE EDGE IS CLOSED (his answer to D255's open
   decision 1 and D256 §9 (1), verbatim: "For the number one of the
   batch-37 questions, please implement the 'one-line fix'. I don't care
   about the main coaster any more as it was already messed up from the
   lack of saving determinism back then"). RESOLVED by his request: D255
   §11's "a held speed control can lose its hold" and the CLAUDE.md
   Known-quirks bullet "A HELD SPEED CONTROL CAN LOSE ITS HOLD … Never
   change it without him" are retired (the bullet is a short "fixed in
   D257" note now), and batch-37 open decision 1 (D256 §9 (1), "NO
   change") is closed. WHAT: an ACCEL speed control that has reached its
   target now holds it on every step until the flags release it — the
   design solve and the real march alike. WHY: SpeedControl.snapCore
   decided "the step crossed the target" by the sign of (vB − vt)(vA −
   vt). After a snap the recorded speed is sqrt(E − loss) with loss +=
   vA² − vt², i.e. the target ± one rounding — MEASURED over his 54
   documents (SnapProbe, read-only): of the 102 ACCEL-governed samples
   sitting at a target, 86 were exactly on it and 16 ONE ulp off (max |v
   − vt| = 7.1e-15 m/s); the nearest genuinely off-target sample was
   3.07e-3 m/s away. Whenever that one ulp fell on the drift's side the
   product read positive, "not crossed", and the hold was dropped: a
   strong control re-caught it in 1-3 samples (the 0.03 m/s dips), a
   control weaker than the slope it held against never did. Six design +
   six real leaks lived in his files: "B Shortcut not working
   7.28.26.fvd" (3, the sample after the first leak 0.027 m/s off) and
   "FULL TEST COASTER 7.30.26.fvd" (3, 0.083 m/s off). MECHANISM — the
   one line, in snapCore's ACCEL branch: `boolean atTarget = Math.abs(vB
   - vt) <= EPS;` then `boolean crossed = atTarget || (vB - vt) * (vA -
   vt) <= 0;`. EPS = 1e-9 is the class's own band (driveCore's "at the
   target"), six orders above the rounding and six below the first real
   miss, so the drive and the snap now mean the same thing by "at the
   target". Old and new differ ONLY where 0 < |vB − vt| ≤ 1e-9: at exact
   equality the old product was already 0 ≤ 0, beyond the band both read
   the sign test. The SPEED branch is untouched (no sign test there),
   tied / untied magnitudes never enter the snap, a non-positive or
   coasting magnitude still governs only the approach (the documented
   contract: "at the target the control holds it exactly until the flags
   release it"), min-only / max-only keep their floor / ceiling
   semantics (an at-target step whose drift violates no flag still runs
   free — 55l), both flags off never holds, a stalled train is never at
   a target above 0.5 (the floored 0.5 == 0.5 case was already crossed),
   and every caller takes it through the one function: FVDIntegrator
   (time + distance), GeoIntegrator (time + distance), ScIntegrator (per
   leg), TrackCompiler.passD (snapRow) and the convert re-time march
   (Coaster.fitLanes' srcCtl.snap) — design and real now flip no coin at
   all, so inside conflicted, tilted or blended stretches the two
   marches agree where they used to disagree by up to 1 m/s
   (unconflicted track already recorded the design's side under D255's
   clean-span rule). The enumeration (25 states) is in the batch-39
   scratchpad's snap-fix-enumeration.md. MEASURED ON HIS FILES
   (gates/probes/cleanspan-2026-09-30, CleanProbe2 + compare3.py, old =
   the recorded batch-38 dump byte for byte, md5
   480e44f5d035815050b8f5ab601a44da; new = THE RECORDED DUMP FROM NOW
   ON, md5 8cbcaaa492250cf4a54a36da14df7bc4): 54 documents, 2 moved,
   everything else bitwise — including the 18 synthetic fixtures and the
   two documents whose ACCEL controls never reach their target
   ("Multiple Strict Seam Testing 9.22.26.fvd", "UVD AI made coaster
   10.10.26.uvd"). "B Shortcut not working 7.28.26.fvd": max geometry
   shift 1.471e-3 m (the end 1.471e-3), vDesign ×2 — piece 0 section 1
   (geo) 40.195592 → 40.195689 (+9.7e-5), section 2 (force) 31.039580 →
   31.039798 (+2.2e-4). "FULL TEST COASTER 7.30.26.fvd" (his main test
   coaster, the one he wrote off): max shift 1.380e+3 m (the end
   1.362e+3), samples 7423 → 7398, track 1217.5 → 2491.1 m, the geometry
   diverging at sample 1514 (129.8 m of arc — inside its first held
   control, span 4), vDesign ×9 on piece 0: s5 17.278904 → 17.653984, s6
   12.242617 → 12.763686, s7 12.397273 → 12.908361, s8 14.348110 →
   14.790329, s9 18.119970 → 18.551638, s10 18.737818 → 20.975766, s11
   14.527390 → 17.210115, s12 22.178156 → 19.048021, s13 14.964333 →
   11.004509 (piece 1 untouched); the real march behind them moves by up
   to 1e2 m/s (spans 13-16) — the batch-37 note's "1.4 km" and "1.5 mm"
   confirmed. After the fix the probe finds ZERO leaks (held samples 26
   → 29 and 76 → 143; the at-target residual still 0-1 ulp, max
   3.6e-15). COST: a document with held ACCEL controls re-settles ONCE
   on first open (its saved vDesign values were anchored on the leaky
   solve): DetermGate197 reports 14 reconcile passes on the main coaster
   where it reported 0, still "well under the cap", and every
   determinism check stays green; files without held ACCEL controls open
   bitwise. THE PIN: Phase3Gate 55k — a geo DIVE (pitch −50 °/s for 1 s,
   then straight at ~55°; 90 m start, 10 m/s) with an ACCEL control
   (target 20, 0.5 g tied, both flags) from 1.5 s to the 5 s end — the
   natural gain g·sin 55° ≈ 0.82 g beats the 0.5 g brake, so a dropped
   hold can never be regained. On the OLD build: snapped at sample 166
   (t = 1.66 s), then 312 of the 333 governed samples off target, worst
   9.8 m/s, end 29.845 instead of 20 (target 22 leaked 155 samples / 4.9
   m/s; target 25 happened to hold — the coin). On the fixed build: 0 of
   334 off target in BOTH the design samples and the real trueV, 47 held
   samples one ulp ABOVE the target (the very steps the old sign test
   called "not crossed" — the knife edge is present in the fixture's own
   numbers) and 287 exactly on it, end 20.000000000. 55l: the same
   fixture min-only reaches 20 and runs free to 46.831 (design == real)
   — no phantom hold from the fix. RE-BASELINE (the only gate whose
   recorded expectation moved): Nl2ElemProbe reads "FULL TEST COASTER
   7.30.26.fvd", whose far end now runs 1.3 km out. The .nl2elem
   writer's "%e" carries 7 significant digits, so its element-local
   coordinates can agree with the CSV only to that FORMAT FLOOR: q =
   1e-5 m under the old 99 m extent (worst 1.5e-5, chords 1.2e-5 — the
   recorded 5e-4 bound was never a design value), q = 1e-3 m at the new
   1283 m (worst 5.4e-4, dy 5.0e-4, chords 8.9e-4 — the old bound failed
   by a hair on seven checks). The three position checks now take tol =
   2q + 1e-5 at the measured extent (3.0e-5 on the old geometry, 2.0e-3
   on the new; the unit-vector checks keep 5e-4). The D202 jitter pin
   was content-dominated as well: after the fix the 367 m bank-node kink
   the probe's own comment already called "real authored content" reads
   1.2 rad/m (was 0.15) because every speed past 130 m moved, lifting
   the whole-track rms to 0.0134 (0.0131 with the %e rounding taken out)
   with the exporter untouched — every other 100 m bucket of the new
   track reads ≤ 0.0012. The pin now reads the SHARED resample through
   the CSV's %.6f text (the %e quantum alone adds 0.003 rms at 1.3 km)
   and takes the 1 %-TRIMMED rms — sparse authored kinks drop out,
   broad-band chord-sag aliasing stays in: Hermite 0.00021 on the old
   geometry / 0.00013 on the new against a LERP twin of the same samples
   at 0.00430 / 0.00504 (ratios 0.048 / 0.027; bounds 0.001 and a
   quarter of the lerp's), the user's 935 m witness from the same
   resample 0.00035 / 0.00006 (bound 0.002 kept). The re-pinned probe
   passes 22/22 on BOTH builds. Nothing else moved: 35 members identical
   PASS/FAIL counts, printed numbers only in DetermGate197 (14 passes,
   rigid 1.2e-13 m), DetermGate200 (timings) and LoadGate196 (7422
   samples, was 7423). FILES: SpeedControl.java (snapCore's ACCEL
   branch: `atTarget` + the comment; the class doc's PHYSICS bullet),
   TrackCompiler.java (COMMENT ONLY, the review's should-fix: passD's
   clean-span doc comment reads "held speed controls, before D257, lost
   their hold at different samples" — it said "lose", a closed knife
   edge read as live), gates/Phase3Gate.java (55k + 55l + the shared
   diveFixture helper, 86 → 88 PASS lines), gates/Nl2ElemProbe.java
   (re-pinned, 22 checks: format-floor tolerances on the three position
   checks, the trimmed-rms jitter pin, hfNoise / trimmedRms). STANDING:
   gates/probes/cleanspan-2026-09-30's recorded dump is batch 39's
   new.txt from here on (the scratch snapfix/ folder holds old.txt,
   new.txt, compare3.txt, secdelta.txt, the probes and the frozen class
   copies). DEVIATIONS: none from the request (the fix is the one line
   he named).

6. THE BATTERY (wiped classes, every rc 0). 36 members since D256, none
   added: SmoothRealGate 24 (was 77 — section d = d1 the recorded
   closure checksums + d2 the make-real round trip), LabelGate 182 (was
   190 — section 17 = 13 checks), Phase3Gate "ALL PHASE-3 GATES PASS"
   (88 PASS lines, was 86 — 55k/55l), Nl2ElemProbe re-pinned (22 checks,
   count unchanged); uvd.ConflictGate 87, DetermGate197/198/199/200
   6/4/10/7 (DetermGate197 now reports 14 reconcile passes on the main
   coaster), LoadGate196 7 (7422 samples, was 7423), uvd.SeamShapeGate
   31, CopyGate 73, MirrorGate 100, RangeResolveGate 115,
   MultiCoasterGate 15, PlacementGate 41, uvd.KeyRouteGate 103,
   TypedMarkGate 42, InsertGate 40, ReanchorGate 3, GestureGate 180,
   Phase2Gate 162, MergeGate 23, InflSplitGate 22 unchanged. In-JVM
   hooks on the same build: -Dfvd.smoothprobe=<conflict.uvd> fails=0 (28
   checks), -Dfvd.initspeedprobe=<conflict.uvd> fails=0 (39 checks),
   -Dfvd.bridgeringprobe=1 fails=0 (41 PASS lines), -Dfvd.pickprobe=1
   fails=0 (unchanged), -Dfvd.winshot the three "WINSHOT povrow" lines
   (1127.3 / 1197.3 / 1197.3 px, "design hidden / hidden / shown", every
   one "fits", no MISSING line) + "trackpiece rows identical=true pixels
   equal=true" + the window PNGs byte-identical to batch 38's,
   -Dfvd.povprobe=3 146 pulses clean. The clean-span dump:
   byte-identical over 54 documents after §3, then §5's two moved files
   (new.txt is the recorded dump). The docs integrator's belt on the
   merged tree: LabelGate, uvd.ConflictGate and SmoothRealGate re-run on
   wiped classes (TESTING #174 carries the lines).

## D258 (v19.29) — Batch 39, round 2: the seam band stays white under a slight "~v", and the init-speed readout is sized for three digits

The last two changes of the 2026-10-13 session, both user spec, both
UI-only (no compile / reconcile / IO path touched; DetermGates not
implicated).

1. THE SMOOTHING BAND TINTS RED ONLY FOR A FULL "≠v". His words: "Only
   make smoothing window turn red if a speed conflict large enough to
   have a 'not equals' sign is present [still white if just a '~v']".
   Batch 38 (D256 §1) had keyed GraphEditor.smoothingCausedDrift on the
   three-state's level ≥ 1 so the red band and the badge's D141 hint
   agreed with the drawn glyph — which made every slightly-conflicted
   smoothed follower's band red. Now: the pre-check reads
   driftLevel(p, 0) == 2 (a full ≠v only — a " ~v" follower keeps a
   white band and its resolve submenu offers the ops like any other
   "~v"), and the window-at-zero test is the historic one again
   (SectionNames.speedLevel(split) < 2: a residual that would still
   read "≠v" with the window at zero is the seam's own, anything less
   counts as caused by the smoothing — exactly the pre-batch-38 ≤ 0.2
   rule). The D141 hint line and the init-speed window's smoothing
   sentence follow the same predicate (unchanged code paths).
   Consequence stated in CLAUDE.md: a smoothed seam whose suspended
   residual is itself a slight (0.005-0.2) gap is "caused by smoothing"
   again, as it was before batch 38.

2. THE INIT-SPEED READOUT IS SIZED FOR "real 999.99". His words: "the
   button for the real readout appears to stick out too far to the
   right. If this is to compensate for if the value gets very large, it
   really should only compensate for up to three digits + two decimals
   [if it were to go beyond that, aka 1000 m/s it'd be okay if the text
   spilled out beyond the button or got cut off as that's extreme]".
   The fixed slot (D254's Readout width rule, kept while shown since
   D257 §2) was measured on Gestures.realSpeedText(INIT_SPEED_MAX) =
   "real 10000.00"; it now measures realSpeedText(999.99). A real speed
   of 1000 m/s or more is ellipsized inside the slot (the Label's max
   width stays fixed) — accepted, nobody rides that. Measured on the
   batch-38 fixture (-Dfvd.initspeedprobe): the window is 276 px wide
   without a conflict and 352 px with one (was 365.3), Cancel's right
   edge 262.0 == the box's right edge in the narrow state, every one
   of the probe's 39 checks green; the three WINSHOT povrow lines
   unchanged (1127.3 / 1197.3 / 1197.3 px, all "fits").

Verification: wiped rebuild rc 0, gates javac rc 0; LabelGate 182,
uvd.ConflictGate 87; -Dfvd.initspeedprobe fails=0 (39), -Dfvd.winshot
the three povrow lines "fits" + "trackpiece rows identical=true".
Committed together with D252-D257 (the first commit since v19.22).

## D259 (v20.0–) — Phase 4 item 1: the straight/curve overhaul — curvature-defined Straight / Planar / Helical elements on a roll-free axis, roll as a graph lane, a strict seam at every s/c edge, element nodes + window + locks, Append Element mode, element interactions, attach and loop closure

His spec of 2026-10-19, analysed in docs/SC-OVERHAUL-PLAN.md (the
organized summary, the C1-C32 contradictions, the R1-R22
recommendations, the eight-stage plan); his answers of 2026-10-20 to
that document's §0 are recorded below and in the design contract
docs/SC-OVERHAUL-DESIGN.md §0. One entry for the whole arc,
sub-numbered per stage run (D259.0, .1a, .1b, .2a, .2b, .3a, .3b, .4,
.5, .6, .7), each in the D257 shape: prose, his words quoted,
BEFORE / NOW, accepted corners, Verification. Supersedes D68's
body-frame curve axis, D69 (per-element roll), D232 §6 / D233 §6-8 /
D234 (the section-entry roll ease) and D135's "a follower's first
section is never reconciled" for every follower whose first section
is real-anchored — the automatic and border-split seams the invariant
creates, and a hand-made follower a resolve op had already flagged
(design doc §3.5 A4). Old-file compatibility for s/c sections is NOT
promised (his words: "Right now I don't care about the compatibility
of any of my old files [or the old s/c features] at all … purely a
placeholder … Do what you wish.") — old `elem` lines load best-effort
with one warning; every other section kind stays bitwise.

Decided (his §0 answers; "default" = the plan's stated default,
quoted where he added words): D1 bounded, bracketed, hard-capped
INTERNAL solves with an honest refusal on non-convergence are allowed
anywhere — the USER never iterates, closed form first ("if there
aren't issues with using iteration in the code for these features
please go ahead with what you have in mind"). D2 the roll-free axis,
fixed at each element's entry against the level frame (helix: world
Y) — "yes, geometry independent of roll". D3 default roll = global
rate 0 °/m; B as on force/geo lanes; an s/c start follows every seam
axis by default, the strict seam lets the user misalign and/or add
smoothing, which works force/geo → s/c, s/c → s/c and s/c → force/geo
exactly as between two lane sections. D4 (REVISED 2026-10-21 on his notes) a roll lane PER ELEMENT on its
own [0, L] axis with its own axis / units / behavior; a longer element
holds the end node's value, a shorter one CUTS the lane with the
insert-node math and keeps the cut-off part, which returns bitwise
unless the cut node was moved (design doc §2, rewritten as Stage 1b's
step 0). D5 automatic seams
all-unlocked, the follower's first section real-anchored and
reconciled at k == 0, generated AT the follow contract. D6 helix =
plan radius + end elevation, LINEAR over track arc. D7/D8 old files
best-effort, the file grammar free. D30 no roll domain (the time VIEW
stays). D31 a strict seam at EVERY s/c edge — "Actually also include
a strict seam at end too". D32 Reverse grays on multi-piece runs.
D9 sharp by default — new elements and the invariant's seams start
without smoothing; the diamonds add it. D10 no lap cap. D11 Append
Element on any active s/c at the section end, Esc leaves it. D12
Shift/Ctrl planes on the hover ghost only. D13 helix defaults R 20 m,
90°, radii linked, pitch unlinked. D14 view-plane node drags. D15
drags RESTRICT, typed values REFUSE only when unreachable ("values in
other boxes that are not locked should be allowed to change
naturally"). D16 active-chart padlocks + a bounded golden-section
projection. D17 the third global number = arc length. D18 click
activates, double-click opens the window. D19 a tangent arrow + an
end-radius arrow, no translation arrows. D20 an element chart flag,
default Local. D21 B / Shift+B on a roll node = the roll lane only;
B on the last element's end node = the backward orientation solve;
the strict-seam menu after an s/c carries "Align previous section to
this seam". D22 loop OPEN re-measured per compile, Break the only
deletion. D23 a closed-tail append opens the link with a sentence.
D24 moot under the revised D4 (every element keeps its own lane; the
flag-run stage is void). D25 the element
blend law kept, feel-tested later — and NO roll-rate jolt at element
blends (today's global-bank ease starts at the element start while
the curvature blend is centered on the border, "it stays at zero roll
rate for a split second"), gated. D26 change type in place keeping
arc length; straight ↔ curve both ways ("straight -> curve makes the
curve have zero curvature"). D27 passive END balls on run tails.
D28 the loop target = the REAL drawn start, solved in the tail's
design frame. D29 per-type Resulting pick sets (s/c: Gv + Gl on).
D33 R15's attach labels. Deviation stated: the element window's
blend, interpolation and link controls carry no padlock — they are
not solved quantities (his spec asked for a lock on every option).
Process: the session stays on Fable 5.1, Opus 5.5 subagents take the
mechanical sub-tasks under exact briefs with a Fable review ("aim to
reduce cost while maintaining same level of quality").

D259.0 (Stage 0, no build) — the decisions above and the design
contract. No code. docs/SC-OVERHAUL-DESIGN.md fixes the element model
and file grammar (§1), the roll lane and its flag runs (§2), the seam
invariant and its lock defaults (§3), the solver with its DOF/lock
table and refusal register (§4), Append Element mode and the ghost
(§5), the element window and rigs (§6), the list ops (§7), attach and
loop closure (§8), the per-type Resulting sets, colors and Help (§9),
the gate register, docs plan, reuse map and stage briefs (§10). The
document was assembled from ten drafted sections and two consistency
reviews (92 findings, every one resolved in a fix sheet — the owner
of each duplicated rule named, every refusal sentence a single
constant, one cross-section numbering). Verification: the battery
baseline the stages re-run against is the one recorded at bfc70fd —
DetermGate197-200 6/4/10/7, Phase2Gate 162, Phase3Gate 88,
SmoothRealGate 24, MirrorGate 100, CopyGate 73, InsertGate 40,
uvd.ConflictGate 87, LabelGate 182, GestureGate 180,
uvd.SeamShapeGate 31, RunGate 78, RangeResolveGate 115,
PlacementGate 41, uvd.KeyRouteGate 103, TypedMarkGate 42; Stage 1a
re-records ScElemGate h's legacy-axis checksums on the unmodified
build before its first edit.

His notes of 2026-10-21 on the design doc (binding, folded into §0 /
§2 / §6.5 / §8.1 / §8.5): D4 REVISED — roll is PER ELEMENT ("I think I
want the element boundaries to behave the same way as simple section
boundaries do for force/geo sections … if changes to the roll graphs or
lengths of elements outside of a given element are made … the roll
graphs within the element do not shift or change"), each element with
its own lane and settings; a longer element leaves the end roll node in
place and holds its value as the dotted continuation; a shorter one
CUTS the lane at the new end with the insert-node math and keeps the
cut-off part, which returns bitwise when the element grows again unless
the cut node's position or slope changed meanwhile (the one-lane +
flag-run model and D24's Combine refusal are void; §2 is rewritten as
Stage 1b's step 0). The run tail's passive END marker is a CONE, not a
white ball ("differentiate it from the start node"). The radial arrow
points outward and its radius change follows mouse travel, never the
arrow's length, with no oddity near 360°. A lone s/c track piece stays
reversible. The speed across a loop boundary is independent by design
("there would be possibilities of positive feedback loops"). Then:
"please begin Stage 1a!"

His second note of 2026-10-21, after Stage 1a landed: "The five Stage 1b recommendations
stand." — the design doc's §2 was then REWRITTEN as Stage 1b's step 0 (the lane per
element: uvd.RollLane with its own axis / units / behavior; the END NODE's cut / shadow
rule E1-E6 — a shorter element cuts the lane by the exact de Casteljau split and keeps
the cut-off part as a persisted shadow that returns bitwise while the cut node's position,
value and handle slopes are untouched, interior edits surviving the return; the per-element
bake on an element-anchored grid with the bank re-binding at every border; the merge rule's
lane clause; the dialog's Roll block with Switch / Bake; B toward the neighbor element, or
the neighbor section across the strict seam), D24 declared moot and Stage 7 (flag runs)
void, the Resulting pick sets moved to Stage 3a, the Stage 1b brief + enumeration written
into §10.6 and a cloud-session rule (§10.7 rule 8: pure-Java items with gate-row
acceptance on a committed tree; UI, hand tests and commits stay local) added.

D259.1a (v20.0) — STAGE 1a, THE GEOMETRY CORE (design doc §1; two
Opus items O1 DesignIO + O4 editor/ScLook, an Opus-written ScElemGate
O3, the integrator / ElementChain / Coaster / TrackCompiler by hand).
BEFORE: an element was a straight (length) or an arc (radius, signed
angle, direction) whose rotation axis was read off the ROLLED body
frame per RK4 stage (cos d·U + sin d·R) — the shape followed the bank:
a banked R20/90°/30° turn dove 10.9 m and GLOBAL-0 turns were exact only
at dir 0/90 from a level entry (2.08 m off at dir 30, PLAN §0 item 2).
NOW: Element = {kind STRAIGHT | PLANAR | HELICAL, L, k0 → k1 (kLink,
kInterp LINEAR | SINUSOIDAL over the TRACK arc), dirDeg, b1Deg (bLink,
bInterp), blendIn, locks} — radius and turn are DERIVED twins; every
element rotates about a ROLL-FREE axis fixed at its ENTRY against the
LEVEL frame (D2): PLANAR Ω/m = −k·a, a = cos d·ul + sin d·rl; HELICAL
Ω/m = −c_h·cos β·Y + β'·h(T); the body roll never bends the shape, which
is gravity-referenced through the entry's level frame like a force
section's (a baked pitch reshapes it — RangeResolveGate's g2 hinge
became a DISTANCE °/m geo section for that reason). ElementChain is the
closed-form forward map (the exact arc; s_h = L·cos β̄·sinc(Δβ/2), Δy =
L·sin β̄·sinc(Δβ/2) for the helix; a fixed GL-8 quadrature for ramped
κ / sinusoidal pitch — the SINUSOIDAL-pitch helix goes through the
quadrature too, the design doc's J0 series was not needed for the
forward map). ScIntegrator lands every RK4 leg on EVERY non-merged
border (its centre too under a blend — the per-border Bound record now
on Span.scBounds), on blend edges and on the end, caps the on-side
lookup at KINKS only, and MERGES adjacent sharp equal-κ equal-axis
elements into one leg run with the ARC-SUM rule (start + (L1 + L2 + …))
so a split that keeps L1 + L2 == L bitwise (Coaster.cutElement nudges L1
by ulps, re-bases the right half's dir into its own entry level frame)
compiles BITWISE the parent (ScElemGate c). The HELICAL merge test
carries a 1e-12 rad band: O3 found a linked helix's re-derived entry
elevation one ulp off its stored β1 at a 20° entry, which made every
pitched split a kink. The pre-v20 law survives this ONE stage behind
the static final -Dfvd.sc.legacyAxis=1 (the flag reads "1" or "true";
Boolean.getBoolean would have read "=1" as false — O3 caught it), pinned
BITWISE against gates/probes/legacy-axis-2026-10-21/legacy-checksums.txt
(56 lines recorded on bfc70fd before the first edit); chains holding a
helix ignore the switch. FILE: `el straight|planar|helical` named-token
lines (every double through d(), one positional kind word, unknown names
ignored, missing names default — the line can grow), the Stage-1a
INTERIM rm=/rv= roll fields on each; old `elem` lines load BEST-EFFORT
(D7/D8 — his "I don't care about the compatibility"): converted to the
κ-form, counted once per section into LoadResult.legacyScSections, the
load report gains SectionNames.SC_LEGACY_CONVERTED ("Old straight/curve
elements were converted."), the document dirty; no bitwise promise (the
migrated turn k·L vs toRadians(a) ~1e-13). EDITOR (O4): bands in the kind
colors ScLook.KIND_RGB (straight (120,185,235), planar (235,130,180),
helical (200,130,235) — FvdDesigner.SC_KIND_COLOR), the chip = the
stroked kind icon (ScLook.iconStroke/iconHead per §9.6; the head tangent
is the curve's exact end tangent, the chord missed §9.6's tips) + the arc
length — no radius/angle/roll suffix; SC_SMOOTH_DEFAULT 0 (D9 sharp); the
element dialog keeps its radius + angle + direction + entry smoothing +
roll rows, mapped onto κ/L through Element.setRadiusAndTurn (a helical
element shows as "Curve" there until the Stage-3a window — accepted).
INTERIM, by the stage scope (§10.6): the v11.1 per-element RATE / GLOBAL
roll pair still rides each element and element 0's blendIn is still the
section-entry roll ease (D233); Stage 1b replaces both with the
per-element roll LANE of D4 as revised 2026-10-21 and the seam invariant.
Help: unchanged in 1a — no new user control (the chip's icon and the band
colors are the only visible change). Accepted corners: the blended-border
centre landing changes the RK4 step phases — Phase2Gate i's clamped-blend
heading reads 2.96e-6° at dt 0.01 (exactly 0 at dt ≤ 0.005, BlendTurnProbe;
the legacy switch keeps today's 0), bound re-pinned 1e-6° → 1e-5°; a
partial convert of a TILTED piece's later s/c section re-bases the right
half's dir off the first own sample tilted back (one dt inside the entry,
dust); a merged border's Bound carries the roll-free closed-form advance
(landed = false) — informational; ElementChain.turnDeg wraps a
non-closed-form helix's plan turn to ±180 (D10 laps live in L).
Verification: wiped `mvn compile` rc 0; `javac -encoding UTF-8 gates/*.java`
rc 0; LegacyAxisProbe ON == recorded (0 differing lines), OFF differs on
the 42 non-level/non-cardinal fixtures as expected; uvd.ScElemGate 122
passed; the whole battery green on wiped classes 2026-10-21: DetermGate197-
200 6/4/10/7, LoadGate196 7, OrientGate 23, OrientGate2 18, Phase2Gate rc 0,
DomainGate rc 0, Phase3Gate 88, MultiCoasterGate 15, SmoothRealGate 24
(d1 28808918c0612fbe unchanged), uvd.KeyRouteGate 103, TypedMarkGate 42,
InflSplitGate 22, ReanchorGate 3, MirrorGate 100, YpAbsGate 22,
uvd.SeamShapeGate 31, RangeResolveGate 115, CopyGate 73, MergeGate 23,
InsertGate 40, LabelGate 182, uvd.PulseClockGate 30, RunGate 78,
PlacementGate 41, OrientModeGate 51, GestureGate 180, EndFollowGate 73,
FloorGate 34, StripGate 49, uvd.ConflictGate 87, Nl2ElemProbe 22 (TESTING
#176/1a). Hand-tested by him: nothing yet.

D259.1b (v20.1) — STAGE 1b, THE ROLL LANE PER ELEMENT + THE SEAM
INVARIANT (design doc §2 as rewritten 2026-10-21 and §3; four runs —
run 1 the model + integrator (cloud, branch sc-1b-core, merged
5cab6ff), run 2 the editor (local, 3359d3e), run 3 the seam invariant's
model (cloud, branch sc-1b-seams, merged 051d6db) + its local half
(62931e8), run 4 his hand-test notes + these docs). WHAT: every
straight/curve ELEMENT carries its OWN roll lane (uvd.RollLane — nodes
on [0, L] in meters, its own axis / rate units / behavior with the lane
sections' interlocks, the default a global 0 °/m hold; the interim v11.1
RATE/GLOBAL pair and the D233 entry ease are GONE, old `elem` lines
migrate best-effort into the lane, the `el … rollg= ratem= rollabs=` +
`rn`/`rsh`/`rcut` grammar persists lane, shadow and cut fingerprint);
the integrator bakes each lane on an element-anchored grid, carries the
twist angle in the RK4 state about T (the spine stays roll-free and
bitwise), snaps an ABSOLUTE lane per leg and re-binds the bank at every
landing border; the LEGACY_BODY_AXIS switch of 1a is retired. THE CUT /
SHADOW RULE (D4 revised): a shorter element CUTS the lane with the
insert-node de Casteljau split and keeps the cut-off part as the SHADOW
(the virtual lane, persisted) that returns when the element grows —
BITWISE while the segment into the cut node is untouched, and since his
2026-10-21 note 1 a changed segment (the node before the cut edited, a
node inserted between) KEEPS the cut node as a real node and continues
the old graph past it through the shadow's right half, so the drawn
graph over [0, L] never changes on growth (run 2's first rule restored
the previous node's handle at the shadow's slope and dropped the cut
node — the curve changed, his report; ScRollLaneGate e7/e10). THE
EDITOR: an s/c binds the LANE ELEMENT's roll lane (sticky — selecting
an element or clicking another element's graph switches it; the other
elements' lanes draw dimmed), the element's L is a HARD wall, the cut
stub, B / Shift+B at element borders (authored when the settings
match, sampled otherwise — the analytic edge slopes since his note 2:
BezierBaker.slopeAt at the first / last knot read 0 or blew up, so B
leveled the handle), mode-3 lane buttons, the s/c rate view; THREE
SETTINGS BUTTONS since his note 3 — the S/C badge (the section), the
element chip at the band's top-left (the element dialog: kind / length
/ radius / angle / direction / entry smoothing) and the roll button
beside it (the Roll dialog: axis / units / behavior + Switch / Bake);
the band's double-click opens nothing, the band's right-click menu
stays for now (slated for removal); a band PRESS also starts a BOX
SELECT of the lane's roll nodes (note 5); a rate-unit Switch between
two rate settings CONVERTS the values literally — every node value and
handle offset × v at the node's arc, or ÷ v, the shadow along (note
6; the axis / behavior flips still reinterpret, a bank lane never
converts, D213). THE SEAM INVARIANT (D5/D31; §3): a straight/curve
section is ALWAYS a piece's section 0 and the section after it the
next piece's section 0 — every s/c edge is a STRICT seam, AUTOMATIC
when the program cuts it (TrackPiece.sAuto, all axes following,
smoothing 0, the follower's first section REAL-ANCHORED so no phantom
≠v opens, LOCKSTEP): the '+' paths, Insert, a partial convert and the
loader's pass (Coaster.appendSectionWithInvariant /
insertSectionAtSeam / ensureScFollower / DesignIO.scInvariantLoadPass —
a pre-D259 file with an in-piece s/c loads cut, one sentence, dirty).
An AUTOMATIC follower generates AT the follow contract exactly like an
in-piece continuation (TrackCompiler's A1 branch: the upstream span's
exact canonical pair; behind a TILTED upstream span it continues the
DESIGN chain and INHERITS the tilt about its own entry — the run-3
review's answer to the notes' open question 2: the in-piece twin to
1e-9 m, frames / felt bitwise, ScSeamGate c4; the ≠o predicate's Span
form reads the inherited tilt), its stored placement is INERT until a
lock or a design delta turns it into a D135/D247 follower
(Coaster.freezeAutoFollower = the ONE prologue of every seam write — the
seam window, the mode row, Design ↔ Real, detach, the orphan guard of
Delete / Move / Switch Coaster / Copy); L4: such a seam cannot be
un-stricted (grayed: connect or convert the sections instead); the
automatic follower's first section is RECONCILED at k == 0 (A4 — the
D256 flag decides, never the lock state). Smoothing works on s/c edges
(SM1 — the D232 ban went with the entry ease; the window's DRY RUN
TrackCompiler.smoothEngagesWhy grays the Enable item, speaks on the
hollow diamond's hover, after a drag that left a non-engaging window
and in the Smoothing Options window); the seam ball sits ON the
junction (the upstream exit, not the follower's first sample one dt
inside); the seam window's "Align to previous end (B)" button (the
LOCKED axes := the previous section's end orientation, one shot,
through seamEdit; B / Shift+B with no node selected align the
highlighted seam — the revealed seam rig's or the open window's — else
B_NO_TARGET_WHY); the roll row grays under an absolute element-0 lane
(the lane's node 0 owns the entry bank; no roll ring); junctions count
s/c edges in their ordinals; POV follow + ride restart key on the RUN
(crossing a seam inside the run activates nothing); ties, combs and the
heartline strand anchor per RUN. CARETS AT KINKS (his note 7): an
AUTHORED instantaneous orientation change inside a run that is not a
strict seam — a plain boundary into a section with absolute lanes
(bank, elevation / heading), an element border into an absolute-bank
element — is declared by the compiler (TrackCompiler.Result.kinks,
KINK_MIN_DEG 2 on the frame jump), Pass A differences ONE-SIDED around
it (the central difference across an absolute border read ~3000 °/s —
the exploding roll rate), the editor draws the components' CARETS on
the boundary / border line (rate views only — a lane shown as an
absolute angle just steps) and breaks the resulting overlays there; a
strict seam's own carets include the bank snap of an absolute first
lane (analytic, smoothing never moves it). WHY the lane: his D4 as
revised — "element borders behave like plain section boundaries for
roll"; the cut/shadow so a resize never loses authored work; the
invariant so an s/c's seams are honest strict seams with one UI, and
the automatic follower so the user never sees a difference from an
in-piece continuation. ACCEPTED: the automatic follower's design speed
re-anchors on the real seam speed (A4) — his three pre-D259 files with
an in-piece s/c and UVD Test Coaster 10.16.26.uvd re-time their s/c on
first open (12.4 / 8.5 / 2.8 / 0.0007 m/s, every track end within
1e-6 m — the clean-span dump against the pre-run-3 tree); an s/c's
numeric integration moves ~1e-6 m with its design speed (RK4 step
phase); a conflict-tilted upstream's follower keeps its ≠o (the twin's
sections did); the kink test is a 2° frame jump at a structural
candidate (a continuous abs lane rolling faster than 200 °/s on its
first step would flag — a caret and a one-sided difference, harmless);
the strict-seam caret's analytic snap covers bank only (an absolute
elevation / heading first lane behind a seam reads the contract frame —
documented limit); the band's right-click menu lives on until its
functions move; value boxes with full spinner behavior wait for the
Stage-3a element window (his note 4). GATES: uvd.ScRollLaneGate 70 (a-n
+ e7/e10/e11 + q1-q4), uvd.ScSeamGate 37 (a-o + c4/c4b), LabelGate 229,
uvd.ConflictGate 90, InsertGate 45, RunGate 82, CopyGate 74,
PlacementGate / OrientModeGate re-pinned to the 6-field porient line,
DetermGate198 d / Phase2Gate scRoundTrip / ScElemGate g+k built through
the invariant; the whole 39-member battery green on wiped classes
2026-10-21; -Dfvd.scprobe=1 (21 checks), keyprobe, typedprobe,
initspeedprobe, smoothprobe fails=0. Hand-tested by him 2026-10-21: the
roll lane strip (his notes 1-7 — this entry); nothing of the seam
invariant yet (TESTING #176/1b).
