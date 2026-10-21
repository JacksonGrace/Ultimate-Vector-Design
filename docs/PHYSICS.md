# PHYSICS.md — Math & derivations for the FVD integrator

This documents the physics model in `FVDIntegrator.java` and the geometry in
`FvdDesigner.buildRailMesh` / `applyPovCamera`, including *why* each choice
was made. Read this before touching any of that code.

## 1. The moving frame

At every instant the train has an orthonormal frame:

- **T** — forward (unit tangent of the heartline)
- **U** — up, *as the rider experiences it*. This is NOT the Frenet normal;
  it is defined by the accumulated roll the designer commanded. This is the
  crucial difference from textbook curve theory and the reason banked track
  works.
- **R** — right. Convention chosen: `R = U × T`, giving the cyclic set
  `T×R = U`, `R×U = T`, `U×T = R`.

Initial conditions: `T = startHeading` (default +X), `U = (0,1,0)`,
`R = U×T`. World +Y is up.

Rotations use Rodrigues' formula (`Vec3.rotate`), and T/U/R are re-normalized
after every step to fight numerical drift.

## 2. Time-domain force equations

The tool is time-domain (v2 rewrite; matches FVD++). For a point moving at
speed `v` whose direction rotates with angular velocity `ω` (rad/s), the
centripetal acceleration is `a = v·ω` (compare arc-length form `a = v²·κ`;
`ω = v·κ`).

The G-force a rider feels along an axis is the track's reaction force minus
gravity, in g's. Solving for the angular velocity that produces a target:

```
ωU = g·(Gv − U.y) / v      rotation of T toward U   (pitch-ish)
ωR = g·(Gl − R.y) / v      rotation of T toward R   (yaw-ish)
```

The `U.y` / `R.y` terms are the components of gravity along the car's up and
right axes (world up is +Y, so `U·(0,1,0) = U.y`). Sanity checks that fall
out correctly:

- Level, straight, unbanked: `U.y = 1`, so `Gv = 1` gives `ωU = 0` — track
  stays straight while the rider feels exactly 1 g. ✓
- `Gv = 0` produces a ballistic parabola (free fall). ✓
- `Gv = 1` on a descending track (`U.y < 1`) gives a small upward curvature —
  the track SLOWLY self-levels toward equilibrium at level. It does not stay
  at constant pitch. This surprised us during tuning; don't "fix" it, it's
  physical.

Combined angular velocity applied per step:

```
Ω = ωR·U − ωU·R + rollRate·T          (rollRate in rad/s)
rotate T, U, R about Ω̂ by |Ω|·dt
pos += T · v · dt ;  s += v · dt
```

The sign `−ωU·R`: rotating about −R takes T toward U (verify with the cyclic
products above). Getting this sign wrong flips pull-ups into dives.

## 2b. Geo sections: direct body rates (Phase 2, `GeoIntegrator.java`)

GEO sections skip the force inversion entirely: the designer authors the
body rates themselves (pitch/yaw/roll, deg/s) and the integrator applies
them directly —

```
Ω    = yaw·U − pitch·R + roll·T       (rad/s, from the authored lanes)
q'   = ½ · (0, Ω) ⊗ q                 (orientation as a unit quaternion)
pos' = T · v                          (v = design-speed energy profile, §3)
```

Signs match the resulting-rate extraction (`pitch = T'·U`, `yaw = T'·R`,
`roll = −U'·R`): +pitch pulls up, +yaw turns rider-right, +roll matches
the roll lane. Gravity plays NO role in the shape — a zero-rate geo
section is a straight line whatever its orientation; gravity only shows
up in the felt forces reported downstream, which are the §2 equations
inverted: `Gv = ω_pitch·v/g + U.y`, `Gl = ω_yaw·v/g + R.y` (recorded per
sample at the design speed). Orientation integrates NATIVELY as a
quaternion (classic RK4 on (pos, q), q normalized after each step and
inside each stage) — the SECTIONS-DESIGN §2 amendment: geo gets the
quaternion path while the force integrator keeps its bit-for-bit-gated
T/U vectors. Validated: constant-rate sections produce exact angle
changes (rate × last-sample t, independent of the speed profile), and
re-authoring the default layout's RESULTING body rates as a geo section
reproduces the force-built geometry to 2 mm over 407 m (end frame within
0.001°) — the strongest cross-check of the sign conventions we have.

