# Straight/curve overhaul — Stage 1b, run 3 (O9: the seam invariant) — notes

Branch `sc-1b-seams`, cut from `main` at 5cab6ff (v20.1, run 1 merged).
Scope: `docs/SC-OVERHAUL-DESIGN.md` §10.6 item O9 — the §3 seam invariant's
MODEL half (ensureScFollower, automatic followers, the belts, s/c-edge
smoothing's dry run, the forward align, the refusals and sentences, the
load pass, `gates/ScSeamGate.java`). No JavaFX file, no `CLAUDE.md` /
`DECISIONS.md` / `TESTING.md` / `gates/README.md` / the design doc were
edited — the JavaFX half is listed under "For the local session".

## 0. Environment (Step 0)

Machine: x86_64 Linux container (Linux 6.18), JDK **26.0.2** (Oracle archive
tarball `jdk-26.0.2_linux-x64_bin.tar.gz`, sha256
`11de2bce72e7f07b9e80afa9bdd950b42f3a5c69cf4969e44389d50f466959e2`, verified,
extracted to `/home/user/jdk26/jdk-26.0.2`; the image shipped JDK 21).
Every java/mvn command ran with `JAVA_HOME` + `PATH` pointed at it and
`JAVA_TOOL_OPTIONS=` cleared. ONE difference from run 1: the image's
JAVA_TOOL_OPTIONS also carried the proxy truststore
(`-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`), so with it
cleared the first wiped `mvn compile` failed on Maven Central with a PKIX
error; the proxy CA bundle (`/root/.ccr/ca-bundle.crt`, 122 certificates)
was imported into JDK 26's own `lib/security/cacerts` (keytool, aliases
`agentproxy-N`) and the compile then ran — the gates themselves need no
network. Maven 3.9.11.

Step-0 results on the untouched `main` checkout (5cab6ff, wiped
`target/classes`):

| step | result |
|---|---|
| `java -version` | 26.0.2 |
| wiped `mvn -q compile` | rc 0 (142 class files) |
| `javac -encoding UTF-8 -cp target/classes -d gates/out gates/*.java` | rc 0 |
| uvd.ScElemGate | 122 passed, 0 failed |
| uvd.ScRollLaneGate | 63 passed, 0 failed |
| uvd.SeamShapeGate | 31 (its recorded pre-D247 checksums held — the pin this run must never move) |
| uvd.ConflictGate | 87 |
| InsertGate | 40 |
| SmoothRealGate | 21; d1 = `28808918c0612fbe` over 1601 samples (20 m `0fc8182da8f4a418`) |
| DetermGate198 | 6 passed |
| DetermGate199 | 10 passed |
| RangeResolveGate | 115 |
| RunGate | 78 passed |
| CopyGate | 73 |
| LabelGate | 202 passed |
| MirrorGate | 100 |
| DetermGate197, DetermGate200, LoadGate196, Nl2ElemProbe | NOT RUNNABLE here — they open the owner's `FULL TEST COASTER 7.30.26.fvd`: RE-RUN LOCALLY |

Every bitwise pin that could be checked held on this machine.

## 1. Item 1 — the invariant's model core (336f305)

`TrackPiece.sAuto` (persisted as the `porient` line's 6th field `auto`
0|1, missing = false; `copy` carries it; `PlacementSnapshot` records /
diffs / restores it). `Coaster.ensureScFollower(pieceIdx, secIdx, dt,
lockYaw, lockPitch, lockRoll, smoothLen)` = `makeSeamStrict`'s body with
the lock triple + `sSmoothLen` as parameters plus `sAuto = all three off`
and `sections[0].vRealAnchored(true)` on EVERY seam it creates; the
`Result` overload reads a compile already in hand (`splitPiece` reads only
the boundary Span; the flattened index via `spanBase`). `makeSeamStrict` =
`ensureScFollower(p, k, dt, true, true, true, 0)` bitwise except the flag:
on a seam with NO s/c on either side the flag is put back as it was.
`autoFollower(p)` = `followsPrev(p) && sAuto && !sLockYaw && !sLockPitch
&& !sLockRoll && designPitchDeg == 0 && designRollDeg == 0`.
`scInvariantHolds()` (L1's structural half: no s/c at k > 0, nothing but a
bridge after an s/c inside a piece) + `ASSERT_SC_INVARIANT` (a public
static a gate build sets; `attachPiece` then throws IllegalStateException
on a merge that breaks L1 — never set in production). L4: `unstrictSeam`
refuses when `unstrictSeamWhy(fi)` is non-null — `scEdgeKind(fi)` counts
the s/c sides (the follower's section 0 / the previous piece's last
non-bridge section): 2 → UNSTRICT_SC_WHY, 1 → UNSTRICT_CONVERT_WHY
(the §3.10 block appended to the END of SectionNames).

§10.6 "seams" cells: Make seam strict (lane seam: bitwise today; s/c
edge: + the flag) · un-strict on an s/c edge (refuse, one sentence) ·
head / locked follower / bridged-in (untouched) · IO (the 6th field).

## 2. Item 2 — automatic followers A1-A5

