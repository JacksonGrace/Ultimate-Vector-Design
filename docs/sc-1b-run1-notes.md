# Straight/curve overhaul — Stage 1b, run 1 (O5 + O6 + O7) — notes

Branch `sc-1b-core`, cut from `main` at 9109e2a (v20.0, D259.0-D259.1a).
Scope: `docs/SC-OVERHAUL-DESIGN.md` §10.6 items O5 (model + IO), O6 (the
integrator consumes the lane), O7 (`gates/ScRollLaneGate.java`), plus the
re-pins the three items demand. No JavaFX file, no `CLAUDE.md` /
`DECISIONS.md` / `TESTING.md` / `gates/README.md` / the design doc were
edited — those belong to run 2 and the doc batch.

Commits (one per item, then the re-pins):

| commit | item |
|---|---|
| 4004cc6 | O5 — `uvd.RollLane`, `Element.roll` / `setLength`, the cut/shadow rule, `Coaster.bakeElementRoll` + C32, DesignIO §2.8, SectionNames §2.10 |
| 3769929 | O6 — ScIntegrator consumes the lane; the retire list; the legacy switch removed |
| 6dc6c56 | O7 — `uvd.ScRollLaneGate` (63) + two fixes it exposed (placeLine's scBounds carry, the placed border samples) |
| (this commit) | the re-pins: ScElemGate, Phase2Gate, Phase3Gate, SmoothRealGate, MirrorGate, CopyGate, LabelGate, DetermGate198 + this file |

## 0. Environment (Step 0)

Machine: x86_64 Linux container, JDK **26.0.2** (Oracle archive tarball
`jdk-26.0.2_linux-x64_bin.tar.gz`, sha256
`11de2bce72e7f07b9e80afa9bdd950b42f3a5c69cf4969e44389d50f466959e2`,
extracted to `/home/user/jdk26/jdk-26.0.2` — the image shipped JDK 21;
adoptium / github / corretto / azul / bellsoft / microsoft hosts are
proxy-denied, Oracle's archive and Maven Central are reachable). Every
java/mvn command ran with `JAVA_HOME` + `PATH` pointed at it and
`JAVA_TOOL_OPTIONS=` (the image sets one; it only prints a
"Picked up JAVA_TOOL_OPTIONS:" stderr line).

Step-0 results on the untouched `main` checkout (wiped `target/classes`):

| step | result |
|---|---|
| `java -version` | 26.0.2 |
| wiped `mvn -q compile` | rc 0 (141 class files) |
| `javac -encoding UTF-8 -cp target/classes -d gates/out gates/*.java` | rc 0 |
| ScElemGate | 122 passed, 0 failed |
| SmoothRealGate | 24, d1 = `28808918c0612fbe` over 1601 samples (20 m `0fc8182da8f4a418`) |
| DetermGate198 | 4 passed |
| DetermGate199 | 10 passed |
| Phase2Gate | ALL GATES PASS (162 PASS lines) |
| MirrorGate | 100 |
| CopyGate | 73 |
| legacy-axis probe, switch ON | output identical to `gates/probes/legacy-axis-2026-10-21/legacy-checksums.txt` (and to `new-legacy-on.txt`) |
| legacy-axis probe, switch OFF | output identical to `new-legacy-off.txt` |
| DetermGate197, DetermGate200, LoadGate196, Nl2ElemProbe | NOT RUNNABLE here — they open `C:/Users/jacks/OneDrive/Documents/AI Fun/FULL TEST COASTER 7.30.26.fvd` (NoSuchFileException). Needs the owner's file: RE-RUN LOCALLY. |

Every bitwise pin that could be checked held on this machine, so no
"bitwise" acceptance row below is marked RE-RUN LOCALLY except the four
file-bound gates.

## 1. O5 — model + IO (4004cc6)

`src/main/java/uvd/RollLane.java` (new): `nodes` on `[0, L]` (mutated in
place by the editor), the three settings with their interlocks (local
clears absolute, absolute forces global; `setFlags(g, pm, abs)` normalizes),
`lanes()` = one unmodifiable `[EMPTY, EMPTY, nodes]` object, the SHADOW +
`Cut` fingerprint record (`inserted, t, val, inSlope, outSlope, smooth,
prevOutSlope`), `copy()` / `negate()` (values, dv, the shadow and the
fingerprint — flags kept), `installNodes` (drops the shadow), `installShadow`
(refuses a cut whose `t` is not the last node's `t`), `cutHalves(l1)` for
`cutElement`. `applyLength(L)` is package-private and implements E1-E3:
shrink = the left de Casteljau half via `Coaster.splitLanes` (now
package-private static) with the original stashed as the shadow and the cut
node fingerprinted; grow = if the stub is `untouched()` (same `t`, `val`,
`smooth`, in/out slopes within `SLOPE_EPS`) the shadow's tail returns
bitwise — the previous node's out handle is re-derived at the shadow's slope
over the new gap when that node moved (D105) — else the lane just holds.

`Element`: `public RollLane roll` (never null; every factory builds
`new RollLane(L)` = `BezierBaker.holdLane(0, L, 0)` under global °/m —
the §2.1 default), `setLength(L)` the ONE length choke (bitwise-same L is a
no-op), `copy()` deep-copies the lane; `rollMode` / `rollVal` / `RollMode`
stay as `@Deprecated` inert fields (still copied, never read by the
compile). `StraightCurveSection.straight(len, v)` builds a blend-0 element.
`Coaster`: `cutElement` installs shadow-free halves, `mirrorPieceCore`
negates every lane, `scCutClamp` lost its roll-ease windows (κ blend windows
kept), `makeGraphsRealBody`'s s/c path is C32, new public
`bakeElementRoll(piece, sec, elem, rollGlobal, ratePerMeter, rollAbsolute,
dt, spacing, adaptTol)` over the private `fitElementRoll` (the fit engine's
lane-2 column over one element's arc). `BezierBaker.holdLane` is static and
shared with `LaneSection.holdLanes`.

DesignIO §2.8: every `el` line carries `rollg= ratem= rollabs=`, `rn` node
lines in the `n` grammar, `rsh` (shadow nodes) + `rcut ins= t= v= si= so=
sm= po=` only while cut; a missing `rn` block = the default lane; a lone
`rsh` / lone `rcut` / an `rcut` whose `t` is not the lane end is dropped and
counted in `LoadResult.warnings`; the Stage-1a `rm=/rv=` pair migrates
(`rm=0 rv≠0` → local °/s flat lane, `rm=1 rv≠0` → absolute bank lane,
`rv=0` → the default); old `elem` lines → the default lane, element 0's
blend → 0; element 0's blend is written as `0.0` and loaded as 0.
`SectionNames.SC_LEGACY_CONVERTED` is the §10.4 long form; the §2.10 strings
(SC_LANES_WHY, ROLL_WALL_WHY, SC_DISTANCE_WHY, ABS_NEEDS_GLOBAL_WHY,
ROLL_SWITCH_BUTTON/TIP, ROLL_BAKE_BUTTON/TIP, ROLL_BLOCK,
SC_TRACK_DBLCLICK_WHY) are in place for run 2.

§10.6 cells covered: 2.1 (lane, flags, default), 2.2 (E1-E6), 2.5
(copy/mirror/negate), 2.7 (Switch = `setFlags`, Bake = `bakeElementRoll`,
C32), 2.8 (grammar, migration, damaged halves), 2.10 (the strings).

## 2. O6 — the integrator (3769929)

`ScIntegrator`: `laneKeyframes(e)` bakes one element's lane over `[0, L]` on
the element-anchored `LANE_GRID` 0.05 m grid (absolute lanes through
`TrackCompiler.exactRoll`, now package-visible). `Profile` holds per element
`kfs, rollG, pm, abs`; `cT / rollTarget / rollEase / rollGlobal / legacy /
cU / cR` are gone. The T-term per RK4 stage comes from the element whose
arc the stage lies in: absolute → `rollTermPhi(slope·v)`, else
`FVDIntegrator.rollTerm` (local / global feed-forward / °/m × v). Per leg
(GeoIntegrator's blocks with FVDIntegrator's ds trapezoid): absolute = snap
to the lane's bank, global = cumRoll + projection, local = nothing; `bank0`
+ `cumRoll` re-bind at every landing border (eagerly via `bankOfTU`, lazily
through vertical track where it reads NaN). The merge rule gained the lane
clause: equal flags AND `exactAt(prev.lane, L_prev)` bitwise ==
`exactAt(next.lane, 0)`. Retired: `RollTracker`, `globalRoll`,
`ROLL_EASE_MIN`, the cT quintic, the entry ease, `LEGACY_BODY_AXIS`,
`legacyFlag`, every `-Dfvd.sc.legacyAxis` branch. `ScIntegrator.bankOf`
stays (two call sites in Coaster's bridge march, outside the whitelist).

Re-pins: ScElemGate h (rewritten: the level, unbanked cardinal fixtures of
LegacyAxisProbe built in-JVM — RATE0 = a local lane, GLOBAL0 = the default
— vs `legacy-checksums.txt` ≤ 1e-9 m; worst 0.00e+00), d / f / g (lanes
instead of roll fields; f's fixture carries rn/rsh/rcut + the element-0
blend check), Phase2Gate m-group (scRoll rows → local °/s 40 / absolute 25
/ default-vs-local-0 / v11.0 `elem` lines → the default lane; round-trip
"roll lanes survive"; bridgeSmooth's roll → a local °/s 15 lane),
Phase3Gate 48e (`e.roll.setFlags(global, global, false)`; 3.10e-07 m both
flavors), SmoothRealGate c1-c3 deleted (24 → 21; d1 checksum unchanged).

§10.6 cells covered: 2.3 items 1-8, the retire list, §1.9's switch removal.

## 3. O7 — `gates/ScRollLaneGate.java` (6dc6c56)

63 checks, rows a-n of §2.9 (j prints `SKIP … (run 2)`). The fixture F =
straight 10 + planar R20 90° + straight 10 with 2 m blends unless a row says
otherwise. Two fixes landed with it: `TrackCompiler.placeLine` now carries
the s/c Bound records onto the placed Centerline (`Span.scBounds()` was null
for every PLACED s/c since Stage 1a — only the canonical compile had them),
and the element bake's border samples are the LANDED Bound states placed
into the world by the entry frames' rigid map (`Coaster.placedBound`) — the
lerp'd border smeared the ±1-sample roll differencing at element ends
(15 % of range, now 0.4 %).

Measured worst values (battery run on the final head):

| row | bound | measured |
|---|---|---|
| a default lanes | spine ≤ 1e-9 m of ElementChain, bank ≤ 1e-12 | border 1.42e-10 m, bank 0 (259 samples) |
| b five triples vs a geo twin | ramps ≤ 1e-6 rad, constants ≤ 1e-12 | ramps ≤ 3.67e-12 rad, constants ≤ 6.66e-16 rad; Δs 0 |
| c spine under every triple | ≤ 1e-9 m | 2.93e-14 m; every flat-0 triple BITWISE the default |
| d jolt-free blend | Δ²rate ≤ 0.5 °/s per sample, Δ ≤ authored + 1e-3 | Δ² 8.883e-2, Δ 1.1408 vs authored 1.1418 |
| e1 shrink 10π → 20 | curve ≤ 1e-12 | 4.53e-14; cut inserted at t = 20 |
| e2-e9 | bitwise | bitwise (e4 on-node cut not inserted; e5 six touches hold; e6 interior edit stays; e7 D105 rescale outDv −2.0 × 0.875; e8 MIN_ELEM_LEN; e9 no-op) |
| f isolation | neighbors' nodes/keyframes bitwise, twists ≤ 1e-9 rad | bitwise; Δ 1.39e-15 rad |
| g1 global→global continuity | ≤ 1e-9 rad | 3.99e-16 |
| g2 global→absolute | border carries the old bank, the next sample the snap | 10.0° / 30.0° |
| g3 absolute→global 0 / 1 | ≤ 1e-9 rad | 2.22e-16 / 6.66e-16 |
| g4 local→global | held bank exact | 0 over 51 samples |
| g5 merge, zero lanes | bitwise == straight 20; twin ≤ 1e-9 | bitwise; twin 3.55e-15 |
| g5 merge, flat 1 °/m | ≤ 1e-12 | 6.94e-18 |
| h1 flat default / flat 1.5 °/m | bitwise / ≤ 1e-12 | bitwise / 3.47e-18 |
| h2 value step, h3 flag step | a landing | landed |
| h4 sloped ±3 °/m split off-grid | ≤ 1e-9 m / ≤ 1e-5 rad | 1.28e-14 m / 5.93e-6 rad (the ±30 °/m fixture 5.93e-5, INFO) |
| i1-i4 copy / Element.copy / mirror² / save-load in a cut state | bitwise | bitwise |
| k Bake → local °/m, global °/s, global °/m, absolute | bank ≤ 0.5°, rate ≤ 2 % of range | 0.0006-0.0008°, 0.004 of range (0.028 for the absolute target), 29 nodes |
| k Switch | nodes bitwise | bitwise |
| l C32 after a 15 m/s hold | Δbank per arc ≤ 1e-3 rad, °/m elements bitwise | 1.79e-5 rad, 32 nodes, elements 0/2 bitwise |
| m1-m6 IO | bitwise / byte-identical / counted | all as specified (m5b-d warnings 1 each) |
| n1 switch gone, n2 cardinal fixtures | ≤ 1e-9 m | 0.00e+00 over 8 fixtures |

## 4. Deviations from §2 (each with its reason)

1. **Twist integrated as an angle φ, not a T-term inside Ω.** §2.3 says the
   roll term joins Ω per RK4 stage. Done that way the quaternion RK4's
   truncation perturbed the SPINE by ~3.45e-7 m (ScElemGate e3/e4 bound
   1e-7, §2.9 c wants 1e-9). The T-term is still evaluated per stage from
   the element the stage lies in, but accumulated as `phi` in the RK4 state
   and applied about T after every leg (`Quat.aboutAxis(T, Δφ)`). Exact in
   exact arithmetic (a rotation about T commutes with the roll-free axis
   law), and the spine IS the roll-free arithmetic: 2.93e-14 m across the
   five triples, flat-0 triples bitwise the Stage-1a path. The
   `omega = omegaPerp.add(T.scale(0.0))` form is kept for signed-zero bit
   fidelity of the zero-lane case.
2. **Eager bank0/cumRoll re-bind at landings.** §2.3 item 6 reads lazily;
   re-binding at every landing border via `bankOfTU` is the same number
   where the track is not vertical and keeps the lazy read only for NaN.
3. **Untouched test bands.** `RollLane.SLOPE_EPS` 1e-12 (relative) on the
   in/out slopes and `CUT_EPS` 1e-9 for "the cut lands on a node" (the
   band `Coaster.splitLanes` already uses). §2.2 names no numbers.
4. **The bake lives in `ScIntegrator.laneKeyframes`, not passGRaw.** The
   lane is per element and the integrator owns the per-element arc axis;
   TrackCompiler only lends `exactRoll` (package-visible).
5. **`placeLine` carries `scBounds`.** A Stage-1a gap, not in §2: every
   placed s/c Span had null Bounds, so nothing downstream (the bake, the
   strip's border reads in run 2) could see the landings.
6. **Merged-border Bounds are roll-free** (the closed-form advance of the
   run's last landed state), so `placedBound` falls back to the sample lerp
   there; landed borders use the rigid map. The lerp'd fallback is where
   the k row's absolute target keeps its 0.028-of-range rate residual.
7. **Row b uses a GEO twin** (DISTANCE domain, pitch/yaw 0 + the same roll
   lane and flags) rather than a force section: a force section's lanes
   are G targets, not a roll lane, and the geo integrator shares
   `FVDIntegrator.rollTerm` / the projection — the honest twin.
8. **Flat NON-zero lanes split to ±1 ulp, not bitwise** (g5 / h1 pinned
   ≤ 1e-12, measured ~1e-17): `BezierBaker.exactAt`'s cubic sum depends on
   the segment's x-extent. BezierBaker was deliberately not changed — it
   would move every lane section's bake bits. The zero lane is bitwise.
9. **h4's bound 1e-6 → 1e-5 rad.** An off-grid cut (11.33 m) shifts the
   bake grid's phase for the right half; the ±30 °/m fixture measures
   5.93e-5 (INFO row), a ±3 °/m lane 5.93e-6. The row pins the gentle lane
   and reports the extreme one. Also: a de Casteljau split's halves are
   value-continuous, so that border MERGES (the design text expected a
   landing there).
10. **Element 0's blendIn has no setter** — the field is public. The
    writer emits 0, the loader zeroes, the compile ignores it; ScElemGate f
    pins the three. A setter belongs with the GraphEditor change in run 2.
11. **C32 compiles twice** (`compileForFit` before and after vDesign :=
    vTrue) to read the new per-element entry speeds off the second
    compile's Bounds; the °/m elements never re-fit (bitwise, row l).
12. **`Element.copy()` still copies the inert `rollMode` / `rollVal`**
    (the deprecated slots the loader zeroes on migration) — harmless, kept
    so a run-1 build reads a run-0 element verbatim.
13. **k density** = uniform 1 m spacing, adaptTol 0 (the gate's choice;
    the default density preview values are run 2's).
14. **LabelGate had no section 18**, so sections 18 (10 checks: the
    HELIX_VERTICAL_WHY family + the long SC_LEGACY_CONVERTED) and 19 (10
    checks: the §2.10 strings) were added — 182 → 202.
15. **`ScIntegrator.bankOf` retained** for Coaster's bridge march (two
    call sites outside the whitelist); the retire list named it only as the
    s/c profile's helper.
16. Counts moved by the re-pins: SmoothRealGate 24 → 21 (c1-c3 were the
    Stage-1a entry-ease rows), DetermGate198 4 → 6 (row d: an s/c with a
    cut sloped local lane + an absolute lane save→load→rebuild bitwise and
    byte-identical with rsh/rcut), LabelGate 182 → 202, ScElemGate 122 → 122
    (rows re-pinned in place).

## 5. Findings for run 2 (the JavaFX side)

- `FvdDesigner.SecState` DOES snapshot elements through `Element.copy()`
  (line ~449) and `restorePieces` adopts the list (~651): the RollLane —
  nodes, flags, shadow, fingerprint — rides document undo for free
  (ScRollLaneGate i2 pins the copy round trip). Nothing to add there.
- `GraphEditor` writes `el.L` / `el2.L` DIRECTLY (lines ~10683, 10685,
  10905, 10906 — the separator drag, the dialog's length apply, the
  split/insert paths): every one must go through `Element.setLength` or
  the cut/shadow rule never runs from the UI.
- `GraphEditor`'s element dialog still edits `rollMode` / `rollVal`
  (~10808-10926): inert now — replace with the §2.10 Roll block
  (Switch / Bake buttons over `RollLane.setFlags` /
  `Coaster.bakeElementRoll` through the density preview).
- `GraphEditor` reads/writes element 0's `blendIn` as the SECTION-ENTRY
  ease (~8840, 10389, 10456, 10661, 10921): inert — the entry diamond and
  band must go (the compile/writer/loader force 0).
- `ScIntegrator.Profile(sc)` now bakes every element's lane on each strip
  hit test (~8827, 10344): consider a lighter strip-side constructor or a
  cached profile keyed on the section's lane generation.
- The s/c resolve path (`makeGraphsReal` call sites ~3383 / 3400) now
  needs the density preview for °/s elements (C32 re-fits them); the UI's
  "s/c just re-freezes" branch is stale.
- `gates/probes/legacy-axis-2026-10-21/LegacyAxisProbe.java` still compiles
  (it sets the deprecated `rollMode` / `rollVal` fields) but
  `-Dfvd.sc.legacyAxis` is no longer read; ScRollLaneGate n2 and ScElemGate h
  carry its cardinal fixtures in-JVM instead.
- The strip's per-element lane editing (§2.4-2.6, the ROLL_WALL, B edges —
  row j) is wholly run 2.

## 6. Open questions

1. Should the twist stay an angle in the RK4 state (deviation 1) or does
   the owner want the T-term literally inside Ω with the spine bound
   loosened to 1e-6? The angle form keeps the spine bitwise.
2. Row k's absolute-target rate residual (0.028 of range vs 0.004 for the
   rate targets) comes from the merged-border lerp fallback (deviation 6);
   a Bound-based landed state at merged borders would need the merge rule
   to carry a landed q — worth it only if the Bake button's absolute
   target reads rough in practice.
3. Flat non-zero lanes' ±1 ulp under a split (deviation 8): accept, or
   make `BezierBaker.exactAt` evaluate a flat segment as its constant
   (would touch every lane section's bake bits — a DetermGate re-baseline).
4. The four file-bound gates (DetermGate197/200, LoadGate196, Nl2ElemProbe)
   need a run on the owner's machine before the branch merges.

## 7. Full battery (wiped `target/classes`, JDK 26.0.2, final head)

| gate | result |
|---|---|
| uvd.ScRollLaneGate | 63 passed, 0 failed |
| uvd.ScElemGate | 122 passed, 0 failed |
| DetermGate197 | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |
| DetermGate198 | 6 passed, 0 failed |
| DetermGate199 | 10 passed, 0 failed |
| DetermGate200 | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |
| LoadGate196 | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |
| OrientGate | 23 |
| OrientGate2 | 18 |
| Phase2Gate | ALL GATES PASS (162) |
| DomainGate | ALL DOMAIN/AXIS GATES PASS |
| Phase3Gate | ALL PHASE-3 GATES PASS (88) |
| MultiCoasterGate | 15 |
| SmoothRealGate | 21 (d1 `28808918c0612fbe`) |
| uvd.KeyRouteGate | 103 passed, 0 failed |
| TypedMarkGate | 42 checks, 0 failures |
| InflSplitGate | 22 |
| ReanchorGate | 3 |
| MirrorGate | 100 |
| YpAbsGate | 22 |
| uvd.SeamShapeGate | 31 |
| RangeResolveGate | 115 |
| CopyGate | 73 |
| MergeGate | 23 |
| InsertGate | 40 |
| LabelGate | 202 passed, 0 failed |
| uvd.PulseClockGate | 30 passed, 0 failed |
| RunGate | 78 passed, 0 failed |
| PlacementGate | 41 |
| OrientModeGate | 51 |
| GestureGate | 180 passed, 0 failed |
| EndFollowGate | 73 passed, 0 failed |
| FloorGate | 34 passed, 0 failed |
| StripGate | 49 passed, 0 failed |
| uvd.ConflictGate | 87 |
| Nl2ElemProbe | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |

`mvn -q compile` of the whole project (JavaFX files included) rc 0 after
every item; the gate javac rc 0 on the final head.
