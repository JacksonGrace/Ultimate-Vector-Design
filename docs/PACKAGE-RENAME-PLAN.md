# Package rename plan: `coaster` → `uvd` (its OWN commit, next session)

**STATUS 2026-09-25: DONE — v19.21 (D250), steps 1-11 executed exactly as below; the
`.uvd` file extension follows as its own commit (v19.22, D251). What remains is the
user's part: the by-hand Apply-and-Restart check and renaming the project folder.**

## Who does what (plain-language version, added 2026-09-25)

Claude does the whole technical rename: re-counting the sites, moving the folder with git, rewriting
every `package coaster;` / `import coaster.` line with count-asserting scripts, the pom main class,
the Apply-and-Restart relaunch literal, the docs' run lines, the wiped compile, the gate battery,
the smoke launch (including a direct `java … uvd.Main` launch, which is the exact command the
relaunch builds), the line-ending/BOM check, and the commit. Steps 1-11 below are Claude's.

You do five small things, none of them code:

1. DECIDE (tell Claude in the message that starts the rename):
   - the package name — `uvd` is recommended (short; `ultimatevectordesign` works too);
   - whether the Maven artifactId changes with it — recommended yes (build-only, harmless);
   - whether the Save-As default file name "coaster.fvd" becomes "design.fvd" — your call;
   - the settings file `~/.fvd-designer.properties` stays (a rename there needs a migration).
2. BEFORE Claude starts: close every running copy of the app, close your code editor/IDE if it has
   the project open (Windows file locks + its own compiler writing stale classes), and pause
   OneDrive syncing (tray icon → Pause syncing → 2 hours) so it cannot hold a file handle mid-move.
3. START it: in a session on this folder say "Do the package rename per
   docs/PACKAGE-RENAME-PLAN.md, package name uvd" (plus your answers from step 1). One usage window
   of work; Claude reports the gate counts, the smoke result and the commit hash at the end.
4. AFTER Claude reports done: resume OneDrive; reopen the editor/IDE and let it re-index — if it shows
   red errors, close it and reopen the folder; if they persist, tell Claude and it walks you through
   the refresh (Maven reload).
5. VERIFY once by hand (the one thing Claude cannot click): launch the app, then Edit > Resolution
   Scale… > "Apply and Restart" — the app must come back on its own. If it does not, tell Claude
   ("the restart didn't come back") — that is the single site a stale class name fails silently.

Nothing else is manual. The memory folder and the repo folder keep their names on purpose.

Written 2026-09-24 with batch 34 (D249, item L). The display name
became "Ultimate Vector Design" in v19.20 (FvdDesigner.APP_NAME);
the Java PACKAGE, the Maven artifactId and the settings-file name
did NOT change. This is the step-by-step for the package half. It
is a pure mechanical rename — no behavior change — and it gets its
OWN commit so `git log -p` on any real change never has to wade
through 130 renamed files.

Site counts measured on the D249 tree (re-count before starting —
the numbers below are what `grep` said on 2026-09-24; the batch-34
brief quoted 43 package lines "incl. 3 gates" and 80 gate imports,
which were the CORE-battery subset):

