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

--- Phase 1 document model (D50) — all pure Java, no JavaFX ---

Quat.java           unit quaternion (mul/rotate/fromFrame/aboutY). The
                    MODEL's orientation type; identity paths are
                    float-exact (the bit-for-bit gate rides on that).
TrackPose.java      pos + orient (+ running global s/t offsets): section
                    entry/exit contract.
Section.java        sealed interface (domain TIME|DISTANCE, vDesign,
                    reversed — getters AND setters since v11.0, so the
                    editor writes through the interface) — permits
                    LaneSection + StraightCurveSection + BridgeSection;
                    external joins the list when it lands.
LaneSection.java    abstract base of the THREE-LANE modes (v10.0, D58):
                    lanes + vDesign + reversed live here so the editor,
                    undo, IO and compiler handle force and geo through
                    one type.
ForceSection.java   classic FVD mode: lanes = Gv / Gl / roll rate.
GeoSection.java     Phase 2 (v10.0, D58): lanes = pitch / yaw / roll
                    rate (°/s), integrated DIRECTLY — no solver;
                    gravity never bends the shape.
GeoIntegrator.java  GEO PHYSICS. Pure Java, no JavaFX. RK4 on (pos,
                    quaternion), q' = ½(0,Ω)⊗q, Ω = yaw·U − pitch·R +
                    roll·T; reuses bake's Keyframe slots (Gv=pitch,
                    Gl=yaw — mapping documented there only); records
                    FELT Gv/Gl at design speed so rail colors/stats
                    stay honest. See PHYSICS.md §2b.
StraightCurveSection.java  Phase 2 (v11.0, D68): the first NON-LANE
                    section — an ELEMENT list: straight (length) | arc
                    (radius, signed angle, direction: 0 = flat turn
                    with +angle = rider-right, 90 = pull-up), each with
                    an ENTRY smoothing length (element 0's = the seam,
                    ignored) and a ROLL spec (v11.1, D69): RATE °/s
                    (roll-lane semantics; 0 = bank drifts through
                    pitched turns) or GLOBAL ° (bank HELD vs the
                    horizon; 0 = rails stay level — NL2-classic).
                    New elements default GLOBAL 0; the loader defaults
                    old elem lines to RATE 0. Always DISTANCE domain:
                    shape is speed-independent (except RATE roll,
                    which is per-second by design). Elements mutable,
                    editor-owned.
ScIntegrator.java   S/C PHYSICS. Pure Java, no JavaFX. Curvature-vector
                    profile over arc length (Ω/m = κ(cos·U − sin·R),
                    sine-eased blends CENTERED on boundaries — total
                    turn angle exactly preserved, halves clamped inside
                    short elements; RATE roll joins Ω as ω_T = rate·T,
                    blended the same way but NOT v-scaled); RK4 on
                    (pos, quat, s) in TIME with
                    v algebraic; SUB-STEPS to land every RK4 leg
                    exactly on profile breakpoints with side-capped
                    lookups (stepping OVER a κ step costs ~0.1°/cross;
                    k4 reads the far side) + Newton landing on the
                    section end. GLOBAL roll = an algebraic PROJECTION
                    after every leg (roll about T to the eased bank
                    target — never bends the spine; ease from the
                    measured entry bank over max(smoothIn, 2 m);
                    sub-1e-12 corrections skipped so planar runs stay
                    bitwise; no-op near vertical track). Samples stay
                    on the dt grid. Records
                    felt Gv/Gl at design speed like GeoIntegrator.
