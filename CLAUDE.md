# CLAUDE.md

## What this project is

An FVD-style roller coaster design tool (in the spirit of **FVD++ / Force Vector
Design**). The user does not draw track geometry directly. Instead they edit
three curves over **ride time**:

1. **Vertical G** (1.0 = normal gravity felt through the seat)
2. **Lateral G** (0 = no side force; +Gl = the body pushed RIGHT
   since v18.13 D184 — a flat right turn reads NEGATIVE; older
   files' laterals load mirrored, user-accepted)
3. **Roll rate** (degrees per **second** of banking)

The software numerically integrates those force targets into a 3D track. This
is the core insight of the whole project: *the designer specifies what the
rider feels; the geometry falls out of the physics.*

Written in **Java 21 + JavaFX 21**, built with **Maven**.

## Build & run

```
mvn javafx:run        # build + launch the designer
mvn compile           # compile-only error check
mvn clean             # nuke target/ if the build gets into a weird state
```

- Main class: `coaster.FvdDesigner` (set in pom.xml's javafx-maven-plugin config).
- Sources live in `src/main/java/coaster/`. Every file starts with `package coaster;`.
- JavaFX comes from Maven (`org.openjfx:javafx-controls:21.0.2`) — no SDK
  download, no `--module-path` flags. Do NOT launch via a plain `java` command
  or IDE run button; only `mvn javafx:run` sets up the JavaFX modules.

## Source files & data flow

```
Vec3.java           immutable 3D vector record (add/sub/scale/dot/cross/
                    normalize + Rodrigues rotate). No dependencies.

BezierBaker.java    EDITOR MODEL + CURVE MATH. Pure Java, no JavaFX.
                    Three independent lanes of Node{t, val, handles, smooth}.
                    bake(lanes, grid) samples the per-lane cubic Beziers onto
                    a uniform grid of linear Keyframes (grid units =
                    the lane's axis; 0.01 s TIME / 0.05 m DISTANCE
                    since v17.11 D151). EXACT values since v17.11
                    (exactAt: bisection straight on the owning
                    segment's cubic — the physics path no longer
                    reads bakeLane's ~0.1-unit parameter-space
                    polyline, whose knots moved under inserts/
                    splits; the editor keeps the polyline for
                    drawing/hit tests). segGaps/
                    rescaleDvToGaps (v16.1, D105): node moves keep
                    handle SLOPES — dv rescales with the squished
                    segment run (supersedes D63's "kinks accepted").

FVDIntegrator.java  PHYSICS. Pure Java, no JavaFX. Takes keyframes
                    (t, Gv, Gl, rollRateDeg), RK4 at dt=0.01s, produces
                    Samples (t, s, pos, T/U/R frame, v, Gv, Gl); also
                    bodyRates(samples) — resulting pitch/yaw/roll °/s
                    for the read-only graph overlay. END LANDING
                    (v17.10, D147; GeoIntegrator's time loop too):
                    the closing step is sized (0, 1.5·dt] so the last
                    sample lands EXACTLY on the authored end — the
                    old loop refused it on float overshoot and every
                    seam ate up to one dt (split-at-node moved the
                    track end, the user's report; interior steps stay
                    bitwise). DISTANCE mode
                    (v16.0, D103): integrateTrackDist — keyframe
                    t-slots hold ARC LENGTH, s joins the RK4 state
                    (s' = v, the ScIntegrator pattern), lookups by
                    the arc traveled, the FINAL step Newton-lands
                    exactly on the lane end; advance floored 0.5 so
                    stalls creep and terminate (gated: dist-authored
                    circle 2.6e-12 m). GLOBAL roll (v16.1, D107,
                    Options.rollGlobal): the roll lane integrates a
                    bank-vs-horizon target (exact per-step trapezoid)
                    enforced by projectBank after every step — the
                    roll term leaves Ω; rate 0 holds the bank through
                    pitched turns; vertical track holds off
                    (bankOfTU NaN). Local = bitwise-historic.
                    FRICTION (Phase 3, v17.0 D133): the Friction
                    record (roll·g + air·v² per meter, ± design
                    direction) + a LOSS deficit in the RK4 state of
                    BOTH domains — v = sqrt(v0² + 2g(y0−y) − loss),
                    4-arg speedAt; zero coefficients keep loss exactly
                    0.0 = bitwise-historic (the old inert per-second
                    bleed field is gone). Geo/Sc/Bridge and every
                    profile re-computation march the same deficit.
                    SPEED CONTROLS (v18.0, D160): Options.ctl (+ ctl
                    overloads on Geo/Sc integrate) — per-step drives
                    replace friction, post-step snaps write the loss;
                    both domains; null = every historic path bitwise.

AppSettings.java    APP-WIDE PERSISTENT SETTINGS. Pure Java, no JavaFX.
                    Shortcut bindings + FOV in ~/.fvd-designer.properties
                    (combos are strings like "Ctrl+Z"; UI parses them).
                    Per-coaster settings do NOT go here — they go in .fvd.

SpeedControl.java   SPEED CONTROLS (Phase 3, v18.0 D160). Pure Java.
                    One lift/brake/launch interval on a section's OWN
                    authoring axis [x0, x0+len]: type SPEED (target
                    m/s, INSTANT snap) | ACCEL (felt longitudinal G
                    magnitude toward a target — loss' = ∓2gA, friction
                    absorbed by the drive, crossing lands ON the
                    target), either COMPLEX (Bezier graph replaces the
                    constant; exactAt reads), min/max flags = ceiling/
                    floor semantics (≥ 1 always on). The Ctl evaluator
                    drives (per step) + snaps (post-step) the LOSS
                    deficit in all three integrators — null = bitwise;
                    rowFor/driveRow/snapRow are passD's per-sample
                    twins (design and real move EQUALLY, which is how
                    a control closes a ≠v conflict); portion/
                    marksPortion clip controls exactly for splits/
                    converts (complex lanes de Casteljau at the cut).

Nl2CsvExporter.java NL2 EXPORT. Pure Java, no JavaFX. Resamples samples
                    onto NoLimits 2's external-track CSV (tab-separated
                    pos + front/left/up rows). Negates Z on the way out
                    (our rendered world is left-handed, NL2 right-handed
                    Y-up) — change signs only with DECISIONS.md D40 open.

--- Phase 1 document model (D50) — all pure Java, no JavaFX ---

Quat.java           unit quaternion (mul/rotate/fromFrame/aboutY). The
                    MODEL's orientation type; identity paths are
                    float-exact (the bit-for-bit gate rides on that).
TrackPose.java      pos + orient (+ running global s/t offsets): section
                    entry/exit contract.
Section.java        sealed interface (domain TIME|DISTANCE, vDesign,
                    reversed — getters AND setters since v11.0, so the
                    editor writes through the interface; domain SETTER
                    + viewOtherDomain since v16.0 D103 — the
                    cross-domain view flag, session state like
                    editInfluence: in DocState, never saved) — permits
                    LaneSection + StraightCurveSection + BridgeSection;
                    external joins the list when it lands.
LaneSection.java    abstract base of the THREE-LANE modes (v10.0, D58):
                    lanes + vDesign + reversed live here so the editor,
                    undo, IO and compiler handle force and geo through
                    one type. DOMAIN field (v16.0, D103): TIME = lane
                    x is seconds (classic), DISTANCE = lane x is
                    METERS of track; values keep their units either
                    way (G, °/s — a domain re-parameterizes the
                    authoring AXIS, which is what makes the cross-
                    domain view's exact warp possible; a distance GEO
                    section's shape therefore still depends on design
                    speed, unlike s/c). AXIS flags rollGlobal /
                    yawPitchGlobal (v16.1, D107 — local defaults =
                    historic, bitwise) + RATE UNITS ratePerMeter
                    (v16.6, D115: rate lanes author °/m — consumed
                    rate = value × v at every stage, global-roll bank
                    targets integrate over ARC; force Gv/Gl are
                    TARGETS, untouched; DISTANCE × °/m geo shape is
                    fully speed-independent, gated 0.08 mm; influence
                    WORKS on °/m sections since v16.7 D117 — deltas
                    author °/m, the operating-speed re-expression is
                    the identity there) + ABSOLUTE ROLL rollAbsolute
                    (v16.7, D118: lane 2 = the BANK ANGLE °, global
                    roll only — the projection targets the lane
                    value, feed-forward from its slope; CONSUMED C1
                    since v16.9 D121: FVDIntegrator.bankHermite CR
                    + TrackCompiler.exactRoll knot-exact resample —
                    the raw 0.05 polyline's staircase derivative +
                    bakeLane's ~0.1 walk grid fill made the rate
                    step per cell, gated 47d 3.56→0.88 °/s; B reads
                    the seam bank off the NEIGHBOR-side sample and
                    UNWRAPS past ±180° to the representative nearest
                    the edge node — v16.8 D120: our own edge sample
                    is post-projection (B parroted the lane at
                    itself) and atan2 mirrored cutback banks; slope
                    best-effort by user acceptance; rate lanes never
                    read an abs neighbor's lane as a rate — sampled/
                    world paths; influence refuses; B/Shift+B
                    TOWARD an abs NEXT neighbor refuse outright
                    with a stats hint, v16.10 D122 — its entry is
                    enforced by its OWN lane, match from ITS first
                    node) + the
                    CROSS-RATE VIEW viewOtherRate (v16.7 D119,
                    COMPLETED v16.9 D121 as the user's actual spec:
                    a full edit-in-the-other-units warp — dispVal/
                    nativeVal scale by v(x), strokeLaneCurve's warp
                    branch draws the polyline through dispVal (the
                    v16.7 gap: raw bezier control points left the
                    curve native under warped nodes; v16.8's
                    unwarp-the-nodes answer was the wrong direction,
                    reverted), updateScale fits DISPLAYED values so
                    the °/m defaults govern, derivedDisp/trueDisp
                    wrap the drawing/hover reads while B-matching
                    keeps the NATIVE derivedAt/trueAt, right-edge
                    axis shows the native units; session-only,
                    auto-drops on leave).
                    INFLUENCE state (v15.0, D98;
                    distance sections join in v16.1 — the two-pass
                    runs over ARC; GLOBAL-AXIS sections join in
                    v16.5 D112, with their own influence axis flags
                    inflRollGlobal/inflYawPitchGlobal — how the
                    two-pass integrates base+delta AS the other
                    mode; entry mirrors the section's roll choice):
                    deltaLanes
                    (OTHER-mode delta lanes; null = uninfluenced) +
                    vAuthored (frozen authored speed — the vDesign
                    FIELD stays the one OPERATING speed everything
                    reads/writes) + editInfluence (which surface the
                    strip edits; in DocState, never saved) +
                    baseCurves (last compile's base rows {t*, L0..L2},
                    transient — the editor's guides/offsets read them
                    via baseValAt/baseSlopeAt; deltasAllZero() gates
                    the do-no-harm fast path).
ForceSection.java   classic FVD mode: lanes = Gv / Gl / roll rate.
GeoSection.java     Phase 2 (v10.0, D58): lanes = pitch / yaw / roll
                    rate (°/s), integrated DIRECTLY — no solver;
                    gravity never bends the shape.
GeoIntegrator.java  GEO PHYSICS. Pure Java, no JavaFX. RK4 on (pos,
                    quaternion), q' = ½(0,Ω)⊗q, Ω = yaw·U − pitch·R +
                    roll·T; reuses bake's Keyframe slots (Gv=pitch,
                    Gl=yaw — mapping documented there only); records
                    FELT Gv/Gl at design speed so rail colors/stats
                    stay honest. qEnd = the LAST SAMPLE's quaternion
                    (v13.6 D80: the post-loop state is one step PAST
                    it — handing that out rotated every downstream
                    section by a phantom end-rate×dt, the "instant
                    roll jump" at geo seams; exit == last drawn
                    sample is the all-modes contract, gated).
                    DISTANCE mode (v16.0): integrateDist, the
                    FVDIntegrator pattern — rates stay °/s over an
                    arc-length axis. GLOBAL axes (v16.1, D107,
                    omegaFor): yawPitchGlobal = lanes 0/1 rotate
                    about world-Y / the heading's horizontal axis
                    (heading + elevation rates; vertical T falls back
                    to body axes); rollGlobal = the FVDIntegrator
                    bank-projection. Felt records the BODY components
                    of Ω under global flags (local path bitwise).
                    See PHYSICS.md §2b.
StraightCurveSection.java  Phase 2 (v11.0, D68): the first NON-LANE
                    section — an ELEMENT list: straight (length) | arc
                    (radius, signed angle, direction: 0 = flat turn
                    with +angle = rider-LEFT since v18.16 D187 (the
                    profile negates the U component — the D185 yaw
                    boundary; old flat-arc files mirror), 90 =
                    pull-up unchanged), each with
                    an ENTRY smoothing length (element 0's = the seam,
                    ignored) and a ROLL spec (v11.1, D69): RATE °/s
                    (roll-lane semantics; 0 = bank drifts through
                    pitched turns) or GLOBAL ° (bank HELD vs the
                    horizon; 0 = rails stay level — NL2-classic).
                    New elements default GLOBAL 0; the loader defaults
                    old elem lines to RATE 0. Always DISTANCE domain:
                    shape is speed-independent (except RATE roll,
                    which is per-second by design). Elements mutable,
                    editor-owned.
ScIntegrator.java   S/C PHYSICS. Pure Java, no JavaFX. Curvature-vector
                    profile over arc length (Ω/m = κ(cos·U − sin·R),
                    sine-eased blends CENTERED on boundaries — total
                    turn angle exactly preserved, halves clamped inside
                    short elements; RATE roll joins Ω as ω_T = rate·T,
                    blended the same way but NOT v-scaled); RK4 on
                    (pos, quat, s) in TIME with
                    v algebraic; SUB-STEPS to land every RK4 leg
                    exactly on profile breakpoints with side-capped
                    lookups (stepping OVER a κ step costs ~0.1°/cross;
                    k4 reads the far side) + Newton landing on the
                    section end. GLOBAL roll = an algebraic PROJECTION
                    after every leg (roll about T to the eased bank
                    target — never bends the spine; ease from the
                    measured entry bank over max(smoothIn, 2 m);
                    sub-1e-12 corrections skipped so planar runs stay
                    bitwise; no-op near vertical track). Samples stay
                    on the dt grid. Records
                    felt Gv/Gl at design speed like GeoIntegrator.
BridgeSection.java  Phase 2 (v13.0 D72; live-follow v13.1 D73; seam
                    smoothness v13.2 D74): connector — the last
                    section of a piece, joining
                    its chain end to the NEXT piece's entry. The
                    compiler RE-CAPTURES the solve inputs EVERY compile
                    (poses; seam curvature acc + its ⊥-projected
                    derivative jerk, MEASURED off the chain tail and a
                    pre-integrated target head; seam roll rates; the
                    target's entry speed vB), so the
                    bridge FOLLOWS moves of either side live (no
                    feedback loop exists here — that concern is §5's);
                    the stored fields are the last capture and govern
                    alone only when the target piece is GONE (orphan =
                    frozen + badge "!"). Geometry: SEPTIC Hermite
                    (p/p'/p''/p''' both ends — rate graphs continuous
                    in VALUE and SLOPE at the seams; zero seam data =
                    the older shapes, how old files load; the jerk
                    vectors are STORED ⊥ T — measuring the tangential
                    part rang (D74) — and integrate() re-adds the
                    ANALYTIC −|κ|²·T at solve time (v13.4 D77: a
                    turning κ vector has one; without it the septic
                    sagged off a continued circle); RMF frames + a quintic twist whose end
                    slopes are the neighbors' ROLL RATES and end
                    second derivatives their roll ACCELERATIONS
                    (v13.4 D76 — min-jerk; a min-strain septic was
                    probed and REJECTED); solvedTarget's orientation
                    comes from the target's pre-integrated HEAD
                    frame, not its nominal entry pose (v13.4 —
                    reversed heads kinked); the speed
                    profile eases onto vB (no far-seam v step; dv/dt
                    continuous); felt G from analytic curvature;
                    tangent lengths user-adjustable, DEFAULT ONE FULL
                    GAP (v13.3 D75 — gap/2 packed the turning into the
                    seam regions: end rates 12-55× the interior,
                    "spiky ends flat middles"; NOT a continuity-order
                    problem; OPT_NONE re-captures SCALE lenA/lenB
                    with the gap ratio since v17.14 D155 — manual
                    mode lost its length surface in D87, so
                    hand-dragged targets left STALE short tangents,
                    the user's 331°/s end spikes; unmoved/loaded
                    compiles stay bitwise via a 1e-6 band) + the toolbar Length spinner AND dragging
                    the strip's END LINE re-optimize placement per
                    tick/event. OPTIMIZED bridges ARE the march
                    since v13.12 (D86): when the placement LANDS on
                    the target (miss test 2 cm/0.3°; a v17.10 draft
                    tightened it and was REVERTED same round, D150;
                    + BANK ≤ 1° since v17.15 D156 — the corkscrew
                    family's ends are exact, only mid-span is
                    garbage, so position+tangent alone let it
                    render; converged marches close bank to 0.006°;
                    lastOptDist =
                    blen's 5th field, missing = 0 = old files keep
                    their septic shapes), integrate() takes the
                    MARCH SPINE itself — Coaster.marchSpine rows,
                    curvature = the jerk-matched Hermite blend +
                    mid-span humps (30u²(1−u)² since v17.12 D153 —
                    zero end value AND slope, so the seam graphs
                    keep the neighbors' slopes; the user asked for
                    the slope line-up once D150's value fix was
                    in), NO polynomial
                    end-concentration
                    (a septic can't bend uniformly for long: the
                    user's "sharper turns near the start and end"
                    on big-turn bridges). The RESIDUAL TWIST rides
                    the march since v17.11 (D150 — his split-and-
                    rebridge file: with roll LOCKED over rolling
                    seams the blended roll ran the bank ~2°/m off
                    the target and the render's after-the-fact
                    twist left the spine's CURVATURE in the
                    untwisted frame — a ~1 g instant far-seam
                    Gv/Gl jump at EVERY length; the bank gap now
                    joins the march as a roll rate (the shared
                    zero-end-slope weight since v17.12 D153 — seam
                    roll rates AND slopes stay the neighbors'), κ —
                    rebuilt in the current frame per step — arrives
                    in the target's directions; measured YAW-
                    INVARIANTLY as a bank-vs-horizon difference so
                    the render reproduces its own placement, and
                    the D97 refinement is a BOUNDED FIXED-POINT
                    (wrap-safe passes — one full-step pass left
                    degrees under a 48° twist; clean marches exit
                    on the first measure, bitwise; BRANCH-CORRECT
                    since v17.14 D155: march() tracks the UNWRAPPED
                    pitch with pole-crossing parity, so a wrong
                    branch reads a ±360k defect and can never
                    converge — yaw/bank measure only on POLE-FREE
                    passes and accumulated resYaw/resTw wrap
                    ±180°, ending the CREST family: both seam
                    curvatures UP + net pitch DOWN overshoot the
                    blend past vertical, and the old wrapped
                    measures walked the twist into 360° corkscrews
                    and full loops; jerk×d capped at 4×kRef in
                    deadbandSlopes — a violent seam's measured
                    jerk extrapolated to −10 g mid-span at 46 m;
                    gates 54a-d. DAMPED ≤14 passes + SEEDED STAGED
                    RESCUE since v17.15 D156 — BANKED crests
                    cliffed at ~45-51 m: the pitch↔twist coupling
                    gain (~κ·d·twist) crosses 1 there and the six
                    FULL steps flung the iterate out of the basin
                    before any damping could act (clean at 44,
                    "shoot the track around" at 46). A growing
                    defect now halves the step (contracting
                    sequences bitwise), and a still-failing case
                    RESTARTS seeded — twist := the seam-to-seam
                    bank gap, pitch := the analytic blend defect —
                    converging pitch alone, then ramping the twist
                    ≤ 0.8/(κ·d) rad per step with pitch
                    re-converged between, pole-crossed increments
                    reverted at half step, walks > 90° off the
                    seed abandoned (a twist-0 stage 1 sat in the
                    WRONG PATH FAMILY: the zero-twist blend's end
                    bank reads ~180° off and the wrapped homotopy
                    walked into a self-consistently-CONVERGED
                    mid-span-inverted corkscrew — seeds are
                    load-bearing); fallback/probe tangents cap at
                    1.5× the placed chord so a miss renders the
                    honest D75 sweep, not a 120 m-tangent balloon
                    loop; file 4 marches clean to ~2× its gap,
                    knee 120 → 60, gates 54e-g) — far-seam felt
                    jump 1.2 g → 0.008 g, gated 53c) — with a
                    residual-only
                    smoothstep-quintic twist closing the (now tiny)
                    leftover bank
                    (march U already carries the blended roll,
                    seams' roll value+slope untouched) and the
                    shared speed-blend/resample tail. The SEPTIC
                    remains the absorber for manual mode, orphans,
                    never-optimized bridges and hand-moved targets;
                    optimize applies set lens := dist (the fallback
                    sweep — the v13.8 LENS_GRID deviation search
                    died with v13.12: nothing to imitate when the
                    geometry IS the ideal). Mode switches BETWEEN
                    the two optimize modes keep the CURRENT length
                    (D86 — re-running the per-mode knee ladder
                    moved the shape with yaw already aligned).
                    NONE round-trips (v14.5 D97, user spec):
                    entering manual stashes {optLen, next-piece
                    placement} (noneStash, transient); returning
                    UNTOUCHED restores it exactly; returning after
                    a hand-move comes back at the manual bridge's
                    own arc, Optimize length unchecked — the v13.8
                    knee-on-entry fires only for stash-less fresh
                    bridges. The grayed Length box tracks the
                    manual bridge live.
                    optLen = the "Optimize length" check (v13.8,
                    blen's 4th field): length :=
                    Coaster.optimalBridgeLength's knee — shortest
                    length within 10%+0.5 of the best rate+slope
                    cost over an ABSOLUTE ladder {1..128 m},
                    bisection-refined; tolerance FLOORED at 1.2×
                    the seams' own rotation rate since v17.14
                    (D155 — crest costs decay ~1/L forever and the
                    knee ran to 88 m: as smooth as the seams
                    demand is long enough; matched seams keep the
                    1 m collapse, valley knees stay slope-term-
                    governed). A pure function of the SEAM
                    STATES (chord-anchored candidates wandered
                    under mode ping-pong — the piece moved, the
                    ladder moved): one answer wherever the piece
                    sits. Smoothly-continuing seams — split pieces,
                    ANY placement, matched values AND slopes —
                    bottom out at the 1 m floor = the
                    zero-distance-attach cue (the prompt fires on
                    the floor, not on proximity).
                    Collinear
                    ends = exact straight line (gated). Cramped
                    geometry still needs fast-but-smooth interior
                    transients — that is the geometry's demand (D74).
TrackPiece.java     placement (origin + BASE ORIENTATION quat + yaw
                    DELTA about Y, v12.1: baseOrient is the natural
                    orientation — identity from scratch, the exact
                    boundary pose after a split, the reversed exit
                    after a piece reversal; yaw 0 = baseOrient exactly,
                    so zeroing the delta reverts. + PITCH/ROLL deltas
                    v13.13 D87, `piece` fields 9-10: applied yaw →
                    pitch-about-horizontal-right → roll-about-heading,
                    each controls exactly ONE Euler quantity of the
                    entry frame; all-zero paths stay float-exact.
                    RIGID since v15.2 D100: sections integrate at
                    genPose() = baseOrient⊕yaw and tilt() — the
                    pitch/roll rotation, IDENTITY object when both 0
                    — rotates the finished geometry about the entry,
                    so tilting preserves shape and the felt splits
                    design/real instead of gravity re-solving.
                    uiLockPitch/Roll = the Placement rows' edit
                    guards, not persisted; a bridged-in piece uses
                    the bridge's lock fields instead) + section
                    chain; editing one piece can never move another.
                    STRICT SEAMS (v17.1, D135): followPrev +
                    sLockYaw/sLockPitch/sLockRoll (piece fields
                    11-14) make a piece FOLLOW the previous piece's
                    exit — position always, unlocked axes align,
                    locked axes keep the STORED placement (never
                    mutated by following: relock = back to the
                    original, gated 0.0). The compiler generates at
                    the stored orientation and rigid-tilts the
                    difference (D100) — upstream edits can never
                    reshape a follower (gated 4e-14 m), only move/
                    re-aim it; design speeds stay independent
                    (honest ≠v) while the real ride runs through
                    (followers join the previous piece's RUN).
                    Coaster ops: makeSeamStrict (D71 split + flags),
                    unstrictSeam (attach + PIN — graphs bit-kept,
                    track re-bent continuous, conflict kept),
                    detachStrictSeam (bake effective placement),
                    connectStrict (reorder + follow — the
                    '+'-attach and zero-distance flows; arrives
                    SHARP; only bridge-converts arrive smoothed at
                    10 m — D143 walked back D141's connect default.
                    LOCK-CARRYING overload since v17.12, D153: a
                    direct attach seeds the seam's Discontinuous
                    axes from the floating piece's placement
                    PADLOCKS — yaw has none = continuous — and the
                    zero-distance YES from the BRIDGE's locks;
                    held angles seed from the piece's current
                    orientation, so nothing moves at creation; the
                    2-arg form stays all-unlocked). A
                    bridge's target never follows (the bridge IS
                    the connection); orphaned followers fall back
                    to their stored placement. Seam SMOOTHING
                    sSmoothLen (v17.2, D139 — piece field 18,
                    0 = off bitwise): the compiler geometrically
                    blends the junction over ±len/2 m of arc (see
                    TrackCompiler); the seam line's drag
                    DIAMOND edits it (v17.3, D140 — the
                    settings-row spinner is gone).
                    CORNER (D100): split/reverse of a TILTED piece
                    bakes the tilt into baseOrient — those re-solve.
Coaster.java        THE DOCUMENT: global v0 + the TWO FRICTION
                    coefficients (v17.0, D133: fricRoll dimensionless
                    decel μ·g, fricAir 1/m decel k·v²; fric() bundles
                    them; `friction` line, Coaster-menu rows in m/m +
                    m⁻¹ since v18.0 D161 — NEW docs default 0.03 /
                    1.0e-4 ≈ NL2, class defaults stay 0 so old files
                    load loss-free; document
                    undo), pieces (speed controls live PER SECTION
                    since v18.0 D160 — the Phase-1 SpeedRegion stub is
                    retired). Section-0 lanes are shared BY
                    REFERENCE with the editor. Cosmetics stay UI-side
                    until milestone B (travel via DesignIO.Cosmetics).
                    In-document converts route through the INSTANCE
                    fitLanes wrappers (friction-aware re-time; the
                    static convertToLanes tower stays loss-free for
                    probes — new converts MUST use fitLanes).
                    REVERSE PIECE = a CONVERT since v17.0 (D134,
                    user re-spec replacing D71's model
                    transformation AND D133's short-lived
                    design-direction scheme): reversePiece(piece,
                    dt, spacing, tol, vEntry) mirrors the compiled
                    piece (reversedSamples — T/R flip, U kept, Gl
                    negated, sample v kept) and re-fits each
                    section's span FORWARD at one energy flow
                    (reversedProfile from the recorded true end
                    speed, or vEntry) — same boundaries, same types
                    (s/c→force lanes; influence folds in; legacy
                    reversed-flag sections come out plain), piece
                    re-anchored at the old exit, NO speed conflict
                    by construction; bridge pieces refuse; the
                    entry-boundary sample joins each fit only
                    across a frame-SMOOTH seam (legacy kinked seams
                    poison edges). Runs through the editor's
                    density preview (padlocked entry-speed row,
                    stall telltale).
                    Also home to the bridge placement MARCH/optimizer +
                    the auto-reoptimize pass (v13.14), the CONVERT
                    engine (convertToLanes uniform/adaptive fits +
                    whole/partial ops + scCutClamp, v14.0 D89/D90;
                    ENTRY-INCLUSIVE since v16.6 D113 — withEntry
                    prepends the boundary sample to every span-based
                    convert extraction: a continuation span starts one
                    dt after its entry (the D84 dropped sample) and
                    mid-chain converts re-fit one step SHORT, v·dt ≈
                    0.2-0.3 m of arc — THE user's standing "converges
                    slightly off" (sole-section gates never saw it);
                    partial FRACTION axes stay span-only (D89's exact
                    cuts). DISCONTINUOUS seams SYNTHESIZE the entry
                    row since v17.2 (D137 — the boundary row's felt
                    belongs to the PREVIOUS section, and at any jump
                    seam it dragged the fit's node 0 between the two
                    sides' values with density-proof oscillation, the
                    user's 2026-07-18 report; make-real's leftover
                    "≠v still present" was the same row): T/U kinks
                    and felt/design-v steps (read against the
                    section's own first interval, 6×+floor — smooth
                    seams stay bitwise) swap the row for the exact
                    seam clock/arc/pos with the section's OWN
                    back-extrapolated dynamics, so node 0 lands on
                    the section's own value. Every lane-refit op
                    compiles via compileForFit (v17.2, D139: strict-
                    seam SMOOTHING suspended — fitting the blended
                    samples from the follow-contract frame swung the
                    refit by the half-kink, gated 122 m → 0.16 m). FULL-FLAG converts since v16.6 D116: type ×
                    domain × axes × rate units in ONE convertToLanes
                    call (convertWholeSection/Bridge, partialConvert*
                    take the set; gate 46e 0.0002 m — global-axis
                    targets stay undefined near vertical, the v16.2
                    cap).
                    AREA-MATCHED since v16.5 D111 —
                    matchSegmentAreas nudges node values ~1e-3 so
                    every segment's weighted integral equals the
                    data's against the BAKED polyline: least-norm
                    tridiagonal solve, damped re-baked passes,
                    best-state on peak cumulative error, sub-grid
                    carpets skip; endpoints land mm-cm, 2-180×
                    closer — adaptive force carpets keep a ~0.1-0.2 m
                    through-the-solution floor, fine UNIFORM is the
                    max-fidelity setting there;
                    pitch/yaw extraction ANALYTIC off the recorded
                    felt since v15.1 D99 — the κ identity, exact;
                    only roll is measured, ±1 sample — and converts
                    CARRY vPinned: a conflicted section stays
                    conflicted, both partial halves pin),
                    the NODE-ANCHORED SPLIT (splitSectionAt, v16.18
                    D130 — splitLanes both-halves + vDesign-at-cut;
                    == authored-as-two, gated 0.0 m) + its inverse
                    mergeSections (v16.19 D131 — like sections only;
                    matched seams lossless, unmatched averaged;
                    split→connect round-trips exactly, gated 0.0 m
                    with SLOPED seams + dirtied dead handles; HELD
                    lanes average like every seam — held value +
                    flat slope as that side's contribution (v16.20;
                    same round: effInDt is NEGATIVE, and a sign-blind
                    guard had halved matched slopes via a phantom
                    flat side),
                    the INFLUENCE enter/discard ops (v15.0 D98 —
                    model-side so the gates drive the real thing;
                    reconcileChainedSpeeds SKIPS influenced sections:
                    "influencing creates the split", their operating
                    speed moves only by explicit action; partial
                    convert refuses them) and TRANSITION-TO-REAL
                    (v15.2 D101: vDesign := true entry + lane start
                    nodes lined to the left neighbor's TRUE curves,
                    slope in real time; the reconcile recognizes
                    REAL-ANCHORED continuations — vDesign nearer the
                    true seam speed than the design end — and
                    freezes them to TRUE so the resolution
                    survives; refreeze/make-real BAKE a tilted
                    piece's deltas first via TrackPiece.bakeTilt —
                    v16.1 D106, the "refreeze wasn't fully
                    resolving" fix: entry pose identical, generation
                    resumes from the real frame, the WHOLE
                    design/real split closes, gated 4.64→0.017 m/s).
                    DOMAIN CONVERSION (v16.1, D104 — REPLACED
                    v16.0's exact node map, user re-spec):
                    convertDomain = a same-mode carpet REFIT through
                    convertToLanes' target-Domain overload (DISTANCE
                    fits over the section-local ARC, spacing in
                    METERS, duration = the exact arc, fit constants
                    ×10; converges with density — 3.47 m coarse /
                    0.24 m fine on the 407 m layout, gated #40d);
                    speeds/pins carry, influenced sections CAPTURE
                    their influence (output plain), reversed refuse.
                    sectionMap/mapCol remain the VIEW warp's map
                    ({t,s,v} rows, reversed flipped to the authored
                    axis, exact end anchors, linear extrapolation).
                    continuation() carries the domain AND axis flags
                    (10 m hold after a distance section);
                    partialConvertSection cuts distance lanes by ARC
                    fraction, kept half stays native. bakeAxes
                    (v16.2, D108): same-mode same-domain refit into
                    the OTHER axis semantics — convertToLanes'
                    flag overload measures world-angle rates
                    (heading/elevation/bank per arc, unwrapped,
                    capped 3600 °/s).
TrackCompiler.java  THE THREE PASSES (SECTIONS-DESIGN §1) + the
                    STRICT-SEAM SMOOTHING pass (v17.2, D139:
                    smoothStrictSeam runs right after a follower's
                    first section lands — orientations SLERP from
                    the upstream curve onto the follower's across a
                    ±sSmoothLen/2 arc window, sides extrapolated at
                    their measured rates, quintic weight; positions
                    re-march at the ORIGINAL per-step arc; interior
                    felt re-derives from the blended rotation PER
                    ARC (κ — time-based Ω mixed the sides' design
                    speeds at conflicted seams); the follower's
                    remainder rigidly TRANSLATES (postShift, bridge
                    captures included) — all but ZERO since the
                    v17.3 POSITION CLOSURE (D140: sin² rotation
                    humps close the ⊥-to-chord miss, a hump-
                    weighted per-step ARC STRETCH the along-chord
                    foreshortening; caps 0.2 rad / ±5%; gated
                    0.011 → 3e-6 m). Window ends sit on the
                    original frames — NOTHING outside reorients,
                    upstream bitwise (gated 50e). The upstream span
                    EXIT keeps the FOLLOW-CONTRACT pose, NOT the
                    blended seam sample — strictEntry/bakeFollow
                    read it; D80's exit==last-sample bends knowingly
                    inside a smoothed window): passG per
                    section at vDesign (force = unchanged bake+RK4;
                    geo = GeoIntegrator; s/c = ScIntegrator — both
                    exit poses carry the integrated quaternion;
                    REVERSED sections generate
                    canonically at the entry height and
                    reverseAndPlace flips + yaw-places them, D67;
                    a piece's PITCH/ROLL deltas apply as a RIGID
                    per-section rotation about its entry (tiltLine/
                    tiltSample, v15.2 D100 — recorded felt
                    re-projects G' = G − axis.y + axis'.y so the κ
                    identity holds; v stays the design profile —
                    the tilt's speed change IS Pass D's split;
                    bridges never tilt, pieceHead feeds the tilted
                    head to the bridge capture);
                    INFLUENCED lane sections take influencedLane's
                    TWO-PASS, v15.0 D98: ref = integrate(authored
                    lanes, vAuthored), base = ANALYTIC other-mode
                    values along ref at the operating profile —
                    rates ω = g(G−axis.y)/v scaled v*over v_d, felt
                    via the §5.1 identity, roll from the bake —
                    re-timed by the convert trapezoid onto t*, then
                    base+delta integrates AS the other mode
                    (force→GeoIntegrator, geo→FVDIntegrator) under
                    the INFLUENCE axis flags at the
                    operating speed, so resulting == base + delta
                    EXACTLY and shape is operating-speed-invariant;
                    all-zero deltas at vDesign==vAuthored take the
                    plain path BITWISE — both gated. baseCurves
                    export rides on LaneSection),
                    passD TRUE speed per RUN (v13.6 D79, §5.1: a
                    run = pieces chained by trailing bridges; each
                    run's energy references its own entry at its
                    first section's vDesign; bridges are pure
                    geometry — the D74 speed blend is design-side
                    only; passD was global AND write-only since
                    Phase 1; FRICTION since v17.0 D133 — the run
                    march sheds the document losses in TRAVEL order,
                    Heun over the sample arc, zero coefficients
                    bitwise),
                    passA resulting rates + TRUE felt/rates (no
                    differencing: Gv_true = (v_t²/v_d²)(Gv_d − U.y)
                    + U.y per sample, rates scale by v_t/v_d —
                    Result.trueFelt/trueRates; rates difference
                    WITHIN runs since v13.10 D84 — a detached
                    piece's frames poisoned run-end rates). A
                    BRIDGED piece's first sample drops like a
                    continuation (v13.10 — it duplicated the bridge
                    exit exactly: zero-advance tick). Result also carries
                    per-section Spans (sample range + t/s extent,
                    v11.0; + the section's EXACT exit TrackPose,
                    v12.1 — splits copy it into baseOrient) — the
                    editor lays out DISTANCE-domain
                    strips from them. Single-force-section output
                    is BIT-IDENTICAL to the old direct call (gated
                    headlessly).
DesignIO.java       save/load: writes FVDD3, reads FVDD3/FVDD2/FVDD1
                    (legacy = one piece/one section). Headlessly
                    round-trip tested at last.

GraphEditor.java    JavaFX GRAPH EDITOR (split out v9.2, D52): the
                    force-curve canvas — lanes, selection, node/handle
                    drags, document undo stacks, node dialog. Reaches
                    the shell through a `final FvdDesigner app` backref
                    (app.rebuildTrack(), app.settings, …).

Viewport3D.java     JavaFX 3D VIEW (split out v9.4, D54): orbit/fly/POV
                    cameras + pseudo-ortho views, mouse/scroll handlers,
                    animation timers, rail/tie/heartline meshes, ground
                    grid + LOD, track picking, node markers, and
                    rebuildMeshes (the per-pane half of the pipeline
                    — FvdDesigner.rebuildTrack owns the compile
                    since v18.10). Same `app` backref pattern.
                    Since v16.11 (D123, reworked v16.12 D124) also
                    the PLACEMENT GIZMOS (revealed by the entry
                    marker; piece entry + trailing bridge end as
                    independent Rigs; locked axes build no part;
                    ABSOLUTE-from-press drags — arrows project onto
                    the press-time screen axis, rings rotate by
                    linear mouse distance, world delta = NEGATED
                    scene delta under the Y-mirror), the middle-drag
                    ORBIT (left = selection only), white
                    ACTIVE-SECTION ties (TiePiece re-tint, self-lit;
                    the white ties AND the inactive-piece
                    desaturation both gate on the View menu's
                    "Visualize track selection" since v17.10 D149)
                    and clickable node markers + double-click insert.
                    v16.13 (D125) adds the track COMBS (vertical/
                    lateral felt G + true speed, hover readout via
                    combLabel) and per-piece heartline strands;
                    v16.14 (D126) makes tie/comb placement arc-EXACT
                    (arcHit) and the combs a three-way heartline-
                    rooted, POV-hidden, mouse-transparent unit.
                    v18.1 (D167): the POV camera INTERPOLATES the
                    heartline frame at the exact povTime (povPoseAt
                    — it snapped to the 0.01 s grid, juddering at
                    Ctrl ×0.25) and rail tubes tessellate by TURN
                    ANGLE (railRings — ~2°/ring, 1.2 m cap on
                    straights, arc floor on twists; a Mesh-quality
                    slider existed for one unshipped round — v18.2
                    removed it, the tuned budget IS the behavior).
                    v18.7 (D176): translation gizmo arrows are
                    DOUBLE-ENDED (the length tangent stays one-
                    sided); node markers ride the HEARTLINE in
                    orbit/fly (spine in POV — synced DIRECTLY in
                    togglePov since v18.8 D178: the redraw-borne
                    sync early-returns while the graph panel is
                    collapsed, which kept heartline markers in
                    POV); the AXES COMPASS (axesCanvas, gizmo
                    colors, own change-guarded timer) shows the
                    world X/Y/Z under the live camera — BOTTOM
                    LEFT since v18.8, hopping above whatever
                    readout occupies that corner (comb label /
                    backwards-POV tag); the POV ride advance
                    floors its trueV ratio at 0.1 — honest v18.6
                    records made the unfloored advance Zeno-freeze
                    short of the stall wrap. v18.8 (D178): TRUE
                    ORTHOGRAPHIC Top/Side — a ParallelCamera
                    mirrors the orbit rig (syncOrthoCam off the
                    compass timer; JavaFX 26 honors camera
                    transforms, reversing D48's verdict; near/far
                    setters are IGNORED, the fixed ±(w/2)-camera-
                    unit depth window is widened by the transform's
                    Z-scale ORTHO_DEPTH; effEye/ringHit/
                    positionGizmo adapt picking + gizmo scale) —
                    and the TOP-LEFT VIEW-MODE LABEL (viewModeLabel;
                    ONE FLAT LIST since v18.16 D187: Fly/Orbit |
                    POV | Top | Front | Back | Right | Left |
                    Tangent to Selection — the D180 Side
                    sub-dropdown is gone, switchViewMode 0-7 routes
                    3-7 through setOrthoSideMode, the label wears
                    the short "Tangent"). v18.16 (D187) also:
                    BALL-CLICK gizmo reveal (gizmoReveal per pane —
                    see the PLACEMENT GIZMOS paragraph), the
                    top-right per-pane Local/Global button. v18.17
                    (D188): gizmos render in an OVERLAY SUBSCENE
                    (gizmoSub — real depth buffer, mirrored camera
                    per frame via syncGizmoCam, pickOnBounds(false)
                    pass-through; D187's DepthTest.DISABLE rendered
                    in submission order = inverted-normal look),
                    the un-revealed ball is FIXED-size in
                    trackBallGroup ∈ world while the SCALING center
                    ball is a rig part (wireBall shared wiring;
                    ball drags reveal mid-gesture via a rig-only
                    build), lock toggles refresh rings live, and
                    node markers carry their node index (the entry
                    ball replacing node 0's marker had shifted the
                    pick's list-position indexing). v18.18 (D189):
                    the overlay goes MOUSE-TRANSPARENT in fly/POV
                    (syncGizmoSubMouse — the warped hidden cursor
                    hovering it un-hid the mouse and starved the
                    fly look of move events, the "shaky view");
                    the track ball HIDES while its rig is up
                    (build-time only — a reveal-by-drag keeps the
                    pressed ball); "Gizmos: Local" aims the RINGS
                    along the entry/follow frame (yaw U, pitch
                    R = U×T via rightOf, roll T; live re-aim = the
                    rig rotates rigidly with the object, the NL2
                    look; writes stay one-Euler-per-ring), and the
                    top-right button reads "Gizmos: Local/Global".
                    v18.10
                    (D181): MULTI-INSTANCE — every view pane IS a
                    Viewport3D (FvdDesigner.viewports; the document
                    phase of the old rebuildTrack lives on the
                    SHELL, panes rebuild via rebuildMeshes();
                    povTime/povFollowPiece/updatePovStats are
                    SHELL-shared; keys route to app.keyViewport() =
                    captured pane first, else hovered; captures
                    exclusive via releaseOtherCaptures; each pane
                    assembles its overlays in buildPane(), Add/
                    Remove View add/remove WHOLE panes — any but
                    the last). Plus the NL2-style ORTHO GRID
                    overlay (drawOrthoGrid: adaptive 1-2-5
                    world-aligned lines, top = X/Z, side = height +
                    view-plane horizontal; the TANGENT side's
                    horizontal origin anchors at tangAnchor,
                    captured at view entry/refresh), Side-from-list
                    defaults FRONT, the tangent ↻ Refresh / ↔
                    Reverse View buttons, and the top-right gizmo
                    Axes toggle REMOVED (View-menu dots remain).
                    v18.11 (D182): new panes spawn in FLY/ORBIT at
                    the spawning pane's pose; POV direction is
                    PER-PANE (povReversed instance field — the
                    settings key is dead); ortho zoom 2× +
                    cursor-anchored zoom-IN; grid overlay = powers
                    of ten from 1 m; the compass stays put (labels
                    z-order over it); drawn ROUND circle-arrow
                    icons replace every ↺/↻ font glyph
                    (FvdDesigner.circleArrowIcon — the glyphs'
                    circles render flat-bottomed). v18.12 (D183):
                    the CENTER BALL drags its owner in the VIEW
                    PLANE (entry ball = the piece, bridge-end ball
                    = manual-mode next piece; 3 px deadband, seam
                    ball position-locked), DOUBLE-CLICK opens the
                    owner's settings, the ball SCALES with the rig
                    (BALL_KEEP; gizmos-off = fixed); fixed ortho +
                    GLOBAL axes hide the OUT-OF-PLANE arrow
                    (orthoPlaneAxis rides the gizmo sig); Ctrl snap
                    = absolute world grid in GLOBAL, step
                    increments in LOCAL; mode transitions
                    refreshGizmo so scales never arrive stale;
                    "(A)" on the backwards tag only while captured;
                    thinner icons + doubleArrowIcon; swatch fills
                    align (-fx-background-insets: 0). v18.13 (D184):
                    dbl-click ANYWHERE on a rig = ITS settings
                    (Rig.onDouble; showPlacementPopup(forceBridge)
                    overrides the active-section branch); fixed
                    ortho keeps only the IN-PLANE ring; the WHITE
                    center ball replaces the node-0 marker where it
                    covers it; Ctrl+arrow drags in GLOBAL land the
                    whole position on the world grid. v18.15 (D186):
                    the center ball rides the RIG scale at a fixed
                    proportion (BALL_PROP 0.8 — BALL_KEEP 8.5 held
                    it near its historic WORLD size, invisible far
                    away; 0.8 = the close-up floored ratio he
                    endorsed; gizmos-off stays fixed-size) and
                    drawOrthoGrid measures its families from the
                    WORLD origin (scene + gridCamX0/Z0 = world —
                    the world group's XZ centering translate had
                    the X/Z overlay lines off the ground grid;
                    tangent side keeps its anchored-node origin;
                    gridCamX0/Z0 ride the redraw sig). v18.9
                    (D180): SIDE SUB-MODES (a second dropdown under
                    the view label — Front/Back/Right/Left/Tangent
                    to Selection; orthoSideMode, Shift+T stays
                    tangent), the side-view GROUND LINE (y=0
                    overlay, timer-positioned), ONE FOV for every
                    camera, and EXTRA VIEW PANES (MiniView: "Add/
                    Remove View" in the view lists — passive
                    orbit/ortho viewers over shared-mesh world
                    clones in a horizontal SplitPane; no POV/fly/
                    picking there, content re-clones per rebuild +
                    tie re-tint via syncMiniViews/cloneNode).

Dialogs.java        JavaFX DIALOGS (split out v9.4, D54): Help,
                    Shortcuts, Rail colors, Options + option-row
                    helpers. Menu-local undo machinery stays on the
                    shell (the Coaster menu shares it).

FvdDesigner.java    the SHELL (~1.1k lines): document + file lifecycle
                    (New/Save/dirty title), key dispatch, menu bar,
                    toolbar, layout, menu-local undo, NL2 export dialog.
```

Pipeline on every edit: `lanes → TrackCompiler.compile (passG: bake +
integrate per section → passD: true speed → passA: rates) → rail meshes +
stats`. This runs live on every mouse-drag event; it is fast enough (a
~14s ride is ~270 keyframes and ~1350 integration steps).

**Deleted files:** `FvdViewer.java` and `TestCircle.java` were earlier
iterations using a distance-based integrator API. They must NOT be in the
source tree — they won't compile against the current time-domain API.

## The physics (short version — see docs/PHYSICS.md for derivations)

- Moving orthonormal frame: **T** (forward), **U** (up, defined by roll — not
  the Frenet normal), **R** (right). Convention: `R = U × T`, cyclic
  `T×R=U, R×U=T, U×T=R`.
- Time domain: angular velocity needed for a target force at speed v is
  `ω = g·(G_target − gravity_component)/v` (rad/s). Specifically
  `ωU = g(Gv − U.y)/v`, `ωR = −g(Gl + R.y)/v` (the LATERAL SIGN flip,
  v18.13 D184: +Gl = body-right; every felt↔rate site pairs the same
  minus + mirrored R.y — records −(ω·v/g + R.y), κ-rescales
  r²(Gl + R.y) − R.y); roll rotates about T directly.
  YAW is REPORTED left-positive since v18.14 (D185, pairing with
  D184): the internal Ω algebra is untouched — authored geo yaw
  lanes/heading rates negate INTO Ω (GeoIntegrator.omegaFor, four
  base paths) and measured rates negate OUT (bodyRates out[2] =
  −dT·R; Coaster.worldRates takes reported yaw in and returns
  reported heading', while its transport terms φ' = p − ψ'·sinθ
  keep the INTERNAL ψ'). The D184+D185 negations CANCEL in
  Gl-sourced yaw reconstructions (κR = +g(Gl+R.y)/v² again — same
  text as pre-D184, now meaning reported-left). Yaw ANGLES store/
  save in the historic internal sense — but the THREE display
  surfaces (Piece yaw spinner, bridge Next-piece yaw, strict-seam
  yaw row incl. its live readout) negate at the UI boundary since
  v18.15 (D186): typed/shown +yaw = left, zero pose change, old
  files load bit-identically with negated-looking numbers. Ring
  drags/compass/eulerOf stay internal; the s/c arc angle joined the
  left-positive convention in v18.16 (D187 — a model change: the
  profile's U component negates, old flat-arc files mirror).
  Combined `Ω = ωR·U − ωU·R + rollRate·T`. Integration is classic RK4
  (v3, D44) on state (pos, T, U): `T' = Ω×T`, `U' = Ω×U`, `pos' = T·v`,
  frame re-orthonormalized each step, R = U×T derived; each RK4 stage
  re-normalizes its local frame copy. Speed is ALGEBRAIC (not ODE state),
  evaluated from energy wherever a stage needs it. Accuracy: analytic
  circle radial deviation < 1e-9 m (old per-step Rodrigues stepper:
  10 cm) at the same dt=0.01.
- Speed from energy conservation vs start height MINUS path-integrated
  friction (Phase 3, v17.0 D133): `v = sqrt(v0² + 2g(y0 − y) − loss)`,
  where loss rides the RK4 state with d(loss)/ds = 2(μ·g + k·v²) —
  μ = Coaster.fricRoll (rolling, dimensionless), k = fricAir (air,
  1/m; the Coaster menu edits it per km). Both default 0 = bitwise
  the historic loss-free model. Closed forms gated: flat rolling
  v² = v0² − 2μgs; drag v = v0·e^(−ks). Pass D marches the same
  deficit per run in TRAVEL order (Heun over the sample arc).
  REVERSAL under friction is a CONVERT (v17.0, D134 — a same-day
  design-direction scheme was walked back, D133): with losses the
  speed at a point differs by travel direction, so "Reverse piece"
  REBUILDS — Coaster.reversedSamples mirrors the compiled geometry
  (T/R flip, U kept, Gl negated, per-sample v kept for the κ decode),
  reversedProfile marches ONE flow from the recorded end speed (or a
  custom one), each section re-fits forward via fitLanes: no speed
  conflict, shape to the fit residual, friction included. Friction
  is paid BOTH ways, so a natural-speed reversal can STALL (the
  preview warns; more entry speed fixes). The reversed FLAG remains
  only as the legacy-file compile path (D67 semantics unchanged).
- `vSafe = max(v, 0.5)` guards divide-by-zero near stalls. The
  RECORDED sample speeds (and Pass D's trueV) are HONEST since v18.6
  (D175): the old per-sample `max(0.1, v)` record parked every slow
  dip at exactly 0.1 on the resulting graphs (the user's "piecewise"
  stair) — all five record sites store the raw value now, every
  internal floor is unchanged (bitwise physics), and consumers that
  divide or seed design speeds floor at THEIR sites.
- The integrated path **is the heartline** (forces are felt at the rider's
  heart). Rails are derived: `spine = heart − U·heartOffset`, rails at
  `spine ± R·(gauge/2)`, radius 0.07 m (140 mm dia). `heartOffset` negative
  ⇒ inverted coaster (rails above rider).
- GEO sections (v10.0) skip the force inversion: authored pitch/yaw/roll
  rates feed `Ω = yaw·U − pitch·R + roll·T` directly (the yaw slot
  takes the NEGATED lane since v18.14 D185 — authored +yaw = left);
  orientation
  integrates as a quaternion (`q' = ½(0,Ω)⊗q`, RK4, PHYSICS.md §2b).
  Gravity never bends geo shape — it only shows in the recorded felt
  forces (`Gv = ω_pitch·v/g + U.y`, at design speed). Validated: exact
  constant-rate angles + re-authoring the default layout's resulting
  rates as geo reproduces the force geometry to 2 mm over 407 m.
- STRAIGHT/CURVE sections (v11.0) author geometry per METER: a
  curvature-vector profile κ(s) with sine-eased transition blends
  (centered on element boundaries — total turn angle preserved
  exactly; blend halves clamp inside short elements). ScIntegrator
  RK4s (pos, quat, s) in time at v-from-energy, sub-stepping so every
  RK4 leg lands EXACTLY on profile breakpoints with lookups capped to
  the leg's side — sharp element chains hit analytic headings to
  1e-6° (naively stepping over a κ step cost ~0.1° per crossing).
  Shape is fully speed-independent; vDesign only sets the time
  mapping and the recorded felt G. Per-element ROLL (v11.1): RATE °/s
  joins Ω about T (time-based, so it alone reintroduces a design-speed
  dependence); GLOBAL ° holds the bank vs the HORIZON via a pure
  T-roll projection after every leg (eased from the entry bank over
  max(smoothIn, 2 m); never bends the spine; global 0 = rails level
  through slopes and turns — rate 0 instead lets bank drift, the two
  differ by 0.34 in R.y on the gated dip+turn chain).

## Key decisions and WHY (full log in docs/DECISIONS.md)

- **Time-domain, not arc-length.** We started arc-length and rewrote. Time is
  what FVD++ uses and what the user asked for. Consequence: editing early in
  the ride shifts where later nodes land in space.
- **Bake-then-integrate.** The Bezier editor never touches the integrator.
  Curves are "baked" to dense linear keyframes (0.01 s / 0.05 m grid,
  values exact on the cubic since v17.11 D151). Keeps the
  physics core simple and lets extreme/folded handle shapes be sanitized in
  one place (monotonic-t guard in `bakeLane`).
- **Pure-Java core, JavaFX only in FvdDesigner.** Non-negotiable. It is what
  makes the math testable headlessly, and every physics bug so far was found
  by headless tests, not in the UI.
- **Per-lane independent nodes** (v5). One node used to carry all three
  values; now each lane has its own list. Ride duration = latest node across
  lanes; shorter lanes hold their final value.
- **Handle semantics (v6):** stored as FRACTIONS of the neighboring segment
  (`outFrac`/`inFrac`, 0..0.95) plus absolute dv value-offsets. Moving a
  node in time therefore rescales attached handles automatically — the curve
  squishes horizontally with its shape preserved (validated headlessly:
  max deviation 0.002, polyline sampling noise). A D62 attempt to keep
  smooth joints from kinking during re-times (rigid absolute handles)
  was REVERTED in v10.5 on user decision — the squish applies to ALL
  nodes, kinks accepted (D63). Defaults: flat tangents at
  1/3 segment ⇒ smooth S-curve force transitions. "Smooth" = matching slope
  dv/dt on both sides (unit-correct C1, deliberately NOT pixel-angle
  matching). Handle drag is FREE by default (angle + length); holding S
  ("slide", moved off Shift in v7.1) locks the handle on its current line
  (length only) and never touches the
  partner; CTRL snaps the length fraction to ease presets — cubic 1/3,
  sine 1−2/π ≈ 0.363, quintic 7/15, circle 0.5523 (fractions whose
  flat-handle Bezier matches each ease's center slope). On a SMOOTH node a
  free drag mirrors the partner handle proportionally on BOTH axes
  (partner frac and dv scale by the dragged handle's ratios — equal handles
  act as mirror images, unequal ones stay proportional and collinear).
  Node drags are free; CTRL snaps time and value — DEFAULT references:
  TRANSITION length (Δt from the previous node) for time, the GLOBAL
  absolute grid for value (defaults 0.1 s, 0.1 G, 5 °/s roll, 1 °/m
  for rate lanes DISPLAYED per-meter — laneShownPM picks the step, so
  the cross-rate view counts; abs BANK lanes are degrees, not rates,
  and keep the roll step (v16.10 D122) — steps
  adjustable in Options). v10.2/3: each axis's reference is a per-user
  Options LOCAL/GLOBAL radio pair (horizontal defaults local = Δt,
  vertical defaults global = absolute); the SECTION END
  is its own snap point that ABSORBS grid points within half a step of
  it (snapping near the end = exactly the end); and the end is a WALL —
  a lane's last node stops there unless it STARTED the gesture at the
  end (extend = drag to end, release, grab again; endLimitFor).
  B toggles smooth/broken per node; re-smoothing keeps the IN handle and
  re-angles the OUT handle (user spec).
- **Undo/redo (DOCUMENT level, D51):** deep DocState snapshots — all lanes
  PLUS heart, gauge, start x/y/z and start speed ("everything that changes
  the track path"; tie spacing/colors are cosmetic and stay menu-local) —
  one per completed gesture or path-spinner click, cap 100; Ctrl+Shift+Z
  redoes (any new edit clears the redo branch — one choke point, pushUndo).
  Path spinners wire through wireDocSpinner (snapshot pre-apply;
  menuApplying guard stops double-recording, docApplying suppresses
  listeners during undo application). Known granularity: node-0 dialog
  editing speed + values = two undo steps.
- **Save format** `FVDD3` (D50, written by DesignIO): human-readable
  text, `Locale.US` decimals (critical — default locale breaks parsing
  on comma-decimal systems), unknown lines ignored for forward
  compatibility, extra fields on known lines ignored (the straight-lock
  8th and level-lock 9th `n` fields ride on this). Global lines (v0,
  `friction mu kair` — the Phase 3 coefficients, v17.0 D133, missing =
  0 0 = loss-free; NEW documents write 0.03 / 1.0e-4 ≈ NL2 since
  v18.0 D161,
  heart, gauge,
  tiespace, railmode/railcolor/gradcolors/gradstops — SIX
  gradient stops since v16.15 (18+6 values; DesignIO.gradSlot maps
  legacy lines: a 15+5 v16.14 line keeps the default CYAN inserted
  at stop 2, a 12+4 line fills stops 2-5 under the default
  purple + blue) —
  latcolors/latstops — the lateral comb's THREE absolute |Gl|
  stops since v16.14 (9+3 values; the v16.13 4-stop shape never
  shipped), missing = defaults — speedregion), then
  `piece x y z yaw [qw qx qy qz]` blocks (fields 5-8 = the base
  orientation, v12.1; missing = identity; %.10f so round-trips skip
  the loader's re-normalize; fields 11-14 = the STRICT-SEAM follow
  flag + its three locks, v17.1 D135, missing = detached; fields
  15-17 = the discontinuous axis angles, D136; field 18 = the seam
  SMOOTHING length (m), v17.2 D139, missing = 0) holding `section force|geo|sc|bridge
  vDesign domain
  reversed` blocks (the domain field — written since Phase 1 — is
  HONORED for force/geo since v16.0 D103: "distance" = lane x in
  meters; every old file says "time" there and loads unchanged;
  field 8 = ratePerMeter, v16.6 D115, missing = °/s; field 9 =
  rollAbsolute, v16.7 D118, missing = rate;
  the cross-domain VIEW flag is deliberately NOT saved) holding
  `lane`/`n` lines (force/geo; an
  INFLUENCED section, v15.0 D98, appends `infl vApply [rollG ypG]` —
  the OPERATING speed + the v16.5 influence axis flags (missing =
  local); the section line keeps the AUTHORED speed so old
  readers pair the lanes with the speed they were designed at — plus
  `dlane k`/`dn …` delta-lane lines in the exact `n` grammar;
  missing = uninfluenced, empty dlane blocks heal to a single (0,0)
  node; SPEED CONTROLS (v18.0, D160) append `sctl type complex x0 len
  speed accel min max` + `cn` node lines (shared grammar) + `smark x`
  per plain diamond — missing = none, old readers ignore all three,
  degenerate complex lanes heal to a flat two-node graph), `elem
  straight len smooth [rollmode rollval]` / `elem curve radius angle
  dir smooth [rollmode rollval]` lines (straight/curve, v11.0; roll
  fields v11.1 — missing = rate 0, the v11.0 geometry), or `bpose`
  (two pos+quat poses, %.10f) + `blen lenA lenB [optMode [optLen
  [lastOptDist]]]`
  (3rd
  field v13.5 — placement mode, missing = 0 = optimize position +
  orientation; 4th field v13.8 — the Optimize-length check, missing =
  off; 5th field v13.12 — the march-spine length, missing = 0 =
  never optimized, old files keep their septic shapes; fields 6-8
  v13.13 — the yaw/pitch/roll LOCKS, missing = yaw unlocked +
  pitch/roll locked = the historic P+Y; `piece` fields 9-10 are the
  pitch/roll deltas, missing = 0) + `bacc` (two seam
  curvature vectors, v13.1 — missing = zero, the v13.0 G¹) + `bdyn`
  (seam jerk vectors + roll rates + target entry speed, v13.2 —
  missing = zeros, the v13.1 shape; + two seam roll ACCELERATIONS,
  v13.4 — missing = zeros, the v13.2/13.3 twist) lines
  (bridge — the LAST-CAPTURE solve inputs; live compiles re-capture,
  they matter alone for orphaned bridges). READS FVDD3,
  FVDD2 and
  FVDD1: legacy flat files load as one piece at their `start` (default
  (0,21,0)) with one force section (vDesign = their v0, default 26);
  FVDD1 handle time-offsets (absolute seconds, negative in-handle)
  auto-convert to segment fractions. Canonical round-trip
  (save→load→save byte-identical) is headlessly gated.
- **Settings split:** app-wide settings (shortcut bindings, FOV) persist
  across runs via `AppSettings` → `~/.fvd-designer.properties`; per-coaster
  settings (heart, gauge, rail color) live in the `.fvd` save file.
- **JavaFX Y-flip:** JavaFX screen Y+ is down, world Y+ is up. ALL mesh
  vertices and camera math negate Y. Touch 3D code ⇒ re-check signs.

## Layout tuning: the hard-won lesson

Hand-writing force layouts fails badly. Curvature ∝ 1/v², so as a train slows
climbing a hill the same G bends the track ever more sharply — layouts are
hypersensitive to segment lengths and blow through vertical. The working
method (used to produce the default layout): **bisection-search each phase
duration against a pitch target** (pull-up ≈ +28°, float ≈ −32°, pull-out ⇒
end pitch 0), run through the FULL bake+integrate pipeline. Also: pull-outs
must be longer than pull-ups (train is faster going down), and a level banked
turn needs `Gv = 1/cos(bank)` — numerically confirmed (bisected 1.297 vs
theory 1.305 for 40°, <1% error, a strong integrator validation).

This pain is exactly why FVD++ has "geometric" sections that target
pitch/heading instead of forces — a prime roadmap feature.

## Testing (see docs/TESTING.md)

The core is validated headlessly; the JavaFX layer has NEVER been executed by
Claude (the original dev sandbox had no display/JavaFX/javac). Tests so far
were run via scratch mains against target/classes; in this repo they should
be converted to proper JUnit. Validated: analytic circle (RK4 radial
deviation < 1e-9 m over a 171 m circle; the old stepper: 10 cm), default
layout ends level (RK4 baseline: 406.878 m, pitch 0.228°, U.y = 1.000),
resulting-rate extraction (yaw matches g·Gl/v to 4e-4 °/s), 200-trial
fuzz of baking with pathological handles (0 violations), save round-trip
(0 mismatches), NL2 export suite.

**Least-verified code:** the POV camera affine (sign conventions), toolbar
key/focus interactions, spinner listeners. If the POV view is rolled 180° or
mirrored, it's a sign flip in `applyPovCamera`. The GIZMO drag math
(v16.11) is in the same class: if a rotation ring runs BACKWARDS
against the mouse, the one documented sign in `wireRingDrag`
(world delta = −scene delta under the Y-mirror) is the tunable.

## Current UI feature set

Single overlaid plot (each lane on its own value scale, AUTO-SCALED:
defaults Gv −1..4 g, Gl −1..1 g, roll ±150 °/s, expanding 1-2-5-rounded
when the visible curve exceeds them, frozen during drags; inactive lanes
25% alpha — clicking a dimmed lane's node or curve switches editing to it,
with the active lane keeping hit-test priority). Lanes ending early draw
their hold-final-value continuation DOTTED (to the SECTION end) —
clickable to append a node. SECTIONS (v9.5 D55, geo v10.0 D58, UX round
v10.1 D59): the piece's sections
are strips on one shared time axis — the active one edits normally in
its own stretch (all node math is section-LOCAL via ltToX/xToLt; one
section ⇒ tOff 0 ⇒ identical to the old editor), inactive ones draw all
three lanes at alpha 0.20 with boundary lines between, and clicking a
greyed strip activates it. UNIFIED VIEW (D59): the plot always shows
ONE mode's quantities — the active section's — so a strip of the OTHER
mode draws what it DOES in the active units, derived from the compiled
track via GraphEditor.derivedAt (geo view of force strips = resulting
pitch/yaw/roll rates; force view of geo strips = felt Gv/Gl + resulting
roll); everything shares the active axes and curves join at boundaries.
Every lane curve carries a translucent zero-line fill
(LANE_FILL_ALPHA 0.13, inherits the strip/lane alpha); the Resulting
overlays have NO fill and stretch across the WHOLE ride since v10.8
(path broken over a SEAM_TRIM band at each boundary; scale fitted to
the visible window like a lane). Every
wide-enough strip wears a force/geo/s-c BADGE at its top-left (type
indicator, ◀ = reversed-flag set + per-section menu: info line, SPLIT
PIECE BEFORE THIS SECTION (v12.0/1) and Delete section — the section-
level REVERSED check moved to piece scope in v12.1 (Coaster menu >
Reverse piece); the flag itself remains reversal's per-section
MECHANISM (v10.9 D67: the train runs the authored shape back-to-front
— generated canonically at entry height, flipped, yaw-placed onto the
entry's horizontal heading; pitched/banked seams mismatch honestly;
felt Gl negates, roll rate is traversal-invariant — §3's "roll flips"
was corrected; lanes still edit in the AUTHORED direction while
inactive strips/B-matching/'+' holds use the compiled curves).
Delete section is disabled at
one document section; downstream re-chains, deleting the active one
activates
its predecessor; one undo step; press rank nodes/knobs > '+' > badges
> curve insert (D57). An open '+'/badge menu dismisses on the next
graph click (JavaFX auto-hide misses the canvas — openMenu field).
A dashed line + circled '+' past the ride
end opens the add-section menu: ONE "Add force/geometric section…"
item since v16.2 (D108) opening a DIALOG — type, domain (the new
section HOLDS the ride's end state for 1 s / 10 m; same-mode
same-axis holds copy the authored end values exactly, global-axis
holds take the incoming WORLD rates — elevation'/heading'/bank'
at the seam, v17.6 D143 (was ZERO rates, the D108 straight
continuation: with BOTH global flags every lane held flat zero,
the user's both-global report; NaN-vertical falls back to 0),
everything else holds the compiled/true end state), roll + yaw/pitch
axes (defaults carried), the conflict fork (shown only after a
drifted end; s/c keeps two menu forks), and the STARTING DESIGN
SPEED behind a padlock — locked tracks what the fork implies;
unlocked = a DELIBERATE conflict (the section PINS so the reconcile
keeps it; B lines the real graphs up). "straight/curve" appends a
10 m STRAIGHT (a straight IS the seamless continuation; over a
conflicted end it is ONE submenu revealing Continue/Transition,
v16.5); vDesign
frozen at the piece-end design speed per §0; bridge + attach-piece
stay disabled placeholders. TIME/DISTANCE DOMAIN (v16.0, D103): the plot's
x-axis follows the ACTIVE section's DISPLAY domain — seconds, or
METERS over a distance-domain force/geo section or an s/c section
(which now lays out LINEARLY in meters when active; elements at true
lengths) — with the axis label/ticks/zoom in those units and every
other strip WARPED on by plotting compiled samples at their other
coordinate (same-mode inactive strips draw authored lanes only when
their native domain IS the axis; overlays/true strokes/POV marker/
hovers all axis-generic, seam trims speed-scaled on meters). The
badge menu's SECTION SETTINGS block (top, after the info line)
held "Convert to time/distance domain…" (ONE action item since
v16.2, was radios) — SUBSUMED in v16.6 (D116) by the unified
"Convert…" dialog = Coaster.convertDomain's
same-mode carpet REFIT through the density preview (v16.1 D104,
replacing v16.0's exact node map; meters spacing for →distance;
converges with density; speeds/pins/conflicts carry; influenced
sections capture their influence; reversed + s/c refuse) — the
cross-domain VIEW check ("View in time/distance domain" —
auto-DROPS when you leave the section, v16.2: returning always
shows the true domain): a pure view
and THE exact-shape mechanism (D104), native lanes untouched, the
strip warps through the section's compiled map (ltToX/xToLt are
the choke points — the dispOff precedent x-flavored; the map
FREEZES per node/handle gesture so live rebuilds don't slide the
curve under the cursor; works on influenced sections too) and
editing goes straight THROUGH it — Ctrl-snap/nudges/insert-snap
grid in the DISPLAY units and map back, readouts show Δs=…m /
slopes per display unit — and the AXIS radios (v16.1, D107):
Roll local|global for both lane modes (global = the roll lane
integrates a bank-vs-horizon target; rate 0 spirals level through
pitched turns) and Yaw/pitch local|global for geo
(heading/elevation rates about the world axes). Global-axis lanes
aren't body rates: inactive strips draw derived — in the ACTIVE
section's OWN axis semantics since v18.9 (D179, the user's "graphs
don't line up on a smooth seam": derivedAt/trueAt route through
worldRates when the active lane authors world quantities —
activeLaneWorldAxis, effective roll included; NaN-vertical draws 0
— and the authored-direct fast path requires the strip's axis
flags to EQUAL the active section's, not just both-local) — '+'
continuations
carry the flags, influence WORKS since v16.5 (D112) — but B/Shift+B WORK on
them since v16.3 (D109): the WORLD-RATE matcher reads the
neighbor's heading/elevation/bank rates analytically
(Coaster.worldRates — Ω per sample via the κ identity, Euler-rate
relations with φ' = Ω·T − ψ'·sinθ; the transport sign is FLIPPED
from the aircraft convention by the up-Y world, probed against
d(bank)/dt; NaN near vertical — ~87° since v18.10 D181, was ~81°
— a capped bank-rate spike
would INTEGRATE into a garbage target, the 42c 121 m lesson), so
every seam combination incl. global|global matches except vertical
seams; local|global ROLL seams convert ANALYTICALLY since v16.6
(D114 — rollAcrossSeam: exact boundary-sample ψ' by the κ identity +
the transport term p = φ' + ψ'·sinθ, value exact so re-presses never
creep; global|global roll and matching-semantics lanes 0/1 take the
exact authored path now; the semantics compared are EFFECTIVE since
v18.8 D177 — effRollGlobal: a yaw-pitch-GLOBAL geo section's "local"
roll lane IS a bank rate (Ω·T = ψ'·T.y + p ⇒ φ' = p), so raw-flag
comparison copied a local neighbor's body roll verbatim and left the
true roll graph stepping by the transport term, 35 °/s on the user's
launch file), and °/s|°/m seams convert value + slope at
the seam speed with the v' product term (D115). "Bake roll / yaw-pitch to global|local (keep shape)…"
(v16.2, D108 — Coaster.bakeAxes): the convert engine re-fits the
SAME compiled shape as lanes meaning the other semantics (radios
reinterpret, bakes preserve — ANALYTIC world rates since v16.3
[D109, the D99 lesson again: the v16.2 angle differencing
low-passed corners]: geo 3-lane bake 4 mm/106 m; force roll-only
bakes 8 mm since v16.4 [D110, was 0.408 m]: Ω carries the SUB-STEP
feed-forward body roll p = φ' + ψ'·sinθ — FVDIntegrator.rollTerm,
shared with omegaFor — so the frame twists within steps and the
projection only corrects O(dt²); local paths op-identical). The
axis radios + both bakes live in ONE "Orientation settings…"
dialog since v16.4 ("Switch to This Orientation (same graphs,
change shape)" / "Bake This Orientation (convert graphs, keep
shape)" — fixed labels per user, enabled when the radios differ
from current; reversed switches-only; influenced sections SWITCH
too since v16.5). While a strip EDITS INFLUENCE the same badge
item reads "Influence orientation settings…" and configures the
INFLUENCE integration's axes instead (v16.5, D112: force section →
roll + yaw/pitch, geo section → roll; switch only — "Bake
influence into design graphs…" stays the exit; fresh influence
mirrors the section's roll choice; `infl` fields 2-3, undo
carries them). Badges are TWO-CLICK (v16.4): an inactive
section's badge first activates, the menu opens while active. B/Shift+B match the REAL
graphs EVERYWHERE (v16.2, completed v16.3 D109): the split test is
TWO-SIDED — either side's design/true split at the seam routes to
TRUE-array targets converted by the κ identity into OUR design
values (rate ×v_d/v_t, slope one more factor for the clock); the
v15.2 real-anchored refusal is GONE (the unconflicted side matches
the conflicted neighbor's real curves, re-press converges);
refreezeSeamSpeed SKIPS vPinned sections (deliberate conflicts are
matched around, never closed); every refusal posts a stats-bar
reason (matchHint — B never fails silently). A custom-speed ⊕ add
arrives with κ-MATCHED hold values (rates ×v_d/v_t, felt ×(v_d/
v_t)² about gravity), so its real graphs line up at creation and B
only re-aims handles. Make-real works right of another conflict
(only a direct bridge blocks). ZOOM SURVIVES every axis flip
(v16.1, D106): a manual
window's ends map through the global t↔s samples (mapGlobalAxis)
— activation changes, view toggles and domain conversions alike.
Distance ergonomics: the element-length step (default 1 m) is THE
distance snap step, min transition 0.1 m, insert gap 0.2 m, A
appends 10 m, node dialog says "Transition length (m)", badges
wear "·m". B/Shift+B convert slope units across mixed-domain
seams (domSlope: per-second ↔ per-meter at the re-frozen seam
speed, all four match paths). Partial convert on distance lanes
cuts by ARC fraction (the split line drops a live cross-domain
view on entry — fraction and cut must share units); whole/mode
converts + make-real still produce TIME lanes. 3D markers/POV/
inserts bridge node-x → ride time via nodeGlobalTime. NODE MOVES
KEEP HANDLE SLOPES (v16.1, D105 — dv rescales with the squished
segment run, BezierBaker.rescaleDvToGaps; drags, nudges, dialog,
group moves, hold-inserts). SPEED-CONFLICT detection reads the
WHOLE span (v16.1, D106 — sectionDrift): tilted pieces surface
≠v even when only the interior splits ("· true splits inside" on
the info line), and Re-freeze design speed BAKES a tilted piece's
pitch/roll into its natural orientation (deltas read 0 after) so
the whole design/real split closes — the D106 fix.
STRAIGHT/CURVE STRIP WIDGET (v11.0, D68): an
active s/c section replaces the lanes with element BANDS (straight
slate / curve rose, labeled chips like "R20 90°→"), felt-G derived
curves behind at 0.45 alpha, dashed separators, and a smoothing
DIAMOND per internal boundary (filled = blending; shaded band = the
blend zone). Drag a separator or the section end = resize the left
element (EXTENDS past the current end too — the cursor map
extrapolates beyond the span at the end sample's speed, v11.1; Ctrl
snaps by the Options element-length step, default 1 m;
live readout; H_RESIZE cursor); drag a diamond = blend length (snaps
shut < 0.2 m); click = select; double-click / right-click "Edit
element…" = typed dialog (kind radios, length or radius/angle/
direction, entry smoothing — disabled on element 0: the seam stays
sharp — and ROLL: Rate °/s | Global bank ° radios + value, v11.1;
chips wear ∠° / ↻°/s when non-zero); right-click also inserts
straight/curve before/after +
deletes (clean-release rule, right-drag still pans); Delete = remove
selected element; A = append a straight; badge menu adds length/count
info + "Append element". New elements default 2 m entry smoothing
(SC_SMOOTH_DEFAULT) and GLOBAL 0 roll. The toolbar button is just
"Append" (v11.1) — seconds on lane sections, elements on s/c (the
tooltip carries both meanings). Lane buttons disable (mode 2), the plot shows
FORCE view (felt G) so other strips/overlays/B-matching read
generically; scales fit the derived curves; s/c strip layout uses the
compiled Spans (DISTANCE domain: duration exists only after compile —
model edits rebuild BEFORE redraw). Reversed s/c strips draw elements
in TRAIN order (element 0 at the right edge). 3D Ctrl+click on s/c
track only activates the section. GEO sections author pitch/yaw/roll RATES (°/s,
lane 0/1/2) with their OWN palette — pitch teal, yaw yellow, roll
orange; GraphEditor.laneColor is the
one accessor (curves, fills, buttons, guides, 3D markers). Selecting a
geo section relabels+retints the lane buttons (setLaneButtonMode),
retitles the y-axis and node dialogs, snaps ALL lanes by the
roll step (°/m-displayed lanes by the 1 °/m step, v16.10), defaults
the scales to ±60 °/s (roll ±150) and drops the
1 g reference line. B on a section-EDGE node (or "Match boundary
(value + slope)" in its dialog) RETURNS it to the boundary — Shift+B
(action "matchAll") does it for ALL THREE lanes' edge nodes as one
undo step: value AND
slope from the neighbor — exact from authored lanes same-mode (a lane
holding past its last node is FLAT there); cross-mode against a
non-reversed LANE-section neighbor it is ANALYTIC (v13.4 D76): a geo
neighbor's edge rates ARE its authored lane values, a force
neighbor's rates invert its authored targets at the exact seam frame
and ITS OWN seam design speed (ω = g(G−axis.y)/v, slope with the
Ω×axis and v' = −g·T.y terms), converted to the active mode at the
ACTIVE side's seam design speed (G = ω·v_seam/g + axis.y, v13.3 D75)
— both directions enforce rate continuity EXACTLY, so alternating
B/Shift+B is idempotent once matched (v13.3's sampled targets, and
v10.2's before them, crept ~0.1 °/s per press at banked rolling
seams: linear extrapolation of curved rate profiles re-injects a
bias — probed, D76). Only s/c, bridge, reversed, influenced and
global-axis neighbors (plus real-split seams) still
sample the compiled curves (~3 samples inside via rateIdxAt —
bodyRates smear boundary jumps across ±2 samples, same reason
derived strips and overlays trim SEAM_TRIM = 2.5·dt — extrapolated
back to the compiled boundary time; the sampled tail reads
sampledPerSec — PER-SECOND, v16.10 D122: derivedAt/trueAt pre-scale
by 1/v for a °/m active lane and boundaryState's own °/m wrapper
divided AGAIN, so every sampled-path target on a per-meter lane
collapsed ~v× to "zero with flat handles" — the analytic/authored
paths honor the °/s contract and never had it). B/Shift+B FIRST re-freeze the
DOWNSTREAM section's vDesign to the upstream design-end speed
(v13.5 D78 — the '+' creation rule re-applied; bridge neighbors
skipped, they blend already): felt Gv/Gl are recorded at each
section's own design speed, so with drifted speeds a rate-matched
seam still stepped by ω·Δv/g in the felt lanes — the user's "jolt
exactly on the transition line". At a common seam speed rate
continuity IS curvature continuity: felt display, rate graphs and
the true ride all join (probed: 0.06–0.10 g → 0.002–0.004 g).
Cross-mode matching
against the NEXT section stays
mildly iterative: our exit pose moves its felt values. Clicking a
greyed strip ALWAYS activates it — checked BEFORE the dimmed-lane test
(v10.2: held end-values used to steal those clicks as lane switches).
PIECES (v12.0 D70, exact-split + piece reversal v12.1 D71; bridged
RUNS v13.4 D76; STRICT SEAMS v17.1 D135): the graph
shows ONE connected RUN at a time in its GLOBAL time window — a run =
consecutive pieces linked by trailing bridges OR strict-seam follow
links, laid out strip by
strip via runLayout (piece+section+t0); a lone piece is a run of one
(the pre-v13.4 behavior exactly). STRICT SEAMS (v17.1, D135;
feedback round D136 — "the
track after the seam works as if the track before it were a
bridge"): a strict junction draws a DOUBLE boundary line (a
BRIDGE's exit seam draws the same double line since v17.17, D158 —
the target rides the bridge like a follower; visual only, no
carets/diamond there) wearing
per-lane SPIKE CARETS (v17.2 D138, re-spec'd v17.3 D140 — the
caret points the way each lane's RATE GRAPH would spike AT the
seam: the instant angular jump Δφ = axisAngle(qR·qL⁻¹) projected
the way Ω decomposes, {−Δφ·R, Δφ·U, Δφ·T} — a pitch-up kink =
+∞ felt = up caret; chevrons STACK at the line's TOP/BOTTOM
edges since v17.4 (D141 — up-spikes under the top, down-spikes
above the bottom; fixed, DIRECTION-only, pan/zoom never moves
them), lane colors, ALWAYS drawn since v17.5 (D142; sub-2°
components draw SMALLER/thinner since v17.7 D144 — was
translucent) and DODGING nodes rung-by-rung while staying
edge-anchored (caretY, the '+'-ladder idea); enabled
Resulting overlays wear theirs in the overlay palette; a
SMOOTHED seam draws none) and — strict only, while smoothing is
ENABLED — the SMOOTHING DIAMOND (v17.3, D140: the s/c pattern —
dragging ADJUSTS sSmoothLen relatively since v17.5 (D142: base + 2·Δarc from the press — right grows; edges keep absolute-to-cursor), a clean SINGLE-CLICK = the settings pop-out since v17.17 (D158, was double-click; ≤3 px press-to-release, with a matching 3 px drag deadband so a click never commits a micro-resize — the 3D seam ball keeps its double-click), and the diamond DODGES nodes down the '+'-ladder (seamDiaYAt); pre-D142 it sized twice the cursor↔seam ARC read off
the samples, live "smoothing X m" readout, Ctrl snaps by the
element-length step, < 0.5 m snaps shut, cap 80 m, filled =
active, shaded blend-zone band whose EDGES also drag (v17.4 —
lowest press rank: nodes/menus/inserts win), one undo per
gesture, H_RESIZE cursor, press rank above the seam menu; the
band tints RED when the smoothing ITSELF opened the follower's
≠v — smoothingCausedDrift, one cached smoothing-suspended
compile — and the badge's resolve submenu is then a grayed
"decrease seam smoothing size" hint, v17.4 D141); seam
lines HIGHLIGHT on hover (hand cursor) and stay lit while their
menu is open (D136). Clicking
any non-bridge seam LINE (press rank after nodes/knobs/badges)
opens the SEAM MENU — on section boundaries "Connect sections
(merge into one)" (= mergeSections, like sections only) + "Make
seam strict (freeze downstream shape)…" (= the D71 rigid split +
follow; the settings auto-open) + "Split track at seam"; on strict
ones "Strict seam settings…" / "Enable smoothing (10 m)"|"Disable
smoothing" (v17.4, D141 — the diamond shows only while enabled;
hand-made seams start OFF, bridge-converts and connects start ON
at 1 m) / "Un-strict seam (reorient
continuous, keep conflict)" / "Split track at seam". ("Split piece
before this section" and "Connect with previous section" LEFT the
badge menu in D136 — the seam + the first node's menu own them.)
The SETTINGS are a POP-OUT (D136; reworked v17.3 D140: WHITE like
the context menus, titled just "Strict seam", MOVABLE by its body
— the pv-popup grab pattern): per axis a DISCONTINUOUS toggle
(RED outline while on), a ±360° spinner (typed + arrows) and a ↺
to the creation value — Discontinuous OFF = follows the seam
(spinner grayed, live value — read ANALYTICALLY off the follow
contract since v17.2, followerEuler: the entry SAMPLE sits inside
a smoothing window), ON = holds the spinner's angle
(TrackPiece.sYawDeg/sPitchDeg/sRollDeg, piece fields 15-17;
toggling ON SEEDS THE ORIGINAL creation angle on the FIRST open
only since v17.8 (D145; TrackPiece.sSeenSettings, transient) —
later opens hold the CURRENT angle; ↺ always returns to the
creation angle), Apply keeps / CANCEL reverts to the open-time
state (v17.8 — auto-hide on click-off is gone). Smoothing
is edited on the seam itself (the diamond), and in 3D the SEAM
GIZMO (v17.3, D140): the follower's reveal ball sits ON the
junction (seamWorldPos — the D136 ghost was the STALE stored
position); its rings (shown whenever gizmos are on, v17.7) cover
the DISCONTINUOUS axes only, writing
the held angles (gizmoSetSeamOrient; ring axes off the follow-
contract frame seamEntryOrient; one undo per gesture);
DOUBLE-CLICK = the settings pop-out (reveal state unchanged). Defaults: hand-made seams yaw-continuous pitch/roll-discontinuous
(the bridge convention); since v17.12 (D153) a direct ATTACH seeds
Discontinuous from the floating piece's placement padlocks (locked
pitch/roll = discontinuous at the piece's own angles; yaw
continuous) and the zero-distance-bridge YES from the bridge's
yaw/pitch/roll locks. The whole-bridge
Convert dialog grows "Make following seam strict (freeze following
track shape)" — CHECKED by default, locks carried from the bridge;
convert+strict then un-strict == the classic attach+pin stitch
BIT-IDENTICALLY (gated). '+'-attach and the zero-distance prompt
connect strictly now. RESOLVE ops on a follower BAKE the follow
first (Coaster.bakeFollow, D136 — the D106 lesson: without it
make-real double-applied the follow rotation, the "launches off
into the distance" bug; make-real bakes with the SAME compile the
fit reads, since baking a follow changes the generation frame);
gated: make-real keeps the on-screen shape, strict refreeze == the
un-stricted twin's. A follower's Placement rows gray wholesale,
its entry gizmo doesn't build, and it gets NO entry reveal marker
(D136 — the stored-position marker floated off-track as a ghost
node); B/Shift+B
across a strict seam take the sampled cross-piece path and never
auto-close its speed independence — and they match the REAL
graphs there since v17.13 (D154: the real-split test resolves the
seam span + neighbor design speed CROSS-PIECE through the follow
link via followerActive; it only saw same-piece indices before,
so conflicted strict seams lined up DESIGN curves — the user's
report; unconflicted strict seams keep the historic path bitwise;
with seam smoothing on, targets read blended samples — a press or
two converges, user-accepted). Clicking any strip/badge in the
run activates that piece+section WITHOUT resetting the zoom
(activateRunSec; the toolbar selector still resets); the '+' sits at
the RUN's end and appends to its last piece (activating it first);
boundary matching crosses bridged piece boundaries (the neighbor is
the bridge — spanOf's flattened index reaches adjacent pieces'
spans, k outside the piece resolves to no Section = sampled path);
view clamps/auto-fit use runT0/runDur; the 3D viewport renders the
WHOLE run full-color (desaturation = detached pieces only). Piece-
scoped ops (placement, split, reverse) still target the ACTIVE
piece. (Axis continues across pieces; POV
rides them in order, jumping the gaps until bridges); a toolbar
"Piece k/n ▾" selector (hidden when single-piece) switches; badge
menu "Split piece before this section" cuts at the compiled boundary
POSE — position AND full orientation into the new piece's baseOrient,
so splits are BITWISE anywhere (pitched/banked included — user rule
"splitting never changes orientation"; Span carries each section's
exact exit TrackPose for this); the editor follows the edited
section onto the new piece. '+' menu "Attach floating piece" appends
another piece's sections to the active chain and dissolves it
(placement discarded — attach MEANS continue, and stays first-class
alongside future bridges per user); Coaster menu "Piece
x/y/z" + "Piece yaw (°)" spinners edit the ACTIVE piece (yaw is a
DELTA on top of baseOrient — zeroing it reverts to the natural
orientation) and re-sync
on switches — rows a connecting BRIDGE owns gray out with a
grayHint pointing at the bridge (v16.13, D125: position under any
optimize mode, yaw when locked or march-aligned; hand-edits there
silently fought the auto re-optimize before); Placement ▾ "REVERSE
PIECE (rebuild backward)…" = the user-facing reversal, a CONVERT
since v17.0 (D134 — replacing D71's model transformation): the
density preview opens with a padlocked ENTRY SPEED row (locked =
the recorded Pass-D end speed, unlock to type; a stats-bar STALL
telltale fires when the reversed flow can't climb home — friction
is paid both ways) and Coaster.reversePiece re-fits the mirrored
compiled geometry as FORWARD sections at one energy flow — same
boundaries/types (s/c→force lanes, influence folds in), piece
re-anchored at the old exit, NO speed conflict, shape to the fit
residual; bridge pieces refuse; Apply = one undo step (Ctrl+Z is
the exact restore — the old exact involution is gone); the
`reversed` FLAG + reverseAndPlace survive only as the LEGACY-file
compile path (D67 semantics unchanged; the ◀ badge still marks
those). Deleting a piece's last
section deletes the piece; 3D
Ctrl+click activates piece + section under the cursor; a clean PLAIN
click on any piece's track does too (v13.1 — drag still orbits), and
the same plain-click works in HANDS-OFF POV (v13.4: uncaptured POV
keeps the mouse; captured POV/fly still own it).
BRIDGES
(v13.0 D72, reworked v13.1 D73): '+' menu "Bridge to piece ▸" lists
EVERY other piece — a non-adjacent pick REORDERS it to come next on
the timeline (the list IS the timeline) — and appends a G² spline
joining the piece's end to that piece's entry (closes the POV gap).
The bridge FOLLOWS LIVE: the compiler re-captures poses + seam
dynamics every compile, so moving either side re-joins instantly;
only a bridge whose target piece is GONE freezes at its last shape
(badge "bridge !", strip hint). Seams are SMOOTH in the rate graphs
(v13.2 D74: septic + speed blend onto the target's design speed +
roll-rate-matched twist — value AND slope continuity; v13.4 D76: the
twist quintic also pins the neighbors' measured roll ACCELERATIONS
(seamRollAcc, two extra bdyn fields, missing = 0) so the roll-rate
graph leaves each seam on the neighbor's slope, not a flat shelf;
cramped
geometry still legitimately swings fast INSIDE the bridge). The
compiler aims solvedTarget at the target's ACTUAL first-sample frame
(pre-integrated head, v13.4) — a reversed-then-edited first section
used to kink hard at its entrance. (The badge's "Bridge tangents…"
dialog and explicit "Optimize placement of piece N" action were
REMOVED in v13.13, D87 — grandfathered from the pre-mode era; the
mode radios/controls + auto re-optimize are the whole surface now.
Since v17.14, D155, the bridge badge menu's TOP item is "Bridge
properties…" — the SAME movable panel as the toolbar button, grayed
"(no following piece)" on orphans: the user's two-spots request,
via FvdDesigner.showPlacementPopup.)
The optimize modes run the CONTINUATION MARCH (v13.4 D77 — the acceptance
principle is "the graphs change as little as possible"): the seams'
body-frame curvature + roll-per-meter, blended entry → target as
CUBIC Hermites whose end slopes are the measured seam jerks
(v13.8 D82 — the linear blend ignored the jerks the septic is
FORCED to leave the seams at, and the resulting fight was the
user's "double bump". v13.9 D83 probed a QUINTIC here — zero end
2nd derivative, softer graph onsets — and v13.10 D84 REVERTED it
on user feel: graphs may bend right off the seam, and the
quintic's mid-blend bulge read as shape weirdness. Slope terms
below 2% of the curvature scale
are deadbanded as measurement noise — on an exact circle the
⊥-jerk measures ~1e-4/m² of fit noise and d-scaling it moved the
placement 10 cm), marched from the chain end for the requested LENGTH
(fixed-step RK4, hard cap; plus ONE bounded refinement re-march
when the humps' second-order re-mix of the blend's pitch/heading
leaves > 1e-4 rad at the end — v14.5 D97: the ~0.06° residual
kinked the far seam in orientation mode, the user's "instant jump
in values at the end"; the 1e-4 deadband keeps clean marches on
the historic two-march sequence bit-identically — NOT D95's
reverted bank iteration: no wrap-ambiguous measure, one pass
max — the refinement is a BOUNDED FIXED-POINT since v17.11, D150:
under a large residual twist one pass left degrees; all measures
wrap-safe, clean marches exit on the first measure bitwise; DAMPED
≤14 passes + the seeded staged rescue since v17.15, D156 — banked
crests' pitch↔twist coupling diverged the full-step loop at
~45-51 m), the target's head placed
at the march's end and yawed to its heading, plus the smallest
vertical-rate hump (30u²(1−u)², zero end value + slope since
v17.12 D153 — briefly shipped in v17.10, reverted in D150 while
the value bug still dominated, and re-landed on the user's
"make the slopes line up") for the
pitch gap yaw can't fix — a
SPLIT CONSTANT TURN re-joins as the SAME turn (gated ≤ 0.11 °/s
rate ripple, ≤ 7 mm off the circle through 103°) and longer bridges
just carry it further around; straight seams reduce to dead-ahead
exactly (1e-12, gated); position + yaw only. The
toolbar "Length (m)" spinner (visible while a live bridge is
active; optimize modes read back the REQUESTED march length —
lastOptDist via requestedBridgeGap, D95: the compiled-arc
readback diverged to the septic's own arc whenever the march
missed, and arrow-stepping fought the re-sync, "stuck at certain
points"; manual bridges keep the honest compiled arc, chord only
pre-compile) re-runs that optimization at the typed/clicked length
per change
(arrow bursts = one undo step); beside it the "Zero lateral G"
CHECK (v14.3 D94, BridgeSection.zeroGl, blen field 9, missing =
off): RENDER-side re-bank of every sample's U onto the felt-force
resultant f⊥ = (v²κ + gŷ)⊥T at the design profile — real lateral
exactly zero (heartline force-vector law); airtime rows (|f⊥| <
0.15 g) interpolate the bank linearly across (the zero-G roll),
±0.75 m box-smoothed, EASED over min(total/3, 10 m) end windows so
seams keep their banks/roll rates; spine + placement + length
machinery untouched (gated 0.93 → 0.000 g, path delta 0, works on
march AND septic renders), and DRAGGING the active bridge
strip's END LINE does the same per event (v13.3 D75: H_RESIZE
cursor, live "length … m" readout, Ctrl snaps by the element-length
step, one undo step per gesture, spinner re-syncs; both paths scale
lenA/lenB with the length so the sweep keeps its shape — except
from a DEGENERATE ≤ 0.5 m start (bridge created right after a
split), which takes the default full-length sweep instead of
scaling noise, v13.4). The
spinner's editor is numeric-only and non-focus-traversable, and 3D/
graph presses pull focus (v13.4: scene key FILTERS consume ride
keys' KEY_PRESSED but not KEY_TYPED — a focused editor kept
collecting w/s letters while riding);
a piece ending with a bridge refuses add/attach ("extend the NEXT
piece") and refuses piece reversal; the bridge strip itself is
read-only derived curves (lane buttons off, mode 2) apart from that
end line. Bridge/s-c derived curves take HOVER readouts like lanes
(v13.5: value, Shift = slope, lane color; drag handles keep priority
via the H_RESIZE cursor). TRUE-SPEED DISPLAY (v13.6 D79, §5 round A):
wherever a section's design speed has DRIFTED off the energy that
actually reaches it — since v14.1 (D95, user rule) only ACROSS
PIECE boundaries: same-piece continuation sections re-freeze
vDesign to their real entry speed automatically after every
compile (Coaster.reconcileChainedSpeeds, looped with the auto
re-optimize pass in rebuildTrack; piece FIRST sections and
REVERSED sections excluded), so plain force/geo/s-c chains keep
their graphs REAL and re-derive geometry instead of splitting — a THIN solid companion stroke in the lane color
draws the TRUE felt/rate curve beside the design curve across the
whole run (GraphEditor.drawTrueStrokes; TRUE_SPLIT_PX 1.5 px on the
lane's scale is the show threshold, TRUE_STROKE_ALPHA 0.85; felt
strokes run straight through seams, rate-sourced ones — roll, and
all lanes in geo view — trim SEAM_TRIM like every bodyRates
consumer; nothing draws when nothing drifted — EXCEPT inside a
smoothed strict seam's window since v17.10 (D149): there the
ACTIVE strip's split reference is the AUTHORED lane value (the
influence/tilt rule), so the real blended curve shows on BOTH
sides of the seam even though the upstream side never
speed-conflicts). Drifted sections'
badges wear "≠v" (DRIFT_EPS 0.2 m/s on the span's first-sample
speeds) and their badge-menu info line gains "· entered X m/s";
lane + derived hover readouts append " (true …)" — value and
Shift-slope — once the curves visibly split. POV W/S rides at TRUE
speed (the design-time advance scales by v_true/v_design at the
current sample) and the stats-bar top speed reads Pass D; both are
identical to before until a drift exists. RESOLVE SPEED CONFLICT (§5 round B, LIVE
since v14.5 D97; REWORKED v15.2 D101, SIMPLIFIED v15.4 D102 —
conflicts resolve LEFT TO RIGHT, one section at a time; reversed
sections refuse — reversal itself is the conflict-free rebuild
since v17.0 D134): a drifted
section's badge menu grows a SUBMENU with TWO ops — "Make graphs
real (keep shape)…" (re-fit at the TRUE entry speed through the
density preview; s/c just re-freezes; grays while the DIRECT left
neighbor is itself conflicted) and "Re-freeze design speed (keep
graphs, reshape)" (available even right of another conflicted
section, D102 — re-freeze THEN Shift+B is the whole
transition-to-real flow for existing sections: against a drifted
neighbor, once the speed is re-anchored to the TRUE seam speed,
B/Shift+B match the neighbor's TRUE curves — value + slope in
REAL time, D101's machinery). A REAL-ANCHORED section is
recognized by its vDesign sitting nearer the true seam speed than
the neighbor's design end: refreezeSeamSpeed and the D95 reconcile
freeze it to TRUE (the reconcile would otherwise undo the
resolution, gated), and B from the CONFLICTED side's end node
toward it REFUSES with a stats hint (the feedback rule). Both ops
clear vPinned. Only a live bridge DIRECTLY
before the section blocks the submenu (explainer: it re-blends to
follow any resolution — convert it first). The '+' menu
FORKS after a conflicted piece end (D101; KEPT in D102 — user:
"I really like how it works for adding"): "Continue speed
conflict ▸" = the historic design continuation; "Transition to
real ▸" = the new section starts at the TRUE end speed holding
the TRUE end state (addSection(mode, real)). Undrifted sections
carry no submenu and no fork. Post-D95 drift
lives at piece-firsts, PINNED sections and
TILTED pieces; converts PRESERVE conflicts (D99: vPinned rides
whole converts, partial converts pin both halves). The
(Coaster.transitionToReal resolve op existed briefly in-session
and was removed unshipped, D102.)
INFLUENCE (v15.0 D98, §5 round C — user re-spec 2026-07-11; speed
semantics + roll removal v15.4 D102): a
force/geo badge menu's "Influence resulting geo/felt force graphs"
gives the section OTHER-MODE DELTA LANES that ADD to its resulting
graphs (resulting == base + delta EXACTLY — the compiler's two-pass
integrates base+delta AS the other mode; see TrackCompiler above).
Deltas exist for lanes 0/1 ONLY (force → Δpitch/Δyaw, geo →
ΔGv/ΔGl; ROLL takes none — its button disables, its strip shows
the dotted resulting roll read-only; lane 2 stays in the model +
file, inert). Entering NEVER touches speeds (D102 — "behave
exactly like a normal section, just with nudges"; the v15.0
instant-reconciliation silently closed the NEXT section's conflict,
gated now): vAuthored just mirrors vDesign from then on
(LaneSection.vDesign(v) syncs the pair through every consumer) and
influenced sections join the D95 reconcile like normal ones
(pinned still skips; the divergent-pair machinery in influencedLane
survives for hand-edited files, gated). Fresh delta lanes hold a
node at 0 AND at the section end (flat zero line). The strip flips
to the other mode's
palette/units/snap (activeGeo() flips; lane buttons wear Δ, badge
wears "fx", info line shows both speeds), app.lanes binds to the
DELTA lanes (every gesture/lock/dialog/undo path works verbatim),
and ONE VIEW since v17.17 (D158 — the "Resulting view" AND its
"Influence view" radio were REMOVED on user verdict: bad edge
cases — opposite orientations, inverted stretches — read buggy/
unintuitive): deltas edit around zero with the RESULTING graphs
dotted behind (hoverable, derived-hover pathway); dispOff() — the
old resulting-view display offset threaded through draw/hit/drag —
survives returning 0 as the seam for any future display warp
(strokeResultingLane and the v15.0/D102 ride-the-sum machinery
are deleted). The delta wall is ABSOLUTE (sectionEnd = the compiled t*
duration, durOf reads the span like s/c; A clamps, hold-insert
refuses, no started-at-end exemption). "Edit influence" unchecks
back to the DESIGN lanes — influence stays live and drawTrueStrokes
references the AUTHORED curves inside that strip, so the thin true
stroke shows exactly what influencing causes (the user's "see the
laterals along with the original graph of no laterals"); inactive
influenced strips draw DERIVED (reversed-strip rule); B-matching
against an influenced neighbor takes the sampled path, and ON a
delta EDGE node returns it to NO INFLUENCE — value 0, flat facing
handle, no seam probe/re-freeze (v15.5 D102 user rule; the v15.0
neighbor-minus-base match nudged nodes by the base sampling
residual). Resulting ▾ overlays read the TRUE
other-mode arrays while influence-editing (labels + right axis say
"True"). Exits (one undo step each): "Bake influence into design
graphs…" = convert to the OWN mode via the density preview
(reference = the compiled/influenced curves); "Discard influence"
= bitwise restore (gated). Partial converts refuse influenced
sections (menu + model guard); '+' after an influenced section
routes through the compiled end state; reversed sections refuse
ENTRY, piece reversal carries influence fine (editing locked).
Node-0 dialog on the influence surface drops the start-speed row
and labels the value "Delta". Gated TESTING #37 (24 checks),
walkthrough #68. SPEED CONTROL STRIP (v18.0, D160-D164 — Phase 3's
speed control): the "+V" box at the graph's bottom-left toggles a
lane ABOVE the x-axis sharing the plot's x mapping (domain views/
warps included; speedStripTall/Size persist per-user — the OPEN
state is SESSION-ONLY since v18.4 D173: every launch starts with
the strip closed; +V always opens COLLAPSED since v18.9 D179, the
arrow/divider/Edit button re-expand).
The thin strip is the SPEED CONTROL LINE — click adds a diamond
(a FAST second click replaces the fresh diamond with a control
spanning its neighbors — the right-click create, v18.13 D184),
click-DRAG on a free stretch CREATES a speed control press→release
(v18.4 D173: live preview, Ctrl snaps, min 2× the domain gap,
swallowed marks consumed, arrives selected; dragging a control's
BODY segment MOVES it like its start diamond since v18.9 D179 —
spGrabOff anchors the grab point, 3 px deadband keeps clicks
selections),
double/right-click a diamond (or the line after one) promotes the
stretch to the next boundary into a CONTROL (consuming its marks)
and opens the SPEED CONTROL options (movable dark popup: Constant
Speed | Constant Acceleration radios, Complex check, speed box +
Max/Min checks — at least one always on — accel box, Start/Length
spinners in native units, read-only "Start:"/"End:" speed readouts
showing true when split (both since v18.7 D176; ONE row, Start
first, since v18.8), Edit Speed/Accel button that expands the strip and nudges
the popup clear); the same clicks REOPEN it. Left-click selects
(Delete removes — strip focus rules; spFocus), start-diamond drag
MOVES, end-diamond drag RESIZES (complex graphs stretch along,
fraction semantics), ripple-time shifts later diamonds, Ctrl snaps
by the axis step, everything clamps inside the section and against
neighbor controls. The ▲ arrow (or the top-edge divider) expands
the TALL view: resulting TRUE speed green dotted + hoverable
("v = … m/s"; HIDDEN inside COMPLEX speed controls since v18.4
D173/D174 — their graph IS the speed; plain constant segments
keep it), complex
const-SPEED controls show their resulting LONGITUDINAL G inside
their span (accel-BLUE dotted, (d(v²/2)/ds + g·T.y)/g off the
true arrays, 4-sample edge trim — the boundary snap is instant;
hover value, Shift slope; joins the accel scale FIT so it always
stays in view, D174), locked strip segments wear MINI
twin-line Shift+F/H visuals, a graph ending early holds its
value to the control's end DOTTED, the control is a HARD BOX
under ripples (time-ripple pins its LAST node, D174), the strip's
value scale GROWS under edge-overshoot drags like the plot axes
(dragGrowTimer's surface branch, v18.6 D175), and
complex graphs edit through the
REAL editor
(v18.3, D172 — the user's "recycle the exact code" verdict ended
the v18.1/2 parallel mini-editor): surfStrip retargets the main
pipeline's choke points (active/warpFwd/warpInv/valToY/snaps/
gaps/end-wall/laneColor…) at the selected control's lane via
withSurf, so nodes, handles, S/Z/X, Ctrl snaps + ease presets,
H/F + both locks (enforceLocks walks control lanes), box select,
nudges, hover/drag readouts + Shift twins, off-screen handle
stubs, the TYPED NODE DIALOG (surface captured at open) and the
Ripple checks are the SAME code on a second surface — the
strip-sized nodes/lines (nodeR/knobR, editL/R/T/B) are the one
sanctioned difference; selIdx/hoverT/gesture ownership rides
spFocus/spHoverSurf/surfGesture; value snaps 1 m/s / 0.1 g.
redraw() ALWAYS renders from the MAIN surface (v18.4 D173 — a
strip-gesture rebuild used to re-enter it with surfStrip set and
paint the plot through the strip's chokes: the "main graphs
flash out while editing the strip" bug; spDraw re-enters the
strip surface itself). Segments/diamonds wear the type color (speed GREEN = the
speed comb's, accel BLUE); the baseline dashes over sections that
take no controls (bridge/influenced/legacy-reversed, stats hint
explains). 3D: type-colored PLATES down the tie centers over every
controlled stretch. STALLED track (true march at the 0.1 floor)
renders TRANSPARENT to its run's end and the POV skips it both
ways (D163). Badge menu "Set initial design speed…" (D162) types
a section's starting speed directly — ride entry writes the
Start-speed spinner, elsewhere pins as a deliberate conflict; its
"Set Design Speed to Real" button == the resolve op, which is
RENAMED to match ("Set Design Speed to Real (keep graphs,
reshape)"); the Add dialog's fork row reads "Initial design speed"
with "Set to Real"; the horizontal Ripple check relabels
time/distance with the axis. TIME comb fillers space evenly
between the whole-second ticks at roughly the other combs' 0.5 m
density (D164; v18.1 D168 recolors: seconds DEEP BLUE at 1.5×,
every 5th second RED since v18.2, fillers light blue). v18.1
FEEDBACK ROUND
(D165-D166): the popup's End speed sits under the speed row, its
entries are numeric-only, and it hides on app focus loss +
returns on refocus (the pv pattern); complex graphs gained
main-lane parity — S/Z/X constraints (spAxisLock, routed by
spFocus), H flatten, F straighten-ahead + Shift+F/Shift+H locks
on the segment LEAVING the selected node (flags enforced per
redraw, spEnforceLocks), Shift-hover slope, zero-line fill +
grey hover ring — and B/Shift+B on a const-speed graph's END
nodes snap value+slope onto the REAL speed outside the control
(spMatchRealEnd); controls can NEVER leave their section
(spClampControls every redraw — shortened sections pull them
back in, complex graphs rescale, lists stay x-sorted so stacked
diamonds select the FIRST), min length = 2× the domain min gap.
PLACEMENT MODES (v13.5 D78,
BridgeSection.optMode, blen's 3rd field, missing = 0): the toolbar
button RELABELS "Bridge properties ▾" over a live bridge (v13.10
D84 — same button, the bridge's own panel; updateBridgeControls
owns the label, syncLaneButtons fires it per activation; the
panel's text is WHITE on the dark popup since v17.10 — black
holdovers from the light-ContextMenu era) — mode radios
"Optimize position and ORIENTATION" (v13.13 D87 — was "and yaw";
same optMode 0: the march aligns every UNLOCKED axis of the next
piece — yaw heading delta, pitch/roll as direct Euler additions
onto TrackPiece.pitchDeg/rollDeg — and humps/twists to the LOCKED
ones; the radio grays out with all three locked; Length re-runs
the full march)
| "Optimize position" (march places, the user owns the next piece's
whole orientation) | "No optimization" (manual next-piece x/y/z +
unlocked-orientation rows; Length +
end-line drag disabled; the DEFAULT for NEW bridges since v13.7 D81
— a fresh bridge moves nothing and the dropdown now says so, user
rule; the FVDD3 missing-field default stays 0 = POS_YAW so old
files keep their documented semantics) — plus the Length spinner
(moved off the
toolbar), an "Optimize length" CHECK beside it (v13.8 D82:
checked = the length is optimalBridgeLength's knee and the spinner
+ end-line drag disable; switching INTO an optimize mode checks it
and applies immediately at the optimal length — user spec; the
search BOTTOMING OUT at its 1 m floor — seams that continue each
other smoothly, e.g. any split piece however far its half was
moved/yawed — prompts "zero-distance bridge — connect directly?"
YES = the bridge deletes and the next piece attaches (stray
placement discarded, seamless re-join), NO = unchecked, nothing
moves), next-piece Yaw and x/y/z rows, each enabled per mode, all
editing the NEXT piece through the bridge; the badge optimize
action follows the mode + check. The optimizer's internals (the
LEN_LADDER candidates, knee tolerance 10% + 0.5, slope weight 0.1)
are deliberately NOT user-exposed (v13.10 D84 — probed
as Options rows in v13.9 and per-bridge rows in v13.10-dev, both
pulled on user verdict: confusing, effects unpredictable; the
tuned constants ARE the behavior, and stale AppSettings keys or
dev `bopt` lines are ignored). AUTO
RE-OPTIMIZE (v13.10 D84, user rule; the pass lives on
Coaster.autoReoptimizeBridges since v13.14 — moved off the shell
so the headless gates drive the real thing): after every compile each live
optimize-mode bridge whose signature changed re-runs at its last
requested length (the knee when Optimize length is on; the
zero-distance prompt never fires on the automatic path) — editing
UPSTREAM of an optimized bridge re-places the next piece live. The
signature (BridgeSection.optSignature since v13.12 D86) is pure
SEAM GEOMETRY — entry pose + body-frame target dynamics, NO mode
flags and NO heading, so flag flips/mode switches never re-place
by themselves (their explicit handlers own that), and
Coaster.optimizeBridge STAMPS it on apply so explicit actions
never double-fire the auto pass; optimizing never changes its own
signature → ONE recompile settles, no loop (optSig transient,
lastOptDist persisted since v13.12; optSig null = first sight,
record only — loads never move pieces — vs SIG_STALE, v13.14 D88:
partial convert's deliberate "re-place on the next compile"). The march also closes the HEADING
gap in position-only mode (v13.10 D84: a rotation-about-world-Y
hump, the pitch hump's twin, heading rate exactly c rad/m at any
pitch — the yaw-blind placement left the septic absorbing the
user's fixed yaw: "very weirdly shaped track", high length
sensitivity; gated 55°-target peak 1.43× ideal, 9% shape change
per 10% length). SEAM SAMPLE FIXES (v13.10 D84, the "brief stop"):
the bridge landing steps ONCE by ≤ 1.25·dt (the old partial read
sAt post-clamp = the last sample stamped a full dt late — the
flattened graph tail; full-step+runt pairs also blew up cost
slopes on noise/dt), and a BRIDGED piece's first sample is dropped
like any continuation (it duplicated the bridge exit exactly —
zero-advance tick, POV froze a frame). Pass A differences WITHIN
runs (detached pieces' frames no longer poison run-end rates).
CONVERT (v13.7 D81 — v13.5's bridge-only
"Unfreeze (edit as geo section)" renamed and UNIFIED; EXPANDED
v14.0 D89/D90; ONE "Convert…" DIALOG since v16.6 D116: Whole/Part
scope, then Type, Domain, Rate units, Yaw/pitch axis, Roll axis,
Roll behavior (the CANONICAL row order, v17.6 D143; axis rows
swapped v17.9 D146 — the Add-section dialog and the Resulting
settings share it) — each
row pre-set to the section's current value with ↺ reset buttons,
any combination to any other, Part carries the choices into the
split-line mode, influenced sections gray Part, reversed refuse;
GRAYED choices wear a hover explainer since v16.10 D122 — how to
un-gray, via GraphEditor.grayHint: disabled controls swallow mouse
events, so the tip rides an enabled StackPane wrapper, installed
only while disabled; the Add-section dialog's grays + padlocked
speed field wear the same, ↺ buttons deliberately none):
the pre-v16.6 menu ended with a WHOLE + PART pair per target mode — "Convert to
geo/force section" (bridges: "Convert whole bridge to …", which
ATTACHES the next piece so the track stays connected — the v13.7
whole convert split the run; orphans just convert) and "Convert
part to … section…" (only the OTHER mode(s) offered — force↔geo,
s/c→either, bridge→either; REVERSED sections refuse, §5.3's rule).
"Convert part" opens the SPLIT-LINE MODE (v13.11 D85 bridges; ALL
modes v14.0 D89) on the strip — highlight, a
draggable vertical line (H_RESIZE, live "% of bridge/section"
readout,
5–95% + rate-fit margins), click a side to pick it (tint + brief
flash; LEFT default), canvas chips [Convert left/right part] /
[Cancel] apply. Cuts are EXACT-FRACTION since v14.0 (D89): the cut
pose interpolates between samples at exactly frac·total
(sampleAtArc/sampleAtTime — the old nearest-sample snap shifted
cuts by up to half a sample, the user's "the length is getting
altered"), and the LINE maps arc↔time through the span's samples
so it sits where the cut lands. On LANE strips the line SNAPS to
nodes — ACTIVE lane only, click = direct node-BODY hit, drag
magnetizes on x within 5 px (v14.4 D96; v14.0 snapped every lane
from anywhere on the node's column) — and the cut then PARTITIONS
the node lists bitwise; on s/c strips it clamps outside
transition-blend + global-roll ease windows (Coaster.scCutClamp —
the only regions where truncating elements would reshape).
LANE/S-C partial (Coaster.partialConvertSection): the unconverted
side keeps its original representation EXACTLY — lanes split by
the de Casteljau identity (locks ride along), s/c elements by
length/angle arithmetic (the right half enters sharp) — and the
downstream side's vDesign re-freezes to the design speed AT the
cut (same energy expression ⇒ the kept shape continues exactly;
gated 0.4 mm s/c / 3 mm lanes, TESTING #31). BRIDGE partial
(Coaster.partialConvertBridge): LEFT ⇒ [..,
converted lanes, bridge(cut→target)] — the bridge stays
last-of-piece and re-solves to the same measured cut conditions
(whole track holds ~0.14 m, bridge lands on the target to 1e-9,
gated TESTING #27); RIGHT ⇒ the lanes PREPEND to the original
target piece, re-anchored at the exact cut pose — its own sections
chain on after them, the track stays CONNECTED end to end (v13.13
D87 — the v13.11 new-piece split stranded everything downstream;
orphaned bridges keep that fallback).
Both PIN the surviving bridge's length:
optLen off + lastOptDist = that side's share of the cut (v13.12 —
an inherited Optimize-length check re-kneed the remainder on the
first upstream edit, and the RIGHT side kept a stale full-length
request). A MARCH-RENDERING bridge gets optSig = SIG_STALE
(v13.14 D88): the first rebuild's auto pass re-places it at the
pinned length off freshly captured seams — the cut nudges by the
former landing miss (~0.1 m hump cases, 0 hump-free; gated
TESTING #30) and the march keeps rendering. Before, both sides
left optSig null (record-only) with the placement NOT on the
fresh sub-march — which cannot reproduce a hump-carrying march's
sub-arc (the hump was a FULL-length parabola; its mid-span
curvature is baked into the measured cut state and the sub-march
re-plans its own) — so integrate()'s 2 cm/0.3° landing gate
failed and the graph silently fell to the SEPTIC: the "partial
convert reshapes the bridge graph" bug, mode ping-pong = the
accidental hand re-place. Septic-rendering bridges (never
optimized, manual mode, hand-moved target) keep the record-only
null: nothing moves, v13.13 behavior exactly. The LEFT remainder
also keeps the lock flags (they silently reset before);
the mode cancels on any activation
change; the editor follows the converted lanes (right: onto the
new piece, the split precedent). The FIT engine:
Coaster.convertToLanes re-authors the compiled shape as editable
lanes — per-meter rates off the samples, re-timed onto the target's
energy profile (identity for force/geo/s-c; real work for bridge
blends), slope-matched C1 nodes; geo lanes = the rates,
FORCE lanes = the felt targets ω·v/g + axis.y + the SAME roll-rate
lane (roll is both modes' shared third lane); vDesign carries over
(conversion is a REPRESENTATION change at DESIGN speed — bridge
cuts take the blend's own speed). Bridge converts PRESERVE the
downstream design speeds AND the design/true split exactly (v14.5
D97, user rule — a draft that converted bridges to the TRUE
profile was reversed): both flavors PIN the first downstream
section (Section.vPinned, section-line 5th field, missing = off;
in DocState) so the D95 reconcile leaves the stitched seam's split
alone; the resolve ops clear the pin. NODE PLACEMENT
(v14.0, D90): TARGET SPACING — endpoints pinned exactly on the
section ends, interior even, count nearest the target
(default NODE_SPACING_DEFAULT 0.1 s, min 2 nodes, 0.01 s floor) —
or ADAPTIVE ("Optimal placement": fitLaneAdaptive inserts nodes
greedily at the worst-fitting sample until each lane tracks its
data within a tolerance × its value range; per-lane counts, flat
lanes = bare endpoints; 1/3-frac C1 handles make the time cubic
exactly linear). EVERY convert commits through the DENSITY
PREVIEW popup (GraphEditor pv*): typed value + log slider
(spacing 0.02–2 s / 0.2–20 m, accuracy 0.01–10 % — D136's
widening walked back on user feel, v17.3 D140),
conversion re-applied
LIVE per change against a restore of the pristine state
(re-captured per restore — applyDocState ADOPTS snapshot
objects), the ORIGINAL compiled curves dashed behind in lane
colors (target-mode units), node-count readout; Apply pushes the
pre-convert state as ONE undo step, Cancel restores it exactly;
canvas presses are consumed while open (view pan/zoom live),
activation changes cancel, and ALL bound key actions are dead
while the split line or preview is up (v14.4 — an open convert is
an open gesture; POV/ortho view keys stay live). The popup is
DRAGGABLE by its body, opens ABOVE the plot (clear of the strip),
and — being a Popup, its own OS window — hides when the app loses
focus and returns, still open in place, when focus comes back
(v14.4, D96). One undo step back;
a whole-converted bridge attaches its target (connected); a
PART-converted bridge keeps following its cut; residuals
gated TESTING #23/#31/#45 (area-matched since v16.5 D111: endpoints
land mm–cm, 2-180× closer; adaptive force carpets keep a ~0.1-0.2 m
through-the-solution floor — fine UNIFORM is the max-fidelity
setting; "delete nodes to taste", D78, now literally a slider). 3D meshes are
PER PIECE (v13.2): inactive pieces render DESATURATED (gradient
texture or solid color), meshes never span a piece boundary — a
clean bridge/attach seam abuts flush and reads as one track, a fresh
split shows via the tint change — with MESH_GAP_BREAK 3.5 m kept as
an in-piece safety (v13.1's stretched-connector fix). Document undo
snapshots ALL pieces (sections + modes + placements + baseOrient +
bridge solves) +
the active
piece/section indices;
the Start-speed spinner and node-0 dialog write SECTION 0's vDesign
only (the ride entry: piece 0, section 0); markers and 3D
Ctrl+click-insert route through the piece + section
containing the clicked ride time.
Shift+click = multi-select (group drag clamps against non-selected
neighbors, ignores ripple; Delete removes all; F with an adjacent PAIR
selected straightens the segment between them onto the chord; Shift+F
("alignLock") STRAIGHT-LOCKS it — Node.straightOut on the segment's left
node, enforced by enforceStraightLocks() at the top of every redraw
(re-chords facing handles; smooth endpoints keep C1 unless that side is
itself locked), drawn as TWIN 1px lines instead of the curve stroke;
endpoints stay draggable (line follows); the FACING knobs are HIDDEN
and unclickable since v10.4 (knobPos null — collinear control points
make the cubic exactly the chord, so they do nothing); Shift+F
with a lone endpoint selected unlocks (with pairs selected the
pair-priority rule above applies) — a joint whose two sides sit at
DIFFERENT slopes comes out BROKEN (a kinked "smooth" node was a lie;
matching slopes, e.g. insert-on-the-line then unlock, stay smooth);
inserting on a locked segment
locks both halves; saved as an 8th field on `n` lines — old readers
ignore extra fields, missing field = unlocked). Holding S
during a node drag = transient axis constraint from the dominant movement
direction (same guide line as Z/X; slideKey tracked via key filters since
S is not a modifier). Arrow keys nudge the selection by snap steps (Ctrl+arrow
lands on the grid; key repeat glides; undo coalesces per burst). Menus and
dialogs have LOCAL undo/redo (Ctrl+Z / Ctrl+Shift+Z while open; history
discarded on close so the next Ctrl+Z hits the coaster). Lane buttons are
tinted with the curve colors. The graph auto-fit time window FREEZES
during drags (fixes runaway end-node dragging); dragging past ANY plot edge
grows that axis at a RATE proportional to the overshoot (dragGrowTimer,
an AnimationTimer — grows even with the mouse held still; view re-fits on
release). A MANUAL zoom window is SANITIZED at every redraw (v13.3
D75: slid back inside the piece's stretch, auto-fit resumed when at
least piece-wide — structural edits used to leave the window where
the strips no longer were: inserts landed "at the beginning of the
section" and the plot went ghost/blank), and '+' add-section /
add-bridge reset to auto-fit outright (they activate the piece-end
section, which must be on screen). The S-slide axis constraint anchors to the node's GESTURE-START
position (snaps back onto the original row/column). After click-inserting a node, the first press prioritizes the node
body over its own knobs (freshInsertPriority). `subHolder.setMinSize(0,0)` is
load-bearing: without it the SubScene's min size stops the window/divider
from shrinking and the collapse controls go off-screen. 3D view floor: static transparent
reference grid (1 m lines, heavier every 10 m) at ABSOLUTE GROUND y=0 —
Start y is height above ground (default 21 so the shipped layout keeps its
old ~2 m clearance; tracks CAN dig below the grid); it fades
over SIX concentric bands (minor lines gone past 120 m, majors past 240 m)
onto a solid translucent sheet (600 m, 3 cm below the lines to dodge
z-fighting) because JavaFX 3D lacks mipmaps and distant sub-pixel lines
shimmer. LOD: the grid group follows the active camera (orbit pivot / fly
pos / POV sample) in 10 m quantized steps, keeping full density around the
viewer and the pattern world-aligned.
Default keys (ALL rebindable via Edit > Shortcuts,
persisted per-user): `1/2/3` lane select · click node = select + show handles
· drag handle = length-only, `Shift` = angle · `B` smooth/broken (circle vs
square knobs) · click the curve = insert node exactly ON it (hover shows a
translucent dot riding the line; flat segments insert level with SINE
handles, curved ones use an exact de Casteljau split — tangent handles,
curve unchanged, headless-tested; keeping the button down chains
straight into a node DRAG — one gesture, one undo step, Ctrl snaps
live, v10.1; Ctrl while HOVERING snaps the dot in TIME per the
horizontal snap reference — value stays on the curve — and the click
inserts there; the dot hides where the snapped spot lacks INSERT_GAP
room, shared insertRoomAt) · right-click a NODE (or selection) = its
OPTIONS menu (v16.18, D130: Edit node…, smooth/broken — boundary
match on edges — match-all, level, level-lock, straighten,
straight-lock, SPLIT SECTION HERE, SPLIT TRACK PIECE HERE, CONNECT
WITH PREVIOUS/NEXT SECTION (v16.19 D131 — edge nodes facing a LIKE
neighbor: same type/domain/axis flags; Coaster.mergeSections —
matched seams merge losslessly, split→connect round-trips EXACTLY,
gated 0.0 m; unmatched seam nodes take the AVERAGE value + averaged
smooth handle slopes), delete;
LIVE shortcut labels in parentheses; inapplicable items are
HIDDEN, not grayed, since v16.20 (D132 — the menu is what you can
do HERE; Delete lost its redundant shortcut tag and never shows on
edge nodes; the BADGE menu carries a GRAYED "Connect with previous
section" copy for discoverability); the
piece split on a section's FIRST node routes to the classic
boundary split; v10.7's dialog lives on as Edit node… and
double-click still opens it directly) · right-click a SEGMENT =
its lock menu (Unlocked/Straight/Horizontal radios, one segment, same
flags as Shift+F/Shift+H; opens on a CLEAN release ≤3 px so
right-drag pan still works from the curve) · `A` append 1 s
to the ACTIVE lane only, holding its final value (other lanes keep their
dotted hold — no nodes added; appended transitions get SINE handles, not
cubic — only the shipped default layout keeps 1/3) ·
`Space` POV cam — starts HANDS-OFF (W/S and the S slide key stay with the
graph, so you can edit while watching); right-click the view CAPTURES the
ride controls (mouse hidden, no mouse look): W/S ride at simulated speed,
Shift ×4, Ctrl ×0.25; A REVERSES the view — A again or D = forward,
W/S keep their track direction, one applier with the View-menu check
(v16.15); right-click releases; while captured, Space flips
between fly (WASD) and POV (W/S) without releasing (`povCaptured`);
scroll = fast burst either way, translucent graph line marks the camera
(drawn ALWAYS since v17.11 — the POV position persists outside POV
and the bottom stats line tracks it). POV↔selection sync (v13.11 D85, user
spec): riding across a PIECE boundary activates that piece in the
editor (activateAt, piece transitions only — povFollowPiece;
SUSPENDED while the convert UI owns the graph, v15.4 D102 — a
whole-bridge convert shifts piece indices and the follow's
activation change cancelled the density preview, undoing the
convert within a frame: the "convert did nothing in POV" bug);
entering POV starts at the active piece's START when a different
piece was selected since POV last rode — the piece's own FIRST
sample since v13.12 (D86: Span.t0 is by definition the PREVIOUS
section's last sample time, which parked the camera at the end of
the previous piece) — and RESUMES the previous
ride position otherwise (was: always restarted at 0) ·
`H` (action "flath") level the selection ONTO the main node — all
selected nodes take selIdx's value with flat handles, one horizontal
line; a single selected node just gets flat handles (v10.5 re-deal:
this was Shift+H's move) ·
`Shift+H` (action "levelSel") LEVEL-LOCK the selection: level onto the
main node, then the segments BETWEEN selected nodes hold HORIZONTAL via
Node.levelOut — enforceLocks pins each right node's value to its
left's, moveNode writes a RIGHT-end move back through the run (v10.6:
EITHER end steers the line's height; interior nodes snap back on) and
the facing handles stay flat; drawn as the straight lock's twin lines
PLUS a
translucent lane-color band between them; facing knobs hidden;
inserted nodes stay ON the line, both halves locked; saved as a 9th
`n` field;
Shift+F⇄Shift+H swap a segment's lock type directly (each clears the
other). BOTH lock toggles are PAIR-PRIORITY (v10.6, D64): with
adjacent nodes selected they add/convert/remove locking ONLY between
the selected nodes (all-already-locked = unlock; locks merely sharing
an endpoint with the selection are untouched), and both take 2+ nodes
(three nodes = two segments, each its own line); a lone selected
endpoint still unlocks its own segment(s) · `Z`/`X` toggle vertical/horizontal
node-drag lock (translucent guide line; per-selection) · `Ctrl+S/O` save/load ·
`Ctrl+Z` undo · `Delete` deletes the selected node (interior deletes bridge
the gap — the neighbors' facing handles keep their FRACTIONS and
their SLOPES: dv rescales onto the merged segment's longer time run
(BezierBaker.removeBridging, v13.12 D86 — user rule "handles stay
their fractional amount, the slope should just not change"; plain
removal kept dv and halved fitted slopes per delete — converted
carpets "snapped flat" — and a v13.11 keep-absolute-knob variant
was REJECTED; the D63 re-timing squish is untouched); last-node
deletes shorten the
ride; node 0 refuses) · double-click a NODE = modal editor to type
transition time, transition Δ, and handle fractions directly (grey
COMPUTED TWINS beside the fields update live as you type — the
absolute axis position and the Δ from the previous node, v16.15;
typed
fractions SLIDE the knob along its current line — dv scales with length,
S-drag semantics; node 0's dialog also edits the START SPEED; labels are
just "In handle"/"Out handle" and the drag readout shows the bare
fraction — units/meaning deliberately live in Help only) · right-click
the 3D view = FLY mode (WASD + mouse look via Robot cursor-warp, Shift ×4,
Ctrl ×0.2, scroll burst; right-click exits KEEPING the view — the orbit rig
has a movable pivot `camPivot` set to the point `zoom` m ahead of the fly
camera; mutually exclusive with POV; TWO FOV settings — "POV" and
"fly & orbit" (the handoff pair must share a lens or mode switches zoom-
jump) — plus a fly mouse-sensitivity slider, all in
Edit > Options; fly look deltas are capped ±35 px/event to tame OS pointer
acceleration, and measure from where the cursor ACTUALLY rests after
each Robot warp — warpFlyCursor reads getMousePosition back, v16.10
D122: the ideal subscene centre is fractional in screen coords while
the warp lands on a whole pixel, and that constant sub-pixel dy bias
pitched the view steadily during sideways sweeps). Graph editor: scroll
zooms the time axis around the cursor (view window in tToX/xToT, min 0.1 s,
full zoom-out resumes auto-fit; grid step floors at 0.01 s with 2-decimal
ticks), right-drag pans, box select on empty plot (Shift adds; plain-click
deselect happens on RELEASE; the PRIMARY node of a box selection is the
EARLIEST one caught — shift+click keeps last-clicked), rotated lane-name
y-axis label + "Time (s)"
x-axis label + faded per-gridline value labels, adaptive 1-2-5 grid lines.
Insert allows 0.02 s from a node (INSERT_GAP); MIN transition length 0.01 s.
Ctrl-snap steps are Options settings (settings.snapT/snapG/snapRoll;
spinner arrows walk the 1-2-5 series; every value row has a ↺ reset),
and so are the axis references — per-axis LOCAL/GLOBAL radio pairs
(v10.3 wording): "Horizontal snap (time)" local = Δt from previous
(default) / global = absolute ride time; "Vertical snap (value)"
local = Δ from previous / global = absolute (default); backed by
settings.snapTAbsolute/snapVRelative.
The S-slide axis constraint engages
instantly, re-picks from total |dx| vs |dy| each event (can switch mid-
drag), and is OVERRIDDEN by explicit Z/X; S engages mid-drag EVEN WITH
CTRL HELD (snap+slide compose), and while a drag is live or S is held
as the slide key, key events with ANY modifier fire no bound action —
Ctrl+S must not save (nor Ctrl+Z undo) mid-gesture; deliberate Ctrl+S
at rest still saves; guide lines are colored by what
the drag CHANGES — lane color = value changing (vertical guide), LIGHT
PURPLE = time changing (horizontal guide); purple is the time color
everywhere (`TIME_COLOR` rgb(200,165,255), v10.2 — was yellow, which
geo yaw now owns: "Time (s)" label, POV marker). Guides draw at
alpha 0.5; S-dragging a handle shows its dashed slide rail. Hovering the curve
shows the insert dot plus the lane's value (Shift REPLACES it with the
ANALYTIC slope from `BezierBaker.slopeAt`; hovering a NODE shows a
GREY ring + the drag readout's Δt/value in grey — Shift swaps in the
absolute axis position + Δ from the previous node, v16.15; the same
Shift twins take over the DRAG readout mid-gesture, and Ctrl-hover's
snapped insert dot leads with the global axis position, v16.16 — differencing the baked
polyline stair-stepped; tracked via key filters so it updates without
moving the mouse). The node-drag readout clamps into the plot bounds. Orbit drags
respond ONLY to the primary button — the right-drag that exits fly used to
fall into the orbit handler with a stale anchor and jump the view.
Mouse sensitivity (Options)
drives fly look AND orbit (orbit = 2× the setting); Shift+wheel arrives as
HORIZONTAL scroll on Windows — every scroll handler falls back to deltaX. Handles reach MAX_FRAC = 1.5
(150% of the segment; baker's monotonic guard sanitizes folds). The
node body always outranks its own knobs on press. valToY does NOT clamp —
plot drawing is clipped instead; off-screen handles show a dashed stub +
hollow edge marker (click = handleViewExpand grows the scale; click off
restores). Append with a selection = 1 s flat hold inserted after it (all
lanes shift; transition shape transfers to the hold node); node dialog
edits transition time + GLOBAL value. The 3D view and graph panel sit
in a vertical SplitPane — divider resizes, and a ▼/▲ button overlaid on the
3D view collapses/restores the whole bottom panel.
Node-drag readout shows Δt from the previous node and the GLOBAL value with
units ("Δt=0.80s  2.50 g"). Toolbar "Ripple:" checks
(minimal filled-square checkboxes, per-axis): "time" shifts every later node
of the lane in t with the drag (preserves later transition times); "value"
translates the rest of the graph vertically. Menu bar: File (New… Ctrl+N —
unsaved-changes gate (Save / Don't Save / Cancel, also guarding Load),
fresh 1 s force/time doc, empty history, "Untitled"; Save Ctrl+S writes
straight to the OPEN file, Save As… re-targets; the WINDOW TITLE is the
open file name with * while dirty; +
Export NL2 CSV — rebindable "export" action Ctrl+E; modal dialog with
rail-center-vs-heartline radios and a segment-length spinner, both persisted
per-user in AppSettings; spacing rounds to a whole number of equal segments;
the CSV negates Z for NL2's right-handed world — see D40 before touching
signs), Edit
(Shortcuts dialog — TWO titled category columns since v10.3 (Graph
editor | View/File/History, ACTIONS carries the category as its 4th
field); rebind by clicking + pressing a key, ↺ resets an edited
binding to default; SNAPPING dialog — renamed from "Options" in
v16.14 after the viewport rows left: snap steps (the v13.9
bridge-optimizer rows were
REMOVED in v13.10, D84 — internal constants again) and snap axis
references; the POV graph marker is ALWAYS ON since v16.14), VIEW
menu (v16.13, D125 — the v7.5
"no View menu" verdict reversed on user spec, between Edit and
Coaster: the "SHOW GIZMOS" row + inline axis dots are GONE since
v18.16 (D187 — ball-click reveal replaces the check, the per-pane
top-right button owns Local/Global), "Show Heartline" check
("(neon)" dropped v17.10) — hairline
self-illuminated yellow tube on the integrated path, ONE STRAND PER
PIECE since v16.13 (a single ride-wide strand visually joined
close-parked pieces), app-wide default off, setVisible(false) while
POV rides (the camera sits ON it) — "SHOW NODE MARKERS" (renamed again in v18.11; was "Node markers
on the 3D track" until v17.11), "VISUALIZE SELECTION"
check (v17.10, D149, renamed from "Visualize track selection"
v17.11; AppSettings.visualizeSelection,
default on: OFF = no piece desaturation — every tube full active
color — and no white active-section ties; pure view setting;
RAIL COLOR lived here v16.14-v17.9 and is the COASTER menu's
"Rail Color" section since v17.10 — see the Coaster paragraph;
showRailColorDialog's owner-crash lesson
lives on as a comment: watch "Custom Color" from a menu row), the
COMB radios — FIVE-way since v17.16 D157 (No | G-Force = vertical
+ lateral together | Speed | GEOMETRIC with inline Local|Global|
Combined dots on its row (the gizmo-dots pattern; grayed unless
selected; AppSettings.combGeoMode, default Combined) | Time —
AppSettings.combMode, modes exclusive) + "Comb size (%)" slider
(applies on release), "G-FORCE COLORS…" (its label's letters tinted by the
LIVE gradient hottest-first, rebuilt per menu show, v16.15; the
SIX vertical stops shared with the rail gradient — purple −1.5 /
blue −1 / cyan −0.5 / green 1 / yellow 3 / red 5 — + the lateral
comb's THREE |Gl| stops, every color with a ↺ grayed at default),
(the "Reverse POV view direction" row was REMOVED in v17.11, D152
— the captured-ride A/D keys + the bottom-left "Backwards POV (A)"
tag are the whole surface now), ONE
"FOV" slider 30–110° since v18.9 (D180 — was the POV + fly/orbit
pair; it drives EVERY camera incl. extra view panes, povCam reads
flyFov, the legacy povFov key writes along but is never read) and
mouse sensitivity — every slider wears a
↺ reset since v16.16 — all CustomMenuItem rows
that keep the menu open, with the Options dialogs' menu-local undo
wiring. NO ColorPicker EXISTS anywhere since v16.16: its Custom
Color child is owned by the picker's transient POPUP, which both
crashed the pre-v7.5 menu embed and caused an UNBEATABLE focus
flash on close (Windows re-activated another app before any
refocus could run — the v16.14 toFront and v16.15 Window-watcher
band-aids lost the race and are deleted). Colors are SWATCH
buttons (Dialogs.colorSwatch) opening Dialogs.pickColor — an
ordinary owned WINDOW_MODAL Stage (a hue×saturation PLANE + vertical
brightness slider + typed RGB/hex since v16.17 D129, plus the quick
palette + last-ten recents; LIVE apply,
OK keeps / Cancel + X restore, one menu-undo pair per session; the
rail swatch re-syncs on menu show via the railSyncing guard so
Load/New don't leave it stale or mark dirty), Help
(clicking the menu TITLE opens the Help dialog directly — it's a Label
graphic on an empty Menu). COMBS (v16.13 D125, reworked v16.14
D126 — FVD++-style; teeth 2× wider along the track since v16.15):
one tooth per 0.5 m at EXACT arc spacing
(arcHit lerps frames between samples — the same fix ended the
ties' quantize-to-sample wobble), per piece, growing from the
HEARTLINE — G-FORCE mode = VERTICAL felt G outward along −U
(0.5 m/g, the rail gradient's colors) + LATERAL felt G along +R
since v18.13 (D184 — with the flipped values every tooth stays on
its historic visual side; 0.8 m/g, ABSOLUTE-value colors mirrored about
zero on latColors/latStops — per-coaster, saved); SPEED mode =
TRUE speed up along U (0.05 m per m/s, solid GREEN since v17.16 —
its old light blue moved to the time comb); GEOMETRIC mode
(v17.16, D157) = curvature per METER, teeth pointing OUTWARD
from the curve (away from its center — the −U-for-positive-G
convention), 0.5 m
per °/m (COMB_K_SCALE), computed straight off the bracketing
frames (dT/ds ⊥ T, runt steps widened past): sub-modes LOCAL =
body pitch (−U) + yaw (−R) tooth pairs, GLOBAL = elevation rate
(−vup) + heading rate (horizontal-right for a left turn),
skipping near-vertical track, COMBINED (default) = ONE tooth
along −κ̂ with |κ|; COLORS per component since v17.18 (D159):
pitch/elevation ORANGE (GEO_LANE_COLORS roll), yaw/heading RED
(235,60,45), the combined tooth their RED-ORANGE midpoint
(245,115,65); EVERY curvature hover appends the radius —
radiusTxt, r≈57.3/val, zero components read (r=∞); TIME mode
(v17.16) =
a constant-height tick every 0.1 s of RIDE TIME (timeHit — lerped
by t so the s/c sub-sample phase never quantizes; integer tick
indices, k%10==0 = WHOLE seconds drawn WHITE, the rest the old
speed blue; hover = "t = X s"); modes exclusive,
force/speed values from the TRUE arrays, cap 3.5 m × the size
setting, teeth
under 2 cm skip, whole combGroup hidden in POV and
mouse-transparent (clicks pass through). Hovering ANYWHERE on a
tooth (root→tip segment, 10 px) shows the value in the
BOTTOM-LEFT overlay label (Viewport3D.combLabel — MANAGED in the
subHolder StackPane; the v16.13 unmanaged version never got sized:
invisible). Shift+MIDDLE-click
OR C+MIDDLE-click the track in orbit mode re-centers the pivot on
it (moved off left with the orbit drag, v16.12;
screen-space nearest track point within 25 px via pickTrackSample:
distance to the SEGMENTS between strided sample projections — near
track projects the dots hundreds of px apart, clicks between them
used to miss (v13.5) — with the eye/forward taken from the ACTIVE
camera's localToScene (the orbit-rig math misclassified half the
scene as behind while the POV camera rendered: picks failed half the
time); BEHIND-CAMERA points filtered — localToScreen
projects them mirrored, which made sky clicks teleport the pivot;
segments spanning a > 6 m piece gap skipped so the space between
detached pieces stays unclickable; a miss
moves nothing and the drag orbits normally; C tracked like the S slide
key). DOUBLE-click the track = INSERT a node in the active lane at
that ride time (v16.12 — was Ctrl+click; same pick, feeds
insertNodeOnCurve — identical semantics to clicking the graph curve,
and the graph window scrolls to the new node). MIDDLE-drag = the
ORBIT drag, REPLACING left-drag entirely (v16.12, user re-spec of
the v16.11 pan; the primary button is selection/gizmos only;
panView survives for the ortho right-drag pan; secondary stays
excluded — fly-exit stale-anchor jump). On-track NODE
MARKERS (Options toggle, default on): a small sphere per
active-lane node — on the HEARTLINE in orbit/fly since v18.7 (D176;
POV keeps the spine-level position, the camera rides the heartline)
— selection in white, signature-guarded rebuild on graph
redraws (the signature carries povActive); a clean CLICK on one selects that node in the graph and
re-centers a manual zoom window onto it (v16.11 — marker pick beats
the track-activate click, 14 px). PLACEMENT GIZMOS (v16.11 D123,
reworked v16.12 D124; BALL-CLICK REVEAL since v18.16 D187,
reversing v17.7 D144's always-on): only the WHITE BALLS show until
one is clicked — entry/seam ball reveals the piece/seam rig,
bridge-end ball the bridge rig; the same ball again, any primary
press off the gizmo, or a piece switch hides it (gizmoReveal, per
pane, in the rebuild sig). AppSettings.showGizmos is DELETED;
gizmoLocal is SESSION-ONLY (every launch starts Global) and the
TOP-RIGHT per-pane Local/Global button is back, visible only while
revealed. Since v18.17 (D188) the rigs render in a transparent
OVERLAY SUBSCENE with a real depth buffer (proper self-occlusion,
still over the whole world; cameras mirrored per frame,
pickOnBounds(false) + mouseTransparent-when-empty pass events
through) — D187's DepthTest.DISABLE painted in submission order
(rings over arrows, inside-out tori). The un-revealed white ball
is FIXED-size and lives with the TRACK (trackBallGroup ∈ world,
occludable); dragging it moves the owner AND reveals mid-gesture
(rig-only build — replacing the pressed ball would kill mouse
capture); the revealed rig carries its own SCALING center ball
(0.272 × rig.scaleT = the D186 proportion; click hides, drag
moves, dbl-click settings; wireBall wires both). The spheres
remain as ANCHORS — the entry ball still selects node 0 on click,
the seam ball's double-click still opens the seam settings. v18.18
(D189): the overlay is mouse-transparent in fly/POV (the warped
fly cursor hovering it un-hid the mouse + starved the look
handler); the track ball hides while its rig is up; LOCAL mode
aims the rings along the entry/follow frame (rigid NL2 rotation);
since v18.19 (D190) a LOCAL ring drag is a TRUE rotation about the
drawn axis — TrackPiece.decomposeEntry inverts entryPose()'s
composition (heading gap → yaw, elevation gap → pitch, U-twist →
roll; DecompProbe-gated), RingLocal sinks write all UNLOCKED
deltas (bridge/seam via gizmoSet*OrientAll) — Global stays the
classic one-Euler-per-ring; the button says "Gizmos:
Local/Global". Also v18.19: A over a view never appends
(mouseOverView gate), a clean non-marker viewport click DESELECTS
(deselectFromViewport), and ball/rig double-click panels open AT
the click (nextPopupAnchor, one-shot). v18.20 (D191): gizmo
gesture ends redraw the GRAPHS (endGizmoDrag →
redrawFromViewport — rebuildTrack never repaints the canvas);
EVERY numeric spinner is typable (FvdDesigner.typable) and
numericEditor commits typed text on FOCUS LOSS app-wide (JavaFX
only commits on ENTER); narrow spinners widened to 82 px;
"Reverse piece…" label. Arrows X green / Y blue / Z red (v16.13); rings wear
GEO_LANE_COLORS (yaw yellow world-Y, pitch teal horizontal-right,
roll orange heading) — yaw always, pitch/roll only while their
padlocks are UNLOCKED; locked or machine-owned axes build NO part
(a piece bridged-in under an optimize mode builds no piece gizmo —
the previous piece's bridge gizmo is the surface). Bridge end per
mode: manual = arrows + unlocked rings; optimize modes = ONE orange
TANGENT arrow dragging the LENGTH (hidden under Optimize length;
optimize-orientation owns the rings so none show) — writes go
through PIECE-scoped trailing-bridge paths (GraphEditor
gizmoSetBridgeGap/gizmoSetBridgeOrient/gizmoBridgeMoved; no undo of
their own, and the OPT_POS+optLen orient path re-knees SILENTLY —
applyOptimalLength's zero-distance prompt must never pop mid-drag).
DRAGS ARE ABSOLUTE FROM THE PRESS (v16.12): arrows project mouse
travel onto the press-time screen axis (pose = base + dir₀·λ);
rings rotate by LINEAR mouse distance along the press-time ring
tangent (~57°/grabbed-radius, value = base − dist·k, the one
Y-mirror sign) — a part that re-places itself per event (an
optimize-position bridge) can't feed back into its own input, the
v16.11 yaw spiral. Ctrl SNAPS translation by the element-length step — onto the
ABSOLUTE world grid with GLOBAL axes, step increments from the
press with LOCAL (v18.12, D183; the length arrow stays absolute
length rounding) — and rings to 1°. Parts re-aim LIVE per event
(each keeps a Rotate fed by direction suppliers; rebuild waits for
release — replacing the pressed node kills JavaFX mouse capture);
scale is view-DEPTH based (~10% of visible height; euclidean
distance ballooned it toward the screen edge) via origin-pivot
Scale transforms (the scale PROPERTIES pivot at layout-bounds
centre — wrong); gizmos stay visible in FLY (POV hides them), push
ONE undo per gesture, and consume their mouse events. The gizmo-axes
choice (AppSettings.gizmoLocal — the top-right overlay button was
REMOVED in v18.10 D181; the View menu's inline Local/Global dots
are the surface) flips the translation arrows between world
X/Y/Z and the entry frame's right/up/forward — rings keep the model
Euler axes (a world-axis pitch/roll isn't representable in the
delta model), and toggling never moves the track. VIEW-DIRECTION DIGITS `1`-`6` (v18.11, D182 — FIXED keys
replacing T/Shift+T; 1 Front, 2 Back, 3 Right, 4 Left, 5 Top,
6 Tangent to Selection; 1-5 only while a pane is captured or the
mouse is over a VIEW — over the graph 1/2/3 stay lane switches —
6 works from the graph too, aimed at the last-hovered pane) enter
the TOP/SIDE views — TRUE orthographic since
v18.8 (D178): a ParallelCamera mirrors the orbit rig while the 1°-FOV
pseudo-ortho state (camZoom ~72× the distance, apparent size
preserved) remains the zoom/pan BOOKKEEPING, so every control is
unchanged (the old "ParallelCamera can't do 3D" was JavaFX-8-era —
26 honors camera transforms); side views draw a GROUND LINE at y=0
and take the SUB-MODE dropdown (Front/Back/Right/Left/Tangent,
v18.9 D180) — top snaps yaw to the nearest 90° (always grid-aligned), side
centers the pivot on the SELECTED node's track point AND yaws the lens
TANGENT to the track's heading there (perpendicular look direction,
nearer of the two sides kept; no selection or vertical tangent = world
X–Y elevation; D53) (selected markers
draw white AND larger, 0.32 vs 0.22 m); RIGHT-drag pans in the view
plane without exiting ortho (grab-style, world-units-per-pixel from the
lens; right-click does NOT hand off to fly while ortho); same key
restores the previous perspective view, orbiting away
keeps the angles and restores the lens, scroll zooms multiplicatively
while ortho, fly/POV entry auto-exits ortho, far clip 200000 while
active. Toolbar "Resulting ▾" is a plain Button + ContextMenu (a
MenuButton's arrow region broke the flat button look). Coaster (Heart
spinner −3..3 default 1.0, Gauge spinner 0.3..2.5 default 1.0, Tie-spacing
spinner 0.2..10 m default 1.0 (cross ties placed by arc length, flush with
the rails), "Friction
(rolling μ)" 0..0.2 step 0.005 + "Air drag (per km)" 0..20 step 0.1
(v17.0 D133 — the model's fricAir is 1/m, the spinner edits ×1000;
tooltips carry the model; both wireDocSpinner = document undo;
the START SPEED row LEFT this menu in v18.6 D175 — the spinner
object survives OFF-SCENE as the v0 write-through choke with its
undo wiring, and node 0's dialog is the one editing surface) — the PIECE rows
(x/y/z ±500 m + yaw ±360°, the ACTIVE piece's placement, re-synced on
piece switches; saved per `piece` line; y = height above the y=0
ground, default 21) MOVED to the toolbar "Track Piece ▾" dropdown (renamed from
"Placement ▾" + made a MOVABLE dark popup, v17.8 D145) next
to the piece selector in v13.2 (D74), along with "Reverse piece" —
the Coaster menu keeps only truly per-coaster knobs. Start speed is
saved as the `v0` line and is edited ONLY in the first node's
double-click
dialog since v18.6 (D175 — the dialog writes through the off-scene
spinner). Rail color lives in
the COASTER menu's "Rail Color" section since v17.10 (D149 — was
the View menu v16.14-v17.9): "Solid" (its SQUARE 22×22 swatch —
all pickers shrank square in v17.10 — shows inline while selected;
the reset ↺ is gone per user) | "Normal Force" (the former
"G-force gradient" wording; the gradient colors by the TRUE felt
vertical G since v17.12, D153 — like the comb, identical until a
design speed drifts; a "G-Force Colors…" item appears
right below it while selected, v17.11 — same dialog as the View
menu's; and the Coaster menu ends with the COASTER STATISTICS
submenu, v17.11 D152; Dialogs.railColorItems, the v16.16
Load/New railMenuSync guard rides the Coaster onShown; the
gradient's stops — each a COLOR and a G VALUE
spinner, defaults purple −1.5 / blue −1 / cyan −0.5 / green 1 /
yellow 3 / red 5, flat outside the ends and lerped between, saved
as `gradcolors` + `gradstops` lines — edit in "G-force & comb
colors…"; the pre-v7.5 lesson that a menu-embedded picker's
"Custom Color" crashed on the auto-hiding popup owner is the WATCH
ITEM on this move). Toolbar: lane toggles, Ripple time/value
checks, "Resulting" popup (persisted per-user, but every file OPEN
— New/Load/startup — clears all picks OFF, v18.10 D181; a dark hover-dismissed Popup of
CheckBoxes since v10.3 — stays open while picks are clicked, closes
250 ms after the mouse leaves both it and the button, re-entry cancels;
Popup's default consumeAutoHidingEvents makes the button click-to-close
work. v10.4: the hide timer VERIFIES the real cursor via
Robot.getMousePosition and re-arms instead of hiding while it's inside
— JavaFX skips MOUSE_ENTERED when the popup appears under an
already-moving mouse, which let the button's exit-timer hide it
mid-hover. MODE-AWARE since v10.2 — the two entries retitle with the active
section: over a FORCE section they are the resulting pitch/yaw rates
from FVDIntegrator.bodyRates wearing the GEO palette; over a GEO
section the felt VERTICAL/LATERAL G in the FORCE palette. Color rule: a
curve wears its QUANTITY's palette. v10.3: enabled overlays share ONE
RESULTING SCALE — fitted like lane scales, defaults ±60 °/s / −1..4 g,
frozen during drags — labeled on a RIGHT-EDGE axis (ticks + rotated
title; marginRight() widens 14→48 px while shown; the roll lane no
longer auto-fits overlays), and hovering an overlay shows a dot + its
value in the curve's color (insert dot keeps priority; Shift swaps in
the numeric slope, v10.4). A third
ROLL/BANK pick exists since v17.6 (D143 — FORCE view only: over
geo the roll lane already IS the shared quantity), and the
popup's "Resulting graph settings…" dialog picks how the
overlays present — Rate units °/s|°/m, Roll axis local|global,
Yaw/pitch axis (geo-resulting only), Roll behavior rate|absolute
bank — persisted per-user (AppSettings resRateM/resRollG/resYpG/
resRollAbs + resRoll; live-apply; supersedes v16.7's automatic
°/m follow; resValueAt converts via worldRates/÷v/bankOfTU;
check labels re-title via syncResChecks; the BANK overlay wears
its own RED-ORANGE (BANK_COLOR rgb(235,70,45), v17.7 D144 —
REAL absolute-bank lanes wear it too since v17.9 D146, and the
roll/bank overlay draws NO seam caret since v17.9; the Resulting
popup DRAGS like the Track Piece panel, drag = hover-dismiss
stands down); the roll overlay
works in BOTH views since v17.7 — grayed only while it would
duplicate the active section's own roll representation,
resRollRedundant; resulting default scales match the lane
defaults incl. ±5 °/m and ±90 bank)),
POV, Append (no shortcut hints on buttons; the stats-bar shortcut
hint died with the POV line, v17.11); the Resulting overlays span the whole
RUN since v13.11 (v10.8 made them ride-wide, but the global timeline
continues into DETACHED pieces, whose curves bled in at the window's
right edge — overlays, true strokes, their hover and the scale fit
all clamp to [runT0, runT0+runDur] now, D85). The BOTTOM STATS LINE
is the LIVE POV POSITION since v17.11 (D152): X/Y/Z, speed,
vertical + lateral G — TRUE values, three decimals
(Viewport3D.updatePovStats, refreshed per rebuild/ride frame/POV
scroll; povTime persists across POV exits so the line stays live
while editing, and the purple POV marker line on the graph draws
ALWAYS now). The five coaster stats (ride time, track length,
height range, top speed, Gv range) moved to the Coaster menu's
COASTER STATISTICS submenu (refreshed per open); the shortcut hint
is gone entirely. While BRIDGE SETTINGS are edited, the POV
position CLAMPS onto the active bridge
(GraphEditor.clampPovIntoActiveBridge in applyBridgeGap /
gizmoBridgeMoved — shrinking the length used to drop the POV into
the next piece, whose ride-follow activation closed the
Bridge-properties panel mid-edit; clamped only when the POV was ON
the bridge before the edit). Window sizes itself to
`Screen.getVisualBounds()` (capped 1400×880) — do not hardcode window sizes;
that broke on the user's 1080p screen once already.

**Help dialog is a USER REQUIREMENT to keep current:** Help > Controls &
Features (`showHelpDialog` in Dialogs) must be updated with EVERY
user-facing feature or control change, in its matching section, prioritizing
non-obvious behavior. Do not ship a feature without touching it.
REWRITTEN CONCISE in v17.16 (D157, user spec — it had grown into
400-word wall-of-text rows): guidance for an fvd-literate user, terse
helpKey rows grouped Getting started / Graph editor / Nodes / Handles /
Sections / Straight-curve / Speed & conflicts / Pieces & strict seams /
Bridges / 3D viewport / Menus & settings — NO version-number
archaeology, no rationale narration; keep entries at ~3-4 lines and
put new features in their section at that density (the changelog
lives in DECISIONS.md, not Help).

## Known quirks / accepted debts

- Extreme handle angles can fold a curve; the baker's monotonic-t guard
  flattens it, so drawn curve and baked result can differ at pathological
  settings.
- Rails + cross ties but no spine box yet. Ties (and comb teeth) sit
  at EXACT arc positions since v16.14 (arcHit lerps between samples
  — they used to quantize to the next sample, up to ~v·dt late, the
  visible uneven-spacing bug). The tie grid anchors at each PIECE's
  starting arc length (v13.3 — per-piece meshes start mid-ride; a
  zero anchor put a tie on every sample until it caught up).
- FRICTION corners (v17.0, D133/D134): the bridge length optimizer's
  rateCost ladder stays LOSS-FREE (a relative smoothness ranking —
  friction shifts every candidate alike; the applied geometry is
  friction-aware). The two-coefficient model is the WHOLE friction
  story — a normal-force-scaled "Advanced Friction Model" was
  designed and REJECTED by the user (v18.6, D175). An s/c GLOBAL-bank element's spine moves ~mm-cm
  under friction (the bank projection's per-leg cadence is
  speed-dependent, O(dt²) — RATE-roll spines are exact, gated).
  Stalling designs (easier to hit with friction) still creep through
  at the 0.5 floor — real stall/rollback is future speed-section
  work. REVERSAL corners: a natural-speed reversal STALLS wherever
  the climb home exceeds the recorded end energy (real physics —
  the preview telltale + a higher entry speed are the answer); a
  LEGACY kinked reversed seam cannot survive the chained re-author
  (frame-continuous sections smooth it — the far side rotates by
  the kink angle); reverse-twice is a fit-of-a-fit, not an exact
  involution (Ctrl+Z restores exactly).
- CREST BRIDGES S-curve BY CONSTRUCTION (v17.14, D155): both seam
  curvatures pull UP while the net pitch drops, so C1 seam
  continuity forces pitch to overshoot ~±10-20° around the drop —
  shorter bridges are flatter; this is the honest minimum, not a
  bug (the pre-v17.14 corkscrews/loops WERE the bug). Pre-v17.14
  saves whose optimized march was a wrong-branch shape render
  SEPTIC until re-optimized (the D148 compat precedent), and a
  manual-mode save with stale short tangents keeps its spiky
  septic until the target is nudged — loads never move pieces.
  Extreme length requests whose ideal path dives near-vertical
  (the world-pitch hump's singular zone) still fall back to the
  septic honestly — with sane 1.5×-chord tangents since v17.15
  (D156), and never as a rendered corkscrew (the bank gate). On
  BANKED crests past ~2× the gap an isolated length can still
  fail to converge while its neighbors march (the family thins
  near the singular zone) — honest septic, practical lengths all
  covered.
- Undo/redo exclude tie spacing and rail colors (cosmetics; menu-local
  Ctrl+Z only — but they do mark the file dirty).
- Friction defaults 0.03 m/m + 1.0e-4 m⁻¹ for NEW documents only
  (v18.0, D161): files without a `friction` line still load loss-free,
  and the headless gates build zero-friction Coasters (class default).
- SPEED CONTROL corners (v18.0, D160; parity round v18.1 D165): piece
  REVERSAL refuses while any section carries controls (a lift on the
  mirrored flow is ill-defined — remove them first); INFLUENCE entry
  grays the same way (the two-pass would drop them = silent reshape);
  the strip's complex graphs edit through the SHARED pipeline since
  v18.3 D172 (one editor, two surfaces — value-ripple AND the typed
  node dialog included; a double-click prefers the node dialog and
  is the promote gesture only off the nodes; smaller nodes/lines
  are the one difference). ZERO sits ON the control line in
  the tall view (speed above, negative accel below; the line PINS
  at its collapsed height unless a COMPLEX control is selected —
  v18.10 D181, scaling grows the scale upward from it; the ▲/▼
  arrow's hit zone is the whole left-gutter column and gutter
  presses never reach the line — v18.11 D182, rapid toggle clicks
  used to create controls) and the strip's
  top edge is a draggable height DIVIDER (speedStripSize 44-420 px;
  < 40 collapses; the arrow stays the quick toggle). Stall
  transparency reads Pass D's 0.1 m/s floor and the POV treats the
  stall like the END OF THE TRACK — wraps to the next piece or the
  ride start (D171, his clarification of D168's stop) — real
  stall/rollback physics is still future work (the creep-through
  model remains; the ghost track is the honest display). ROLL-ONLY
  converts keep lanes 0/1 BIT-EXACT (D169 — only the roll lane
  refits; the preview auto-selects it; gate 55j 3 mm).
- ALL TOOL WINDOWS ARE MODELESS since v18.1 (D166 — viewport controls
  work with any window open; installViewKeys forwards ride keys from
  every secondary Scene; every numeric entry is digits+period only).
  Accepted corners: a node/element dialog left open across edits
  applies to the object it captured (deleted node = harmless no-op),
  and the same dialog can be opened twice.
- A straight/curve section's duration is rarely a whole number of dt
  steps, so samples DOWNSTREAM of one sit off the 0.01 s grid by a
  constant sub-sample phase. POV/marker code indexes time as t/dt
  (nearest sample) — the lag is < 10 ms of ride and invisible, but
  don't add code that assumes sample i sits exactly at i·dt.
- TIME-domain sections LAND their authored end exactly since v17.10
  (D147, superseding D130's "never fix the loop epsilon" — the user
  asked): the closing integrator step is sized (0, 1.5·dt], so the
  last sample sits ON the authored end instead of the old loop
  refusing it on float overshoot (every seam used to absorb up to
  one dt of profile; a split moved the track end ~0.24 m on the
  probe ramp, the user's report). With v17.11's EXACT bake + the
  0.01 s time grid (D151 — knots on every RK4 step boundary, so
  split halves' grids land on the parent's own points), a split at
  any 0.01-multiple is exact to MACHINE precision (1.2e-12 m on
  the curved hill fixture) and off-grid cuts sit at the few-µm RK4
  step-phase floor — gates 52a-c. Old saves re-time slightly under
  both changes (pure accuracy gains); all suites re-baselined
  green (default layout 406.878 → 406.931 m). Don't add code
  assuming the last sample interval is exactly dt (it is the
  landing remainder).
- SMOOTHED STRICT SEAMS (v17.2, D139) bend three contracts
  knowingly (third since v17.3 D140: the closure's arc STRETCH
  means the drawn arc inside the window exceeds the s fields by up
  to ±5% — ties/combs sit a hair wide there — in exchange for
  the downstream not moving at all):
  the upstream span's EXIT stays the FOLLOW-CONTRACT pose, not the
  blended seam sample (D80's exit==last-drawn-sample rule bends
  inside the window — re-stamping the blend would bake the
  half-kink into resolve ops), and the design felt still STEPS at
  a conflicted smoothed seam (the ≠v speaking — the TRUE felt is
  what smooths). A hard kink in a short window is an honest big-g
  excursion (κ·v²): widening the window is the fix, not a bug.
  Fit ops read through the smoothing (compileForFit); reversal
  deliberately captures the smoothed truth.
- SPLITTING or REVERSING a TILTED piece (pitch/roll deltas ≠ 0)
  bakes the tilt into the new baseOrient — generation resumes FROM
  the tilted pose there, so those two ops re-solve force sections
  against gravity instead of staying rigid (D100 accepted corner;
  everything else about tilts is rigid + gated).
- The Placement rows' pitch/roll padlocks on un-bridged pieces
  (TrackPiece.uiLockPitch/Roll) are UI edit-guards only — not
  persisted, but they DO ride undo snapshots since v16.12 (D124:
  applyDocState rebuilt pieces with default-locked guards, so any
  Ctrl+Z slammed open padlocks shut); a bridged-in piece shares the
  BRIDGE's persisted lock fields instead.

## Roadmap (user-endorsed, phased — see docs/SECTIONS-DESIGN.md for Phase 1+)

The long-term goal is a multi-section designer (force / geometric / bridge /
straight-curve sections, splittable track pieces, speed control, NL2
interop). That requires ONE deep overhaul: splitting the single integrator
pass into geometry generation (per section, at a per-section DESIGN speed)
and a global dynamics pass (true speed from energy/friction/speed sections)
plus an analysis pass (true forces from geometry + true speed). Rationale
and contracts live in docs/SECTIONS-DESIGN.md. Order:

**Phase 0 — independent wins (do first, none is wasted by the overhaul):**
1. ~~NoLimits 2 CSV export~~ DONE v7.8 (D40: Nl2CsvExporter.java, File >
   Export NL2 CSV, rail-center/heartline + segment-length settings)
2. ~~RK4 integrator upgrade~~ DONE v8.0 (D44: FVDIntegrator v3, circle
   radial error 10 cm → <1e-9 m; layout invariants re-baselined)
3. ~~Read-only "resulting" graphs~~ DONE v8.0 (D45/D47: bodyRates +
   toolbar "Resulting" dropdown — pitch/yaw picks, no roll)
4. ~~Ortho top/side views · C+click orbit recenter · middle-drag orbit~~
   DONE v8.2 (D48: T/Shift+T pseudo-ortho — 1° FOV at ~72× distance,
   ParallelCamera is unusable; C+click aliases Shift+click recenter;
   middle-drag orbits)
5. ~~Smoothing tool~~ shipped v8.0 (D46), REMOVED v8.1 on user decision
   (D47: the design method shouldn't lean on value-smoothing)
6. ~~Viewport editing part 1~~ DONE v8.2 (D48: read-only spine spheres
   for the active lane's nodes + Ctrl+click-track-to-insert)

Phase 0 is COMPLETE (v8.2).

**Phase 1 — the overhaul (feature-neutral):** section/piece model,
geometry–dynamics–analysis split, per-section design speed, time/distance
domain toggle, quaternion internal orientation, save format FVDD3,
section-aware graph, document-level undo, split FvdDesigner into files.
Existing headless invariants must pass unchanged.
- ~~Milestone A~~ DONE v9.0 (D50): document model (Coaster/TrackPiece/
  Section/ForceSection/TrackPose/Quat), TrackCompiler three-pass
  pipeline (bit-identical output gated), DesignIO with FVDD3.
- Milestone B: ~~document-level undo~~ DONE v9.1 (D51, incl. heart/gauge/
  start/v0 per user rule) + ~~file lifecycle~~ (New/Save/Save As/dirty
  title — a user feature request folded in); ~~FvdDesigner split~~
  COMPLETE v9.4 (D52 GraphEditor, D54 Viewport3D + Dialogs; shell
  4.2k → 1.1k lines); ~~section-aware graph + '+' add-section~~ DONE
  v9.5 (D55).

PHASE 1 IS COMPLETE (v9.5). The '+'
menu and a future piece selector are where "attach floating piece" and
multi-piece editing land (user roadmap in SECTIONS-DESIGN §8).

**Phase 2 — section types (easiest first):** ~~geo sections~~ DONE
v10.0 (D58: LaneSection base, quaternion-native GeoIntegrator,
mode-aware editor, `section geo` in FVDD3, gated TESTING #12 incl. the
2 mm force/geo equivalence canary) + UX rounds v10.1-v10.4 (D59-D62:
unified view, zero-line fills, geo palette, section badges + delete,
boundary return (Shift+B = all lanes), insert-drag, end-wall/end-snap,
snap axis toggles,
purple time, mode-aware Resulting with right-edge axis + hover/slope,
hover-dismissed Resulting popup (cursor-verified, seam-padded),
categorized Shortcuts dialog, straight-lock knob
hiding + honest unlock, H re-deal + LEVEL LOCK v10.5, pair-priority
lock toggles + multi-segment F + two-ended level v10.6, right-click
node dialog + segment lock menu + Ctrl-snapped inserts v10.7,
full-ride Resulting overlays + Gv/Gl color swap v10.8 — the
D62 C1-preserving re-timing was reverted in v10.5) →
~~reversal~~ DONE v10.9 (D67: badge-menu toggle, canonical generation
+ yaw placement, Gl negates / roll invariant, gated TESTING #13) →
~~straight/curve mode~~ DONE v11.0 (D68: element list + strip widget
replacing the lanes, sine transition smoothing from day one,
ScIntegrator with breakpoint sub-stepping, `section sc` in FVDD3,
compiler Spans, gated TESTING #14; s/c roll modes + drag-extend +
"Append" v11.1, D69) →
~~split/reconnect + piece selector~~ DONE v12.0 (D70: badge-menu
split; '+'-menu attach; toolbar piece selector; Piece x/y/z + yaw
spinners edit the ACTIVE piece; gated TESTING #15) + v12.1 (D71:
splits carry the FULL boundary orientation via TrackPiece.baseOrient
— bitwise at ANY boundary, user rule; REVERSAL became a piece
operation — Coaster menu > Reverse piece, a model transformation
that keeps the track put; the per-section badge check is gone.
3D DRAG GIZMOS
still to come — spinner placement shipped first) →
~~bridge sections~~ DONE v13.0 (D72: quintic Hermite + RMF/eased-
twist orientation, '+'-menu create, tangents dialog, gated TESTING
#16) + v13.1 (D73, user redesign: LIVE-FOLLOW — the compiler
re-captures endpoints+curvatures every compile, freeze only for
orphaned bridges; G² ends; bridge to ANY piece with timeline
reorder; "Optimize placement" badge action — closed-form §4 mode 2;
plus viewport fixes: meshes break at piece gaps, plain-click
activates a piece) + v13.2 (D74, seam smoothness + piece-UX round:
SEPTIC ends + speed blend + roll-rate twist = rate graphs continuous
in value AND slope at seams; toolbar Gap spinner auto-optimizing per
tick; piece placement/reverse moved to the toolbar "Placement ▾"
dropdown; per-piece meshes with inactive pieces DESATURATED; bridged
seams abut flush = one visual track) →
~~piece placement 3D gizmos~~ DONE v16.11 (D123, the viewport
round: entry/bridge-end gizmos with the lock rule — no part for a
locked axis — plus the bridge LENGTH tangent arrow, the Axes
local/global toggle, white active-section ties,
clickable node markers; gated TESTING walkthrough #82) + v16.12
feedback round (D124: middle-drag = THE orbit, double-click
insert, gizmo REVEAL markers + both rigs at once, absolute-from-
press drags with Ctrl snap, live re-aim, depth-based scale,
fly-visible; walkthrough #83) →
~~mid-section piece split~~ DONE v16.18 (D130, node-anchored:
Coaster.splitSectionAt — both halves via the splitLanes de
Casteljau identity, right half's vDesign = speed at the cut, flags
+ pins carry; == authoring two sections there EXACTLY (gated
0.0 m; vs the unsplit original only bake-grid phase since the
v17.10 END LANDING, D147 — see Known quirks); "Split track piece" chains
the D71 rigid boundary split (gated 0.0 m); both live in the new
node right-click menu; Phase2Gate 258. PHASE 2 IS COMPLETE) →
influence
curves + "become real" (direct attach REMAINS first-class next to
bridges, user decision; SECTIONS-DESIGN §5 spec'd in full + user-
approved 2026-07-07 — ROUND A live v13.6 D79: per-run Pass D, true
Pass A arrays, drift display/POV/stats, gated TESTING #21; CONVERT
live v13.7 D81 (§5.3b, user-spec'd during round A — landed early,
gated TESTING #23) and EXPANDED v14.0 D89-D91 per the user's list:
partial convert for every mode + node snap + exact cuts +
whole-bridge attach + density preview + adaptive placement + lock
tints, gated TESTING #31; round B —
the two reconciliation ops — SHIPPED v14.5 D97; round C —
INFLUENCE mode — SHIPPED v15.0 D98 per the user's 2026-07-11
re-spec (cross-mode delta lanes, two views, non-modal design
editing, gated TESTING #37 + walkthrough #68 — §5 is COMPLETE) →
~~TIME/DISTANCE domain toggle for force/geo lanes~~ DONE v16.0
(D103, per the user's 2026-07-12 re-spec: per-section domain, the
graph x-axis follows the ACTIVE section's display domain with every
other strip WARPED on, badge-menu Domain radios = the exact node-map
conversion (the 2026-07-09 rule — NO carpet refit: t_i → s(t_i)
through the section's own profile, fracs kept, values exact, dv ×
v̄_segment/v(node); constant-rate exact, the 407 m torture layout
0.20% — below the convert engine's 0.3% fit residual, the promised
"sub-fit-residual"; speeds/pins/conflicts persist), plus the
CROSS-DOMAIN VIEW (edit the native lanes through the other domain's
warped axis; snaps/readouts in display units, map frozen per
gesture). Gated TESTING #40 + walkthrough #71. The same user message
placed the domain options in the badge-menu SECTION SETTINGS block
where the GLOBAL/LOCAL axis toggles now live) →
~~v16.1 follow-up round~~ DONE same day (D104-D107, per the user's
answers: domain conversion became a carpet REFIT with the density
preview — the VIEW owns exact-shape; GLOBAL/LOCAL axes shipped
(roll = bank-vs-horizon for both modes, yaw/pitch =
heading/elevation for geo — he had misspoken, current roll IS
local); influence works on DISTANCE sections (two-pass over arc);
refreeze BAKES piece tilts (his "not fully resolving" bug) with
whole-span conflict detection; node moves keep handle slopes
(supersedes D63); zoom survives axis flips. Gated TESTING #41 +
walkthrough #72).

**Phase 3 — dynamics:** ~~air + rolling friction~~ DONE v17.0 (D133:
two per-coaster coefficients — rolling μ·g + air k·v² — as a loss
deficit in every integrator's RK4 state AND Pass D's run march;
bitwise-off gated by the whole old suite. REVERSAL reworked as a
CONVERT the same day — D134, user re-spec walking back a first
design-direction scheme: "Reverse piece" mirrors the compiled
geometry and re-fits it as forward sections at one energy flow from
the recorded end speed (padlocked row, custom on unlock), same
structure, NO speed conflict, friction included; bridge pieces
refuse; the reversed flag = legacy files only. Gated TESTING #48 +
walkthrough #92) → ~~SPEED CONTROLS~~ DONE v18.0 (D160-D164: per-
section lift/brake/launch intervals on the "+V" strip — constant-v +
constant-felt-G + complex graphs with min/max semantics, applied to
design AND real speed equally (a clamp closes ≠v conflicts), tie
plates, stall-transparent track + POV skip, NL2 friction units and
defaults, time-comb density; gated TESTING #55 + walkthrough #111) →
real stall/rollback handling (the 0.1 m/s creep floor is still the
model — the transparent ghost track is its honest display).

**Phase 4 — interop & full interactivity:** NL2 CSV import (external-
geometry sections), viewport editing part 2 (drag values/times/handles in
3D), Euler display toggle.

**Small items, anytime:** spine box in the rail mesh (copy
`buildTieMesh`), friction spinner (subsumed by Phase 3).
