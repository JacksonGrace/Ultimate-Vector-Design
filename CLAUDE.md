# CLAUDE.md

## What this project is

An FVD-style roller coaster design tool (in the spirit of **FVD++ / Force Vector
Design**). The user does not draw track geometry directly. Instead they edit
three curves over **ride time**:

1. **Vertical G** (1.0 = normal gravity felt through the seat)
2. **Lateral G** (0 = no side force)
3. **Roll rate** (degrees per **second** of banking)

The software numerically integrates those force targets into a 3D track. This
is the core insight of the whole project: *the designer specifies what the
rider feels; the geometry falls out of the physics.*

Written in **Java 21 + JavaFX 21**, built with **Maven**.

## Build & run

```
mvn javafx:run        # build + launch the designer
mvn compile           # compile-only error check
mvn clean             # nuke target/ if the build gets into a weird state
```

- Main class: `coaster.FvdDesigner` (set in pom.xml's javafx-maven-plugin config).
- Sources live in `src/main/java/coaster/`. Every file starts with `package coaster;`.
- JavaFX comes from Maven (`org.openjfx:javafx-controls:21.0.2`) — no SDK
  download, no `--module-path` flags. Do NOT launch via a plain `java` command
  or IDE run button; only `mvn javafx:run` sets up the JavaFX modules.

## Source files & data flow

```
Vec3.java           immutable 3D vector record (add/sub/scale/dot/cross/
                    normalize + Rodrigues rotate). No dependencies.

BezierBaker.java    EDITOR MODEL + CURVE MATH. Pure Java, no JavaFX.
                    Three independent lanes of Node{t, val, handles, smooth}.
                    bake(lanes, 0.05) samples the per-lane cubic Beziers onto
                    a uniform time grid of linear Keyframes.

FVDIntegrator.java  PHYSICS. Pure Java, no JavaFX. Takes keyframes
                    (t, Gv, Gl, rollRateDeg), RK4 at dt=0.01s, produces
                    Samples (t, s, pos, T/U/R frame, v, Gv, Gl); also
                    bodyRates(samples) — resulting pitch/yaw/roll °/s
                    for the read-only graph overlay.

AppSettings.java    APP-WIDE PERSISTENT SETTINGS. Pure Java, no JavaFX.
                    Shortcut bindings + FOV in ~/.fvd-designer.properties
                    (combos are strings like "Ctrl+Z"; UI parses them).
                    Per-coaster settings do NOT go here — they go in .fvd.

Nl2CsvExporter.java NL2 EXPORT. Pure Java, no JavaFX. Resamples samples
                    onto NoLimits 2's external-track CSV (tab-separated
                    pos + front/left/up rows). Negates Z on the way out
                    (our rendered world is left-handed, NL2 right-handed
                    Y-up) — change signs only with DECISIONS.md D40 open.

FvdDesigner.java    ALL JavaFX. 3D view (two-rail mesh, orbit + POV cameras),
                    graph editor canvas, menu bar (File/Edit/Coaster),
                    toolbar, save/load/export, undo, shortcuts/options dialogs.
```

Pipeline on every edit: `lanes → BezierBaker.bake → FVDIntegrator.integrateTrack
→ rail meshes + stats`. This runs live on every mouse-drag event; it is fast
enough (a ~14s ride is ~270 keyframes and ~1350 integration steps).

**Deleted files:** `FvdViewer.java` and `TestCircle.java` were earlier
iterations using a distance-based integrator API. They must NOT be in the
source tree — they won't compile against the current time-domain API.

## The physics (short version — see docs/PHYSICS.md for derivations)

- Moving orthonormal frame: **T** (forward), **U** (up, defined by roll — not
  the Frenet normal), **R** (right). Convention: `R = U × T`, cyclic
  `T×R=U, R×U=T, U×T=R`.
- Time domain: angular velocity needed for a target force at speed v is
  `ω = g·(G_target − gravity_component)/v` (rad/s). Specifically
  `ωU = g(Gv − U.y)/v`, `ωR = g(Gl − R.y)/v`; roll rotates about T directly.
  Combined `Ω = ωR·U − ωU·R + rollRate·T`. Integration is classic RK4
  (v3, D44) on state (pos, T, U): `T' = Ω×T`, `U' = Ω×U`, `pos' = T·v`,
  frame re-orthonormalized each step, R = U×T derived; each RK4 stage
  re-normalizes its local frame copy. Speed is ALGEBRAIC (not ODE state),
  evaluated from energy wherever a stage needs it. Accuracy: analytic
  circle radial deviation < 1e-9 m (old per-step Rodrigues stepper:
  10 cm) at the same dt=0.01.
- Speed from energy conservation vs start height:
  `v = sqrt(v0² + 2g(y0 − y))`, then a crude friction bleed
  (`friction` option exists but is currently 0 / not exposed in UI).
- `vSafe = max(v, 0.5)` guards divide-by-zero near stalls.
- The integrated path **is the heartline** (forces are felt at the rider's
  heart). Rails are derived: `spine = heart − U·heartOffset`, rails at
  `spine ± R·(gauge/2)`, radius 0.07 m (140 mm dia). `heartOffset` negative
  ⇒ inverted coaster (rails above rider).

## Key decisions and WHY (full log in docs/DECISIONS.md)

- **Time-domain, not arc-length.** We started arc-length and rewrote. Time is
  what FVD++ uses and what the user asked for. Consequence: editing early in
  the ride shifts where later nodes land in space.
- **Bake-then-integrate.** The Bezier editor never touches the integrator.
  Curves are "baked" to dense linear keyframes (0.05 s grid). Keeps the
  physics core simple and lets extreme/folded handle shapes be sanitized in
  one place (monotonic-t guard in `bakeLane`).
- **Pure-Java core, JavaFX only in FvdDesigner.** Non-negotiable. It is what
  makes the math testable headlessly, and every physics bug so far was found
  by headless tests, not in the UI.
- **Per-lane independent nodes** (v5). One node used to carry all three
  values; now each lane has its own list. Ride duration = latest node across
  lanes; shorter lanes hold their final value.
- **Handle semantics (v6):** stored as FRACTIONS of the neighboring segment
  (`outFrac`/`inFrac`, 0..0.95) plus absolute dv value-offsets. Moving a
  node in time therefore rescales attached handles automatically — the curve
  squishes horizontally with its shape preserved (validated headlessly:
  max deviation 0.002, polyline sampling noise). Defaults: flat tangents at
  1/3 segment ⇒ smooth S-curve force transitions. "Smooth" = matching slope
  dv/dt on both sides (unit-correct C1, deliberately NOT pixel-angle
  matching). Handle drag is FREE by default (angle + length); holding S
  ("slide", moved off Shift in v7.1) locks the handle on its current line
  (length only) and never touches the
  partner; CTRL snaps the length fraction to ease presets — cubic 1/3,
  sine 1−2/π ≈ 0.363, quintic 7/15, circle 0.5523 (fractions whose
  flat-handle Bezier matches each ease's center slope). On a SMOOTH node a
  free drag mirrors the partner handle proportionally on BOTH axes
  (partner frac and dv scale by the dragged handle's ratios — equal handles
  act as mirror images, unequal ones stay proportional and collinear).
  Node drags are free; CTRL snaps the TRANSITION length (Δt from the
  previous node) and the value to the GLOBAL absolute grid — defaults
  0.1 s, 0.1 G, 5 °/s roll, all adjustable in Options (deliberately not
  Δ-value snapping).
  B toggles smooth/broken per node; re-smoothing keeps the IN handle and
  re-angles the OUT handle (user spec).
- **Undo/redo:** deep snapshots of all lanes, one per completed gesture,
  cap 100; Ctrl+Shift+Z redoes (any new edit clears the redo branch — one
  choke point, pushUndo). Heart/gauge/start-speed settings are NOT in undo.
- **Save format** `FVDD2`: human-readable text, `Locale.US` decimals
  (critical — default locale breaks parsing on comma-decimal systems),
  unknown lines ignored for forward compatibility. Round-trip tested.
  Includes per-coaster `tiespace`, `railmode solid|gradient`, `railcolor
  r g b`, `v0` (start speed) lines
  (older versions ignore them via the unknown-line rule); `n` node lines
  carry an OPTIONAL 8th field (straight-lock flag) — readers ignore extra
  fields on known lines, so this is compatible both ways. v2 stores handle
  time-offsets as segment FRACTIONS; `FVDD1` files (absolute seconds,
  negative in-handle) are auto-converted on load.
- **Settings split:** app-wide settings (shortcut bindings, FOV) persist
  across runs via `AppSettings` → `~/.fvd-designer.properties`; per-coaster
  settings (heart, gauge, rail color) live in the `.fvd` save file.
- **JavaFX Y-flip:** JavaFX screen Y+ is down, world Y+ is up. ALL mesh
  vertices and camera math negate Y. Touch 3D code ⇒ re-check signs.

## Layout tuning: the hard-won lesson

Hand-writing force layouts fails badly. Curvature ∝ 1/v², so as a train slows
climbing a hill the same G bends the track ever more sharply — layouts are
hypersensitive to segment lengths and blow through vertical. The working
method (used to produce the default layout): **bisection-search each phase
duration against a pitch target** (pull-up ≈ +28°, float ≈ −32°, pull-out ⇒
end pitch 0), run through the FULL bake+integrate pipeline. Also: pull-outs
must be longer than pull-ups (train is faster going down), and a level banked
turn needs `Gv = 1/cos(bank)` — numerically confirmed (bisected 1.297 vs
theory 1.305 for 40°, <1% error, a strong integrator validation).

This pain is exactly why FVD++ has "geometric" sections that target
pitch/heading instead of forces — a prime roadmap feature.

## Testing (see docs/TESTING.md)

The core is validated headlessly; the JavaFX layer has NEVER been executed by
Claude (the original dev sandbox had no display/JavaFX/javac). Tests so far
were run via scratch mains against target/classes; in this repo they should
be converted to proper JUnit. Validated: analytic circle (RK4 radial
deviation < 1e-9 m over a 171 m circle; the old stepper: 10 cm), default
layout ends level (RK4 baseline: 406.878 m, pitch 0.228°, U.y = 1.000),
resulting-rate extraction (yaw matches g·Gl/v to 4e-4 °/s), 200-trial
fuzz of baking with pathological handles (0 violations), save round-trip
(0 mismatches), NL2 export suite.

**Least-verified code:** the POV camera affine (sign conventions), toolbar
key/focus interactions, spinner listeners. If the POV view is rolled 180° or
mirrored, it's a sign flip in `applyPovCamera`.

## Current UI feature set

Single overlaid plot (each lane on its own value scale, AUTO-SCALED:
defaults Gv −1..4 g, Gl −1..1 g, roll ±150 °/s, expanding 1-2-5-rounded
when the visible curve exceeds them, frozen during drags; inactive lanes
25% alpha — clicking a dimmed lane's node or curve switches editing to it,
with the active lane keeping hit-test priority). Lanes ending early draw
their hold-final-value continuation DOTTED — clickable to append a node.
Shift+click = multi-select (group drag clamps against non-selected
neighbors, ignores ripple; Delete removes all; F with an adjacent PAIR
selected straightens the segment between them onto the chord; Shift+F
("alignLock") STRAIGHT-LOCKS it — Node.straightOut on the segment's left
node, enforced by enforceStraightLocks() at the top of every redraw
(re-chords facing handles; smooth endpoints keep C1 unless that side is
itself locked), drawn as TWIN 1px lines instead of the curve stroke;
endpoints stay draggable (line follows), facing knobs slide along the
chord (enforcement pins dv — emergent, no special drag code); Shift+F
with either endpoint selected unlocks; inserting on a locked segment
locks both halves; saved as an 8th field on `n` lines — old readers
ignore extra fields, missing field = unlocked). Holding S
during a node drag = transient axis constraint from the dominant movement
direction (same guide line as Z/X; slideKey tracked via key filters since
S is not a modifier). Arrow keys nudge the selection by snap steps (Ctrl+arrow
lands on the grid; key repeat glides; undo coalesces per burst). Menus and
dialogs have LOCAL undo/redo (Ctrl+Z / Ctrl+Shift+Z while open; history
discarded on close so the next Ctrl+Z hits the coaster). Lane buttons are
tinted with the curve colors. The graph auto-fit time window FREEZES
during drags (fixes runaway end-node dragging); dragging past ANY plot edge
grows that axis at a RATE proportional to the overshoot (dragGrowTimer,
an AnimationTimer — grows even with the mouse held still; view re-fits on
release). The S-slide axis constraint anchors to the node's GESTURE-START
position (snaps back onto the original row/column). After click-inserting a node, the first press prioritizes the node
body over its own knobs (freshInsertPriority). `subHolder.setMinSize(0,0)` is
load-bearing: without it the SubScene's min size stops the window/divider
from shrinking and the collapse controls go off-screen. 3D view floor: static transparent
reference grid (1 m lines, heavier every 10 m) at ABSOLUTE GROUND y=0 —
Start y is height above ground (default 21 so the shipped layout keeps its
old ~2 m clearance; tracks CAN dig below the grid); it fades
over SIX concentric bands (minor lines gone past 120 m, majors past 240 m)
onto a solid translucent sheet (600 m, 3 cm below the lines to dodge
z-fighting) because JavaFX 3D lacks mipmaps and distant sub-pixel lines
shimmer. LOD: the grid group follows the active camera (orbit pivot / fly
pos / POV sample) in 10 m quantized steps, keeping full density around the
viewer and the pattern world-aligned.
Default keys (ALL rebindable via Edit > Shortcuts,
persisted per-user): `1/2/3` lane select · click node = select + show handles
· drag handle = length-only, `Shift` = angle · `B` smooth/broken (circle vs
square knobs) · click the curve = insert node exactly ON it (hover shows a
translucent dot riding the line; flat segments insert level with SINE
handles, curved ones use an exact de Casteljau split — tangent handles,
curve unchanged, headless-tested) · right-click delete · `A` append 1 s
to the ACTIVE lane only, holding its final value (other lanes keep their
dotted hold — no nodes added; appended transitions get SINE handles, not
cubic — only the shipped default layout keeps 1/3) ·
`Space` POV cam — starts HANDS-OFF (W/S and the S slide key stay with the
graph, so you can edit while watching); right-click the view CAPTURES the
ride controls (mouse hidden, no mouse look): W/S ride at simulated speed,
Shift ×4, Ctrl ×0.25; right-click releases; while captured, Space flips
between fly (WASD) and POV (W/S) without releasing (`povCaptured`);
scroll = fast burst either way, translucent graph line marks the camera
(Options toggle, default on) ·
`H` flatten handles — of EVERY selected node when multi-selected ·
`Shift+H` (action "levelSel") level the selection ONTO the main node: all
selected nodes take selIdx's value with flat handles (one horizontal
line; <2 selected falls back to H) · `Z`/`X` toggle vertical/horizontal
node-drag lock (translucent guide line; per-selection) · `Ctrl+S/O` save/load ·
`Ctrl+Z` undo · `Delete` deletes the selected node (interior deletes bridge
the gap — neighbors keep positions/handles; last-node deletes shorten the
ride; node 0 refuses) · double-click a NODE = modal editor to type
transition time, transition Δ, and handle fractions directly (typed
fractions SLIDE the knob along its current line — dv scales with length,
S-drag semantics; node 0's dialog also edits the START SPEED; labels are
just "In handle"/"Out handle" and the drag readout shows the bare
fraction — units/meaning deliberately live in Help only) · right-click
the 3D view = FLY mode (WASD + mouse look via Robot cursor-warp, Shift ×4,
Ctrl ×0.2, scroll burst; right-click exits KEEPING the view — the orbit rig
has a movable pivot `camPivot` set to the point `zoom` m ahead of the fly
camera; mutually exclusive with POV; TWO FOV settings — "POV" and
"fly & orbit" (the handoff pair must share a lens or mode switches zoom-
jump) — plus a fly mouse-sensitivity slider, all in
Edit > Options; fly look deltas are capped ±35 px/event to tame OS pointer
acceleration). Graph editor: scroll
zooms the time axis around the cursor (view window in tToX/xToT, min 0.1 s,
full zoom-out resumes auto-fit; grid step floors at 0.01 s with 2-decimal
ticks), right-drag pans, box select on empty plot (Shift adds; plain-click
deselect happens on RELEASE; the PRIMARY node of a box selection is the
EARLIEST one caught — shift+click keeps last-clicked), rotated lane-name
y-axis label + "Time (s)"
x-axis label + faded per-gridline value labels, adaptive 1-2-5 grid lines.
Insert allows 0.02 s from a node (INSERT_GAP); MIN transition length 0.01 s.
Ctrl-snap steps are Options settings (settings.snapT/snapG/snapRoll;
spinner arrows walk the 1-2-5 series; every Options row has a ↺ reset).
The S-slide axis constraint engages
instantly, re-picks from total |dx| vs |dy| each event (can switch mid-
drag), and is OVERRIDDEN by explicit Z/X; S engages mid-drag EVEN WITH
CTRL HELD (snap+slide compose), and while a drag is live or S is held
as the slide key, key events with ANY modifier fire no bound action —
Ctrl+S must not save (nor Ctrl+Z undo) mid-gesture; deliberate Ctrl+S
at rest still saves; guide lines are colored by what
the drag CHANGES — lane color = value changing (vertical guide), YELLOW =
time changing (horizontal guide); yellow stays the time color everywhere
else too (`TIME_COLOR`: "Time (s)" label, POV marker). Guides draw at
alpha 0.5; S-dragging a handle shows its dashed slide rail. Hovering the curve
shows the insert dot plus the lane's value (Shift REPLACES it with the
ANALYTIC slope from `BezierBaker.slopeAt` — differencing the baked
polyline stair-stepped; tracked via key filters so it updates without
moving the mouse). The node-drag readout clamps into the plot bounds. Orbit drags
respond ONLY to the primary button — the right-drag that exits fly used to
fall into the orbit handler with a stale anchor and jump the view.
Mouse sensitivity (Options)
drives fly look AND orbit (orbit = 2× the setting); Shift+wheel arrives as
HORIZONTAL scroll on Windows — every scroll handler falls back to deltaX. Handles reach MAX_FRAC = 1.5
(150% of the segment; baker's monotonic guard sanitizes folds). The
node body always outranks its own knobs on press. valToY does NOT clamp —
plot drawing is clipped instead; off-screen handles show a dashed stub +
hollow edge marker (click = handleViewExpand grows the scale; click off
restores). Append with a selection = 1 s flat hold inserted after it (all
lanes shift; transition shape transfers to the hold node); node dialog
edits transition time + GLOBAL value. The 3D view and graph panel sit
in a vertical SplitPane — divider resizes, and a ▼/▲ button overlaid on the
3D view collapses/restores the whole bottom panel.
Node-drag readout shows Δt from the previous node and the GLOBAL value with
units ("Δt=0.80s  2.50 g"). Toolbar "Ripple:" checks
(minimal filled-square checkboxes, per-axis): "time" shifts every later node
of the lane in t with the drag (preserves later transition times); "value"
translates the rest of the graph vertically. Menu bar: File (Save/Load +
Export NL2 CSV — rebindable "export" action Ctrl+E; modal dialog with
rail-center-vs-heartline radios and a segment-length spinner, both persisted
per-user in AppSettings; spacing rounds to a whole number of equal segments;
the CSV negates Z for NL2's right-handed world — see D40 before touching
signs), Edit
(Shortcuts dialog — rebind by clicking + pressing a key, ↺ resets an edited
binding to default; Options dialog — the TWO FOV sliders 30–110°, mouse
sensitivity, snap steps, "Show heartline (neon)" check — hairline
self-illuminated yellow tube on the integrated path, app-wide default off,
setVisible(false) while POV rides (the camera sits ON it) — and the POV
marker), Help
(clicking the menu TITLE opens the Help dialog directly — it's a Label
graphic on an empty Menu; there is deliberately NO View menu, tried and
merged back into Options in v7.5). Shift+click
OR C+click the track in orbit mode re-centers the pivot on it
(screen-space nearest sample within 25 px via pickTrackSample,
BEHIND-CAMERA samples filtered — localToScreen
projects them mirrored, which made sky clicks teleport the pivot; a miss
moves nothing and the drag orbits normally; C tracked like the S slide
key). Ctrl+click the track = INSERT a node in the active lane at that
ride time (same pick, feeds insertNodeOnCurve — identical semantics to
clicking the graph curve). Middle-drag orbits like primary-drag
(secondary stays excluded — fly-exit stale-anchor jump). On-track NODE
MARKERS (Options toggle, default on): a small spine-level sphere per
active-lane node, selection in white, signature-guarded rebuild on graph
redraws. `T`/`Shift+T` = TOP/SIDE views with a pseudo-ortho lens (1° FOV
at ~72× the distance, apparent size preserved; ParallelCamera can't do
3D) — top snaps yaw to the nearest 90° (always grid-aligned), side
centers the pivot on the SELECTED node's track point (selected markers
draw white AND larger, 0.32 vs 0.22 m); RIGHT-drag pans in the view
plane without exiting ortho (grab-style, world-units-per-pixel from the
lens; right-click does NOT hand off to fly while ortho); same key
restores the previous perspective view, orbiting away
keeps the angles and restores the lens, scroll zooms multiplicatively
while ortho, fly/POV entry auto-exits ortho, far clip 200000 while
active. Toolbar "Resulting ▾" is a plain Button + ContextMenu (a
MenuButton's arrow region broke the flat button look). Coaster (Heart
spinner −3..3 default 1.0, Gauge spinner 0.3..2.5 default 1.0, Tie-spacing
spinner 0.2..10 m default 1.0 (cross ties placed by arc length, flush with
the rails), Start x/y/z spinners ±500 m (saved as `start` line; y = height
above the y=0 ground, default 21), Start speed spinner 1–100 m/s default 26
(saved as `v0` line; ALSO editable in the first node's double-click
dialog — the dialog writes through the spinner), rail color:
G-force gradient or solid — the ColorPicker lives in its own dialog, NOT in
the menu: embedded there, "Custom Color" crashed because its owner window
was the auto-hiding menu popup; the same dialog customizes the FOUR
G-force gradient stops — each a COLOR and a G VALUE spinner, defaults
0/1/3/5 g, flat outside the ends and lerped between stops, saved as
`gradcolors` + `gradstops` lines). Toolbar: lane toggles, Ripple time/value
checks, "Resulting" DROPDOWN (CheckMenuItems, persisted per-user: dashed
read-only overlays of the RESULTING pitch rate and yaw rate from
FVDIntegrator.bodyRates, drawn on the roll lane's scale in the matching
lane colors; auto-scale includes enabled ones; roll is deliberately NOT
offered — the resulting roll rate is the authored roll lane),
POV, Append (no shortcut hints on buttons — the stats bar hint is
generated from live bindings). Stats bar shows ride time, track length,
height range, top speed, Gv range. Window sizes itself to
`Screen.getVisualBounds()` (capped 1400×880) — do not hardcode window sizes;
that broke on the user's 1080p screen once already.