`TrackCompiler.compile`'s follower branch: when `c.autoFollower(p)`,
`eff = strictEntry(prevExit, piece)` (= `prevExit` exactly), `tilt =
Quat.IDENTITY` (the object), `pose = eff`, `anchor = eff.pos()`, `cp = new
CanonPose(eff.pos(), prevSpan.exitPsi(), prevSpan.exitCanon())` — the SAME
pair an in-piece continuation receives — when the upstream span's
`exitTilt` is the IDENTITY object, else `canonOf(eff)` (see deviation 1);
`continuation` was already true for followers; run membership unchanged.
Locked / `sAuto == false` followers keep the old branch verbatim (the
else-arm) — SeamShapeGate 31 with its recorded checksums held.
`pieceHead` needs no mirror (a bridge target is bridged-in, never a
follower — javadoc says so). `Coaster.freezeAutoFollower(fi, spans)` (+ the
`Result` form): x/y/z := the upstream span's exit, baseOrient := its
orientation, the canonical pair := that span's (psi, canon) (null behind a
tilted upstream), both tiers 0, held angles seeded (`seedStrictValues`);
returns false for a non-automatic follower. The ORPHAN GUARD overloads
`deleteRun(head, spans)`, `moveRun(head, afterIdx, spans)`,
`moveRunTo(dest, head, spans)`, `copyRun(runStart, runEnd, dz, spans)`
freeze the auto followers whose predecessor changes (the pieces after the
block's old place / the destination, the copied run start) through
`freezeIfAuto`, then run the plain op — the plain overloads are untouched
(by the run topology those slots are run heads, so the guards are belts).
A4: `reconcileChainedSpeeds` keeps `k == 0` only when `followsPrev(pi) &&
sec.vRealAnchored()` (the piece loop is indexed now); pinned / bridge /
reversed skips, the anchor branch (vIn = vTrue), the EXACT pass and the
fast-pass carry are unchanged. A5: `refreezeSpeedOnly`'s "inert at k == 0"
comment, `hasUpstream`'s javadoc, `Section.vRealAnchored`'s javadoc;
`realEntrySpeed` returns `max(0.5, real)` (the op's own write) only for a
BRIDGED-IN first section — a strict follower's k == 0 predicts the flagged
landing `max(0.1, real)` like a continuation; `firstSectionSettle` returns
NaN for a strict follower (deviation 2). ConflictGate i7 RE-PINNED to the
new contract (it had pinned "the follower keeps the op's suspended write"):
behind the smoothed 8° seam the op flags the follower's first section, the
rebuild lands vDesign on the DISPLAYED seam speed bitwise (6 exact
rewrites, then a bitwise no-op), the dry run reads no difference,
firstSectionSettle reads NaN.

§10.6 cells: head (no change) · auto follower (A1) · locked follower /
`sAuto` off (old branch bitwise) · bridged-in (never a follower) · tilted
≠o upstream (canonOf fallback, deviation 1) · the k == 0 real anchor
(A4; friction + smoothing gated in item 6) · freeze / unlock round trip ·
delete / move / switch-coaster / copy of an auto follower (the guards).

## 3. Item 3 — the belts (§3.2)

`insertSectionAtSeam`: both shapes end in `restoreScInvariant(pieceIdx, i,
dt)` — for the just-inserted section i it cuts an automatic seam AFTER it
(an s/c followed by a non-bridge section) and BEFORE it (an s/c, or a
force/geo right after an s/c; never across a bridge), downstream first,
both cuts off ONE compile of the post-insert document (no cut needed = no
compile); Lock ON = `makeSeamStrict` first, append, then the cut before the
appended s/c (a one-section automatic follower between the upstream and
the all-locked follower); the return is {piece, section} where the section
now lives. `appendSectionWithInvariant(pieceIdx, added, dt)` = the '+'
paths' one model call (append, then the same restore; a lane after a lane
compiles nothing — bitwise the old append). `partialConvertSection` on an
s/c source: after installing the two halves, an automatic seam at the cut
boundary off a compile of the post-convert document (either keep side).
`partialConvertBridge` RIGHT onto an s/c-first target: an automatic seam
between the prepended lanes and the s/c (the lanes stay the bridged-in
piece; the s/c's D97 pin keeps its split). `absorbFollower` refuses across
an s/c edge, `absorbFollowerWhy(prev, fi)` = `unstrictSeamWhy(fi)` (the
range flow's gray — a belt: with L1 holding a range never meets an s/c
inside one piece; `isolateRange` needs nothing — an s/c piece is one
section). THE LOAD PASS: `DesignIO.scInvariantLoadPass(c)` runs at the end
of `finishCoaster` only when `scInvariantHolds()` is false (every other
file: no compile, every bit as before); ONE compile at
`Coaster.INTEGRATE_DT`, then last-to-first per piece every s/c at k > 0 and
every non-bridge section after an s/c → `ensureScFollower(…, r, false,
false, false, 0)`; the cuts are counted in the NEW `LoadResult.scSeamsCut`
(a sixth record component; the 5-arg constructor kept) and `legacyNote()`
returns SC_LEGACY_CONVERTED when either count is > 0 — the ONE load
sentence (§10.4), the caller marks dirty (FvdDesigner already does for a
non-null note? — see "For the local session").

§10.6 cells: '+' s/c after force / geo / s/c · '+' force after an s/c ·
Insert s/c at a plain / strict boundary, Lock ON / OFF · partial convert
keep-left / keep-right · bridge partial RIGHT onto an s/c-first target ·
absorbFollower / isolateRange across an s/c edge (refuse / nothing to do) ·
attachPiece's L1 assert (item 1) · Load with an in-piece s/c at k > 0.

### Gate re-pins the load pass forced (OUTSIDE the file whitelist — minimal)

The load pass splits every in-piece s/c, so a fixture that saves `[…, sc]`
or `[sc, sc]` inside one piece and expects a byte-identical round trip
cannot pass any more. Each fixture now builds the same document through
`appendSectionWithInvariant` (the s/c behind an automatic seam — the
compile is the same geometry, the follower's first section real-anchored
and re-frozen at k == 0 exactly like the old in-piece continuation was),
and the one old-file fixture asserts the pass itself:

| gate (not on the whitelist) | row | change |
|---|---|---|
| DetermGate198 | d | the continuation s/c appended through the model (6 passed, unchanged count) |
| uvd.ScRollLaneGate | m1/m2 | the appended straight through the model (63, unchanged) |
| Phase2Gate | scRoundTrip | the s/c appended through the model; `s3` read off piece 1 section 0 |
| uvd.ScElemGate | g | an old `elem` file with two in-piece s/c sections loads as TWO pieces behind an automatic seam: pieces 2, `autoFollower(1)`, `scInvariantHolds`, `legacyScSections 2 + scSeamsCut 1`, one sentence; k's fixture appended through the model (122, unchanged) |
| PlacementGate | c1 | the `porient` line is 6 fields (`… 0` for a free piece) |
| OrientModeGate | c1 | same |

Whitelisted re-pins: InsertGate d3 (the lock insert of an s/c yields [s0] |
automatic [sc] | locked [s1 s2], returns {1, 0}) + d7 through a generalized
`followerClaims(…, fi, insIdx)`; uvd.ConflictGate l14a (both s/c behind
automatic seams; the pinned one still writes 1 and loads pinned +
unflagged — the pin wins on the line). The local session must merge the
six outside-whitelist hunks (each a handful of lines, all in fixture
builders / expected strings).

## 4. Item 4 — smoothing on s/c edges (§3.6, the model half)

`TrackCompiler.smoothEngages(all, spans, spanB, len)` (+ the last-two-spans
form) and `smoothEngagesWhy(all, spans, spanB, len)`: the IDENTICAL window
arithmetic of `smoothStrictSeam` (hUp/hDn = min(len/2, half the adjacent
span's arc), the iA/iB walks, the 2/3-samples-per-side test) with nothing
written; null = engages, SMOOTH_SECTION_SHORT_WHY when the failing side's
half was clamped by its own span's arc, SMOOTH_TOO_SHORT_WHY otherwise (and
for a seam the compile does not cover). The compiler itself needs no change
— the blend is type-agnostic. `makeSmoothingReal`'s s/c refusal sentence is
`SectionNames.SMOOTH_REAL_SC_WHY` verbatim (SmoothRealGate b1 re-pinned on
the constant). The §3.6 SM1 deletion of `GraphEditor.seamScEdge` and its
three sites, and SM3's tooltips / grays, are JavaFX → "For the local
session". ScSeamGate e pins `smoothEngages == (smoothStrictSeam != null)`
over 200 random (len, v) pairs (item 6).

## 5. Item 5 — seam ops + sentences (§3.7 forward align, §3.8's model rows, §3.10)

`Coaster.alignSeamToUpstream(fi, spans)` (+ `Result`): for each LOCKED axis
the held angle := that component of `eulerOf(the upstream span's exit
orientation)` — yaw internal-sign, the `seedStrictValues` convention; roll
skipped when `seamRollOwnedByLane(fi)` (the follower's section 0 is an s/c
whose element 0's lane is absolute, §3.9); locks kept, one-shot.
`alignSeamWhy(fi, spans)`: ALIGN_FOLLOWS_WHY with no locked axis (or only a
skipped roll; also a non-follower / no compile — unreachable from the seam
window), ALIGN_VERTICAL_WHY when `eulerOf` is NaN on an axis to write, else
null. `upstreamExit(fi, spans)` / `seamWorldPos(fi, spans)` = the model
half of §3.8's seam-ball rule (the UPSTREAM span's exit position, not the
follower's first sample one dt inside). REVERSE_MULTI_WHY /
REVERSE_MULTI_CONTROLS_WHY REWRITTEN in place to §3.10's texts (the one
deliberate edit of existing SectionNames lines — L4 forbids the old
"un-strict its seams" advice; LabelGate 13h / 16b / 16m's suffix test
updated accordingly — 16m now admits "… not available yet." beside
"… first."). The §3.10 block at the end of SectionNames now holds
UNSTRICT_SC_WHY, UNSTRICT_CONVERT_WHY, SMOOTH_TOO_SHORT_WHY,
SMOOTH_SECTION_SHORT_WHY, SMOOTH_REAL_SC_WHY, ALIGN_FOLLOWS_WHY,
ALIGN_VERTICAL_WHY, B_NO_TARGET_WHY, ALIGN_BUTTON, ALIGN_TIP,
SEAM_ROLL_ABS_WHY (COMBINE_*, ALIGN_PREV_*, ALIGN_NO_CURVE_WHY,
ALIGN_LOCKED_WHY are Stages 4/5's; REVERSED_WHY stays 2a's). LabelGate
`section20()` appended and called last: 25 checks — the strings verbatim,
`reverseWhy`'s routing, and the model choosers on fixtures
(`unstrictSeamWhy` force|s/c → convert, s/c|s/c → connect, lane seam →
null; `absorbFollowerWhy`; `alignSeamWhy` all-unlocked → follows /
all-locked lane seam → null; `smoothEngagesWhy` 0.3 m → too short, 10 m →
null, a 0.8 m s/c neighbor → the short-section sentence). LabelGate 202 →
227.

§10.6 cells: Reverse on a multi-piece run (gray, the new text) vs a lone
s/c piece (allowed — `reverseWhy(false, …)` null) · the forward align on an
automatic (refuse), a locked (write) and a vertical (refuse) seam · the
seam ball's position (model accessor).

## 6. Item 6 — `gates/ScSeamGate.java` (35 checks) + the §10.2 rows

Fixtures (built in code): base = one piece [force hill 5 s at 26 m/s];
F1 = base → `appendSectionWithInvariant(0, straight 10 m at the compiled
end speed)` = [force] | automatic [sc]; F2 = F1 → a 1 s force hold of the
compiled end state through the same append; F3 = F1 frozen + yaw locked;
F4 = F1 smoothed at 4 m; F5 = [sc 10 m + R20 60°] | automatic [sc 8 m].
The twins = the same sections by direct `sections.add` in one piece.

Measured worst values (the battery run on the final head):

| row | bound | measured |
|---|---|---|
| a0 | L3's fields on the created seam | sAuto, locks off, smoothing 0, LOCKSTEP, section 0 real-anchored, L1 holds |
| a1 F1 vs the in-piece twin | bitwise samples + trueV | bitwise, 560 samples, both settle |
| a2 F2 vs its twin | bitwise | bitwise, 660 samples |
| b1 head x+50 y+7 z−3 yaw+37 on both | bitwise | bitwise |
| b2 moved vs unmoved F1 | v / Gv / Gl / trueV / T.y / U.y bitwise, pos the rigid image ≤ 1e-9 | bitwise / ≤ 1e-9 (D199) |
| c1 upstream Gv node 2.2 → 2.6 on both | bitwise | bitwise |
| c2 head LOCKSTEP pitch +5° on both | bitwise; seamConflictTilt == IDENTITY | bitwise, the IDENTITY object |
| c3 F3 (yaw locked) under the upstream edit | held heading ≤ 1e-9°, local end ≤ 1e-9 m, the twin re-aims | held −120.513813° kept (twin −171.538216°), local end dev 1.10e-14 m |
| e1 F4 | upstream outside the window bitwise vs F1; s fields bitwise vs the same doc unsmoothed; stretch ≤ 5 %; downstream = the closure residual ≤ 0.02 m | window samples 488..512 of 560; stretch 0.0000; inside 1.12e-3 m; downstream 4.8e-8 m; settled |
| e2 `smoothEngages == (smoothStrictSeam != null)` | 0 mismatches over 200 (len 0.05-60 m, v 5-40 m/s) | 0 mismatches, 171 of 200 engage |
| e3 smoothEngagesWhy | 10 m null, 0.3 m too short, an uncovered seam too short | exact |
| f1a F1 at rest | vDesign == trueV[seam] bitwise, design == real across the span, realEntrySpeed predicts it, a second rebuild no-op | bitwise, 15.689745687288 |
| f1b … with friction 0.03 / 1e-4 | same | bitwise, 15.256622431366 |
| f3 a typed 30 m/s pins | stays 30, the dry run predicts max(0.1, real) | real 15.689746, stays 30 |
| f4 Set Design to Real on the follower's k == 0 | lands bitwise == the dry run, un-pinned + flagged | bitwise |
| f2 F4's follower entry gap | level ≤ 1 (" ~v" or nothing), the suspended compile level 0 | gap 1.65e-4 m/s = level 0 (suspended 5.1e-5, level 0) — see deviation 8 |
| g1-g3 unstrictSeam / absorbFollower on F1, F2 (convert sentence) and F5 (connect sentence) | refuse, nothing moves | exact |
| g4 attachPiece across an s/c edge under ASSERT_SC_INVARIANT | throws | throws |
| g5 detachStrictSeam on the automatic follower | a run head, zero deltas, samples bitwise where they were (+ its entry sample) | bitwise |
| h a '+' s/c | ONE compile for the cut, ≤ 2 in the rebuild (Prof, -Dfvd.timing) | cut 1, rebuild 1 |
| h2 a lane after a lane | no cut, no compile | 0 compiles |
| i*1-i*4 the forward align's model op (Stage-5 row i proper SKIPPED) | FOLLOWS on an automatic seam; a locked yaw lands bitwise on eulerOf(exit); roll skipped under an absolute element-0 lane; VERTICAL on a 90°-pitched geo exit | exact, T.y 1.000000000 on the vertical fixture |
| k lock ON after an upstream edit | the freeze writes the contract (pos, baseOrient, the span's exact pair, tiers 0); samples before / after bitwise | bitwise (the stored pose moved from (62.701, 42.915, 35.354)) |
| j unlock-all | the next compile == the never-frozen F1 bitwise | bitwise |
| k2 freeze on a hand-made follower / a run head | false, nothing written | exact |
| l seamWorldPos | == the upstream exit position bitwise; one dropped dt before the first sample | bitwise; 0.1571 m past the seam (v·dt 0.1569) |
| n the §3.10 strings | 13 constants verbatim | exact (LabelGate section 20 is the pin) |
| o1 copy / PlacementSnapshot carry sAuto | exact | exact |
| o2 copyRun of F1 | frames / v / felt / trueV bitwise, positions +5 z ≤ 1e-9 | 0.00e+00 m |
| o3 mirrorRun on F1 | the follower stays automatic, the z-reflection ≤ 1e-9 | 0.00e+00 m |
| d, i, m | SKIPPED | Stage 4 / Stage 5 (model smoke as i*) / the 54-document dump needs the owner's files — RE-RUN LOCALLY |

The §10.2 rows: InsertGate d3 re-pinned + d7 generalized + d8 (a NO-lock s/c
insert at a plain boundary = [s0] | automatic [sc] | automatic [s1 s2], both
first sections real-anchored, the settle terminates, arc == len, bitwise the
in-piece twin — 1336 samples) + d9 (a force hold appended after an s/c tail
gets its own automatic seam) → 40 → 45; uvd.ConflictGate m1/m2 (an s/c
appended through the invariant under friction: vDesign == the real seam
speed bitwise 40.870866633, the span clean, 0 exact rewrites, a second
rebuild no-op; the same seam smoothed at 6 m: lands on the DISPLAYED seam
speed bitwise, the follower's span is the march (29 unequal), the upstream
still copies up to its window, level 0, 2 exact rewrites then 0) → 87 → 90;
RunGate l1-l4 (an automatic follower is a run member; moveRun / deleteRun
through the orphan-guard overloads keep it automatic and bitwise;
freezeAutoFollower false on a run head, the contract on the follower) →
78 → 82; CopyGate k1 (copyRun carries the automatic seam, 0.00e+00 m) →
73 → 74; SmoothRealGate b1 on the constant (21, count unchanged). The
MirrorGate growth lives in ScSeamGate o3 instead (MirrorGate untouched).

§10.6 cells pinned here: every "seams (§3)" cell except the four JavaFX
ones (POV crossing the seam, ties across the seam, the seam window's
button, the menu grays) — listed under "For the local session".

## 7. Deviations from §3 (each with its reason)

1. **A1 behind a TILTED upstream span uses `canonOf(eff)`**, not the
   span's (psi, canon) pair. The pair is the DESIGN chain; when the upstream
   span carries a conflict tilt or a locked follower's re-aim, the world
   exit the follow contract reads is not that chain, and generating at the
   design canon with the IDENTITY tilt would kink the seam in the world.
   The untilted upstream (every fixture in §3.11 a-c) takes the pair
   bitwise; the tilted case decomposes the world pose — D199 dust under a
   yaw move there, the documented tilted-piece limit. (Continuing the design
   chain exactly would need the upstream's UNTILTED exit position on the
   Span — open question 2.)
2. **`firstSectionSettle` returns NaN for a strict follower** (§3.5 A5 said
   `max(0.1, real)`). The op's smoothing-suspended write is not the
   follower's final value any more — the at-rest EXACT reconcile lands it on
   the DISPLAYED seam speed, which `realEntrySpeed` predicts off the
   displayed arrays (bitwise, ConflictGate i7 / ScSeamGate f4); the init-speed
   window's supplier already keeps that prediction when this read is NaN,
   so no JavaFX change is needed for it. Only a bridged-in first section is
   finalized by the op's own write and keeps the suspended read.
3. **`pieceHead` has no auto-follower mirror** — vacuous: a bridge target is
   bridged-in, never a follower; the javadoc says so.
4. **`ensureScFollower` refuses when the section before the cut is a
   bridge** (my reading of §3.3's "the previous piece does not end in a
   bridge"); a bridge is a trailing section, so the branch is a belt.
5. **The load pass counts into a NEW `LoadResult.scSeamsCut`** (a sixth
   record component; the 5-arg constructor kept) and `legacyNote()` returns
   SC_LEGACY_CONVERTED when EITHER count is > 0 — the one load sentence,
   §10.4 — instead of adding the cuts to `legacyScSections` (which keeps
   meaning "converted sections", ScElemGate g pins 2 + 1).
6. **REVERSE_MULTI_WHY / REVERSE_MULTI_CONTROLS_WHY were rewritten IN PLACE**
   (the brief's parallel-work rule says never rewrite existing SectionNames
   / LabelGate lines; the brief also mandates the new texts). The four
   touched lines: the two constants, LabelGate 13h / 16b's expected strings
   and 16m's suffix test (admits "… not available yet."). The local session
   merges them.
7. **The orphan-guard overloads take `List<TrackCompiler.Span>`** (the
   shell holds `sectionSpans`, not a Result); the `Result` forms delegate.
8. **ScSeamGate f2 measured level 0, not the expected " ~v"**: on F4 (a
   10 m STRAIGHT follower behind a 4 m window) the smoothing changes the
   entry gap by 1.65e-4 m/s — under the 0.005 glyph threshold; the
   smoothing-suspended compile reads 5.1e-5. The mechanism (the record is
   the march behind a smoothed seam, the suspended compile reads no gap)
   is pinned; the row asserts level ≤ 1 and reports the level. A curved
   follower with a kinked seam would read ~v (ConflictGate i7's 8° fixture
   moves the seam speed 7e-4 m/s). Open question 1.
9. **`seamConflictTilt` returns the IDENTITY object for an automatic
   follower** (not stated in §3): the compile applies the IDENTITY tilt
   there, and the ≠o predicate (GraphEditor.orientConflictAt,
   FvdDesigner.povConflictTilt) must read the same object while the stored
   design pose is inert and stale (ScSeamGate c2).
10. **`bakeFollow` on an automatic follower IS the freeze** (keeps the
    upstream span's exact pair instead of nulling it — generation stays
    bitwise the A1 branch), and **`detachStrictSeam` runs the freeze first**
    (A3 lists both as prologue sites; being model methods they do it
    themselves — ScSeamGate g5).
11. **Row i (the forward align) runs as model smoke rows i*1-i*4** besides
    the brief's SKIPPED line — an unverified op landed in item 5 would be
    worse; row n duplicates section 20's literals (LabelGate is the pin);
    row l's ties half is SKIPPED (JavaFX).
12. **The MirrorGate growth is ScSeamGate o3** (mirrorRun on F1 keeps the
    automatic seam, the z-reflection 0.00e+00 m); MirrorGate itself is
    untouched.
13. **`Prof.count("compile")`** stamps every `TrackCompiler.compile`
    (guarded no-op off); the gate parses `Prof.report()` so Prof.java is
    untouched.
14. **The six outside-whitelist gate re-pins** of item 3 (DetermGate198 d,
    ScRollLaneGate m, Phase2Gate scRoundTrip, ScElemGate g / k,
    PlacementGate c1, OrientModeGate c1) — each a fixture builder or an
    expected `porient` string; the loader's §3.2 pass and the 6th field
    forced them.
15. **`absorbFollowerWhy(prev, fi)`** reads null unless `fi == prev + 1`
    (the pair must be adjacent to be an absorb at all).
16. **`isolateRange` needs nothing**: with L1 an s/c piece is one section
    (k = m = 0 = last), so a range never cuts INSIDE an s/c piece; on a
    legacy in-piece [force, sc] document `makeSeamStrict(p, 1)` is exactly
    the seam the invariant wants.

## 8. For the local session (the JavaFX half of §3 — file, method, call)

1. **`GraphEditor.addSection(mode, real)` (~7520 `secs.add(added)`) and
   `addLaneSection` (~8172)**: replace the bare `secs.add(added)` with
   `hideJunctionWindows();` (a cut inserts pieces — index-shifting) then
   `int[] at = app.coaster.appendSectionWithInvariant(activePiece, added,
   FvdDesigner.INTEGRATE_DT);` and set `activePiece = at[0]; activeSection
   = at[1];` instead of `activeSection = sections().size() − 1` (after a
   cut the new section is section 0 of piece at[0]); keep the rest of the
   refresh (`app.lanes = lanesFor(added)`, `defaultLane()`, `elSel`,
   `viewT0/viewT1`, `syncLaneButtons()`, `app.rebuildTrack()`,
   `refreshOffset()`, `redraw()`) and add the Insert path's
   `app.updatePieceButton(); app.syncPlacementSpinners();`. A lane after a
   lane is bitwise the old append (no compile, no cut).
2. **The Insert dialog (~8110)**: `insertSectionAtSeam` already returns the
   section's new home and the caller already activates `at[0] / at[1]`;
   add `hideJunctionWindows();` before the call (the cuts shift indices).
   The strict-seam branch's `secIdx = sections().size()` of the upstream
   piece stays. Lock OFF on an s/c now yields [upstream] | auto [sc] |
   auto [downstream]; Lock ON [upstream] | auto [sc] | locked [downstream].
3. **`GraphEditor.seamEdit(fi, write)` (~828)**: inside the `placementEdit`
   lambda, before `write.run()`, call
   `app.coaster.freezeAutoFollower(fi, app.sectionSpans);` (a no-op on a
   non-automatic follower). Same first line in `seamEditDesign(fi, axis,
   deg)` (~888, before `Coaster.seamDesignSet`), in the seam window's MODE
   ROW writer (orientMode / affectSubsequentSeams on a follower), in
   `GraphEditor.seamRealToDesign(fi)` (~1182, before the model call), and
   in the padlock-ON handlers if they bypass seamEdit (they should not).
   `bakeFollow` and `detachStrictSeam` freeze inside the model already.
4. **The orphan guard's one JavaFX site — `GraphEditor.deleteSection(k)`
   (~7385)**: when deleting a piece's LAST section removes the piece p, call
   `app.coaster.freezeAutoFollower(p + 1, app.sectionSpans)` BEFORE the
   removal (the next piece loses its predecessor; a stale stored pose would
   otherwise be its fallback). The run ops: pass `sectionSpans` —
   `coaster.deleteRun(head, sectionSpans)` (the Delete… button),
   `coaster.moveRunTo(dest, head, sectionSpans)` (Switch Coaster…),
   `coaster.copyRun(start, end, Coaster.COPY_DZ, sectionSpans)` (Copy);
   `connectStrictRun` calls the plain `moveRun` on a run head (never
   automatic) — no change.
5. **The seam menu's "Un-strict seam" (~590)**: `String why =
   app.coaster.unstrictSeamWhy(fp)` → `grayItem("Un-strict seam", why)`
   when non-null; the refusal branch posts `why` when the model returns
   false (it refuses on an s/c edge now).
6. **SM1 — delete `GraphEditor.seamScEdge` (8766-8780) and its three
   sites**: the menu gray (563-572 — the "Enable smoothing" item is grayed
   instead with `TrackCompiler.smoothEngagesWhy(app.lastSamples,
   app.sectionSpans, <the follower's first span index>, 10)` when non-null),
   `hitSeamDiamond` (8750-8754) and `drawSeamDiamond` (9076). SM3: the
   hollow diamond's hover tooltip and the Smoothing Options window's
   `grayNote` show `smoothEngagesWhy(…, the box value)` live; a diamond
   drag through a non-engaging length posts the sentence once on release.
   The D232 sentence "Smoothing cannot be enabled on a straight/curve edge
   — the s/c entry ease owns that seam." retires (it has no LabelGate row).
7. **`GraphEditor.seamWorldPos(fi)` (760-765)**: `return
   app.coaster.seamWorldPos(fi, app.sectionSpans);` — the UPSTREAM span's
   exit position (today the follower's first sample, one dropped dt
   inside). Viewport3D's rig anchor, junction ball and
   `junctionBallCoversNode0` read it already.
8. **The seam window**: a row under the three orient rows —
   `Button(SectionNames.ALIGN_BUTTON + " (" + settings.shortcut("smooth")
   + ")")`, tooltip `SectionNames.ALIGN_TIP`, `grayHint(() ->
   app.coaster.alignSeamWhy(fi, app.sectionSpans))`, action
   `pushUndoSnapshot(); seamEdit(fi, () -> app.coaster.alignSeamToUpstream
   (fi, app.sectionSpans)); app.markDirty(); app.rebuildTrack(); redraw();`
   (`seamSync` refreshes its enable state). B / Shift+B: a
   `highlightedSeam()` helper (the revealed seam rig = `Reveal(p, 1)` on a
   follower, keyViewport first then any pane, else the open seam window's
   follower, else −1); in `toggleSmooth` and `matchBoundaryAll` before `if
   (selIdx < 0) return;`: `selIdx < 0 && highlightedSeam() >= 0` → the
   same align call; nothing → `app.statsLabel.setText(SectionNames.
   B_NO_TARGET_WHY)` (today's silent return goes). The roll row under
   `app.coaster.seamRollOwnedByLane(fi)`: `grayHint(SectionNames.
   SEAM_ROLL_ABS_WHY)` on the padlock / spinner / ↺ and no roll ring on the
   seam rig. For an AUTOMATIC follower the window's rows show the live
   follow-contract angles (followerEuler) — they do already; a padlock ON
   holds the current angle through seamEdit (the freeze runs first).
9. **`FvdDesigner.PieceState` (~312 / 472 / 634)**: a `boolean sAuto` slot
   — capture `piece.sAuto`, restore `piece.sAuto = pst.sAuto()` (a restored
   follower without it would fall back to its stale stored placement).
10. **The init-speed window (~16050)**: `if (k == 0)` → `if (k == 0 &&
    !app.coaster.followsPrev(piece))` so a strict follower's first section
    takes the k ≥ 1 sentences (`noConflict` / `noConflictNone` /
    `noConflictFree`), never `noConflictFirstPin`; "This track piece starts
    here — a click sets nothing." stays for run heads. The `realNow`
    supplier needs no change (firstSectionSettle reads NaN for a follower,
    so realEntrySpeed's flagged landing stands). Re-run
    `-Dfvd.initspeedprobe`.
11. **`FvdDesigner.loadDesign` (~10443)**: `r.legacyNote()` now also posts
    for the load pass's cuts (`r.scSeamsCut() > 0`) — make sure the document
    is marked dirty whenever the note is non-null.
12. **Viewport3D (§3.8)**: the POV piece-follow (817-823) and the ride-
    restart test (1405-1411) key on `app.coaster.runStartOf(pc)` — crossing
    an invariant seam activates nothing, entering another RUN activates its
    head (lever `POV_FOLLOW_RUN`); the tie grid phase, the comb loop and the
    heartline strand anchor per RUN (the run head's first sample arc,
    `Coaster.runStartOf` / `spanBase`) — ScSeamGate l's ties half is yours.
13. **`GraphEditor.rangeResolveWhy` (~3467)**: carry
    `app.coaster.absorbFollowerWhy(prev, fi)` for a seam the chain would
    absorb (a belt — unreachable under L1); the badge menu's "Reverse…"
    gray already reads the new `reverseWhy` texts (no edit).
14. Already automatic through the model: `orientConflictAt` /
    `povConflictTilt` read `seamConflictTilt` (IDENTITY on an automatic
    follower); the junction ordinals / window titles count the new seams;
    `reverseWhy`'s texts.

## 9. Open questions

1. ScSeamGate f2: the design expected a " ~v caused by smoothing" at F4;
   the straight follower reads level 0 (1.65e-4 m/s). Keep the row at
   "level ≤ 1" (the mechanism is pinned), or make the fixture a curved
   follower with a kinked seam so the glyph itself shows?
2. Deviation 1: accept `canonOf(eff)` behind a tilted upstream span (D199
   dust under a yaw move there), or carry the upstream's UNTILTED design-
   chain exit position on `TrackCompiler.Span` so an automatic follower can
   continue the design chain bitwise even behind a conflict tilt?
3. `LoadResult.scSeamsCut` as a separate component (deviation 5) — keep, or
   fold into `legacyScSections`?
4. An s/c-first FLOATING piece attached start→end (`connectStrictRun(…,
   false, true, true)`): §3.4 says sAuto false; should its first section
   be real-anchored like the invariant's seams, or keep D135's honest ≠v?
5. The orphan guard's JavaFX site (deleteSection's piece removal, item 8.4)
   — should the model own a `Coaster.deleteSection` so the freeze cannot
   be forgotten?
6. The four file-bound gates (DetermGate197/200, LoadGate196, Nl2ElemProbe)
   and ScSeamGate m (the clean-span dump) need a run on the owner's machine
   before the branch merges; the dump's follower-generation change (A1 +
   A4) is exactly the §10.7 rule-7 trigger.

## 10. Full battery (wiped `target/classes`, JDK 26.0.2, the final head)

`rm -rf target/classes/uvd && mvn -q compile` rc 0 (142 class files, the
whole project incl. the JavaFX files); `javac -encoding UTF-8 -cp
target/classes -d gates/out gates/*.java` rc 0.

| gate | result |
|---|---|
| uvd.ScElemGate | 122 passed, 0 failed |
| uvd.ScRollLaneGate | 63 passed, 0 failed |
| **uvd.ScSeamGate (NEW)** | **35 passed, 0 failed** (d / i / m SKIPPED with reasons) |
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
| CopyGate | 74 (73 + k1) |
| MergeGate | 23 |
| InsertGate | 45 (40 + d8/d8b/d8c/d9/d9b; d3/d7 re-pinned) |
| LabelGate | 227 passed, 0 failed (202 + section 20's 25) |
| uvd.PulseClockGate | 30 passed, 0 failed |
| RunGate | 82 passed, 0 failed (78 + l1-l4) |
| PlacementGate | 41 (c1 re-pinned to the 6-field porient line) |
| OrientModeGate | 51 (c1 re-pinned to the 6-field porient line) |
| GestureGate | 180 passed, 0 failed |
| EndFollowGate | 73 passed, 0 failed |
| FloorGate | 34 passed, 0 failed |
| StripGate | 49 passed, 0 failed |
| uvd.ConflictGate | ALL CONFLICT GATES PASS (90) (87 + m1/m1b/m2; i7 + l14a re-pinned) |
| Nl2ElemProbe | NOT RUNNABLE (owner's file) — RE-RUN LOCALLY |

The clean-span dump (gates/probes/cleanspan-2026-09-30, CleanProbe2 +
compare3.py — ScSeamGate m) needs the owner's 54 documents: RE-RUN LOCALLY
(§10.7 rule 7: follower generation and the k == 0 reconcile changed, so
documents holding a strict follower whose first section is real-anchored
re-settle once).

Commits on `sc-1b-seams` (one per item; `main` untouched):

| commit | item |
|---|---|
| 336f305 | 1 — sAuto, ensureScFollower, makeSeamStrict delegating, autoFollower, scInvariantHolds + the gate-build assert, L4 refusals |
| b681b66 | 2 — the automatic-follower compile branch, freezeAutoFollower + the orphan-guard overloads, the k == 0 real anchor, the A5 javadocs, ConflictGate i7 re-pinned |
| f7741bd | 3 — the belts: insertSectionAtSeam / appendSectionWithInvariant / the partial-convert seams / absorbFollower's refusal / the load pass; the round-trip fixtures re-pinned |
| 4c033b2 | 4 — smoothEngages / smoothEngagesWhy, SMOOTH_* sentences, SMOOTH_REAL_SC_WHY |
| 976e38c | 5 — alignSeamToUpstream / alignSeamWhy / seamWorldPos, the Reverse texts, the §3.10 block, LabelGate section 20 |
| 8a7bca2 | 6 — uvd.ScSeamGate + the InsertGate / ConflictGate / RunGate / CopyGate rows; seamConflictTilt IDENTITY on an automatic follower, the bakeFollow / detachStrictSeam prologue, the Prof compile count |
| (this commit) | the notes' battery + the final sections |
