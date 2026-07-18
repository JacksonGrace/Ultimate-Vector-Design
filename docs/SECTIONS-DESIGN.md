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
"become real" (direct attach stays first-class beside bridges —
§5 spec'd in full 2026-07-07, user-approved; ROUND A live v13.6
D79: per-run Pass D + true Pass A + drift display; rounds B/C to
come); then
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
  solver needed); GREW into the continuation march (v13.4 D77) and
  then march v2 + deterministic lens + "Optimize length" (v13.8
  D82): the march blends the seam states as jerk-slope-matched cubic
  Hermites, the tangent lengths come from a fixed deviation-
  minimizing grid against the march path (same geometry = the one
  same bridge — the old rescale drifted per round trip), and an
  optional length knee search collapses matched seams toward zero
  (the "connect directly?" prompt).

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

## 5. Design vs TRUE speed: display, "become real", influence curves

STATUS: designed in full 2026-07-07 (user-approved); ROUND A LIVE
(v13.6, D79 — true speed exists and shows; 5.1/5.2 implemented);
CONVERT LIVE (v13.7, D81 — 5.3b, user-spec'd during round A and
landed early); ROUND B LIVE (v14.5, D97 — the resolve-conflict
submenu implements 5.3's two ops); ROUND C LIVE (v15.0, D98 —
influence mode, re-spec'd CROSS-MODE by the user 2026-07-11; the
5.4/5.5 amendments below record where the shipped design departs
from the 2026-07-07 text). §5 is COMPLETE.

### 5.0 The problem, and the property that shapes the design