**Help dialog is a USER REQUIREMENT to keep current:** Help > Controls &
Features (`showHelpDialog` in FvdDesigner) must be updated with EVERY
user-facing feature or control change, in its matching section, prioritizing
non-obvious behavior. Do not ship a feature without touching it.

## Known quirks / accepted debts

- Extreme handle angles can fold a curve; the baker's monotonic-t guard
  flattens it, so drawn curve and baked result can differ at pathological
  settings.
- Rails + cross ties but no spine box yet; tie positions quantize to the
  nearest integration sample (up to ~0.13 m jitter at speed — invisible at
  normal tie spacing).
- Undo/redo exclude heart/gauge/start-speed (menu-local Ctrl+Z only).
- Friction is hardcoded (0), not in the UI. (v0 became the per-coaster
  Start speed setting in v7.9; like heart/gauge it is NOT in undo.)

## Roadmap (user-endorsed, phased — see docs/SECTIONS-DESIGN.md for Phase 1+)

The long-term goal is a multi-section designer (force / geometric / bridge /
straight-curve sections, splittable track pieces, speed control, NL2
interop). That requires ONE deep overhaul: splitting the single integrator
pass into geometry generation (per section, at a per-section DESIGN speed)
and a global dynamics pass (true speed from energy/friction/speed sections)
plus an analysis pass (true forces from geometry + true speed). Rationale
and contracts live in docs/SECTIONS-DESIGN.md. Order:

