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