## 2c. Straight/curve sections: rotation rate per meter on a roll-free axis (v11.0, `ScIntegrator.java`; v20.0 D259, `ElementChain.java`)

STRAIGHT/CURVE sections author geometry in DISTANCE: an element list
(STRAIGHT: arc length L | PLANAR: L, signed curvature k0 → k1, plane
direction dir | HELICAL: L, plan curvature c_h, end elevation β1)
defines a ROTATION-RATE VECTOR profile over arc length, in WORLD
coordinates —

```
PLANAR    Ω/m = −k(s) · a          a = cos(dir)·ul + sin(dir)·rl   (world-fixed per element)
HELICAL   Ω/m = −c_h(s)·cos β(s) · Y  +  β'(s) · h(T)             h(T) = (−T.z, 0, T.x)/|T_xz|
STRAIGHT  Ω/m = 0
Ω    = v · Ω/m  (+ the interim roll term about T)       (rad/s at the design speed)
q'   = ½ · (0, Ω) ⊗ q,   pos' = T·v,   s' = v
```

where ul = (Y − (Y·T)T)/|…| is the LEVEL up at the element's ENTRY
tangent T and rl = ul × T (the project's R = U × T). The axis is read
from the ENTRY frame once and stays fixed (D2, the ROLL-FREE axis): the
body roll — the bank — never enters it, so a banked or pitched entry no
longer tilts a turn's plane. (The v11.0 law read the axis off the
ROLLED body frame per RK4 stage, cos·U − sin·R, which made a banked
R20/90°/30° turn dive 10.9 m; it survives one stage behind
`-Dfvd.sc.legacyAxis=1` for the gate that pins its bits.) Rotating about
a fixed axis leaves the axis invariant, so each constant-κ element is an
EXACT circle or line: dir 0 = flat turn, +κ = rider-LEFT (D187, matching
the reported-left yaw); dir 90 = pull-up; a ramped k0 ≠ k1 (linear or
sinusoidal over the track arc) is a Fresnel/Anger-class spiral the
integrator handles like any Ω(s). The helix rotates about world Y at
the plan rate c_h·cos β per track metre and about the horizontal right
h(T) at the elevation rate β' — a circle of plan radius 1/|c_h| whose
elevation runs β0 (the entry's) → β1; both rotations preserve the bank
vs the horizon. Since pos' and q' both scale by the same v, dq/ds is
speed-independent — the SHAPE cannot depend on the speed profile, only
the time mapping does. The shape IS gravity-referenced through the
entry's level frame: a baked entry pitch reshapes an s/c exactly as it
re-solves a force section.

ELEMENTCHAIN is the closed-form forward map of the same chain (no RK4):
STRAIGHT p0 + T·L; PLANAR p0 + L·[T·sinc Θ + N·(Θ/2)·sinc²(Θ/2)] with
N = T × a, Θ = ∫k, q = aboutAxis(a, −Θ)·q0 (a fixed composite 8-node
Gauss–Legendre on 0.5 m panels of (cos θ(s), sin θ(s)) for a ramped k,
θ(s) closed); HELICAL s_h = L·cos β̄·sinc(Δβ/2), Δy = L·sin β̄·sinc(Δβ/2),
Δψ = c_h·s_h (constant c_h, linear β; a nested GL-8 otherwise). It
supplies the integrator's axes and entry elevations, the SHARP border
poses of the per-border `Bound` record, and (from Stage 2a) the solver
and the append ghost — the ONE evaluator.

TRANSITION SMOOTHING blends the Ω/m VECTORS of the two neighbours with
a sine ease `(1 − cos πu)/2` over the entry element's blend length,
CENTERED on the border. The ease is symmetric, so the blend integrates
to the mean rotation and a turn's TOTAL ANGLE is preserved exactly
through straight↔arc transitions (one fixed axis; arc↔arc with
different dir mixes axes — approximately preserved). Blend halves clamp
proportionally when they'd overlap inside a short element.

INTEGRATION ACCURACY: RK4 loses two orders stepping OVER a curvature
step. ScIntegrator SUB-STEPS: every RK4 leg lands exactly on the next
leg boundary — EVERY non-merged element border (its centre too under a
blend), every blend edge and the section end — via Newton-sized leg
lengths, and the profile lookup of each stage is capped to the leg's
own side at KINKS (sharp borders). Adjacent SHARP elements of equal
constant κ, equal axis (within 1e-12) and equal interim roll MERGE into
one leg run whose end arc is start + (L₁ + L₂ + …), so a split that
keeps L₁ + L₂ == L bitwise reproduces the parent's bits. Samples still
land on the uniform dt grid. Validated (TESTING #14 + #176/1a): exact
line/circle/helix against ElementChain to 1e-7 m on level, pitched and
banked entries, sharp chains analytic to 1e-6°, the clamped blend
fixture to 3e-6° at dt 0.01 (exactly 0 at dt ≤ 0.005 — a step phase of
the centre landing), blendIn 0 bitwise equal to sharp.

ROLL — STAGE-1a INTERIM (v11.1, D69; Stage 1b replaces it with a roll
LANE per element, D4 as revised 2026-10-21): per element, two modes.

- RATE (°/s): ω_T = rate·T joins Ω — a TIME rate exactly like the roll
  lane, deliberately NOT v-scaled, blended across borders with the
  quintic smoothstep (D233). Rate 0 leaves the frame purely
  rotation-driven: with the roll-free axis the bank vs the horizon no
  longer drifts through a flat turn, only through a pitched one.
- GLOBAL (°): the value is the BANK ANGLE VS THE HORIZON, held by an
  algebraic projection after every integration leg: measure the bank as
  the angle of U against the level up at T, about T — positive matches
  the roll lane — then roll the quaternion about T by the difference. A
  pure T-roll moves neither pos nor T, so the projection CANNOT bend the
  spine. The target eases from the element's measured ENTRY bank over
  max(blendIn, 2 m) (element 0: its blendIn verbatim — the interim
  section-entry ease) with the quintic; corrections < 1e-12 rad are
  skipped; near-vertical track (1 − T.y² < 1e-6) is left untouched; a
  merged run eases once, at its leader.

## 3. Speed: energy minus path-integrated friction (Phase 3, v17.0 D133)

Speed is energy conservation relative to the *start* height minus an
accumulated **loss deficit** (v² units) that rides the RK4 state:

```
v = sqrt( max( v0² + 2·g·(y0 − y) − loss, 0 ) )
d(loss)/ds = dir · 2·( fricRoll·g  +  fricAir·v² )      [per meter]
d(loss)/dt = d(loss)/ds · v                              [the ODE form]
```

The two per-coaster coefficients (SECTIONS-DESIGN §7; NL2's "friction"
and "air resistance"): **fricRoll** is dimensionless — a constant
deceleration fricRoll·g per meter (the first cut deliberately skips
normal-force scaling) — and **fricAir** is 1/m, deceleration
fricAir·v². Closed forms on flat track (both gated to float noise):
rolling only ⇒ v² = v0² − 2·μ·g·s; drag only ⇒ v = v0·e^(−k·s).

Both coefficients 0 keeps `loss` exactly 0.0, and `x − 0.0` is
bitwise `x` — the entire pre-friction behavior is preserved
bit-for-bit (the old per-second "friction bleed" stub was inert and
is gone). Pass D marches the same deficit over the assembled samples
per run (Heun steps, always in TRAVEL order); converts, the influence
two-pass and the bridge's blended profile each march their own copy so
every re-derived profile agrees with the integrators.

REVERSAL under friction (v17.0, D134 — reversal is a CONVERT): with
losses, the speed at a point genuinely differs by travel direction,
so no fixed authored profile can serve both ways. "Reverse piece"
therefore REBUILDS: Coaster.reversedSamples mirrors the compiled
geometry in place (T/R flip, U kept, felt Gl negated — the D67
identities; per-sample v kept so the κ decode from felt stays exact),
reversedProfile marches ONE energy flow from the recorded end speed
(or a user-typed one) with the same Heun deficit, and each section's
span re-fits as ordinary forward lanes at the flow speed at its
entry — the result is conflict-free by construction and matches the
original shape to the fit residual, friction included. Corollary of
"friction is paid both ways": a piece reversed at its natural end
speed often cannot climb home and the reversed flow STALLS — the
preview says so; more entry speed is the fix.

`vSafe = max(v, 0.5)` still prevents ω → ∞ near stalls; a genuinely
stalling design (friction makes this easier to hit) creeps through at
the floor and reads garbage-but-finite rather than NaN — real stall /
rollback physics stays future Phase-3 work (speed sections).

## 4. Validations performed (keep these as regression tests)

1. **Analytic circle.** Flat unbanked turn, constant v and Gl: expected
   radius `r = v²/(g·Gl)`. With v=20, Gl=1.5 → r=27.192 m, circumference
   170.855 m. The distance-domain integrator closed the loop within **1.5 cm**
   with heading dot-product 1.000000 (ds=0.02).
2. **Banked-turn theory match.** For a level turn at bank φ with zero felt
   lateral G, theory says `Gv = 1/cos(φ)`. Bisecting Gv for "ends level"
   at φ=40° through the full pipeline gave **1.297 vs theoretical 1.305**
   (<1%). This cross-checks the ω equations, the roll accumulation, and the
   frame conventions simultaneously.
3. **Default layout invariants.** The shipped layout must end with
   |pitch| < 1° and U.y > 0.999 (level and unbanked). Currently: pitch 0.2°,
   U.y 1.000, 13.46 s, ~407 m.

## 5. Heartline → rails geometry

The integrated path is the **heartline** — the designer's Gv/Gl are the
forces at the rider's heart, so that is the curve the physics lives on.
Track structure is derived per sample:

```
spineCenter = pos − U·heartOffset            // heartOffset default 1.0 m
leftRail    = spineCenter − R·(gauge/2)      // gauge default 1.0 m
rightRail   = spineCenter + R·(gauge/2)
rail radius = 0.07 m (140 mm diameter)
```

`heartOffset < 0` puts rails ABOVE the heart ⇒ inverted coaster, and every
downstream feature (ground placement, POV) works unchanged because it's all
frame-relative. Rails are extruded as rings in the (R,U) plane per sample
(stride 3 of dt=0.01 samples, 8 sides), with a 1-D gradient texture mapping
Gv → color (blue <0 g, green ~1 g, yellow→red high g) via per-ring texcoords.

## 6. JavaFX coordinate flip — read before touching 3D code

JavaFX screen space has **+Y down**. World space has **+Y up**. The mesh
builder emits `(x, −y, z)` for every vertex, and ALL camera math must flip
consistently. Define `flip(v) = (v.x, −v.y, v.z)`.

**POV camera.** A JavaFX `PerspectiveCamera` looks along its local **+Z**,
with local **+Y = screen-down**, local **+X = screen-right**. To orient it
along the ride frame, build a rotation whose columns are:

```
c2 (local +Z, view dir)  = flip(T)
c1 (local +Y, down)      = −flip(U)
c0 (local +X, right)     = c1 × c2       // completes right-handed basis
eye                      = flip(heartPos)
Affine(c0.x, c1.x, c2.x, eye.x,
       c0.y, c1.y, c2.y, eye.y,
       c0.z, c1.z, c2.z, eye.z)
```

Worked identity check (T=+X, U=+Y): c2=(1,0,0), c1=(0,1,0),
c0=(0,0,−1) = flip(R) since R=U×T=(0,0,−1). ✓ on paper — but this is the
least runtime-verified code in the project. Symptom→fix table: view upside
down ⇒ c1 sign; mirrored ⇒ c0 (cross-product order); looking backwards ⇒ c2
sign.

The POV camera node lives INSIDE the `world` group so the one-time centering
`Translate` applies to it exactly as to the track mesh. Moving it out breaks
POV positioning.

## 7. Why bake Beziers to linear keyframes

Alternatives considered: (a) evaluate Beziers inside the integrator — rejected
because solving x(t)=time for a cubic each step is wasted work and couples
the physics to the editor; (b) sample per-segment at t-uniform points only —
rejected because the three lanes have DIFFERENT handle layouts, so their
natural sample times differ; a shared uniform grid (0.05 s) keeps the
integrator's merged-keyframe interface. The grid is dense enough that linear
interpolation between grid points is invisible in the output.

`bakeLane` guards monotonicity (`t[i] = max(t[i], t[i−1]+1e−6)`) because
sufficiently extreme handles can fold a cubic's time-axis back on itself;
the guard turns an invalid function into a clamped valid one instead of
corrupting the keyframe ordering. Fuzz-tested with 200 randomized layouts
including deliberately out-of-range handles: zero violations.
