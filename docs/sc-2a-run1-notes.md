# Straight/curve overhaul — Stage 2a, run 1 (O11 + O12: ScSolve, the kinematic solver) — notes

Branch `sc-2a-solve`, cut from the committed tree at d93aa6c (v20.1, D259.1b
round 2 — the Stage-1b tree the brief names; the remote's `main` ref still
sits at 9109e2a / Stage 1a, the Stage-1b commits live on
`claude/ecstatic-cray-y35nld`, so the branch base is that commit by hash).
Scope: `docs/SC-OVERHAUL-DESIGN.md` §10.6 items O11 + O12 — `uvd.ScSolve`
(§4.1-§4.12), `uvd.SectionFrame` (§4.2), the §4.14 register appended to
`SectionNames`, `LabelGate.section21()`, `gates/ScSolveGate.java` (§4.15
a-r; s skipped). PURE JAVA: no JavaFX file, no `CLAUDE.md` / `DECISIONS.md`
/ `TESTING.md` / `gates/README.md` / design-doc edit (run 2b's); no edit to
`ScIntegrator`, `TrackCompiler`, `Coaster`, `DesignIO`, `RollLane`,
`ElementChain`, `StraightCurveSection` — no read accessor was needed (the
SINUSOIDAL-pitch J0 series lives in ScSolve as the Newton's SEED only;
ElementChain's quadrature stays the forward authority).

## 0. Environment (Step 0)

Machine: x86_64 Linux container (Linux 6.18), JDK **26.0.2** (Oracle archive
tarball `jdk-26.0.2_linux-x64_bin.tar.gz`, sha256
`11de2bce72e7f07b9e80afa9bdd950b42f3a5c69cf4969e44389d50f466959e2`, verified,
extracted to `/home/user/jdk26/jdk-26.0.2`; the image shipped JDK 21). Every
java/mvn command ran with `JAVA_HOME` + `PATH` pointed at it and
`JAVA_TOOL_OPTIONS=` cleared; the proxy CA bundle (`/root/.ccr/ca-bundle.crt`,
122 certificates) imported into JDK 26's `lib/security/cacerts` (keytool,
aliases `agentproxy-N`) so the first wiped `mvn compile` resolved Maven
Central (run 3's recipe verbatim). Maven 3.9.11.

Step-0 results on the untouched d93aa6c checkout (wiped `target/classes`):

| step | result |
|---|---|
| `java -version` | 26.0.2 |
| wiped `mvn -q compile` | rc 0 (142 class files) |
| `javac -encoding UTF-8 -cp target/classes -d gates/out gates/*.java` | rc 0 |
| uvd.ScElemGate | 122 passed, 0 failed |
| uvd.ScRollLaneGate | 70 passed, 0 failed |
| uvd.ScSeamGate | 37 passed, 0 failed |
| SmoothRealGate | 21; d1 = `28808918c0612fbe` over 1601 samples (20 m `0fc8182da8f4a418`) |
| DetermGate198 | 6 passed |
| DetermGate199 | 10 passed |
| LabelGate | 229 passed |
| DetermGate197, DetermGate200, LoadGate196, Nl2ElemProbe | NOT RUNNABLE here — they open the owner's `FULL TEST COASTER 7.30.26.fvd`: RE-RUN LOCALLY |

Every bitwise pin that could be checked held on this machine.

Enumeration before coding (the standing rule; each cell handle / refuse with
ONE sentence / accept + document) — the §10.6 axes as the gate's fixtures
realize them: kind {STRAIGHT, PLANAR κ > 0 / κ < 0 / κ = 0 / ramped LINEAR /
SINUSOIDAL, HELICAL linked / unlinked / SINUSOIDAL pitch} × lock set (none,
each single, each pair incl. the dependent K0 + TURN + LEN triple, the
three-lock point, x/y/z singles / pairs / triple, HEAD/ELEV on a straight
stored forward) × target class (in / out of the reachable set, the backward
ray, the torus core, inside the previous circle, on the sphere,
antiparallel, parallel, under the length floor) × lap (−1, 0, +1, the 3-lap
sweep) × entry (level / pitched / yawed+pitched / banked = a no-op on the
roll-free axis / vertical → the pole refusals) × straight context (no curve
before, one straight, two straights of total Λ, a helix before with β1
fixed / free, a ramped curve before) × absorber (none, free, DIR-locked,
TURN-locked, helix linked / unlinked, a locked X/Y/Z beyond, the absorber's
own end locked) × tail (lone straight, one curve, curve + straight, two
curves, s-c-s in / out of plane, a helix among A/B, a locked A or B) × the
predictor (δ = 0 bitwise / a 2 m blend) × the frame (run head, yawed +
translated copy, pitched baseOrient; the automatic / locked follower and
bridged-in cases are SectionFrame branches without a rig until 3a) ×
determinism (re-solve bitwise, rebuild no-op, save → load → rebuild).

## 1. Item O11a — the API, the forward map, the curve and straight nodes (65f90fe)

`ScSolve.ElemParams` (record; `of(Element)` / `of(List)` / `of(sc)`,
`defaultOf(kind, β0)` = §1.1's defaults, the `with…` copies, `applyTo` —
every geometry field verbatim and the length through `Element.setLength`,
`toElement` = the scratch element of every forward and the list ops' new
element), `Frame(p, T, U, R)` (`of(Pose)`, `level(p, T, upIfVertical)`,
`pose()`, `axis(d)`, `N(d)`, `dirOf(a)`, heading / elevation / vertical),
`Target` (sealed: `Point`, `Tangent`, `Pose`), `Hint(θ, dir, sign κ, lap)`
(`of(e[, β0])`), `Solution(params, status, why, residualRot, landedPos,
iterations, insertedAt, deletedAt)`, `LandedProbe`, `Chart`, `Box`, `Ring`,
`Arrow`, `TailMode`. `forwardEnd` / `frames` go through
`ElementChain.advance` on scratch elements — the closed forms in the class
are inverses only. IDEMPOTENCE: every node solve first asks whether the
current params already land on the target (`AT_TARGET` 1e-9 m) and returns
them unchanged, and every projection carries an "already on the set" guard
(`ON_SET` 1e-12 relative) — the base of ScSolveGate e / n.

The in-plane arc core (`Arc2`): `inv1` (free κ, θ; the horn-torus core →
the κ_max circle, the backward ray → the κ_max circle on the hint's side +
`REACH_RAY_WHY`, the MIN_ELEM_LEN floor by scaling the point out along its
own ray — φ kept, κ shrinks), `invK` (the circle, lap-continuous), `invTheta`
(the cone's generator, κ clamped to [floor, κ_max]), `invL` (the bearing
surface), `invFixed`. The 3-D planar rows (`solvePlanar`): the free-dir
representation pick (`pickRep`: (dir_az, +) vs (dir_az + 180, −) nearest the
stored dir — the pass-through-straight branch), x/y/z-only → the affine
projection then I1, HEAD + ELEV → I2 then the Θ+dir ray (`rayRow`), the
mixed row → `goldenMixed` (u = θ, or L when the turn is locked; the inner
closed solves `dirsForAngle` / `dirsForCoord` / `kappaForCoord`; ≤ 40
evaluations) with the DEGENERATE case split out (`degenerateCons` /
`degenerateMixed`: the constraint direction ∥ T — θ from the constraint, κ
per the locks, dir free on the end-point circle, the heading's forward
branch by mirroring), a lone HEAD / ELEV lock holds κ (D14), the local
rows (dir-locked / vertical-entry plane, κ, Θ, L, the chord circle), rank ≥
3 → ALL_LOCKED_WHY / POS_LOCKED_WHY. `solveRampedPlanar`: dir from the
azimuth, k0 held, Newton on (k1, L) from the element's CURRENT pair first,
then the constant-κ seed (each ≤ 20 steps, central differences, halving ≤
6); failure → PROJECTED to the seed + RAMP_SOLVE_WHY. Rings (`solveRing`:
TURN / DIR / HEADING / ELEVATION; I2 = `inv2` with a = unit(T1 × T), the
antiparallel and PARALLEL_WHY / VERTICAL_WHY cases; a straight's ring acts
on the curve before it, NO_CURVE_BEFORE_WHY without one) and arrows
(`solveArrow`: TANGENT = L, RADIAL = the end radius, linked / unlinked).
The HELICAL node (`solveHelix`): I9 (plan I1 → c_h, Δψ lap-kept, L_h; β1 =
2·atan2(Δy, L_h) − β0; L = L_h / (cos β̄·sinc(Δβ/2))) with the K0 (plan
circle), TURN / HEAD (plan cone), LEN (β1 from the plan length by
bisection), B1 / bLink / ELEV (the height implied), x/y/z variants; the
SINUSOIDAL pitch seeded by the J0 closed form (`planFactor`) and polished by
`newtonHelix` against ElementChain's quadrature; ramped c_h by
`solveRampedHelix`; `solveHelixRing`. The STRAIGHT node (`solveStraight`):
I16 with no curve before, the ray rows (a fixed tangent, a locked j end,
the fully locked curve; Θ+dir locked → ℓ held at its floor and 1/κ along
the ray), `inv4` (κ kept) with the hand-off to `inv5` at the floor, `inv4Held`
(κ locked / ℓ held: the angle alone), `inv5` (the sphere → the κ_max
boundary by the closed scale factor), `inv6` / `inv6Held`; after a HELIX
(`straightAfterHelix`: the plan I4 closed, the height implied for a fixed
β1 / one monotone bisection for a free β1); after a ramped curve
(`straightAfterRamped`: Newton on (L_j, ℓ)). `project` = the rows without
the absorb; `solveNodeDrag` = the rows + `absorbAfter`.

§10.6 cells: every kind × lock set × target class of the table above
through ScSolveGate a-f, j, m, n; the entry classes through a / b / g.

## 2. Item O11b — typed boxes, the lock model, the absorb, the list ops (65f90fe)

`solveTypedBox(els, entry, k, Box, value, chart, hasUpstream)`: a locked
box never writes (FULLY_CONSTRAINED_WHY); X / Y / Z = the current end with
one canonical coordinate replaced through `project`, a PROJECTED landing =
REFUSED with the row's sentence (REACH_RAY_WHY behind the start,
INSIDE_CIRCLE_WHY / SPHERE_WHY / HELIX_STRAIGHT_WHY on a straight, else
`unreachableWhy(elementName, boxLabel, value + unit)`); KAPPA (a typed 0 =
L held, Θ := 0; Θ and dir hold → L; LEN locked → κ alone; the unlinked k0),
TURN (κ and dir hold → L, laps verbatim; κ = 0 / LEN locked → κ := Θ/L),
DIR (a straight's goes to the curve before it; none → SEAM_OWNS_WHY /
ANCHOR_OWNS_WHY on `hasUpstream`; a vertical entry → DIR_INHERITED_WHY),
HEADING / ELEVATION (elevation + κ hold → I2 with κ held, LEN locked flips
it to κ; κ = 0 + K0 locked → FULLY_CONSTRAINED_WHY; the pole →
VERTICAL_WHY; a straight's → curve j, PREV_CURVE_LOCKED_WHY when j is fully
locked), LEN (κ, dir hold → Θ; a straight: its length; TURN locked → κ),
K1 (linked → KAPPA; unlinked → Θ, dir hold → L), B1 (c_h, Δψ hold → L;
past B1_MAX → HELIX_ELEV_WHY); then the absorb. `boxValueText` formats the
value with its unit. The lock model: `lockRank` (PLANAR: K0 / TURN / LEN at
most 2 together, DIR, HEAD, ELEV, X / Y / Z; HELICAL: HEAD ≡ TURN, ELEV ≡
B1; STRAIGHT: LEN + X / Y / Z), `dof`, `padlockWhy` (null or
FULLY_CONSTRAINED_WHY; the consistent K0 + TURN + LEN triple allowed).
`absorbAfter(els, entry, k, exitOld)` (k = −1 = the entry): `absorberAfter`
= the first curve after k with TURN or DIR unlocked (a helix with a free
heading or elevation), downstream X/Y/Z locks → REFUSED
`downstreamLockWhy` (the absorber's own locked end → it solves I1 for the
position, its exit-T change the residual), T kept → nothing absorbs, no
curve after k → nothing, no sentence; `absorbPlanar` (the full restore from
the old exit tangent T2 = the old chain forwarded from exitOld — κ KEPT,
the lap count kept, dir in the new level frame nearest the stored one; the
floor L := MIN, κ := Θ/MIN through zero — the parallel case lands there as
a 0.5 m straight-shaped planar; DIR-locked: θ toward T2's projection,
TURN-locked: the cone point nearest T2; a ramped absorber holds its turn by
L alone), `absorbHelix` (pitch-linked: heading only; unlinked: b1 :=
elevation(T2) too); a residual > 1e-9° → PROJECTED + `absorbResidualWhy(c)`.
`insertAt(els, entry, b, kind)` (§1.1's default of the kind — a helix at a
vertical border refuses; a second locked node beyond → `insertBlockedWhy`;
a LOCKED end of element b: the STRAIGHT insert's L = Δ_T ∓ √(2Δ_N/κ − Δ_N²)
nearest 10 m with element b re-solving (Θ, dir) at its κ for the exact
circle point, 0 roots → `insertLockWhy`; the CURVE insert keeps the
default shape and element b re-solves by I1 (κ free), an unreachable or
coincident locked point → `insertLockWhy`; otherwise the absorb at b),
`changeType` (§1.7's mapping; locks LEN / X / Y / Z / HEAD / ELEV always
carried, K0 / K1 / TURN between curve kinds, DIR and B1 dropped with their
kind; HELIX_VERTICAL_WHY / HELIX_ELEV_WHY), `deleteAt` (`deleteLockWhy` on
a downstream position lock, else the absorb at k − 1 from the deleted
element's old exit), `closedTailEditWhy` (NOTHING_TO_MOVE_WHY), `reversedWhy`
(REVERSED_WHY), `apply(sc, solution)` (the list ops add / remove the
element object, every length through `Element.setLength`).

§10.6 cells: the typed boxes per kind × lock through ScSolveGate j; the
absorber classes through h / h2 / i / o; the list ops through o and j.

## 3. Item O11c — the tail solves, the bounded solvers, the plane rule, the corrector (65f90fe)

`solveTail(els, entry, Target, TailMode)`: CLOSE_POS → `positionOnly` (the
last curve by the curve-node rows; trailing straights = the straight-node
solve of the last node with its length held; a lone straight →
LONE_STRAIGHT_WHY; the head at the curve's start → the whole lap nearest
the hint); ALIGN_PREV / CLOSE_POSE → `poseSolve` (a vertical target →
HEAD_VERTICAL_WHY; the last two free planar curves A < B → `biarcSolve`:
control points per t, the positive root of the quadratic, each arc by I1
from its chord (`arcI1`, lap 0), the FIXED tie-break |κ_A(t)| = |κ_A,old|
(A's K0 locked = the lock; A free + B's K0 locked → B's; both locked →
TWO_CURVES_WHY) by bisection on [1e-3, 1 − 1e-3] ≤ 60 to 1e-12, no root →
t = ½ + BIARC_FALLBACK_WHY — and when the t = ½ member leaves the model
(κ_max / MIN_LEN) a bounded golden over t (≤ 40) picks the gentlest
admissible member before any refusal; a locked A or B → ALIGN_LOCKED_WHY
unless the s-c-s route applies; one curve + a trailing straight of free
length → `scsSolve` (the plane test to 1e-9 else SCS_PLANE_WHY, θ and dir
from the tangent pair, I6 for (1/κ, ℓ)); a helical / ramped A or B →
TAIL_KIND_WHY; no curve → ALIGN_NO_CURVE_WHY); SNAP_HEADING /
SNAP_ELEVATION → `snapOne` (two free curves → the biarc with the current
other angle; one free curve + a trailing straight → the square system: dir
= the azimuth of Δ⊥, Θ from `A cos Θ + B sin Θ = 0` on the forward branch
(heading) or `T.y cos Θ + N.y sin Θ = sin θ1` (elevation), then I6; κ > 0
and ℓ ≥ the floor else SNAP_ONE_WHY; a lone curve → SNAP_ONE_WHY). The
bounded solvers: `newton` (≤ NEWTON_MAX 20, NEWTON_TOL 1e-9, halving ≤ 6,
Gaussian elimination n ≤ 3), `golden` (exactly maxEvals evaluations,
GOLDEN_MAX 40), `golden1`, `bisect` (≤ BISECT_MAX 60, BISECT_TOL 1e-12).
`solveTypedPoint(els, entry, k, target, chart, hasUpstream, probe)` = the
predictor's corrector: δ = landed − sharp from the probe, target_sharp =
target − δ, re-measured per round ≤ CORRECTOR_MAX 3, still > 1 mm →
`predictorOffWhy(mm)` as a notice; NO element with a blend ⇒ δ := ZERO and
the probe is never consulted (bitwise the plain path). §4.12:
`orthoTarget` (the cursor point with the node's own depth) and
`solveOrthoDrag` (a helix: Top holds β1, a side view holds c_h; a planar
with κ or Θ locked: dir to ±the view normal when the entry and T lie in the
node's plane, else the golden over dir on the locked curve ∩ the node plane
— κ-locked: the circle's intersection roots, Θ-locked: the generator's;
ORTHO_LOCK_WHY when no admissible intersection exists in the bracket;
everything else → the ordinary rows on the in-plane target).

§10.6 cells: the tail shapes through ScSolveGate k / l / j; the predictor
through r / j.29; the bounded solvers through m / n / k.

## 4. Item O11d — SectionFrame, the §4.14 register, LabelGate 21 (65f90fe)

`uvd.SectionFrame(psiDeg, canon, tilt, entryW, spanIdx, span, reversed,
hasUpstream)`: `of(result, coaster, p, k)` — the entry pair of section k >
0 and of an AUTOMATIC follower's section 0 from the previous Span's
`exitPsi / exitCanon` (`TrackCompiler.canonOf(prev.exit())` without a pair
— the compile's own belt) with the tilt read off the section's OWN Span's
`exitTilt` (the compile stamps the piece's tilt object on every span of the
piece: the IDENTITY object behind an untilted upstream, the inherited
conflict tilt behind a tilted one — the run-3 review's A1 branch); a run
head / a locked or non-automatic follower / a bridged-in piece from
`TrackCompiler.designCanon(piece, pos)` (pieceCanon's bits at zero deltas);
`entryW` = the previous Span's world exit (a continuation) or the piece's
x/y/z. THE LAW `x_c = rotY(−psi)·tilt⁻¹·(x_w − entryW)` (`toCanonical` /
`toCanonicalDir` / `toWorld` / `toWorldDir`), `entryFrame()` =
`ElementChain.entry(ZERO, canon)`, `bounds()` = `Span.scBounds`,
`delta(border)` = landed − sharp, `poseTarget(TrackPose)`. SectionNames:
the §4.14 register appended as ONE block at the end of the class
(`elementName(i)` = "Element n" 1-based, `LOCK_POS_QUANTITY`, `boxLabel(ordinal)`,
the 31 constants + 7 functions verbatim; HELIX_VERTICAL_WHY /
DIR_INHERITED_WHY / HELIX_ELEV_WHY stay section 18's; ALIGN_NO_CURVE_WHY /
ALIGN_LOCKED_WHY (§3.10's texts, not in code until now) and REVERSED_WHY
land here). `LabelGate.section21()` appended and called last from `main`:
57 rows (229 → 286) — every constant, every function on a sample,
`ScSolve.boxValueText`'s three unit forms.

## 5. Item O12 — `uvd.ScSolveGate` (12e5dbe): 71 passed, 0 failed; s SKIPPED

Measured worst values (the battery run on the final head, wiped classes):

| row | fixture | bound | measured |
|---|---|---|---|
| a planar | 2000 random (κ ∈ [−10, 10] incl. 0, Θ ∈ [−3π, 3π], dir ∈ [0, 360), four entry classes; laps within 0.5 rad of a whole turn set aside) | κ / L 1e-12, dir 1e-9°, pos 1e-12 m | κ/L 4.55e-13, dir 1.36e-12°, pos 5.80e-13 m over 1727 draws (727 sub-floor draws skipped) |
| a2 near-lap | the 273 draws within 0.5 rad of 2πk (k ≥ 1) | pos 1e-12 m, params 1e-7 | κ/L 5.01e-09, pos 5.80e-13 m (eps/chord conditioning — deviation 14) |
| a helix | 500 random (β0, β1 ∈ (−85°, 85°), c_h ≠ 0) | c_h 1e-12, β1 1e-9°, L 1e-11, pos 1e-12 | c_h 8.93e-14, β1 1.93e-12°, L 3.48e-13, pos 3.55e-14 m |
| b | R20 90° at dir 0/30/45/90 × level / 20°-pitched + helix R20 90° β 10° → 30° vs ScIntegrator | 1e-7 m / 1e-6° | pos 3.42e-09 m, T 3.61e-10° |
| c | the 3-lap sweep, 720 steps | monotone, step < 10°, no wrap | steps 1.5000°..1.5000°, final Θ 1090.000000°, no wrap |
| d | the forward-ray crossing, 801 steps of 5 mm | \|Δdir\| < 1e-9, sign flips, L continuous | dir 0.00e+00°, flipped, max L step 1.10e-03 m per 5 mm |
| e | 32 PROJECTED fixtures (every §4.4 / §4.5 row) | re-solve bitwise + EXACT | 32 / 32 |
| f | the straight entering the previous circle | floor first (κ bitwise), then κ monotone, landing continuous | switch t = 0.1660, κ const before / floor after / monotone after, every landing exact, jump 1.12e-14 m |
| g | the 12°-pitched fixture vs its copy yawed 37° + (50, 3, −20) | frame round trip + params 1e-12 | drag rotation 0, round trip 0, params 0 (EXACT / EXACT); equal params ⇒ v / s / t / felt bitwise, positions rigid 7.32e-15 m |
| h | the S-bend, node 0 dragged | the absorber's exit T restored, deltas constant 1e-12, residual 0 | exit T 0 (BITWISE on this fixture), delta const 0, end T 0, integrator 5.46e-11 m |
| h2 | the DIR-locked absorber | PROJECTED + absorbResidualWhy | residual 0.3943° |
| i | the S straightened, 401 steps | L ≥ 0.5, κ → 0 continuous, 4 elements kept | min L 0.500, max κ step 7.85e-03 per 0.22°, final κ −1.75e-03 |
| j | 40 fixtures, one per §4.14 sentence | REFUSED / noticed with the exact constant | 40 / 40; ORTHO_LOCK_WHY SKIPPED (deviation 7); B_ELEMENT_WHY UI-only (LabelGate 21ao) |
| k | 200 random pose pairs, Λ ∈ {0, 5}, ℓ ∈ {0, 3} | 1e-9 m / 1e-9 rad, root or fallback | met 200, fallbacks 96, refused 0, 2.52e-12 m / 8.41e-13 rad |
| k 2-D | 50 planar pairs vs the gate's own 2-D biarc | 1e-12 | 7.11e-15, the same fallback flags |
| l | 40 curve+straight tails (heading / elevation snaps), 40 two-curve tails, the lone curve | 1e-9 | 6.73e-14 m / 5.68e-14°, 6.73e-14 m / 4.62e-14°; 3.03e-14 m / 1.19e-12°; SNAP_ONE_WHY |
| m | 100 ramped planar (each end's turn ≤ 270°) | ≤ 20 steps, 1e-9 m, bitwise twice | max steps 5, 8.77e-10 m, 0 failures, bitwise |
| m helix | 50 SINUSOIDAL-pitch helices | ≤ 20 steps, 1e-9 m, bitwise twice | max steps 0 (the J0 seed lands), 3.08e-14 m, bitwise |
| n | κ + heading locked (curve(20, 60°, dir 20)), 50 targets | ≤ 40 evaluations, 1e-6 m of a 200001-point dense minimum, idempotent | 40 evaluations, gap 0.00e+00 m, 50 idempotent |
| n Y | κ + Y locked, 50 targets | same | 40 evaluations, gap 5.51e-08 m, 50 idempotent |
| o | the 1-root straight insert (L = 40), the 0-root refusal, the curve insert, a plain insert | lock holds 1e-12 | hold 3.55e-15 m; insertLockWhy; hold 0.00 m with residual 56.9° noticed; the plain insert restores the exit (0°, residual 0) |
| p | 30 linear + 30 SINUSOIDAL helices vs a 64-node quadrature | 1e-9 m; J0 factor 1e-12 rel | 7.38e-15 m; 7.23e-16 rel; 2.87e-14 m |
| q | one node edit on the default fixture (s/c + an automatic force follower) | rebuild bitwise no-op; save → load → rebuild bitwise | diff null, vDesign stable, landing 5.02e-15 m; diff null, re-save byte-identical |
| r | the 2 m blend on R20 90° | < 1 mm in ≤ 3 rounds; δ = 0 bitwise | δ 4.74 mm, 1 round, miss 0.118 mm; δ = 0 bitwise (probe and no probe) |
| s | the auto-follower RIG | — | SKIPPED: Stage 3a's rig (SectionFrame.of already carries the follower frame; row g exercises the frame on a run head) |

## 6. Deviations from §4 (the contract wins unless the code proved it wrong)

1. **SectionFrame's law has no separate postShift.** `x_c = rotY(−psi)·tilt⁻¹·(x_w − entryW)` with entryW the section's WORLD entry (the previous span's exit, or the piece's x/y/z). Proof: the compile's world point is `x_w = postShift + anchor + tilt·(place(x_c) − anchor)` and the world entry `entryW = postShift + anchor + tilt·(place(0) − anchor)`; subtracting, `x_w − entryW = tilt·rotY(psi)·x_c` — the smoothing shift and the tilt's anchor offset are already inside the world entry. §4.2's `− postShift − entryW` form double-counts the shift when entryW is the world entry. Row g pins the round trip at 0.0.
2. **X / Y / Z locks and typed X / Y / Z boxes are CANONICAL coordinates** (section-relative — §4.2's "locks are section-relative for free, D199"); the window converts its world box through SectionFrame before the solve. A world-axis lock on a yawed piece would mix canonical axes and break D199's placement rigidity.
3. **I1 carries the MIN_ELEM_LEN floor as a projection** (the point scaled out along its own ray: φ kept, κ shrinks — closed, continuous). §4.4's reachable set listed only the backward ray and the torus core; §1.1's length floor is a model clamp the solver must honor.
4. **The I2 axis is a = unit(T1 × T)** (§4.4 wrote `a ∝ T × T1`): ElementChain's planar law rotates about a by −Θ, so the exit tangent is `T cos Θ + N sin Θ` with N = T × a, and the axis that carries T onto T1 is T1 × T. The ring round trips (rows e, j.11) pin it.
5. **A lone HEAD / ELEV lock holds κ** (D14's "κ last" — the table had no single-global row); the family is then the κ-locked mixed row.
6. **The DEGENERATE mixed row** (the constraint direction ∥ T: a coordinate whose axis is T, a heading a quarter turn away): θ is set by the constraint alone and dir runs free on the end-point circle — solved closed (`degenerateMixed`), not by §4.11's golden section, whose inner d-solve has no root there (0 = 0 or 0 = c). The golden runs for every other mixed set (row n on a 60° turn at dir 20).
7. **ORTHO_LOCK_WHY is unreachable by construction** under §4.12's node-plane rule: the plane through the current end always contains the current end, so the locked curve ∩ plane is never empty for the current dir. The belt stays in `solveOrthoDrag` (an empty intersection in the ±90° dir bracket, or every intersection under the model's limits), LabelGate 21u pins the sentence, row j prints the SKIP with this reason.
8. **A straight after a HELIX needs no Newton.** With c_h kept the plan is a circle and the plan I4 is closed (plan arc, plan straight); a fixed β1 (linked / B1 or ELEV locked) implies the height (PROJECTED on a drag, HELIX_STRAIGHT_WHY on a typed box); a free β1 falls out of the one monotone equation `Δy = L_h·tan β̄ + ℓ_h·tan β1` by bisection ≤ 60 (EXACT). §4.5's "2 transcendental eqs in (L_j, ℓ)" do not arise; the sentence keeps its typed-box use.
9. **The SINUSOIDAL-pitch helix is closed too**: `L_h = L·cos β̄·J0(Δβ/2)`, `Δy = L·sin β̄·J0(Δβ/2)` share the J0 factor, so the hinge `Δy/L_h = tan β̄` holds and I9 inverts it closed; the J0 series (`ScSolve.j0`) is the SEED and §4.11's Newton polishes against ElementChain's quadrature (0 steps measured, row m) — the ONE evaluator rule; no accessor was added to ElementChain (the brief allowed one).
10. **The ramped Newton seeds from the element's CURRENT (k1, L) first**, the constant-κ closed form second (each ≤ 20 steps). The closed-form seed alone failed on strongly ramped S elements; row m's generator bounds each end's turn to ≤ 270° over the element — a multi-lap double spiral (k0 ≈ +3, k1 ≈ −3 over 25 m winds six laps each way) has no basin a 20 % perturbation reaches, and RAMP_SOLVE_WHY (PROJECTED to the seed) is its honest answer.
11. **The biarc's fallback searches t** when the kept-curvature root is absent AND the t = ½ member leaves the model (κ_max / MIN_LEN): a bounded golden over t (≤ 40) picks the gentlest admissible member, still flagged BIARC_FALLBACK_WHY; only an empty family refuses (TWO_CURVES_WHY). Row k lands 200 of 200.
12. **Insert against a lock: the 2-root case is unreachable** — the locked end lies on element b's own arc, so `L = Δ_T ∓ √(2Δ_N/κ − Δ_N²) = {0, 2 sin Θ/κ}`: one admissible root at most (a turn ≥ 180° has none → insertLockWhy). A CURVE insert whose default already sits on or behind the locked point refuses with insertLockWhy (an element cannot be 0 m long).
13. **The predictor's δ is ZERO whenever no element of the section carries a blend** — the probe is not consulted. D9's "bitwise the plain path" cannot be met by reading `Bound.pos − sharpPos`: the records differ by the integrator's ~1e-7 m even with blends off (ScElemGate e).
14. **Row a's 1e-12 holds away from whole laps**; within 0.5 rad of 2πk (k ≥ 1) the chord collapses and any inverse carries eps/chord in the params (5.0e-9 measured) while the position still round-trips to 1e-12 — a conditioning limit of the problem, stated as its own row (a2) at 1e-7.
15. **Row h's "bitwise" exit tangent is measured, not guaranteed** (trig round trips): the S-bend fixture came back bitwise; the check's bound is 1e-12° and the detail prints whether it was bitwise.
16. **Placeholders the later owners may rename**: `elementName(i)` = "Element " + (i + 1) (§7's owner), `LOCK_POS_QUANTITY` = "end position", `boxLabel` = {X, Y, Z, Curvature, Turn, Direction, Heading, Elevation, Arc length, End curvature, End elevation} (§6.2's lock-row labels) — LabelGate 21 pins the current strings.
17. **ALIGN_NO_CURVE_WHY / ALIGN_LOCKED_WHY / REVERSED_WHY** did not exist in code (§3.10 defined the first two for the backward align, Stage 2a's) — added in the §4.14 block.
18. **LabelGate section 21**, not 20: run 3 took section 20 for §3.10.
19. **A helix has no DIR ring** (FULLY_CONSTRAINED_WHY); its TURN / HEADING rings edit the plan turn Δψ, ELEVATION edits β1 with c_h and Δψ kept.
20. **A straight's HEAD / ELEV lock, or ONE global lock on the curve before it, is treated as a full tangent lock** (the ray row) — conservative; a single global lock on j leaves a 1-D family the row does not search.
21. **CLOSE_POS returns PROJECTED, never REFUSED, for an unreachable position** — the closure's SATISFIED / OPEN is the compile's residual (D22), never a solver write; the pose modes refuse with their sentences.
22. **The chart argument enters the math only through the lock set** (a locked turn makes the golden's free parameter the length); both charts take the same code path — the gate runs LOCAL; the window's ring set is the chart's job (3a).
23. **The row-r δ measured 4.74 mm** on R20 90° with a 2 m blend (the doc's estimate was 6.6 mm from another probe); the corrector landed in ONE round at 0.118 mm.

## 7. For the local session (the consumers of Stage 3a and `-Dfvd.scsolveprobe`)

- ONE frame per event: `SectionFrame sf = SectionFrame.of(lastResult, coaster, p, k)`; the solver's inputs are `ScSolve.ElemParams.of(sc)` and `sf.entryFrame()`; the cursor's world point goes through `sf.toCanonical(w)` (a ray's hit point — D14 view-plane point first), a glyph position back through `sf.toWorld(sol.landedPos())`; ALIGN_PREV's target = `sf.poseTarget(Coaster.strictEntry(prevExit, piece))` of the NEXT piece's seam, CLOSE's = `sf.poseTarget(head.entryPose())` (D28); a NaN heading in the Pose = the HEAD_VERTICAL_WHY case the solver refuses.
- Reading a `Solution`: `refused()` → gray / stats with `why`; `PROJECTED` with a non-null `why` → post the notice (absorbResidualWhy, BIARC_FALLBACK_WHY, RAMP_SOLVE_WHY, HELIX_ELEV_WHY, REACH_RAY_WHY, predictorOffWhy); `residualRot` (deg) for the absorb; `iterations` for the probe's counts; apply with `ScSolve.apply(sc, sol)` (handles insertedAt / deletedAt; every length through `Element.setLength` — the roll lane's cut / shadow rule runs), then the ordinary rebuild. `Solution.params` is the WHOLE section.
- Entry points: `project` (a drag's landing without the absorb — the ghost of a pressed node), `solveNodeDrag` (ball), `solveRing`, `solveArrow`, `solveTypedBox` (canonical X/Y/Z), `solveTypedPoint` (the three-coordinate typed box under the predictor — pass a `LandedProbe` that compiles a scratch section through `ScIntegrator.integrate(scOf(params), …).bounds().get(border).pos()`; the solver ignores it without a blend), `absorbAfter`, `insertAt` / `changeType` / `deleteAt`, `solveTail`, `solveOrthoDrag` / `orthoTarget`, `padlockWhy` (the window's padlocks), `lockRank` / `dof`, `closedTailEditWhy`, `reversedWhy`.
- Signs: internal heading `atan2(−T.z, T.x)` everywhere (negate at the UI, D186); +κ at dir 0 = rider-left; the stored dir is never normalized (branch continuity); a drag may flip κ's sign keeping dir (the pass-through-straight) — the window's radius box shows |1/κ|, the turn's sign is the arc's side.
- `hasUpstream` (SEAM_OWNS_WHY vs ANCHOR_OWNS_WHY) and `reversed` ride the SectionFrame (`Coaster.hasUpstream(p, k)`, `Section.reversed()`).
- The probe `-Dfvd.scsolveprobe=<file.uvd>`: per s/c section build the frame, print every end node's canonical and world position, `project` at the current end (expect EXACT / unchanged), the padlock table (`padlockWhy` per bit), the ring solves at the current angles (expect unchanged), and the bounded solvers' `iterations`.
- The three placeholder strings of deviation 16 and the ORTHO_LOCK_WHY question (deviation 7) are the two things the 3a window may want to settle before its strings are pinned.

## 8. Open questions

1. ORTHO_LOCK_WHY has no reachable fixture under the node-plane rule — keep the belt + sentence, or drop the sentence from the register?
2. `elementName` 1-based ("Element 1") and the box labels — §6.2 / §7 to confirm before the window pins them.
3. Canonical-frame X/Y/Z locks (deviation 2) — confirm the D199 reading; the window shows world numbers either way.
4. A drag of a straight after a pitched helix lands PROJECTED silently (the height implied) — the §4.5 sentence fires only on a typed box; confirm the feel.
5. The single-global lock on the curve before a straight is treated as a tangent lock (deviation 20) — a 1-D search would be a 3b refinement.
6. SNAP_ONE on two free curves uses the biarc incl. its fallback search — confirm.
7. Row h's bitwise exit tangent is measured on one fixture; the contract should say "≤ 1e-12°" unless bitwise is required.

## 9. Full battery (wiped `target/classes`, JDK 26.0.2, the final head)

`rm -rf target/classes/uvd && mvn -q compile` rc 0 (163 class files — 142 +
ScSolve's nested types + SectionFrame); `javac -encoding UTF-8 -cp
target/classes -d gates/out gates/*.java` rc 0.

| gate | result |
|---|---|
| uvd.ScElemGate | 122 passed, 0 failed |
| uvd.ScRollLaneGate | 70 passed, 0 failed |
| uvd.ScSeamGate | 37 passed, 0 failed |
| **uvd.ScSolveGate (NEW)** | **71 passed, 0 failed** (s SKIPPED; j's ORTHO_LOCK_WHY SKIPPED with its reason) |
| DetermGate197 | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |
| DetermGate198 | 6 passed, 0 failed |
| DetermGate199 | 10 passed, 0 failed |
| DetermGate200 | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |
| LoadGate196 | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |
| OrientGate | 23 |
| OrientGate2 | 18 |
| Phase2Gate | ALL GATES PASS (162 PASS lines) |
| DomainGate | ALL DOMAIN/AXIS GATES PASS |
| Phase3Gate | ALL PHASE-3 GATES PASS (88) |
| MultiCoasterGate | 15 |
| SmoothRealGate | 21 (d1 `28808918c0612fbe` over 1601 samples, 20 m `0fc8182da8f4a418` — UNCHANGED) |
| uvd.KeyRouteGate | 103 passed, 0 failed |
| TypedMarkGate | 42 checks, 0 failures |
| InflSplitGate | 22 |
| ReanchorGate | 3 |
| MirrorGate | 100 |
| YpAbsGate | 22 |
| uvd.SeamShapeGate | 31 — its recorded pre-D247 checksums UNTOUCHED |
| RangeResolveGate | 115 |
| CopyGate | 74 |
| MergeGate | 23 |
| InsertGate | 45 |
| LabelGate | 286 passed, 0 failed (229 + section 21's 57) |
| uvd.PulseClockGate | 30 passed, 0 failed |
| RunGate | 82 passed, 0 failed |
| PlacementGate | 41 |
| OrientModeGate | 51 |
| GestureGate | 180 passed, 0 failed |
| EndFollowGate | 73 passed, 0 failed |
| FloorGate | 34 passed, 0 failed |
| StripGate | 49 passed, 0 failed |
| uvd.ConflictGate | ALL CONFLICT GATES PASS (90) |
| Nl2ElemProbe | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |

No change to passD, the speed recording, the reconcile or follower
generation — the clean-span dump (§10.7 rule 7) does not need a re-run; the
solver writes nothing the compile reads differently (ScElemGate /
ScRollLaneGate / ScSeamGate untouched at their counts).

Commits on `sc-2a-solve` (one per item; `main` untouched):

| commit | item |
|---|---|
| 65f90fe | O11 — uvd.ScSolve (§4.1-§4.12), uvd.SectionFrame (§4.2), the §4.14 register in SectionNames, LabelGate section 21 |
| 12e5dbe | O12 — uvd.ScSolveGate rows a-r |
| (this commit) | the notes |