| what | where | count |
|---|---|---|
| `package coaster;` | src/main/java/coaster/*.java | 34 files (every source file) |
| `package coaster;` | gates/*.java (package-coaster probes) | 16 files: AbsSeamProbe BProbe BridgeSeam2 ConflictGate CrestProbe2 DecompProbe DerProbe KfProbe RampTrace Rev48gProbe ScratchPartialConvert SeamShapeGate StairFileProbe StairProbe StairProbe2 Yaw51Probe |
| `import coaster.…` | gates/*.java | 88 files, 98 lines |
| `<mainClass>coaster.Main</mainClass>` | pom.xml | 1 (+ the comment two lines above it) |
| `coaster.X` gate names | gates/README.md, docs/TESTING.md, CLAUDE.md, memory | the run lines "coaster.SeamShapeGate / coaster.ConflictGate / coaster.DecompProbe" + every "package coaster" mention |
| `coaster.Main` in the dev-hook command line | gates/README.md ("JavaFX DEV HOOKS ride the app") | 1 |
| `coaster.Main` relaunch | FvdDesigner.restartApp (D242 — reconstructs `java … coaster.Main`): a LITERAL `cmd.add("coaster.Main")` (line ~6544 on the D249 tree) + two comments naming coaster.Main | 1 literal (the one silent-crash site) |
| `-Dfvd.devmenu=1` Robot hook (D232/D234) | FvdDesigner — Robot-drives the Coaster menu; references NO class by name and waits on no title | 0 (run it once anyway) |
| `"coaster.fvd"` | FvdDesigner's Save-As default file name (~6478) | not the package — leave, or rename with the user's OK |
| `coaster.FvdDesigner` / `coaster.Main` command lines | memory file coaster-headless-gates.md (line ~61) | the run instructions only |
| gates/attic-noncompiling/ | old probes against dead APIs | leave untouched (they never compile anyway) |

Not renamed (deliberately):
- the MEMORY folder `~/.claude/projects/C--Users-jacks-OneDrive-Documents-AI-Fun-coaster/memory/` — its name is the
  project PATH, not the package; the repo folder `coaster` is not moving either;
- `~/.fvd-designer.properties` (a rename needs a read-old-write-new migration — a separate decision);
- pom.xml `<artifactId>coaster</artifactId>` (build-only; may go with the package or stay — decide at the commit);
- docs history: DECISIONS.md / TESTING.md keep their historic "package coaster" prose verbatim; only the RUN
  INSTRUCTIONS (the `java -cp … coaster.X` lines) change.

## Steps (in order; every step ends with a check)

1. Start from a CLEAN tree on `main` (`git status` empty; the batch-34 commit is in). Record the baseline: a wiped
   compile and the full 33-member battery green, plus one smoke launch — note the exact window title
   ("Untitled — Ultimate Vector Design").
2. Move the source tree with git so history follows:
   ```
   git mv "src/main/java/coaster" "src/main/java/uvd"
   ```
   (Windows: run from Git Bash in the repo root; OneDrive may hold a file handle — retry once if `mv` reports
   "Permission denied".)
3. Rewrite the package lines — SCRIPTED, asserting the count first (never a PowerShell text pipeline: it
   re-encodes and can drop the UTF-8 BOM state or flip line endings):
   ```
   python - <<'EOF'
   import io, glob, os
   files = glob.glob("src/main/java/uvd/*.java") + glob.glob("gates/*.java")
   n = 0
   for p in files:
       s = io.open(p, "r", encoding="utf-8", newline="").read()
       t = s.replace("package coaster;", "package uvd;").replace("import coaster.", "import uvd.")
       if t != s:
           io.open(p + ".tmp", "w", encoding="utf-8", newline="").write(t); os.replace(p + ".tmp", p); n += 1
   print("files touched", n)   # expect 34 + 16 + 88-ish (some gates carry both a package and imports)
   EOF
   ```
   Then `grep -rn "coaster\." src gates --include=*.java | grep -v attic-noncompiling` must list ONLY
   non-package uses (none expected — the word "coaster" in prose/comments stays, `coaster.` with a dot is
   the package) and `grep -rln "package coaster" src gates` must be empty outside attic-noncompiling.
4. pom.xml: `<mainClass>uvd.Main</mainClass>` (+ its comment). Decide the artifactId at the same time.
5. Dev hooks and the relaunch: FvdDesigner.restartApp's literal `cmd.add("coaster.Main")` → `"uvd.Main"` (the
   D242 Apply-and-Restart MUST relaunch the new class — a stale literal fails silently as a spawned JVM that
   exits at once) + its two comments; then grep FvdDesigner for `"coaster.` and `Class.forName(` to be sure
   nothing else holds a literal; the `-Dfvd.devmenu=1`, `-Dfvd.devshot`, `-Dfvd.winshot`, `-Dfvd.pickprobe`,
   `-Dfvd.gridshot(.sweep)` hooks reference no class by name (verified on the D249 tree — re-verify).
6. Docs run lines: gates/README.md ("Run: … (package-coaster probes: coaster.<Name>)", the core-battery
   entries `coaster.SeamShapeGate` / `coaster.ConflictGate` / `coaster.DecompProbe`, the dev-hook command
   `-cp target/classes coaster.Main`), docs/TESTING.md's "run as coaster.X" instructions, CLAUDE.md
   ("Every file starts with `package coaster;`", "Main class: `coaster.Main`", "Sources live in
   `src/main/java/coaster/`", the HEADLESS GATES paragraph's `coaster.SeamShapeGate`), and the memory file
   coaster-headless-gates.md's command lines → `uvd.X` / `src/main/java/uvd/`. Historic prose stays.
7. Wiped compile: `rm -rf target/classes && mvn compile -q; echo EXIT=$?` (wipe the WHOLE classes dir this
   once — a stale `target/classes/coaster/` would let old-package classes shadow the gates' imports).
8. Gates: `rm -rf gates/out && javac -cp target/classes -d gates/out gates/*.java`, then the full battery with
   the new names (`java -cp "target/classes;gates/out" uvd.SeamShapeGate` etc.). Every count must equal the
   baseline from step 1 (a rename changes no arithmetic — DetermGate197/198 bitwise).
9. Smoke launch: `mvn javafx:run` — the title reads "Untitled — Ultimate Vector Design" (kill ONLY that exact
   clean title started after the launch; a starred title is the user's session); Edit > Resolution Scale… >
   "Apply and Restart" once (the relaunch is the one place a stale class name would crash silently).
10. BOM / CRLF scan on every touched file (the D248/D249 rule: keep each file's endings; no BOM appears
    where there was none): `git diff --stat` should show ~140 renames/edits and NO whole-file churn.
11. Commit ALONE: "v19.21: package coaster → uvd (mechanical rename; no behavior change)". Do not fold any
    fix into it.

## Pitfalls (each cost a session before)

- ASSERT-UNIQUE ANCHORS: scripted edits assert the count of every anchor before replacing (the v18.3 splice-damage
  postmortem in memory); `package coaster;` should hit exactly once per file.
- CRLF: DECISIONS.md is mixed CRLF/LF (its D247+ tail is LF); source files are LF; write with `newline=""` and
  never let a tool normalize a whole file (git's "LF will be replaced by CRLF" warning is autocrlf noise, not a
  file change — but check `git diff --stat` for whole-file rewrites).
- BOM: DesignIO loads a BOM'd .fvd file (D216) but the Java sources have none — a PowerShell `Set-Content`
  would add one and javac rejects `﻿package`.
- The D232/D234 Robot hook (`-Dfvd.devmenu=1`) drives the Coaster menu through a Robot on the live app — it
  names no class, but it is the cheapest end-to-end proof that the renamed app boots and its menus work; run it
  once (it exits 0 when green).
- The smoke title check in TESTING #39 / #166 and CLAUDE.md already says "Ultimate Vector Design"; the
  rename changes the CLASS name only.
- gates/Phase2Gate.lost-edits.txt and gates/attic-noncompiling/ mention `coaster.` — leave them (history).
- Scratchpad probes (Temp) are session artifacts — nothing there to rename.
- `git mv` + a content edit in the same commit is fine for git's rename detection (similarity stays > 90%);
  do NOT reformat the moved files in the same commit.