Each section's geometry integrates at its own design-speed profile
v_d(s) (energy from vDesign at the section's entry height, §0). The
TRUE speed is what a train actually carries there. They drift apart
whenever upstream edits change the energy reaching a seam — '+'
freezes vDesign only at creation, B/Shift+B re-freeze only at match
time (D78) — and every felt/rate curve the app shows is recorded at
DESIGN speed, so drift makes the graphs lie about the ride (D78's
"jolt exactly on the transition line" was this, at one seam, fixed
seam-locally). §5 makes drift first-class:

- SHOW it: finish Pass D/A so true curves exist and draw (5.1-5.2).
  Result.trueV has been WRITE-ONLY since Phase 1 — nothing consumes
  it today; POV, stats and all felt curves ride design speed.
- FIX it: two explicit per-section reconciliation ops (5.3) — one
  keeps the lanes and reshapes, one keeps the shape and re-authors
  the lanes ("become real").
- SCULPT on it: influence mode (5.4) — sparse delta lanes over a
  self-computed reference, for adjusting a section at its true
  entry speed without abandoning the authored lanes.

The property doing most of the work (CORRECTED during round A — the
design pass overclaimed "seamless by construction"): TRUE curves
tell the GEOMETRIC truth. True speed is continuous along connected
geometry (energy never steps), and true felt/rates are pure
functions of (κ, v_true) — so they are continuous exactly where the
track's CURVATURE is, and step exactly where it physically kinks,
independent of all design-speed bookkeeping. Both directions
matter, and both are gated (TESTING #21 b/b2): a felt-matched force seam
whose design speeds drifted LOOKS smooth in the design lanes while
the ride physically kinks — the true curve steps and exposes it
(D78's jolt, made visible); a stale-vDesign s/c (or bridge) seam
STEPS in the design felt while the ride is actually smooth — the
true curve stays joined and calls off the false alarm. Design
curves need the freeze rules and the D74 speed blend to stay
honest; true curves never do. The blend in particular is a
design-side device: Pass D rides a bridge as plain geometry, so the
true curves show the ride WITHOUT the blend's "invisible
brake/boost" (Phase 3 makes speed real and subsumes this honestly).

### 5.1 Finish Pass D and Pass A (round A)

Pass D becomes per-RUN (run = maximal chain of pieces linked by
trailing bridges — the same structure GraphEditor.runLayout walks,
re-derived model-side from "last section is a BridgeSection"):

    vRun = the run's FIRST section's vDesign (run 0: == v0, the
           Start-speed coupling unchanged)
    yRun = the run's first sample height
    v_true(s) = sqrt(vRun² + 2g(yRun − y(s)))    within the run

A detached piece is its own run — its trueV references its own
entry, not the ride start (today's passD referenced sample 0 of
the whole document, meaningless for floating pieces; harmless only
because nothing consumed it).

Pass A grows true-value arrays alongside rates[][]. NO differencing
— the recorded design felt already encodes curvature exactly:

    κ_U = g(Gv_d − U.y)/v_d²            (per sample; Gl/R.y alike)
    Gv_true = κ_U·v_true²/g + U.y
            = (v_true²/v_d²)·(Gv_d − U.y) + U.y
    true rates = design rates · (v_true/v_d)      (roll included)

Exact per sample, uniform across ALL modes (force, geo, s/c,
bridge — each records felt at design speed). Graphs keep plotting
over the design time axis (the shared axis); true values plot at
their sample's position on it. POV advance and the stats-bar top
speed switch from Sample.v to trueV (identical when nothing has
drifted; ride-time stat stays the axis duration).

### 5.2 Display (round A)

- TRUE STROKES: wherever |true − design| exceeds a pixel threshold,
  the lane/derived felt curve gains a THIN solid companion stroke
  in the same lane color (design keeps the full-weight stroke). No
  new toggle: the strokes splitting IS the drift indicator; they
  rejoin when reconciled. (Reserve alternative if this reads noisy
  in feel-testing: a "True" pick in the Resulting popup on the
  right-edge axis.)
- BADGE hint: a drifted section's badge wears "≠v"; its badge
  MENU's info line spells it out ("… — design 20.0 m/s · entered
  26.4 m/s"; canvas badges have no native tooltips, the menu is one
  click and already the reconcile-op home). Threshold DRIFT_EPS
  0.2 m/s, tunable.
- HOVER: the lane hover readout appends the true value when
  drifted ("2.50 g (true 2.91)"); Shift/slope likewise.

### 5.3 Reconciliation ops (round B — badge menu, one undo step)

Two ops with opposite contracts:

- "SET DESIGN SPEED TO ENTRY (reshape)": vDesign := trueV at the
  section's first sample; lanes/elements untouched. Force/geo
  geometry regenerates at the new speed (the §0-sanctioned way
  shape changes: an edit to THIS section); an s/c SPINE is
  untouched by construction and its felt re-times — but RATE-mode
  roll is per-second by design (D69), so RATE-roll banking DOES
  re-shape with the speed; GLOBAL-roll s/c is fully stable.
  Disabled on bridges
  (their vDesign is live-captured). This promotes D78's B-matching
  side effect to an explicit op — with one deliberate difference:
  B-matching freezes to the UPSTREAM DESIGN-END speed (seam-local
  agreement even under compound drift); this op freezes to TRUE
  (global honesty). On a clean chain they coincide. A chain-wide
  convenience ("Re-freeze all design speeds") sweeps upstream-
  first with a recompile per section — each section's true entry
  depends only on already-frozen upstream, so one sweep converges
  exactly; afterwards design == true everywhere in the run.
- "BECOME REAL (keep shape, re-author lanes)": vDesign := trueV at
  entry; the lanes are REWRITTEN as a fit of the TRUE felt/rate
  curves on the true time axis; influence state (if any) clears.
  Why shape is preserved (closure): the true curves are exactly
  the targets that, integrated at true speed, turn at the existing
  curvature — g(G_true − U.y)/v_true = κ·v_true. The only error is
  fit residual + the dt cadence quantum (the unfreezeBridge story,
  D78, which is this op's proven mechanism). FIT RULE: nodes at
  the AUTHORED nodes' time-mapped positions (t_d ↦ s ↦ t_true) —
  the author's node structure carries intent and survives — with
  values + slope-matched handles from the true curves (effOutDt/
  effInDt, as unfreeze); if the baked shape residual exceeds
  BECOME_REAL_TOL (~0.05 m, tunable) the worst segments midpoint-
  subdivide up to a cap. The sketch's "first node forced to match
  upstream" happens BY CONSTRUCTION: at vDesign == true entry the
  first fitted value IS the upstream true exit felt.

Round-1 refusals (menu-disabled with tooltip): REVERSED sections
(lanes author the opposite direction, and a yaw-placed mismatched
seam is unrepresentable as a forward section's entry — becoming
real there needs an auto-split-at-the-seam story first; splits are
bitwise, so this is a natural round-2), BRIDGES (Convert, 5.3b, IS
their become-real), s/c (shape cannot drift; re-freeze only). Geo
sections get both ops — same formulas on rates.

### 5.3b CONVERT — mode re-authoring (v13.7, D81 — LANDED early)

USER SPEC during round A: "two modes to edit geo on a force
section and vice versa: influence and Convert", presented unified
and simple. CONVERT re-authors a section's compiled shape as the
OTHER lane mode at its own design speed — a REPRESENTATION change,
orthogonal to 5.3's speed reconciliation (§5.1 drift, if any,
survives conversion; the ops compose freely). Directions:
force↔geo, s/c→either, bridge→either — v13.5's bridge-only
"Unfreeze (edit as geo section)" renamed and generalized into it
(a converted bridge still stops following and splits its run).
Engine: Coaster.convertToLanes, the unfreeze recipe generalized —
per-meter rates measured off the samples, re-timed onto the
target's energy profile (identity for force/geo/s-c profiles, real
work for bridge blends), fitted as a 0.1 s slope-matched node
carpet ("delete nodes to taste", D78); geo lanes = the rates,
force lanes = the felt targets ω·v/g + axis.y at that profile,
and BOTH keep the same roll-rate third lane. UI: a "Convert to
geo/force section" group at the bottom of EVERY badge menu, only
the other mode(s) offered; reversed sections refuse (5.3's rule).
vDesign carries over. Gated TESTING #23 (mm–2 cm on geo/s-c
fixtures; 0.3% relative across the whole default layout). Round
B's become-real shares this machinery — the authored-node-times
fit (5.3) upgrades both when it lands.

### 5.4 Influence mode (round C) — the reference/base construction

SHIPPED v15.0 (D98) with user amendments (2026-07-11) that OVERRIDE
this section where they conflict:
- CROSS-MODE: the delta lanes live in the OTHER mode's units and add
  to the section's RESULTING other-mode graphs (force section → geo
  rate deltas, geo section → felt-G deltas; lane 2 = shared roll).
  passG integrates base+delta AS the other mode, so resulting ==
  base + delta by construction.
