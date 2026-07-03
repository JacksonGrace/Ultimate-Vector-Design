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
                    (t, Gv, Gl, rollRateDeg), steps dt=0.01s, produces
                    Samples (t, s, pos, T/U/R frame, v, Gv, Gl).

FvdDesigner.java    ALL JavaFX. 3D view (two-rail mesh, orbit + POV cameras),
                    graph editor canvas, toolbar, save/load, undo.
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
  Combined `Ω = ωR·U − ωU·R + rollRate·T`, frame rotated by `|Ω|·dt`
  (Rodrigues), then `pos += T·v·dt`.
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
- **Handle semantics:** stored as offsets (dt seconds, dv value-units).
  Defaults: flat tangents at 1/3 segment ⇒ smooth S-curve force transitions
  (what coaster designers want). "Smooth" = matching slope dv/dt on both
  sides (unit-correct C1, deliberately NOT pixel-angle matching). Default
  drag changes handle LENGTH only (pixel-space projection onto current
  direction); SHIFT unlocks angle. B toggles smooth/broken per node;
  re-smoothing keeps the IN handle and re-angles the OUT handle (user spec).
- **Undo:** deep snapshots of all lanes, one per completed gesture, cap 100,
  no redo yet. Heart/gauge settings are NOT in undo.
- **Save format** `FVDD1`: human-readable text, `Locale.US` decimals
  (critical — default locale breaks parsing on comma-decimal systems),
  unknown lines ignored for forward compatibility. Round-trip tested.
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
were run via a single-file concatenation trick; in this repo they should be
converted to proper JUnit. Validated: circle closure (1.5 cm error over a
171 m analytic circle), default layout ends level (pitch 0.2°, U.y = 1.000),
200-trial fuzz of baking with pathological handles (0 violations), save
round-trip (0 mismatches).

**Least-verified code:** the POV camera affine (sign conventions), toolbar
key/focus interactions, spinner listeners. If the POV view is rolled 180° or
mirrored, it's a sign flip in `applyPovCamera`.

## Current UI feature set

Single overlaid plot (each lane on its own value scale; inactive lanes 25%
alpha, mouse-transparent). Keys: `1/2/3` lane select · click node = select +
show handles · drag handle = length-only, `Shift` = angle · `B`
smooth/broken (circle vs square knobs) · double-click add node · right-click
delete · `A` append 1 s holding final values · `R` POV ride cam (loops,
follows live edits) · `Ctrl+S/O` save/load · `Ctrl+Z` undo. Toolbar:
lane toggles, Heart spinner (−3..3, default 1.0), Gauge spinner (0.3..2.5,
default 1.0), POV/Append/Save/Load. Stats bar shows ride time, track length,
height range, top speed, Gv range. Window sizes itself to
`Screen.getVisualBounds()` (capped 1400×880) — do not hardcode window sizes;
that broke on the user's 1080p screen once already.

## Known quirks / accepted debts

- Adding a node mid-segment slightly changes the local curve (no de Casteljau
  split yet — that would preserve shape exactly).
- Extreme handle angles can fold a curve; the baker's monotonic-t guard
  flattens it, so drawn curve and baked result can differ at pathological
  settings.
- `interpAt` in the integrator is an O(n) scan per step — fine at current
  sizes, binary-search it if rides get long.
- Two bare rails look skeletal (no ties/spine yet — intentional, per spec).
- No redo; undo excludes heart/gauge.
- Friction and v0 (26 m/s) are hardcoded, not in the UI.

## Roadmap (user-endorsed ideas, in rough priority)

1. Cross-ties + spine box in the rail mesh (copy `buildRailMesh`'s pattern)
2. Friction + starting-speed settings (copy the spinner pattern)
3. Redo (Ctrl+Y / Ctrl+Shift+Z)
4. De Casteljau split on node insert
5. Geometric sections (target pitch/heading, like FVD++) — needs a solver
6. Graph editor zoom/pan for long rides
7. Track export (OBJ / NoLimits2)
