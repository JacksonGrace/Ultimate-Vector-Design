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