**Phase 0 — independent wins (do first, none is wasted by the overhaul):**
1. ~~NoLimits 2 CSV export~~ DONE v7.8 (D40: Nl2CsvExporter.java, File >
   Export NL2 CSV, rail-center/heartline + segment-length settings)
2. ~~RK4 integrator upgrade~~ DONE v8.0 (D44: FVDIntegrator v3, circle
   radial error 10 cm → <1e-9 m; layout invariants re-baselined)
3. ~~Read-only "resulting" graphs~~ DONE v8.0 (D45/D47: bodyRates +
   toolbar "Resulting" dropdown — pitch/yaw picks, no roll)
4. ~~Ortho top/side views · C+click orbit recenter · middle-drag orbit~~
   DONE v8.2 (D48: T/Shift+T pseudo-ortho — 1° FOV at ~72× distance,
   ParallelCamera is unusable; C+click aliases Shift+click recenter;
   middle-drag orbits)
5. ~~Smoothing tool~~ shipped v8.0 (D46), REMOVED v8.1 on user decision
   (D47: the design method shouldn't lean on value-smoothing)
6. ~~Viewport editing part 1~~ DONE v8.2 (D48: read-only spine spheres
   for the active lane's nodes + Ctrl+click-track-to-insert)

Phase 0 is COMPLETE (v8.2).

**Phase 1 — the overhaul (feature-neutral):** section/piece model,
geometry–dynamics–analysis split, per-section design speed, time/distance
domain toggle, quaternion internal orientation, save format FVDD3,
section-aware graph, document-level undo, split FvdDesigner into files.
Existing headless invariants must pass unchanged.

**Phase 2 — section types (easiest first):** geo sections (direct
pitch/yaw/roll rates — NO solver needed) + reversal → straight/curve mode →
split/reconnect + piece placement gizmos → bridge sections + influence
curves + "become real".

**Phase 3 — dynamics:** speed-controlled sections (constant-v /
constant-longitudinal-G / curve), air + rolling friction.

**Phase 4 — interop & full interactivity:** NL2 CSV import (external-
geometry sections), viewport editing part 2 (drag values/times/handles in
3D), Euler display toggle.

**Small items, anytime:** spine box in the rail mesh (copy
`buildTieMesh`), friction spinner (subsumed by Phase 3).
