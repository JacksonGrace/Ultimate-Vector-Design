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

## 3. Speed model

Energy conservation relative to the *start* height, then a crude quadratic
friction bleed:

```
v_ideal = sqrt( v0² + 2·g·(y0 − y) )
v = max(0.1, v_ideal − friction · v_ideal · dt)
```

Deliberate simplifications (fine for a designer, revisit for realism):
friction isn't path-integrated (it's recomputed from height, so it doesn't
accumulate losses properly over undulating track), and there's no rolling
resistance / air-drag split. `vSafe = max(v, 0.5)` prevents ω → ∞ near
stalls; if a design nearly stalls on a hill crest the result is garbage-but-
finite rather than NaN.

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