BridgeSection.java  Phase 2 (v13.0 D72; live-follow v13.1 D73; seam
                    smoothness v13.2 D74): connector — the last
                    section of a piece, joining
                    its chain end to the NEXT piece's entry. The
                    compiler RE-CAPTURES the solve inputs EVERY compile
                    (poses; seam curvature acc + its ⊥-projected
                    derivative jerk, MEASURED off the chain tail and a
                    pre-integrated target head; seam roll rates; the
                    target's entry speed vB), so the
                    bridge FOLLOWS moves of either side live (no
                    feedback loop exists here — that concern is §5's);
                    the stored fields are the last capture and govern
                    alone only when the target piece is GONE (orphan =
                    frozen + badge "!"). Geometry: SEPTIC Hermite
                    (p/p'/p''/p''' both ends — rate graphs continuous
                    in VALUE and SLOPE at the seams; zero seam data =
                    the older shapes, how old files load; the jerk
                    vectors are STORED ⊥ T — measuring the tangential
                    part rang (D74) — and integrate() re-adds the
                    ANALYTIC −|κ|²·T at solve time (v13.4 D77: a
                    turning κ vector has one; without it the septic
                    sagged off a continued circle); RMF frames + a quintic twist whose end
                    slopes are the neighbors' ROLL RATES and end
                    second derivatives their roll ACCELERATIONS
                    (v13.4 D76 — min-jerk; a min-strain septic was
                    probed and REJECTED); solvedTarget's orientation
                    comes from the target's pre-integrated HEAD
                    frame, not its nominal entry pose (v13.4 —
                    reversed heads kinked); the speed
                    profile eases onto vB (no far-seam v step; dv/dt
                    continuous); felt G from analytic curvature;
                    tangent lengths user-adjustable, DEFAULT ONE FULL
                    GAP (v13.3 D75 — gap/2 packed the turning into the
                    seam regions: end rates 12-55× the interior,
                    "spiky ends flat middles"; NOT a continuity-order
                    problem) + the toolbar GAP spinner AND dragging
                    the strip's END LINE re-optimize placement per
                    tick/event (both rescale lenA/lenB with the gap).
                    Collinear
                    ends = exact straight line (gated). Cramped
                    geometry still needs fast-but-smooth interior
                    transients — that is the geometry's demand (D74).
TrackPiece.java     placement (origin + BASE ORIENTATION quat + yaw
                    DELTA about Y, v12.1: baseOrient is the natural
                    orientation — identity from scratch, the exact
                    boundary pose after a split, the reversed exit
                    after a piece reversal; yaw 0 = baseOrient exactly,
                    so zeroing the delta reverts) + section
                    chain; editing one piece can never move another.
Coaster.java        THE DOCUMENT: global v0/friction, pieces, SpeedRegion
                    slots (Phase 3). Section-0 lanes are shared BY
                    REFERENCE with the editor. Cosmetics stay UI-side
                    until milestone B (travel via DesignIO.Cosmetics).
TrackCompiler.java  THE THREE PASSES (SECTIONS-DESIGN §1): passG per
                    section at vDesign (force = unchanged bake+RK4;
                    geo = GeoIntegrator; s/c = ScIntegrator — both
                    exit poses carry the integrated quaternion;
                    REVERSED sections generate
                    canonically at the entry height and
                    reverseAndPlace flips + yaw-places them, D67),
                    passD global true speed,
                    passA resulting rates. Result also carries
                    per-section Spans (sample range + t/s extent,
                    v11.0; + the section's EXACT exit TrackPose,
                    v12.1 — splits copy it into baseOrient) — the
                    editor lays out DISTANCE-domain
                    strips from them. Single-force-section output
                    is BIT-IDENTICAL to the old direct call (gated
                    headlessly).
DesignIO.java       save/load: writes FVDD3, reads FVDD3/FVDD2/FVDD1
                    (legacy = one piece/one section). Headlessly
                    round-trip tested at last.

GraphEditor.java    JavaFX GRAPH EDITOR (split out v9.2, D52): the
                    force-curve canvas — lanes, selection, node/handle
                    drags, document undo stacks, node dialog. Reaches
                    the shell through a `final FvdDesigner app` backref
                    (app.rebuildTrack(), app.settings, …).

Viewport3D.java     JavaFX 3D VIEW (split out v9.4, D54): orbit/fly/POV
                    cameras + pseudo-ortho views, mouse/scroll handlers,
                    animation timers, rail/tie/heartline meshes, ground
                    grid + LOD, track picking, node markers, and
                    rebuildTrack (the pipeline entry — the shell keeps
                    name-stable delegates). Same `app` backref pattern.

Dialogs.java        JavaFX DIALOGS (split out v9.4, D54): Help,
                    Shortcuts, Rail colors, Options + option-row
                    helpers. Menu-local undo machinery stays on the
                    shell (the Coaster menu shares it).

FvdDesigner.java    the SHELL (~1.1k lines): document + file lifecycle
                    (New/Save/dirty title), key dispatch, menu bar,
                    toolbar, layout, menu-local undo, NL2 export dialog.
```

Pipeline on every edit: `lanes → TrackCompiler.compile (passG: bake +
integrate per section → passD: true speed → passA: rates) → rail meshes +
stats`. This runs live on every mouse-drag event; it is fast enough (a
~14s ride is ~270 keyframes and ~1350 integration steps).

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
- GEO sections (v10.0) skip the force inversion: authored pitch/yaw/roll
  rates feed `Ω = yaw·U − pitch·R + roll·T` directly; orientation
  integrates as a quaternion (`q' = ½(0,Ω)⊗q`, RK4, PHYSICS.md §2b).
  Gravity never bends geo shape — it only shows in the recorded felt
  forces (`Gv = ω_pitch·v/g + U.y`, at design speed). Validated: exact
  constant-rate angles + re-authoring the default layout's resulting
  rates as geo reproduces the force geometry to 2 mm over 407 m.
- STRAIGHT/CURVE sections (v11.0) author geometry per METER: a
  curvature-vector profile κ(s) with sine-eased transition blends
  (centered on element boundaries — total turn angle preserved
  exactly; blend halves clamp inside short elements). ScIntegrator
  RK4s (pos, quat, s) in time at v-from-energy, sub-stepping so every
  RK4 leg lands EXACTLY on profile breakpoints with lookups capped to
  the leg's side — sharp element chains hit analytic headings to
  1e-6° (naively stepping over a κ step cost ~0.1° per crossing).
  Shape is fully speed-independent; vDesign only sets the time
  mapping and the recorded felt G. Per-element ROLL (v11.1): RATE °/s
  joins Ω about T (time-based, so it alone reintroduces a design-speed
  dependence); GLOBAL ° holds the bank vs the HORIZON via a pure
  T-roll projection after every leg (eased from the entry bank over
  max(smoothIn, 2 m); never bends the spine; global 0 = rails level
  through slopes and turns — rate 0 instead lets bank drift, the two
  differ by 0.34 in R.y on the gated dip+turn chain).

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
  max deviation 0.002, polyline sampling noise). A D62 attempt to keep
  smooth joints from kinking during re-times (rigid absolute handles)
  was REVERTED in v10.5 on user decision — the squish applies to ALL
  nodes, kinks accepted (D63). Defaults: flat tangents at
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
  Node drags are free; CTRL snaps time and value — DEFAULT references:
  TRANSITION length (Δt from the previous node) for time, the GLOBAL
  absolute grid for value (defaults 0.1 s, 0.1 G, 5 °/s roll, steps
  adjustable in Options). v10.2/3: each axis's reference is a per-user
  Options LOCAL/GLOBAL radio pair (horizontal defaults local = Δt,
  vertical defaults global = absolute); the SECTION END
  is its own snap point that ABSORBS grid points within half a step of
  it (snapping near the end = exactly the end); and the end is a WALL —
  a lane's last node stops there unless it STARTED the gesture at the
  end (extend = drag to end, release, grab again; endLimitFor).
  B toggles smooth/broken per node; re-smoothing keeps the IN handle and
  re-angles the OUT handle (user spec).
- **Undo/redo (DOCUMENT level, D51):** deep DocState snapshots — all lanes
  PLUS heart, gauge, start x/y/z and start speed ("everything that changes
  the track path"; tie spacing/colors are cosmetic and stay menu-local) —
  one per completed gesture or path-spinner click, cap 100; Ctrl+Shift+Z
  redoes (any new edit clears the redo branch — one choke point, pushUndo).
  Path spinners wire through wireDocSpinner (snapshot pre-apply;
  menuApplying guard stops double-recording, docApplying suppresses
  listeners during undo application). Known granularity: node-0 dialog
  editing speed + values = two undo steps.
- **Save format** `FVDD3` (D50, written by DesignIO): human-readable
  text, `Locale.US` decimals (critical — default locale breaks parsing
  on comma-decimal systems), unknown lines ignored for forward
  compatibility, extra fields on known lines ignored (the straight-lock
  8th and level-lock 9th `n` fields ride on this). Global lines (v0,
  heart, gauge,
  tiespace, railmode/railcolor/gradcolors/gradstops, speedregion), then
  `piece x y z yaw [qw qx qy qz]` blocks (fields 5-8 = the base
  orientation, v12.1; missing = identity; %.10f so round-trips skip
  the loader's re-normalize) holding `section force|geo|sc|bridge
  vDesign domain
  reversed` blocks holding `lane`/`n` lines (force/geo), `elem
  straight len smooth [rollmode rollval]` / `elem curve radius angle
  dir smooth [rollmode rollval]` lines (straight/curve, v11.0; roll
  fields v11.1 — missing = rate 0, the v11.0 geometry), or `bpose`
  (two pos+quat poses, %.10f) + `blen lenA lenB` + `bacc` (two seam
  curvature vectors, v13.1 — missing = zero, the v13.0 G¹) + `bdyn`
  (seam jerk vectors + roll rates + target entry speed, v13.2 —
  missing = zeros, the v13.1 shape; + two seam roll ACCELERATIONS,
  v13.4 — missing = zeros, the v13.2/13.3 twist) lines
  (bridge — the LAST-CAPTURE solve inputs; live compiles re-capture,
  they matter alone for orphaned bridges). READS FVDD3,
  FVDD2 and
  FVDD1: legacy flat files load as one piece at their `start` (default
  (0,21,0)) with one force section (vDesign = their v0, default 26);
  FVDD1 handle time-offsets (absolute seconds, negative in-handle)
  auto-convert to segment fractions. Canonical round-trip
  (save→load→save byte-identical) is headlessly gated.
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
their hold-final-value continuation DOTTED (to the SECTION end) —
clickable to append a node. SECTIONS (v9.5 D55, geo v10.0 D58, UX round
v10.1 D59): the piece's sections
are strips on one shared time axis — the active one edits normally in
its own stretch (all node math is section-LOCAL via ltToX/xToLt; one
section ⇒ tOff 0 ⇒ identical to the old editor), inactive ones draw all
three lanes at alpha 0.20 with boundary lines between, and clicking a
greyed strip activates it. UNIFIED VIEW (D59): the plot always shows
ONE mode's quantities — the active section's — so a strip of the OTHER
mode draws what it DOES in the active units, derived from the compiled
track via GraphEditor.derivedAt (geo view of force strips = resulting
pitch/yaw/roll rates; force view of geo strips = felt Gv/Gl + resulting
roll); everything shares the active axes and curves join at boundaries.
Every lane curve carries a translucent zero-line fill
(LANE_FILL_ALPHA 0.13, inherits the strip/lane alpha); the Resulting
overlays have NO fill and stretch across the WHOLE ride since v10.8
(path broken over a SEAM_TRIM band at each boundary; scale fitted to
the visible window like a lane). Every
wide-enough strip wears a force/geo/s-c BADGE at its top-left (type
indicator, ◀ = reversed-flag set + per-section menu: info line, SPLIT
PIECE BEFORE THIS SECTION (v12.0/1) and Delete section — the section-
level REVERSED check moved to piece scope in v12.1 (Coaster menu >
Reverse piece); the flag itself remains reversal's per-section
MECHANISM (v10.9 D67: the train runs the authored shape back-to-front
— generated canonically at entry height, flipped, yaw-placed onto the
entry's horizontal heading; pitched/banked seams mismatch honestly;
felt Gl negates, roll rate is traversal-invariant — §3's "roll flips"
was corrected; lanes still edit in the AUTHORED direction while
inactive strips/B-matching/'+' holds use the compiled curves).
Delete section is disabled at
one document section; downstream re-chains, deleting the active one
activates
its predecessor; one undo step; press rank nodes/knobs > '+' > badges
> curve insert (D57). An open '+'/badge menu dismisses on the next
graph click (JavaFX auto-hide misses the canvas — openMenu field).
A dashed line + circled '+' past the ride
end opens the add-section menu: the LANE modes append a 1 s HOLD of the
ride's end state (same-mode = final lane values via
Coaster.continuation, now mode-generic; force-after-geo = felt end
Gv/Gl + final roll; geo-after-force = resulting end pitch/yaw +
authored roll; after an s/c or reversed section the roll comes from
the compiled resulting rates), and "straight/curve" appends a 10 m
STRAIGHT (a straight IS the seamless continuation); vDesign frozen at
the piece-end design speed per §0; bridge + attach-piece stay
disabled placeholders. STRAIGHT/CURVE STRIP WIDGET (v11.0, D68): an
active s/c section replaces the lanes with element BANDS (straight
slate / curve rose, labeled chips like "R20 90°→"), felt-G derived
curves behind at 0.45 alpha, dashed separators, and a smoothing
DIAMOND per internal boundary (filled = blending; shaded band = the
blend zone). Drag a separator or the section end = resize the left
element (EXTENDS past the current end too — the cursor map
extrapolates beyond the span at the end sample's speed, v11.1; Ctrl
snaps by the Options element-length step, default 1 m;
live readout; H_RESIZE cursor); drag a diamond = blend length (snaps
shut < 0.2 m); click = select; double-click / right-click "Edit
element…" = typed dialog (kind radios, length or radius/angle/
direction, entry smoothing — disabled on element 0: the seam stays
sharp — and ROLL: Rate °/s | Global bank ° radios + value, v11.1;
chips wear ∠° / ↻°/s when non-zero); right-click also inserts
straight/curve before/after +
deletes (clean-release rule, right-drag still pans); Delete = remove
selected element; A = append a straight; badge menu adds length/count
info + "Append element". New elements default 2 m entry smoothing
(SC_SMOOTH_DEFAULT) and GLOBAL 0 roll. The toolbar button is just
"Append" (v11.1) — seconds on lane sections, elements on s/c (the
tooltip carries both meanings). Lane buttons disable (mode 2), the plot shows
FORCE view (felt G) so other strips/overlays/B-matching read
generically; scales fit the derived curves; s/c strip layout uses the
compiled Spans (DISTANCE domain: duration exists only after compile —
model edits rebuild BEFORE redraw). Reversed s/c strips draw elements
in TRAIN order (element 0 at the right edge). 3D Ctrl+click on s/c
track only activates the section. GEO sections author pitch/yaw/roll RATES (°/s,
lane 0/1/2) with their OWN palette — pitch teal, yaw yellow, roll
orange; GraphEditor.laneColor is the
one accessor (curves, fills, buttons, guides, 3D markers). Selecting a
geo section relabels+retints the lane buttons (setLaneButtonMode),
retitles the y-axis and node dialogs, snaps ALL lanes by the
roll step, defaults the scales to ±60 °/s (roll ±150) and drops the
1 g reference line. B on a section-EDGE node (or "Match boundary
(value + slope)" in its dialog) RETURNS it to the boundary — Shift+B
(action "matchAll") does it for ALL THREE lanes' edge nodes as one
undo step: value AND
slope from the neighbor — exact from authored lanes same-mode (a lane
holding past its last node is FLAT there); cross-mode against a
non-reversed LANE-section neighbor it is ANALYTIC (v13.4 D76): a geo
neighbor's edge rates ARE its authored lane values, a force
neighbor's rates invert its authored targets at the exact seam frame
and ITS OWN seam design speed (ω = g(G−axis.y)/v, slope with the
Ω×axis and v' = −g·T.y terms), converted to the active mode at the
ACTIVE side's seam design speed (G = ω·v_seam/g + axis.y, v13.3 D75)
— both directions enforce rate continuity EXACTLY, so alternating
B/Shift+B is idempotent once matched (v13.3's sampled targets, and
v10.2's before them, crept ~0.1 °/s per press at banked rolling
seams: linear extrapolation of curved rate profiles re-injects a
bias — probed, D76). Only s/c, bridge and reversed neighbors still
sample the compiled curves (~3 samples inside via rateIdxAt —
bodyRates smear boundary jumps across ±2 samples, same reason
derived strips and overlays trim SEAM_TRIM = 2.5·dt — extrapolated
back to the compiled boundary time). The felt-G DISPLAY still steps
honestly at seams whose design speeds differ; rates join.
Cross-mode matching
against the NEXT section stays
mildly iterative: our exit pose moves its felt values. Clicking a
greyed strip ALWAYS activates it — checked BEFORE the dimmed-lane test
(v10.2: held end-values used to steal those clicks as lane switches).
PIECES (v12.0 D70, exact-split + piece reversal v12.1 D71; bridged
RUNS v13.4 D76): the graph
shows ONE bridged RUN at a time in its GLOBAL time window — a run =
consecutive pieces linked by trailing bridges, laid out strip by
strip via runLayout (piece+section+t0); a lone piece is a run of one
(the pre-v13.4 behavior exactly). Clicking any strip/badge in the
run activates that piece+section WITHOUT resetting the zoom
(activateRunSec; the toolbar selector still resets); the '+' sits at
the RUN's end and appends to its last piece (activating it first);
boundary matching crosses bridged piece boundaries (the neighbor is
the bridge — spanOf's flattened index reaches adjacent pieces'
spans, k outside the piece resolves to no Section = sampled path);
view clamps/auto-fit use runT0/runDur; the 3D viewport renders the
WHOLE run full-color (desaturation = detached pieces only). Piece-
scoped ops (placement, split, reverse) still target the ACTIVE
piece. (Axis continues across pieces; POV
rides them in order, jumping the gaps until bridges); a toolbar
"Piece k/n ▾" selector (hidden when single-piece) switches; badge
menu "Split piece before this section" cuts at the compiled boundary
POSE — position AND full orientation into the new piece's baseOrient,
so splits are BITWISE anywhere (pitched/banked included — user rule
"splitting never changes orientation"; Span carries each section's
exact exit TrackPose for this); the editor follows the edited
section onto the new piece. '+' menu "Attach floating piece" appends
another piece's sections to the active chain and dissolves it
(placement discarded — attach MEANS continue, and stays first-class
alongside future bridges per user); Coaster menu "Piece
x/y/z" + "Piece yaw (°)" spinners edit the ACTIVE piece (yaw is a
DELTA on top of baseOrient — zeroing it reverts to the natural
orientation) and re-sync
on switches; Coaster menu "REVERSE PIECE (flip travel direction)" =
the user-facing reversal since v12.1: a MODEL transformation
(placement re-anchors at the old exit via baseOrient, section list
flips to train order, each section's reversed flag toggles) — the
track STAYS PUT and the train runs it backward (level seams exactly,
gated; others re-join with D67's yaw-only per-section honesty);
applying twice restores the model exactly. Deleting a piece's last
section deletes the piece; 3D
Ctrl+click activates piece + section under the cursor; a clean PLAIN
click on any piece's track does too (v13.1 — drag still orbits), and
the same plain-click works in HANDS-OFF POV (v13.4: uncaptured POV
keeps the mouse; captured POV/fly still own it).
BRIDGES
(v13.0 D72, reworked v13.1 D73): '+' menu "Bridge to piece ▸" lists
EVERY other piece — a non-adjacent pick REORDERS it to come next on
the timeline (the list IS the timeline) — and appends a G² spline
joining the piece's end to that piece's entry (closes the POV gap).
The bridge FOLLOWS LIVE: the compiler re-captures poses + seam
dynamics every compile, so moving either side re-joins instantly;
only a bridge whose target piece is GONE freezes at its last shape
(badge "bridge !", strip hint). Seams are SMOOTH in the rate graphs
(v13.2 D74: septic + speed blend onto the target's design speed +
roll-rate-matched twist — value AND slope continuity; v13.4 D76: the
twist quintic also pins the neighbors' measured roll ACCELERATIONS
(seamRollAcc, two extra bdyn fields, missing = 0) so the roll-rate
graph leaves each seam on the neighbor's slope, not a flat shelf;
cramped
geometry still legitimately swings fast INSIDE the bridge). The
compiler aims solvedTarget at the target's ACTUAL first-sample frame
(pre-integrated head, v13.4) — a reversed-then-edited first section
used to kink hard at its entrance. Badge
menu: "Bridge tangents…"
adjusts the sweep; "Optimize placement of piece N (continue the
turn)" runs the CONTINUATION MARCH (v13.4 D77 — the acceptance
principle is "the graphs change as little as possible"): the seams'
body-frame curvature + roll-per-meter, linearly blended entry →
target, marched from the chain end for the requested LENGTH
(fixed-step RK4, hard cap, no iteration), the target's head placed
at the march's end and yawed to its heading, plus the smallest
parabolic vertical-rate hump for the pitch gap yaw can't fix — a
SPLIT CONSTANT TURN re-joins as the SAME turn (gated ≤ 0.11 °/s
rate ripple, ≤ 7 mm off the circle through 103°) and longer bridges
just carry it further around; straight seams reduce to dead-ahead
exactly (1e-12, gated); position + yaw only. The
toolbar "Length (m)" spinner (visible while a live bridge is
active; reads back the compiled span's ARC length, chord only
pre-compile) re-runs that optimization at the typed/clicked length
per change
(arrow bursts = one undo step), and DRAGGING the active bridge
strip's END LINE does the same per event (v13.3 D75: H_RESIZE
cursor, live "length … m" readout, Ctrl snaps by the element-length
step, one undo step per gesture, spinner re-syncs; both paths scale
lenA/lenB with the length so the sweep keeps its shape — except
from a DEGENERATE ≤ 0.5 m start (bridge created right after a
split), which takes the default full-length sweep instead of
scaling noise, v13.4). The
spinner's editor is numeric-only and non-focus-traversable, and 3D/
graph presses pull focus (v13.4: scene key FILTERS consume ride
keys' KEY_PRESSED but not KEY_TYPED — a focused editor kept
collecting w/s letters while riding);
a piece ending with a bridge refuses add/attach ("extend the NEXT
piece") and refuses piece reversal; the bridge strip itself is
read-only derived curves (lane buttons off, mode 2) apart from that
end line. 3D meshes are
PER PIECE (v13.2): inactive pieces render DESATURATED (gradient
texture or solid color), meshes never span a piece boundary — a
clean bridge/attach seam abuts flush and reads as one track, a fresh
split shows via the tint change — with MESH_GAP_BREAK 3.5 m kept as
an in-piece safety (v13.1's stretched-connector fix). Document undo
snapshots ALL pieces (sections + modes + placements + baseOrient +
bridge solves) +
the active
piece/section indices;
the Start-speed spinner and node-0 dialog write SECTION 0's vDesign
only (the ride entry: piece 0, section 0); markers and 3D
Ctrl+click-insert route through the piece + section
containing the clicked ride time.
Shift+click = multi-select (group drag clamps against non-selected
neighbors, ignores ripple; Delete removes all; F with an adjacent PAIR
selected straightens the segment between them onto the chord; Shift+F
("alignLock") STRAIGHT-LOCKS it — Node.straightOut on the segment's left
node, enforced by enforceStraightLocks() at the top of every redraw
(re-chords facing handles; smooth endpoints keep C1 unless that side is
itself locked), drawn as TWIN 1px lines instead of the curve stroke;
endpoints stay draggable (line follows); the FACING knobs are HIDDEN
and unclickable since v10.4 (knobPos null — collinear control points
make the cubic exactly the chord, so they do nothing); Shift+F
with a lone endpoint selected unlocks (with pairs selected the
pair-priority rule above applies) — a joint whose two sides sit at
DIFFERENT slopes comes out BROKEN (a kinked "smooth" node was a lie;
matching slopes, e.g. insert-on-the-line then unlock, stay smooth);
inserting on a locked segment
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
release). A MANUAL zoom window is SANITIZED at every redraw (v13.3
D75: slid back inside the piece's stretch, auto-fit resumed when at
least piece-wide — structural edits used to leave the window where
the strips no longer were: inserts landed "at the beginning of the
section" and the plot went ghost/blank), and '+' add-section /
add-bridge reset to auto-fit outright (they activate the piece-end
section, which must be on screen). The S-slide axis constraint anchors to the node's GESTURE-START
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
curve unchanged, headless-tested; keeping the button down chains
straight into a node DRAG — one gesture, one undo step, Ctrl snaps
live, v10.1; Ctrl while HOVERING snaps the dot in TIME per the
horizontal snap reference — value stays on the curve — and the click
inserts there; the dot hides where the snapped spot lacks INSERT_GAP
room, shared insertRoomAt) · right-click a NODE = its editor dialog
(v10.7 — was delete; Delete key only now) · right-click a SEGMENT =
its lock menu (Unlocked/Straight/Horizontal radios, one segment, same
flags as Shift+F/Shift+H; opens on a CLEAN release ≤3 px so
right-drag pan still works from the curve) · `A` append 1 s
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
`H` (action "flath") level the selection ONTO the main node — all
selected nodes take selIdx's value with flat handles, one horizontal
line; a single selected node just gets flat handles (v10.5 re-deal:
this was Shift+H's move) ·
`Shift+H` (action "levelSel") LEVEL-LOCK the selection: level onto the
main node, then the segments BETWEEN selected nodes hold HORIZONTAL via
Node.levelOut — enforceLocks pins each right node's value to its
left's, moveNode writes a RIGHT-end move back through the run (v10.6:
EITHER end steers the line's height; interior nodes snap back on) and
the facing handles stay flat; drawn as the straight lock's twin lines
PLUS a
translucent lane-color band between them; facing knobs hidden;
inserted nodes stay ON the line, both halves locked; saved as a 9th
`n` field;
Shift+F⇄Shift+H swap a segment's lock type directly (each clears the
other). BOTH lock toggles are PAIR-PRIORITY (v10.6, D64): with
adjacent nodes selected they add/convert/remove locking ONLY between
the selected nodes (all-already-locked = unlock; locks merely sharing
an endpoint with the selection are untouched), and both take 2+ nodes
(three nodes = two segments, each its own line); a lone selected
endpoint still unlocks its own segment(s) · `Z`/`X` toggle vertical/horizontal
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
spinner arrows walk the 1-2-5 series; every value row has a ↺ reset),
and so are the axis references — per-axis LOCAL/GLOBAL radio pairs
(v10.3 wording): "Horizontal snap (time)" local = Δt from previous
(default) / global = absolute ride time; "Vertical snap (value)"
local = Δ from previous / global = absolute (default); backed by
settings.snapTAbsolute/snapVRelative.
The S-slide axis constraint engages
instantly, re-picks from total |dx| vs |dy| each event (can switch mid-
drag), and is OVERRIDDEN by explicit Z/X; S engages mid-drag EVEN WITH
CTRL HELD (snap+slide compose), and while a drag is live or S is held
as the slide key, key events with ANY modifier fire no bound action —
Ctrl+S must not save (nor Ctrl+Z undo) mid-gesture; deliberate Ctrl+S
at rest still saves; guide lines are colored by what
the drag CHANGES — lane color = value changing (vertical guide), LIGHT
PURPLE = time changing (horizontal guide); purple is the time color
everywhere (`TIME_COLOR` rgb(200,165,255), v10.2 — was yellow, which
geo yaw now owns: "Time (s)" label, POV marker). Guides draw at
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
translates the rest of the graph vertically. Menu bar: File (New… Ctrl+N —
unsaved-changes gate (Save / Don't Save / Cancel, also guarding Load),
fresh 1 s force/time doc, empty history, "Untitled"; Save Ctrl+S writes
straight to the OPEN file, Save As… re-targets; the WINDOW TITLE is the
open file name with * while dirty; +
Export NL2 CSV — rebindable "export" action Ctrl+E; modal dialog with
rail-center-vs-heartline radios and a segment-length spinner, both persisted
per-user in AppSettings; spacing rounds to a whole number of equal segments;
the CSV negates Z for NL2's right-handed world — see D40 before touching
signs), Edit
(Shortcuts dialog — TWO titled category columns since v10.3 (Graph
editor | View/File/History, ACTIONS carries the category as its 4th
field); rebind by clicking + pressing a key, ↺ resets an edited
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
centers the pivot on the SELECTED node's track point AND yaws the lens
TANGENT to the track's heading there (perpendicular look direction,
nearer of the two sides kept; no selection or vertical tangent = world
X–Y elevation; D53) (selected markers
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
the rails), Start speed spinner 1–100 m/s default 26 — the PIECE rows
(x/y/z ±500 m + yaw ±360°, the ACTIVE piece's placement, re-synced on
piece switches; saved per `piece` line; y = height above the y=0
ground, default 21) MOVED to the toolbar "Placement ▾" dropdown next
to the piece selector in v13.2 (D74), along with "Reverse piece" —
the Coaster menu keeps only truly per-coaster knobs. Start speed is
saved as the `v0` line and is ALSO editable in the first node's
double-click
dialog — the dialog writes through the spinner. Rail color:
G-force gradient or solid — the ColorPicker lives in its own dialog, NOT in
the menu: embedded there, "Custom Color" crashed because its owner window
was the auto-hiding menu popup; the same dialog customizes the FOUR
G-force gradient stops — each a COLOR and a G VALUE spinner, defaults
0/1/3/5 g, flat outside the ends and lerped between stops, saved as
`gradcolors` + `gradstops` lines). Toolbar: lane toggles, Ripple time/value
checks, "Resulting" popup (persisted per-user; a dark hover-dismissed Popup of
CheckBoxes since v10.3 — stays open while picks are clicked, closes
250 ms after the mouse leaves both it and the button, re-entry cancels;
Popup's default consumeAutoHidingEvents makes the button click-to-close
work. v10.4: the hide timer VERIFIES the real cursor via
Robot.getMousePosition and re-arms instead of hiding while it's inside
— JavaFX skips MOUSE_ENTERED when the popup appears under an
already-moving mouse, which let the button's exit-timer hide it
mid-hover. MODE-AWARE since v10.2 — the two entries retitle with the active
section: over a FORCE section they are the resulting pitch/yaw rates
from FVDIntegrator.bodyRates wearing the GEO palette; over a GEO
section the felt VERTICAL/LATERAL G in the FORCE palette. Color rule: a
curve wears its QUANTITY's palette. v10.3: enabled overlays share ONE
RESULTING SCALE — fitted like lane scales, defaults ±60 °/s / −1..4 g,
frozen during drags — labeled on a RIGHT-EDGE axis (ticks + rotated
title; marginRight() widens 14→48 px while shown; the roll lane no
longer auto-fits overlays), and hovering an overlay shows a dot + its
value in the curve's color (insert dot keeps priority; Shift swaps in
the numeric slope, v10.4). Roll is
deliberately NOT
offered — the resulting roll rate is the authored roll lane),
POV, Append (no shortcut hints on buttons — the stats bar hint is
generated from live bindings); the Resulting overlays span the WHOLE
ride since v10.8 (v10.1-v10.7 drew them over the active section
only). Stats bar shows ride time, track length,
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
  normal tie spacing). The tie grid anchors at each PIECE's starting arc
  length (v13.3 — per-piece meshes start mid-ride; a zero anchor put a
  tie on every sample until it caught up).
- Undo/redo exclude tie spacing and rail colors (cosmetics; menu-local
  Ctrl+Z only — but they do mark the file dirty).
- Friction is hardcoded (0), not in the UI. (v0 became the per-coaster
  Start speed setting in v7.9; like heart/gauge it is NOT in undo.)
- A straight/curve section's duration is rarely a whole number of dt
  steps, so samples DOWNSTREAM of one sit off the 0.01 s grid by a
  constant sub-sample phase. POV/marker code indexes time as t/dt
  (nearest sample) — the lag is < 10 ms of ride and invisible, but
  don't add code that assumes sample i sits exactly at i·dt.

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
- ~~Milestone A~~ DONE v9.0 (D50): document model (Coaster/TrackPiece/
  Section/ForceSection/TrackPose/Quat), TrackCompiler three-pass
  pipeline (bit-identical output gated), DesignIO with FVDD3.
- Milestone B: ~~document-level undo~~ DONE v9.1 (D51, incl. heart/gauge/
  start/v0 per user rule) + ~~file lifecycle~~ (New/Save/Save As/dirty
  title — a user feature request folded in); ~~FvdDesigner split~~
  COMPLETE v9.4 (D52 GraphEditor, D54 Viewport3D + Dialogs; shell
  4.2k → 1.1k lines); ~~section-aware graph + '+' add-section~~ DONE
  v9.5 (D55).

PHASE 1 IS COMPLETE (v9.5). The '+'
menu and a future piece selector are where "attach floating piece" and
multi-piece editing land (user roadmap in SECTIONS-DESIGN §8).

**Phase 2 — section types (easiest first):** ~~geo sections~~ DONE
v10.0 (D58: LaneSection base, quaternion-native GeoIntegrator,
mode-aware editor, `section geo` in FVDD3, gated TESTING #12 incl. the
2 mm force/geo equivalence canary) + UX rounds v10.1-v10.4 (D59-D62:
unified view, zero-line fills, geo palette, section badges + delete,
boundary return (Shift+B = all lanes), insert-drag, end-wall/end-snap,
snap axis toggles,
purple time, mode-aware Resulting with right-edge axis + hover/slope,
hover-dismissed Resulting popup (cursor-verified, seam-padded),
categorized Shortcuts dialog, straight-lock knob
hiding + honest unlock, H re-deal + LEVEL LOCK v10.5, pair-priority
lock toggles + multi-segment F + two-ended level v10.6, right-click
node dialog + segment lock menu + Ctrl-snapped inserts v10.7,
full-ride Resulting overlays + Gv/Gl color swap v10.8 — the
D62 C1-preserving re-timing was reverted in v10.5) →
~~reversal~~ DONE v10.9 (D67: badge-menu toggle, canonical generation
+ yaw placement, Gl negates / roll invariant, gated TESTING #13) →
~~straight/curve mode~~ DONE v11.0 (D68: element list + strip widget
replacing the lanes, sine transition smoothing from day one,
ScIntegrator with breakpoint sub-stepping, `section sc` in FVDD3,
compiler Spans, gated TESTING #14; s/c roll modes + drag-extend +
"Append" v11.1, D69) →
~~split/reconnect + piece selector~~ DONE v12.0 (D70: badge-menu
split; '+'-menu attach; toolbar piece selector; Piece x/y/z + yaw
spinners edit the ACTIVE piece; gated TESTING #15) + v12.1 (D71:
splits carry the FULL boundary orientation via TrackPiece.baseOrient
— bitwise at ANY boundary, user rule; REVERSAL became a piece
operation — Coaster menu > Reverse piece, a model transformation
that keeps the track put; the per-section badge check is gone.
3D DRAG GIZMOS
still to come — spinner placement shipped first) →
~~bridge sections~~ DONE v13.0 (D72: quintic Hermite + RMF/eased-
twist orientation, '+'-menu create, tangents dialog, gated TESTING
#16) + v13.1 (D73, user redesign: LIVE-FOLLOW — the compiler
re-captures endpoints+curvatures every compile, freeze only for
orphaned bridges; G² ends; bridge to ANY piece with timeline
reorder; "Optimize placement" badge action — closed-form §4 mode 2;
plus viewport fixes: meshes break at piece gaps, plain-click
activates a piece) + v13.2 (D74, seam smoothness + piece-UX round:
SEPTIC ends + speed blend + roll-rate twist = rate graphs continuous
in value AND slope at seams; toolbar Gap spinner auto-optimizing per
tick; piece placement/reverse moved to the toolbar "Placement ▾"
dropdown; per-piece meshes with inactive pieces DESATURATED; bridged
seams abut flush = one visual track) →
piece placement 3D gizmos (pitch editing user-approved if revertible
— baseOrient already separates the natural orientation from edit
deltas) → mid-section piece split (node-anchored: select a node,
split the section's lanes there — de Casteljau, user-confirmed) →
influence
curves + "become real" (direct attach REMAINS first-class next to
bridges, user decision) → TIME/DISTANCE domain toggle for force/geo
lanes (author over meters instead of seconds; the graph x-axis
follows the active section's domain — user-confirmed on the list
2026-07-06; the Domain slot has been in the model/FVDD3 since
Phase 1, and s/c strips already lay out DISTANCE sections by their
compiled time extent, which is the precedent the toggle builds on).

**Phase 3 — dynamics:** speed-controlled sections (constant-v /
constant-longitudinal-G / curve), air + rolling friction.

**Phase 4 — interop & full interactivity:** NL2 CSV import (external-
geometry sections), viewport editing part 2 (drag values/times/handles in
3D), Euler display toggle.

**Small items, anytime:** spine box in the rail mesh (copy
`buildTieMesh`), friction spinner (subsumed by Phase 3).