- NON-MODAL: the authored lanes stay editable (badge toggle between
  the two surfaces); "dormant" below is dead.
- vApply lives in the vDesign FIELD (every consumer unchanged); the
  NEW field vAuthored freezes the authored speed for ref. The §5.6
  file slots are unchanged (section line = authored, infl line =
  operating). Influenced sections are EXEMPT from the D95 same-piece
  reconcile — "influencing creates the split" (user note): vApply
  moves only by explicit action.
- Exits: "Bake influence into design graphs…" (= convert to the OWN
  mode via the density preview) replaces "Become real"; "Discard
  influence" restores bitwise (gated). Partial converts refuse on
  influenced sections. Reversed: entry refused; carried influence
  keeps applying under piece reversal (canonical two-pass), editing
  locked.

The original text (kept for the construction, which survives):
the sketch's `effectiveTarget = resulting + delta` is kept, with
"resulting" pinned down so regeneration can NEVER feed itself (the
feedback loop D72 originally guarded against, relocated here by
D73): the base is computed fresh every compile from the section's
OWN authored data — never from its own output.

    ref      = integrate(authored lanes, authoredVDesign, entry
               pose)                    — the section's old shape
    v*(s)    = sqrt(vApply² + 2g(y_entry − y_ref(s)))
    base(t*) = true curves along ref at the v* profile (5.1's
               formulas), resampled onto the bake grid over
               t*(s) = ∫ ds/v*
    geometry = integrate(base + bake(deltaLanes), vApply, entry
               pose)                    — passG runs TWICE

