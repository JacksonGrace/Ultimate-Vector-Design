# SECTIONS-DESIGN.md — Phase 1+ architecture: sections, pieces, and the geometry/dynamics split

Status: PHASE 1 COMPLETE (v9.5, D50-D55); PHASE 2 STARTED — GEO
SECTIONS LIVE (v10.0, D58, gated TESTING #12): LaneSection base under
the sealed Section, quaternion-native GeoIntegrator, mode-aware graph
editor, `section geo` in FVDD3, '+'-menu entry; UX rounds v10.1-v10.2
(D59-D60):
unified one-mode graph view (other-mode strips show their RESULTING
curves), geo lane palette, zero-line fills, section badges + delete,
boundary return (value + slope, cross-mode, seam-safe), insert-then-
drag, section-end snap + wall, snap axis toggles, purple time,
mode-aware Resulting overlays; REVERSAL live (v10.9, D67 — badge-menu
toggle, canonical generation + yaw placement, gated TESTING #13);
STRAIGHT/CURVE SECTIONS LIVE (v11.0, D68, gated TESTING #14): first
non-lane mode, element list (straight | arc radius+angle+direction)
with per-transition sine smoothing from day one, DISTANCE domain,
ScIntegrator (breakpoint-aligned sub-stepping — analytic circles AND
analytic sharp chains), the ELEMENT STRIP WIDGET replacing the lanes
in the graph, `section sc`/`elem` in FVDD3, per-section Spans on the
compile Result; s/c ROLL modes v11.1 (D69 — rate °/s or GLOBAL bank
held vs the horizon); SPLIT/ATTACH/PIECE SELECTOR LIVE (v12.0, D70,
gated TESTING #15): badge-menu split, '+'-menu attach, toolbar
piece selector, Piece x/y/z + yaw spinners, global-timeline windows;
v12.1 (D71): splits carry the FULL boundary orientation
(TrackPiece.baseOrient — bitwise at any boundary, user rule) and
REVERSAL became a whole-PIECE operation (Coaster menu; model
transformation — track stays put; the §2 "yaw about vertical only"
placement note now means the EDIT DELTA — baseOrient itself carries
arbitrary orientation).
BRIDGES LIVE (v13.0 D72, gated TESTING #16; reworked v13.1 D73 on
user review): LIVE-FOLLOW quintic (re-captured every compile; frozen
only when orphaned), G² ends (seam curvature continuity), bridge to
ANY piece with timeline reorder, closed-form optimize-placement
action, tangent dialog; viewport meshes break at piece gaps and
plain-click activates a piece.
Still to come in
Phase 2: piece placement 3D gizmos (pitch editing user-approved if
revertible), mid-section node-anchored split (user-confirmed),
influence curves +
"become real" (direct attach stays first-class beside bridges); then
the TIME/DISTANCE domain toggle for force/geo
lanes (user-confirmed roadmap item — the Domain slot has waited in
the model since Phase 1). One AMENDMENT to
§2 below: quaternions are the model-level
orientation (poses, placements, exit poses) but the FORCE-section
integrator keeps stepping T/U as vectors — §1's bit-for-bit gate
outranks §2's "Pass G integrates orientation as a quaternion" (they
conflict; re-discretizing would shift every float). Geo sections DO
integrate quaternions natively (GeoIntegrator, as amended — the
force/geo equivalence canary in TESTING #12d validates the pairing),
and so does ScIntegrator.
Read PHYSICS.md first;
this doc builds on its conventions (T/U/R frame, time-domain force
equations, Y-flip is rendering-only and irrelevant here).

## 0. The one invariant that shapes everything

**Connecting, splitting, re-entering, or re-simulating track must NEVER
change the geometric shape of existing sections.** A force section's shape
depends on the speed used to integrate it (curvature = g·ΔG/v · 1/v). So
shape must not be a function of *actual* speed — otherwise touching
anything upstream (a brake, a bridge, a lift) would ripple geometry through
the whole ride. Therefore:

- Every section owns a **design entry speed** `vDesign`. Its geometry is
  generated *only* from its own control curves + its entry pose + vDesign.
- The *actual* speed at any point comes from a separate global pass and
  affects **forces experienced**, never shape.

This is the load-bearing decision. Everything else is bookkeeping.

## 1. Three-pass pipeline (replaces the single integrator pass)

```
Pass G (geometry, per section, cached until its inputs change):
    (EntryPose, section control data, vDesign) -> Centerline
    Centerline = dense samples { s_local, pos, T, U, R (unit quaternion
    internally), designV }   spacing: by arc length (~0.10 m target)

Pass D (dynamics, global, cheap, reruns on any change):
    assembled centerlines + v0 + friction params + speed regions
    -> trueV(s), trueT(s)      (time re-derived: dt = ds / v)

Pass A (analysis, global):
    geometry + trueV -> true Gv, Gl, longitudinal G, pitch/yaw/roll rates
    -> "resulting" graphs, POV, stats, exports
```

Key consequences:
- Pass G is exactly today's integrator run with `v0 = vDesign` and energy
  referenced to the section's own entry height (design-speed *profile*
  inside the section still evolves by energy — a section is internally
  consistent; it is only *external* speed changes that must not deform it).
- Pass D walks the fixed geometry: `v² = vEntry² + 2g(y_entry − y)` minus
  friction losses, overridden inside speed regions (§6).
- Today's app = one force section with vDesign = v0 = 26. Phase 1 must
  reproduce current output bit-for-bit (same dt, same math) before
  anything new is added.

## 2. Data model

```
Coaster
 ├─ globals: v0 (dynamics entry speed), friction params, per-coaster
 │           cosmetics (heart, gauge, ties, colors — unchanged)
 └─ pieces: List<TrackPiece>            // disconnected groups
     TrackPiece
      ├─ placement: origin (x,y,z) + heading yaw (rotation about
      │             vertical only, per user spec)                // gizmo-editable
      └─ sections: List<Section>        // contiguous chain
          Section (sealed interface; one record/class per mode)
           ├─ common: name, domain (TIME | DISTANCE), reversed flag,
           │          vDesign, influence curves (§5), cached Centerline
           ├─ ForceSection:    lanes Gv/Gl/rollRate (today's model)
           ├─ GeoSection:      lanes pitchRate/yawRate/rollRate
           ├─ BridgeSection:   target = next piece's entry; solved spline
           │                   (§4); solvedGeometry is FROZEN until the
           │                   user explicitly re-solves (feedback guard)
           ├─ StraightCurveSection: element list (line length | arc
           │                   radius+angle, optional smoothing), always
           │                   DISTANCE domain
           └─ ExternalSection: imported sample geometry (NL2 CSV), always
                               DISTANCE domain
 └─ speedRegions: List<SpeedRegion>     // §6 — global, arc-length anchored
```

**EntryPose contract:** `{ pos: Vec3, orient: Quaternion }` plus running
offsets (global s, global t) for bookkeeping. A section's exit pose is a
pure function of (entry pose, its geometry); the next section consumes it.
The first section of a piece takes its entry pose from the piece placement.

**Orientation:** quaternion internally everywhere (Pass G integrates
orientation as a quaternion; T/U/R derived on demand). Euler angles are a
DISPLAY/readout convention only — never a second math path.

## 3. Section modes — geometry functions

- **Force:** today's equations at the design-speed profile:
  `ωU = g(Gv − U.y)/v`, `ωR = g(Gl − R.y)/v`, roll about T; `v` from
  section-local energy starting at vDesign. TIME domain native; DISTANCE
  domain converts via the design-speed profile (dt = ds/v).
- **Geo:** the designer *specifies* body rates (pitch/yaw/roll, deg/s or
  deg/m). Integration is direct — no solver, speed-independent in DISTANCE
  domain. This supersedes the old "needs a solver" roadmap note (that was
  for *targeting* end pitch, which can come later as a tool ON these).
  IMPLEMENTED v10.0 (D58) in TIME domain (deg/s; DISTANCE arrives with
  the domain toggle): GeoIntegrator, quaternion-native, felt-G recorded
  at design speed; PHYSICS.md §2b has the equations.
- **Straight/curve:** analytic; IMPLEMENTED v11.0 (D68). Element list
  of straights (length) and arcs (radius + signed angle + direction:
  0 = flat turn with +angle = rider-right, 90 = pull-up); per-element
  ENTRY smoothing length — a sine curvature blend centered on the
  boundary (total turn angle preserved exactly for straight↔arc;
  halves clamp inside short elements). Always DISTANCE domain; shape
  fully speed-independent. Authoring is the ELEMENT STRIP WIDGET in
  the graph (user pick over dialog-first/viewport-first — the original
  "node editing in the viewport" idea can still join with the
  placement-gizmo work). ScIntegrator sub-steps at profile breakpoints
  so sharp chains are analytic, not just per-element circles.
  Per-element ROLL since v11.1 (D69): RATE °/s (roll-lane semantics)
  or GLOBAL bank ° held against the horizon via a spine-preserving
  T-roll projection (NL2-classic banking; global 0 = level rails
  through anything — the station/brake default for new elements).
- **Bridge (§4)** and **External:** produce/carry fixed geometry.

**Reversal:** a geometry-level transform (run the centerline backward,
T → −T, R → −R, U unchanged). Original lanes
stay editable; regeneration re-applies the flip. No shape change.
IMPLEMENTED v10.9 (D67): canonical-frame generation (identity heading,
entry height) + yaw-only placement onto the entry's horizontal heading
(a force shape is gravity-honest only in its authored orientation, so
a pitched/banked seam shows its mismatch honestly). SIGN CORRECTION to
the original note here: felt Gl negates, but the ROLL RATE is
traversal-INVARIANT — T and the bank-change-per-second both flip and
cancel (derived + gated, TESTING #13). SCOPE CHANGE v12.1 (D71): the
USER-FACING reversal is per PIECE (Coaster menu > Reverse piece) — a
model transformation (train-ordered sections, toggled per-section
flags, placement re-anchored at the old exit) so the track stays put;
the per-section flag above is the mechanism and kept its exact D67
semantics.

## 4. Bridge sections

Connect exit pose A → entry pose B with G¹-continuous position and
orientation (quintic Hermite on position with end tangents ∝ T_A, T_B;
orientation slerped with roll matched at both ends; length parameter
user-adjustable). Two modes:
- **Autocomplete:** both pieces stay put; the spline spans the gap as-is.
  IMPLEMENTED v13.0 (D72), upgraded v13.1 (D73): BridgeSection.java —
  quintic with p'' ends = the NEIGHBORS' curvature (G², beyond this
  section's G¹ spec, user upgrade), rotation-minimizing frames + a
  sine-eased twist instead of a literal slerp (roll matched both
  ends), per-end tangent lengths in a badge dialog, bridge to ANY
  piece (timeline reorder). Gated TESTING #16.
- **Optimize placement:** solver may translate piece B and yaw it about
  vertical (ONLY those DOF, per spec) to minimize bridge strain
  (curvature/torsion cost), then solve the spline. IMPLEMENTED v13.1
  as an explicit repeatable ACTION (closed form: with the gap distance
  held, minimum strain = dead ahead with headings aligned — no numeric
  solver needed).

AMENDED v13.1 (D73, user decision): the bridge is NOT frozen — it
LIVE-FOLLOWS its endpoints (the compiler re-captures poses + seam
curvatures every compile). The original feedback-loop concern was
real but belongs to §5 influence mode, where regeneration feeds its
own inputs; a bridge's geometry never feeds its endpoints, so
following is stable. Only a bridge whose target piece is GONE stays
frozen at its last capture (badge "!").

AMENDED v13.2 (D74, user: "no snap — slopes too"): the seams are
smooth beyond G²: SEPTIC position ends (curvature derivative matched,
⊥-projected — the tangential T'' term rings, D74), a speed profile
eased onto the target's entry design speed (rates = κ·v, so v steps
were rate steps), and a twist whose end slopes are the neighbors'
roll rates. The rate/felt graphs are continuous in value AND slope
across both seams; cramped geometry still legitimately produces
fast-but-smooth interior transients (the gap spinner / tangents /
optimize are the cure). A toolbar GAP spinner re-runs the placement
optimization at the chosen distance on every change.

## 5. Design-vs-true editing, influence curves, "become real"

A section entered at trueV ≠ vDesign keeps its shape; its TRUE forces
differ from the authored lanes. The designer can:
1. **Edit at design speed** (default): lanes mean what they always meant;
   the resulting graphs (Pass A) show the true forces alongside.
2. **Influence mode:** author delta curves on top of the *resulting*
   curves: `effectiveTarget(t) = resulting(t) + delta(t)`, re-integrated
   at the true entry speed to REGENERATE the section's geometry. (This is
   still Pass G — just with a different control parametrization; vDesign
   for such a section is defined as the true entry speed at last apply.)
3. **"Become real" button:** sets vDesign := current true entry speed,
   bakes the resulting curves (+deltas) into ordinary editable lanes
   (first node forced to match the bridge/upstream exit state), clears
   influence mode. One-way, undoable via document snapshot.

## 6. Speed regions (Phase 3, but the model lands in Phase 1)

Global list, **anchored by arc length s** (survives force/geo/domain
toggles), length fixable in time OR distance (converted via trueV when
toggled, then held in the chosen unit). Three modes:
- constant speed (v clamped; implied longitudinal G reported by Pass A),
- constant longitudinal G (dv/ds = g·(GlongTarget − gravity/friction
  terms)/v integrated through the region),
- curve mode (Bezier lanes over the region for v(s) or Glong(s), reusing
  the whole node/handle editing stack).
Pass D applies them in order; overlaps are invalid (reject on placement).

## 7. Dynamics pass details (Phase 3 completes this)

`dv/ds` from: gravity component along T, minus rolling friction
(dimensionless coefficient c_rr, deceleration ≈ c_rr·g·|N|/m ≈ c_rr·g for
a first cut), minus air drag (k_air·v², k_air in 1/m). NL2 calls these
"friction" and "air resistance". RK4 for Pass G's frame ODE (DONE in
Phase 0, D44) and Pass D's speed ODE. v floor 0.5 m/s stays
(garbage-but-finite on stalls).

## 8. Persistence, undo, UI

- **FVDD3:** hierarchical text (piece blocks -> section blocks -> lanes),
  same Locale.US + unknown-line rules. FVDD2/1 files load as one
  ForceSection in one piece (existing converters reused). Write FVDD3
  only.
- **Undo:** snapshot the whole Coaster document per gesture (same pattern,
  one level up). Redo becomes worth adding here.
- **Graph:** one strip along the time/distance axis per section; inactive
  sections greyed (like inactive lanes today); click a greyed section to
  activate; section boundaries drawn. IMPLEMENTED v9.5 (D55) with the
  user's insertion gesture: a vertical line + circled '+' past the ride
  end opens the add-section menu. The Phase-2+ roadmap items LANDED in
  v12.0 (D70): the '+' menu lists FLOATING PIECES to attach, and the
  toolbar PIECE SELECTOR shows one piece at a time (its global time
  window; 3D Ctrl+click also activates a piece). Splitting a PIECE is
  the badge menu's "Split piece before this section"; insert-BETWEEN-
  two-sections (splitting a strip into two sections) is still to come.
- **FvdDesigner split**: COMPLETE (GraphEditor v9.2 D52; Viewport3D +
  Dialogs v9.4 D54; DesignIO v9.0 D50 — round-trip tested at last);
  FvdDesigner is the ~1.1k-line shell. Pure core grew: Section types,
  passes.

## 9. Testing gates (each phase blocks on these, headless as always)

- Phase 1: default layout reproduces EXACTLY against the RK4 baseline
  (re-baselined in Phase 0, D44: 271 keyframes, 1347 samples, 13.46 s,
  406.878 m, pitch 0.2284°, U.y 0.999971); circle + banked-turn + squish
  + split suites pass; FVDD2 round-trip loads identical.
- Connect-invariance test: assemble A→bridge→B, then change v0, add speed
  regions, edit A — assert B's centerline is bitwise unchanged.
- Geo section: constant pitch rate over Δt ⇒ exact pitch change; roll
  likewise (analytic).
- Bridge: endpoint pose match to tolerance; frozen-until-resolve behavior.
- Reversal: forward/backward centerlines identical point sets.
- Dynamics: energy conservation with zero friction; constant-v region
  holds v; drag-only deceleration matches closed form.

## 10. Open questions (decide when the phase starts)

- Design-speed profile vs constant vDesign inside a section (spec above
  says profile-by-energy; revisit if sections get long).
- Bridge cost function weights (curvature vs torsion vs length).
- Influence-mode UX: where the delta lanes live in the graph strip.
- Speed-region overlap policy vs auto-trim.
- DISTANCE-domain force sections: exact ds-grid bake spacing.
