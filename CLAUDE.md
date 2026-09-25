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

Written in **Java 26 + JavaFX 26** (pom.xml is the authority — an
older doc line said 21), built with **Maven**.

## Build & run

```
mvn javafx:run        # build + launch the designer
mvn compile           # compile-only error check
mvn clean             # nuke target/ if the build gets into a weird state
```

- Main class: `uvd.Main` since v19.12 (D241 — a plain launcher, set
  in pom.xml's javafx-maven-plugin config): it sets `glass.win.uiScale`
  from AppSettings.uiScale BEFORE the toolkit initializes (an
  Application main class gets the toolkit pre-initialized before its
  own main() runs, so the property would arrive too late) and then
  launches `uvd.FvdDesigner`.
- Sources live in `src/main/java/uvd/`. Every file starts with `package uvd;`
  (package `coaster` → `uvd` in v19.21, D250 — a mechanical rename, no behavior
  change; the settings file `~/.fvd-designer.properties` keeps its name).
- JavaFX comes from Maven (`org.openjfx:javafx-controls:26`) — no SDK
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
                    INFLUENCE deltas (v19.7, D236): Options.dInfl +
                    dInflYpGlobal — Δpitch/Δyaw GEO-RATE keyframes
                    injected into Ω per RK4 stage at the current
                    state (GeoIntegrator.inflOmega, BEFORE the roll
                    term so global-roll feed-forward sees the whole
                    turn; °/m lanes scale by v); the felt records
                    add the deltas' analytic contribution −ΔΩ·axis·
                    v/g, so resulting == base + delta exactly.
                    null = plain, bitwise.

AppSettings.java    APP-WIDE PERSISTENT SETTINGS. Pure Java, no JavaFX.
                    Shortcut bindings + FOV in ~/.fvd-designer.properties
                    (combos are strings like "Ctrl+Z"; UI parses them).
                    Per-coaster settings do NOT go here — they go in .uvd.
                    uiScale (v19.12, D241): the Resolution Scale factor
                    RELATIVE to the OS display scale — 1.0 = never set
                    the glass property = today bitwise; read by
                    uvd.Main pre-toolkit, edited in Preferences >
                    Resolution Scale… (restart-only by platform fact:
                    glass.win.uiScale is consumed in WinApplication's
                    clinit; a malformed value crashes startup, so Main
                    sanitizes hard and multiplies by the AWT-read OS
                    scale — the property REPLACES the OS scale).

Main.java           THE LAUNCHER (v19.12, D241): plain class, does NOT
                    extend Application — see Build & run.

SpeedControl.java   SPEED CONTROLS (Phase 3, v18.0 D160). Pure Java.
                    One lift/brake/launch interval on a section's OWN
                    authoring axis [x0, x0+len]: type SPEED (target
                    m/s, INSTANT snap) | ACCEL (felt longitudinal G
                    magnitude toward a target — loss' = ∓2gA, friction
                    absorbed by the drive, crossing lands ON the
                    target; SPLIT accel/decel since v18.45 D217 —
                    fields decel + tied + decelLane + the session
                    showDecel display flag: tied (default, every old
                    file) reads accel for BOTH sides bitwise, untied
                    brakes at its own magnitude/graph — driveCore
                    takes both, per-side a ≤ 0 = natural; editLane()
                    is the strip's displayed-graph choke), either
                    COMPLEX (Bezier graph replaces the
                    constant; exactAt reads), min/max flags = ceiling/
                    floor semantics (≥ 1 always on). The Ctl evaluator
                    drives (per step) + snaps (post-step) the LOSS
                    deficit in all three integrators — null = bitwise;
                    rowFor/driveRow/snapRow are passD's per-sample
                    twins (rows {type, vt, aAcc, aDec, min, max};
                    design and real move EQUALLY, which is how
                    a control closes a ≠v conflict); portion/
                    marksPortion clip controls exactly for splits/
                    converts (complex lanes de Casteljau at the cut,
                    decelLane identically).

Nl2CsvExporter.java NL2 EXPORT. Pure Java, no JavaFX. Resamples samples
                    onto NoLimits 2's external-track CSV (tab-separated
                    pos + front/left/up rows). Negates Z on the way out
                    (our rendered world is left-handed, NL2 right-handed
                    Y-up) — change signs only with DECISIONS.md D40 open.
                    HOME of the SHARED export resampling (v18.30,
                    D202, both writers): heartline = cubic Hermite on
                    the exact sample T's, frame = per-segment SLERP,
                    spine = heart − offset·U from that frame — the
                    old per-exporter LERP left chord-sag aliasing
                    that read as high-frequency jitter in NL2 (the
                    classic FVD++ artifact; gated rms bound in
                    Nl2ElemProbe). Never interpolate the offset spine
                    with heartline tangents — probed WORSE (D202).

Nl2ElemExporter.java NL2 ELEMENT export (.nl2elem, v18.29 D201) — the
                    FVD++ "NoLimits 2 Exporter" format, structure taken
                    from openFVD's source: root>element>description +
                    vertex x/y/z/strict blocks + roll blocks (up,
                    right = front×up == FVD++'s vLat, coord = k/nSeg),
                    all %e. ELEMENT-LOCAL coords: D40 z-negation, then
                    anchor-subtract + heading-about-Y onto forward=−Z
                    (heights ride through). Endpoints strict. Shares
                    the CSV exporter's line/resample construction;
                    pure Java, gated by Nl2ElemProbe vs the CSV under
                    the rigid transform.

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
                    one type. tiltDeviates(ls, lane) /
                    tiltDeviatesAny (v19.19, D248 item S3): which
                    lanes a RIGID TILT changes — all force lanes, geo
                    world-axis + absolute lanes; geo local rates are
                    rotation-invariant — the editor's true-stroke
                    split reference reads it. DOMAIN field (v16.0, D103): TIME = lane
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
                    node) + ABSOLUTE YAW/PITCH ypAbsolute (v19.8,
                    D237 — the rollAbsolute twin for GEO lanes 0/1
                    under GLOBAL yaw/pitch, section field 10: lane
                    0 = the ELEVATION angle °, lane 1 = the HEADING
                    ° — reported-left, PIECE-relative since v19.9
                    (D238 — 0 = the PIECE's entry heading, one
                    continuous zero across the piece's chain like
                    elevation/bank, = world heading for unrotated
                    pieces; supersedes D237's section-entry-
                    relative "always sets the entrance angle to
                    zero") and unwrapped past ±180; elevation
                    authors within ±90, a loop needs rate lanes.
                    The lanes wear their OWN colors + names since
                    D238 — Elevation deep cyan-blue rgb(40,150,200)
                    / Heading YELLOW-GREEN rgb(180,225,45) since
                    v19.10 (D239 — was amber) (ELEV/HEAD_
                    ABS_COLOR, the BANK_COLOR pattern: curves,
                    buttons, y-axis "Elevation"/"Heading"/"Bank",
                    resulting overlays; the abs-roll button caption
                    is "Bank" since D239 too). The "Yaw/pitch
                    behavior" row's RATE radio grays under Force
                    with a hover reason since D239 (Convert/Add/
                    wizard/Resulting — the axis row's rule).
                    GeoIntegrator.projectYp targets the lane values
                    post-step — elevation about horizontal-right
                    FIRST, heading about world-Y second (preserves
                    the set elevation exactly), before the roll
                    blocks; slopes feed Ω per stage via omegaFor's
                    absTheta/absPsi; TrackCompiler.exactYp = the
                    D121 knot-exact resample, then the D238 SHIFT
                    (geoLane subtracts the section entry's piece-
                    relative heading = the chain-psi difference —
                    generation bits, so x/y/z + yaw moves stay
                    bitwise-rigid and piece firsts are offset-0
                    bitwise); SPLITS/MERGES WITHIN a piece carry
                    lane values VERBATIM (D238 — the D237
                    cut-rebase/lift pair is gone); splitPiece /
                    reanchorAtBoundary REBASE moved lanes by the
                    boundary's chain-psi delta (rebaseAbsHeading,
                    2.8e-14 m gated); converts extract PIECE-
                    relative unwrapped heading (ypHeadDeg
                    reference through fitLanes; entry value lands
                    in (−180,180]) and NEVER divide angle lanes by
                    v (the D213 rule); influence refuses; B = the
                    D120 neighbor-side recipe (absYpTarget) minus
                    the piece reference, landing on absRep's
                    PRINCIPAL representative (D238 — adjacent-node
                    escape keeps deliberately out-of-range lanes'
                    branch; supersedes D120's unwrap-to-edge-node;
                    absRollTarget shares it), refusals toward abs
                    neighbors on every lane; the
                    "Yaw/pitch behavior" row sits directly below
                    "Yaw/pitch axis" in Convert/Add/wizard,
                    double-interlocked Geometric+Global; YpAbsGate
                    22. The Resulting settings DO have a
                    "Yaw/pitch behavior" row since D238 (resYpAbs
                    — force view + global axis; comps 1/2 show
                    absolute elevation/piece-relative heading in
                    degrees, own colors, no seam carets, ±90
                    default; a rate roll then own-scales — the
                    D214 inverse; two abs behaviors share)) + the
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
                    INFLUENCE state (v15.0, D98; the ONE-PASS HYBRID
                    since v19.7 D236 — see TrackCompiler; GLOBAL-AXIS
                    sections join in v16.5 D112, with their own
                    influence axis flags inflRollGlobal/
                    inflYawPitchGlobal — the delta Ω injects about
                    those axes; entry mirrors the section's roll
                    choice): deltaLanes
                    (OTHER-mode delta lanes; null = uninfluenced) +
                    vAuthored (INERT since D236 — a mirrored
                    file-compat field; the vDesign FIELD is the one
                    speed the whole solve runs at, hand-edited
                    divergent pairs compile at vDesign like plain
                    sections) + editInfluence (which surface the
                    strip edits; in DocState, never saved) +
                    baseCurves (last compile's base rows {x, L0..L2}
                    on the section's OWN axis = resulting − delta,
                    transient — the editor's guides/offsets read them
                    via baseValAt/baseSlopeAt; deltasAllZero() gates
                    the do-no-harm fast path, bitwise). The D235
                    SPLIT ANCHOR (inflRefTilt/inflVStarK) is GONE —
                    the hybrid's splits are exact by the Markov
                    property alone; the loader ignores the one-build
                    `infl` fields 4-8. INFLUENCED sections TAKE
                    SPEED CONTROLS (v19.6 D235; the one Ctl drives
                    the single hybrid solve since D236).
                    PRISTINE-LANE END FOLLOW (v19.19, D248): endFollows[3]
                    — per-lane "not edited since creation" flags, SET on
                    every hold creation (continuation / holdSection / the
                    '+' and Insert holds / the wizard; split halves
                    inherit, merges / converts / delta lanes never;
                    TrackPiece.copy deep-copies); pristineHold (exactly two
                    nodes, equal values, DEFAULT_FRAC flat handles within
                    1e-9, no lock) = the load-time derivation for files
                    without the `lane` field (AT the section end only,
                    and only while the DEFINING lanes end there too —
                    the sync's FIXED POINT, D248 review round 2: a hold
                    short of the end or the sole longest hold stays off,
                    old files open bitwise) and the redraw-time BELT
                    (clearStaleFollows — CLEARS a follower that stopped
                    being hold-shaped, never moves one; a redraw can't
                    rebuild); touchLane = the
                    editor's ONE clearing choke (identity against the
                    section's own lanes — delta lanes / control graphs are
                    no-ops); syncFollowingEnds moves every still-following
                    lane's last node to the latest DEFINING lane's end
                    (floored at node 0 + the domain gap; growing AND
                    shrinking the section; a non-pristine flagged lane is
                    cleared, never moved). Feeds no geometry — the WRITE is
                    an editor action (the GraphEditor gesture sinks,
                    before their own rebuild; the redraw runs only the
                    clear-only belt; guarded docApplying/docRestoring),
                    so compile/rebuild stay idempotent; EndFollowGate 73.

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
                    INFLUENCE deltas (v19.7, D236 — the one-pass
                    hybrid): the widest integrate/integrateDist
                    overloads take dInfl = ΔGv/ΔGl FELT keyframes;
                    each RK4 stage adds Δω = ±g·ΔG/v to Ω at the
                    current state and the felt records add the
                    deltas verbatim (null = plain, bitwise). Also
                    home of inflOmega — the Δpitch/Δyaw RATE
                    delta → Ω mapping (local body or global
                    heading/elevation, omegaFor without roll) that
                    FVDIntegrator.deriv injects for force sections.
                    See PHYSICS.md §2b.
StraightCurveSection.java  Phase 2 (v11.0, D68): the first NON-LANE
                    section — an ELEMENT list: straight (length) | arc
                    (radius, signed angle, direction: 0 = flat turn
                    with +angle = rider-LEFT since v18.16 D187 (the
                    profile negates the U component — the D185 yaw
                    boundary; old flat-arc files mirror), 90 =
                    pull-up unchanged), each with
                    an ENTRY smoothing length (element 0's is the
                    SECTION-ENTRY roll ease since v19.3 D232, VERBATIM
                    since v19.4 D233 — 0 = an instant bank snap; old
                    files stored 0 and now snap (accepted), NEW
                    sections create elem 0 with an explicit 2 m; its
                    own strip DIAMOND + band drags it absolutely,
                    and the band's far EDGE drags too since v19.5
                    (D234 — scEdgeHit 0, the strict-seam pattern;
                    INTERIOR blend-band EDGES drag ONE-SIDED too since
                    v19.19 D248 — Gestures.edgeWindowLen, off past the
                    boundary and staying off; the diamond keeps |·|);
                    the κ seam itself stays sharp) and a ROLL spec (v11.1, D69): RATE °/s
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
                    blended with the QUINTIC smoothstep since v19.4
                    D233 — roll rate tangent to zero at both blend
                    ends, the strict-seam weight; κ keeps the sine so
                    roll-less sections stay bitwise — and NOT
                    v-scaled); RK4 on
                    (pos, quat, s) in TIME with
                    v algebraic; SUB-STEPS to land every RK4 leg
                    exactly on profile breakpoints with side-capped
                    lookups (stepping OVER a κ step costs ~0.1°/cross;
                    k4 reads the far side) + Newton landing on the
                    section end. GLOBAL roll = an algebraic PROJECTION
                    after every leg (roll about T to the QUINTIC-eased
                    bank target, v19.4 D233 — never bends the spine;
                    ease from the measured entry bank over elem 0's
                    verbatim smoothIn / interior max(smoothIn, 2 m);
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
                    DELTA about Y + TWO-TIER ORIENTATION v19.0
                    D221/D222: designPitchDeg/designRollDeg beside
                    the TRUE pitchDeg/rollDeg — generation composes
                    the DESIGN tier (gravity re-solves there), the
                    design→true gap is the rigid conflict tilt;
                    design (0,0) = the D100 path bitwise, design ==
                    true = the pure link. THE ORIENTATION MODE (v19.20,
                    D249 — enum OrientMode { LOCKSTEP, REAL_ONLY,
                    DESIGN_ONLY }, field orientMode, default LOCKSTEP =
                    D248's unlocked default; it REPLACED the boolean
                    shapeLocked — kept as the DERIVED accessor
                    shapeLocked() == REAL_ONLY) is a pure EDIT-ROUTING
                    choice the compile never reads — editPitch/editRoll
                    are the chokes: LOCKSTEP = both tiers by the same
                    delta (the D222 invariance: same design triple ⇒
                    same shape), REAL_ONLY = the true tier only (the
                    conflict grows, shape kept — D222-D248's "locked"),
                    DESIGN_ONLY = the DESIGN tier only (design := v, v the
                    design delta; the real entry never moves, the shape
                    re-solves); setDispPitch/Roll + setRawAbsPitch/Roll
                    derive the new delta from the tier the mode edits
                    (DESIGN_ONLY: the design raw incl. its own pole
                    branch — rawAbsDesignPitch/Roll are the ring baseVals
                    there). ONE TIER RULE: EVERY writer of a piece's
                    pitch/roll — boxes/↺/rings AND the bridge march
                    (Coaster.alignPieceForBridge) — goes through them, so
                    a LOCKSTEP march never opens a conflict (supersedes
                    D222's "the machine writes the true tier by
                    convention"); yaw is shared by both tiers in every
                    mode. orientConflict() keys the buttons/resolve ops;
                    bakeTilt zeroes BOTH tiers; `porient locked pd rd
                    affect mode` line (field 1 keeps its D222 meaning
                    1 = REAL_ONLY for old readers, field 4 = the carry,
                    field 5 = the mode ordinal = the authority when it
                    parses to 0..2; the LOADER decides field 4's meaning
                    by FIELD COUNT — exactly 4 fields = a D248 'only'
                    line, INVERTED; 3 = no carry), missing LINE =
                    LOCKSTEP + (0,0) + no carry (the compile reads none
                    of it, every old file's TRACK bitwise, only its edit
                    routing changed). affectSubsequentSeams (v19.20, D249
                    — the INVERTED rename of D248's orientOnlyToSeam;
                    "Affect Subsequent Strict Seams", default FALSE =
                    D248's default behavior; PieceState/copy): while ON a
                    REAL_ONLY piece's placement edit carries the CONNECTED
                    chain rigidly (the D221 carry bitwise); OFF = the edit
                    stops at the piece's own exit junction of EITHER kind
                    (bridges re-march/re-solve, followers keep their held
                    angles; a horizontal re-aim can open a seam ≠o,
                    accepted); inert under DESIGN_ONLY (the real entry
                    never moves) — Coaster.carriesDownstream = orientMode
                    == REAL_ONLY && affectSubsequentSeams is the ONE
                    predicate. ABSOLUTE DISPLAY (v19.1, D224):
                    dispYaw/dispPitch/dispRoll (+ dispDesignPitch/
                    Roll) = the CANONICAL heading/elevation/bank of
                    the entry (base Euler via decomposeEntry off the
                    identity + delta; past the pole: heading+180,
                    mirrored elevation, bank+180 — the NL2 flip);
                    setDisp* invert on the CURRENT pole branch (route
                    = the D222 lock; false = plain true write for the
                    bridge rows); rawAbsYaw/Pitch/Roll + setRawAbs*
                    are the RING coordinate (base + delta, unreduced —
                    continuous through the pole; no ±85 clamp
                    anywhere, spinners ±90). decomposeNear(base,
                    target, hints) = decomposeEntry's BRANCH-
                    CONTINUOUS twin (both Euler branches, each
                    component wrapped to the hint's 360° window,
                    smallest total change wins — local rings, carries
                    and the tilted split use it; the old canonical
                    decompose + clamp was the "wobble" and would flip
                    the shared yaw). v12.1: baseOrient is the natural
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
                    CANONICAL PAIR canonPsiDeg/canonOrient (v18.27,
                    D199, piece fields 19-23, missing = null =
                    decompose baseOrient): the compile chain's exact
                    heading + heading-stripped orientation, stored
                    by splitPiece from the boundary Span so a split
                    piece generates with bitwise the parent chain's
                    arithmetic; rides undo snapshots; every OTHER
                    baseOrient write NULLS it (bakeTilt keeps it
                    across pure-yaw bakes — psi += yaw exact).
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
                    FOLLOWERS HAVE A LIVE DESIGN TIER since v19.18
                    (D247 — supersedes D222's "both tiers dead"):
                    designPitch/RollDeg ≠ 0 generate from
                    designPose() (zero deltas = the historic path
                    bitwise), the REAL orientation is the follow
                    contract, and the seam conflict = Coaster.
                    seamConflictTilt / isSeamOrientConflict —
                    elevation/bank ONLY (a heading-only re-aim
                    commutes with gravity, D199, no conflict).
                    GraphEditor.seamEdit is the ONE held-angle
                    write choke, MODE-KEYED since v19.20 (D249):
                    LOCKSTEP = Coaster.seamDesignLockstep keeps the
                    design/real elevation+bank gaps constant (the
                    quaternion tilt drifts at second order like a
                    free piece under editPitch/editRoll — accepted;
                    a write crossing straight up/down leaves the tier
                    alone + posts SEAM_POLE_MSG; its math = the public
                    Coaster.applyDesignEulerDelta), REAL_ONLY = the
                    historic write bitwise, DESIGN_ONLY = a value-only
                    held pitch/roll write is DIVERTED to the design
                    tier and the held values restored (padlock toggles
                    and yaw stay real-side — the locks are the real
                    contract, heading is shared); the UI's design boxes
                    / rings write through Coaster.seamDesignSet /
                    GraphEditor.seamEditDesign (design elevation or
                    bank := deg via eulerOf(designPose()); refuses
                    axis 0 and a vertical design frame with stats
                    reasons); yaw never touches the design tier.
                    Seam Design → Real = bakeFollow (both tiers
                    zero, follow + locks kept), Real → Design =
                    seamRealToDesign (held pitch/roll := the
                    design elevation/bank, locked; refuses a
                    vertical design frame). Attach keeps a
                    design-tilted piece's conflict for free (no
                    fold; SeamShapeGate).
                    Coaster ops: makeSeamStrict (D71 split + flags),
                    unstrictSeam (attach + PIN — graphs bit-kept,
                    track re-bent continuous, conflict kept),
                    detachStrictSeam (CONFLICT-PRESERVING since
                    D247: position bakes + the TRUE tier
                    decomposes onto the follow contract via
                    Coaster.followContract/decomposeNear, design
                    tier + canonical pair kept — attach→detach is
                    an involution, SeamShapeGate 8; a vertical
                    frame falls back to the bakeFollow bake),
                    insertSectionAtSeam (D247 — makeSeamStrict
                    FIRST when the downstream is to keep its
                    shape, then append; see INSERT SECTION),
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
                    probes — new converts MUST use fitLanes). The
                    re-time march is SPEED-CONTROL-AWARE since v19.7
                    (D236 — the fit's energy march was blind to the
                    source's controls, and a launched section's bake
                    landed 10.9 m off: (v/vG)² felt distortion + the
                    clock stretch; the widest convertToLanes overload
                    takes (srcCtl, srcOff, srcDist) — drive + snap
                    per sample, the passD twins, keyed by the
                    source-native coordinate — and fitLanes(…, src,
                    sp) derives them; convertDomain / bakeAxes /
                    partialConvertSection / convertWholeSection /
                    makeGraphsReal all pass the source; CtlBakeProbe
                    0.011 m).
                    RUN TOPOLOGY (v19.19, D248 — the ONE definition the
                    editor, SectionNames and the gates share:
                    endsWithBridge / followsPrev (the D135 rule) /
                    bridgedInto / runStartOf / runEndOf — GraphEditor's
                    runStart/runEnd/followsPrev/endsWithBridge delegate,
                    LabelGate pins the equivalence) + RUN AS PIECE:
                    runHeads / runCount / runOrdinal (the "Piece k/n ▾"
                    selector, the '+' lists and the Export picker name
                    RUNS), canMoveRun (dry run) + moveRun (moves the
                    contiguous block head..runEndOf, order kept, only
                    the head's inert followPrev normalized; refuses a
                    non-head, a destination inside the block, a
                    bridge-ending destination, a destination that is not
                    its run's END (D248 review round 2 — the block would
                    splice into that run) and an orphan-bridge block
                    unless already last), connectStrictRun (moveRun +
                    connectStrict's tail), the static helpers runLengthM
                    (labels), runSampleRange (export — head first span..
                    tail last span, the D84 seam prepend dead for heads)
                    and runTailEnd (the floating wizard's placement, 5 m
                    past the RUN tail); RunGate 51. Switch Coaster /
                    Reverse / Mirror stay PIECE-scoped (accepted).
                    MIRROR PIECE (v19.8, D237 — mirrorPiece, the
                    Track Piece panel beside Reverse): flip the
                    piece horizontally about the vertical plane
                    through its entry heading — a pure ONE-TO-ONE
                    GRAPH NEGATION, no refit (force Gl + roll lane
                    incl. abs bank, geo yaw, influence delta lane
                    1, s/c angle+dir+roll, piece roll deltas, held
                    seam bank; orientation conjugates by
                    Quat.mirrorZ, the canonical pair kept exact) —
                    the compile is BITWISE the z-negated track in
                    the canonical entry frame (MirrorGate 20,
                    mirror² == identity bitwise); bridge-ending
                    pieces refuse. SWITCH COASTER
                    (FvdDesigner.switchPieceCoaster, the panel's
                    third button): moves the active piece under a
                    SAME-FRICTION coaster BITWISE (compile reads
                    only pieces+friction; different friction grays
                    with the reason, bridge connections gray,
                    strict follows bake in place first, v0 mirrors
                    both ride entries, both compile caches
                    invalidate, the view follows the piece). COPY
                    (v19.18, D247 — copyRun / copySection,
                    TrackPiece.copy(dz), COPY_DZ = 5; the panel's
                    fourth ops button, no ellipsis): deep-copies
                    the whole RUN (head..runEnd — followers +
                    bridged targets; a lone follower or a lone
                    bridge-ending piece would copy dishonestly)
                    5 m along world +Z at the list END, then
                    activates the copy, reveals its entry rig and
                    selects node 0; session flags reset, the
                    canonical pair untouched (generation bitwise,
                    D199), the head's followPrev normalized, copied
                    bridges optSig = null (record-only first sight
                    — the sig carries the world entry) with
                    lastOptDist kept; REFUSES (grayed + stats)
                    when the LIST's last piece ends in an orphan
                    bridge (listEndsInOrphanBridge — appending
                    would hand it the copy as target and move the
                    ORIGINAL). CopyGate 73: bitwise except tilted /
                    held-angle / smoothed dust 1e-9, bridges 1e-6.
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
                    Also home to the RIGID DOWNSTREAM CARRY (v19.0,
                    D221/D222 — carryDownstream: a shape-LOCKED
                    piece's hand placement edit moves the CONNECTED
                    chain as one rigid body; manual-bridge targets
                    get position + delta-decomposed orientation,
                    OPT-mode targets orientation only — the march
                    re-lands position on the rigid image itself —
                    followers get their LOCKED Euler angles
                    rewritten; carried writes hit the TRUE tier only
                    (the conflict is adopted, design kept); every UI
                    write funnels through FvdDesigner.placementEdit,
                    which skips the carry for unlocked pieces =
                    natural updates — and, since v19.20 D249, unless
                    the piece is REAL_ONLY with Affect Subsequent Strict
                    Seams ON: Coaster.carriesDownstream (orientMode ==
                    REAL_ONLY && affectSubsequentSeams — D248's inverted
                    orientOnlyToSeam) is the ONE predicate, carryAfterEdit
                    the choke placementEdit calls (PlacementGate); the
                    march's own alignment goes through editPitch/editRoll
                    too (D249's one tier rule — LOCKSTEP aligns both
                    tiers, no conflict; REAL_ONLY the true tier bitwise
                    the historic march), and a LOCKSTEP target's re-solve
                    is settled INSIDE optimizeBridge (settleLockstepAlign:
                    re-capture the head via TrackCompiler.captureTargetHead
                    at Coaster.INTEGRATE_DT + re-align, ≤ 24 rounds /
                    1e-9° — the rebuild loop sees the fixed point in ≤ 1
                    pass; it took 13-23 whole-document passes before;
                    REST GUARANTEE since the D249 review: the head is
                    re-captured ONCE MORE after the last alignment so the
                    stamped signature == the next compile's capture
                    whatever the convergence — a slow pitched target's
                    fixed point is REPELLING and the loop exits
                    unconverged, restoring its smallest-delta round (or
                    stopping on the ±85° pitch clamp); the placement then
                    fails the landing test → the honest septic, never a
                    per-rebuild cascade (g7); under Optimize length the
                    knee is RE-COMPUTED off the settled seams and applied
                    in one bounded extra pass, g7b), so
                    an aligned target can never reshape-loop the
                    optimizer; reversal /
                    bakeFollow / partialConvertBridge re-anchors
                    reset BOTH tiers — a leftover design delta would
                    activate generation later),
                    MAKE SMOOTHING REAL (v19.3, D232 —
                    makeSmoothingReal, seam menu > "Make smoothing
                    real…" via the density preview): the smoothed
                    seam's blend window becomes ONE authored section
                    of the UPSTREAM section's type/flags, fitted from
                    the SMOOTHED samples at the TRUE entry speed;
                    neighbor halves = exact de Casteljau cuts; the
                    follower re-anchors at the window END as a fresh
                    strict seam — its baseOrient = the DESIGN-chain
                    frame recovered via Span.exitTilt (anchoring at
                    the blended WORLD frame re-solved force lanes
                    against gravity: the 37 m gate bug), held angles
                    re-seeded from the world frame. Track holds to
                    the fit residual (5 mm gated); the guard seam
                    auto-un-stricts only on an essentially exact
                    joint (< 1e-4 — practically never: a fit's exit
                    always misses by the residual, the honest
                    answer). Refusals are WINDOW-SCOPED since v19.4
                    (D233; influence joins in v19.5 D234): speed
                    controls/marks on the seam sections are fine
                    OUTSIDE the blend window (they carry onto the
                    kept halves); inside = refuse. An INFLUENCED
                    seam section converts when its DELTAS are zero
                    across the window (deltasZeroOn — polyline rows
                    + held tails + an exact sweep; the kept halves
                    STAY influenced via carryInfluence); non-zero
                    deltas inside the window refuse (the D235
                    divergent-pair refusal is GONE — vAuthored is
                    inert under the D236 hybrid). Still refuses
                    s/c-edge / legacy-
                    reversed / stalled-window seams with stats-bar
                    reasons; smoothing itself can no longer be
                    ENABLED on s/c edges (D232).
                    Also the bridge placement MARCH/optimizer +
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
                    cap; the °/m division NEVER touches an ABSOLUTE
                    bank lane since v18.41 D213 — degrees are not a
                    rate, and dividing them flattened every °/m +
                    absolute convert's banking to angle/v, the
                    shakedown's "always erroneous" abs-roll bug).
                    AREA-MATCHED since v16.5 D111 —
                    matchSegmentAreas nudges node values ~1e-3 so
                    every segment's weighted integral equals the
                    data's against the BAKED polyline: a RIDGE
                    least-squares since v19.7 (D236 — the exact
                    per-segment constraints DEMAND node-to-node
                    alternation on sub-segment features, measured
                    −0.93 lag-1 autocorrelation, the user's
                    remaining bake oscillation; min ‖A·δv−E‖² +
                    μ‖δv‖² + λ‖D₁δv‖², λ=1.0/μ=1e-4 of the mean c²
                    scale — the first-difference penalty kills the
                    alternating mode, still tridiagonal SPD Thomas;
                    his 0.11-spacing bake 20 flips ±0.19 g → 0),
                    damped re-baked passes,
                    best-state on peak cumulative error, sub-grid
                    carpets skip — plus the POINTWISE curve guard
                    (v19.6 D235: a state ships only while its
                    baked curve stays within 1.3× the raw fit's own
                    miss + 2% of range — fine fits improved, the
                    coarse 2.0-spacing round-trip honestly
                    re-baselined to D104's converges-with-density
                    story, gate 40d); endpoints land mm-cm —
                    adaptive force carpets keep a ~0.1-0.2 m
                    through-the-solution floor, fine UNIFORM is the
                    max-fidelity setting there;
                    pitch/yaw extraction ANALYTIC off the recorded
                    felt since v15.1 D99 — the κ identity, exact;
                    only roll is measured, ±1 sample — and converts
                    CARRY vPinned: a conflicted section stays
                    conflicted, both partial halves pin),
                    the NODE-ANCHORED SPLIT (splitSectionAt, v16.18
                    D130 — splitLanes both-halves + vDesign-at-cut;
                    == authored-as-two, gated 0.0 m; INFLUENCED
                    sections split MACHINE-EXACTLY under the hybrid
                    (v19.7 D236, superseding D235's split anchor):
                    a plain native-axis cut — lanes AND delta lanes
                    de Casteljau at xc, carryInfluence copies the
                    axis flags and resets the vAuthored mirror —
                    is exact by the Markov property, whatever the
                    deltas did before the cut (1.4e-12 m gated,
                    was 0.0002 m anchored / 11.8 m at D234).
                    InflSplitGate) + its inverse
                    mergeSections (v16.19 D131 — like sections only;
                    matched seams lossless: split→connect
                    round-trips exactly, gated 0.0 m with SLOPED
                    seams + dirtied dead handles — MergeGate part 1
                    is the recreated D131 fixture; UNMATCHED seams
                    KEEP BOTH VALUES since v19.18 (D247, superseding
                    D131/D132's averaging): a STEP over δ =
                    mergeStepLen(domain), 0.01 s / 0.1 m mirroring
                    GraphEditor.MIN_GAP / minGapOf (keep the three
                    in step) — left node = A's end at seam−δ (in
                    handle kept with the D105 dv rescale, out flat,
                    locks cleared), right node = the seam node with
                    B's value (in flat, out + locks B's verbatim),
                    each BROKEN unless its outer slope is flat
                    (MERGE_EPS 1e-9); equal values + differing
                    slopes = ONE broken node; A's last gap < 2δ
                    mirrors the step onto B's side, both short δ' =
                    max(gap)/2, two one-node lanes = the old
                    average; HELD lanes keep b0's value (in flat,
                    out VERBATIM — v16.20 had halved it through the
                    average) plus a flat hold node at seam−δ when
                    the values differ; the physics sees the step as
                    a one-to-two-cell keyframe ramp on the bake grid
                    — an ~80 g/s jerk for a 0.8 g step BY DESIGN
                    (v16.20's lesson stands: effInDt is NEGATIVE,
                    and a sign-blind guard had halved matched slopes
                    via a phantom flat side),
                    the INFLUENCE enter/discard ops (v15.0 D98 —
                    model-side so the gates drive the real thing;
                    influenced sections JOIN the reconcile like
                    normal ones since D102 — the vDesign setter
                    mirrors vAuthored unconditionally again (D236),
                    pinned still skips; partial
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
                    CANONICAL-FRAME GENERATION contract (v18.27,
                    D199): every non-bridge section integrates at
                    position ZERO with its entry HEADING stripped.
                    TWO-TIER pieces (v19.0, D221/D222) generate with
                    their DESIGN pitch/roll deltas composed in
                    CANONICAL space (designCanon — designPose's axis
                    math rotated by −psi, heading-equivariant, so psi
                    stays additive: x/y/z + yaw moves stay
                    bitwise-rigid while design edits re-solve) and
                    rigid-tilt the design→true gap (conflictTilt:
                    design (0,0) = tilt()'s historic arithmetic
                    bitwise, design == true = the IDENTITY object;
                    a LIVE conflict's yaw moves keep the D100
                    tilted-piece ulp dust — documented limit).
                    pieceHead mirrors the branch for bridge captures.
                    Strict followers take the D135 freeze at their
                    DESIGN pose since v19.18 (D247 — a follower with
                    design deltas generates from designPose()/
                    designCanon; zero deltas keep the historic path
                    bitwise, pinned by SeamShapeGate's recorded
                    pre-D247 sample checksums; D222 had walked back
                    D221's linked-follower branch and declared both
                    tiers dead — superseded).
                    Heading-stripping works because rotation about Y
                    + translation commute with gravity exactly; every
                    section places rigidly (placeLine;
                    rotY keeps the y column bitwise); the chain's
                    heading accumulates ADDITIVELY (CanonPose —
                    never re-extracted from composed world
                    orientations) and Pass D reads run-relative
                    ENERGY HEIGHTS chained from the local bits, so
                    piece x/y/z + yaw moves are BITWISE-rigid even
                    across a chaotic stall knot (gated DetermGate199
                    on the user's knot file: Y+50 → 0 rewrites +
                    heights exactly +50; was 9 rewrites + 561 m).
                    Placement rigidity deliberately ends at BRIDGES
                    (world-anchored re-solve), held-angle strict
                    followers re-aim under upstream yaw (accepted),
                    and tilted pieces / smoothing windows keep
                    world-delta trueV dust. Spans carry the chain's
                    exact (psi, canon) at each boundary — plus the
                    piece's exitTilt since v19.1 (D224; the pair is
                    ALWAYS stored now): splitPiece copies the pair
                    onto the new piece so splits stay bitwise (the
                    D71 rule), and a TILTED parent (conflict tilt or
                    follower re-aim) hands the new piece the DESIGN
                    chain as baseOrient + the tilt as its own true-
                    tier deltas (decomposeNear) — the track stays put
                    to dust and the D100 "splits re-solve" corner is
                    closed — see TrackPiece.
                    Also the
                    STRICT-SEAM SMOOTHING pass (v17.2, D139:
                    smoothStrictSeam runs right after a follower's
                    first section lands — orientations SLERP from
                    the upstream curve onto the follower's across a
                    ±sSmoothLen/2 arc window, sides extrapolated at
                    their measured per-ARC rates (v18.31, D203 —
                    per-INDEX exponents stepped the blended field's
                    per-arc rate by the design-speed ratio at ≠v
                    seams: an instant TRUE-felt jump no window
                    length touched; equal cadence = old to dust),
                    quintic weight; positions
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
                    ONE-PASS HYBRID (v19.7, D236 — user re-spec,
                    REPLACING the v15.0/D98 two-pass and its D235
                    split anchor: the two-pass replayed the ref's
                    rates in whatever frame the deltas had left the
                    track in — residual laterals after every delta
                    window, leftover conflicts, and the anchor
                    existed only to pin the ref frame for splits):
                    ONE integration of the section's OWN mode with
                    the delta lanes injected PER RK4 STAGE at the
                    current state — force sections' Δpitch/Δyaw
                    join Ω (Options.dInfl/dInflYpGlobal,
                    GeoIntegrator.inflOmega), geo sections' ΔGv/ΔGl
                    become Δω = ±g·ΔG/v (GeoIntegrator's dInfl
                    overloads) — so a force section keeps re-solving
                    its authored targets against gravity LIVE and
                    zero deltas mean the plain solve exactly: felt
                    == authored, no residual anything (his "Odd
                    influence behavior" file, 0.0 gated). The felt
                    records add the deltas' contribution
                    analytically, so resulting == base + delta
                    EXACTLY by linearity; baseCurves := resulting −
                    delta on the section's OWN axis (no t* clock
                    exists). vAuthored is INERT; the influenced
                    shape depends on vDesign like any section (the
                    two-pass's operating-speed invariance is gone
                    by design); splits are exact by the Markov
                    property (no anchor); all-zero deltas take the
                    plain path BITWISE; one Ctl drives the single
                    solve. baseCurves
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
                    WITHIN section SPANS since v18.35 D207 (was
                    per RUN, v13.10 D84 — a detached piece's
                    frames poisoned run-end rates): one-sided ±1
                    windows at span edges keep seam-adjacent
                    rates CLEAN — a seam jump lives BETWEEN
                    samples (the carets' job), never smeared, so
                    the graph editor draws to the boundary with
                    no trim band; single-section docs bitwise). A
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
                    round-trip tested at last. 1-NODE lanes LOAD
                    since v18.24 (D196 — splitLanes' hold half is a
                    legal held constant, which the loader's v9.1
                    ≥2-node check refused: split-then-saved files
                    wouldn't reopen). FAIL-SOFT since v18.44
                    (D216): damaged lines skip per line, empty
                    lanes HEAL to a held constant, element-less
                    s/c sections and sectionless pieces DROP —
                    all counted in LoadResult.warnings (loadDesign
                    reports + marks dirty); only a document with
                    NOTHING left refuses ("no track data"),
                    cleanly BEFORE the live doc mutates. A UTF-8
                    BOM header loads; the `camera` cosmetic line
                    (v18.38, D210) carries the fly/orbit pose.
                    BIT-EXACT saves since v18.26
                    (D198): geometry-feeding numbers write
                    Double.toString (helper d()) so reload
                    compiles bitwise == the session; load clamps
                    admit everything the app writes (vDesign
                    [0.1, 1e4] since D216 — the reconcile floor
                    AND its uncapped top — piece
                    coords ±1e6). See the DETERMINISM CONTRACT
                    in Known quirks. D248/D249 fields:
                    `porient locked pd rd affect mode` — 5 fields since
                    v19.20 D249 (field 5 = the OrientMode ordinal, the
                    authority when it parses 0..2, fail-soft to field 1;
                    field 4 = affectSubsequentSeams; the loader inverts a
                    4-field D248 'only' line by its FIELD COUNT; 3 fields
                    = no carry; missing LINE = LOCKSTEP + (0,0) + no
                    carry — see TrackPiece),
                    `lane k f` 3rd = the END-FOLLOW flag (missing =
                    derived in finishCoaster from the pristine-hold
                    shape AT the section end; old readers ignore it).

GraphEditor.java    JavaFX GRAPH EDITOR (split out v9.2, D52): the
                    force-curve canvas — lanes, selection, node/handle
                    drags, document undo stacks, node dialog. Reaches
                    the shell through a `final FvdDesigner app` backref
                    (app.rebuildTrack(), app.settings, …). redraw()
                    fires app.fireModelHooks() after its redrawHooks
                    loop (D247 live windows); the node editor sits in
                    both lists. v19.19 (D248): the NAMING wrappers
                    sectionTitle / junctionOrdinal / junctionTitle /
                    ownerButtonLabel / seamTitle on SectionNames;
                    labelItem (the CustomMenuItem + Label pattern
                    factored out of grayItem — the badge menu's BOLD
                    inert title row — its D248 sectionInfoText tooltip is
                    GONE since v19.20 D249) + the measured badge width
                    (badgeWidth — the D248 width LADDER / short form went
                    with D249's revert to the bare type word); openOwnerWindow
                    (the Orientation dialog's owner button); the strict
                    seam WINDOW (showStrictSeamSettings on LiveWindow,
                    index-resolved, seamSync hook, hideJunctionWindows
                    before index-shifting ops); the BRIDGE CONTEXT
                    withBridge/bridgePieceCtx (scopes the active-section
                    bridge API to a piece's trailing bridge — the bridge
                    window keeps editing ITS bridge across activation
                    changes; bridgeTargetWrite = the one routed target
                    write tail); the END-FOLLOW sinks (touchLane /
                    touchActive / syncFollowingEnds); defaultLane (lane 0
                    on create / '+' / Insert); openNodeEditorFromViewport
                    (marker double-click); the run-as-piece selector /
                    '+' lists (runPickWhy / moveRefuseWhy, WIZARD_OPEN_WHY
                    / ORPHAN_BLOCK_WHY / ORPHAN_END_WHY). v19.20 (D249):
                    takePlotSelection / spTakeStripSelection (THE selection
                    choke — node and speed-control selection mutually
                    exclusive; the options popup is NOT a selection),
                    seamEditDesign / gizmoSetSeamDesignAll (the seam's
                    DESIGN-tier writes), bridgeTargetWrite covering both
                    optimize modes + setBridgeLock as the ONE lock choke
                    for both windows, setBridgeOptMode's DESIGN_ONLY belt,
                    seamDiaYAt's caret dodge, SEAM_SMOOTH_MAX 100,
                    showInitSpeedDialog LIVE (a LiveWindow), the strip's
                    display-domain snap (spSnapNative → SpeedStrip.
                    snapDisplay) + spPosReadout.

Viewport3D.java     JavaFX 3D VIEW (split out v9.4, D54): orbit/fly/POV
                    cameras + pseudo-ortho views, mouse/scroll handlers,
                    animation timers, rail/tie/heartline meshes, ground
                    grid + LOD, track picking, node markers, and
                    rebuildMeshes (the per-pane half of the pipeline
                    — FvdDesigner.rebuildTrack owns the compile
                    since v18.10). Same `app` backref pattern.
                    VIEWPORT DISKS since v19.19 (D248, item S1):
                    ctlDiskGroup — a draggable disk per
                    speed-control START/END on the heartline
                    (pickCtlDisk 12 px at press, diskHover →
                    combLabel "t = 3.20 s" / "s = 12.5 m" since v19.20
                    D249 — no prefix, LIVE during the disk drag, the
                    display domain for a section in the run; the pick
                    region = the projected RIM's bounding radius
                    floored at 12 px, disks 2× thicker, the selected
                    ring lightened 1.18 not white —
                    trackNativeAt = nearest projected track point →
                    ViewDisks.nativeAtArc → the strip's
                    spViewportPress / DragTo / Release; darker type
                    color, the selected control's full color + white
                    rim; hidden in POV) + boundaryGroup — light-gray
                    mouse-transparent disks at every section
                    boundary — since v19.20 D249 ONLY where the graph
                    draws a single seam line: plain boundaries inside
                    a piece incl. the entry INTO a bridge section,
                    never strict seams / bridge exits / run heads or
                    ends (ViewDisks.boundarySamples, rebuilt with the
                    markers; BOUNDARY_R 0.34 / BOUNDARY_HT 0.02 /
                    BOUNDARY_COLOR rgb(105,110,122) / glow 0.30); every
                    rig-part press deselects the graph nodes (through
                    GraphEditor.takePlotSelection since D249, which
                    also unlights a selected control); node 0's marker
                    hides under a JUNCTION BALL (junctionBallCoversNode0:
                    run heads, bridged-in pieces, followers whose seam
                    ball exists).
                    Since v16.11 (D123, reworked v16.12 D124) also
                    the PLACEMENT GIZMOS (revealed by a white ball
                    on EVERY junction of the run since v19.18 D247
                    — record Reveal(piece, kind); piece entry +
                    trailing bridge end as independent Rigs;
                    locked axes build no part;
                    ABSOLUTE-from-press drags — arrows project onto
                    the press-time screen axis, rings rotate by
                    linear mouse distance, world delta = NEGATED
                    scene delta under the Y-mirror), the middle-drag
                    ORBIT (left = selection only), white
                    ACTIVE-SECTION ties (TiePiece re-tint, self-lit;
                    the white ties AND the inactive-piece
                    desaturation both gate on the View menu's
                    "Visualize track selection" since v17.10 D149)
                    and clickable node markers + double-click insert
                    (a marker DOUBLE-CLICK opens its node editor since
                    v19.19 D248 — the marker pick runs before the insert
                    branch).
                    v16.13 (D125) adds the track COMBS (vertical/
                    lateral felt G + true speed, hover readout via
                    combLabel) and per-piece heartline strands;
                    v16.14 (D126) makes tie/comb placement arc-EXACT
                    (arcHit) and the combs a three-way heartline-
                    rooted, POV-hidden, mouse-transparent unit.
                    v18.1 (D167): the POV camera INTERPOLATES the
                    heartline frame at the exact povTime (povPoseAt
                    — it snapped to the 0.01 s grid, juddering at
                    Ctrl ×0.25; since v18.22 D193 the ride VALUES
                    interpolate too — FvdDesigner.povBracket/
                    trueVAt/designVAt feed the stats line and the
                    advance ratio, ending the stall-approach speed
                    stair on tilted/drifted sections whose design-
                    spaced samples sit decimeters apart) and rail
                    tubes tessellate by TURN
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
                    a Reveal(piece, kind) record since D247 with a
                    run-membership reset + revealEntryGizmo for
                    Copy; see the PLACEMENT GIZMOS paragraph), the
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
                    v18.11 (D182): new panes spawn in FLY/ORBIT —
                    CENTERED ON THE COASTER since v18.46 (D218,
                    frameCoasterPose: bbox center + default angles +
                    FOV-fitted distance — 0.8× fit + STARTUP/New
                    framing too since v18.48 D220 — via
                    applyCameraWorld, which
                    also re-follows the LOD floor grid — the D182
                    spawner-pose SCENE-coord copy was wrong across
                    differently-centered worlds and left the grid at
                    mid-track, the "no grid until moved" bug; every
                    programmatic camera jump routes through
                    applyCameraWorld now); POV direction is
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
                    (Rig.onDouble; openPlacementPopup(forceBridge) —
                    always a FRESH window since D248 — overrides the
                    active-section branch); fixed
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
                    v19.19 (D248): the SEE-THROUGH FLOOR — its D248
                    depth-tested build (b′) was RETIRED in v19.20 D249:
                    the floor is the D242-D246 depth-disabled first-drawn
                    build again and the underground look comes from the
                    GROUND-SPLIT track layer — world order [trackUnder,
                    ghostUnder] → [gridGroup, axisGroup] → [trackGroup,
                    povHolder, trackBallGroup] → [ghostGroup], the two
                    track blocks swapping around the floor while the eye
                    is below ground (setFloorOrder; see the 3D view floor
                    paragraph); the SCREEN-SPACE GIZMO PICKER (GPart +
                    gizmoMouseFilter — ONE nearest-by-px pass since D249
                    with the center ball measured from its silhouette
                    EDGE (Gestures.ballEdgeDist) and ties going to a ball,
                    arrows ≤ 9 px / rings ≤ 6 px; gizmoSub PERMANENTLY
                    mouse-transparent; -Dfvd.pickprobe=1);
                    revealDuringDrag hides the pressed ball mid-gesture;
                    isGizmoDragging = past the deadband OR a part
                    pressed (the shell's mid-drag modifier guard);
                    MARKER_R 0.22 / MARKER_R_SEL 0.26 / MARKER_SEL_MIX 0.4
                    (the selected marker is a pale lane-colored sphere,
                    not white); dropGizmoReveal before a run move.

Dialogs.java        JavaFX DIALOGS (split out v9.4, D54): Help,
                    Shortcuts, Rail colors, Options + option-row
                    helpers. Menu-local undo machinery stays on the
                    shell (the Coaster menu shares it).

FvdDesigner.java    the SHELL (~1.1k lines): document + file lifecycle
                    (New/Save/dirty title), key dispatch, menu bar,
                    the LIVE-WINDOW registry (v19.18, D247:
                    modelHooks + fireModelHooks — fired LAST in
                    rebuildTrack and inside its early returns;
                    docRestoring spans applyDocState +
                    rebuildTrackRestoring so undo outranks typing;
                    setUnlessTyping/typing; convertBusy() +
                    CONVERT_BUSY_MSG = the density-preview
                    transaction gate; placementTargetIdx/Ref = the
                    Track Piece panel's run-head rule),
                    toolbar, layout, menu-local undo, NL2 export dialog
                    (+ the "Track piece" picker, v19.1 D223 — a piece
                    exports its own sample range incl. the seam
                    sample). Also the START WIZARD glue (v19.1, D226:
                    showStartWizard / wizardCreatePiece / wizardPlace /
                    wizardReplaceSection / wizardSetSpeed /
                    wizardSetFriction / wizardDiscardPiece /
                    wizardFinish, trackHidden, activePieceEnd — the RUN
                    TAIL's end since v19.19 D248, Coaster.runTailEnd) and the
                    `-Dfvd.devshot=<dir>` DEV HOOK (devShots — renders
                    the start screen's three states to PNG and exits).
                    THE LIVE WINDOWS (v19.19, D248): openTrackPieceWindow /
                    closeTrackPieceWindow (showPlacementPopup = the toolbar
                    TOGGLE: open = close without revert), openPlacementPopup
                    (boolean, fresh), openBridgeWindow(bp) / toggleBridgeWindow
                    / closeBridgeWindow ("Strict Seam n (bridge)", the bridge
                    addressed by PIECE, bridgeWinPiece), the open-time
                    TpRecord (Cancel / ↺; re-captured on activation change),
                    syncDesignReadout (the derived rows of both windows),
                    syncJunctionButton (the ONE junction button "Strict
                    Seam n ▴" / "Strict Seam n (bridge) ▴", orphan grayed
                    "This bridge has no following piece."), placementTargetIdx
                    = ALWAYS the run head, isWizardPiece (the open wizard's
                    piece, picked or not), runLengthLabel, isGizmoDragging
                    (mid-drag modifier guard), the `-Dfvd.winshot=<dir>`
                    (renders the three windows) and `-Dfvd.pickprobe=1`
                    (drives the gizmo picker) dev hooks; the gridshot ladder
                    gained an underground fixture (15 poses). v19.20 (D249):
                    APP_NAME "Ultimate Vector Design" (the window / Help
                    titles), cancelRestore + the TpRecord/BrRecord
                    PlacementSnapshot (the windows' full-scope Cancel),
                    pieceOrientWrite (a bridged-in piece's Track Piece
                    orientation rows → the bridge's write tail), pyWhy (the
                    PY radio's live gray), the Delete… button + confirm
                    dialog (Coaster.deleteRun), BRIDGE_LOCK_TIP /
                    BRIDGE_ALIGNS_AXIS / FREE_LOCK_TIP, the four anchor
                    tooltips, INTEGRATE_DT read off Coaster, and the
                    `-Dfvd.gridshot.sweep=<dir>` hook (the ladder's
                    temporal twin with a per-frame line-pixel count).

StartPanel.java     THE START SCREEN (v19.1 D226; reworked v19.2
                    D231; polish v19.3 D232): a JavaFX overlay the
                    GraphEditor hosts over its canvas (showStartPanel
                    / hideStartPanel / startPanelActive — graph keys
                    dead while up except undo/redo/save, text boxes
                    keep theirs). ONE form: Placement | Coaster/Speed
                    (+ name + friction for a NEW_COASTER) | Section
                    Type — the two bare tiles "Force/Geometric"
                    (FIRST, selected by default since D232: the
                    placeholder 5 s force section IS the pick, the
                    track is visible from open, Done always works)
                    and "Straight / Curve"; the lane options in a
                    TWO-COLUMN grid (Type+Yaw/pitch axis, Domain+Roll
                    axis, Rate units+Roll behavior) + the INITIAL
                    SIZE spinner (v19.7, D236: the first section's
                    length in the surface's own units — label "(s)"/
                    "(m)"; PER-DOMAIN memory in the WizardState
                    record's lenTime/lenDist, s/c shares meters — a
                    6 s edit survives a domain round-trip;
                    wizardWithLen folds every built section's length
                    back into the record; the seam menu's Insert
                    dialog mirrors the spinner as "Initial length"
                    with SESSION memory GraphEditor.insLenTime/
                    insLenDist since D247). Modes NEW_COASTER
                    (File > New's first coaster, Coaster ▸ New
                    Coaster…) and FLOATING (⊕ > "Add floating
                    piece…" — renamed from "…section…" in v19.10
                    D239 — 5 m past the active piece's end at its
                    heading + true speed; a ZERO-piece coaster —
                    its last piece moved away via Switch Coaster —
                    shows a centered "Add Floating Piece" button
                    over the blank plot instead (D239,
                    GraphEditor.setEmptyPiece, synced by
                    syncNoCoasterPanel + rebuildTrack), the same
                    route into this wizard, whose no-piece fallback
                    placement is origin / y 5 / 5 m/s — WORKING
                    since v19.11 (D240: setActivePiece's bare
                    same-index return skipped the lane re-point on
                    a zero-piece coaster — NO_LANES stayed under a
                    ForceSection and redraw threw before the panel
                    could show; the guard now also requires
                    app.lanes == lanesFor(...), the
                    setActiveSection rule, and BezierBaker's
                    rideEnd/bakeLane/evalPolyline no-op on empty
                    lanes as hardening). The wizard's record
                    (FvdDesigner.WizardState) RIDES THE DOCUMENT
                    (DocState.wizard): every control change is a
                    document undo step through FvdDesigner.wizard*,
                    Ctrl+Z right after opening removes the piece/
                    coaster — and undo INTO any wizard-era snapshot
                    RE-SHOWS the panel since v19.7 (D236:
                    applyDocState sets wizardOpen = wizard != null,
                    so Ctrl+Z right after Create re-opens with the
                    picks intact). The panel is a VIEW (syncFromModel
                    after every restore — IDEMPOTENT and never
                    re-parenting a node since v19.20 D249: the lane grid
                    is built ONCE (laneGrid) and only selections/disables
                    change in place; the D247 wizardHook runs it on every
                    redraw, and a re-created parent between a radio's
                    press and release disarmed the radio — the "wizard
                    radios do nothing" bug; syncGray saves/restores
                    muted); syncWizardPanel shows it only while
                    its coaster (and, floating, its piece) is active
                    and the session flag wizardOpen holds (Done/Cancel
                    clear it) — and while it SHOWS, the Track Piece
                    + junction buttons gray (grayHint) and
                    openTrackPieceWindow / openBridgeWindow refuse with a
                    stats reason (every route — ball
                    double-clicks included): the wizard's Placement
                    rows own the piece, and the 3D gizmos write into
                    them LIVE (placementEdit ends with
                    wizardPanel.syncFromModel, D236). The editor pins
                    minHeight to the panel's REAL pref height since
                    v19.12 (D241 — max(MIN_HEIGHT 240, prefHeight):
                    the fixed 240 dated from D232's 3-row grid; the
                    D237/D238 rows grew content to ~279 px and the
                    CENTERED title rendered half above the dark
                    background — the content box is TOP_CENTER now
                    so overflow can only ever eat the bottom)
                    (growEditorTo). Yaw/pitch/roll write BOTH tiers
                    (no lock conflict) — and the wizard PIECE
                    creates with shapeLocked=false + OPEN pitch/
                    roll padlocks (v19.8, D237: the entry gizmo's
                    rings build and edit both tiers; Done restores
                    the default locks). wizardFinish/wizardCancel
                    end with syncWizardPanel (D237 — bypassing it
                    left the Track Piece button stuck disabled,
                    the "settings not selectable after Create"
                    bug).

SectionNames.java   SECTION + JUNCTION NAMING (v19.19, D248). Pure
                    Java, no JavaFX (LabelGate 103 pins every string):
                    typeWord/shortWord, tags (" !" " ·m" " ◀" " fx" in
                    order), sectionOrdinal/sectionTitle/shortTitle —
                    per-TYPE counters across the WHOLE RUN in run order
                    ("Force Section 1", "Geometric Section 2", "S/C
                    Section 1", "Bridge Section 1"; short "Geo 2";
                    numbering restarts per run), junctionOrdinal/
                    junctionTitle — plain strict seams AND bridge exits
                    counted together ("Strict Seam n" / "Strict Seam n
                    (bridge)", the bridge test FIRST so a stale
                    followPrev is inert), ownerButtonLabel — EXACTLY the
                    toolbar text owning a section's placement ("Track
                    Piece ▴" / "Strict Seam n ▴" / "Strict Seam n
                    (bridge) ▴"; a bridge section = its own exit). v19.20
                    (D249): badgeLabel = the DRAWN badge's text — the bare
                    type word + spaced tags ("Geo ·m", "Force ·m ◀ fx",
                    "Bridge !"; the D248 numbered / short forms are gone),
                    BRIDGE_SETTINGS_ITEM "Bridge settings…" and
                    bridgeWindowTitle "Bridge settings (Strict Seam n)".

LiveWindow.java     THE SHARED LIVE-WINDOW BUILDER (v19.19, D248).
                    JavaFX, no model: show/place — an OWNED light Stage
                    whose bottom edge sits at the graph canvas top, left
                    at an anchor x (toolbar button / seam strip / node /
                    gizmo anchor), clamped into Screen.getVisualBounds
                    (PLACED BEFORE SHOW since v19.20 D249 — applyCss +
                    resize(pref) + layout() pre-show, the frame from the
                    cached decoration insets, setX/setY then show; the
                    layout step is load-bearing: a Labeled measured
                    before its first layout caches a clipped string —
                    setOnShown stays the > 0.5 px residual corrector;
                    D248's opacity-0 show + onShown move flashed the
                    native frame at the owner-centered spot); Confirm/Enter/X
                    keep (the focused box commits first), Cancel/Esc =
                    the caller's field-level revert; installDocUndoKeys +
                    installViewKeys; the row factories orientRow /
                    labeledRow / lockToggle (bridge-style red/green
                    padlock) / revertBtn (small ↺) / designReadout (grey
                    "design 12.34°" / "real 12.34°" — the click-swappable
                    Readout since v19.20 D249) / thinSeparator / grayNote /
                    flagRow (since D249 the MODE ROW: CheckBox "Edit Real
                    and Design Orientation Independently" + radios "Edit
                    real orientation (locks track shape)" / "Edit design
                    orientation" + "Affect Subsequent Strict Seams", ONE
                    writer, settleTyping before a flip; D248's Lock Track
                    Shape + Only-Affect row is gone) / resolveRow (Design ↔
                    Real) keep the Track Piece, bridge and seam windows
                    identical; the node editor takes place() too.

Gestures.java       PURE gesture arithmetic (v19.19, D248;
                    GestureGate 108 — 49 of them item G's, section 7
                    = item S1's disks, + D249's ballEdgeDist/ballEdgeBand,
                    the gizmo pick metric): step/nudge (arrow nudges — Shift a
                    tenth, Ctrl the NEXT round multiple from the current
                    value against the editor's per-axis references),
                    continueOut/matchInToOut (Append's C1 slope carry),
                    resizeStart (the speed-control START diamond as a
                    resize from the left), edgeWindowLen (the one-sided
                    smoothing band edge), clampInitSpeed (INIT_SPEED_MIN/
                    MAX 0.1 / 1e4, the D198 band).

FloorDepth.java     THE FLOOR'S PURE ARITHMETIC (v19.19 D248, RE-BASED
                    v19.20 D249; FloorGate 18): the RING_EDGE / AXIS_SEG /
                    AXIS_ANCHOR_Q 30 ladders, ringStrength (the D242-D246
                    strength law, bitwise) and — since D249 — the
                    GROUND-SPLIT routing of the track layer: underMask /
                    faceWorldY / splitCounts (a face goes UNDER iff its
                    centroid's world y < −GROUND_DEADBAND 0.07 = the rail
                    radius; straddlers by centroid, ties toward ABOVE),
                    eyeBelowGround (±0 = above). The D248 lift API
                    (FLOOR_LSB_MARGIN / DEPTH_BITS / lsbK / liftAt /
                    shear / ranks / ORTHO_AXIS_LIFT) is RETIRED — the
                    header carries the POSTMORTEM and the standing rule:
                    no depth-tested coplanar floor layer without a
                    MEASURED platform envelope + a screen-float budget.

ViewDisks.java      VIEWPORT-DISK arithmetic (v19.19, D248 item S1;
                    GestureGate section 7): arcAtNative /
                    nativeAtArc — a speed control's native x ↔ the
                    global arc (the plates' historic arcAtTime moved
                    here; spanLo lands a continuation's x = 0 ON the
                    seam sample), nearestOnPolyline (the drag's
                    screen-space mapping, no radius cap, NaN
                    vertices skipped), strideIndices (always ends on
                    the last sample), boundarySamples (every section's
                    exit except the last — plain interior boundaries
                    only since v19.20 D249 —, deduped by index and
                    DEDUPE_M 1e-6 m), rimPoints / pickRadiusPx /
                    pickScore (D249 — the whole-disk pick: the projected
                    rim's bounding radius floored at PICK_PX, normalized
                    d²/pickR² scoring); the disk constants CTL_DISK_R
                    0.26 / CTL_DISK_HT 0.07 / CTL_RING_R 0.30 / CTL_RING_HT
                    0.03 (both ×2 in D249) / CTL_SEL_RING_LIGHTEN 1.18 /
                    BOUNDARY_R 0.34 / BOUNDARY_HT 0.02 / PICK_PX 12.

SpeedStrip.java     SPEED-STRIP design-line arithmetic (v19.19, D248
                    item S2; StripGate 49): split (|yDesign − yTrue|
                    > TRUE_SPLIT_PX, NaN hidden), fitHi (design
                    joins the scale fit), designSuffix (" (design
                    12.34)"), the line styles TRUE_W 1.6 / TRUE_DASH
                    (3, 4) / TRUE_RGB (160,250,170) and DESIGN_W 0.8
                    / DESIGN_DASH (2, 2.67) — the old true style /
                    1.5; + v19.20 D249: snapDisplay (the display-axis
                    Ctrl snap on the section-local absolute grid, a0 = 0
                    == the historic native snap), posReadout / posValue
                    ("t = 3.20 s" / "s = 12.5 m", Locale.US, z3),
                    diamondAt (the draw-time hover re-resolution).

PlacementSnapshot.java  THE FULL-SCOPE PLACEMENT RECORD (v19.20, D249).
                    Pure Java: capture(Coaster) records every piece's
                    placement fields (x/y/z/yaw/both tiers/baseOrient/
                    the canonical pair/orientMode/affectSubsequentSeams/
                    uiLocks/followPrev/seam locks + held angles/
                    sSmoothLen) and every trailing bridge's optMode/
                    optLen/lastOptDist/lenA/lenB/locks/noneStash/optSig
                    (SIG_STALE / null identities kept); differs() /
                    restore() = DIRECT field writes BY OBJECT IDENTITY
                    (never placementEdit — the carry is not an exact
                    inverse), vanished pieces skipped, appended ones
                    untouched, a replaced bridge object skipped; the
                    three live windows' Cancel/Esc (FvdDesigner.
                    cancelRestore). OrientModeGate (h).
```

MULTI-COASTER DOCUMENT (v19.2, D230): FvdDesigner.coasters is the
file, `coaster` the ACTIVE one (re-pointed on switch; every path
reads it live); Coaster.name + Coaster.look (an inactive coaster's
stashed render look — the active one's lives in the shell fields,
swapped by stashActiveLook/applyLook). ZERO coasters is legal:
`coaster` sits on an empty placeholder, hasCoaster() gates, the
editor shows its no-coaster panel. Inactive coasters render in their
SOLID rail color (Viewport3D.buildOtherCoasters, compiles cached per
coaster until it is next active). DocState = all coasters + active
index + the wizard record. IO: one coaster = FVDD3 (+ `name` line),
otherwise FVDD4 with `ncoasters` + `coaster <name>` blocks
(DesignIO.saveAll; LoadResult carries lists). The COASTER MENU is
restructured since v19.3 (D232): selector — the "Coaster:
[ChoiceBox]" row (restored v19.5 D234; a ChoiceBox works FINE
inside a menu popup — Robot-verified end to end via the
-Dfvd.devmenu=1 hook. The REAL D232 bug: the menu had NO items
until onShown, and an EMPTY Menu never opens its popup at all —
the Help-menu trick backwards — so it could never populate;
rebuildCoasterMenu now runs at construction + onShowing. D233's
submenu + its "focus grab" lesson were a misdiagnosis, expunged;
Menu.getStyleableNode() is null on MenuBar menus — lookup
".menu-button" instead) · "Coaster
Options…" (modeless dialog: rename + friction rows + statistics;
grayed via grayItem while ITS coaster is still in the start
wizard, v19.7 D236) · "Rail
Settings…" (DIRECTLY under Options since v19.7 D236, user spec —
modeless dialog: rail-color radios/swatch/G-Force
Colors… + heartline + gauge + tie spacing — the future track-TYPE
home; Dialogs.railSettingsContent superseded railColorItems) ·
"New Coaster…" · "Delete Coaster" (name-confirm). A plain 3D
click on an INACTIVE coaster's track offers "Switch to editing
…?" (v19.7 D236 — Viewport3D.pickOtherCoaster over
otherCoasterRenders + a small dark Switch/Cancel popup; Switch =
switchCoaster + the clicked piece/section activated; meshes stay
mouse-transparent, the pick is screen-space). The
settings ROWS are shell fields re-parenting per dialog open; both
dialogs take installDocUndoKeys. Export has a Coaster dropdown.
UNDO KEYS RULE (D227): every window that can change the track routes
Ctrl+Z / Ctrl+Shift+Z to the GLOBAL history (installDocUndoKeys —
Track Piece/Bridge panel, seam pop-out, node editor, speed popup,
orientation/add-section/element/initial-speed dialogs, Coaster
menu); installMenuUndoKeys (menu-local) is for cosmetic/settings
menus only (View, Snapping, colors, Export). Live panels resolve
pieces BY INDEX and re-read after restores (GraphEditor.
docRestoreHooks; the seam pop-out rebuilds in place). NULL-BEFORE-HIDE
(v19.18, D247 — STANDING, the seam undo bug's root cause): a popup's
field is nulled BEFORE hide() and every handler carries a `pop !=
field` identity guard — hiding un-focuses the auto-focused editor,
whose focus-loss commit re-parsed the rounded "#.##" text and ran as
a user edit on the ORPHANED piece (pushUndo + redoStack.clear());
hideSeamPopup / spHideCtlPopup / clearPv / the panel toggle all do it.
FvdDesigner.commitTyped treats UNTOUCHED editor text (== the
converter's rendering of the current value, isUntouchedEditor) as a
no-op, so a merely clicked-into box never becomes an edit (D212
blank→0 kept); a window's Apply commits a typed-but-unentered box
explicitly (commitFocusedNumericEditor), Cancel discards it.

HEADLESS GATES live in the repo folder `gates/` since v19.1 (D223 —
the session scratchpads that held them were purged by Windows Temp
cleanup; recovered from transcripts): `javac -cp target/classes -d
gates/out gates/*.java` then `java -cp "target/classes;gates/out" X`.
Run DetermGate197/198/199/200 + OrientGate/OrientGate2 (+
InflSplitGate/ReanchorGate/SmoothRealGate for influence, splits,
deletes or seams; + uvd.SeamShapeGate / RangeResolveGate /
CopyGate / MergeGate / InsertGate since D247 for followers' design
tier, range resolves, Copy, Connect and Insert — gates/README.md
lists the whole core battery) for anything
touching compile, reconcile, IO or placement.

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
  Path spinners wire through wireDocSpinner (snapshot pre-apply —
  ONE history since D227: no menu-local twin; docApplying suppresses
  listeners during undo application). Since v19.2 (D230) a snapshot
  holds EVERY coaster of the document (CoasterState) + the active
  coaster + the start wizard's record; live panels resolve pieces
  by index and re-read after restores (D227). Known granularity:
  node-0 dialog editing speed + values = two undo steps.
- **Save format** `FVDD3` (D50, written by DesignIO; `FVDD4` for
  multi-coaster documents since v19.2 D230 — `ncoasters N`, camera,
  then `coaster <name>` blocks each holding the FVDD3 body; a single
  coaster still writes FVDD3 plus a `name` line; zero coasters is a
  legal FVDD4 file): human-readable
  text, `Locale.US` decimals (critical — default locale breaks parsing
  on comma-decimal systems), unknown lines ignored for forward
  compatibility, extra fields on known lines ignored (the straight-lock
  8th and level-lock 9th `n` fields ride on this). GEOMETRY-FEEDING
  numbers write BIT-EXACTLY since v18.26 (D198, superseding
  D197's %.10f: Double.toString via DesignIO.d — the shortest
  decimal reparsing to the same double, so a reload compiles
  BITWISE-identically even inside a chaotic stall knot, where
  %.10f's 5e-11 dust flipped knife edges; cosmetics stay %.4f,
  old files load unchanged, E-notation parses fine). LOAD CLAMPS
  must ADMIT every value the app itself writes (D198): section
  vDesign [0.1, 1000] (the reconcile's stall floor saved as
  exactly 0.1 and the old [1,100] clamp silently made it 1.0 —
  10× the knot curvature, the user's reload divergence), infl
  same, piece x/y/z ±1e6 (the bridge optimizer marches
  placements past ±500). Never add a load clamp tighter than
  the writers' range. Global lines (v0,
  `friction mu kair` — the Phase 3 coefficients, v17.0 D133, missing =
  0 0 = loss-free; NEW documents write 0.03 / 1.0e-4 ≈ NL2 since
  v18.0 D161,
  `camera px py pz yaw pitch dist` — the primary pane's fly/orbit
  pose in world coords, v18.38 D210, %.4f cosmetic; missing = the
  view stays put (every pre-v18.38 file),
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
  orientation, v12.1; missing = identity; bit-exact writes keep the
  loader's re-normalize silent; fields 11-14 = the STRICT-SEAM follow
  flag + its three locks, v17.1 D135, missing = detached; fields
  15-17 = the discontinuous axis angles, D136; field 18 = the seam
  SMOOTHING length (m), v17.2 D139, missing = 0; fields 19-23 = the
  CANONICAL generation pair — heading + heading-stripped quat,
  v18.27 D199, written only when a split stored it, missing =
  decompose baseOrient; a `porient locked designPitch designRoll affect mode`
  LINE follows every piece line since v19.0 D221/D222 — field 1
  = the D222 lock bit (1 = REAL_ONLY, kept for old readers) +
  the DESIGN-tier deltas (Double.toString: they shape the
  track) + field 4 = affectSubsequentSeams + field 5 = the
  OrientMode ordinal (v19.20 D249 — the authority when it
  parses 0..2, fail-soft to field 1; the loader reads a
  4-field line as D248's 'Only Affect' bit and INVERTS it, a
  3-field line as no carry), its own line because fields
  19-23 are conditional; missing LINE = LOCKSTEP + (0,0) + no
  carry (the compile reads none of it, the track stays
  bitwise); the uncommitted D221 interim 2-field form
  migrates on load) holding `section force|geo|sc|bridge
  vDesign domain
  reversed` blocks (the domain field — written since Phase 1 — is
  HONORED for force/geo since v16.0 D103: "distance" = lane x in
  meters; every old file says "time" there and loads unchanged;
  field 8 = ratePerMeter, v16.6 D115, missing = °/s; field 9 =
  rollAbsolute, v16.7 D118, missing = rate; field 10 = ypAbsolute,
  v19.8 D237, missing = rate;
  the cross-domain VIEW flag is deliberately NOT saved) holding
  `lane k f`/`n` lines (force/geo; f = the per-lane END-FOLLOW
  flag since v19.19 D248 — old readers ignore it, missing =
  derived from the pristine-hold shape at the section end; an
  INFLUENCED section, v15.0 D98, appends `infl vApply [rollG ypG]`
  (the short-lived D235 anchor fields 4-8 are IGNORED on load and
  never written since v19.7 D236 — the hybrid needs no anchor) —
  the OPERATING speed + the v16.5 influence axis flags (missing =
  local); the section line keeps the AUTHORED speed so old
  readers pair the lanes with the speed they were designed at — plus
  `dlane k`/`dn …` delta-lane lines in the exact `n` grammar;
  missing = uninfluenced, empty dlane blocks heal to a single (0,0)
  node; SPEED CONTROLS (v18.0, D160) append `sctl type complex x0 len
  speed accel min max [decel tied]` (fields 9-10 v18.45 D217 —
  missing = tied with decel := accel, every old file bitwise) +
  `cn` node lines (shared grammar) + `cdn` decel-graph nodes
  (v18.45; written even while tied — the evaluator, not the file,
  ignores them then) + `smark x`
  per plain diamond — missing = none, old readers ignore all four,
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
  settings (heart, gauge, rail color) live in the `.uvd` save file
  (`.uvd` since v19.22, D251 — Save enforces the suffix, Load opens
  ONLY `.uvd` by name; the FVDD3/FVDD4 content is unchanged, the
  Save-As default name stays "coaster.uvd"; the settings file keeps
  its `.fvd-designer.properties` name).
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
(path broken exactly ON each boundary since v18.35 — seam-crossing
breaks; the old SEAM_TRIM band collapsed with D207's span-clean
rates, and the drawn sample loops are view-culled + pixel-column
compacted since v18.36, D208 — ColPath, coverage identical; scale
fitted to the visible window like a lane). Every
wide-enough strip wears a Force/Geo/S/C BADGE at its top-left (type
indicator — HOVER-LIT since v19.1 D223, hoverBadge: brighter fill/edge
+ hand cursor, the seam-line pattern, so it reads as the button it
is; NEUTRAL GRAY again since v19.14 (D243 — the D241/D242 type
colors REVERSED on user spec) but LIGHTER than the historic fill
(56,62,76 / hot 84,92,112) with a THICKER edge (1.6 / 2.2 hot) so
the button stands out; labels stay capitalized; the dirty-bridge
warning colors kept; alphas 0.6/0.9/1.0 inactive/active/hover;
the label appends " ≠v" (ENTRY-only speed conflict since D247) and
" ≠o" (orientation conflict — sectionOrientConflict →
orientConflictAt), both can show;
◀ = reversed-flag set + per-section menu: the bold TITLE ROW
(v19.19 D248 — the PURE run-wide title "<Type> Section n",
labelItem inert: never highlighted, never closes the menu; its
TOOLTIP carries the D247 info-line numbers — design/authored/
entered speed, domain/axes/units, s/c length, the orientation
line; the drawn badge is the title + tags + glyphs through a
Text-measured WIDTH LADDER: full → short "Geo 2 ≠v" → none),
SPLIT
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
the COASTER's only section (one piece, one section — wording fixed
D239; other coasters in the file don't count); downstream
re-chains — EXCEPT deleting a
piece's FIRST section, which RE-ANCHORS the piece at the old
section-0/1 boundary since v19.6 (D235, Coaster.reanchorAtBoundary
— the splitPiece pose recipe on the existing piece: the remaining
track never moves, BITWISE via the canonical pair, ReanchorGate;
the ride entry's v0 follows the new first section's design speed;
strict-seam followers skip — their entry is glued to the seam);
deleting the active one
activates
its predecessor; one undo step; press rank nodes/knobs > '+' > badges
> curve insert (D57). An open '+'/badge menu dismisses on the next
graph click (JavaFX auto-hide misses the canvas — openMenu field).
A dashed line + circled '+' past the ride
end opens the add-section menu (+ "Add floating piece…" since
v19.1 D226 = the START SCREEN for a new detached piece, see
StartPanel; the SEAM MENU's "Insert section…" reuses this dialog's
rows at ANY boundary since v19.18 D247 — see INSERT SECTION in the
seam-menu paragraph): ONE "Add force/geometric section…"
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
badge menu's SECTION SETTINGS block (top, after the title row)
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
group moves, hold-inserts). SPEED-CONFLICT detection: the ≠v BADGE
is ENTRY-ONLY since v19.18 (D247 — sectionDrifted reads the span's
FIRST sample; D106's whole-span sectionDrift lives on as
sectionDriftedSpan for the true strokes and B's real-split
fallback; a tilted piece's interior split now shows as the ≠o
ORIENTATION glyph — "· true splits inside" is gone), and Re-freeze
design speed BAKES a tilted piece's
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
direction, entry smoothing — on element 0 the field is the SECTION-
ENTRY roll ease (v19.3, D232; the κ seam stays sharp) — and ROLL: Rate °/s | Global bank ° radios + value, v11.1;
chips wear ∠° / ↻°/s when non-zero); right-click also inserts
straight/curve before/after +
deletes (clean-release rule, right-drag still pans); Delete = remove
selected element; A = append a straight; badge menu adds length/count
info + "Append element". New elements default 2 m entry smoothing
(SC_SMOOTH_DEFAULT) and GLOBAL 0 roll. The toolbar button is
"Append (A)" (v11.1; the live shortcut tag + short tooltip since
v19.12 D241) — seconds on lane sections, elements on s/c (the
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
sample the compiled curves (~3 samples inside via rateIdxAt,
extrapolated back to the compiled boundary time — reading a few
samples clear stays: it dodges value-vs-frame transients at the
seam sample itself. Since v18.35 (D207) rates are SPAN-differenced
and never smear, so SEAM_TRIM is 0.6·dt and guards only hover
readouts/scale fits — the DRAWN curves run to the seam and break
ON it, and hover-slope windows clamp to the sample's own span
(slopeWin); the sampled tail reads
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
ENABLED — the SMOOTHING DIAMOND — ALWAYS VISIBLE on strict seams since
v19.8 (D237: HOLLOW while smoothing is off, dragging the hollow
diamond ENABLES smoothing at the dragged length — s/c edges still
refuse via seamScEdge; hover = a gray center dot in the hollow
diamond + small ◀ ▶ carets in both states, drawCaretH; and the
cursor respects press priority — a node on a band edge shows the
normal cursor, not H_RESIZE) — (v17.3, D140: the s/c pattern —
dragging ADJUSTS sSmoothLen relatively since v17.5 (D142: base + 2·Δarc from the press — right grows; edges keep absolute-to-cursor), a clean SINGLE-CLICK = the full SEAM MENU since v19.3 (D232 — was the settings pop-out, v17.17; ≤3 px press-to-release, with a matching 3 px drag deadband so a click never commits a micro-resize — the 3D seam ball keeps its double-click), and the diamond DODGES nodes down the '+'-ladder (seamDiaYAt); pre-D142 it sized twice the cursor↔seam ARC read off
the samples, live "smoothing X m" readout, Ctrl snaps by the
element-length step, < 0.5 m snaps shut, cap 100 m
(GraphEditor.SEAM_SMOOTH_MAX — 80 until v19.20 D249; the loader
admits 0..200, a loaded larger value stays until the next drag;
the diamond DODGES THE CARETS too since D249 — seamDiaYAt's
three-pass ladder, SEAM_DIA_CARET_CLEAR 12 px), filled =
active, shaded blend-zone band whose EDGES also drag (v17.4 —
lowest press rank: nodes/menus/inserts win; ONE-SIDED since
v19.19 D248, Gestures.edgeWindowLen: the window is twice the
arc from the seam to the cursor on the grabbed side and 0 once
the cursor crosses the seam, staying off past it — the diamond
keeps its relative drag; s/c interior blend edges the same), one undo per
gesture, H_RESIZE cursor, press rank above the seam menu; the
band tints RED when the smoothing ITSELF opened the follower's
≠v — smoothingCausedDrift, one cached smoothing-suspended
compile — and the badge's resolve submenu is then the grayed
line "Speed conflict caused by seam smoothing — shrink the ◇
window instead", v17.4 D141, reworded v19.19 D248 and again
v19.20 D249, shown ONLY for that case: the resolve ops themselves
RUN under smoothing since D249 — Coaster.withSeamsSuspended
zeroes the piece's own seam and the next one for the op and
restores the exact doubles by piece object; rangeResolveWhy's
smoothed refusals are gone; RangeResolveGate s1-s6); seam
lines HIGHLIGHT on hover (hand cursor) and stay lit while their
menu is open (D136). Clicking
any non-bridge seam LINE (press rank after nodes/knobs/badges)
opens the SEAM MENU — on section boundaries "Connect sections
(merge into one)" (= mergeSections, like sections only; unmatched
seams keep BOTH values as a steep step since D247) + "Insert
section…" (v19.18 D247 — see INSERT SECTION below) + "Make
seam strict (freeze downstream shape)…" (= the D71 rigid split +
follow; the settings auto-open) + "Split track at seam"; on strict
ones "Strict Seam n…" (v19.19 D248 — was "Strict seam
orientation…", itself renamed from "settings" in v19.6 D235) / "Insert section…" (D247 — appends to the UPSTREAM piece
before the seam) / "Make smoothing real…" — PRE-CHECKED at
menu build via the makeSmoothingRealWhy dry-run; grayed with the
FULL reason on hover since v19.7 (D236, grayItem) — / "Enable smoothing (10 m)"|"Disable
smoothing" (v17.4, D141 — the diamond shows only while enabled;
hand-made seams start OFF, bridge-converts and connects start ON
at 1 m) / "Un-strict seam (reorient
continuous, keep conflict)" / "Split track at seam". ("Split piece
before this section" and "Connect with previous section" LEFT the
badge menu in D136 — the seam + the first node's menu own them.)
INSERT SECTION (v19.18, D247 — GraphEditor.showInsertSectionDialog +
Coaster.insertSectionAtSeam(piece, sec, added, lockDownstream, dt)):
grayed "This boundary isn't compiled yet." without a span; the
"Insert section" dialog = the Add dialog's rows in the D143 order +
a THIRD Type radio "Straight / Curve" (lane rows grayed with one
reason; the fork + padlocked speed stay — a custom speed pins a
straight too), the fork row when seamDrifted(kLeft) (the
generalized pieceEndDrifted), the padlocked design speed (custom
clamp [0.1, 1e4], D198), an "Initial length (s)/(m)" spinner
(defaults NEW_START_LEN 5 s / CONT_HOLD_DIST 10 m; per-domain
SESSION memory insLenTime/insLenDist, s/c shares meters) and, on
non-strict seams only, "Lock Subsequent Track Shape" — OFF = a plain
list insert (the downstream re-solves from the new exit against
gravity, the reconcile re-freezes its speeds: '+' semantics), ON =
makeSeamStrict FIRST on the pre-insert compile (the downstream
becomes an all-locked D235 follower keeping its shape AND
orientation and moves to the new exit; its design speed stays
independent, so a ≠v can open there honestly); on a strict seam the
section appends to the UPSTREAM piece before the seam, held angles
stay, unlocked axes re-aim (D135 verbatim). The '+' hold recipe was
factored into buildLaneHold / buildScStraight (the '+' path
bitwise); Add commits a typed length, re-validates by index (piece,
count, strict-ness, drifted) with a stats reason, refuses an
uncompiled boundary before pushing undo; one undo step; the seam
pop-out pre-hides before a lock insert. InsertGate 40.
The seam WINDOW (titled "Strict Seam n" — a LIGHT owned
LiveWindow Stage since v19.19 D248, placed just above the graph
canvas at the follower's strip / the junction button, Confirm/
Enter/X keep · Cancel/Esc revert, INDEX-resolved handlers +
docRestoreHooks refresh — the D247 undo in-place re-open is
gone; D232-D247 it was a DARK movable Popup with focus-hide):
per axis a PADLOCK toggle
(🔒/🔓, v18.32 D204 — the labeled "Discontinuous" button reverted
on user spec; RED outline while locked, the outlineStyle pattern),
a ±360° spinner (typed + arrows)
and a ↺ — UNLOCKED 🔓 = follows the seam
(spinner grayed with a hover reason, live value — read
ANALYTICALLY off the follow
contract since v17.2, followerEuler: the entry SAMPLE sits inside
a smoothing window), LOCKED 🔒 = holds the spinner's angle
(TrackPiece.sYawDeg/sPitchDeg/sRollDeg, piece fields 15-17;
toggling ON always holds the CURRENT angle since v19.6 — D235
removed D145's first-open creation-angle snap + sSeenSettings;
↺ returns the WINDOW-OPEN value, or for an axis locked during
this session the STORED placement's angle — a direct attach's
pre-attach orientation), Apply keeps / CANCEL reverts to the
open-time state (v17.8 — auto-hide on click-off is gone). REWORKED
v19.18 (D247, user spec: pitch + roll shared ONE row) and
RE-SPLIT v19.19 (D248 — the unified layout: "Yaw (heading °)"
/ "Pitch (°)" / "Roll (°)" rows, one label each, [label]
[spinner][🔒][↺][grey design readout] via LiveWindow.orientRow
with the bridge-lock look; ±360 factories so held pitches past ±90 never clip; the
seam padlock SEMANTICS kept — 🔒 = holds the typed angle; the
BRIDGE padlocks read the same way since v19.20 D249, only a free
piece's Track Piece padlocks stay edit guards); per-row grey
readouts since D248 ("design 12.34°" — eulerOf(designPose()), NaN
→ "—"; under "Edit design orientation" the BOXES show/edit the
design elevation/bank regardless of the padlocks via
Coaster.seamDesignSet, ↺ = the open-time design euler, the
readouts read "real …" off followerEuler, the yaw row always the
held/real heading — v19.20 D249; the D247 "Design orientation:"
line is gone), the MODE ROW (LiveWindow.flagRow since D249 —
"Edit Real and Design Orientation Independently" + the real /
design radios + "Affect Subsequent Strict Seams" on the
follower's own orientMode / affectSubsequentSeams; D248's "Lock
Track Shape" + "Only Affect…" row is gone) — and the
"Design → Real Orientation" / "Real → Design Orientation" buttons
(GraphEditor.seamDesignToReal — absolute lanes route through the
D240 Preserve/Convert dialog — / seamRealToDesign), readout +
buttons only while orientConflictAt(fi); EVERY held-angle write
(spinners, padlocks, ↺, the seam gizmo rings) routes through
GraphEditor.seamEdit (mode-keyed since D249; the design boxes/
rings through seamEditDesign); Cancel restores the whole
coaster's PlacementSnapshot by identity since v19.20 D249 — a
mid-window Design → Real bake reverts too (baseOrient + the
canonical pair are recorded; supersedes D247's sameBase guard);
ONE resync GraphEditor.seamSync (registered as a model hook via
seamHook, focus-guarded; hideSeamPopup = null-before-hide and
de-registers it); seamLive self-validation tears the window down
with "The strict seam window closed — its seam no longer exists."
when its seam is gone, and every structural op pre-hides it; every
write gated by convertBusy(). The toolbar JUNCTION button
(FvdDesigner.strictSeamBtn in strictSeamWrap, right after Track
Piece ▴ — ONE button since v19.19 D248, syncJunctionButton, label
= SectionNames.ownerButtonLabel: "Strict Seam n ▴" while the
active piece follows a plain strict seam, "Strict Seam n
(bridge) ▴" while the active section IS a bridge or the piece is
bridged-in (it opens the PREVIOUS piece's bridge window), none
before the first junction, an ORPHAN bridge GRAYED "This bridge
has no following piece."; the WRAPPER's visibility toggles so
the HBox spacing collapses; updatePieceButton owns it,
wizard-disabled like the Track Piece button; tooltips
"Orientation of the strict seam this section follows." / "The
bridge junction: its placement mode, length and the next
piece's orientation.") opens the window (a toggle: open = close
without revert). Smoothing
is edited on the seam itself (the diamond), and in 3D the SEAM
GIZMO (v17.3, D140): the follower's reveal ball sits ON the
junction (seamWorldPos — the D136 ghost was the STALE stored
position); its rings (shown whenever gizmos are on, v17.7) cover
the DISCONTINUOUS axes only, writing
the held angles (gizmoSetSeamOrient; ring axes off the follow-
contract frame seamEntryOrient; one undo per gesture);
DOUBLE-CLICK = the settings pop-out (reveal state unchanged).
Defaults since v19.6 (D235, user spec): EVERY strict-seam creation
locks ALL THREE axes at the current angles (makeSeamStrict,
bridge-convert strict, make-real's reseed — nothing moves at
creation), EXCEPT a direct ATTACH, which keeps yaw unlocked and since
v19.8 (D237, superseding D153's padlock seeding) ALWAYS locks
pitch+roll at the piece's PRE-ATTACH angles — a conflict-free
layout stays conflict-free, only the honest speed split remains;
the zero-distance-bridge YES still carries the bridge's
yaw/pitch/roll locks. The whole-bridge
Convert dialog grows "Make following seam strict (freeze following
track shape)" — CHECKED by default (all axes locked since D235);
convert+strict then un-strict == the classic attach+pin stitch
BIT-IDENTICALLY (gated). '+'-attach and the zero-distance prompt
connect strictly now. RESOLVE ops on a follower BAKE the follow
first (Coaster.bakeFollow, D136 — the D106 lesson: without it
make-real double-applied the follow rotation, the "launches off
into the distance" bug; make-real bakes with the SAME compile the
fit reads, since baking a follow changes the generation frame);
gated: make-real keeps the on-screen shape, strict refreeze == the
un-stricted twin's. A follower's Track Piece panel edits the RUN
HEAD's x/y/z since v19.18 (D247 — FvdDesigner.placementTargetIdx/
placementTargetRef, the head's carry moves bridged targets,
followers follow by compile; the anchor label wears the run-head
hint, a second sentence per orientation mode / carry flag — four
tooltips since v19.20 D249),
its yaw/pitch/roll rows AND padlocks gray with the live
junction-named reason ("…edited in Strict Seam n ▴…", D248; the
design readouts + flag/resolve rows stay hidden — seam-owned), its entry gizmo doesn't build, and its junction ball
is the SEAM ball at seamWorldPos (D136 — the stored-position marker
floated off-track as a ghost node; D247's run-wide balls keep that
rule); B/Shift+B
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
"Piece k/n ▾" selector switches — it lists RUNS since v19.19
(D248, RUN AS PIECE: one entry per run head with the whole run's
length + the head's anchor, ✓ and k/n keyed off
runStartOf(active), hidden with one run, the active run a no-op
pick, the open wizard's piece and an open convert preview grayed
with reasons); badge
menu "Split piece before this section" cuts at the compiled boundary
POSE — position AND full orientation into the new piece's baseOrient,
so splits are BITWISE anywhere (pitched/banked included — user rule
"splitting never changes orientation"; Span carries each section's
exact exit TrackPose for this); the editor follows the edited
section onto the new piece. '+' menu "Attach floating piece ▸" (OTHER RUNS' heads only since
v19.19 D248 — like "Bridge to piece ▸": Coaster.moveRun moves
the whole block, canMoveRun pre-checks BEFORE the undo push,
refusals grayed / "Attach refused — …" on the stats bar, the
seam + speed popups pre-hidden, the gizmo reveal dropped)
appends
another piece's sections to the active chain and dissolves it
(placement discarded — attach MEANS continue, and stays first-class
alongside future bridges per user; ABSOLUTE heading lanes REBASE
into the target's frame since v19.9, D238 — world headings kept,
the splitPiece rebase's exact inverse, un-strict + bridge-stitch
ride the same attachPiece path); Coaster menu "Piece
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
settings…" (v19.20 D249; "Strict Seam n (bridge)…" in v19.19
D248, "Bridge properties…" before) opening the window titled
"Bridge settings (Strict Seam n)" — the SAME window as the
toolbar junction button (which keeps "Strict Seam n (bridge)
▴"), grayed
"This bridge has no following piece." on orphans: the user's
two-spots request, via FvdDesigner.openBridgeWindow.)
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
compile (Coaster.reconcileChainedSpeeds — its REAL-ANCHOR
recognition is AMPLIFICATION-AWARE since v19.8, D237: a seam gap
under 0.2 m/s can read OVER 0.2 at a slow crest (energy offset ÷
2·vMin), and the old constant entry test snapped every such user
resolution back onto the design chain — the unresolvable phantom
of his "Conflict not getting resolved" file; the gate now scales
by vTrue/vMin over the section's span, the same quantity the ≠v
badge measures, flat sections bitwise-historic; the resolve ops
anchor at the reconcile's own SEAM sample now = bitwise fixed
points; refreezeSeamSpeed got the twin fix + the D198 [0.1,1e4]
clamp; ConflictGate. Looped with the auto
re-optimize pass in rebuildTrack — to QUIESCENCE since v18.25
D197, cap 12 was a hard 3, and the reconcile CARRIES a
just-rewritten predecessor's speed change through the frozen
energy profile sqrt(vNew² + vEndStale² − vOld²) so ONE pass
walks the whole chain: the old stale read cascaded one section
per compile, and on a 17-section layout the capped loop froze
docs MID-CASCADE — every rebuild (open/click/spinner) then
morphed the track tens of meters, the 2026-07-31 "reopened
coaster keeps changing" report; an untouched predecessor keeps
the stale read bitwise; piece FIRST sections and
REVERSED sections excluded. TWO MODES since v18.28, D200: the
fast tube pass above runs per live-gesture event; every AT-REST
rebuild then runs the EXACT pass — bit-difference writes, carry
off, terminating in ≤ chain-depth passes by the chain's
triangular structure — so settled speeds are a pure function of
the graphs and a hand-reverted edit returns bitwise;
gestureHot/settleIfPending own the split, saveFile settles
pre-write), so plain force/geo/s-c chains keep
their graphs REAL and re-derive geometry instead of splitting — a THIN solid companion stroke in the lane color
draws the TRUE felt/rate curve beside the design curve across the
whole run (GraphEditor.drawTrueStrokes; TRUE_SPLIT_PX 1.5 px on the
lane's scale is the show threshold, TRUE_STROKE_ALPHA 0.85; felt
strokes run straight through seams, rate-sourced ones — roll, and
all lanes in geo view — break exactly ON seams since v18.35
(D207 span-clean rates; they trimmed a SEAM_TRIM band before);
nothing draws when nothing drifted — EXCEPT inside a
smoothed strict seam's window since v17.10 (D149): the split
reference is the AUTHORED lane value (the influence/tilt rule),
so the real blended curve shows — across the WHOLE window from
EITHER neighbor since v19.6 (D235: the collection carries both
sides' spans/lanes, matching units required on the far side; it
used to clip to the active span, one half at a time)). THE SPLIT
REFERENCE is ONE helper since v19.19 (D248, item S3 —
GraphEditor.splitRef, shared by drawTrueStrokes and the " (true
…)" hover trueSuffix): a smoothed-window row's authored lane → the
active section's AUTHORED lane while its piece is
ORIENTATION-CONFLICTED (pieceConflicted = orientConflictAt, the
D247 predicate — the old pieceTilted() read only the pre-v19.0
TRUE-tier deltas, so a design-tier conflict or a follower's
held-seam tilt kept the derived reference == the true felt
wherever speeds matched and the lateral-G stroke VANISHED, his
report; ConflictGate e1-e7) on the lanes LaneSection.tiltDeviates
says a rigid tilt changes (all force lanes; geo world-axis +
absolute lanes; geo local rates keep derived) → derived; authDisp
warps authored references like derivedDisp in the cross-rate
view; a lockstep-tilted piece (design == true) is NOT conflicted
(derived reference, inactive strips authored, B analytic); the
B-match analytic guard reads !pieceConflicted (a conflicted piece
takes the sampled path); inactive force / world-geo strips of a
conflicted piece draw DERIVED; the Resulting ▾ overlays are
unconditional and untouched. Drifted sections'
badges wear "≠v" (ENTRY-ONLY since v19.18 D247: DRIFT_EPS 0.2 m/s on
the span's first-sample speeds — the RAW gap, not the reconcile's
D237 amplified gate, so a sub-0.2 seam gap over a slow interior can
split the true strokes with no ≠v; flagged to the user, a one-line
change in sectionDrifted; the D247 "· entered X m/s" info line
became the badge title row's TOOLTIP in v19.19 D248 and is GONE
since v19.20 D249 — the glyph, the init-speed window and the
orientation windows carry the numbers); ORIENTATION-conflicted
pieces' badges wear "≠o" (sectionOrientConflict →
orientConflictAt: free piece TrackPiece.orientConflict(),
follower = the seam tilt with a horizontal axis; both glyphs can
show);
lane + derived hover readouts append " (true …)" — inside a
smoothed strict seam's window the split test reads the AUTHORED
lane as reference since v19.7 (D236, smoothedWindowParts — shared
with drawTrueStrokes; the compiled samples ARE the blend there,
so the old derived-vs-true test hid the blended value without a
≠v) — value and
Shift-slope — once the curves visibly split. POV W/S rides at TRUE
speed (the design-time advance scales by v_true/v_design at the
current sample) and the stats-bar top speed reads Pass D; both are
identical to before until a drift exists. RESOLVE CONFLICT (the
submenu title since v19.6 D235 — was "Resolve speed conflict";
§5 round B, LIVE
since v14.5 D97; REWORKED v15.2 D101, SIMPLIFIED v15.4 D102 —
SPEED conflicts resolve LEFT TO RIGHT, one section at a time;
ORIENTATION conflicts resolve over a RANGE since v19.18 D247, see
RANGE RESOLVE below; reversed
sections refuse — reversal itself is the conflict-free rebuild
since v17.0 D134): a drifted
section's badge menu grows a SUBMENU (opening for a pure ≠o too
since D247; TITLED "Resolve conflict: speed" / "…: orientation"
/ "…: speed + orientation" (v19.20 D249 — D248 said "Resolve
section conflict: …"; the submenu sits BELOW "Initial design
speed…" since D248) — the D247 disabled "Resolves: …" scope
line is GONE, the range scope is stated by the end-section
dialog + Help; behind a bridge both items gray with the bridge
sentence; both items gray with the march sentence under an
OPT_POS_YAW bridge; a smoothing-CAUSED pure speed conflict shows
only the D141 line (see the seam paragraph — smoothed seams no
longer gray the ops, D249)) with TWO
ops — "Convert Real
to Design (keep track shape)…" (labels re-specced v19.5 D234; was
"Make graphs real…" — re-fit at the TRUE entry speed through the
density preview; s/c just re-freezes; grays while the DIRECT left
neighbor is itself conflicted) and "Set Design to Real (keep
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
follow any resolution — convert it first). RANGE RESOLVE (v19.18,
D247 — on an orientation-conflicted piece, orientConflictAt; pure
speed conflicts keep the single-section ops bitwise): the two items
resolve sections k..m behind INSERTED strict seams —
Coaster.isolateRange(p, k, m, dt) = makeSeamStrict after m (unless
m = lastNonBridge(p); a trailing bridge is never in a range) THEN
before k (the upstream keeps its conflict as its own piece, the
remainder becomes an all-locked follower keeping the conflict;
local shape bitwise, world placement to Euler dust); k == last
resolves alone, else the WINDOW_MODAL "Resolve orientation
conflict" dialog picks the end section ("Section j — <badge>",
default last). "Set Design to Real" = Coaster.refreezeRange(rp, dt)
(the refreezeSpeed prologue — bakeTilt / bakeFollow — then
refreezeSpeedOnly(rp, 0) + unpin; ONE undo step; == today's
whole-piece op restricted to the range only to the seam-bake dust,
1e-7 measured, RangeResolveGate g2c bound 1e-6 — never bitwise);
"Convert Real to Design" = a CHAINED density preview (RangeChain:
per step isolate + lock [i+1..m] as a follower + makeGraphsReal +
absorbFollower — followPrev off + attachPiece — re-joins the
converted sections into ONE piece; s/c members apply directly; one
Apply per section = one undo step; Cancel keeps earlier Applies;
chainStepBack = the convert-UI Ctrl+Z re-opens the previous
section's preview, past step 0 the pre-isolation document). Seams
are LEFT strict (un-stricting the upstream seam re-chains the
range onto the conflicted design chain = visually undoes it); the
remainder is a piece-first, never reconciled, so a ≠v can open on
it. Gray reasons via rangeResolveWhy (a smoothed strict seam on the
piece or the follower right after it — conservative —, a reversed
section in k..last, uncompiled, a lane section under 4 samples);
LEAK FIXES: makeGraphsReal refuses `< 4` samples BEFORE baking, a
refused first preview run restores pvBefore, refused ops snapshot
first and push only on success (rollbackTo keeps the dirty flag),
the dialogs re-check orientConflictAt + run membership at Resolve.
The '+' menu
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
semantics + roll removal v15.4 D102; the ONE-PASS HYBRID model
v19.7 D236 — user re-spec 2026-08-25): a
force/geo badge menu's "Influence geo/force graphs" (the
resulting/felt qualifiers dropped in v19.15, D244)
gives the section OTHER-MODE DELTA LANES that ADD to its resulting
graphs (resulting == base + delta EXACTLY — the deltas inject into
the section's OWN solve per RK4 stage; see TrackCompiler above.
Wherever the deltas sit at zero the section behaves EXACTLY per
its original graphs — no residual laterals after a delta window,
no leftover speed/orientation conflict, the D236 headline fix).
Deltas exist for lanes 0/1 ONLY (force → Δpitch/Δyaw, geo →
ΔGv/ΔGl; ROLL takes none — its button disables, its strip shows
the dotted resulting roll read-only; lane 2 stays in the model +
file, inert). Entering NEVER touches speeds (D102 — "behave
exactly like a normal section, just with nudges"; the v15.0
instant-reconciliation silently closed the NEXT section's conflict,
gated now): vAuthored just mirrors vDesign from then on
(LaneSection.vDesign(v) syncs the pair through every consumer) and
influenced sections join the D95 reconcile like normal ones
(pinned still skips; a hand-edited divergent pair is INERT since
D236 — it compiles at vDesign, gated bitwise). Fresh delta lanes hold a
node at 0 AND at the section end (flat zero line). The strip flips
to the other mode's
palette/units/snap (activeGeo() flips; lane buttons wear Δ, badge
wears "fx", the title-row tooltip shows both speeds), app.lanes binds to the
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
delta EDGE node LINES UP THE RESULTING GRAPHS since v18.37 (D209,
user re-spec reversing v15.5/D102's return-to-zero): delta :=
neighborTarget − base (value + slope; baseValAt/baseSlopeAt at
the exact edge, distance lanes' slope ÷v) with the target from
the SAMPLED boundaryState tail under influence-mode routing —
authored/analytic shortcuts skipped (their semantics are the
SECTION's, not the surface's), °/m + world-rate handling keyed
off the DELTA lanes' own semantics, real-split κ conversion in
(B aims at the REAL curves, as usual), re-press converges; still
NO seam re-freeze (operating speeds move only by explicit
action), and no neighbor on that side / no compiled data / the
inert roll lane fall back to the old zero return (InflMatchProbe
gates it: 135 → 3.4 °/s first press). B on the DESIGN surface of
an influenced section lines up the TRUE curves since v19.6 (D235
— it used to write the neighbor's target verbatim onto the
AUTHORED lane, leaving the resulting offset by the edge delta):
a CORRECTION form — authored edge += target − ownEdgeState (our
own compiled edge, the neighbor-tail recipe pointed inward, pm
wrapper included) — converging under re-press. Resulting ▾ overlays read the TRUE
other-mode arrays while influence-editing (labels + right axis say
"True"). Exits (one undo step each): "Bake influence into design
graphs…" = convert to the OWN mode via the density preview
(reference = the compiled/influenced curves; keeps the section's
domain/axis flags since D235 — the 2-arg reset was a bug);
"Discard influence"
= bitwise restore (gated; an ANCHORED half keeps its operating
speed, D235). Partial converts refuse influenced
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
BODY segment MOVES it since v18.9 D179 (as its start diamond did
D179-D247; the start diamond RESIZES from the left since D248) —
spGrabOff anchors the grab point, 3 px deadband keeps clicks
selections),
double/right-click a diamond (or the line after one) promotes the
stretch to the next boundary into a CONTROL (consuming its marks)
and opens the SPEED CONTROL options (movable dark popup: Speed |
Acceleration radios — "Constant Speed" / "Constant Acceleration"
until v19.19 D248 item S2, the lift/transport + launch/brake hints
kept —, Complex check, speed box +
Max/Min checks — at least one always on — accel box, Start/Length
spinners in native units, read-only "Enter:"/"End:" speed readouts
showing true when split (one row since v18.8; "Start" renamed
"Enter" + made CLICKABLE in v19.8 D237 — click = target := enter
speed, layout-stable hover outline + tooltip; complex SPEED
controls keep a plain readout; fresh controls also START at the
enter speed to TWO decimals now, was whole m/s), Edit Speed/Accel button that expands the strip, SELECTS
the panel's control (D249 review — the strip edits it at once; a
plot node selection drops) and nudges
the popup clear); the same clicks REOPEN it — and the popup STAYS
OPEN when a node/marker/gizmo gets selected since v19.20 D249 (an
open panel is not a selection: bound to its own spPop* indices, it
hides only via ✕ / +V-off / a control delete / structural clears /
spAfterRebuild when ITS control vanished, and follows another
selected control). Left-click selects (Delete removes — strip
focus rules; spFocus — node and control selection are MUTUALLY
EXCLUSIVE since D249: GraphEditor.takePlotSelection (PRIMARY plot
press — a right-drag pan / middle press / menu open selects nothing
and leaves a lit control lit, D249 review; the selecting secondary
paths, the node menu + the element menu, call it themselves —,
marker click, gizmo/ball press, empty-track click, s/c element)
drops the control/mark selection and hands spFocus back, the
strip's select sites + the disk press drop the node selection via
spTakeStripSelection; before, a lit control stayed lit and a
marker click under spFocus wrote selIdx onto the STRIP surface —
"markers unselectable while a control is selected"), start-diamond drag
RESIZES FROM THE LEFT since v19.19 (D248 — SP_DRAG_START,
Gestures.resizeStart: x0 moves between the previous control's
end and end − 2×the domain gap, the END fixed, no ripple; it
MOVED the control D179-D247; the popup's Start spinner keeps MOVE
semantics; an abutting pair still picks the END diamond first),
end-diamond drag RESIZES (complex graphs stretch along, fraction
semantics — BOTH graphs via SpeedControl.rescaleLanes, shared by
both diamonds since D248, spRescaleCtlLanes since v18.45), and a complex graph's LAST NODE drags the CONTROL LENGTH
itself since v18.43 (D215, user spec — no stretch: only that node
moves, surfCtl.len follows it; endLimitFor's strip wall becomes
surfCtlMaxLen for the last node, interior nodes keep the D174
hard box), ripple-time shifts later diamonds, Ctrl snaps
by the axis step, everything clamps inside the section and against
neighbor controls. ACCEL controls split accel/decel since v18.45
(D217; chain-link UI v18.47 D219, re-specced v18.48 D220):
Acceleration and Deceleration ALWAYS visible on ONE popup line
with a small 🔗 LINK ToggleButton between them (tooltip "Link
Acceleration and Deceleration"; NEON-YELLOW background while
linked, plain gray otherwise, glyph always black, no outline).
Linked = default = the classic one-magnitude behavior, bitwise —
the DECEL side grays out (spinner disabled but LIVE, mirroring
every Acceleration edit; Edit Deceleration disabled; the ▸ stays
on Acceleration); unlink for a separate brake G. Complex swaps
the spinners for ▸ Edit Acceleration/Deceleration buttons in the
same layout choosing which graph the strip displays/edits
(SpeedControl.editLane() at the active() choke; the other draws
dimmed; accel first). Either flip of the link leaves decel ==
accel at that moment (unlink also deep-copies the graph), so
nothing changes at the split. The ▲ arrow (or the top-edge divider) expands
the TALL view: resulting TRUE speed green dotted + hoverable
("v = … m/s"; HIDDEN inside COMPLEX speed controls since v18.4
D173/D174 — their graph IS the speed; plain constant segments
keep it) — and the DESIGN speed as a SECOND dotted line since
v19.19 (D248, item S2; uvd.SpeedStrip is the pure half,
StripGate 49 since D249): drawn only where |spVToY(design) − spVToY(true)| >
TRUE_SPLIT_PX (SpeedStrip.split — purely pixel-gated, no ≠v/≠o
flag read: a tilted piece's line appears mid-section like the
lanes' true strokes), design = SP_SPEED_COLOR at DESIGN_W 0.8 with
DESIGN_DASH (2, 2.67) — the old true style / 1.5 —, true =
SP_TRUE_COLOR rgb(160,250,170) at TRUE_W 1.6 with the historic
(3, 4) dashes (brighter, never alpha; feel-tunable, named); hidden
with the true line inside complex controls, draws through stalls,
joins the fit (SpeedStrip.fitHi); the TRUE line's hover appends
" (design 12.34)" (SpeedStrip.designSuffix) only where the design
line draws —, complex
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
controlled stretch + a draggable DISK per control START/END on the
heartline since v19.19 (D248, item S1 — see Viewport3D: the disk IS
the diamond, dragged along the track through spViewportPress /
DragTo / Release into spDragDiamondNative = the strip's own write
path and clamps; one undo per gesture, a press auto-opens a hidden
strip collapsed, double-click = the options popup, refused under
the preview / the start screen; GestureGate section 7 pins disk
write == strip write). STALLED track (true march at the 0.1 floor)
renders TRANSPARENT to its run's end and the POV skips it both
ways (D163). Badge menu "Initial design speed…" (D162; "Set
initial design speed…" until v19.19 D248 item S2 — now DIRECTLY
under "Orientation settings…", ABOVE the resolve submenu;
INDEX-RESOLVED + a live box since D247; the box is a TYPABLE
SPINNER since v19.19 D248 — step 1 m/s, Shift 0.1, Ctrl whole
multiples, hold-repeat, %.2f display, the [0.1, 1e4] factory
range = the D198 clamp re-applied at Apply via
Gestures.clampInitSpeed, commitTyped before Apply — and its
enabled "Set Design to Real" wears the user's VERBATIM tooltip
"Fills box with the real entry speed, resolving a speed
conflict. Does not resolve an orientation conflict" (D248, no
trailing period)) sets
a section's starting speed directly — LIVE since v19.20 (D249, a
LiveWindow with Confirm/Cancel: every spinner change applies at
once — the ride entry writes v0 + section 0's vDesign through the
muted Start-speed spinner, elsewhere vDesign + PIN as a deliberate
conflict (a value re-typed equal still pins, D242) — undo coalesced
per 0.8 s typing burst; "Set Design to Real" runs
Coaster.refreezeSpeedOnly AT ONCE (one undo step: the exact seam
value, un-pinned so the speed follows the real flow, an ORIENTATION
conflict left standing — the badge menu's resolve op alone bakes
tilt/follow via refreezeSpeed; grayed on the ride entry, whose real
entry speed IS v0), Cancel/Esc restores the open-time vDesign +
vPinned (+ v0) as one more undo step, the hook re-seeds the box and
tears the window down when its section is gone; supersedes D240's
fill-the-box + D242's unmodified-Apply rule — there is no Apply
moment). The resolve pair
reads "Convert Real to Design (keep track shape)…" / "Set Design
to Real (keep graphs, reshape)" (re-specced v19.5 D234 — was
"Make graphs real (keep
shape)…" / "Set Design Speed to Real …"); the Add dialog's fork
row reads "Initial design speed"
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
CROSS-DOMAIN-VIEW PARITY (v19.20, D249): under "View in time/
distance domain" strip drags and the create drag Ctrl-snap on the
DISPLAY axis (SpeedStrip.snapDisplay — warpFwd → the section-local
ABSOLUTE display grid → map back; an inactive strip of another
domain snaps on the AXIS grid through sectionMap; the view-off
same-domain case runs the old native snap LITERALLY, bitwise), the
warp map FREEZES per strip gesture (refreshWarp's gate covers
spDragKind != 0), readouts in display units, the popup's Start/
Length stay NATIVE spinners with grey display-unit TWINS ("= 12.5
m" / "= 3.20 s") while the other domain is viewed (flagged);
POSITION READOUTS: hovering or dragging a diamond shows "t = 3.20
s" / "s = 12.5 m" (SpeedStrip.posReadout — %.2f s / %.1f m,
Locale.US, z3; below the line on a collapsed strip; the hover
re-resolves at DRAW time and needs a diamond still at the stored
native x — SpeedStrip.diamondAt), the viewport's combLabel the same
text live during a disk drag (GraphEditor.spPosReadout).
PLACEMENT MODES (v13.5 D78,
BridgeSection.optMode, blen's 3rd field, missing = 0): the toolbar
JUNCTION button reads "Strict Seam n (bridge) ▴" over a live
bridge and opens the BRIDGE WINDOW (v19.19 D248 — a light
LiveWindow Stage, FvdDesigner.openBridgeWindow, addressed BY
PIECE via GraphEditor.withBridge so it keeps editing ITS bridge
across activation changes; rows [mode radios][Length + Optimize
length][Next piece yaw + 🔒 + ↺][Next piece pitch / roll + 🔒 + ↺
+ readout][Next piece x/y/z][the TARGET piece's mode row +
Design ↔ Real][Confirm / Cancel — Cancel restores the whole
coaster's PlacementSnapshot (bridge fields incl. lastOptDist/
optLen/noneStash/optSig) and re-lands the march; titled "Bridge
settings (Strict Seam n)" since v19.20 D249]; the Track Piece
button RELABELED "Bridge properties ▾"
v13.10 D84-v19.18, a dark popup with white text v17.10-v19.18)
— mode radios
"Optimize position and ORIENTATION" (v13.13 D87 — was "and yaw";
same optMode 0: the march aligns every UNLOCKED axis of the next
piece — yaw heading delta, pitch/roll as direct Euler additions
onto TrackPiece.pitchDeg/rollDeg — and humps/twists to the LOCKED
ones; the radio grays out with all three locked — and, since
v19.20 D249, while the target edits its DESIGN orientation
(pyWhy joins both reasons with " · "; the target's design radio
grays under this mode in turn; setBridgeOptMode refuses as a
belt; never grayed while selected); THE PADLOCKS follow the SEAM
RULE since D249 (BridgeSection.axisLocked/axisEditable/
axisAligned — ONE predicate every surface reads): 🔒 = HELD at the
typed value, box/↺/ring editable in EVERY mode (a typed held value
re-marches at once under this mode so the hump/twist meets it),
🔓 = free — aligned here (box grayed "The bridge aligns this axis —
lock it to hold a value."), typeable in the other two modes;
locking captures the current angle with NO write (the held value
IS the target's own field); the target's Track Piece window
shares the padlocks (enabled in every mode, routed through
setBridgeLock) and its yaw/pitch/roll rows take the same
enable rule + bridgeTargetWrite's tail (pieceOrientWrite); the
bridge-end rig builds rings for the HELD axes in OPT_NONE and
OPT_POS (a default free-yaw bridge shows no yaw ring), the tangent
arrow alone here — supersedes D87's "locked = frozen"; Length re-runs
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
row pre-set to the section's current value (the per-row ↺ resets
are GONE since v19.9, D238 — the dialog matches the Add dialog's
format; wireOriginal's orange changed-row dot remains, and the ↺
keeps only its reset-to-a-SETTING homes: Snapping/Shortcuts/View
sliders, color dialogs, Export, the strict-seam pop-out),
any combination to any other, Part carries the choices into the
split-line mode, influenced sections gray Part, reversed refuse;
GRAYED choices wear a hover explainer since v16.10 D122 — how to
un-gray, via GraphEditor.grayHint: disabled controls swallow mouse
events, so the tip rides an enabled StackPane wrapper, installed
only while disabled AND left-aligned CENTER_LEFT since v19.9
(D238 — StackPane's CENTER default let a fill-width VBox center
the wrapped child against its siblings, the Resulting popup's
drifted roll check; structural, all three overloads); the
Add-section dialog's grays + padlocked
speed field wear the same, ↺ buttons deliberately none; graying
the Yaw/pitch-axis row under Force also DESELECTS Global → Local
since v19.12 (D241, Add + Convert — the behavior row's Abs→Rate
precedent: the dot sat on a choice that no longer applied; the
wizard was already correct via its model round-trip; the
Resulting dialog deliberately keeps its persisted pick)):
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
flash; LEFT default), TWO chip rows since v19.10 (D239, user
spec): [Convert left/right part] TOGGLES the side (a second way
to flip it), [Confirm] below applies, [Cancel] to its right
closes. Cuts are EXACT-FRACTION since v14.0 (D89): the cut
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
the de Casteljau identity (locks ride along), INCLUDING its
axis/rate/behavior flags since v19.9 (D238 sweep find: only the
domain copied since v16.x — a global/°/m/absolute section's kept
half silently read as local °/s), s/c elements by
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
PREVIEW popup (GraphEditor pv*): opens on "Optimal placement"
EVERY time since v18.32 (D204 — uncheck per open for even
spacing), typed value + log slider
(spacing 0.02–2 s / 0.2–20 m, accuracy 0.01–10 % — D136's
widening walked back on user feel, v17.3 D140),
conversion re-applied
LIVE per change against a restore of the pristine state
(re-captured per restore — applyDocState ADOPTS snapshot
objects), the ORIGINAL compiled curves dashed behind in lane
colors (target-mode units — FULL sample density through ColPath
since v19.11, D240: the old 400-point stride drew visible chords
on long sections; captured once per open, view-culled per draw;
the reversal guides share the fix), node-count readout; Apply
pushes the
pre-convert state as ONE undo step, Cancel restores it exactly;
canvas presses are consumed while open (view pan/zoom live) —
EXCEPT lane-switch clicks since v19.11 (D240: a dimmed lane's
node/curve inside the preview's own strip switches the VIEWED
lane, read-only — the v16.7 1/2/3-key rule extended to the
mouse; double-clicks stay consumed so the node editor can't open
under a live preview);
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
neighbors, ignores ripple; Delete removes all; F straightens EVERY
segment between consecutive selected nodes onto its chord since
v19.12 (D241 — chordSegment over the selectedPairs set; interior
joints whose chords DISAGREE go honestly BROKEN FIRST, else the C1
far-handle re-angle would overwrite the neighbor chord — exactly the
snap-back the user hypothesized; agreeing collinear runs stay smooth;
far-handle re-angles only at chain ends; locked segments skip; two
adjacent nodes = the classic behavior bitwise; a SINGLE selected node
straightens to its PREVIOUS node, section-firsts no-op); Shift+F
("alignLock") STRAIGHT-LOCKS the same set — Node.straightOut on the segment's left
node, enforced by enforceStraightLocks() at the top of every redraw
(re-chords facing handles; smooth endpoints keep C1 unless that side is
itself locked), drawn as TWIN 1px lines instead of the curve stroke;
endpoints stay draggable (line follows); the FACING knobs are HIDDEN
and unclickable since v10.4 (knobPos null — collinear control points
make the cubic exactly the chord, so they do nothing); Shift+F
with a lone endpoint selected unlocks its locked neighbor(s), or —
nothing adjacent locked — LOCKS the segment to the PREVIOUS node
(D241; Shift+H mirrors: level the previous node onto this one and
level-lock; the node right-click menu carries the "with previous"
entries, D132 hide-inapplicable rule) — a joint whose two sides sit at
DIFFERENT slopes comes out BROKEN (a kinked "smooth" node was a lie;
matching slopes, e.g. insert-on-the-line then unlock, stay smooth);
inserting on a locked segment
locks both halves; saved as an 8th field on `n` lines — old readers
ignore extra fields, missing field = unlocked). Holding S
during a node drag = transient axis constraint from the dominant movement
direction (same guide line as Z/X; slideKey tracked via key filters since
S is not a modifier). Arrow keys nudge the selection by snap steps (Shift+arrow a
TENTH, Ctrl+arrow the NEXT round multiple from the current value
— Gestures.nudge, the D241 modStep recipe since v19.19 D248, both
combine, the per-axis snap references kept, only the pressed
axis moves, no end wall; key repeat glides; undo coalesces per
burst). Menus and
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
from shrinking and the collapse controls go off-screen. 3D view floor: the
ADAPTIVE reference grid at ABSOLUTE GROUND y=0 (Blender-style, v19.12
D241; REBUILT on live feedback v19.13 D242, TUNED v19.14 D243 —
Start y is height above ground, default 21; tracks CAN dig below
it): THREE DECADE MESHES — the coarsest spacing is CAPPED at 100 m
(D243, user spec: 1 m/10 m/100 m grids only; base ≤ 2) — ALL lines
each (D241's skip-10th left multiples of the coarsest spacing incl.
the origin lines drawn by NOBODY, and its skip slots only align
under 10q translate quanta) × 15 GEOMETRIC alpha rings each (inner
edges q·{0,1,1.35,…}, extent 64q). EVERY ring's strength is ONE
pure function of its projected cell size × the GRAZING factor
dRef/dist (Viewport3D.gridAlpha, with a high-side rolloff retiring
a decade whose sparse cells pass ~150 px) × a WIDTH-COVERAGE
attenuation (sub-pixel-wide quads rasterize as shimmering dash
chains; sqrt-grazing, GRAZING-AWARE thresholds since D245 and
HEIGHT-GATED since D246: the width floor slides 0.04 px steep →
0.008 edge-on (cell fade-in start 0.8 → 0.09 px) via c = 1 −
hf·(1−f) with f = ss(grazing/0.45) and hf = ss(dRef/70) — the
long reach unlocks only as the eye RISES, because near the
ground the deep-sub-pixel tail rasterized as whole lines popping
in/out of the MSAA lattice (his "entire lines missing" flicker;
the expand/contract is smoothstep-seamless, his suggested
trade). A flat threshold drop was probed first: the FINEST
decade became a mid-range speckle carpet; the far tail from
height stays an accepted soft dashed haze) × a
DENSITY gate ss(3·dRef/q − 0.3) (D243 — the 100 m grid
appears only once the camera is well away; its close-up lines
read massive before). hw = 0.015·q^0.6 (D243 exponent, D244
halved); ring ladder to 384q with rings past the 5.5 km far
clip never built (D245). The level cross-fade, radial fade
and ortho behavior all emerge from that law — and the lens branch
hides the line decades entirely under ORTHO (the 2D drawOrthoGrid
canvas IS the grid there, its own alphas continuous per decade
since D241/D242; the TOP view strokes WHITE like the 3D grid since
D243, side views keep grey-blue). THREE D242 platform lessons (probe-proven, standing):
(a) the 3D pipeline blends src·1 + dst·(1−α) — material alpha NEVER
dims the source, so fades must PREMULTIPLY (diffuse = o·color at
alpha o) and a zero-strength mesh must be setVisible(false), not
alpha-0 (an α=0 quad renders its full source color); (b) translucent
3D WRITES DEPTH and z-fights below the perspective depth resolution
(~z²/(near·2²⁴)) — the whole grid + axisGroup render DepthTest.
DISABLE at the FRONT of `world`, built in the CONSTRUCTOR (empty
coasters get a floor too): pure submission-order blending, sheet
first, no depth writes. The D248 SEE-THROUGH FLOOR (b′) — the floor
drawn AFTER the track DEPTH-ENABLED with its coplanar layers
sheared onto RANKED TILTED PLANES two depth LSB apart — shipped
in v19.19 and was RETIRED in v19.20 (D249, his "the grid is
flashing like crazy / almost the entire grid is invisible"): the
GPU's coplanar depth bias between two independently rasterized
kilometre-scale triangles exceeds a 2-LSB margin, is PER TRIANGLE
(a losing layer is dropped wholesale), and the eye-following
sheet — the depth reference of every line — re-rolled it every
frame of motion; the D242 probe's "21-79% mis-ordered pixels"
were fights BELOW one LSB with an asymmetric outcome, never the
clean threshold; no margin is both bias-robust and sub-pixel
(the screen float is r·m·0.0073·|eyeY| px), and under ~2 m the
separation sits below float32 vertex rounding. STANDING RULE
(FloorDepth's postmortem): no depth-tested coplanar floor layer
without a MEASURED platform envelope (a coplanar-quad probe at
k = 1..64 LSB on the user's GPU) + a screen-float budget. The
floor is the D242-D246 build BITWISE again (one mesh per ring,
axis quads at y = 0.005, the sheet translate unquantized) and the
see-through look comes from ORDER: the GROUND-SPLIT TRACK LAYER —
every track/tie/plate/heartline/comb/ghost mesh builder routes
its faces by the y = 0 plane (FloorDepth.underMask: UNDER iff the
face centroid's world y < −GROUND_DEADBAND 0.07 = the rail radius;
straddlers by centroid, ties toward ABOVE — a rail lying ON the
ground is one clean tube; a mesh with no under face is left
untouched = the D246 object graph); world order [trackUnder
(ENABLE), ghostUnder (DISABLE)] → [gridGroup = sheet + line
decades, axisGroup (DISABLE)] → [trackGroup, povHolder,
trackBallGroup] → [ghostGroup (DISABLE, D248's composite rule for
the above-ground ghost half kept)] — the underground halves draw
BEFORE the floor so the sheet + lines + axes composite over them,
the rest after it; ZERO coplanar depth tests; while the eye is
BELOW ground the two track blocks SWAP around the floor
(setFloorOrder, a scene-graph reorder on the sign flip only; ±0 =
above; ortho forced above); node markers / boundary + control
disks / reveal balls stay in the after-floor group (pick anchors,
index-walked — an underground one draws over the floor, flagged);
TiePiece carries two groups (the re-tint re-splits); trackUnder
is NOT mouse-transparent; a straddling tube segment is cut per
FACE (≤ ~0.14 m, accepted); ORTHO Top keeps the 2D canvas as the
grid (sheet visible), Side hides the sheet — D246 verbatim.
FloorGate 18 pins the strength law + the split routing; the
15-pose gridshot ladder (its first 9 pixel-identical to a HEAD
f84061a build outside PNG dust; six underground poses) and the
`-Dfvd.gridshot.sweep=<dir>` hook (the ladder's TEMPORAL twin:
s1 a low pan, s2 a grazing yaw + nine rest stills, s3 micro-pans,
a printed per-frame line-pixel count — a dropped layer swings, the
D246 sub-pixel phase drifts) the render; (c) transparent 3D is NOT sorted — order is the scene
graph's. Anchored on the CAMERA EYE per frame off the axes timer
(updateFloorGrid — orbit rotate/zoom included; the old pivot-follow
updateGridLOD sites are gone), per-decade translates quantized to
the decade's OWN spacing q (10q parked the eye-centered alpha field
up to 7q off and HOPPED it per step — D242's "grid vanishes"), base
flips carry HYSTERESIS (up at upp>10^b, down at 0.5·10^(b−1); the
swapped decade sits at strength 0 either way), strengths bucket at
1/256. The ±3500 m single-quad sheet (inside the 5000 far clip — no
far-plane cut line) follows the eye continuously, unquantized, and
hides in SIDE ortho (edge-on it's a shimmering 1 px line). The
MAIN AXES are the ONLY lines on the world zero lines since v19.14
(D243, user spec): buildAxisMesh runs ±6.4 km from the camera
anchor in a geometric segment ladder, each segment's half-width
max(0.09, 0.0016·d) — ~1.5 px at its own distance AND wider than
the 100 m decade's line wherever that decade shows, so the white
zero lines beneath never peek out (the axes draw AFTER the grid,
submission order — D248's highest-rank plane retired in D249) —
strength fading smoothly to the far end;
anchor quantized 30 m. Middle-clicking OUT of the TOP view drops
the orbit pivot on the GROUND POINT under the cursor (D243 —
XZ of the click, y = 0; the ortho-lens screen→world math).
`-Dfvd.gridshot=<dir>` walks a pose ladder and snapshots the
pane per pose — the grid's whole envelope in one run.
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
OPTIONS menu (v16.18, D130 — the SPLIT/CONNECT group sits DIRECTLY
below "Edit node…" since v19.13, D242 user spec: Edit node…, then
SPLIT SECTION HERE, SPLIT TRACK PIECE HERE, CONNECT
WITH PREVIOUS/NEXT SECTION (v16.19 D131 — edge nodes facing a LIKE
neighbor: same type/domain/axis flags; Coaster.mergeSections —
matched seams merge losslessly, split→connect round-trips EXACTLY,
gated 0.0 m; unmatched seams KEEP BOTH VALUES as a steep step one
minimum transition wide since v19.18 D247 — see mergeSections),
then smooth/broken — boundary match on
edges — match-all, level, level-lock, straighten, straight-lock,
delete;
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
cubic — only the shipped default layout keeps 1/3; a SMOOTH SLOPED
last node's out handle CONTINUES its in slope since v19.19 D248 —
Gestures.continueOut, C1 through the joint, the new node holds
flat; broken / locked / 1-node / flat-in last nodes keep the flat
out bitwise; lanes still FOLLOWING the end ride along, D248 end
follow) ·
`Space` POV cam — starts HANDS-OFF (W/S and the S slide key stay with the
graph, so you can edit while watching); right-click the view CAPTURES the
ride controls (mouse hidden AND CONFINED to the pane like fly since
v19.11, D240 — flyLook's confine-only branch warps the cursor
without moving the camera): W/S ride at simulated speed,
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
this was Shift+H's move) — and a SECOND press on an already-flat
node LEVELS it WITH the previous node since v19.12 (D241, value
copy; third press exact no-op; section-firsts only flatten; a
mid-drag broken-node H keeps its single-handle meaning) ·
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
ride; node 0 refuses) · double-click a NODE = the LIVE node editor
(modeless; v19.1 D225 — no Apply: every parseable text change in a
box applies at once through moveNode/handle writes, a blank box
holds, each edit is a GLOBAL undo step with 0.8 s typing bursts
coalesced per box, the node re-resolved BY INDEX per apply so Ctrl+Z
leaves it live; boxes are GraphEditor.LiveNum = TextField + ▲/▼
steppers + wheel per the D237 global spinner rules; NO Close
button since v19.10 (D239 — Esc/Enter/X close, the grid's own
padding is the bottom margin); ONE COLUMN throughout since v19.11
(D240 — a broken node's slopes sit directly BELOW their handle
lengths as "In slope"/"Out slope" rows, a section-EDGE node's
single slope keeps the D239 "Handle slope" row); the handle
boxes take the D240 EASE STOPS — see the spinner quirk) for
transition
time, transition Δ, and handle fractions (grey
COMPUTED TWINS beside the fields update live as you type — the
absolute axis position and the Δ from the previous node, v16.15 —
and CLICKING a twin SWAPS which representation the box edits since
v19.12 (D241, user spec: titles flip to "Absolute time (s)"/
"Absolute position (m)" and the Δ form, the twin shows the other
representation, snapping follows the box, applyPos
converts back — abs mode maps warpInv(box − section start), delta
mode adds the previous node's value; the speed-popup "Enter:"
hover-outline pattern, constant transparent border; per-dialog-
instance flags, reopening resets; node 0 has no twins; the
undo-refresh hook re-seeds mode-aware and re-runs both twin fills,
closing the old stale-twin corner). The VALUE row wears the LANE'S
OWN quantity name since v19.13 (D242, user spec — laneQtyName:
Vertical G / Lateral G / Roll / Pitch / Yaw / Bank / Elevation /
Heading + the unit, EXCEPT "(g)" which the G names carry, D243;
the click-swap delta title is "Δ " + the same,
replacing "Change in value"; the influence surface prefixes its
own Δ). The WHOLE dialog is LIVE-SYNCED since v19.14 (D243, user
spec): a resync hook runs at the top of EVERY redraw
(GraphEditor.redrawHooks — outside edits to the node, its
handles, or the previous node update every box and twin in real
time; a FOCUSED box is never overwritten mid-typing, undo
restores force-overwrite, and LiveNum.set() no-ops on equal text
so the steady state is free AND the ease-resume anchor survives
— an unconditional write wiped it every redraw);
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
the View menu; fly look deltas are capped ±35 px/event to tame OS pointer
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
snapped insert dot leads with the Δ from the PREVIOUS NODE since
v19.3 (D232 — was the global axis position, v16.16: it reads as the
transition an insert would create) — differencing the baked
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
lanes shift; transition shape transfers to the hold node; the
selected node's slope continues into the hold and the hold node's
in handle is re-angled to it since v19.19 D248 —
Gestures.matchInToOut, a truly smooth hold node; a lock carries
onto the hold→next half); node dialog
edits transition time + GLOBAL value. The 3D view and graph panel sit
in a vertical SplitPane — divider resizes, and a ▼/▲ button overlaid on the
3D view collapses/restores the whole bottom panel; while COLLAPSED the
editor UNLOADS (v18.36, D208 — redraw() hard no-op via graphCollapsed;
markers/gizmos stay synced through rebuildMeshes' direct refreshMarkers,
expanding repaints via the divider listener).
Node-drag readout shows Δt from the previous node and the GLOBAL value with
units ("Δt=0.80s  2.50 g"). Toolbar "Ripple:" checks
(minimal filled-square checkboxes, per-axis): "time" shifts every later node
of the lane in t with the drag (preserves later transition times); "value"
translates the rest of the graph vertically. Menu bar: File (New… Ctrl+N —
unsaved-changes gate (Save / Don't Save / Cancel, also guarding Load
AND closing the app since v19.6 D235 — Cancel keeps it open),
fresh 5 s force/time doc at y = 5 m and 5 m/s (NEW_START_Y/SPEED/LEN,
v18.32 D204 + v18.38 D210 — the STARTUP demo layout keeps 21 m /
26 m/s; legacy-load
defaults untouched) and then the START SCREEN over the graph (v19.1
D226 — the default doc is the "Skip" outcome; a pick replaces it),
empty history, the WINDOW TITLE "Untitled — Ultimate Vector Design"
(FvdDesigner.APP_NAME since v19.20 D249 — "<file>* — Ultimate
Vector Design" while dirty, "[name]" for multi-coaster documents;
the Help window "Help — Ultimate Vector Design", the .nl2elem
description "Ultimate Vector Design Export" and the settings-file
header comment are literals in their pure-Java classes; the
settings FILE NAME ~/.fvd-designer.properties and the package name
`coaster` are unchanged — docs/PACKAGE-RENAME-PLAN.md holds the
package rename for its own commit); Save Ctrl+S writes
straight to the OPEN file, Save As… re-targets; the WINDOW TITLE is the
open file name with * while dirty; +
Export… — rebindable "export" action Ctrl+E; ONE dialog since v18.29
(D201) with a "Track piece" dropdown (v19.1 D223: Whole track | Piece
k/n, hidden for one-piece docs — a piece exports its own compiled
sample range incl. a trailing bridge + the seam sample; LIVE since
v19.18 D247 — piece labels follow edits and the track is re-read at
export time after settleIfPending), FORMAT
radios (Spline (CSV) | NL2 Element .nl2elem — the
FVD++ exporter format), rail-center-vs-heartline radios and a
segment-length spinner, all persisted per-user in AppSettings; the
explanation paragraph is GONE (user ask); spacing rounds to a whole
number of equal segments; both formats negate Z for NL2's right-handed
world — see D40/D201 before touching signs), Edit (a D241
"Preferences" rename was REVERSED in v19.13 D242, user spec —
Shortcuts dialog — TWO titled category columns since v10.3 (Graph
editor | View/File/History, ACTIONS carries the category as its 4th
field); rebind by clicking + pressing a key, ↺ resets an edited
binding to default; SNAPPING dialog — renamed from "Options" in
v16.14 after the viewport rows left: snap steps (the v13.9
bridge-optimizer rows were
REMOVED in v13.10, D84 — internal constants again) and snap axis
references; the POV graph marker is ALWAYS ON since v16.14;
RESOLUTION SCALE… dialog (v19.12, D241): the Blender-style whole-UI
factor — slider 0.75–1.75 snapped 0.05, ↺ at 1.0, "Applies at the
next launch." (restart-only, a platform fact — see AppSettings/Main
above) + an "Apply and Restart" button since v19.13 (D242, his
"live like Blender?" ask — a true live apply is impossible:
FvdDesigner.restartApp spawns a fresh JVM (same java/JVM args/
classpath on uvd.Main) through the unsaved-changes gate and
exits)), VIEW
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
D126 — FVD++-style; teeth 2× wider along the track since v16.15;
1.5× DENSER + 1.4× BIGGER at "100%" since v18.48 D220 —
COMB_SPACING ⅓ m, COMB_SIZE_BASE 1.4 bakes the old 140% look
into the slider's default; time-comb fillers follow the spacing):
one tooth per ⅓ m at EXACT arc spacing
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
radiusTxt, r=57.3/val, zero components read (r=∞); TIME mode
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
— the SELECTED marker radius 0.26 vs 0.22 tinted
laneColor.interpolate(WHITE, 0.4) since v19.19 D248 (MARKER_R /
MARKER_R_SEL / MARKER_SEL_MIX; was a big white sphere),
signature-guarded rebuild on graph redraws (the signature carries
povActive); a clean CLICK on one selects that node in the graph and
re-centers a manual zoom window onto it (v16.11 — marker pick beats
the track-activate click, 14 px — a speed-control DISK wins at
PRESS across its whole projected face, floored at ViewDisks.PICK_PX
12 px, D248 item S1 / D249 item S3; a marker click also drops the
strip's control selection — takePlotSelection, D249; node 0's
marker is hidden under a junction ball — run heads, bridged-in
pieces, followers with a seam ball, junctionBallCoversNode0 D249); a
DOUBLE-CLICK opens that node's
LIVE editor since D248 (GraphEditor.openNodeEditorFromViewport —
the marker pick runs BEFORE the double-click branch, so within 14
px of a marker the track insert no longer fires; refused with
stats reasons under the convert preview / the start wizard; the
entry ball's double-click keeps opening the Track Piece window). PLACEMENT GIZMOS (v16.11 D123,
reworked v16.12 D124; BALL-CLICK REVEAL since v18.16 D187,
reversing v17.7 D144's always-on): only the WHITE BALLS show until
one is clicked — ONE BALL PER JUNCTION of the active RUN since
v19.18 (D247, record Reveal(piece, kind)): the head's entry ball +
each follower's seam ball at seamWorldPos (kind 1, the piece/seam
rig) + each trailing bridge's end ball at the next piece's x/y/z
(kind 2, the bridge rig; a piece bridged in from within the run
gets no entry ball of its own — its double-click opens the BRIDGE
rows); a single click also ACTIVATES that piece's section 0 (kind
2: the next piece's) — and DESELECTS the graph nodes since v19.19
(D248, item S1: every rig-part press runs deselectFromViewport
before the busy gate; the D247 kind-1 node-0 select is gone),
double-click activates then opens the owner's settings (bridge-end:
the bridge SECTION so Bridge properties syncs), drags never
activate (D188); the same ball again, any primary press off the
gizmo, or activation LEAVING the run hides it (gizmoReveal, per
pane, in the rebuild sig — which signs every run piece +
`seamWorldPos != null` per follower; the trailing-bridge gizmo
paths are piece-indexed overloads whose no-arg forms delegate
bitwise; revealEntryGizmo(idx) = Copy's handoff; ball clicks/drags
+ rig presses refuse under convertBusy(), and the bridge-end track
ball's drag routes through placementEdit). The sig LEADS with
FvdDesigner.docGen
since v18.33 (D205): applyDocState/Load/New replace the TrackPiece
OBJECTS — often with identical values (a convert preview's Apply, a
lanes-only undo) — and the value-only sig kept the ball/rig
closures writing to an ORPHANED piece ("only the gizmo moved, not
the track", his geo-convert repro). AppSettings.showGizmos is DELETED;
gizmoLocal is SESSION-ONLY (every launch starts Global) and the
TOP-RIGHT per-pane Local/Global button is back, visible only while
revealed. Since v18.17 (D188) the rigs render in a transparent
OVERLAY SUBSCENE with a real depth buffer (proper self-occlusion,
still over the whole world; cameras mirrored per frame; PERMANENTLY mouse-transparent since
v19.19 D248 — every rig part registers a GPart and the pane-level
SCREEN-SPACE PICKER gizmoMouseFilter picks in pixels: ONE
nearest-by-px pass since v19.20 D249 in which a center ball is
measured from its silhouette EDGE (Gestures.ballEdgeDist =
max(0, d − rPx) — inside the visible ball 0 px, it wins; the edge
band rPx·(PICK_BALL_MUL 1.5 − 1) + PICK_BALL_PAD 6 px keeps D248's
outer reach; an exact tie goes to a BALL — every arrow shaft and
an edge-on ring pass through the rig center at 0 px; D248 ran a
balls-first pass within radius × 1.5 + 6 px), the nearest arrow
(segment ≤ PICK_ARROW_PX 9) or ring
(48-point projected polyline ≤ PICK_RING_PX 6; adjacent rings'
bands overlap, nearest-by-px resolves it); the gesture rides
gPress in the filter, never the pressed node; only PRIMARY
presses whose target is the 3D content are consumed (targetIs3D
— the view label, tangent buttons, collapse button and Gizmos
button keep their clicks); fly/POV take nothing but a live
gesture still ends on its release; a refused convertBusy press
posts CONVERT_BUSY_MSG once; hover glow central (setGizmoHover);
-Dfvd.pickprobe=1 drives it; D188-D247 it was pickOnBounds(false)
+ mouseTransparent-when-empty pass-through) — D187's
DepthTest.DISABLE painted in submission order (rings over arrows,
inside-out tori). The un-revealed white ball
is FIXED-size and lives with the TRACK (trackBallGroup ∈ world,
occludable); dragging it moves the owner AND reveals mid-gesture
(rig-only build; since D248 the pressed ball is HIDDEN the moment
the rig builds — revealDuringDrag — because the picker's capture
never depended on the node; D188-D247 replacing the pressed ball
would have killed mouse capture; mid-drag MODIFIER combos are
suppressed while isGizmoDragging = past the deadband OR a part
still pressed, bare keys unaffected); the revealed rig carries its own SCALING center ball
(0.272 × rig.scaleT = the D186 proportion; click hides, drag
moves, dbl-click settings; wireBall wires both). The spheres
remain as ANCHORS — a ball click DESELECTS the graph nodes (D248,
item S1; it selected node 0 v18.17-v19.18), the seam ball's
double-click still opens the seam settings. v18.18
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
only commits on ENTER — and a BLANKED editor commits clamp(0) at
EVERY commit entry since v18.40, D212: BlankSafeConverter wraps
the factory converter so Spinner's own internal commits obey the
D192 rule too, and scene-level press filters +
commitStrayNumericEditors commit the focused editor even in the
non-autohide popup panels where no focus event ever fires);
narrow spinners widened to 82 px;
"Reverse piece…" label (→ "Reverse…" in v19.18, D247). Arrows X green / Y blue / Z red (v16.13); rings wear
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
replacing T/Shift+T; DROPDOWN-ORDER numbering since v19.13, D242:
1 Top, 2 Front, 3 Back, 4 Right, 5 Left,
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
v18.9 D180) — top enters at ONE FIXED orientation since v19.12 (D241,
user spec: world Z up the screen, X right — as if tilted down from
Front; the old nearest-90° yaw snap made the map depend on where the
camera pointed), side
centers the pivot on the SELECTED node's track point AND yaws the lens
TANGENT to the track's heading there (perpendicular look direction,
nearer of the two sides kept; no selection or vertical tangent = world
X–Y elevation; D53) (selected markers
draw white AND larger, 0.32 vs 0.22 m); RIGHT-drag pans in the view
plane without exiting ortho (grab-style, world-units-per-pixel from the
lens; right-click does NOT hand off to fly while ortho); leaving for
fly/orbit/POV — the same key, the view-list pick, or fly/POV entry —
RESTORES the FULL pre-ortho perspective pose since v19.12 (D241:
angles, zoom AND pivot via exitOrthoRestorePose + the savedPiv* stash;
fly used to adopt the top-down direction, "fly lands where the top
view was"); orbiting away still keeps the current angles (the
deliberate adopt path); scroll zooms multiplicatively
while ortho, far clip 200000 while
active. The view-label dropdown wears shortcut tags since D241
("(1)"…"(6)" fixed digits + the live POV binding). Toolbar "Resulting ▾" is a plain Button + ContextMenu (a
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
(x/y/z ±500 m + yaw ±360°, the ACTIVE piece's placement — labeled
"Anchor x/y/z (m)" / "Anchor yaw (°)" / "Anchor pitch (°)" / "Anchor
roll (°)" (named by v19.18 D247, the pitch/roll row SPLIT in
v19.19 D248; a RUN is what the user calls "the track piece", its
head entry "the anchor"); the x/y/z rows show and edit the RUN
HEAD ALWAYS since D248 (placementTargetIdx/Ref — D247 generalized
only followers; a bridged-in piece's own position lives in the
bridge window's Next piece x/y/z row) while a follower's
orientation rows + padlocks gray with the junction-named reason —
re-synced on piece switches; saved per `piece` line; y = height
above the y=0 ground, default 21) MOVED to the toolbar "Track
Piece ▴" button (renamed from "Placement ▾" + made a MOVABLE dark
popup, v17.8 D145; since v19.19 D248 a LIGHT owned LiveWindow
STAGE placed just above the graph canvas at the button —
FvdDesigner.openTrackPieceWindow, toggled by showPlacementPopup:
open = close WITHOUT revert; rows [Anchor x/y/z][Anchor yaw + ↺]
[separator][Anchor pitch + 🔒 + ↺ + grey "design 12.34°"][Anchor
roll …][flag row][resolve row][ops row][Confirm / Cancel] —
Confirm/Enter/X keep, Cancel/Esc = the FULL-SCOPE PlacementSnapshot
of every piece + trailing bridge restored BY IDENTITY with direct
writes since v19.20 D249 (FvdDesigner.cancelRestore — a mid-window
Design → Real bake reverts too, graph edits survive, "Cancel
skipped — the document changed underneath this window; use Ctrl+Z
to go back." on a coaster/docGen mismatch; D248's per-field
TpRecord revert is gone), RE-CAPTURED when the active piece or run
head changes under the open window and after undo restores; the ↺
revert per row (the design tier's r.dPitch/r.dRoll under
DESIGN_ONLY); the node editor takes the same placement) next
to the piece selector in v13.2 (D74), along with "Reverse piece" (the
ops row reads Reverse…, Mirror, Copy, Switch Coaster…, Delete… —
the last since v19.20 D249: a WINDOW_MODAL dark confirm "Delete the
whole track piece (N pieces)?" then Coaster.deleteRun removes the
whole RUN (bridge + targets), activates the run after else the
previous head, may empty the coaster (D239), v0 follows a deleted
first run; grayed under the wizard / convertBusy; RunGate j) —
the Coaster menu keeps only truly per-coaster knobs. The window — the
only surface for FREE pieces (v19.0 D222 removed the D221 bridge/seam
copies; since v19.18 D247 the STRICT SEAM WINDOW carries its own Lock
Track Shape + design readouts + Design ↔ Real buttons for followers,
and since D248 the BRIDGE window the TARGET piece's — see the
strict-seam and bridge paragraphs) — wears the MODE ROW
(LiveWindow.flagRow → tpFlags, v19.20 D249; D248's "Lock Track
Shape (creates orientation conflict)" + "Only Affect Orientation
Until Next Strict Seam" row and D247's lockShapeRows are gone):
CheckBox "Edit Real and Design Orientation Independently" (off =
LOCKSTEP — pitch/roll edits move design AND true together, the
shape re-solves, a frozen conflict offset rides along; on → the
two RadioButtons below it, "Edit real orientation (locks track
shape)" = REAL_ONLY pre-selected (tooltip verbatim "Design
orientation does not change for adjustments to pitch/roll" — the
D100 rigid tilt, the conflict grows) / "Edit design orientation"
= DESIGN_ONLY (the BOX shows/edits dispDesignPitch/Roll, the real
entry stays put; grayed "Optimize position and orientation must
not be selected." on a PY bridge target)) then "Affect Subsequent
Strict Seams" (TrackPiece.affectSubsequentSeams, default OFF,
HIDDEN while the check is off, grayed "Applies while editing the
real orientation." under DESIGN_ONLY): ON = the rigid downstream
CARRY takes every connected piece along (Coaster.carriesDownstream
= REAL_ONLY && affectSubsequentSeams), OFF = the edit stops at the
piece's own exit junction (bridges re-march/re-solve, followers
keep their held angles); ONE mode writer, a flip first commits a
typed-but-unentered box to its old tier (LiveWindow.settleTyping);
toggling never moves anything, one undo step, the compile never
reads any of it. The grey per-row READOUT (LiveWindow.Readout,
synced per hook via syncDesignReadout) shows the OTHER tier —
"design …°" under LOCKSTEP/REAL_ONLY, "real …°" under DESIGN_ONLY
— whenever the check is on or the piece is conflicted, and CLICKING
it swaps the radio (the node-editor twin recipe, tooltip "Click to
edit this orientation instead"; a plain label while the target
choice is grayed). While design ≠ real ONE
button line shows — "Design → Real
Orientation" (design := true, shape re-solves at the real frame —
with ABSOLUTE lanes on the piece a DIALOG intervenes since v19.11,
D240: absolute angles re-assert themselves whatever the entry
frame, so the plain write SNAPPED the track back to the design
shape (probed 28.9 m on his file); per absolute quantity the user
picks Preserve (that snap, informed) or Convert — Coaster.
designToRealConvert refits the chosen lanes from the CURRENT
tilted compileForFit samples with the section's own flags + the
D238 heading ref, installs only those lanes in place, then writes
design := true; commits through the density preview, 0.40 m on
his 37 m file at default density; GraphEditor.
showDesignToRealDialog / FvdDesigner.designToRealDirect) /
"Real → Design Orientation" (true := design, rigid un-rotation,
chain carried back). Hidden for strict-seam followers (seam-owned —
the seam window has its own copies since D247); under an
optimize-orientation bridge only "Real → Design" grays (D248 —
"The optimize-orientation bridge owns this piece's pitch/roll —
switch the mode or lock the axes."; the check, the real radio and
Design → Real stay enabled in all three windows; the design radio
and that bridge mode exclude each other, D249). Start speed is
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
longer auto-fits overlays) — EXCEPT the roll/bank pick since
v18.42 (D214, resRollOwnScale): whenever its units differ from
comps 1/2 (geo view = g vs rates; absolute bank = degrees) it
normalizes on its OWN pair (resLo3/resHi3, comp-aware resToY, own
defaults ±60/±5/±90) and the axis title appends "· roll °/s own
scale" — a legit −112 °/s roll rate used to stretch the g axis to
"−125 g", the user's scale-blowup report; force-view rate roll
still shares (same units) — and hovering an overlay shows a dot + its
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
its own ORANGE (BANK_COLOR rgb(240,110,35) since v19.10 D239 —
was the red-orange rgb(235,70,45), v17.7 D144 —
REAL absolute-bank lanes wear it too since v17.9 D146, and the
roll/bank overlay draws NO seam caret since v17.9; the Resulting
popup DRAGS like the Track Piece panel, drag = hover-dismiss
stands down); the roll overlay
works in BOTH views since v17.7 — grayed only while it would
duplicate the active section's own roll representation,
resRollRedundant; resulting default scales match the lane
defaults incl. ±5 °/m and ±90 bank)),
Append (the toolbar POV button LEFT in v19.8, D237 — Space and
the view-label dropdown are the surface; no shortcut hints on
buttons); the Resulting overlays span the whole
RUN since v13.11 (v10.8 made them ride-wide, but the global timeline
continues into DETACHED pieces, whose curves bled in at the window's
right edge — overlays, true strokes, their hover and the scale fit
all clamp to [runT0, runT0+runDur] now, D85). The BOTTOM STATS LINE
is the LIVE POV POSITION since v17.11 (D152): X/Y/Z, speed,
vertical + lateral G — TRUE values, three decimals — plus the
absolute GLOBAL Pitch/Yaw/Roll angles with their °/s rates since
v18.39 (D211: eulerOf + worldRates on the true arrays; yaw
DISPLAYS reported-left per D186; vertical track reads "—")
(FvdDesigner.updatePovStats — it never lived on Viewport3D, doc
corrected; refreshed per rebuild/ride frame/POV
scroll; povTime persists across POV exits so the line stays live
while editing, and the purple POV marker line on the graph draws
ALWAYS now). FIXED SLOTS since v19.10 (D239, user spec — no more
horizontal jumping): the POV values render into per-field labels
(buildPovRow/povSet) whose widths come from worst-case templates
measured in the real font, values left-anchored, captions their
own fixed labels; an extreme value RATCHETS its slot wider once
for the session (never narrower) so overlap can't happen and
nothing moves per frame. The row stacks over the plain statsLabel
in one StackPane: ANY setText on statsLabel (refusal reasons,
save/load lines — the bar is dual-duty) reclaims the bar until
the next POV update, exactly the old clobber order. The five coaster stats (ride time, track length,
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
RE-REWRITTEN in v19.11 (D240, user spec, superseding D157's density):
HALF the length, purely user-facing — say what things DO, never how
they work inside; NO all-caps emphasis; DIAGRAMS where they teach
faster than words (helpCanvas rows — the node/handles intro, the
editor-landmarks map, smooth-vs-broken knobs, the pieces/run sketch,
the gizmo). Keep new entries to a line or two in their section; the
changelog lives in DECISIONS.md, not Help. (Canvas fillText cannot
render ⊕/emoji glyphs — draw shapes, label with plain words.)

## Known quirks / accepted debts

- OUTLINED/HIGHLIGHTED BUTTONS (v19.4, D233 — STANDING user rule
  after the crooked seam-padlock outline): never draw a border over
  JavaFX's DEFAULT button background (multi-layer, unequal insets,
  its own radii — it never lines up). Use FvdDesigner.outlineStyle:
  ONE background layer, -fx-background-insets: 0, and the SAME
  radius on background and border. Highlight-by-background (the
  bridge padlocks, the 🔗 link) is fine as-is.
- DETERMINISM CONTRACT (v18.25-v18.26, D197/D198 — STANDING user
  requirement for every future change): (a) compile(doc) is
  bit-deterministic; (b) a rebuild at the fixed point is a
  bitwise no-op (the rebuild loop settles the reconcile/
  re-optimize write-backs, so interaction alone never changes
  the track); (c) save→load→rebuild reproduces the session
  BITWISE (Double.toString serialization + admit-what-the-app-
  writes load clamps — chaotic stall knots included). Run
  DetermGate197/198 for ANY change touching compile, reconcile,
  or IO. PLACEMENT MOVES ARE BITWISE-RIGID since v18.27 (D199,
  canonical-frame generation + energy-height Pass D): piece
  x/y/z AND yaw never touch the generation or real-march
  arithmetic — zero reconcile writes, shape bits identical,
  chaotic stall knots included (DetermGate199). Pitch/roll
  tilts stay rigid by construction. Deliberate limits: bridges
  re-solve to world endpoints (rigidity ends there), held-angle
  strict followers re-aim under upstream yaw (user-accepted),
  tilted pieces/smoothing windows keep world-delta trueV dust.
  v19.0 (D221/D222) adds: DESIGN-tier pitch/roll edits RE-SOLVE
  by design (x/y/z + yaw moves stay bitwise via designCanon's
  additive psi — but ONLY while no conflict rides: a live
  design→true conflict's yaw moves keep the D100 tilted-piece
  ulp dust, the tilt quat being yaw-conjugated; design (0,0) and
  design == true are both bitwise-clean — OrientGate c/d); the
  RIGID carry is geometrically exact (translation 9e-14,
  rotation 6e-14 on pieces, bridge spine rigid with only its
  TIME sampling re-derived over the new heights) but a
  follower's UNLOCKED axes re-aim at second order under a
  pitch/roll carry, a vertical-heading bridge target carries
  position-only, and spinner-click carries compose per commit
  (hand-revert leaves dust; Ctrl+Z is the exact path). Old
  files: no porient line = LOCKSTEP + (0,0) + no carry since
  v19.20 D249 (D248: unlocked + Only-Affect; D222: locked — the
  compile reads none of it, so the track is bitwise; only the edit
  routing changed: pre-v19 files re-solve under placement edits,
  v19.0-v19.18 files keep their saved lock as REAL_ONLY, a D248
  4-field line's carry bit is inverted on load; all four
  DetermGates green untouched).
  Files saved by pre-v18.25 builds settle ONCE on first open
  (mid-cascade saves / old quantization) and EVERY pre-v18.27
  file re-settles once (the canonical arithmetic re-baseline),
  then never again. Ctrl+Z is bitwise always; HAND-reverted
  graph edits return bitwise too since v18.28 (D200 — the
  reconcile's ±1e-3 tube is CLOSED: rebuildTrack's at-rest
  EXACT settle writes vDesign on BIT difference, carry off, so
  the settled speeds are a pure function of the graphs; live
  gestures keep the fast tube and settle on release/KEY_RELEASED
  via gestureHot/settleIfPending, and saveFile settles first —
  the doc can never rest or save inside the tube. The one kept
  memory is the D101 design/true anchor choice.)
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
  at the 0.5 floor — FINAL by user decision (D195): the ghost
  track + the instant POV wrap ARE the stall model; no rollback
  simulation. REVERSAL corners: a natural-speed reversal STALLS wherever
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
  mirrored flow is ill-defined — the UI pre-checks with its own
  reason + the panel button grays, D235; remove them first).
  INFLUENCE takes controls since v19.6 (D235 — the D160 gray is
  GONE: ref/profile/pass-2 share one Ctl); CONVERTS have carried
  controls since v18.0 in every flavor incl. domain flips
  (CtlConvProbe);
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
  ride start (D171, his clarification of D168's stop; the wrap
  fires the INSTANT the interpolated speed reads zero since
  v18.23 D194 — the old gate crawled a full design-sample past
  the zero point first) — the creep-through model + ghost track
  are the FINAL stall story (user decision, D195: no rollback
  simulation). PER-LANE convert preservation (v19.9, D238 —
  generalizing D169's roll-only case, the user's verified theory):
  a lane whose MEANING the requested flag flips don't touch keeps
  its authored nodes BIT-EXACT — roll-only changes keep lanes 0/1
  (D169, gate 55j 3 mm), yaw/pitch-only changes keep the roll
  lane, rate-unit-only changes keep absolute-angle lanes (angles
  aren't rates, D213); a FULLY IDENTICAL flag tuple preserves
  NOTHING (the identity convert = the deliberate re-author-at-
  density tool). Coaster.laneKeepMap/preserveLanes; the preview
  auto-selects the first re-fitting lane; bakeAxes keys the map
  off the fit's ACTUAL targets (fixes the D237 bug where a
  roll-only bake on an abs-yp section shipped angle lanes into a
  rate-flagged output).
- ALL TOOL WINDOWS ARE MODELESS since v18.1 (D166 — viewport controls
  work with any window open; installViewKeys forwards ride keys from
  every secondary Scene; every numeric entry is digits+period only).
  LIVE since v19.18 (D247 — see TOOL-WINDOW BEHAVIOR RULES):
  modeless windows resync per compile; Apply-only dialogs (Element,
  Add-section, Design → Real, Insert) re-resolve their target BY
  INDEX at Apply and post a stats reason when it changed (the old
  "applies to the captured object" corner is closed); the same
  dialog can still be opened twice.
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
- SPLITTING an INFLUENCED section is MACHINE-EXACT since v19.7
  (D236 — the hybrid's Markov property; 1.4e-12 m gated, no anchor,
  InflSplitGate). vAuthored is an INERT mirror everywhere — no
  divergent-pair corners survive.
- GLOBAL SPINNER STEP CONVENTIONS (v19.8 D237, MODIFIERS SWAPPED
  v19.12 D241 — STANDING, every value box present and future;
  installed by numericEditor so nothing can miss it): the scroll
  WHEEL steps any spinner by its increment (always consumed),
  SHIFT — wheel or arrow click — steps at ONE TENTH, CTRL SNAPS
  to the next round multiple of the (possibly Shift-scaled)
  increment (0.435 → 0.5 → 0.6). Node/handle DRAGS keep their
  historic Ctrl-snap — the D241 swap is value entry boxes only.
  HOLD-TO-REPEAT everywhere since v19.11 (D240, STANDING): plain
  arrows keep the native skin repeat; consumed MODIFIER presses
  and LiveNum's ▲/▼ arm FvdDesigner.HoldRepeat at the native
  cadence (immediate, 300 ms, then 60 ms; modifiers captured at
  press; MOUSE_RELEASED/disable/scene-loss stop it — D237's "no
  release handling needed" note is superseded). The Windows
  Shift+wheel deltaX fallback is load-bearing; the 1-2-5 walkers
  keep their series. The node editor's LiveNum boxes follow the
  same rules — and its HANDLE boxes add EASE STOPS (D240):
  stepping always stops on a named ease fraction (cubic 1/3, sine
  0.3634, quintic 7/15, circle 0.5523) lying between the current
  value and the step target; landing remembers the pre-stop grid
  and the next step resumes it (0.31 → 0.333 → 0.36); a box
  opened/typed ONTO an ease value steps to round multiples; on a
  stop the field wears a soft blue ring + a name tag. A
  stop within 5e-4 of the CURRENT value is SKIPPED (D241 — the
  3-decimal render lands 0.333 just off the exact 1/3, and the
  exact stop then sat between the box and every target: frozen; a
  stop is an inserted snap point, never a wall). LAYOUT-NEUTRAL
  since v19.13 (D242 — his screenshot): the ring is LAYERED
  BACKGROUNDS, not a border (a CSS border joins the control's
  layout insets — the D240 border grew the field 4 px on every
  stop and shoved its arrows), and the tag is an UNMANAGED overlay
  floating past the arrows over the empty gutter (any reserved
  width held the grid column open and pushed the readout twins
  right). D243 refinements: an unmanaged node is NEVER RESIZED by
  its parent — without an explicit autosize() the tag stayed 0×0
  and rendered nothing (the vanished-tag bug); the ring is 1 px
  (half of D240) and a FOCUSED box adds an -fx-focus-color layer
  at negative insets (outside the bounds, layout untouched) since
  the ring style replaces the skin's own focus visuals. D244: the
  tag re-places off the managed children's boundsInParent (the
  layoutBounds listener fires BEFORE the first layout positions
  them — the tag opened INSIDE the field) + a runLater pulse.
  NEGATIVE-ZERO display rule (D244, re-specced D245 — standing):
  FvdDesigner.z3 snaps only |v| < 1e-6 to +0.0 before formatting
  (a flat handle's slope is inDv/effInDt with effInDt NEGATIVE —
  ±0 numerators yield −0.0, and a typed 0 stores −0.0 back via
  0·(−dt)); LARGER sub-display dust deliberately KEEPS its sign
  ("-0.000" honestly means a-little-negative, user spec). Applied
  in LiveNum.fmt, the dialog twins, the POV bar, hover/drag slope
  readouts — use z3 for any new readout that can hit zero. Typed
  values always apply at FULL precision (display-only rounding;
  a stepper steps from the DISPLAYED text, so stepping after a
  sub-display entry starts from the rounded value).
- NEVER SHIP A CLICK THAT SILENTLY DOES NOTHING (v19.6, D235 —
  STANDING user rule): anything prevented must be GRAYED with a
  reason on HOVER — MenuItems use GraphEditor.grayItem since v19.7
  (D236: an enabled CustomMenuItem holding a gray tooltip'd Label,
  full-sentence reasons — the D235 "(reason)" parentheticals and
  the shortWhy compressor are retired; SELF-MEASURING left pad
  since v19.10 D239: the skin puts CustomMenuItem content at the
  bare left inset while native TEXT sits after the menu's
  check/graphic COLUMN, which exists only in menus holding
  Check/Radio rows — the badge menu — so a fixed pad is wrong in
  either direction; the label measures a native sibling's x after
  layout via a runLater hop and pads to match, pixel-exact both
  menu kinds, MenuAlignProbe-verified), Controls/rows take
  GraphEditor.grayHint
  (String, live Supplier, or Node-row overloads); ops that can
  still fail at apply time post the full sentence on the stats
  bar. Pre-check menu items where a dry-run exists
  (makeSmoothingRealWhy → grayItem with the full sentence is the
  pattern). TOOLTIP ACCURACY is part of every change: a batch-24
  audit of ~124 hover strings found 8 stale ones (D239) — update
  the touched tooltips with the behavior they describe.
- TOOL-WINDOW BEHAVIOR RULES (v19.6, D235 — STANDING): every
  non-autohide Popup takes the pv focus-hide pattern (hide on app
  focus loss, return on refocus — pvPopup/spPopup are the two
  copies; the seam pop-out is an OWNED LiveWindow Stage since
  v19.19 D248 and needs none, like the Track Piece / bridge
  windows and the node editor); during fly/captured-POV capture ALL open tool
  windows STAY VISIBLE and live-updating since v19.8 (D237,
  superseding the D235 hide: syncCaptureWindows wires each
  window's scene with Cursor.NONE + mouse-move FORWARDING into
  the capturing pane's flyLook — screen-coordinate math, so a
  window under the warped hidden cursor no longer starves the
  look — a right-click over one releases the capture,
  presses/scrolls swallowed; release unwires). HARDENED v19.11
  (D240 — the user's cursor flashes): every NODE-level cursor in
  a wired window is stashed + nulled (the TextField I-beam is CSS
  and beats the scene NONE — the deepest non-null cursor wins,
  and the cursor update runs outside the dispatch chain, so
  consuming events can't stop it), resizable Stages go
  non-resizable for the capture (native resize-border cursors),
  Stages owned TRANSITIVELY by the main stage wire too (the
  color picker owned by Rail Settings), windows opened
  MID-CAPTURE wire via a Window-list watch (Ctrl+Z rebuilds the
  seam pop-out, Ctrl+E opens Export), and the move filter
  re-sweeps cursors every 25th event. CAPTURED POV also CONFINES
  the mouse like fly since D240 (flyLook's confine-only branch
  warps without moving the camera; both capture entries init the
  Robot + center). New windows must follow all of this unless
  explicitly specced otherwise. Also: typed text NEVER
  fires actions — handleKey/forwardViewKey return early while a
  TextInputControl holds focus without a shortcut modifier.
  LIVE WINDOWS (v19.18, D247 — STANDING): every modeless window
  registers a READ-ONLY resync in FvdDesigner.modelHooks on show
  and removes it on hide (fireModelHooks runs LAST in rebuildTrack
  + its early returns and from GraphEditor.redraw after
  redrawHooks; re-entry-guarded, docApplying held, per-hook
  try/catch; the pairs: node editor, seam pop-out via seamHook/
  hideSeamPopup, speed popup via spHook/spHideCtlPopup,
  initial-speed dialog, Coaster Options, Track Piece window via
  trackPieceHook, the bridge window via bridgeHook (D248 —
  placementOpsHook until then), StartPanel via wizardHook, Export); hooks never
  pushUndo/markDirty; model objects are resolved BY INDEX at every
  use (Supplier captures, never the open-time object — the speed
  popup wrote a stale control before); focus-guarded writes go
  through FvdDesigner.setUnlessTyping — a box being typed in is
  never overwritten EXCEPT by an undo restore (docRestoring spans
  applyDocState + rebuildTrackRestoring: undo outranks typing);
  THE DENSITY PREVIEW IS A TRANSACTION — FvdDesigner.convertBusy()
  posts CONVERT_BUSY_MSG ("Finish or cancel the open convert
  preview first.") and refuses gizmo drags/presses, reveal-ball
  clicks, every document spinner (reverted muted), the panel ops
  buttons, seam/speed popup writes and plain Ctrl+Z from tool
  windows while editor.convertUiActive(); popups tear down
  NULL-BEFORE-HIDE with identity-guarded handlers (the UNDO KEYS
  RULE); a window whose model object can vanish self-validates (the
  seam pop-out's seamLive) and structural ops pre-hide it. New
  windows follow all of this. PLACEMENT WINDOWS (v19.19, D248 —
  STANDING): the Track Piece / bridge / seam windows and the node
  editor are built on uvd.LiveWindow — an owned LIGHT Stage
  placed just above the graph canvas (LiveWindow.place, clamped
  into Screen.getVisualBounds, never a hardcoded size);
  Confirm/Enter/X keep (the focused box commits first),
  Cancel/Esc = the FULL-SCOPE PlacementSnapshot of the coaster
  restored BY OBJECT IDENTITY with direct writes (v19.20 D249 —
  every piece + trailing bridge, captured at open and after undo
  restores; never a DocState snapshot — it would undo unrelated
  graph edits; never placementEdit — the carry is not an exact
  inverse);
  the toolbar button toggles = close WITHOUT revert; index-
  resolved handlers + modelHooks/docRestoreHooks; the shared row
  factories (orientRow / lockToggle / revertBtn / Readout /
  flagRow — the mode row — / resolveRow) keep the three identical
  — build any new tool window on it; place() lays the content out
  at its pref size BEFORE show (D249 — the layout step is
  load-bearing against JavaFX's cached Labeled clip).
- REVERSING a TILTED piece (pitch/roll deltas ≠ 0) bakes the tilt
  into the new baseOrient — generation resumes FROM the tilted pose
  there, so reversal re-solves force sections against gravity
  instead of staying rigid (D100 accepted corner). SPLITTING left
  this corner in v19.1 (D224): the new piece inherits the design
  chain + the tilt as its own conflict, the track stays put to dust
  (OrientGate2 c1/e). Everything else about tilts is rigid + gated.
- ORIENTATION BOXES ARE ABSOLUTE (v19.1, D224): the Track Piece /
  bridge next-piece rows show the entry's canonical heading/
  elevation/bank (yaw + = left) — a split piece reads its real
  starting orientation, and past straight up/down the numbers flip
  the NL2 way (heading+180, mirrored elevation, bank+180) while the
  RAW deltas keep running (rings drive raw). Yaw 0 therefore means
  heading 0, not "revert to natural" (the natural orientation is
  still baseOrient; a fresh piece's boxes equal its deltas bitwise).
- The Placement rows' pitch/roll padlocks on un-bridged pieces
  (TrackPiece.uiLockPitch/Roll) are UI edit-guards only — not
  persisted, but they DO ride undo snapshots since v16.12 (D124:
  applyDocState rebuilt pieces with default-locked guards, so any
  Ctrl+Z slammed open padlocks shut; tooltip "Guards the box
  against accidental edits." since D249 — the residual OPPOSITE
  sense, flagged); a bridged-in piece shares the BRIDGE's
  persisted lock fields instead, and those read the SEAM way
  since v19.20 D249 — 🔒 = held at the typed angle, editable in
  every mode, 🔓 = free (aligned under Optimize position and
  orientation, typeable otherwise); the Track Piece window's copies
  are enabled in every mode and route through setBridgeLock. Over
  a strict-seam follower the window's padlocks are DISABLED with
  the junction-named reason ("…edited in Strict Seam n ▴…",
  D247/D248); the seam window's own padlocks: 🔒 holds the typed
  angle, 🔓 follows the seam (its tooltip says so).

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
~~real stall/rollback handling~~ REJECTED by user decision (D195,
2026-07-29): the 0.1 m/s creep floor, the transparent ghost track
and the instant POV wrap (D194) ARE the final stall model — no
rollback simulation. PHASE 3 IS COMPLETE.

**Phase 4 — the s/c overhaul & content (user-approved order, D195):**
1. STRAIGHT/CURVE OVERHAUL (the next arc): richer elements —
   planar vs 3D curves with editable beginning/ending SLOPES and
   RADII (advanced turns), FULL roll customization, end-to-start
   CLOSURE (line a piece's end up with its own start), and strong
   VIEWPORT interactivity: drag an s/c section's 3D nodes and
   NEIGHBORING s/c sections re-solve live (e.g. moving a
   straight's end continuously re-solves the previous curve's
   turn angle). The gizmo drag pattern (absolute-from-press,
   solve, rebuild) applied to element parameters.
2. TRAIN APPROXIMATION GRAPHS: felt forces at the train's BACK/
   FRONT and LEFT/RIGHT seats — deliberately NO simulation, the
   POV position = the train middle (front/back = felt evaluated
   at arc ± offset at the train's speed; seats = analytic
   lateral-offset terms off Ω — the D99 lesson: no differencing).
   Enabled/configured in the Resulting dropdown.
3. IMPORTS & CONTENT: NL2 CSV import (external-geometry
   sections); ground IMAGES; 3D MODELS (image-textured, .glb —
   dependency decision pending: JavaFX has no glTF loader); NL2
   TERRAIN; MARKERS (3D nodes joined by lines); SKYBOX in the NL2
   image format (images only, no nl2env); full track
   customization (textures, track types). Save format grows
   asset-reference lines (path-vs-embed decision pending).
4. 3D NODAL TRACK INFLUENCE (post-first-release, his ranking): a
   node placed ON the track with draggable influence-extent
   neighbors; moving it deforms that window geometrically without
   moving track outside it; a roll variant too. (Geometry-space —
   the D139/D140 smoothing machinery generalized; felt re-derives
   via the κ identity. NOT the dynamics-space Influence feature —
   naming will matter.)
Interleaved throughout: his running laundry list of minor updates
(the session feedback rounds).

**Small items, anytime:** spine box in the rail mesh (copy
`buildTieMesh`), Euler display toggle.
