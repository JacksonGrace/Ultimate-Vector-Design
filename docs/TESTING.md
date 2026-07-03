# TESTING.md — What's verified, how, and what to keep testing

## The trust boundary (most important thing in this file)

- **Verified headlessly:** everything in `Vec3`, `FVDIntegrator`,
  `BezierBaker`, the save-format read/write logic, and the default layout's
  end-state invariants.
- **Never executed by the AI assistant:** all JavaFX code in `FvdDesigner`
  (the original dev environment had no display, no JavaFX, no javac). The
  human user has run and confirmed earlier UI versions on Windows; the v5
  additions (POV camera, rails, spinners, save/load dialogs) compile-checked
  only by careful reading. Treat UI behavior claims accordingly.

## Existing validations (convert these to JUnit in this repo)

1. **Circle closure (integrator ground truth).** Flat unbanked turn,
   v0=20 m/s, Gl=1.5 ⇒ analytic radius v²/(g·Gl)=27.192 m. Assert: after one
   circumference the position error < 5 cm and end·start heading dot > 0.999.
   (Historical result: 1.5 cm over 170.9 m.) Note: originally written for the
   distance-domain integrator; re-express in time domain (duration =
   circumference / v).

2. **Default layout invariants (bake + integrate pipeline).** Build the
   shipped three-lane default (node tables live in `FvdDesigner`; consider
   extracting to a shared `DefaultLayout` class so tests and app share one
   source of truth). Assert: baked keyframes strictly increasing in t, all
   finite; end |pitch| < 3°; end U.y > 0.99. (Historical: pitch 0.2°,
   U.y 1.000, 13.46 s, ~407 m.)

3. **Baker fuzz.** 200 seeded-random layouts (4–13 nodes, random values in
   ~2× the UI ranges), then scramble ALL handles with out-of-range values
   (dt exceeding segments, wrong signs, huge dv). Assert: baked output
   strictly monotonic in t, all values finite, zero exceptions. (Historical:
   0 violations.)

4. **Save round-trip.** Serialize lanes (include ≥1 broken-smooth node and
   non-default handles) + heart/gauge through the same writer/parser logic as
   `FvdDesigner`, re-parse, compare field-by-field at 1e-3 tolerance.
   (Historical: 0 mismatches.) Recommend extracting save/load into a
   `DesignIO` class so the test exercises the REAL production code instead of
   a copy — the historical test used duplicated logic, which is a known
   weakness.

5. **Banked-turn physics identity (recommended addition).** Bisect Gv for
   "turn ends level" at a 40° bank through the full pipeline and assert the
   result is within 2% of 1/cos(40°) ≈ 1.305. This single test cross-checks
   the ω equations, roll accumulation, and frame conventions.

## The historical headless harness (context, not a prescription)

The dev sandbox had only a JRE, so multi-file compilation was impossible.
Tests ran via Java's single-file source launcher against a concatenation:

```
{ cat TestMain.java.part
  sed 's/^public record/record/; /^package/d; /^import/d' Vec3.java
  sed 's/^public class/class/;  /^package/d; /^import/d' FVDIntegrator.java
  sed 's/^public class/class/;  /^package/d; /^import/d' BezierBaker.java
} > Combined.java   # prepend shared imports, test class must be first & public
java Combined.java
```

Quirks that shaped it: exactly one public top-level class per file; the
class with `main` must appear first; shared `java.util` imports hoisted and
stripped from sources. In this repo none of that is needed — use JUnit 5 via
Maven Surefire. The pure-core/JavaFX split is what makes that possible;
preserve it.

## Manual test script for UI changes (until UI tests exist)

After any `FvdDesigner` change, run `mvn javafx:run` and walk:
1. Window fits screen incl. title bar (test target: 1920×1080).
2. Keys 1/2/3 switch lanes; inactive curves faint and unclickable.
3. Click node → ring + translucent handles. Drag knob: moves along its line
   only. Shift-drag: angle changes; partner handle follows (smooth mode).
4. B on an interior node: knobs circle↔square; re-smooth swings the RIGHT
   handle to match the LEFT.
5. Double-click adds node ON the curve (no visible jump); right-click deletes;
   endpoints refuse deletion.
6. A appends 1 s; ride time in stats bar grows by 1.0.
7. R: POV ride — right-side-up, facing forward, banks through the turn,
   loops; R exits; orbit drag disabled while riding.
8. Heart spinner to −1.0: rails jump above the heartline (inverted); POV now
   hangs below track. Gauge visibly widens/narrows rail spacing.
9. Ctrl+S → .fvd file; edit something; Ctrl+O the file back: design, heart,
   gauge all restored; Ctrl+Z after load returns the pre-load design.
10. Undo spam: Ctrl+Z ×20 walks history without exceptions; one drag = one
    undo step.

## Debugging heuristics learned the hard way

- Wild 3D output after math edits → check the frame-rotation sign (`−ωU·R`)
  and the Y-flip discipline before anything else.
- "Track dives underground / loops unexpectedly" is usually a LAYOUT problem
  (curvature ∝ 1/v² runaway), not an integrator bug — reproduce headlessly
  and print pitch over time before touching physics code.
- Parameter scans that respond non-monotonically mean the track is passing
  through vertical; shrink the phase durations and re-bisect.
- If numbers parse/format weirdly for a European user: a missing `Locale.US`.
