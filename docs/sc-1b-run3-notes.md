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