vApply is captured := the section's true entry speed when the mode
is ENTERED (that instant's reconciliation) and stored; it does NOT
live-follow upstream (that would violate §0 — shape changing on
upstream edits). base is a pure function of (authored lanes,
authoredVDesign, vApply, entry pose): deterministic, exact across
save/load (no dense snapshot persisted), and upstream edits move
it only as they move any force section — through the entry pose
(height translations cancel in y_entry − y_ref: shape is
translation-invariant, same as today). delta ≡ 0 reproduces the
authored shape exactly up to discretization (5.3's closure), gated.

Mode rules:
- LaneSection only (force + geo). Reversed sections refused in
  round 1 (5.3's reason). s/c and bridges have nothing to gain.
- MODAL: while influenced, the authored lanes are dormant (not
  directly editable — one editing surface per section); the strip
  edits the DELTA lanes. Exit paths, one undo step each: "Become
  real" (fits the EFFECTIVE curves, clears influence) or "Discard
  influence" (deltas + vApply dropped, authored lanes govern —
  the shape snaps back).
- The section's DURATION becomes t*_end (true traversal time);
  strips re-lay out via the spans as usual.
- vDesign()/vDesign(v) — the interface, and everything through it
  ('+' vDesign freezing, bridge vB capture, B-matching's D78
  seam-speed re-freeze) — reads AND writes vApply while
  influenced: the active generator's speed. The authored field
  stays bound to the dormant lanes; DesignIO writes it explicitly.
- B/Shift+B on an influence section write the DELTA edge nodes:
  delta = neighbor's target effective value − base at the edge
  (slope likewise) — the existing matching machinery minus base.
- '+' after an influence section holds the effective end values
  (base end + delta end): Coaster.continuation reads effective.
- The future TIME/DISTANCE domain toggle applies to delta lanes
  the same way it applies to lanes (they live on the section's
  axis, whatever unit it is).

### 5.5 Influence UX (round C; §10's open question, decided)

SHIPPED v15.0 (D98): the user asked for BOTH visuals as a toggle —
"Resulting view" is this section's effective-view (nodes ride
base+delta, purely visual, one dispOff choke point through draw /
hit / drag / box-select; Ctrl value-snap lands the RESULTING value
on the grid) and "Influence view" (the DEFAULT) is the reserve
alternative below (deltas around zero, the resulting graphs dotted
behind, hoverable). Badge-menu radios switch; the badge wears "fx",
lane buttons wear Δ, and the Resulting ▾ overlays read the TRUE
other-mode values while either influence view is active (user
spec). In the DESIGN surface of an influenced section the true
strokes reference the AUTHORED curves, so the influence's effect
(e.g. the laterals a yaw delta causes) draws beside the untouched
design lanes.

The original text: EFFECTIVE-VIEW editing — the strip draws the EFFECTIVE curves
(base + delta) as the lane curves — the graph keeps showing real
ride quantities, per the unified-view rule — with the BASE as a
dashed guide underneath (lane color, low alpha). Delta nodes RIDE
the effective curve: a node plots at (t, base(t) + delta); a drag
writes delta = y − base(t); a zero-delta node sits exactly ON the
guide (a fresh influence section is all guide — grab anywhere and
sculpt). Handles shape the DEVIATION — the drawn segment is
base + deltaBezier, so a "flat" handle follows the guide, not the
horizontal; knobs plot base-offset like nodes. Zero-line fills
reference the guide, so the tint shows exactly what the delta
adds/removes. (Reserve alternative if effective-view editing
confuses in feel-testing: delta lanes drawn around zero in their
own band, effective as a derived overlay — one redraw-path switch;
both share the same delta model.)

Badges: influenced sections wear an "fx" mark + the two exit menu
entries; the ≠v drift hint (5.2) stacks on top (vApply itself can
go stale against later upstream edits).

### 5.6 Persistence + undo

FVDD3, inside a lane-section block (unknown-line rules cover old
readers; missing = no influence):

    infl vApply                  (presence = influence mode on)
    dlane i  +  dn ...           (delta nodes, exact `n` grammar)

The `section` line's vDesign field stays the AUTHORED speed.
Rounds A/B change NO format (become-real writes ordinary lanes).
Undo: DocState already deep-copies sections; it grows the two
fields. base/ref are derived and never stored anywhere.

### 5.7 Rounds and gates (round A gated in TESTING #21)

Round A — true speed exists and shows: per-run Pass D, true Pass A
arrays, true strokes + badge hint + hover, POV/stats on trueV.
IMPLEMENTED (see D79). Gates (live in Phase2Gate.trueSpeed):
(a) a clean '+'-frozen chain keeps trueV == design v to float
noise, and a detached piece's trueV references its own entry;
(b) BOTH seam truth directions — a felt-matched force seam with a
drifted downstream design speed reads smooth in design felt while
the TRUE felt steps (the hidden kink, D78's jolt made visible),
and a stale-vDesign s/c seam steps in design felt while the TRUE
felt stays continuous (the false alarm suppressed); a bridged run
carries ONE energy body (no trueV step at bridge seams; the blend
honestly diverges inside); (c) the single-force bit-for-bit gate
untouched, trueV == Sample.v bitwise there (Pass D/A additions are
read-only).

Round B — reconciliation: the two badge ops + chain re-freeze.
Gates: (d) become-real shape preservation ≤ tol on tame + violent
fixtures (UnfreezeProbe pattern) and felt honesty — recompiled
design felt == previous true felt ≤ fit residual; (e) idempotence
— a second become-real is ~a no-op, re-freeze after become-real is
an exact no-op; (f) after the chain sweep, design == true along
the whole run.

Round C — influence: model + IO + two-pass passG + strip UX +
B-matching + '+'. Gates: (g) delta-0 closure — shape == authored
shape ≤ fit tol; (h) §0 invariance — an upstream translation
leaves an influence section's local shape bitwise (pose tilts
affect it exactly as they affect a plain force section);
(i) infl/dn round-trip byte-identical, old files load
uninfluenced; (j) delta-space B-matching idempotent at a drifted
seam.

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

## 7. Dynamics pass details (FRICTION LANDED v17.0 D133; speed
##    regions complete it)

`dv/ds` from: gravity component along T, minus rolling friction
(dimensionless coefficient c_rr, deceleration ≈ c_rr·g·|N|/m ≈ c_rr·g for
a first cut), minus air drag (k_air·v², k_air in 1/m). NL2 calls these
"friction" and "air resistance". RK4 for Pass G's frame ODE (DONE in
Phase 0, D44) and Pass D's speed ODE. v floor 0.5 m/s stays
(garbage-but-finite on stalls).

