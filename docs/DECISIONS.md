# DECISIONS.md — Chronological decision log

Each entry: what was decided, why, and what it superseded. Newest model wins;
earlier entries explain the fossils you may find in old commits/files.

## D1. FVD approach itself
Specify rider forces (Gv, Gl, roll rate) and integrate them into geometry,
rather than drawing splines and computing forces after. This is the product's
identity; a public reference for the technique is Ian Kopack's
"Force-Vector Design Roller Coaster Centerline Modelling" series
(iankopack.com).

## D2. Language: prototyped in JS, product in Java
First working integrator was Node.js (fast iteration, testable in the dev
sandbox). Ported to Java at the user's request; results matched exactly
(same 1.5 cm circle-closure error), proving the math ports cleanly. The JS
files (`fvd-integrator.js`, `test-circle.js`) are historical artifacts only.

## D3. Pure-Java core / JavaFX shell split
`Vec3`, `FVDIntegrator`, `BezierBaker` have zero JavaFX imports. Reason:
the dev environment had no JavaFX, no display, and no `javac` (JRE only),
so the only way to validate anything was headless single-file execution.
This constraint produced the project's best architectural property — keep it.

## D4. Build: Maven + javafx-maven-plugin
Chosen over manual JavaFX SDK + `--module-path` flags after the user hit the
usual setup friction (winget couldn't find Maven → manual zip+PATH install
worked). `mvn javafx:run` is the only supported launch path. Java 21,
JavaFX 21.0.2, mainClass `coaster.FvdDesigner`.

## D5. Rendering: JavaFX 3D over jMonkeyEngine/LWJGL
Lowest-friction path to a live 3D view inside the same window as the editor.
Sufficient for rails + POV. Revisit only if scenery/lighting ambitions grow.

## D6. Graph editor: Canvas immediate-mode
One `Canvas` with custom hit-testing rather than scene-graph nodes per
control point. Simple, fast, full control over alpha/overlays.

## D7. Editor transitions: cubic Beziers with user handles (v2)
Replaced linear interpolation between keyframes. User explicitly wanted
adjustable Bezier handles, not preset cubic easing. Handles stored as
(dt, dv) offsets from the node. Defaults = flat tangents at 1/3 segment,
which yields the "sinusoidal" force transitions the FVD community
recommends, with zero user effort.

## D8. Bake-then-integrate
Editor curves are baked to dense linear keyframes; integrator unchanged.
See PHYSICS.md §7 for rejected alternatives.

## D9. Handle interaction model (evolved across v2→v4, all user-driven)
- v2: handles revealed on HOVER → superseded; handles blocked node access.
- v3: handles appear on CLICK-select only; smooth (collinear) handles by
  default; **B** toggles smooth/broken per node; re-smoothing keeps the IN
  (left) handle and re-angles the OUT (right) — exact user spec. Knob shape
  encodes mode: circle = smooth, square = broken.
- v4: default drag changes handle LENGTH only (pixel-space projection onto
  the current direction); **Shift** unlocks angle. "Smooth" is slope-matching
  (dv/dt), the unit-correct C1 notion — NOT screen-angle matching, which
  would depend on axis scales.

## D10. Undo (v3)
User asked for it "in the future"; implemented immediately because the B
toggle made destructive edits cheap. Deep snapshots of the whole model, one
per completed gesture (snapshot staged on mouse-press, committed on first
actual movement so click-only selections don't pollute history). Cap 100.
No redo yet. Heart/gauge deliberately excluded.

## D11. Time domain replaces arc length (v4) — BREAKING
User request; also matches FVD++. Rewrote `FVDIntegrator` around angular
velocities (ω = g·ΔG/v, rad/s) and dt stepping; roll rate became deg/SECOND
(lane scale ±120°/s). Old distance-based files (`FvdViewer`, `TestCircle`)
became incompatible and were deleted from the tree. The default layout was
re-tuned from scratch in time domain (see D14).

## D12. Single overlaid plot (v4)
Replaced three stacked lanes. All curves share full plot height, each on its
own value scale; active lane opaque + interactive, others 25% alpha and
mouse-transparent; keys 1/2/3 or toolbar toggles switch. Left axis relabels
in the active lane's color.

## D13. Fully independent per-lane nodes (v5) — BREAKING model change
Previously one node carried all three values (moving it in time moved all
lanes). Now each lane has its own `List<Node>`. Ride end = max last-node t
across lanes; shorter lanes hold their final value (drawn as a flat
continuation). Append (+1 s, key A) extends all three lanes holding final
values. Node insert now takes its value from the actual curve
(`BezierBaker.evalAt`) instead of linear neighbor interpolation.

## D14. Layout tuning by per-phase bisection
Hand-tuned layouts repeatedly blew through vertical (curvature ∝ 1/v²
runaway as trains slow on climbs; non-monotonic parameter response).
Working recipe: bisect each phase duration against a pitch target
(pull-up +28°, float −32°, pull-out → end pitch 0, then bisect turn Gv for
level), always through the FULL bake+integrate pipeline. Also derived: the
banked-turn identity Gv = 1/cos(φ) (validated <1%), and pull-outs must be
longer than pull-ups. Current default: 13.46 s / ~407 m — crest, diving
drop, level pull-out, 40°-banked turn, ends level.

## D15. Rails + heartline (v5)
Integrated path treated explicitly as heartline; two 140 mm rails at
`heart − U·heartOffset ± R·gauge/2`. Heart (default 1.0 m, negative =
inverted) and Gauge (default 1.0 m) are toolbar spinners; changes rebuild
live. Single-tube rendering retired. Ground plane sits under the lowest
RAIL, not the heartline. Known accepted gap: no ties/spine yet (user spec
was exactly two rails).

## D16. POV camera (v5)
Toggle with R / toolbar button. `AnimationTimer` advances ride time
(looping), camera oriented by an `Affine` built from the sample frame
(derivation + sign-flip debugging table in PHYSICS.md §6). POV camera node
lives inside the `world` group so scene centering applies to it. Orbit
input disabled while riding. Least-verified code in the project.

## D17. Save/load format `FVDD1` (v5)
Plain text: header, `heart`, `gauge`, then `lane i` sections of
`n t val outDt outDv inDt inDv smooth` lines. `Locale.US` on ALL numeric
formatting (comma-decimal locales would corrupt files). Unknown lines
ignored (forward compatibility). Lanes must load with ≥2 nodes or the load
is rejected. Loading pushes an undo snapshot first. Round-trip verified.

## D18. Window sizing from screen bounds (v3 fix)
Hardcoded 1200×900 overflowed the user's 1920×1080 (bottom lanes + title bar
off-screen). Now sized from `Screen.getPrimary().getVisualBounds()`
(excludes taskbar), capped 1400×880, centered. Never hardcode window sizes.

## D19. Testing method: single-file concatenation harness
No `javac` in the dev sandbox — only the `java` single-file launcher. Tests
were run by concatenating (public-stripped, package-stripped) sources plus a
test main into one file. See TESTING.md. In this repo, convert the four
existing validations into JUnit and keep the harness description for
historical context.