SHIPPED v17.0 (D133) exactly as above (the c_rr·g first cut, no |N|
scaling yet): the two coefficients live on the Coaster (`friction`
line, Coaster-menu rows, document undo), a loss deficit joined the
RK4 state of every integrator AND Pass D's per-run march (Heun over
the sample arc), and every profile re-computation (converts via
Coaster.fitLanes, influence, bridge blend) marches the same deficit.
Zero coefficients are bitwise the old model. REVERSAL under friction
(D134, user re-spec same day — a first design-direction scheme was
walked back): "Reverse piece" is a CONVERT — the compiled geometry
mirrors and re-fits as forward sections at one energy flow from the
recorded end speed (or a custom one, padlocked row in the density
popup); same section structure, no speed conflict, shape to the fit
residual with friction on; bridge pieces refuse; the reversed FLAG
survives only as the legacy-file compile path. See PHYSICS.md §3
and DECISIONS D133/D134.

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
- ~~Influence-mode UX: where the delta lanes live in the graph
  strip~~ decided in the 2026-07-07 design pass (5.5):
  effective-view delta editing, base as a dashed guide (the
  around-zero band kept as the reserve alternative).
- Speed-region overlap policy vs auto-trim.
- DISTANCE-domain force sections: exact ds-grid bake spacing.
